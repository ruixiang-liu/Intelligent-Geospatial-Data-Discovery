package edu.psu.giscience.graphbuilding.postprocessing2neo4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.neo4j.driver.*;
import org.neo4j.driver.Record;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.neo4j.driver.Values.parameters;

/**
 * Run this AFTER Stage A and BEFORE Stage B.
 *
 * It embeds:
 *   Dataset:       title, name, notes
 *   Organization:  title, name, description
 *   Keyword:       name
 *   PendingTopic:  canonical
 *   Format:        canonical
 *   License:       license_id, license_title, license_url
 *   Source:        id, url
 *
 * It writes:
 *   n.embedding : List<Double>
 *   n.embedding_model : String
 *   n.embedding_at : ISO-8601 String
 *   n.embedding_status : DONE|SKIPPED|FAILED|IN_PROGRESS
 *   n.embedding_error : (optional) String
 */
public class StageAEmbedAllEntities {

    public static void main(String[] args) throws Exception {

        // ============ CONFIG (edit here) ============
        String OPENAI_API_KEY = ""; // DO NOT commit real keys
        String EMBEDDING_MODEL = "text-embedding-3-large"; // you can change

        String NEO4J_URI = "";
        String NEO4J_USER = "neo4j";
        String NEO4J_PASS = "";
        String NEO4J_DB = "neo4j";

        int FETCH_BATCH_SIZE = 200;          // nodes reserved per DB fetch
        int EMBED_API_BATCH_SIZE = 96;       // texts per OpenAI /v1/embeddings call
        int WORKER_CONCURRENCY = 10;         // 16-core: 8~12 is good
        int MAX_IN_FLIGHT = 24;              // cap in-flight worker tasks (≈2x concurrency)

        int MAX_TEXT_CHARS = 6000;           // avoid super long notes/description
        boolean RESET_STUCK_IN_PROGRESS = true; // reset IN_PROGRESS to null before starting

        int LOG_EVERY = 2000;                // progress print frequency (per label)
        // ===========================================

        if (OPENAI_API_KEY == null || OPENAI_API_KEY.isBlank() || OPENAI_API_KEY.startsWith("sk-REPLACE")) {
            throw new IllegalArgumentException("Please set OPENAI_API_KEY in main().");
        }

        OpenAIEmbeddingsClient embedClient = new OpenAIEmbeddingsClient(OPENAI_API_KEY, EMBEDDING_MODEL);

        try (Neo4jDao dao = new Neo4jDao(NEO4J_URI, NEO4J_USER, NEO4J_PASS, NEO4J_DB)) {

            dao.ensureEmbeddingPropsIndexesOptional();

            if (RESET_STUCK_IN_PROGRESS) {
                dao.resetAllInProgress();
            }

            // Jobs (order matters only for your preference)
            List<EmbeddingJob> jobs = List.of(
                    EmbeddingJob.dataset(),
                    EmbeddingJob.organization(),
                    EmbeddingJob.keyword(),
                    EmbeddingJob.pendingTopic(),
                    EmbeddingJob.format(),
                    EmbeddingJob.license(),
                    EmbeddingJob.source()
            );

            // Pre-compute overall total remaining (embedding IS NULL)
            long overallTotal = 0;
            Map<String, Long> initialRemainingByLabel = new LinkedHashMap<>();
            for (EmbeddingJob job : jobs) {
                long rem = dao.countRemainingToEmbed(job.label);
                initialRemainingByLabel.put(job.label, rem);
                overallTotal += rem;
            }

            System.out.println("=== EMBEDDING PLAN (remaining without embedding) ===");
            for (var e : initialRemainingByLabel.entrySet()) {
                System.out.printf(Locale.ROOT, "  %s: %d%n", e.getKey(), e.getValue());
            }
            System.out.println("  OVERALL: " + overallTotal);

            Progress prog = new Progress(LOG_EVERY);
            prog.setOverallTotal(overallTotal);

            for (EmbeddingJob job : jobs) {
                long labelTotal = initialRemainingByLabel.getOrDefault(job.label, 0L);
                System.out.println("\n=== Embedding label: " + job.label + " (to_do=" + labelTotal + ") ===");

                if (labelTotal <= 0) {
                    prog.registerLabel(job.label, 0);
                    prog.printLabelSummary(job.label, dao);
                    continue;
                }

                prog.registerLabel(job.label, labelTotal);

                runOneJob(
                        job,
                        dao,
                        embedClient,
                        prog,
                        FETCH_BATCH_SIZE,
                        EMBED_API_BATCH_SIZE,
                        WORKER_CONCURRENCY,
                        MAX_IN_FLIGHT,
                        MAX_TEXT_CHARS
                );

                prog.printLabelSummary(job.label, dao);
            }

            System.out.println("\nALL EMBEDDINGS DONE.");
        }
    }

    // =========================
    // Orchestration per label
    // =========================
    private static void runOneJob(
            EmbeddingJob job,
            Neo4jDao dao,
            OpenAIEmbeddingsClient embedClient,
            Progress prog,
            int fetchBatchSize,
            int embedApiBatchSize,
            int concurrency,
            int maxInFlight,
            int maxTextChars
    ) throws Exception {

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CompletionService<JobResult> ecs = new ExecutorCompletionService<>(pool);
        Semaphore inFlight = new Semaphore(Math.max(1, maxInFlight));

        long submitted = 0;
        long completed = 0;

        try {
            while (true) {
                // Reserve nodes atomically (mark IN_PROGRESS) to avoid duplicates.
                List<NodeText> reserved = dao.reserveBatch(job, fetchBatchSize);
                if (reserved.isEmpty()) break;

                // Split into smaller API batches
                List<List<NodeText>> chunks = chunk(reserved, embedApiBatchSize);

                for (List<NodeText> chunk : chunks) {
                    inFlight.acquire();
                    ecs.submit(() -> {
                        try {
                            return processChunk(job, dao, embedClient, chunk, maxTextChars);
                        } finally {
                            inFlight.release();
                        }
                    });
                    submitted++;

                    while (submitted - completed >= maxInFlight) {
                        JobResult r = ecs.take().get();
                        completed++;
                        prog.onUpdate(job.label, r);
                    }
                }
            }

            while (completed < submitted) {
                JobResult r = ecs.take().get();
                completed++;
                prog.onUpdate(job.label, r);
            }

        } finally {
            pool.shutdown();
            pool.awaitTermination(60, TimeUnit.MINUTES);
        }
    }

    private static JobResult processChunk(
            EmbeddingJob job,
            Neo4jDao dao,
            OpenAIEmbeddingsClient embedClient,
            List<NodeText> chunk,
            int maxTextChars
    ) {
        String now = Instant.now().toString();

        // Build texts; if empty -> SKIP
        List<NodeText> toEmbed = new ArrayList<>();
        List<Long> toSkipIds = new ArrayList<>();

        for (NodeText nt : chunk) {
            String text = job.buildEmbeddingText(nt, maxTextChars);
            if (text == null || text.isBlank()) {
                toSkipIds.add(nt.nodeId);
            } else {
                toEmbed.add(new NodeText(nt.nodeId, text, nt.rawFields));
            }
        }

        JobResult out = new JobResult();
        out.total = chunk.size();
        out.skipped = toSkipIds.size();

        if (!toSkipIds.isEmpty()) {
            dao.markSkipped(toSkipIds, now);
        }

        if (toEmbed.isEmpty()) {
            return out;
        }

        // Call embeddings API
        List<String> inputs = toEmbed.stream().map(x -> x.text).toList();

        try {
            List<List<Double>> vecs = embedClient.embed(inputs);

            // Map back by index order
            List<EmbeddingRow> rows = new ArrayList<>(toEmbed.size());
            for (int i = 0; i < toEmbed.size(); i++) {
                rows.add(new EmbeddingRow(toEmbed.get(i).nodeId, vecs.get(i)));
            }

            dao.writeEmbeddings(rows, embedClient.model, now);
            out.done = rows.size();
            return out;

        } catch (Exception e) {
            String err = truncate(("Embedding API failed: " + e.getMessage()), 1200);
            List<Long> failedIds = toEmbed.stream().map(x -> x.nodeId).toList();
            dao.markFailed(failedIds, now, err);
            out.failed = failedIds.size();
            out.error = err;
            return out;
        }
    }

    // =========================
    // Data structures
    // =========================
    static class NodeText {
        final long nodeId;
        final String text;                 // may be null until built
        final Map<String, Object> rawFields;

        NodeText(long nodeId, String text, Map<String, Object> rawFields) {
            this.nodeId = nodeId;
            this.text = text;
            this.rawFields = rawFields == null ? Map.of() : rawFields;
        }
    }

    static class EmbeddingRow {
        final long nodeId;
        final List<Double> embedding;

        EmbeddingRow(long nodeId, List<Double> embedding) {
            this.nodeId = nodeId;
            this.embedding = embedding;
        }
    }

    static class JobResult {
        long total = 0;   // total nodes in this chunk
        long done = 0;
        long skipped = 0;
        long failed = 0;
        String error = null;
    }

    // =========================
    // Progress logging (per label + overall)
    // =========================
    static class Progress {
        final int logEvery;

        // overall
        volatile long overallTotal = 0;
        final long overallStartMs = System.currentTimeMillis();
        final AtomicLong overallCompleted = new AtomicLong(0);
        final AtomicLong overallDone = new AtomicLong(0);
        final AtomicLong overallSkipped = new AtomicLong(0);
        final AtomicLong overallFailed = new AtomicLong(0);

        // per label
        final ConcurrentHashMap<String, Long> labelTotal = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, Long> labelStartMs = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, AtomicLong> labelCompleted = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, AtomicLong> labelDone = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, AtomicLong> labelSkipped = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, AtomicLong> labelFailed = new ConcurrentHashMap<>();

        Progress(int logEvery) {
            this.logEvery = Math.max(1, logEvery);
        }

        void setOverallTotal(long total) {
            this.overallTotal = Math.max(0, total);
        }

        void registerLabel(String label, long totalToDo) {
            labelTotal.put(label, Math.max(0, totalToDo));
            labelStartMs.put(label, System.currentTimeMillis());
            labelCompleted.put(label, new AtomicLong(0));
            labelDone.put(label, new AtomicLong(0));
            labelSkipped.put(label, new AtomicLong(0));
            labelFailed.put(label, new AtomicLong(0));

            // Print initial line
            System.out.printf(Locale.ROOT,
                    "[EMBED] label=%s start total=%d overall_total=%d mem=%dMB%n",
                    label, totalToDo, overallTotal, usedMemMB());
        }

        void onUpdate(String label, JobResult r) {
            AtomicLong lc = labelCompleted.get(label);
            AtomicLong ld = labelDone.get(label);
            AtomicLong ls = labelSkipped.get(label);
            AtomicLong lf = labelFailed.get(label);

            if (lc == null) return;

            long labelNewCompleted = lc.addAndGet(r.total);
            ld.addAndGet(r.done);
            ls.addAndGet(r.skipped);
            lf.addAndGet(r.failed);

            long overallNewCompleted = overallCompleted.addAndGet(r.total);
            overallDone.addAndGet(r.done);
            overallSkipped.addAndGet(r.skipped);
            overallFailed.addAndGet(r.failed);

            long total = labelTotal.getOrDefault(label, 0L);

            boolean shouldLog = (labelNewCompleted % logEvery == 0) || (total > 0 && labelNewCompleted >= total);
            if (!shouldLog) return;

            long now = System.currentTimeMillis();
            long lsMs = labelStartMs.getOrDefault(label, overallStartMs);
            double labelSec = Math.max(1.0, (now - lsMs) / 1000.0);
            double overallSec = Math.max(1.0, (now - overallStartMs) / 1000.0);

            double labelRate = labelNewCompleted / labelSec;
            double overallRate = overallNewCompleted / overallSec;

            double labelPct = (total <= 0) ? 100.0 : (100.0 * labelNewCompleted / total);
            double overallPct = (overallTotal <= 0) ? 100.0 : (100.0 * overallNewCompleted / overallTotal);

            System.out.printf(Locale.ROOT,
                    "[EMBED] label=%s completed=%d/%d (%.1f%%) done=%d skipped=%d failed=%d rate=%.2f nodes/s | overall=%d/%d (%.1f%%) overall_rate=%.2f nodes/s mem=%dMB%n",
                    label,
                    labelNewCompleted, total, labelPct,
                    ld.get(), ls.get(), lf.get(),
                    labelRate,
                    overallNewCompleted, overallTotal, overallPct,
                    overallRate,
                    usedMemMB()
            );

            if (r.error != null && !r.error.isBlank()) {
                System.out.printf(Locale.ROOT, "  [EMBED][WARN] label=%s last_error=%s%n", label, truncate(r.error, 260));
            }
        }

        void printLabelSummary(String label, Neo4jDao dao) {
            long done = dao.countByStatus(label, "DONE");
            long skipped = dao.countByStatus(label, "SKIPPED");
            long failed = dao.countByStatus(label, "FAILED");
            long remaining = dao.countRemainingToEmbed(label);

            System.out.printf(Locale.ROOT,
                    "=== %s summary(DB): DONE=%d SKIPPED=%d FAILED=%d remaining_without_embedding=%d mem=%dMB ===%n",
                    label, done, skipped, failed, remaining, usedMemMB());
        }

        static long usedMemMB() {
            Runtime rt = Runtime.getRuntime();
            return (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        }
    }

    // =========================
    // Embedding jobs (per label)
    // =========================
    static class EmbeddingJob {
        final String label;
        final String reserveCypher;

        EmbeddingJob(String label, String reserveCypher) {
            this.label = label;
            this.reserveCypher = reserveCypher;
        }

        static EmbeddingJob dataset() {
            return new EmbeddingJob("Dataset", """
                    MATCH (n:Dataset)
                    WHERE n.embedding IS NULL AND coalesce(n.embedding_status,'') <> 'IN_PROGRESS'
                    WITH n LIMIT $limit
                    SET n.embedding_status='IN_PROGRESS', n.embedding_status_at=$now
                    RETURN id(n) AS nid, n.title AS title, n.name AS name, n.notes AS notes
                    """);
        }

        static EmbeddingJob organization() {
            return new EmbeddingJob("Organization", """
                    MATCH (n:Organization)
                    WHERE n.embedding IS NULL AND coalesce(n.embedding_status,'') <> 'IN_PROGRESS'
                    WITH n LIMIT $limit
                    SET n.embedding_status='IN_PROGRESS', n.embedding_status_at=$now
                    RETURN id(n) AS nid, n.title AS title, n.name AS name, n.description AS description
                    """);
        }

        static EmbeddingJob keyword() {
            return new EmbeddingJob("Keyword", """
                    MATCH (n:Keyword)
                    WHERE n.embedding IS NULL AND coalesce(n.embedding_status,'') <> 'IN_PROGRESS'
                    WITH n LIMIT $limit
                    SET n.embedding_status='IN_PROGRESS', n.embedding_status_at=$now
                    RETURN id(n) AS nid, n.name AS name
                    """);
        }

        static EmbeddingJob pendingTopic() {
            return new EmbeddingJob("PendingTopic", """
                    MATCH (n:PendingTopic)
                    WHERE n.embedding IS NULL AND coalesce(n.embedding_status,'') <> 'IN_PROGRESS'
                    WITH n LIMIT $limit
                    SET n.embedding_status='IN_PROGRESS', n.embedding_status_at=$now
                    RETURN id(n) AS nid, n.canonical AS canonical
                    """);
        }

        static EmbeddingJob format() {
            return new EmbeddingJob("Format", """
                    MATCH (n:Format)
                    WHERE n.embedding IS NULL AND coalesce(n.embedding_status,'') <> 'IN_PROGRESS'
                    WITH n LIMIT $limit
                    SET n.embedding_status='IN_PROGRESS', n.embedding_status_at=$now
                    RETURN id(n) AS nid, n.canonical AS canonical
                    """);
        }

        static EmbeddingJob license() {
            return new EmbeddingJob("License", """
                    MATCH (n:License)
                    WHERE n.embedding IS NULL AND coalesce(n.embedding_status,'') <> 'IN_PROGRESS'
                    WITH n LIMIT $limit
                    SET n.embedding_status='IN_PROGRESS', n.embedding_status_at=$now
                    RETURN id(n) AS nid, n.license_id AS license_id, n.license_title AS license_title, n.license_url AS license_url
                    """);
        }

        static EmbeddingJob source() {
            return new EmbeddingJob("Source", """
                    MATCH (n:Source)
                    WHERE n.embedding IS NULL AND coalesce(n.embedding_status,'') <> 'IN_PROGRESS'
                    WITH n LIMIT $limit
                    SET n.embedding_status='IN_PROGRESS', n.embedding_status_at=$now
                    RETURN id(n) AS nid, n.id AS source_id, n.url AS url
                    """);
        }

        /**
         * Build embedding input text according to your whitelist.
         * Handle missing fields safely.
         */
        String buildEmbeddingText(NodeText nt, int maxChars) {
            Map<String, Object> f = nt.rawFields;

            return switch (label) {
                case "Dataset" -> joinNonEmpty(maxChars,
                        s(f.get("title")),
                        s(f.get("name")),
                        s(f.get("notes"))
                );
                case "Organization" -> joinNonEmpty(maxChars,
                        s(f.get("title")),
                        s(f.get("name")),
                        s(f.get("description"))
                );
                case "Keyword" -> joinNonEmpty(maxChars, s(f.get("name")));
                case "PendingTopic" -> joinNonEmpty(maxChars, s(f.get("canonical")));
                case "Format" -> joinNonEmpty(maxChars, s(f.get("canonical")));
                case "License" -> joinNonEmpty(maxChars,
                        s(f.get("license_id")),
                        s(f.get("license_title")),
                        s(f.get("license_url"))
                );
                case "Source" -> joinNonEmpty(maxChars,
                        s(f.get("source_id")),
                        s(f.get("url"))
                );
                default -> null;
            };
        }
    }

    // =========================
    // Neo4j DAO
    // =========================
    static class Neo4jDao implements AutoCloseable {
        private final Driver driver;
        private final String database;

        Neo4jDao(String uri, String user, String pass, String database) {
            this.driver = GraphDatabase.driver(uri, AuthTokens.basic(user, pass));
            this.database = database;
        }

        void ensureEmbeddingPropsIndexesOptional() {
            // Optional helper indexes (not required)
            List<String> ddl = List.of(
                    "CREATE INDEX embedding_status_idx_ds IF NOT EXISTS FOR (n:Dataset) ON (n.embedding_status)",
                    "CREATE INDEX embedding_status_idx_org IF NOT EXISTS FOR (n:Organization) ON (n.embedding_status)",
                    "CREATE INDEX embedding_status_idx_kw IF NOT EXISTS FOR (n:Keyword) ON (n.embedding_status)",
                    "CREATE INDEX embedding_status_idx_pt IF NOT EXISTS FOR (n:PendingTopic) ON (n.embedding_status)",
                    "CREATE INDEX embedding_status_idx_fmt IF NOT EXISTS FOR (n:Format) ON (n.embedding_status)",
                    "CREATE INDEX embedding_status_idx_lic IF NOT EXISTS FOR (n:License) ON (n.embedding_status)",
                    "CREATE INDEX embedding_status_idx_src IF NOT EXISTS FOR (n:Source) ON (n.embedding_status)"
            );

            try (Session s = driver.session(SessionConfig.forDatabase(database))) {
                for (String c : ddl) {
                    try {
                        s.run(c).consume();
                    } catch (Exception ignore) {
                        // ignore for older versions/editions
                    }
                }
            }
        }

        void resetAllInProgress() {
            String q = """
                    MATCH (n)
                    WHERE n.embedding_status='IN_PROGRESS'
                    SET n.embedding_status = null
                    REMOVE n.embedding_status_at
                    """;
            try (Session s = driver.session(SessionConfig.forDatabase(database))) {
                s.executeWrite(tx -> {
                    tx.run(q).consume();
                    return null;
                });
            }
            System.out.println("[EMBED] resetAllInProgress done.");
        }

        List<NodeText> reserveBatch(EmbeddingJob job, int limit) {
            String now = Instant.now().toString();
            try (Session s = driver.session(SessionConfig.forDatabase(database))) {
                Result r = s.run(job.reserveCypher, parameters("limit", limit, "now", now));
                List<NodeText> out = new ArrayList<>();

                while (r.hasNext()) {
                    Record rec = r.next();
                    long nid = rec.get("nid").asLong();

                    Map<String, Object> fields = new HashMap<>();
                    for (String key : rec.keys()) {
                        if ("nid".equals(key)) continue;
                        Value v = rec.get(key);
                        if (!v.isNull()) fields.put(key, v.asObject());
                    }
                    out.add(new NodeText(nid, null, fields));
                }
                return out;
            }
        }

        void writeEmbeddings(List<EmbeddingRow> rows, String model, String now) {
            String q = """
                    UNWIND $rows AS row
                    MATCH (n) WHERE id(n) = row.nid
                    SET n.embedding = row.embedding,
                        n.embedding_model = $model,
                        n.embedding_at = $now,
                        n.embedding_status = 'DONE'
                    REMOVE n.embedding_error
                    """;

            List<Map<String, Object>> payload = new ArrayList<>(rows.size());
            for (EmbeddingRow r : rows) {
                Map<String, Object> m = new HashMap<>();
                m.put("nid", r.nodeId);
                m.put("embedding", r.embedding);
                payload.add(m);
            }

            try (Session s = driver.session(SessionConfig.forDatabase(database))) {
                s.executeWrite(tx -> {
                    tx.run(q, parameters("rows", payload, "model", model, "now", now)).consume();
                    return null;
                });
            }
        }

        void markSkipped(List<Long> ids, String now) {
            String q = """
                    UNWIND $ids AS nid
                    MATCH (n) WHERE id(n)=nid
                    SET n.embedding_status='SKIPPED',
                        n.embedding_at=$now
                    REMOVE n.embedding_error
                    """;
            try (Session s = driver.session(SessionConfig.forDatabase(database))) {
                s.executeWrite(tx -> {
                    tx.run(q, parameters("ids", ids, "now", now)).consume();
                    return null;
                });
            }
        }

        void markFailed(List<Long> ids, String now, String err) {
            String q = """
                    UNWIND $ids AS nid
                    MATCH (n) WHERE id(n)=nid
                    SET n.embedding_status='FAILED',
                        n.embedding_at=$now,
                        n.embedding_error=$err
                    """;
            try (Session s = driver.session(SessionConfig.forDatabase(database))) {
                s.executeWrite(tx -> {
                    tx.run(q, parameters("ids", ids, "now", now, "err", err)).consume();
                    return null;
                });
            }
        }

        long countRemainingToEmbed(String label) {
            String q = "MATCH (n:" + label + ") WHERE n.embedding IS NULL RETURN count(n) AS n";
            try (Session s = driver.session(SessionConfig.forDatabase(database))) {
                return s.run(q).single().get("n").asLong();
            } catch (Exception e) {
                return -1;
            }
        }

        long countByStatus(String label, String status) {
            String q = "MATCH (n:" + label + ") WHERE n.embedding_status=$st RETURN count(n) AS n";
            try (Session s = driver.session(SessionConfig.forDatabase(database))) {
                return s.run(q, parameters("st", status)).single().get("n").asLong();
            } catch (Exception e) {
                return -1;
            }
        }

        @Override
        public void close() {
            driver.close();
        }
    }

    // =========================
    // OpenAI embeddings via REST (/v1/embeddings)
    // =========================
    static class OpenAIEmbeddingsClient {
        final String apiKey;
        final String model;
        final HttpClient http = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();
        final ObjectMapper om = new ObjectMapper();

        OpenAIEmbeddingsClient(String apiKey, String model) {
            this.apiKey = apiKey;
            this.model = model;
        }

        List<List<Double>> embed(List<String> inputs) throws Exception {
            int maxAttempts = 6;
            long sleepMs = 800;

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    Map<String, Object> body = new HashMap<>();
                    body.put("model", model);
                    body.put("input", inputs);

                    String json = om.writeValueAsString(body);

                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create("https://api.openai.com/v1/embeddings"))
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json))
                            .build();

                    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                    int code = resp.statusCode();

                    if (code == 200) {
                        return parseEmbeddings(resp.body(), inputs.size());
                    }

                    if (code == 429 || code >= 500) {
                        throw new RuntimeException("HTTP " + code + " body=" + truncate(resp.body(), 500));
                    }

                    throw new RuntimeException("Embeddings API failed (non-retryable) HTTP " + code + " body=" + truncate(resp.body(), 900));

                } catch (Exception e) {
                    if (attempt == maxAttempts) throw e;
                    Thread.sleep(sleepMs);
                    sleepMs = Math.min((long) (sleepMs * 1.8), 12_000);
                }
            }
            throw new RuntimeException("Unreachable");
        }

        private List<List<Double>> parseEmbeddings(String json, int expected) throws Exception {
            JsonNode root = om.readTree(json);
            JsonNode data = root.path("data");
            if (!data.isArray()) throw new RuntimeException("Invalid embeddings response: missing data[]");

            List<List<Double>> out = new ArrayList<>(Collections.nCopies(expected, null));

            for (JsonNode item : data) {
                int idx = item.path("index").asInt(-1);
                JsonNode emb = item.path("embedding");
                if (idx < 0 || idx >= expected || !emb.isArray()) continue;

                List<Double> vec = new ArrayList<>(emb.size());
                for (JsonNode v : emb) vec.add(v.asDouble());
                out.set(idx, vec);
            }

            for (int i = 0; i < out.size(); i++) {
                if (out.get(i) == null) {
                    throw new RuntimeException("Embedding missing for index " + i);
                }
            }
            return out;
        }
    }

    // =========================
    // Helpers
    // =========================
    private static String s(Object o) {
        if (o == null) return null;
        String t = String.valueOf(o).trim();
        return t.isBlank() ? null : t;
    }

    private static String joinNonEmpty(int maxChars, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isBlank()) continue;
            if (!sb.isEmpty()) sb.append("\n");
            sb.append(p);
            if (sb.length() >= maxChars) break;
        }
        if (sb.isEmpty()) return null;
        String out = sb.toString();
        return out.length() <= maxChars ? out : out.substring(0, maxChars);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }

    private static <T> List<List<T>> chunk(List<T> xs, int size) {
        int n = xs.size();
        if (n == 0) return List.of();
        if (size <= 0) return List.of(xs);

        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < n; i += size) {
            out.add(xs.subList(i, Math.min(n, i + size)));
        }
        return out;
    }
}

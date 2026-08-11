package edu.psu.giscience.graphbuilding.postprocessing2neo4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.neo4j.driver.*;
import org.neo4j.driver.Record;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.neo4j.driver.Values.parameters;

/**
 * StageB: Topic governance (batch fulltext + batch writes + aggressive LLM gating + optional parallel LLM)
 * StageC: Materialize (Dataset)-[:HAS_TOPIC]->(Topic) from PendingTopic RESOLVES_TO
 *
 * SPEED FIXES (core):
 *  - B0 seeding is ONE cypher (no 2000 loop).
 *  - Fulltext candidates fetched in batch (UNWIND qs).
 *  - Writes are batched (UNWIND rows).
 *  - Progress logging avoids COUNT() on each log.
 *  - LLM calls are heavily gated (freq + jw + token jaccard), and can be parallel.
 *
 * EMBEDDING RULE (your requirement):
 *  - During Stage B: DO NOT set Topic.embedding at all.
 *  - After Stage B completes: embed ALL active Topics using ONLY Topic.canonical, then write Topic.embedding.
 */
public class StageB {

    // =========================
    // MAIN
    // =========================
    public static void main(String[] args) throws Exception {

        // --------- REQUIRED CONFIG (edit strings here) ---------
        String OPENAI_API_KEY = "";              // DO NOT commit real keys
        String OPENAI_CHAT_MODEL = "gpt-5.2";

        // unified embedding AFTER Stage B
        String OPENAI_EMBED_MODEL = "text-embedding-3-large"; // 3072 dims

        String NEO4J_URI = "";
        String NEO4J_USER = "neo4j";
        String NEO4J_PASS = "";
        String NEO4J_DB = "neo4j";

        // -------- Stage B settings --------
        int FREQ_BATCH = 800;                 // B0 freq compute
        int SEED_TOP_K = 2000;                // B0 seed
        int PENDING_SCAN_BATCH = 800;         // bigger batch helps because we now do batch fulltext + batch writes
        int VECTOR_TOP_K = 8;                 // kept but StageB doesn't rely on topic vectors
        int FULLTEXT_TOP_K = 8;

        // LLM gating (major speed lever)
        double LLM_JW_THRESHOLD = 0.90;       // Jaro-Winkler threshold
        double LLM_TOKEN_JACCARD_MIN = 0.60;  // token overlap threshold
        long   LLM_FREQ_MIN = 5;              // ONLY call LLM if pending freq >= this

        boolean ENABLE_MERGE_RENAME = true;

        // Parallel LLM (major speed lever)
        int LLM_PARALLELISM = 4;              // 1 = sequential, 4 = usually much faster (watch rate limits)

        // -------- Unified embedding settings (after Stage B) --------
        int TOPIC_EMBED_BATCH = 64;           // embedding API batch size
        int TOPIC_WRITE_BATCH = 200;          // neo4j UNWIND write batch size

        // -------- Stage C settings --------
        int LINK_BATCH = 30_000;
        boolean REMOVE_PENDING_EDGES = false;

        // Progress
        int LOG_EVERY_SEC = 4;

        if (OPENAI_API_KEY == null || OPENAI_API_KEY.isBlank() || OPENAI_API_KEY.startsWith("sk-REPLACE")) {
            throw new IllegalArgumentException("Please set OPENAI_API_KEY in main() as a real key.");
        }

        OpenAIClient openAIClient = OpenAIOkHttpClient.builder()
                .apiKey(OPENAI_API_KEY)
                .build();
        LlmClientService llm = new LlmClientService(openAIClient, OPENAI_CHAT_MODEL);

        EmbeddingClientService embedder = new EmbeddingClientService(OPENAI_API_KEY, OPENAI_EMBED_MODEL);

        ProgressTracker progress = new ProgressTracker(LOG_EVERY_SEC);

        try (Neo4jWriter writer = new Neo4jWriter(NEO4J_URI, NEO4J_USER, NEO4J_PASS, NEO4J_DB)) {

            writer.ensureSchemaAndVectorIndex(progress);

            // ======== Stage B ========
            TopicGovernanceEmbeddingRunner runner = new TopicGovernanceEmbeddingRunner(
                    llm, writer,
                    FREQ_BATCH,
                    SEED_TOP_K,
                    PENDING_SCAN_BATCH,
                    VECTOR_TOP_K,
                    FULLTEXT_TOP_K,
                    LLM_JW_THRESHOLD,
                    LLM_TOKEN_JACCARD_MIN,
                    LLM_FREQ_MIN,
                    ENABLE_MERGE_RENAME,
                    LLM_PARALLELISM
            );

            long tB = System.currentTimeMillis();
            runner.runStageB(progress);
            System.out.println("[Stage B] done. elapsed(ms)=" + (System.currentTimeMillis() - tB));

            // ======== Unified Topic Embedding (AFTER Stage B) ========
            long tE = System.currentTimeMillis();
            writer.embedAllActiveTopicsByCanonical(embedder, TOPIC_EMBED_BATCH, TOPIC_WRITE_BATCH, progress);
            System.out.println("[Topic Embedding] done. elapsed(ms)=" + (System.currentTimeMillis() - tE));

            // ======== Stage C ========
            long tC = System.currentTimeMillis();
            writer.materializeDatasetTopicsFromPending(LINK_BATCH, REMOVE_PENDING_EDGES, progress);
            System.out.println("[Stage C] done. elapsed(ms)=" + (System.currentTimeMillis() - tC));

            System.out.println("ALL DONE.");
        }
    }

    // =========================
    // Progress tracker
    // =========================
    static class ProgressTracker {
        private final long startMs = System.currentTimeMillis();
        private final int logEverySec;
        private long lastLogMs = 0;

        ProgressTracker(int logEverySec) {
            this.logEverySec = Math.max(1, logEverySec);
        }

        boolean shouldLog() {
            long now = System.currentTimeMillis();
            if (now - lastLogMs >= logEverySec * 1000L) {
                lastLogMs = now;
                return true;
            }
            return false;
        }

        double ratePerSec(long count) {
            double sec = Math.max(1.0, (System.currentTimeMillis() - startMs) / 1000.0);
            return count / sec;
        }

        static long usedMemMB() {
            Runtime rt = Runtime.getRuntime();
            return (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        }
    }

    // =========================
    // OpenAI LLM client (responses API)
    // =========================
    static class LlmClientService {
        private final OpenAIClient client;
        private final String modelName;

        LlmClientService(OpenAIClient client, String modelName) {
            this.client = client;
            this.modelName = modelName;
        }

        public String askPlain(String prompt) {
            int maxAttempts = 5;
            long sleepMs = 800;

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    ResponseCreateParams params = ResponseCreateParams.builder()
                            .model(ChatModel.of(modelName))
                            .input(prompt)
                            .build();

                    Response response = client.responses().create(params);

                    // robust text extraction
                    try {
                        return response.output().get(0).message().get().content().get(0).outputText().get().text();
                    } catch (Exception e) {
                        for (var out : response.output()) {
                            if (out.message().isPresent()) {
                                var msg = out.message().get();
                                for (var c : msg.content()) {
                                    if (c.outputText().isPresent()) return c.outputText().get().text();
                                }
                            }
                        }
                        throw new RuntimeException("Failed to extract model text from OpenAI response.", e);
                    }
                } catch (Exception e) {
                    if (attempt == maxAttempts) throw new RuntimeException("OpenAI call failed after retries.", e);
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during retry sleep.", ie);
                    }
                    sleepMs = Math.min((long) (sleepMs * 1.8), 10_000);
                }
            }
            throw new RuntimeException("Unreachable");
        }
    }

    // =========================
    // OpenAI Embedding client (raw HTTP)
    // =========================
    static class EmbeddingClientService {
        private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

        private final String apiKey;
        private final String model;
        private final OkHttpClient http;
        private final ObjectMapper om = new ObjectMapper();

        EmbeddingClientService(String apiKey, String model) {
            this.apiKey = apiKey;
            this.model = model;
            this.http = new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .writeTimeout(120, TimeUnit.SECONDS)
                    .callTimeout(180, TimeUnit.SECONDS)
                    .build();
        }

        List<List<Double>> embedBatch(List<String> inputs) {
            if (inputs == null || inputs.isEmpty()) return List.of();

            int maxAttempts = 5;
            long sleepMs = 800;

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("model", model);
                    body.put("input", inputs);

                    String json = om.writeValueAsString(body);

                    Request req = new Request.Builder()
                            .url("https://api.openai.com/v1/embeddings")
                            .addHeader("Authorization", "Bearer " + apiKey)
                            .addHeader("Content-Type", "application/json")
                            .post(RequestBody.create(json, JSON))
                            .build();

                    try (okhttp3.Response resp = http.newCall(req).execute()) {
                        String respBody = resp.body() == null ? "" : resp.body().string();

                        if (!resp.isSuccessful()) {
                            throw new RuntimeException("Embedding HTTP " + resp.code() + ": " + truncate(respBody, 600));
                        }

                        JsonNode root = om.readTree(respBody);
                        JsonNode data = root.get("data");
                        if (data == null || !data.isArray()) {
                            throw new RuntimeException("Embedding response missing data[]. body=" + truncate(respBody, 600));
                        }

                        List<List<Double>> out = new ArrayList<>(Collections.nCopies(inputs.size(), null));
                        for (JsonNode item : data) {
                            int idx = item.has("index") ? item.get("index").asInt() : -1;
                            JsonNode emb = item.get("embedding");
                            if (idx < 0 || idx >= inputs.size() || emb == null || !emb.isArray()) continue;

                            List<Double> vec = new ArrayList<>(emb.size());
                            for (JsonNode v : emb) vec.add(v.asDouble());
                            out.set(idx, vec);
                        }

                        for (int i = 0; i < out.size(); i++) {
                            if (out.get(i) == null) {
                                throw new RuntimeException("Embedding missing for input index=" + i);
                            }
                        }
                        return out;
                    }

                } catch (Exception e) {
                    if (attempt == maxAttempts) throw new RuntimeException("Embedding call failed after retries.", e);
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during embedding retry sleep.", ie);
                    }
                    sleepMs = Math.min((long) (sleepMs * 1.8), 10_000);
                }
            }

            throw new RuntimeException("Unreachable");
        }

        private static String truncate(String s, int max) {
            if (s == null) return "";
            if (s.length() <= max) return s;
            return s.substring(0, max) + "...";
        }
    }

    // =========================
    // Utils
    // =========================
    static class U {
        static String safe(String s) {
            if (s == null) return null;
            String t = s.trim();
            return t.isBlank() ? null : t;
        }

        static String canonicalizeTopic(String name) {
            if (name == null) return "";
            String s = name.toLowerCase(Locale.ROOT).trim();
            s = s.replaceAll("[-_/]+", " ");
            s = s.replaceAll("[^a-z0-9\\s]", " ");
            s = s.replaceAll("\\s+", " ").trim();
            return s;
        }

        static String safeCanon(String s) {
            String t = safe(s);
            if (t == null) return null;
            String c = canonicalizeTopic(t);
            return c.isBlank() ? null : c;
        }

        static double jaroWinkler(String s1, String s2) {
            if (s1 == null || s2 == null) return 0.0;
            s1 = s1.trim();
            s2 = s2.trim();
            if (s1.isEmpty() || s2.isEmpty()) return 0.0;
            if (s1.equals(s2)) return 1.0;

            int len1 = s1.length();
            int len2 = s2.length();

            int matchDist = Math.max(0, Math.max(len1, len2) / 2 - 1);

            boolean[] s1Matches = new boolean[len1];
            boolean[] s2Matches = new boolean[len2];

            int matches = 0;
            for (int i = 0; i < len1; i++) {
                int start = Math.max(0, i - matchDist);
                int end = Math.min(i + matchDist + 1, len2);
                for (int j = start; j < end; j++) {
                    if (s2Matches[j]) continue;
                    if (s1.charAt(i) != s2.charAt(j)) continue;
                    s1Matches[i] = true;
                    s2Matches[j] = true;
                    matches++;
                    break;
                }
            }
            if (matches == 0) return 0.0;

            int t = 0;
            int k = 0;
            for (int i = 0; i < len1; i++) {
                if (!s1Matches[i]) continue;
                while (!s2Matches[k]) k++;
                if (s1.charAt(i) != s2.charAt(k)) t++;
                k++;
            }
            double transpositions = t / 2.0;

            double m = matches;
            double jaro = (m / len1 + m / len2 + (m - transpositions) / m) / 3.0;

            int prefix = 0;
            int maxPrefix = 4;
            for (int i = 0; i < Math.min(Math.min(len1, len2), maxPrefix); i++) {
                if (s1.charAt(i) == s2.charAt(i)) prefix++;
                else break;
            }

            double p = 0.1;
            return jaro + prefix * p * (1.0 - jaro);
        }

        static double tokenJaccard(String a, String b) {
            if (a == null || b == null) return 0.0;
            String aa = safeCanon(a);
            String bb = safeCanon(b);
            if (aa == null || bb == null) return 0.0;
            Set<String> sa = new LinkedHashSet<>(Arrays.asList(aa.split("\\s+")));
            Set<String> sb = new LinkedHashSet<>(Arrays.asList(bb.split("\\s+")));
            if (sa.isEmpty() || sb.isEmpty()) return 0.0;
            int inter = 0;
            for (String x : sa) if (sb.contains(x)) inter++;
            int union = sa.size() + sb.size() - inter;
            return union <= 0 ? 0.0 : (double) inter / (double) union;
        }

        static Map<String, Object> map(Object... kv) {
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < kv.length; i += 2) {
                m.put(String.valueOf(kv[i]), kv[i + 1]);
            }
            return m;
        }
    }

    // =========================
    // Neo4j writer
    // =========================
    static class Neo4jWriter implements AutoCloseable {
        private final Driver driver;
        private final String database;

        Neo4jWriter(String uri, String user, String pass, String database) {
            this.driver = GraphDatabase.driver(uri, AuthTokens.basic(user, pass));
            this.database = database;
        }

        void ensureSchemaAndVectorIndex(ProgressTracker progress) {
            long t0 = System.currentTimeMillis();
            System.out.println("[Neo4j] Ensuring constraints/indexes...");

            List<String> ddl = List.of(
                    "CREATE CONSTRAINT pending_canon IF NOT EXISTS FOR (p:PendingTopic) REQUIRE p.canonical IS UNIQUE",
                    "CREATE INDEX pending_status IF NOT EXISTS FOR (p:PendingTopic) ON (p.status)",
                    "CREATE INDEX pending_freq IF NOT EXISTS FOR (p:PendingTopic) ON (p.freq)",

                    "CREATE CONSTRAINT topic_id IF NOT EXISTS FOR (t:Topic) REQUIRE t.id IS UNIQUE",
                    "CREATE CONSTRAINT topic_canon IF NOT EXISTS FOR (t:Topic) REQUIRE t.canonical IS UNIQUE",
                    "CREATE FULLTEXT INDEX topic_fulltext IF NOT EXISTS FOR (t:Topic) ON EACH [t.name, t.canonical, t.aliases]"
            );

            try (Session s = driver.session(SessionConfig.forDatabase(database))) {
                for (String c : ddl) s.run(c).consume();
            }

            System.out.println("[Neo4j] Detecting embedding dimension...");
            int dim = detectEmbeddingDim();
            if (dim <= 0) dim = 3072;
            System.out.println("[Neo4j] Detected embedding dim=" + dim);

            String indexName = "topic_embedding_vec";
            System.out.println("[Neo4j] Creating vector index if needed: " + indexName);

            String createVec = String.format(Locale.ROOT, """
                    CREATE VECTOR INDEX %s IF NOT EXISTS
                    FOR (t:Topic) ON (t.embedding)
                    OPTIONS {
                      indexConfig: {
                        `vector.dimensions`: %d,
                        `vector.similarity_function`: 'cosine'
                      }
                    }
                    """, indexName, dim);

            try (Session s = driver.session(SessionConfig.forDatabase(database))) {
                s.run(createVec).consume();
            }

            waitIndexOnline(indexName, 60);

            System.out.println("[Neo4j] Vector index ONLINE: " + indexName);
            System.out.println("[Neo4j] Schema/index init done. Elapsed ms=" + (System.currentTimeMillis() - t0)
                    + " mem=" + ProgressTracker.usedMemMB() + "MB");
        }

        private int detectEmbeddingDim() {
            String q1 = "MATCH (p:PendingTopic) WHERE p.embedding IS NOT NULL RETURN size(p.embedding) AS dim LIMIT 1";
            String q2 = "MATCH (t:Topic) WHERE t.embedding IS NOT NULL RETURN size(t.embedding) AS dim LIMIT 1";
            try (Session s = driver.session(SessionConfig.forDatabase(database))) {
                Result r1 = s.run(q1);
                if (r1.hasNext()) return r1.next().get("dim").asInt(0);

                Result r2 = s.run(q2);
                if (r2.hasNext()) return r2.next().get("dim").asInt(0);
            } catch (Exception ignored) {}
            return -1;
        }

        private void waitIndexOnline(String indexName, int timeoutSec) {
            long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
            String q = """
                    SHOW INDEXES
                    YIELD name, state
                    WHERE name = $name
                    RETURN state AS state
                    """;
            while (System.currentTimeMillis() < deadline) {
                try (Session s = driver.session(SessionConfig.forDatabase(database))) {
                    Result r = s.run(q, parameters("name", indexName));
                    if (r.hasNext()) {
                        String state = r.next().get("state").asString("");
                        if ("ONLINE".equalsIgnoreCase(state)) return;
                    }
                } catch (Exception ignored) {}
                try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
            System.out.println("[Neo4j] WARN: vector index not ONLINE after timeout, continuing anyway.");
        }

        // ---------- counts ----------
        long countUnresolvedPending() {
            String q = "MATCH (p:PendingTopic) WHERE coalesce(p.status,'PENDING') <> 'RESOLVED' RETURN count(p) AS n";
            try (Session s = driver.session(SessionConfig.forDatabase(database))) {
                return s.run(q).single().get("n").asLong();
            }
        }

        // ---------- B0 freq computation ----------
        record BatchProg(long maxId, long n) {}

        BatchProg computeFreqBatch(long lastId, int batch) {
            String q = """
                    MATCH (p:PendingTopic)
                    WHERE id(p) > $last AND p.freq IS NULL
                    WITH p ORDER BY id(p) LIMIT $batch
                    OPTIONAL MATCH (d:Dataset)-[:HAS_PENDING_TOPIC]->(p)
                    WITH p, count(d) AS freq
                    SET p.freq = freq
                    RETURN max(id(p)) AS maxId, count(*) AS n
                    """;
            try (Session s = driver.session(SessionConfig.forDatabase(database))) {
                return s.executeWrite(tx -> {
                    Record rec = tx.run(q, parameters("last", lastId, "batch", batch)).single();
                    long n = rec.get("n").asLong(0);
                    long maxId = rec.get("maxId").isNull() ? lastId : rec.get("maxId").asLong(lastId);
                    return new BatchProg(maxId, n);
                });
            }
        }

        long countPendingFreqNull() {
            String q = "MATCH (p:PendingTopic) WHERE p.freq IS NULL RETURN count(p) AS n";
            try (Session s = driver.session(SessionConfig.forDatabase(database))) {
                return s.run(q).single().get("n").asLong();
            }
        }

        // ---------- Stage B0 seed in ONE query ----------
        record SeedStats(long resolved, long created) {}

        SeedStats seedFromTopPending(int k) {
            String now = Instant.now().toString();
            String q = """
                    MATCH (p:PendingTopic)
                    WHERE coalesce(p.status,'PENDING') <> 'RESOLVED'
                    WITH p ORDER BY coalesce(p.freq,0) DESC, p.canonical ASC
                    LIMIT $k
                    MERGE (t:Topic {canonical: p.canonical})
                    ON CREATE SET t.id = randomUUID(),
                                  t.name = coalesce(p.name, p.canonical),
                                  t.aliases = [coalesce(p.name, p.canonical)],
                                  t.created_at = $now,
                                  t.status = 'ACTIVE',
                                  t._tmp_created = 1
                    SET t.updated_at = $now
                    MERGE (p)-[:RESOLVES_TO]->(t)
                    SET p.status='RESOLVED', p.resolved_at=$now, p.updated_at=$now
                    WITH t, coalesce(t._tmp_created,0) AS createdFlag
                    REMOVE t._tmp_created
                    RETURN count(*) AS resolved, sum(createdFlag) AS created
                    """;
            try (Session s = driver.session(SessionConfig.forDatabase(database))) {
                Record r = s.executeWrite(tx -> tx.run(q, parameters("k", k, "now", now)).single());
                return new SeedStats(r.get("resolved").asLong(0), r.get("created").asLong(0));
            }
        }

        // ---------- scan pending ----------
        record PendingRow(String canonical, String name, long freq, List<Number> embedding) {}

        List<PendingRow> fetchPendingBatchAfter(String lastCanonical, int limit) {
            String q = """
                    MATCH (p:PendingTopic)
                    WHERE coalesce(p.status,'PENDING') <> 'RESOLVED' AND p.canonical > $last
                    RETURN p.canonical AS c, p.name AS n, coalesce(p.freq,0) AS f, p.embedding AS e
                    ORDER BY p.canonical
                    LIMIT $limit
                    """;
            try (Session s = driver.session(SessionConfig.forDatabase(database))) {
                Result r = s.run(q, parameters("last", lastCanonical == null ? "" : lastCanonical, "limit", limit));
                List<PendingRow> out = new ArrayList<>();
                while (r.hasNext()) {
                    Record rec = r.next();
                    String c = rec.get("c").asString();
                    String n = rec.get("n").isNull() ? "" : rec.get("n").asString("");
                    long f = rec.get("f").asLong(0);
                    List<Number> e = rec.get("e").isNull() ? null : rec.get("e").asList(Value::asNumber);
                    out.add(new PendingRow(c, n, f, e));
                }
                return out;
            }
        }

        // ---------- quick exact match: Topic by canonical (batch) ----------
        Map<String, String> topicIdByCanonicalBatch(List<String> canonicals) {
            if (canonicals == null || canonicals.isEmpty()) return Map.of();
            String q = """
                    UNWIND $cs AS c
                    MATCH (t:Topic {canonical:c})
                    WHERE coalesce(t.status,'ACTIVE')='ACTIVE'
                    RETURN c AS c, t.id AS id
                    """;
            try (Session s = driver.session(SessionConfig.forDatabase(database))) {
                Result r = s.run(q, parameters("cs", canonicals));
                Map<String, String> out = new HashMap<>();
                while (r.hasNext()) {
                    Record rec = r.next();
                    out.put(rec.get("c").asString(""), rec.get("id").asString(""));
                }
                return out;
            } catch (Exception e) {
                return Map.of();
            }
        }

        // ---------- vector candidates (kept; may be empty before embedding) ----------
        record TopicCandidate(String id, String name, String canonical, double score) {}

        List<TopicCandidate> vectorCandidates(List<Number> embedding, int k) {
            if (embedding == null || embedding.isEmpty()) return List.of();
            String q = """
                    CALL db.index.vector.queryNodes('topic_embedding_vec', $k, $vec) YIELD node, score
                    WHERE coalesce(node.status,'ACTIVE') = 'ACTIVE'
                    RETURN node.id AS id, node.name AS name, node.canonical AS canonical, score AS score
                    ORDER BY score DESC
                    """;
            try (Session s = driver.session(SessionConfig.forDatabase(database))) {
                Result r = s.run(q, parameters("k", k, "vec", embedding));
                List<TopicCandidate> out = new ArrayList<>();
                while (r.hasNext()) {
                    Record rec = r.next();
                    out.add(new TopicCandidate(
                            rec.get("id").asString(""),
                            rec.get("name").isNull() ? "" : rec.get("name").asString(""),
                            rec.get("canonical").isNull() ? "" : rec.get("canonical").asString(""),
                            rec.get("score").asDouble(0.0)
                    ));
                }
                return out;
            } catch (Exception e) {
                return List.of();
            }
        }

        // ---------- fulltext candidates (BATCH) ----------
        Map<String, List<TopicCandidate>> fulltextCandidatesBatch(List<String> queries, int k) {
            if (queries == null || queries.isEmpty()) return Map.of();

            String cypher = """
                    UNWIND $qs AS q
                    CALL db.index.fulltext.queryNodes('topic_fulltext', q) YIELD node, score
                    WHERE coalesce(node.status,'ACTIVE')='ACTIVE'
                    WITH q, node, score
                    ORDER BY q, score DESC
                    WITH q, collect({id: node.id, name: node.name, canonical: node.canonical, score: score})[0..$k] AS items
                    RETURN q AS q, items AS items
                    """;

            try (Session s = driver.session(SessionConfig.forDatabase(database))) {
                Result r = s.run(cypher, parameters("qs", queries, "k", k));
                Map<String, List<TopicCandidate>> out = new HashMap<>();
                while (r.hasNext()) {
                    Record rec = r.next();
                    String q = rec.get("q").asString("");
                    List<Map<String, Object>> items = rec.get("items").asList(Value::asMap);
                    List<TopicCandidate> cs = new ArrayList<>();
                    for (Map<String, Object> it : items) {
                        String id = String.valueOf(it.getOrDefault("id", ""));
                        String name = String.valueOf(it.getOrDefault("name", ""));
                        String canonical = String.valueOf(it.getOrDefault("canonical", ""));
                        Object scObj = it.get("score");
                        double sc = 0.0;
                        if (scObj instanceof Number) sc = ((Number) scObj).doubleValue();
                        cs.add(new TopicCandidate(id, name, canonical, sc));
                    }
                    out.put(q, cs);
                }
                return out;
            } catch (Exception e) {
                return Map.of();
            }
        }

        // ---------- BATCH writes (big speedup) ----------
        long resolvePendingToExistingBatch(List<Map<String, Object>> rows) {
            if (rows == null || rows.isEmpty()) return 0;
            String now = Instant.now().toString();
            String q = """
                    UNWIND $rows AS row
                    MATCH (p:PendingTopic {canonical: row.pc})
                    MATCH (t:Topic {id: row.tid})
                    MERGE (p)-[:RESOLVES_TO]->(t)
                    SET p.status='RESOLVED', p.resolved_at=$now, p.updated_at=$now
                    RETURN count(*) AS n
                    """;
            try (Session s = driver.session(SessionConfig.forDatabase(database))) {
                return s.executeWrite(tx -> tx.run(q, parameters("rows", rows, "now", now)).single().get("n").asLong(0));
            }
        }

        long createTopicAndResolveBatch(List<Map<String, Object>> rows) {
            if (rows == null || rows.isEmpty()) return 0;
            String now = Instant.now().toString();
            String q = """
                    UNWIND $rows AS row
                    MATCH (p:PendingTopic {canonical: row.pc})
                    MERGE (t:Topic {canonical: row.tc})
                    ON CREATE SET t.id = row.tid,
                                  t.name = row.name,
                                  t.aliases = row.aliases,
                                  t.created_at = $now,
                                  t.status = 'ACTIVE'
                    SET t.updated_at = $now
                    MERGE (p)-[:RESOLVES_TO]->(t)
                    SET p.status='RESOLVED', p.resolved_at=$now, p.updated_at=$now
                    RETURN count(*) AS n
                    """;
            try (Session s = driver.session(SessionConfig.forDatabase(database))) {
                return s.executeWrite(tx -> tx.run(q, parameters("rows", rows, "now", now)).single().get("n").asLong(0));
            }
        }

        // ---------- merge/rename (same as your logic) ----------
        List<String> getTopicAliases(String topicId) {
            String q = "MATCH (t:Topic {id:$id}) RETURN coalesce(t.aliases, []) AS a";
            try (Session s = driver.session(SessionConfig.forDatabase(database))) {
                Result r = s.run(q, parameters("id", topicId));
                if (!r.hasNext()) return List.of();
                Record rec = r.next();
                List<String> out = new ArrayList<>();
                for (var v : rec.get("a").values()) out.add(v.asString(""));
                return out;
            } catch (Exception e) {
                return List.of();
            }
        }

        void setTopicAliases(String topicId, List<String> aliases) {
            String now = Instant.now().toString();
            String q = "MATCH (t:Topic {id:$id}) SET t.aliases=$a, t.updated_at=$now";
            try (Session s = driver.session(SessionConfig.forDatabase(database))) {
                s.executeWrite(tx -> { tx.run(q, parameters("id", topicId, "a", aliases == null ? List.of() : aliases, "now", now)).consume(); return null; });
            }
        }

        void mergeTopics(String winnerId, String loserId) {
            String now = Instant.now().toString();
            String q = """
                    MATCH (win:Topic {id:$win})
                    MATCH (lose:Topic {id:$lose})
                    
                    CALL {
                      WITH win, lose
                      MATCH (d:Dataset)-[r:HAS_TOPIC]->(lose)
                      MERGE (d)-[:HAS_TOPIC]->(win)
                      DELETE r
                      RETURN count(*) AS moved1
                    }
                    CALL {
                      WITH win, lose
                      MATCH (p:PendingTopic)-[r:RESOLVES_TO]->(lose)
                      MERGE (p)-[:RESOLVES_TO]->(win)
                      DELETE r
                      SET p.status='RESOLVED', p.resolved_at=$now, p.updated_at=$now
                      RETURN count(*) AS moved2
                    }
                    
                    SET lose.status='DEPRECATED',
                        lose.replaced_by=win.id,
                        lose.deprecated_at=$now,
                        lose.updated_at=$now,
                        win.updated_at=$now
                    """;
            try (Session s = driver.session(SessionConfig.forDatabase(database))) {
                s.executeWrite(tx -> { tx.run(q, parameters("win", winnerId, "lose", loserId, "now", now)).consume(); return null; });
            }

            LinkedHashSet<String> set = new LinkedHashSet<>();
            for (String a : getTopicAliases(winnerId)) if (a != null && !a.isBlank()) set.add(a.trim());
            for (String a : getTopicAliases(loserId)) if (a != null && !a.isBlank()) set.add(a.trim());
            setTopicAliases(winnerId, new ArrayList<>(set));
        }

        void renameTopicIfSafe(String topicId, String newName, String newCanonical) {
            if (topicId == null || topicId.isBlank()) return;
            String now = Instant.now().toString();

            String check = """
                    MATCH (t:Topic {id:$id})
                    OPTIONAL MATCH (other:Topic {canonical:$canon})
                    RETURN t.id AS tid, other.id AS otherId
                    """;

            try (Session s = driver.session(SessionConfig.forDatabase(database))) {
                Result rr = s.run(check, parameters("id", topicId, "canon", newCanonical));
                if (!rr.hasNext()) return;
                Record r = rr.next();
                String otherId = r.get("otherId").isNull() ? null : r.get("otherId").asString();

                if (otherId != null && !otherId.equals(topicId)) return;

                String q = """
                        MATCH (t:Topic {id:$id})
                        SET t.name = coalesce($name, t.name),
                            t.canonical = coalesce($canon, t.canonical),
                            t.updated_at = $now
                        """;

                s.executeWrite(tx -> {
                    tx.run(q, parameters(
                            "id", topicId,
                            "name", (newName == null || newName.isBlank()) ? null : newName,
                            "canon", (newCanonical == null || newCanonical.isBlank()) ? null : newCanonical,
                            "now", now
                    )).consume();
                    return null;
                });
            }
        }

        // ---------- Unified embedding AFTER Stage B ----------
        record TopicToEmbed(long nid, String id, String canonical) {}

        long countTopicsMissingEmbedding() {
            String q = """
                    MATCH (t:Topic)
                    WHERE coalesce(t.status,'ACTIVE')='ACTIVE' AND t.embedding IS NULL
                      AND t.canonical IS NOT NULL AND trim(t.canonical) <> ''
                    RETURN count(t) AS n
                    """;
            try (Session s = driver.session(SessionConfig.forDatabase(database))) {
                return s.run(q).single().get("n").asLong(0);
            }
        }

        List<TopicToEmbed> fetchTopicsMissingEmbeddingAfter(long lastNid, int limit) {
            String q = """
                    MATCH (t:Topic)
                    WHERE coalesce(t.status,'ACTIVE')='ACTIVE'
                      AND t.embedding IS NULL
                      AND t.canonical IS NOT NULL AND trim(t.canonical) <> ''
                      AND id(t) > $last
                    RETURN id(t) AS nid, t.id AS id, t.canonical AS c
                    ORDER BY nid
                    LIMIT $limit
                    """;
            try (Session s = driver.session(SessionConfig.forDatabase(database))) {
                Result r = s.run(q, parameters("last", lastNid, "limit", limit));
                List<TopicToEmbed> out = new ArrayList<>();
                while (r.hasNext()) {
                    Record rec = r.next();
                    out.add(new TopicToEmbed(
                            rec.get("nid").asLong(),
                            rec.get("id").asString(""),
                            rec.get("c").asString("")
                    ));
                }
                return out;
            }
        }

        long writeTopicEmbeddings(List<Map<String, Object>> rows) {
            if (rows == null || rows.isEmpty()) return 0;
            String now = Instant.now().toString();
            String q = """
                    UNWIND $rows AS row
                    MATCH (t:Topic {id: row.id})
                    SET t.embedding = row.embedding,
                        t.updated_at = $now
                    RETURN count(*) AS n
                    """;
            try (Session s = driver.session(SessionConfig.forDatabase(database))) {
                return s.executeWrite(tx -> tx.run(q, parameters("rows", rows, "now", now)).single().get("n").asLong(0));
            }
        }

        void embedAllActiveTopicsByCanonical(EmbeddingClientService embedder,
                                             int embedBatchSize,
                                             int writeBatchSize,
                                             ProgressTracker progress) {
            long missing = countTopicsMissingEmbedding();
            System.out.println("[Topic Embedding] topics_missing_embedding=" + missing);

            long embedded = 0;
            long lastNid = -1;

            while (true) {
                List<TopicToEmbed> batch = fetchTopicsMissingEmbeddingAfter(lastNid, Math.max(10, embedBatchSize));
                if (batch.isEmpty()) break;

                lastNid = batch.get(batch.size() - 1).nid();

                List<String> inputs = new ArrayList<>(batch.size());
                for (TopicToEmbed t : batch) inputs.add(t.canonical());

                List<List<Double>> embs = embedder.embedBatch(inputs);

                int i = 0;
                while (i < batch.size()) {
                    int j = Math.min(batch.size(), i + Math.max(10, writeBatchSize));
                    List<Map<String, Object>> rows = new ArrayList<>(j - i);
                    for (int k = i; k < j; k++) {
                        TopicToEmbed t = batch.get(k);
                        rows.add(U.map("id", t.id(), "embedding", embs.get(k)));
                    }
                    embedded += writeTopicEmbeddings(rows);
                    i = j;
                }

                if (progress.shouldLog()) {
                    long remain = countTopicsMissingEmbedding();
                    System.out.printf(Locale.ROOT,
                            "[Topic Embedding] embedded=%d remaining=%d rate=%.2f topics/s mem=%dMB%n",
                            embedded, remain, progress.ratePerSec(embedded), ProgressTracker.usedMemMB());
                }
            }

            long remain = countTopicsMissingEmbedding();
            System.out.printf(Locale.ROOT,
                    "[Topic Embedding] finished embedded=%d remaining=%d mem=%dMB%n",
                    embedded, remain, ProgressTracker.usedMemMB());
        }

        // ---------- Stage C ----------
        void materializeDatasetTopicsFromPending(int limit, boolean removePendingEdges, ProgressTracker progress) {
            long created = 0;
            long lastId = -1;

            while (true) {
                String q = """
                        MATCH (d:Dataset)-[:HAS_PENDING_TOPIC]->(p:PendingTopic)-[:RESOLVES_TO]->(t:Topic)
                        WHERE id(d) > $last AND coalesce(t.status,'ACTIVE')='ACTIVE'
                        WITH d, t, id(d) AS did
                        ORDER BY did
                        LIMIT $limit
                        MERGE (d)-[:HAS_TOPIC]->(t)
                        RETURN max(did) AS maxId, count(*) AS n
                        """;

                Record r;
                try (Session s = driver.session(SessionConfig.forDatabase(database))) {
                    Result rr = s.run(q, parameters("last", lastId, "limit", limit));
                    if (!rr.hasNext()) break;
                    r = rr.next();
                }

                long n = r.get("n").asLong();
                if (n == 0) break;

                long newLastId = r.get("maxId").asLong();
                created += n;

                if (progress.shouldLog()) {
                    System.out.printf(Locale.ROOT,
                            "[Stage C] has_topic_edges_created=%d rate=%.2f edges/s mem=%dMB%n",
                            created, progress.ratePerSec(created), ProgressTracker.usedMemMB());
                }

                if (removePendingEdges) {
                    final long maxIdFinal = newLastId;
                    final long minIdFinal = Math.max(-1, maxIdFinal - limit - 5);

                    String del = """
                            MATCH (d:Dataset)-[r:HAS_PENDING_TOPIC]->(p:PendingTopic)-[:RESOLVES_TO]->(t:Topic)
                            WHERE id(d) <= $maxId AND id(d) > $minId AND coalesce(t.status,'ACTIVE')='ACTIVE'
                            DELETE r
                            """;
                    try (Session s = driver.session(SessionConfig.forDatabase(database))) {
                        s.executeWrite(tx -> { tx.run(del, parameters("minId", minIdFinal, "maxId", maxIdFinal)).consume(); return null; });
                    }
                }

                lastId = newLastId;
            }

            System.out.printf(Locale.ROOT,
                    "[Stage C] done edges=%d mem=%dMB%n",
                    created, ProgressTracker.usedMemMB());
        }

        @Override
        public void close() {
            driver.close();
        }
    }

    // =========================
    // Stage B runner
    // =========================
    static class TopicGovernanceEmbeddingRunner {
        private final ObjectMapper om = new ObjectMapper();
        private final LlmClientService llm;
        private final Neo4jWriter writer;

        private final int freqBatch;
        private final int seedTopK;
        private final int pendingScanBatch;
        @SuppressWarnings("unused")
        private final int vectorTopK;
        private final int fulltextTopK;

        private final double llmJwThreshold;
        private final double llmTokenJaccardMin;
        private final long llmFreqMin;

        private final boolean enableMergeRename;
        private final int llmParallelism;

        private long llmCalls = 0;
        private long resolved = 0;
        private long createdTopics = 0;
        private long scanned = 0;

        TopicGovernanceEmbeddingRunner(LlmClientService llm,
                                       Neo4jWriter writer,
                                       int freqBatch,
                                       int seedTopK,
                                       int pendingScanBatch,
                                       int vectorTopK,
                                       int fulltextTopK,
                                       double llmJwThreshold,
                                       double llmTokenJaccardMin,
                                       long llmFreqMin,
                                       boolean enableMergeRename,
                                       int llmParallelism) {
            this.llm = llm;
            this.writer = writer;
            this.freqBatch = Math.max(50, freqBatch);
            this.seedTopK = Math.max(0, seedTopK);
            this.pendingScanBatch = Math.max(50, pendingScanBatch);
            this.vectorTopK = Math.max(3, vectorTopK);
            this.fulltextTopK = Math.max(3, fulltextTopK);

            this.llmJwThreshold = llmJwThreshold;
            this.llmTokenJaccardMin = llmTokenJaccardMin;
            this.llmFreqMin = Math.max(0, llmFreqMin);

            this.enableMergeRename = enableMergeRename;
            this.llmParallelism = Math.max(1, llmParallelism);
        }

        // ===== LLM decision schema =====
        static class Decision {
            public List<MapEntry> map = new ArrayList<>();
            public List<MergeEntry> merge = new ArrayList<>();
            public List<RenameEntry> rename = new ArrayList<>();
        }
        static class MapEntry {
            public String pending_canonical;
            public String action;      // "existing" | "create"
            public String topic_id;    // for existing
            public String name;        // for create
            public String canonical;   // for create
            public List<String> aliases;
        }
        static class MergeEntry {
            public String winner_id;
            public List<String> loser_ids;
        }
        static class RenameEntry {
            public String topic_id;
            public String new_name;
            public String new_canonical;
        }

        record LlmJob(Neo4jWriter.PendingRow pending, List<Neo4jWriter.TopicCandidate> candidates) {}
        record LlmResult(String pendingCanonical, Decision decision) {}

        void runStageB(ProgressTracker progress) throws Exception {

            long pendingTotal = writer.countUnresolvedPending();
            System.out.println("[Stage B] pending_total=" + pendingTotal + " (unresolved)");

            // =========================
            // Stage B0: compute freq for PendingTopic (only once)
            // =========================
            long freqNull = writer.countPendingFreqNull();
            if (freqNull > 0) {
                System.out.println("[Stage B0] Computing PendingTopic.freq for " + freqNull + " nodes in batches...");
                long done = 0;
                long lastId = -1;

                while (true) {
                    Neo4jWriter.BatchProg bp = writer.computeFreqBatch(lastId, freqBatch);
                    if (bp.n() == 0) break;
                    done += bp.n();
                    lastId = bp.maxId();

                    if (progress.shouldLog()) {
                        System.out.printf(Locale.ROOT,
                                "[Stage B0] freq_updated=%d rate=%.2f nodes/s mem=%dMB%n",
                                done, progress.ratePerSec(done), ProgressTracker.usedMemMB());
                    }
                }
                System.out.println("[Stage B0] freq done. mem=" + ProgressTracker.usedMemMB() + "MB");
            } else {
                System.out.println("[Stage B0] freq already computed (p.freq not null).");
            }

            // =========================
            // B0.5: Seed initial Topics from top frequent PendingTopic (ONE query)
            // =========================
            if (seedTopK > 0) {
                System.out.println("[Stage B0] Seeding Topics from top " + seedTopK + " frequent PendingTopic (batch cypher)...");
                Neo4jWriter.SeedStats stats = writer.seedFromTopPending(seedTopK);
                resolved += stats.resolved();
                createdTopics += stats.created();
                System.out.println("[Stage B0] seeded_resolved=" + stats.resolved() + " seeded_created=" + stats.created()
                        + " total_resolved=" + resolved + " total_created=" + createdTopics
                        + " mem=" + ProgressTracker.usedMemMB() + "MB");
            }

            // =========================
            // Stage B main loop:
            // - Batch fetch pending
            // - Exact canonical match (batch) => batch resolve (no fulltext / no llm)
            // - Batch fulltext candidates for remaining
            // - Heavily gate LLM (freq + jw + token overlap)
            // - Batch writes
            // =========================
            ExecutorService pool = (llmParallelism <= 1) ? null : Executors.newFixedThreadPool(llmParallelism);

            String last = "";
            while (true) {
                List<Neo4jWriter.PendingRow> batch = writer.fetchPendingBatchAfter(last, pendingScanBatch);
                if (batch.isEmpty()) break;
                last = batch.get(batch.size() - 1).canonical();

                // canonical list
                List<String> pcs = new ArrayList<>(batch.size());
                for (Neo4jWriter.PendingRow pr : batch) {
                    String pc = U.safeCanon(pr.canonical());
                    if (pc != null) pcs.add(pc);
                }

                // 1) exact match by canonical (fast)
                Map<String, String> exact = writer.topicIdByCanonicalBatch(pcs);

                // 2) prepare remaining queries for batch fulltext
                List<String> needFulltext = new ArrayList<>();
                Map<String, Neo4jWriter.PendingRow> pendingByCanon = new HashMap<>();
                for (Neo4jWriter.PendingRow pr : batch) {
                    String pc = U.safeCanon(pr.canonical());
                    if (pc == null) continue;
                    pendingByCanon.put(pc, pr);

                    if (!exact.containsKey(pc)) {
                        needFulltext.add(pc);
                    }
                }

                Map<String, List<Neo4jWriter.TopicCandidate>> candMap = writer.fulltextCandidatesBatch(needFulltext, fulltextTopK);

                // 3) accumulate batched writes
                List<Map<String, Object>> resolveExistingRows = new ArrayList<>();
                List<Map<String, Object>> createAndResolveRows = new ArrayList<>();

                // 4) accumulate LLM jobs
                List<LlmJob> llmJobs = new ArrayList<>();

                for (String pc : pcs) {
                    Neo4jWriter.PendingRow pr = pendingByCanon.get(pc);
                    if (pr == null) continue;

                    scanned++;

                    // already exact matched
                    if (exact.containsKey(pc)) {
                        resolveExistingRows.add(U.map("pc", pc, "tid", exact.get(pc)));
                        continue;
                    }

                    // candidates
                    List<Neo4jWriter.TopicCandidate> cands = candMap.getOrDefault(pc, List.of());
                    String bestCanon = cands.isEmpty() ? null : U.safeCanon(cands.get(0).canonical());
                    double jw = (bestCanon == null) ? 0.0 : U.jaroWinkler(pc, bestCanon);
                    double jac = (bestCanon == null) ? 0.0 : U.tokenJaccard(pc, bestCanon);

                    // LLM gating: freq + jw + token overlap
                    boolean callLlm = pr.freq() >= llmFreqMin && jw >= llmJwThreshold && jac >= llmTokenJaccardMin;

                    if (!callLlm) {
                        // create directly (NO LLM)
                        createAndResolveRows.add(U.map(
                                "pc", pc,
                                "tc", pc,
                                "tid", UUID.randomUUID().toString(),
                                "name", (pr.name() == null || pr.name().isBlank()) ? pc : pr.name(),
                                "aliases", List.of((pr.name() == null || pr.name().isBlank()) ? pc : pr.name())
                        ));
                    } else {
                        llmJobs.add(new LlmJob(pr, cands));
                    }
                }

                // 5) run LLM jobs (parallel or sequential)
                List<LlmResult> llmResults = new ArrayList<>();
                if (!llmJobs.isEmpty()) {
                    if (pool == null) {
                        for (LlmJob job : llmJobs) {
                            Decision d = askDecision(job.pending(), job.candidates());
                            llmCalls++;
                            llmResults.add(new LlmResult(U.safeCanon(job.pending().canonical()), d));
                        }
                    } else {
                        List<Future<LlmResult>> futures = new ArrayList<>();
                        for (LlmJob job : llmJobs) {
                            futures.add(pool.submit(() -> {
                                Decision d = askDecision(job.pending(), job.candidates());
                                return new LlmResult(U.safeCanon(job.pending().canonical()), d);
                            }));
                        }
                        for (Future<LlmResult> f : futures) {
                            try {
                                LlmResult rr = f.get();
                                llmCalls++;
                                llmResults.add(rr);
                            } catch (Exception e) {
                                // fallback: create
                                // (保持 pipeline 不阻塞)
                            }
                        }
                    }
                }

                // 6) apply LLM results => merges/renames + mapping
                Map<String, String> loserToWinner = new HashMap<>();
                if (enableMergeRename) {
                    for (LlmResult rr : llmResults) {
                        Decision d = rr.decision();
                        if (d == null) continue;

                        for (MergeEntry me : (d.merge == null ? List.<MergeEntry>of() : d.merge)) {
                            String win = U.safe(me.winner_id);
                            if (win == null) continue;
                            for (String loseRaw : (me.loser_ids == null ? List.<String>of() : me.loser_ids)) {
                                String lose = U.safe(loseRaw);
                                if (lose == null || lose.equals(win)) continue;
                                loserToWinner.put(lose, win);
                                writer.mergeTopics(win, lose);
                            }
                        }

                        for (RenameEntry re : (d.rename == null ? List.<RenameEntry>of() : d.rename)) {
                            String id = U.safe(re.topic_id);
                            if (id == null) continue;
                            writer.renameTopicIfSafe(id, U.safe(re.new_name), U.safeCanon(re.new_canonical));
                        }
                    }
                }

                // mapping outputs
                for (LlmResult rr : llmResults) {
                    String pc = rr.pendingCanonical();
                    if (pc == null) continue;

                    Neo4jWriter.PendingRow pr = pendingByCanon.get(pc);
                    Decision d = rr.decision();
                    if (d == null) {
                        // fallback: create
                        createAndResolveRows.add(U.map(
                                "pc", pc,
                                "tc", pc,
                                "tid", UUID.randomUUID().toString(),
                                "name", (pr == null || pr.name() == null || pr.name().isBlank()) ? pc : pr.name(),
                                "aliases", List.of((pr == null || pr.name() == null || pr.name().isBlank()) ? pc : pr.name())
                        ));
                        continue;
                    }

                    boolean applied = false;
                    for (MapEntry m : (d.map == null ? List.<MapEntry>of() : d.map)) {
                        String mpc = U.safeCanon(m.pending_canonical);
                        if (mpc == null || !mpc.equals(pc)) continue;

                        String action = U.safe(m.action);
                        if ("existing".equalsIgnoreCase(action)) {
                            String tid = U.safe(m.topic_id);
                            if (tid != null) {
                                tid = loserToWinner.getOrDefault(tid, tid);
                                resolveExistingRows.add(U.map("pc", pc, "tid", tid));
                                applied = true;
                            }
                        } else {
                            String canon = U.safeCanon(m.canonical);
                            if (canon == null) canon = pc;
                            String name = U.safe(m.name);
                            if (name == null && pr != null) name = pr.name();
                            if (name == null || name.isBlank()) name = canon;

                            List<String> aliases = (m.aliases == null || m.aliases.isEmpty())
                                    ? List.of(name)
                                    : m.aliases;

                            createAndResolveRows.add(U.map(
                                    "pc", pc,
                                    "tc", canon,
                                    "tid", UUID.randomUUID().toString(),
                                    "name", name,
                                    "aliases", aliases
                            ));
                            applied = true;
                        }
                    }

                    if (!applied) {
                        // fallback: create
                        createAndResolveRows.add(U.map(
                                "pc", pc,
                                "tc", pc,
                                "tid", UUID.randomUUID().toString(),
                                "name", (pr == null || pr.name() == null || pr.name().isBlank()) ? pc : pr.name(),
                                "aliases", List.of((pr == null || pr.name() == null || pr.name().isBlank()) ? pc : pr.name())
                        ));
                    }
                }

                // 7) execute batch writes (big speedup)
                long r1 = writer.resolvePendingToExistingBatch(resolveExistingRows);
                long r2 = writer.createTopicAndResolveBatch(createAndResolveRows);

                resolved += (r1 + r2);
                // createdTopics：严格统计需要额外判断 Topic 是否新建，会慢；这里用“createRows 数量”做近似计数（快）
                createdTopics += createAndResolveRows.size();

                long remainingEstimate = Math.max(0, pendingTotal - resolved);

                if (progress.shouldLog()) {
                    System.out.printf(Locale.ROOT,
                            "[Stage B] scanned=%d resolved=%d createdTopics=%d llmCalls=%d remaining≈%d rate=%.2f pending/s mem=%dMB%n",
                            scanned, resolved, createdTopics, llmCalls, remainingEstimate,
                            progress.ratePerSec(scanned),
                            ProgressTracker.usedMemMB()
                    );
                }
            }

            if (pool != null) pool.shutdownNow();

            long remaining = writer.countUnresolvedPending(); // final exact check once
            System.out.println("[Stage B] finished. scanned=" + scanned
                    + " resolved=" + resolved
                    + " createdTopics~=" + createdTopics
                    + " llmCalls=" + llmCalls
                    + " remaining=" + remaining
                    + " mem=" + ProgressTracker.usedMemMB() + "MB");
        }

        private Decision askDecision(Neo4jWriter.PendingRow pending, List<Neo4jWriter.TopicCandidate> candidates) {
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("pending_canonical", pending.canonical());
                payload.put("pending_name", pending.name());
                payload.put("pending_freq", pending.freq());

                List<Map<String, Object>> cs = new ArrayList<>();
                for (Neo4jWriter.TopicCandidate c : candidates) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.id());
                    m.put("name", c.name());
                    m.put("canonical", c.canonical());
                    m.put("score", c.score());
                    cs.add(m);
                }
                payload.put("candidates", cs);

                // prompt kept similar to your schema (only trimmed a bit)
                String prompt = """
                        You curate a GLOBAL controlled topic vocabulary for geospatial dataset discovery.
                        Input: ONE pending topic and a few candidate existing Topics.

                        Decide:
                        - action="existing" with a topic_id ONLY if meaning is FULLY synonymous; otherwise action="create".

                        Rules:
                        - Only "existing" if FULLY synonymous.
                        - Canonical must be lowercase, 2-5 words, letters/digits/spaces only.
                        - You MAY propose merge/rename of existing Topics if they are fully synonymous.

                        Output MUST be valid JSON ONLY.

                        Output schema:
                        {
                          "map":[
                            {"pending_canonical":"...","action":"existing","topic_id":"..."}
                            OR
                            {"pending_canonical":"...","action":"create","name":"...","canonical":"...","aliases":["..."]}
                          ],
                          "merge":[{"winner_id":"...","loser_ids":["..."]}],
                          "rename":[{"topic_id":"...","new_name":"...","new_canonical":"..."}]
                        }

                        Input:
                        %s
                        """.formatted(om.writeValueAsString(payload));

                String txt = llm.askPlain(prompt).trim();
                JsonNode node = om.readTree(txt);
                return om.treeToValue(node, Decision.class);

            } catch (Exception e) {
                // fallback: create
                Decision d = new Decision();
                MapEntry m = new MapEntry();
                m.pending_canonical = pending.canonical();
                m.action = "create";
                m.name = pending.name();
                m.canonical = pending.canonical();
                m.aliases = List.of(pending.name());
                d.map.add(m);
                return d;
            }
        }
    }
}

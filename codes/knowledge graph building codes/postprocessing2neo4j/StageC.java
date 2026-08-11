package edu.psu.giscience.graphbuilding.postprocessing2neo4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;

import okhttp3.*;
import org.neo4j.driver.*;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import static org.neo4j.driver.Values.parameters;

/**
 * DedupNewTopicsWithLLM (NO APOC + Progress)
 *
 * Use case:
 *  - After StageB creates many new Topic nodes, run a SECOND PASS dedupe among Topics
 *  - Rule-based grouping -> LLM confirms merges with confidence
 *  - Merge: migrate relationships -> delete duplicate Topic (DETACH DELETE) OR soft-deprecate (config)
 *  - Topic: merge aliases (pure Cypher, no APOC)
 *  - Output CSV mapping: duplicate -> canonical, for provenance
 *
 * Notes:
 *  - This does NOT use embeddings. It groups by normalized canonical/name/aliases (+ token signature).
 *  - Designed to be safe: LLM must be high confidence, otherwise skip.
 */
public class StageC {

    // ===== OpenAI =====
    static String OPENAI_API_KEY;
    static String OPENAI_BASE_URL;   // https://api.openai.com/v1
    static String LLM_MODEL;         // e.g. gpt-5.2
    static int LLM_CONCURRENCY;

    // ===== Neo4j =====
    static String NEO4J_URI;
    static String NEO4J_USER;
    static String NEO4J_PASS;
    static String NEO4J_DB;

    // ===== Scope: only "new" topics =====
    // If NEW_SINCE_ISO is null/blank -> dedupe ALL ACTIVE topics.
    // If set (ISO-8601, e.g. "2025-12-21T17:00:00Z") -> only dedupe topics with created_at >= this.
    static String NEW_SINCE_ISO;

    // ===== Dedupe tuning =====
    static double MIN_CONFIDENCE;    // e.g. 0.85
    static int MAX_GROUP_SIZE;       // chunk size to LLM, e.g. 24
    static int MAX_ATTEMPTS;         // OpenAI retry attempts

    // ===== Merge behavior =====
    // true: migrate rels then DETACH DELETE dup topic
    // false: migrate rels, then mark dup as DEPRECATED (keeps node)
    static boolean DELETE_DUPLICATE_TOPIC;

    // ===== Output =====
    static String CSV_OUTPUT_PATH;

    // ===== Progress tuning =====
    static long LOG_EVERY_MS = 5000;

    // ===== Runtime =====
    static final ObjectMapper OM = new ObjectMapper();
    static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    static OkHttpClient OK;

    static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    static final Pattern WS = Pattern.compile("\\s+");

    static class TopicCandidate {
        final String id;
        final String canonical;
        final String name;
        final String createdAt;      // ISO string
        final List<String> aliases;

        TopicCandidate(String id, String canonical, String name, String createdAt, List<String> aliases) {
            this.id = id;
            this.canonical = canonical;
            this.name = name;
            this.createdAt = createdAt;
            this.aliases = aliases == null ? List.of() : aliases;
        }
    }

    static class Op {
        final String fromId;
        final String toId;
        final double confidence;
        final String reason;
        Op(String fromId, String toId, double confidence, String reason) {
            this.fromId = fromId;
            this.toId = toId;
            this.confidence = confidence;
            this.reason = reason;
        }
    }

    static class MergeDecision {
        final List<Op> ops;
        MergeDecision(List<Op> ops) { this.ops = ops; }
    }

    static class Progress {
        final long startMs = System.currentTimeMillis();
        volatile long lastLogMs = 0;

        long candidates = 0;
        long groups = 0;
        long tasksTotal = 0;

        final AtomicLong tasksDone = new AtomicLong();
        final AtomicLong llmFail = new AtomicLong();

        final AtomicLong mergesProposed = new AtomicLong();
        final AtomicLong mergesAttempted = new AtomicLong();
        final AtomicLong mergesOk = new AtomicLong();
        final AtomicLong mergesFail = new AtomicLong();
        final AtomicLong mergesSkippedLowConf = new AtomicLong();
        final AtomicLong mergesSkippedSame = new AtomicLong();
        final AtomicLong mergesSkippedMissing = new AtomicLong();

        void log(boolean force) {
            long now = System.currentTimeMillis();
            if (!force && now - lastLogMs < LOG_EVERY_MS) return;
            lastLogMs = now;

            long done = tasksDone.get();
            double pct = tasksTotal <= 0 ? 0.0 : (100.0 * done / tasksTotal);
            double sec = Math.max(1.0, (now - startMs) / 1000.0);

            System.out.printf(Locale.ROOT,
                    "[TopicDedup] cand=%d groups=%d tasks=%d done=%d(%.1f%%) llmFail=%d " +
                            "proposed=%d attempted=%d ok=%d fail=%d skipLowConf=%d skipSame=%d skipMissing=%d elapsed=%.1fs mem=%dMB%n",
                    candidates, groups, tasksTotal, done, pct, llmFail.get(),
                    mergesProposed.get(), mergesAttempted.get(), mergesOk.get(), mergesFail.get(),
                    mergesSkippedLowConf.get(), mergesSkippedSame.get(), mergesSkippedMissing.get(),
                    sec, usedMemMB());
        }

        static long usedMemMB() {
            Runtime rt = Runtime.getRuntime();
            return (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        }
    }

    public static void main(String[] args) throws Exception {

        // ===== Fill these (DO NOT commit real keys) =====
        OPENAI_API_KEY  = "";
        OPENAI_BASE_URL = "https://api.openai.com/v1";
        LLM_MODEL       = "gpt-5.2";
        LLM_CONCURRENCY = 10;

        NEO4J_URI  = "";
        NEO4J_USER = "neo4j";
        NEO4J_PASS = "";
        NEO4J_DB   = "neo4j";

        // If you only want to dedupe "new topics" created after StageB:
        // NEW_SINCE_ISO = "2025-12-21T17:00:00Z";
        // Else set blank to dedupe all ACTIVE topics:
        NEW_SINCE_ISO = ""; // "" -> all ACTIVE

        MIN_CONFIDENCE = 0.85;
        MAX_GROUP_SIZE = 24;
        MAX_ATTEMPTS   = 6;

        // default: delete duplicates after migrating relationships
        DELETE_DUPLICATE_TOPIC = true;

        CSV_OUTPUT_PATH = "topic_second_dedupe_mapping_" + Instant.now().toString().replace(":", "-") + ".csv";

        if (OPENAI_API_KEY == null || OPENAI_API_KEY.isBlank() || OPENAI_API_KEY.startsWith("sk-REPLACE")) {
            throw new IllegalArgumentException("Set OPENAI_API_KEY in main().");
        }

        // ===== OkHttp tuned for concurrency =====
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(Math.max(64, LLM_CONCURRENCY * 8));
        dispatcher.setMaxRequestsPerHost(Math.max(32, LLM_CONCURRENCY * 8));
        ConnectionPool pool = new ConnectionPool(Math.max(32, LLM_CONCURRENCY * 2), 5, TimeUnit.MINUTES);

        OK = new OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectionPool(pool)
                .retryOnConnectionFailure(true)
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofMinutes(5))
                .writeTimeout(Duration.ofMinutes(5))
                .callTimeout(Duration.ofMinutes(6))
                .build();

        Path csvPath = Paths.get(CSV_OUTPUT_PATH).toAbsolutePath().normalize();
        if (csvPath.getParent() != null) Files.createDirectories(csvPath.getParent());

        try (BufferedWriter csv = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

            csv.write("deprecated_id,deprecated_canonical,canonical_id,canonical_canonical,confidence,reason,merged_at\n");

            Driver driver = GraphDatabase.driver(NEO4J_URI, AuthTokens.basic(NEO4J_USER, NEO4J_PASS));
            try {
                runTopicSecondDedup(driver, csv);
            } finally {
                driver.close();
            }

            System.out.println("[DONE] CSV mapping written to: " + csvPath);
        }
    }

    // =========================
    // Pipeline
    // =========================
    static void runTopicSecondDedup(Driver driver, BufferedWriter csv) throws Exception {
        Progress p = new Progress();

        System.out.println("\n========== SECOND PASS TOPIC DEDUPE ==========");
        System.out.println("Filter: status=ACTIVE" + (isBlank(NEW_SINCE_ISO) ? "" : (" AND created_at>=" + NEW_SINCE_ISO)));
        System.out.println("Delete duplicate nodes: " + DELETE_DUPLICATE_TOPIC);

        List<TopicCandidate> all = fetchTopics(driver);
        p.candidates = all.size();
        p.log(true);
        if (all.size() < 2) return;

        // caches for CSV
        Map<String, String> idToCanon = new HashMap<>(all.size() * 2);
        for (TopicCandidate c : all) idToCanon.put(c.id, c.canonical == null ? "" : c.canonical);

        List<List<TopicCandidate>> groups = buildGroups(all);
        p.groups = groups.size();
        p.log(true);
        if (groups.isEmpty()) return;

        // chunk -> tasks
        List<List<TopicCandidate>> tasks = new ArrayList<>();
        for (List<TopicCandidate> g : groups) tasks.addAll(chunkGroup(g, MAX_GROUP_SIZE));
        p.tasksTotal = tasks.size();
        p.log(true);

        ExecutorService llmPool = Executors.newFixedThreadPool(LLM_CONCURRENCY);
        CompletionService<List<MergeDecision>> ecs = new ExecutorCompletionService<>(llmPool);

        for (List<TopicCandidate> chunk : tasks) {
            ecs.submit(() -> decideMergesWithLLM(chunk));
        }

        // redirect map to keep merges consistent when many ops happen
        ConcurrentHashMap<String, String> redirect = new ConcurrentHashMap<>();

        for (int i = 0; i < tasks.size(); i++) {
            Future<List<MergeDecision>> f = ecs.take();
            List<MergeDecision> decisions;
            try {
                decisions = f.get();
            } catch (Exception e) {
                p.llmFail.incrementAndGet();
                p.tasksDone.incrementAndGet();
                p.log(false);
                continue;
            }

            for (MergeDecision d : decisions) {
                for (Op op0 : d.ops) {
                    p.mergesProposed.incrementAndGet();

                    if (op0.confidence < MIN_CONFIDENCE) {
                        p.mergesSkippedLowConf.incrementAndGet();
                        continue;
                    }

                    String from = resolveRedirect(redirect, op0.fromId);
                    String to = resolveRedirect(redirect, op0.toId);
                    if (from == null || to == null || from.isBlank() || to.isBlank()) {
                        p.mergesSkippedMissing.incrementAndGet();
                        continue;
                    }
                    if (from.equals(to)) {
                        p.mergesSkippedSame.incrementAndGet();
                        continue;
                    }

                    // apply merge
                    p.mergesAttempted.incrementAndGet();
                    boolean ok = applyMergeTopic(driver, from, to);

                    if (ok) {
                        // record redirect
                        redirect.put(from, to);
                        p.mergesOk.incrementAndGet();
                        writeCsv(csv, from, idToCanon.getOrDefault(from, ""), to, idToCanon.getOrDefault(to, ""),
                                op0.confidence, op0.reason);
                    } else {
                        p.mergesFail.incrementAndGet();
                    }

                    p.log(false);
                }
            }

            p.tasksDone.incrementAndGet();
            p.log(false);
        }

        llmPool.shutdownNow();
        p.log(true);
        System.out.println("[Summary] Topic second-pass dedupe done.");
    }

    // =========================
    // Neo4j fetch
    // =========================
    static List<TopicCandidate> fetchTopics(Driver driver) {
        String cypher = """
                MATCH (t:Topic)
                WHERE coalesce(t.status,'ACTIVE') = 'ACTIVE'
                  AND ($since = '' OR $since IS NULL OR coalesce(t.created_at,'') >= $since)
                RETURN t.id AS id,
                       coalesce(t.canonical,'') AS canonical,
                       coalesce(t.name,'') AS name,
                       coalesce(t.created_at,'') AS created_at,
                       coalesce(t.aliases, []) AS aliases
                ORDER BY id
                """;

        try (Session session = driver.session(SessionConfig.forDatabase(NEO4J_DB))) {
            return session.executeRead(tx ->
                    tx.run(cypher, parameters("since", isBlank(NEW_SINCE_ISO) ? "" : NEW_SINCE_ISO))
                            .list(rec -> {
                                String id = rec.get("id").asString("");
                                String canonical = rec.get("canonical").asString("");
                                String name = rec.get("name").asString("");
                                String createdAt = rec.get("created_at").asString("");
                                List<String> aliases = new ArrayList<>();
                                try {
                                    for (Value v : rec.get("aliases").values()) {
                                        if (!v.isNull()) {
                                            String a = v.asString("").trim();
                                            if (!a.isEmpty()) aliases.add(a);
                                        }
                                    }
                                } catch (Exception ignore) {}
                                return new TopicCandidate(id, canonical, name, createdAt, aliases);
                            })
            );
        }
    }

    // =========================
    // Grouping (Union-Find)
    // =========================
    static List<List<TopicCandidate>> buildGroups(List<TopicCandidate> all) {
        int n = all.size();
        UnionFind uf = new UnionFind(n);
        Map<String, Integer> first = new HashMap<>(n * 4);

        for (int i = 0; i < n; i++) {
            TopicCandidate c = all.get(i);
            for (String k : keysForTopic(c)) {
                if (k.isEmpty()) continue;
                Integer j = first.putIfAbsent(k, i);
                if (j != null) uf.union(i, j);
            }
        }

        Map<Integer, List<TopicCandidate>> comp = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int r = uf.find(i);
            comp.computeIfAbsent(r, x -> new ArrayList<>()).add(all.get(i));
        }

        List<List<TopicCandidate>> out = new ArrayList<>();
        for (List<TopicCandidate> g : comp.values()) if (g.size() >= 2) out.add(g);
        return out;
    }

    static List<String> keysForTopic(TopicCandidate c) {
        List<String> ks = new ArrayList<>(16);

        String canon = norm(c.canonical);
        String name = norm(c.name);

        if (!canon.isEmpty()) ks.add("canon|" + canon);
        if (!name.isEmpty()) ks.add("name|" + name);

        // aliases
        for (String a : c.aliases) {
            String ak = norm(a);
            if (!ak.isEmpty()) ks.add("alias|" + ak);
        }

        // token signature helps catch word-order variants: "land surface temperature" vs "surface land temperature"
        String sig = tokenSignature(canon);
        if (!sig.isEmpty()) ks.add("sig|" + sig);

        // short prefix bucket reduces accidental huge unions
        String base = !canon.isEmpty() ? canon : name;
        if (!base.isEmpty()) {
            ks.add("p3|" + base.substring(0, Math.min(3, base.length())));
            ks.add("p6|" + base.substring(0, Math.min(6, base.length())));
        }

        return ks;
    }

    static List<List<TopicCandidate>> chunkGroup(List<TopicCandidate> g, int maxSize) {
        if (g.size() <= maxSize) return List.of(g);

        Map<String, List<TopicCandidate>> buckets = new LinkedHashMap<>();
        for (TopicCandidate c : g) {
            String k = norm(c.canonical);
            if (k.isEmpty()) k = norm(c.name);
            String p = k.length() <= 6 ? k : k.substring(0, 6);
            buckets.computeIfAbsent(p, x -> new ArrayList<>()).add(c);
        }

        List<List<TopicCandidate>> out = new ArrayList<>();
        for (List<TopicCandidate> b : buckets.values()) {
            for (int i = 0; i < b.size(); i += maxSize) {
                out.add(b.subList(i, Math.min(b.size(), i + maxSize)));
            }
        }
        return out;
    }

    static class UnionFind {
        final int[] p;
        final int[] r;
        UnionFind(int n) { p = new int[n]; r = new int[n]; for (int i = 0; i < n; i++) p[i] = i; }
        int find(int x) { while (p[x] != x) { p[x] = p[p[x]]; x = p[x]; } return x; }
        void union(int a, int b) {
            int ra = find(a), rb = find(b);
            if (ra == rb) return;
            if (r[ra] < r[rb]) p[ra] = rb;
            else if (r[ra] > r[rb]) p[rb] = ra;
            else { p[rb] = ra; r[ra]++; }
        }
    }

    // =========================
    // LLM decide merges (Responses API)
    // =========================
    static List<MergeDecision> decideMergesWithLLM(List<TopicCandidate> group) throws Exception {

        ArrayNode candidates = OM.createArrayNode();
        for (TopicCandidate c : group) {
            ObjectNode o = OM.createObjectNode();
            o.put("id", c.id);
            o.put("canonical", c.canonical == null ? "" : c.canonical);
            o.put("name", c.name == null ? "" : c.name);
            o.put("created_at", c.createdAt == null ? "" : c.createdAt);

            ArrayNode al = o.putArray("aliases");
            for (String a : c.aliases) al.add(a);

            candidates.add(o);
        }

        ObjectNode req = OM.createObjectNode();
        req.put("model", LLM_MODEL);
        req.put("temperature", 0.0);

        ArrayNode input = req.putArray("input");

        ObjectNode sys = input.addObject();
        sys.put("role", "system");
        sys.putArray("content").addObject().put("type", "input_text").put("text", systemPrompt());

        ObjectNode user = input.addObject();
        user.put("role", "user");
        user.putArray("content").addObject().put("type", "input_text").put("text", userPrompt(candidates));

        ObjectNode text = req.putObject("text");
        text.putObject("format").put("type", "json_object");

        JsonNode resp = postResponsesWithRetry(req);
        String out = extractOutputText(resp);

        JsonNode j = OM.readTree(out);
        if (j == null || !j.isObject()) return List.of(new MergeDecision(List.of()));

        ArrayNode merge = j.has("merge") && j.get("merge").isArray() ? (ArrayNode) j.get("merge") : OM.createArrayNode();

        List<Op> ops = new ArrayList<>();
        for (JsonNode m : merge) {
            String from = m.path("from_id").asText("");
            String to = m.path("to_id").asText("");
            double conf = m.path("confidence").isNumber() ? m.path("confidence").asDouble() : 0.0;
            String reason = m.path("reason").asText("");
            if (!from.isBlank() && !to.isBlank()) ops.add(new Op(from, to, conf, reason));
        }

        return List.of(new MergeDecision(ops));
    }

    static String systemPrompt() {
        return """
You are a STRICT topic entity deduplication engine for a Neo4j knowledge graph.
You must output ONLY valid JSON.

Rules:
- Only merge when two topics are FULLY synonymous (same concept). If unsure, DO NOT merge.
- Do NOT merge broad vs specific (e.g., "climate" vs "climate change").
- Minor wording, spelling, punctuation, pluralization, and alias-like variations CAN be duplicates.
- Always choose a canonical keep-node (to_id) that is "cleaner" and more stable:
  Prefer:
  - clearer canonical phrase (lowercase, readable, not overly long),
  - older created_at if both are equally clean,
  - richer aliases is a plus.
- Ensure every from_id and to_id is one of the provided candidate ids.

Output JSON shape:
{
  "merge": [
    {"from_id":"dupId","to_id":"canonId","confidence":0.0-1.0,"reason":"short"}
  ]
}
""";
    }

    static String userPrompt(ArrayNode candidates) {
        return """
You are deduping Topics. Decide which ones are duplicates and provide merge operations.
- If no duplicates, return an empty merge list.
- You may output multiple merges, but avoid chains when possible (merge everything into one canonical node).
Return ONLY the JSON object.

Candidates:
%s
""".formatted(candidates.toString());
    }

    // =========================
    // OpenAI HTTP
    // =========================
    static JsonNode postResponsesWithRetry(JsonNode payload) throws Exception {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return postJson(OPENAI_BASE_URL + "/responses", payload);
            } catch (RuntimeException | IOException e) {
                if (attempt == MAX_ATTEMPTS) throw e;
                sleepBackoff(attempt);
            }
        }
        throw new RuntimeException("unreachable");
    }

    static JsonNode postJson(String url, JsonNode payload) throws IOException {
        RequestBody body = RequestBody.create(payload.toString(), JSON);
        Request req = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + OPENAI_API_KEY)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .post(body)
                .build();

        try (okhttp3.Response resp = OK.newCall(req).execute()) {
            String rb = resp.body() != null ? resp.body().string() : "";
            if (resp.code() < 200 || resp.code() >= 300) {
                throw new RuntimeException("OpenAI HTTP " + resp.code() + ": " + truncate(rb, 800));
            }
            return OM.readTree(rb);
        }
    }

    static String extractOutputText(JsonNode resp) {
        if (resp.hasNonNull("output_text")) return resp.get("output_text").asText();
        JsonNode out = resp.path("output");
        if (out.isArray()) {
            for (JsonNode item : out) {
                JsonNode content = item.path("content");
                if (content.isArray()) {
                    for (JsonNode c : content) {
                        if (c.hasNonNull("text")) return c.get("text").asText();
                    }
                }
            }
        }
        return resp.toString();
    }

    static void sleepBackoff(int attempt) {
        long base = 500L;
        long jitter = ThreadLocalRandom.current().nextLong(200, 900);
        long sleep = base * (1L << Math.min(6, attempt - 1)) + jitter;
        try { Thread.sleep(sleep); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    static String truncate(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }

    // =========================
    // Apply merge in Neo4j (NO APOC)
    // =========================
    static boolean applyMergeTopic(Driver driver, String fromId, String toId) {
        try (Session session = driver.session(SessionConfig.forDatabase(NEO4J_DB))) {
            return session.executeWrite(tx -> {

                // ensure both exist (ACTIVE)
                long cnt = tx.run("""
                        MATCH (a:Topic {id:$a}), (b:Topic {id:$b})
                        WHERE coalesce(a.status,'ACTIVE')='ACTIVE' AND coalesce(b.status,'ACTIVE')='ACTIVE'
                        RETURN count(*) AS c
                        """, parameters("a", fromId, "b", toId)).single().get("c").asLong();
                if (cnt <= 0) return false;

                // merge aliases into target
                tx.run("""
                        MATCH (f:Topic {id:$f}), (t:Topic {id:$t})
                        WITH f, t,
                             coalesce(t.aliases, []) AS tal,
                             coalesce(f.aliases, []) AS fal,
                             coalesce(f.name, '') AS fname,
                             coalesce(f.canonical, '') AS fcanon
                        WITH t, tal + fal
                              + (CASE WHEN fname <> '' THEN [fname] ELSE [] END)
                              + (CASE WHEN fcanon <> '' THEN [fcanon] ELSE [] END) AS raw
                        UNWIND raw AS x
                        WITH t, CASE WHEN x IS NULL THEN NULL ELSE trim(toString(x)) END AS x2
                        WITH t, collect(DISTINCT x2) AS merged
                        WITH t, [x IN merged WHERE x IS NOT NULL AND x <> ''] AS merged2
                        SET t.aliases = merged2
                        """, parameters("f", fromId, "t", toId)).consume();

                // collect relationship types around "from"
                List<String> relTypes = tx.run(
                        "MATCH (f:Topic {id:$id})-[r]-() RETURN DISTINCT type(r) AS t",
                        parameters("id", fromId)
                ).list(r -> r.get("t").asString());

                // migrate outgoing/incoming rels; MERGE to avoid duplicates
                for (String rt : relTypes) {
                    String outCypher = """
                            MATCH (f:Topic {id:$from})-[r:`%s`]->(x)
                            MATCH (t:Topic {id:$to})
                            WHERE coalesce(x.id,'') <> $to
                            MERGE (t)-[r2:`%s`]->(x)
                            SET r2 += properties(r)
                            DELETE r
                            """.formatted(rt, rt);
                    tx.run(outCypher, parameters("from", fromId, "to", toId)).consume();

                    String inCypher = """
                            MATCH (x)-[r:`%s`]->(f:Topic {id:$from})
                            MATCH (t:Topic {id:$to})
                            WHERE coalesce(x.id,'') <> $to
                            MERGE (x)-[r2:`%s`]->(t)
                            SET r2 += properties(r)
                            DELETE r
                            """.formatted(rt, rt);
                    tx.run(inCypher, parameters("from", fromId, "to", toId)).consume();
                }

                if (DELETE_DUPLICATE_TOPIC) {
                    tx.run("MATCH (f:Topic {id:$id}) DETACH DELETE f", parameters("id", fromId)).consume();
                } else {
                    tx.run("""
                            MATCH (f:Topic {id:$id})
                            SET f.status='DEPRECATED',
                                f.replaced_by=$to,
                                f.deprecated_at=$now,
                                f.updated_at=$now
                            """, parameters("id", fromId, "to", toId, "now", Instant.now().toString())).consume();
                }

                return true;
            });
        } catch (Exception e) {
            System.err.println("[MergeFail] Topic " + fromId + " -> " + toId + " :: " + e.getMessage());
            return false;
        }
    }

    // =========================
    // CSV
    // =========================
    static void writeCsv(BufferedWriter csv,
                         String depId, String depCanon,
                         String canonId, String canonCanon,
                         double conf, String reason) {
        try {
            String ts = Instant.now().toString();
            csv.write(escape(depId) + "," + escape(depCanon) + ","
                    + escape(canonId) + "," + escape(canonCanon) + ","
                    + conf + "," + escape(reason) + "," + escape(ts) + "\n");
            csv.flush();
        } catch (IOException e) {
            System.err.println("[CSVFail] " + e.getMessage());
        }
    }

    static String escape(String s) {
        if (s == null) return "";
        String x = s.replace("\"", "\"\"");
        if (x.contains(",") || x.contains("\n") || x.contains("\r")) return "\"" + x + "\"";
        return x;
    }

    // =========================
    // Normalization helpers
    // =========================
    static String norm(String s) {
        if (s == null) return "";
        String x = s.trim().toLowerCase(Locale.ROOT);
        x = NON_ALNUM.matcher(x).replaceAll(" ");
        x = WS.matcher(x).replaceAll(" ").trim();
        return x;
    }

    static String tokenSignature(String normed) {
        if (normed == null || normed.isBlank()) return "";
        String[] toks = normed.split(" ");
        if (toks.length <= 1) return "";
        if (toks.length > 8) return ""; // avoid over-merging long phrases
        List<String> list = new ArrayList<>();
        for (String t : toks) {
            if (t.isBlank()) continue;
            list.add(t);
        }
        Collections.sort(list);
        return String.join("_", list);
    }

    static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    static String resolveRedirect(ConcurrentHashMap<String, String> redirect, String id) {
        if (id == null) return null;
        String cur = id;
        int guard = 0;
        while (guard++ < 20) {
            String next = redirect.get(cur);
            if (next == null || next.equals(cur)) return cur;
            cur = next;
        }
        return cur;
    }
}

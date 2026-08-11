package edu.psu.giscience.graphbuilding.datagov;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import org.neo4j.driver.*;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.neo4j.driver.Values.parameters;

/**
 * Stage A only:
 * - Ingest Dataset/Organization/Resource/Format/Keyword/Space/Time/License
 * - Extract PendingTopic via LLM and connect (Dataset)-[:HAS_PENDING_TOPIC]->(PendingTopic)
 */
public class StageAIngestCkan {

    public static void main(String[] args) throws Exception {

        // ========= CONFIG (fill your own) =========
        String OPENAI_API_KEY = "sk-REPLACE_ME";
        String OPENAI_MODEL = "gpt-5.2";

        String INPUT_JSON_ARRAY_PATH = "REPLACE_ME";

        String SOURCE_ID = "data.gov";
        String SOURCE_URL = "https://catalog.data.gov/api/3/action/package_search";

        String NEO4J_URI = "";
        String NEO4J_USER = "neo4j";
        String NEO4J_PASS = "REPLACE_ME";
        String NEO4J_DB = "neo4j";

        int INGEST_BATCH_SIZE = 500;
        int TOPIC_EXTRACT_LLM_BATCH = 12;

        int LLM_CONCURRENCY = 10;           // 8~12
        int MAX_IN_FLIGHT_LLM_BATCHES = 24; // ~= 2 * LLM_CONCURRENCY

        int LOG_EVERY_DATASETS = 200;

        if (OPENAI_API_KEY == null || OPENAI_API_KEY.isBlank() || OPENAI_API_KEY.startsWith("sk-REPLACE")) {
            throw new IllegalArgumentException("Please set OPENAI_API_KEY in StageAIngestMain.main().");
        }

        OpenAIClient openAIClient = OpenAIOkHttpClient.builder()
                .apiKey(OPENAI_API_KEY)
                .build();
        LlmClientService llm = new LlmClientService(openAIClient, OPENAI_MODEL);

        try (Neo4jWriter writer = new Neo4jWriter(NEO4J_URI, NEO4J_USER, NEO4J_PASS, NEO4J_DB)) {
            writer.ensureSchema();

            ProgressTracker progress = new ProgressTracker(LOG_EVERY_DATASETS);

            DatasetIngestor ingestor = new DatasetIngestor(
                    llm, writer,
                    INGEST_BATCH_SIZE,
                    TOPIC_EXTRACT_LLM_BATCH,
                    LLM_CONCURRENCY,
                    MAX_IN_FLIGHT_LLM_BATCHES
            );

            long t0 = System.currentTimeMillis();
            try (FileInputStream fis = new FileInputStream(Path.of(INPUT_JSON_ARRAY_PATH).toFile())) {
                ingestor.ingestJsonArray(fis, SOURCE_ID, SOURCE_URL, progress);
            }
            long t1 = System.currentTimeMillis();

            System.out.println("[Stage A] DONE. elapsed_ms=" + (t1 - t0));
        }
    }

    // =========================
    // Progress tracker
    // =========================
    static class ProgressTracker {
        final long t0 = System.currentTimeMillis();
        final int logEveryDatasets;

        long datasetsSeen = 0;
        long datasetsWritten = 0;
        long llmCallsTopicExtract = 0;

        ProgressTracker(int logEveryDatasets) {
            this.logEveryDatasets = Math.max(1, logEveryDatasets);
        }

        void onDatasetProcessed(long seen, long written, long llmCallsTopic) {
            this.datasetsSeen = seen;
            this.datasetsWritten = written;
            this.llmCallsTopicExtract = llmCallsTopic;

            if (seen % logEveryDatasets == 0) {
                System.out.printf(Locale.ROOT,
                        "[Stage A] seen=%d written=%d llmTopicCalls=%d rate=%.2f ds/s mem=%dMB%n",
                        seen, written, llmCallsTopic,
                        ratePerSec(seen),
                        usedMemMB());
            }
        }

        double ratePerSec(long count) {
            double sec = Math.max(1.0, (System.currentTimeMillis() - t0) / 1000.0);
            return count / sec;
        }

        static long usedMemMB() {
            Runtime rt = Runtime.getRuntime();
            return (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        }
    }

    // =========================
    // OpenAI LLM client (with retry)
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
                        throw new RuntimeException("Failed to extract model text.", e);
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
    // Utils
    // =========================
    static class U {

        static Map<String, String> extrasToMap(JsonNode extrasArray) {
            Map<String, String> map = new HashMap<>();
            if (extrasArray == null || !extrasArray.isArray()) return map;
            for (JsonNode e : extrasArray) {
                String k = e.path("key").asText(null);
                if (k == null) continue;
                String v = e.path("value").isNull() ? null : e.path("value").asText(null);
                map.put(k, v);
            }
            return map;
        }

        static Double parseDouble(String s) {
            if (s == null) return null;
            try { return Double.parseDouble(s.trim()); }
            catch (Exception e) { return null; }
        }

        static String canonicalizeTopic(String name) {
            if (name == null) return "";
            String s = name.toLowerCase(Locale.ROOT).trim();
            s = s.replaceAll("[-_/]+", " ");
            s = s.replaceAll("[^a-z0-9\\s]", " ");
            s = s.replaceAll("\\s+", " ").trim();
            return s;
        }

        static String text(JsonNode n, String field) {
            JsonNode v = n.get(field);
            if (v == null || v.isNull()) return null;
            String s = v.asText(null);
            return (s == null || s.isBlank()) ? null : s;
        }

        static String sha1(String s) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                byte[] b = md.digest((s == null ? "" : s).getBytes());
                StringBuilder sb = new StringBuilder();
                for (byte x : b) sb.append(String.format("%02x", x));
                return sb.toString();
            } catch (Exception e) {
                return UUID.randomUUID().toString().replace("-", "");
            }
        }

        static String safe(String s) {
            if (s == null) return null;
            String t = s.trim();
            return t.isBlank() ? null : t;
        }

        static String normalizeBegin(String raw) {
            return normalizeTemporal(raw, true);
        }

        static String normalizeEnd(String raw) {
            return normalizeTemporal(raw, false);
        }

        static String normalizeTemporal(String raw, boolean isBegin) {
            if (raw == null) {
                return isBegin ? "00010101 00:00:00" : "99991231 00:00:00";
            }
            String s = raw.trim();
            if (s.isBlank()) return isBegin ? "00010101 00:00:00" : "99991231 00:00:00";

            if (s.startsWith("9999")) return "99991231 00:00:00";
            if (s.matches("^-\\d+.*")) return isBegin ? "00010101 00:00:00" : "99991231 00:00:00";

            if (s.matches("^\\d{4}$")) {
                int y = Integer.parseInt(s);
                return isBegin
                        ? String.format(Locale.ROOT, "%04d0101 00:00:00", y)
                        : String.format(Locale.ROOT, "%04d1231 00:00:00", y);
            }

            if (s.matches("^\\d{4}[-/]\\d{2}$")) {
                String[] parts = s.split("[-/]");
                int y = Integer.parseInt(parts[0]);
                int m = Integer.parseInt(parts[1]);
                if (isBegin) return String.format(Locale.ROOT, "%04d%02d01 00:00:00", y, m);
                int lastDay = YearMonth.of(y, m).lengthOfMonth();
                return String.format(Locale.ROOT, "%04d%02d%02d 00:00:00", y, m, lastDay);
            }

            if (s.matches("^\\d{4}[-/]\\d{2}[-/]\\d{2}$")) {
                String[] parts = s.split("[-/]");
                return parts[0] + parts[1] + parts[2] + " 00:00:00";
            }

            try {
                Instant inst = Instant.parse(s);
                LocalDateTime ldt = LocalDateTime.ofInstant(inst, ZoneOffset.UTC);
                return ldt.format(DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss"));
            } catch (DateTimeParseException ignored) {}

            try {
                OffsetDateTime odt = OffsetDateTime.parse(s);
                LocalDateTime ldt = odt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
                return ldt.format(DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss"));
            } catch (DateTimeParseException ignored) {}

            return isBegin ? "00010101 00:00:00" : "99991231 00:00:00";
        }

        // WGS84 validation to avoid Neo4j point errors
        static boolean isValidLon(double lon) { return lon >= -180.0 && lon <= 180.0; }
        static boolean isValidLat(double lat) { return lat >= -90.0 && lat <= 90.0; }
        static boolean isValidWgs84BBox(double west, double south, double east, double north) {
            return isValidLon(west) && isValidLon(east) && isValidLat(south) && isValidLat(north);
        }
    }

    // =========================
    // Model
    // =========================
    static class M {
        record Source(String id, String url) {}

        // Dataset whitelist
        record Dataset(
                String id,
                String name,
                String notes,
                Integer num_resources,
                Integer num_tags,
                String title,
                String type,
                String version,
                String url,
                String license_id,
                String license_title,
                String license_url
        ) {}

        // Organization whitelist
        record Organization(
                String id,
                String name,
                String title,
                String type,
                String description,
                String image_url
        ) {}

        record Space(Double west, Double south, Double east, Double north,
                     Double centroidLon, Double centroidLat,
                     String spatialType, Integer srid, String wktPolygon) {}

        record Time(String beginRaw, String endRaw, String beginTs, String endTs) {}

        record Keyword(String id, String name) {}

        record PendingTopic(String canonical, String name) {}

        record Format(String id, String name, String canonical) {}

        // Resource whitelist
        record Resource(
                String id,
                String created,
                String description,
                String last_modified,
                String name,
                String package_id,
                String resource_locator_function,
                String resource_locator_protocol,
                String resource_type,
                Long size,
                String url,
                String url_type,
                Format format
        ) {}

        // License dedupe by license_id (unique)
        record License(String license_id, String license_title, String license_url) {}

        record Envelope(
                Source source,
                Dataset dataset,
                Organization organization,
                Space space,
                Time time,
                License license,
                List<Keyword> keywords,
                List<PendingTopic> pendingTopics,
                List<Resource> resources
        ) {}
    }

    // =========================
    // Neo4j writer (Stage A only)
    // =========================
    static class Neo4jWriter implements AutoCloseable {
        private final Driver driver;
        private final String database;

        Neo4jWriter(String uri, String user, String pass, String database) {
            this.driver = GraphDatabase.driver(uri, AuthTokens.basic(user, pass));
            this.database = database;
        }

        void ensureSchema() {
            List<String> ddl = List.of(
                    "CREATE CONSTRAINT source_id IF NOT EXISTS FOR (n:Source) REQUIRE n.id IS UNIQUE",
                    "CREATE CONSTRAINT dataset_id IF NOT EXISTS FOR (n:Dataset) REQUIRE n.id IS UNIQUE",
                    "CREATE CONSTRAINT org_id IF NOT EXISTS FOR (n:Organization) REQUIRE n.id IS UNIQUE",
                    "CREATE CONSTRAINT keyword_id IF NOT EXISTS FOR (n:Keyword) REQUIRE n.id IS UNIQUE",
                    "CREATE CONSTRAINT resource_id IF NOT EXISTS FOR (n:Resource) REQUIRE n.id IS UNIQUE",
                    "CREATE CONSTRAINT format_id IF NOT EXISTS FOR (n:Format) REQUIRE n.id IS UNIQUE",

                    "CREATE CONSTRAINT space_dataset IF NOT EXISTS FOR (n:Space) REQUIRE n.dataset_id IS UNIQUE",
                    "CREATE CONSTRAINT time_dataset IF NOT EXISTS FOR (n:Time) REQUIRE n.dataset_id IS UNIQUE",
                    "CREATE POINT INDEX space_centroid IF NOT EXISTS FOR (s:Space) ON (s.centroid)",

                    "CREATE CONSTRAINT license_id_unique IF NOT EXISTS FOR (l:License) REQUIRE l.license_id IS UNIQUE",

                    "CREATE CONSTRAINT topic_canon IF NOT EXISTS FOR (n:Topic) REQUIRE n.canonical IS UNIQUE",
                    "CREATE CONSTRAINT topic_id IF NOT EXISTS FOR (n:Topic) REQUIRE n.id IS UNIQUE",
                    "CREATE FULLTEXT INDEX topic_fulltext IF NOT EXISTS FOR (t:Topic) ON EACH [t.name, t.canonical, t.aliases]",

                    "CREATE CONSTRAINT pending_canon IF NOT EXISTS FOR (p:PendingTopic) REQUIRE p.canonical IS UNIQUE",
                    "CREATE INDEX pending_status IF NOT EXISTS FOR (p:PendingTopic) ON (p.status)"
            );

            try (Session session = driver.session(SessionConfig.forDatabase(database))) {
                for (String c : ddl) session.run(c).consume();
            }
        }

        void writeBatch(List<M.Envelope> batch) {
            if (batch == null || batch.isEmpty()) return;

            String cypher = """
                    UNWIND $rows AS row
                    
                    MERGE (src:Source {id: row.sourceId})
                    SET src.url = coalesce(row.sourceUrl, src.url)
                    
                    MERGE (d:Dataset {id: row.datasetId})
                    SET d.id = row.datasetId,
                        d.name = row.datasetName,
                        d.notes = row.datasetNotes,
                        d.num_resources = row.datasetNumResources,
                        d.num_tags = row.datasetNumTags,
                        d.title = row.datasetTitle,
                        d.type = row.datasetType,
                        d.version = row.datasetVersion,
                        d.url = row.datasetUrl,
                        d.ingested_at = row.ingestedAt
                    MERGE (src)-[:PROVIDES]->(d)
                    
                    FOREACH (_ IN CASE WHEN row.orgId IS NULL THEN [] ELSE [1] END |
                      MERGE (o:Organization {id: row.orgId})
                      SET o.id = row.orgId,
                          o.name = row.orgName,
                          o.title = row.orgTitle,
                          o.type = row.orgType,
                          o.description = row.orgDescription,
                          o.image_url = row.orgImageUrl
                      MERGE (d)-[:PUBLISHED_BY]->(o)
                    )
                    
                    FOREACH (_ IN CASE WHEN row.hasSpace = false THEN [] ELSE [1] END |
                      MERGE (sp:Space {dataset_id: row.datasetId})
                      SET sp.west = row.west, sp.south = row.south, sp.east = row.east, sp.north = row.north,
                          sp.spatial_type = row.spatialType, sp.srid = row.srid, sp.wkt = row.wktPolygon,
                          sp.centroid = point({longitude: row.centroidLon, latitude: row.centroidLat})
                      MERGE (d)-[:HAS_SPACE]->(sp)
                    )
                    
                    FOREACH (_ IN CASE WHEN row.hasTime = false THEN [] ELSE [1] END |
                      MERGE (t:Time {dataset_id: row.datasetId})
                      SET t.begin_raw = row.beginRaw, t.end_raw = row.endRaw,
                          t.begin = row.beginTs, t.end = row.endTs
                      MERGE (d)-[:HAS_TIME]->(t)
                    )
                    
                    // License: global dedupe by license_id
                    FOREACH (_ IN CASE WHEN row.hasLicense = false THEN [] ELSE [1] END |
                      MERGE (l:License {license_id: row.licenseId})
                      SET l.license_title = coalesce(row.licenseTitle, l.license_title),
                          l.license_url   = coalesce(row.licenseUrl,   l.license_url)
                      MERGE (d)-[:HAS_LICENSE]->(l)
                    )
                    
                    FOREACH (kw IN row.keywords |
                      MERGE (k:Keyword {id: kw.id})
                      SET k.name = kw.name
                      MERGE (d)-[:HAS_KEYWORD]->(k)
                    )
                    
                    FOREACH (pt IN row.pendingTopics |
                      MERGE (p:PendingTopic {canonical: pt.canonical})
                      ON CREATE SET p.name = pt.name, p.created_at = row.ingestedAt, p.status = 'PENDING'
                      SET p.updated_at = row.ingestedAt
                      MERGE (d)-[:HAS_PENDING_TOPIC]->(p)
                    )
                    
                    FOREACH (r IN row.resources |
                      MERGE (res:Resource {id: r.id})
                      SET res.id = r.id,
                          res.created = r.created,
                          res.description = r.description,
                          res.last_modified = r.last_modified,
                          res.name = r.name,
                          res.package_id = r.package_id,
                          res.resource_locator_function = r.resource_locator_function,
                          res.resource_locator_protocol = r.resource_locator_protocol,
                          res.resource_type = r.resource_type,
                          res.size = r.size,
                          res.url = r.url,
                          res.url_type = r.url_type
                      MERGE (d)-[:HAS_RESOURCE]->(res)
                    
                      MERGE (f:Format {id: r.format.id})
                      SET f.name = r.format.name, f.canonical = r.format.canonical
                      MERGE (res)-[:HAS_FORMAT]->(f)
                    )
                    """;

            List<Map<String, Object>> rows = new ArrayList<>(batch.size());
            String now = Instant.now().toString();

            for (M.Envelope env : batch) {
                Map<String, Object> row = new HashMap<>();

                row.put("sourceId", env.source().id());
                row.put("sourceUrl", env.source().url());

                row.put("datasetId", env.dataset().id());
                row.put("datasetName", env.dataset().name());
                row.put("datasetNotes", env.dataset().notes());
                row.put("datasetNumResources", env.dataset().num_resources());
                row.put("datasetNumTags", env.dataset().num_tags());
                row.put("datasetTitle", env.dataset().title());
                row.put("datasetType", env.dataset().type());
                row.put("datasetVersion", env.dataset().version());
                row.put("datasetUrl", env.dataset().url());
                row.put("ingestedAt", now);

                if (env.organization() != null) {
                    row.put("orgId", env.organization().id());
                    row.put("orgName", env.organization().name());
                    row.put("orgTitle", env.organization().title());
                    row.put("orgType", env.organization().type());
                    row.put("orgDescription", env.organization().description());
                    row.put("orgImageUrl", env.organization().image_url());
                } else {
                    row.put("orgId", null);
                }

                if (env.space() != null && env.space().centroidLon() != null && env.space().centroidLat() != null) {
                    row.put("hasSpace", true);
                    row.put("west", env.space().west());
                    row.put("south", env.space().south());
                    row.put("east", env.space().east());
                    row.put("north", env.space().north());
                    row.put("centroidLon", env.space().centroidLon());
                    row.put("centroidLat", env.space().centroidLat());
                    row.put("spatialType", env.space().spatialType());
                    row.put("srid", env.space().srid());
                    row.put("wktPolygon", env.space().wktPolygon());
                } else {
                    row.put("hasSpace", false);
                }

                if (env.time() != null) {
                    row.put("hasTime", true);
                    row.put("beginRaw", env.time().beginRaw());
                    row.put("endRaw", env.time().endRaw());
                    row.put("beginTs", env.time().beginTs());
                    row.put("endTs", env.time().endTs());
                } else {
                    row.put("hasTime", false);
                }

                if (env.license() != null && env.license().license_id() != null && !env.license().license_id().isBlank()) {
                    row.put("hasLicense", true);
                    row.put("licenseId", env.license().license_id());
                    row.put("licenseTitle", env.license().license_title());
                    row.put("licenseUrl", env.license().license_url());
                } else {
                    row.put("hasLicense", false);
                }

                List<Map<String, Object>> kw = new ArrayList<>();
                for (M.Keyword k : env.keywords()) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", k.id());
                    m.put("name", k.name());
                    kw.add(m);
                }
                row.put("keywords", kw);

                List<Map<String, Object>> pts = new ArrayList<>();
                for (M.PendingTopic pt : env.pendingTopics()) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("canonical", pt.canonical());
                    m.put("name", pt.name());
                    pts.add(m);
                }
                row.put("pendingTopics", pts);

                List<Map<String, Object>> rs = new ArrayList<>();
                for (M.Resource r : env.resources()) {
                    Map<String, Object> rm = new HashMap<>();
                    rm.put("id", r.id());
                    rm.put("created", r.created());
                    rm.put("description", r.description());
                    rm.put("last_modified", r.last_modified());
                    rm.put("name", r.name());
                    rm.put("package_id", r.package_id());
                    rm.put("resource_locator_function", r.resource_locator_function());
                    rm.put("resource_locator_protocol", r.resource_locator_protocol());
                    rm.put("resource_type", r.resource_type());
                    rm.put("size", r.size());
                    rm.put("url", r.url());
                    rm.put("url_type", r.url_type());

                    Map<String, Object> fmt = new HashMap<>();
                    fmt.put("id", r.format().id());
                    fmt.put("name", r.format().name());
                    fmt.put("canonical", r.format().canonical());
                    rm.put("format", fmt);

                    rs.add(rm);
                }
                row.put("resources", rs);

                rows.add(row);
            }

            try (Session session = driver.session(SessionConfig.forDatabase(database))) {
                session.executeWrite(tx -> {
                    tx.run(cypher, parameters("rows", rows)).consume();
                    return null;
                });
            }
        }

        @Override public void close() { driver.close(); }
    }

    // =========================
    // Stage A: Ingestor (parallel LLM topic extraction)
    // =========================
    static class DatasetIngestor {
        private final ObjectMapper om = new ObjectMapper();
        private final LlmClientService llm;
        private final Neo4jWriter writer;
        private final int writeBatchSize;
        private final int llmTopicBatchSize;

        private final int llmConcurrency;
        private final int maxInFlightBatches;

        private final ConcurrentHashMap<String, List<M.PendingTopic>> topicExtractCache = new ConcurrentHashMap<>();
        private final AtomicLong llmTopicCalls = new AtomicLong(0);

        DatasetIngestor(LlmClientService llm, Neo4jWriter writer,
                        int writeBatchSize, int llmTopicBatchSize,
                        int llmConcurrency, int maxInFlightBatches) {
            this.llm = llm;
            this.writer = writer;
            this.writeBatchSize = writeBatchSize;
            this.llmTopicBatchSize = Math.max(1, llmTopicBatchSize);
            this.llmConcurrency = Math.max(1, llmConcurrency);
            this.maxInFlightBatches = Math.max(1, maxInFlightBatches);
        }

        static class PendingWork {
            final String datasetId;
            final JsonNode datasetNode;
            final List<M.Keyword> keywords;
            final String mergedText;
            final String mergedHash;

            PendingWork(String datasetId, JsonNode datasetNode, List<M.Keyword> keywords, String mergedText, String mergedHash) {
                this.datasetId = datasetId;
                this.datasetNode = datasetNode;
                this.keywords = keywords;
                this.mergedText = mergedText;
                this.mergedHash = mergedHash;
            }
        }

        void ingestJsonArray(InputStream jsonArrayStream, String sourceId, String sourceUrl, ProgressTracker progress) throws Exception {
            JsonParser p = om.getFactory().createParser(jsonArrayStream);

            if (p.nextToken() != JsonToken.START_ARRAY) {
                throw new IllegalArgumentException("Expected root JSON array: [ {dataset}, ... ]");
            }

            ExecutorService pool = Executors.newFixedThreadPool(llmConcurrency);
            CompletionService<List<M.Envelope>> ecs = new ExecutorCompletionService<>(pool);

            List<M.Envelope> writeBatch = new ArrayList<>(writeBatchSize);
            List<PendingWork> topicBatch = new ArrayList<>(llmTopicBatchSize);

            long seen = 0;
            long written = 0;

            int submitted = 0;
            int completed = 0;

            try {
                while (p.nextToken() == JsonToken.START_OBJECT) {
                    JsonNode datasetNode = om.readTree(p);

                    String datasetId = U.text(datasetNode, "id");
                    if (datasetId == null) datasetId = UUID.randomUUID().toString();

                    // tags -> Keyword
                    List<M.Keyword> keywords = new ArrayList<>();
                    JsonNode tags = datasetNode.path("tags");
                    if (tags.isArray()) {
                        for (JsonNode t : tags) {
                            String kid = t.path("id").asText(null);
                            if (kid == null || kid.isBlank()) kid = UUID.randomUUID().toString();
                            String name = t.path("display_name").asText(t.path("name").asText(""));
                            name = U.safe(name);
                            if (name != null) keywords.add(new M.Keyword(kid, name));
                        }
                    }

                    String title = datasetNode.path("title").asText("");
                    String notes = datasetNode.path("notes").asText("");
                    String tagText = keywords.stream().map(M.Keyword::name).collect(Collectors.joining(", "));
                    String merged = ("TITLE:\n" + title + "\n\nNOTES:\n" + notes + "\n\nTAGS:\n" + tagText).trim();
                    String hash = U.sha1(merged);

                    topicBatch.add(new PendingWork(datasetId, datasetNode, keywords, merged, hash));
                    seen++;

                    if (topicBatch.size() >= llmTopicBatchSize) {
                        List<PendingWork> batchCopy = new ArrayList<>(topicBatch);
                        topicBatch.clear();

                        ecs.submit(() -> buildEnvelopesForTopicBatch(batchCopy, sourceId, sourceUrl));
                        submitted++;

                        while (submitted - completed >= maxInFlightBatches) {
                            List<M.Envelope> envs = ecs.take().get();
                            completed++;

                            writeBatch.addAll(envs);
                            while (writeBatch.size() >= writeBatchSize) {
                                List<M.Envelope> sub = new ArrayList<>(writeBatch.subList(0, writeBatchSize));
                                writer.writeBatch(sub);
                                written += sub.size();
                                writeBatch.subList(0, writeBatchSize).clear();
                            }
                        }
                    }

                    progress.onDatasetProcessed(seen, written, llmTopicCalls.get());
                }

                if (!topicBatch.isEmpty()) {
                    List<PendingWork> batchCopy = new ArrayList<>(topicBatch);
                    topicBatch.clear();
                    ecs.submit(() -> buildEnvelopesForTopicBatch(batchCopy, sourceId, sourceUrl));
                    submitted++;
                }

                while (completed < submitted) {
                    List<M.Envelope> envs = ecs.take().get();
                    completed++;

                    writeBatch.addAll(envs);
                    while (writeBatch.size() >= writeBatchSize) {
                        List<M.Envelope> sub = new ArrayList<>(writeBatch.subList(0, writeBatchSize));
                        writer.writeBatch(sub);
                        written += sub.size();
                        writeBatch.subList(0, writeBatchSize).clear();
                    }
                }

                if (!writeBatch.isEmpty()) {
                    writer.writeBatch(writeBatch);
                    written += writeBatch.size();
                    writeBatch.clear();
                }

                progress.onDatasetProcessed(seen, written, llmTopicCalls.get());
                System.out.println("[Stage A] Total datasets ingested: " + seen);

            } finally {
                pool.shutdown();
                pool.awaitTermination(10, TimeUnit.MINUTES);
            }
        }

        private List<M.Envelope> buildEnvelopesForTopicBatch(List<PendingWork> works, String sourceId, String sourceUrl) {
            Map<String, List<M.PendingTopic>> hash2topics = new HashMap<>();
            List<PendingWork> needLlm = new ArrayList<>();

            for (PendingWork w : works) {
                List<M.PendingTopic> cached = topicExtractCache.get(w.mergedHash);
                if (cached != null) {
                    hash2topics.put(w.mergedHash, cached);
                } else {
                    needLlm.add(w);
                }
            }

            if (!needLlm.isEmpty()) {
                Map<String, List<M.PendingTopic>> got = extractPendingTopicsBatchWithLlm(needLlm);
                for (var e : got.entrySet()) {
                    topicExtractCache.put(e.getKey(), e.getValue());
                    hash2topics.put(e.getKey(), e.getValue());
                }
            }

            List<M.Envelope> envs = new ArrayList<>(works.size());
            for (PendingWork w : works) {
                M.Source source = new M.Source(sourceId, sourceUrl);

                // Dataset whitelist
                M.Dataset dataset = new M.Dataset(
                        w.datasetId,
                        U.text(w.datasetNode, "name"),
                        U.text(w.datasetNode, "notes"),
                        w.datasetNode.path("num_resources").isMissingNode() ? null : w.datasetNode.path("num_resources").asInt(),
                        w.datasetNode.path("num_tags").isMissingNode() ? null : w.datasetNode.path("num_tags").asInt(),
                        U.text(w.datasetNode, "title"),
                        U.text(w.datasetNode, "type"),
                        U.text(w.datasetNode, "version"),
                        U.text(w.datasetNode, "url"),
                        U.text(w.datasetNode, "license_id"),
                        U.text(w.datasetNode, "license_title"),
                        U.text(w.datasetNode, "license_url")
                );

                // Organization whitelist
                M.Organization org = null;
                JsonNode orgNode = w.datasetNode.path("organization");
                if (orgNode != null && !orgNode.isMissingNode() && !orgNode.isNull()) {
                    String orgId = orgNode.path("id").asText(null);
                    if (orgId == null || orgId.isBlank()) orgId = UUID.randomUUID().toString();
                    org = new M.Organization(
                            orgId,
                            U.text(orgNode, "name"),
                            U.text(orgNode, "title"),
                            U.text(orgNode, "type"),
                            U.text(orgNode, "description"),
                            U.text(orgNode, "image_url")
                    );
                }

                Map<String, String> extras = U.extrasToMap(w.datasetNode.path("extras"));
                M.Space space = buildSpace(extras);
                M.Time time = buildTime(extras);

                // License: if (id==null && title==null) => null
                // Dedupe key is license_id. If missing but title exists, synthesize stable id from title.
                M.License license = buildLicense(dataset);

                List<M.Resource> resources = buildResources(w.datasetNode);
                List<M.PendingTopic> pending = hash2topics.getOrDefault(w.mergedHash, List.of());

                envs.add(new M.Envelope(source, dataset, org, space, time, license, w.keywords, pending, resources));
            }

            return envs;
        }

        private M.License buildLicense(M.Dataset dataset) {
            String rawId = U.safe(dataset.license_id());
            String title = U.safe(dataset.license_title());
            String url = U.safe(dataset.license_url());
            if (rawId == null && title == null) return null;

            // keep uniqueness on license_id property:
            // if license_id missing but title exists -> synthetic stable id
            String id = (rawId != null) ? rawId : ("lic:" + U.sha1(title.toLowerCase(Locale.ROOT)));
            return new M.License(id, title, url);
        }

        private Map<String, List<M.PendingTopic>> extractPendingTopicsBatchWithLlm(List<PendingWork> works) {
            try {
                ArrayNode arr = om.createArrayNode();
                for (PendingWork w : works) {
                    ObjectNode o = arr.addObject();
                    o.put("hash", w.mergedHash);
                    o.put("text", w.mergedText);
                }

                String prompt = """
                        You extract topical concepts for geospatial dataset discovery.
                        For each item, return short noun-phrase topics (2-5 words each).
                        Avoid places, dates, agencies, file formats, licenses, and generic words like "dataset".
                        
                        Output MUST be valid JSON ONLY.
                        Schema:
                        {
                          "results":[
                            {"hash":"...","topics":["t1","t2","..."]},
                            ...
                          ]
                        }
                        
                        Items:
                        %s
                        """.formatted(om.writeValueAsString(arr));

                String txt = llm.askPlain(prompt).trim();
                llmTopicCalls.incrementAndGet();

                JsonNode root = om.readTree(txt);
                Map<String, List<M.PendingTopic>> out = new HashMap<>();

                for (JsonNode item : root.path("results")) {
                    String hash = item.path("hash").asText("");
                    if (hash.isBlank()) continue;

                    List<String> names = new ArrayList<>();
                    for (JsonNode t : item.path("topics")) {
                        String s = t.asText("").trim();
                        if (!s.isEmpty()) names.add(s);
                    }

                    LinkedHashMap<String, String> canon2name = new LinkedHashMap<>();
                    for (String raw : names) {
                        String n = raw.trim();
                        String canon = U.canonicalizeTopic(n);
                        if (!canon.isBlank()) canon2name.putIfAbsent(canon, n);
                    }

                    List<M.PendingTopic> pts = new ArrayList<>();
                    for (var e : canon2name.entrySet()) {
                        pts.add(new M.PendingTopic(e.getKey(), e.getValue()));
                    }
                    out.put(hash, pts);
                }

                for (PendingWork w : works) out.putIfAbsent(w.mergedHash, List.of());
                return out;

            } catch (Exception e) {
                Map<String, List<M.PendingTopic>> out = new HashMap<>();
                for (PendingWork w : works) out.put(w.mergedHash, List.of());
                return out;
            }
        }

        private M.Space buildSpace(Map<String, String> extras) {
            Double east = U.parseDouble(extras.get("bbox-east-long"));
            Double north = U.parseDouble(extras.get("bbox-north-lat"));
            Double south = U.parseDouble(extras.get("bbox-south-lat"));
            Double west = U.parseDouble(extras.get("bbox-west-long"));

            if (east == null || north == null || south == null || west == null) return null;

            double w = west, e = east, s = south, n = north;

            // drop non-WGS84 bbox (prevents Neo4j invalid point exception)
            if (!U.isValidWgs84BBox(w, s, e, n)) return null;

            if (s > n) { double tmp = s; s = n; n = tmp; }
            if (w > e) { double tmp = w; w = e; e = tmp; }

            if (!U.isValidWgs84BBox(w, s, e, n)) return null;

            double centroidLon = (w + e) / 2.0;
            double centroidLat = (s + n) / 2.0;

            if (!U.isValidLon(centroidLon) || !U.isValidLat(centroidLat)) return null;

            String wkt = "POLYGON(("
                    + w + " " + s + ", "
                    + e + " " + s + ", "
                    + e + " " + n + ", "
                    + w + " " + n + ", "
                    + w + " " + s
                    + "))";

            return new M.Space(w, s, e, n, centroidLon, centroidLat, "bbox", 4326, wkt);
        }

        private M.Time buildTime(Map<String, String> extras) {
            String beginRaw = extras.get("temporal-extent-begin");
            String endRaw = extras.get("temporal-extent-end");
            if ((beginRaw == null || beginRaw.isBlank()) && (endRaw == null || endRaw.isBlank())) return null;

            String beginTs = U.normalizeBegin(beginRaw);
            String endTs = U.normalizeEnd(endRaw);

            if (beginTs.compareTo(endTs) > 0) endTs = beginTs;
            return new M.Time(beginRaw, endRaw, beginTs, endTs);
        }

        private List<M.Resource> buildResources(JsonNode datasetNode) {
            List<M.Resource> resources = new ArrayList<>();
            JsonNode resArr = datasetNode.path("resources");
            if (!resArr.isArray()) return resources;

            for (JsonNode r : resArr) {
                String rid = r.path("id").asText(null);
                if (rid == null || rid.isBlank()) rid = UUID.randomUUID().toString();

                String fmtName = r.path("format").asText("");
                M.Format fmt = buildFormat(fmtName);

                M.Resource res = new M.Resource(
                        rid,
                        U.text(r, "created"),
                        U.text(r, "description"),
                        U.text(r, "last_modified"),
                        U.text(r, "name"),
                        U.text(r, "package_id"),
                        U.text(r, "resource_locator_function"),
                        U.text(r, "resource_locator_protocol"),
                        U.text(r, "resource_type"),
                        r.path("size").isMissingNode() || r.path("size").isNull() ? null : r.path("size").asLong(),
                        U.text(r, "url"),
                        U.text(r, "url_type"),
                        fmt
                );
                resources.add(res);
            }
            return resources;
        }

        private M.Format buildFormat(String formatName) {
            String name = (formatName == null) ? "" : formatName.trim();
            String canonical = name.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
            if (canonical.isBlank()) canonical = "unknown";
            String id = "format:" + canonical;
            return new M.Format(id, name.isBlank() ? "UNKNOWN" : name, canonical);
        }
    }
}

package edu.psu.giscience.graphbuilding.stac;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import org.neo4j.driver.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.neo4j.driver.Values.parameters;

/**
 * Stage A (STAC): Ingest STAC Catalog / STAC API into Neo4j using the SAME core ontology
 * used by your Data.gov pipeline:
 *
 *   (Source)-[:PROVIDES]->(Dataset)
 *   (Dataset)-[:PUBLISHED_BY]->(Organization)
 *   (Dataset)-[:HAS_SPACE]->(Space)
 *   (Dataset)-[:HAS_TIME]->(Time)
 *   (Dataset)-[:HAS_LICENSE]->(License)
 *   (Dataset)-[:HAS_KEYWORD]->(Keyword)
 *   (Dataset)-[:HAS_PENDING_TOPIC]->(PendingTopic)
 *   (Dataset)-[:HAS_RESOURCE]->(Resource)-[:HAS_FORMAT]->(Format)
 *
 * Recommended pipeline order:
 *   1) Run this file (StageAIngestStac)
 *   2) Run StageAEmbedAllEntities.java
 *   3) Run StageB.java
 *
 * Your latest requirements applied:
 *  1) Time uses the SAME string format as your original Data.gov pipeline:
 *        "yyyyMMdd HH:mm:ss"
 *     (NOT epoch millis)
 *  2) For STAC ingestion, entity IDs can be random UUID strings (like Data.gov UUIDs).
 *     De-duplication is done by CANONICAL within STAC (not cross-source):
 *        - Keyword: MERGE by canonical (store id as UUID on create)
 *        - Organization: MERGE by canonicalName (store id as UUID on create)
 *        - Format: MERGE by canonical (store id as UUID on create)
 *        - License: MERGE by license_id (STAC "license" string)
 *
 * Notes:
 * - This ingests STAC *Collections* as (Dataset).
 * - It attaches a small set of STAC links as (Resource) nodes.
 * - PendingTopic can be seeded from keywords and/or extracted via LLM (optional).
 */
public class StageAIngestStac {

    // ===========================
    // Entry
    // ===========================
    public static void main(String[] args) throws Exception {

        // ---------------------------
        // STAC inputs
        // ---------------------------
        // You can set STAC_ROOT_URL to:
        //   - a STAC API landing page (recommended), OR
        //   - a STAC Catalog root, OR
        //   - a single STAC Collection URL
        // https://hda.data.destination-earth.eu/stac/v2
        // https://earthengine.openeo.org/v1.0/
        // https://planetarycomputer.microsoft.com/api/stac/v1/
        // https://paituli.csc.fi/geoserver/ogc/stac/v1
        String STAC_ROOT_URL = "https://paituli.csc.fi/geoserver/ogc/stac/v1"; // REPLACE_ME
        int MAX_CRAWL_DEPTH = 6;          // Catalog traversal depth limit (ignored for STAC API /collections listing)
        int MAX_COLLECTIONS = 5000;       // Safety cap
        boolean ENABLE_HTTP_CACHE = true; // Cache GET responses to disk
        Path CACHE_DIR = Paths.get("stac_cache"); // created if not exists

        // If true, try to fetch each Collection's "items" link and COUNT items (lightweight).
        // This is optional and may be slow for some APIs; leave false unless needed.
        boolean FETCH_ITEM_COUNTS = false;

        // ---------------------------
        // Topic extraction options
        // ---------------------------
        // If OPENAI_API_KEY is blank, LLM extraction is skipped.
        String OPENAI_API_KEY = ""; // leave blank/REPLACE_ME to disable
        String OPENAI_MODEL = "gpt-5.2";
        boolean SEED_PENDING_TOPICS_FROM_KEYWORDS = true;
        boolean ENABLE_LLM_TOPIC_EXTRACT =
                OPENAI_API_KEY != null && !OPENAI_API_KEY.isBlank() && !"REPLACE_ME".equalsIgnoreCase(OPENAI_API_KEY.trim());

        int TOPIC_EXTRACT_LLM_BATCH = 12;
        int LLM_CONCURRENCY = 10;
        int MAX_IN_FLIGHT_LLM_BATCHES = 24;

        // ---------------------------
        // Neo4j configs
        // ---------------------------
        String NEO4J_URI = "";
        String NEO4J_USER = "neo4j";
        String NEO4J_PASS = "";
        String NEO4J_DB = "neo4j";
        int INGEST_BATCH_SIZE = 500;

        // ---------------------------
        // Basic setup
        // ---------------------------
        ObjectMapper om = new ObjectMapper();
        if (ENABLE_HTTP_CACHE) Files.createDirectories(CACHE_DIR);

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        StacClient stac = new StacClient(http, om, ENABLE_HTTP_CACHE ? CACHE_DIR : null);
        String ingestedAt = Instant.now().toString();

        // Source id is deterministic hash (stable). (No "stac:" prefix)
        String sourceId = U.sha1Hex(STAC_ROOT_URL);
        String sourceUrl = STAC_ROOT_URL;

        List<JsonNode> collections = StacDiscovery.discoverCollections(stac, STAC_ROOT_URL, MAX_CRAWL_DEPTH, MAX_COLLECTIONS);
        System.out.println("[STAC] Discovered collections: " + collections.size());

        // LLM client (optional)
        Llm llm = null;
        ExecutorService llmPool = null;
        if (ENABLE_LLM_TOPIC_EXTRACT) {
            llm = new Llm(OPENAI_API_KEY, OPENAI_MODEL);
            llmPool = Executors.newFixedThreadPool(LLM_CONCURRENCY);
        }

        try (Driver driver = GraphDatabase.driver(NEO4J_URI, AuthTokens.basic(NEO4J_USER, NEO4J_PASS))) {
            Neo4jWriter writer = new Neo4jWriter(driver, NEO4J_DB);
            writer.ensureSchema();

            // Build envelopes
            List<M.Envelope> envelopes = new ArrayList<>(collections.size());
            for (JsonNode col : collections) {
                M.Envelope env = StacMapper.mapCollectionToEnvelope(col, sourceId, sourceUrl, ingestedAt, FETCH_ITEM_COUNTS, stac);
                if (env == null) continue;

                // Seed pending topics from keywords (optional)
                if (SEED_PENDING_TOPICS_FROM_KEYWORDS && env.keywords != null && !env.keywords.isEmpty()) {
                    LinkedHashMap<String, String> canon2name = new LinkedHashMap<>();
                    for (M.Keyword kw : env.keywords) {
                        String canon = U.canonicalizeTopic(kw.name);
                        if (canon.isBlank()) continue;
                        canon2name.putIfAbsent(canon, kw.name.trim());
                    }
                    for (var e : canon2name.entrySet()) {
                        env.pendingTopics.add(new M.PendingTopic(e.getKey(), e.getValue()));
                    }
                }

                envelopes.add(env);
            }

            // Optional LLM extraction in batches (adds more PendingTopic)
            AtomicLong llmTopicCalls = new AtomicLong(0);
            if (ENABLE_LLM_TOPIC_EXTRACT && llm != null) {
                System.out.println("[STAC] LLM topic extraction enabled. model=" + OPENAI_MODEL);
                TopicExtractor.extractInParallel(
                        envelopes,
                        TOPIC_EXTRACT_LLM_BATCH,
                        llm,
                        llmPool,
                        MAX_IN_FLIGHT_LLM_BATCHES,
                        llmTopicCalls
                );
                System.out.println("[STAC] LLM topic batches called: " + llmTopicCalls.get());
            } else {
                System.out.println("[STAC] LLM topic extraction skipped (no API key).");
            }

            // Write to Neo4j
            int total = envelopes.size();
            int wrote = 0;

            List<M.Envelope> batch = new ArrayList<>(INGEST_BATCH_SIZE);
            for (M.Envelope env : envelopes) {
                batch.add(env);
                if (batch.size() >= INGEST_BATCH_SIZE) {
                    writer.writeBatch(batch);
                    wrote += batch.size();
                    batch.clear();
                    if (wrote % 500 == 0) System.out.println("[STAC] Ingested " + wrote + "/" + total);
                }
            }
            if (!batch.isEmpty()) {
                writer.writeBatch(batch);
                wrote += batch.size();
            }

            System.out.println("[STAC] Done. Ingested datasets=" + wrote);
        } finally {
            if (llmPool != null) llmPool.shutdownNow();
        }
    }

    // ============================================================
    // STAC discovery + mapping
    // ============================================================

    static class StacDiscovery {

        /**
         * Returns a list of STAC Collection JSON nodes.
         *
         * Supported roots:
         *  - STAC API landing page (Catalog with link rel=data/collections)
         *  - STAC Catalog root (links rel=child/collection)
         *  - Single Collection URL
         */
        static List<JsonNode> discoverCollections(StacClient stac, String rootUrl, int maxDepth, int maxCollections)
                throws IOException, InterruptedException {

            JsonNode root = stac.getJson(rootUrl);

            String type = root.path("type").asText("");
            if ("Collection".equalsIgnoreCase(type)) {
                return List.of(root);
            }

            // Prefer STAC API /collections listing if available
            String collectionsUrl = findCollectionsUrl(rootUrl, root);
            if (collectionsUrl != null) {
                return listCollectionsFromStacApi(stac, collectionsUrl, maxCollections);
            }

            // Otherwise traverse as Catalog
            return traverseCatalogForCollections(stac, rootUrl, maxDepth, maxCollections);
        }

        private static String findCollectionsUrl(String rootUrl, JsonNode root) {
            // STAC API landing page commonly has link rel="data" pointing to /collections
            for (JsonNode link : root.path("links")) {
                String rel = link.path("rel").asText("");
                String href = link.path("href").asText("");
                if (href.isBlank()) continue;

                boolean relOk = "data".equalsIgnoreCase(rel)
                        || "collections".equalsIgnoreCase(rel)
                        || "http://www.opengis.net/def/rel/ogc/1.0/data".equalsIgnoreCase(rel);

                if (!relOk) continue;

                String abs = U.absUrl(rootUrl, href);
                if (abs == null) continue;

                String lower = abs.toLowerCase(Locale.ROOT);
                if (lower.endsWith("/collections") || lower.contains("/collections?") || lower.contains("/collections/")) {
                    return abs;
                }
            }

            // Fallback: if root looks like STAC API, try rootUrl + "/collections"
            if (root.has("conformsTo")) {
                return U.joinUrl(rootUrl, "collections");
            }

            return null;
        }

        private static List<JsonNode> listCollectionsFromStacApi(StacClient stac, String collectionsUrl, int maxCollections)
                throws IOException, InterruptedException {

            JsonNode res = stac.getJson(collectionsUrl);
            JsonNode arr = res.path("collections");
            if (arr == null || !arr.isArray()) return List.of();

            List<JsonNode> out = new ArrayList<>();
            for (JsonNode c : arr) {
                if (out.size() >= maxCollections) break;

                // If a self link exists, fetch it to normalize.
                String self = null;
                for (JsonNode link : c.path("links")) {
                    if ("self".equalsIgnoreCase(link.path("rel").asText(""))) {
                        self = link.path("href").asText("");
                        break;
                    }
                }
                if (self != null && !self.isBlank()) {
                    String abs = U.absUrl(collectionsUrl, self);
                    try {
                        out.add(stac.getJson(abs));
                    } catch (Exception e) {
                        out.add(c);
                    }
                } else {
                    out.add(c);
                }
            }
            return out;
        }

        private static List<JsonNode> traverseCatalogForCollections(StacClient stac, String rootUrl, int maxDepth, int maxCollections)
                throws IOException, InterruptedException {

            Deque<Q> q = new ArrayDeque<>();
            q.add(new Q(rootUrl, 0));

            Set<String> visited = new HashSet<>();
            List<JsonNode> out = new ArrayList<>();

            while (!q.isEmpty() && out.size() < maxCollections) {
                Q cur = q.pollFirst();
                if (cur.depth > maxDepth) continue;

                String norm = U.normalizeUrl(cur.url);
                if (!visited.add(norm)) continue;

                JsonNode node;
                try {
                    node = stac.getJson(cur.url);
                } catch (Exception e) {
                    continue;
                }

                String type = node.path("type").asText("");
                if ("Collection".equalsIgnoreCase(type)) {
                    out.add(node);
                    continue;
                }

                for (JsonNode link : node.path("links")) {
                    String rel = link.path("rel").asText("");
                    String href = link.path("href").asText("");
                    if (href.isBlank()) continue;

                    if ("child".equalsIgnoreCase(rel) || "collection".equalsIgnoreCase(rel) || "data".equalsIgnoreCase(rel)) {
                        String abs = U.absUrl(cur.url, href);
                        if (abs == null) continue;
                        q.addLast(new Q(abs, cur.depth + 1));
                    }
                }
            }

            return out;
        }

        static class Q {
            final String url;
            final int depth;

            Q(String url, int depth) {
                this.url = url;
                this.depth = depth;
            }
        }
    }

    static class StacMapper {

        static M.Envelope mapCollectionToEnvelope(JsonNode col,
                                                  String sourceId,
                                                  String sourceUrl,
                                                  String ingestedAt,
                                                  boolean fetchItemCounts,
                                                  StacClient stac) {

            String type = col.path("type").asText("");
            if (!"Collection".equalsIgnoreCase(type) && !col.has("extent")) {
                return null;
            }

            String stacVersion = col.path("stac_version").asText("");
            JsonNode stacExt = col.get("stac_extensions");

            String colId = col.path("id").asText("");
            if (colId.isBlank()) colId = U.sha1Hex(col.toString()).substring(0, 12);

            String title = U.safe(col.path("title").asText(null));
            if (title == null) title = colId;

            String desc = U.safe(col.path("description").asText(null));
            String datasetUrl = findSelfUrl(col, sourceUrl);

            // Dataset id: deterministic hash (stable) (no "stac:" prefix)
            String datasetId = U.sha1Hex(sourceId + "::" + colId + "::" + datasetUrl);

            // Keywords: UUID id, de-dupe by canonical (within STAC)
            List<M.Keyword> keywords = new ArrayList<>();
            Set<String> seenKwCanon = new HashSet<>();

            JsonNode kwNode = col.get("keywords");
            if (kwNode == null) kwNode = col.path("properties").get("keywords");
            if (kwNode != null && kwNode.isArray()) {
                for (JsonNode kw : kwNode) {
                    String raw = U.safe(kw.asText(null));
                    if (raw == null) continue;
                    String canon = U.canonicalizeKeyword(raw);
                    if (canon.isBlank()) continue;
                    if (!seenKwCanon.add(canon)) continue;
                    keywords.add(new M.Keyword(UUID.randomUUID().toString(), canon, raw.trim()));
                }
            }

            // License: MERGE on license_id
            String licenseId = U.safe(col.path("license").asText(null));
            boolean hasLicense = licenseId != null;

            // Organizations (providers): UUID id, de-dupe by canonicalName (within STAC)
            List<M.Organization> orgs = new ArrayList<>();
            Set<String> seenOrgCanon = new HashSet<>();

            JsonNode providers = col.get("providers");
            if (providers != null && providers.isArray()) {
                for (JsonNode p : providers) {
                    String name = U.safe(p.path("name").asText(null));
                    if (name == null) continue;

                    String canon = U.canonicalizeOrgName(name);
                    if (canon.isBlank()) continue;
                    if (!seenOrgCanon.add(canon)) continue;

                    String url = U.safe(p.path("url").asText(null));
                    String roles = joinStrArray(p.path("roles"));

                    M.Organization o = new M.Organization(UUID.randomUUID().toString(), canon, name, name, null, null);
                    o.url = url;
                    o.roles = roles;
                    orgs.add(o);
                }
            }

            // Space + Time from extent
            M.Space space = parseExtentSpace(datasetId, col.path("extent").path("spatial"));
            M.Time time = parseExtentTime(datasetId, col.path("extent").path("temporal"));

            // Resources from links
            List<M.Resource> resources = new ArrayList<>();
            for (JsonNode link : col.path("links")) {
                String rel = link.path("rel").asText("");
                String href = link.path("href").asText("");
                if (href.isBlank()) continue;

                if (!isUsefulRel(rel)) continue;

                String abs = U.absUrl(datasetUrl, href);
                if (abs == null) continue;

                // Resource id: deterministic hash (stable)
                String resId = U.sha1Hex(datasetId + "|" + rel + "|" + abs);
                String rName = U.safe(link.path("title").asText(null));
                String mime = U.safe(link.path("type").asText(null));

                M.Resource r = new M.Resource(
                        resId,
                        null,
                        null,
                        null,
                        rName != null ? rName : rel,
                        datasetId,
                        null,
                        "https",
                        "STAC_LINK",
                        null,
                        abs,
                        rel
                );

                if (mime != null) {
                    M.Format f = M.Format.fromMime(mime);
                    if (f != null) r.format = f; // f has UUID id, canonical for MERGE
                }
                resources.add(r);
            }

            // Optional: attach item count (if items endpoint is available)
            Integer itemCount = null;
            if (fetchItemCounts) {
                String items = findItemsUrl(col, datasetUrl);
                if (items != null) {
                    try {
                        itemCount = stac.tryCountItems(items);
                    } catch (Exception ignored) {}
                }
            }

            M.Envelope env = new M.Envelope();
            env.sourceId = sourceId;
            env.sourceUrl = sourceUrl;

            env.datasetId = datasetId;
            env.datasetName = colId;
            env.datasetTitle = title;
            env.datasetNotes = desc;
            env.datasetType = "STAC_COLLECTION";
            env.datasetVersion = stacVersion;
            env.datasetUrl = datasetUrl;

            env.datasetNumResources = resources.size();
            env.datasetNumTags = keywords.size();

            env.ingestedAt = ingestedAt;

            env.orgs = orgs;
            env.space = space;
            env.time = time;

            env.hasLicense = hasLicense;
            env.licenseId = hasLicense ? licenseId : null;
            env.licenseTitle = null;
            env.licenseUrl = null;

            env.keywords = keywords;
            env.pendingTopics = new ArrayList<>();
            env.resources = resources;

            // Extra (optional) fields stored on Dataset
            env.stacId = colId;
            env.stacVersion = stacVersion;
            env.stacExtensions = stacExt != null && stacExt.isArray() ? stacExt.toString() : null;
            env.itemCount = itemCount;

            return env;
        }

        private static String joinStrArray(JsonNode arr) {
            if (arr == null || !arr.isArray()) return null;
            List<String> xs = new ArrayList<>();
            for (JsonNode n : arr) {
                String s = U.safe(n.asText(null));
                if (s != null) xs.add(s);
            }
            if (xs.isEmpty()) return null;
            return String.join(",", xs);
        }

        private static boolean isUsefulRel(String rel) {
            if (rel == null) return false;
            String r = rel.toLowerCase(Locale.ROOT);
            return r.equals("self") || r.equals("root") || r.equals("parent") || r.equals("items")
                    || r.equals("license") || r.equals("documentation") || r.equals("describedby");
        }

        private static String findSelfUrl(JsonNode col, String baseUrl) {
            for (JsonNode link : col.path("links")) {
                if ("self".equalsIgnoreCase(link.path("rel").asText(""))) {
                    String href = link.path("href").asText("");
                    if (!href.isBlank()) return U.absUrl(baseUrl, href);
                }
            }
            return baseUrl;
        }

        private static String findItemsUrl(JsonNode col, String baseUrl) {
            for (JsonNode link : col.path("links")) {
                if ("items".equalsIgnoreCase(link.path("rel").asText(""))) {
                    String href = link.path("href").asText("");
                    if (!href.isBlank()) return U.absUrl(baseUrl, href);
                }
            }
            return null;
        }

        private static M.Space parseExtentSpace(String datasetId, JsonNode spatial) {
            JsonNode bboxArr = spatial.path("bbox");
            if (bboxArr == null || !bboxArr.isArray() || bboxArr.isEmpty()) return null;

            double west = Double.POSITIVE_INFINITY, south = Double.POSITIVE_INFINITY;
            double east = Double.NEGATIVE_INFINITY, north = Double.NEGATIVE_INFINITY;

            for (JsonNode bb : bboxArr) {
                if (!bb.isArray() || bb.size() < 4) continue;
                Double w = U.parseDouble(bb.get(0).asText());
                Double s = U.parseDouble(bb.get(1).asText());
                Double e = U.parseDouble(bb.get(2).asText());
                Double n = U.parseDouble(bb.get(3).asText());
                if (w == null || s == null || e == null || n == null) continue;
                west = Math.min(west, w);
                south = Math.min(south, s);
                east = Math.max(east, e);
                north = Math.max(north, n);
            }

            if (!Double.isFinite(west) || !Double.isFinite(south) || !Double.isFinite(east) || !Double.isFinite(north)) return null;

            String wkt = U.bboxToWkt(west, south, east, north);
            double clon = (west + east) / 2.0;
            double clat = (south + north) / 2.0;

            M.Space sp = new M.Space();
            sp.datasetId = datasetId;
            sp.west = west;
            sp.south = south;
            sp.east = east;
            sp.north = north;
            sp.spatialType = "bbox";
            sp.srid = 4326;
            sp.wktPolygon = wkt;
            sp.centroidLon = clon;
            sp.centroidLat = clat;
            return sp;
        }

        /**
         * Time normalization must match your original StageAIngest.java:
         * beginTs/endTs are strings like "yyyyMMdd HH:mm:ss".
         */
        private static M.Time parseExtentTime(String datasetId, JsonNode temporal) {
            JsonNode intervals = temporal.path("interval");
            if (intervals == null || !intervals.isArray() || intervals.isEmpty()) return null;

            String minBeginTs = null;
            String maxEndTs = null;
            String minBeginRaw = null;
            String maxEndRaw = null;

            for (JsonNode itv : intervals) {
                if (!itv.isArray() || itv.size() < 2) continue;

                String bRaw = itv.get(0).isNull() ? null : itv.get(0).asText(null);
                String eRaw = itv.get(1).isNull() ? null : itv.get(1).asText(null);

                String bTs = U.normalizeBegin(bRaw);
                String eTs = U.normalizeEnd(eRaw);

                if (minBeginTs == null || bTs.compareTo(minBeginTs) < 0) {
                    minBeginTs = bTs;
                    minBeginRaw = bRaw;
                }
                if (maxEndTs == null || eTs.compareTo(maxEndTs) > 0) {
                    maxEndTs = eTs;
                    maxEndRaw = eRaw;
                }
            }

            if (minBeginTs == null && maxEndTs == null) return null;

            M.Time t = new M.Time();
            t.datasetId = datasetId;
            t.beginRaw = minBeginRaw;
            t.endRaw = maxEndRaw;
            t.beginTs = minBeginTs;
            t.endTs = maxEndTs;
            return t;
        }
    }

    // ============================================================
    // LLM topic extraction
    // ============================================================

    static class TopicExtractor {

        static void extractInParallel(List<M.Envelope> envelopes,
                                      int batchSize,
                                      Llm llm,
                                      ExecutorService pool,
                                      int maxInFlightBatches,
                                      AtomicLong llmTopicCalls) throws Exception {

            List<PendingWork> works = new ArrayList<>();
            for (M.Envelope env : envelopes) {
                String mergedText = mergedTextForLlm(env);
                if (mergedText == null) continue;
                works.add(new PendingWork(env.datasetId, mergedText));
            }

            List<List<PendingWork>> batches = new ArrayList<>();
            for (int i = 0; i < works.size(); i += batchSize) {
                batches.add(works.subList(i, Math.min(works.size(), i + batchSize)));
            }

            Semaphore inFlight = new Semaphore(maxInFlightBatches);
            List<Future<Map<String, List<M.PendingTopic>>>> futures = new ArrayList<>();

            for (List<PendingWork> b : batches) {
                inFlight.acquire();
                futures.add(pool.submit(() -> {
                    try {
                        return extractBatch(llm, b, llmTopicCalls);
                    } finally {
                        inFlight.release();
                    }
                }));
            }

            Map<String, List<M.PendingTopic>> byDataset = new HashMap<>();
            for (Future<Map<String, List<M.PendingTopic>>> f : futures) {
                Map<String, List<M.PendingTopic>> part = f.get();
                byDataset.putAll(part);
            }

            for (M.Envelope env : envelopes) {
                List<M.PendingTopic> add = byDataset.get(env.datasetId);
                if (add == null || add.isEmpty()) continue;

                Set<String> canon = new HashSet<>();
                for (M.PendingTopic pt : env.pendingTopics) canon.add(pt.canonical);

                for (M.PendingTopic pt : add) {
                    if (pt == null || pt.canonical == null || pt.canonical.isBlank()) continue;
                    if (canon.add(pt.canonical)) env.pendingTopics.add(pt);
                }
            }
        }

        private static String mergedTextForLlm(M.Envelope env) {
            StringBuilder sb = new StringBuilder();
            if (env.datasetTitle != null) sb.append(env.datasetTitle).append("\n");
            if (env.datasetNotes != null) sb.append(env.datasetNotes).append("\n");

            if (env.keywords != null && !env.keywords.isEmpty()) {
                sb.append("Keywords: ");
                for (int i = 0; i < Math.min(env.keywords.size(), 24); i++) {
                    sb.append(env.keywords.get(i).name);
                    if (i < Math.min(env.keywords.size(), 24) - 1) sb.append(", ");
                }
                sb.append("\n");
            }

            if (env.orgs != null && !env.orgs.isEmpty()) {
                sb.append("Providers: ");
                for (int i = 0; i < Math.min(env.orgs.size(), 10); i++) {
                    sb.append(env.orgs.get(i).title);
                    if (i < Math.min(env.orgs.size(), 10) - 1) sb.append(", ");
                }
                sb.append("\n");
            }

            String s = sb.toString().trim();
            if (s.length() < 20) return null;
            if (s.length() > 2200) s = s.substring(0, 2200);
            return s;
        }

        private static Map<String, List<M.PendingTopic>> extractBatch(Llm llm,
                                                                      List<PendingWork> works,
                                                                      AtomicLong llmTopicCalls) {

            ObjectMapper om = new ObjectMapper();

            try {
                ArrayNode arr = om.createArrayNode();
                for (PendingWork w : works) {
                    ObjectNode o = arr.addObject();
                    o.put("id", w.datasetId);
                    o.put("text", w.text);
                }

                String prompt = """
                        You extract topical concepts for geospatial dataset discovery.
                        For each item, return short noun-phrase topics (2-5 words each).
                        Avoid places, dates, agencies, file formats, licenses, and generic words like "dataset".

                        Output MUST be valid JSON ONLY.
                        Schema:
                        {
                          "results":[
                            {"id":"...","topics":["t1","t2","..."]},
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
                    String id = item.path("id").asText("");
                    if (id.isBlank()) continue;

                    LinkedHashMap<String, String> canon2name = new LinkedHashMap<>();
                    for (JsonNode t : item.path("topics")) {
                        String raw = t.asText("").trim();
                        if (raw.isEmpty()) continue;
                        String canon = U.canonicalizeTopic(raw);
                        if (canon.isBlank()) continue;
                        canon2name.putIfAbsent(canon, raw);
                    }

                    List<M.PendingTopic> pts = new ArrayList<>();
                    for (var e : canon2name.entrySet()) {
                        pts.add(new M.PendingTopic(e.getKey(), e.getValue()));
                    }
                    out.put(id, pts);
                }

                for (PendingWork w : works) out.putIfAbsent(w.datasetId, List.of());
                return out;

            } catch (Exception e) {
                Map<String, List<M.PendingTopic>> out = new HashMap<>();
                for (PendingWork w : works) out.put(w.datasetId, List.of());
                return out;
            }
        }

        static class PendingWork {
            final String datasetId;
            final String text;

            PendingWork(String datasetId, String text) {
                this.datasetId = datasetId;
                this.text = text;
            }
        }
    }

    // ============================================================
    // Neo4j writer
    // ============================================================

    static class Neo4jWriter {
        private final Driver driver;
        private final String database;

        Neo4jWriter(Driver driver, String database) {
            this.driver = driver;
            this.database = database;
        }

        void ensureSchema() {
            // Keep the same core constraints you already use.
            // IMPORTANT: Do NOT add "REQUIRE canonical IS UNIQUE" constraints here,
            // because your existing Data.gov nodes may not have canonical properties.
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
                    "CREATE INDEX pending_status IF NOT EXISTS FOR (p:PendingTopic) ON (p.status)",

                    // Non-unique indexes to speed MERGE by canonical (safe with existing nodes)
                    "CREATE INDEX kw_canonical IF NOT EXISTS FOR (k:Keyword) ON (k.canonical)",
                    "CREATE INDEX org_canonical IF NOT EXISTS FOR (o:Organization) ON (o.canonical)",
                    "CREATE INDEX format_canonical IF NOT EXISTS FOR (f:Format) ON (f.canonical)"
            );

            try (Session session = driver.session(SessionConfig.forDatabase(database))) {
                for (String c : ddl) session.run(c).consume();
            }
        }

        void writeBatch(List<M.Envelope> batch) {
            if (batch == null || batch.isEmpty()) return;

            // NOTE:
            // - Keyword/Organization/Format are MERGE'd by canonical for STAC-only dedupe;
            //   id is set on CREATE only (UUID).
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
                        d.ingested_at = row.ingestedAt,
                        d.stac_id = row.stacId,
                        d.stac_version = row.stacVersion,
                        d.stac_extensions = row.stacExtensions,
                        d.stac_item_count = row.itemCount
                    MERGE (src)-[:PROVIDES]->(d)

                    FOREACH (org IN row.orgs |
                      MERGE (o:Organization {canonical: org.canonical})
                      ON CREATE SET o.id = org.id
                      SET o.title = org.title,
                          o.name = org.name,
                          o.description = org.description,
                          o.image_url = org.image_url,
                          o.url = org.url,
                          o.roles = org.roles,
                          o.canonical = org.canonical
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

                    FOREACH (_ IN CASE WHEN row.hasLicense = false THEN [] ELSE [1] END |
                      MERGE (l:License {license_id: row.licenseId})
                      SET l.license_title = coalesce(row.licenseTitle, l.license_title),
                          l.license_url   = coalesce(row.licenseUrl,   l.license_url)
                      MERGE (d)-[:HAS_LICENSE]->(l)
                    )

                    FOREACH (kw IN row.keywords |
                      MERGE (k:Keyword {canonical: kw.canonical})
                      ON CREATE SET k.id = kw.id
                      SET k.name = kw.name,
                          k.canonical = kw.canonical
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

                      FOREACH (_ IN CASE WHEN r.format IS NULL THEN [] ELSE [1] END |
                        MERGE (f:Format {canonical: r.format.canonical})
                        ON CREATE SET f.id = r.format.id
                        SET f.name = r.format.name,
                            f.canonical = r.format.canonical
                        MERGE (res)-[:HAS_FORMAT]->(f)
                      )
                    )
                    """;

            List<Map<String, Object>> rows = new ArrayList<>(batch.size());
            for (M.Envelope e : batch) rows.add(e.toMap());

            try (Session session = driver.session(SessionConfig.forDatabase(database))) {
                session.executeWrite(tx -> tx.run(cypher, parameters("rows", rows)).consume());
            }
        }
    }

    // ============================================================
    // STAC HTTP client + cache
    // ============================================================

    static class StacClient {
        private final HttpClient http;
        private final ObjectMapper om;
        private final Path cacheDir;

        StacClient(HttpClient http, ObjectMapper om, Path cacheDirOrNull) {
            this.http = http;
            this.om = om;
            this.cacheDir = cacheDirOrNull;
        }

        JsonNode getJson(String url) throws IOException, InterruptedException {
            String norm = U.normalizeUrl(url);

            if (cacheDir != null) {
                Path fp = cacheDir.resolve(U.sha1Hex(norm) + ".json");
                if (Files.exists(fp)) {
                    String s = Files.readString(fp, StandardCharsets.UTF_8);
                    return om.readTree(s);
                }
                String s = httpGet(norm);
                Files.writeString(fp, s, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                return om.readTree(s);
            } else {
                return om.readTree(httpGet(norm));
            }
        }

        String httpGet(String url) throws IOException, InterruptedException {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IOException("HTTP " + resp.statusCode() + " for " + url + " body=" + clip(resp.body(), 200));
            }
            return resp.body();
        }

        private static String clip(String s, int n) {
            if (s == null) return "";
            return s.length() <= n ? s : s.substring(0, n);
        }

        /**
         * Best-effort item counting. Returns null if unknown.
         */
        Integer tryCountItems(String itemsUrl) throws IOException, InterruptedException {
            JsonNode node = getJson(itemsUrl);

            JsonNode matched = node.path("context").path("matched");
            if (matched.isInt() || matched.isLong()) return matched.asInt();

            JsonNode nm = node.get("numberMatched");
            if (nm != null && (nm.isInt() || nm.isLong())) return nm.asInt();

            JsonNode feats = node.get("features");
            if (feats != null && feats.isArray()) return feats.size();

            return null;
        }
    }

    // ============================================================
    // LLM wrapper (OpenAI Responses API)
    // ============================================================

    static class Llm {
        private final OpenAIClient client;
        private final String model;

        Llm(String apiKey, String modelName) {
            this.client = OpenAIOkHttpClient.builder()
                    .apiKey(apiKey)
                    .build();
            this.model = modelName;
        }

        String askPlain(String prompt) {
            int maxAttempts = 5;
            long sleepMs = 800;

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    ResponseCreateParams params = ResponseCreateParams.builder()
                            .model(ChatModel.of(model))
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
                    if (attempt == maxAttempts) throw new RuntimeException("LLM call failed after retries.", e);
                    try { Thread.sleep(sleepMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    sleepMs = Math.min(10_000, (long) (sleepMs * 1.6));
                }
            }
            throw new RuntimeException("LLM call failed.");
        }
    }

    // ============================================================
    // Data model
    // ============================================================

    static class M {

        static class Envelope {
            String sourceId;
            String sourceUrl;

            String datasetId;
            String datasetName;
            String datasetNotes;
            Integer datasetNumResources;
            Integer datasetNumTags;
            String datasetTitle;
            String datasetType;
            String datasetVersion;
            String datasetUrl;
            String ingestedAt;

            String stacId;
            String stacVersion;
            String stacExtensions;
            Integer itemCount;

            List<Organization> orgs = new ArrayList<>();

            Space space;
            Time time;

            boolean hasLicense;
            String licenseId;
            String licenseTitle;
            String licenseUrl;

            List<Keyword> keywords = new ArrayList<>();
            List<PendingTopic> pendingTopics = new ArrayList<>();
            List<Resource> resources = new ArrayList<>();

            Map<String, Object> toMap() {
                Map<String, Object> m = new HashMap<>();

                m.put("sourceId", sourceId);
                m.put("sourceUrl", sourceUrl);

                m.put("datasetId", datasetId);
                m.put("datasetName", datasetName);
                m.put("datasetNotes", datasetNotes);
                m.put("datasetNumResources", datasetNumResources);
                m.put("datasetNumTags", datasetNumTags);
                m.put("datasetTitle", datasetTitle);
                m.put("datasetType", datasetType);
                m.put("datasetVersion", datasetVersion);
                m.put("datasetUrl", datasetUrl);
                m.put("ingestedAt", ingestedAt);

                m.put("stacId", stacId);
                m.put("stacVersion", stacVersion);
                m.put("stacExtensions", stacExtensions);
                m.put("itemCount", itemCount);

                List<Map<String, Object>> orgMaps = new ArrayList<>();
                for (Organization o : orgs) orgMaps.add(o.toMap());
                m.put("orgs", orgMaps);

                boolean hasSpace = space != null;
                m.put("hasSpace", hasSpace);
                if (hasSpace) {
                    m.put("west", space.west);
                    m.put("south", space.south);
                    m.put("east", space.east);
                    m.put("north", space.north);
                    m.put("spatialType", space.spatialType);
                    m.put("srid", space.srid);
                    m.put("wktPolygon", space.wktPolygon);
                    m.put("centroidLon", space.centroidLon);
                    m.put("centroidLat", space.centroidLat);
                }

                boolean hasTime = time != null;
                m.put("hasTime", hasTime);
                if (hasTime) {
                    m.put("beginRaw", time.beginRaw);
                    m.put("endRaw", time.endRaw);
                    m.put("beginTs", time.beginTs);
                    m.put("endTs", time.endTs);
                }

                m.put("hasLicense", hasLicense);
                m.put("licenseId", licenseId);
                m.put("licenseTitle", licenseTitle);
                m.put("licenseUrl", licenseUrl);

                List<Map<String, Object>> kws = new ArrayList<>();
                for (Keyword k : keywords) kws.add(k.toMap());
                m.put("keywords", kws);

                List<Map<String, Object>> pts = new ArrayList<>();
                for (PendingTopic p : pendingTopics) pts.add(p.toMap());
                m.put("pendingTopics", pts);

                List<Map<String, Object>> rs = new ArrayList<>();
                for (Resource r : resources) rs.add(r.toMap());
                m.put("resources", rs);

                return m;
            }
        }

        static class Organization {
            String id;
            String canonical;
            String title;
            String name;
            String description;
            String image_url;

            String url;
            String roles;

            Organization(String id, String canonical, String title, String name, String description, String image_url) {
                this.id = id;
                this.canonical = canonical;
                this.title = title;
                this.name = name;
                this.description = description;
                this.image_url = image_url;
            }

            Map<String, Object> toMap() {
                Map<String, Object> m = new HashMap<>();
                m.put("id", id);
                m.put("canonical", canonical);
                m.put("title", title);
                m.put("name", name);
                m.put("description", description);
                m.put("image_url", image_url);
                m.put("url", url);
                m.put("roles", roles);
                return m;
            }
        }

        static class Space {
            String datasetId;
            Double west, south, east, north;
            String spatialType;
            Integer srid;
            String wktPolygon;
            Double centroidLon, centroidLat;
        }

        static class Time {
            String datasetId;
            String beginRaw;
            String endRaw;
            String beginTs; // yyyyMMdd HH:mm:ss
            String endTs;   // yyyyMMdd HH:mm:ss
        }

        static class Keyword {
            String id;
            String canonical;
            String name;

            Keyword(String id, String canonical, String name) {
                this.id = id;
                this.canonical = canonical;
                this.name = name;
            }

            Map<String, Object> toMap() {
                Map<String, Object> m = new HashMap<>();
                m.put("id", id);
                m.put("canonical", canonical);
                m.put("name", name);
                return m;
            }
        }

        static class PendingTopic {
            String canonical;
            String name;

            PendingTopic(String canonical, String name) {
                this.canonical = canonical;
                this.name = name;
            }

            Map<String, Object> toMap() {
                Map<String, Object> m = new HashMap<>();
                m.put("canonical", canonical);
                m.put("name", name);
                return m;
            }
        }

        static class Resource {
            String id;
            String created;
            String description;
            String last_modified;
            String name;
            String package_id;
            String resource_locator_function;
            String resource_locator_protocol;
            String resource_type;
            Long size;
            String url;
            String url_type;

            Format format;

            Resource(String id,
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
                     String url_type) {
                this.id = id;
                this.created = created;
                this.description = description;
                this.last_modified = last_modified;
                this.name = name;
                this.package_id = package_id;
                this.resource_locator_function = resource_locator_function;
                this.resource_locator_protocol = resource_locator_protocol;
                this.resource_type = resource_type;
                this.size = size;
                this.url = url;
                this.url_type = url_type;
            }

            Map<String, Object> toMap() {
                Map<String, Object> m = new HashMap<>();
                m.put("id", id);
                m.put("created", created);
                m.put("description", description);
                m.put("last_modified", last_modified);
                m.put("name", name);
                m.put("package_id", package_id);
                m.put("resource_locator_function", resource_locator_function);
                m.put("resource_locator_protocol", resource_locator_protocol);
                m.put("resource_type", resource_type);
                m.put("size", size);
                m.put("url", url);
                m.put("url_type", url_type);
                m.put("format", format == null ? null : format.toMap());
                return m;
            }
        }

        static class Format {
            String id;
            String name;
            String canonical;

            Format(String id, String name, String canonical) {
                this.id = id;
                this.name = name;
                this.canonical = canonical;
            }

            Map<String, Object> toMap() {
                Map<String, Object> m = new HashMap<>();
                m.put("id", id);
                m.put("name", name);
                m.put("canonical", canonical);
                return m;
            }

            static Format fromMime(String mime) {
                if (mime == null) return null;
                String mm = mime.toLowerCase(Locale.ROOT).trim();

                if (mm.contains("application/vnd.cog") || mm.contains("cog")) return fromCanonical("cog", "COG");
                if (mm.contains("geotiff") || mm.contains("image/tiff")) return fromCanonical("geotiff", "GeoTIFF");
                if (mm.contains("netcdf")) return fromCanonical("netcdf", "NetCDF");
                if (mm.contains("hdf")) return fromCanonical("hdf", "HDF");
                if (mm.contains("geo+json") || mm.contains("geojson")) return fromCanonical("geojson", "GeoJSON");
                if (mm.contains("application/json") || mm.contains("json")) return fromCanonical("json", "JSON");
                if (mm.contains("image/jpeg") || mm.contains("jpeg")) return fromCanonical("jpeg", "JPEG");
                if (mm.contains("image/png") || mm.contains("png")) return fromCanonical("png", "PNG");
                if (mm.contains("text/csv") || mm.contains("csv")) return fromCanonical("csv", "CSV");

                String[] parts = mm.split(";");
                String base = parts[0].trim();
                int slash = base.indexOf('/');
                if (slash >= 0 && slash < base.length() - 1) {
                    String sub = base.substring(slash + 1).trim();
                    sub = sub.replaceAll("[^a-z0-9]+", "");
                    if (!sub.isBlank()) return fromCanonical(sub, sub.toUpperCase(Locale.ROOT));
                }
                return null;
            }

            static Format fromCanonical(String canonical, String displayName) {
                String canon = canonical.toLowerCase(Locale.ROOT).trim();
                if (canon.isBlank()) return null;
                return new Format(UUID.randomUUID().toString(), displayName, canon);
            }
        }
    }

    // ============================================================
    // Utilities
    // ============================================================

    static class U {

        static String safe(String s) {
            if (s == null) return null;
            String t = s.trim();
            return t.isEmpty() ? null : t;
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

        static String canonicalizeKeyword(String kw) {
            return canonicalizeTopic(kw);
        }

        static String canonicalizeOrgName(String orgName) {
            if (orgName == null) return "";
            String s = orgName.toLowerCase(Locale.ROOT).trim();
            s = s.replaceAll("[^a-z0-9\\s&\\-_/]", " ");
            s = s.replaceAll("\\s+", " ").trim();
            return s;
        }

        // ===== Time normalization (Data.gov-compatible) =====
        static String normalizeBegin(String raw) { return normalizeTemporal(raw, true); }
        static String normalizeEnd(String raw) { return normalizeTemporal(raw, false); }

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

            if (s.matches("^\\d{6}$")) {
                int y = Integer.parseInt(s.substring(0, 4));
                int m = Integer.parseInt(s.substring(4, 6));
                YearMonth ym = YearMonth.of(y, Math.min(12, Math.max(1, m)));
                if (isBegin) {
                    return String.format(Locale.ROOT, "%04d%02d01 00:00:00", y, ym.getMonthValue());
                } else {
                    int d = ym.lengthOfMonth();
                    return String.format(Locale.ROOT, "%04d%02d%02d 00:00:00", y, ym.getMonthValue(), d);
                }
            }

            if (s.matches("^\\d{8}$")) {
                return s + " 00:00:00";
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
                LocalDateTime ldt = odt.toInstant().atZone(ZoneOffset.UTC).toLocalDateTime();
                return ldt.format(DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss"));
            } catch (DateTimeParseException ignored) {}

            return isBegin ? "00010101 00:00:00" : "99991231 00:00:00";
        }

        static String sha1Hex(String s) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                byte[] bytes = md.digest(s.getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (byte b : bytes) sb.append(String.format("%02x", b));
                return sb.toString();
            } catch (Exception e) {
                return Integer.toHexString(Objects.hashCode(s));
            }
        }

        static String normalizeUrl(String url) {
            if (url == null) return null;
            String u = url.trim();
            if (u.endsWith("/") && u.length() > 8) u = u.substring(0, u.length() - 1);
            return u;
        }

        static String joinUrl(String base, String path) {
            if (base == null) return null;
            if (path == null) return normalizeUrl(base);
            String b = base.endsWith("/") ? base : base + "/";
            return normalizeUrl(URI.create(b).resolve(path).toString());
        }

        static String absUrl(String baseUrl, String href) {
            if (href == null || href.isBlank()) return null;
            try {
                URI h = URI.create(href.trim());
                if (h.isAbsolute()) return normalizeUrl(h.toString());
                if (baseUrl == null || baseUrl.isBlank()) return normalizeUrl(href.trim());
                URI b = URI.create(baseUrl.trim());
                return normalizeUrl(b.resolve(h).toString());
            } catch (Exception e) {
                return null;
            }
        }

        static String bboxToWkt(double west, double south, double east, double north) {
            return "POLYGON((" +
                    west + " " + south + "," +
                    east + " " + south + "," +
                    east + " " + north + "," +
                    west + " " + north + "," +
                    west + " " + south +
                    "))";
        }
    }
}

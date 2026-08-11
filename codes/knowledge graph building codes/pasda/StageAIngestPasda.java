
package edu.psu.giscience.graphbuilding.pasda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import org.neo4j.driver.*;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.neo4j.driver.Values.parameters;

/**
 * Stage A (PASDA): Ingest PASDA metadata (FGDC CSDGM rendered as HTML) into Neo4j using the SAME core ontology
 * used by your Data.gov and STAC pipelines:
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
 * PASDA scraping strategy:
 *   1) For each provider/originator:
 *      /uci/SearchResults.aspx?originator=<URLEncoded provider>
 *      -> extract dataset IDs from DataSummary.aspx?dataset=<id>
 *   2) For each dataset ID:
 *      /uci/DataSummary.aspx?dataset=<id>
 *      -> extract resource links + FullMetadataDisplay.aspx?file=<...xml>
 *   3) Parse /uci/FullMetadataDisplay.aspx?file=<...xml>
 *      -> Originator, Publication_Date, Abstract, Theme_Keyword, Bounding_Coordinates, Calendar_Date, Constraints, etc.
 *
 * Notes:
 * - PASDA's FGDC HTML is not strict XML/HTML5; we use robust "strip tags -> parse text" heuristics.
 * - IDs are deterministic to make re-runs idempotent:
 *     dataset.id  = "pasda:" + datasetNumericId
 *     org.id      = "org:" + sha1(canonicalName)
 *     keyword.id  = "kw:"  + sha1(canonicalKeyword)
 *     format.id   = "format:" + canonical
 *     resource.id = sha1(normalizedUrl)
 *     license.license_id = sha1(access_constraints + "|" + use_constraints)
 *
 * Recommended pipeline order:
 *   1) Run this file (StageAIngestPasda)
 *   2) Run StageAEmbedAllEntities.java
 *   3) Run StageB.java
 */
public class StageAIngestPasda {

    // ============================================================
    // Entry
    // ============================================================
    public static void main(String[] args) throws Exception {

        // ---------------------------
        // PASDA inputs
        // ---------------------------
        String PASDA_BASE = "https://www.pasda.psu.edu";
        String PASDA_UCI  = PASDA_BASE + "/uci";

        // Provider list (originator values). You can replace this list with your own.
        List<String> PROVIDERS = List.of(
                "Adams County",
                "Allegheny College",
                "Allegheny County",
                "Alliance for Aquatic Resource Monitoring",
                "American Forests",
                "Appalachian Mountain Club",
                "Appalachian Trail Conference",
                "Bat Conservation International",
                "Bedford County",
                "Berks County",
                "Blair County",
                "Bradford County",
                "Brodhead Creek Regional Authority",
                "Bucks County",
                "Butler County",
                "Cambria County",
                "Carbon County",
                "Carnegie Mellon University",
                "Centre County",
                "Chesapeake Bay Program",
                "Chester County",
                "Choptank River Heritage",
                "City of Philadelphia",
                "Columbia County",
                "Conservation Biology Institute",
                "Crawford County",
                "Cumberland County",
                "DCNR PAMAP Program",
                "Delaware County",
                "Delaware River Basin Commission DRBC",
                "Delaware Valley Regional Planning Commission",
                "Dauphin County",
                "Eastern Brook Trout Joint Venture",
                "Eastern Pennsylvania Coalition for Abandoned Mine Reclamation",
                "Erie County",
                "Erie National Wildlife Refuge",
                "Franklin County",
                "Federal Emergency Management Agency",
                "Fulton County",
                "Greener Planning",
                "Heritage Conservancy",
                "Homeland Infrastructure Foundation Level Data HIFLD",
                "Huntingdon County",
                "Indiana County",
                "Juniata County",
                "Keep Pennsylvania Beautiful",
                "Lancaster County",
                "Lehigh County",
                "Lehigh Valley Planning Commission",
                "Luzerne County",
                "Lycoming County",
                "Mercer County",
                "Mifflin County",
                "Monroe County",
                "Montgomery County",
                "Montour County",
                "Municipality of Murrysville",
                "National Aeronautics and Space Administration NASA",
                "National Geodetic Survey",
                "National Park Service",
                "National Renewable Energy Laboratory NREL",
                "National Weather Service NOAA NWS",
                "Natural Heritage Inventory",
                "Natural Lands Trust",
                "Open Space Institute",
                "Partnership for the Delaware Estuary",
                "Pennsylvania Department of Agriculture",
                "Pennsylvania Department of Conservation and Natural Resources",
                "Pennsylvania Department of Environmental Protection",
                "Pennsylvania Department of Health",
                "Pennsylvania Department of Military Veterans Affairs",
                "Pennsylvania Department of Transportation",
                "Pennsylvania Emergency Management Agency",
                "Pennsylvania Fish and Boat Commission",
                "Pennsylvania Game Commission",
                "Pennsylvania Historical and Museum Commission",
                "Pennsylvania State Police",
                "Pennsylvania Turnpike Commission",
                "Perry County",
                "Snyder County",
                "Somerset County",
                "Southeastern Pennsylvania Transportation Authority",
                "Southwestern Pennsylvania Commission",
                "Southwestern Pennsylvania Regional Planning Commission SPRPC",
                "Susquehanna River Basin Commission SRBC",
                "The Conservation Fund",
                "The Pennsylvania State University",
                "U S Census Bureau",
                "U S Department of Agriculture",
                "U S Department of Commerce",
                "U S Department of Justice",
                "U S Environmental Protection Agency",
                "U S Fish and Wildlife Service",
                "U S Geological Survey",
                "Union County",
                "United States Army Corps of Engineers USACE",
                "University of Vermont Spatial Analysis Laboratory",
                "USGS Patuxent Wildlife Research Center",
                "Venango County",
                "Virginia Department of Environmental Quality",
                "Warren County",
                "Washington County",
                "WeConservePA",
                "Western Pennsylvania Conservancy",
                "Wyoming County",
                "York County",
                "York County Planning Commission"
        );

        // Safety caps (to avoid accidental huge runs)
        int MAX_DATASETS_PER_PROVIDER = 50_000;
        int MAX_DATASETS_GLOBAL = 500_000;

        // Cache raw HTML fetches to disk (highly recommended)
        boolean ENABLE_HTTP_CACHE = true;
        Path CACHE_DIR = Paths.get("pasda_cache");

        // Scrape parallelism (polite but fast)
        int DISCOVERY_CONCURRENCY = 12; // provider-level SearchResults fetch
        int DATASET_FETCH_CONCURRENCY = 32; // DataSummary + FullMetadataDisplay fetch

        // ---------------------------
        // Topic extraction options
        // ---------------------------
        // If OPENAI_API_KEY is blank, LLM extraction is skipped.
        String OPENAI_API_KEY = ""; // leave blank / REPLACE_ME to disable
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
        // Setup
        // ---------------------------
        if (ENABLE_HTTP_CACHE) Files.createDirectories(CACHE_DIR);

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        PasdaClient pasda = new PasdaClient(http, ENABLE_HTTP_CACHE ? CACHE_DIR : null);

        String ingestedAt = Instant.now().toString();

        // Keep Source.id stable (like "datagov"/"stac"); no hash.
        String sourceId = "pasda";
        String sourceUrl = PASDA_BASE;

        // ---------------------------
        // 1) Discover dataset IDs per provider
        // ---------------------------
        System.out.println("[PASDA] Providers: " + PROVIDERS.size());

        ExecutorService providerPool = Executors.newFixedThreadPool(DISCOVERY_CONCURRENCY);
        List<Future<ProviderDiscovery>> providerFutures = new ArrayList<>();

        for (String provider : PROVIDERS) {
            providerFutures.add(providerPool.submit(() -> {
                List<Integer> ids = PasdaDiscovery.discoverDatasetIdsForProvider(
                        PASDA_UCI, provider, pasda, MAX_DATASETS_PER_PROVIDER
                );
                return new ProviderDiscovery(provider, ids);
            }));
        }

        LinkedHashMap<Integer, String> datasetIdToProvider = new LinkedHashMap<>();
        int providersDone = 0;
        for (Future<ProviderDiscovery> f : providerFutures) {
            ProviderDiscovery pd = f.get();
            providersDone++;
            for (int id : pd.datasetIds) {
                datasetIdToProvider.putIfAbsent(id, pd.provider);
                if (datasetIdToProvider.size() >= MAX_DATASETS_GLOBAL) break;
            }
            if (providersDone % 10 == 0 || providersDone == PROVIDERS.size()) {
                System.out.println("[PASDA] Discovery " + providersDone + "/" + PROVIDERS.size()
                        + " providers; unique datasets=" + datasetIdToProvider.size());
            }
            if (datasetIdToProvider.size() >= MAX_DATASETS_GLOBAL) break;
        }
        providerPool.shutdownNow();

        List<Integer> allDatasetIds = new ArrayList<>(datasetIdToProvider.keySet());
        System.out.println("[PASDA] Total unique datasets to fetch: " + allDatasetIds.size());

        // ---------------------------
        // 2) Fetch + map datasets
        // ---------------------------
        ExecutorService fetchPool = Executors.newFixedThreadPool(DATASET_FETCH_CONCURRENCY);
        CompletionService<M.Envelope> ecs = new ExecutorCompletionService<>(fetchPool);

        AtomicLong submitted = new AtomicLong(0);
        for (int datasetNumId : allDatasetIds) {
            String providerGuess = datasetIdToProvider.getOrDefault(datasetNumId, "");
            ecs.submit(() -> PasdaMapper.mapDataset(
                    PASDA_UCI,
                    datasetNumId,
                    providerGuess,
                    pasda,
                    sourceId,
                    sourceUrl,
                    ingestedAt
            ));
            submitted.incrementAndGet();
        }

        List<M.Envelope> envelopes = new ArrayList<>(allDatasetIds.size());
        long done = 0;
        long nulls = 0;

        for (int i = 0; i < allDatasetIds.size(); i++) {
            Future<M.Envelope> fut = ecs.take();
            M.Envelope env = null;
            try {
                env = fut.get();
            } catch (Exception e) {
                nulls++;
            }
            if (env != null) envelopes.add(env);
            else nulls++;
            done++;

            if (done % 200 == 0 || done == allDatasetIds.size()) {
                System.out.println("[PASDA] Fetched " + done + "/" + allDatasetIds.size()
                        + " ok=" + envelopes.size() + " null=" + nulls);
            }
        }

        fetchPool.shutdownNow();
        System.out.println("[PASDA] Mapping done. Envelopes=" + envelopes.size());

        // ---------------------------
        // 3) Seed pending topics from keywords (optional)
        // ---------------------------
        if (SEED_PENDING_TOPICS_FROM_KEYWORDS) {
            for (M.Envelope env : envelopes) {
                if (env.keywords == null || env.keywords.isEmpty()) continue;

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
        }

        // ---------------------------
        // 4) Optional LLM topic extraction in batches
        // ---------------------------
        Llm llm = null;
        ExecutorService llmPool = null;
        AtomicLong llmTopicCalls = new AtomicLong(0);

        if (ENABLE_LLM_TOPIC_EXTRACT) {
            System.out.println("[PASDA] LLM topic extraction enabled. model=" + OPENAI_MODEL);
            llm = new Llm(OPENAI_API_KEY, OPENAI_MODEL);
            llmPool = Executors.newFixedThreadPool(LLM_CONCURRENCY);

            TopicExtractor.extractInParallel(
                    envelopes,
                    TOPIC_EXTRACT_LLM_BATCH,
                    llm,
                    llmPool,
                    MAX_IN_FLIGHT_LLM_BATCHES,
                    llmTopicCalls
            );

            System.out.println("[PASDA] LLM topic batches called: " + llmTopicCalls.get());
        } else {
            System.out.println("[PASDA] LLM topic extraction skipped (no API key).");
        }

        // ---------------------------
        // 5) Write to Neo4j
        // ---------------------------
        try (Driver driver = GraphDatabase.driver(NEO4J_URI, AuthTokens.basic(NEO4J_USER, NEO4J_PASS))) {
            Neo4jWriter writer = new Neo4jWriter(driver, NEO4J_DB);
            writer.ensureSchema();

            int total = envelopes.size();
            int wrote = 0;

            List<M.Envelope> batch = new ArrayList<>(INGEST_BATCH_SIZE);
            for (M.Envelope env : envelopes) {
                batch.add(env);
                if (batch.size() >= INGEST_BATCH_SIZE) {
                    writer.writeBatch(batch);
                    wrote += batch.size();
                    batch.clear();
                    if (wrote % 500 == 0) System.out.println("[PASDA] Ingested " + wrote + "/" + total);
                }
            }
            if (!batch.isEmpty()) {
                writer.writeBatch(batch);
                wrote += batch.size();
            }

            System.out.println("[PASDA] Done. Ingested datasets=" + wrote);
        } finally {
            if (llmPool != null) llmPool.shutdownNow();
        }
    }

    // ============================================================
    // Provider discovery
    // ============================================================

    record ProviderDiscovery(String provider, List<Integer> datasetIds) {}

    static class PasdaDiscovery {

        private static final Pattern DATASET_ID = Pattern.compile("DataSummary\\.aspx\\?dataset=(\\d+)", Pattern.CASE_INSENSITIVE);

        static List<Integer> discoverDatasetIdsForProvider(
                String pasdaUciBase,
                String provider,
                PasdaClient client,
                int maxPerProvider
        ) throws Exception {
            String q = URLEncoder.encode(provider, StandardCharsets.UTF_8); // spaces -> +
            String url = pasdaUciBase + "/SearchResults.aspx?originator=" + q;

            String html = client.getHtml(url);
            if (html == null || html.isBlank()) return List.of();

            // Pull dataset IDs
            LinkedHashSet<Integer> ids = new LinkedHashSet<>();
            Matcher m = DATASET_ID.matcher(html);
            while (m.find()) {
                try {
                    ids.add(Integer.parseInt(m.group(1)));
                    if (ids.size() >= maxPerProvider) break;
                } catch (Exception ignored) {}
            }
            return new ArrayList<>(ids);
        }
    }

    // ============================================================
    // PASDA mapping (DataSummary + FullMetadataDisplay)
    // ============================================================

    static class PasdaMapper {

        private static final Pattern HREF = Pattern.compile("href\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
        private static final Pattern META_FILE = Pattern.compile("FullMetadataDisplay\\.aspx\\?file=([^\"&#\\s>]+)", Pattern.CASE_INSENSITIVE);

        static M.Envelope mapDataset(
                String pasdaUciBase,
                int datasetNumId,
                String providerGuess,
                PasdaClient client,
                String sourceId,
                String sourceUrl,
                String ingestedAt
        ) {
            try {
                String dataSummaryUrl = pasdaUciBase + "/DataSummary.aspx?dataset=" + datasetNumId;
                String summaryHtml = client.getHtml(dataSummaryUrl);
                if (summaryHtml == null || summaryHtml.isBlank()) return null;

                // Extract links on the DataSummary page
                List<String> summaryLinks = extractLinks(summaryHtml, pasdaUciBase);
                String metaUrl = findMetadataUrl(summaryHtml, pasdaUciBase);
                if (metaUrl == null) return null;

                String metaHtml = client.getHtml(metaUrl);
                if (metaHtml == null || metaHtml.isBlank()) return null;

                String metaText = U.htmlToText(metaHtml);

                // Title (prefer <h2>, else parse from text)
                String title = U.extractFirstGroup(metaHtml, "(?is)<h2[^>]*>\\s*(.*?)\\s*</h2>");
                if (title == null || title.isBlank()) title = U.extractValue(metaText, "Title:");
                title = U.clean(title);
                if (title.isBlank()) title = "PASDA dataset " + datasetNumId;

                // Originators (Organization) from metadata; fallback to providerGuess
                List<String> originators = U.extractAllValues(metaText, "Originator:");
                if (originators.isEmpty() && providerGuess != null && !providerGuess.isBlank()) originators = List.of(providerGuess);

                List<M.Organization> orgs = new ArrayList<>();
                LinkedHashSet<String> seenOrgCanon = new LinkedHashSet<>();
                for (String orgName : originators) {
                    String canon = U.canonicalizeOrg(orgName);
                    if (canon.isBlank()) continue;
                    if (!seenOrgCanon.add(canon)) continue;
                    orgs.add(M.Organization.fromName(orgName));
                }

                // Abstract
                String abstractText = U.extractBlock(metaText,
                        "Abstract:",
                        List.of("Time_Period_of_Content:", "Keywords:", "Access_Constraints:", "Use_Constraints:", "Native_Data_Set_Environment:", "Spatial_Data_Organization_Information:", "Spatial_Reference_Information:", "Entity_and_Attribute_Information:", "Distribution_Information:", "Metadata_Reference_Information:")
                );
                abstractText = U.clean(abstractText);

                // Publication date (often year)
                String publicationDate = U.extractValue(metaText, "Publication_Date:");
                publicationDate = U.clean(publicationDate);

                // Time: prefer Calendar_Date(s), then Beginning/Ending, else Publication_Date
                List<String> calDates = U.extractAllValues(metaText, "Calendar_Date:");
                String beginRaw = null, endRaw = null;
                if (!calDates.isEmpty()) {
                    beginRaw = calDates.get(0);
                    endRaw = calDates.size() >= 2 ? calDates.get(calDates.size() - 1) : calDates.get(0);
                } else {
                    String bd = U.extractValue(metaText, "Beginning_Date:");
                    String ed = U.extractValue(metaText, "Ending_Date:");
                    if (bd != null && !bd.isBlank()) beginRaw = bd;
                    if (ed != null && !ed.isBlank()) endRaw = ed;
                }
                if (beginRaw == null || beginRaw.isBlank()) beginRaw = publicationDate;
                if (endRaw == null || endRaw.isBlank()) endRaw = beginRaw;

                String beginTs = U.normalizeBegin(beginRaw);
                String endTs = U.normalizeEnd(endRaw);

                M.Time time = null;
                if (beginTs != null && !beginTs.isBlank()) {
                    time = new M.Time(beginRaw, endRaw, beginTs, endTs);
                }

                // Space: Bounding_Coordinates (FGDC uses west/east/north/south)
                Double west = U.extractDouble(metaText, "West_Bounding_Coordinate:");
                Double east = U.extractDouble(metaText, "East_Bounding_Coordinate:");
                Double north = U.extractDouble(metaText, "North_Bounding_Coordinate:");
                Double south = U.extractDouble(metaText, "South_Bounding_Coordinate:");

                M.Space space = null;
                if (west != null && east != null && north != null && south != null) {
                    if (U.isLonLatBbox(west, south, east, north)) {
                        double cl = (west + east) / 2.0;
                        double ct = (south + north) / 2.0;
                        String wkt = U.wktPolygonFromBbox(west, south, east, north);
                        space = new M.Space(west, south, east, north, cl, ct, "bbox", 4326, wkt);
                    }
                }

                // Keywords: Theme_Keyword + Place_Keyword + Stratum_Keyword + Temporal_Keyword (best-effort)
                List<String> kws = new ArrayList<>();
                kws.addAll(U.extractAllValues(metaText, "Theme_Keyword:"));
                kws.addAll(U.extractAllValues(metaText, "Place_Keyword:"));
                kws.addAll(U.extractAllValues(metaText, "Stratum_Keyword:"));
                kws.addAll(U.extractAllValues(metaText, "Temporal_Keyword:"));

                LinkedHashMap<String, String> canon2raw = new LinkedHashMap<>();
                for (String kw : kws) {
                    String raw = U.clean(kw);
                    if (raw.isBlank()) continue;
                    String canon = U.canonicalizeKeyword(raw);
                    if (canon.isBlank()) continue;
                    canon2raw.putIfAbsent(canon, raw);
                }

                List<M.Keyword> keywords = new ArrayList<>();
                for (var e : canon2raw.entrySet()) {
                    keywords.add(new M.Keyword(M.Keyword.idForCanonical(e.getKey()), e.getValue(), e.getKey()));
                }

                // Constraints -> License (best-effort)
                String access = U.extractValue(metaText, "Access_Constraints:");
                String use = U.extractBlock(metaText,
                        "Use_Constraints:",
                        List.of("Native_Data_Set_Environment:", "Distribution_Information:", "Metadata_Reference_Information:", "Metadata_Standard_Name:", "Metadata_Standard_Version:", "Spatial_Data_Organization_Information:")
                );
                access = U.clean(access);
                use = U.clean(use);

                M.License license = null;
                if ((access != null && !access.isBlank()) || (use != null && !use.isBlank())) {
                    String lid = U.sha1Hex((access == null ? "" : access) + "|" + (use == null ? "" : use));
                    String title2 = (access != null && !access.isBlank()) ? access : "PASDA constraints";
                    String url2 = null;
                    license = new M.License(lid, title2, url2);
                }

                // Dataset type (vector digital data / raster, etc.)
                String geoForm = U.extractValue(metaText, "Geospatial_Data_Presentation_Form:");
                geoForm = U.clean(geoForm);

                // Build resources from DataSummary links + always include metadata link
                List<M.Resource> resources = new ArrayList<>();
                LinkedHashSet<String> seenRes = new LinkedHashSet<>();

                // Always include metadata page as a resource
                {
                    String u = U.normalizeUrl(metaUrl);
                    if (seenRes.add(u)) {
                        resources.add(M.Resource.ofUrl(u, "Metadata", "PASDA FGDC FullMetadataDisplay", "metadata"));
                    }
                }

                for (String link : summaryLinks) {
                    String u = U.normalizeUrl(link);
                    if (u == null || u.isBlank()) continue;
                    if (!seenRes.add(u)) continue;

                    // Skip obvious navigational duplicates
                    if (u.contains("/uci/SearchResults.aspx")) continue;

                    String name = inferResourceName(u);
                    String type = inferResourceType(u);
                    String desc = null;

                    resources.add(M.Resource.ofUrl(u, name, desc, type));
                }

                // Envelope
                String datasetId = "pasda:" + datasetNumId;

                M.Dataset ds = new M.Dataset(
                        datasetId,
                        title,                 // name
                        abstractText,          // notes
                        title,                 // title
                        geoForm.isBlank() ? "dataset" : geoForm,
                        publicationDate,       // version
                        dataSummaryUrl,
                        resources.size(),
                        keywords.size()
                );

                M.Source src = new M.Source(sourceId, sourceUrl);

                M.Envelope env = new M.Envelope(
                        src,
                        ds,
                        orgs,
                        space,
                        time,
                        license,
                        keywords,
                        resources,
                        new ArrayList<>(),
                        ingestedAt
                );
                return env;

            } catch (Exception e) {
                return null;
            }
        }

        private static List<String> extractLinks(String html, String pasdaUciBase) {
            List<String> out = new ArrayList<>();
            Matcher m = HREF.matcher(html);
            while (m.find()) {
                String href = m.group(1);
                if (href == null) continue;
                href = href.trim();
                if (href.isEmpty()) continue;
                if (href.startsWith("javascript:")) continue;
                if (href.startsWith("#")) continue;

                out.add(U.resolveUrl(pasdaUciBase, href));
            }
            return out;
        }

        private static String findMetadataUrl(String html, String pasdaUciBase) {
            Matcher m = META_FILE.matcher(html);
            if (m.find()) {
                String rel = "FullMetadataDisplay.aspx?file=" + m.group(1);
                return U.resolveUrl(pasdaUciBase, rel);
            }
            return null;
        }

        private static String inferResourceName(String url) {
            String u = url.toLowerCase(Locale.ROOT);

            if (u.contains("fullmetadatadisplay.aspx")) return "Metadata";
            if (u.contains("download")) return "Download";
            if (u.contains("geojson")) return "GeoJSON";
            if (u.endsWith(".kmz") || u.contains("kmz")) return "KMZ";
            if (u.contains("spreadsheet") || u.endsWith(".xlsx") || u.endsWith(".csv")) return "Spreadsheet";
            if (u.contains("preview")) return "Preview";
            if (u.contains("mapserver") && u.contains("/rest/")) return "ArcGIS REST";
            if (u.contains("wmss") || u.contains("wms")) return "WMS";
            if (u.contains("featureserver")) return "Feature Service";
            if (u.contains("imageserver")) return "Image Service";

            // fallback: last path segment
            try {
                URI uri = URI.create(url);
                String path = uri.getPath();
                if (path != null && !path.isBlank()) {
                    String[] seg = path.split("/");
                    String last = seg[seg.length - 1];
                    if (!last.isBlank()) return last;
                }
            } catch (Exception ignored) {}
            return "Resource";
        }

        private static String inferResourceType(String url) {
            String u = url.toLowerCase(Locale.ROOT);

            if (u.contains("fullmetadatadisplay.aspx")) return "metadata";
            if (u.contains("download")) return "download";
            if (u.contains("preview")) return "preview";
            if (u.contains("mapserver") || u.contains("featureserver") || u.contains("imageserver")) return "service";

            return "link";
        }
    }

    // ============================================================
    // Neo4j writer (same core as STAC, but no STAC-specific fields)
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

                    // helpful non-unique indexes
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

                    FOREACH (org IN row.orgs |
                      MERGE (o:Organization {id: org.id})
                      SET o.id = org.id,
                          o.title = org.title,
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
                      MERGE (k:Keyword {id: kw.id})
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
                        MERGE (f:Format {id: r.format.id})
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
    // PASDA HTTP client + cache
    // ============================================================

    static class PasdaClient {
        private final HttpClient http;
        private final Path cacheDir;

        PasdaClient(HttpClient http, Path cacheDirOrNull) {
            this.http = http;
            this.cacheDir = cacheDirOrNull;
        }

        String getHtml(String url) throws IOException, InterruptedException {
            String norm = U.normalizeUrl(url);

            if (cacheDir != null) {
                Path fp = cacheDir.resolve(U.sha1Hex(norm) + ".html");
                if (Files.exists(fp)) {
                    return Files.readString(fp, StandardCharsets.UTF_8);
                }
                String s = httpGetWithRetry(norm);
                Files.writeString(fp, s, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                return s;
            } else {
                return httpGetWithRetry(norm);
            }
        }

        private String httpGetWithRetry(String url) throws IOException, InterruptedException {
            int maxAttempts = 6;
            long sleepMs = 400;

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    return httpGet(url);
                } catch (IOException e) {
                    if (attempt == maxAttempts) throw e;
                    Thread.sleep(sleepMs);
                    sleepMs = Math.min(8000, (long) (sleepMs * 1.8));
                }
            }
            throw new IOException("unreachable");
        }

        private String httpGet(String url) throws IOException, InterruptedException {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("User-Agent", "Mozilla/5.0 (compatible; PASDA-Ingest/1.0)")
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
                    try { Thread.sleep(sleepMs); } catch (InterruptedException ignored) {}
                    sleepMs = Math.min(8000, (long)(sleepMs * 1.7));
                }
            }
            throw new RuntimeException("unreachable");
        }
    }

    // ============================================================
    // LLM topic extraction (batching)
    // ============================================================

    static class TopicExtractor {

        static void extractInParallel(
                List<M.Envelope> envs,
                int batchSize,
                Llm llm,
                ExecutorService pool,
                int maxInFlightBatches,
                AtomicLong llmTopicCalls
        ) throws Exception {

            if (envs == null || envs.isEmpty()) return;
            if (batchSize <= 0) batchSize = 8;

            Semaphore inFlight = new Semaphore(Math.max(1, maxInFlightBatches));
            ObjectMapper om = new ObjectMapper();

            List<Future<Map<String, List<M.PendingTopic>>>> futures = new ArrayList<>();

            for (int i = 0; i < envs.size(); i += batchSize) {
                int from = i;
                int to = Math.min(envs.size(), i + batchSize);

                List<M.Envelope> slice = envs.subList(from, to);

                // Build work items
                List<PendingWork> works = new ArrayList<>(slice.size());
                for (M.Envelope e : slice) {
                    String text = buildDatasetTextForTopics(e);
                    if (text.isBlank()) text = e.dataset.title;
                    works.add(new PendingWork(e.dataset.id, text));
                }

                inFlight.acquire();
                futures.add(pool.submit(() -> {
                    try {
                        return runOneBatch(works, llm, om, llmTopicCalls);
                    } finally {
                        inFlight.release();
                    }
                }));
            }

            // apply results (O(N) overall)
            Map<String, M.Envelope> byId = new HashMap<>(envs.size() * 2);
            for (M.Envelope e : envs) byId.put(e.dataset.id, e);

            for (Future<Map<String, List<M.PendingTopic>>> f : futures) {
                Map<String, List<M.PendingTopic>> got = f.get();
                for (var entry : got.entrySet()) {
                    M.Envelope e = byId.get(entry.getKey());
                    if (e == null) continue;

                    List<M.PendingTopic> pts = entry.getValue();
                    if (pts == null || pts.isEmpty()) continue;

                    Set<String> existing = new HashSet<>();
                    for (M.PendingTopic p : e.pendingTopics) existing.add(p.canonical);

                    for (M.PendingTopic p : pts) {
                        if (existing.add(p.canonical)) e.pendingTopics.add(p);
                    }
                }
            }
        }

        private static String buildDatasetTextForTopics(M.Envelope e) {
            StringBuilder sb = new StringBuilder();
            if (e.dataset.title != null) sb.append(e.dataset.title).append("\n");
            if (e.dataset.notes != null) sb.append(e.dataset.notes).append("\n");

            // Include a few keywords (not too many)
            if (e.keywords != null && !e.keywords.isEmpty()) {
                int k = 0;
                sb.append("Keywords: ");
                for (M.Keyword kw : e.keywords) {
                    if (kw == null || kw.name == null) continue;
                    if (k++ >= 18) break;
                    sb.append(kw.name).append("; ");
                }
                sb.append("\n");
            }

            String txt = sb.toString().trim();
            if (txt.length() > 2500) txt = txt.substring(0, 2500);
            return txt;
        }

        private static Map<String, List<M.PendingTopic>> runOneBatch(
                List<PendingWork> works,
                Llm llm,
                ObjectMapper om,
                AtomicLong llmTopicCalls
        ) {
            try {
                ArrayNode arr = om.createArrayNode();
                for (PendingWork w : works) {
                    arr.add(om.createObjectNode()
                            .put("id", w.datasetId)
                            .put("text", w.text));
                }

                String prompt = """
                        You are extracting topical themes from geospatial dataset metadata.
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
    // Models (Envelope)
    // ============================================================

    static class M {

        record Source(String id, String url) {}

        record Dataset(
                String id,
                String name,
                String notes,
                String title,
                String type,
                String version,
                String url,
                int num_resources,
                int num_tags
        ) {}

        record Organization(
                String id,
                String name,
                String title,
                String description,
                String image_url,
                String url,
                List<String> roles,
                String canonical
        ) {
            static Organization fromName(String rawName) {
                String name = U.clean(rawName);
                if (name.isBlank()) name = "UNKNOWN";

                String canonical = U.canonicalizeOrg(name);
                String id = "org:" + U.sha1Hex(canonical);

                return new Organization(
                        id,
                        name,
                        name,
                        null,
                        null,
                        null,
                        List.of("originator"),
                        canonical
                );
            }
        }

        record Keyword(String id, String name, String canonical) {
            static String idForCanonical(String canon) {
                return "kw:" + U.sha1Hex(canon);
            }
        }

        record PendingTopic(String canonical, String name) {}

        record License(String license_id, String license_title, String license_url) {}

        record Format(String id, String name, String canonical) {}

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
        ) {
            static Resource ofUrl(String url, String name, String description, String type) {
                String norm = U.normalizeUrl(url);
                String id = U.sha1Hex(norm);
                Format fmt = U.formatFromUrl(norm);

                return new Resource(
                        id,
                        null,
                        description,
                        null,
                        name,
                        null,
                        null,
                        null,
                        type,
                        null,
                        norm,
                        null,
                        fmt
                );
            }
        }

        record Space(
                double west,
                double south,
                double east,
                double north,
                double centroidLon,
                double centroidLat,
                String spatialType,
                int srid,
                String wktPolygon
        ) {}

        record Time(String beginRaw, String endRaw, String beginTs, String endTs) {}

        static class Envelope {
            final Source source;
            final Dataset dataset;
            final List<Organization> orgs;
            final Space space;
            final Time time;
            final License license;
            final List<Keyword> keywords;
            final List<Resource> resources;
            final List<PendingTopic> pendingTopics;
            final String ingestedAt;

            Envelope(
                    Source source,
                    Dataset dataset,
                    List<Organization> orgs,
                    Space space,
                    Time time,
                    License license,
                    List<Keyword> keywords,
                    List<Resource> resources,
                    List<PendingTopic> pendingTopics,
                    String ingestedAt
            ) {
                this.source = source;
                this.dataset = dataset;
                this.orgs = (orgs == null) ? new ArrayList<>() : new ArrayList<>(orgs);
                this.space = space;
                this.time = time;
                this.license = license;
                this.keywords = (keywords == null) ? new ArrayList<>() : new ArrayList<>(keywords);
                this.resources = (resources == null) ? new ArrayList<>() : new ArrayList<>(resources);
                this.pendingTopics = (pendingTopics == null) ? new ArrayList<>() : new ArrayList<>(pendingTopics);
                this.ingestedAt = (ingestedAt == null || ingestedAt.isBlank()) ? Instant.now().toString() : ingestedAt;
            }

            Map<String, Object> toMap() {
                Map<String, Object> m = new HashMap<>();

                m.put("sourceId", source.id);
                m.put("sourceUrl", source.url);

                m.put("datasetId", dataset.id);
                m.put("datasetName", dataset.name);
                m.put("datasetNotes", dataset.notes);
                m.put("datasetNumResources", dataset.num_resources);
                m.put("datasetNumTags", dataset.num_tags);
                m.put("datasetTitle", dataset.title);
                m.put("datasetType", dataset.type);
                m.put("datasetVersion", dataset.version);
                m.put("datasetUrl", dataset.url);
                m.put("ingestedAt", ingestedAt);

                // orgs
                List<Map<String, Object>> orgMaps = new ArrayList<>();
                for (Organization o : orgs) {
                    Map<String, Object> om = new HashMap<>();
                    om.put("id", o.id);
                    om.put("name", o.name);
                    om.put("title", o.title);
                    om.put("description", o.description);
                    om.put("image_url", o.image_url);
                    om.put("url", o.url);
                    om.put("roles", o.roles);
                    om.put("canonical", o.canonical);
                    orgMaps.add(om);
                }
                m.put("orgs", orgMaps);

                // space
                if (space != null) {
                    m.put("hasSpace", true);
                    m.put("west", space.west);
                    m.put("south", space.south);
                    m.put("east", space.east);
                    m.put("north", space.north);
                    m.put("centroidLon", space.centroidLon);
                    m.put("centroidLat", space.centroidLat);
                    m.put("spatialType", space.spatialType);
                    m.put("srid", space.srid);
                    m.put("wktPolygon", space.wktPolygon);
                } else {
                    m.put("hasSpace", false);
                }

                // time
                if (time != null) {
                    m.put("hasTime", true);
                    m.put("beginRaw", time.beginRaw);
                    m.put("endRaw", time.endRaw);
                    m.put("beginTs", time.beginTs);
                    m.put("endTs", time.endTs);
                } else {
                    m.put("hasTime", false);
                }

                // license
                if (license != null && license.license_id != null && !license.license_id.isBlank()) {
                    m.put("hasLicense", true);
                    m.put("licenseId", license.license_id);
                    m.put("licenseTitle", license.license_title);
                    m.put("licenseUrl", license.license_url);
                } else {
                    m.put("hasLicense", false);
                }

                // keywords
                List<Map<String, Object>> kwMaps = new ArrayList<>();
                for (Keyword k : keywords) {
                    Map<String, Object> km = new HashMap<>();
                    km.put("id", k.id);
                    km.put("name", k.name);
                    km.put("canonical", k.canonical);
                    kwMaps.add(km);
                }
                m.put("keywords", kwMaps);

                // pending topics
                List<Map<String, Object>> ptMaps = new ArrayList<>();
                for (PendingTopic pt : pendingTopics) {
                    Map<String, Object> pm = new HashMap<>();
                    pm.put("canonical", pt.canonical);
                    pm.put("name", pt.name);
                    ptMaps.add(pm);
                }
                m.put("pendingTopics", ptMaps);

                // resources
                List<Map<String, Object>> resMaps = new ArrayList<>();
                for (Resource r : resources) {
                    Map<String, Object> rm = new HashMap<>();
                    rm.put("id", r.id);
                    rm.put("created", r.created);
                    rm.put("description", r.description);
                    rm.put("last_modified", r.last_modified);
                    rm.put("name", r.name);
                    rm.put("package_id", r.package_id);
                    rm.put("resource_locator_function", r.resource_locator_function);
                    rm.put("resource_locator_protocol", r.resource_locator_protocol);
                    rm.put("resource_type", r.resource_type);
                    rm.put("size", r.size);
                    rm.put("url", r.url);
                    rm.put("url_type", r.url_type);

                    if (r.format != null) {
                        Map<String, Object> fm = new HashMap<>();
                        fm.put("id", r.format.id);
                        fm.put("name", r.format.name);
                        fm.put("canonical", r.format.canonical);
                        rm.put("format", fm);
                    } else {
                        rm.put("format", null);
                    }
                    resMaps.add(rm);
                }
                m.put("resources", resMaps);

                return m;
            }
        }
    }

    // ============================================================
    // Utilities
    // ============================================================

    static class U {

        private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss").withZone(ZoneOffset.UTC);

        static String sha1Hex(String s) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder(dig.length * 2);
                for (byte b : dig) sb.append(String.format("%02x", b));
                return sb.toString();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        static String normalizeUrl(String url) {
            if (url == null) return null;
            String u = url.trim();
            if (u.isBlank()) return null;

            // Decode common html entity
            u = u.replace("&amp;", "&");

            return u;
        }

        static String resolveUrl(String base, String href) {
            if (href == null) return null;
            String h = href.trim();
            if (h.isEmpty()) return null;

            if (h.startsWith("http://") || h.startsWith("https://")) return h;
            if (h.startsWith("//")) return "https:" + h;

            // handle absolute path
            if (h.startsWith("/")) {
                try {
                    URI b = URI.create(base);
                    return b.getScheme() + "://" + b.getHost() + h;
                } catch (Exception e) {
                    return base + h;
                }
            }

            // relative to base (which is /uci)
            if (!base.endsWith("/")) base = base + "/";
            return base + h;
        }

        static String htmlToText(String html) {
            if (html == null) return "";

            String s = html;

            // Remove scripts/styles
            s = s.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
            s = s.replaceAll("(?is)<style[^>]*>.*?</style>", " ");

            // Structural line breaks
            s = s.replaceAll("(?i)<br\\s*/?>", "\n");
            s = s.replaceAll("(?i)</(p|div|dt|dd|li|h1|h2|h3|h4|h5|h6)>", "\n");
            s = s.replaceAll("(?i)<hr\\s*/?>", "\n");
            s = s.replaceAll("(?i)<pre[^>]*>", "\n");
            s = s.replaceAll("(?i)</pre>", "\n");

            // Strip tags
            s = s.replaceAll("(?s)<[^>]+>", "");

            // Decode a few entities
            s = s.replace("&nbsp;", " ");
            s = s.replace("&amp;", "&");
            s = s.replace("&lt;", "<");
            s = s.replace("&gt;", ">");
            s = s.replace("&quot;", "\"");
            s = s.replace("&#39;", "'");

            // Normalize whitespace
            s = s.replaceAll("[\\t\\x0B\\f\\r]+", "\n");
            s = s.replaceAll("\\n{2,}", "\n");
            return s.trim();
        }

        static String extractFirstGroup(String s, String regex) {
            if (s == null) return null;
            Matcher m = Pattern.compile(regex).matcher(s);
            if (m.find()) return m.group(1);
            return null;
        }

        static String extractValue(String text, String label) {
            if (text == null || label == null) return null;
            Pattern p = Pattern.compile("(?m)\\b" + Pattern.quote(label) + "\\s*([^\\n\\r]+)");
            Matcher m = p.matcher(text);
            if (m.find()) return m.group(1);
            return null;
        }

        static List<String> extractAllValues(String text, String label) {
            if (text == null || label == null) return List.of();
            Pattern p = Pattern.compile("(?m)\\b" + Pattern.quote(label) + "\\s*([^\\n\\r]+)");
            Matcher m = p.matcher(text);

            List<String> out = new ArrayList<>();
            while (m.find()) {
                String v = m.group(1);
                if (v == null) continue;
                v = clean(v);
                if (!v.isBlank()) out.add(v);
            }
            return out;
        }

        static Double extractDouble(String text, String label) {
            String v = extractValue(text, label);
            if (v == null) return null;
            v = clean(v).replace(",", "");
            try {
                return Double.parseDouble(v);
            } catch (Exception e) {
                return null;
            }
        }

        static String extractBlock(String text, String startLabel, List<String> endLabels) {
            if (text == null || startLabel == null) return null;
            int start = indexOfIgnoreCase(text, startLabel);
            if (start < 0) return null;
            start += startLabel.length();

            int end = text.length();
            if (endLabels != null) {
                for (String endLabel : endLabels) {
                    int idx = indexOfIgnoreCase(text, endLabel);
                    if (idx >= 0 && idx > start && idx < end) end = idx;
                }
            }
            String block = text.substring(start, end);
            return clean(block);
        }

        private static int indexOfIgnoreCase(String text, String needle) {
            return text.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
        }

        static String clean(String s) {
            if (s == null) return "";
            String t = s.replace("\u00A0", " ");
            t = t.replaceAll("[\\s\\u200B]+", " ").trim();
            return t;
        }

        static String canonicalizeTopic(String name) {
            if (name == null) return "";
            String canonical = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\s]+", " ").replaceAll("\\s+", " ").trim();
            if (canonical.isBlank()) return "";
            return canonical;
        }

        static String canonicalizeOrg(String name) {
            if (name == null) return "";
            String canonical = name.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
            canonical = canonical.replaceAll("[^a-z0-9\\s&\\-]+", " ").replaceAll("\\s+", " ").trim();
            if (canonical.isBlank()) canonical = "unknown";
            return canonical;
        }

        static String canonicalizeKeyword(String kw) {
            if (kw == null) return "";
            String canonical = kw.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
            canonical = canonical.replaceAll("[^a-z0-9\\s\\-_/]+", " ").replaceAll("\\s+", " ").trim();
            if (canonical.isBlank()) return "";
            return canonical;
        }

        static boolean isLonLatBbox(double west, double south, double east, double north) {
            return west >= -180 && west <= 180 &&
                    east >= -180 && east <= 180 &&
                    south >= -90 && south <= 90 &&
                    north >= -90 && north <= 90 &&
                    west <= east && south <= north;
        }

        static String wktPolygonFromBbox(double west, double south, double east, double north) {
            // (west,south) -> (east,south) -> (east,north) -> (west,north) -> close
            return String.format(Locale.ROOT,
                    "POLYGON((%f %f,%f %f,%f %f,%f %f,%f %f))",
                    west, south, east, south, east, north, west, north, west, south);
        }

        static String normalizeBegin(String raw) {
            return normalizeTs(raw, true);
        }

        static String normalizeEnd(String raw) {
            return normalizeTs(raw, false);
        }

        /**
         * Normalize FGDC-ish date strings to the SAME format used by your other pipelines:
         *   "yyyyMMdd HH:mm:ss" (UTC)
         *
         * Accepted inputs (best-effort):
         * - "2019"
         * - "20190913"
         * - "2019-09-13"
         * - "2019/09/13"
         * - "2019-09-13 12:34:56"
         */
        static String normalizeTs(String raw, boolean isBegin) {
            if (raw == null) return null;
            String s = raw.trim();
            if (s.isBlank()) return null;

            // keep only first token if multiple
            s = s.split("\\s+")[0].trim();

            // yyyy
            if (s.matches("\\d{4}")) {
                int y = Integer.parseInt(s);
                LocalDate d = isBegin ? LocalDate.of(y, 1, 1) : LocalDate.of(y, 12, 31);
                Instant ins = d.atStartOfDay(ZoneOffset.UTC).toInstant();
                if (!isBegin) ins = ins.plus(Duration.ofHours(23)).plus(Duration.ofMinutes(59)).plus(Duration.ofSeconds(59));
                return TS_FMT.format(ins);
            }

            // yyyymmdd
            if (s.matches("\\d{8}")) {
                int y = Integer.parseInt(s.substring(0, 4));
                int m = Integer.parseInt(s.substring(4, 6));
                int d = Integer.parseInt(s.substring(6, 8));
                LocalDate ld = LocalDate.of(y, m, d);
                Instant ins = ld.atStartOfDay(ZoneOffset.UTC).toInstant();
                if (!isBegin) ins = ins.plus(Duration.ofHours(23)).plus(Duration.ofMinutes(59)).plus(Duration.ofSeconds(59));
                return TS_FMT.format(ins);
            }

            // yyyy-mm-dd or yyyy/mm/dd
            String norm = s.replace("/", "-");
            if (norm.matches("\\d{4}-\\d{2}-\\d{2}")) {
                LocalDate ld = LocalDate.parse(norm);
                Instant ins = ld.atStartOfDay(ZoneOffset.UTC).toInstant();
                if (!isBegin) ins = ins.plus(Duration.ofHours(23)).plus(Duration.ofMinutes(59)).plus(Duration.ofSeconds(59));
                return TS_FMT.format(ins);
            }

            // try full datetime
            try {
                Instant ins = Instant.parse(raw.trim());
                return TS_FMT.format(ins);
            } catch (Exception ignored) {}

            // last resort: try some common patterns
            List<String> pats = List.of(
                    "yyyy-MM-dd HH:mm:ss",
                    "yyyy/MM/dd HH:mm:ss",
                    "yyyy-MM-dd'T'HH:mm:ss",
                    "yyyy-MM-dd'T'HH:mm:ss'Z'"
            );
            for (String p : pats) {
                try {
                    DateTimeFormatter f = DateTimeFormatter.ofPattern(p).withZone(ZoneOffset.UTC);
                    TemporalAccessor ta = f.parse(raw.trim());
                    Instant ins = Instant.from(ta);
                    return TS_FMT.format(ins);
                } catch (Exception ignored) {}
            }

            return null;
        }

        static M.Format formatFromUrl(String url) {
            if (url == null) return null;
            String u = url.toLowerCase(Locale.ROOT);

            String name = null;

            if (u.contains("geojson")) name = "GeoJSON";
            else if (u.endsWith(".kmz") || u.contains("kmz")) name = "KMZ";
            else if (u.contains("wms")) name = "WMS";
            else if (u.contains("/rest/services") || u.contains("/server/rest/services")) name = "ArcGIS REST";
            else if (u.contains("mapserver")) name = "MapServer";
            else if (u.contains("featureserver")) name = "FeatureServer";
            else if (u.contains("imageserver")) name = "ImageServer";
            else if (u.contains("fullmetadatadisplay.aspx")) name = "HTML";
            else if (u.endsWith(".zip")) name = "ZIP";
            else if (u.endsWith(".csv")) name = "CSV";
            else if (u.endsWith(".xlsx") || u.endsWith(".xls")) name = "XLSX";
            else if (u.endsWith(".json")) name = "JSON";
            else if (u.endsWith(".xml")) name = "XML";
            else if (u.endsWith(".tif") || u.endsWith(".tiff")) name = "GeoTIFF";
            else if (u.endsWith(".shp")) name = "SHP";
            else if (u.endsWith(".gpkg")) name = "GPKG";

            if (name == null) name = "UNKNOWN";
            String canonical = name.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
            String id = "format:" + canonical;

            return new M.Format(id, name, canonical);
        }
    }
}

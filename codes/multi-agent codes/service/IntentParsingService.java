package edu.psu.giscience.igdd.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.psu.giscience.igdd.domain.intent.GeoIntent;
import edu.psu.giscience.igdd.llm.LlmClientService;
import edu.psu.giscience.igdd.memory.ConversationMemory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Intent parsing/refinement with:
 *  - Query expansion / query transformation (English)
 *  - No longer requires topic or dataset (any dimension is acceptable)
 *  - Always aims to produce English outputs
 *  - "New question" detection: decide whether to reset conversation memory/state
 */
@Service
public class IntentParsingService {

    private final LlmClientService llm;
    private final ObjectMapper om;
    private final SpaceTimeNormalizationService spaceTimeNormalizer;

    // quick heuristics
    // Note: source is not parsed from user input - it is selected via the frontend Data Catalog Selection panel
    private static final Pattern KEYVAL_ANY = Pattern.compile("(?i)\\b(topic|format|license|organization|space|time|bbox)\\s*=");

    private static final Pattern USER_SAYS_NEW = Pattern.compile("(?i)\\b(new question|another question|next question|different question|switch topic|new topic|start over|new request)\\b");

    private static final String EXPAND_PROMPT = """
            You are a query expansion and rewriting agent for a geospatial dataset discovery system.

            TASK:
            Rewrite the user's message into a clearer, more explicit English query for dataset discovery.
            - Preserve all factual constraints (space/time/format/license/organization/topic names).
            - If the input is not English, translate it to English.
            - Do NOT invent constraints.
            - Keep it concise (1-3 sentences).
            - For format constraints, convert format names to standard abbreviations or MIME types.
              Examples: "geotiff" -> "GeoTIFF", "geojson" -> "GeoJSON", "geopackage" -> "application/geopackage+sqlite3",
              "javascript" -> "text/javascript", "gif" -> "gif", "rar" -> "rar", "shp" -> "shp".
              Use standard abbreviations (gif, rar, shp) or MIME types (text/javascript, application/geopackage+sqlite3).
            
            Output requirements:
            - Output MUST be valid JSON ONLY (no markdown).
            - Output language MUST be English.

            JSON schema:
            {
              "expanded_query": "..."
            }

            USER MESSAGE:
            %s
            """;

    // Merged prompt that combines expansion and parsing when useExpand=true
    private static final String PARSE_PROMPT_WITH_EXPAND = """
            You are an intent extraction agent for a geospatial dataset discovery system.

            TASK 1 - QUERY EXPANSION (if needed):
            - If the user's message is not in English, translate it to English.
            - Rewrite the message into a clearer, more explicit English query for dataset discovery.
            - Preserve all factual constraints (space/time/format/license/organization/topic names).
            - Do NOT invent constraints.
            - Keep it concise (1-3 sentences).
            - For format constraints, convert format names to standard abbreviations or MIME types.
              Examples: "geotiff" -> "GeoTIFF", "geojson" -> "GeoJSON", "geopackage" -> "application/geopackage+sqlite3",
              "javascript" -> "text/javascript", "gif" -> "gif", "rar" -> "rar", "shp" -> "shp".
              Use standard abbreviations (gif, rar, shp) or MIME types (text/javascript, application/geopackage+sqlite3).

            TASK 2 - INTENT EXTRACTION:
            Extract any of these dimensions if present in the expanded/translated query:
            Topic, Format, License, Organization, Space, Time.

            IMPORTANT RULES:
            - Any subset of dimensions is valid.
            - If you cannot extract a dimension, set it to null.
            - Do NOT hallucinate values.
            - All output text MUST be English.
            - For space:
              - If user provides bbox, set bbox=[minLon,minLat,maxLon,maxLat] (EPSG:4326/WGS84).
              - Otherwise put place name into raw_text/value and leave bbox as null.
            - For time:
              - If user provides a clear year/date/range, set raw_text and (optionally) include start/end in YYYYMMDD HH:mm:ss.
              - Otherwise set raw_text and leave start/end null.
            - For all extracted dimensions, set needs_clarification=true (we will ask user to confirm).
            
            CONFIDENCE SCORING (0.0-1.0) - You must evaluate BOTH factors:
            1. Extraction accuracy (0.0-1.0): How confident are you that you correctly extracted what the user mentioned?
               - High (0.8-1.0): Clear, explicit mention (e.g., "land cover", "GeoTIFF", "CC-BY-4.0", "USGS", "Pennsylvania", "2020-2022")
               - Medium (0.5-0.7): Implied or inferred mention 
               - Low (0.3-0.4): Weak or ambiguous mention
               - Very Low (0.0-0.2): No clear mention, likely hallucination
            
            2. Semantic plausibility (0.0-1.0): Does the extracted value make realistic sense for this dimension?
               - High (0.8-1.0): The value has clear real-world meaning in this dimension context
                 * Topic: "land cover", "urban heat island", "precipitation", "soil moisture" are realistic topics
                 * Format: "GeoTIFF", "GeoJSON", "Shapefile" are realistic formats
                 * License: "CC-BY-4.0", "Public Domain", "ODC-BY" are realistic licenses
                 * Organization: "USGS", "NASA", "NOAA" are realistic organizations
                 * Space: "Pennsylvania", "United States", valid bbox coordinates are realistic
                 * Time: Valid date ranges, years are realistic
               - Low (0.0-0.3): The value does not make realistic sense for this dimension
                 * Topic: "hello world", "test", generic words like "data" or "information"
                 * Format: "hello", "unknown", generic terms
                 * License: Nonsensical values, generic terms
                 * Organization: Generic terms, common words
                 * Space: Nonsensical place names
                 * Time: Invalid dates, impossible ranges
            
            Final confidence = (Extraction accuracy × 0.6) + (Semantic plausibility × 0.4)
            - This balances both factors: extraction accuracy is slightly more important, but semantic plausibility is also critical.
            - If extraction accuracy is very low (<0.3), final confidence should be low regardless of plausibility.
            - If semantic plausibility is very low (<0.3), final confidence should be reduced significantly.

            Output MUST be valid JSON ONLY (no markdown), matching the same schema as regular intent extraction:
            (The expanded/translated query is used internally for extraction, but output follows the same GeoIntent schema)

            {
              "topic": {"raw_text":"...","value":"...","kg_node_id":null,"confidence":0.0,"needs_clarification":true} | null,
              "space": {"raw_text":"...","value":"...","kg_node_id":null,"bbox":[...,...,...,...] | null,"confidence":0.0,"needs_clarification":true} | null,
              "time": {"raw_text":"...","type":"range|instant|year|unspecified","start":"YYYYMMDD HH:mm:ss" | null,"end":"YYYYMMDD HH:mm:ss" | null,"granularity":"day|month|year|unspecified","confidence":0.0,"needs_clarification":true} | null,
              "format": {"raw_text":"...","value":"...","kg_node_id":null,"confidence":0.0,"needs_clarification":true} | null,
              "license": {"raw_text":"...","value":"...","kg_node_id":null,"confidence":0.0,"needs_clarification":true} | null,
              "organization": {"raw_text":"...","value":"...","kg_node_id":null,"confidence":0.0,"needs_clarification":true} | null,
              "questions_for_user": ["..."],
              "overall_confidence": 0.0
            }

            USER MESSAGE:
            %s

            RECENT HISTORY:
            %s
            """;

    // Original prompt for text search mode (no expansion)
    private static final String PARSE_PROMPT = """
            You are an intent extraction agent for a geospatial dataset discovery system.

            INPUTS:
            - User message (use as-is, no expansion needed)
            - Recent conversation history (may help, but do NOT assume facts not stated)

            GOAL:
            Extract any of these dimensions if present:
            Topic, Format, License, Organization, Space, Time.

            IMPORTANT RULES:
            - Any subset of dimensions is valid.
            - If you cannot extract a dimension, set it to null.
            - Do NOT hallucinate values.
            - All output text MUST be English.
            - For space:
              - If user provides bbox, set bbox=[minLon,minLat,maxLon,maxLat] (EPSG:4326/WGS84).
              - Otherwise put place name into raw_text/value and leave bbox as null.
            - For time:
              - If user provides a clear year/date/range, set raw_text and (optionally) include start/end in YYYYMMDD HH:mm:ss.
              - Otherwise set raw_text and leave start/end null.
            - For all extracted dimensions, set needs_clarification=true (we will ask user to confirm).
            
            CONFIDENCE SCORING (0.0-1.0) - You must evaluate BOTH factors:
            1. Extraction accuracy (0.0-1.0): How confident are you that you correctly extracted what the user mentioned?
               - High (0.8-1.0): Clear, explicit mention (e.g., "land cover", "GeoTIFF", "CC-BY-4.0", "USGS", "Pennsylvania", "2020-2022")
               - Medium (0.5-0.7): Implied or inferred mention 
               - Low (0.3-0.4): Weak or ambiguous mention
               - Very Low (0.0-0.2): No clear mention, likely hallucination
            
            2. Semantic plausibility (0.0-1.0): Does the extracted value make realistic sense for this dimension?
               - High (0.8-1.0): The value has clear real-world meaning in this dimension context
                 * Topic: "land cover", "urban heat island", "precipitation", "soil moisture" are realistic topics
                 * Format: "GeoTIFF", "GeoJSON", "Shapefile" are realistic formats
                 * License: "CC-BY-4.0", "Public Domain", "ODC-BY" are realistic licenses
                 * Organization: "USGS", "NASA", "NOAA" are realistic organizations
                 * Space: "Pennsylvania", "United States", valid bbox coordinates are realistic
                 * Time: Valid date ranges, years are realistic
               - Low (0.0-0.3): The value does not make realistic sense for this dimension
                 * Topic: "hello world", "test", generic words like "data" or "information"
                 * Format: "hello", "unknown", generic terms
                 * License: Nonsensical values, generic terms
                 * Organization: Generic terms, common words
                 * Space: Nonsensical place names
                 * Time: Invalid dates, impossible ranges
            
            Final confidence = (Extraction accuracy × 0.6) + (Semantic plausibility × 0.4)
            - This balances both factors: extraction accuracy is slightly more important, but semantic plausibility is also critical.
            - If extraction accuracy is very low (<0.3), final confidence should be low regardless of plausibility.
            - If semantic plausibility is very low (<0.3), final confidence should be reduced significantly.

            Output MUST be valid JSON ONLY (no markdown), matching this schema:

            {
              "topic": {"raw_text":"...","value":"...","kg_node_id":null,"confidence":0.0,"needs_clarification":true} | null,
              "space": {"raw_text":"...","value":"...","kg_node_id":null,"bbox":[...,...,...,...] | null,"confidence":0.0,"needs_clarification":true} | null,
              "time": {"raw_text":"...","type":"range|instant|year|unspecified","start":"YYYYMMDD HH:mm:ss" | null,"end":"YYYYMMDD HH:mm:ss" | null,"granularity":"day|month|year|unspecified","confidence":0.0,"needs_clarification":true} | null,
              "format": {"raw_text":"...","value":"...","kg_node_id":null,"confidence":0.0,"needs_clarification":true} | null,
              "license": {"raw_text":"...","value":"...","kg_node_id":null,"confidence":0.0,"needs_clarification":true} | null,
              "organization": {"raw_text":"...","value":"...","kg_node_id":null,"confidence":0.0,"needs_clarification":true} | null,
              "questions_for_user": ["..."],
              "overall_confidence": 0.0
            }

            USER MESSAGE:
            %s

            RECENT HISTORY:
            %s
            """;

    private static final String REFINE_PROMPT = """
            You are an intent refinement agent for a geospatial dataset discovery system.

            INPUTS:
            - Previous intent JSON
            - New user message (and expanded English version)
            - Recent conversation history

            TASK:
            Update the intent JSON with refinements from the new message.
            - If the new message clearly refines a previous dimension, update that dimension.
            - If the new message introduces a new dimension, add it.
            - Do NOT delete previous dimensions unless the user clearly contradicts them.
            - Do NOT extract or update source - it is selected via the frontend Data Catalog Selection panel, not from user text.
            - All output text MUST be English.
            - For any updated or newly added dimension, set needs_clarification=true.
            - Do NOT hallucinate.
            
            CONFIDENCE SCORING (0.0-1.0) - You must evaluate BOTH factors for updated/new dimensions:
            1. Extraction accuracy (0.0-1.0): How confident are you that you correctly extracted what the user mentioned?
               - High (0.8-1.0): Clear, explicit mention
               - Medium (0.5-0.7): Implied or inferred mention
               - Low (0.3-0.4): Weak or ambiguous mention
               - Very Low (0.0-0.2): No clear mention, likely hallucination
            
            2. Semantic plausibility (0.0-1.0): Does the extracted value make realistic sense for this dimension?
               - High (0.8-1.0): The value has clear real-world meaning in this dimension context
                 * Topic: "land cover", "urban heat island", "precipitation" are realistic topics
                 * Format: "GeoTIFF", "GeoJSON", "Shapefile" are realistic formats
                 * License: "CC-BY-4.0", "Public Domain" are realistic licenses
                 * Organization: "USGS", "NASA", "NOAA" are realistic organizations
                 * Space: Valid place names, valid bbox coordinates
                 * Time: Valid date ranges, years
               - Low (0.0-0.3): The value does not make realistic sense (e.g., "hello world" as topic, generic words like "data")
            
            Final confidence = (Extraction accuracy × 0.6) + (Semantic plausibility × 0.4)
            - This balances both factors: extraction accuracy is slightly more important, but semantic plausibility is also critical.
            - If extraction accuracy is very low (<0.3), final confidence should be low regardless of plausibility.
            - If semantic plausibility is very low (<0.3), final confidence should be reduced significantly.

            Output MUST be valid JSON ONLY (no markdown), SAME schema as previous.

            PREVIOUS INTENT JSON:
            %s

            EXPANDED ENGLISH NEW MESSAGE:
            %s

            ORIGINAL NEW MESSAGE:
            %s

            RECENT HISTORY:
            %s
            """;

    private static final String NEW_CONVERSATION_PROMPT = """
            You are a conversation boundary detector for a dataset discovery chatbot.

            Decide whether the user's new message is a NEW, SEPARATE discovery request (new conversation),
            or a REFINEMENT/CONTINUATION of the current request.

            Rules of thumb:
            - If user refers to "that", "it", "same", or answers a confirmation question, it's likely continuation.
            - If user switches to a different topic or asks an unrelated discovery request, it's likely new.
            - If user explicitly says "new question", "another question", "switch topic", it's new.

            Output requirements:
            - Output MUST be valid JSON ONLY (no markdown).
            - Output language MUST be English.

            JSON schema:
            {
              "is_new_conversation": true|false,
              "confidence": 0.0-1.0,
              "reason": "..."
            }

            PREVIOUS INTENT (may be empty):
            %s

            RECENT HISTORY:
            %s

            NEW USER MESSAGE:
            %s
            """;

    private static final String DATA_DISCOVERY_CHECK_PROMPT = """
            You are a classifier for a geospatial dataset discovery system.

            TASK:
            Determine if the user's question is related to geospatial dataset discovery.

            A question is related to dataset discovery if it:
            - Asks about finding, searching, discovering, or locating datasets
            - Mentions data, datasets, geospatial data, geographic data, spatial data
            - Mentions dimensions relevant to data discovery (topic, format, license, organization, location, time)
            - Asks about data availability, data sources, or data portals
            - Requests help with data search or discovery

            A question is NOT related to dataset discovery if it:
            - Asks general questions (e.g., "What is the weather?", "How are you?", "What time is it?")
            - Asks about general knowledge not related to data (e.g., "What is machine learning?", "Explain quantum physics")
            - Asks for help with non-data tasks (e.g., "Help me write code", "Translate this text")
            - Is a casual conversation (e.g., "Hello", "Thanks", "Goodbye")
            - Asks about the system itself in a non-discovery way (e.g., "What can you do?" - but "What data can you find?" IS related)

            Output requirements:
            - Output MUST be valid JSON ONLY (no markdown).
            - Output language MUST be English.

            JSON schema:
            {
              "is_data_discovery_related": true|false,
              "confidence": 0.0-1.0,
              "reason": "..."
            }

            USER MESSAGE:
            %s

            RECENT CONVERSATION HISTORY:
            %s
            """;

    public IntentParsingService(LlmClientService llm, SpaceTimeNormalizationService spaceTimeNormalizer) {
        this.llm = llm;
        this.spaceTimeNormalizer = spaceTimeNormalizer;
        this.om = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** Parse initial intent from a user query. */
    public GeoIntent parseInitialIntent(String userQuery, ConversationMemory memory, String model, 
                                       String apiKey, String questionId) {
        return parseInitialIntent(userQuery, memory, model, true, apiKey, questionId);
    }
    
    /** Parse initial intent from a user query. */
    public GeoIntent parseInitialIntent(String userQuery, ConversationMemory memory, String model, 
                                       boolean useExpand, String apiKey, String questionId) {
        String uq = userQuery == null ? "" : userQuery.trim();
        String history = memory == null ? "" : safeHistory(memory);

        // OPTIMIZATION: Merge expandQuery and parseInitialIntent into a single LLM call
        // This eliminates one LLM API round-trip, significantly improving performance
        String prompt;
        if (useExpand) {
            // Use merged prompt that does expansion and parsing in one step
            // Check if expansion is really needed (short explicit key=value queries don't need expansion)
            boolean hasKeyVal = KEYVAL_ANY.matcher(uq).find();
            if (hasKeyVal && uq.length() < 25) {
                // Short explicit constraint: use simple parse prompt (no expansion)
                prompt = PARSE_PROMPT.formatted(uq, history);
            } else {
                // Use merged prompt for expansion + parsing
                prompt = PARSE_PROMPT_WITH_EXPAND.formatted(uq, history);
            }
        } else {
            // Text search mode: no expansion, use simple parse prompt
            prompt = PARSE_PROMPT.formatted(uq, history);
        }

        String raw = llm.askPlain(prompt, model, apiKey, questionId);
        GeoIntent intent = safeParseIntent(raw);

        if (intent == null) intent = new GeoIntent();
        // Deterministic parsing for bbox/time if user typed it directly
        // OPTIMIZATION: Parallelize Space and Time normalization if both need LLM calls
        applyDeterministicSpaceTime(intent, apiKey, questionId);
        enforceRules(intent);
        return intent;
    }

    /** Refine previous intent given a new user message. */
    public GeoIntent refineIntent(GeoIntent prev, String userMessage, ConversationMemory memory, 
                                 String model, String apiKey, String questionId) {
        return refineIntent(prev, userMessage, memory, model, true, apiKey, questionId);
    }
    
    /** Refine previous intent given a new user message. */
    public GeoIntent refineIntent(GeoIntent prev, String userMessage, ConversationMemory memory, 
                                 String model, boolean useExpand, String apiKey, String questionId) {
        if (prev == null) prev = new GeoIntent();
        String um = userMessage == null ? "" : userMessage.trim();

        String expanded = useExpand ? expandQuery(um, model, apiKey, questionId) : um;
        String history = memory == null ? "" : safeHistory(memory);

        String prevJson;
        try {
            prevJson = om.writeValueAsString(prev);
        } catch (Exception e) {
            prevJson = "{}";
        }

        String prompt = REFINE_PROMPT.formatted(
                prevJson,
                expanded,
                um,
                history
        );

        String raw = llm.askPlain(prompt, model, apiKey, questionId);
        GeoIntent intent = safeParseIntent(raw);
        if (intent == null) intent = prev;

        applyDeterministicSpaceTime(intent, apiKey, questionId);
        enforceRules(intent);
        return intent;
    }

    /**
     * Check if the user's question is related to data discovery.
     * If false, the orchestrator should answer directly using LLM without entering any agent.
     * 
     * @return true if related to data discovery, false otherwise
     */
    public boolean isDataDiscoveryRelated(String userMessage, ConversationMemory memory, 
                                         String model, String apiKey, String questionId) {
        if (userMessage == null || userMessage.trim().isEmpty()) return true; // Empty message, default to data discovery
        
        String msg = userMessage.trim();
        String history = memory == null ? "" : safeHistory(memory);
        
        String prompt = DATA_DISCOVERY_CHECK_PROMPT.formatted(msg, history);
        String raw = llm.askPlain(prompt, model, apiKey, questionId);
        DataDiscoveryCheckResult result = safeParseDataDiscoveryCheck(raw);
        
        if (result == null) {
            // If parsing fails, default to data discovery related (safer)
            return true;
        }
        
        // Return true if related, false otherwise
        // Use a confidence threshold of 0.7 to avoid false negatives
        if (result.isRelated() && result.confidence >= 0.7) {
            return true;
        }
        if (!result.isRelated() && result.confidence >= 0.7) {
            return false;
        }
        
        // If confidence is low, default to data discovery related (safer)
        return true;
    }

    /**
     * Detect whether the new message should start a new conversation.
     * If true: orchestrator should reset memory + intent + pending HITL.
     */
    public boolean shouldStartNewConversation(GeoIntent prevIntent, ConversationMemory memory, 
                                             String userMessage, String model, String apiKey, String questionId) {
        String msg = userMessage == null ? "" : userMessage.trim();
        if (msg.isEmpty()) return false;

        // strong heuristic
        if (USER_SAYS_NEW.matcher(msg).find()) return true;

        // If no previous context, cannot be "new"
        if (prevIntent == null || memory == null || memory.size() == 0) return false;

        String prevJson;
        try { prevJson = om.writeValueAsString(prevIntent); }
        catch (Exception e) { prevJson = "{}"; }

        String history = safeHistory(memory);
        String prompt = NEW_CONVERSATION_PROMPT.formatted(prevJson, history, msg);

        String raw = llm.askPlain(prompt, model, apiKey, questionId);
        BoundaryDecision d = safeParseBoundary(raw);

        if (d == null) return false;
        if (d.isNew() && d.confidence >= 0.65) return true;

        // fallback heuristic: if message has many new constraints and doesn't look like a HITL answer
        boolean looksKeyval = KEYVAL_ANY.matcher(msg).find();
        boolean shortAnswer = msg.length() <= 4 && msg.matches("\\d+");
        if (!shortAnswer && looksKeyval && d.isNew()) return true;

        return false;
    }

    /**
     * Enforce post-processing rules:
     * - Any dimension is allowed; do NOT require topic/dataset.
     * - Ensure needs_clarification is true whenever a dimension is present (we will ask user to confirm).
     * - If nothing extracted, add guidance questions.
     */
    public void enforceRules(GeoIntent intent) {
        if (intent == null) return;

        // normalize "present => needs_clarification"
        normalizeEntityDim(intent.getTopic());
        normalizeEntityDim(intent.getFormat());
        normalizeEntityDim(intent.getLicense());
        normalizeEntityDim(intent.getOrganization());
        // Source is not normalized here - it is selected via the frontend Data Catalog Selection panel, not from user input
        normalizeSpaceDim(intent.getSpace());
        normalizeTimeDim(intent.getTime());

        boolean any = hasAnySemantic(intent);
        if (!any) {
            // Nothing detected: show all dimension examples
            List<String> q = new ArrayList<>();
            q.add("I couldn't detect any discovery constraints yet.");
            q.add("You can mention ANY dimension, for example:");
            q.add("- Topic: urban heat island");
            q.add("- Format: GeoTIFF");
            q.add("- License: CC-BY-4.0");
            q.add("- Organization: USGS");
            q.add("- Space: Pennsylvania, USA (or bounding box: -80,39,-74,42)");
            q.add("- Time: 2018-01-01 to 2020-12-31 (or year: 2020)");
            q.add("Note: You can also select data sources via Data Catalog Selection in Advanced Settings.");
            intent.setQuestionsForUser(q);
            intent.setOverallConfidence(0.0);
        } else {
            // Detected dimensions: only ask about the detected dimensions
            List<String> q = buildQuestionsForDetectedDimensions(intent);
            intent.setQuestionsForUser(q);
            if (intent.getOverallConfidence() <= 0.0) intent.setOverallConfidence(0.6);
        }
    }

    /**
     * Build questions only for detected dimensions that need clarification.
     * If a dimension is detected, it will be handled by candidate selection, so don't add it to questions_for_user.
     * Only add questions for dimensions that need explicit user input (e.g., Space/Time without bbox/range).
     */
    private List<String> buildQuestionsForDetectedDimensions(GeoIntent intent) {
        List<String> q = new ArrayList<>();
        
        // Topic, Format, License, Organization: if detected, they will be handled by candidate selection
        // Source: not extracted from user input - it is selected via the frontend Data Catalog Selection panel
        // Don't add them to questions_for_user
        
        // Space: only ask if bbox is missing (needs explicit input)
        if (intent.getSpace() != null && hasText(intent.getSpace().getValue(), intent.getSpace().getRawText())) {
            String text = bestText(intent.getSpace().getValue(), intent.getSpace().getRawText());
            if (intent.getSpace().getBbox() == null || intent.getSpace().getBbox().length != 4) {
                q.add("Please confirm the Space location: " + (text == null ? "" : text));
            }
        }
        
        // Time: only ask if start/end is missing (needs explicit input)
        if (intent.getTime() != null && hasText(intent.getTime().getRawText(), intent.getTime().getStart(), intent.getTime().getEnd())) {
            String text = intent.getTime().getRawText();
            if (text == null || text.isBlank()) {
                text = intent.getTime().getStart() + " to " + intent.getTime().getEnd();
            }
            if (intent.getTime().getStart() == null || intent.getTime().getEnd() == null) {
                q.add("Please confirm the Time range: " + (text == null ? "" : text));
            }
        }
        
        return q;
    }
    
    private String bestText(String v, String raw) {
        if (v != null && !v.trim().isEmpty()) return v;
        return raw == null ? "" : raw;
    }

    // -----------------------
    // Helpers
    // -----------------------

    private String expandQuery(String userQuery, String model, String apiKey, String questionId) {
        if (userQuery == null) return "";
        String uq = userQuery.trim();
        if (uq.isEmpty()) return "";

        // If user already uses explicit key=value, expansion may be unnecessary; still useful for translation, but keep light.
        boolean hasKeyVal = KEYVAL_ANY.matcher(uq).find();
        if (hasKeyVal && uq.length() < 25) {
            // short explicit constraint: keep as-is
            return uq;
        }

        String prompt = EXPAND_PROMPT.formatted(uq);
        String raw = llm.askPlain(prompt, model, apiKey, questionId);
        try {
            JsonExpand ex = safeParseExpand(raw);
            if (ex != null) {
                String expanded = ex.getExpanded();
                if (expanded != null && !expanded.isBlank()) {
                    return expanded.trim();
                }
            }
        } catch (Exception ignored) {}
        return uq;
    }

    private String safeHistory(ConversationMemory memory) {
        if (memory == null) return "";
        // Keep short to avoid context bloat
        return memory.formatRecentAsText(8);
    }

    private GeoIntent safeParseIntent(String raw) {
        if (raw == null) return null;
        String json = extractJsonObject(raw);
        if (json == null) return null;
        try {
            return om.readValue(json, GeoIntent.class);
        } catch (Exception e) {
            return null;
        }
    }

    private BoundaryDecision safeParseBoundary(String raw) {
        if (raw == null) return null;
        String json = extractJsonObject(raw);
        if (json == null) return null;
        try {
            return om.readValue(json, BoundaryDecision.class);
        } catch (Exception e) {
            return null;
        }
    }

    private JsonExpand safeParseExpand(String raw) {
        if (raw == null) return null;
        String json = extractJsonObject(raw);
        if (json == null) return null;
        try {
            return om.readValue(json, JsonExpand.class);
        } catch (Exception e) {
            return null;
        }
    }

    private DataDiscoveryCheckResult safeParseDataDiscoveryCheck(String raw) {
        if (raw == null) return null;
        String json = extractJsonObject(raw);
        if (json == null) return null;
        try {
            return om.readValue(json, DataDiscoveryCheckResult.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractJsonObject(String s) {
        if (s == null) return null;
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        return s.substring(a, b + 1).trim();
    }

    private boolean hasAnySemantic(GeoIntent i) {
        if (i == null) return false;
        if (hasText(i.getTopic())) return true;
        if (hasText(i.getFormat())) return true;
        if (hasText(i.getLicense())) return true;
        if (hasText(i.getOrganization())) return true;
        // Source is not checked here - it is selected via the frontend Data Catalog Selection panel, not from user input
        if (i.getSpace() != null && hasText(i.getSpace().getValue(), i.getSpace().getRawText())) return true;
        if (i.getTime() != null && hasText(i.getTime().getRawText(), i.getTime().getStart(), i.getTime().getEnd())) return true;
        return false;
    }

    private boolean hasText(GeoIntent.EntityDim d) {
        if (d == null) return false;
        return hasText(d.getValue(), d.getRawText());
    }

    private boolean hasText(String... ss) {
        if (ss == null) return false;
        for (String s : ss) {
            if (s != null && !s.trim().isEmpty()) return true;
        }
        return false;
    }

    private void normalizeEntityDim(GeoIntent.EntityDim d) {
        if (d == null) return;
        String v = safeEnglish(d.getValue());
        String r = safeEnglish(d.getRawText());
        if (v != null) d.setValue(v);
        if (r != null) d.setRawText(r);

        if (hasText(d)) {
            // If confidence < 0.5, require HITL confirmation
            if (d.getConfidence() < 0.5) {
                d.setNeedsClarification(true);
            } else {
                d.setNeedsClarification(true); // Default to true, can be overridden
            }
            if (d.getConfidence() <= 0.0) d.setConfidence(0.6);
            // kg_node_id stays null until HITL
            d.setKgNodeId(null);
        }
    }

    private void normalizeSpaceDim(GeoIntent.SpaceDim s) {
        if (s == null) return;
        String v = safeEnglish(s.getValue());
        String r = safeEnglish(s.getRawText());
        if (v != null) s.setValue(v);
        if (r != null) s.setRawText(r);

        if (hasText(s.getValue(), s.getRawText()) || (s.getBbox() != null && s.getBbox().length == 4)) {
            // If confidence < 0.5, require HITL confirmation
            if (s.getConfidence() < 0.5) {
                s.setNeedsClarification(true);
            } else {
                s.setNeedsClarification(true); // Default to true, can be overridden
            }
            if (s.getConfidence() <= 0.0) s.setConfidence(0.6);
            s.setKgNodeId(null);
        }
    }

    private void normalizeTimeDim(GeoIntent.TimeDim t) {
        if (t == null) return;
        String r = safeEnglish(t.getRawText());
        if (r != null) t.setRawText(r);

        if (hasText(t.getRawText(), t.getStart(), t.getEnd())) {
            // If confidence < 0.5, require HITL confirmation
            if (t.getConfidence() < 0.5) {
                t.setNeedsClarification(true);
            } else {
                t.setNeedsClarification(true); // Default to true, can be overridden
            }
            if (t.getConfidence() <= 0.0) t.setConfidence(0.6);
            if (t.getType() == null || t.getType().isBlank()) t.setType("unspecified");
            if (t.getGranularity() == null || t.getGranularity().isBlank()) t.setGranularity("unspecified");
        }
    }

    /**
     * Normalize Space and Time dimensions:
     * - Space: convert place names to bbox format [minLon, minLat, maxLon, maxLat] (EPSG:4326)
     * - Time: convert time expressions to standard format YYYYMMDD HH:mm:ss
     * Uses deterministic parsing first, then LLM fallback if needed.
     * 
     * OPTIMIZATION: Parallelize Space and Time normalization if both require LLM calls.
     */
    private void applyDeterministicSpaceTime(GeoIntent intent, String apiKey, String questionId) {
        if (intent == null) return;

        // Prepare Space and Time normalization tasks
        CompletableFuture<SpaceTimeNormalizationService.NormalizedSpace> spaceTask = null;
        CompletableFuture<SpaceTimeNormalizationService.NormalizedTime> timeTask = null;
        
        boolean needsSpaceLLM = false;
        boolean needsTimeLLM = false;
        GeoIntent.SpaceDim spaceDim = null;
        GeoIntent.TimeDim timeDim = null;

        // SPACE: normalize place name to bbox (hard filter, no embedding)
        if (intent.getSpace() != null) {
            GeoIntent.SpaceDim s = intent.getSpace();
            spaceDim = s;
            // If bbox already exists, skip
            if (s.getBbox() == null || s.getBbox().length != 4) {
                String txt = hasText(s.getRawText()) ? s.getRawText() : (hasText(s.getValue()) ? s.getValue() : null);
                if (txt != null && !txt.trim().isEmpty()) {
                    // Try deterministic parsing first
                    double[] bb = SpaceTimeNormalizationService.parseBbox(txt);
                    if (bb != null) {
                        s.setBbox(bb);
                    } else {
                        // Need LLM fallback: normalize place name to bbox
                        needsSpaceLLM = true;
                        spaceTask = CompletableFuture.supplyAsync(() -> 
                            spaceTimeNormalizer.normalizeSpace(txt, null, apiKey, questionId)
                        );
                    }
                }
            }
        }

        // TIME: normalize time expression to standard format (hard filter, no embedding)
        if (intent.getTime() != null) {
            GeoIntent.TimeDim t = intent.getTime();
            timeDim = t;
            // If start/end already exist, skip
            if ((t.getStart() == null || t.getEnd() == null) && hasText(t.getRawText())) {
                // Try deterministic parsing first
                SpaceTimeNormalizationService.NormalizedTime nt =
                        SpaceTimeNormalizationService.parseDeterministicTime(t.getRawText());
                if (nt != null && nt.start() != null && nt.end() != null) {
                    t.setType(nt.type());
                    t.setStart(nt.start());
                    t.setEnd(nt.end());
                    t.setGranularity(nt.granularity());
                    t.setConfidence(Math.max(t.getConfidence(), nt.confidence()));
                } else {
                    // Need LLM fallback: normalize time expression
                    needsTimeLLM = true;
                    timeTask = CompletableFuture.supplyAsync(() -> 
                        spaceTimeNormalizer.normalizeTime(t.getRawText(), null, apiKey, questionId)
                    );
                }
            }
        }

        // OPTIMIZATION: Wait for all LLM tasks to complete in parallel
        if (needsSpaceLLM && needsTimeLLM) {
            // Both need LLM - wait for both in parallel
            CompletableFuture.allOf(spaceTask, timeTask).join();
            try {
                // Apply Space result
                SpaceTimeNormalizationService.NormalizedSpace ns = spaceTask.get();
                if (ns != null && ns.bbox() != null && ns.bbox().length == 4) {
                    spaceDim.setBbox(ns.bbox());
                    if (ns.nameEn() != null && !ns.nameEn().isEmpty()) {
                        spaceDim.setValue(ns.nameEn());
                    }
                }
                // Apply Time result
                SpaceTimeNormalizationService.NormalizedTime nt = timeTask.get();
                if (nt != null && nt.start() != null && nt.end() != null) {
                    timeDim.setType(nt.type());
                    timeDim.setStart(nt.start());
                    timeDim.setEnd(nt.end());
                    timeDim.setGranularity(nt.granularity());
                    timeDim.setConfidence(Math.max(timeDim.getConfidence(), nt.confidence()));
                }
            } catch (Exception e) {
                System.out.println("[Intent] Warning: Failed to apply Space/Time normalization: " + e.getMessage());
            }
        } else if (needsSpaceLLM && spaceTask != null && spaceDim != null) {
            // Only Space needs LLM
            try {
                SpaceTimeNormalizationService.NormalizedSpace ns = spaceTask.get();
                if (ns != null && ns.bbox() != null && ns.bbox().length == 4) {
                    spaceDim.setBbox(ns.bbox());
                    if (ns.nameEn() != null && !ns.nameEn().isEmpty()) {
                        spaceDim.setValue(ns.nameEn());
                    }
                }
            } catch (Exception e) {
                System.out.println("[Intent] Warning: Failed to apply Space normalization: " + e.getMessage());
            }
        } else if (needsTimeLLM && timeTask != null && timeDim != null) {
            // Only Time needs LLM
            try {
                SpaceTimeNormalizationService.NormalizedTime nt = timeTask.get();
                if (nt != null && nt.start() != null && nt.end() != null) {
                    timeDim.setType(nt.type());
                    timeDim.setStart(nt.start());
                    timeDim.setEnd(nt.end());
                    timeDim.setGranularity(nt.granularity());
                    timeDim.setConfidence(Math.max(timeDim.getConfidence(), nt.confidence()));
                }
            } catch (Exception e) {
                System.out.println("[Intent] Warning: Failed to apply Time normalization: " + e.getMessage());
            }
        }
    }

    /**
     * Best-effort: try to keep outputs English-ish. We do not do strict validation,
     * but we can normalize whitespace and trim.
     */
    private String safeEnglish(String s) {
        if (s == null) return null;
        String t = s.trim().replaceAll("\\s+", " ");
        if (t.isEmpty()) return null;
        // Avoid accidentally returning Russian guidance etc.
        // We do not block non-ascii; translation is handled by expandQuery prompt.
        return t;
    }

    // small DTOs for parsing JSON responses
    private static class JsonExpand {
        public String expanded_query;
        public String expandedQuery; // allow either name

        public String getExpanded() {
            if (expandedQuery != null && !expandedQuery.isBlank()) return expandedQuery;
            return expanded_query;
        }
    }

    private static class BoundaryDecision {
        public boolean is_new_conversation;
        public boolean isNewConversation;
        public double confidence;
        @SuppressWarnings("unused")
        public String reason;

        public boolean isNew() {
            return isNewConversation || is_new_conversation;
        }
    }

    private static class DataDiscoveryCheckResult {
        public boolean is_data_discovery_related;
        public boolean isDataDiscoveryRelated;
        public double confidence;
        @SuppressWarnings("unused")
        public String reason;

        public boolean isRelated() {
            return isDataDiscoveryRelated || is_data_discovery_related;
        }
    }

    private static final String QUESTION_TYPE_CHECK_PROMPT = """
            You are a question classification agent for an Intelligent Geospatial Data Discovery Assistant.

            TASK:
            Classify the user's question into one of these categories:
            1. "greeting_introduction" - User is greeting, asking who you are, what you can do, or asking for an introduction
               Examples: "hi", "hello", "who are you", "what can you do", "introduce yourself", "what is igdd", "what are you", "what do you do"
            2. "example_request" - User is asking for example queries or sample questions
               Examples: "can you generate some examples", "show me example queries", "give me some sample questions", "give me some examples", "show examples", "generate examples"
            3. "other" - Any other type of question

            IMPORTANT:
            - Classify as "greeting_introduction" if the question asks about the assistant's identity, capabilities, or is a greeting
              - "What can you do?" -> "greeting_introduction"
              - "Who are you?" -> "greeting_introduction"
              - "What do you do?" -> "greeting_introduction"
            - Classify as "example_request" if the question asks for example queries, sample questions, or example searches
              - "Give me some examples" -> "example_request"
              - "Show me examples" -> "example_request"
              - "Can you generate examples?" -> "example_request"
            - If the question is about actual data discovery (e.g., "find datasets", "I need land cover data"), classify as "other"
            - Be accurate: classify based on the actual intent of the question

            OUTPUT FORMAT:
            You must output valid JSON only (no markdown, no explanation):
            {
                "question_type": "greeting_introduction" | "example_request" | "other",
                "confidence": 0.0-1.0
            }

            User message: %s
            Recent conversation history: %s
            """;

    /**
     * Classify the user's question type using LLM.
     * 
     * @return QuestionTypeResult with the classified type, or null if parsing fails
     */
    public QuestionTypeResult classifyQuestionType(String userMessage, ConversationMemory memory, 
                                                   String model, String apiKey, String questionId) {
        if (userMessage == null || userMessage.trim().isEmpty()) {
            return new QuestionTypeResult("other", 1.0);
        }
        
        String msg = userMessage.trim();
        String history = memory == null ? "" : safeHistory(memory);
        
        String prompt = QUESTION_TYPE_CHECK_PROMPT.formatted(msg, history);
        String raw = llm.askPlain(prompt, model, apiKey, questionId);
        QuestionTypeResult result = safeParseQuestionType(raw);
        
        if (result == null) {
            // If parsing fails, default to "other"
            return new QuestionTypeResult("other", 0.5);
        }
        
        return result;
    }

    private QuestionTypeResult safeParseQuestionType(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String json = extractJsonObject(raw);
        if (json == null) return null;
        try {
            return om.readValue(json, QuestionTypeResult.class);
        } catch (Exception e) {
            System.out.println("[Intent] Warning: Failed to parse question type result: " + e.getMessage());
            return null;
        }
    }

    public static class QuestionTypeResult {
        public String question_type;
        public double confidence;

        public QuestionTypeResult() {}

        public QuestionTypeResult(String question_type, double confidence) {
            this.question_type = question_type;
            this.confidence = confidence;
        }

        public boolean isGreetingOrIntroduction() {
            return "greeting_introduction".equals(question_type);
        }

        public boolean isExampleRequest() {
            return "example_request".equals(question_type);
        }

        public boolean isOther() {
            return "other".equals(question_type) || question_type == null;
        }
    }

    // make shouldStartNewConversation use the normalized fields
    public boolean shouldStartNewConversationInternal(GeoIntent prevIntent, ConversationMemory memory, String userMessage, String model, String apiKey, String questionId) {
        return shouldStartNewConversation(prevIntent, memory, userMessage, model, apiKey, questionId);
    }
}

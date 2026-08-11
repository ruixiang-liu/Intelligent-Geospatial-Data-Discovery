package edu.psu.giscience.igdd.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.psu.giscience.igdd.domain.graphrag.DatasetBundle;
import edu.psu.giscience.igdd.domain.graphrag.EvidencePack;
import edu.psu.giscience.igdd.domain.graphrag.SynthesisResult;
import edu.psu.giscience.igdd.domain.intent.GeoIntent;
import edu.psu.giscience.igdd.llm.LlmClientService;
import edu.psu.giscience.igdd.memory.ConversationMemory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

@Service
public class AnswerSynthesisService {

    private final LlmClientService llm;
    private final ObjectMapper om = new ObjectMapper();

    // Cyrillic block (covers Russian etc.)
    private static final Pattern CYRILLIC = Pattern.compile("[\\u0400-\\u04FF]");

    public AnswerSynthesisService(LlmClientService llm) {
        this.llm = llm;
    }

    public SynthesisResult synthesize(String userQuery,
                                      GeoIntent intent,
                                      EvidencePack pack,
                                      ConversationMemory memory,
                                      String model,
                                      boolean useKeywords,
                                      String apiKey,
                                      String questionId) {
        try {
            String intentJson = om.writeValueAsString(intent);

            // IMPORTANT: do NOT dump entire evidence.subgraph into the prompt (can exceed context).
            Map<String, Object> compactEvidence = buildCompactEvidence(pack);
            String evidenceJson = om.writeValueAsString(compactEvidence);

            String history = compactHistory(memory, 6, 2600);

            String prompt = """
You are the Answer Synthesis Agent in a GraphRAG dataset discovery system.

CRITICAL OUTPUT RULES:
1) Output MUST be English only.
2) Do NOT output JSON, code, markdown, or bullet-only lists.
3) Keep it concise and helpful.

You will receive:
- User query
- Structured intent (HITL-resolved as much as possible)
- Compact evidence (top datasets + brief linked attributes + short context)
- Recent conversation history
- useKeywords flag

Task:
1) Answer in English.
2) Provide a brief, concise summary of the search results. Do NOT list individual datasets with detailed descriptions.
3) Keep it short (2-3 sentences maximum). Simply acknowledge that datasets were found and mention the key dimensions that matched (e.g., "Found datasets matching your topic and time constraints").
4) If matches are broad or uncertain, state the key dimensions that were not well matched.

[useKeywords]
""" + useKeywords + """

[User query]
""" + (userQuery == null ? "" : userQuery) + """

[Intent JSON]
""" + intentJson + """

[Compact Evidence JSON]
""" + evidenceJson + """

[Recent history]
""" + history + """
""";

            String reply = llm.askPlain(prompt, model, apiKey, questionId);
            if (reply == null) reply = "";

            // If upstream returned an error string, fall back to deterministic summary
            String low = reply.toLowerCase(Locale.ROOT);
            if (low.startsWith("error:") || low.startsWith("llm error:") || low.startsWith("llm exception:") || low.contains("context_length_exceeded")) {
                return new SynthesisResult(fallbackSummary(pack, reply));
            }

            reply = reply.trim();

            // Enforce English: if Cyrillic detected, translate to English (2nd pass).
            if (containsCyrillic(reply)) {
                reply = translateToEnglish(reply, model, apiKey, questionId).trim();
            }

            // If still empty, deterministic fallback
            if (reply.isBlank()) {
                return new SynthesisResult(fallbackSummary(pack, "Empty synthesis output"));
            }

            return new SynthesisResult(reply);
        } catch (Exception e) {
            return new SynthesisResult(
                    "I completed GraphRAG retrieval, but encountered an internal error during answer synthesis. " +
                            "Please try again with a clearer constraint, for example: Topic, Space, Time, Format, License, Organization, or Source."
            );
        }
    }

    private boolean containsCyrillic(String s) {
        if (s == null || s.isBlank()) return false;
        return CYRILLIC.matcher(s).find();
    }

    private String translateToEnglish(String text, String model, String apiKey, String questionId) {
        String prompt = """
Translate the following text into fluent English.

Rules:
- Output MUST be English only.
- Preserve meaning.
- Do NOT output JSON, code, or markdown.

TEXT:
""" + (text == null ? "" : text);
        String out = llm.askPlain(prompt, model, apiKey, questionId);
        return out == null ? "" : out;
    }

    private Map<String, Object> buildCompactEvidence(EvidencePack pack) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (pack == null) {
            out.put("context_text", "");
            out.put("top_datasets", List.of());
            return out;
        }

        Object ctx = (pack.evidence() == null) ? null : pack.evidence().get("context_text");
        String context = (ctx == null) ? "" : String.valueOf(ctx);
        //context = trim(context, 1800);

        List<DatasetBundle> bundles = pack.datasets() == null ? List.of() : pack.datasets();
        List<Map<String, Object>> top = new ArrayList<>();

        for (int i = 0; i < Math.min(8, bundles.size()); i++) {
            top.add(compactBundle(bundles.get(i)));
        }

        out.put("context_text", context);
        out.put("top_datasets", top);
        return out;
    }

    private Map<String, Object> compactBundle(DatasetBundle b) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (b == null) return m;

        Map<String, Object> p = b.datasetProps() == null ? Map.of() : b.datasetProps();

        m.put("id", b.datasetId());
        m.put("title", pick(p, "title", "name", "id"));
        //m.put("description", trim(String.valueOf(p.getOrDefault("description", p.getOrDefault("notes", ""))), 260));
        m.put("description", String.valueOf(p.getOrDefault("description", p.getOrDefault("notes", ""))));

        // a few linked attrs
        m.put("source", firstLinkedName(b, "Source"));
        m.put("organization", firstLinkedName(b, "Organization"));
        m.put("format", firstLinkedName(b, "Format"));
        m.put("license", firstLinkedName(b, "License"));

        return m;
    }

    private String firstLinkedName(DatasetBundle b, String label) {
        if (b == null || b.linkedEntities() == null) return "";
        var list = b.linkedEntities().get(label);
        if (list == null || list.isEmpty()) return "";
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> map)) return "";
        Object propsObj = map.get("props");
        if (propsObj instanceof Map<?, ?> props) {
            Object name = props.get("name");
            if (name == null) name = props.get("title");
            if (name == null) name = props.get("value");
            return name == null ? "" : String.valueOf(name);
        }
        return "";
    }

    private String pick(Map<String, Object> p, String... keys) {
        for (String k : keys) {
            Object v = p.get(k);
            if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v);
        }
        return "";
    }

    private String compactHistory(ConversationMemory memory, int turns, int maxLenPerTurn) {
        if (memory == null) return "";
        List<ConversationMemory.Turn> t = memory.getTurns();
        if (t == null || t.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, t.size() - turns);
        for (int i = start; i < t.size(); i++) {
            ConversationMemory.Turn x = t.get(i);
            String role = x.getRole() == null ? "" : x.getRole();
            String text = x.getText() == null ? "" : x.getText();
            String line = role + ": " + trim(text, maxLenPerTurn);
            sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }

    private String fallbackSummary(EvidencePack pack, String err) {
        StringBuilder sb = new StringBuilder();
        sb.append("I retrieved datasets from the knowledge graph, but the LLM synthesis step failed.\n");
        if (err != null && !err.isBlank()) sb.append(trim(err, 220)).append("\n\n");

        List<DatasetBundle> ds = pack == null || pack.datasets() == null ? List.of() : pack.datasets();
        if (ds.isEmpty()) {
            sb.append("""
I don't have dataset details to summarize right now.

You can refine by ANY dimension, for example:
- Topic: ...
- Format: ...
- License: ...
- Organization: ...
- Source: ...
- Space: ... (or bounding box: minLon,minLat,maxLon,maxLat)
- Time: YYYY-MM-DD to YYYY-MM-DD
""".trim());
            return sb.toString().trim();
        }

        sb.append("Top datasets (preview):\n");
        int n = Math.min(5, ds.size());
        for (int i = 0; i < n; i++) {
            DatasetBundle b = ds.get(i);
            Map<String, Object> p = b.datasetProps() == null ? Map.of() : b.datasetProps();
            String title = pick(p, "title", "name", "id");
            sb.append(i + 1).append(") ").append(title).append("\n");
        }

        sb.append("\nIf you want, you can narrow by space/time/format/license, or ask: 'show details for dataset 1'.");
        return sb.toString().trim();
    }

    private String trim(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }
}

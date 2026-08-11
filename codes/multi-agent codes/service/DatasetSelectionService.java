package edu.psu.giscience.igdd.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.psu.giscience.igdd.domain.graphrag.DatasetBundle;
import edu.psu.giscience.igdd.domain.intent.GeoIntent;
import edu.psu.giscience.igdd.llm.LlmClientService;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for LLM-based dataset selection and ranking.
 * Selects top 10 datasets from up to 20 candidates and provides selection reasons.
 */
@Service
public class DatasetSelectionService {

    private final LlmClientService llm;
    private final ObjectMapper om = new ObjectMapper();

    public DatasetSelectionService(LlmClientService llm) {
        this.llm = llm;
    }

    /**
     * Selects top 10 datasets from up to 20 candidates using LLM, and provides selection reasons.
     * Even if datasets <= 10, still calls LLM to generate "Why it matches" reasons.
     * 
     * @param bundles Up to 20 candidate datasets (already sorted by score)
     * @param userQuery Original user query
     * @param intent Parsed geo intent
     * @param model LLM model to use
     * @return List of selected datasets (up to 10) with LLM selection reasons, sorted by relevance
     */
    public List<DatasetBundle> selectTopDatasets(List<DatasetBundle> bundles,
                                                 String userQuery,
                                                 GeoIntent intent,
                                                 String model,
                                                 String apiKey,
                                                 String questionId) {
        if (bundles == null || bundles.isEmpty()) {
            return List.of();
        }

        // Even if datasets <= 10, still call LLM to generate "Why it matches" reasons
        // The LLM will select all datasets (or up to 10) and provide reasons for each

        try {
            // Build compact dataset summaries for LLM
            StringBuilder datasetSummaries = new StringBuilder();
            Map<String, Integer> datasetIndexMap = new HashMap<>();
            
            for (int i = 0; i < bundles.size() && i < 20; i++) {
                DatasetBundle b = bundles.get(i);
                datasetIndexMap.put(b.datasetId(), i);
                
                String title = extractTitle(b);
                String description = extractDescription(b);
                Double score = b.matchScore();
                Map<String, Double> dimScores = b.dimensionScores();
                
                datasetSummaries.append(i + 1).append(". Dataset ID: ").append(b.datasetId())
                        .append("\n   Title: ").append(title)
                        .append("\n   Description: ").append(description.length() > 200 ? description.substring(0, 200) + "..." : description)
                        .append("\n   Match Score: ").append(score != null ? String.format("%.3f", score) : "N/A");
                
                if (dimScores != null && !dimScores.isEmpty()) {
                    datasetSummaries.append("\n   Dimension Scores: ");
                    List<String> dimParts = new ArrayList<>();
                    for (Map.Entry<String, Double> entry : dimScores.entrySet()) {
                        dimParts.add(entry.getKey() + "=" + String.format("%.2f", entry.getValue()));
                    }
                    datasetSummaries.append(String.join(", ", dimParts));
                }
                datasetSummaries.append("\n\n");
            }

            String intentJson = om.writeValueAsString(intent);

            String prompt = """
You are a dataset selection agent. Your task is to select the top 10 most relevant datasets from the provided candidates based on the user's query and intent.

CRITICAL OUTPUT RULES:
1) Output MUST be valid JSON only, no other text.
2) Output format:
{
  "selected_datasets": [
    {
      "dataset_id": "elementId1",
      "rank": 1,
      "reasons": ["reason1", "reason2", "reason3"]
    },
    {
      "dataset_id": "elementId2",
      "rank": 2,
      "reasons": ["reason1", "reason2", "reason3"]
    },
    ...
  ]
}
3) Select exactly 10 datasets (or all datasets if fewer than 10 candidates provided).
4) Rank them from 1 (most relevant) to N (least relevant among selected, where N is the number of selected datasets).
5) Provide 2-3 concise reasons (each as a string) for why each dataset was selected ("Why it matches").
6) Reasons should be specific to the dataset and query, e.g., "Strong topic match with 'climate change'", "Covers the requested time period 2020-2023", "High-quality data from authoritative source".
7) Output MUST be English only.
8) If there are 10 or fewer datasets, select ALL of them and provide reasons for each.

[User Query]
""" + (userQuery == null ? "" : userQuery) + """

[Intent JSON]
""" + intentJson + """

[Candidate Datasets (up to 20)]
""" + datasetSummaries.toString() + """

Select the top 10 most relevant datasets (or all datasets if there are 10 or fewer) and provide reasons for each selection.
""";

            String reply = llm.askPlain(prompt, model, apiKey, questionId);
            if (reply == null || reply.isBlank()) {
                // Fallback: return top 10 by score
                return bundles.subList(0, Math.min(10, bundles.size()));
            }

            // Parse LLM response
            List<DatasetBundle> selected = parseSelectionResponse(reply, bundles, datasetIndexMap);
            
            // If parsing failed, fallback to all bundles (or top 10 if more than 10)
            if (selected.isEmpty()) {
                // If we have <= 10 bundles, return all; otherwise return top 10
                int limit = Math.min(10, bundles.size());
                return bundles.subList(0, limit);
            }
            
            // If we got fewer results than expected, but have some results, return what we got
            // (This handles cases where LLM selected fewer than requested)
            return selected;

        } catch (Exception e) {
            System.out.println("DatasetSelectionService error: " + e.getMessage());
            e.printStackTrace();
            // Fallback: return top 10 by score
            return bundles.subList(0, Math.min(10, bundles.size()));
        }
    }

    private List<DatasetBundle> parseSelectionResponse(String jsonResponse,
                                                       List<DatasetBundle> allBundles,
                                                       Map<String, Integer> indexMap) {
        List<DatasetBundle> selected = new ArrayList<>();
        
        try {
            // Try to extract JSON from response (might have extra text)
            String json = extractJsonFromResponse(jsonResponse);
            if (json == null || json.isBlank()) {
                return selected;
            }

            JsonNode root = om.readTree(json);
            JsonNode selectedDatasets = root.path("selected_datasets");
            
            if (!selectedDatasets.isArray()) {
                return selected;
            }

            // Create a map of datasetId -> DatasetBundle for quick lookup
            Map<String, DatasetBundle> bundleMap = new HashMap<>();
            for (DatasetBundle b : allBundles) {
                bundleMap.put(b.datasetId(), b);
            }

            // Process selected datasets in rank order
            List<Map.Entry<Integer, JsonNode>> rankedEntries = new ArrayList<>();
            for (JsonNode item : selectedDatasets) {
                int rank = item.path("rank").asInt(0);
                if (rank > 0) {
                    rankedEntries.add(new AbstractMap.SimpleEntry<>(rank, item));
                }
            }
            rankedEntries.sort(Comparator.comparingInt(Map.Entry::getKey));

            for (Map.Entry<Integer, JsonNode> entry : rankedEntries) {
                JsonNode item = entry.getValue();
                String datasetId = item.path("dataset_id").asText();
                
                if (datasetId == null || datasetId.isBlank()) {
                    continue;
                }

                DatasetBundle original = bundleMap.get(datasetId);
                if (original == null) {
                    continue;
                }

                // Extract reasons
                List<String> reasons = new ArrayList<>();
                JsonNode reasonsNode = item.path("reasons");
                if (reasonsNode.isArray()) {
                    for (JsonNode reason : reasonsNode) {
                        String reasonText = reason.asText();
                        if (reasonText != null && !reasonText.isBlank()) {
                            reasons.add(reasonText.trim());
                        }
                    }
                }

                // Limit to 2-3 reasons
                if (reasons.size() > 3) {
                    reasons = reasons.subList(0, 3);
                }

                // Create new DatasetBundle with LLM selection reasons
                DatasetBundle withReasons = new DatasetBundle(
                        original.datasetId(),
                        original.datasetProps(),
                        original.linkedEntities(),
                        original.matchScore(),
                        original.dimensionScores(),
                        reasons.isEmpty() ? null : reasons
                );

                selected.add(withReasons);
            }

        } catch (Exception e) {
            System.out.println("Failed to parse LLM selection response: " + e.getMessage());
            e.printStackTrace();
        }

        return selected;
    }

    private String extractJsonFromResponse(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }

        response = response.trim();

        // Try to find JSON object boundaries
        int startIdx = response.indexOf('{');
        int endIdx = response.lastIndexOf('}');

        if (startIdx >= 0 && endIdx > startIdx) {
            return response.substring(startIdx, endIdx + 1);
        }

        // If no JSON found, return original (might be valid JSON already)
        return response;
    }

    private String extractTitle(DatasetBundle bundle) {
        if (bundle.datasetProps() == null) {
            return "Unknown";
        }
        Object title = bundle.datasetProps().get("title");
        if (title != null) return title.toString();
        Object name = bundle.datasetProps().get("name");
        if (name != null) return name.toString();
        return "Untitled Dataset";
    }

    private String extractDescription(DatasetBundle bundle) {
        if (bundle.datasetProps() == null) {
            return "";
        }
        Object desc = bundle.datasetProps().get("description");
        if (desc != null) return desc.toString();
        return "";
    }
}

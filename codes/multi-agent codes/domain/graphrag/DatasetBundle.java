package edu.psu.giscience.igdd.domain.graphrag;

import java.util.List;
import java.util.Map;

/**
 * For frontend: full dataset info including connected entities.
 */
public record DatasetBundle(
        String datasetId, // elementId(Dataset) recommended
        Map<String, Object> datasetProps,
        // grouped by label: "Format", "Keyword", "License", "Organization", "Source", "Space", "Time", "Resource", "Topic"(if exists through other rels)
        Map<String, List<Map<String, Object>>> linkedEntities,
        // Match reason: score and matching dimensions
        Double matchScore,
        Map<String, Double> dimensionScores,  // e.g., {"Topic": 0.9, "Format": 0.05}
        List<String> llmSelectionReasons  // LLM selection reasons (2-3 items) for why this dataset was selected
) {
    public DatasetBundle(String datasetId, Map<String, Object> datasetProps, Map<String, List<Map<String, Object>>> linkedEntities) {
        this(datasetId, datasetProps, linkedEntities, null, null, null);
    }
    
    public DatasetBundle(String datasetId, Map<String, Object> datasetProps, Map<String, List<Map<String, Object>>> linkedEntities, Double matchScore, Map<String, Double> dimensionScores) {
        this(datasetId, datasetProps, linkedEntities, matchScore, dimensionScores, null);
    }
}

package edu.psu.giscience.igdd.domain.graphrag;

import java.util.List;
import java.util.Map;

public record GraphRetrievalResult(
        List<String> datasetIds,
        Map<String, Double> scoresByDatasetId,
        Map<String, Map<String, Double>> dimensionContributions  // Map<datasetId, Map<dimension, weightedContribution>>
) {
    public GraphRetrievalResult(List<String> datasetIds, Map<String, Double> scoresByDatasetId) {
        this(datasetIds, scoresByDatasetId, Map.of());
    }
}

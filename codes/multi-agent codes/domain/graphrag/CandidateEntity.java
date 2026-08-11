package edu.psu.giscience.igdd.domain.graphrag;

import java.util.Map;

public record CandidateEntity(
        String nodeId,
        String label,
        String name,
        double score,
        Map<String, Object> props
) {}

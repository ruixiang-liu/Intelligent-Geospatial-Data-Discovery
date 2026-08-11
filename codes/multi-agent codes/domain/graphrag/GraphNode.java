package edu.psu.giscience.igdd.domain.graphrag;

import java.util.Map;

public record GraphNode(
        String id,
        String type,
        String label,
        Map<String, Object> props
) {}

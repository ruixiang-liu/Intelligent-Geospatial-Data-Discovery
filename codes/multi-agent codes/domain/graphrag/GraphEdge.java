package edu.psu.giscience.igdd.domain.graphrag;

import java.util.Map;

public record GraphEdge(
        String id,
        String source,
        String target,
        String type,
        Map<String, Object> props
) {}

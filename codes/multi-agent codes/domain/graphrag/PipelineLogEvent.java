package edu.psu.giscience.igdd.domain.graphrag;

public record PipelineLogEvent(
        String ts,
        String stage,
        String message
) {}

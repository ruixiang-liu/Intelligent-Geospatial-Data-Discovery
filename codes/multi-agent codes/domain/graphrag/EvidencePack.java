package edu.psu.giscience.igdd.domain.graphrag;

import java.util.List;
import java.util.Map;

/**
 * Two parts:
 * 1) datasets: for frontend structured display
 * 2) evidence: for frontend explanation + LLM grounding:
 *    {
 *      "context_text": "...",
 *      "subgraph": { "nodes": [...], "edges": [...] },
 *      "citations": [...]
 *    }
 */
public record EvidencePack(
        List<DatasetBundle> datasets,
        Map<String, Object> evidence
) {}

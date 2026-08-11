package edu.psu.giscience.igdd.service;

import edu.psu.giscience.igdd.domain.Hyperparameters;
import edu.psu.giscience.igdd.domain.graphrag.*;
import edu.psu.giscience.igdd.graph.Neo4jGraphRepository;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class EvidencePackBuilder {

    private final Neo4jGraphRepository repo;

    public EvidencePackBuilder(Neo4jGraphRepository repo) {
        this.repo = repo;
    }

    public EvidencePack build(List<String> datasetIds, Map<String, Double> scoresByDatasetId, Map<String, Map<String, Double>> dimensionContributions, Hyperparameters hyperparams) {
        List<String> ids = (datasetIds == null) ? new ArrayList<>() : new ArrayList<>(datasetIds);
        Map<String, Double> scores = (scoresByDatasetId == null) ? Map.<String, Double>of() : scoresByDatasetId;

        // If retrieval returned nothing, return empty bundles (no random sampling)
        // The frontend will display a message explaining why no results were found
        List<DatasetBundle> bundles = new ArrayList<>();
        if (!ids.isEmpty()) {
            List<DatasetBundle> rawBundles = repo.fetchDatasetBundles(ids);
            
            // Enrich bundles with score information and remove embeddings (not needed after retrieval)
            for (DatasetBundle b : rawBundles) {
                Double totalScore = scores.get(b.datasetId());
                Map<String, Double> dimScores = computeDimensionScores(b.datasetId(), dimensionContributions, hyperparams);
                
                // Remove embedding from datasetProps to save database space
                Map<String, Object> cleanedProps = new HashMap<>(b.datasetProps());
                cleanedProps.remove("embedding");
                
                // Remove embeddings from linkedEntities props as well
                Map<String, List<Map<String, Object>>> cleanedLinkedEntities = new LinkedHashMap<>();
                if (b.linkedEntities() != null) {
                    for (Map.Entry<String, List<Map<String, Object>>> entry : b.linkedEntities().entrySet()) {
                        List<Map<String, Object>> cleanedList = new ArrayList<>();
                        for (Map<String, Object> entity : entry.getValue()) {
                            Map<String, Object> cleanedEntity = new HashMap<>(entity);
                            Object propsObj = entity.get("props");
                            if (propsObj instanceof Map) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> props = new HashMap<>((Map<String, Object>) propsObj);
                                props.remove("embedding");
                                cleanedEntity.put("props", props);
                            }
                            cleanedList.add(cleanedEntity);
                        }
                        cleanedLinkedEntities.put(entry.getKey(), cleanedList);
                    }
                }
                
                bundles.add(new DatasetBundle(b.datasetId(), cleanedProps, cleanedLinkedEntities, totalScore, dimScores));
            }
        }

        // evidence.context_text (for LLM) + evidence.subgraph (for frontend)
        StringBuilder ctx = new StringBuilder();
        if (datasetIds == null || datasetIds.isEmpty()) {
            ctx.append("No datasets matched the current constraints. The query constraints may be too restrictive or no matching datasets exist in the knowledge graph.\n");
        } else {
            ctx.append("Retrieved datasets from KG:\n");
        }

        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        List<GraphEdge> edges = new ArrayList<>();

        for (int i = 0; i < bundles.size(); i++) {
            DatasetBundle b = bundles.get(i);

            String did = "dataset:" + b.datasetId();
            nodes.putIfAbsent(did, new GraphNode(did, "Dataset", displayLabel(b.datasetProps()), b.datasetProps()));

            Object title = b.datasetProps().getOrDefault("title", b.datasetProps().getOrDefault("name", ""));
            Object desc = b.datasetProps().getOrDefault("description", "");

            ctx.append(i + 1).append(") ")
                    .append(title == null ? "" : title.toString())
                    .append(" | ")
                    .append(desc == null ? "" : trim(desc.toString(), 220))
                    .append("\n");

            if (b.linkedEntities() == null) continue;

            // First pass: create nodes for all entities and edges for non-Format entities
            // Store Format entries for second pass (need to link from Resource, not Dataset)
            List<Map<String, Object>> formatEntries = new ArrayList<>();
            
            for (Map.Entry<String, List<Map<String, Object>>> entry : b.linkedEntities().entrySet()) {
                String type = entry.getKey();
                for (Map<String, Object> relEntry : entry.getValue()) {
                    String relType = String.valueOf(relEntry.get("rel"));

                    Object nodeIdObj = relEntry.get("node_id");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> props = (Map<String, Object>) relEntry.get("props");

                    String nid = type.toLowerCase() + ":" + (nodeIdObj == null ? UUID.randomUUID() : nodeIdObj.toString());
                    nodes.putIfAbsent(nid, new GraphNode(nid, type, displayLabel(props), props));

                    // Format nodes should be linked from Resource, not Dataset
                    // Store Format entries for second pass
                    if ("Format".equals(type) && relEntry.containsKey("resource_id")) {
                        formatEntries.add(relEntry);
                    } else {
                        // Create edge from Dataset to this entity
                        edges.add(new GraphEdge(
                                UUID.randomUUID().toString(),
                                did,
                                nid,
                                (relType == null || relType.isBlank()) ? "RELATED" : relType,
                                Map.of()
                        ));
                    }
                }
            }
            
            // Second pass: create Resource -> Format edges
            for (Map<String, Object> formatEntry : formatEntries) {
                String relType = String.valueOf(formatEntry.get("rel"));
                Object nodeIdObj = formatEntry.get("node_id");
                @SuppressWarnings("unchecked")
                Map<String, Object> props = (Map<String, Object>) formatEntry.get("props");
                Object resourceIdObj = formatEntry.get("resource_id");
                
                if (nodeIdObj == null || resourceIdObj == null) continue;
                
                String formatNid = "format:" + nodeIdObj.toString();
                String resourceNid = "resource:" + resourceIdObj.toString();
                
                // Find the resource node that matches this resource_id
                // Resource nodes should already be created in first pass
                if (nodes.containsKey(resourceNid)) {
                    // Create edge from Resource to Format
                    edges.add(new GraphEdge(
                            UUID.randomUUID().toString(),
                            resourceNid,
                            formatNid,
                            (relType == null || relType.isBlank()) ? "HAS_FORMAT" : relType,
                            Map.of()
                    ));
                }
            }
        }

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("context_text", ctx.toString().trim());
        evidence.put("subgraph", Map.of("nodes", nodes.values(), "edges", edges));
        evidence.put("citations", bundles.stream().map(DatasetBundle::datasetId).toList());

        return new EvidencePack(bundles, evidence);
    }

    private String displayLabel(Map<String, Object> props) {
        if (props == null) return "";
        Object name = props.get("name");
        if (name != null) return name.toString();
        Object title = props.get("title");
        if (title != null) return title.toString();
        Object value = props.get("value");
        if (value != null) return value.toString();
        Object id = props.get("id");
        return id == null ? "" : id.toString();
    }

    private String trim(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }
    
    /**
     * Compute dimension scores (raw scores) based on dimensionContributions from GraphRetrievalService.
     * dimensionContributions stores weighted contributions (rawScore * weight), so we divide by weight to get raw scores.
     */
    private Map<String, Double> computeDimensionScores(String datasetId, Map<String, Map<String, Double>> dimensionContributions, Hyperparameters hyperparams) {
        Map<String, Double> dimScores = new HashMap<>();
        if (dimensionContributions == null || dimensionContributions.isEmpty()) return dimScores;
        
        Map<String, Double> contributions = dimensionContributions.get(datasetId);
        if (contributions == null || contributions.isEmpty()) return dimScores;
        
        Hyperparameters h = (hyperparams != null) ? hyperparams : Hyperparameters.defaults();
        // Normalize weights to sum to 1.0 (frontend may send weights that don't sum to 1)
        h.normalizeWeights();
        // Validate hyperparameters
        h.validate();
        // Use the same weights as GraphRetrievalService to reverse-calculate raw scores
        final double wTopic = (h.weightTopic != null) ? h.weightTopic : 0.3;
        final double wFormat = (h.weightFormat != null) ? h.weightFormat : 0.1;
        final double wLicense = (h.weightLicense != null) ? h.weightLicense : 0.1;
        final double wOrg = (h.weightOrganization != null) ? h.weightOrganization : 0.1;
        final double wSpace = (h.weightSpace != null) ? h.weightSpace : 0.15;
        final double wTime = (h.weightTime != null) ? h.weightTime : 0.15;
        
        // Calculate raw scores from weighted contributions
        // dimContributions stores (rawScore * weight), so rawScore = dimContributions / weight
        for (Map.Entry<String, Double> entry : contributions.entrySet()) {
            String dim = entry.getKey();
            double weightedContribution = entry.getValue();
            double weight = 0.0;
            
            switch (dim) {
                case "Topic":
                    weight = wTopic;
                    break;
                case "Format":
                    weight = wFormat;
                    break;
                case "License":
                    weight = wLicense;
                    break;
                case "Organization":
                    weight = wOrg;
                    break;
                case "Space":
                    weight = wSpace;
                    break;
                case "Time":
                    weight = wTime;
                    break;
                default:
                    continue; // Skip unknown dimensions
            }
            
            if (weight > 0) {
                double rawScore = weightedContribution / weight;
                // Cap raw score at 1.0 (since it's normalized)
                dimScores.put(dim, Math.min(1.0, rawScore));
            }
        }
        
        return dimScores;
    }
}

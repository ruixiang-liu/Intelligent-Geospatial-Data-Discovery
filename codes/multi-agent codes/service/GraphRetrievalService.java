package edu.psu.giscience.igdd.service;

import edu.psu.giscience.igdd.domain.Hyperparameters;
import edu.psu.giscience.igdd.domain.graphrag.CandidateEntity;
import edu.psu.giscience.igdd.domain.graphrag.DatasetBundle;
import edu.psu.giscience.igdd.domain.graphrag.GraphRetrievalResult;
import edu.psu.giscience.igdd.domain.intent.GeoIntent;
import edu.psu.giscience.igdd.graph.Neo4jGraphRepository;
import edu.psu.giscience.igdd.llm.LlmClientService;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.Calendar;

@Service
public class GraphRetrievalService {

    private final Neo4jGraphRepository repo;
    private final LlmClientService llm;
    
    // Thread-local cache for topic/keyword candidates to avoid duplicate searches
    // Key: "topic:<text>:<useKeywords>" or "keyword:<text>:<useKeywords>"
    private final ThreadLocal<Map<String, List<CandidateEntity>>> candidateCache = ThreadLocal.withInitial(HashMap::new);
    
    // Thread-local cache for embeddings to avoid duplicate embedding generation
    // Key: "<text>"
    private final ThreadLocal<Map<String, List<Double>>> embeddingCache = ThreadLocal.withInitial(HashMap::new);

    // Optional linker for recalculating similarity scores when cache misses
    private GraphEntityLinker linker;
    
    // Optional status emitter for sending status updates during retrieval
    private StatusEmitter statusEmitter;
    private String sessionKey;
    
    public GraphRetrievalService(Neo4jGraphRepository repo, LlmClientService llm) {
        this.repo = repo;
        this.llm = llm;
    }
    
    /**
     * Set status emitter and session key for status updates during retrieval.
     * This allows retrieve() to send status updates at the correct time points.
     */
    public void setStatusEmitter(StatusEmitter statusEmitter, String sessionKey) {
        this.statusEmitter = statusEmitter;
        this.sessionKey = sessionKey;
    }
    
    /**
     * Set the linker for recalculating similarity scores when cache misses.
     * This is called from DiscoveryOrchestratorService after retrieval starts.
     */
    public void setLinker(GraphEntityLinker linker) {
        this.linker = linker;
    }
    
    /**
     * Get cached candidates for a topic/keyword search.
     * Returns null if not cached.
     */
    public List<CandidateEntity> getCachedCandidates(String label, String text, boolean useKeywords) {
        String key = label.toLowerCase() + ":" + text + ":" + useKeywords;
        return candidateCache.get().get(key);
    }
    
    /**
     * Cache candidates for a topic/keyword search.
     */
    public void cacheCandidates(String label, String text, boolean useKeywords, List<CandidateEntity> candidates) {
        String key = label.toLowerCase() + ":" + text + ":" + useKeywords;
        candidateCache.get().put(key, candidates);
    }
    
    /**
     * Get cached embedding for a text.
     * Returns null if not cached.
     */
    public List<Double> getCachedEmbedding(String text) {
        return embeddingCache.get().get(text);
    }
    
    /**
     * Cache embedding for a text.
     */
    public void cacheEmbedding(String text, List<Double> embedding) {
        embeddingCache.get().put(text, embedding);
    }
    
    /**
     * Clear the thread-local cache (should be called after request processing).
     */
    public void clearCache() {
        candidateCache.get().clear();
        embeddingCache.get().clear();
    }

    public GraphRetrievalResult retrieve(GeoIntent intent, boolean useKeywords, Hyperparameters hyperparams, 
                                        String apiKey, String questionId) {
        // Prepare inputs and validate (before starting timing)
        Hyperparameters h = (hyperparams != null) ? hyperparams : Hyperparameters.defaults();
        // Normalize weights to sum to 1.0 (frontend may send weights that don't sum to 1)
        h.normalizeWeights();
        // Validate hyperparameters
        h.validate();
        
        double wTopic = (h.weightTopic != null) ? h.weightTopic : 0.3;
        double wFormat = (h.weightFormat != null) ? h.weightFormat : 0.1;
        double wLicense = (h.weightLicense != null) ? h.weightLicense : 0.1;
        double wOrg = (h.weightOrganization != null) ? h.weightOrganization : 0.1;
        double wSpace = (h.weightSpace != null) ? h.weightSpace : 0.2;
        double wTime = (h.weightTime != null) ? h.weightTime : 0.2;

        // ---- HARD FILTER INPUTS ----
        // Logic: if has space (bbox), filter by space; if has time, filter by time; if both, filter by both
        // Space always uses bbox for filtering (no spaceId)
        double[] bbox = (intent.getSpace() == null) ? null : intent.getSpace().getBbox(); // [minLon,minLat,maxLon,maxLat]
        boolean hasBbox = (bbox != null && bbox.length == 4);
        
        String tStart = (intent.getTime() == null) ? null : blankToNull(intent.getTime().getStart());
        String tEnd = (intent.getTime() == null) ? null : blankToNull(intent.getTime().getEnd());
        boolean hasTime = (tStart != null && !tStart.isBlank() && tEnd != null && !tEnd.isBlank());

        // ---- SOURCE HARD FILTER (includes both data catalog selection and user-selected sources) ----
        // Data catalog and Source are the same thing - both filter by Source nodes
        // Data catalog selection maps to specific Source node IDs (datagov, pasda, STAC catalogs, etc.)
        // User-selected sources are also Source node IDs from intent
        Set<String> sourceHardSet = null;
        List<String> allSourceIds = new ArrayList<>();
        
        // 1. Add catalog-selected Source IDs
        // Data catalog id mapping
        Map<String, String> portalIds = Map.of(
            "datagov", "data.gov",
            "pasda", "pasda",
            "stacGoogle", "3886fcaf359f302ca7255a9b71b265b33662f88b",
            "stacDedl", "7148d4a5544f0896e63775cc95908897fa0e8a36",
            "stacMicrosoft", "32c3d214ac192b3316cef7a5996e3cba84989ffd",
            "stacPaituli", "c923746393499b170b2172f45ffe2f8037be3a9e"
        );
        
        if (h.portals != null && !h.portals.isEmpty()) {
            // Validate: at least one catalog must be selected
            boolean hasEnabledPortal = h.portals.values().stream().anyMatch(Boolean.TRUE::equals);
            if (!hasEnabledPortal) {
                // No catalog selected - return error
                throw new IllegalArgumentException("At least one data catalog must be selected. Please select at least one catalog in the Data Catalog Selection section.");
            }
            
            // Check if all catalogs are selected (if all selected, skip catalog filtering to save time)
            boolean allPortalsSelected = true;
            for (String portalKey : portalIds.keySet()) {
                if (!Boolean.TRUE.equals(h.portals.getOrDefault(portalKey, false))) {
                    allPortalsSelected = false;
                    break;
                }
            }
            
            if (!allPortalsSelected) {
                // Not all catalogs selected - need to filter by selected catalogs
                for (Map.Entry<String, String> entry : portalIds.entrySet()) {
                    if (Boolean.TRUE.equals(h.portals.getOrDefault(entry.getKey(), false))) {
                        allSourceIds.add(entry.getValue());
                    }
                }
            }
        } else {
            // Data catalog selection is null or empty - return error
            throw new IllegalArgumentException("At least one data catalog must be selected. Please select at least one catalog in the Data Catalog Selection section.");
        }
        
        // 2. Add user-selected Source IDs from intent
        if (intent.getSource() != null && intent.getSource().getKgNodeIds() != null 
                && !intent.getSource().getKgNodeIds().isEmpty()) {
            List<String> selectedSourceIds = intent.getSource().getKgNodeIds();
            // Check if any selected source IDs are already in the list (from data catalog selection)
            for (String sourceId : selectedSourceIds) {
                if (!allSourceIds.contains(sourceId)) {
                    allSourceIds.add(sourceId);
                }
            }
        }
        
        // Check if intent is null after preparation (should not happen, but safety check)
        if (intent == null) {
            return new GraphRetrievalResult(List.of(), Map.of());
        }
        
        // 3. If we have any Source IDs (from catalog or user selection), filter datasets
        if (!allSourceIds.isEmpty()) {
            System.out.println("  [Hard Filter] Source: " + allSourceIds.size() + " source(s)");
            sourceHardSet = new HashSet<>();
            
            // Batch query: get all datasets for all sources in one query
            Map<String, List<String>> sourceToDatasets = repo.datasetsForSourcesBatch(allSourceIds);
            for (List<String> ds : sourceToDatasets.values()) {
                sourceHardSet.addAll(ds);
            }
            
            System.out.println("  [Hard Filter] Source: " + sourceHardSet.size() + " datasets");
            if (sourceHardSet.isEmpty()) {
                // No datasets found for selected sources/catalogs
                return new GraphRetrievalResult(List.of(), Map.of(), Map.of());
            }
        }

        boolean hasHard = hasBbox || hasTime;

        Set<String> hardSet = Collections.emptySet();
        if (hasHard) {
            // Use bbox-based filtering (handles both space via bbox and time)
            // If hasBbox: filter by bbox; if hasTime: filter by time; if both: filter by both
            List<String> hard = repo.hardFilterDatasetIdsByBboxTime(bbox, tStart, tEnd, 0); // limit removed for accuracy
            Set<String> spaceTimeHardSet = new HashSet<>(hard);
            
            // Intersect with source hard filter if applied
            if (sourceHardSet != null) {
                // Intersection: keep only datasets that are in both sets
                hardSet = new HashSet<>();
                for (String did : spaceTimeHardSet) {
                    if (sourceHardSet.contains(did)) {
                        hardSet.add(did);
                    }
                }
                System.out.println("  [Hard Filter] Combined: " + hardSet.size() + " datasets");
            } else {
                // No source filter, just use space/time results
                hardSet = spaceTimeHardSet;
            }
            
            if (hardSet.isEmpty()) {
                return new GraphRetrievalResult(List.of(), Map.of(), Map.of());
            }
        } else if (sourceHardSet != null) {
            // Only source hard filter, no space/time filter
            hardSet = sourceHardSet;
        }

        // Update hasHard to include source hard filter
        // hasHard is used throughout the code for checks like "if (hasHard && !hardSet.contains(did))"
        hasHard = sourceHardSet != null || hasHard;

        // Spatial/Temporal filter done → Dataset scoring active (immediately after spatial/temporal filter done)
        if (statusEmitter != null && sessionKey != null) {
            statusEmitter.emitStatus(sessionKey, "spatial_temporal_filter", "done");
            statusEmitter.emitStatus(sessionKey, "dataset_scoring", "active");
            // Emit active message for dataset scoring (only via SSE, not saved to DB)
            // The done message will be saved by DiscoveryOrchestratorService
            statusEmitter.emitPipelineMessage(sessionKey, 
                "I'm scoring the datasets based on how well they match your requirements...", 
                "dataset_scoring", "active", null);
        }

        // Track dimension contributions separately to ensure each dimension is in [0,1] range
        // Map<datasetId, Map<dimension, contribution>>
        Map<String, Map<String, Double>> dimContributions = new HashMap<>();

        // -----------------
        // TOPIC constraint
        // -----------------
        if (intent.getTopic() != null) {
            // Use bestText to prefer rawText over value for score calculation
            // This ensures we use the original intent parsing result, not user-selected candidate names
            String topicText = bestText(intent.getTopic().getValue(), intent.getTopic().getRawText());
            if (!isBlank(topicText)) {
                // Check if user selected multiple candidates (kg_node_ids)
                // This can come from: 1) auto-execute mode (autoSetCandidates), 2) user manual selection
                List<String> selectedTopicIds = intent.getTopic().getKgNodeIds();
                
                if (selectedTopicIds != null && !selectedTopicIds.isEmpty()) {
                // Topics/Keywords are already selected (either by auto-execute or user) - directly get datasets connected to these entities
                // No need to do embedding/text matching - just get datasets directly connected to selected topics/keywords
                // Limit to first 100 candidates if more than 100
                List<String> limitedEntityIds = selectedTopicIds.size() > 100 ? 
                    selectedTopicIds.subList(0, 100) : selectedTopicIds;
                
                // When useKeywords is true, selectedTopicIds may contain both Topic and Keyword nodes
                // We need to separate them and query datasets for each type separately
                List<String> topicIds = new ArrayList<>();
                List<String> keywordIds = new ArrayList<>();
                
                if (useKeywords) {
                    // Get node labels to distinguish Topic from Keyword
                    Map<String, String> nodeLabels = repo.getNodeLabels(limitedEntityIds);
                    for (String nodeId : limitedEntityIds) {
                        String label = nodeLabels.get(nodeId);
                        if ("Keyword".equals(label)) {
                            keywordIds.add(nodeId);
                        } else {
                            // Default to Topic if label is null or not Keyword
                            topicIds.add(nodeId);
                        }
                    }
                } else {
                    // If useKeywords is false, all are Topics
                    topicIds = limitedEntityIds;
                }
                
                int totalEntities = topicIds.size() + keywordIds.size();
                System.out.println("  [Soft Filter] Topic: " + topicIds.size() + " topic(s), " + keywordIds.size() + " keyword(s)" + 
                    (selectedTopicIds.size() > 100 ? " (limited from " + selectedTopicIds.size() + ")" : ""));
                
                // Get cached candidates for similarity scores
                List<CandidateEntity> cachedTopics = null;
                List<CandidateEntity> cachedKeywords = null;
                if (!topicText.isBlank()) {
                    cachedTopics = getCachedCandidates("Topic", topicText, useKeywords);
                    if (useKeywords) {
                        cachedKeywords = getCachedCandidates("Keyword", topicText, useKeywords);
                    }
                }
                
                // Batch query: get all (entityId, datasetId) pairs for Topics
                Map<String, List<String>> topicToDatasets = Map.of();
                if (!topicIds.isEmpty()) {
                    topicToDatasets = hasHard ? 
                        repo.datasetsForTopicsBatch(topicIds, hardSet) : 
                        repo.datasetsForTopicsBatch(topicIds, null);
                }
                
                // Batch query: get all (entityId, datasetId) pairs for Keywords
                Map<String, List<String>> keywordToDatasets = Map.of();
                if (!keywordIds.isEmpty()) {
                    keywordToDatasets = hasHard ? 
                        repo.datasetsForKeywordsBatch(keywordIds, hardSet) : 
                        repo.datasetsForKeywordsBatch(keywordIds, null);
                }
                
                // Build similarity score map for all entities (Topic + Keyword)
                Map<String, Double> entitySimilarityScores = new HashMap<>();
                double similarityScoreThreshold = (h.similarityScoreThreshold != null) ? h.similarityScoreThreshold : 0.5;
                
                // Get scores for Topics
                for (String topicId : topicIds) {
                    double similarityScore = similarityScoreThreshold; // default
                    if (cachedTopics != null) {
                        for (CandidateEntity candidate : cachedTopics) {
                            if (candidate.nodeId().equals(topicId)) {
                                similarityScore = candidate.score();
                                break;
                            }
                        }
                    }
                    entitySimilarityScores.put(topicId, similarityScore);
                }
                
                // Get scores for Keywords
                for (String keywordId : keywordIds) {
                    double similarityScore = similarityScoreThreshold; // default
                    if (cachedKeywords != null) {
                        for (CandidateEntity candidate : cachedKeywords) {
                            if (candidate.nodeId().equals(keywordId)) {
                                similarityScore = candidate.score();
                                break;
                            }
                        }
                    }
                    entitySimilarityScores.put(keywordId, similarityScore);
                }
                
                // First pass: collect all datasets and their candidate scores (without weight)
                // Map<datasetId, Map<entityId, rawScore>> - includes both Topic and Keyword
                Map<String, Map<String, Double>> datasetEntityScores = new HashMap<>();
                
                // Process Topic datasets
                for (Map.Entry<String, List<String>> entry : topicToDatasets.entrySet()) {
                    String topicId = entry.getKey();
                    List<String> datasetIds = entry.getValue();
                    double similarityScore = entitySimilarityScores.getOrDefault(topicId, similarityScoreThreshold);
                    for (String did : datasetIds) {
                        datasetEntityScores.computeIfAbsent(did, k -> new HashMap<>())
                            .put(topicId, similarityScore);
                    }
                }
                
                // Process Keyword datasets
                for (Map.Entry<String, List<String>> entry : keywordToDatasets.entrySet()) {
                    String keywordId = entry.getKey();
                    List<String> datasetIds = entry.getValue();
                    double similarityScore = entitySimilarityScores.getOrDefault(keywordId, similarityScoreThreshold);
                    for (String did : datasetIds) {
                        datasetEntityScores.computeIfAbsent(did, k -> new HashMap<>())
                            .put(keywordId, similarityScore);
                    }
                }
                
                // Second pass: for each dataset, find the maximum rawScore among all connected entities (Topic + Keyword)
                // Then multiply by weight and store in dimContributions
                for (Map.Entry<String, Map<String, Double>> entry : datasetEntityScores.entrySet()) {
                    String did = entry.getKey();
                    Map<String, Double> entityScores = entry.getValue();
                    // Find maximum rawScore among all connected entities (Topic + Keyword)
                    double maxRawScore = entityScores.values().stream()
                        .mapToDouble(Double::doubleValue)
                        .max()
                        .orElse(0.0);
                    // Store weighted score in dimContributions
                    dimContributions.computeIfAbsent(did, k -> new HashMap<>())
                        .put("Topic", maxRawScore * wTopic);
                }
                }
                // If no topic candidates selected (either auto or user), skip soft filtering
                // Topic soft filtering only happens when candidates are explicitly selected (via autoSetCandidates or user selection)
            }
        }

        // -----------------
        // OPTIONAL dims (Format, License, Organization)
        // Soft filtering only happens when candidates are explicitly selected (via autoSetCandidates or user selection)
        // -----------------
        if (intent.getFormat() != null) {
            String formatValue = intent.getFormat().getValue();
            List<String> selectedFormatIds = intent.getFormat().getKgNodeIds();
            
            if (selectedFormatIds != null && !selectedFormatIds.isEmpty()) {
                // Limit to first 50 candidates if more than 50
                List<String> limitedFormatIds = selectedFormatIds.size() > 50 ? 
                    selectedFormatIds.subList(0, 50) : selectedFormatIds;
                System.out.println("  [Soft Filter] Format: " + limitedFormatIds.size() + " format(s)" + 
                    (selectedFormatIds.size() > 50 ? " (limited from " + selectedFormatIds.size() + ")" : ""));
                // User has explicitly selected format IDs - no need to do embedding/text matching again
                // Just get datasets directly connected to these selected formats
                // Try to get similarity scores from cache if available
                List<CandidateEntity> cachedFormats = null;
                if (!isBlank(formatValue)) {
                    cachedFormats = getCachedCandidates("Format", formatValue, false);
                }
                
                // Batch query: get all (formatId, datasetId) pairs in one Neo4j query
                Map<String, List<String>> formatToDatasets = hasHard ? 
                    repo.datasetsForFormatsBatch(limitedFormatIds, hardSet) : 
                    repo.datasetsForFormatsBatch(limitedFormatIds, null);
                
                // Build similarity score map for all formats
                Map<String, Double> formatSimilarityScores = new HashMap<>();
                double similarityScoreThreshold = (h.similarityScoreThreshold != null) ? h.similarityScoreThreshold : 0.5;
                for (String formatId : limitedFormatIds) {
                    double similarityScore = similarityScoreThreshold; // default
                    if (cachedFormats != null) {
                        for (CandidateEntity candidate : cachedFormats) {
                            if (candidate.nodeId().equals(formatId)) {
                                similarityScore = candidate.score();
                                break;
                            }
                        }
                    }
                    formatSimilarityScores.put(formatId, similarityScore);
                }
                
                // First pass: collect all datasets and their candidate scores (without weight)
                // Map<datasetId, Map<formatId, rawScore>>
                Map<String, Map<String, Double>> datasetFormatScores = new HashMap<>();
                for (Map.Entry<String, List<String>> entry : formatToDatasets.entrySet()) {
                    String formatId = entry.getKey();
                    List<String> datasetIds = entry.getValue();
                    double similarityScore = formatSimilarityScores.getOrDefault(formatId, similarityScoreThreshold);
                    for (String did : datasetIds) {
                        datasetFormatScores.computeIfAbsent(did, k -> new HashMap<>())
                            .put(formatId, similarityScore);
                    }
                }
                
                // Second pass: for each dataset, find the maximum rawScore among all connected formats
                // Then multiply by weight and store in dimContributions
                for (Map.Entry<String, Map<String, Double>> entry : datasetFormatScores.entrySet()) {
                    String did = entry.getKey();
                    Map<String, Double> formatScores = entry.getValue();
                    // Find maximum rawScore among all connected formats
                    double maxRawScore = formatScores.values().stream()
                        .mapToDouble(Double::doubleValue)
                        .max()
                        .orElse(0.0);
                    // Store weighted score in dimContributions
                    dimContributions.computeIfAbsent(did, k -> new HashMap<>())
                        .put("Format", maxRawScore * wFormat);
                }
            }
            // If no format candidates selected (either auto or user), skip soft filtering
            // Format soft filtering only happens when candidates are explicitly selected (via autoSetCandidates or user selection)
        }

        if (intent.getLicense() != null) {
            String licenseValue = intent.getLicense().getValue();
            List<String> selectedLicenseIds = intent.getLicense().getKgNodeIds();
            
            if (selectedLicenseIds != null && !selectedLicenseIds.isEmpty()) {
                // Limit to first 50 candidates if more than 50
                List<String> limitedLicenseIds = selectedLicenseIds.size() > 50 ? 
                    selectedLicenseIds.subList(0, 50) : selectedLicenseIds;
                // User has explicitly selected license IDs - no need to do embedding/text matching again
                // Just get datasets directly connected to these selected licenses
                // Try to get similarity scores from cache if available
                List<CandidateEntity> cachedLicenses = null;
                if (!isBlank(licenseValue)) {
                    cachedLicenses = getCachedCandidates("License", licenseValue, false);
                }
                
                // Batch query: get all (licenseId, datasetId) pairs in one Neo4j query
                Map<String, List<String>> licenseToDatasets = hasHard ? 
                    repo.datasetsForLicensesBatch(limitedLicenseIds, hardSet) : 
                    repo.datasetsForLicensesBatch(limitedLicenseIds, null);
                
                // Build similarity score map for all licenses
                Map<String, Double> licenseSimilarityScores = new HashMap<>();
                double similarityScoreThreshold = (h.similarityScoreThreshold != null) ? h.similarityScoreThreshold : 0.5;
                for (String licenseId : limitedLicenseIds) {
                    double similarityScore = similarityScoreThreshold; // default
                    if (cachedLicenses != null) {
                        for (CandidateEntity candidate : cachedLicenses) {
                            if (candidate.nodeId().equals(licenseId)) {
                                similarityScore = candidate.score();
                                break;
                            }
                        }
                    }
                    licenseSimilarityScores.put(licenseId, similarityScore);
                }
                
                // First pass: collect all datasets and their candidate scores (without weight)
                // Map<datasetId, Map<licenseId, rawScore>>
                Map<String, Map<String, Double>> datasetLicenseScores = new HashMap<>();
                for (Map.Entry<String, List<String>> entry : licenseToDatasets.entrySet()) {
                    String licenseId = entry.getKey();
                    List<String> datasetIds = entry.getValue();
                    double similarityScore = licenseSimilarityScores.getOrDefault(licenseId, similarityScoreThreshold);
                    for (String did : datasetIds) {
                        datasetLicenseScores.computeIfAbsent(did, k -> new HashMap<>())
                            .put(licenseId, similarityScore);
                    }
                }
                
                // Second pass: for each dataset, find the maximum rawScore among all connected licenses
                // Then multiply by weight and store in dimContributions
                for (Map.Entry<String, Map<String, Double>> entry : datasetLicenseScores.entrySet()) {
                    String did = entry.getKey();
                    Map<String, Double> licenseScores = entry.getValue();
                    // Find maximum rawScore among all connected licenses
                    double maxRawScore = licenseScores.values().stream()
                        .mapToDouble(Double::doubleValue)
                        .max()
                        .orElse(0.0);
                    // Store weighted score in dimContributions
                    dimContributions.computeIfAbsent(did, k -> new HashMap<>())
                        .put("License", maxRawScore * wLicense);
                }
            }
            // If no license candidates selected (either auto or user), skip soft filtering
            // License soft filtering only happens when candidates are explicitly selected (via autoSetCandidates or user selection)
        }

        if (intent.getOrganization() != null) {
            String orgValue = intent.getOrganization().getValue();
            List<String> selectedOrgIds = intent.getOrganization().getKgNodeIds();
            
            if (selectedOrgIds != null && !selectedOrgIds.isEmpty()) {
                // Limit to first 50 candidates if more than 50
                List<String> limitedOrgIds = selectedOrgIds.size() > 50 ? 
                    selectedOrgIds.subList(0, 50) : selectedOrgIds;
                System.out.println("  [Soft Filter] Organization: " + limitedOrgIds.size() + " organization(s)" + 
                    (selectedOrgIds.size() > 50 ? " (limited from " + selectedOrgIds.size() + ")" : ""));
                // User has explicitly selected organization IDs - no need to do embedding/text matching again
                // Just get datasets directly connected to these selected organizations
                // Try to get similarity scores from cache if available
                List<CandidateEntity> cachedOrgs = null;
                if (!isBlank(orgValue)) {
                    cachedOrgs = getCachedCandidates("Organization", orgValue, false);
                }
                
                // Batch query: get all (orgId, datasetId) pairs in one Neo4j query
                Map<String, List<String>> orgToDatasets = hasHard ? 
                    repo.datasetsForOrganizationsBatch(limitedOrgIds, hardSet) : 
                    repo.datasetsForOrganizationsBatch(limitedOrgIds, null);
                
                // Build similarity score map for all organizations
                Map<String, Double> orgSimilarityScores = new HashMap<>();
                double similarityScoreThreshold = (h.similarityScoreThreshold != null) ? h.similarityScoreThreshold : 0.5;
                for (String orgId : limitedOrgIds) {
                    double similarityScore = similarityScoreThreshold; // default
                    if (cachedOrgs != null) {
                        for (CandidateEntity candidate : cachedOrgs) {
                            if (candidate.nodeId().equals(orgId)) {
                                similarityScore = candidate.score();
                                break;
                            }
                        }
                    }
                    orgSimilarityScores.put(orgId, similarityScore);
                }
                
                // First pass: collect all datasets and their candidate scores (without weight)
                // Map<datasetId, Map<orgId, rawScore>>
                Map<String, Map<String, Double>> datasetOrgScores = new HashMap<>();
                for (Map.Entry<String, List<String>> entry : orgToDatasets.entrySet()) {
                    String orgId = entry.getKey();
                    List<String> datasetIds = entry.getValue();
                    double similarityScore = orgSimilarityScores.getOrDefault(orgId, similarityScoreThreshold);
                    for (String did : datasetIds) {
                        datasetOrgScores.computeIfAbsent(did, k -> new HashMap<>())
                            .put(orgId, similarityScore);
                    }
                }
                
                // Second pass: for each dataset, find the maximum rawScore among all connected organizations
                // Then multiply by weight and store in dimContributions
                for (Map.Entry<String, Map<String, Double>> entry : datasetOrgScores.entrySet()) {
                    String did = entry.getKey();
                    Map<String, Double> orgScores = entry.getValue();
                    // Find maximum rawScore among all connected organizations
                    double maxRawScore = orgScores.values().stream()
                        .mapToDouble(Double::doubleValue)
                        .max()
                        .orElse(0.0);
                    // Store weighted score in dimContributions
                    dimContributions.computeIfAbsent(did, k -> new HashMap<>())
                        .put("Organization", maxRawScore * wOrg);
                }
            }
            // If no organization candidates selected (either auto or user), skip soft filtering
            // Organization soft filtering only happens when candidates are explicitly selected (via autoSetCandidates or user selection)
        }

        // Source dimension is now handled as hard filter only (removed from soft filtering)
        // Source soft filtering removed per user request

        // Final rescue - add to Topic dimension if no contributions yet
        if (dimContributions.isEmpty()) {
            String fallbackText = null;
            if (intent.getTopic() != null && !isBlank(intent.getTopic().getValue())) fallbackText = intent.getTopic().getValue();

            if (fallbackText != null) {
                List<String> dsLex = repo.searchDatasetIdsByText(fallbackText, 150, useKeywords);
                for (String did : dsLex) {
                    // Filter by hardSet if applicable (this is a fallback, so we still check manually)
                    if (hasHard && !hardSet.contains(did)) continue;
                    dimContributions.computeIfAbsent(did, k -> new HashMap<>())
                        .merge("Topic", 0.10, Math::max);
                }
            }
        }
        
        // Cap each dimension contribution at 1.0, then sum all dimensions for final score
        Map<String, Double> score = new HashMap<>();
        for (Map.Entry<String, Map<String, Double>> entry : dimContributions.entrySet()) {
            String did = entry.getKey();
            Map<String, Double> dims = entry.getValue();
            
            double totalScore = 0.0;
            for (Map.Entry<String, Double> dimEntry : dims.entrySet()) {
                // Cap each dimension contribution at 1.0
                double capped = Math.min(1.0, dimEntry.getValue());
                totalScore += capped;
            }
            score.put(did, totalScore);
        }

        // -----------------
        // SPACE and TIME soft scoring (based on overlap ratio)
        // -----------------
        
        // Get all candidate dataset IDs (from score or hardSet)
        Set<String> candidateIds = new HashSet<>(score.keySet());
        if (hasHard) {
            candidateIds.addAll(hardSet);
        }
        
        if (!candidateIds.isEmpty() && (hasBbox || hasTime)) {
            // Get Space/Time info for all candidate datasets
            Map<String, Map<String, Object>> spaceTimeInfo = repo.getDatasetSpaceTimeInfo(new ArrayList<>(candidateIds));
            
            // Calculate query bbox area (if hasBbox)
            double queryBboxArea = 0.0;
            if (hasBbox) {
                double queryWidth = bbox[2] - bbox[0];  // maxLon - minLon
                double queryHeight = bbox[3] - bbox[1]; // maxLat - minLat
                queryBboxArea = Math.abs(queryWidth * queryHeight);
            }
            
            // Calculate query time range length (if hasTime)
            long queryTimeRange = 0L;
            if (hasTime) {
                try {
                    long startTime = parseTimeToEpoch(tStart);
                    long endTime = parseTimeToEpoch(tEnd);
                    queryTimeRange = Math.max(1, endTime - startTime); // Avoid division by zero
                } catch (Exception e) {
                    // Failed to parse time range - silently ignore
                }
            }
            
            // Score each dataset based on overlap ratio
            for (String did : candidateIds) {
                Map<String, Object> info = spaceTimeInfo.get(did);
                if (info == null) continue;
                
                // Space overlap scoring using F1 score
                if (hasBbox && queryBboxArea > 0) {
                    Object bboxObj = info.get("bbox");
                    if (bboxObj instanceof double[] datasetBbox && datasetBbox.length == 4) {
                        double f1 = calculateBboxF1(bbox, datasetBbox, queryBboxArea);
                        if (f1 > 0) {
                            dimContributions.computeIfAbsent(did, k -> new HashMap<>())
                                .merge("Space", f1 * wSpace, Math::max);
                        }
                    }
                }
                
                // Time overlap scoring using F1 score
                if (hasTime && queryTimeRange > 0) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> timeInfo = (Map<String, String>) info.get("time");
                    if (timeInfo != null) {
                        String datasetBegin = timeInfo.get("begin");
                        String datasetEnd = timeInfo.get("end");
                        if (datasetBegin != null && datasetEnd != null) {
                            try {
                                double f1 = calculateTimeF1(tStart, tEnd, datasetBegin, datasetEnd, queryTimeRange);
                                if (f1 > 0) {
                                    dimContributions.computeIfAbsent(did, k -> new HashMap<>())
                                        .merge("Time", f1 * wTime, Math::max);
                                }
                            } catch (Exception e) {
                                // Failed to calculate time F1 - silently ignore
                            }
                        }
                    }
                }
            }
            // Release memory after processing Space/Time
            spaceTimeInfo = null;
            candidateIds = null;
        }
        
        // Recalculate score after Space and Time contributions are added
        // Cap each dimension contribution at 1.0, then sum all dimensions for final score
        score = new HashMap<>();
        for (Map.Entry<String, Map<String, Double>> entry : dimContributions.entrySet()) {
            String did = entry.getKey();
            Map<String, Double> dims = entry.getValue();
            
            double totalScore = 0.0;
            for (Map.Entry<String, Double> dimEntry : dims.entrySet()) {
                // Cap each dimension contribution at 1.0
                double capped = Math.min(1.0, dimEntry.getValue());
                totalScore += capped;
            }
            score.put(did, totalScore);
        }
        
        // If still empty but we have hard filter results, use them directly
        if (dimContributions.isEmpty() && hasHard && !hardSet.isEmpty()) {
            for (String did : hardSet) {
                dimContributions.computeIfAbsent(did, key -> new HashMap<>())
                    .put("HardFilter", 1.0);  // Give equal score to all hard-filtered results
                score.put(did, 1.0);  // Also add to score directly
            }
        }
        
        // Save raw scores before normalization
        Map<String, Double> rawScores = new HashMap<>(score);
        
        // Normalize final scores to 0-1 range
        if (!score.isEmpty()) {
            double maxScore = score.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
            if (maxScore > 0) {
                Map<String, Double> normalizedScore = new HashMap<>();
                for (Map.Entry<String, Double> entry : score.entrySet()) {
                    // Normalize each score to [0,1] range
                    double normalized = Math.min(1.0, entry.getValue() / maxScore);
                    normalizedScore.put(entry.getKey(), normalized);
                }
                score = normalizedScore;
            }
        }

        // -----------------
        // HARD FILTER: For all dimensions (Topic, Format, License, Organization) with explicitly selected candidates
        // (via auto-mode autoSetCandidates or manual user selection), collect datasets connected to these candidates.
        // Final results must be connected to at least one candidate from at least one dimension (union of all dimensions).
        // This ensures both auto and non-auto modes only return datasets that actually have at least one selected candidate.
        // -----------------
        Set<String> dimensionHardFilterSet = new HashSet<>();
        boolean hasAnyDimensionFilter = false;
        
        // Topic dimension (includes Keyword when useKeywords is true)
        if (intent.getTopic() != null && intent.getTopic().getKgNodeIds() != null 
                && !intent.getTopic().getKgNodeIds().isEmpty()) {
            List<String> selectedEntityIds = intent.getTopic().getKgNodeIds();
            
            // When useKeywords is true, selectedEntityIds may contain both Topic and Keyword nodes
            // We need to separate them and query datasets for each type separately
            List<String> topicIds = new ArrayList<>();
            List<String> keywordIds = new ArrayList<>();
            
            if (useKeywords) {
                // Get node labels to distinguish Topic from Keyword
                Map<String, String> nodeLabels = repo.getNodeLabels(selectedEntityIds);
                for (String nodeId : selectedEntityIds) {
                    String label = nodeLabels.get(nodeId);
                    if ("Keyword".equals(label)) {
                        keywordIds.add(nodeId);
                    } else {
                        // Default to Topic if label is null or not Keyword
                        topicIds.add(nodeId);
                    }
                }
            } else {
                // If useKeywords is false, all are Topics
                topicIds = selectedEntityIds;
            }
            
            // Batch query: get all datasets for all topics in one query
            if (!topicIds.isEmpty()) {
                Map<String, List<String>> topicToDatasets = hasHard ? 
                    repo.datasetsForTopicsBatch(topicIds, hardSet) : 
                    repo.datasetsForTopicsBatch(topicIds, null);
                for (List<String> ds : topicToDatasets.values()) {
                    dimensionHardFilterSet.addAll(ds);
                }
            }
            
            // Batch query: get all datasets for all keywords in one query
            if (!keywordIds.isEmpty()) {
                Map<String, List<String>> keywordToDatasets = hasHard ? 
                    repo.datasetsForKeywordsBatch(keywordIds, hardSet) : 
                    repo.datasetsForKeywordsBatch(keywordIds, null);
                for (List<String> ds : keywordToDatasets.values()) {
                    dimensionHardFilterSet.addAll(ds);
                }
            }
            
            hasAnyDimensionFilter = true;
            System.out.println("  [Hard Filter] Topic: " + topicIds.size() + " topic(s), " + keywordIds.size() + " keyword(s), " + dimensionHardFilterSet.size() + " datasets");
        }
        
        // Format dimension
        if (intent.getFormat() != null && intent.getFormat().getKgNodeIds() != null 
                && !intent.getFormat().getKgNodeIds().isEmpty()) {
            List<String> selectedFormatIds = intent.getFormat().getKgNodeIds();
            // Batch query: get all datasets for all formats in one query
            Map<String, List<String>> formatToDatasets = hasHard ? 
                repo.datasetsForFormatsBatch(selectedFormatIds, hardSet) : 
                repo.datasetsForFormatsBatch(selectedFormatIds, null);
            Set<String> formatDatasets = new HashSet<>();
            for (List<String> ds : formatToDatasets.values()) {
                formatDatasets.addAll(ds);
            }
            dimensionHardFilterSet.addAll(formatDatasets);
            hasAnyDimensionFilter = true;
            System.out.println("  [Hard Filter] Format: " + selectedFormatIds.size() + " format(s), " + formatDatasets.size() + " datasets");
        }
        
        // License dimension
        if (intent.getLicense() != null && intent.getLicense().getKgNodeIds() != null 
                && !intent.getLicense().getKgNodeIds().isEmpty()) {
            List<String> selectedLicenseIds = intent.getLicense().getKgNodeIds();
            // Batch query: get all datasets for all licenses in one query
            Map<String, List<String>> licenseToDatasets = hasHard ? 
                repo.datasetsForLicensesBatch(selectedLicenseIds, hardSet) : 
                repo.datasetsForLicensesBatch(selectedLicenseIds, null);
            Set<String> licenseDatasets = new HashSet<>();
            for (List<String> ds : licenseToDatasets.values()) {
                licenseDatasets.addAll(ds);
            }
            dimensionHardFilterSet.addAll(licenseDatasets);
            hasAnyDimensionFilter = true;
            System.out.println("  [Hard Filter] License: " + selectedLicenseIds.size() + " license(s), " + licenseDatasets.size() + " datasets");
        }
        
        // Organization dimension
        if (intent.getOrganization() != null && intent.getOrganization().getKgNodeIds() != null 
                && !intent.getOrganization().getKgNodeIds().isEmpty()) {
            List<String> selectedOrgIds = intent.getOrganization().getKgNodeIds();
            // Batch query: get all datasets for all organizations in one query
            Map<String, List<String>> orgToDatasets = hasHard ? 
                repo.datasetsForOrganizationsBatch(selectedOrgIds, hardSet) : 
                repo.datasetsForOrganizationsBatch(selectedOrgIds, null);
            Set<String> orgDatasets = new HashSet<>();
            for (List<String> ds : orgToDatasets.values()) {
                orgDatasets.addAll(ds);
            }
            dimensionHardFilterSet.addAll(orgDatasets);
            hasAnyDimensionFilter = true;
            System.out.println("  [Hard Filter] Organization: " + selectedOrgIds.size() + " organization(s), " + orgDatasets.size() + " datasets");
        }
        
        // Apply hard filter: only keep datasets that are connected to at least one selected candidate from any dimension
        if (hasAnyDimensionFilter) {
            if (dimensionHardFilterSet.isEmpty()) {
                System.out.println("  [Hard Filter] No datasets found for any selected dimension candidates");
                return new GraphRetrievalResult(List.of(), Map.of(), Map.of());
            }
            
            System.out.println("  [Hard Filter] Union of all dimensions: " + dimensionHardFilterSet.size() + " datasets (after deduplication)");
            
            // Filter score and dimContributions to only include datasets connected to at least one selected candidate
            score.keySet().retainAll(dimensionHardFilterSet);
            dimContributions.keySet().retainAll(dimensionHardFilterSet);
        }

        // Sort all datasets by score (descending)
        List<Map.Entry<String, Double>> sortedEntries = score.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .toList();
        
        // Take at least 20, but include all datasets with the same score as the 20th one
        List<String> ranked = new ArrayList<>();
        if (sortedEntries.size() > 0) {
            // Get the score of the 20th dataset (or the last one if fewer than 20)
            int minIndex = Math.min(19, sortedEntries.size() - 1);
            double thresholdScore = sortedEntries.get(minIndex).getValue();
            
            // Include all datasets with score >= thresholdScore
            for (Map.Entry<String, Double> entry : sortedEntries) {
                if (entry.getValue() >= thresholdScore) {
                    ranked.add(entry.getKey());
                } else {
                    // Once we encounter a score lower than threshold, stop
                    break;
                }
            }
        }

        // Output scoring details for Top 20 datasets
        if (!ranked.isEmpty()) {
            printTopDatasetsScores(ranked, dimContributions, rawScores, score, h);
        }
        
        // Dataset scoring done (immediately after scoring completes)
        if (statusEmitter != null && sessionKey != null) {
            statusEmitter.emitStatus(sessionKey, "dataset_scoring", "done");
            // Emit done message for dataset scoring (will be replaced by DiscoveryOrchestratorService's message)
            // But we emit a basic one here in case
        }
        
        GraphRetrievalResult result = new GraphRetrievalResult(ranked, score, dimContributions);
        
        // Release memory: clear intermediate variables (they will be garbage collected)
        // Note: score and dimContributions are included in result, so they shouldn't be cleared
        
        return result;
    }

    private List<Double> safeEmbed(String text, String apiKey, String questionId) {
        try {
            return llm.embed(text, apiKey, questionId);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
    
    private String bestText(String v, String raw) {
        if (!isBlank(v)) return v;
        return raw == null ? "" : raw;
    }
    
    /**
     * Split a dimension value into multiple values by common separators (comma, semicolon, and, &).
     * Returns a list of trimmed, non-empty values.
     */
    private List<String> splitDimensionValue(String value) {
        if (value == null || value.isBlank()) return List.of();
        
        // Split by comma, semicolon, "and", "&", or " and "
        String[] parts = value.split("[,;]|\\s+and\\s+|\\s+&\\s+|&", -1);
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
    
    /**
     * Calculate bbox F1 score (harmonic mean of Precision and Recall)
     * F1 = 2 × (Precision × Recall) / (Precision + Recall)
     * where:
     *   Precision = intersection area / dataset area (how much of dataset overlaps with query)
     *   Recall = intersection area / query area (how much of query is covered by dataset)
     * Returns value in [0, 1]
     * 
     * This is better than IoU for data discovery because:
     * - High Recall (coverage) when dataset fully covers query → high F1
     * - High Precision when query fully covers dataset → high F1
     * - Better reflects relevance for geospatial data discovery
     */
    private double calculateBboxF1(double[] queryBbox, double[] datasetBbox, double queryArea) {
        // queryBbox: [minLon, minLat, maxLon, maxLat]
        // datasetBbox: [west, south, east, north]
        double qMinLon = queryBbox[0];
        double qMinLat = queryBbox[1];
        double qMaxLon = queryBbox[2];
        double qMaxLat = queryBbox[3];
        
        double dMinLon = datasetBbox[0];
        double dMinLat = datasetBbox[1];
        double dMaxLon = datasetBbox[2];
        double dMaxLat = datasetBbox[3];
        
        // Calculate overlap (intersection)
        double overlapMinLon = Math.max(qMinLon, dMinLon);
        double overlapMinLat = Math.max(qMinLat, dMinLat);
        double overlapMaxLon = Math.min(qMaxLon, dMaxLon);
        double overlapMaxLat = Math.min(qMaxLat, dMaxLat);
        
        // Check if there's overlap
        if (overlapMinLon >= overlapMaxLon || overlapMinLat >= overlapMaxLat) {
            return 0.0;
        }
        
        // Calculate intersection area
        double overlapWidth = overlapMaxLon - overlapMinLon;
        double overlapHeight = overlapMaxLat - overlapMinLat;
        double intersectionArea = Math.abs(overlapWidth * overlapHeight);
        
        // Calculate dataset area
        double datasetWidth = dMaxLon - dMinLon;
        double datasetHeight = dMaxLat - dMinLat;
        double datasetArea = Math.abs(datasetWidth * datasetHeight);
        
        if (datasetArea == 0 || queryArea == 0) return 0.0;
        
        // Calculate Precision and Recall
        double precision = intersectionArea / datasetArea;  // How much of dataset overlaps with query
        double recall = intersectionArea / queryArea;       // How much of query is covered by dataset
        
        // Calculate F1 score (harmonic mean)
        if (precision == 0.0 && recall == 0.0) return 0.0;
        double f1 = 2.0 * (precision * recall) / (precision + recall);
        
        return Math.min(1.0, f1);
    }
    
    /**
     * Calculate time F1 score (harmonic mean of Precision and Recall)
     * F1 = 2 × (Precision × Recall) / (Precision + Recall)
     * where:
     *   Precision = intersection duration / dataset duration (how much of dataset overlaps with query)
     *   Recall = intersection duration / query duration (how much of query is covered by dataset)
     * Returns value in [0, 1]
     * 
     * This is better than IoU for data discovery because:
     * - High Recall (coverage) when dataset fully covers query time range → high F1
     * - High Precision when query fully covers dataset time range → high F1
     * - Better reflects relevance for temporal data discovery
     */
    private double calculateTimeF1(String queryStart, String queryEnd, 
                                   String datasetBegin, String datasetEnd, 
                                   long queryTimeRange) {
        try {
            long qStart = parseTimeToEpoch(queryStart);
            long qEnd = parseTimeToEpoch(queryEnd);
            long dStart = parseTimeToEpoch(datasetBegin);
            long dEnd = parseTimeToEpoch(datasetEnd);
            
            // Calculate overlap (intersection)
            long overlapStart = Math.max(qStart, dStart);
            long overlapEnd = Math.min(qEnd, dEnd);
            
            // Check if there's overlap
            if (overlapStart >= overlapEnd) {
                return 0.0;
            }
            
            long intersectionDuration = overlapEnd - overlapStart;
            
            // Calculate dataset duration
            long datasetDuration = dEnd - dStart;
            if (datasetDuration < 0) datasetDuration = 0;
            
            if (datasetDuration == 0 || queryTimeRange == 0) return 0.0;
            
            // Calculate Precision and Recall
            double precision = (double) intersectionDuration / datasetDuration;  // How much of dataset overlaps with query
            double recall = (double) intersectionDuration / queryTimeRange;      // How much of query is covered by dataset
            
            // Calculate F1 score (harmonic mean)
            if (precision == 0.0 && recall == 0.0) return 0.0;
            double f1 = 2.0 * (precision * recall) / (precision + recall);
            
            return Math.min(1.0, f1);
        } catch (Exception e) {
            // Failed to calculate time F1 - silently ignore
            return 0.0;
        }
    }
    
    /**
     * Parse time string "YYYYMMDD HH:mm:ss" to epoch milliseconds
     */
    private long parseTimeToEpoch(String timeStr) {
        if (timeStr == null || timeStr.isBlank()) return 0L;
        
        try {
            // Format: "YYYYMMDD HH:mm:ss" or "YYYYMMDD"
            String cleaned = timeStr.trim();
            if (cleaned.length() >= 8) {
                int year = Integer.parseInt(cleaned.substring(0, 4));
                int month = Integer.parseInt(cleaned.substring(4, 6));
                int day = cleaned.length() >= 10 ? Integer.parseInt(cleaned.substring(6, 8)) : 1;
                int hour = cleaned.length() >= 13 ? Integer.parseInt(cleaned.substring(9, 11)) : 0;
                int minute = cleaned.length() >= 16 ? Integer.parseInt(cleaned.substring(12, 14)) : 0;
                int second = cleaned.length() >= 19 ? Integer.parseInt(cleaned.substring(15, 17)) : 0;
                
                Calendar cal = Calendar.getInstance();
                cal.set(year, month - 1, day, hour, minute, second);
                cal.set(Calendar.MILLISECOND, 0);
                return cal.getTimeInMillis();
            }
        } catch (Exception e) {
            // Failed to parse time - silently ignore
        }
        return 0L;
    }
    
    /**
     * Print scoring details for Top 20 datasets (before dataset selection).
     * 
     * @param rankedDatasetIds List of Top 20 dataset IDs (or more if tied)
     * @param dimContributions Dimension contributions map
     * @param rawScores Raw scores before normalization
     * @param normalizedScores Normalized scores
     * @param h Hyperparameters used for scoring
     */
    private void printTopDatasetsScores(List<String> rankedDatasetIds,
                                       Map<String, Map<String, Double>> dimContributions,
                                       Map<String, Double> rawScores,
                                       Map<String, Double> normalizedScores,
                                       Hyperparameters h) {
        if (rankedDatasetIds.isEmpty() || dimContributions.isEmpty() || rawScores.isEmpty()) {
            return;
        }
        
        // Get weights
        final double wTopic = (h.weightTopic != null) ? h.weightTopic : 0.3;
        final double wFormat = (h.weightFormat != null) ? h.weightFormat : 0.1;
        final double wLicense = (h.weightLicense != null) ? h.weightLicense : 0.1;
        final double wOrg = (h.weightOrganization != null) ? h.weightOrganization : 0.1;
        final double wSpace = (h.weightSpace != null) ? h.weightSpace : 0.2;
        final double wTime = (h.weightTime != null) ? h.weightTime : 0.2;
        
        System.out.println("\n========== Dataset Scoring Details (Top 20) ==========");
        System.out.printf("Total ranked datasets: %d\n\n", rankedDatasetIds.size());
        
        // Batch fetch dataset titles and URLs
        Map<String, String> datasetTitles = repo.getDatasetTitles(rankedDatasetIds);
        Map<String, String> datasetUrls = repo.getDatasetUrls(rankedDatasetIds);
        
        for (String datasetId : rankedDatasetIds) {
            double normalizedScore = normalizedScores.getOrDefault(datasetId, 0.0);
            double rawScore = rawScores.getOrDefault(datasetId, 0.0);
            
            Map<String, Double> dims = dimContributions.get(datasetId);
            if (dims == null) dims = new HashMap<>();
            
            // Calculate raw dimension scores (reverse from weighted contributions)
            Map<String, Double> rawDimScores = new HashMap<>();
            for (Map.Entry<String, Double> dimEntry : dims.entrySet()) {
                String dim = dimEntry.getKey();
                double weightedContribution = dimEntry.getValue();
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
                    case "HardFilter":
                        // HardFilter doesn't have a raw score, it's just a flag
                        rawDimScores.put(dim, 1.0);
                        continue;
                    default:
                        continue;
                }
                
                if (weight > 0) {
                    double rawDimScore = weightedContribution / weight;
                    rawDimScores.put(dim, Math.min(1.0, rawDimScore));
                }
            }
            
            // Print dataset header
            System.out.println("----------------------------------------");
            String title = datasetTitles.getOrDefault(datasetId, "Unknown");
            String url = datasetUrls.getOrDefault(datasetId, null);
            System.out.printf("Dataset ID: %s\n", datasetId);
            System.out.printf("Dataset Title: %s\n", title);
            if (url != null && !url.isEmpty()) {
                System.out.printf("Dataset URL: %s\n", url);
            }
            System.out.printf("Raw Total Score: %.4f\n", rawScore);
            System.out.printf("Normalized Score: %.4f\n", normalizedScore);
            
            // Print dimension raw scores
            System.out.println("Dimension Raw Scores:");
            String[] dimOrder = {"Topic", "Format", "License", "Organization", "Space", "Time", "HardFilter"};
            boolean hasAnyDim = false;
            for (String dim : dimOrder) {
                if (rawDimScores.containsKey(dim)) {
                    double rawDim = rawDimScores.get(dim);
                    double weightedContribution = dims.getOrDefault(dim, 0.0);
                    System.out.printf("  %-15s: Raw Score=%.4f, Weighted Contribution=%.4f\n", dim, rawDim, weightedContribution);
                    hasAnyDim = true;
                } else if (!"HardFilter".equals(dim)) {
                    // Show dimensions that were not calculated (score = 0.0)
                    // This helps debug why certain dimensions are missing
                    System.out.printf("  %-15s: Raw Score=0.0000, Weighted Contribution=0.0000 (not calculated)\n", dim);
                }
            }
            if (!hasAnyDim) {
                System.out.println("  (No dimension scores)");
            }
            System.out.println();
        }
        
        System.out.println("========================================\n");
    }
    
    /**
     * Print scoring details for selected datasets that enter answer synthesis stage.
     * This method should be called after dataset selection is complete.
     * 
     * @param result GraphRetrievalResult containing dimension contributions and normalized scores
     * @param selectedDatasetIds List of dataset IDs that were selected for answer synthesis
     * @param hyperparams Hyperparameters used for scoring
     */
    public void printSelectedDatasetScores(GraphRetrievalResult result, 
                                          List<String> selectedDatasetIds,
                                          Hyperparameters hyperparams) {
        if (result == null || selectedDatasetIds == null || selectedDatasetIds.isEmpty()) {
            return;
        }
        
        Map<String, Map<String, Double>> dimContributions = result.dimensionContributions();
        Map<String, Double> normalizedScores = result.scoresByDatasetId();
        
        if (dimContributions.isEmpty() || normalizedScores.isEmpty()) {
            return;
        }
        
        Hyperparameters h = (hyperparams != null) ? hyperparams : Hyperparameters.defaults();
        h.normalizeWeights();
        
        // Get weights
        final double wTopic = (h.weightTopic != null) ? h.weightTopic : 0.3;
        final double wFormat = (h.weightFormat != null) ? h.weightFormat : 0.1;
        final double wLicense = (h.weightLicense != null) ? h.weightLicense : 0.1;
        final double wOrg = (h.weightOrganization != null) ? h.weightOrganization : 0.1;
        final double wSpace = (h.weightSpace != null) ? h.weightSpace : 0.2;
        final double wTime = (h.weightTime != null) ? h.weightTime : 0.2;
        
        // Calculate raw scores from dimension contributions
        Map<String, Double> rawScores = new HashMap<>();
        for (String datasetId : selectedDatasetIds) {
            Map<String, Double> dims = dimContributions.get(datasetId);
            if (dims == null) continue;
            
            double rawScore = 0.0;
            for (Map.Entry<String, Double> dimEntry : dims.entrySet()) {
                // Cap each dimension contribution at 1.0, then sum
                double capped = Math.min(1.0, dimEntry.getValue());
                rawScore += capped;
            }
            rawScores.put(datasetId, rawScore);
        }
        
        // Sort selected datasets by normalized score (descending)
        List<String> sortedDatasetIds = new ArrayList<>(selectedDatasetIds);
        sortedDatasetIds.sort((a, b) -> {
            double scoreA = normalizedScores.getOrDefault(a, 0.0);
            double scoreB = normalizedScores.getOrDefault(b, 0.0);
            return Double.compare(scoreB, scoreA);
        });
        
        System.out.println("\n========== Dataset Scoring Details (Selected for Answer Synthesis) ==========");
        System.out.printf("Total selected datasets: %d\n\n", sortedDatasetIds.size());
        
        // Batch fetch dataset titles and URLs
        Map<String, String> datasetTitles = repo.getDatasetTitles(sortedDatasetIds);
        Map<String, String> datasetUrls = repo.getDatasetUrls(sortedDatasetIds);
        
        for (String datasetId : sortedDatasetIds) {
            double normalizedScore = normalizedScores.getOrDefault(datasetId, 0.0);
            double rawScore = rawScores.getOrDefault(datasetId, 0.0);
            
            Map<String, Double> dims = dimContributions.get(datasetId);
            if (dims == null) dims = new HashMap<>();
            
            // Calculate raw dimension scores (reverse from weighted contributions)
            Map<String, Double> rawDimScores = new HashMap<>();
            for (Map.Entry<String, Double> dimEntry : dims.entrySet()) {
                String dim = dimEntry.getKey();
                double weightedContribution = dimEntry.getValue();
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
                    case "HardFilter":
                        // HardFilter doesn't have a raw score, it's just a flag
                        rawDimScores.put(dim, 1.0);
                        continue;
                    default:
                        continue;
                }
                
                if (weight > 0) {
                    double rawDimScore = weightedContribution / weight;
                    rawDimScores.put(dim, Math.min(1.0, rawDimScore));
                }
            }
            
            // Print dataset header
            System.out.println("----------------------------------------");
            String title = datasetTitles.getOrDefault(datasetId, "Unknown");
            String url = datasetUrls.getOrDefault(datasetId, null);
            System.out.printf("Dataset ID: %s\n", datasetId);
            System.out.printf("Dataset Title: %s\n", title);
            if (url != null && !url.isEmpty()) {
                System.out.printf("Dataset URL: %s\n", url);
            }
            System.out.printf("Raw Total Score: %.4f\n", rawScore);
            System.out.printf("Normalized Score: %.4f\n", normalizedScore);
            
            // Print dimension raw scores
            System.out.println("Dimension Raw Scores:");
            String[] dimOrder = {"Topic", "Format", "License", "Organization", "Space", "Time", "HardFilter"};
            boolean hasAnyDim = false;
            for (String dim : dimOrder) {
                if (rawDimScores.containsKey(dim)) {
                    double rawDim = rawDimScores.get(dim);
                    double weightedContribution = dims.getOrDefault(dim, 0.0);
                    System.out.printf("  %-15s: Raw Score=%.4f, Weighted Contribution=%.4f\n", dim, rawDim, weightedContribution);
                    hasAnyDim = true;
                }
            }
            if (!hasAnyDim) {
                System.out.println("  (No dimension scores)");
            }
            System.out.println();
        }
        
        System.out.println("========================================\n");
    }
}

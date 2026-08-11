package edu.psu.giscience.igdd.service;

import edu.psu.giscience.igdd.domain.Hyperparameters;
import edu.psu.giscience.igdd.domain.graphrag.CandidateEntity;
import edu.psu.giscience.igdd.domain.graphrag.HitlSlot;
import edu.psu.giscience.igdd.domain.graphrag.PendingHitl;
import edu.psu.giscience.igdd.domain.intent.GeoIntent;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class HitlPlanner {

    private final GraphEntityLinker linker;
    // Thread pool for parallelizing embedding searches across dimensions
    private static final ExecutorService executorService = Executors.newFixedThreadPool(4);

    // If the parser is extremely confident, skip HITL to avoid over-confirming.
    // The user can always refine constraints in a follow-up.
    private static final double AUTO_SKIP_CONFIRM_CONFIDENCE = 0.97;

    public HitlPlanner(GraphEntityLinker linker) {
        this.linker = linker;
    }

    /**
     * Get top-5 candidates for detected dimensions (similarity > 0) that require user selection.
     * Only returns candidates for dimensions with confidence >= 0.5 (let user select from candidates).
     * <p>
     * NOTE:
     * - Only returns candidates for dimensions with confidence >= 0.5
     * - Only returns candidates with similarity score > 0.5 (except Space and Time dimensions)
     * - If less than 5 candidates found, returns all available
     * - If no candidates found, returns empty list (frontend will show "not found" message)
     * - For dimensions with confidence < 0.5, use nextHitl() instead (let user re-enter)
     */
    public java.util.Map<String, List<CandidateEntity>> getCandidatesForSelection(GeoIntent intent, boolean useKeywords, Hyperparameters hyperparams, String apiKey, String questionId) {
        // Set context for embedding calls
        linker.setCurrentContext(apiKey, questionId);
        
        java.util.Map<String, List<CandidateEntity>> result = new java.util.HashMap<>();
        if (intent == null) return result;
        
        Hyperparameters h = (hyperparams != null) ? hyperparams : Hyperparameters.defaults();
        double confidenceThreshold = (h.confidenceThreshold != null) ? h.confidenceThreshold : 0.5;
        double similarityScoreThreshold = (h.similarityScoreThreshold != null) ? h.similarityScoreThreshold : 0.5;
        boolean useEmbeddingSearch = (h.useEmbeddingSearch != null) ? h.useEmbeddingSearch : true;
        
        normalizeResolvedFlags(intent, confidenceThreshold);
        
        // Topic: get top-5 candidates with score > 0.5, only if confidence >= 0.5
        if (intent.getTopic() != null && !isBlank(bestText(intent.getTopic().getValue(), intent.getTopic().getRawText()))) {
            if (intent.getTopic().getKgNodeIds() == null || intent.getTopic().getKgNodeIds().isEmpty()) {
                // Only return candidates if confidence >= threshold (let user select from candidates)
                if (intent.getTopic().getConfidence() >= confidenceThreshold) {
                    String text = bestText(intent.getTopic().getValue(), intent.getTopic().getRawText());
                    // Split multiple values and get candidates for each separately
                    List<String> topicValues = splitDimensionValue(text);
                    if (topicValues.isEmpty()) topicValues = List.of(text); // Fallback to original if split fails
                    
                    List<CandidateEntity> allCandidates = new ArrayList<>();
                    // Request more candidates to ensure we have enough after filtering
                    // For text search, we need more candidates because lexical scores may be lower
                    // For embedding search, we also need more to ensure we get top 10 candidates after filtering
                    int requestedCandidates = useEmbeddingSearch ? 20 : 20;
                    for (String topicValue : topicValues) {
                        if (isBlank(topicValue)) continue;
                        List<CandidateEntity> cand = linker.candidatesForTopic(topicValue, requestedCandidates, useKeywords, useEmbeddingSearch);
                        // Filter: only candidates with score > threshold
                        final double scoreThreshold = similarityScoreThreshold;
                        List<CandidateEntity> filtered = cand.stream()
                            .filter(c -> c != null && c.score() > scoreThreshold)
                            .toList();
                        allCandidates.addAll(filtered);
                    }
                    
                    // Deduplicate by nodeId and limit to top 10 (score > 0.5)
                    Map<String, CandidateEntity> uniqueCandidates = new LinkedHashMap<>();
                    for (CandidateEntity c : allCandidates) {
                        if (c != null && c.nodeId() != null && !uniqueCandidates.containsKey(c.nodeId())) {
                            uniqueCandidates.put(c.nodeId(), c);
                        }
                    }
                    List<CandidateEntity> finalCandidates = uniqueCandidates.values().stream()
                        .sorted((a, b) -> Double.compare(b.score(), a.score()))
                        .limit(10) // Return top 10 with score > 0.5
                        .toList();
                    
                    if (!finalCandidates.isEmpty()) {
                        result.put("Topic", finalCandidates);
                    }
                }
            }
        }
        
        // Format: get top-10 candidates with score > 0.5, only if confidence >= threshold
        if (intent.getFormat() != null && !isBlank(bestText(intent.getFormat().getValue(), intent.getFormat().getRawText()))) {
            if (intent.getFormat().getKgNodeIds() == null || intent.getFormat().getKgNodeIds().isEmpty()) {
                if (intent.getFormat().getConfidence() >= confidenceThreshold) {
                    String text = bestText(intent.getFormat().getValue(), intent.getFormat().getRawText());
                    // Split multiple values and get candidates for each separately
                    List<String> formatValues = splitDimensionValue(text);
                    if (formatValues.isEmpty()) formatValues = List.of(text);
                    
                    List<CandidateEntity> allCandidates = new ArrayList<>();
                    for (String formatValue : formatValues) {
                        if (isBlank(formatValue)) continue;
                        // Request more candidates to ensure we get top 10 after filtering
                        List<CandidateEntity> cand = linker.candidatesForFormat(formatValue, 20, useEmbeddingSearch);
                        List<CandidateEntity> filtered = cand.stream()
                            .filter(c -> c != null && c.score() > similarityScoreThreshold)
                            .toList();
                        allCandidates.addAll(filtered);
                    }
                    
                    // Deduplicate by nodeId and limit to top 10 (score > 0.5)
                    Map<String, CandidateEntity> uniqueCandidates = new LinkedHashMap<>();
                    for (CandidateEntity c : allCandidates) {
                        if (c != null && c.nodeId() != null && !uniqueCandidates.containsKey(c.nodeId())) {
                            uniqueCandidates.put(c.nodeId(), c);
                        }
                    }
                    List<CandidateEntity> finalCandidates = uniqueCandidates.values().stream()
                        .sorted((a, b) -> Double.compare(b.score(), a.score()))
                        .limit(10) // Return top 10 with score > 0.5
                        .toList();
                    
                    if (!finalCandidates.isEmpty()) {
                        result.put("Format", finalCandidates);
                    }
                }
            }
        }
        
        // License: get top-10 candidates with score > 0.5, only if confidence >= threshold
        if (intent.getLicense() != null && !isBlank(bestText(intent.getLicense().getValue(), intent.getLicense().getRawText()))) {
            if (intent.getLicense().getKgNodeIds() == null || intent.getLicense().getKgNodeIds().isEmpty()) {
                if (intent.getLicense().getConfidence() >= confidenceThreshold) {
                    String text = bestText(intent.getLicense().getValue(), intent.getLicense().getRawText());
                    // Split multiple values and get candidates for each separately
                    List<String> licenseValues = splitDimensionValue(text);
                    if (licenseValues.isEmpty()) licenseValues = List.of(text);
                    
                    List<CandidateEntity> allCandidates = new ArrayList<>();
                    for (String licenseValue : licenseValues) {
                        if (isBlank(licenseValue)) continue;
                        // Request more candidates to ensure we get top 10 after filtering
                        List<CandidateEntity> cand = linker.candidatesForLicense(licenseValue, 20, useEmbeddingSearch);
                        List<CandidateEntity> filtered = cand.stream()
                            .filter(c -> c != null && c.score() > similarityScoreThreshold)
                            .toList();
                        allCandidates.addAll(filtered);
                    }
                    
                    // Deduplicate by nodeId and limit to top 10 (score > 0.5)
                    Map<String, CandidateEntity> uniqueCandidates = new LinkedHashMap<>();
                    for (CandidateEntity c : allCandidates) {
                        if (c != null && c.nodeId() != null && !uniqueCandidates.containsKey(c.nodeId())) {
                            uniqueCandidates.put(c.nodeId(), c);
                        }
                    }
                    List<CandidateEntity> finalCandidates = uniqueCandidates.values().stream()
                        .sorted((a, b) -> Double.compare(b.score(), a.score()))
                        .limit(10) // Return top 10 with score > 0.5
                        .toList();
                    
                    if (!finalCandidates.isEmpty()) {
                        result.put("License", finalCandidates);
                    }
                }
            }
        }
        
        // Organization: get top-10 candidates with score > 0.5, only if confidence >= threshold
        if (intent.getOrganization() != null && !isBlank(bestText(intent.getOrganization().getValue(), intent.getOrganization().getRawText()))) {
            if (intent.getOrganization().getKgNodeIds() == null || intent.getOrganization().getKgNodeIds().isEmpty()) {
                if (intent.getOrganization().getConfidence() >= confidenceThreshold) {
                    String text = bestText(intent.getOrganization().getValue(), intent.getOrganization().getRawText());
                    // Split multiple values and get candidates for each separately
                    List<String> orgValues = splitDimensionValue(text);
                    if (orgValues.isEmpty()) orgValues = List.of(text);
                    
                    List<CandidateEntity> allCandidates = new ArrayList<>();
                    for (String orgValue : orgValues) {
                        if (isBlank(orgValue)) continue;
                        // Request more candidates to ensure we get top 10 after filtering
                        List<CandidateEntity> cand = linker.candidatesForOrganization(orgValue, 20, useEmbeddingSearch);
                        List<CandidateEntity> filtered = cand.stream()
                            .filter(c -> c != null && c.score() > similarityScoreThreshold)
                            .toList();
                        allCandidates.addAll(filtered);
                    }
                    
                    // Deduplicate by nodeId and limit to top 10 (score > 0.5)
                    Map<String, CandidateEntity> uniqueCandidates = new LinkedHashMap<>();
                    for (CandidateEntity c : allCandidates) {
                        if (c != null && c.nodeId() != null && !uniqueCandidates.containsKey(c.nodeId())) {
                            uniqueCandidates.put(c.nodeId(), c);
                        }
                    }
                    List<CandidateEntity> finalCandidates = uniqueCandidates.values().stream()
                        .sorted((a, b) -> Double.compare(b.score(), a.score()))
                        .limit(10) // Return top 10 with score > 0.5
                        .toList();
                    
                    if (!finalCandidates.isEmpty()) {
                        result.put("Organization", finalCandidates);
                    }
                }
            }
        }
        
        // Source: removed from candidate selection - only used for hard filtering (via data catalog selection or user intent)
        // Source is handled directly in GraphRetrievalService as a hard filter, not as a soft filter dimension
        
        return result;
    }

    /**
     * Get all candidates for all dimensions for display purposes (both selected and unselected).
     * This method always returns candidates regardless of whether they are already selected,
     * so it can be used to display candidates in the parsed intent panel.
     */
    public java.util.Map<String, List<CandidateEntity>> getAllCandidatesForDisplay(GeoIntent intent, boolean useKeywords, Hyperparameters hyperparams, String apiKey, String questionId) {
        // Set context for embedding calls
        linker.setCurrentContext(apiKey, questionId);
        
        java.util.Map<String, List<CandidateEntity>> result = new java.util.HashMap<>();
        if (intent == null) return result;
        
        Hyperparameters h = (hyperparams != null) ? hyperparams : Hyperparameters.defaults();
        double similarityScoreThreshold = (h.similarityScoreThreshold != null) ? h.similarityScoreThreshold : 0.5;
        boolean useEmbeddingSearch = (h.useEmbeddingSearch != null) ? h.useEmbeddingSearch : true;
        
        // Topic: get candidates for display
        // If candidates are already selected, only return selected ones; otherwise return all candidates
        if (intent.getTopic() != null && !isBlank(bestText(intent.getTopic().getValue(), intent.getTopic().getRawText()))) {
            List<String> selectedTopicIds = intent.getTopic().getKgNodeIds();
            
            // If candidates are already selected, only return selected ones
            if (selectedTopicIds != null && !selectedTopicIds.isEmpty()) {
                // Get all candidates first (will use cache), then filter to only selected ones
                String text = bestText(intent.getTopic().getValue(), intent.getTopic().getRawText());
                List<String> topicValues = splitDimensionValue(text);
                if (topicValues.isEmpty()) topicValues = List.of(text);
                
                List<CandidateEntity> allCandidates = new ArrayList<>();
                for (String topicValue : topicValues) {
                    if (isBlank(topicValue)) continue;
                    // Request candidates (will use cache if available)
                    List<CandidateEntity> cand = linker.candidatesForTopic(topicValue, 50, useKeywords, useEmbeddingSearch);
                    allCandidates.addAll(cand);
                }
                
                // Filter to only selected candidates
                Set<String> selectedIdsSet = new HashSet<>(selectedTopicIds);
                List<CandidateEntity> selectedCandidates = allCandidates.stream()
                    .filter(c -> c != null && c.nodeId() != null && selectedIdsSet.contains(c.nodeId()))
                    .sorted((a, b) -> Double.compare(b.score(), a.score()))
                    .toList();
                
                if (!selectedCandidates.isEmpty()) {
                    result.put("Topic", selectedCandidates);
                }
            } else {
                // No candidates selected yet, return all candidates above threshold
                String text = bestText(intent.getTopic().getValue(), intent.getTopic().getRawText());
                List<String> topicValues = splitDimensionValue(text);
                if (topicValues.isEmpty()) topicValues = List.of(text);
                
                List<CandidateEntity> allCandidates = new ArrayList<>();
                for (String topicValue : topicValues) {
                    if (isBlank(topicValue)) continue;
                    // Request more candidates to ensure we get all candidates above threshold
                    List<CandidateEntity> cand = linker.candidatesForTopic(topicValue, 50, useKeywords, useEmbeddingSearch);
                    List<CandidateEntity> filtered = cand.stream()
                        .filter(c -> c != null && c.score() > similarityScoreThreshold)
                        .toList();
                    allCandidates.addAll(filtered);
                }
                
                // Deduplicate and return all candidates above threshold (no limit)
                Map<String, CandidateEntity> uniqueCandidates = new LinkedHashMap<>();
                for (CandidateEntity c : allCandidates) {
                    if (c != null && c.nodeId() != null && !uniqueCandidates.containsKey(c.nodeId())) {
                        uniqueCandidates.put(c.nodeId(), c);
                    }
                }
                List<CandidateEntity> finalCandidates = uniqueCandidates.values().stream()
                    .sorted((a, b) -> Double.compare(b.score(), a.score()))
                    .toList(); // Return all candidates above threshold, no limit
                
                if (!finalCandidates.isEmpty()) {
                    result.put("Topic", finalCandidates);
                }
            }
        }
        
        // Format: get candidates for display
        // If candidates are already selected, only return selected ones; otherwise return all candidates
        if (intent.getFormat() != null && !isBlank(bestText(intent.getFormat().getValue(), intent.getFormat().getRawText()))) {
            List<String> selectedFormatIds = intent.getFormat().getKgNodeIds();
            
            // If candidates are already selected, only return selected ones
            if (selectedFormatIds != null && !selectedFormatIds.isEmpty()) {
                // Get all candidates first (will use cache), then filter to only selected ones
                String text = bestText(intent.getFormat().getValue(), intent.getFormat().getRawText());
                List<String> formatValues = splitDimensionValue(text);
                if (formatValues.isEmpty()) formatValues = List.of(text);
                
                List<CandidateEntity> allCandidates = new ArrayList<>();
                for (String formatValue : formatValues) {
                    if (isBlank(formatValue)) continue;
                    // Request candidates (will use cache if available)
                    List<CandidateEntity> cand = linker.candidatesForFormat(formatValue, 50, useEmbeddingSearch);
                    allCandidates.addAll(cand);
                }
                
                // Filter to only selected candidates
                Set<String> selectedIdsSet = new HashSet<>(selectedFormatIds);
                List<CandidateEntity> selectedCandidates = allCandidates.stream()
                    .filter(c -> c != null && c.nodeId() != null && selectedIdsSet.contains(c.nodeId()))
                    .sorted((a, b) -> Double.compare(b.score(), a.score()))
                    .toList();
                
                if (!selectedCandidates.isEmpty()) {
                    result.put("Format", selectedCandidates);
                }
            } else {
                // No candidates selected yet, return all candidates above threshold
                String text = bestText(intent.getFormat().getValue(), intent.getFormat().getRawText());
                List<String> formatValues = splitDimensionValue(text);
                if (formatValues.isEmpty()) formatValues = List.of(text);
                
                List<CandidateEntity> allCandidates = new ArrayList<>();
                for (String formatValue : formatValues) {
                    if (isBlank(formatValue)) continue;
                    // Request more candidates to ensure we get all candidates above threshold
                    List<CandidateEntity> cand = linker.candidatesForFormat(formatValue, 50, useEmbeddingSearch);
                    List<CandidateEntity> filtered = cand.stream()
                        .filter(c -> c != null && c.score() > similarityScoreThreshold)
                        .toList();
                    allCandidates.addAll(filtered);
                }
                
                Map<String, CandidateEntity> uniqueCandidates = new LinkedHashMap<>();
                for (CandidateEntity c : allCandidates) {
                    if (c != null && c.nodeId() != null && !uniqueCandidates.containsKey(c.nodeId())) {
                        uniqueCandidates.put(c.nodeId(), c);
                    }
                }
                List<CandidateEntity> finalCandidates = uniqueCandidates.values().stream()
                    .sorted((a, b) -> Double.compare(b.score(), a.score()))
                    .toList(); // Return all candidates above threshold, no limit
                
                if (!finalCandidates.isEmpty()) {
                    result.put("Format", finalCandidates);
                }
            }
        }
        
        // License: get candidates for display
        // If candidates are already selected, only return selected ones; otherwise return all candidates
        if (intent.getLicense() != null && !isBlank(bestText(intent.getLicense().getValue(), intent.getLicense().getRawText()))) {
            List<String> selectedLicenseIds = intent.getLicense().getKgNodeIds();
            
            // If candidates are already selected, only return selected ones
            if (selectedLicenseIds != null && !selectedLicenseIds.isEmpty()) {
                // Get all candidates first (will use cache), then filter to only selected ones
                String text = bestText(intent.getLicense().getValue(), intent.getLicense().getRawText());
                List<String> licenseValues = splitDimensionValue(text);
                if (licenseValues.isEmpty()) licenseValues = List.of(text);
                
                List<CandidateEntity> allCandidates = new ArrayList<>();
                for (String licenseValue : licenseValues) {
                    if (isBlank(licenseValue)) continue;
                    // Request candidates (will use cache if available)
                    List<CandidateEntity> cand = linker.candidatesForLicense(licenseValue, 50, useEmbeddingSearch);
                    allCandidates.addAll(cand);
                }
                
                // Filter to only selected candidates
                Set<String> selectedIdsSet = new HashSet<>(selectedLicenseIds);
                List<CandidateEntity> selectedCandidates = allCandidates.stream()
                    .filter(c -> c != null && c.nodeId() != null && selectedIdsSet.contains(c.nodeId()))
                    .sorted((a, b) -> Double.compare(b.score(), a.score()))
                    .toList();
                
                if (!selectedCandidates.isEmpty()) {
                    result.put("License", selectedCandidates);
                }
            } else {
                // No candidates selected yet, return all candidates above threshold
                String text = bestText(intent.getLicense().getValue(), intent.getLicense().getRawText());
                List<String> licenseValues = splitDimensionValue(text);
                if (licenseValues.isEmpty()) licenseValues = List.of(text);
                
                List<CandidateEntity> allCandidates = new ArrayList<>();
                for (String licenseValue : licenseValues) {
                    if (isBlank(licenseValue)) continue;
                    // Request more candidates to ensure we get all candidates above threshold
                    List<CandidateEntity> cand = linker.candidatesForLicense(licenseValue, 50, useEmbeddingSearch);
                    List<CandidateEntity> filtered = cand.stream()
                        .filter(c -> c != null && c.score() > similarityScoreThreshold)
                        .toList();
                    allCandidates.addAll(filtered);
                }
                
                Map<String, CandidateEntity> uniqueCandidates = new LinkedHashMap<>();
                for (CandidateEntity c : allCandidates) {
                    if (c != null && c.nodeId() != null && !uniqueCandidates.containsKey(c.nodeId())) {
                        uniqueCandidates.put(c.nodeId(), c);
                    }
                }
                List<CandidateEntity> finalCandidates = uniqueCandidates.values().stream()
                    .sorted((a, b) -> Double.compare(b.score(), a.score()))
                    .toList(); // Return all candidates above threshold, no limit
                
                if (!finalCandidates.isEmpty()) {
                    result.put("License", finalCandidates);
                }
            }
        }
        
        // Organization: get candidates for display
        // If candidates are already selected, only return selected ones; otherwise return all candidates
        if (intent.getOrganization() != null && !isBlank(bestText(intent.getOrganization().getValue(), intent.getOrganization().getRawText()))) {
            List<String> selectedOrgIds = intent.getOrganization().getKgNodeIds();
            
            // If candidates are already selected, only return selected ones
            if (selectedOrgIds != null && !selectedOrgIds.isEmpty()) {
                // Get all candidates first (will use cache), then filter to only selected ones
                String text = bestText(intent.getOrganization().getValue(), intent.getOrganization().getRawText());
                List<String> orgValues = splitDimensionValue(text);
                if (orgValues.isEmpty()) orgValues = List.of(text);
                
                List<CandidateEntity> allCandidates = new ArrayList<>();
                for (String orgValue : orgValues) {
                    if (isBlank(orgValue)) continue;
                    // Request candidates (will use cache if available)
                    List<CandidateEntity> cand = linker.candidatesForOrganization(orgValue, 50, useEmbeddingSearch);
                    allCandidates.addAll(cand);
                }
                
                // Filter to only selected candidates
                Set<String> selectedIdsSet = new HashSet<>(selectedOrgIds);
                List<CandidateEntity> selectedCandidates = allCandidates.stream()
                    .filter(c -> c != null && c.nodeId() != null && selectedIdsSet.contains(c.nodeId()))
                    .sorted((a, b) -> Double.compare(b.score(), a.score()))
                    .toList();
                
                if (!selectedCandidates.isEmpty()) {
                    result.put("Organization", selectedCandidates);
                }
            } else {
                // No candidates selected yet, return all candidates above threshold
                String text = bestText(intent.getOrganization().getValue(), intent.getOrganization().getRawText());
                List<String> orgValues = splitDimensionValue(text);
                if (orgValues.isEmpty()) orgValues = List.of(text);
                
                List<CandidateEntity> allCandidates = new ArrayList<>();
                for (String orgValue : orgValues) {
                    if (isBlank(orgValue)) continue;
                    // Request more candidates to ensure we get all candidates above threshold
                    List<CandidateEntity> cand = linker.candidatesForOrganization(orgValue, 50, useEmbeddingSearch);
                    List<CandidateEntity> filtered = cand.stream()
                        .filter(c -> c != null && c.score() > similarityScoreThreshold)
                        .toList();
                    allCandidates.addAll(filtered);
                }
                
                Map<String, CandidateEntity> uniqueCandidates = new LinkedHashMap<>();
                for (CandidateEntity c : allCandidates) {
                    if (c != null && c.nodeId() != null && !uniqueCandidates.containsKey(c.nodeId())) {
                        uniqueCandidates.put(c.nodeId(), c);
                    }
                }
                List<CandidateEntity> finalCandidates = uniqueCandidates.values().stream()
                    .sorted((a, b) -> Double.compare(b.score(), a.score()))
                    .toList(); // Return all candidates above threshold, no limit
                
                if (!finalCandidates.isEmpty()) {
                    result.put("Organization", finalCandidates);
                }
            }
        }
        
        return result;
    }

    /**
     * Automatically set top-5 candidates for detected dimensions (no HITL confirmation needed).
     * Only auto-set if confidence >= 0.5. If confidence < 0.5, require HITL confirmation.
     * <p>
     * NOTE:
     * - If a dimension is detected with confidence >= 0.5, automatically use top-5 candidates.
     * - If confidence < 0.5, skip auto-set and require HITL confirmation.
     * - User can refine later by continuing the conversation.
     */
    public void autoSetCandidates(GeoIntent intent, boolean useKeywords, Hyperparameters hyperparams, String apiKey, String questionId) {
        // Set context for embedding calls
        linker.setCurrentContext(apiKey, questionId);
        
        if (intent == null) return;
        
        Hyperparameters h = (hyperparams != null) ? hyperparams : Hyperparameters.defaults();
        double confidenceThreshold = (h.confidenceThreshold != null) ? h.confidenceThreshold : 0.5;
        boolean useEmbeddingSearch = (h.useEmbeddingSearch != null) ? h.useEmbeddingSearch : true;
        double similarityScoreThreshold = (h.similarityScoreThreshold != null) ? h.similarityScoreThreshold : 0.5;
        
        normalizeResolvedFlags(intent, confidenceThreshold);
        
        // Collect tasks for parallel execution
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        
        // Topic: auto-set all candidates with score > 0.5 if detected but not resolved AND confidence >= 0.5
        if (intent.getTopic() != null && !isBlank(bestText(intent.getTopic().getValue(), intent.getTopic().getRawText()))) {
            if (intent.getTopic().getKgNodeIds() == null || intent.getTopic().getKgNodeIds().isEmpty()) {
                if (intent.getTopic().getConfidence() >= confidenceThreshold) {
                    String text = bestText(intent.getTopic().getValue(), intent.getTopic().getRawText());
                    tasks.add(CompletableFuture.runAsync(() -> {
                        List<CandidateEntity> cand = linker.candidatesForTopic(text, 50, useKeywords, useEmbeddingSearch);
                        List<CandidateEntity> selectedCandidates = cand.stream()
                            .filter(c -> c != null && c.score() > similarityScoreThreshold)
                            .toList();
                        if (!selectedCandidates.isEmpty()) {
                            List<String> nodeIds = selectedCandidates.stream()
                                .map(CandidateEntity::nodeId)
                                .filter(java.util.Objects::nonNull)
                                .toList();
                            if (!nodeIds.isEmpty()) {
                                synchronized (intent) {
                                    intent.getTopic().setKgNodeIds(nodeIds);
                                    intent.getTopic().setKgNodeId(nodeIds.get(0));
                                    intent.getTopic().setNeedsClarification(false);
                                }
                            }
                        }
                    }, executorService));
                } else {
                    intent.getTopic().setNeedsClarification(true);
                }
            }
        }
        
        // Format: auto-set all candidates with score > 0.5 if confidence >= threshold
        if (intent.getFormat() != null && !isBlank(bestText(intent.getFormat().getValue(), intent.getFormat().getRawText()))) {
            if (intent.getFormat().getKgNodeIds() == null || intent.getFormat().getKgNodeIds().isEmpty()) {
                if (intent.getFormat().getConfidence() >= confidenceThreshold) {
                    String text = bestText(intent.getFormat().getValue(), intent.getFormat().getRawText());
                    tasks.add(CompletableFuture.runAsync(() -> {
                        List<CandidateEntity> cand = linker.candidatesForFormat(text, 50, useEmbeddingSearch);
                        List<CandidateEntity> selectedCandidates = cand.stream()
                            .filter(c -> c != null && c.score() > similarityScoreThreshold)
                            .toList();
                        if (!selectedCandidates.isEmpty()) {
                            List<String> nodeIds = selectedCandidates.stream()
                                .map(CandidateEntity::nodeId)
                                .filter(java.util.Objects::nonNull)
                                .toList();
                            if (!nodeIds.isEmpty()) {
                                synchronized (intent) {
                                    intent.getFormat().setKgNodeIds(nodeIds);
                                    intent.getFormat().setKgNodeId(nodeIds.get(0));
                                    intent.getFormat().setNeedsClarification(false);
                                }
                            }
                        }
                    }, executorService));
                } else {
                    intent.getFormat().setNeedsClarification(true);
                }
            }
        }
        
        // License: auto-set all candidates with score > 0.5 if confidence >= 0.5
        if (intent.getLicense() != null && !isBlank(bestText(intent.getLicense().getValue(), intent.getLicense().getRawText()))) {
            if (intent.getLicense().getKgNodeIds() == null || intent.getLicense().getKgNodeIds().isEmpty()) {
                if (intent.getLicense().getConfidence() >= confidenceThreshold) {
                    String text = bestText(intent.getLicense().getValue(), intent.getLicense().getRawText());
                    tasks.add(CompletableFuture.runAsync(() -> {
                        List<CandidateEntity> cand = linker.candidatesForLicense(text, 50, useEmbeddingSearch);
                        List<CandidateEntity> selectedCandidates = cand.stream()
                            .filter(c -> c != null && c.score() > similarityScoreThreshold)
                            .toList();
                        if (!selectedCandidates.isEmpty()) {
                            List<String> nodeIds = selectedCandidates.stream()
                                .map(CandidateEntity::nodeId)
                                .filter(java.util.Objects::nonNull)
                                .toList();
                            if (!nodeIds.isEmpty()) {
                                synchronized (intent) {
                                    intent.getLicense().setKgNodeIds(nodeIds);
                                    intent.getLicense().setKgNodeId(nodeIds.get(0));
                                    intent.getLicense().setNeedsClarification(false);
                                }
                            }
                        }
                    }, executorService));
                } else {
                    intent.getLicense().setNeedsClarification(true);
                }
            }
        }
        
        // Organization: auto-set all candidates with score > 0.5 if confidence >= 0.5
        if (intent.getOrganization() != null && !isBlank(bestText(intent.getOrganization().getValue(), intent.getOrganization().getRawText()))) {
            if (intent.getOrganization().getKgNodeIds() == null || intent.getOrganization().getKgNodeIds().isEmpty()) {
                if (intent.getOrganization().getConfidence() >= confidenceThreshold) {
                    String text = bestText(intent.getOrganization().getValue(), intent.getOrganization().getRawText());
                    tasks.add(CompletableFuture.runAsync(() -> {
                        List<CandidateEntity> cand = linker.candidatesForOrganization(text, 50, useEmbeddingSearch);
                        List<CandidateEntity> selectedCandidates = cand.stream()
                            .filter(c -> c != null && c.score() > similarityScoreThreshold)
                            .toList();
                        if (!selectedCandidates.isEmpty()) {
                            List<String> nodeIds = selectedCandidates.stream()
                                .map(CandidateEntity::nodeId)
                                .filter(java.util.Objects::nonNull)
                                .toList();
                            if (!nodeIds.isEmpty()) {
                                synchronized (intent) {
                                    intent.getOrganization().setKgNodeIds(nodeIds);
                                    intent.getOrganization().setKgNodeId(nodeIds.get(0));
                                    intent.getOrganization().setNeedsClarification(false);
                                }
                            }
                        }
                    }, executorService));
                } else {
                    intent.getOrganization().setNeedsClarification(true);
                }
            }
        }
        
        // Wait for all parallel tasks to complete
        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
        
        // Source: removed from auto-set candidates - only used for hard filtering (via data catalog selection or user intent)
        // Source is handled directly in GraphRetrievalService as a hard filter, not as a soft filter dimension
    }

    /**
     * HITL rules:
     * - Space/Time: return HITL if bbox/range is missing and cannot be auto-normalized.
     * - Other dimensions: return HITL if confidence < 0.5 (require user confirmation).
     * <p>
     * NOTE:
     * - Always return top-K candidates (never say "not found").
     * - Use human-readable format, never "dimension=xxx" in output.
     */
    public PendingHitl nextHitl(GeoIntent intent, boolean useKeywords, Hyperparameters hyperparams, String apiKey, String questionId) {
        // Set context for embedding calls
        linker.setCurrentContext(apiKey, questionId);
        
        if (intent == null) {
            return null;
        }

        Hyperparameters h = (hyperparams != null) ? hyperparams : Hyperparameters.defaults();
        double confidenceThreshold = (h.confidenceThreshold != null) ? h.confidenceThreshold : 0.5;
        double similarityScoreThreshold = (h.similarityScoreThreshold != null) ? h.similarityScoreThreshold : 0.5;
        boolean useEmbeddingSearch = (h.useEmbeddingSearch != null) ? h.useEmbeddingSearch : true;

        // Defensive normalization: if a dim already has a kg_node_id (or very high confidence),
        // treat it as resolved even if needs_clarification was not flipped properly.
        normalizeResolvedFlags(intent, confidenceThreshold);

        // ----------------------------
        // Check dimensions for HITL based on confidence < 0.5 or missing required data
        // ----------------------------

        // Topic: require HITL if confidence < 0.5
        if (intent.getTopic() != null && !isBlank(bestText(intent.getTopic().getValue(), intent.getTopic().getRawText()))) {
            if (intent.getTopic().getKgNodeIds() == null || intent.getTopic().getKgNodeIds().isEmpty()) {
                if (intent.getTopic().getConfidence() < confidenceThreshold) {
                    String text = bestText(intent.getTopic().getValue(), intent.getTopic().getRawText());
                    // Split multiple values and get candidates for each separately
                    List<String> topicValues = splitDimensionValue(text);
                    if (topicValues.isEmpty()) topicValues = List.of(text); // Fallback to original if split fails
                    
                    List<CandidateEntity> allCandidates = new ArrayList<>();
                    // Request more candidates to ensure we have enough after filtering
                    // For text search, we need more candidates because lexical scores may be lower
                    // For embedding search, we also need more to ensure we get 5n candidates after filtering
                    int requestedCandidates = useEmbeddingSearch ? 20 : 20;
                    for (String topicValue : topicValues) {
                        if (isBlank(topicValue)) continue;
                        List<CandidateEntity> cand = linker.candidatesForTopic(topicValue, requestedCandidates, useKeywords, useEmbeddingSearch);
                        final double scoreThresholdForNextHitl = similarityScoreThreshold;
                        List<CandidateEntity> filtered = cand.stream()
                            .filter(c -> c != null && c.score() > scoreThresholdForNextHitl)
                            .limit(requestedCandidates)
                            .toList();
                        allCandidates.addAll(filtered);
                    }
                    
                    // Deduplicate by nodeId and limit to top (5 * n) where n is the number of values
                    int limitCount = 5 * topicValues.size();
                    Map<String, CandidateEntity> uniqueCandidates = new LinkedHashMap<>();
                    for (CandidateEntity c : allCandidates) {
                        if (c != null && c.nodeId() != null && !uniqueCandidates.containsKey(c.nodeId())) {
                            uniqueCandidates.put(c.nodeId(), c);
                        }
                    }
                    List<CandidateEntity> finalCandidates = uniqueCandidates.values().stream()
                        .sorted((a, b) -> Double.compare(b.score(), a.score()))
                        .limit(limitCount)
                        .toList();
                    
                    String q = buildMultiSelectQuestion("Topic", text, finalCandidates);
                    return new PendingHitl(HitlSlot.TOPIC, q.trim(), finalCandidates);
                }
            }
        }

        // Format: require HITL if confidence < 0.5
        if (intent.getFormat() != null && !isBlank(bestText(intent.getFormat().getValue(), intent.getFormat().getRawText()))) {
            if (intent.getFormat().getKgNodeIds() == null || intent.getFormat().getKgNodeIds().isEmpty()) {
                if (intent.getFormat().getConfidence() < confidenceThreshold) {
                    String text = bestText(intent.getFormat().getValue(), intent.getFormat().getRawText());
                    // Split multiple values and get candidates for each separately
                    List<String> formatValues = splitDimensionValue(text);
                    if (formatValues.isEmpty()) formatValues = List.of(text);
                    
                    List<CandidateEntity> allCandidates = new ArrayList<>();
                    for (String formatValue : formatValues) {
                        if (isBlank(formatValue)) continue;
                        List<CandidateEntity> cand = linker.candidatesForFormat(formatValue, 5, useEmbeddingSearch);
                        List<CandidateEntity> filtered = cand.stream()
                            .filter(c -> c != null && c.score() > similarityScoreThreshold)
                            .limit(5)
                            .toList();
                        allCandidates.addAll(filtered);
                    }
                    
                    // Deduplicate by nodeId and limit to top (5 * n) where n is the number of values
                    int limitCount = 5 * formatValues.size();
                    Map<String, CandidateEntity> uniqueCandidates = new LinkedHashMap<>();
                    for (CandidateEntity c : allCandidates) {
                        if (c != null && c.nodeId() != null && !uniqueCandidates.containsKey(c.nodeId())) {
                            uniqueCandidates.put(c.nodeId(), c);
                        }
                    }
                    List<CandidateEntity> finalCandidates = uniqueCandidates.values().stream()
                        .sorted((a, b) -> Double.compare(b.score(), a.score()))
                        .limit(limitCount)
                        .toList();
                    
                    String q = buildMultiSelectQuestion("Format", text, finalCandidates);
                    return new PendingHitl(HitlSlot.FORMAT, q.trim(), finalCandidates);
                }
            }
        }

        // License: require HITL if confidence < 0.5
        if (intent.getLicense() != null && !isBlank(bestText(intent.getLicense().getValue(), intent.getLicense().getRawText()))) {
            if (intent.getLicense().getKgNodeIds() == null || intent.getLicense().getKgNodeIds().isEmpty()) {
                if (intent.getLicense().getConfidence() < confidenceThreshold) {
                    String text = bestText(intent.getLicense().getValue(), intent.getLicense().getRawText());
                    // Split multiple values and get candidates for each separately
                    List<String> licenseValues = splitDimensionValue(text);
                    if (licenseValues.isEmpty()) licenseValues = List.of(text);
                    
                    List<CandidateEntity> allCandidates = new ArrayList<>();
                    for (String licenseValue : licenseValues) {
                        if (isBlank(licenseValue)) continue;
                        List<CandidateEntity> cand = linker.candidatesForLicense(licenseValue, 5, useEmbeddingSearch);
                        List<CandidateEntity> filtered = cand.stream()
                            .filter(c -> c != null && c.score() > similarityScoreThreshold)
                            .limit(5)
                            .toList();
                        allCandidates.addAll(filtered);
                    }
                    
                    // Deduplicate by nodeId and limit to top (5 * n) where n is the number of values
                    int limitCount = 5 * licenseValues.size();
                    Map<String, CandidateEntity> uniqueCandidates = new LinkedHashMap<>();
                    for (CandidateEntity c : allCandidates) {
                        if (c != null && c.nodeId() != null && !uniqueCandidates.containsKey(c.nodeId())) {
                            uniqueCandidates.put(c.nodeId(), c);
                        }
                    }
                    List<CandidateEntity> finalCandidates = uniqueCandidates.values().stream()
                        .sorted((a, b) -> Double.compare(b.score(), a.score()))
                        .limit(limitCount)
                        .toList();
                    
                    String q = buildMultiSelectQuestion("License", text, finalCandidates);
                    return new PendingHitl(HitlSlot.LICENSE, q.trim(), finalCandidates);
                }
            }
        }

        // Organization: require HITL if confidence < 0.5
        if (intent.getOrganization() != null && !isBlank(bestText(intent.getOrganization().getValue(), intent.getOrganization().getRawText()))) {
            if (intent.getOrganization().getKgNodeIds() == null || intent.getOrganization().getKgNodeIds().isEmpty()) {
                if (intent.getOrganization().getConfidence() < confidenceThreshold) {
                    String text = bestText(intent.getOrganization().getValue(), intent.getOrganization().getRawText());
                    // Split multiple values and get candidates for each separately
                    List<String> orgValues = splitDimensionValue(text);
                    if (orgValues.isEmpty()) orgValues = List.of(text);
                    
                    List<CandidateEntity> allCandidates = new ArrayList<>();
                    for (String orgValue : orgValues) {
                        if (isBlank(orgValue)) continue;
                        List<CandidateEntity> cand = linker.candidatesForOrganization(orgValue, 5, useEmbeddingSearch);
                        List<CandidateEntity> filtered = cand.stream()
                            .filter(c -> c != null && c.score() > similarityScoreThreshold)
                            .limit(5)
                            .toList();
                        allCandidates.addAll(filtered);
                    }
                    
                    // Deduplicate by nodeId and limit to top (5 * n) where n is the number of values
                    int limitCount = 5 * orgValues.size();
                    Map<String, CandidateEntity> uniqueCandidates = new LinkedHashMap<>();
                    for (CandidateEntity c : allCandidates) {
                        if (c != null && c.nodeId() != null && !uniqueCandidates.containsKey(c.nodeId())) {
                            uniqueCandidates.put(c.nodeId(), c);
                        }
                    }
                    List<CandidateEntity> finalCandidates = uniqueCandidates.values().stream()
                        .sorted((a, b) -> Double.compare(b.score(), a.score()))
                        .limit(limitCount)
                        .toList();
                    
                    String q = buildMultiSelectQuestion("Organization", text, finalCandidates);
                    return new PendingHitl(HitlSlot.ORGANIZATION, q.trim(), finalCandidates);
                }
            }
        }

        // Source: removed from HITL - only used for hard filtering (via data catalog selection or user intent)
        // Source is handled directly in GraphRetrievalService as a hard filter, not as a soft filter dimension

        // Space: resolved if bbox exists; otherwise ask for bbox or place name
        if (intent.getSpace() != null && !isBlank(bestText(intent.getSpace().getValue(), intent.getSpace().getRawText()))) {
            if (intent.getSpace().getBbox() != null && intent.getSpace().getBbox().length == 4) {
                // Already has bbox, resolved (unless confidence < 0.5)
                if (intent.getSpace().getConfidence() >= confidenceThreshold) {
                    intent.getSpace().setNeedsClarification(false);
                } else {
                    String text = bestText(intent.getSpace().getValue(), intent.getSpace().getRawText());
                    String q = "I detected a location (confidence < 50%): " + (text == null ? "" : text) + "\n\n"
                            + "Please confirm the location or provide a bounding box.\n"
                            + "You can reply with:\n"
                            + "- The location name (e.g., \"Pennsylvania, USA\")\n"
                            + "- A bounding box (e.g., \"-80,39,-74,42\")";
                    return new PendingHitl(HitlSlot.SPACE, q.trim(), List.of());
                }
            } else {
                // Need to normalize place name to bbox (handled by SpaceTimeNormalizationService)
                // For now, mark as needing clarification if no bbox
                String text = bestText(intent.getSpace().getValue(), intent.getSpace().getRawText());
                String q = "I detected a location: " + (text == null ? "" : text) + "\n\n"
                        + "Please confirm the location or provide a bounding box.\n"
                        + "You can reply with:\n"
                        + "- The location name (e.g., \"Pennsylvania, USA\")\n"
                        + "- A bounding box (e.g., \"-80,39,-74,42\")";
                return new PendingHitl(HitlSlot.SPACE, q.trim(), List.of());
            }
        }

        // Time: resolved if start/end exist; otherwise normalize time expression (hard filter, no embedding)
        if (intent.getTime() != null && !isBlank(bestText(null, intent.getTime().getRawText()))) {
            boolean hasRange = !isBlank(intent.getTime().getStart()) && !isBlank(intent.getTime().getEnd());
            if (hasRange) {
                // Already has normalized time range, resolved (unless confidence < 0.5)
                if (intent.getTime().getConfidence() >= confidenceThreshold) {
                    intent.getTime().setNeedsClarification(false);
                } else {
                    String raw = bestText(null, intent.getTime().getRawText());
                    String q = "I detected a time expression (confidence < 50%): " + (raw == null ? "" : raw) + "\n\n"
                            + "Please confirm the time range.\n"
                            + "You can reply with:\n"
                            + "- A date range (e.g., \"2018-01-01 to 2020-12-31\")\n"
                            + "- A single year (e.g., \"2020\")";
                    return new PendingHitl(HitlSlot.TIME, q.trim(), List.of());
                }
            } else {
                // Need to normalize time expression (handled by SpaceTimeNormalizationService)
                String raw = bestText(null, intent.getTime().getRawText());
                String q = "I detected a time expression: " + (raw == null ? "" : raw) + "\n\n"
                        + "Please confirm the time range.\n"
                        + "You can reply with:\n"
                        + "- A date range (e.g., \"2018-01-01 to 2020-12-31\")\n"
                        + "- A single year (e.g., \"2020\")";
                return new PendingHitl(HitlSlot.TIME, q.trim(), List.of());
            }
        }

        return null;
    }

    /**
     * Check if any dimension has confidence below threshold and requires HITL confirmation.
     * This method should be called after intent parsing to ensure low-confidence dimensions
     * are confirmed by the user before proceeding with retrieval.
     * When confidence is below threshold, user should re-enter more precise input (no candidates provided).
     * 
     * @return PendingHitl if a dimension needs confirmation, null otherwise
     */
    public PendingHitl requireHITLForLowConfidence(GeoIntent intent, boolean useKeywords, Hyperparameters hyperparams, String apiKey, String questionId) {
        if (intent == null) return null;
        
        Hyperparameters h = (hyperparams != null) ? hyperparams : Hyperparameters.defaults();
        double confidenceThreshold = (h.confidenceThreshold != null) ? h.confidenceThreshold : 0.5;
        
        // Check Topic dimension
        if (intent.getTopic() != null && !isBlank(bestText(intent.getTopic().getValue(), intent.getTopic().getRawText()))) {
            if (intent.getTopic().getKgNodeIds() == null || intent.getTopic().getKgNodeIds().isEmpty()) {
                if (intent.getTopic().getConfidence() < confidenceThreshold && intent.getTopic().getConfidence() > 0.0) {
                    String text = bestText(intent.getTopic().getValue(), intent.getTopic().getRawText());
                    String q = "I detected Topic (confidence: " + String.format("%.0f%%", intent.getTopic().getConfidence() * 100) + "): " + text + "\n\n"
                            + "Please provide a more precise topic description.\n"
                            + "You can:\n"
                            + "- Provide more specific keywords or context\n"
                            + "- Use more technical terminology\n"
                            + "- Include related concepts or synonyms";
                    return new PendingHitl(HitlSlot.TOPIC, q.trim(), List.of());
                }
            }
        }
        
        // Check Format dimension
        if (intent.getFormat() != null && !isBlank(bestText(intent.getFormat().getValue(), intent.getFormat().getRawText()))) {
            if (intent.getFormat().getKgNodeIds() == null || intent.getFormat().getKgNodeIds().isEmpty()) {
                if (intent.getFormat().getConfidence() < confidenceThreshold && intent.getFormat().getConfidence() > 0.0) {
                    String text = bestText(intent.getFormat().getValue(), intent.getFormat().getRawText());
                    String q = "I detected Format (confidence: " + String.format("%.0f%%", intent.getFormat().getConfidence() * 100) + "): " + text + "\n\n"
                            + "Please provide a more precise format specification.\n"
                            + "Examples: GeoTIFF, GeoJSON, Shapefile, NetCDF, CSV, etc.";
                    return new PendingHitl(HitlSlot.FORMAT, q.trim(), List.of());
                }
            }
        }
        
        // Check License dimension
        if (intent.getLicense() != null && !isBlank(bestText(intent.getLicense().getValue(), intent.getLicense().getRawText()))) {
            if (intent.getLicense().getKgNodeIds() == null || intent.getLicense().getKgNodeIds().isEmpty()) {
                if (intent.getLicense().getConfidence() < confidenceThreshold && intent.getLicense().getConfidence() > 0.0) {
                    String text = bestText(intent.getLicense().getValue(), intent.getLicense().getRawText());
                    String q = "I detected License (confidence: " + String.format("%.0f%%", intent.getLicense().getConfidence() * 100) + "): " + text + "\n\n"
                            + "Please provide a more precise license specification.\n"
                            + "Examples: CC-BY-4.0, CC0-1.0, Public Domain, Open Data Commons, etc.";
                    return new PendingHitl(HitlSlot.LICENSE, q.trim(), List.of());
                }
            }
        }
        
        // Check Organization dimension
        if (intent.getOrganization() != null && !isBlank(bestText(intent.getOrganization().getValue(), intent.getOrganization().getRawText()))) {
            if (intent.getOrganization().getKgNodeIds() == null || intent.getOrganization().getKgNodeIds().isEmpty()) {
                if (intent.getOrganization().getConfidence() < confidenceThreshold && intent.getOrganization().getConfidence() > 0.0) {
                    String text = bestText(intent.getOrganization().getValue(), intent.getOrganization().getRawText());
                    String q = "I detected Organization (confidence: " + String.format("%.0f%%", intent.getOrganization().getConfidence() * 100) + "): " + text + "\n\n"
                            + "Please provide a more precise organization name.\n"
                            + "Examples: USGS, NASA, NOAA, OpenStreetMap, etc.";
                    return new PendingHitl(HitlSlot.ORGANIZATION, q.trim(), List.of());
                }
            }
        }
        
        // Check Space dimension
        if (intent.getSpace() != null && !isBlank(bestText(intent.getSpace().getValue(), intent.getSpace().getRawText()))) {
            if (intent.getSpace().getBbox() != null && intent.getSpace().getBbox().length == 4) {
                if (intent.getSpace().getConfidence() < confidenceThreshold && intent.getSpace().getConfidence() > 0.0) {
                    String text = bestText(intent.getSpace().getValue(), intent.getSpace().getRawText());
                    String q = "I detected a location (confidence: " + String.format("%.0f%%", intent.getSpace().getConfidence() * 100) + "): " + (text == null ? "" : text) + "\n\n"
                            + "Please provide a more precise location specification.\n"
                            + "You can reply with:\n"
                            + "- A more specific location name (e.g., \"Pennsylvania, USA\" or \"Philadelphia, PA\")\n"
                            + "- A bounding box (e.g., \"-80,39,-74,42\")";
                    return new PendingHitl(HitlSlot.SPACE, q.trim(), List.of());
                }
            }
        }
        
        // Check Time dimension
        if (intent.getTime() != null && !isBlank(bestText(null, intent.getTime().getRawText()))) {
            boolean hasRange = !isBlank(intent.getTime().getStart()) && !isBlank(intent.getTime().getEnd());
            // Check confidence regardless of whether range exists
            // If confidence is low, trigger HITL even if range exists (user may have provided incorrect range)
            // If range is missing, also check confidence to trigger HITL
            if (intent.getTime().getConfidence() < confidenceThreshold && intent.getTime().getConfidence() > 0.0) {
                String raw = bestText(null, intent.getTime().getRawText());
                String q;
                if (hasRange) {
                    q = "I detected a time expression (confidence: " + String.format("%.0f%%", intent.getTime().getConfidence() * 100) + "): " + (raw == null ? "" : raw) + "\n\n"
                            + "Please provide a more precise time specification.\n"
                            + "You can reply with:\n"
                            + "- A specific date range (e.g., \"2018-01-01 to 2020-12-31\")\n"
                            + "- A single year (e.g., \"2020\")\n"
                            + "- A specific date (e.g., \"2020-06-15\")";
                } else {
                    q = "I detected a time expression (confidence: " + String.format("%.0f%%", intent.getTime().getConfidence() * 100) + "): " + (raw == null ? "" : raw) + "\n\n"
                            + "Please provide a more precise time specification.\n"
                            + "You can reply with:\n"
                            + "- A specific date range (e.g., \"2018-01-01 to 2020-12-31\")\n"
                            + "- A single year (e.g., \"2020\")\n"
                            + "- A specific date (e.g., \"2020-06-15\")";
                }
                return new PendingHitl(HitlSlot.TIME, q.trim(), List.of());
            }
        }
        
        return null;
    }

    private void normalizeResolvedFlags(GeoIntent intent, double confidenceThreshold) {
        if (intent == null) return;

        normalizeEntity(intent.getTopic(), confidenceThreshold);
        normalizeEntity(intent.getFormat(), confidenceThreshold);
        normalizeEntity(intent.getLicense(), confidenceThreshold);
        normalizeEntity(intent.getOrganization(), confidenceThreshold);
        // Source: removed from normalization - only used for hard filtering (via data catalog selection or user intent)
    }

    private void normalizeEntity(GeoIntent.EntityDim dim, double confidenceThreshold) {
        if (dim == null) return;
        // If user has selected candidates, no need for clarification
        if (dim.getKgNodeIds() != null && !dim.getKgNodeIds().isEmpty()) {
            dim.setNeedsClarification(false);
            return;
        }
        if (!isBlank(dim.getKgNodeId()) || dim.getConfidence() >= AUTO_SKIP_CONFIRM_CONFIDENCE) {
            dim.setNeedsClarification(false);
        }
        // If confidence < threshold, require HITL confirmation
        if (dim.getConfidence() < confidenceThreshold && dim.getConfidence() > 0.0) {
            dim.setNeedsClarification(true);
        }
    }

    private String buildMultiSelectQuestion(String dim, String raw, List<CandidateEntity> cand) {
        StringBuilder sb = new StringBuilder();
        sb.append("I detected ").append(dim.toLowerCase(Locale.ROOT)).append(": ").append(raw == null ? "" : raw).append("\n\n");
        
        if (cand == null || cand.isEmpty()) {
            sb.append("Please provide the exact ").append(dim.toLowerCase(Locale.ROOT)).append(" name you're looking for.");
        } else {
            sb.append("Please select one or more candidates (or click Continue to use all top candidates):\n\n");
        sb.append("Candidates:\n");
        for (int i = 0; i < cand.size(); i++) {
            CandidateEntity c = cand.get(i);
                sb.append(i + 1).append(") ").append(c.name());
                if (c.label() != null && !c.label().equalsIgnoreCase(dim)) {
                    sb.append(" (").append(c.label()).append(")");
                }
                sb.append("\n");
            }
            sb.append("\nYou can:\n");
            sb.append("- Select multiple candidates (e.g., \"1,3,5\" or \"1 3 5\")\n");
            sb.append("- Select a single candidate (e.g., \"1\")\n");
            sb.append("- Click Continue to use all top candidates for search");
        }
        return sb.toString().trim();
    }

    private String bestText(String v, String raw) {
        if (!isBlank(v)) return v;
        return raw == null ? "" : raw;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
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
}

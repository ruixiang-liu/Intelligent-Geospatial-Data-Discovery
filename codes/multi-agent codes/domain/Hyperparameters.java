package edu.psu.giscience.igdd.domain;

import java.util.Map;

/**
 * Hyperparameters for dataset discovery.
 * All values default to current hardcoded values if not provided.
 */
public class Hyperparameters {
    
    // Dimension weights (used by both GraphRetrievalService and EvidencePackBuilder)
    // EvidencePackBuilder uses the same weights to reverse-calculate raw scores from weighted contributions
    public Double weightTopic = 0.3;
    public Double weightFormat = 0.1;
    public Double weightLicense = 0.1;
    public Double weightOrganization = 0.1;
    public Double weightSpace = 0.2;
    public Double weightTime = 0.2;
    
    // Search mode: true for embedding search, false for text search
    public Boolean useEmbeddingSearch = true;
    
    // Auto execute: if true, automatically select candidates above similarity threshold
    // If false, only use user-selected candidates
    public Boolean autoExecute = true;
    
    // Thresholds (default values)
    // Similarity score threshold for both embedding search and text search (default 0.5)
    public Double similarityScoreThreshold = 0.5;
    public Double confidenceThreshold = 0.5;      // For confidence-based candidate filtering
    
    // Data catalog selection: which catalogs to include in dataset retrieval
    // Map<String, Boolean> where keys are portal identifiers (e.g., "datagov", "pasda", "stacGoogle", etc.)
    // If null or empty, all portals are included (no filtering)
    public Map<String, Boolean> portals = null;
    
    /**
     * Get default hyperparameters with all default values.
     */
    public static Hyperparameters defaults() {
        return new Hyperparameters();
    }
    
    /**
     * Validate hyperparameters:
     * - All weights must be greater than 0
     * - Similarity score threshold must be between 0.4 and 1 (inclusive)
     * - Confidence threshold must be between 0.3 and 0.9 (inclusive)
     */
    public void validate() {
        // Validate weights: must be greater than 0
        if (weightTopic == null || weightTopic <= 0 || !Double.isFinite(weightTopic)) {
            throw new IllegalArgumentException("weightTopic must be greater than 0");
        }
        if (weightFormat == null || weightFormat <= 0 || !Double.isFinite(weightFormat)) {
            throw new IllegalArgumentException("weightFormat must be greater than 0");
        }
        if (weightLicense == null || weightLicense <= 0 || !Double.isFinite(weightLicense)) {
            throw new IllegalArgumentException("weightLicense must be greater than 0");
        }
        if (weightOrganization == null || weightOrganization <= 0 || !Double.isFinite(weightOrganization)) {
            throw new IllegalArgumentException("weightOrganization must be greater than 0");
        }
        if (weightSpace == null || weightSpace <= 0 || !Double.isFinite(weightSpace)) {
            throw new IllegalArgumentException("weightSpace must be greater than 0");
        }
        if (weightTime == null || weightTime <= 0 || !Double.isFinite(weightTime)) {
            throw new IllegalArgumentException("weightTime must be greater than 0");
        }
        
        // Validate similarityScoreThreshold: must be between 0.4 and 1 (inclusive)
        if (similarityScoreThreshold == null || !Double.isFinite(similarityScoreThreshold) || 
            similarityScoreThreshold < 0.4 || similarityScoreThreshold > 1) {
            throw new IllegalArgumentException("similarityScoreThreshold must be between 0.4 and 1 (inclusive)");
        }
        
        // Validate confidenceThreshold: must be between 0.3 and 0.9 (inclusive)
        if (confidenceThreshold == null || !Double.isFinite(confidenceThreshold) || 
            confidenceThreshold < 0.3 || confidenceThreshold > 0.9) {
            throw new IllegalArgumentException("confidenceThreshold must be between 0.3 and 0.9 (inclusive)");
        }
    }
    
    /**
     * Normalize weights so they sum to 1.0.
     * This method normalizes all weight values proportionally.
     * Frontend can send weights that don't sum to 1, and backend will normalize them.
     */
    public void normalizeWeights() {
        double sum = (weightTopic != null ? weightTopic : 0.0) +
                     (weightFormat != null ? weightFormat : 0.0) +
                     (weightLicense != null ? weightLicense : 0.0) +
                     (weightOrganization != null ? weightOrganization : 0.0) +
                     (weightSpace != null ? weightSpace : 0.0) +
                     (weightTime != null ? weightTime : 0.0);
        
        if (sum <= 0 || !Double.isFinite(sum)) {
            // If sum is invalid, use defaults
            weightTopic = 0.3;
            weightFormat = 0.1;
            weightLicense = 0.1;
            weightOrganization = 0.1;
            weightSpace = 0.2;
            weightTime = 0.2;
            return;
        }
        
        // Normalize each weight proportionally
        if (weightTopic != null) weightTopic = weightTopic / sum;
        if (weightFormat != null) weightFormat = weightFormat / sum;
        if (weightLicense != null) weightLicense = weightLicense / sum;
        if (weightOrganization != null) weightOrganization = weightOrganization / sum;
        if (weightSpace != null) weightSpace = weightSpace / sum;
        if (weightTime != null) weightTime = weightTime / sum;
    }
}

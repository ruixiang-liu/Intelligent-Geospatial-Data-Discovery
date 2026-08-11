package edu.psu.giscience.igdd.service;

import edu.psu.giscience.igdd.domain.graphrag.CandidateEntity;
import edu.psu.giscience.igdd.graph.Neo4jGraphRepository;
import edu.psu.giscience.igdd.llm.LlmClientService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

@Service
public class GraphEntityLinker {

    // Label names for Neo4j nodes (used for embedding search)
    public static final String LABEL_TOPIC = "Topic";
    public static final String LABEL_KEYWORD = "Keyword";
    public static final String LABEL_DATASET = "Dataset";
    public static final String LABEL_SPACE = "Space";
    public static final String LABEL_TIME = "Time";
    public static final String LABEL_FORMAT = "Format";
    public static final String LABEL_LICENSE = "License";
    public static final String LABEL_ORG = "Organization";
    public static final String LABEL_SOURCE = "Source";

    private final Neo4jGraphRepository repo;
    private final LlmClientService llm;
    private GraphRetrievalService retrievalService; // Optional, for cache access
    private String currentApiKey; // Current API key for embedding calls
    private String currentQuestionId; // Current question ID for embedding calls

    public GraphEntityLinker(Neo4jGraphRepository repo, LlmClientService llm) {
        this.repo = repo;
        this.llm = llm;
    }
    
    // Set retrieval service for cache access (optional, set via setter to avoid circular dependency)
    public void setRetrievalService(GraphRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }
    
    // Set current API key and question ID for embedding calls
    public void setCurrentContext(String apiKey, String questionId) {
        this.currentApiKey = apiKey;
        this.currentQuestionId = questionId;
    }

    public List<CandidateEntity> candidatesForTopic(String text, int k, boolean useKeywords, boolean useEmbeddingSearch) {
        text = norm(text);
        if (text.isEmpty()) return List.of();

        List<CandidateEntity> out = new ArrayList<>();

        if (useEmbeddingSearch) {
            // Use embedding search only
            // Check cache first if retrievalService is available
            List<CandidateEntity> v = null;
            List<CandidateEntity> kv = null;
            
            if (retrievalService != null) {
                v = retrievalService.getCachedCandidates("Topic", text, useKeywords);
                if (useKeywords) {
                    kv = retrievalService.getCachedCandidates("Keyword", text, useKeywords);
                }
            }
            
            // If not cached, search
            if (v == null || (useKeywords && kv == null)) {
                // Check embedding cache first
                List<Double> emb = null;
                if (retrievalService != null) {
                    emb = retrievalService.getCachedEmbedding(text);
                }
                if (emb == null || emb.isEmpty()) {
                    emb = llm.embed(text, currentApiKey, currentQuestionId);
                    if (retrievalService != null && !emb.isEmpty()) {
                        retrievalService.cacheEmbedding(text, emb);
                    }
                }
                
                if (!emb.isEmpty()) {
                    if (v == null) {
                        v = repo.vectorSearchNodes(LABEL_TOPIC, emb, Math.max(k, 20));
                        if (retrievalService != null) {
                            retrievalService.cacheCandidates("Topic", text, useKeywords, v);
                        }
                    }
                    if (!v.isEmpty()) out.addAll(v);

                    // Only if toggle is ON, we also surface Keyword candidates (for HITL choices)
                    if (useKeywords && kv == null) {
                        kv = repo.vectorSearchNodes(LABEL_KEYWORD, emb, Math.max(k, 20));
                        if (retrievalService != null) {
                            retrievalService.cacheCandidates("Keyword", text, useKeywords, kv);
                        }
                        if (!kv.isEmpty()) out.addAll(kv);
                    } else if (useKeywords && kv != null) {
                        out.addAll(kv);
                    }
                }
            } else {
                // Use cached results
                if (v != null) out.addAll(v);
                if (useKeywords && kv != null) out.addAll(kv);
            }
            
            // If embedding search returns empty, return empty (no fallback)
            if (out.isEmpty()) return List.of();
        } else {
            // Use text search only
            System.out.println("[Text Search] Topic: " + text);
            out.addAll(repo.nameContainsSearch("Topic", text, Math.max(k, 20)));
            if (useKeywords) {
                out.addAll(repo.nameContainsSearch("Keyword", text, Math.max(k, 20)));
            }
        }

        out = maybeAddLexicalScores(out, text);
        // Sort all candidates (Topic + Keyword) by score in descending order
        // This ensures Topic and Keyword candidates are ranked together by their similarity scores
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        return dedupeAndLimit(out, k);
    }

    public List<CandidateEntity> candidatesForSpace(String text, int k, boolean useEmbeddingSearch) {
        return generic(LABEL_SPACE, text, k, useEmbeddingSearch);
    }

    public List<CandidateEntity> candidatesForTime(String text, int k, boolean useEmbeddingSearch) {
        return generic(LABEL_TIME, text, k, useEmbeddingSearch);
    }

    public List<CandidateEntity> candidatesForFormat(String text, int k, boolean useEmbeddingSearch) {
        return generic(LABEL_FORMAT, text, k, useEmbeddingSearch);
    }

    public List<CandidateEntity> candidatesForLicense(String text, int k, boolean useEmbeddingSearch) {
        return generic(LABEL_LICENSE, text, k, useEmbeddingSearch);
    }

    public List<CandidateEntity> candidatesForOrganization(String text, int k, boolean useEmbeddingSearch) {
        return generic(LABEL_ORG, text, k, useEmbeddingSearch);
    }

    public List<CandidateEntity> candidatesForSource(String text, int k, boolean useEmbeddingSearch) {
        return generic(LABEL_SOURCE, text, k, useEmbeddingSearch);
    }

    private List<CandidateEntity> generic(String label, String text, int k, boolean useEmbeddingSearch) {
        text = norm(text);
        if (text.isEmpty()) return List.of();

        if (useEmbeddingSearch) {
            // Use embedding search only
            // Check cache first if retrievalService is available
            List<CandidateEntity> cached = null;
            if (retrievalService != null) {
                cached = retrievalService.getCachedCandidates(label, text, false);
            }
            
            if (cached != null) {
                // Use cached results, but limit to requested k
                System.out.println("[Embedding Search] " + label + ": " + text + " (cached), candidates found: " + cached.size());
                return dedupeAndLimit(cached, k);
            }
            
            // If not cached, search
            List<Double> emb = null;
            if (retrievalService != null) {
                emb = retrievalService.getCachedEmbedding(text);
            }
            if (emb == null || emb.isEmpty()) {
                emb = llm.embed(text, currentApiKey, currentQuestionId);
                if (retrievalService != null && !emb.isEmpty()) {
                    retrievalService.cacheEmbedding(text, emb);
                }
            }
            
            System.out.println("[Embedding Search] " + label + ": " + text + ", Embedding size: " + emb.size());
            if (!emb.isEmpty()) {
                List<CandidateEntity> v = repo.vectorSearchNodes(label, emb, k);
                System.out.println("[Embedding Search] " + label + " candidates found: " + v.size());
                // Cache the results for future use
                if (retrievalService != null && !v.isEmpty()) {
                    retrievalService.cacheCandidates(label, text, false, v);
                }
                if (!v.isEmpty()) return v;
            } else {
                System.out.println("[Embedding Search] Failed to generate embedding for " + label + ": " + text);
            }
            // If embedding search returns empty, return empty (no fallback)
            System.out.println("[Embedding Search] No candidates found for " + label + ": " + text);
            return List.of();
        } else {
            // Use text search only
            System.out.println("[Text Search] Label: " + label + ", Query: " + text);
            return maybeAddLexicalScores(repo.nameContainsSearch(label, text, k), text);
        }
    }

    /**
     * If the underlying store cannot provide a meaningful score (e.g., text fallback),
     * we attach a lightweight lexical similarity score so the UI doesn't show all 0.000.
     * We ONLY do this when ALL scores are <= ~0.
     */
    private List<CandidateEntity> maybeAddLexicalScores(List<CandidateEntity> in, String query) {
        if (in == null || in.isEmpty()) return List.of();

        boolean anyNonZero = false;
        for (CandidateEntity c : in) {
            if (c != null && c.score() > 1e-6) {
                anyNonZero = true;
                break;
            }
        }
        if (anyNonZero) return in;

        final String q = normForScore(query);
        List<CandidateEntity> rescored = new ArrayList<>(in.size());
        for (CandidateEntity c : in) {
            if (c == null) continue;
            double s = lexicalScore(q, normForScore(c.name()));
            rescored.add(new CandidateEntity(c.nodeId(), c.label(), c.name(), s, c.props()));
        }
        rescored.sort((a, b) -> Double.compare(b.score(), a.score()));
        return rescored;
    }

    private String normForScore(String s) {
        if (s == null) return "";
        String t = s.toLowerCase().trim();
        t = t.replaceAll("[^a-z0-9\\s]", " ");
        t = t.replaceAll("\\s+", " ").trim();
        return t;
    }

    private double lexicalScore(String q, String c) {
        if (q.isEmpty() || c.isEmpty()) return 0.0;

        // Token Jaccard
        String[] qt = q.split(" ");
        String[] ct = c.split(" ");

        java.util.HashSet<String> qs = new java.util.HashSet<>();
        java.util.HashSet<String> cs = new java.util.HashSet<>();
        for (String t : qt) if (!t.isBlank()) qs.add(t);
        for (String t : ct) if (!t.isBlank()) cs.add(t);

        int inter = 0;
        for (String t : qs) if (cs.contains(t)) inter++;
        int union = qs.size() + cs.size() - inter;

        double jac = union <= 0 ? 0.0 : (inter * 1.0 / union);

        // Bonus if substring matches
        double bonus = 0.0;
        if (c.contains(q) || q.contains(c)) bonus = 0.15;
        if (c.startsWith(q) || q.startsWith(c)) bonus = Math.max(bonus, 0.20);

        double s = jac + bonus;
        if (s > 1.0) s = 1.0;
        return s;
    }

    private List<CandidateEntity> dedupeAndLimit(List<CandidateEntity> in, int limit) {
        if (in == null || in.isEmpty()) return List.of();
        // Note: input list should already be sorted by score (descending) before calling this method
        // We deduplicate by nodeId while preserving the order (first occurrence wins)
        LinkedHashMap<String, CandidateEntity> m = new LinkedHashMap<>();
        
        // Get the score of the limit-th candidate (or the last one if fewer than limit)
        double thresholdScore = 0.0;
        if (in.size() >= limit) {
            // Find the score of the limit-th unique candidate
            LinkedHashMap<String, CandidateEntity> temp = new LinkedHashMap<>();
            for (CandidateEntity c : in) {
                if (c == null || c.nodeId() == null) continue;
                temp.putIfAbsent(c.nodeId(), c);
                if (temp.size() >= limit) {
                    thresholdScore = c.score();
                    break;
                }
            }
        }
        
        // Include all candidates with score >= thresholdScore
        for (CandidateEntity c : in) {
            if (c == null || c.nodeId() == null) continue;
            // Only include if score >= thresholdScore (or if we haven't reached limit yet)
            if (m.size() < limit || c.score() >= thresholdScore) {
                // putIfAbsent keeps the first occurrence (highest score if input is sorted)
                m.putIfAbsent(c.nodeId(), c);
            } else {
                // Once we encounter a score lower than threshold, stop
                break;
            }
        }
        return new ArrayList<>(m.values());
    }

    private String norm(String s) {
        return s == null ? "" : s.trim();
    }
}

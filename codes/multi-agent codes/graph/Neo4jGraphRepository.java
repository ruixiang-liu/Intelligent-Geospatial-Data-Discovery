package edu.psu.giscience.igdd.graph;

import edu.psu.giscience.igdd.domain.graphrag.CandidateEntity;
import edu.psu.giscience.igdd.domain.graphrag.DatasetBundle;
import edu.psu.giscience.igdd.exception.Neo4jConnectionException;
import org.neo4j.driver.*;
import org.neo4j.driver.Record;
import org.neo4j.driver.exceptions.Neo4jException;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class Neo4jGraphRepository {

    private final Driver driver;

    private static final long INDEX_CACHE_TTL_MS = 30_000L;
    private volatile long indexCacheTs = 0L;
    private volatile Set<String> indexNameCache = Set.of();
    
    // Cache for vector index names per label
    private final Map<String, String> vectorIndexCache = new ConcurrentHashMap<>();

    private static final List<String> DATASET_FULLTEXT_INDEX_CANDIDATES = List.of(
            "dataset_fulltext_index",
            "datasets_fulltext_index",
            "dataset_text_index",
            "datasetTextIndex",
            "dataset_fulltext",
            "datasets_fulltext"
    );

    public Neo4jGraphRepository(Driver driver) {
        this.driver = driver;
        // Initialize vector indexes for common labels (lazy creation on first use)
    }

    // -------------------------
    // Index existence (safe)
    // -------------------------
    public boolean indexExists(String indexName) {
        if (indexName == null || indexName.isBlank()) return false;
        return getIndexNamesCached().contains(indexName);
    }

    private Set<String> getIndexNamesCached() {
        long now = System.currentTimeMillis();
        if (now - indexCacheTs < INDEX_CACHE_TTL_MS && indexNameCache != null && !indexNameCache.isEmpty()) {
            return indexNameCache;
        }
        synchronized (this) {
            now = System.currentTimeMillis();
            if (now - indexCacheTs < INDEX_CACHE_TTL_MS && indexNameCache != null && !indexNameCache.isEmpty()) {
                return indexNameCache;
            }
            indexNameCache = loadIndexNamesBestEffort();
            indexCacheTs = now;
            return indexNameCache;
        }
    }

    private Set<String> loadIndexNamesBestEffort() {
        Set<String> names = new HashSet<>();
        try (Session session = driver.session()) {

            // Neo4j procedure style
            try {
                Result rs = session.run("CALL db.indexes() YIELD name RETURN name");
                while (rs.hasNext()) {
                    Record r = rs.next();
                    if (r.containsKey("name") && !r.get("name").isNull()) {
                        names.add(r.get("name").asString());
                    }
                }
                return names;
            } catch (Exception ignored) { }

            // Neo4j 5+ SHOW INDEXES
            try {
                Result rs = session.run("SHOW INDEXES YIELD name RETURN name");
                while (rs.hasNext()) {
                    Record r = rs.next();
                    if (r.containsKey("name") && !r.get("name").isNull()) {
                        names.add(r.get("name").asString());
                    }
                }
            } catch (Exception ignored2) { }

        } catch (Neo4jException | SecurityException e) {
            throw new Neo4jConnectionException("Neo4j connection failed: " + e.getMessage(), e);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Unable to connect") || msg.contains("Connection refused") 
                    || msg.contains("timeout") || msg.contains("Connection reset") 
                    || msg.contains("ServiceUnavailable") || msg.contains("SessionExpired"))) {
                throw new Neo4jConnectionException("Neo4j connection failed: " + msg, e);
            }
            return Set.of();
        }
        return names;
    }

    private String pickFirstExistingIndex(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
        Set<String> existing = getIndexNamesCached();
        for (String c : candidates) {
            if (existing.contains(c)) return c;
        }
        return null;
    }

    // -------------------------
    // Sampling candidates (used by EvidencePackBuilder)
    // -------------------------
    public List<CandidateEntity> sampleCandidates(String label, int limit) {
        if (label == null) label = "";
        label = label.trim();
        if (label.isEmpty()) return List.of();

        String cypher = """
MATCH (n:%s)
RETURN elementId(n) AS nodeId,
       labels(n)[0] AS label,
       coalesce(n.name, n.title, n.value, n.id, n.identifier) AS name,
       0.0 AS score,
       properties(n) AS props
ORDER BY rand()
LIMIT $limit
""".formatted(label);

        Map<String, Object> params = Map.of("limit", Math.max(1, limit));

        try (Session session = driver.session()) {
            Result res = session.run(cypher, params);
            List<CandidateEntity> out = new ArrayList<>();
            while (res.hasNext()) {
                Record r = res.next();
                out.add(new CandidateEntity(
                        r.get("nodeId").asString(),
                        r.get("label").asString(),
                        r.get("name").isNull() ? "" : r.get("name").asString(),
                        r.get("score").asDouble(),
                        r.get("props").asMap()
                ));
            }
            return out;
        } catch (Neo4jException | SecurityException e) {
            throw new Neo4jConnectionException("Neo4j connection failed: " + e.getMessage(), e);
        } catch (Exception e) {
            // For other exceptions, check if it's a connection issue
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Unable to connect") || msg.contains("Connection refused") 
                    || msg.contains("timeout") || msg.contains("Connection reset") 
                    || msg.contains("ServiceUnavailable") || msg.contains("SessionExpired"))) {
                throw new Neo4jConnectionException("Neo4j connection failed: " + msg, e);
            }
            return List.of();
        }
    }

    // -------------------------
    // Vector search using vector index (optimized for performance)
    // Falls back to GDS similarity if index is not available
    // -------------------------
    
    /**
     * Ensure vector index exists for a label. Creates it if it doesn't exist.
     * Returns the index name if successful, null otherwise.
     */
    private String ensureVectorIndex(String label, int dimensions) {
        if (label == null || label.isBlank() || dimensions <= 0) return null;
        
        // Check cache first
        String cacheKey = label + "_" + dimensions;
        String cachedIndex = vectorIndexCache.get(cacheKey);
        if (cachedIndex != null && indexExists(cachedIndex)) {
            return cachedIndex;
        }
        
        // Generate index name: lowercase label + "_embedding_vec"
        String indexName = label.toLowerCase(Locale.ROOT) + "_embedding_vec";
        
        // Check if index already exists
        if (indexExists(indexName)) {
            vectorIndexCache.put(cacheKey, indexName);
            return indexName;
        }
        
        // Try to create the index
        try (Session session = driver.session()) {
            String createIndexCypher = String.format(Locale.ROOT, """
                CREATE VECTOR INDEX %s IF NOT EXISTS
                FOR (n:%s) ON (n.embedding)
                OPTIONS {
                  indexConfig: {
                    `vector.dimensions`: %d,
                    `vector.similarity_function`: 'cosine'
                  }
                }
                """, indexName, label, dimensions);
            
            session.run(createIndexCypher).consume();
            
            // Wait a bit for index to be available (best effort)
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // Verify index was created
            if (indexExists(indexName)) {
                vectorIndexCache.put(cacheKey, indexName);
                return indexName;
            }
        } catch (Exception e) {
            // Index creation failed - will fallback to GDS similarity
            System.out.println("[Vector Index] Failed to create index " + indexName + ": " + e.getMessage());
        }
        
        return null;
    }
    
    public List<CandidateEntity> vectorSearchNodes(String label, List<Double> embedding, int k) {
        if (embedding == null || embedding.isEmpty()) return List.of();
        if (label == null || label.isBlank()) return List.of();

        // Convert to float vector for Neo4j
        List<Float> embF = new ArrayList<>(embedding.size());
        for (Double d : embedding) embF.add(d == null ? 0.0f : d.floatValue());
        
        int dimensions = embedding.size();

        // Try to use vector index first (much faster)
        String indexName = ensureVectorIndex(label, dimensions);
        if (indexName != null) {
            try {
                // Use vector index query (optimized)
                String cypher = """
                    CALL db.index.vector.queryNodes($indexName, $k, $embedding)
                    YIELD node, score
                    RETURN elementId(node) AS nodeId,
                           labels(node)[0] AS label,
                           coalesce(node.name, node.title, node.value, node.id, node.identifier) AS name,
                           score AS score,
                           properties(node) AS props
                    ORDER BY score DESC
                    LIMIT $k
                    """;

                Map<String, Object> params = new HashMap<>();
                params.put("indexName", indexName);
                params.put("embedding", embF);
                params.put("k", k);

                try (Session session = driver.session()) {
                    Result res = session.run(cypher, params);
                    List<CandidateEntity> out = new ArrayList<>();
                    
                    while (res.hasNext()) {
                        Record r = res.next();
                        out.add(new CandidateEntity(
                                r.get("nodeId").asString(),
                                r.get("label").asString(),
                                r.get("name").isNull() ? "" : r.get("name").asString(),
                                r.get("score").asDouble(),
                                r.get("props").asMap()
                        ));
                    }
                    
                    params.clear();
                    embF.clear();
                    return out;
                }
            } catch (Exception e) {
                // Vector index query failed, fallback to GDS similarity
                System.out.println("[Vector Index] Query failed, falling back to GDS similarity: " + e.getMessage());
            }
        }

        // Fallback: Use Neo4j GDS similarity function (slower but always works)
        String cypher = """
MATCH (n:%s)
WHERE n.embedding IS NOT NULL
WITH n, gds.similarity.cosine(n.embedding, $embedding) AS similarity
WHERE similarity IS NOT NULL
RETURN elementId(n) AS nodeId,
       labels(n)[0] AS label,
       coalesce(n.name, n.title, n.value, n.id, n.identifier) AS name,
       similarity AS score,
       properties(n) AS props
ORDER BY similarity DESC
LIMIT $k
""".formatted(label);

        Map<String, Object> params = new HashMap<>();
        params.put("embedding", embF);
        params.put("k", k);

        try (Session session = driver.session()) {
            Result res = session.run(cypher, params);
            List<CandidateEntity> out = new ArrayList<>();
            
            // Consume all records to ensure Neo4j memory is released
            while (res.hasNext()) {
                Record r = res.next();
                out.add(new CandidateEntity(
                        r.get("nodeId").asString(),
                        r.get("label").asString(),
                        r.get("name").isNull() ? "" : r.get("name").asString(),
                        r.get("score").asDouble(),
                        r.get("props").asMap()
                ));
            }
            
            // Result is automatically consumed and will be closed when Session closes
            // Clear intermediate variables to help GC
            params.clear();
            embF.clear();
            
            return out;
        } catch (Neo4jException | SecurityException e) {
            String msg = e.getMessage();
            // For connection issues, throw exception
            if (msg != null && (msg.contains("Unable to connect") || msg.contains("Connection refused") 
                    || msg.contains("timeout") || msg.contains("Connection reset") 
                    || msg.contains("ServiceUnavailable") || msg.contains("SessionExpired"))) {
                throw new Neo4jConnectionException("Neo4j connection failed: " + msg, e);
            }
            // Other Neo4j exceptions
            System.err.println("[Vector Search Error] Neo4j error for label '" + label + "': " + msg);
            throw new Neo4jConnectionException("Neo4j query failed: " + msg, e);
        } catch (Exception e) {
            // For other exceptions, check if it's a connection issue
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Unable to connect") || msg.contains("Connection refused") 
                    || msg.contains("timeout") || msg.contains("Connection reset") 
                    || msg.contains("ServiceUnavailable") || msg.contains("SessionExpired"))) {
                throw new Neo4jConnectionException("Neo4j connection failed: " + msg, e);
            }
            // Log other exceptions
            System.err.println("[Vector Search Error] Unexpected error for label '" + label + "': " + msg);
            return List.of();
        }
    }


    // -------------------------
    // Simple name contains search
    // -------------------------
    public List<CandidateEntity> nameContainsSearch(String label, String query, int limit) {
        if (label == null) label = "";
        label = label.trim();
        if (label.isEmpty()) return List.of();
        if (query == null) query = "";
        query = query.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) return List.of();

        // Determine which property to search based on label type
        String searchProperty;
        String displayProperty;
        switch (label) {
            case "Topic":
            case "Keyword":
            case "Format":
                searchProperty = "n.name";
                displayProperty = "coalesce(n.name, n.title, n.value, n.id, n.identifier)";
                break;
            case "Source":
            case "Organization":
            case "License":
                searchProperty = "n.title";
                displayProperty = "coalesce(n.title, n.name, n.value, n.id, n.identifier)";
                break;
            case "Dataset":
                searchProperty = "coalesce(n.title, n.notes)";
                displayProperty = "coalesce(n.title, n.name, n.notes, n.value, n.id, n.identifier)";
                break;
            default:
                // Fallback to name for unknown types
                searchProperty = "n.name";
                displayProperty = "coalesce(n.name, n.title, n.value, n.id, n.identifier)";
                break;
        }

        String cypher = """
MATCH (n:%s)
WITH n, toLower(%s) AS nm
WHERE nm CONTAINS $q
WITH n, nm, size(nm) AS nmLen, size($q) AS qLen,
     CASE 
       WHEN nm = $q THEN 1.0
       WHEN nm STARTS WITH $q THEN 0.8
       WHEN nm ENDS WITH $q THEN 0.6
       ELSE 0.4
     END AS baseScore
WITH n, nm, baseScore,
     CASE 
       WHEN nmLen = 0 THEN 0.0
       ELSE 1.0 - (abs(nmLen - qLen) / toFloat(CASE WHEN nmLen > qLen THEN nmLen ELSE qLen END))
     END AS lengthScore
RETURN elementId(n) AS nodeId,
       labels(n)[0] AS label,
       %s AS name,
       (baseScore * 0.7 + lengthScore * 0.3) AS score,
       properties(n) AS props
ORDER BY score DESC
LIMIT $limit
""".formatted(label, searchProperty, displayProperty);

        Map<String, Object> params = Map.of("q", query, "limit", Math.max(1, limit));

        try (Session session = driver.session()) {
            Result res = session.run(cypher, params);
            List<CandidateEntity> out = new ArrayList<>();
            while (res.hasNext()) {
                Record r = res.next();
                out.add(new CandidateEntity(
                        r.get("nodeId").asString(),
                        r.get("label").asString(),
                        r.get("name").isNull() ? "" : r.get("name").asString(),
                        r.get("score").asDouble(),
                        r.get("props").asMap()
                ));
            }
            return out;
        } catch (Neo4jException | SecurityException e) {
            throw new Neo4jConnectionException("Neo4j connection failed: " + e.getMessage(), e);
        } catch (Exception e) {
            // For other exceptions, check if it's a connection issue
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Unable to connect") || msg.contains("Connection refused") 
                    || msg.contains("timeout") || msg.contains("Connection reset") 
                    || msg.contains("ServiceUnavailable") || msg.contains("SessionExpired"))) {
                throw new Neo4jConnectionException("Neo4j connection failed: " + msg, e);
            }
            return List.of();
        }
    }

    // -------------------------
    // Fulltext search
    // -------------------------
    public List<CandidateEntity> fulltextSearchNodes(String indexName, String query, int limit) {
        if (indexName == null || indexName.isBlank()) return List.of();
        if (query == null) query = "";
        query = query.trim();
        if (query.isEmpty()) return List.of();

        if (!getIndexNamesCached().isEmpty() && !indexExists(indexName)) {
            return List.of();
        }

        String cypher = """
CALL db.index.fulltext.queryNodes($index, $q) YIELD node, score
RETURN elementId(node) AS nodeId,
       labels(node)[0] AS label,
       coalesce(node.name, node.title, node.value, node.id, node.identifier) AS name,
       score AS score,
       properties(node) AS props
ORDER BY score DESC
LIMIT $limit
""";

        Map<String, Object> params = Map.of("index", indexName, "q", query, "limit", Math.max(1, limit));

        try (Session session = driver.session()) {
            Result res = session.run(cypher, params);
            List<CandidateEntity> out = new ArrayList<>();
            while (res.hasNext()) {
                Record r = res.next();
                out.add(new CandidateEntity(
                        r.get("nodeId").asString(),
                        r.get("label").asString(),
                        r.get("name").isNull() ? "" : r.get("name").asString(),
                        r.get("score").asDouble(),
                        r.get("props").asMap()
                ));
            }
            return out;
        } catch (Neo4jException | SecurityException e) {
            throw new Neo4jConnectionException("Neo4j connection failed: " + e.getMessage(), e);
        } catch (Exception e) {
            // For other exceptions, check if it's a connection issue
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Unable to connect") || msg.contains("Connection refused") 
                    || msg.contains("timeout") || msg.contains("Connection reset") 
                    || msg.contains("ServiceUnavailable") || msg.contains("SessionExpired"))) {
                throw new Neo4jConnectionException("Neo4j connection failed: " + msg, e);
            }
            return List.of();
        }
    }

    // -------------------------
    // Dataset lexical search: IDs
    // -------------------------
    public List<String> searchDatasetIdsByText(String query, int limit, boolean includeKeywords) {
        if (query == null) query = "";
        String q = query.trim();
        if (q.isEmpty()) return List.of();

        // Prefer fulltext if present
        String idx = pickFirstExistingIndex(DATASET_FULLTEXT_INDEX_CANDIDATES);
        if (idx != null) {
            List<CandidateEntity> cands = fulltextSearchNodes(idx, q, Math.max(limit * 2, limit));
            List<String> out = new ArrayList<>();
            for (CandidateEntity c : cands) {
                if ("Dataset".equalsIgnoreCase(c.label())) {
                    out.add(c.nodeId());
                    if (out.size() >= limit) break;
                }
            }
            if (!out.isEmpty()) return out;
        }

        String cypherNoKw = """
MATCH (d:Dataset)
WITH d,
     toLower(coalesce(d.title, d.name, d.value, d.id, d.identifier, "")) AS nm,
     toLower(coalesce(d.description, d.notes, d.summary, d.abstract, d.text, "")) AS ds
WHERE nm CONTAINS $q OR ds CONTAINS $q
RETURN DISTINCT elementId(d) AS did
LIMIT $limit
""";

        String cypherWithKw = """
MATCH (d:Dataset)
OPTIONAL MATCH (d)-[:HAS_KEYWORD]->(k:Keyword)
WITH d,
     toLower(coalesce(d.title, d.name, d.value, d.id, d.identifier, "")) AS nm,
     toLower(coalesce(d.description, d.notes, d.summary, d.abstract, d.text, "")) AS ds,
     collect(toLower(coalesce(k.name, k.title, k.value, ""))) AS kws
WHERE nm CONTAINS $q OR ds CONTAINS $q OR any(x IN kws WHERE x CONTAINS $q)
RETURN DISTINCT elementId(d) AS did
LIMIT $limit
""";

        Map<String, Object> params = Map.of("q", q.toLowerCase(Locale.ROOT), "limit", Math.max(1, limit));

        List<String> out = new ArrayList<>();
        try (Session session = driver.session()) {
            Result rs = session.run(includeKeywords ? cypherWithKw : cypherNoKw, params);
            while (rs.hasNext()) out.add(rs.next().get("did").asString());
        } catch (Neo4jException | SecurityException e) {
            throw new Neo4jConnectionException("Neo4j connection failed: " + e.getMessage(), e);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Unable to connect") || msg.contains("Connection refused") 
                    || msg.contains("timeout") || msg.contains("Connection reset") 
                    || msg.contains("ServiceUnavailable") || msg.contains("SessionExpired"))) {
                throw new Neo4jConnectionException("Neo4j connection failed: " + msg, e);
            }
            return List.of();
        }
        return out;
    }

    // -------------------------
    // Dataset expansions
    // -------------------------
    /**
     * @deprecated Use {@link #datasetsForTopicsBatch(List, Set)} instead for better performance.
     * This method performs a single query per topic, which is inefficient when processing multiple topics.
     */
    @Deprecated
    public List<String> datasetsForTopic(String topicNodeId, int limit) {
        String cypher = """
MATCH (d:Dataset)-[:HAS_TOPIC]->(t:Topic)
WHERE elementId(t) = $tid
RETURN elementId(d) AS did
""";
        return runIdList(cypher, Map.of("tid", topicNodeId), "did");
    }

    /**
     * @deprecated Use {@link #datasetsForTopicsBatch(List, Set)} instead for better performance.
     * This method performs a single query per topic, which is inefficient when processing multiple topics.
     */
    @Deprecated
    public List<String> datasetsForTopic(String topicNodeId, Set<String> hardFilterDatasetIds, int limit) {
        if (hardFilterDatasetIds == null || hardFilterDatasetIds.isEmpty()) {
            return datasetsForTopic(topicNodeId, limit);
        }
        String cypher = """
MATCH (d:Dataset)-[:HAS_TOPIC]->(t:Topic)
WHERE elementId(t) = $tid AND elementId(d) IN $hardFilterIds
RETURN elementId(d) AS did
""";
        return runIdList(cypher, Map.of("tid", topicNodeId, "hardFilterIds", hardFilterDatasetIds), "did");
    }

    /**
     * Batch query: Get (topicId, datasetId) pairs for multiple topics in one query.
     * Returns a map: topicId -> list of datasetIds
     */
    public Map<String, List<String>> datasetsForTopicsBatch(List<String> topicNodeIds, Set<String> hardFilterDatasetIds) {
        if (topicNodeIds == null || topicNodeIds.isEmpty()) return Map.of();
        
        String cypher;
        Map<String, Object> params = new HashMap<>();
        params.put("topicIds", topicNodeIds);
        
        if (hardFilterDatasetIds != null && !hardFilterDatasetIds.isEmpty()) {
            cypher = """
MATCH (d:Dataset)-[:HAS_TOPIC]->(t:Topic)
WHERE elementId(t) IN $topicIds AND elementId(d) IN $hardFilterIds
RETURN elementId(t) AS topicId, elementId(d) AS datasetId
""";
            params.put("hardFilterIds", hardFilterDatasetIds);
        } else {
            cypher = """
MATCH (d:Dataset)-[:HAS_TOPIC]->(t:Topic)
WHERE elementId(t) IN $topicIds
RETURN elementId(t) AS topicId, elementId(d) AS datasetId
""";
        }
        
        Map<String, List<String>> result = new HashMap<>();
        try (Session session = driver.session()) {
            Result rs = session.run(cypher, params);
            while (rs.hasNext()) {
                Record r = rs.next();
                String topicId = r.get("topicId").asString();
                String datasetId = r.get("datasetId").asString();
                result.computeIfAbsent(topicId, k -> new ArrayList<>()).add(datasetId);
            }
        } catch (Neo4jException | SecurityException e) {
            throw new Neo4jConnectionException("Neo4j connection failed: " + e.getMessage(), e);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Unable to connect") || msg.contains("Connection refused") 
                    || msg.contains("timeout") || msg.contains("Connection reset") 
                    || msg.contains("ServiceUnavailable") || msg.contains("SessionExpired"))) {
                throw new Neo4jConnectionException("Neo4j connection failed: " + msg, e);
            }
            return Map.of();
        }
        return result;
    }

    public List<String> datasetsForKeyword(String keywordNodeId, int limit) {
        String cypher = """
MATCH (d:Dataset)-[:HAS_KEYWORD]->(k:Keyword)
WHERE elementId(k) = $kid
RETURN elementId(d) AS did
""";
        return runIdList(cypher, Map.of("kid", keywordNodeId), "did");
    }

    public List<String> datasetsForKeyword(String keywordNodeId, Set<String> hardFilterDatasetIds, int limit) {
        if (hardFilterDatasetIds == null || hardFilterDatasetIds.isEmpty()) {
            return datasetsForKeyword(keywordNodeId, limit);
        }
        String cypher = """
MATCH (d:Dataset)-[:HAS_KEYWORD]->(k:Keyword)
WHERE elementId(k) = $kid AND elementId(d) IN $hardFilterIds
RETURN elementId(d) AS did
""";
        return runIdList(cypher, Map.of("kid", keywordNodeId, "hardFilterIds", hardFilterDatasetIds), "did");
    }

    /**
     * Batch query: Get (keywordId, datasetId) pairs for multiple keywords in one query.
     * Returns a map: keywordId -> list of datasetIds
     */
    public Map<String, List<String>> datasetsForKeywordsBatch(List<String> keywordNodeIds, Set<String> hardFilterDatasetIds) {
        if (keywordNodeIds == null || keywordNodeIds.isEmpty()) return Map.of();
        
        String cypher;
        Map<String, Object> params = new HashMap<>();
        params.put("keywordIds", keywordNodeIds);
        
        if (hardFilterDatasetIds != null && !hardFilterDatasetIds.isEmpty()) {
            cypher = """
MATCH (d:Dataset)-[:HAS_KEYWORD]->(k:Keyword)
WHERE elementId(k) IN $keywordIds AND elementId(d) IN $hardFilterIds
RETURN elementId(k) AS keywordId, elementId(d) AS datasetId
""";
            params.put("hardFilterIds", hardFilterDatasetIds);
        } else {
            cypher = """
MATCH (d:Dataset)-[:HAS_KEYWORD]->(k:Keyword)
WHERE elementId(k) IN $keywordIds
RETURN elementId(k) AS keywordId, elementId(d) AS datasetId
""";
        }
        
        Map<String, List<String>> result = new HashMap<>();
        try (Session session = driver.session()) {
            Result rs = session.run(cypher, params);
            while (rs.hasNext()) {
                Record r = rs.next();
                String keywordId = r.get("keywordId").asString();
                String datasetId = r.get("datasetId").asString();
                result.computeIfAbsent(keywordId, k -> new ArrayList<>()).add(datasetId);
            }
        } catch (Neo4jException | SecurityException e) {
            throw new Neo4jConnectionException("Neo4j connection failed: " + e.getMessage(), e);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Unable to connect") || msg.contains("Connection refused") 
                    || msg.contains("timeout") || msg.contains("Connection reset") 
                    || msg.contains("ServiceUnavailable") || msg.contains("SessionExpired"))) {
                throw new Neo4jConnectionException("Neo4j connection failed: " + msg, e);
            }
            return Map.of();
        }
        return result;
    }

    /**
     * Get node labels for a list of node IDs.
     * Returns a map: nodeId -> label (e.g., "Topic" or "Keyword")
     */
    public Map<String, String> getNodeLabels(List<String> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) return Map.of();
        
        String cypher = """
MATCH (n)
WHERE elementId(n) IN $nodeIds
RETURN elementId(n) AS nodeId, labels(n)[0] AS label
""";
        
        Map<String, String> result = new HashMap<>();
        try (Session session = driver.session()) {
            Result rs = session.run(cypher, Map.of("nodeIds", nodeIds));
            while (rs.hasNext()) {
                Record r = rs.next();
                String nodeId = r.get("nodeId").asString();
                String label = r.get("label").isNull() ? null : r.get("label").asString();
                if (label != null) {
                    result.put(nodeId, label);
                }
            }
        } catch (Neo4jException | SecurityException e) {
            throw new Neo4jConnectionException("Neo4j connection failed: " + e.getMessage(), e);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Unable to connect") || msg.contains("Connection refused") 
                    || msg.contains("timeout") || msg.contains("Connection reset") 
                    || msg.contains("ServiceUnavailable") || msg.contains("SessionExpired"))) {
                throw new Neo4jConnectionException("Neo4j connection failed: " + msg, e);
            }
            return Map.of();
        }
        return result;
    }

    /**
     * @deprecated Use {@link #datasetsForFormatsBatch(List, Set)} instead for better performance.
     * This method performs a single query per format, which is inefficient when processing multiple formats.
     */
    @Deprecated
    public List<String> datasetsForFormat(String formatNodeId, int limit) {
        String cypher = """
MATCH (d:Dataset)-[:HAS_RESOURCE]->(r:Resource)-[:HAS_FORMAT]->(f:Format)
WHERE elementId(f) = $fid
RETURN DISTINCT elementId(d) AS did
""";
        return runIdList(cypher, Map.of("fid", formatNodeId), "did");
    }

    /**
     * @deprecated Use {@link #datasetsForFormatsBatch(List, Set)} instead for better performance.
     * This method performs a single query per format, which is inefficient when processing multiple formats.
     */
    @Deprecated
    public List<String> datasetsForFormat(String formatNodeId, Set<String> hardFilterDatasetIds, int limit) {
        if (hardFilterDatasetIds == null || hardFilterDatasetIds.isEmpty()) {
            return datasetsForFormat(formatNodeId, limit);
        }
        String cypher = """
MATCH (d:Dataset)-[:HAS_RESOURCE]->(r:Resource)-[:HAS_FORMAT]->(f:Format)
WHERE elementId(f) = $fid AND elementId(d) IN $hardFilterIds
RETURN DISTINCT elementId(d) AS did
""";
        return runIdList(cypher, Map.of("fid", formatNodeId, "hardFilterIds", hardFilterDatasetIds), "did");
    }

    /**
     * Batch query: Get (formatId, datasetId) pairs for multiple formats in one query.
     * Returns a map: formatId -> list of datasetIds
     */
    public Map<String, List<String>> datasetsForFormatsBatch(List<String> formatNodeIds, Set<String> hardFilterDatasetIds) {
        if (formatNodeIds == null || formatNodeIds.isEmpty()) return Map.of();
        
        String cypher;
        Map<String, Object> params = new HashMap<>();
        params.put("formatIds", formatNodeIds);
        
        if (hardFilterDatasetIds != null && !hardFilterDatasetIds.isEmpty()) {
            cypher = """
MATCH (d:Dataset)-[:HAS_RESOURCE]->(r:Resource)-[:HAS_FORMAT]->(f:Format)
WHERE elementId(f) IN $formatIds AND elementId(d) IN $hardFilterIds
RETURN DISTINCT elementId(f) AS formatId, elementId(d) AS datasetId
""";
            params.put("hardFilterIds", hardFilterDatasetIds);
        } else {
            cypher = """
MATCH (d:Dataset)-[:HAS_RESOURCE]->(r:Resource)-[:HAS_FORMAT]->(f:Format)
WHERE elementId(f) IN $formatIds
RETURN DISTINCT elementId(f) AS formatId, elementId(d) AS datasetId
""";
        }
        
        Map<String, List<String>> result = new HashMap<>();
        try (Session session = driver.session()) {
            Result rs = session.run(cypher, params);
            while (rs.hasNext()) {
                Record r = rs.next();
                String formatId = r.get("formatId").asString();
                String datasetId = r.get("datasetId").asString();
                result.computeIfAbsent(formatId, k -> new ArrayList<>()).add(datasetId);
            }
        } catch (Neo4jException | SecurityException e) {
            throw new Neo4jConnectionException("Neo4j connection failed: " + e.getMessage(), e);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Unable to connect") || msg.contains("Connection refused") 
                    || msg.contains("timeout") || msg.contains("Connection reset") 
                    || msg.contains("ServiceUnavailable") || msg.contains("SessionExpired"))) {
                throw new Neo4jConnectionException("Neo4j connection failed: " + msg, e);
            }
            return Map.of();
        }
        return result;
    }

    /**
     * @deprecated Use {@link #datasetsForLicensesBatch(List, Set)} instead for better performance.
     * This method performs a single query per license, which is inefficient when processing multiple licenses.
     */
    @Deprecated
    public List<String> datasetsForLicense(String licenseNodeId, int limit) {
        String cypher = """
MATCH (d:Dataset)-[:HAS_LICENSE]->(l:License)
WHERE elementId(l) = $lid
RETURN elementId(d) AS did
""";
        return runIdList(cypher, Map.of("lid", licenseNodeId), "did");
    }

    /**
     * @deprecated Use {@link #datasetsForLicensesBatch(List, Set)} instead for better performance.
     * This method performs a single query per license, which is inefficient when processing multiple licenses.
     */
    @Deprecated
    public List<String> datasetsForLicense(String licenseNodeId, Set<String> hardFilterDatasetIds, int limit) {
        if (hardFilterDatasetIds == null || hardFilterDatasetIds.isEmpty()) {
            return datasetsForLicense(licenseNodeId, limit);
        }
        String cypher = """
MATCH (d:Dataset)-[:HAS_LICENSE]->(l:License)
WHERE elementId(l) = $lid AND elementId(d) IN $hardFilterIds
RETURN elementId(d) AS did
""";
        return runIdList(cypher, Map.of("lid", licenseNodeId, "hardFilterIds", hardFilterDatasetIds), "did");
    }

    /**
     * Batch query: Get (licenseId, datasetId) pairs for multiple licenses in one query.
     * Returns a map: licenseId -> list of datasetIds
     */
    public Map<String, List<String>> datasetsForLicensesBatch(List<String> licenseNodeIds, Set<String> hardFilterDatasetIds) {
        if (licenseNodeIds == null || licenseNodeIds.isEmpty()) return Map.of();
        
        String cypher;
        Map<String, Object> params = new HashMap<>();
        params.put("licenseIds", licenseNodeIds);
        
        if (hardFilterDatasetIds != null && !hardFilterDatasetIds.isEmpty()) {
            cypher = """
MATCH (d:Dataset)-[:HAS_LICENSE]->(l:License)
WHERE elementId(l) IN $licenseIds AND elementId(d) IN $hardFilterIds
RETURN elementId(l) AS licenseId, elementId(d) AS datasetId
""";
            params.put("hardFilterIds", hardFilterDatasetIds);
        } else {
            cypher = """
MATCH (d:Dataset)-[:HAS_LICENSE]->(l:License)
WHERE elementId(l) IN $licenseIds
RETURN elementId(l) AS licenseId, elementId(d) AS datasetId
""";
        }
        
        Map<String, List<String>> result = new HashMap<>();
        try (Session session = driver.session()) {
            Result rs = session.run(cypher, params);
            while (rs.hasNext()) {
                Record r = rs.next();
                String licenseId = r.get("licenseId").asString();
                String datasetId = r.get("datasetId").asString();
                result.computeIfAbsent(licenseId, k -> new ArrayList<>()).add(datasetId);
            }
        } catch (Neo4jException | SecurityException e) {
            throw new Neo4jConnectionException("Neo4j connection failed: " + e.getMessage(), e);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Unable to connect") || msg.contains("Connection refused") 
                    || msg.contains("timeout") || msg.contains("Connection reset") 
                    || msg.contains("ServiceUnavailable") || msg.contains("SessionExpired"))) {
                throw new Neo4jConnectionException("Neo4j connection failed: " + msg, e);
            }
            return Map.of();
        }
        return result;
    }

    /**
     * @deprecated Use {@link #datasetsForOrganizationsBatch(List, Set)} instead for better performance.
     * This method performs a single query per organization, which is inefficient when processing multiple organizations.
     */
    @Deprecated
    public List<String> datasetsForOrganization(String orgNodeId, int limit) {
        String cypher = """
MATCH (d:Dataset)-[:PUBLISHED_BY]->(o:Organization)
WHERE elementId(o) = $oid
RETURN elementId(d) AS did
""";
        return runIdList(cypher, Map.of("oid", orgNodeId), "did");
    }

    /**
     * @deprecated Use {@link #datasetsForOrganizationsBatch(List, Set)} instead for better performance.
     * This method performs a single query per organization, which is inefficient when processing multiple organizations.
     */
    @Deprecated
    public List<String> datasetsForOrganization(String orgNodeId, Set<String> hardFilterDatasetIds, int limit) {
        if (hardFilterDatasetIds == null || hardFilterDatasetIds.isEmpty()) {
            return datasetsForOrganization(orgNodeId, limit);
        }
        String cypher = """
MATCH (d:Dataset)-[:PUBLISHED_BY]->(o:Organization)
WHERE elementId(o) = $oid AND elementId(d) IN $hardFilterIds
RETURN elementId(d) AS did
""";
        return runIdList(cypher, Map.of("oid", orgNodeId, "hardFilterIds", hardFilterDatasetIds), "did");
    }

    /**
     * Batch query: Get (orgId, datasetId) pairs for multiple organizations in one query.
     * Returns a map: orgId -> list of datasetIds
     */
    public Map<String, List<String>> datasetsForOrganizationsBatch(List<String> orgNodeIds, Set<String> hardFilterDatasetIds) {
        if (orgNodeIds == null || orgNodeIds.isEmpty()) return Map.of();
        
        String cypher;
        Map<String, Object> params = new HashMap<>();
        params.put("orgIds", orgNodeIds);
        
        if (hardFilterDatasetIds != null && !hardFilterDatasetIds.isEmpty()) {
            cypher = """
MATCH (d:Dataset)-[:PUBLISHED_BY]->(o:Organization)
WHERE elementId(o) IN $orgIds AND elementId(d) IN $hardFilterIds
RETURN elementId(o) AS orgId, elementId(d) AS datasetId
""";
            params.put("hardFilterIds", hardFilterDatasetIds);
        } else {
            cypher = """
MATCH (d:Dataset)-[:PUBLISHED_BY]->(o:Organization)
WHERE elementId(o) IN $orgIds
RETURN elementId(o) AS orgId, elementId(d) AS datasetId
""";
        }
        
        Map<String, List<String>> result = new HashMap<>();
        try (Session session = driver.session()) {
            Result rs = session.run(cypher, params);
            while (rs.hasNext()) {
                Record r = rs.next();
                String orgId = r.get("orgId").asString();
                String datasetId = r.get("datasetId").asString();
                result.computeIfAbsent(orgId, k -> new ArrayList<>()).add(datasetId);
            }
        } catch (Neo4jException | SecurityException e) {
            throw new Neo4jConnectionException("Neo4j connection failed: " + e.getMessage(), e);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Unable to connect") || msg.contains("Connection refused") 
                    || msg.contains("timeout") || msg.contains("Connection reset") 
                    || msg.contains("ServiceUnavailable") || msg.contains("SessionExpired"))) {
                throw new Neo4jConnectionException("Neo4j connection failed: " + msg, e);
            }
            return Map.of();
        }
        return result;
    }

    /**
     * @deprecated Use {@link #datasetsForSourcesBatch(List)} instead for better performance.
     * This method performs a single query per source, which is inefficient when processing multiple sources.
     */
    @Deprecated
    public List<String> datasetsForSource(String sourceNodeId, int limit) {
        // Support both elementId (internal Neo4j ID) and s.id property (portal IDs like "data.gov")
        String cypher = """
MATCH (s:Source)-[:PROVIDES]->(d:Dataset)
WHERE s.id = $sid
RETURN elementId(d) AS did
""";
        return runIdList(cypher, Map.of("sid", sourceNodeId), "did");
    }

    /**
     * Batch query: Get (sourceId, datasetId) pairs for multiple sources in one query.
     * Returns a map: sourceId -> list of datasetIds
     * Note: Source matching uses s.id property (portal IDs), not elementId
     */
    public Map<String, List<String>> datasetsForSourcesBatch(List<String> sourceNodeIds) {
        if (sourceNodeIds == null || sourceNodeIds.isEmpty()) return Map.of();
        
        String cypher = """
MATCH (s:Source)-[:PROVIDES]->(d:Dataset)
WHERE s.id IN $sourceIds
RETURN s.id AS sourceId, elementId(d) AS datasetId
""";
        
        Map<String, List<String>> result = new HashMap<>();
        try (Session session = driver.session()) {
            Result rs = session.run(cypher, Map.of("sourceIds", sourceNodeIds));
            while (rs.hasNext()) {
                Record r = rs.next();
                String sourceId = r.get("sourceId").asString();
                String datasetId = r.get("datasetId").asString();
                result.computeIfAbsent(sourceId, k -> new ArrayList<>()).add(datasetId);
            }
        } catch (Neo4jException | SecurityException e) {
            throw new Neo4jConnectionException("Neo4j connection failed: " + e.getMessage(), e);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Unable to connect") || msg.contains("Connection refused") 
                    || msg.contains("timeout") || msg.contains("Connection reset") 
                    || msg.contains("ServiceUnavailable") || msg.contains("SessionExpired"))) {
                throw new Neo4jConnectionException("Neo4j connection failed: " + msg, e);
            }
            return Map.of();
        }
        return result;
    }

    /**
     * Get dataset IDs filtered by data catalog selection.
     * @deprecated This method is no longer used. Data catalog selection is now handled by directly converting
     * portal names to Source node IDs and using datasetsForSource() instead. See GraphRetrievalService
     * for the unified Source/Portal filtering logic.
     * Portal matching is based on Source node id property (exact match):
     * - datagov: id = "data.gov"
     * - pasda: id = "pasda"
     * - stacGoogle: id = "3886fcaf359f302ca7255a9b71b265b33662f88b" (Google Earth Engine Proxy for openEO)
     * - stacDedl: id = "7148d4a5544f0896e63775cc95908897fa0e8a36" (Destination Earth Data Lake Harmonized Data Access)
     * - stacMicrosoft: id = "32c3d214ac192b3316cef7a5996e3cba84989ffd" (Microsoft Planetary Computer STAC API)
     * - stacPaituli: id = "c923746393499b170b2172f45ffe2f8037be3a9e" (Paituli STAC with Finnish data)
     */
    @Deprecated
    public List<String> datasetsForPortals(Map<String, Boolean> portalSelection, int limit) {
        if (portalSelection == null || portalSelection.isEmpty()) {
            // No portal filtering, return empty list (will be handled by caller)
            return List.of();
        }
        
        // Portal id mapping
        Map<String, String> portalIds = Map.of(
            "datagov", "data.gov",
            "pasda", "pasda",
            "stacGoogle", "3886fcaf359f302ca7255a9b71b265b33662f88b",
            "stacDedl", "7148d4a5544f0896e63775cc95908897fa0e8a36",
            "stacMicrosoft", "32c3d214ac192b3316cef7a5996e3cba84989ffd",
            "stacPaituli", "c923746393499b170b2172f45ffe2f8037be3a9e"
        );
        
        // Collect enabled portal ids
        List<String> enabledPortalIds = new ArrayList<>();
        List<String> enabledPortalNames = new ArrayList<>();
        
        for (Map.Entry<String, String> entry : portalIds.entrySet()) {
            String portalKey = entry.getKey();
            String portalId = entry.getValue();
            if (Boolean.TRUE.equals(portalSelection.getOrDefault(portalKey, false))) {
                enabledPortalIds.add(portalId);
                enabledPortalNames.add(portalKey);
            }
        }
        
        if (enabledPortalIds.isEmpty()) {
            // No portals enabled, return empty list
            return List.of();
        }
        
        // Build WHERE clause using exact id matching
        String cypher = """
MATCH (s:Source)-[:PROVIDES]->(d:Dataset)
WHERE s.id IN $portalIds
RETURN DISTINCT elementId(d) AS did
""";
        
        List<String> result = runIdList(cypher, Map.of("portalIds", enabledPortalIds), "did");
        
        return result;
    }

    // -------------------------
    // Hard filter (spaceId + time)
    // NOTE: This method is NOT USED. The system always uses bbox-based filtering via hardFilterDatasetIdsByBboxTime().
    // Space filtering uses bbox (bounding box coordinates), not spaceId.
    // This method is kept for backward compatibility or potential future use.
    // Logic: if has space, filter by space; if has time, filter by time; if both, filter by both
    // Time uses: begin, end properties (format: "20220831 00:00:00")
    // -------------------------
    @Deprecated
    public List<String> hardFilterDatasetIds(String spaceId, String tStart, String tEnd, int limit) {
        boolean hasSpace = (spaceId != null && !spaceId.isBlank());
        boolean hasTime = (tStart != null && !tStart.isBlank() && tEnd != null && !tEnd.isBlank());
        
        if (!hasSpace && !hasTime) return List.of();
        
        String cypher = """
MATCH (d:Dataset)
OPTIONAL MATCH (d)-[:HAS_SPACE]->(s:Space)
OPTIONAL MATCH (d)-[:HAS_TIME]->(t:Time)
WITH d, s, t
WHERE
  (
    $hasSpace = false
    OR (s IS NOT NULL AND elementId(s) = $spaceId)
  )
  AND
  (
    $hasTime = false
    OR (
      t IS NOT NULL AND t.begin IS NOT NULL AND t.end IS NOT NULL
      AND t.begin <= $tEnd AND t.end >= $tStart
    )
  )
RETURN DISTINCT elementId(d) AS did
""";
        Map<String, Object> params = new HashMap<>();
        params.put("hasSpace", hasSpace);
        params.put("spaceId", hasSpace ? spaceId : null);
        params.put("hasTime", hasTime);
        params.put("tStart", hasTime ? tStart : null);
        params.put("tEnd", hasTime ? tEnd : null);
        return runIdList(cypher, params, "did");
    }

    // -------------------------
    // Hard filter (bbox + time)
    // Logic: if has space, filter by space; if has time, filter by time; if both, filter by both
    // Space uses: east, north, south, west properties
    // Time uses: begin, end properties (format: "20220831 00:00:00")
    // -------------------------
    /**
     * Optimized spatial/temporal filtering using separate query templates.
     * Uses indexes on Space (west, east, south, north) and Time (begin, end) for better performance.
     * 
     * Query templates:
     * - A: Space + Time (filter Space first, then Time)
     * - B: Space only
     * - C: Time only
     */
    public List<String> hardFilterDatasetIdsByBboxTime(double[] bbox, String tStart, String tEnd, int limit) {
        boolean hasBbox = (bbox != null && bbox.length == 4);
        boolean hasTime = (tStart != null && !tStart.isBlank() && tEnd != null && !tEnd.isBlank());

        if (!hasBbox && !hasTime) return List.of();

        Double qW = hasBbox ? bbox[0] : null;  // minLon
        Double qS = hasBbox ? bbox[1] : null;  // minLat
        Double qE = hasBbox ? bbox[2] : null;  // maxLon
        Double qN = hasBbox ? bbox[3] : null;  // maxLat

        String cypher;
        Map<String, Object> params = new HashMap<>();

        if (hasBbox && hasTime) {
            // Template A: Space + Time
            // Filter Space first (uses space_bbox_index), then Time (uses time_range_index)
            cypher = """
MATCH (s:Space)
WHERE s.west <= $qE AND s.east >= $qW
  AND s.south <= $qN AND s.north >= $qS
MATCH (s)<-[:HAS_SPACE]-(d:Dataset)
MATCH (d)-[:HAS_TIME]->(t:Time)
WHERE t.begin <= $tEnd AND t.end >= $tStart
RETURN DISTINCT elementId(d) AS did
""";
            params.put("qW", qW);
            params.put("qS", qS);
            params.put("qE", qE);
            params.put("qN", qN);
            params.put("tStart", tStart);
            params.put("tEnd", tEnd);
        } else if (hasBbox) {
            // Template B: Space only
            // Filter Space first (uses space_bbox_index)
            cypher = """
MATCH (s:Space)
WHERE s.west <= $qE AND s.east >= $qW
  AND s.south <= $qN AND s.north >= $qS
MATCH (s)<-[:HAS_SPACE]-(d:Dataset)
RETURN DISTINCT elementId(d) AS did
""";
            params.put("qW", qW);
            params.put("qS", qS);
            params.put("qE", qE);
            params.put("qN", qN);
        } else {
            // Template C: Time only
            // Filter Time first (uses time_range_index)
            cypher = """
MATCH (t:Time)
WHERE t.begin <= $tEnd AND t.end >= $tStart
MATCH (t)<-[:HAS_TIME]-(d:Dataset)
RETURN DISTINCT elementId(d) AS did
""";
            params.put("tStart", tStart);
            params.put("tEnd", tEnd);
        }

        return runIdList(cypher, params, "did");
    }

    // -------------------------
    // Bundles for evidence / UI
    // -------------------------
    public List<DatasetBundle> fetchDatasetBundles(List<String> datasetIds) {
        if (datasetIds == null || datasetIds.isEmpty()) return List.of();

        String cypher = """
MATCH (d:Dataset)
WHERE elementId(d) IN $ids

// Collect all direct relationships separately to avoid Cartesian product
OPTIONAL MATCH (d)-[:HAS_TOPIC]->(topic:Topic)
WITH d, collect(DISTINCT CASE WHEN topic IS NOT NULL THEN {rel: 'HAS_TOPIC', node_id: elementId(topic), label: 'Topic', props: properties(topic)} ELSE null END) AS topics

OPTIONAL MATCH (d)-[:PUBLISHED_BY]->(org:Organization)
WITH d, topics, collect(DISTINCT CASE WHEN org IS NOT NULL THEN {rel: 'PUBLISHED_BY', node_id: elementId(org), label: 'Organization', props: properties(org)} ELSE null END) AS orgs

OPTIONAL MATCH (d)-[:HAS_LICENSE]->(lic:License)
WITH d, topics, orgs, collect(DISTINCT CASE WHEN lic IS NOT NULL THEN {rel: 'HAS_LICENSE', node_id: elementId(lic), label: 'License', props: properties(lic)} ELSE null END) AS licenses

OPTIONAL MATCH (d)-[:HAS_SPACE]->(space:Space)
WITH d, topics, orgs, licenses, collect(DISTINCT CASE WHEN space IS NOT NULL THEN {rel: 'HAS_SPACE', node_id: elementId(space), label: 'Space', props: properties(space)} ELSE null END) AS spaces

OPTIONAL MATCH (d)-[:HAS_TIME]->(time:Time)
WITH d, topics, orgs, licenses, spaces, collect(DISTINCT CASE WHEN time IS NOT NULL THEN {rel: 'HAS_TIME', node_id: elementId(time), label: 'Time', props: properties(time)} ELSE null END) AS times

OPTIONAL MATCH (src:Source)-[:PROVIDES]->(d)
WITH d, topics, orgs, licenses, spaces, times, collect(DISTINCT CASE WHEN src IS NOT NULL THEN {rel: 'PROVIDES', node_id: elementId(src), label: 'Source', props: properties(src)} ELSE null END) AS sources

OPTIONAL MATCH (d)-[:HAS_RESOURCE]->(res:Resource)
WITH d, topics, orgs, licenses, spaces, times, sources, collect(DISTINCT CASE WHEN res IS NOT NULL THEN {rel: 'HAS_RESOURCE', node_id: elementId(res), label: 'Resource', props: properties(res)} ELSE null END) AS resources

// Match Format for each Resource (Resource-HAS_FORMAT->Format)
OPTIONAL MATCH (d)-[:HAS_RESOURCE]->(res2:Resource)-[:HAS_FORMAT]->(fmt:Format)
WITH d, topics, orgs, licenses, spaces, times, sources, resources, collect(DISTINCT CASE WHEN fmt IS NOT NULL AND res2 IS NOT NULL THEN {rel: 'HAS_FORMAT', node_id: elementId(fmt), label: 'Format', resource_id: elementId(res2), props: properties(fmt)} ELSE null END) AS formats

WITH d, 
     [rel IN (topics + orgs + licenses + spaces + times + sources + resources + formats) WHERE rel IS NOT NULL AND rel.node_id IS NOT NULL AND rel.label IS NOT NULL] AS allRels

RETURN elementId(d) AS datasetId,
       properties(d) AS datasetProps,
       allRels AS rels
""";

        Map<String, Object> params = Map.of("ids", datasetIds);

        List<DatasetBundle> out = new ArrayList<>();
        try (Session session = driver.session()) {
            Result rs = session.run(cypher, params);
            while (rs.hasNext()) {
                Record r = rs.next();
                String did = r.get("datasetId").asString();
                Map<String, Object> dprops = r.get("datasetProps").asMap();

                List<Object> rels = r.get("rels").asList();
                Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
                for (Object o : rels) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) o;
                    String lbl = String.valueOf(m.getOrDefault("label", "Entity"));
                    if (lbl != null && !lbl.equals("null") && !lbl.isBlank()) {
                        grouped.computeIfAbsent(lbl, kk -> new ArrayList<>()).add(m);
                    }
                }
                
                // Deduplicate Source entities by node_id (keep only one per dataset)
                if (grouped.containsKey("Source")) {
                    List<Map<String, Object>> sources = grouped.get("Source");
                    if (sources != null && sources.size() > 1) {
                        // Keep only the first Source (deduplicate by node_id)
                        Map<String, Map<String, Object>> seen = new LinkedHashMap<>();
                        for (Map<String, Object> src : sources) {
                            Object nodeId = src.get("node_id");
                            if (nodeId != null && !seen.containsKey(nodeId.toString())) {
                                seen.put(nodeId.toString(), src);
                            }
                        }
                        grouped.put("Source", new ArrayList<>(seen.values()));
                    }
                }
                
                // Debug: log labels found for first dataset
                if (out.isEmpty() && !grouped.isEmpty()) {
                }

                out.add(new DatasetBundle(did, dprops, grouped, null, null));
            }
        } catch (Neo4jException | SecurityException e) {
            throw new Neo4jConnectionException("Neo4j connection failed: " + e.getMessage(), e);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Unable to connect") || msg.contains("Connection refused") 
                    || msg.contains("timeout") || msg.contains("Connection reset") 
                    || msg.contains("ServiceUnavailable") || msg.contains("SessionExpired"))) {
                throw new Neo4jConnectionException("Neo4j connection failed: " + msg, e);
            }
            return List.of();
        }
        return out;
    }

    private List<String> runIdList(String cypher, Map<String, Object> params, String key) {
        List<String> out = new ArrayList<>();
        try (Session session = driver.session()) {
            Result rs = session.run(cypher, params);
            while (rs.hasNext()) {
                Record r = rs.next();
                if (r.containsKey(key) && !r.get(key).isNull()) {
                    out.add(r.get(key).asString());
                }
            }
        } catch (Neo4jException | SecurityException e) {
            throw new Neo4jConnectionException("Neo4j connection failed: " + e.getMessage(), e);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Unable to connect") || msg.contains("Connection refused") 
                    || msg.contains("timeout") || msg.contains("Connection reset") 
                    || msg.contains("ServiceUnavailable") || msg.contains("SessionExpired"))) {
                throw new Neo4jConnectionException("Neo4j connection failed: " + msg, e);
            }
            return List.of();
        }
        return out;
    }
    
    /**
     * Get Space and Time information for datasets (bbox and time range)
     * Returns a map: datasetId -> {space: {bbox: [w,s,e,n]}, time: {begin: "...", end: "..."}}
     */
    public Map<String, Map<String, Object>> getDatasetSpaceTimeInfo(List<String> datasetIds) {
        if (datasetIds == null || datasetIds.isEmpty()) return Map.of();
        
        String cypher = """
MATCH (d:Dataset)
WHERE elementId(d) IN $ids
OPTIONAL MATCH (d)-[:HAS_SPACE]->(s:Space)
OPTIONAL MATCH (d)-[:HAS_TIME]->(t:Time)
WITH d, s, t
RETURN elementId(d) AS datasetId,
       CASE WHEN s IS NULL THEN null
            WHEN s.bbox IS NOT NULL AND size(s.bbox) = 4 THEN s.bbox
            WHEN s.west IS NOT NULL AND s.south IS NOT NULL AND s.east IS NOT NULL AND s.north IS NOT NULL 
            THEN [toFloat(s.west), toFloat(s.south), toFloat(s.east), toFloat(s.north)]
            ELSE null END AS bbox,
       CASE WHEN t IS NULL THEN null ELSE t.begin END AS timeBegin,
       CASE WHEN t IS NULL THEN null ELSE t.end END AS timeEnd
""";
        
        Map<String, Object> params = Map.of("ids", datasetIds);
        Map<String, Map<String, Object>> result = new HashMap<>();
        
        try (Session session = driver.session()) {
            Result rs = session.run(cypher, params);
            while (rs.hasNext()) {
                Record r = rs.next();
                String did = r.get("datasetId").asString();
                
                Map<String, Object> info = new HashMap<>();
                
                // Space bbox
                if (!r.get("bbox").isNull()) {
                    List<Object> bboxList = r.get("bbox").asList();
                    if (bboxList != null && bboxList.size() == 4) {
                        double[] bbox = new double[4];
                        bbox[0] = ((Number) bboxList.get(0)).doubleValue(); // west
                        bbox[1] = ((Number) bboxList.get(1)).doubleValue(); // south
                        bbox[2] = ((Number) bboxList.get(2)).doubleValue(); // east
                        bbox[3] = ((Number) bboxList.get(3)).doubleValue(); // north
                        info.put("bbox", bbox);
                    }
                }
                
                // Time range
                String timeBegin = r.get("timeBegin").isNull() ? null : r.get("timeBegin").asString();
                String timeEnd = r.get("timeEnd").isNull() ? null : r.get("timeEnd").asString();
                if (timeBegin != null && timeEnd != null) {
                    Map<String, String> timeInfo = new HashMap<>();
                    timeInfo.put("begin", timeBegin);
                    timeInfo.put("end", timeEnd);
                    info.put("time", timeInfo);
                }
                
                if (!info.isEmpty()) {
                    result.put(did, info);
                }
            }
        } catch (Neo4jException | SecurityException e) {
            throw new Neo4jConnectionException("Neo4j connection failed: " + e.getMessage(), e);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Unable to connect") || msg.contains("Connection refused") 
                    || msg.contains("timeout") || msg.contains("Connection reset") 
                    || msg.contains("ServiceUnavailable"))) {
                throw new Neo4jConnectionException("Neo4j connection failed: " + msg, e);
            }
            throw new Neo4jConnectionException("Neo4j query failed: " + msg, e);
        }
        
        return result;
    }
    
    /**
     * Get dataset titles by dataset IDs.
     * Returns a map from dataset ID to title (or "Unknown" if not found).
     */
    public Map<String, String> getDatasetTitles(List<String> datasetIds) {
        Map<String, String> titles = new HashMap<>();
        if (datasetIds == null || datasetIds.isEmpty()) {
            return titles;
        }
        
        String cypher = """
            MATCH (d:Dataset)
            WHERE elementId(d) IN $ids
            RETURN elementId(d) AS datasetId, 
                   coalesce(d.title, d.name, d.notes, d.value, d.id, d.identifier, 'Unknown') AS title
            """;
        
        Map<String, Object> params = Map.of("ids", datasetIds);
        
        try (Session session = driver.session()) {
            Result rs = session.run(cypher, params);
            while (rs.hasNext()) {
                Record r = rs.next();
                String did = r.get("datasetId").asString();
                String title = r.get("title").asString();
                titles.put(did, title);
            }
        } catch (Exception e) {
            // If query fails, return empty map (titles will show as "Unknown")
            System.err.println("Warning: Failed to fetch dataset titles: " + e.getMessage());
        }
        
        return titles;
    }
    
    /**
     * Get dataset URLs by dataset IDs.
     * Returns a map from dataset ID to URL (or null if not found).
     */
    public Map<String, String> getDatasetUrls(List<String> datasetIds) {
        Map<String, String> urls = new HashMap<>();
        if (datasetIds == null || datasetIds.isEmpty()) {
            return urls;
        }
        
        String cypher = """
            MATCH (d:Dataset)
            WHERE elementId(d) IN $ids
            RETURN elementId(d) AS datasetId, 
                   d.url AS url
            """;
        
        Map<String, Object> params = Map.of("ids", datasetIds);
        
        try (Session session = driver.session()) {
            Result rs = session.run(cypher, params);
            while (rs.hasNext()) {
                Record r = rs.next();
                String did = r.get("datasetId").asString();
                if (!r.get("url").isNull()) {
                    String url = r.get("url").asString();
                    urls.put(did, url);
                }
            }
        } catch (Exception e) {
            // If query fails, return empty map
            System.err.println("Warning: Failed to fetch dataset URLs: " + e.getMessage());
        }
        
        return urls;
    }
}

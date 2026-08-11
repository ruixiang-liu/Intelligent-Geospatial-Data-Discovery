package edu.psu.giscience.igdd.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Best-effort PostgreSQL persistence for conversations.
 * 
 * New architecture: conversation_id is the primary key, no session_id.
 * Conversations can be soft-deleted (deleted=true) but remain in database.
 *
 * Enable by setting in application.properties (or env):
 *   postgres.url=jdbc:postgresql://host:5432/db
 *   postgres.user=...
 *   postgres.password=...
 *
 * Requires PostgreSQL JDBC driver on classpath.
 * Tables are created by CreatePostgresConversationTables (standalone tool).
 */
@Service
public class PostgresConversationRepository {

    private final ObjectMapper om = new ObjectMapper();

    @Value("${postgres.url:}")
    private String url;

    @Value("${postgres.user:}")
    private String user;

    @Value("${postgres.password:}")
    private String password;

    private boolean enabled() {
        return url != null && !url.isBlank();
    }

    private Connection open() throws Exception {
        return DriverManager.getConnection(url, user, password);
    }

    /**
     * Create a new conversation.
     * Returns the conversation_id (same as input).
     */
    public UUID createConversation(UUID conversationId, String apiKey) {
        if (!enabled()) return conversationId;
        if (conversationId == null) return null;
        if (apiKey == null || apiKey.isBlank()) apiKey = "default";

        String sql = """
                INSERT INTO igdd_conversation (conversation_id, api_key, created_at, updated_at)
                VALUES (?, ?, now(), now())
                ON CONFLICT (conversation_id) DO UPDATE
                SET updated_at = now()
                """;

        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, conversationId);
            ps.setString(2, apiKey);
            ps.executeUpdate();
        } catch (Exception ignored) {
            // never break discovery flow due to DB issues
        }
        return conversationId;
    }

    /**
     * Update conversation title.
     */
    public void updateTitle(UUID conversationId, String title) {
        if (!enabled()) return;
        if (conversationId == null) return;

        String sql = """
                UPDATE igdd_conversation
                SET title = ?, updated_at = now()
                WHERE conversation_id = ? AND deleted = FALSE
                """;

        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, title);
            ps.setObject(2, conversationId);
            ps.executeUpdate();
        } catch (Exception ignored) {
            // never break discovery flow due to DB issues
        }
    }

    /**
     * Soft-delete a conversation (set deleted=true).
     */
    public void deleteConversation(UUID conversationId) {
        if (!enabled()) return;
        if (conversationId == null) return;

        String sql = """
                UPDATE igdd_conversation
                SET deleted = TRUE, updated_at = now()
                WHERE conversation_id = ?
                """;

        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, conversationId);
            ps.executeUpdate();
        } catch (Exception ignored) {
            // never break discovery flow due to DB issues
        }
    }

    /**
     * Get the next question_index for a conversation.
     * Returns the maximum question_index + 1, or 1 if no turns exist.
     */
    public int getNextQuestionIndex(UUID conversationId) {
        if (!enabled()) return 1;
        if (conversationId == null) return 1;

        String sql = """
                SELECT COALESCE(MAX(question_index), 0) + 1 AS next_index
                FROM igdd_conversation_turn
                WHERE conversation_id = ?
                """;

        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("next_index");
                }
            }
        } catch (Exception ignored) {
        }
        return 1;
    }

    /**
     * Get the current (latest) question_index for a conversation.
     * Returns the maximum question_index, or 0 if no turns exist.
     */
    public int getCurrentQuestionIndex(UUID conversationId) {
        if (!enabled()) return 0;
        if (conversationId == null) return 0;

        String sql = """
                SELECT COALESCE(MAX(question_index), 0) AS current_index
                FROM igdd_conversation_turn
                WHERE conversation_id = ?
                """;

        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("current_index");
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /**
     * Get the next turn_index for a specific question_index in a conversation.
     * Returns the maximum turn_index + 1 for the given question_index, or 1 if no turns exist for that question.
     */
    public int getNextTurnIndex(UUID conversationId, int questionIndex) {
        if (!enabled()) return 1;
        if (conversationId == null) return 1;
        if (questionIndex < 1) return 1;

        String sql = """
                SELECT COALESCE(MAX(turn_index), 0) + 1 AS next_index
                FROM igdd_conversation_turn
                WHERE conversation_id = ? AND question_index = ?
                """;

        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, conversationId);
            ps.setInt(2, questionIndex);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("next_index");
                }
            }
        } catch (Exception ignored) {
        }
        return 1;
    }

    public void insertTurn(UUID conversationId,
                           String questionId,
                           int questionIndex,
                           int turnIndex,
                           String role,
                           String agent,
                           String text,
                           Instant ts,
                           Map<String, Object> meta) {
        if (!enabled()) return;
        if (conversationId == null) return;
        if (questionIndex < 1) return;
        if (turnIndex < 1) return;

        String metaJson = "{}";
        try {
            if (meta != null && !meta.isEmpty()) metaJson = om.writeValueAsString(meta);
        } catch (Exception ignored) {
        }

        String sql = """
                INSERT INTO igdd_conversation_turn
                  (conversation_id, question_id, question_index, turn_index, role, agent, text, ts, meta)
                VALUES
                  (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (conversation_id, question_index, turn_index) DO NOTHING
                """;

        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, conversationId);
            ps.setString(2, questionId);
            ps.setInt(3, questionIndex);
            ps.setInt(4, turnIndex);
            ps.setString(5, role == null ? "" : role);
            ps.setString(6, agent);
            ps.setString(7, text == null ? "" : text);
            ps.setTimestamp(8, Timestamp.from(ts == null ? Instant.now() : ts));
            ps.setString(9, metaJson);
            ps.executeUpdate();
        } catch (Exception ignored) {
        }
    }

    /**
     * List all non-deleted conversations for a given API key.
     * Returns a list of conversation summaries sorted by created_at DESC.
     * Note: API key comparison is case-sensitive.
     */
    public List<Map<String, Object>> listConversationsByApiKey(String apiKey) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!enabled()) return result;
        if (apiKey == null || apiKey.isBlank()) apiKey = "default";

        // Use explicit case-sensitive comparison
        // Force case-sensitive comparison using COLLATE "C" (POSIX collation)
        // This ensures API keys are compared byte-by-byte, distinguishing case
        String sql = """
                SELECT conversation_id, title, shareable, created_at, updated_at
                FROM igdd_conversation
                WHERE api_key COLLATE "C" = ? COLLATE "C" AND deleted = FALSE
                ORDER BY created_at DESC
                """;

        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, apiKey);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> conv = new HashMap<>();
                    UUID convId = (UUID) rs.getObject("conversation_id");
                    conv.put("conversationId", convId.toString());
                    conv.put("title", rs.getString("title"));
                    conv.put("shareable", rs.getBoolean("shareable"));
                    
                    Timestamp createdAt = rs.getTimestamp("created_at");
                    if (createdAt != null) {
                        conv.put("createdAt", createdAt.toInstant().atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                    }
                    Timestamp updatedAt = rs.getTimestamp("updated_at");
                    if (updatedAt != null) {
                        conv.put("updatedAt", updatedAt.toInstant().atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                    }
                    
                    // Get preview (first user message or title if available)
                    String title = rs.getString("title");
                    if (title == null || title.isBlank()) {
                        title = getConversationPreview(convId);
                    }
                    conv.put("preview", title != null ? title : "");
                    
                    result.add(conv);
                }
            }
        } catch (Exception e) {
            // Log but don't throw - best effort
            System.err.println("Error listing conversations: " + e.getMessage());
        }
        return result;
    }

    /**
     * Get the first user message as a preview for the conversation.
     */
    private String getConversationPreview(UUID conversationId) {
        if (conversationId == null) return "";
        
        String sql = """
                SELECT text
                FROM igdd_conversation_turn
                WHERE conversation_id = ? AND role = 'user'
                ORDER BY question_index ASC, turn_index ASC
                LIMIT 1
                """;
        
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String text = rs.getString("text");
                    if (text != null && text.length() > 100) {
                        return text.substring(0, 100) + "...";
                    }
                    return text != null ? text : "";
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /**
     * Get all turns (messages) for a conversation.
     * Returns a list of turns sorted by question_index ASC, then turn_index ASC.
     */
    public List<Map<String, Object>> getConversationTurns(UUID conversationId) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!enabled()) return result;
        if (conversationId == null) return result;

        String sql = """
                SELECT question_id, question_index, turn_index, role, agent, text, ts, meta
                FROM igdd_conversation_turn
                WHERE conversation_id = ?
                ORDER BY question_index ASC, turn_index ASC
                """;

        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> turn = new HashMap<>();
                    turn.put("questionId", rs.getString("question_id"));
                    turn.put("questionIndex", rs.getInt("question_index"));
                    turn.put("turnIndex", rs.getInt("turn_index"));
                    turn.put("role", rs.getString("role"));
                    turn.put("agent", rs.getString("agent"));
                    turn.put("text", rs.getString("text"));
                    
                    Timestamp ts = rs.getTimestamp("ts");
                    if (ts != null) {
                        turn.put("timestamp", ts.toInstant().atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                    }
                    
                    // Parse JSONB meta
                    String metaJson = rs.getString("meta");
                    if (metaJson != null && !metaJson.isEmpty()) {
                        try {
                            Map<?, ?> meta = om.readValue(metaJson, Map.class);
                            turn.put("meta", meta);
                        } catch (Exception ignored) {
                            turn.put("meta", new HashMap<>());
                        }
                    } else {
                        turn.put("meta", new HashMap<>());
                    }
                    
                    result.add(turn);
                }
            }
        } catch (Exception e) {
            // Log but don't throw - best effort
            System.err.println("Error getting conversation turns: " + e.getMessage());
        }
        return result;
    }

    /**
     * Get the question_id for a specific question_index in a conversation.
     * Returns the question_id from the first turn of that question_index, or null if not found.
     */
    public String getQuestionIdForQuestionIndex(UUID conversationId, int questionIndex) {
        if (!enabled()) return null;
        if (conversationId == null) return null;
        if (questionIndex < 1) return null;

        String sql = """
                SELECT question_id
                FROM igdd_conversation_turn
                WHERE conversation_id = ? AND question_index = ?
                ORDER BY turn_index ASC
                LIMIT 1
                """;

        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, conversationId);
            ps.setInt(2, questionIndex);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("question_id");
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Update the question_id for all turns of a specific question_index in a conversation.
     * This is used when we need to correct the question_id after intent parsing.
     */
    public void updateQuestionIdForQuestionIndex(UUID conversationId, int questionIndex, String questionId) {
        if (!enabled()) return;
        if (conversationId == null) return;
        if (questionIndex < 1) return;
        if (questionId == null || questionId.isBlank()) return;

        String sql = """
                UPDATE igdd_conversation_turn
                SET question_id = ?
                WHERE conversation_id = ? AND question_index = ?
                """;

        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, questionId);
            ps.setObject(2, conversationId);
            ps.setInt(3, questionIndex);
            ps.executeUpdate();
        } catch (Exception ignored) {
            // Best-effort: don't break flow
        }
    }

    /**
     * Check if a question (by question_index) has already returned final results.
     * A question is considered completed if any assistant turn for that question_index
     * has meta containing "stage": "done" or has non-empty datasets.
     */
    public boolean hasQuestionCompleted(UUID conversationId, int questionIndex) {
        if (!enabled()) return false;
        if (conversationId == null) return false;
        if (questionIndex < 1) return false;

        String sql = """
                SELECT meta
                FROM igdd_conversation_turn
                WHERE conversation_id = ? AND question_index = ? AND role = 'assistant'
                ORDER BY turn_index DESC
                """;

        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, conversationId);
            ps.setInt(2, questionIndex);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String metaJson = rs.getString("meta");
                    if (metaJson != null && !metaJson.isEmpty()) {
                        try {
                            Map<?, ?> meta = om.readValue(metaJson, Map.class);
                            // Check if stage is "done"
                            Object stage = meta.get("stage");
                            if ("done".equals(stage)) {
                                return true;
                            }
                            // Check if datasets exist and is non-empty
                            Object datasets = meta.get("datasets");
                            if (datasets != null) {
                                if (datasets instanceof List && !((List<?>) datasets).isEmpty()) {
                                    return true;
                                }
                                if (datasets instanceof Map && !((Map<?, ?>) datasets).isEmpty()) {
                                    return true;
                                }
                                // Also check if datasets count > 0
                                if (datasets instanceof Number && ((Number) datasets).intValue() > 0) {
                                    return true;
                                }
                            }
                        } catch (Exception ignored) {
                            // Continue to next turn if meta parse fails
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Best-effort: return false on any error
        }
        return false;
    }

    /**
     * Get the last assistant turn's question_index and turn_index for a conversation.
     * Returns an array [questionIndex, turnIndex], or null if no assistant turn exists.
     */
    public int[] getLastAssistantTurnIndex(UUID conversationId) {
        if (!enabled()) return null;
        if (conversationId == null) return null;

        String sql = """
                SELECT question_index, turn_index
                FROM igdd_conversation_turn
                WHERE conversation_id = ? AND role = 'assistant'
                ORDER BY question_index DESC, turn_index DESC
                LIMIT 1
                """;

        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int questionIndex = rs.getInt("question_index");
                    int turnIndex = rs.getInt("turn_index");
                    return new int[]{questionIndex, turnIndex};
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Update the meta field of the last assistant turn in a conversation.
     * This is used to save the complete response data (datasets, HITL, etc.).
     */
    public void updateLastAssistantTurnMeta(UUID conversationId, int questionIndex, int turnIndex, Map<String, Object> meta) {
        if (!enabled()) return;
        if (conversationId == null) return;
        
        String metaJson = "{}";
        try {
            if (meta != null && !meta.isEmpty()) metaJson = om.writeValueAsString(meta);
        } catch (Exception ignored) {
            return;
        }
        
        String sql = """
                UPDATE igdd_conversation_turn
                SET meta = ?::jsonb
                WHERE conversation_id = ?
                  AND question_index = ?
                  AND turn_index = ?
                  AND role = 'assistant'
                """;
        
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, metaJson);
            ps.setObject(2, conversationId);
            ps.setInt(3, questionIndex);
            ps.setInt(4, turnIndex);
            ps.executeUpdate();
        } catch (Exception ignored) {
            // Best-effort: don't break flow
        }
    }

    /**
     * Get conversation by ID (including deleted ones).
     */
    public Map<String, Object> getConversation(UUID conversationId) {
        if (!enabled()) return null;
        if (conversationId == null) return null;

        String sql = """
                SELECT conversation_id, api_key, title, deleted, shareable, created_at, updated_at
                FROM igdd_conversation
                WHERE conversation_id = ?
                """;

        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> conv = new HashMap<>();
                    conv.put("conversationId", rs.getObject("conversation_id", UUID.class).toString());
                    conv.put("apiKey", rs.getString("api_key"));
                    conv.put("title", rs.getString("title"));
                    conv.put("deleted", rs.getBoolean("deleted"));
                    conv.put("shareable", rs.getBoolean("shareable"));
                    
                    Timestamp createdAt = rs.getTimestamp("created_at");
                    if (createdAt != null) {
                        conv.put("createdAt", createdAt.toInstant().atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                    }
                    Timestamp updatedAt = rs.getTimestamp("updated_at");
                    if (updatedAt != null) {
                        conv.put("updatedAt", updatedAt.toInstant().atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                    }
                    
                    return conv;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Check if conversation is shareable and not deleted.
     * Returns true if conversation exists, is not deleted, and is shareable.
     */
    public boolean isConversationShareable(UUID conversationId) {
        if (!enabled()) return false;
        if (conversationId == null) return false;

        String sql = """
                SELECT deleted, shareable
                FROM igdd_conversation
                WHERE conversation_id = ?
                """;

        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    boolean deleted = rs.getBoolean("deleted");
                    boolean shareable = rs.getBoolean("shareable");
                    return !deleted && shareable;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Set shareable flag for a conversation.
     */
    public void setShareable(UUID conversationId, boolean shareable) {
        if (!enabled()) return;
        if (conversationId == null) return;

        String sql = """
                UPDATE igdd_conversation
                SET shareable = ?, updated_at = now()
                WHERE conversation_id = ? AND deleted = FALSE
                """;

        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setBoolean(1, shareable);
            ps.setObject(2, conversationId);
            ps.executeUpdate();
        } catch (Exception ignored) {
        }
    }

    /**
     * Get shareable status for a conversation (returns false if deleted).
     */
    public boolean getShareable(UUID conversationId) {
        if (!enabled()) return false;
        if (conversationId == null) return false;

        String sql = """
                SELECT deleted, shareable
                FROM igdd_conversation
                WHERE conversation_id = ?
                """;

        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    boolean deleted = rs.getBoolean("deleted");
                    if (deleted) return false;
                    return rs.getBoolean("shareable");
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}

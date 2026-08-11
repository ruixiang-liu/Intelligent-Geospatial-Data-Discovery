package edu.psu.giscience.igdd.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.psu.giscience.igdd.domain.Feedback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;

/**
 * PostgreSQL persistence for user feedback.
 * 
 * Enable by setting in application.properties (or env):
 *   postgres.url=jdbc:postgresql://host:5432/db
 *   postgres.user=...
 *   postgres.password=...
 *
 * Requires PostgreSQL JDBC driver on classpath.
 * Table is created by CreatePostgresFeedbackTable (standalone tool).
 */
@Service
public class FeedbackRepository {

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

    /**
     * Save feedback to PostgreSQL.
     */
    public void save(Feedback feedback) {
        if (!enabled()) {
            return; // Silently skip if PostgreSQL is not configured
        }

        String sql = """
                INSERT INTO igdd_feedback 
                (api_key, conversation_id, content, rating, user_agent, ip_address, metadata, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """;

        try (Connection c = DriverManager.getConnection(url, user, password);
             PreparedStatement ps = c.prepareStatement(sql)) {
            
            ps.setString(1, feedback.getApiKey());
            if (feedback.getConversationId() != null) {
                ps.setObject(2, feedback.getConversationId());
            } else {
                ps.setObject(2, null);
            }
            ps.setString(3, feedback.getContent());
            if (feedback.getRating() != null) {
                ps.setInt(4, feedback.getRating());
            } else {
                ps.setObject(4, null);
            }
            ps.setString(5, feedback.getUserAgent());
            ps.setString(6, feedback.getIpAddress());
            
            // Serialize metadata to JSON
            String metadataJson = om.writeValueAsString(feedback.getMetadata() != null ? feedback.getMetadata() : new HashMap<>());
            ps.setString(7, metadataJson);
            
            if (feedback.getCreatedAt() != null) {
                ps.setTimestamp(8, Timestamp.from(feedback.getCreatedAt()));
            } else {
                ps.setTimestamp(8, Timestamp.from(Instant.now()));
            }
            
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("Error saving feedback: " + e.getMessage());
            e.printStackTrace();
            // Don't throw - feedback submission should not break the application
        }
    }
}

// File: ConversationMemoryStore.java
package edu.psu.giscience.igdd.memory;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Conversation-scoped in-memory store + best-effort PostgreSQL persistence.
 *
 * - Each conversationId maps to one ConversationMemory instance.
 * - Frontend should create a new conversationId for each new conversation.
 * - This store does NOT automatically restore conversations (frontend must explicitly load via API).
 * - addUserMessage/addAgentMessage write to memory and PostgreSQL (if enabled).
 */
@Service
public class ConversationMemoryStore {

    private static final class ConversationState {
        final UUID conversationId;
        final ConversationMemory memory;

        ConversationState(UUID conversationId, ConversationMemory memory) {
            this.conversationId = conversationId;
            this.memory = memory;
        }
    }

    private final ConcurrentHashMap<UUID, ConversationState> conversations = new ConcurrentHashMap<>();
    private final PostgresConversationRepository pg;

    public ConversationMemoryStore(PostgresConversationRepository pg) {
        this.pg = pg;
    }

    /**
     * Get or create memory for a conversation.
     * Creates a new in-memory state if conversationId is not in cache.
     * Does NOT restore from database (frontend should load via API if needed).
     */
    public ConversationMemory getOrCreate(UUID conversationId) {
        return getOrCreateState(conversationId).memory;
    }

    public void addUserMessage(UUID conversationId, String questionId, int questionIndex, int turnIndex, String text, Map<String, Object> meta) {
        ConversationState st = getOrCreateState(conversationId);
        st.memory.addUserMessage(text, meta);
        pg.insertTurn(st.conversationId, questionId, questionIndex, turnIndex, "user", null, text, Instant.now(), meta);
    }

    public void addAgentMessage(UUID conversationId, String questionId, int questionIndex, int turnIndex, String text, String agent, Map<String, Object> meta) {
        ConversationState st = getOrCreateState(conversationId);
        st.memory.addAgentMessage(text, agent, meta);
        pg.insertTurn(st.conversationId, questionId, questionIndex, turnIndex, "assistant", agent, text, Instant.now(), meta);
    }

    /**
     * Clear memory for a conversation (in-memory only, database remains).
     * This does NOT delete the conversation from database (use repository.deleteConversation for that).
     */
    public void clearMemory(UUID conversationId) {
        if (conversationId == null) return;
        conversations.remove(conversationId);
    }

    private ConversationState getOrCreateState(UUID conversationId) {
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId cannot be null");
        }
        return conversations.computeIfAbsent(conversationId, cid -> {
            // Ensure conversation exists in database
            pg.createConversation(cid, "default"); // API key will be set by controller
            ConversationMemory mem = new ConversationMemory();
            return new ConversationState(cid, mem);
        });
    }
}

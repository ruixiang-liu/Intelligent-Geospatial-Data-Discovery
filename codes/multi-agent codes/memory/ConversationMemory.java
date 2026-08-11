package edu.psu.giscience.igdd.memory;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Session-scoped conversation memory (in-process).
 *
 * - Keep recent turns to help intent parsing / synthesis.
 * - PostgreSQL persistence is handled by ConversationMemoryStore via PostgresConversationRepository (best-effort).
 */
public class ConversationMemory {

    public static class Turn {
        private final String role;   // "user" | "assistant"
        private final String agent;  // optional agent name
        private final String text;
        private final String ts;     // ISO-8601
        private final Map<String, Object> meta;

        public Turn(String role, String agent, String text, Map<String, Object> meta) {
            this.role = role;
            this.agent = agent;
            this.text = text == null ? "" : text;
            this.ts = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
            this.meta = meta;
        }

        public String getRole() { return role; }
        public String getAgent() { return agent; }
        public String getText() { return text; }
        public String getTs() { return ts; }
        public Map<String, Object> getMeta() { return meta; }
    }

    private final List<Turn> turns = new ArrayList<>();

    public void addUserMessage(String text, Map<String, Object> meta) {
        turns.add(new Turn("user", null, text, meta));
    }

    public void addAgentMessage(String text, String agent, Map<String, Object> meta) {
        turns.add(new Turn("assistant", agent, text, meta));
    }

    public List<Turn> getTurns() {
        return Collections.unmodifiableList(turns);
    }

    public int size() {
        return turns.size();
    }

    public void clear() {
        turns.clear();
    }

    /** Format recent turns as plain text for LLM prompts. */
    public String formatRecentAsText(int n) {
        if (n <= 0 || turns.isEmpty()) return "";
        int start = Math.max(0, turns.size() - n);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < turns.size(); i++) {
            Turn t = turns.get(i);
            if (t.getRole() == null) continue;
            sb.append(t.getRole()).append(": ").append(t.getText()).append("\n");
        }
        return sb.toString().trim();
    }
}

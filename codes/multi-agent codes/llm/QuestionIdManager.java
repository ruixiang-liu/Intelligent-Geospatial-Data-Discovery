package edu.psu.giscience.igdd.llm;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages question_id for GIBD API.
 * 
 * Rules:
 * - question_id is associated with (conversation_id, intent_hash)
 * - New conversation -> new question_id
 * - New intent detected -> new question_id
 * - Same conversation + same intent -> same question_id (including HITL)
 * - question_id is stored in turn meta (not in conversation table)
 */
@Service
public class QuestionIdManager {

    private final GibdApiClient gibdClient;
    
    // Store question_id by (conversationId, intentHash)
    // intentHash is a hash of the intent to detect new intents
    private final Map<String, String> questionIdCache = new ConcurrentHashMap<>();
    
    // Store current intent hash by conversationId
    private final Map<UUID, String> intentHashCache = new ConcurrentHashMap<>();
    
    // Store validation question_id (from API key validation) by conversationId
    // This can be reused if conversation has no actual questions yet
    private final Map<UUID, String> validationQuestionIdCache = new ConcurrentHashMap<>();

    public QuestionIdManager(GibdApiClient gibdClient) {
        this.gibdClient = gibdClient;
    }
    
    /**
     * Store a question_id obtained during API key validation.
     * This can be reused later if the conversation has no actual questions yet.
     * 
     * @param conversationId Conversation ID
     * @param questionId Question ID obtained from validation
     */
    public void storeValidationQuestionId(UUID conversationId, String questionId) {
        if (conversationId != null && questionId != null && !questionId.isBlank()) {
            validationQuestionIdCache.put(conversationId, questionId);
        }
    }
    
    /**
     * Get and remove the validation question_id for a conversation.
     * This should be called when we want to reuse a validation question_id
     * for an empty conversation (one with no turns yet).
     * 
     * @param conversationId Conversation ID
     * @return Validation question_id, or null if not found
     */
    public String takeValidationQuestionId(UUID conversationId) {
        if (conversationId == null) return null;
        return validationQuestionIdCache.remove(conversationId);
    }

    /**
     * Get or create question_id for a conversation.
     * 
     * @param apiKey User's GIBD API key
     * @param conversationId Conversation ID (persistent UUID)
     * @param intentHash Hash of current intent (null for new conversation)
     * @param hasExistingTurns Whether the conversation has any existing turns (to determine if validation questionId can be reused)
     * @return question_id
     * @throws GibdApiClient.GibdApiException if API call fails
     */
    public String getOrCreateQuestionId(String apiKey, UUID conversationId, String intentHash, boolean hasExistingTurns) 
            throws GibdApiClient.GibdApiException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new GibdApiClient.GibdApiException("API key is required. Please enter your GIBD API key in the configuration panel.");
        }
        if (conversationId == null) {
            throw new GibdApiClient.GibdApiException("Conversation ID is required");
        }
        
        String previousIntentHash = intentHashCache.get(conversationId);
        
        // Check if intent changed (new intent = new question_id)
        boolean intentChanged = (intentHash != null && !intentHash.equals(previousIntentHash));
        
        // If new conversation or intent changed, create new question_id
        if (previousIntentHash == null || intentChanged) {
            // IMPORTANT: If conversation has no existing turns and we have a validation questionId, reuse it
            // This avoids wasting a questionId that was obtained during API key validation
            if (!hasExistingTurns && previousIntentHash == null) {
                String validationQuestionId = takeValidationQuestionId(conversationId);
                if (validationQuestionId != null && !validationQuestionId.isBlank()) {
                    // Reuse validation questionId
                    String fullCacheKey = conversationId.toString() + "|" + (intentHash != null ? intentHash : "default");
                    questionIdCache.put(fullCacheKey, validationQuestionId);
                    intentHashCache.put(conversationId, intentHash != null ? intentHash : "default");
                    return validationQuestionId;
                }
            }
            
            // Create new question_id
            String questionId = gibdClient.getQuestionId(apiKey);
            
            // Update cache
            String fullCacheKey = conversationId.toString() + "|" + (intentHash != null ? intentHash : "default");
            questionIdCache.put(fullCacheKey, questionId);
            intentHashCache.put(conversationId, intentHash != null ? intentHash : "default");
            
            return questionId;
        }
        
        // Use existing question_id from cache
        String fullCacheKey = conversationId.toString() + "|" + previousIntentHash;
        String cachedQuestionId = questionIdCache.get(fullCacheKey);
        if (cachedQuestionId != null) {
            return cachedQuestionId;
        }
        
        // Cache miss - create new one (should not happen in normal flow, but handle gracefully)
        String questionId = gibdClient.getQuestionId(apiKey);
        questionIdCache.put(fullCacheKey, questionId);
        return questionId;
    }
    
    /**
     * Get or create question_id for a conversation (backward compatibility - assumes hasExistingTurns=false for new conversations).
     * 
     * @param apiKey User's GIBD API key
     * @param conversationId Conversation ID (persistent UUID)
     * @param intentHash Hash of current intent (null for new conversation)
     * @return question_id
     * @throws GibdApiClient.GibdApiException if API call fails
     */
    public String getOrCreateQuestionId(String apiKey, UUID conversationId, String intentHash) 
            throws GibdApiClient.GibdApiException {
        // For backward compatibility, check if conversation has turns by checking intentHashCache
        // If previousIntentHash is null, assume conversation is new (no turns)
        boolean hasExistingTurns = intentHashCache.containsKey(conversationId);
        return getOrCreateQuestionId(apiKey, conversationId, intentHash, hasExistingTurns);
    }

    /**
     * Clear question_id for a conversation (when conversation ends).
     */
    public void clearQuestionId(UUID conversationId) {
        if (conversationId == null) return;
        intentHashCache.remove(conversationId);
        
        // Remove all question_ids for this conversation
        questionIdCache.entrySet().removeIf(entry -> 
            entry.getKey().startsWith(conversationId.toString() + "|"));
    }

    /**
     * Generate hash from intent for comparison.
     */
    public static String hashIntent(Object intent) {
        if (intent == null) return null;
        // Simple hash based on intent's string representation
        // In production, you might want a more sophisticated hash
        return String.valueOf(intent.hashCode());
    }
}

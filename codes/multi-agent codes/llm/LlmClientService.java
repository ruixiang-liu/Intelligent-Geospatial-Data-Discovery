package edu.psu.giscience.igdd.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class LlmClientService {

    private final GibdApiClient gibdClient;
    private final ObjectMapper om = new ObjectMapper();

    public LlmClientService(GibdApiClient gibdClient) {
        this.gibdClient = gibdClient;
    }

    /**
     * Ask LLM using GIBD API.
     * 
     * @param prompt User prompt
     * @param modelOverride Model name (e.g., "gpt-5.2")
     * @param userApiKey GIBD API key from user
     * @param questionId Question ID for this conversation
     * @return LLM response or error message
     */
    public String askPlain(String prompt, String modelOverride, String userApiKey, String questionId) {
        if (userApiKey == null || userApiKey.isBlank()) {
            return "Error: API key is required. Please enter your GIBD API key in the configuration panel.";
        }
        
        if (questionId == null || questionId.isBlank()) {
            return "Error: Question ID is required. Please try again.";
        }

        String model = (modelOverride != null && !modelOverride.isBlank())
                ? modelOverride.trim()
                : "gpt-5.2";

        try {
            // Prepare messages for chat format
            List<Map<String, String>> messages = new ArrayList<>();
            Map<String, String> userMessage = new HashMap<>();
            userMessage.put("role", "user");
            userMessage.put("content", prompt);
            messages.add(userMessage);

            String response = gibdClient.callOpenAI(userApiKey, questionId, model, messages, false);
            return response;
        } catch (GibdApiClient.GibdApiException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Ask LLM with chat messages format.
     * 
     * @param messages List of messages (each with "role" and "content")
     * @param modelOverride Model name
     * @param userApiKey GIBD API key
     * @param questionId Question ID
     * @return LLM response or error message
     */
    public String askChat(List<Map<String, String>> messages, String modelOverride, 
                         String userApiKey, String questionId) {
        if (userApiKey == null || userApiKey.isBlank()) {
            return "Error: API key is required. Please enter your GIBD API key in the configuration panel.";
        }
        
        if (questionId == null || questionId.isBlank()) {
            return "Error: Question ID is required. Please try again.";
        }

        String model = (modelOverride != null && !modelOverride.isBlank())
                ? modelOverride.trim()
                : "gpt-5.2";

        try {
            String response = gibdClient.callOpenAI(userApiKey, questionId, model, messages, false);
            return response;
        } catch (GibdApiClient.GibdApiException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Get embeddings using GIBD API.
     * 
     * @param text Text to embed
     * @param userApiKey GIBD API key from user
     * @param questionId Question ID for this conversation
     * @return List of embedding values, or empty list on error
     */
    public List<Double> embed(String text, String userApiKey, String questionId) {
        if (text == null) text = "";
        text = text.trim();
        if (text.isEmpty()) return List.of();
        
        if (userApiKey == null || userApiKey.isBlank()) {
            return List.of();
        }
        
        if (questionId == null || questionId.isBlank()) {
            return List.of();
        }

        try {
            return gibdClient.getEmbeddings(userApiKey, questionId, text);
        } catch (GibdApiClient.GibdApiException e) {
            // Log error but return empty list to avoid breaking the flow
            System.err.println("Embedding error: " + e.getMessage());
            return List.of();
        } catch (Exception e) {
            System.err.println("Unexpected embedding error: " + e.getMessage());
            return List.of();
        }
    }
}

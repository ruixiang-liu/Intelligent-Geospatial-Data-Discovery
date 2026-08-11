package edu.psu.giscience.igdd.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * GIBD API Client for accessing OpenAI through GIBD gateway.
 * 
 * Flow:
 * 1. Get question_id using user_api_key
 * 2. Use question_id to call OpenAI API
 */
@Service
public class GibdApiClient {

    private static final String GIBD_API_URL = "https://www.gibd.online";
    private static final String SERVICE_NAME = "Intelligent Geospatial Data Discovery";

    private final OkHttpClient http;
    private final ObjectMapper om;

    public GibdApiClient() {
        this.http = new OkHttpClient.Builder()
                .callTimeout(90, TimeUnit.SECONDS)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(90, TimeUnit.SECONDS)
                .build();
        this.om = new ObjectMapper();
    }

    /**
     * Get question_id from GIBD API.
     * 
     * @param userApiKey GIBD API key from user
     * @return question_id or null if error
     * @throws GibdApiException if API call fails
     */
    public String getQuestionId(String userApiKey) throws GibdApiException {
        if (userApiKey == null || userApiKey.isBlank()) {
            throw new GibdApiException("API key is required. Please enter your GIBD API key in the configuration panel.");
        }

        try {
            String url = GIBD_API_URL + "/api/request-question-id";
            
            Map<String, String> payload = new HashMap<>();
            payload.put("user_api_key", userApiKey);
            payload.put("service_name", SERVICE_NAME);
            
            String jsonPayload = om.writeValueAsString(payload);

            Request req = new Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json; charset=utf-8")
                    .post(RequestBody.create(jsonPayload.getBytes(StandardCharsets.UTF_8),
                            MediaType.parse("application/json")))
                    .build();

            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    String body = resp.body() == null ? "" : resp.body().string();
                    throw new GibdApiException("Failed to get question_id: HTTP " + resp.code() + " - " + body);
                }
                
                String body = resp.body() == null ? "" : resp.body().string();
                JsonNode root = om.readTree(body);
                String questionId = root.path("question_id").asText(null);
                
                if (questionId == null || questionId.isBlank()) {
                    throw new GibdApiException("Invalid response from GIBD API: question_id not found in response");
                }
                
                return questionId;
            }
        } catch (IOException e) {
            throw new GibdApiException("Network error while getting question_id: " + e.getMessage(), e);
        } catch (Exception e) {
            if (e instanceof GibdApiException) {
                throw e;
            }
            throw new GibdApiException("Error getting question_id: " + e.getMessage(), e);
        }
    }

    /**
     * Call OpenAI through GIBD API using question_id.
     * 
     * @param userApiKey GIBD API key
     * @param questionId Question ID from getQuestionId()
     * @param model Model name (e.g., "gpt-5.2")
     * @param messages List of messages for chat
     * @param stream Whether to stream response
     * @return OpenAI response content
     * @throws GibdApiException if API call fails
     */
    public String callOpenAI(String userApiKey, String questionId, String model, 
                            List<Map<String, String>> messages, boolean stream) throws GibdApiException {
        if (userApiKey == null || userApiKey.isBlank()) {
            throw new GibdApiException("API key is required. Please enter your GIBD API key in the configuration panel.");
        }
        if (questionId == null || questionId.isBlank()) {
            throw new GibdApiException("Question ID is required");
        }

        try {
            String url = GIBD_API_URL + "/api/openai/" + userApiKey;
            
            Map<String, Object> payload = new HashMap<>();
            payload.put("question_id", questionId);
            payload.put("service_name", SERVICE_NAME);
            payload.put("model", model);
            payload.put("messages", messages);
            payload.put("stream", stream);
            
            String jsonPayload = om.writeValueAsString(payload);

            Request req = new Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json; charset=utf-8")
                    .post(RequestBody.create(jsonPayload.getBytes(StandardCharsets.UTF_8),
                            MediaType.parse("application/json")))
                    .build();

            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    String body = resp.body() == null ? "" : resp.body().string();
                    throw new GibdApiException("OpenAI API error: HTTP " + resp.code() + " - " + body);
                }
                
                String body = resp.body() == null ? "" : resp.body().string();
                JsonNode root = om.readTree(body);
                
                // Extract content from OpenAI response format
                JsonNode choices = root.path("choices");
                if (choices.isArray() && choices.size() > 0) {
                    JsonNode message = choices.get(0).path("message");
                    String content = message.path("content").asText("");
                    if (!content.isBlank()) {
                        return content.trim();
                    }
                }
                
                throw new GibdApiException("Invalid response format from OpenAI API: " + body);
            }
        } catch (IOException e) {
            throw new GibdApiException("Network error while calling OpenAI: " + e.getMessage(), e);
        } catch (Exception e) {
            if (e instanceof GibdApiException) {
                throw e;
            }
            throw new GibdApiException("Error calling OpenAI: " + e.getMessage(), e);
        }
    }

    /**
     * Get embeddings through GIBD API.
     * 
     * @param userApiKey GIBD API key from user
     * @param questionId Question ID for this conversation
     * @param text Text to embed
     * @return List of embedding values
     * @throws GibdApiException if API call fails
     */
    public List<Double> getEmbeddings(String userApiKey, String questionId, String text) throws GibdApiException {
        if (userApiKey == null || userApiKey.isBlank()) {
            throw new GibdApiException("API key is required");
        }
        if (questionId == null || questionId.isBlank()) {
            throw new GibdApiException("Question ID is required");
        }
        if (text == null || text.isBlank()) {
            return List.of();
        }

        try {
            String url = GIBD_API_URL + "/api/embeddings/" + userApiKey;
            
            Map<String, Object> payload = new HashMap<>();
            payload.put("question_id", questionId);
            payload.put("service_name", SERVICE_NAME);
            payload.put("input", text);
            payload.put("model", "text-embedding-3-large");
            
            String jsonPayload = om.writeValueAsString(payload);

            Request req = new Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json; charset=utf-8")
                    .post(RequestBody.create(jsonPayload.getBytes(StandardCharsets.UTF_8),
                            MediaType.parse("application/json")))
                    .build();

            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    String body = resp.body() == null ? "" : resp.body().string();
                    throw new GibdApiException("Embedding API error: HTTP " + resp.code() + " - " + body);
                }
                
                String body = resp.body() == null ? "" : resp.body().string();
                JsonNode root = om.readTree(body);
                
                // Extract embedding from response
                JsonNode data = root.path("data");
                if (data.isArray() && data.size() > 0) {
                    JsonNode embedding = data.get(0).path("embedding");
                    if (embedding.isArray()) {
                        List<Double> result = new java.util.ArrayList<>(embedding.size());
                        for (JsonNode value : embedding) {
                            result.add(value.asDouble());
                        }
                        return result;
                    }
                }
                
                throw new GibdApiException("Invalid response format from embedding API: " + body);
            }
        } catch (IOException e) {
            throw new GibdApiException("Network error while getting embeddings: " + e.getMessage(), e);
        } catch (Exception e) {
            if (e instanceof GibdApiException) {
                throw e;
            }
            throw new GibdApiException("Error getting embeddings: " + e.getMessage(), e);
        }
    }

    /**
     * Custom exception for GIBD API errors.
     */
    public static class GibdApiException extends Exception {
        public GibdApiException(String message) {
            super(message);
        }

        public GibdApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

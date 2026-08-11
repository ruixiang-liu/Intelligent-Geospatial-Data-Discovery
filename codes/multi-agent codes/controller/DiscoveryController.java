package edu.psu.giscience.igdd.controller;

import edu.psu.giscience.igdd.domain.Feedback;
import edu.psu.giscience.igdd.domain.Hyperparameters;
import edu.psu.giscience.igdd.llm.GibdApiClient;
import edu.psu.giscience.igdd.memory.FeedbackRepository;
import edu.psu.giscience.igdd.memory.PostgresConversationRepository;
import edu.psu.giscience.igdd.service.DiscoveryOrchestratorService;
import edu.psu.giscience.igdd.service.StatusEmitter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller exposing HTTP endpoints for the IGDD system.
 *
 * Contract:
 * POST /api/igdd/query
 * Body:
 * {
 *   "conversationId": "...",  // UUID string
 *   "apiKey": "...",
 *   "query": "...",
 *   "action": "message" | "continue",
 *   "model": "gpt-5.2",
 *   "useKeywords": true
 * }
 */
@CrossOrigin
@RestController
@RequestMapping("/api/igdd")
public class DiscoveryController {

    private final DiscoveryOrchestratorService orchestratorService;
    private final StatusEmitter statusEmitter;
    private final PostgresConversationRepository conversationRepository;
    private final GibdApiClient gibdApiClient;
    private final FeedbackRepository feedbackRepository;
    private final edu.psu.giscience.igdd.llm.QuestionIdManager questionIdManager;

    public DiscoveryController(DiscoveryOrchestratorService orchestratorService,
                              StatusEmitter statusEmitter,
                              PostgresConversationRepository conversationRepository,
                              GibdApiClient gibdApiClient,
                              FeedbackRepository feedbackRepository,
                              edu.psu.giscience.igdd.llm.QuestionIdManager questionIdManager) {
        this.orchestratorService = orchestratorService;
        this.statusEmitter = statusEmitter;
        this.conversationRepository = conversationRepository;
        this.gibdApiClient = gibdApiClient;
        this.feedbackRepository = feedbackRepository;
        this.questionIdManager = questionIdManager;
    }

    public static class QueryRequest {
        public String conversationId;  // UUID string
        public String apiKey;
        public String query;

        /** "message" (default) or "continue" */
        public String action;

        /** optional: chosen by frontend */
        public String model;

        /** optional: keyword matching toggle */
        public Boolean useKeywords;
        
        /** optional: hyperparameters (weights, thresholds) */
        public Hyperparameters hyperparameters;
    }

    public static class CreateConversationRequest {
        public String apiKey;
    }

    public static class UpdateTitleRequest {
        public String title;
    }

    public static class ToggleShareableRequest {
        public Boolean shareable;
    }

    public static class FeedbackRequest {
        public String apiKey;
        public String conversationId;
        public String content;
        public Integer rating; // 1-5 scale
        public Map<String, Object> metadata;
    }

    @PostMapping("/query")
    public ResponseEntity<Map<String, Object>> query(@RequestBody QueryRequest request) {

        String apiKey = (request.apiKey == null || request.apiKey.isBlank())
                ? "default"
                : request.apiKey.trim();
        
        UUID conversationId;
        try {
            if (request.conversationId == null || request.conversationId.isBlank()) {
                // Validate API key before creating new conversation
                if (!apiKey.equals("default") && !apiKey.isBlank()) {
                    try {
                        // Store the questionId obtained from validation so it can be reused if conversation is empty
                        String validationQuestionId = gibdApiClient.getQuestionId(apiKey);
                        // Create conversation first to get the UUID
                        conversationId = UUID.randomUUID();
                        conversationRepository.createConversation(conversationId, apiKey);
                        // Store validation questionId for potential reuse
                        questionIdManager.storeValidationQuestionId(conversationId, validationQuestionId);
                    } catch (GibdApiClient.GibdApiException e) {
                        // API key validation failed (invalid key or service unavailable)
                        Map<String, Object> error = new HashMap<>();
                        error.put("error", "API key validation failed: " + e.getMessage());
                        return ResponseEntity.status(401).body(error);
                    } catch (Exception e) {
                        // Catch any other unexpected exceptions during validation
                        Map<String, Object> error = new HashMap<>();
                        error.put("error", "API key validation error: " + e.getMessage());
                        return ResponseEntity.status(500).body(error);
                    }
                } else {
                    // API key is default, create new conversation
                    conversationId = UUID.randomUUID();
                    conversationRepository.createConversation(conversationId, apiKey);
                }
            } else {
                conversationId = UUID.fromString(request.conversationId);
            }
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Invalid conversation ID format");
            return ResponseEntity.badRequest().body(error);
        }

        String action = (request.action == null || request.action.isBlank())
                ? "message"
                : request.action;

        String query = request.query == null ? "" : request.query;

        // Emit intent active immediately after user input (for "message" action)
        // This ensures timing starts from user input
        // Only emit intent active for actual messages, not dimension selections (format: "Dimension:1,2")
        if ("message".equals(action) && !query.isEmpty()) {
            // Check if this is a dimension selection (format: "Dimension:1,3,5" or "Dimension:continue")
            // Don't emit intent parsing active for dimension selections - they don't need parsing
            boolean isDimensionSelection = query.matches("(?i)^((Topic|Format|License|Organization|Source):(.+?)(;\\s*)?)+$");
            if (!isDimensionSelection) {
                statusEmitter.emitStatus(conversationId.toString(), "intent_parsing", "active");
            }
        }

        // Use default hyperparameters if not provided
        Hyperparameters hyperparams = (request.hyperparameters != null) 
                ? request.hyperparameters 
                : Hyperparameters.defaults();
        hyperparams.validate();
        
        // Pass conversationId instead of sessionId
        Map<String, Object> response = orchestratorService.handleQuery(
                apiKey,
                conversationId,
                query,
                request.model,
                action,
                request.useKeywords,
                hyperparams
        );

        // Include conversationId in response
        response.put("conversationId", conversationId.toString());

        // Save complete response data to database (for full conversation restoration)
        // Create a copy of response data for saving (remove conversationId as it's already in the turn)
        Map<String, Object> responseDataForSave = new HashMap<>(response);
        responseDataForSave.remove("conversationId"); // Don't duplicate conversationId
        
        // Add pipeline states to response data for restoration
        // Pipeline states track which stages completed (for reconstructing pipeline graph)
        // Include durations from StatusEmitter for accurate time display
        List<Map<String, Object>> pipelineStates = new ArrayList<>();
        String sessionId = conversationId.toString();
        Map<String, Long> durations = statusEmitter.getAllStageDurations(sessionId);
        Map<String, String> statuses = statusEmitter.getAllStageStatuses(sessionId);
        
        String stage = (String) response.getOrDefault("stage", "unknown");
        // If stage is "done", all pipeline stages should be marked as done
        if ("done".equals(stage)) {
            // Add all stages with their durations if available
            addPipelineState(pipelineStates, "intent_parsing", "done", durations.get("intent_parsing"));
            // Check if HITL was skipped or done
            String hitlStatus = statuses.get("hitl_confirmation");
            if ("skipped".equals(hitlStatus)) {
                addPipelineState(pipelineStates, "hitl_confirmation", "skipped", null);
            } else {
                addPipelineState(pipelineStates, "hitl_confirmation", "done", durations.get("hitl_confirmation"));
            }
            addPipelineState(pipelineStates, "entity_matching", "done", durations.get("entity_matching"));
            addPipelineState(pipelineStates, "spatial_temporal_filter", "done", durations.get("spatial_temporal_filter"));
            addPipelineState(pipelineStates, "dataset_scoring", "done", durations.get("dataset_scoring"));
            addPipelineState(pipelineStates, "evidence_collection", "done", durations.get("evidence_collection"));
            // Check if selection was skipped or done
            String selectionStatus = statuses.get("dataset_selection");
            if ("skipped".equals(selectionStatus)) {
                addPipelineState(pipelineStates, "dataset_selection", "skipped", null);
            } else {
                addPipelineState(pipelineStates, "dataset_selection", "done", durations.get("dataset_selection"));
            }
            addPipelineState(pipelineStates, "answer_synthesis", "done", durations.get("answer_synthesis"));
        } else if ("general_response".equals(stage)) {
            // For general_response (non-data discovery), mark intent parsing as done
            // Use general_response duration if available, otherwise use intent_parsing duration
            Long generalResponseDuration = durations.get("general_response");
            Long intentDuration = durations.get("intent_parsing");
            addPipelineState(pipelineStates, "intent_parsing", "done", 
                (generalResponseDuration != null) ? generalResponseDuration : intentDuration);
        } else if ("intent".equals(stage) || "candidates_selection".equals(stage) || "dimensions_selected".equals(stage) || 
                   "hitl_needed".equals(stage) || "hitl_required".equals(stage) || "require_human".equals(stage) ||
                   "need_input".equals(stage)) {
            // For intermediate stages, only mark completed stages as done
            addPipelineState(pipelineStates, "intent_parsing", "done", durations.get("intent_parsing"));
            if (!stage.equals("intent")) {
                String hitlStatus = statuses.get("hitl_confirmation");
                if ("skipped".equals(hitlStatus)) {
                    addPipelineState(pipelineStates, "hitl_confirmation", "skipped", null);
                } else {
                    addPipelineState(pipelineStates, "hitl_confirmation", "done", durations.get("hitl_confirmation"));
                }
            }
        }
        responseDataForSave.put("pipeline_states", pipelineStates);
        
        // Note: Embeddings are already removed in EvidencePackBuilder, so no need to clean here
        
        // Get the last assistant turn's question_index and turn_index
        int[] lastTurnIndex = conversationRepository.getLastAssistantTurnIndex(conversationId);
        if (lastTurnIndex != null && lastTurnIndex.length == 2) {
            conversationRepository.updateLastAssistantTurnMeta(conversationId, lastTurnIndex[0], lastTurnIndex[1], responseDataForSave);
        }

        return ResponseEntity.ok(response);
    }
    
    /**
     * Helper method to add a pipeline state with optional duration.
     */
    private void addPipelineState(List<Map<String, Object>> pipelineStates, String stage, String status, Long duration) {
        Map<String, Object> state = new HashMap<>();
        state.put("stage", stage);
        state.put("status", status);
        if (duration != null && duration > 0) {
            state.put("duration", duration);
        }
        pipelineStates.add(state);
    }

    /**
     * Create a new conversation.
     * POST /api/igdd/conversations
     * Body: { "apiKey": "..." }
     * Returns: { "conversationId": "...", "createdAt": "..." }
     */
    @PostMapping("/conversations")
    public ResponseEntity<Map<String, Object>> createConversation(@RequestBody CreateConversationRequest request) {
        String apiKey = (request.apiKey == null || request.apiKey.isBlank()) ? null : request.apiKey.trim();
        
        // Validate API key before creating conversation
        if (apiKey == null || apiKey.isBlank()) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "API key is required");
            return ResponseEntity.badRequest().body(error);
        }
        
        UUID conversationId;
        try {
            // Validate API key by calling GIBD API
            // Store the questionId obtained from validation so it can be reused if conversation is empty
            String validationQuestionId = gibdApiClient.getQuestionId(apiKey);
            // Create conversation first to get the UUID
            conversationId = UUID.randomUUID();
            conversationRepository.createConversation(conversationId, apiKey);
            // Store validation questionId for potential reuse
            questionIdManager.storeValidationQuestionId(conversationId, validationQuestionId);
        } catch (GibdApiClient.GibdApiException e) {
            // API key validation failed (invalid key or service unavailable)
            Map<String, Object> error = new HashMap<>();
            error.put("error", "API key validation failed: " + e.getMessage());
            return ResponseEntity.status(401).body(error);
        } catch (Exception e) {
            // Catch any other unexpected exceptions during validation
            Map<String, Object> error = new HashMap<>();
            error.put("error", "API key validation error: " + e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
        
        Map<String, Object> conversation = conversationRepository.getConversation(conversationId);
        if (conversation == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to create conversation");
            return ResponseEntity.internalServerError().body(error);
        }
        
        Map<String, Object> response = new HashMap<>();
        response.put("conversationId", conversationId.toString());
        response.put("createdAt", conversation.get("createdAt"));
        
        return ResponseEntity.ok(response);
    }

    /**
     * Get all non-deleted conversations for a given API key.
     * GET /api/igdd/conversations?apiKey=...
     */
    @GetMapping("/conversations")
    public ResponseEntity<Map<String, Object>> getConversations(@RequestParam String apiKey) {
        String ak = (apiKey == null || apiKey.isBlank()) ? "default" : apiKey.trim();
        
        List<Map<String, Object>> conversations = conversationRepository.listConversationsByApiKey(ak);
        
        Map<String, Object> response = new HashMap<>();
        response.put("conversations", conversations);
        response.put("count", conversations.size());
        
        return ResponseEntity.ok(response);
    }

    /**
     * Get a specific conversation.
     * GET /api/igdd/conversations/{conversationId}?apiKey=... (optional)
     * If conversation is shareable, can be accessed without API key.
     * If not shareable, API key must match conversation owner.
     * Deleted conversations cannot be accessed even if shareable.
     */
    @GetMapping("/conversations/{conversationId}")
    public ResponseEntity<Map<String, Object>> getConversation(
            @PathVariable String conversationId,
            @RequestParam(required = false) String apiKey) {
        UUID convId;
        try {
            convId = UUID.fromString(conversationId);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Invalid conversation ID format");
            return ResponseEntity.badRequest().body(error);
        }
        
        Map<String, Object> conversation = conversationRepository.getConversation(convId);
        if (conversation == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Conversation not found");
            return ResponseEntity.notFound().build();
        }
        
        // Check if conversation is deleted - deleted conversations cannot be accessed
        Boolean deleted = (Boolean) conversation.get("deleted");
        if (deleted != null && deleted) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Conversation not found");
            return ResponseEntity.notFound().build();
        }
        
        // Check access permission
        Boolean shareable = (Boolean) conversation.get("shareable");
        String conversationApiKey = (String) conversation.get("apiKey");
        
        if (shareable == null || !shareable) {
            // Not shareable - require API key to match owner
            String ak = (apiKey == null || apiKey.isBlank()) ? "default" : apiKey.trim();
            if (conversationApiKey == null || !conversationApiKey.equals(ak)) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Conversation not found");
                return ResponseEntity.notFound().build();
            }
        }
        // If shareable, allow access without API key check
        
        // Remove apiKey from response for security
        conversation.remove("apiKey");
        
        return ResponseEntity.ok(conversation);
    }

    /**
     * Update conversation title.
     * PUT /api/igdd/conversations/{conversationId}/title
     * Body: { "title": "..." }
     */
    @PutMapping("/conversations/{conversationId}/title")
    public ResponseEntity<Map<String, Object>> updateTitle(
            @PathVariable String conversationId,
            @RequestBody UpdateTitleRequest request) {
        
        UUID convId;
        try {
            convId = UUID.fromString(conversationId);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Invalid conversation ID format");
            return ResponseEntity.badRequest().body(error);
        }
        
        String title = request.title != null ? request.title.trim() : null;
        conversationRepository.updateTitle(convId, title);
        
        Map<String, Object> response = new HashMap<>();
        response.put("conversationId", conversationId);
        response.put("title", title);
        response.put("success", true);
        
        return ResponseEntity.ok(response);
    }

    /**
     * Soft-delete a conversation.
     * DELETE /api/igdd/conversations/{conversationId}
     */
    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<Map<String, Object>> deleteConversation(@PathVariable String conversationId) {
        UUID convId;
        try {
            convId = UUID.fromString(conversationId);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Invalid conversation ID format");
            return ResponseEntity.badRequest().body(error);
        }
        
        conversationRepository.deleteConversation(convId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("conversationId", conversationId);
        response.put("success", true);
        
        return ResponseEntity.ok(response);
    }

    /**
     * Get all turns (messages) for a conversation.
     * GET /api/igdd/conversations/{conversationId}/turns?apiKey=... (optional)
     * If conversation is shareable, can be accessed without API key.
     * If not shareable, API key must match conversation owner.
     * Deleted conversations cannot be accessed even if shareable.
     */
    @GetMapping("/conversations/{conversationId}/turns")
    public ResponseEntity<Map<String, Object>> getConversationTurns(
            @PathVariable String conversationId,
            @RequestParam(required = false) String apiKey) {
        
        UUID convId;
        try {
            convId = UUID.fromString(conversationId);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Invalid conversation ID format");
            return ResponseEntity.badRequest().body(error);
        }
        
        // Check conversation existence and access permission
        Map<String, Object> conversation = conversationRepository.getConversation(convId);
        if (conversation == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Conversation not found");
            return ResponseEntity.notFound().build();
        }
        
        // Check if conversation is deleted - deleted conversations cannot be accessed
        Boolean deleted = (Boolean) conversation.get("deleted");
        if (deleted != null && deleted) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Conversation not found");
            return ResponseEntity.notFound().build();
        }
        
        // Check access permission
        Boolean shareable = (Boolean) conversation.get("shareable");
        String conversationApiKey = (String) conversation.get("apiKey");
        
        if (shareable == null || !shareable) {
            // Not shareable - require API key to match owner
            String ak = (apiKey == null || apiKey.isBlank()) ? "default" : apiKey.trim();
            if (conversationApiKey == null || !conversationApiKey.equals(ak)) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Conversation not found");
                return ResponseEntity.notFound().build();
            }
        }
        // If shareable, allow access without API key check
        
        List<Map<String, Object>> turns = conversationRepository.getConversationTurns(convId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("conversationId", conversationId);
        response.put("turns", turns);
        response.put("count", turns.size());
        
        return ResponseEntity.ok(response);
    }

    /**
     * Toggle shareable status for a conversation.
     * PUT /api/igdd/conversations/{conversationId}/shareable?apiKey=...
     * Body: { "shareable": true/false }
     * Requires API key to match conversation's API key (only owner can change sharing).
     */
    @PutMapping("/conversations/{conversationId}/shareable")
    public ResponseEntity<Map<String, Object>> toggleShareable(
            @PathVariable String conversationId,
            @RequestParam String apiKey,
            @RequestBody ToggleShareableRequest request) {
        
        UUID convId;
        try {
            convId = UUID.fromString(conversationId);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Invalid conversation ID format");
            return ResponseEntity.badRequest().body(error);
        }
        
        String ak = (apiKey == null || apiKey.isBlank()) ? "default" : apiKey.trim();
        
        // Verify API key matches conversation owner
        Map<String, Object> conversation = conversationRepository.getConversation(convId);
        if (conversation == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Conversation not found");
            return ResponseEntity.notFound().build();
        }
        
        String conversationApiKey = (String) conversation.get("apiKey");
        if (conversationApiKey == null || !conversationApiKey.equals(ak)) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Unauthorized: API key does not match conversation owner");
            return ResponseEntity.status(403).body(error);
        }
        
        // Check if deleted
        Boolean deleted = (Boolean) conversation.get("deleted");
        if (deleted != null && deleted) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Cannot modify deleted conversation");
            return ResponseEntity.badRequest().body(error);
        }
        
        // Get shareable value from request
        boolean shareable = (request.shareable != null) ? request.shareable : false;
        
        // Update shareable status
        conversationRepository.setShareable(convId, shareable);
        
        Map<String, Object> response = new HashMap<>();
        response.put("conversationId", conversationId);
        response.put("shareable", shareable);
        response.put("success", true);
        
        return ResponseEntity.ok(response);
    }

    /**
     * SSE endpoint for real-time status updates.
     * GET /api/igdd/status?conversationId=...
     * Note: This endpoint still uses conversationId as sessionId for compatibility with StatusEmitter
     */
    @GetMapping(value = "/status", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter status(@RequestParam(required = false) String conversationId) {
        String sid = (conversationId == null || conversationId.isBlank()) ? "default" : conversationId.trim();
        return statusEmitter.createEmitter(sid);
    }

    /**
     * Submit user feedback.
     * POST /api/igdd/feedback
     * Body: { "apiKey": "...", "conversationId": "...", "content": "...", "rating": 1-5, "metadata": {...} }
     * Returns: { "success": true }
     * 
     * Requires: apiKey and conversationId must be provided
     */
    @PostMapping("/feedback")
    public ResponseEntity<Map<String, Object>> submitFeedback(
            @RequestBody FeedbackRequest request,
            @RequestHeader(value = "User-Agent", required = false) String userAgent,
            @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor) {
        
        // Validate required fields
        if (request.apiKey == null || request.apiKey.isBlank()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "API key is required");
            return ResponseEntity.badRequest().body(error);
        }
        
        if (request.conversationId == null || request.conversationId.isBlank()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "Conversation ID is required");
            return ResponseEntity.badRequest().body(error);
        }
        
        try {
            Feedback feedback = new Feedback();
            feedback.setApiKey(request.apiKey.trim());
            
            UUID conversationId;
            try {
                conversationId = UUID.fromString(request.conversationId.trim());
            } catch (IllegalArgumentException e) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("error", "Invalid conversation ID format");
                return ResponseEntity.badRequest().body(error);
            }
            feedback.setConversationId(conversationId);
            
            feedback.setContent(request.content != null ? request.content.trim() : "");
            feedback.setRating(request.rating);
            feedback.setUserAgent(userAgent);
            
            // Extract IP address from X-Forwarded-For header (if behind proxy)
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                String[] ips = forwardedFor.split(",");
                feedback.setIpAddress(ips[0].trim());
            }
            
            feedback.setMetadata(request.metadata != null ? request.metadata : new HashMap<>());
            
            feedbackRepository.save(feedback);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }
}

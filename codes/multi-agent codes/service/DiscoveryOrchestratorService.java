package edu.psu.giscience.igdd.service;

import edu.psu.giscience.igdd.domain.Hyperparameters;
import edu.psu.giscience.igdd.domain.graphrag.*;
import edu.psu.giscience.igdd.domain.intent.GeoIntent;
import edu.psu.giscience.igdd.llm.GibdApiClient;
import edu.psu.giscience.igdd.llm.LlmClientService;
import edu.psu.giscience.igdd.llm.QuestionIdManager;
import edu.psu.giscience.igdd.memory.ConversationMemory;
import edu.psu.giscience.igdd.memory.ConversationMemoryStore;
import edu.psu.giscience.igdd.memory.GeoIntentStore;
import edu.psu.giscience.igdd.memory.PostgresConversationRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.UUID;

/**
 * Orchestrates the end-to-end flow:
 * - message -> (optional boundary detection) -> parse/refine intent -> HITL -> retrieval -> synthesis
 * - continue -> proceed after HITL resolved (frontend auto-continue)
 *
 * Response contract (frontend expects):
 * {
 *   "stage": "...",
 *   "reply": "...",
 *   "intent": {...},
 *   "pending_hitl": {...} | null,
 *   "question_for_users": [...],
 *   "questions_for_user": [...],
 *   "datasets": [...],
 *   "logs": [...]
 *   "next_action": "continue" | ""
 * }
 */
@Service
public class DiscoveryOrchestratorService {

    private final IntentParsingService intentParsingService;
    private final ConversationMemoryStore memoryStore;
    private final GeoIntentStore intentStore;
    private final PostgresConversationRepository conversationRepository;

    private final HitlStateStore hitlStateStore;
    private final HitlPlanner hitlPlanner;
    private final HitlResolver hitlResolver;

    private final GraphRetrievalService retrievalService;
    private final EvidencePackBuilder evidencePackBuilder;
    private final DatasetSelectionService datasetSelectionService;
    private final AnswerSynthesisService synthesisService;
    private final StatusEmitter statusEmitter;
    private final QuestionIdManager questionIdManager;
    private final LlmClientService llmClientService;

    public DiscoveryOrchestratorService(IntentParsingService intentParsingService,
                                        ConversationMemoryStore memoryStore,
                                        GeoIntentStore intentStore,
                                        PostgresConversationRepository conversationRepository,
                                        HitlStateStore hitlStateStore,
                                        HitlPlanner hitlPlanner,
                                        HitlResolver hitlResolver,
                                        GraphRetrievalService retrievalService,
                                        EvidencePackBuilder evidencePackBuilder,
                                        DatasetSelectionService datasetSelectionService,
                                        AnswerSynthesisService synthesisService,
                                        StatusEmitter statusEmitter,
                                        QuestionIdManager questionIdManager,
                                        LlmClientService llmClientService) {
        this.intentParsingService = intentParsingService;
        this.memoryStore = memoryStore;
        this.intentStore = intentStore;
        this.conversationRepository = conversationRepository;
        this.hitlStateStore = hitlStateStore;
        this.hitlPlanner = hitlPlanner;
        this.hitlResolver = hitlResolver;
        this.retrievalService = retrievalService;
        this.evidencePackBuilder = evidencePackBuilder;
        this.datasetSelectionService = datasetSelectionService;
        this.synthesisService = synthesisService;
        this.statusEmitter = statusEmitter;
        this.questionIdManager = questionIdManager;
        this.llmClientService = llmClientService;
    }

    public Map<String, Object> handleQuery(String apiKey,
                                           UUID conversationId,
                                           String query,
                                           String model,
                                           String action,
                                           Boolean useKeywords,
                                           Hyperparameters hyperparameters) {
        String ak = (apiKey == null || apiKey.isBlank()) ? null : apiKey.trim();
        String q = query == null ? "" : query.trim();
        String act = (action == null || action.isBlank()) ? "message" : action.trim().toLowerCase(Locale.ROOT);
        boolean kw = true; // always use keywords (toggle removed from UI)
        Hyperparameters hyperparams = (hyperparameters != null) ? hyperparameters : Hyperparameters.defaults();

        List<PipelineLogEvent> logs = new ArrayList<>();
        logs.add(log("request", "received action=" + act + ", query_len=" + q.length(), conversationId));

        // Check API key first
        if (ak == null || ak.isBlank()) {
            String errorMsg = "API key is required. Please enter your GIBD API key in the configuration panel.";
            logs.add(log("error", errorMsg, conversationId));
            return response("error", errorMsg, null, null, Collections.emptyList(), logs, "");
        }

        // Check conversationId
        if (conversationId == null) {
            String errorMsg = "Conversation ID is required.";
            logs.add(log("error", errorMsg, null));
            return response("error", errorMsg, null, null, Collections.emptyList(), logs, "");
        }

        GeoIntent prevIntent = intentStore.get(conversationId);
        PendingHitl pending = hitlStateStore.get(conversationId);
        
        // Get or create question_id (associated with conversation_id)
        // Check if conversation has existing turns to determine if validation questionId can be reused
        int checkQuestionIndex = conversationRepository.getCurrentQuestionIndex(conversationId);
        boolean hasExistingTurns = checkQuestionIndex > 0;
        
        String questionId;
        try {
            String intentHash = prevIntent != null ? QuestionIdManager.hashIntent(prevIntent) : null;
            questionId = questionIdManager.getOrCreateQuestionId(ak, conversationId, intentHash, hasExistingTurns);
        } catch (GibdApiClient.GibdApiException e) {
            String errorMsg = "Error getting question_id: " + e.getMessage();
            logs.add(log("error", errorMsg));
            return response("error", errorMsg, prevIntent, pending, Collections.emptyList(), logs, "");
        }

        // CONTINUE: proceed without new user text
        if ("continue".equals(act)) {
            logs.add(log("continue", "continuing pipeline"));

            if (pending != null) {
                logs.add(log("hitl", "still pending HITL, cannot continue"));
                return response(
                        "hitl_needed",
                        "Please answer the confirmation question to proceed.",
                        prevIntent,
                        pending,
                        Collections.emptyList(),
                        logs,
                        ""
                );
            }

            if (prevIntent == null || !hasAnySemantic(prevIntent)) {
                String msg = guidanceMessage();
                logs.add(log("need_input", "no intent found; asking user for any dimension", conversationId));
                return response(
                        "need_input",
                        msg,
                        prevIntent,
                        null,
                        Collections.emptyList(),
                        logs,
                        ""
                );
            }

            // Retrieval + synthesis
            // Note: For continue action, entity_matching should already be done
            // Immediately activate spatial/temporal filter before continuing
            String sessionKey = conversationId.toString();
            statusEmitter.emitStatus(sessionKey, "spatial_temporal_filter", "active");
            System.out.println("[Spatial/Temporal Filter] Starting spatial/temporal/source filtering (continue action)");
            logs.add(log("spatial_temporal_filter", "starting spatial/temporal/source filtering (continue action)", conversationId));
            
            try {
                // Use the conversationId from the beginning of the method
                String intentHash = QuestionIdManager.hashIntent(prevIntent);
                // Use existing hasExistingTurns from above
                String qid = questionIdManager.getOrCreateQuestionId(ak, conversationId, intentHash, hasExistingTurns);
                int currentQuestionIndex = conversationRepository.getCurrentQuestionIndex(conversationId);
                if (currentQuestionIndex == 0) {
                    currentQuestionIndex = 1; // First question
                }
                return runRetrievalAndSynthesis(ak, conversationId, "", prevIntent, model, kw, hyperparams, logs, qid, currentQuestionIndex);
            } catch (GibdApiClient.GibdApiException e) {
                String errorMsg = "Error getting question_id: " + e.getMessage();
                logs.add(log("error", errorMsg, conversationId));
                return response("error", errorMsg, prevIntent, null, Collections.emptyList(), logs, "");
            }
        }

        // MESSAGE: user sent text
        if (q.isEmpty()) {
            logs.add(log("empty", "empty message; no-op", conversationId));
            String msg = guidanceMessage();
            return response("need_input", msg, prevIntent, pending, Collections.emptyList(), logs, "");
        }

        // Intent active status is emitted in DiscoveryController.query() 
        // immediately after user input is received, ensuring timing starts from user input

        // Get or create memory for this conversation
        ConversationMemory mem = memoryStore.getOrCreate(conversationId);
        
        // Determine question_index and question_id
        // IMPORTANT: We need to determine the final question_id before adding the user message
        // to ensure all turns in the same question use the same question_id
        // Note: questionId is already declared at the beginning of handleQuery method
        int questionIndex;
        boolean isNewQuestion = false;
        
        // If we have previous intent and no pending, detect whether this is a new conversation
        if (prevIntent != null && pending == null) {
            try {
                String intentHash = QuestionIdManager.hashIntent(prevIntent);
                // Use existing hasExistingTurns from above
                String qid = questionIdManager.getOrCreateQuestionId(ak, conversationId, intentHash, hasExistingTurns);
                boolean isNew = intentParsingService.shouldStartNewConversation(prevIntent, mem, q, model, ak, qid);
                if (isNew) {
                    logs.add(log("intent_reset", "detected new conversation; clearing intent/pending/memory"));
                    intentStore.put(conversationId, null);
                    hitlStateStore.clear(conversationId);
                    // Clear question_id for the old conversation before resetting
                    questionIdManager.clearQuestionId(conversationId);
                    memoryStore.clearMemory(conversationId);
                    prevIntent = null;
                    pending = null;
                    isNewQuestion = true;
                    questionIndex = conversationRepository.getNextQuestionIndex(conversationId);
                    // For new question, we'll determine question_id after parsing intent
                } else {
                    // Use current question_index
                    questionIndex = conversationRepository.getCurrentQuestionIndex(conversationId);
                    if (questionIndex == 0) {
                        questionIndex = 1; // First question
                        isNewQuestion = true;
                    } else {
                        // IMPORTANT: If current question has already returned results, force a new question
                        boolean currentQuestionCompleted = conversationRepository.hasQuestionCompleted(conversationId, questionIndex);
                        if (currentQuestionCompleted) {
                            logs.add(log("question_completed", "current question_index=" + questionIndex + " has results, starting new question"));
                            isNewQuestion = true;
                            questionIndex = conversationRepository.getNextQuestionIndex(conversationId);
                        } else {
                            // Use existing question_id for this question_index
                            // Get question_id from the first turn of this question_index
                            String existingQuestionId = conversationRepository.getQuestionIdForQuestionIndex(conversationId, questionIndex);
                            if (existingQuestionId != null) {
                                questionId = existingQuestionId;
                            }
                        }
                    }
                }
            } catch (GibdApiClient.GibdApiException e) {
                String errorMsg = "Error getting question_id: " + e.getMessage();
                logs.add(log("error", errorMsg));
                return response("error", errorMsg, prevIntent, pending, Collections.emptyList(), logs, "");
            }
        } else {
            // No previous intent or has pending - determine if new question
            int currentQuestionIndex = conversationRepository.getCurrentQuestionIndex(conversationId);
            if (currentQuestionIndex == 0) {
                questionIndex = 1; // First question
                isNewQuestion = true;
            } else {
                questionIndex = currentQuestionIndex;
                // IMPORTANT: If current question has already returned results, force a new question
                boolean currentQuestionCompleted = conversationRepository.hasQuestionCompleted(conversationId, questionIndex);
                if (currentQuestionCompleted) {
                    logs.add(log("question_completed", "current question_index=" + questionIndex + " has results, starting new question"));
                    isNewQuestion = true;
                    questionIndex = conversationRepository.getNextQuestionIndex(conversationId);
                } else {
                    // Use existing question_id for this question_index
                    String existingQuestionId = conversationRepository.getQuestionIdForQuestionIndex(conversationId, questionIndex);
                    if (existingQuestionId != null) {
                        questionId = existingQuestionId;
                    }
                }
            }
        }
        
        // Get next turn_index for this question
        int turnIndex = conversationRepository.getNextTurnIndex(conversationId, questionIndex);

        // IMPORTANT: If the current question has already completed (returned results),
        // we should NOT process HITL selections or dimension selections for it.
        // Instead, treat any input as a new question and start from intent parsing.
        // Double-check: even if isNewQuestion is false, verify the question hasn't completed
        boolean currentQuestionCompleted = false;
        if (!isNewQuestion) {
            currentQuestionCompleted = conversationRepository.hasQuestionCompleted(conversationId, questionIndex);
            if (currentQuestionCompleted) {
                // Question is completed - clear ALL state and force new question from scratch
                logs.add(log("question_completed_force_new", "question_index=" + questionIndex + " has results, forcing new question and clearing all state"));
                
                // Clear all state to start fresh from intent parsing
                if (pending != null) {
                    hitlStateStore.clear(conversationId);
                    pending = null;
                }
                intentStore.put(conversationId, null);  // Clear previous intent
                memoryStore.clearMemory(conversationId);  // Clear conversation memory
                questionIdManager.clearQuestionId(conversationId);  // Clear question_id mapping
                prevIntent = null;  // Clear local prevIntent reference
                
                // Force new question with new question_index
                isNewQuestion = true;
                questionIndex = conversationRepository.getNextQuestionIndex(conversationId);
                turnIndex = conversationRepository.getNextTurnIndex(conversationId, questionIndex);
                
                // Reset questionId to null so it will be created fresh from new intent parsing
                questionId = null;
            }
        }

        // Check for greeting/introduction or example requests first (regardless of isNewQuestion)
        // This should be checked before any intent parsing or data discovery logic
        if (pending == null && !q.matches("(?i)^((Topic|Format|License|Organization|Source):(.+?)(;\\s*)?)+$")) {
            // Use LLM to classify question type first
            IntentParsingService.QuestionTypeResult questionType = intentParsingService.classifyQuestionType(q, mem, model, ak, questionId);
            
            // If classified as greeting/introduction, return introduction response regardless of confidence
            if (questionType != null && questionType.isGreetingOrIntroduction()) {
                // Return friendly introduction response
                statusEmitter.emitStatus(conversationId.toString(), "intent_parsing", "done");
                emitPipelineProgressMessage(conversationId, questionId, questionIndex, "intent_parsing", "done", 
                                          "Ready to answer.", null);
                
                String introductionResponse = "Hello! I'm an **Intelligent Geospatial Data Discovery Assistant (IGDD)**. " +
                    "I help you discover and find geospatial datasets based on your needs. " +
                    "You can ask me about datasets using various dimensions such as:\n\n" +
                    "- **Topic**: What kind of data you're looking for (e.g., land cover, temperature, precipitation)\n" +
                    "- **Format**: Data format preferences (e.g., NetCDF, Shapefile)\n" +
                    "- **License**: Licensing requirements (e.g., CC-BY-4.0, Public Domain)\n" +
                    "- **Organization**: Data provider (e.g., NASA, USGS, ESA)\n" +
                    "- **Space**: Geographic location (e.g., Pennsylvania, USA, or bounding box coordinates)\n" +
                    "- **Time**: Temporal range (e.g., 2018-01-01 to 2020-12-31, or year: 2020)\n\n" +
                    "Feel free to ask me anything about finding geospatial datasets!";
                
                int userTurnIndex = conversationRepository.getNextTurnIndex(conversationId, questionIndex);
                memoryStore.addUserMessage(conversationId, questionId, questionIndex, userTurnIndex, q, Map.of("stage", "greeting"));
                
                int assistantTurnIndex = conversationRepository.getNextTurnIndex(conversationId, questionIndex);
                memoryStore.addAgentMessage(conversationId, questionId, questionIndex, assistantTurnIndex, introductionResponse, "general_assistant", Map.of("stage", "introduction_response"));
                
                return response(
                        "general_response",
                        introductionResponse,
                        null,
                        null,
                        Collections.emptyList(),
                        logs,
                        ""
                );
            }
            
            // If classified as example request, return examples response regardless of confidence
            if (questionType != null && questionType.isExampleRequest()) {
                // Return examples response with first 5 quick examples
                statusEmitter.emitStatus(conversationId.toString(), "intent_parsing", "done");
                emitPipelineProgressMessage(conversationId, questionId, questionIndex, "intent_parsing", "done", 
                                          "Ready to answer.", null);
                
                String examplesResponse = "Here are some example queries you can try:\n\n" +
                    "1. I need land cover datasets for Pennsylvania between 2018 and 2020.\n" +
                    "2. Find Sentinel-2 imagery datasets for Centre County, Pennsylvania in June 2019.\n" +
                    "3. I'm looking for daily temperature (tmax) datasets for New York State from 1990 to 2020.\n" +
                    "4. Discover road network datasets for California that are available as Shapefile or GeoPackage.\n" +
                    "5. Find population density raster datasets for the United States around 2010–2020.\n\n" +
                    "You can click on any of these examples in the left sidebar to fill the input box, or modify them to match your specific needs!";
                
                int userTurnIndex = conversationRepository.getNextTurnIndex(conversationId, questionIndex);
                memoryStore.addUserMessage(conversationId, questionId, questionIndex, userTurnIndex, q, Map.of("stage", "example_request"));
                
                int assistantTurnIndex = conversationRepository.getNextTurnIndex(conversationId, questionIndex);
                memoryStore.addAgentMessage(conversationId, questionId, questionIndex, assistantTurnIndex, examplesResponse, "general_assistant", Map.of("stage", "examples_response"));
                
                return response(
                        "general_response",
                        examplesResponse,
                        null,
                        null,
                        Collections.emptyList(),
                        logs,
                        ""
                );
            }
            
            // Check if the question is related to data discovery (for non-greeting/non-example questions)
            // This should be checked regardless of isNewQuestion to handle general questions properly
            try {
                boolean isDataDiscoveryRelated = intentParsingService.isDataDiscoveryRelated(q, mem, model, ak, questionId);
                if (!isDataDiscoveryRelated) {
                    // Not related to data discovery - answer directly using LLM
                    // Intent parsing done (since we determined it's not data discovery related)
                    statusEmitter.emitStatus(conversationId.toString(), "intent_parsing", "done");
                    // Mark pipeline progress as done without showing the general question message
                    // Use a simple done message to mark pipeline as complete
                    emitPipelineProgressMessage(conversationId, questionId, questionIndex, "intent_parsing", "done", 
                                              "Ready to answer.", null);
                    System.out.println("[General] Answering with LLM (not data discovery)");
                    logs.add(log("general_question", "Question is not related to data discovery, answering directly with LLM", conversationId));
                    // General response is not part of the main pipeline, handled separately
                    
                    // Prepare prompt for general question answering
                    String conversationHistory = mem == null ? "" : mem.formatRecentAsText(10);
                    String prompt = "You are a helpful assistant. Please answer the user's question in a clear and concise manner.\n\n";
                    if (!conversationHistory.isEmpty()) {
                        prompt += "Recent conversation history:\n" + conversationHistory + "\n\n";
                    }
                    prompt += "User's question: " + q + "\n\nPlease provide a helpful answer.";
                    
                    // Get question ID for LLM call
                    String tempQuestionId = questionId != null ? questionId : questionIdManager.getOrCreateQuestionId(ak, conversationId, QuestionIdManager.hashIntent(null));
                    
                    String llmResponse = llmClientService.askPlain(prompt, model, ak, tempQuestionId);
                    
                    // Add user message and assistant response to conversation memory
                    int userTurnIndex = conversationRepository.getNextTurnIndex(conversationId, questionIndex);
                    memoryStore.addUserMessage(conversationId, tempQuestionId, questionIndex, userTurnIndex, q, Map.of("stage", "general_question"));
                    
                    int assistantTurnIndex = conversationRepository.getNextTurnIndex(conversationId, questionIndex);
                    memoryStore.addAgentMessage(conversationId, tempQuestionId, questionIndex, assistantTurnIndex, llmResponse, "general_assistant", Map.of("stage", "general_response"));
                    
                    // Return response without entering any agent
                    return response(
                            "general_response",
                            llmResponse,
                            null,
                            null,
                            Collections.emptyList(),
                            logs,
                            ""
                    );
                }
            } catch (GibdApiClient.GibdApiException e) {
                // If API error occurs during data discovery check or LLM call, fall through to normal intent parsing
                String errorMsg = "Error checking data discovery relevance or answering general question: " + e.getMessage();
                logs.add(log("error", errorMsg, conversationId));
                // Fall through to normal intent parsing flow
            }
        }

        // For new questions, parse intent first to determine question_id
        if (isNewQuestion && pending == null) {
            // Pre-parse intent to get the correct question_id before adding user message
            // This only applies to new questions that are not dimension selections
            if (!q.matches("(?i)^((Topic|Format|License|Organization|Source):(.+?)(;\\s*)?)+$")) {
                try {
                    boolean useExpand = (hyperparams.useEmbeddingSearch != null) ? hyperparams.useEmbeddingSearch : true;
                    GeoIntent preParsedIntent;
                    // If prevIntent is null (e.g., after clearing state for completed question), parse initial intent
                    // In text search mode (useExpand=false), always parse initial intent instead of refining
                    if (prevIntent == null || currentQuestionCompleted || !useExpand) {
                        // For new questions, after clearing completed question state, or in text search mode, always parse initial intent
                        System.out.println("----------------conversation id: " + conversationId + "----------------");
                        System.out.println("[Intent] Parsing initial intent" + (!useExpand ? " (text search mode)" : ""));
                        preParsedIntent = intentParsingService.parseInitialIntent(q, mem, model, useExpand, ak, questionId);
                        // IMPORTANT: Only create new questionId if this is a truly new question (isNewQuestion=true)
                        // Do NOT create new questionId if expand changed the intentHash - expand is part of the same question
                        // Only create new questionId when starting a new questionIndex
                        if (isNewQuestion && questionId == null) {
                            // Only create new questionId for new questions when questionId is not already set
                            String intentHash = QuestionIdManager.hashIntent(preParsedIntent);
                            questionId = questionIdManager.getOrCreateQuestionId(ak, conversationId, intentHash);
                        }
                        // If questionId already exists (from previous turn), continue using it even if intentHash changed
                    } else {
                        // Refining intent - continue using existing questionId (same question)
                        System.out.println("[Intent] Refining intent (pre-parse) - using existing question_id");
                        preParsedIntent = intentParsingService.refineIntent(prevIntent, q, mem, model, useExpand, ak, questionId);
                        // Do NOT create new questionId here - refinement is part of the same question
                        // questionId remains unchanged
                    }
                } catch (Exception e) {
                    // If pre-parsing fails, create a new questionId
                    // The actual parsing will happen later and may update it
                    logs.add(log("warning", "Pre-parsing intent failed, will create new question_id: " + e.getMessage()));
                    try {
                        // Create a temporary questionId based on the query text
                        String tempHash = QuestionIdManager.hashIntent(null);  // Use null to get a new hash
                        questionId = questionIdManager.getOrCreateQuestionId(ak, conversationId, tempHash);
                    } catch (Exception e2) {
                        logs.add(log("error", "Failed to create question_id: " + e2.getMessage()));
                    }
                }
            } else {
                // For dimension selections, if questionId is null, create a new one
                if (questionId == null) {
                    try {
                        String tempHash = QuestionIdManager.hashIntent(null);
                        questionId = questionIdManager.getOrCreateQuestionId(ak, conversationId, tempHash);
                    } catch (Exception e) {
                        logs.add(log("error", "Failed to create question_id for dimension selection: " + e.getMessage()));
                    }
                }
            }
        }

        // Ensure questionId is not null before adding user message
        // If still null (shouldn't happen, but safety check), create a temporary one
        if (questionId == null) {
            try {
                String tempHash = QuestionIdManager.hashIntent(null);
                questionId = questionIdManager.getOrCreateQuestionId(ak, conversationId, tempHash);
                logs.add(log("warning", "questionId was null, created temporary questionId: " + questionId));
            } catch (Exception e) {
                logs.add(log("error", "Failed to create questionId: " + e.getMessage()));
                // Use a fallback questionId
                questionId = "temp-" + System.currentTimeMillis();
            }
        }
        
        // Add user message to memory (and PostgreSQL best-effort)
        // Use the final question_id determined above
        memoryStore.addUserMessage(conversationId, questionId, questionIndex, turnIndex, q, Map.of("action", act));
        
        // Emit request received pipeline message (only for message actions, not dimension selections)
        // Check if this is a dimension selection (format: "Dimension:1,2" or "Dimension:continue")
        boolean isDimSelection = q.matches("(?i)^((Topic|Format|License|Organization|Source):(.+?)(;\\s*)?)+$");
        if (!isDimSelection && "message".equals(act)) {
            String requestMessage = "I've received your request. Let me start processing it...";
            emitPipelineProgressMessage(conversationId, questionId, questionIndex, "request_received", "done", requestMessage, null);
            
            // Emit intent parsing active pipeline message
            emitPipelineProgressMessage(conversationId, questionId, questionIndex, "intent_parsing", "active", 
                                      "I'm analyzing your request to understand what you're looking for...", null);
        }

        // CASE A: Pending HITL exists -> treat user message as HITL answer
        // Skip if question is completed (we already cleared pending above)
        if (pending != null && !currentQuestionCompleted) {
            statusEmitter.emitStatus(conversationId.toString(), "hitl_confirmation", "active");
            logs.add(log("hitl_confirmation", "applying HITL answer to slot=" + pending.slot(), conversationId));
            GeoIntent updated = hitlResolver.applyUserAnswer(prevIntent, pending, q, ak, questionId);

            // Ensure parsing rules still hold
            intentParsingService.enforceRules(updated);

            // After HITL confirmation, if user provided text input (not candidate selection),
            // loop call LLM to re-parse and get confidence until confidence >= threshold
            // This ensures LLM can properly evaluate and score the user's refined input
            boolean needsReparse = needsReparseAfterHitl(updated, pending.slot());
            if (needsReparse) {
                double confidenceThreshold = (hyperparams.confidenceThreshold != null) ? hyperparams.confidenceThreshold : 0.5;
                int maxIterations = 3;  // Maximum iterations to avoid infinite loop
                int iteration = 0;
                
                while (iteration < maxIterations && needsReparseAfterHitl(updated, pending.slot())) {
                    iteration++;
                    System.out.println("[HITL] Re-parsing intent with LLM (iteration " + iteration + ") to get confidence score");
                    logs.add(log("hitl_confirmation", "re-parsing intent with LLM (iteration " + iteration + ")", conversationId));
                    
                    // Use refineIntent to re-parse the user's input and get updated confidence
                    // Pass the user's HITL answer as the new message to refine the intent
                    boolean useExpand = (hyperparams.useEmbeddingSearch != null) ? hyperparams.useEmbeddingSearch : true;
                    updated = intentParsingService.refineIntent(updated, q, mem, model, useExpand, ak, questionId);
                    
                    // Ensure parsing rules still hold
                    intentParsingService.enforceRules(updated);
                    
                    // Check if confidence is now above threshold
                    if (!needsReparseAfterHitl(updated, pending.slot(), confidenceThreshold)) {
                        System.out.println("[HITL] Confidence reached threshold after " + iteration + " iteration(s)");
                        logs.add(log("hitl_confirmation", "confidence reached threshold after " + iteration + " iteration(s)", conversationId));
                        break;
                    }
                }
                
                if (iteration >= maxIterations) {
                    System.out.println("[HITL] Reached maximum iterations (" + maxIterations + "), proceeding with current confidence");
                    logs.add(log("hitl_confirmation", "reached maximum iterations, proceeding", conversationId));
                }
            }

            // Save updated intent
            intentStore.put(conversationId, updated);
            
            // Check if auto mode - in auto mode, don't send candidates via SSE after HITL confirmation
            // User's input is already in the updated intent, we'll auto-select candidates matching similarity threshold
            boolean autoExecute = (hyperparams.autoExecute != null) ? hyperparams.autoExecute : true;
            
            // Only get and send candidates if NOT in auto mode
            // In auto mode, we'll auto-select candidates later and don't need to show them to user
            if (!autoExecute) {
                // Get candidates for all dimensions to display in parsed intent (non-auto mode only)
                // Use getAllCandidatesForDisplay to get candidates regardless of whether they are already selected
                Map<String, List<CandidateEntity>> allCandidates = hitlPlanner.getAllCandidatesForDisplay(updated, kw, hyperparams, ak, questionId);
                
                // Create intent wrapper with candidates for frontend display
                Map<String, Object> intentWithCandidates = new HashMap<>();
                try {
                    // Serialize intent to Map
                    String intentJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(updated);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> intentMap = new com.fasterxml.jackson.databind.ObjectMapper().readValue(intentJson, Map.class);
                    intentWithCandidates.putAll(intentMap);
                } catch (Exception e) {
                    // Fallback: just use intent as-is
                    intentWithCandidates.put("intent", updated);
                }
                
                // Add candidates map to intent wrapper
                Map<String, Object> candidatesMap = new HashMap<>();
                for (Map.Entry<String, List<CandidateEntity>> entry : allCandidates.entrySet()) {
                    List<Map<String, Object>> candList = new ArrayList<>();
                    for (CandidateEntity c : entry.getValue()) {
                        Map<String, Object> candMap = new HashMap<>();
                        candMap.put("nodeId", c.nodeId());
                        candMap.put("name", c.name());
                        candMap.put("label", c.label());
                        candMap.put("score", c.score());
                        candList.add(candMap);
                    }
                    candidatesMap.put(entry.getKey(), candList);
                }
                intentWithCandidates.put("dimension_candidates", candidatesMap);
                
                // Emit intent with candidates in real-time via SSE (non-auto mode only)
                statusEmitter.emitIntent(conversationId.toString(), intentWithCandidates);
            } else {
                // In auto mode, just emit intent without candidates
                // Candidates will be auto-selected later based on similarity threshold
                statusEmitter.emitIntent(conversationId.toString(), updated);
            }
            // Update conversation title from intent
            updateConversationTitleFromIntent(conversationId, updated);

            // Determine next HITL if needed
            // In auto mode, after HITL confirmation, we should NOT show candidates to user
            // Instead, we let autoSetCandidates handle it based on LLM's confidence judgment
            // If confidence is low (user's second input is still ambiguous), no candidates will be set
            // but we won't show candidates to user either (next = null ensures this)
            PendingHitl next = null;
            if (!autoExecute) {
                // Only check for next HITL in non-auto mode
                // In auto mode, we skip nextHitl to prevent showing candidates to user
                next = hitlPlanner.nextHitl(updated, kw, hyperparams, ak, questionId);
            }
            hitlStateStore.put(conversationId, next);

            if (next != null) {
                logs.add(log("hitl_needed", "next HITL required slot=" + next.slot()));
                // IMPORTANT: when a dimension is ambiguous, only ask about THAT dimension
                // (do not add other dimensions into the prompt).
                String reply = (next.question() == null || next.question().isBlank())
                        ? "Please confirm the detected constraint."
                        : next.question();
                int agentTurnIndex = conversationRepository.getNextTurnIndex(conversationId, questionIndex);
                memoryStore.addAgentMessage(conversationId, questionId, questionIndex, agentTurnIndex, reply, "orchestrator", Map.of("stage", "hitl_needed"));
                return response(
                        "hitl_needed",
                        reply,
                        updated,
                        next,
                        Collections.emptyList(),
                        logs,
                        ""
                );
            } else {
                // HITL done → Entity matching active (immediately after HITL done)
                statusEmitter.emitStatus(conversationId.toString(), "hitl_confirmation", "done");
                logs.add(log("hitl_confirmation", "HITL resolved, proceeding to entity matching", conversationId));
                
                // Entity matching stage: find candidate entities after HITL resolved
                statusEmitter.emitStatus(conversationId.toString(), "entity_matching", "active");
                System.out.println("[Entity Matching] Finding candidate entities");
                logs.add(log("entity_matching", "finding candidate entities", conversationId));
                
                // In auto-execute mode, automatically set candidates first (after HITL resolved)
                // This ensures that in auto mode, candidates are auto-selected based on similarity threshold
                // User's input (from HITL confirmation) is already in the updated intent, use it as benchmark
                // autoExecute variable is already defined above (line 437), reuse it
                if (autoExecute) {
                    // Auto-set candidates for all dimensions (score > threshold)
                    // This uses the user's input from HITL confirmation as the benchmark for similarity matching
                    // The updated intent contains the user's refined input, which will be used to match candidates
                    // Note: autoSetCandidates will only set candidates if confidence >= threshold (0.5 by default)
                    // If confidence is low (user's second input is still ambiguous), LLM's judgment is respected
                    // and no candidates will be set, but we won't show candidates to user (next = null ensures this)
                    hitlPlanner.autoSetCandidates(updated, kw, hyperparams, ak, questionId);
                    intentStore.put(conversationId, updated);  // Persist updated intent with candidates
                    // Update conversation title from intent
                    updateConversationTitleFromIntent(conversationId, updated);
                }
                
                // Entity matching done → Spatial/Temporal filter active (immediately after entity matching done)
                statusEmitter.emitStatus(conversationId.toString(), "entity_matching", "done");
                System.out.println("[Entity Matching] Entity matching completed");
                logs.add(log("entity_matching", "entity matching completed", conversationId));
                
                // Immediately activate spatial/temporal filter before continuing
                statusEmitter.emitStatus(conversationId.toString(), "spatial_temporal_filter", "active");
                System.out.println("[Spatial/Temporal Filter] Starting spatial/temporal/source filtering");
                logs.add(log("spatial_temporal_filter", "starting spatial/temporal/source filtering", conversationId));
                
                // Continue to retrieval immediately (no need to return response for auto-continue)
                return runRetrievalAndSynthesis(ak, conversationId, q, updated, model, kw, hyperparams, logs, questionId, questionIndex);
            }
        }

        // CASE B: No pending -> parse/refine intent or handle dimension selection
        GeoIntent intent;
        
        // Check if this is a dimension selection response (format: "Dimension:1,3,5" or "Dimension:continue")
        // Also support multiple selections separated by semicolon: "Topic:1,2;Format:continue"
        // IMPORTANT: If the current question has completed, do NOT process dimension selections for it
        if (!currentQuestionCompleted && q.matches("(?i)^((Topic|Format|License|Organization|Source):(.+?)(;\\s*)?)+$")) {
            intent = prevIntent != null ? prevIntent : new GeoIntent();
            
            // Split by semicolon to handle multiple dimension selections
            String[] selections = q.split(";");
            boolean intentUpdated = false;
            for (String sel : selections) {
                sel = sel.trim();
                if (sel.isEmpty()) continue;
                
                if (sel.matches("(?i)^(Topic|Format|License|Organization|Source):(.+)$")) {
                    String[] parts = sel.split(":", 2);
                    String dimension = parts[0].trim();
                    String selection = parts.length > 1 ? parts[1].trim() : "";
                    
                    logs.add(log("dimension_selection", "processing selection for " + dimension + ": " + selection, conversationId));
                    
                    // Get candidates for this dimension
                    Map<String, List<CandidateEntity>> candidatesMap = hitlPlanner.getCandidatesForSelection(intent, kw, hyperparams, ak, questionId);
                    List<CandidateEntity> candidates = candidatesMap.get(dimension);
                    
                    if (candidates != null && !candidates.isEmpty()) {
                        if ("continue".equalsIgnoreCase(selection) || selection.isEmpty()) {
                            // Use all candidates
                            List<String> nodeIds = candidates.stream()
                                .map(c -> c.nodeId())
                                .filter(java.util.Objects::nonNull)
                                .toList();
                            List<String> names = candidates.stream()
                                .map(c -> c.name())
                                .filter(java.util.Objects::nonNull)
                                .filter(n -> !n.isBlank())
                                .toList();
                            applyDimensionSelection(intent, dimension, nodeIds, names);
                            intentUpdated = true;
                        } else {
                            // Parse selection indices (e.g., "1,3,5")
                            List<Integer> indices = parseSelectionIndices(selection);
                            List<String> nodeIds = new ArrayList<>();
                            List<String> names = new ArrayList<>();
                            for (Integer idx : indices) {
                                if (idx > 0 && idx <= candidates.size()) {
                                    CandidateEntity candidate = candidates.get(idx - 1);
                                    if (candidate.nodeId() != null) {
                                        nodeIds.add(candidate.nodeId());
                                    }
                                    if (candidate.name() != null && !candidate.name().isBlank()) {
                                        names.add(candidate.name());
                                    }
                                }
                            }
                            if (!nodeIds.isEmpty()) {
                                applyDimensionSelection(intent, dimension, nodeIds, names);
                                intentUpdated = true;
                            }
                        }
                    }
                }
            }
            
            // Emit intent update after all dimension selections are processed
            if (intentUpdated) {
                intentStore.put(conversationId, intent);
                
                // Get candidates for all dimensions to display in parsed intent
                // Use getAllCandidatesForDisplay to get candidates regardless of whether they are already selected
                Map<String, List<CandidateEntity>> allCandidates = hitlPlanner.getAllCandidatesForDisplay(intent, kw, hyperparams, ak, questionId);
                
                // Create intent wrapper with candidates for frontend display
                Map<String, Object> intentWithCandidates = new HashMap<>();
                try {
                    // Serialize intent to Map
                    String intentJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(intent);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> intentMap = new com.fasterxml.jackson.databind.ObjectMapper().readValue(intentJson, Map.class);
                    intentWithCandidates.putAll(intentMap);
                } catch (Exception e) {
                    // Fallback: just use intent as-is
                    intentWithCandidates.put("intent", intent);
                }
                
                // Add candidates map to intent wrapper
                Map<String, Object> candidatesMap = new HashMap<>();
                for (Map.Entry<String, List<CandidateEntity>> entry : allCandidates.entrySet()) {
                    List<Map<String, Object>> candList = new ArrayList<>();
                    for (CandidateEntity c : entry.getValue()) {
                        Map<String, Object> candMap = new HashMap<>();
                        candMap.put("nodeId", c.nodeId());
                        candMap.put("name", c.name());
                        candMap.put("label", c.label());
                        candMap.put("score", c.score());
                        candList.add(candMap);
                    }
                    candidatesMap.put(entry.getKey(), candList);
                }
                intentWithCandidates.put("dimension_candidates", candidatesMap);
                
                statusEmitter.emitIntent(conversationId.toString(), intentWithCandidates);
            }
            // For dimension selection, intent parsing is already done (no parsing needed)
            // Mark intent parsing as done and proceed directly to entity matching
            statusEmitter.emitStatus(conversationId.toString(), "intent_parsing", "done");
            // Emit intent parsing done pipeline message
            emitPipelineProgressMessage(conversationId, questionId, questionIndex, "intent_parsing", "done", 
                                      "I've recorded your candidate selections. The intent remains unchanged from the initial parsing.", null);
            
            // Persist intent
            intentStore.put(conversationId, intent);
            // Don't emit intent again here - it was already emitted above with candidates if intentUpdated
            // Only emit if intent was not updated (shouldn't happen, but for safety)
            if (!intentUpdated) {
                // Get candidates for all dimensions to display in parsed intent
                Map<String, List<CandidateEntity>> allCandidates = hitlPlanner.getAllCandidatesForDisplay(intent, kw, hyperparams, ak, questionId);
                
                // Create intent wrapper with candidates for frontend display
                Map<String, Object> intentWithCandidates = new HashMap<>();
                try {
                    // Serialize intent to Map
                    String intentJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(intent);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> intentMap = new com.fasterxml.jackson.databind.ObjectMapper().readValue(intentJson, Map.class);
                    intentWithCandidates.putAll(intentMap);
                } catch (Exception e) {
                    // Fallback: just use intent as-is
                    intentWithCandidates.put("intent", intent);
                }
                
                // Add candidates map to intent wrapper
                Map<String, Object> candidatesMap = new HashMap<>();
                for (Map.Entry<String, List<CandidateEntity>> entry : allCandidates.entrySet()) {
                    List<Map<String, Object>> candList = new ArrayList<>();
                    for (CandidateEntity c : entry.getValue()) {
                        Map<String, Object> candMap = new HashMap<>();
                        candMap.put("nodeId", c.nodeId());
                        candMap.put("name", c.name());
                        candMap.put("label", c.label());
                        candMap.put("score", c.score());
                        candList.add(candMap);
                    }
                    candidatesMap.put(entry.getKey(), candList);
                }
                intentWithCandidates.put("dimension_candidates", candidatesMap);
                
                statusEmitter.emitIntent(conversationId.toString(), intentWithCandidates);
            }
            // Update conversation title from intent
            updateConversationTitleFromIntent(conversationId, intent);
            
            // User has completed candidate selection - entity matching is done
            // Entity matching done → Spatial/Temporal filter active (immediately after entity matching done)
            statusEmitter.emitStatus(conversationId.toString(), "entity_matching", "done");
            System.out.println("[Entity Matching] User has selected candidates, proceeding to spatial/temporal filter");
            logs.add(log("entity_matching", "user has selected candidates, proceeding to spatial/temporal filter", conversationId));
            
            // Set linker's retrievalService BEFORE calling HitlPlanner methods to ensure cache works
            setupLinkerForHitlPlanner(ak, questionId);
            
            // Check if any dimension has confidence below threshold and requires HITL confirmation
            // NOTE: HITL confirmation is only for intent refinement (low confidence), NOT for candidate selection
            // In auto mode, HITL confirmation should NOT return candidates to frontend
            boolean autoExecute = (hyperparams.autoExecute != null) ? hyperparams.autoExecute : true;
            PendingHitl lowConfidenceHITL = hitlPlanner.requireHITLForLowConfidence(intent, kw, hyperparams, ak, questionId);
            if (lowConfidenceHITL != null) {
                System.out.println("[HITL] Low confidence - " + lowConfidenceHITL.slot());
                statusEmitter.emitStatus(conversationId.toString(), "hitl_confirmation", "active");
                logs.add(log("hitl_confirmation", "Low confidence dimension requires user confirmation: " + lowConfidenceHITL.slot(), conversationId));
                hitlStateStore.put(conversationId, lowConfidenceHITL);
                String reply = lowConfidenceHITL.question();
                int agentTurnIndex = conversationRepository.getNextTurnIndex(conversationId, questionIndex);
                memoryStore.addAgentMessage(conversationId, questionId, questionIndex, agentTurnIndex, reply, "orchestrator", Map.of("stage", "hitl_required"));
                
                Map<String, Object> resp = response(
                        "require_human",
                        reply,
                        intent,
                        lowConfidenceHITL,
                        Collections.emptyList(),
                        logs,
                        ""
                );
                // Ensure dimension_candidates is not included in auto mode for HITL confirmation
                if (autoExecute) {
                    resp.remove("dimension_candidates");
                    if (resp.containsKey("intent") && resp.get("intent") instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> intentMap = (Map<String, Object>) resp.get("intent");
                        intentMap.remove("dimension_candidates");
                    }
                }
                return resp;
            }
            
            // If no HITL needed, mark HITL as skipped and immediately activate spatial/temporal filter
            // NOTE: Entity matching was already done when candidates were found (before user selection)
            // User selection is part of entity matching, so entity_matching is already done
            statusEmitter.emitStatus(conversationId.toString(), "hitl_confirmation", "skipped");
            logs.add(log("hitl_confirmation", "No HITL required, proceeding to spatial/temporal filter", conversationId));
            
            // Entity matching done → Spatial/Temporal filter active (immediately after HITL skipped)
            statusEmitter.emitStatus(conversationId.toString(), "spatial_temporal_filter", "active");
            System.out.println("[Spatial/Temporal Filter] Starting spatial/temporal/source filtering");
            logs.add(log("spatial_temporal_filter", "starting spatial/temporal/source filtering", conversationId));
            
            // Get candidates for all dimensions to display in parsed intent
            hitlPlanner.getAllCandidatesForDisplay(intent, kw, hyperparams, ak, questionId);
            
            // Continue to retrieval immediately (no need to return response for auto-continue)
            return runRetrievalAndSynthesis(ak, conversationId, q, intent, model, kw, hyperparams, logs, questionId, questionIndex);
        } else if (prevIntent == null) {
            // Intent parsing active is now emitted in DiscoveryController.query() immediately after user input
            logs.add(log("intent_parsing", "parsing initial intent", conversationId));
            // In text search mode, skip expand step
            boolean useExpand = (hyperparams.useEmbeddingSearch != null) ? hyperparams.useEmbeddingSearch : true;
            intent = intentParsingService.parseInitialIntent(q, mem, model, useExpand, ak, questionId);
            // IMPORTANT: Continue using the same questionId - do NOT create a new one based on intentHash
            // Even if expand changes the intent content (and thus intentHash), this is still the same question
            // Only create new questionId for truly new questions (new questionIndex), which is handled in pre-parsing
            // questionId should already be set from pre-parsing (if isNewQuestion) or from existing questionIndex
            logs.add(log("intent_parsing", "intent parsing completed (using same question_id)", conversationId));
            statusEmitter.emitStatus(conversationId.toString(), "intent_parsing", "done");
            // Note: Pipeline message will be emitted after intent is persisted (see below)
        } else {
            // Intent parsing active is now emitted in DiscoveryController.query() immediately after user input
            // In text search mode (useEmbeddingSearch=false), always parse initial intent instead of refining
            boolean useExpand = (hyperparams.useEmbeddingSearch != null) ? hyperparams.useEmbeddingSearch : true;
            if (!useExpand) {
                // Text search mode: always parse initial intent, don't refine
                logs.add(log("intent_parsing", "parsing initial intent (text search mode)", conversationId));
                intent = intentParsingService.parseInitialIntent(q, mem, model, useExpand, ak, questionId);
                // IMPORTANT: Continue using the same questionId - do NOT create a new one based on intentHash
                // Even if expand changes the intent content (and thus intentHash), this is still the same question
                // questionId should already be set from existing questionIndex
                logs.add(log("intent_parsing", "intent parsing completed (text search mode, using same question_id)", conversationId));
                statusEmitter.emitStatus(conversationId.toString(), "intent_parsing", "done");
                // Note: Pipeline message will be emitted after intent is persisted (see below)
            } else {
                // Embedding search mode: refine intent
                logs.add(log("intent_parsing", "refining previous intent", conversationId));
                intent = intentParsingService.refineIntent(prevIntent, q, mem, model, useExpand, ak, questionId);
                // IMPORTANT: After refining intent, continue using the same questionId
                // Refinement is part of the same question, even if intentHash changes
                // Do NOT create a new questionId here - it would incorrectly consume a new question_id quota
                logs.add(log("intent_parsing", "intent refinement completed (using same question_id)", conversationId));
                statusEmitter.emitStatus(conversationId.toString(), "intent_parsing", "done");
                // Note: Pipeline message will be emitted after intent is persisted (see below)
            }
        }

        // Persist intent
        intentStore.put(conversationId, intent);
        // Emit intent in real-time via SSE
        statusEmitter.emitIntent(conversationId.toString(), intent);
        // Update conversation title from intent
        updateConversationTitleFromIntent(conversationId, intent);
        
        // Emit intent parsing progress message when done (via SSE for chat panel only)
        String intentDescription = describeIntentParsing(intent);
        emitPipelineProgressMessage(conversationId, questionId, questionIndex, "intent_parsing", "done", intentDescription, null);
        
        // If nothing recognized, guide user (requirement #6)
        if (intent == null || !hasAnySemantic(intent)) {
            logs.add(log("need_input", "no semantic dimensions detected", conversationId));
            String reply = guidanceMessage();
            int needInputTurnIndex = conversationRepository.getNextTurnIndex(conversationId, questionIndex);
            memoryStore.addAgentMessage(conversationId, questionId, questionIndex, needInputTurnIndex, reply, "orchestrator", Map.of("stage", "need_input"));
            return response(
                    "need_input",
                    reply,
                    intent,
                    null,
                    Collections.emptyList(),
                    logs,
                    ""
            );
        }
        
        // Immediately determine and activate next stage after intent done
        // Pre-compute next stage decision to ensure immediate transition after intent done
        boolean autoExecute = (hyperparams.autoExecute != null) ? hyperparams.autoExecute : true;
        
        // Set linker's retrievalService BEFORE calling HitlPlanner methods to ensure cache works
        // This allows HitlPlanner's linker to cache candidates in GraphRetrievalService's cache
        setupLinkerForHitlPlanner(ak, questionId);
        
        // Check if any dimension has confidence below threshold and requires HITL confirmation
        // This should be done before auto-set or candidate selection
        // In auto mode, HITL confirmation should NOT return candidates to frontend
        PendingHitl lowConfidenceHITL = hitlPlanner.requireHITLForLowConfidence(intent, kw, hyperparams, ak, questionId);
        if (lowConfidenceHITL != null) {
            System.out.println("[HITL] Low confidence - " + lowConfidenceHITL.slot());
            statusEmitter.emitStatus(conversationId.toString(), "hitl_confirmation", "active");
            logs.add(log("hitl_confirmation", "Low confidence dimension requires user confirmation: " + lowConfidenceHITL.slot(), conversationId));
            hitlStateStore.put(conversationId, lowConfidenceHITL);
            String reply = lowConfidenceHITL.question();
            int hitlTurnIndex = conversationRepository.getNextTurnIndex(conversationId, questionIndex);
            memoryStore.addAgentMessage(conversationId, questionId, questionIndex, hitlTurnIndex, reply, "orchestrator", Map.of("stage", "hitl_required"));
            
            // In auto mode, do NOT include candidates in response for HITL confirmation
            // Create a clean intent without candidates for HITL confirmation
            GeoIntent intentForHitl = intent;
            if (autoExecute) {
                // In auto mode, create a copy of intent without candidates to avoid showing them
                // The intent itself is fine, but we don't want to include dimension_candidates in the response
                intentForHitl = intent; // Use intent as-is, but response() won't add dimension_candidates
            }
            
            Map<String, Object> resp = response(
                    "require_human",
                    reply,
                    intentForHitl,
                    lowConfidenceHITL,
                    Collections.emptyList(),
                    logs,
                    ""
            );
            // Ensure dimension_candidates is not included in auto mode for HITL confirmation
            if (autoExecute) {
                resp.remove("dimension_candidates");
                if (resp.containsKey("intent") && resp.get("intent") instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> intentMap = (Map<String, Object>) resp.get("intent");
                    intentMap.remove("dimension_candidates");
                }
            }
            return resp;
        }
        
        // If no HITL needed, mark HITL as skipped and proceed to entity matching
        // Intent parsing done → HITL skipped → Entity matching active (immediately after HITL skipped)
        statusEmitter.emitStatus(conversationId.toString(), "hitl_confirmation", "skipped");
        logs.add(log("hitl_confirmation", "No HITL required, proceeding to entity matching", conversationId));
        
        // Entity matching stage: find candidate entities after HITL skipped/done
        statusEmitter.emitStatus(conversationId.toString(), "entity_matching", "active");
        System.out.println("[Entity Matching] Finding candidate entities");
        logs.add(log("entity_matching", "finding candidate entities", conversationId));
        
        // Emit active message for entity matching
        emitPipelineProgressMessage(conversationId, questionId, questionIndex, "entity_matching", "active", 
                                   "I'm searching the knowledge graph for matching entities...", null);
        
        // In auto-execute mode, automatically set candidates first
        if (autoExecute) {
            // Auto-set candidates for all dimensions (score > threshold)
            hitlPlanner.autoSetCandidates(intent, kw, hyperparams, ak, questionId);
            intentStore.put(conversationId, intent);  // Persist updated intent with candidates
            // Update conversation title from intent
            updateConversationTitleFromIntent(conversationId, intent);
        }
        
        // Get candidates for user selection (only similarity > 0, max 5 per dimension)
        // In auto mode, this should return empty after autoSetCandidates
        // In non-auto mode, this returns candidates for user to select
        Map<String, List<CandidateEntity>> candidatesForSelection = hitlPlanner.getCandidatesForSelection(intent, kw, hyperparams, ak, questionId);
        
        // Get all candidates for display (for progress message)
        Map<String, List<CandidateEntity>> allCandidatesForDisplay = hitlPlanner.getAllCandidatesForDisplay(intent, kw, hyperparams, ak, questionId);
        
        // If there are candidates to select, return them to frontend (non-auto mode only)
        // In auto mode, skip candidate selection even if getCandidatesForSelection returns non-empty
        // NOTE: User candidate selection is part of entity matching, NOT HITL confirmation
        // HITL confirmation is only for intent refinement (low confidence dimensions)
        if (!autoExecute && !candidatesForSelection.isEmpty()) {
            // Entity matching done (candidates found, waiting for user selection)
            // User selection is part of entity matching, so keep entity_matching as done
            // Do NOT set hitl active - candidate selection is not HITL confirmation
            statusEmitter.emitStatus(conversationId.toString(), "entity_matching", "done");
            System.out.println("[Entity Matching] Entity matching completed - waiting for user selection");
            logs.add(log("entity_matching", "Found candidates for " + candidatesForSelection.size() + " dimension(s), waiting for user selection", conversationId));
            
            String reply = "I found some matching entities. Please select the ones you want to use:";
            int candidateSelectionTurnIndex = conversationRepository.getNextTurnIndex(conversationId, questionIndex);
            memoryStore.addAgentMessage(conversationId, questionId, questionIndex, candidateSelectionTurnIndex, reply, "orchestrator", Map.of("stage", "candidates_selection"));
            
            // Build response with candidates map
            Map<String, Object> resp = response(
                    "candidates_selection",
                    reply,
                    intent,
                    null,
                    Collections.emptyList(),
                    logs,
                    ""
            );
            // Add candidates map to response
            Map<String, Object> candidatesMap = new HashMap<>();
            for (Map.Entry<String, List<CandidateEntity>> entry : candidatesForSelection.entrySet()) {
                List<Map<String, Object>> candList = new ArrayList<>();
                for (CandidateEntity c : entry.getValue()) {
                    Map<String, Object> candMap = new HashMap<>();
                    candMap.put("nodeId", c.nodeId());
                    candMap.put("name", c.name());
                    candMap.put("label", c.label());
                    candMap.put("score", c.score());
                    candList.add(candMap);
                }
                candidatesMap.put(entry.getKey(), candList);
            }
            resp.put("dimension_candidates", candidatesMap);
            return resp;
        }
        // Entity matching done → Immediately activate spatial/temporal filter, then check HITL
        statusEmitter.emitStatus(conversationId.toString(), "entity_matching", "done");
        System.out.println("[Entity Matching] Entity matching completed");
        logs.add(log("entity_matching", "entity matching completed", conversationId));
        
        // Emit done message for entity matching with candidates
        String entityMatchingDescription = describeEntityMatching(allCandidatesForDisplay, autoExecute);
        emitPipelineProgressMessage(conversationId, questionId, questionIndex, "entity_matching", "done", 
                                   entityMatchingDescription, allCandidatesForDisplay);
        
        // Immediately activate spatial/temporal filter before any other operations
        // This ensures the UI shows the next stage is active right away
        statusEmitter.emitStatus(conversationId.toString(), "spatial_temporal_filter", "active");
        System.out.println("[Spatial/Temporal Filter] Starting spatial/temporal/source filtering");
        logs.add(log("spatial_temporal_filter", "starting spatial/temporal/source filtering", conversationId));
        
        // Emit active message for spatial/temporal filter
        emitPipelineProgressMessage(conversationId, questionId, questionIndex, "spatial_temporal_filter", "active", 
                                   "I'm applying spatial, temporal, and source filters to narrow down the datasets...", null);
        
        intentStore.put(conversationId, intent);  // Persist updated intent with candidates
        
        // Get candidates for all dimensions to display in parsed intent (both auto and non-auto modes)
        // Use getAllCandidatesForDisplay to get candidates regardless of whether they are already selected
        // BUT: In auto mode, if HITL confirmation is needed, do NOT send candidates via SSE
        Map<String, List<CandidateEntity>> allCandidatesForIntentDisplay = hitlPlanner.getAllCandidatesForDisplay(intent, kw, hyperparams, ak, questionId);
        
        // Check if HITL is needed (only for Space/Time that need explicit input)
        // Do this BEFORE emitting intent with candidates, so we can skip candidates in auto mode
        PendingHitl nextHitl = hitlPlanner.nextHitl(intent, kw, hyperparams, ak, questionId);
        
        // Only emit intent with candidates if NOT in HITL confirmation mode (or if non-auto mode)
        // In auto mode with HITL confirmation, skip sending candidates
        boolean shouldEmitCandidates = !(autoExecute && nextHitl != null);
        
        if (shouldEmitCandidates) {
            // Create intent wrapper with candidates for frontend display
            Map<String, Object> intentWithCandidates = new HashMap<>();
            try {
                // Serialize intent to Map
                String intentJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(intent);
                @SuppressWarnings("unchecked")
                Map<String, Object> intentMap = new com.fasterxml.jackson.databind.ObjectMapper().readValue(intentJson, Map.class);
                intentWithCandidates.putAll(intentMap);
            } catch (Exception e) {
                // Fallback: just use intent as-is
                intentWithCandidates.put("intent", intent);
            }
            
            // Add candidates map to intent wrapper
            Map<String, Object> candidatesMap = new HashMap<>();
            for (Map.Entry<String, List<CandidateEntity>> entry : allCandidatesForIntentDisplay.entrySet()) {
                List<Map<String, Object>> candList = new ArrayList<>();
                for (CandidateEntity c : entry.getValue()) {
                    Map<String, Object> candMap = new HashMap<>();
                    candMap.put("nodeId", c.nodeId());
                    candMap.put("name", c.name());
                    candMap.put("label", c.label());
                    candMap.put("score", c.score());
                    candList.add(candMap);
                }
                candidatesMap.put(entry.getKey(), candList);
            }
            intentWithCandidates.put("dimension_candidates", candidatesMap);
            
            // Emit intent with candidates in real-time via SSE
            statusEmitter.emitIntent(conversationId.toString(), intentWithCandidates);
        } else {
            // In auto mode with HITL confirmation, emit intent WITHOUT candidates
            statusEmitter.emitIntent(conversationId.toString(), intent);
        }
        // Update conversation title from intent
        updateConversationTitleFromIntent(conversationId, intent);
        hitlStateStore.put(conversationId, nextHitl);

        if (nextHitl != null) {
            // If HITL is needed, we need to pause spatial/temporal filter and go to HITL
            // Set spatial_temporal_filter to idle since we're going to HITL
            statusEmitter.emitStatus(conversationId.toString(), "spatial_temporal_filter", "idle");
            
            // Entity matching done → HITL active (for regular HITL)
            System.out.println("[HITL] Required - " + nextHitl.slot());
            logs.add(log("hitl_confirmation", "HITL required slot=" + nextHitl.slot()));
            statusEmitter.emitStatus(conversationId.toString(), "hitl_confirmation", "active");
            // IMPORTANT: when a dimension is ambiguous, only ask about THAT dimension
            // (do not add other dimensions into the prompt).
            String reply = (nextHitl.question() == null || nextHitl.question().isBlank())
                    ? "Please confirm the detected constraint."
                    : nextHitl.question();
            int nextHitlTurnIndex = conversationRepository.getNextTurnIndex(conversationId, questionIndex);
            memoryStore.addAgentMessage(conversationId, questionId, questionIndex, nextHitlTurnIndex, reply, "orchestrator", Map.of("stage", "hitl_needed"));
            return response(
                    "hitl_needed",
                    reply,
                    intent,
                    nextHitl,
                    Collections.emptyList(),
                    logs,
                    ""
            );
        }

        // If no HITL needed, mark HITL as skipped (spatial_temporal_filter is already active)
        statusEmitter.emitStatus(conversationId.toString(), "hitl_confirmation", "skipped");
        logs.add(log("hitl_confirmation", "No HITL required, proceeding to spatial/temporal filter", conversationId));
        
        // IMPORTANT: Continue using the existing questionId - do NOT create a new one based on intentHash
        // This is the same question (same questionIndex), even if intentHash changed due to refinement
        // Creating a new questionId here would incorrectly consume a new question_id quota
        // Only create new questionId for truly new questions (new questionIndex)
        // questionId should already be set from earlier in the flow (pre-parsing or existing)
        if (questionId == null || questionId.isEmpty()) {
            // Safety check: only create new questionId if it's null/empty (should not happen in normal flow)
            String intentHash = QuestionIdManager.hashIntent(intent);
            try {
                questionId = questionIdManager.getOrCreateQuestionId(ak, conversationId, intentHash);
            } catch (GibdApiClient.GibdApiException e) {
                String errorMsg = "Error getting question_id: " + e.getMessage();
                logs.add(log("error", errorMsg));
                return response("error", errorMsg, intent, null, Collections.emptyList(), logs, "");
            }
        }
        
        return runRetrievalAndSynthesis(ak, conversationId, q, intent, model, kw, hyperparams, logs, questionId, questionIndex);
    }

    // ------------------------
    // Helper methods
    // ------------------------
    
    /**
     * Setup linker's retrievalService before calling HitlPlanner methods.
     * This ensures that candidates calculated by HitlPlanner are cached in GraphRetrievalService's cache.
     */
    private void setupLinkerForHitlPlanner(String apiKey, String questionId) {
        try {
            java.lang.reflect.Field linkerField = hitlPlanner.getClass().getDeclaredField("linker");
            linkerField.setAccessible(true);
            GraphEntityLinker linker = (GraphEntityLinker) linkerField.get(hitlPlanner);
            if (linker != null) {
                linker.setRetrievalService(retrievalService);
                linker.setCurrentContext(apiKey, questionId);
            }
        } catch (Exception e) {
            // Ignore if field doesn't exist or fails
        }
    }
    
    // ------------------------
    // Retrieval + synthesis
    // ------------------------

    private Map<String, Object> runRetrievalAndSynthesis(String apiKey,
                                                         UUID conversationId,
                                                         String userQuery,
                                                         GeoIntent intent,
                                                         String model,
                                                         boolean useKeywords,
                                                         Hyperparameters hyperparams,
                                                         List<PipelineLogEvent> logs,
                                                         String questionId,
                                                         int questionIndex) {
        String sessionKey = conversationId.toString();
        
        // Note: spatial_temporal_filter active should already be sent before calling this method
        // This ensures immediate transition from entity_matching done to spatial_temporal_filter active
        // All callers should send spatial_temporal_filter active before calling this method
        
        // Set linker in retrievalService BEFORE calling retrieve() to enable similarity score recalculation
        // This ensures that when retrieve() needs to recalculate scores, the linker is already available
        try {
            java.lang.reflect.Field linkerField = hitlPlanner.getClass().getDeclaredField("linker");
            linkerField.setAccessible(true);
            GraphEntityLinker linker = (GraphEntityLinker) linkerField.get(hitlPlanner);
            if (linker != null) {
                linker.setRetrievalService(retrievalService);
                linker.setCurrentContext(apiKey, questionId);
                // Set linker in retrievalService for similarity score recalculation
                retrievalService.setLinker(linker);
            }
        } catch (Exception e) {
            // Ignore if field doesn't exist or fails
        }
        
        // Set status emitter in retrievalService so it can send status updates at the correct time points
        retrievalService.setStatusEmitter(statusEmitter, sessionKey);
        
        // Dataset retrieval: retrieve dataset IDs and scores
        // Note: retrieve() will send spatial_temporal_filter done and dataset_scoring active/done at the correct time points
        GraphRetrievalResult gr = retrievalService.retrieve(intent, useKeywords, hyperparams, apiKey, questionId);
        
        // Log completion (status was already sent inside retrieve())
        System.out.println("[Spatial/Temporal Filter] Spatial/temporal/source filtering completed");
        logs.add(log("spatial_temporal_filter", "spatial/temporal/source filtering completed", conversationId));
        
        List<String> ids = (gr == null || gr.datasetIds() == null) ? List.of() : gr.datasetIds();
        
        // Emit done message for spatial/temporal filter
        String filteringDescription = describeFiltering(ids.size());
        emitPipelineProgressMessage(conversationId, questionId, questionIndex, "spatial_temporal_filter", "done", filteringDescription, null);
        
        System.out.println("[Dataset Scoring] Dataset scoring completed");
        logs.add(log("dataset_scoring", "dataset scoring completed", conversationId));
        
        // Emit done message for dataset scoring
        String scoringDescription = describeDatasetScoring(ids.size());
        emitPipelineProgressMessage(conversationId, questionId, questionIndex, "dataset_scoring", "done", scoringDescription, null);
        
        statusEmitter.emitStatus(sessionKey, "evidence_collection", "active");
        System.out.println("[Evidence Collection] Starting evidence collection");
        logs.add(log("evidence_collection", "starting evidence collection", conversationId));
        
        // Emit active message for evidence collection
        emitPipelineProgressMessage(conversationId, questionId, questionIndex, "evidence_collection", "active", 
                                   "I'm gathering detailed information about the datasets and their relationships...", null);
        
        System.out.println("[Evidence Collection] Found " + ids.size() + " datasets");
        logs.add(log("evidence_collection", "retrieved datasetIds=" + ids.size(), conversationId));
        
        // Evidence collection stage: includes fetching full dataset info and subgraph expansion
        System.out.println("[Evidence Collection] Building evidence pack with subgraph");
        logs.add(log("evidence_collection", "building evidence pack with subgraph", conversationId));
        
        EvidencePack pack = evidencePackBuilder.build(ids, gr.scoresByDatasetId(), gr.dimensionContributions(), hyperparams);
        
        // Evidence collection done → Dataset selection active (immediately after evidence collection done)
        statusEmitter.emitStatus(sessionKey, "evidence_collection", "done");
        System.out.println("[Evidence Collection] Evidence collection completed");
        logs.add(log("evidence_collection", "evidence collection completed", conversationId));
        
        // Emit done message for evidence collection
        String evidenceDescription = describeEvidenceCollection(ids.size());
        emitPipelineProgressMessage(conversationId, questionId, questionIndex, "evidence_collection", "done", evidenceDescription, null);
        
        statusEmitter.emitStatus(sessionKey, "dataset_selection", "active");
        System.out.println("[Dataset Selection] Starting dataset selection");
        logs.add(log("dataset_selection", "starting dataset selection", conversationId));
        
        // Emit active message for dataset selection
        emitPipelineProgressMessage(conversationId, questionId, questionIndex, "dataset_selection", "active", 
                                   "I'm analyzing and selecting the most relevant datasets for you...", null);
        
        // Pre-compute bundles before selection
        List<DatasetBundle> bundles = (pack == null || pack.datasets() == null) ? List.of() : pack.datasets();
        
        // TEST FEATURE: Save Top 20 datasets before selection (for testing/debugging purposes only)
        // This will be removed before production release
        List<DatasetBundle> top20BeforeSelection = new ArrayList<>(bundles);
        if (top20BeforeSelection.size() > 20) {
            top20BeforeSelection = top20BeforeSelection.subList(0, 20);
        }
        
        // Dataset selection stage: LLM-based selection to select top 10 from up to 20 datasets
        // Even if datasets <= 10, still call LLM to generate "Why it matches" reasons
        int beforeSelectionCount = bundles.size();
        if (bundles.size() > 10) {
            System.out.println("[Dataset Selection] Selecting top 10 from " + bundles.size() + " datasets");
            logs.add(log("dataset_selection", "LLM selecting top 10 from " + bundles.size() + " datasets", conversationId));
        } else {
            System.out.println("[Dataset Selection] Generating 'Why it matches' reasons for " + bundles.size() + " datasets");
            logs.add(log("dataset_selection", "LLM generating 'Why it matches' reasons for " + bundles.size() + " datasets", conversationId));
        }
        try {
            bundles = datasetSelectionService.selectTopDatasets(bundles, userQuery, intent, model, apiKey, questionId);
        } catch (Exception e) {
            String errorMsg = "Error in dataset selection: " + e.getMessage();
            logs.add(log("error", errorMsg, conversationId));
            // Continue with all bundles if selection fails
        }
        // Dataset selection done → Answer synthesis active (immediately after dataset selection done)
        statusEmitter.emitStatus(sessionKey, "dataset_selection", "done");
        System.out.println("[Dataset Selection] Selected " + bundles.size() + " datasets with LLM");
        logs.add(log("dataset_selection", "Selected " + bundles.size() + " datasets with LLM", conversationId));
        
        // Emit done message for dataset selection
        String selectionDescription = describeDatasetSelection(beforeSelectionCount, bundles.size());
        emitPipelineProgressMessage(conversationId, questionId, questionIndex, "dataset_selection", "done", selectionDescription, null);
        
        statusEmitter.emitStatus(sessionKey, "answer_synthesis", "active");
        System.out.println("[Answer Synthesis] Generating final answer");
        logs.add(log("answer_synthesis", "synthesizing reply", conversationId));
        
        // Emit active message for answer synthesis
        emitPipelineProgressMessage(conversationId, questionId, questionIndex, "answer_synthesis", "active", 
                                   "I'm synthesizing a comprehensive answer based on the selected datasets...", null);
        ConversationMemory mem = memoryStore.getOrCreate(conversationId);
        SynthesisResult sr;
        try {
            sr = synthesisService.synthesize(
                    userQuery == null ? "" : userQuery,
                    intent,
                    pack,
                    mem,
                    model,
                    useKeywords,
                    apiKey,
                    questionId
            );
        } catch (Exception e) {
            String errorMsg = "Error in answer synthesis: " + e.getMessage();
            logs.add(log("error", errorMsg, conversationId));
            sr = new SynthesisResult("Error generating response: " + errorMsg);
        }
        statusEmitter.emitStatus(sessionKey, "answer_synthesis", "done");
        logs.add(log("answer_synthesis", "answer synthesis completed", conversationId));
        
        // Emit done message for answer synthesis
        String synthesisDoneDescription = "I've completed synthesizing the answer. Here are the results:";
        emitPipelineProgressMessage(conversationId, questionId, questionIndex, "answer_synthesis", "done", synthesisDoneDescription, null);
        System.out.println("----------------conversation id: " + conversationId + "----------------");

        String reply = (sr == null || sr.reply() == null || sr.reply().isBlank())
                ? "Here are the most relevant datasets I found."
                : sr.reply();

        int agentTurnIndex = conversationRepository.getNextTurnIndex(conversationId, questionIndex);
        memoryStore.addAgentMessage(conversationId, questionId, questionIndex, agentTurnIndex, reply, "synthesis", 
                Map.of("stage", "done", "datasets", bundles.size()));

        Map<String, Object> resp = response(
                "done",
                reply,
                intent,
                null,
                bundles,
                logs,
                ""
        );
        
        // TEST FEATURE: Add Top 20 datasets before selection (for testing/debugging purposes only)
        // This will be removed before production release
        resp.put("top20_before_selection", top20BeforeSelection);
        
        // Add candidates to intent in response (for frontend display in parsed intent)
        if (intent != null) {
            // Ensure linker's retrievalService is set before calling getAllCandidatesForDisplay to use cache
            // This allows getAllCandidatesForDisplay to reuse cached candidates from earlier stages
            try {
                java.lang.reflect.Field linkerField = hitlPlanner.getClass().getDeclaredField("linker");
                linkerField.setAccessible(true);
                GraphEntityLinker linker = (GraphEntityLinker) linkerField.get(hitlPlanner);
                if (linker != null) {
                    linker.setRetrievalService(retrievalService);
                    linker.setCurrentContext(apiKey, questionId);
                }
            } catch (Exception e) {
                // Ignore if field doesn't exist or fails
            }
            // Use getAllCandidatesForDisplay to get candidates regardless of whether they are already selected
            // This will reuse cached candidates from autoSetCandidates/retrieval stages if available
            Map<String, List<CandidateEntity>> allCandidates = hitlPlanner.getAllCandidatesForDisplay(intent, useKeywords, hyperparams, apiKey, questionId);
            Map<String, Object> candidatesMap = new HashMap<>();
            for (Map.Entry<String, List<CandidateEntity>> entry : allCandidates.entrySet()) {
                List<Map<String, Object>> candList = new ArrayList<>();
                for (CandidateEntity c : entry.getValue()) {
                    Map<String, Object> candMap = new HashMap<>();
                    candMap.put("nodeId", c.nodeId());
                    candMap.put("name", c.name());
                    candMap.put("label", c.label());
                    candMap.put("score", c.score());
                    candList.add(candMap);
                }
                candidatesMap.put(entry.getKey(), candList);
            }
            // Convert intent to Map and add candidates
            try {
                String intentJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(intent);
                @SuppressWarnings("unchecked")
                Map<String, Object> intentMap = new com.fasterxml.jackson.databind.ObjectMapper().readValue(intentJson, Map.class);
                intentMap.put("dimension_candidates", candidatesMap);
                resp.put("intent", intentMap);
            } catch (Exception e) {
                // If conversion fails, just add candidates to a wrapper
                Map<String, Object> intentWrapper = new HashMap<>();
                intentWrapper.put("intent", intent);
                intentWrapper.put("dimension_candidates", candidatesMap);
                resp.put("intent", intentWrapper);
            }
        }
        
        // Include evidence subgraph for frontend visualization
        if (pack != null && pack.evidence() != null) {
            Object subgraph = pack.evidence().get("subgraph");
            if (subgraph != null) {
                resp.put("evidence", Map.of("subgraph", subgraph));
            }
        }
        
        return resp;
    }

    // ------------------------
    // Pipeline progress messages (first-person, natural language)
    // ------------------------
    
    /**
     * Emit a pipeline progress message via SSE to display in chat panel in real-time.
     * Also save the message to database for conversation restoration.
     * This is for the chat panel only - logs remain unchanged.
     * @param status "active" or "done" - done messages replace active messages in the same box
     * @param candidates Optional candidates map to include in the message
     */
    private void emitPipelineProgressMessage(UUID conversationId, String questionId, int questionIndex,
                                            String stage, String status, 
                                            String message, Map<String, List<CandidateEntity>> candidates) {
        // Convert candidates to Map format for SSE and database
        Map<String, Object> candidatesMap = null;
        if (candidates != null && !candidates.isEmpty()) {
            candidatesMap = new HashMap<>();
            for (Map.Entry<String, List<CandidateEntity>> entry : candidates.entrySet()) {
                List<Map<String, Object>> candList = new ArrayList<>();
                for (CandidateEntity c : entry.getValue()) {
                    Map<String, Object> candMap = new HashMap<>();
                    candMap.put("nodeId", c.nodeId());
                    candMap.put("name", c.name());
                    candMap.put("label", c.label());
                    candMap.put("score", c.score());
                    candList.add(candMap);
                }
                candidatesMap.put(entry.getKey(), candList);
            }
        }
        
        // Save pipeline message to database for conversation restoration
        try {
            int agentTurnIndex = conversationRepository.getNextTurnIndex(conversationId, questionIndex);
            Map<String, Object> meta = new HashMap<>();
            meta.put("stage", stage);
            meta.put("status", status);
            if (candidatesMap != null && !candidatesMap.isEmpty()) {
                meta.put("dimension_candidates", candidatesMap);
            }
            memoryStore.addAgentMessage(conversationId, questionId, questionIndex, agentTurnIndex, 
                                      message, "pipeline", meta);
        } catch (Exception e) {
            // If saving fails, log but don't fail the request
            System.err.println("Failed to save pipeline message to database: " + e.getMessage());
        }
        
        // Emit pipeline message via SSE to display in chat panel
        statusEmitter.emitPipelineMessage(conversationId.toString(), message, stage, status, candidatesMap);
    }
    
    /**
     * Generate a natural language description of intent parsing results
     */
    private String describeIntentParsing(GeoIntent intent) {
        if (intent == null) {
            return "I couldn't fully understand your request. Could you provide more details about what you're looking for?";
        }
        
        List<String> parts = new ArrayList<>();
        
        if (intent.getTopic() != null && intent.getTopic().getValue() != null && !intent.getTopic().getValue().isBlank()) {
            parts.add("**Topic**: " + intent.getTopic().getValue());
        }
        if (intent.getFormat() != null && intent.getFormat().getValue() != null && !intent.getFormat().getValue().isBlank()) {
            parts.add("**Format**: " + intent.getFormat().getValue());
        }
        if (intent.getLicense() != null && intent.getLicense().getValue() != null && !intent.getLicense().getValue().isBlank()) {
            parts.add("**License**: " + intent.getLicense().getValue());
        }
        if (intent.getOrganization() != null && intent.getOrganization().getValue() != null && !intent.getOrganization().getValue().isBlank()) {
            parts.add("**Organization**: " + intent.getOrganization().getValue());
        }
        if (intent.getSource() != null && intent.getSource().getValue() != null && !intent.getSource().getValue().isBlank()) {
            parts.add("**Source**: " + intent.getSource().getValue());
        }
        if (intent.getSpace() != null && intent.getSpace().getBbox() != null) {
            double[] bbox = intent.getSpace().getBbox();
            parts.add("**Spatial area**: [" + String.format("%.4f", bbox[0]) + ", " + 
                     String.format("%.4f", bbox[1]) + ", " + String.format("%.4f", bbox[2]) + ", " + 
                     String.format("%.4f", bbox[3]) + "]");
        }
        if (intent.getTime() != null && intent.getTime().getStart() != null && intent.getTime().getEnd() != null) {
            parts.add("**Time range**: " + intent.getTime().getStart() + " to " + intent.getTime().getEnd());
        }
        
        if (parts.isEmpty()) {
            return "I've analyzed your request, but I need more specific information to help you find the right datasets.";
        }
        
        return "I've understood your request. Here's what I extracted:\n\n" + String.join("\n", parts) + "\n\nLet me search for matching entities in the knowledge graph...";
    }
    
    /**
     * Generate a natural language description of entity matching results
     */
    private String describeEntityMatching(Map<String, List<CandidateEntity>> candidates, boolean autoMode) {
        if (candidates == null || candidates.isEmpty()) {
            return "I searched the knowledge graph but couldn't find any matching entities. Let me proceed with the search anyway.";
        }
        
        List<String> parts = new ArrayList<>();
        int totalCandidates = 0;
        
        for (Map.Entry<String, List<CandidateEntity>> entry : candidates.entrySet()) {
            String dimension = entry.getKey();
            List<CandidateEntity> cands = entry.getValue();
            if (cands != null && !cands.isEmpty()) {
                totalCandidates += cands.size();
                String bestName = cands.get(0).name();
                double bestScore = cands.get(0).score();
                parts.add(String.format("**%s**: Found %d candidate(s), best match is \"%s\" (similarity: %.3f)", 
                    dimension, cands.size(), bestName, bestScore));
            }
        }
        
        if (parts.isEmpty()) {
            return "I searched the knowledge graph but couldn't find any matching entities.";
        }
        
        String intro = autoMode 
            ? String.format("I found %d matching entity candidate(s) across different dimensions:\n\n", totalCandidates)
            : String.format("I found %d matching entity candidate(s) across different dimensions. Please review and select the ones you want to use:\n\n", totalCandidates);
        
        return intro + String.join("\n", parts);
    }
    
    /**
     * Generate a natural language description of filtering results
     */
    private String describeFiltering(int datasetCount) {
        if (datasetCount == 0) {
            return "I've applied spatial, temporal, and source filters, but no datasets matched all the criteria. Let me check if we can relax some constraints.";
        }
        return String.format("I've applied spatial, temporal, and source filters, and found %d dataset(s) that match your criteria. Now I'm scoring them for relevance...", datasetCount);
    }
    
    /**
     * Generate a natural language description of dataset scoring
     */
    private String describeDatasetScoring(int datasetCount) {
        return String.format("I've scored %d dataset(s) based on how well they match your requirements. The scores consider topic relevance, spatial overlap, temporal alignment, and other factors.", datasetCount);
    }
    
    /**
     * Generate a natural language description of evidence collection
     */
    private String describeEvidenceCollection(int datasetCount) {
        return String.format("I'm gathering detailed information about %d dataset(s), including their relationships with topics, organizations, licenses, and other entities in the knowledge graph...", datasetCount);
    }
    
    /**
     * Generate a natural language description of dataset selection
     */
    private String describeDatasetSelection(int beforeCount, int afterCount) {
        if (beforeCount <= 10) {
            return String.format("I've reviewed all %d dataset(s) and generated explanations for why each one matches your query.", afterCount);
        }
        return String.format("I've reviewed %d dataset(s) and selected the top %d most relevant ones based on comprehensive analysis.", beforeCount, afterCount);
    }
    
    /**
     * Generate a natural language description of answer synthesis
     */
    private String describeAnswerSynthesis(int datasetCount) {
        return String.format("I'm now synthesizing a comprehensive answer based on the %d most relevant dataset(s) I found for you.", datasetCount);
    }

    // ------------------------
    // Response builder
    // ------------------------

    private void applyDimensionSelection(GeoIntent intent, String dimension, List<String> nodeIds, List<String> names) {
        if (intent == null || nodeIds == null || nodeIds.isEmpty()) return;
        if (names == null) names = List.of();
        
        switch (dimension.toLowerCase()) {
            case "topic" -> {
                if (intent.getTopic() == null) intent.setTopic(new GeoIntent.EntityDim());
                // Only set kgNodeIds - do NOT modify value or rawText
                // This ensures GraphRetrievalService uses original intent value for score calculation
                intent.getTopic().setKgNodeIds(nodeIds);
                intent.getTopic().setKgNodeId(nodeIds.get(0));
                // Keep original value and rawText unchanged - they are used for score calculation
                intent.getTopic().setNeedsClarification(false);
            }
            case "format" -> {
                if (intent.getFormat() == null) intent.setFormat(new GeoIntent.EntityDim());
                intent.getFormat().setKgNodeIds(nodeIds);
                intent.getFormat().setKgNodeId(nodeIds.get(0));
                // Keep original value and rawText unchanged
                intent.getFormat().setNeedsClarification(false);
            }
            case "license" -> {
                if (intent.getLicense() == null) intent.setLicense(new GeoIntent.EntityDim());
                intent.getLicense().setKgNodeIds(nodeIds);
                intent.getLicense().setKgNodeId(nodeIds.get(0));
                // Keep original value and rawText unchanged
                intent.getLicense().setNeedsClarification(false);
            }
            case "organization" -> {
                if (intent.getOrganization() == null) intent.setOrganization(new GeoIntent.EntityDim());
                intent.getOrganization().setKgNodeIds(nodeIds);
                intent.getOrganization().setKgNodeId(nodeIds.get(0));
                // Keep original value and rawText unchanged
                intent.getOrganization().setNeedsClarification(false);
            }
            case "source" -> {
                if (intent.getSource() == null) intent.setSource(new GeoIntent.EntityDim());
                intent.getSource().setKgNodeIds(nodeIds);
                intent.getSource().setKgNodeId(nodeIds.get(0));
                // Keep original value and rawText unchanged
                intent.getSource().setNeedsClarification(false);
            }
        }
    }
    
    private List<Integer> parseSelectionIndices(String selection) {
        List<Integer> indices = new ArrayList<>();
        if (selection == null || selection.isBlank()) return indices;
        
        // Parse "1,3,5" or "1 3 5" format
        String[] parts = selection.split("[,;\\s]+");
        for (String part : parts) {
            try {
                int idx = Integer.parseInt(part.trim());
                if (idx > 0) indices.add(idx);
            } catch (NumberFormatException ignored) {}
        }
        return indices;
    }

    private Map<String, Object> response(String stage,
                                         String reply,
                                         GeoIntent intent,
                                         PendingHitl pending,
                                         List<DatasetBundle> datasets,
                                         List<PipelineLogEvent> logs,
                                         String nextAction) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", stage);
        m.put("reply", reply == null ? "" : reply);
        m.put("intent", intent);

        // frontend expects snake_case key
        m.put("pending_hitl", pending);

        // keep both spellings (frontend uses question_for_users; GeoIntent uses questions_for_user)
        List<String> qs = (intent == null || intent.getQuestionsForUser() == null) ? List.of() : intent.getQuestionsForUser();
        m.put("question_for_users", qs);
        m.put("questions_for_user", qs);

        m.put("datasets", datasets == null ? List.of() : datasets);
        m.put("logs", logs == null ? List.of() : logs);

        if (nextAction != null && !nextAction.isBlank()) m.put("next_action", nextAction);
        return m;
    }

    private PipelineLogEvent log(String stage, String message, UUID conversationId) {
        String ts = Instant.now().toString();
        PipelineLogEvent logEvent = new PipelineLogEvent(ts, stage, message);
        // Emit log in real-time via SSE
        if (conversationId != null) {
            statusEmitter.emitLog(conversationId.toString(), ts, stage, message);
        }
        return logEvent;
    }
    
    // Overloaded method for backward compatibility (when conversationId is not available)
    private PipelineLogEvent log(String stage, String message) {
        return log(stage, message, null);
    }

    private boolean hasAnySemantic(GeoIntent i) {
        if (i == null) return false;
        if (hasText(i.getTopic())) return true;
        if (hasText(i.getFormat())) return true;
        if (hasText(i.getLicense())) return true;
        if (hasText(i.getOrganization())) return true;
        if (hasText(i.getSource())) return true;
        if (i.getSpace() != null && hasText(i.getSpace().getValue(), i.getSpace().getRawText())) return true;
        if (i.getTime() != null && hasText(i.getTime().getRawText(), i.getTime().getStart(), i.getTime().getEnd())) return true;
        return false;
    }

    private boolean hasText(GeoIntent.EntityDim d) {
        if (d == null) return false;
        return hasText(d.getValue(), d.getRawText());
    }

    private boolean hasText(String... ss) {
        if (ss == null) return false;
        for (String s : ss) {
            if (s != null && !s.trim().isEmpty()) return true;
        }
        return false;
    }

    private String guidanceMessage() {
        return "I couldn't detect any dataset discovery constraints yet. You can ask using ANY dimension, for example:\n" +
               "- Topic: urban heat island\n" +
               "- Format: GeoTIFF\n" +
               "- License: CC-BY-4.0\n" +
               "- Organization: USGS\n" +
               "- Source: NASA Earthdata\n" +
               "- Space: Pennsylvania, USA (or bounding box: -80,39,-74,42)\n" +
               "- Time: 2018-01-01 to 2020-12-31 (or year: 2020)";
    }

    /**
     * Extract a title from GeoIntent for conversation title.
     * Returns a summary string based on detected dimensions.
     */
    private String extractTitleFromIntent(GeoIntent intent) {
        if (intent == null) return null;
        
        List<String> parts = new ArrayList<>();
        
        // Topic
        if (intent.getTopic() != null) {
            String topic = intent.getTopic().getValue();
            if (topic == null || topic.isBlank()) topic = intent.getTopic().getRawText();
            if (topic != null && !topic.isBlank()) {
                parts.add("Topic: " + topic);
            }
        }
        
        // Space
        if (intent.getSpace() != null) {
            String space = intent.getSpace().getValue();
            if (space == null || space.isBlank()) space = intent.getSpace().getRawText();
            if (space != null && !space.isBlank()) {
                parts.add("Space: " + space);
            }
        }
        
        // Time
        if (intent.getTime() != null) {
            String time = intent.getTime().getRawText();
            if (time == null || time.isBlank()) {
                if (intent.getTime().getStart() != null && intent.getTime().getEnd() != null) {
                    time = intent.getTime().getStart() + " to " + intent.getTime().getEnd();
                } else if (intent.getTime().getStart() != null) {
                    time = "from " + intent.getTime().getStart();
                } else if (intent.getTime().getEnd() != null) {
                    time = "until " + intent.getTime().getEnd();
                }
            }
            if (time != null && !time.isBlank()) {
                parts.add("Time: " + time);
            }
        }
        
        // Format
        if (intent.getFormat() != null) {
            String format = intent.getFormat().getValue();
            if (format == null || format.isBlank()) format = intent.getFormat().getRawText();
            if (format != null && !format.isBlank()) {
                parts.add("Format: " + format);
            }
        }
        
        // License
        if (intent.getLicense() != null) {
            String license = intent.getLicense().getValue();
            if (license == null || license.isBlank()) license = intent.getLicense().getRawText();
            if (license != null && !license.isBlank()) {
                parts.add("License: " + license);
            }
        }
        
        // Organization
        if (intent.getOrganization() != null) {
            String org = intent.getOrganization().getValue();
            if (org == null || org.isBlank()) org = intent.getOrganization().getRawText();
            if (org != null && !org.isBlank()) {
                parts.add("Organization: " + org);
            }
        }
        
        // Source
        if (intent.getSource() != null) {
            String source = intent.getSource().getValue();
            if (source == null || source.isBlank()) source = intent.getSource().getRawText();
            if (source != null && !source.isBlank()) {
                parts.add("Source: " + source);
            }
        }
        
        if (parts.isEmpty()) return null;
        
        // Combine parts, limit to 100 characters
        String title = String.join("; ", parts);
        if (title.length() > 100) {
            title = title.substring(0, 97) + "...";
        }
        return title;
    }

    /**
     * Update conversation title from intent (best-effort, doesn't break flow).
     */
    private void updateConversationTitleFromIntent(UUID conversationId, GeoIntent intent) {
        if (conversationId == null || intent == null) return;
        try {
            String title = extractTitleFromIntent(intent);
            if (title != null && !title.isBlank()) {
                conversationRepository.updateTitle(conversationId, title);
            }
        } catch (Exception ignored) {
            // Best-effort: don't break discovery flow
        }
    }

    /**
     * Check if intent needs re-parsing after HITL confirmation.
     * Returns true if user provided text input (not candidate selection) and confidence is below threshold.
     */
    private boolean needsReparseAfterHitl(GeoIntent intent, HitlSlot slot) {
        if (intent == null) return false;
        double defaultThreshold = 0.5;
        return needsReparseAfterHitl(intent, slot, defaultThreshold);
    }

    /**
     * Check if intent needs re-parsing after HITL confirmation with specific threshold.
     * Returns true if user provided text input (not candidate selection) and confidence is below threshold.
     */
    private boolean needsReparseAfterHitl(GeoIntent intent, HitlSlot slot, double confidenceThreshold) {
        if (intent == null) return false;
        
        GeoIntent.EntityDim dim = null;
        switch (slot) {
            case TOPIC -> dim = intent.getTopic();
            case FORMAT -> dim = intent.getFormat();
            case LICENSE -> dim = intent.getLicense();
            case ORGANIZATION -> dim = intent.getOrganization();
            case SOURCE -> dim = intent.getSource();
            case SPACE, TIME -> {
                // Space and Time don't use EntityDim, so no re-parsing needed
                return false;
            }
        }
        
        if (dim == null) return false;
        
        // Check if user provided text input (has value but no kgNodeIds)
        boolean hasValue = hasText(dim.getValue(), dim.getRawText());
        boolean hasKgNodeIds = dim.getKgNodeIds() != null && !dim.getKgNodeIds().isEmpty();
        
        // If user selected candidates (has kgNodeIds), no re-parsing needed
        if (hasKgNodeIds) return false;
        
        // If user provided text but no candidates selected, check confidence
        if (hasValue) {
            double confidence = dim.getConfidence();
            // If confidence is below threshold, need re-parsing
            return confidence < confidenceThreshold;
        }
        
        return false;
    }
}

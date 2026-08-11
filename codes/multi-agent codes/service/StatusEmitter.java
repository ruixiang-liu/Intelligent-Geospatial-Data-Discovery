package edu.psu.giscience.igdd.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Service for emitting real-time status updates to frontend via SSE.
 */
@Service
public class StatusEmitter {
    
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    // Track stage start times: sessionId -> stage -> startTime
    private final Map<String, Map<String, Long>> stageStartTimes = new ConcurrentHashMap<>();
    // Track stage durations: sessionId -> stage -> duration (stored when status becomes "done")
    private final Map<String, Map<String, Long>> stageDurations = new ConcurrentHashMap<>();
    // Track stage statuses: sessionId -> stage -> status (for skipped stages that don't have duration)
    private final Map<String, Map<String, String>> stageStatuses = new ConcurrentHashMap<>();
    // Scheduled executor for periodic ping to keep connections alive
    private final ScheduledExecutorService pingScheduler = Executors.newScheduledThreadPool(1);
    // Track scheduled ping tasks for each session to allow cancellation
    private final Map<String, java.util.concurrent.ScheduledFuture<?>> pingTasks = new ConcurrentHashMap<>();
    
    /**
     * Register a new SSE emitter for a session.
     * IMPORTANT: Immediately sends a ping event to ensure response headers are sent to nginx.
     * This prevents nginx from timing out while waiting for the first response.
     */
    public SseEmitter createEmitter(String sessionId) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.put(sessionId, emitter);
        
        emitter.onCompletion(() -> {
            emitters.remove(sessionId);
            // Cancel ping task when connection completes
            java.util.concurrent.ScheduledFuture<?> task = pingTasks.remove(sessionId);
            if (task != null) {
                task.cancel(false);
            }
        });
        emitter.onTimeout(() -> {
            emitters.remove(sessionId);
            // Cancel ping task when connection times out
            java.util.concurrent.ScheduledFuture<?> task = pingTasks.remove(sessionId);
            if (task != null) {
                task.cancel(false);
            }
            emitter.complete();
        });
        emitter.onError((ex) -> {
            emitters.remove(sessionId);
            // Cancel ping task when connection errors
            java.util.concurrent.ScheduledFuture<?> task = pingTasks.remove(sessionId);
            if (task != null) {
                task.cancel(false);
            }
            emitter.completeWithError(ex);
        });
        
        // CRITICAL: Immediately send a ping event to flush response headers
        // This ensures nginx receives the response immediately and doesn't timeout
        // Spring's SseEmitter will send headers when the first event is sent
        try {
            emitter.send(SseEmitter.event()
                .name("ping")
                .data("{\"status\":\"connected\"}"));
        } catch (IOException e) {
            // If initial ping fails, remove emitter and complete with error
            emitters.remove(sessionId);
            emitter.completeWithError(e);
            return emitter;
        }
        
        // Schedule periodic ping to keep connection alive (every 4 minutes)
        // This prevents nginx and network timeouts for long-lived connections
        // nginx timeout is 600s (10 minutes), so 4-minute ping ensures connection stays alive
        java.util.concurrent.ScheduledFuture<?> pingTask = pingScheduler.scheduleAtFixedRate(() -> {
            SseEmitter activeEmitter = emitters.get(sessionId);
            if (activeEmitter != null) {
                try {
                    activeEmitter.send(SseEmitter.event()
                        .name("ping")
                        .data("{\"status\":\"keepalive\"}"));
                } catch (IOException e) {
                    // Connection closed, remove emitter and cancel ping task
                    emitters.remove(sessionId);
                    java.util.concurrent.ScheduledFuture<?> task = pingTasks.remove(sessionId);
                    if (task != null) {
                        task.cancel(false);
                    }
                    activeEmitter.complete();
                }
            } else {
                // Emitter was removed, cancel this ping task
                java.util.concurrent.ScheduledFuture<?> task = pingTasks.remove(sessionId);
                if (task != null) {
                    task.cancel(false);
                }
            }
        }, 4, 4, TimeUnit.MINUTES); // Start after 4 minutes, repeat every 4 minutes
        
        // Store ping task for cleanup
        pingTasks.put(sessionId, pingTask);
        
        return emitter;
    }
    
    /**
     * Emit a status update for a session.
     */
    public void emitStatus(String sessionId, String stage, String status) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter != null) {
            try {
                long currentTime = System.currentTimeMillis();
                Long duration = null;
                
                // Track start time when status becomes "active"
                // Only update startTime if not already set (avoid overwriting if active is sent multiple times)
                if ("active".equals(status)) {
                    Map<String, Long> startTimes = stageStartTimes.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());
                    // Only set startTime if not already active (avoid overwriting)
                    if (!startTimes.containsKey(stage)) {
                        startTimes.put(stage, currentTime);
                    }
                }
                // Calculate duration when status becomes "done" or "skipped"
                else if ("done".equals(status) || "skipped".equals(status)) {
                    // Track status for later retrieval when building pipeline_states
                    stageStatuses.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                        .put(stage, status);
                    
                    Map<String, Long> startTimes = stageStartTimes.get(sessionId);
                    if (startTimes != null) {
                        Long startTime = startTimes.remove(stage);
                        if (startTime != null) {
                            duration = currentTime - startTime;
                            // Store duration for later retrieval when building pipeline_states
                            stageDurations.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                                .put(stage, duration);
                        }
                    }
                }
                
                // Format as JSON string for SSE, include duration if available
                String json;
                if (duration != null) {
                    json = String.format("{\"stage\":\"%s\",\"status\":\"%s\",\"duration\":%d}", 
                        escapeJson(stage), escapeJson(status), duration);
                } else {
                    json = String.format("{\"stage\":\"%s\",\"status\":\"%s\"}", 
                        escapeJson(stage), escapeJson(status));
                }
                emitter.send(SseEmitter.event()
                    .name("status")
                    .data(json));
            } catch (IOException e) {
                emitters.remove(sessionId);
                emitter.completeWithError(e);
            }
        }
    }
    
    /**
     * Emit a log event for a session (real-time log updates).
     */
    public void emitLog(String sessionId, String ts, String stage, String message) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter != null) {
            try {
                // Format log as JSON string for SSE
                String json = String.format("{\"ts\":\"%s\",\"stage\":\"%s\",\"message\":\"%s\"}", 
                    escapeJson(ts), escapeJson(stage), escapeJson(message));
                emitter.send(SseEmitter.event()
                    .name("log")
                    .data(json));
            } catch (IOException e) {
                emitters.remove(sessionId);
                emitter.completeWithError(e);
            }
        }
    }
    
    /**
     * Emit an intent update for a session (real-time intent updates).
     */
    public void emitIntent(String sessionId, Object intent) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter != null && intent != null) {
            try {
                // Serialize intent object to JSON
                String json = objectMapper.writeValueAsString(intent);
                emitter.send(SseEmitter.event()
                    .name("intent")
                    .data(json));
            } catch (Exception e) {
                // If serialization fails, try to remove emitter
                emitters.remove(sessionId);
                emitter.completeWithError(e);
            }
        }
    }
    
    /**
     * Emit a pipeline progress message for a session (real-time pipeline messages for chat panel).
     * This sends messages that will be displayed in the chat conversation.
     * @param status "active" or "done" - done messages replace active messages in the same box
     */
    public void emitPipelineMessage(String sessionId, String message, String stage, String status, Map<String, Object> candidates) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter != null && message != null) {
            try {
                Map<String, Object> data = new HashMap<>();
                data.put("message", message);
                data.put("stage", stage != null ? stage : "");
                data.put("status", status != null ? status : "active");
                if (candidates != null && !candidates.isEmpty()) {
                    data.put("dimension_candidates", candidates);
                }
                String json = objectMapper.writeValueAsString(data);
                emitter.send(SseEmitter.event()
                    .name("pipeline_message")
                    .data(json));
            } catch (Exception e) {
                // If serialization fails, try to remove emitter
                emitters.remove(sessionId);
                emitter.completeWithError(e);
            }
        }
    }
    
    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
    
    /**
     * Get the duration for a specific stage in a session.
     * Returns null if the stage has no stored duration.
     */
    public Long getStageDuration(String sessionId, String stage) {
        Map<String, Long> durations = stageDurations.get(sessionId);
        if (durations != null) {
            return durations.get(stage);
        }
        return null;
    }
    
    /**
     * Get all stage durations for a session.
     * Returns a map of stage -> duration.
     */
    public Map<String, Long> getAllStageDurations(String sessionId) {
        Map<String, Long> durations = stageDurations.get(sessionId);
        return (durations != null) ? new HashMap<>(durations) : new HashMap<>();
    }
    
    /**
     * Get all stage statuses for a session.
     * Returns a map of stage -> status (e.g., "done", "skipped").
     */
    public Map<String, String> getAllStageStatuses(String sessionId) {
        Map<String, String> statuses = stageStatuses.get(sessionId);
        return (statuses != null) ? new HashMap<>(statuses) : new HashMap<>();
    }
    
    /**
     * Complete and remove emitter for a session.
     */
    public void complete(String sessionId) {
        SseEmitter emitter = emitters.remove(sessionId);
        if (emitter != null) {
            emitter.complete();
        }
        // Cancel ping task
        java.util.concurrent.ScheduledFuture<?> task = pingTasks.remove(sessionId);
        if (task != null) {
            task.cancel(false);
        }
        // Clean up stage start times, durations, and statuses
        stageStartTimes.remove(sessionId);
        stageDurations.remove(sessionId);
        stageStatuses.remove(sessionId);
    }
}

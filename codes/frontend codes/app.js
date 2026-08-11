/**
 * IGDD GraphRAG Frontend
 *
 * Changes:
 * - Left sidebar: Quick examples at top; remove "New chat" button
 * - Show questions_for_users and pending_hitl (HITL confirmation) inside chat
 * - Right sidebar keeps: pipeline / parsed intent / logs (no HITL panel there)
 *
 * Key requirement implemented:
 * - ONE backend response -> ONE assistant bubble (reply + datasets + questions + HITL panel).
 * - Per dataset: text (title/desc) + list (kv rows), consistent layout.
 */

const DEFAULT_API_URL = "http://xxxx:8087/api/igdd/query";
const API_URL = window.__IGDD_API_URL || DEFAULT_API_URL;

// Current conversation ID (null means new conversation)
let currentConversationId = null;

// Read-only mode for shared conversations (when user is not the owner)
let isReadOnlyMode = false;

// API Key management
function getApiKey() {
  const apiKeyInput = document.getElementById("api-key-input");
  if (apiKeyInput && apiKeyInput.value.trim()) {
    return apiKeyInput.value.trim();
  }
  const stored = localStorage.getItem("igdd-api-key");
  return stored || "";
}

function setApiKey(apiKey) {
  const apiKeyInput = document.getElementById("api-key-input");
  if (apiKeyInput) {
    apiKeyInput.value = apiKey || "";
  }
  if (apiKey) {
    localStorage.setItem("igdd-api-key", apiKey);
  } else {
    localStorage.removeItem("igdd-api-key");
  }
}

// UI elements
const chatMessages = document.getElementById("chat-messages");
const messageInput = document.getElementById("message");
const sendBtn = document.getElementById("send-btn");

const stageText = document.getElementById("stage-text");
const datasetsCount = document.getElementById("datasets-count");

const modelSelect = document.getElementById("model-select");
const searchModeSelect = document.getElementById("search-mode-select");

const pipelineGraph = document.getElementById("pipeline-graph");

const intentPanel = document.getElementById("intent-panel");
const intentRaw = document.getElementById("intent-raw");

const logsPanel = document.getElementById("logs-panel");

// Quick examples container

// Track if there are pending candidates waiting for user selection (non-auto mode)
let hasPendingCandidates = false;
const quickExamplesEl = document.getElementById("quick-examples");

let isSending = false;
const seenLogKeys = new Set();
let statusEventSource = null;

const PIPELINE_STEPS = [
  { id: "intent_parsing", label: "Intent Parsing" },
  { id: "hitl_confirmation", label: "Human-in-the-loop Confirmation" },
  { id: "entity_matching", label: "Entity Candidate Matching" },
  { id: "spatial_temporal_filter", label: "Spatial/Temporal/Source Filter" },
  { id: "dataset_scoring", label: "Dataset Relevance Scoring" },
  { id: "evidence_collection", label: "Evidence Collection" },
  { id: "dataset_selection", label: "Dataset Selection" },
  { id: "answer_synthesis", label: "Answer Synthesis" }
];

const QUICK_EXAMPLES = [
  "I need land cover datasets for Pennsylvania between 2018 and 2020.",
  "Find Sentinel-2 imagery datasets for Centre County, Pennsylvania in June 2019.",
  "I’m looking for daily temperature (tmax) datasets for New York State from 1990 to 2020.",
  "Discover road network datasets for California that are available as Shapefile or GeoPackage.",
  "Find population density raster datasets for the United States around 2010–2020.",
  "I need datasets about urban heat island and land surface temperature for Phoenix, Arizona.",
  "I want precipitation data provided by NASA in the format of NetCDF and licensed by CC0 1.0 in North America during 2015–2020.",
  "I want nighttime light data provided by NOAA in the format of GeoTIFF and licensed by Public Domain in China during 2018–2019.",
  "I want soil moisture data provided by ESA in the format of NetCDF and licensed by CC-BY-SA 3.0 in Europe during 2016–2018.",
  "I want digital elevation model data provided by NASA in the format of GeoTIFF and licensed by Public Domain in South America during 2000–2015."
];

function escapeHtml(str) {
  return String(str)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

/**
 * Render Markdown text to HTML with support for common OpenAI Markdown formats.
 * Supports: **bold**, *italic*, `code`, lists, links, etc.
 * @param {string} text - Markdown text to render
 * @returns {string} - HTML string with Markdown formatting applied
 */
function renderMarkdown(text) {
  if (!text) return '';
  
  let html = String(text);
  
  // Escape HTML first to prevent XSS attacks
  html = escapeHtml(html);
  
  // Split into lines for processing
  const lines = html.split('\n');
  const processedLines = [];
  let inCodeBlock = false;
  let codeBlockContent = [];
  
  // Process line by line
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    
    // Code blocks (```code```)
    if (line.trim().startsWith('```')) {
      if (inCodeBlock) {
        // End of code block
        const codeContent = codeBlockContent.join('\n');
        processedLines.push(`<pre class="markdown-code-block"><code>${codeContent}</code></pre>`);
        codeBlockContent = [];
        inCodeBlock = false;
      } else {
        // Start of code block
        inCodeBlock = true;
        const lang = line.trim().substring(3).trim();
        // Store language if provided, but don't render it for simplicity
      }
      continue;
    }
    
    if (inCodeBlock) {
      codeBlockContent.push(line);
      continue;
    }
    
    let processedLine = line;
    
    // Headers (# Header)
    if (line.match(/^###\s+(.+)$/)) {
      processedLine = line.replace(/^###\s+(.+)$/, '<h3 class="markdown-h3">$1</h3>');
    } else if (line.match(/^##\s+(.+)$/)) {
      processedLine = line.replace(/^##\s+(.+)$/, '<h2 class="markdown-h2">$1</h2>');
    } else if (line.match(/^#\s+(.+)$/)) {
      processedLine = line.replace(/^#\s+(.+)$/, '<h1 class="markdown-h1">$1</h1>');
    }
    // Horizontal rule (---)
    else if (line.trim() === '---') {
      processedLine = '<hr class="markdown-hr"/>';
    }
    let isListItem = false;
    let listContent = '';
    
    // Numbered lists (1. item)
    if (line.match(/^\d+\.\s+(.+)$/)) {
      listContent = line.replace(/^\d+\.\s+(.+)$/, '$1');
      isListItem = true;
      processedLine = line.replace(/^\d+\.\s+(.+)$/, '<li class="markdown-list-item markdown-ordered">$1</li>');
    }
    // Unordered lists (- item or * item) - allow formatting inside list items
    else if (line.match(/^[-*]\s+(.+)$/)) {
      listContent = line.replace(/^[-*]\s+(.+)$/, '$1');
      isListItem = true;
      processedLine = line.replace(/^[-*]\s+(.+)$/, '<li class="markdown-list-item markdown-unordered">$1</li>');
    }
    
    // Process inline formatting for all lines (including list items)
    if (isListItem && listContent) {
      // Process list content separately
      let formattedContent = listContent;
      
      // Inline code (`code`)
      formattedContent = formattedContent.replace(/`([^`\n]+)`/g, '<code class="markdown-inline-code">$1</code>');
      
      // Bold (**text** or __text__)
      formattedContent = formattedContent.replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>');
      formattedContent = formattedContent.replace(/__([^_\n]+)__/g, '<strong>$1</strong>');
      
      // Italic (*text* or _text_)
      formattedContent = formattedContent.replace(/(?<!\*)\*([^*\n\s][^*\n]*?[^*\n\s])\*(?!\*)/g, '<em>$1</em>');
      formattedContent = formattedContent.replace(/(?<!_)_([^_\n\s][^_\n]*?[^_\n\s])_(?!_)/g, '<em>$1</em>');
      
      // Links [text](url)
      formattedContent = formattedContent.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer" class="markdown-link">$1</a>');
      
      // Update processedLine with formatted content
      if (processedLine.includes('markdown-ordered')) {
        processedLine = processedLine.replace(/<li class="markdown-list-item markdown-ordered">(.+?)<\/li>/, `<li class="markdown-list-item markdown-ordered">${formattedContent}</li>`);
      } else if (processedLine.includes('markdown-unordered')) {
        processedLine = processedLine.replace(/<li class="markdown-list-item markdown-unordered">(.+?)<\/li>/, `<li class="markdown-list-item markdown-unordered">${formattedContent}</li>`);
      }
    } else if (processedLine === line) {
      // Process regular lines (not headers/lists)
      // Inline code (`code`) - must be before bold/italic processing
      processedLine = processedLine.replace(/`([^`\n]+)`/g, '<code class="markdown-inline-code">$1</code>');
      
      // Bold (**text** or __text__)
      processedLine = processedLine.replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>');
      processedLine = processedLine.replace(/__([^_\n]+)__/g, '<strong>$1</strong>');
      
      // Italic (*text* or _text_)
      processedLine = processedLine.replace(/(?<!\*)\*([^*\n\s][^*\n]*?[^*\n\s])\*(?!\*)/g, '<em>$1</em>');
      processedLine = processedLine.replace(/(?<!_)_([^_\n\s][^_\n]*?[^_\n\s])_(?!_)/g, '<em>$1</em>');
      
      // Links [text](url) - must be after code/bold/italic
      processedLine = processedLine.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer" class="markdown-link">$1</a>');
    } else {
      // Headers also need inline formatting
      // Inline code (`code`)
      processedLine = processedLine.replace(/`([^`\n]+)`/g, '<code class="markdown-inline-code">$1</code>');
      
      // Bold (**text** or __text__)
      processedLine = processedLine.replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>');
      processedLine = processedLine.replace(/__([^_\n]+)__/g, '<strong>$1</strong>');
      
      // Italic (*text* or _text_)
      processedLine = processedLine.replace(/(?<!\*)\*([^*\n\s][^*\n]*?[^*\n\s])\*(?!\*)/g, '<em>$1</em>');
      processedLine = processedLine.replace(/(?<!_)_([^_\n\s][^_\n]*?[^_\n\s])_(?!_)/g, '<em>$1</em>');
      
      // Links [text](url)
      processedLine = processedLine.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer" class="markdown-link">$1</a>');
    }
    
    processedLines.push(processedLine);
  }
  
  // Handle any remaining code block
  if (inCodeBlock && codeBlockContent.length > 0) {
    processedLines.push(`<pre class="markdown-code-block"><code>${codeBlockContent.join('\n')}</code></pre>`);
  }
  
  html = processedLines.join('\n');
  
  // Wrap consecutive list items and remove line breaks/whitespace within lists
  // First handle ordered lists
  html = html.replace(/(<li class="markdown-list-item markdown-ordered">.*?<\/li>\s*\n?)+/g, function(match) {
    // Remove line breaks and extra whitespace between list items
    const cleaned = match.replace(/\s*\n\s*/g, '');
    return '<ol class="markdown-list markdown-ordered-list">' + cleaned + '</ol>';
  });
  
  // Then handle unordered lists
  html = html.replace(/(<li class="markdown-list-item markdown-unordered">.*?<\/li>\s*\n?)+/g, function(match) {
    // Remove line breaks and extra whitespace between list items
    const cleaned = match.replace(/\s*\n\s*/g, '');
    return '<ul class="markdown-list markdown-unordered-list">' + cleaned + '</ul>';
  });
  
  // Convert line breaks to <br/> (but not inside lists, which are already handled)
  html = html.replace(/\n/g, '<br/>');
  
  return html;
}

// Toast Notification System
function showToast(message, type = 'info') {
  const container = document.getElementById('toast-container');
  if (!container) return;
  
  const toast = document.createElement('div');
  toast.className = `toast ${type}`;
  toast.innerHTML = `
    <span class="toast-message">${escapeHtml(message)}</span>
    <button class="toast-close" onclick="this.parentElement.remove()">×</button>
  `;
  
  container.appendChild(toast);
  
  // Auto-remove after 3 seconds
  setTimeout(() => {
    if (toast.parentElement) {
      toast.style.animation = 'toastSlideIn 0.3s ease-out reverse';
      setTimeout(() => toast.remove(), 300);
    }
  }, 3000);
}

// Confirm Dialog System (toast-style)
function showConfirmDialog(message) {
  return new Promise((resolve) => {
    const container = document.getElementById('toast-container');
    if (!container) {
      resolve(false);
      return;
    }
    
    // Create overlay mask to block all interactions
    const overlay = document.createElement('div');
    overlay.className = 'confirm-dialog-overlay';
    overlay.id = 'confirm-dialog-overlay';
    document.body.appendChild(overlay);
    
    const confirmDialog = document.createElement('div');
    confirmDialog.className = 'toast-confirm-dialog';
    confirmDialog.innerHTML = `
      <div class="toast-confirm-message">${escapeHtml(message)}</div>
      <div class="toast-confirm-buttons">
        <button class="toast-confirm-btn toast-confirm-cancel">Cancel</button>
        <button class="toast-confirm-btn toast-confirm-ok">Confirm</button>
      </div>
    `;
    
    container.appendChild(confirmDialog);
    
    const removeDialog = () => {
      if (confirmDialog.parentElement) {
        confirmDialog.style.animation = 'toastSlideIn 0.3s ease-out reverse';
        setTimeout(() => confirmDialog.remove(), 300);
      }
      // Remove overlay mask
      if (overlay.parentElement) {
        overlay.style.opacity = '0';
        setTimeout(() => overlay.remove(), 300);
      }
    };
    
    const handleConfirm = () => {
      removeDialog();
      resolve(true);
    };
    
    const handleCancel = () => {
      removeDialog();
      resolve(false);
    };
    
    confirmDialog.querySelector('.toast-confirm-ok').addEventListener('click', handleConfirm);
    confirmDialog.querySelector('.toast-confirm-cancel').addEventListener('click', handleCancel);
    
    // Prevent clicks on overlay from closing the dialog (only buttons can close it)
    overlay.addEventListener('click', (e) => {
      e.stopPropagation();
    });
  });
}

function nowTime() {
  const d = new Date();
  return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

// Function to show full pipeline progress history in toast
// Defined in global scope so it can be accessed from both real-time updates and conversation restoration
function showPipelineProgressToast(history) {
  if (!history || history.length === 0) return;
  
  const container = document.getElementById('toast-container');
  if (!container) return;
  
  // Create a detailed toast with all pipeline messages
  const toast = document.createElement("div");
  toast.className = "toast info pipeline-progress-toast";
  
  // Format timestamp for display (UTC format, same as logs)
  function formatTimestamp(timestamp) {
    if (!timestamp) return '';
    try {
      // Handle both number and string timestamps
      // If it's already an ISO string, use it directly
      if (typeof timestamp === 'string' && timestamp.includes('T') && timestamp.includes('Z')) {
        return timestamp;
      }
      const ts = typeof timestamp === 'string' ? parseInt(timestamp) : timestamp;
      if (isNaN(ts) || ts <= 0) return '';
      const date = new Date(ts);
      if (isNaN(date.getTime())) return '';
      // Format as UTC time string (same format as logs: ISO 8601)
      return date.toISOString();
    } catch (e) {
      return '';
    }
  }
  
  // Remove duplicates based on stage (keep the first occurrence)
  // Use a Map to track the latest occurrence of each stage+status combination
  const uniqueHistoryMap = new Map();
  for (const item of history) {
    // For request_received, only keep one (the first one encountered)
    if (item.stage === "request_received") {
      if (!uniqueHistoryMap.has("request_received")) {
        uniqueHistoryMap.set("request_received", item);
      }
    } else {
      // For other stages, use stage+status as key to avoid duplicates
      // Keep the latest one (with highest timestamp)
      const key = `${item.stage}_${item.status}`;
      const existing = uniqueHistoryMap.get(key);
      if (!existing) {
        uniqueHistoryMap.set(key, item);
      } else {
        // Keep the one with the latest timestamp
        const existingTime = (typeof existing.timestamp === 'string' ? parseInt(existing.timestamp) : existing.timestamp) || 0;
        const itemTime = (typeof item.timestamp === 'string' ? parseInt(item.timestamp) : item.timestamp) || 0;
        if (itemTime > existingTime) {
          uniqueHistoryMap.set(key, item);
        }
      }
    }
  }
  
  const uniqueHistory = Array.from(uniqueHistoryMap.values());
  
  // Sort history by timestamp in ascending order (oldest first, chronological order)
  const sortedHistory = [...uniqueHistory].sort((a, b) => {
    const timeA = (typeof a.timestamp === 'string' ? parseInt(a.timestamp) : a.timestamp) || 0;
    const timeB = (typeof b.timestamp === 'string' ? parseInt(b.timestamp) : b.timestamp) || 0;
    // Ascending order (oldest first)
    if (timeA === timeB) return 0;
    return timeA - timeB;
  });
  
  const historyHtml = sortedHistory.map((item, idx) => {
    const isActive = item.status === "active";
    const statusBadge = isActive
      ? '<span class="pipeline-status-badge active">Active</span>'
      : '<span class="pipeline-status-badge done">Done</span>';
    const stageName = item.stage ? item.stage.replace(/_/g, " ").replace(/\b\w/g, l => l.toUpperCase()) : "Unknown";
    const timestamp = formatTimestamp(item.timestamp);
    const itemClass = isActive ? "pipeline-history-item pipeline-history-item-active" : "pipeline-history-item pipeline-history-item-done";
    return `
      <div class="${itemClass}">
        <div class="pipeline-history-header">
          <div class="pipeline-history-stage-info">
            <span class="pipeline-history-stage">${escapeHtml(stageName)}</span>
            ${timestamp ? `<span class="pipeline-history-timestamp">${escapeHtml(timestamp)}</span>` : ''}
          </div>
          ${statusBadge}
        </div>
        <div class="pipeline-history-message">${renderMarkdown(String(item.message))}</div>
      </div>
    `;
  }).join("");
  
  toast.innerHTML = `
    <div class="pipeline-progress-toast-header">
      <div class="pipeline-progress-toast-title">Pipeline Progress History</div>
      <button class="toast-close" onclick="this.parentElement.parentElement.remove()" title="Close">×</button>
    </div>
    <div class="pipeline-progress-toast-content">
      ${historyHtml}
    </div>
  `;
  
  container.appendChild(toast);
  
  // Add click outside handler to close toast (only for this toast)
  setTimeout(() => {
    const handleClickOutside = (event) => {
      if (toast && !toast.contains(event.target)) {
        toast.remove();
        document.removeEventListener('click', handleClickOutside);
      }
    };
    // Use setTimeout to avoid immediate trigger from the click that opened the toast
    setTimeout(() => {
      document.addEventListener('click', handleClickOutside);
    }, 100);
  }, 0);
  
  // Don't auto-remove - user must click close button or click outside
}

function currentModel() {
  return (modelSelect && modelSelect.value) ? modelSelect.value : "gpt-5.2";
}

function getSearchMode() {
  return searchModeSelect && searchModeSelect.value ? searchModeSelect.value : "embedding";
}

function getSelectedPortals() {
  return {
    datagov: document.getElementById("portal-datagov")?.checked ?? true,
    pasda: document.getElementById("portal-pasda")?.checked ?? true,
    stacGoogle: document.getElementById("portal-stac-google")?.checked ?? true,
    stacMicrosoft: document.getElementById("portal-stac-microsoft")?.checked ?? true,
    stacDedl: document.getElementById("portal-stac-dedl")?.checked ?? true,
    stacPaituli: document.getElementById("portal-stac-paituli")?.checked ?? true
  };
}

function validateDataCatalogSelection() {
  const portals = getSelectedPortals();
  const selectedCount = Object.values(portals).filter(v => v === true).length;
  return selectedCount > 0;
}

// Save and restore user settings (API key, model, hyperparameters, etc.)
function saveUserSettings() {
  const settings = {
    // API key (already handled separately, but we'll include it for completeness)
    apiKey: document.getElementById("api-key-input")?.value.trim() || "",
    
    // Model
    model: document.getElementById("model-select")?.value || "gpt-5.2",
    
    // Auto Execute
    autoExecute: document.getElementById("auto-execute-toggle")?.checked ?? true,
    
    // Search Mode
    searchMode: document.getElementById("search-mode-select")?.value || "embedding",
    
    // Data Catalog Selection (portals)
    portals: {
      datagov: document.getElementById("portal-datagov")?.checked ?? true,
      pasda: document.getElementById("portal-pasda")?.checked ?? true,
      stacGoogle: document.getElementById("portal-stac-google")?.checked ?? true,
      stacMicrosoft: document.getElementById("portal-stac-microsoft")?.checked ?? true,
      stacDedl: document.getElementById("portal-stac-dedl")?.checked ?? true,
      stacPaituli: document.getElementById("portal-stac-paituli")?.checked ?? true
    },
    
    // Hyperparameters
    hyperparameters: {
      weightTopic: document.getElementById("weight-topic")?.value || "0.3",
      weightFormat: document.getElementById("weight-format")?.value || "0.1",
      weightLicense: document.getElementById("weight-license")?.value || "0.1",
      weightOrganization: document.getElementById("weight-organization")?.value || "0.1",
      weightSpace: document.getElementById("weight-space")?.value || "0.2",
      weightTime: document.getElementById("weight-time")?.value || "0.2",
      similarityScoreThreshold: document.getElementById("similarity-score-threshold")?.value || "0.7",
      confidenceThreshold: document.getElementById("confidence-threshold")?.value || "0.5"
    }
  };
  
  localStorage.setItem("igdd-user-settings", JSON.stringify(settings));
}

function restoreUserSettings() {
  const stored = localStorage.getItem("igdd-user-settings");
  if (!stored) return;
  
  try {
    const settings = JSON.parse(stored);
    
    // Restore API key (if not already set by initApiKey)
    const apiKeyInput = document.getElementById("api-key-input");
    if (apiKeyInput && settings.apiKey && !apiKeyInput.value.trim()) {
      apiKeyInput.value = settings.apiKey;
      // Also save to the old localStorage key for compatibility
      if (settings.apiKey) {
        localStorage.setItem("igdd-api-key", settings.apiKey);
      }
    }
    
    // Restore Model
    const modelSelect = document.getElementById("model-select");
    if (modelSelect && settings.model) {
      modelSelect.value = settings.model;
    }
    
    // Restore Auto Execute
    const autoExecuteToggle = document.getElementById("auto-execute-toggle");
    if (autoExecuteToggle && settings.autoExecute !== undefined) {
      autoExecuteToggle.checked = settings.autoExecute;
    }
    
    // Restore Search Mode
    const searchModeSelect = document.getElementById("search-mode-select");
    if (searchModeSelect && settings.searchMode) {
      searchModeSelect.value = settings.searchMode;
    }
    
    // Restore Data Catalog Selection
    if (settings.portals) {
      const portalIds = {
        datagov: "portal-datagov",
        pasda: "portal-pasda",
        stacGoogle: "portal-stac-google",
        stacMicrosoft: "portal-stac-microsoft",
        stacDedl: "portal-stac-dedl",
        stacPaituli: "portal-stac-paituli"
      };
      
      for (const [key, id] of Object.entries(portalIds)) {
        const checkbox = document.getElementById(id);
        if (checkbox && settings.portals[key] !== undefined) {
          checkbox.checked = settings.portals[key];
        }
      }
    }
    
    // Restore Hyperparameters
    if (settings.hyperparameters) {
      const hyperparamIds = {
        weightTopic: "weight-topic",
        weightFormat: "weight-format",
        weightLicense: "weight-license",
        weightOrganization: "weight-organization",
        weightSpace: "weight-space",
        weightTime: "weight-time",
        similarityScoreThreshold: "similarity-score-threshold",
        confidenceThreshold: "confidence-threshold"
      };
      
      for (const [key, id] of Object.entries(hyperparamIds)) {
        const input = document.getElementById(id);
        if (input && settings.hyperparameters[key]) {
          input.value = settings.hyperparameters[key];
          // Also update lastValid for validation
          if (input.dataset) {
            input.dataset.lastValid = settings.hyperparameters[key];
          }
        }
      }
    }
  } catch (e) {
    console.error("Error restoring user settings:", e);
  }
}

function initUserSettingsPersistence() {
  // Restore settings on page load (after initApiKey has run)
  // Use setTimeout to ensure DOM is ready and initApiKey has completed
  setTimeout(() => {
    restoreUserSettings();
  }, 50);
  
  // Save settings when they change
  // Model
  const modelSelect = document.getElementById("model-select");
  if (modelSelect) {
    modelSelect.addEventListener("change", saveUserSettings);
  }
  
  // Auto Execute
  const autoExecuteToggle = document.getElementById("auto-execute-toggle");
  if (autoExecuteToggle) {
    autoExecuteToggle.addEventListener("change", saveUserSettings);
  }
  
  // Search Mode
  const searchModeSelect = document.getElementById("search-mode-select");
  if (searchModeSelect) {
    searchModeSelect.addEventListener("change", saveUserSettings);
  }
  
  // Data Catalog Selection (portals)
  const portalIds = [
    "portal-datagov",
    "portal-pasda",
    "portal-stac-google",
    "portal-stac-microsoft",
    "portal-stac-dedl",
    "portal-stac-paituli"
  ];
  
  portalIds.forEach(id => {
    const checkbox = document.getElementById(id);
    if (checkbox) {
      checkbox.addEventListener("change", saveUserSettings);
    }
  });
  
  // Hyperparameters
  const hyperparamIds = [
    "weight-topic",
    "weight-format",
    "weight-license",
    "weight-organization",
    "weight-space",
    "weight-time",
    "similarity-score-threshold",
    "confidence-threshold"
  ];
  
  hyperparamIds.forEach(id => {
    const input = document.getElementById(id);
    if (input) {
      // Save on change and blur (to catch manual edits)
      input.addEventListener("change", saveUserSettings);
      input.addEventListener("blur", saveUserSettings);
    }
  });
  
  // API key is already handled in initApiKey, but we'll also save it here for consistency
  // We'll add a listener that saves to our unified settings after the existing handlers
  const apiKeyInput = document.getElementById("api-key-input");
  if (apiKeyInput) {
    // Add saveUserSettings to existing change and blur handlers
    // Use a small delay to ensure it runs after existing handlers
    apiKeyInput.addEventListener("change", () => {
      setTimeout(saveUserSettings, 10);
    });
    
    apiKeyInput.addEventListener("blur", () => {
      setTimeout(saveUserSettings, 10);
    });
  }
}

function initPortalCheckboxes() {
  // Get all portal checkboxes
  const portalCheckboxes = [
    document.getElementById("portal-datagov"),
    document.getElementById("portal-pasda"),
    document.getElementById("portal-stac-google"),
    document.getElementById("portal-stac-microsoft"),
    document.getElementById("portal-stac-dedl"),
    document.getElementById("portal-stac-paituli")
  ].filter(el => el !== null);
  
  // Add event listeners to prevent unchecking the last selected portal
  portalCheckboxes.forEach(checkbox => {
    checkbox.addEventListener('change', function() {
      if (!this.checked) {
        // Check if this is the last selected portal
        const portals = getSelectedPortals();
        const selectedCount = Object.values(portals).filter(v => v === true).length;
        
        if (selectedCount === 0) {
          // Prevent unchecking - this is the last one, re-check it
          this.checked = true;
          showToast('Please select at least one portal.', 'warning');
        }
      }
    });
  });
}

function getHyperparameters() {
  const searchMode = getSearchMode();
  const useEmbeddingSearch = searchMode === "embedding";
  
  // Default thresholds based on search mode
  const defaultSimilarityThreshold = 0.7;
  
  const autoExecuteToggle = document.getElementById("auto-execute-toggle");
  const autoExecute = autoExecuteToggle ? autoExecuteToggle.checked : true; // Default to true
  
  // Helper function to parse and round to 0.01 precision
  const parseToStep = (value, defaultValue) => {
    const parsed = parseFloat(value);
    if (isNaN(parsed)) return defaultValue;
    // Round to 2 decimal places (0.01 precision)
    return Math.round(parsed * 100) / 100;
  };
  
  return {
    useEmbeddingSearch: useEmbeddingSearch,
    autoExecute: autoExecute,
    weightTopic: parseToStep(document.getElementById("weight-topic")?.value, 0.3),
    weightFormat: parseToStep(document.getElementById("weight-format")?.value, 0.1),
    weightLicense: parseToStep(document.getElementById("weight-license")?.value, 0.1),
    weightOrganization: parseToStep(document.getElementById("weight-organization")?.value, 0.1),
    weightSpace: parseToStep(document.getElementById("weight-space")?.value, 0.2),
    weightTime: parseToStep(document.getElementById("weight-time")?.value, 0.2),
    similarityScoreThreshold: parseToStep(document.getElementById("similarity-score-threshold")?.value, 0.7),
    confidenceThreshold: parseToStep(document.getElementById("confidence-threshold")?.value, 0.5),
    portals: getSelectedPortals()
  };
}

function validateHyperparameters(hyperparams) {
  // Validate weights: must be greater than 0
  const weights = [
    { name: 'weightTopic', value: hyperparams.weightTopic },
    { name: 'weightFormat', value: hyperparams.weightFormat },
    { name: 'weightLicense', value: hyperparams.weightLicense },
    { name: 'weightOrganization', value: hyperparams.weightOrganization },
    { name: 'weightSpace', value: hyperparams.weightSpace },
    { name: 'weightTime', value: hyperparams.weightTime }
  ];
  
  for (const weight of weights) {
    if (!weight.value || weight.value <= 0 || !isFinite(weight.value)) {
      const displayName = weight.name.replace('weight', '').replace(/([A-Z])/g, ' $1').trim();
      return { valid: false, error: `${displayName} must be greater than 0` };
    }
  }
  
  // Validate similarityScoreThreshold: must be between 0.4 and 1 (inclusive)
  if (hyperparams.similarityScoreThreshold === null || 
      hyperparams.similarityScoreThreshold === undefined ||
      !isFinite(hyperparams.similarityScoreThreshold) ||
      hyperparams.similarityScoreThreshold < 0.4 ||
      hyperparams.similarityScoreThreshold > 1) {
    return { valid: false, error: 'Similarity score threshold must be between 0.4 and 1 (inclusive)' };
  }
  
  // Validate confidenceThreshold: must be between 0.3 and 0.9 (inclusive)
  if (hyperparams.confidenceThreshold === null || 
      hyperparams.confidenceThreshold === undefined ||
      !isFinite(hyperparams.confidenceThreshold) ||
      hyperparams.confidenceThreshold < 0.3 ||
      hyperparams.confidenceThreshold > 0.9) {
    return { valid: false, error: 'Confidence threshold must be between 0.3 and 0.9 (inclusive)' };
  }
  
  return { valid: true };
}

function autoResizeTextarea(el) {
  if (!el) return;
  el.style.height = "auto";
  const maxHeight = 8 * 16; // 8rem in pixels (assuming 16px base font size)
  const scrollHeight = el.scrollHeight;
  el.style.height = `${Math.min(scrollHeight, maxHeight)}px`;
  // Only show scrollbar when content exceeds max height
  el.style.overflowY = scrollHeight > maxHeight ? "auto" : "hidden";
}

function initQuickExamples() {
  if (!quickExamplesEl) return;

  quickExamplesEl.innerHTML = "";

  QUICK_EXAMPLES.forEach((text) => {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "quick-example-btn";
    btn.textContent = text;
    btn.title = "Click to fill the input box";
    btn.addEventListener("click", () => {
      if (!messageInput) return;
      messageInput.value = text;
      autoResizeTextarea(messageInput);
      messageInput.focus();
    });
    quickExamplesEl.appendChild(btn);
  });
}

function initPipelineGraph() {
  if (!pipelineGraph) return;
  pipelineGraph.innerHTML = "";
  
  // Reset durations
  PIPELINE_STEPS.forEach(step => {
    pipelineDurations[step.id] = null;
  });
  
  // Reset total time display
  updatePipelineTotalTime();

  // Agent grouping configuration - map step index to agent name
  const agentMap = {
    0: "Intent Parsing Agent",           // Intent Parsing
    2: "Graph Retrieval Agent",          // Entity Matching, Spatial/Temporal Filter, Dataset Scoring
    5: "Answer Synthesis Agent"           // Evidence Collection, Dataset Selection, Answer Synthesis
  };

  PIPELINE_STEPS.forEach((step, idx) => {
    // Check if we need to add an agent label before this step
    if (agentMap[idx] !== undefined) {
      const agentWrap = document.createElement("div");
      agentWrap.className = "pipeline-agent";
      agentWrap.innerHTML = `
        <div class="pipeline-agent-label">${escapeHtml(agentMap[idx])}</div>
      `;
      pipelineGraph.appendChild(agentWrap);
    }

    const wrap = document.createElement("div");
    wrap.className = "pipeline-step";
    wrap.dataset.stepId = step.id;

    const lineStyle = (idx === PIPELINE_STEPS.length - 1) ? 'style="visibility:hidden"' : "";

    wrap.innerHTML = `
      <div class="pipeline-track">
        <div class="pipeline-circle" id="pipeline-circle-${step.id}"></div>
        <div class="pipeline-line" ${lineStyle}></div>
      </div>
      <div class="pipeline-content">
        <div class="pipeline-label">${escapeHtml(step.label)}</div>
        <div class="pipeline-state" id="node-state-${step.id}">idle</div>
      </div>
    `;
    pipelineGraph.appendChild(wrap);
  });
}

function mapStageToPipelineIndex(stage) {
  if (!stage) return 0;
  const s = String(stage).toLowerCase();

  if (s.includes("intent") || s.includes("general_response") || s.includes("general_question")) return 0;
  if (s.includes("require_human") || s.includes("hitl") || s.includes("clarif")) return 1;
  if (s.includes("entity_matching") || s.includes("candidate")) return 2;
  if (s.includes("spatial_temporal") || s.includes("hard")) return 3;
  if (s.includes("dataset_scoring") || s.includes("soft")) return 4;
  if (s.includes("evidence") || s.includes("subgraph") || s.includes("expand")) return 5;
  if (s.includes("dataset_selection") || s.includes("selection")) return 6;
  if (s.includes("answer_synthesis") || s.includes("synth") || s.includes("answer")) return 7;
  if (s.includes("done")) return 7;

  return 0;
}

function setPipelineState(stage) {
  if (!pipelineGraph) return;
  
  // If stage is "idle" or empty, set all steps to idle
  if (!stage || stage === "idle") {
    PIPELINE_STEPS.forEach(step => {
      const el = pipelineGraph.querySelector(`[data-step-id="${step.id}"]`);
      const stateEl = document.getElementById(`node-state-${step.id}`);
      const circleEl = document.getElementById(`pipeline-circle-${step.id}`);
      if (el) {
        el.classList.remove("active", "done");
      }
      if (stateEl) {
        stateEl.textContent = "idle";
      }
      if (circleEl) {
        circleEl.classList.remove("spinning");
      }
    });
    return;
  }
  
  const activeIndex = mapStageToPipelineIndex(stage);

  PIPELINE_STEPS.forEach((step, i) => {
    const el = pipelineGraph.querySelector(`[data-step-id="${step.id}"]`);
    const stateEl = document.getElementById(`node-state-${step.id}`);
    if (!el || !stateEl) return;

    el.classList.remove("active", "done");

    if (i < activeIndex) {
      el.classList.add("done");
      stateEl.textContent = "done";
    } else if (i === activeIndex) {
      el.classList.add("active");
      stateEl.textContent = "active";
    } else {
      stateEl.textContent = "idle";
    }
  });
}

// Store durations for each pipeline step
const pipelineDurations = {};

function updatePipelineTotalTime() {
  const totalTimeEl = document.getElementById("pipeline-total-time");
  if (!totalTimeEl) return;
  
  // Calculate total time from all completed steps
  let totalMs = 0;
  PIPELINE_STEPS.forEach(step => {
    const duration = pipelineDurations[step.id];
    if (duration != null && duration > 0) {
      totalMs += duration;
    }
  });
  
  if (totalMs > 0) {
    const totalSeconds = (totalMs / 1000).toFixed(1);
    totalTimeEl.textContent = `Total time: ${totalSeconds}s`;
  } else {
    totalTimeEl.textContent = "";
  }
}

function setPipelineStepState(stepId, status, duration) {
  if (!pipelineGraph) return;
  const step = PIPELINE_STEPS.find(s => s.id === stepId);
  if (!step) return;
  
  const stepIndex = PIPELINE_STEPS.indexOf(step);
  const el = pipelineGraph.querySelector(`[data-step-id="${stepId}"]`);
  const stateEl = document.getElementById(`node-state-${stepId}`);
  if (!el || !stateEl) return;

  el.classList.remove("active", "done");
  
  // Store duration if provided
  if (duration != null && duration > 0) {
    pipelineDurations[stepId] = duration;
  }
  
  // Format status text with duration if available
  let statusText = status;
  if (status === "done" && duration != null && duration > 0) {
    const durationSeconds = (duration / 1000).toFixed(1);
    statusText = `done (${durationSeconds}s)`;
  }
  stateEl.textContent = statusText;
  
  // Update total time
  updatePipelineTotalTime();

  // Get circle element for spinner
  const circleEl = document.getElementById(`pipeline-circle-${stepId}`);
  
  if (status === "active") {
    el.classList.add("active");
    // Add spinner to circle
    if (circleEl) {
      circleEl.classList.add("spinning");
    }
    // Mark previous steps as done
    PIPELINE_STEPS.forEach((s, i) => {
      if (i < stepIndex) {
        const prevEl = pipelineGraph.querySelector(`[data-step-id="${s.id}"]`);
        const prevStateEl = document.getElementById(`node-state-${s.id}`);
        const prevCircleEl = document.getElementById(`pipeline-circle-${s.id}`);
        if (prevEl && prevStateEl) {
          prevEl.classList.add("done");
          prevEl.classList.remove("active");
          // Keep previous duration if available (preserve existing text)
          // But don't overwrite "skipped" status
          const currentText = prevStateEl.textContent;
          if (!currentText.includes("(") && currentText !== "skipped") {
            prevStateEl.textContent = "done";
          }
          // Remove spinner from previous step
          if (prevCircleEl) {
            prevCircleEl.classList.remove("spinning");
          }
        }
      }
    });
    
    // Special handling: if spatial_temporal_filter becomes active and hitl_confirmation is still idle, mark hitl_confirmation as skipped
    if (stepId === "spatial_temporal_filter") {
      const hitlEl = pipelineGraph.querySelector(`[data-step-id="hitl_confirmation"]`);
      const hitlStateEl = document.getElementById(`node-state-hitl_confirmation`);
      if (hitlEl && hitlStateEl && !hitlEl.classList.contains("done") && !hitlEl.classList.contains("active")) {
        // HITL was skipped - mark it as skipped
        hitlEl.classList.add("done");
        hitlStateEl.textContent = "skipped";
      }
    }
  } else if (status === "done") {
    el.classList.add("done");
    el.classList.remove("active");
    // Remove spinner when done
    if (circleEl) {
      circleEl.classList.remove("spinning");
    }
  } else if (status === "skipped") {
    el.classList.add("done");
    el.classList.remove("active");
    stateEl.textContent = "skipped";
    // Remove spinner
    if (circleEl) {
      circleEl.classList.remove("spinning");
    }
  } else {
    el.classList.remove("active", "done");
    // Remove spinner
    if (circleEl) {
      circleEl.classList.remove("spinning");
    }
  }
}

function connectStatusStream() {
  // Close existing connection if any
  if (statusEventSource) {
    // Clear health check interval if exists
    if (statusEventSource._healthCheckInterval) {
      clearInterval(statusEventSource._healthCheckInterval);
    }
    statusEventSource.close();
    statusEventSource = null;
  }

  // If no conversation, ensure pipeline is idle and don't connect SSE
  if (!currentConversationId) {
    // Ensure pipeline is idle when no conversation
    if (pipelineGraph) {
      PIPELINE_STEPS.forEach(step => {
        setPipelineStepState(step.id, "idle");
      });
    }
    return;
  }
  
  const statusUrl = `${API_URL.replace('/query', '/status')}?conversationId=${encodeURIComponent(currentConversationId)}`;
  statusEventSource = new EventSource(statusUrl);

  statusEventSource.addEventListener('status', (event) => {
    try {
      const data = JSON.parse(event.data);
      const { stage, status, duration } = data;
      if (stage && status) {
        // Handle general_response as a special case - map it to intent_parsing step for display
        if (stage === "general_response") {
          setPipelineStepState("intent_parsing", status, duration); // Map to intent_parsing step for display
          if (stageText) {
            stageText.textContent = "General response";
          }
        } else {
          // If spatial_temporal_filter becomes active and hitl_confirmation is still idle, mark hitl_confirmation as skipped
          if (stage === "spatial_temporal_filter" && status === "active") {
            const hitlEl = pipelineGraph?.querySelector(`[data-step-id="hitl_confirmation"]`);
            const hitlStateEl = document.getElementById(`node-state-hitl_confirmation`);
            if (hitlEl && hitlStateEl && !hitlEl.classList.contains("done") && !hitlEl.classList.contains("active")) {
              // HITL was skipped - mark it as skipped
              hitlEl.classList.add("done");
              hitlStateEl.textContent = "skipped";
            }
          }
          
          setPipelineStepState(stage, status, duration);
          if (stageText) {
            stageText.textContent = stage || "idle";
          }
        }
      }
    } catch (e) {
      // Status event parsing error - silently ignore
    }
  });
  
  // Handle real-time log events
  statusEventSource.addEventListener('log', (event) => {
    try {
      const data = JSON.parse(event.data);
      const { ts, stage, message } = data;
      if (ts && stage && message) {
        // Add log immediately to frontend
        addLogs([{ ts, stage, message }]);
        
        // Check if this is the "received action=message" log - show pipeline progress immediately
        // Log format: stage="request", message="received action=message, query_len=66"
        // Also check for variations in message format
        const isRequestLog = stage === "request" && message && (
          message.includes("received action=message") || 
          message.match(/received\s+action\s*=\s*message/i) ||
          message.startsWith("received action=")
        );
        
        if (isRequestLog) {
          // Extract query length from message (format: "received action=message, query_len=66")
          const queryLenMatch = message.match(/query_len\s*=\s*(\d+)/);
          const queryLen = queryLenMatch ? parseInt(queryLenMatch[1]) : 0;
          
          // Create request received message
          const requestMessage = `I've received your request. Let me start processing it...`;
          
          // Check if request_received is already in history (avoid duplicates)
          const alreadyExists = window.pipelineMessageHistory.some(item => item.stage === "request_received");
          
          if (!alreadyExists) {
            // Add to history first (at the beginning to maintain chronological order)
            window.pipelineMessageHistory.unshift({
              stage: "request_received",
              status: "done",
              message: requestMessage,
              dimension_candidates: null,
              timestamp: Date.now()
            });
          }
          
          // Create or update pipeline message box
          if (!window.pipelineMessageBox) {
            // Create pipeline message box immediately
            window.pipelineMessageBox = document.createElement("div");
            window.pipelineMessageBox.className = "message-row assistant pipeline-progress-box";
            window.pipelineMessageBox.id = "pipeline-progress-box";
            
            const avatarClass = "avatar-assistant";
            // Initial state: show animated spinner (request received is active)
            window.pipelineMessageBox.innerHTML = `
              <div class="message-bubble-wrapper">
                <div class="avatar-circle ${avatarClass} pipeline-avatar-active"><div class="pipeline-avatar-spinner"></div></div>
                <div>
                  <div class="message-meta">IGDD · Pipeline Progress</div>
                  <div class="message-bubble assistant pipeline-progress-bubble">
                    <div class="pipeline-progress-content"></div>
                  </div>
                </div>
              </div>
            `;
            
            // Add click handler to show full history in toast
            const bubble = window.pipelineMessageBox.querySelector(".pipeline-progress-bubble");
            if (bubble) {
              bubble.style.cursor = "pointer";
              bubble.addEventListener("click", () => {
                showPipelineProgressToast(window.pipelineMessageHistory);
              });
            }
            
            // Show "request received" message
            const contentDiv = window.pipelineMessageBox.querySelector(".pipeline-progress-content");
            if (contentDiv) {
              contentDiv.innerHTML = `<div class="assistant-reply">${renderMarkdown(requestMessage)}</div>`;
            }
            
            // Insert after user message (if chatMessages exists)
            if (chatMessages) {
              // Find the last user message and insert after it
              const messageRows = chatMessages.querySelectorAll('.message-row.user');
              if (messageRows.length > 0) {
                const lastUserMessage = messageRows[messageRows.length - 1];
                if (!chatMessages.contains(window.pipelineMessageBox)) {
                  if (lastUserMessage.nextSibling) {
                    chatMessages.insertBefore(window.pipelineMessageBox, lastUserMessage.nextSibling);
                  } else {
                    chatMessages.appendChild(window.pipelineMessageBox);
                  }
                }
              } else {
                // No user messages yet, append at end
                if (!chatMessages.contains(window.pipelineMessageBox)) {
                  chatMessages.appendChild(window.pipelineMessageBox);
                }
              }
              chatMessages.scrollTop = chatMessages.scrollHeight;
            }
          } else {
            // Pipeline box already exists, just update it to show request_received message
            const contentDiv = window.pipelineMessageBox.querySelector(".pipeline-progress-content");
            if (contentDiv) {
              contentDiv.innerHTML = `<div class="assistant-reply">${renderMarkdown(requestMessage)}</div>`;
            }
            // Scroll to show the updated message
            if (chatMessages) {
              chatMessages.scrollTop = chatMessages.scrollHeight;
            }
          }
        }
      }
    } catch (e) {
      // Log event parsing error - silently ignore
    }
  });
  
  // Handle real-time intent updates
  statusEventSource.addEventListener('intent', (event) => {
    try {
      const intent = JSON.parse(event.data);
      if (intent) {
        // Update intent panel immediately
        renderIntentPanel(intent);
        if (intentPanel) {
          intentPanel.style.display = 'block';
        }
      }
    } catch (e) {
      // Intent event parsing error - silently ignore
    }
  });
  
  // Handle real-time pipeline progress messages (display in chat panel)
  // Use a single updatable message box that shows the latest stage
  // Declare in global scope so it can be accessed from sendToBackend
  window.pipelineMessageBox = null;
  window.pipelineMessageHistory = []; // Store all messages for toast display
  
  statusEventSource.addEventListener('pipeline_message', (event) => {
    try {
      const data = JSON.parse(event.data);
      const { message, stage, status, dimension_candidates } = data;
      if (message) {
      // Store message in history (avoid duplicates for request_received)
      // Check if this is request_received and already exists
      if (stage === "request_received") {
        const alreadyExists = window.pipelineMessageHistory.some(item => item.stage === "request_received");
        if (alreadyExists) {
          // Skip adding duplicate request_received
          return;
        }
      }
      
      window.pipelineMessageHistory.push({
        stage,
        status,
        message,
        dimension_candidates,
        timestamp: Date.now()
      });
        
        // Create or update the single pipeline message box
        if (!window.pipelineMessageBox) {
          // Create new pipeline message box
          window.pipelineMessageBox = document.createElement("div");
          window.pipelineMessageBox.className = "message-row assistant pipeline-progress-box";
          window.pipelineMessageBox.id = "pipeline-progress-box";
          
          const avatarClass = "avatar-assistant";
          // Show animated spinner if status is active, otherwise show IGDD icon
          const avatarContent = status === "active" 
            ? '<div class="pipeline-avatar-spinner"></div>'
            : '<img src="igdd_logo.png" alt="IGDD" class="avatar-icon" />';
          const avatarActiveClass = status === "active" ? " pipeline-avatar-active" : "";
          window.pipelineMessageBox.innerHTML = `
            <div class="message-bubble-wrapper">
              <div class="avatar-circle ${avatarClass}${avatarActiveClass}">${avatarContent}</div>
              <div>
                <div class="message-meta">IGDD · Pipeline Progress</div>
                <div class="message-bubble assistant pipeline-progress-bubble">
                  <div class="pipeline-progress-content"></div>
                </div>
              </div>
            </div>
          `;
          
          if (chatMessages) {
            chatMessages.appendChild(window.pipelineMessageBox);
            chatMessages.scrollTop = chatMessages.scrollHeight;
          }
          
          // Add click handler to show full history in toast
          const bubble = window.pipelineMessageBox.querySelector(".pipeline-progress-bubble");
          if (bubble) {
            bubble.style.cursor = "pointer";
            bubble.addEventListener("click", () => {
              showPipelineProgressToast(window.pipelineMessageHistory);
            });
          }
        }
        
        // Update avatar based on status (active = animated loading, done = IGDD icon)
        const avatarDiv = window.pipelineMessageBox.querySelector(".avatar-circle");
        if (avatarDiv) {
          if (status === "active") {
            // Show animated loading spinner
            avatarDiv.innerHTML = '<div class="pipeline-avatar-spinner"></div>';
            avatarDiv.classList.add("pipeline-avatar-active");
          } else {
            // Show IGDD icon
            avatarDiv.innerHTML = '<img src="igdd_logo.png" alt="IGDD" class="avatar-icon" />';
            avatarDiv.classList.remove("pipeline-avatar-active");
          }
        }
        
        // Update the content of the existing box
        const contentDiv = window.pipelineMessageBox.querySelector(".pipeline-progress-content");
        if (contentDiv) {
          const parts = [];
          parts.push(`<div class="assistant-reply">${renderMarkdown(String(message))}</div>`);
          
          // If there are candidates, render them as clickable options
          // BUT: Only wire selection handlers if there's no pending HITL that needs user input
          // Check if there's a pending HITL in the current response (if available)
          // Note: In SSE pipeline_message events, we don't have full response data, so we need to be careful
          // We'll check if there are any HITL panels already rendered that need user input
          if (dimension_candidates && Object.keys(dimension_candidates).length > 0) {
            const candidatesTimestamp = Date.now();
            const candidatesPanel = renderDimensionCandidates(dimension_candidates, candidatesTimestamp, false);
            if (candidatesPanel) {
              parts.push(candidatesPanel);
            }
            
            // Wire candidates selection handlers ONLY if there's no HITL that needs user input
            // Check for existing HITL panels that might need user input (no candidates)
            setTimeout(() => {
              // Check if there's a pending HITL panel that needs user input (no candidates)
              const hitlPanels = document.querySelectorAll('[id^="chat-hitl-options-"]');
              let hasHitlNeedingInput = false;
              for (const panel of hitlPanels) {
                const actionsContainer = panel.querySelector('[id$="-actions"]');
                // If HITL panel exists but has no candidate checkboxes, it needs user input
                const hasCandidates = panel.querySelectorAll('.chat-option-checkbox').length > 0;
                if (!hasCandidates && actionsContainer) {
                  hasHitlNeedingInput = true;
                  break;
                }
              }
              
              // Only wire dimension candidates if HITL doesn't need user input
              if (!hasHitlNeedingInput) {
                wireDimensionCandidatesSelection(dimension_candidates, candidatesTimestamp);
              } else {
                // HITL needs user input - ensure input is enabled
                hasPendingCandidates = false;
                setReadOnlyMode(isReadOnlyMode);
              }
            }, 100);
          }
          
          contentDiv.innerHTML = parts.join("\n");
          
          // Scroll to show the updated message
          if (chatMessages) {
            chatMessages.scrollTop = chatMessages.scrollHeight;
          }
        }
      }
    } catch (e) {
      // Pipeline message event parsing error - silently ignore
      console.error("Error parsing pipeline message:", e);
    }
  });

  statusEventSource.onerror = (error) => {
    // Reconnect after a delay if connection is closed
    setTimeout(() => {
      if (statusEventSource && statusEventSource.readyState === EventSource.CLOSED) {
        console.log('[SSE] Connection closed, reconnecting...');
        connectStatusStream();
      }
    }, 2000);
  };
  
  // Add periodic connection health check (every 5 minutes)
  // This ensures we detect and reconnect if connection silently fails
  const healthCheckInterval = setInterval(() => {
    if (statusEventSource) {
      // Check if connection is still open
      if (statusEventSource.readyState === EventSource.CLOSED) {
        console.log('[SSE] Connection closed detected by health check, reconnecting...');
        clearInterval(healthCheckInterval);
        connectStatusStream();
      } else if (statusEventSource.readyState === EventSource.CONNECTING) {
        // Connection is still connecting, wait a bit longer
        console.log('[SSE] Connection still connecting...');
      }
      // If OPEN, connection is healthy, do nothing
    } else {
      // No connection exists, reconnect if we have a conversation
      if (currentConversationId) {
        console.log('[SSE] No connection exists, reconnecting...');
        clearInterval(healthCheckInterval);
        connectStatusStream();
      } else {
        // No conversation, stop health check
        clearInterval(healthCheckInterval);
      }
    }
  }, 5 * 60 * 1000); // Check every 5 minutes
  
  // Store interval ID so we can clear it when reconnecting
  if (statusEventSource) {
    statusEventSource._healthCheckInterval = healthCheckInterval;
  }
}

function setStatus(stage, datasetN) {
  if (stageText) stageText.textContent = stage || "idle";
  if (datasetsCount) datasetsCount.textContent = String(datasetN ?? 0);
  setPipelineState(stage || "idle");
}

function makeSpinner() {
  const s = document.createElement("span");
  s.className = "spinner";
  return s;
}

// Enable/disable input for read-only mode
function setReadOnlyMode(readOnly) {
  isReadOnlyMode = readOnly;
  if (messageInput) {
    // If there are pending candidates, keep input disabled regardless of readOnly state
    messageInput.disabled = readOnly || hasPendingCandidates;
    messageInput.placeholder = readOnly 
      ? "This is a shared conversation. You can view it but cannot send messages." 
      : hasPendingCandidates
      ? "Please select candidates above first"
      : "Message IGDD (describe any dataset constraints).";
  }
  if (sendBtn) {
    // If there are pending candidates, keep send button disabled regardless of other states
    sendBtn.disabled = isSending || readOnly || hasPendingCandidates;
  }
}

function setSending(sending) {
  isSending = sending;
  if (!sendBtn) return;

  // If there are pending candidates, keep send button disabled regardless of sending state
  sendBtn.disabled = sending || isReadOnlyMode || hasPendingCandidates;

  if (sending) {
    sendBtn.classList.add("loading");
    sendBtn.innerHTML = "";
    sendBtn.appendChild(makeSpinner());
  } else {
    sendBtn.classList.remove("loading");
    sendBtn.innerHTML = `<span class="send-icon">➤</span>`;
  }
}

function appendMessage(role, content) {
  if (!chatMessages) return;

  const row = document.createElement("div");
  row.className = `message-row ${role}`;

  const avatarClass = role === "assistant" ? "avatar-assistant" : "avatar-user";
  const avatarContent = role === "assistant" 
    ? '<img src="igdd_logo.png" alt="IGDD" class="avatar-icon" />'
    : '<svg class="avatar-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M12 12C14.7614 12 17 9.76142 17 7C17 4.23858 14.7614 2 12 2C9.23858 2 7 4.23858 7 7C7 9.76142 9.23858 12 12 12Z" fill="currentColor"/><path d="M12 14C7.58172 14 4 16.6863 4 20V22H20V20C20 16.6863 16.4183 14 12 14Z" fill="currentColor"/></svg>';

  // Use markdown rendering for assistant messages, plain text for user messages
  const contentHtml = role === "assistant" 
    ? renderMarkdown(content)
    : escapeHtml(content).replace(/\n/g, "<br/>");

  row.innerHTML = `
    <div class="message-bubble-wrapper">
      <div class="avatar-circle ${avatarClass}">${avatarContent}</div>
      <div>
        <div class="message-meta">${role === "assistant" ? "IGDD" : "You"} · ${nowTime()}</div>
        <div class="message-bubble ${role}">
          ${contentHtml}
        </div>
      </div>
    </div>
  `;

  chatMessages.appendChild(row);
  chatMessages.scrollTop = chatMessages.scrollHeight;
  return row;
}

/**
 * Append a single assistant bubble that contains rich HTML.
 * IMPORTANT: This is used to ensure one backend response -> one bubble.
 */
function appendAssistantBubbleHtml(html) {
  if (!chatMessages) return null;

  const row = document.createElement("div");
  row.className = "message-row assistant";

  row.innerHTML = `
    <div class="message-bubble-wrapper">
      <div class="avatar-circle avatar-assistant"><img src="igdd_logo.png" alt="IGDD" class="avatar-icon" /></div>
      <div>
        <div class="message-meta">IGDD · ${nowTime()}</div>
        <div class="message-bubble assistant"></div>
      </div>
    </div>
  `;

  const bubble = row.querySelector(".message-bubble.assistant");
  if (bubble) bubble.innerHTML = html;

  chatMessages.appendChild(row);
  chatMessages.scrollTop = chatMessages.scrollHeight;
  return row;
}

// (deprecated) kept for backwards compatibility; now prefer appendAssistantBubbleHtml
function appendAssistantWithHtml(content, html) {
  // Use Markdown rendering for LLM output
  const safeText = renderMarkdown(content);
  const full = `
    <div class="assistant-reply">${safeText}</div>
    ${html || ""}
  `;
  appendAssistantBubbleHtml(full);
}

function compactText(s, max = 220) {
  if (!s) return "";
  const t = String(s).replace(/\s+/g, " ").trim();
  if (t.length <= max) return t;
  return t.slice(0, max) + "…";
}

// Initialize space maps for all datasets
function initializeSpaceMaps() {
  if (!window.datasetSpaceMaps) return;
  
  Object.entries(window.datasetSpaceMaps).forEach(([mapId, bboxes]) => {
    const mapElement = document.getElementById(mapId);
    if (!mapElement || mapElement._leaflet) return; // Already initialized
    
    // Calculate bounds from all bboxes
    let minLat = Infinity, minLon = Infinity, maxLat = -Infinity, maxLon = -Infinity;
    bboxes.forEach(bbox => {
      const [w, s, e, n] = bbox;
      minLon = Math.min(minLon, w);
      minLat = Math.min(minLat, s);
      maxLon = Math.max(maxLon, e);
      maxLat = Math.max(maxLat, n);
    });
    
    // Validate bounds
    if (!isFinite(minLat) || !isFinite(minLon) || !isFinite(maxLat) || !isFinite(maxLon)) {
      return;
    }
    
    // Create map
    const map = L.map(mapId, {
      attributionControl: false
    });
    
    // Add tile layer
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '© OpenStreetMap contributors'
    }).addTo(map);
    
    // Add shapes (point, line, or rectangle) for each bbox
    const shapes = [];
    const EPSILON = 1e-6; // Threshold for floating point comparison
    
    bboxes.forEach((bbox, idx) => {
      const [w, s, e, n] = bbox;
      const color = idx === 0 ? "#10b981" : "#3b82f6";
      const popupText = `Bbox ${idx + 1}: [${w.toFixed(4)}, ${s.toFixed(4)}, ${e.toFixed(4)}, ${n.toFixed(4)}]`;
      let shape;
      
      // Check if bbox is a point (width and height are both zero or very small)
      const width = Math.abs(e - w);
      const height = Math.abs(n - s);
      
      if (width < EPSILON && height < EPSILON) {
        // Point: use circle marker
        const centerLat = (s + n) / 2;
        const centerLon = (w + e) / 2;
        shape = L.circleMarker([centerLat, centerLon], {
          radius: 8,
          fillColor: color,
          color: color,
          weight: 2,
          fillOpacity: 0.8
        }).addTo(map);
      } else if (width < EPSILON) {
        // Vertical line (north-south): use polyline
        shape = L.polyline([[s, w], [n, e]], {
          color: color,
          weight: 3,
          opacity: 0.8
        }).addTo(map);
      } else if (height < EPSILON) {
        // Horizontal line (east-west): use polyline
        shape = L.polyline([[s, w], [n, e]], {
          color: color,
          weight: 3,
          opacity: 0.8
        }).addTo(map);
      } else {
        // Rectangle: use rectangle
        shape = L.rectangle([[s, w], [n, e]], {
          color: color,
          fillColor: color,
          fillOpacity: 0.2,
          weight: 2
        }).addTo(map);
      }
      
      // Add popup with bbox info
      shape.bindPopup(popupText);
      shapes.push(shape);
    });
    
    // Fit map to bounds - use shape bounds if available, otherwise use calculated bounds
    if (shapes.length > 0) {
      // Use the bounds from all shapes
      const group = new L.featureGroup(shapes);
      const bounds = group.getBounds();
      // For points, ensure minimum zoom level
      if (bounds.getNorth() - bounds.getSouth() < 0.001 && bounds.getEast() - bounds.getWest() < 0.001) {
        // Point case: set a reasonable zoom level
        map.setView(bounds.getCenter(), 15);
      } else {
        map.fitBounds(bounds.pad(0.1), {
          padding: [20, 20],
          maxZoom: 15
        });
      }
    } else {
      // Fallback to calculated bounds
      const bounds = [[minLat, minLon], [maxLat, maxLon]];
      map.fitBounds(bounds, {
        padding: [20, 20],
        maxZoom: 15
      });
    }
    
    mapElement._leaflet = map; // Mark as initialized
  });
}

// Toggle resource visibility
function toggleResource(resourceId) {
  const resourceContent = document.getElementById(resourceId);
  const resourceIcon = document.getElementById(resourceId + "-icon");
  if (!resourceContent || !resourceIcon) return;
  
  const isVisible = resourceContent.style.display !== "none";
  resourceContent.style.display = isVisible ? "none" : "grid";
  resourceIcon.textContent = isVisible ? "▼" : "▲";
}

// Toggle space map visibility
function toggleSpaceMap(mapId) {
  const mapElement = document.getElementById(mapId);
  if (!mapElement) return;
  
  const isVisible = mapElement.style.display !== "none";
  mapElement.style.display = isVisible ? "none" : "block";
  
  // Initialize map if not already done
  if (!isVisible && !mapElement._leaflet && window.datasetSpaceMaps && window.datasetSpaceMaps[mapId]) {
    setTimeout(() => {
      initializeSpaceMaps();
      // After initialization, ensure proper fit
      setTimeout(() => {
        const map = mapElement._leaflet;
        if (map && window.datasetSpaceMaps && window.datasetSpaceMaps[mapId]) {
          const bboxes = window.datasetSpaceMaps[mapId];
          if (bboxes && bboxes.length > 0) {
            // Recalculate bounds
            let minLat = Infinity, minLon = Infinity, maxLat = -Infinity, maxLon = -Infinity;
            bboxes.forEach(bbox => {
              const [w, s, e, n] = bbox;
              minLon = Math.min(minLon, w);
              minLat = Math.min(minLat, s);
              maxLon = Math.max(maxLon, e);
              maxLat = Math.max(maxLat, n);
            });
            
            if (isFinite(minLat) && isFinite(minLon) && isFinite(maxLat) && isFinite(maxLon)) {
              const bounds = [[minLat, minLon], [maxLat, maxLon]];
              map.fitBounds(bounds, {
                padding: [20, 20],
                maxZoom: 15
              });
            }
          }
        }
      }, 150);
    }, 50);
  }
  
  // Resize map when shown and refit bounds
  if (!isVisible && mapElement._leaflet) {
    setTimeout(() => {
      const map = mapElement._leaflet;
      map.invalidateSize();
      
      // Refit bounds after resize
      if (window.datasetSpaceMaps && window.datasetSpaceMaps[mapId]) {
        const bboxes = window.datasetSpaceMaps[mapId];
        if (bboxes && bboxes.length > 0) {
          let minLat = Infinity, minLon = Infinity, maxLat = -Infinity, maxLon = -Infinity;
          bboxes.forEach(bbox => {
            const [w, s, e, n] = bbox;
            minLon = Math.min(minLon, w);
            minLat = Math.min(minLat, s);
            maxLon = Math.max(maxLon, e);
            maxLat = Math.max(maxLat, n);
          });
          
          if (isFinite(minLat) && isFinite(minLon) && isFinite(maxLat) && isFinite(maxLon)) {
            const bounds = [[minLat, minLon], [maxLat, maxLon]];
            map.fitBounds(bounds, {
              padding: [20, 20],
              maxZoom: 15
            });
          }
        }
      }
    }, 100);
  }
}

function pickFirstName(arr) {
  if (!Array.isArray(arr) || arr.length === 0) return null;
  const a0 = arr[0]?.props || arr[0];
  return a0?.name || a0?.title || a0?.value || null;
}

/**
 * Per dataset: text + list (kv rows). Consistent layout; no global "list then summary" duplication.
 * @param {boolean} isReadOnly - If true, candidates are already selected (for restored conversations)
 */
function renderDimensionCandidates(dimensionCandidates, timestamp, isReadOnly = false) {
  if (!dimensionCandidates || typeof dimensionCandidates !== "object") return "";
  
  const panels = [];
  for (const [dimension, candidates] of Object.entries(dimensionCandidates)) {
    if (!Array.isArray(candidates) || candidates.length === 0) {
      panels.push(`
        <div class="chat-panel">
          <div class="chat-panel-title">${escapeHtml(dimension)}</div>
          <div class="chat-note">No matching entities found (similarity > 0).</div>
        </div>
      `);
      continue;
    }
    
    const panelId = `dimension-candidates-${dimension.toLowerCase()}-${timestamp}`;
    const titleText = isReadOnly 
      ? `${escapeHtml(dimension)} - Selected candidates (${candidates.length})`
      : `${escapeHtml(dimension)} - Please select (up to ${candidates.length}, similarity > 0)`;
    const continueBtnDisabled = isReadOnly ? ' disabled' : '';
    panels.push(`
      <div class="chat-panel">
        <div class="chat-panel-title">${titleText}</div>
        <div class="chat-options" id="${escapeHtml(panelId)}"></div>
        <div class="chat-hitl-actions" id="${escapeHtml(panelId)}-actions">
          <button class="chat-continue-btn" id="${escapeHtml(panelId)}-continue" type="button"${continueBtnDisabled}>
            Continue (use all top candidates)
          </button>
        </div>
      </div>
    `);
  }
  
  return panels.join("");
}

function wireDimensionCandidatesSelection(dimensionCandidates, timestamp) {
  // Track submission state for all dimensions
  const dimensionCount = Object.keys(dimensionCandidates).filter(
    (dim) => Array.isArray(dimensionCandidates[dim]) && dimensionCandidates[dim].length > 0
  ).length;
  const submittedDimensions = new Set(); // Track which dimensions have been submitted
  const dimensionSelections = {}; // Store selections for each dimension
  
  // CRITICAL: Before setting hasPendingCandidates, check if there's a HITL that needs user input
  // If HITL needs user input (no candidates), we should NOT disable input for dimension candidates
  // Check for existing HITL panels that might need user input (no candidates)
  const hitlPanels = document.querySelectorAll('[id^="chat-hitl-options-"]');
  let hasHitlNeedingInput = false;
  for (const panel of hitlPanels) {
    const actionsContainer = panel.querySelector('[id$="-actions"]');
    // If HITL panel exists but has no candidate checkboxes, it needs user input
    const hasCandidates = panel.querySelectorAll('.chat-option-checkbox').length > 0;
    if (!hasCandidates && actionsContainer) {
      hasHitlNeedingInput = true;
      break;
    }
  }
  
  // Disable input and send button when candidates are present (non-auto mode)
  // BUT: Only if there's no HITL that needs user input
  if (dimensionCount > 0 && !hasHitlNeedingInput) {
    hasPendingCandidates = true;
    updateInputAndSendButtonState();
  } else if (hasHitlNeedingInput) {
    // HITL needs user input - ensure input is enabled
    hasPendingCandidates = false;
    updateInputAndSendButtonState();
  }
  
  // Function to update input and send button state based on pending candidates
  function updateInputAndSendButtonState() {
    // Use setReadOnlyMode to ensure state is consistent with global state management
    // setReadOnlyMode already handles hasPendingCandidates check
    setReadOnlyMode(isReadOnlyMode);
    // Also explicitly update send button state to account for isSending
    if (sendBtn) {
      sendBtn.disabled = isSending || isReadOnlyMode || hasPendingCandidates;
    }
  }
  
  // Function to check if all dimensions are submitted and send if so
  const checkAndSendAll = async () => {
    if (submittedDimensions.size === dimensionCount) {
      // All dimensions submitted - re-enable input and send button
      hasPendingCandidates = false;
      updateInputAndSendButtonState();
      // All dimensions submitted, now display messages and send to backend
      const allMessages = [];
      const dimensionOrder = ['Topic', 'Format', 'License', 'Organization', 'Source'];
      const orderedSelections = [];
      
      // Sort selections by dimension order
      for (const dim of dimensionOrder) {
        if (dimensionSelections[dim]) {
          orderedSelections.push(dimensionSelections[dim]);
          allMessages.push(dimensionSelections[dim].displayText);
        }
      }
      
      // Also include any dimensions not in the standard order
      for (const [dim, selection] of Object.entries(dimensionSelections)) {
        if (!dimensionOrder.includes(dim)) {
          orderedSelections.push(selection);
          allMessages.push(selection.displayText);
        }
      }
      
      // Display all user messages first
      for (const msg of allMessages) {
        appendMessage("user", msg);
      }
      
      // Send all queries combined as a single request (backend now supports multiple selections)
      if (orderedSelections.length > 0) {
        const combinedQuery = orderedSelections.map(s => s.query).join("; ");
        await sendToBackend({ 
          query: combinedQuery, 
          action: "message"
        });
      }
    }
  };
  
  for (const [dimension, candidates] of Object.entries(dimensionCandidates)) {
    if (!Array.isArray(candidates) || candidates.length === 0) {
      continue;
    }
    
    const panelId = `dimension-candidates-${dimension.toLowerCase()}-${timestamp}`;
    const container = document.getElementById(panelId);
    if (!container) {
      continue;
    }
    
    const selectedIndices = new Set();
    let submitBtn = null;
    let updateSubmitButtonState = null;
    let isSubmitted = false; // Track if submit has been clicked
    
    // Wire submit button first (so we can reference it in checkbox handlers)
    const actionsContainer = document.getElementById(`${panelId}-actions`);
    if (actionsContainer) {
      submitBtn = document.createElement("button");
      submitBtn.className = "chat-submit-btn";
      submitBtn.type = "button";
      submitBtn.textContent = "Submit selected";
      submitBtn.style.marginLeft = "8px";
      // Initially disabled if no selection
      submitBtn.disabled = true;
      
      // Function to update submit button state
      updateSubmitButtonState = () => {
        if (submitBtn && !isSubmitted) {
          // Only update state if not already submitted
          submitBtn.disabled = selectedIndices.size === 0;
        }
      };
      
      submitBtn.addEventListener("click", async () => {
        if (selectedIndices.size === 0 || isSubmitted) {
          // Should not happen as button is disabled, but just in case
          return;
        }
        
        // Mark as submitted and disable both buttons immediately
        isSubmitted = true;
        submitBtn.disabled = true;
        const continueBtn = document.getElementById(`${panelId}-continue`);
        if (continueBtn) continueBtn.disabled = true;
        
        // Get selected candidate names for display
        const selectedNames = Array.from(selectedIndices)
          .sort((a, b) => a - b)
          .map(i => {
            const c = candidates[i];
            return c.name || c.title || c.value || "Unknown";
          })
          .join(", ");
        
        const selection = Array.from(selectedIndices)
          .sort((a, b) => a - b)
          .map(i => String(i + 1))
          .join(",");
        
        const displayText = `${dimension}: ${selectedNames}`;
        const query = `${dimension}:${selection}`;
        
        // Store selection for this dimension (don't send yet)
        dimensionSelections[dimension] = { displayText, query };
        submittedDimensions.add(dimension);
        
        // Check if all dimensions are submitted, then send
        await checkAndSendAll();
      });
      
      actionsContainer.appendChild(submitBtn);
    }
    
    candidates.forEach((c, idx) => {
      const name = c.name || c.title || c.value || c.nodeId || "Candidate";
      const score = (typeof c.score === "number") ? c.score : 0;
      
      const row = document.createElement("div");
      row.className = "chat-option chat-option-multiselect";
      row.dataset.index = idx;
      row.dataset.dimension = dimension;
      
      const checkboxId = `${panelId}-checkbox-${idx}`;
      row.innerHTML = `
        <input type="checkbox" id="${escapeHtml(checkboxId)}" class="chat-option-checkbox" />
        <label for="${escapeHtml(checkboxId)}" class="chat-option-label">
          <span class="chat-option-badge">${idx + 1}</span>
          <div>
            <div class="chat-option-title">${escapeHtml(name)}</div>
            <div class="chat-option-subtitle">similarity=${score.toFixed(3)}</div>
          </div>
        </label>
      `;
      
      const checkbox = row.querySelector(`#${checkboxId}`);
      checkbox.addEventListener("change", (e) => {
        if (e.target.checked) {
          selectedIndices.add(idx);
          row.classList.add("selected");
        } else {
          selectedIndices.delete(idx);
          row.classList.remove("selected");
        }
        // Update submit button state
        if (updateSubmitButtonState) updateSubmitButtonState();
      });
      
      row.addEventListener("click", (e) => {
        if (e.target.tagName !== "INPUT" && e.target.tagName !== "LABEL") {
          checkbox.checked = !checkbox.checked;
          checkbox.dispatchEvent(new Event("change"));
        }
      });
      
      container.appendChild(row);
    });
    
    // Wire Continue button
    const continueBtn = document.getElementById(`${panelId}-continue`);
    if (continueBtn) {
      continueBtn.addEventListener("click", async () => {
        // Mark as submitted and disable both buttons immediately
        isSubmitted = true;
        continueBtn.disabled = true;
        if (submitBtn) submitBtn.disabled = true;
        
        // Get all candidate names for display
        const allNames = candidates.map(c => c.name || c.title || c.value || "Unknown").join(", ");
        const displayText = `${dimension}: ${allNames}`;
        const query = `${dimension}:continue`;
        
        // Store selection for this dimension
        dimensionSelections[dimension] = { displayText, query };
        submittedDimensions.add(dimension);
        
        // Check if all dimensions are submitted, then send
        await checkAndSendAll();
      });
    }
  }
}

/**
 * Convert dimension selection query format (e.g., "Topic:1,2; Format:continue") to display text with candidate names
 * @param {string} query - Query string like "Topic:1,2; Format:continue"
 * @param {Object} dimensionCandidates - Object mapping dimension names to candidate arrays
 * @returns {string} Display text like "Topic: candidate1, candidate2; Format: all candidates"
 */
function convertDimensionSelectionToDisplayText(query, dimensionCandidates) {
  if (!query || !dimensionCandidates) return query;
  
  const parts = query.split(';').map(part => part.trim()).filter(part => part);
  const displayParts = [];
  
  for (const part of parts) {
    const match = part.match(/^(\w+):(.+)$/i);
    if (!match) {
      displayParts.push(part);
      continue;
    }
    
    const dimension = match[1];
    const selection = match[2].trim();
    const candidates = dimensionCandidates[dimension] || dimensionCandidates[dimension.charAt(0).toUpperCase() + dimension.slice(1).toLowerCase()];
    
    if (!candidates || !Array.isArray(candidates)) {
      displayParts.push(part);
      continue;
    }
    
    if (selection === 'continue') {
      // Use all candidates
      const allNames = candidates.map(c => c.name || c.title || c.value || "Unknown").join(", ");
      displayParts.push(`${dimension}: ${allNames}`);
    } else {
      // Parse indices (e.g., "1,2,3")
      const indices = selection.split(',').map(s => parseInt(s.trim())).filter(n => !isNaN(n) && n > 0);
      const selectedNames = indices
        .map(idx => {
          if (idx <= candidates.length) {
            const c = candidates[idx - 1];
            return c.name || c.title || c.value || "Unknown";
          }
          return null;
        })
        .filter(name => name !== null)
        .join(", ");
      
      if (selectedNames) {
        displayParts.push(`${dimension}: ${selectedNames}`);
      } else {
        displayParts.push(part);
      }
    }
  }
  
  return displayParts.join("; ");
}

/**
 * Render restored candidates as read-only (for historical conversations)
 * Only check the candidates that were actually selected by the user (based on intent.kg_node_ids)
 * @param {Object} dimensionCandidates - Object mapping dimension names to candidate arrays
 * @param {number} timestamp - Timestamp for panel IDs
 * @param {Object} intent - Intent object containing kg_node_ids for each dimension
 */
function renderRestoredCandidates(dimensionCandidates, timestamp, intent = null) {
  for (const [dimension, candidates] of Object.entries(dimensionCandidates)) {
    if (!Array.isArray(candidates) || candidates.length === 0) {
      continue;
    }
    
    const panelId = `dimension-candidates-${dimension.toLowerCase()}-${timestamp}`;
    const container = document.getElementById(panelId);
    if (!container) continue;
    
    // Get selected node IDs for this dimension from intent
    let selectedNodeIds = new Set();
    if (intent) {
      const dimKey = dimension.toLowerCase();
      const dim = intent[dimKey];
      if (dim && dim.kg_node_ids && Array.isArray(dim.kg_node_ids)) {
        selectedNodeIds = new Set(dim.kg_node_ids);
      }
    }
    
    // Render candidates as read-only
    // Only check the ones that were actually selected
    candidates.forEach((c, idx) => {
      const name = c.name || c.title || c.value || c.nodeId || "Candidate";
      const score = (typeof c.score === "number") ? c.score : 0;
      const nodeId = c.nodeId;
      const isSelected = nodeId && selectedNodeIds.has(nodeId);
      
      const row = document.createElement("div");
      row.className = isSelected ? "chat-option chat-option-multiselect selected" : "chat-option chat-option-multiselect";
      row.dataset.index = idx;
      row.dataset.dimension = dimension;
      
      const checkboxId = `${panelId}-checkbox-${idx}`;
      row.innerHTML = `
        <input type="checkbox" id="${escapeHtml(checkboxId)}" class="chat-option-checkbox" ${isSelected ? 'checked' : ''} disabled />
        <label for="${escapeHtml(checkboxId)}" class="chat-option-label">
          <span class="chat-option-badge">${idx + 1}</span>
          <div>
            <div class="chat-option-title">${escapeHtml(name)}</div>
            <div class="chat-option-subtitle">similarity=${score.toFixed(3)}</div>
          </div>
        </label>
      `;
      
      container.appendChild(row);
    });
    
    // Add submit button (disabled) for consistency
    const actionsContainer = document.getElementById(`${panelId}-actions`);
    if (actionsContainer) {
      const submitBtn = document.createElement("button");
      submitBtn.className = "chat-submit-btn";
      submitBtn.type = "button";
      submitBtn.textContent = "Submit selected";
      submitBtn.disabled = true;
      submitBtn.style.marginLeft = "8px";
      actionsContainer.appendChild(submitBtn);
    }
  }
}

/**
 * Render text with "show more" functionality if it exceeds 2 lines
 * @param {string} text - The text to render
 * @param {string} className - CSS class name for the container
 * @param {string} uniqueId - Unique identifier for this text block
 * @returns {string} HTML string with show more functionality
 */
function renderTextWithShowMore(text, className, uniqueId) {
  if (!text || text.trim() === "") return "";
  
  const safeText = escapeHtml(text);
  // Use more unique ID including datasetId and field type to avoid conflicts across questions
  const textId = uniqueId || `text-${Date.now()}-${Math.floor(Math.random() * 100000)}`;
  
  // className should be applied to the outer container to preserve original styles
  // text-with-showmore is added for functionality
  // Show more link follows directly after text, with ellipsis before it
  return `
    <div class="${className} text-with-showmore" data-text-id="${textId}">
      <span class="text-content text-collapsed" id="${textId}-content">${safeText}</span>
      <span class="text-showmore-link" id="${textId}-link" onclick="event.preventDefault(); event.stopPropagation(); toggleTextShowMore('${textId}');" style="display: none;">show more</span>
    </div>
  `;
}

function renderDatasetBlocks(datasets, userIntent) {
  if (!datasets || !Array.isArray(datasets) || datasets.length === 0) return "";
  
  // Limit to top 10 if more than 10
  const limitedDatasets = datasets.slice(0, 10);
  const maxVisible = 2; // Show first 2, collapse the rest
  const hasMore = limitedDatasets.length > maxVisible;

  const blocks = limitedDatasets.map((bundle, idx) => {
    const isVisible = idx < maxVisible;
    const props = bundle.datasetProps || bundle.dataset_props || bundle.props || bundle || {};
    const linked = bundle.linkedEntities || bundle.linked_entities || {};
    // Try both camelCase and snake_case for score fields
    const matchScore = bundle.matchScore !== undefined ? bundle.matchScore : 
                      (bundle.match_score !== undefined ? bundle.match_score : null);
    
    const title = props.title || props.name || props.id || bundle.datasetId || `Dataset ${idx + 1}`;
    // Get description and notes separately - notes should be shown in full
    const desc = props.description || props.abstract || props.summary || "";
    const notes = props.notes || "";

    // Build entity information rows
    const rows = [];
    
    // Topic
    const topics = linked.Topic || [];
    if (topics.length > 0) {
      const topicNames = topics.map(t => t.props?.name || t.props?.title || t.name || "Topic").join(", ");
      rows.push(["Topic", topicNames]);
    }
    
    // License - show title with url link if available
    const licenses = linked.License || [];
    let licenseDisplay = null;
    if (licenses.length > 0) {
      const licenseItems = licenses.map(l => {
        const props = l.props || l || {};
        const title = props.title || props.license_title || props.name || l.name || "License";
        const url = props.url || props.license_url || props.link || props.landingPage || props.landing_page || null;
        return { title, url };
      });
      
      // Build license display: if any has URL, show as links
      if (licenseItems.some(item => item.url)) {
        licenseDisplay = {
          type: "links",
          items: licenseItems
        };
      } else {
        licenseDisplay = {
          type: "text",
          text: licenseItems.map(item => item.title).join(", ")
        };
      }
    }
    
    // Organization - show title only, with show more for long text
    const orgs = linked.Organization || [];
    if (orgs.length > 0) {
      const orgNames = orgs.map(o => {
        const props = o.props || o || {};
        return props.title || props.name || o.name || "Organization";
      }).join(", ");
      rows.push(["Organization", orgNames]);
    }
    
    // Source - show title with URL link if available
    const sources = linked.Source || [];
    let isDataGov = false;
    let sourceDisplay = null;
    if (sources.length > 0) {
      const sourceItems = sources.map(s => {
        const props = s.props || s || {};
        const title = props.title || props.name || s.name || "Source";
        const url = props.url || props.link || props.landingPage || props.landing_page || null;
        
        // Check if this is data.gov
        const nameLower = String(title).toLowerCase();
        if (nameLower.includes("data.gov") || nameLower === "data.gov") {
          isDataGov = true;
        }
        
        return { title, url };
      });
      
      // Build source display: if any has URL, show as links
      if (sourceItems.some(item => item.url)) {
        sourceDisplay = {
          type: "links",
          items: sourceItems
        };
      } else {
        sourceDisplay = {
          type: "text",
          text: sourceItems.map(item => item.title).join(", ")
        };
      }
    }
    
    // Space - show bbox if available, with map
    const spaces = linked.Space || [];
    let spaceMapHtml = "";
    if (spaces.length > 0) {
      const spaceBboxes = [];
      const spaceInfo = spaces.map((s, sIdx) => {
        const props = s.props || s || {};
        let bbox = null;
        
        // Try to get bbox first
        if (props.bbox && Array.isArray(props.bbox) && props.bbox.length === 4) {
          bbox = props.bbox;
        }
        // Try east, north, south, west
        else if (props.east != null && props.north != null && props.south != null && props.west != null) {
          bbox = [parseFloat(props.west), parseFloat(props.south), parseFloat(props.east), parseFloat(props.north)];
        }
        
        if (bbox) {
          const [minLon, minLat, maxLon, maxLat] = bbox;
          spaceBboxes.push({ bbox, idx: sIdx });
          return `[${minLon.toFixed(4)}, ${minLat.toFixed(4)}, ${maxLon.toFixed(4)}, ${maxLat.toFixed(4)}]`;
        }
        
        // Fallback to name
        return props.name || props.title || "Space";
      }).join(", ");
      
      rows.push(["Space", spaceInfo]);
      
      // Create map for this dataset if we have bboxes
      if (spaceBboxes.length > 0) {
        // Generate unique map ID
        const datasetId = bundle.datasetId || `dataset-${idx}`;
        const mapId = `space-map-${datasetId.replace(/[^a-zA-Z0-9-]/g, '-')}-${idx}`;
        const safeMapId = escapeHtml(mapId);
        spaceMapHtml = `
          <div class="space-map-container">
            <div class="space-map-toggle" onclick="toggleSpaceMap('${safeMapId}')">
              <span class="space-map-toggle-icon">🗺️</span>
              <span class="space-map-toggle-text">Show bounding box on map</span>
            </div>
            <div id="${safeMapId}" class="space-map" style="display: none;"></div>
          </div>
        `;
        
        // Store bbox data for map initialization
        if (!window.datasetSpaceMaps) window.datasetSpaceMaps = {};
        window.datasetSpaceMaps[safeMapId] = spaceBboxes.map(sb => sb.bbox);
      }
    }
    
    // Time
    const times = linked.Time || [];
    if (times.length > 0) {
      const timeInfo = times.map(t => {
        const begin = t.props?.begin || "";
        const end = t.props?.end || "";
        if (begin && end) return `${begin} to ${end}`;
        return t.props?.name || t.props?.title || t.name || "Time";
      }).join(", ");
      rows.push(["Time", timeInfo]);
    }

    // Build link: if source is data.gov, use catalog.data.gov URL
    let linkUrl = null;
    if (isDataGov) {
      // Get dataset name for data.gov link
      const datasetName = props.name || props.title || props.id || bundle.datasetId || "";
      if (datasetName) {
        // Format dataset name for URL: lowercase, replace spaces/special chars with hyphens
        const cleanName = String(datasetName)
          .toLowerCase()
          .trim()
          .replace(/[^a-z0-9\s-]/g, "")  // Remove special chars except spaces and hyphens
          .replace(/\s+/g, "-")           // Replace spaces with hyphens
          .replace(/-+/g, "-")            // Replace multiple hyphens with single
          .replace(/^-|-$/g, "");         // Remove leading/trailing hyphens
        
        if (cleanName) {
          linkUrl = `https://catalog.data.gov/dataset/${cleanName}`;
        }
      }
    }
    
    // Fallback to existing URL if not data.gov or if data.gov link couldn't be built
    if (!linkUrl) {
      linkUrl = props.url || props.link || props.landingPage || props.landing_page || null;
    }
    
    // Don't add Link to rows - it will be merged into title

    // Resource - collapsible list
    const resources = linked.Resource || [];
    const resourceFormats = linked.Format || [];
    // Create a map of resource_id -> format names for quick lookup
    const resourceFormatMap = new Map();
    resourceFormats.forEach(f => {
      const formatProps = f.props || f || {};
      const resourceId = f.resource_id || f.resourceId || null;
      const formatName = formatProps.name || formatProps.title || f.name || "Format";
      if (resourceId) {
        if (!resourceFormatMap.has(resourceId)) {
          resourceFormatMap.set(resourceId, []);
        }
        resourceFormatMap.get(resourceId).push(formatName);
      }
    });
    
    let resourceDisplay = null;
    if (resources.length > 0) {
      resourceDisplay = {
        items: resources.map(r => {
          const props = r.props || r || {};
          const resourceId = r.id || r.nodeId || r.node_id || null;
          // Get format names from resourceFormatMap using resource_id
          let formatNames = resourceFormatMap.get(resourceId) || [];
          let formatName = formatNames.join(", ") || "";
          
          // Fallback: try to get format from props if still empty
          if (!formatName && props.format) {
            if (typeof props.format === 'string') {
              formatName = props.format;
            } else if (typeof props.format === 'object' && props.format.name) {
              formatName = props.format.name;
            }
          }
          if (!formatName) {
            formatName = props.type || "";
          }
          return {
            name: props.name || props.title || r.name || "Resource",
            url: props.url || props.link || props.landingPage || props.landing_page || null,
            description: props.description || props.notes || "",
            format: formatName,
            size: props.size || props.fileSize || ""
          };
        })
      };
    }
    
    // Add License row if we have license display
    if (licenseDisplay) {
      if (licenseDisplay.type === "links") {
        const licenseLinksHtml = licenseDisplay.items.map(item => {
          if (item.url) {
            return `<a href="${escapeHtml(item.url)}" target="_blank" rel="noopener noreferrer" class="dataset-link">${escapeHtml(item.title)}</a>`;
          }
          return escapeHtml(item.title);
        }).join(", ");
        rows.push(["License", licenseLinksHtml]);
      } else {
        rows.push(["License", licenseDisplay.text]);
      }
    }
    
    // Add Source row if we have source display
    if (sourceDisplay) {
      if (sourceDisplay.type === "links") {
        const sourceLinksHtml = sourceDisplay.items.map(item => {
          if (item.url) {
            return `<a href="${escapeHtml(item.url)}" target="_blank" rel="noopener noreferrer" class="dataset-link">${escapeHtml(item.title)}</a>`;
          }
          return escapeHtml(item.title);
        }).join(", ");
        rows.push(["Source", sourceLinksHtml]);
      } else {
        rows.push(["Source", sourceDisplay.text]);
      }
    }
    
    const listHtml = rows.length
      ? `<div class="dataset-kv">
           ${rows.map(([k, v]) => {
             // Special handling for Source - may contain HTML links (no show more, just display)
             if (k === "Source" && typeof v === "string" && v.includes("<a ")) {
               return `
                 <div class="dataset-kv-row">
                   <div class="dataset-k">${escapeHtml(k)}</div>
                   <div class="dataset-v">${v}</div>
                 </div>
               `;
             }
             // Special handling for License - may contain HTML links (no show more, just display)
             if (k === "License" && typeof v === "string" && v.includes("<a ")) {
               return `
                 <div class="dataset-kv-row">
                   <div class="dataset-k">${escapeHtml(k)}</div>
                   <div class="dataset-v">${v}</div>
                 </div>
               `;
             }
            // For Organization field, use show more; others just show plain text
            if (k === "Organization") {
              const valueId = `dataset-v-${bundle.datasetId || idx}-${k}-${Date.now()}-${Math.floor(Math.random() * 100000)}`;
              const valueHtml = renderTextWithShowMore(String(v), 'dataset-v', valueId);
              return `
                <div class="dataset-kv-row">
                  <div class="dataset-k">${escapeHtml(k)}</div>
                  <div class="dataset-v">${valueHtml}</div>
                </div>
              `;
            } else {
              // Plain text for other fields
              return `
                <div class="dataset-kv-row">
                  <div class="dataset-k">${escapeHtml(k)}</div>
                  <div class="dataset-v">${escapeHtml(String(v))}</div>
                </div>
              `;
            }
           }).join("")}
         </div>`
      : "";
    
    // Add Resource section (collapsible)
    let resourceHtml = "";
    if (resourceDisplay && resourceDisplay.items.length > 0) {
      const resourceId = `resource-${bundle.datasetId || idx}`;
      const resourceCount = resourceDisplay.items.length;
      resourceHtml = `
        <div class="dataset-resource-section">
          <div class="dataset-resource-header" onclick="toggleResource('${resourceId}')">
            <span class="dataset-resource-title">
              <span class="dataset-resource-icon">📥</span>
              <span>Access Resource/Data (${resourceCount})</span>
            </span>
            <span class="dataset-resource-toggle" id="${resourceId}-icon">▲</span>
          </div>
          <div id="${resourceId}" class="dataset-resource-content">
            ${resourceDisplay.items.map((res, resIdx) => {
              const resTitle = res.url 
                ? `<a href="${escapeHtml(res.url)}" target="_blank" rel="noopener noreferrer" class="dataset-link">${escapeHtml(res.name)}</a>`
                : escapeHtml(res.name);
              const resDetails = [];
              if (res.size) resDetails.push(`Size: ${escapeHtml(res.size)}`);
              
              return `
                <div class="dataset-resource-item">
                  <div class="dataset-resource-name">${resTitle}</div>
                  ${res.format ? `<div class="dataset-resource-format">Format: ${escapeHtml(res.format)}</div>` : ""}
                  ${res.description ? `<div class="dataset-resource-description">Description: ${escapeHtml(res.description)}</div>` : ""}
                  ${resDetails.length > 0 ? `<div class="dataset-resource-details">${resDetails.join(" • ")}</div>` : ""}
                </div>
              `;
            }).join("")}
          </div>
        </div>
      `;
    }

    // Build match reasons - only show LLM selection reasons
    const matchReasons = [];
    
    // Add LLM selection reasons if available
    const llmReasons = bundle.llmSelectionReasons || bundle.llm_selection_reasons;
    if (llmReasons && Array.isArray(llmReasons) && llmReasons.length > 0) {
      // Add all LLM selection reasons
      matchReasons.push(...llmReasons);
    }
    
    
    const finalReasons = matchReasons;
    
    const matchReasonHtml = finalReasons.length > 0
      ? `<div class="dataset-match-reason">
           <div class="dataset-match-reason-title">Why it matches:</div>
           ${finalReasons.map((reason, reasonIdx) => {
             const reasonId = `match-reason-${bundle.datasetId || idx}-${reasonIdx}`;
             return `<div class="dataset-match-reason-item">• ${escapeHtml(reason)}</div>`;
           }).join("")}
         </div>`
      : "";

    // Render title with link if available
    const titleHtml = linkUrl
      ? `<a href="${escapeHtml(linkUrl)}" target="_blank" rel="noopener noreferrer" class="dataset-title-link">${escapeHtml(title)}</a>`
      : `<div class="dataset-title">${escapeHtml(title)}</div>`;
    
    const displayStyle = isVisible ? '' : 'style="display: none;"';
    return `
      <div class="dataset-card" ${displayStyle}>
        <div class="dataset-header">
          <span class="dataset-number">${idx + 1}</span>
          ${titleHtml}
        </div>
        ${desc ? `<div class="dataset-desc">${escapeHtml(desc)}</div>` : ""}
        ${notes ? renderTextWithShowMore(notes, 'dataset-notes', `dataset-notes-${bundle.datasetId || idx}-${Date.now()}-${Math.floor(Math.random() * 100000)}`) : ""}
        ${matchReasonHtml}
        ${listHtml}
        ${resourceHtml}
        ${spaceMapHtml}
      </div>
    `;
  }).join("");

  // Add toggle button if there are more than 2 datasets
  let toggleButton = "";
  if (hasMore) {
    const hiddenCount = limitedDatasets.length - maxVisible;
    const toggleId = `dataset-toggle-${Date.now()}-${Math.floor(Math.random() * 100000)}`;
    toggleButton = `
      <div class="dataset-list-toggle-container">
        <div class="dataset-list-toggle-header" onclick="toggleDatasetList(event, '${toggleId}')">
          <span class="dataset-list-toggle-title">Show ${hiddenCount} more dataset${hiddenCount > 1 ? 's' : ''}</span>
          <span class="dataset-list-toggle-icon" id="${toggleId}-icon">▼</span>
        </div>
      </div>
    `;
  }

  return `<div class="dataset-list">${blocks}</div>${toggleButton}`;
}

// Toggle function for showing/hiding additional datasets
// event: the click event (optional)
// toggleId: unique ID for the toggle button (optional, for finding the correct elements)
function toggleDatasetList(event, toggleId) {
  // Find the clicked toggle header
  let toggleHeader;
  if (event && event.currentTarget) {
    toggleHeader = event.currentTarget;
  } else if (toggleId) {
    const icon = document.getElementById(toggleId + '-icon');
    toggleHeader = icon ? icon.closest('.dataset-list-toggle-header') : null;
  } else {
    // Fallback: use first one (for backward compatibility)
    toggleHeader = document.querySelector('.dataset-list-toggle-header');
  }
  
  if (!toggleHeader) return;
  
  // Find the dataset list container that contains this toggle button
  // The toggle container is a sibling of the dataset-list
  const toggleContainer = toggleHeader.closest('.dataset-list-toggle-container');
  if (!toggleContainer) return;
  
  // Find the dataset-list that is associated with this toggle button
  // The structure is: <div class="dataset-list">...</div><div class="dataset-list-toggle-container">...</div>
  // They are siblings within the same parent (message-bubble)
  let datasetList = null;
  
  // First, try to find it as the previous sibling (most common case)
  let sibling = toggleContainer.previousElementSibling;
  while (sibling) {
    if (sibling.classList && sibling.classList.contains('dataset-list')) {
      datasetList = sibling;
      break;
    }
    sibling = sibling.previousElementSibling;
  }
  
  // If not found as previous sibling, search in the parent container
  // This handles cases where there might be other elements between them
  if (!datasetList) {
    const parent = toggleContainer.parentElement;
    if (parent) {
      // Get all dataset-lists in the parent
      const allLists = Array.from(parent.querySelectorAll('.dataset-list'));
      if (allLists.length > 0) {
        // Find the one that comes immediately before this toggle container in DOM order
        // We'll use the last dataset-list that appears before toggleContainer
        for (let i = allLists.length - 1; i >= 0; i--) {
          const list = allLists[i];
          // Check if list comes before toggleContainer in document order
          const position = list.compareDocumentPosition(toggleContainer);
          if (position & Node.DOCUMENT_POSITION_FOLLOWING) {
            datasetList = list;
            break;
          }
        }
        // Fallback: if no list found before toggleContainer, use the last one in the parent
        // (this should be the correct one since toggle follows the list)
        if (!datasetList && allLists.length > 0) {
          datasetList = allLists[allLists.length - 1];
        }
      }
    }
  }
  
  if (!datasetList) return;
  
  const allBlocks = datasetList.querySelectorAll('.dataset-card');
  const toggleIcon = toggleHeader.querySelector('.dataset-list-toggle-icon');
  const toggleTitle = toggleHeader.querySelector('.dataset-list-toggle-title');
  
  if (!allBlocks || allBlocks.length <= 2) return;
  
  // Get all blocks except first 2
  const rest = Array.from(allBlocks).slice(2);
  
  // Check current state: check computed style to see if any are visible
  let isExpanded = false;
  for (const block of rest) {
    const computedStyle = window.getComputedStyle(block);
    if (computedStyle.display !== 'none') {
      isExpanded = true;
      break;
    }
  }
  
  if (isExpanded) {
    // Collapse: hide all except first 2
    rest.forEach(block => {
      block.style.display = 'none';
    });
    if (toggleIcon) toggleIcon.textContent = '▼';
    if (toggleTitle) {
      const hiddenCount = rest.length;
      toggleTitle.textContent = `Show ${hiddenCount} more dataset${hiddenCount > 1 ? 's' : ''}`;
    }
  } else {
    // Expand: show all - remove inline style to show elements
    rest.forEach(block => {
      block.style.removeProperty('display');
    });
    if (toggleIcon) toggleIcon.textContent = '▲';
    if (toggleTitle) {
      toggleTitle.textContent = 'Show less';
    }
    
    // Re-initialize show more functionality for newly visible elements
    // Use setTimeout to ensure DOM is updated before measuring
    setTimeout(() => {
      initializeTextShowMore();
    }, 50);
  }
}

/**
 * Toggle show more/less for text blocks
 */
function toggleTextShowMore(textId) {
  const contentEl = document.getElementById(textId + '-content');
  const linkEl = document.getElementById(textId + '-link');
  
  if (!contentEl || !linkEl) return;
  
  // Check if link is disabled (content doesn't exceed 2 lines)
  if (linkEl.classList.contains('disabled')) {
    return; // Do nothing if disabled
  }
  
  // Re-check if content actually exceeds 2 lines before allowing toggle
  // This prevents toggling for content that doesn't need it
  const exceedsTwoLines = checkTextExceedsTwoLines(contentEl);
  
  if (!exceedsTwoLines) {
    // Content doesn't exceed 2 lines - do nothing
    return;
  }
  
  // Get current state
  const isCurrentlyCollapsed = contentEl.classList.contains('text-collapsed');
  
  if (isCurrentlyCollapsed) {
    // Currently collapsed (showing "show more") - expand it
    contentEl.classList.remove('text-collapsed');
    linkEl.textContent = 'show less';
  } else {
    // Currently expanded (showing "show less") - collapse it
    contentEl.classList.add('text-collapsed');
    linkEl.textContent = 'show more';
  }
}

/**
 * Initialize show more functionality for all text blocks after DOM update
 * This checks if text exceeds 2 lines and shows/hides the "show more" link
 */
/**
 * Check if text content exceeds 2 lines by directly measuring rendered height
 * This method accounts for all layout factors including zoom, sidebar collapse, etc.
 */
function checkTextExceedsTwoLines(contentEl) {
  if (!contentEl || !contentEl.textContent || !contentEl.textContent.trim()) {
    return false;
  }
  
  // Get computed styles - these account for current zoom level
  const computedStyle = window.getComputedStyle(contentEl);
  const lineHeight = parseFloat(computedStyle.lineHeight);
  const fontSize = parseFloat(computedStyle.fontSize);
  
  // If lineHeight is 'normal', calculate it (typically 1.2-1.5x font size)
  const calculatedLineHeight = (lineHeight && !isNaN(lineHeight) && lineHeight > 0) 
    ? lineHeight 
    : (fontSize * 1.5);
  
  // Save current state
  const wasCollapsed = contentEl.classList.contains('text-collapsed');
  const originalStyles = {
    display: contentEl.style.display,
    webkitLineClamp: contentEl.style.webkitLineClamp,
    webkitBoxOrient: contentEl.style.webkitBoxOrient,
    overflow: contentEl.style.overflow,
    maxHeight: contentEl.style.maxHeight,
    whiteSpace: contentEl.style.whiteSpace,
    height: contentEl.style.height
  };
  
  // Temporarily remove all restrictions to measure full height
  // First, ensure element and its ancestors are visible and not constrained
  const parentBlock = contentEl.closest('.text-with-showmore');
  const datasetCard = contentEl.closest('.dataset-card');
  
  // Check if parent elements are hidden and temporarily show them for measurement
  const wasParentHidden = parentBlock && parentBlock.style.display === 'none';
  const wasCardHidden = datasetCard && datasetCard.style.display === 'none';
  
  if (wasParentHidden) {
    parentBlock.style.display = 'block';
  }
  if (wasCardHidden) {
    datasetCard.style.display = 'block';
  }
  
  contentEl.classList.remove('text-collapsed');
  contentEl.style.display = 'block';
  contentEl.style.webkitLineClamp = 'none';
  contentEl.style.webkitBoxOrient = 'horizontal';
  contentEl.style.overflow = 'visible';
  contentEl.style.maxHeight = 'none';
  contentEl.style.whiteSpace = 'normal';
  contentEl.style.height = 'auto';
  
  // Force reflow to ensure styles are applied and layout is calculated
  void contentEl.offsetHeight;
  
  // Measure actual scroll height (accounts for actual rendered height with current layout)
  const scrollHeight = contentEl.scrollHeight;
  
  // Calculate expected 2-line height
  const expectedTwoLineHeight = calculatedLineHeight * 2;
  
  // Use a small tolerance (2px) to account for rounding and sub-pixel rendering
  const tolerance = 2;
  const exceedsTwoLines = scrollHeight > (expectedTwoLineHeight + tolerance);
  
  // Restore original state - restore styles first, then class
  Object.keys(originalStyles).forEach(key => {
    const value = originalStyles[key];
    if (value) {
      contentEl.style[key] = value;
    } else {
      contentEl.style.removeProperty(key);
    }
  });
  
  // Restore collapsed class state
  if (wasCollapsed) {
    contentEl.classList.add('text-collapsed');
  } else {
    contentEl.classList.remove('text-collapsed');
  }
  
  // Restore parent visibility if it was hidden
  if (wasParentHidden && parentBlock) {
    parentBlock.style.display = 'none';
  }
  if (wasCardHidden && datasetCard) {
    datasetCard.style.display = 'none';
  }
  
  return exceedsTwoLines;
}

function initializeTextShowMore() {
  // Use requestAnimationFrame to ensure DOM is fully rendered
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      const textBlocks = document.querySelectorAll('.text-with-showmore');
      
      textBlocks.forEach(block => {
        const contentEl = block.querySelector('.text-content');
        const linkEl = block.querySelector('.text-showmore-link');
        
        if (!contentEl || !linkEl) {
          return;
        }
        
        // Check if content exceeds 2 lines
        const exceedsTwoLines = checkTextExceedsTwoLines(contentEl);
        
        // Always show the link, but disable it if content doesn't exceed 2 lines
        linkEl.style.display = 'inline';
        
        if (exceedsTwoLines) {
          // Content exceeds 2 lines - enable link, start in collapsed state
          linkEl.classList.remove('disabled');
          linkEl.textContent = 'show more';
          contentEl.classList.add('text-collapsed');
        } else {
          // Content fits in 2 lines - disable link, ensure expanded state
          linkEl.classList.add('disabled');
          linkEl.textContent = 'show more';
          contentEl.classList.remove('text-collapsed');
        }
      });
    });
  });
}

// Re-check text show more when window is resized or layout changes
let resizeTimeout;
let resizeObserver = null;

function handleResize() {
  clearTimeout(resizeTimeout);
  resizeTimeout = setTimeout(() => {
    initializeTextShowMore();
  }, 150);
}

// Listen for window resize and orientation change
window.addEventListener('resize', handleResize);
window.addEventListener('orientationchange', handleResize);

// Use ResizeObserver to watch for container size changes (e.g., sidebar collapse/expand)
function setupResizeObserver() {
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => {
      handleResize();
    });
    
    // Observe the main chat container to detect sidebar changes
    const chatContainer = document.querySelector('.chat-container');
    if (chatContainer) {
      resizeObserver.observe(chatContainer);
    }
    
    // Also observe the app root to catch all layout changes
    const appRoot = document.querySelector('.app-root');
    if (appRoot) {
      resizeObserver.observe(appRoot);
    }
    
    // Observe dataset cards container to catch width changes
    const chatMessages = document.querySelector('.chat-messages');
    if (chatMessages) {
      resizeObserver.observe(chatMessages);
    }
  }
}

// Initialize ResizeObserver when DOM is ready
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', setupResizeObserver);
} else {
  setupResizeObserver();
}

// Theme toggle functionality
function initThemeToggle() {
  // Get saved theme preference or default to 'light'
  const savedTheme = localStorage.getItem('igdd-theme') || 'light';
  document.documentElement.setAttribute('data-theme', savedTheme);
  
  // Find or create theme toggle button
  let themeToggleBtn = document.getElementById('theme-toggle-btn');
  const topHeaderRight = document.querySelector('.top-header-right');
  
  if (!themeToggleBtn && topHeaderRight) {
    // Create theme toggle button
    themeToggleBtn = document.createElement('button');
    themeToggleBtn.id = 'theme-toggle-btn';
    themeToggleBtn.className = 'theme-toggle-btn';
    themeToggleBtn.setAttribute('aria-label', 'Toggle theme');
    themeToggleBtn.title = savedTheme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode';
    
    // Set icon based on current theme
    updateThemeIcon(themeToggleBtn, savedTheme);
    
    // Add click handler
    themeToggleBtn.addEventListener('click', () => {
      const currentTheme = document.documentElement.getAttribute('data-theme') || 'light';
      const newTheme = currentTheme === 'dark' ? 'light' : 'dark';
      
      // Apply new theme
      document.documentElement.setAttribute('data-theme', newTheme);
      localStorage.setItem('igdd-theme', newTheme);
      
      // Update button icon and title
      updateThemeIcon(themeToggleBtn, newTheme);
      themeToggleBtn.title = newTheme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode';
    });
    
    // Insert at the beginning of top-header-right
    topHeaderRight.insertBefore(themeToggleBtn, topHeaderRight.firstChild);
  } else if (themeToggleBtn) {
    // Button already exists, update icon
    updateThemeIcon(themeToggleBtn, savedTheme);
  }
}

function updateThemeIcon(btn, theme) {
  if (!btn) return;
  
  // Use sun/moon icons: ☀️ for light mode, 🌙 for dark mode
  // Or use text: ☀ for light, ☾ for dark
  if (theme === 'dark') {
    btn.textContent = '☀️';
  } else {
    btn.textContent = '🌙';
  }
}

// Initialize theme toggle when DOM is ready
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', initThemeToggle);
} else {
  initThemeToggle();
}

function renderQuestionsPanel(questionForUsers) {
  if (!Array.isArray(questionForUsers) || questionForUsers.length === 0) return "";
  const items = questionForUsers
    .filter(Boolean)
    .map(q => `<li>${escapeHtml(String(q))}</li>`)
    .join("");
  if (!items) return "";
  return `
    <div class="chat-panel">
      <div class="chat-panel-title">A quick question</div>
      <ul class="chat-questions">${items}</ul>
    </div>
  `;
}

function shouldShowScore(score) {
  if (typeof score !== "number") return false;
  return score > 0.0001;
}

function buildPendingHitlPanel(pendingHitl, panelId) {
  if (!pendingHitl || typeof pendingHitl !== "object") return "";

  const slot = pendingHitl.slot ? String(pendingHitl.slot).toLowerCase() : "";
  const q = pendingHitl.question || "Please clarify.";
  const cands = Array.isArray(pendingHitl.candidates) ? pendingHitl.candidates : [];
  const title = slot ? `Confirmation needed: ${slot}` : "Confirmation needed";

  // Use renderMarkdown to properly handle lists and formatting
  const qHtml = renderMarkdown(q);

  return `
    <div class="chat-panel">
      <div class="chat-panel-title">${escapeHtml(title)}</div>
      <div class="chat-note">${qHtml}</div>
      ${cands.length ? `
        <div class="chat-options" id="${escapeHtml(panelId)}"></div>
        <div class="chat-hitl-actions" id="${escapeHtml(panelId)}-actions">
          <button class="chat-continue-btn" id="${escapeHtml(panelId)}-continue" type="button">
            Continue (use all top candidates)
          </button>
        </div>
        <div class="chat-note">Select one or more candidates, or click Continue to use all top candidates.</div>`
        : `<div class="chat-note">Reply in chat to clarify.</div>`}
    </div>
  `;
}

function wirePendingHitlOptions(panelId, pendingHitl) {
  const cands = Array.isArray(pendingHitl?.candidates) ? pendingHitl.candidates : [];
  // CRITICAL: Set hasPendingCandidates based on whether HITL has candidates
  // If there are HITL candidates, disable input (user must select from options)
  // If there are NO HITL candidates, user needs to type in chat - enable input
  // This must be set BEFORE any dimension candidates are wired, to ensure correct priority
  if (cands.length > 0) {
    hasPendingCandidates = true;
    setReadOnlyMode(isReadOnlyMode);
  } else {
    // No HITL candidates - user needs to type in chat, so enable input
    // This takes priority over dimension candidates
    hasPendingCandidates = false;
    setReadOnlyMode(isReadOnlyMode);
  }
  if (!cands.length) return;

  const container = document.getElementById(panelId);
  if (!container) return;

  // Track selected candidates
  const selectedIndices = new Set();
  let submitBtn = null;
  let updateSubmitButtonState = null;
  let isSubmitted = false; // Track if submit has been clicked

  // Wire submit button first (so we can reference it in checkbox handlers)
  const actionsContainer = document.getElementById(`${panelId}-actions`);
  if (actionsContainer && cands.length > 0) {
    submitBtn = document.createElement("button");
    submitBtn.className = "chat-submit-btn";
    submitBtn.type = "button";
    submitBtn.textContent = "Submit selected";
    submitBtn.style.marginLeft = "8px";
    // Initially disabled if no selection
    submitBtn.disabled = true;
    
    // Function to update submit button state
    updateSubmitButtonState = () => {
      if (submitBtn && !isSubmitted) {
        // Only update state if not already submitted
        submitBtn.disabled = selectedIndices.size === 0;
      }
    };
    
    submitBtn.addEventListener("click", async () => {
      if (selectedIndices.size === 0 || isSubmitted) {
        // Should not happen as button is disabled, but just in case
        return;
      }

      // Mark as submitted and disable both buttons immediately
      isSubmitted = true;
      submitBtn.disabled = true;
      const continueBtn = document.getElementById(`${panelId}-continue`);
      if (continueBtn) continueBtn.disabled = true;

      // Build selection string (e.g., "1,3,5")
      const selection = Array.from(selectedIndices)
        .sort((a, b) => a - b)
        .map(i => String(i + 1))
        .join(",");
      
      // Clear hasPendingCandidates before sending (candidates are now submitted)
      hasPendingCandidates = false;
      setReadOnlyMode(isReadOnlyMode);
      
      appendMessage("user", selection);
      // Scroll to bottom after user message
      setTimeout(() => {
        if (chatMessages) {
          chatMessages.scrollTop = chatMessages.scrollHeight;
        }
      }, 50);
      await sendToBackend({ query: selection, action: "message" });
    });

    actionsContainer.appendChild(submitBtn);
  }

  cands.forEach((c, idx) => {
    const name = c.name || c.title || c.value || c.nodeId || "Candidate";
    const type = c.label || "Entity";
    const score = (typeof c.score === "number") ? c.score : null;

    const row = document.createElement("div");
    row.className = "chat-option chat-option-multiselect";
    row.dataset.index = idx;

    const subtitleParts = [type];
    if (shouldShowScore(score)) subtitleParts.push(`score=${score.toFixed(3)}`);

    const checkboxId = `${panelId}-checkbox-${idx}`;
    row.innerHTML = `
      <input type="checkbox" id="${escapeHtml(checkboxId)}" class="chat-option-checkbox" />
      <label for="${escapeHtml(checkboxId)}" class="chat-option-label">
        <span class="chat-option-badge">${idx + 1}</span>
        <div>
          <div class="chat-option-title">${escapeHtml(name)}</div>
          <div class="chat-option-subtitle">${escapeHtml(subtitleParts.join(" · "))}</div>
        </div>
      </label>
    `;

    const checkbox = row.querySelector(`#${checkboxId}`);
    checkbox.addEventListener("change", (e) => {
      if (e.target.checked) {
        selectedIndices.add(idx);
        row.classList.add("selected");
      } else {
        selectedIndices.delete(idx);
        row.classList.remove("selected");
      }
      // Update submit button state
      if (updateSubmitButtonState) updateSubmitButtonState();
    });

    // Also allow clicking the row to toggle
    row.addEventListener("click", (e) => {
      if (e.target.tagName !== "INPUT" && e.target.tagName !== "LABEL") {
        checkbox.checked = !checkbox.checked;
        checkbox.dispatchEvent(new Event("change"));
      }
    });

    container.appendChild(row);
  });

  // Wire Continue button
  const continueBtn = document.getElementById(`${panelId}-continue`);
  if (continueBtn) {
    continueBtn.addEventListener("click", async () => {
      // Mark as submitted and disable both buttons immediately
      isSubmitted = true;
      continueBtn.disabled = true;
      if (submitBtn) submitBtn.disabled = true;
      
      // Clear hasPendingCandidates before sending (candidates are now submitted)
      hasPendingCandidates = false;
      setReadOnlyMode(isReadOnlyMode);
      
      appendMessage("user", "continue");
      // Scroll to bottom after user message
      setTimeout(() => {
        if (chatMessages) {
          chatMessages.scrollTop = chatMessages.scrollHeight;
        }
      }, 50);
      await sendToBackend({ query: "continue", action: "message" });
    });
  }
}

function renderIntentPanel(intent) {
  if (!intentPanel || !intentRaw) return;

  if (!intent || typeof intent !== "object") {
    intentPanel.innerHTML = `<div class="intent-empty">No intent available.</div>`;
    intentRaw.textContent = "{}";
    return;
  }
  
  // Check if this is a general question (non-data discovery)
  // If overall_confidence is 1.0 and no dimensions are set, it's likely a general question
  const isGeneralQuestion = intent.overall_confidence === 1.0 && 
                            (!intent.topic && !intent.space && !intent.time && 
                             !intent.format && !intent.license && !intent.organization && !intent.source);
  
  if (isGeneralQuestion) {
    intentPanel.innerHTML = `
      <div class="intent-card">
        <div class="intent-card-header">
          <div class="intent-card-title">General Question</div>
        </div>
        <div class="intent-card-content">
          <div class="intent-card-value">This is a general question, not related to geospatial data discovery.</div>
        </div>
      </div>
    `;
    intentRaw.textContent = JSON.stringify({ type: "general_question", overall_confidence: 1.0 }, null, 2);
    if (intentPanel) {
      intentPanel.style.display = 'block';
    }
    return;
  }

  const cards = [];
  
  // 1. Overall confidence (display first)
  if (intent.overall_confidence !== undefined && intent.overall_confidence !== null) {
    const confidence = (typeof intent.overall_confidence === "number") 
      ? (intent.overall_confidence * 100).toFixed(1) + "%"
      : String(intent.overall_confidence);
    cards.push(`
      <div class="intent-card">
        <div class="intent-card-header">
          <div class="intent-card-title">Overall Confidence</div>
        </div>
        <div class="intent-card-value">${escapeHtml(confidence)}</div>
      </div>
    `);
  }
  
  // 2. Display dimensions in specified order: source, topic, space, time, organization, format, license
  const dimensionOrder = ['source', 'topic', 'space', 'time', 'organization', 'format', 'license'];
  
  dimensionOrder.forEach(dimKey => {
    const dim = intent[dimKey];
    if (!dim || (typeof dim !== "object")) return;
    
    let displayValue = "";
    
    if (dimKey === 'topic' || dimKey === 'source' || dimKey === 'organization' || dimKey === 'format' || dimKey === 'license') {
      // Entity dimension
      if (dim.value) {
        displayValue = dim.value;
      } else if (dim.raw_text) {
        displayValue = dim.raw_text;
      } else if (dim.kg_node_ids && Array.isArray(dim.kg_node_ids) && dim.kg_node_ids.length > 0) {
        // If user selected candidates but no value/raw_text, show node IDs (should be improved by backend)
        displayValue = dim.kg_node_ids.join(", ");
      } else if (dim.kg_node_id) {
        displayValue = dim.kg_node_id;
      }
      
      if (dim.confidence !== undefined && dim.confidence !== null) {
        const conf = (typeof dim.confidence === "number") 
          ? (dim.confidence * 100).toFixed(1) + "%"
          : String(dim.confidence);
        displayValue += ` (confidence: ${conf})`;
      }
    } else if (dimKey === 'space') {
      // Space dimension
      if (dim.bbox && Array.isArray(dim.bbox) && dim.bbox.length === 4) {
        const [minLon, minLat, maxLon, maxLat] = dim.bbox;
        displayValue = `bbox [${minLon.toFixed(4)}, ${minLat.toFixed(4)}, ${maxLon.toFixed(4)}, ${maxLat.toFixed(4)}]`;
      } else if (dim.value) {
        displayValue = dim.value;
      } else if (dim.raw_text) {
        displayValue = dim.raw_text;
      }
      
      if (dim.confidence !== undefined && dim.confidence !== null) {
        const conf = (typeof dim.confidence === "number") 
          ? (dim.confidence * 100).toFixed(1) + "%"
          : String(dim.confidence);
        displayValue += ` (confidence: ${conf})`;
      }
    } else if (dimKey === 'time') {
      // Time dimension
      const parts = [];
      if (dim.start && dim.end) {
        parts.push(`${dim.start} to ${dim.end}`);
      } else if (dim.start) {
        parts.push(`from ${dim.start}`);
      } else if (dim.end) {
        parts.push(`until ${dim.end}`);
      }
      if (dim.raw_text) {
        parts.push(`(${dim.raw_text})`);
      }
      displayValue = parts.join(" ") || dim.raw_text || "";
      
      if (dim.confidence !== undefined && dim.confidence !== null) {
        const conf = (typeof dim.confidence === "number") 
          ? (dim.confidence * 100).toFixed(1) + "%"
          : String(dim.confidence);
        displayValue += ` (confidence: ${conf})`;
      }
    }
    
    if (displayValue) {
      const title = dimKey.charAt(0).toUpperCase() + dimKey.slice(1);
      
      // Check if there are candidates for this dimension
      // Try both lowercase and capitalized keys (backend may use "Topic", frontend uses "topic")
      const dimKeyCapitalized = dimKey.charAt(0).toUpperCase() + dimKey.slice(1);
      const candidates = intent.dimension_candidates && (
        intent.dimension_candidates[dimKey] || intent.dimension_candidates[dimKeyCapitalized]
      ) ? (intent.dimension_candidates[dimKey] || intent.dimension_candidates[dimKeyCapitalized]) : null;
      const candidatesCount = candidates ? candidates.length : 0;
      
      // Build candidates HTML if available
      let candidatesHtml = '';
      if (candidates && candidates.length > 0) {
        const candidatesList = candidates.map((c, idx) => {
          // Use name field, not label (label is for Topic/Keyword type, not the entity name)
          const name = escapeHtml(c.name || c.nodeId || 'Unknown');
          const score = c.score !== undefined ? ` (${(c.score * 100).toFixed(1)}%)` : '';
          const isSelected = dim.kg_node_ids && Array.isArray(dim.kg_node_ids) && dim.kg_node_ids.includes(c.nodeId);
          const selectedClass = isSelected ? ' intent-candidate-selected' : '';
          // For topic dimension, show label (Topic/Keyword) if available
          let labelBadge = '';
          if (dimKey === 'topic' && c.label) {
            const labelText = c.label === 'Keyword' ? 'Keyword' : 'Topic';
            const labelClass = c.label === 'Keyword' ? 'intent-candidate-label-keyword' : 'intent-candidate-label-topic';
            labelBadge = `<span class="intent-candidate-label ${labelClass}">[${labelText}]</span>`;
          }
          return `<div class="intent-candidate-item${selectedClass}" data-node-id="${escapeHtml(c.nodeId || '')}">
            ${labelBadge}
            <span class="intent-candidate-name">${name}</span>
            <span class="intent-candidate-score">${score}</span>
          </div>`;
        }).join('');
        
        candidatesHtml = `
          <div class="intent-candidates-list" style="display: none;">
            ${candidatesList}
          </div>
        `;
      }
      
      cards.push(`
        <div class="intent-card" data-dimension="${dimKey}">
          <div class="intent-card-header">
            <div class="intent-card-title">${escapeHtml(title)}</div>
            ${candidatesCount > 0 ? `<span class="intent-candidates-toggle" data-dimension="${dimKey}" title="Click to view ${candidatesCount} candidate(s)">[Candidates: ${candidatesCount}]</span>` : ''}
          </div>
          <div class="intent-card-value">${escapeHtml(displayValue)}</div>
          ${candidatesHtml}
        </div>
      `);
    }
  });
  
  if (cards.length === 0) {
    intentPanel.innerHTML = `<div class="intent-empty">Intent parsed, but empty.</div>`;
    intentRaw.textContent = "{}";
    return;
  }
  
  intentPanel.innerHTML = cards.join("");
  intentRaw.textContent = JSON.stringify(intent, null, 2);
  
  // Add click handlers for candidates toggle
  intentPanel.querySelectorAll('.intent-candidates-toggle').forEach(toggle => {
    toggle.addEventListener('click', function(e) {
      e.stopPropagation();
      const dimension = this.getAttribute('data-dimension');
      const card = this.closest('.intent-card');
      const candidatesList = card.querySelector('.intent-candidates-list');
      if (candidatesList) {
        const isVisible = candidatesList.style.display !== 'none';
        const count = candidatesList.querySelectorAll('.intent-candidate-item').length;
        // Toggle visibility: if currently visible, hide it; if hidden, show it
        candidatesList.style.display = isVisible ? 'none' : 'block';
        // Update text: if we're hiding (was visible), show count; if showing (was hidden), show collapse indicator
        this.textContent = isVisible ? `[Candidates: ${count}]` : `[−]`;
      }
    });
  });
}

function addLogs(logs) {
  if (!logsPanel) return;
  if (!Array.isArray(logs) || logs.length === 0) return;

  const empty = logsPanel.querySelector(".logs-empty");
  if (empty) empty.remove();

  // Sort logs by timestamp in descending order (newest first in array)
  // This ensures newest logs are inserted first and appear at the top
  const sortedLogs = [...logs].sort((a, b) => {
    const tsA = a.ts || '';
    const tsB = b.ts || '';
    // Compare timestamps as strings (ISO format is sortable)
    // If timestamps are equal or missing, maintain original order
    if (!tsA && !tsB) return 0;
    if (!tsA) return 1;  // Missing timestamp goes to end
    if (!tsB) return -1; // Missing timestamp goes to end
    return tsB.localeCompare(tsA); // Descending order (newest first)
  });

  // Insert logs at the top (most recent first)
  // Process sorted logs in order (newest first) and insert at the top
  // We iterate from the beginning of the sorted array (newest first)
  // and insert each log at the top of the panel
  for (let i = 0; i < sortedLogs.length; i++) {
    const l = sortedLogs[i];
    const ts = l.ts || "";
    const stage = l.stage || "";
    const msg = l.message || l.msg || JSON.stringify(l);

    const key = `${ts}|${stage}|${msg}`;
    if (seenLogKeys.has(key)) continue;
    seenLogKeys.add(key);

    const line = document.createElement("div");
    line.className = "log-line";
    line.innerHTML = `
      <span class="log-meta">${escapeHtml(ts || nowTime())}</span>
      <span class="log-stage"> [${escapeHtml(stage)}]</span>
      <span class="log-msg"> ${escapeHtml(msg)}</span>
    `;
    
    // Always insert at the top (before first child, or append if no children)
    // This ensures newest logs appear at the top
    const firstChild = logsPanel.firstChild;
    if (firstChild) {
      logsPanel.insertBefore(line, firstChild);
    } else {
      logsPanel.appendChild(line);
    }
  }

  // Keep scroll position at top (most recent logs)
  logsPanel.scrollTop = 0;
}

async function readJsonOrText(res) {
  const ct = res.headers.get("content-type") || "";
  if (ct.includes("application/json")) return await res.json();
  const txt = await res.text();
  return { reply: txt, stage: "error" };
}

/**
 * Render a complete assistant response with all components (reply, datasets, HITL, dimension candidates).
 * This function is used both for new responses and for restoring saved conversations.
 * @param {Object} data - Response data (from backend or from saved meta)
 * @param {boolean} isRestore - If true, this is restoring a saved conversation (no auto-continue, update UI state)
 */
function renderCompleteAssistantResponse(data, isRestore = false) {
  const datasets = data.datasets || data.items || [];
  const limitedDatasets = Array.isArray(datasets) ? datasets.slice(0, 10) : [];
  
  if (!isRestore) {
    // Only update stage/dataset count for new responses (not for restore)
    const stage = data.stage || "idle";
    if (stageText) stageText.textContent = stage;
    if (datasetsCount) datasetsCount.textContent = String(limitedDatasets.length);
    
    // Only render intent for new responses (not for restore - already restored above)
    // Logs are now handled in real-time via SSE, so don't add them here
    renderIntentPanel(data.intent || null);
    // Note: Logs are added in real-time via SSE event listener, not from response data
  } else {
    // For restore mode, intent and logs are already restored from last turn's meta
    // Only render intent panel if intent is provided in this specific turn (for backwards compatibility)
    if (data.intent) {
      renderIntentPanel(data.intent);
    }
    // Don't add logs here in restore mode - logs are already restored from last turn's meta
    // to avoid duplicates and ensure correct order
  }
  
  // One backend response -> one assistant bubble
  const replyText = data.reply || "";
  const pending = data.pending_hitl || data.pendingHitl || null;
  const dimensionCandidates = data.dimension_candidates || {};
  
  const parts = [];
  
  // Merge reply text and datasets list together
  if (replyText && String(replyText).trim()) {
    // Use Markdown rendering for LLM output
    parts.push(`<div class="assistant-reply">${renderMarkdown(String(replyText))}</div>`);
  }
  
  // Show dimension candidates for user selection
  // For restored conversations, candidates are already selected and should be displayed as read-only
  const candidatesTimestamp = Date.now();
  if (Object.keys(dimensionCandidates).length > 0) {
    const candidatesPanel = renderDimensionCandidates(dimensionCandidates, candidatesTimestamp, isRestore);
    if (candidatesPanel) parts.push(candidatesPanel);
  }
  
  // Merge datasets list with reply (not separate)
  const datasetsHtml = renderDatasetBlocks(limitedDatasets, data.intent || null);
  if (datasetsHtml) {
    parts.push(datasetsHtml);
    // Initialize maps and text show more after DOM is updated
    setTimeout(() => {
      initializeSpaceMaps();
      initializeTextShowMore();
    }, 100);
  }
  
  // TEST FEATURE: Add Top 20 datasets before selection (for testing/debugging purposes only)
  // This will be removed before production release
  const top20BeforeSelection = data.top20_before_selection || data.top20BeforeSelection || [];
  if (top20BeforeSelection.length > 0) {
    parts.push(renderTop20BeforeSelection(top20BeforeSelection, data.intent || null));
    // Initialize maps and text show more for Top 20 datasets after DOM is updated
    setTimeout(() => {
      initializeSpaceMaps();
      initializeTextShowMore();
    }, 100);
  }
  
  // Add graph visualization if evidence subgraph is available
  const evidence = data.evidence || {};
  const subgraph = evidence.subgraph;
  let graphTimestamp = null;
  if (subgraph && limitedDatasets.length > 0) {
    graphTimestamp = Date.now();
    parts.push(renderGraphVisualization(subgraph, graphTimestamp, limitedDatasets));
  }
  
  const hitlPanelId = `chat-hitl-options-${Date.now()}-${Math.floor(Math.random() * 100000)}`;
  const hitlPanel = buildPendingHitlPanel(pending, hitlPanelId);
  if (hitlPanel) parts.push(hitlPanel);
  
  const html = parts.length ? parts.join("\n") : `<div class="assistant-reply">(no reply)</div>`;
  const bubbleRow = appendAssistantBubbleHtml(html);
  
  // Check HITL status first to determine if user needs to type (no candidates)
  const hitlCandidates = Array.isArray(pending?.candidates) ? pending.candidates : [];
  const hitlNeedsUserInput = pending && hitlCandidates.length === 0;
  
  // Wire HITL option click handlers (if any) - this will set hasPendingCandidates appropriately
  if (pending) {
    wirePendingHitlOptions(hitlPanelId, pending);
  }
  
  // Wire dimension candidates selection handlers (must use same timestamp)
  // Only wire selection handlers for new responses, not for restored conversations
  // In restored conversations, candidates are already selected and should be displayed as read-only
  if (!isRestore && Object.keys(dimensionCandidates).length > 0) {
    // Use setTimeout to ensure DOM is ready and HITL wiring is complete
    setTimeout(() => {
      // CRITICAL: Only wire dimension candidates if HITL doesn't need user input
      // If HITL needs user input, user should be able to type regardless of dimension candidates
      // This check must happen AFTER wirePendingHitlOptions has been called
      if (!hitlNeedsUserInput) {
        // HITL either doesn't exist, or has candidates (user selects from options)
        // In this case, dimension candidates can be wired normally
        wireDimensionCandidatesSelection(dimensionCandidates, candidatesTimestamp);
      } else {
        // HITL needs user input (no candidates) - don't wire dimension candidates selection
        // Just render them as read-only (for display purposes)
        // CRITICAL: Ensure input is enabled for HITL user input
        // wirePendingHitlOptions should have already set hasPendingCandidates = false,
        // but we explicitly ensure it here to prevent any race conditions
        hasPendingCandidates = false;
        setReadOnlyMode(isReadOnlyMode);
      }
    }, 100);
  } else if (isRestore && Object.keys(dimensionCandidates).length > 0) {
    // For restored conversations, render candidates as read-only (already selected)
    // Pass intent to determine which candidates were actually selected
    setTimeout(() => {
      renderRestoredCandidates(dimensionCandidates, candidatesTimestamp, data.intent);
    }, 100);
  } else if (!isRestore && Object.keys(dimensionCandidates).length === 0) {
    // If there are no dimension candidates in the response, check if we need to enable input
    // If there's pending_hitl without candidates (user needs to type new intent), enable input
    if (hitlNeedsUserInput) {
      // HITL mode without candidates - user needs to type in chat, so enable input
      // wirePendingHitlOptions should have already set this, but ensure it explicitly
      hasPendingCandidates = false;
      setReadOnlyMode(isReadOnlyMode);
    } else if (!pending) {
      // No pending HITL and no dimension candidates - conversation completed, enable input
      hasPendingCandidates = false;
      setReadOnlyMode(isReadOnlyMode);
    }
  }
  
  // Initialize graph visualization if evidence subgraph is available
  if (subgraph && limitedDatasets.length > 0 && graphTimestamp !== null) {
    setTimeout(() => {
      // Find the graph container that was just added using the timestamp
      const graphContainer = bubbleRow.querySelector(`[data-graph-timestamp="${graphTimestamp}"]`);
      if (graphContainer) {
        const actualGraphId = graphContainer.querySelector('.cytoscape-graph')?.id;
        if (actualGraphId) {
          // Store datasets data in container for filtering
          graphContainer.dataset.datasetsJson = JSON.stringify(limitedDatasets);
          initializeGraphVisualization(actualGraphId, subgraph, limitedDatasets, graphTimestamp);
        }
      }
    }, 100);
  }
}

/**
 * allowReentry=true means: keep spinner enabled (outer call controls it).
 */
async function sendToBackend({ query, action = "message", extra = {}, allowReentry = false }) {
  // Clear pipeline message box when starting a new query (not for dimension selections)
  if (action === "message" && query !== "continue") {
    const existingBox = document.getElementById("pipeline-progress-box");
    if (existingBox) {
      existingBox.remove();
    }
    window.pipelineMessageBox = null;
    window.pipelineMessageHistory = [];
    // Reset hasPendingCandidates when starting a new message
    // This ensures the input field is enabled for the new query
    hasPendingCandidates = false;
    setReadOnlyMode(isReadOnlyMode);
  }
  if (isSending && !allowReentry) return;
  if (!allowReentry) setSending(true);

  // Validate data catalog selection - must have at least one catalog selected
  if (!validateDataCatalogSelection()) {
    showToast('Please select at least one portal before sending.', 'error');
    setSending(false);
    // Don't reset hasPendingCandidates here - it should remain as is
    return;
  }

  // Validate and correct hyperparameters before sending
  const hyperparams = getHyperparameters();
  const validation = validateHyperparameters(hyperparams);
  if (!validation.valid) {
    // Try to correct hyperparameters automatically
    let corrected = false;
    const correctedParams = { ...hyperparams };
    const corrections = [];
    
    // Correct weights (must be > 0)
    const weightNames = {
      weightTopic: 'wTopic',
      weightFormat: 'wFormat',
      weightLicense: 'wLicense',
      weightOrganization: 'wOrganization',
      weightSpace: 'wSpace',
      weightTime: 'wTime'
    };
    
    Object.keys(weightNames).forEach(weightKey => {
      const value = correctedParams[weightKey];
      if (!value || value <= 0 || !isFinite(value)) {
        const oldValue = value;
        correctedParams[weightKey] = 0.01; // Set to minimum valid value
        corrections.push(`${weightNames[weightKey]}: ${oldValue} → 0.01`);
        corrected = true;
      }
    });
    
    // Correct similarityScoreThreshold (0.4 to 1)
    if (correctedParams.similarityScoreThreshold < 0.4) {
      const oldValue = correctedParams.similarityScoreThreshold;
      correctedParams.similarityScoreThreshold = 0.4;
      corrections.push(`Similarity Score Threshold: ${oldValue} → 0.4`);
      corrected = true;
    } else if (correctedParams.similarityScoreThreshold > 1) {
      const oldValue = correctedParams.similarityScoreThreshold;
      correctedParams.similarityScoreThreshold = 1;
      corrections.push(`Similarity Score Threshold: ${oldValue} → 1`);
      corrected = true;
    }
    
    // Correct confidenceThreshold (0.3 to 0.9)
    if (correctedParams.confidenceThreshold < 0.3) {
      const oldValue = correctedParams.confidenceThreshold;
      correctedParams.confidenceThreshold = 0.3;
      corrections.push(`Confidence Threshold: ${oldValue} → 0.3`);
      corrected = true;
    } else if (correctedParams.confidenceThreshold > 0.9) {
      const oldValue = correctedParams.confidenceThreshold;
      correctedParams.confidenceThreshold = 0.9;
      corrections.push(`Confidence Threshold: ${oldValue} → 0.9`);
      corrected = true;
    }
    
    if (corrected) {
      // Update input values to corrected values (round to 0.01 precision)
      const roundToStep = (val) => Math.round(val * 100) / 100;
      
      if (correctedParams.weightTopic !== undefined) {
        document.getElementById('weight-topic').value = roundToStep(correctedParams.weightTopic);
      }
      if (correctedParams.weightFormat !== undefined) {
        document.getElementById('weight-format').value = roundToStep(correctedParams.weightFormat);
      }
      if (correctedParams.weightLicense !== undefined) {
        document.getElementById('weight-license').value = roundToStep(correctedParams.weightLicense);
      }
      if (correctedParams.weightOrganization !== undefined) {
        document.getElementById('weight-organization').value = roundToStep(correctedParams.weightOrganization);
      }
      if (correctedParams.weightSpace !== undefined) {
        document.getElementById('weight-space').value = roundToStep(correctedParams.weightSpace);
      }
      if (correctedParams.weightTime !== undefined) {
        document.getElementById('weight-time').value = roundToStep(correctedParams.weightTime);
      }
      if (correctedParams.similarityScoreThreshold !== undefined) {
        document.getElementById('similarity-score-threshold').value = roundToStep(correctedParams.similarityScoreThreshold);
      }
      if (correctedParams.confidenceThreshold !== undefined) {
        document.getElementById('confidence-threshold').value = roundToStep(correctedParams.confidenceThreshold);
      }
      
      // Show correction message
      const correctionMsg = corrections.length > 0 
        ? `Some hyperparameter values were out of range and have been corrected:\n${corrections.join('\n')}`
        : 'Some hyperparameter values were out of range and have been corrected.';
      showToast(correctionMsg, 'warning');
      
      // Re-validate with corrected values
      const revalidation = validateHyperparameters(correctedParams);
      if (!revalidation.valid) {
        showToast(revalidation.error, 'error');
        setSending(false);
        return;
      }
      // Use corrected parameters
      Object.assign(hyperparams, correctedParams);
    } else {
    showToast(validation.error, 'error');
    setSending(false);
    return;
    }
  }

  // Create new conversation if needed
  if (!currentConversationId) {
    try {
      const apiKey = getApiKey();
      const createUrl = `${API_URL.replace('/query', '/conversations')}`;
      const createRes = await fetch(createUrl, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ apiKey })
      });
      if (createRes.ok) {
        const createData = await createRes.json();
        currentConversationId = createData.conversationId;
        // Update URL to include conversation ID
        const url = new URL(window.location.href);
        url.searchParams.set('conversation', currentConversationId);
        window.history.pushState({ conversationId: currentConversationId }, '', url);
        connectStatusStream();
        // Refresh conversation history to show new conversation (immediate)
        loadConversationHistory(true);
      }
    } catch (e) {
      // Failed to create conversation - silently ignore
    }
  }

  // Ensure status stream is connected
  if (!statusEventSource || statusEventSource.readyState === EventSource.CLOSED) {
    connectStatusStream();
  }

  // Check if this is a dimension selection (format: "Dimension:1,2" or "Dimension:continue")
  // Don't reset pipeline and intent for dimension selections - they should continue from previous state
  const isDimensionSelection = query && typeof query === 'string' && query.match(/^((Topic|Format|License|Organization|Source):(.+?)(;\s*)?)+$/i);

  // Reset pipeline, logs, and intent for new question (but not for dimension selections)
  if (!isDimensionSelection) {
    initPipelineGraph();
    // Clear logs panel
    if (logsPanel) {
      logsPanel.innerHTML = '<div class="logs-empty">No logs yet.</div>';
    }
    seenLogKeys.clear();
    // Clear intent panel (will be updated when new intent arrives)
    if (intentPanel) {
      intentPanel.innerHTML = '<div class="intent-empty">No intent yet. Send a message to see parsed intent here.</div>';
      intentPanel.style.display = 'block';
    }
    if (intentRaw) {
      intentRaw.textContent = '{}';
    }
    // Clear pipeline message box and history
    const existingBox = document.getElementById("pipeline-progress-box");
    if (existingBox) {
      existingBox.remove();
    }
    window.pipelineMessageBox = null;
    window.pipelineMessageHistory = [];
  }
  // For dimension selections, keep the current pipeline and intent state
  // They will be updated by SSE events from the backend

  try {
    const payload = {
      conversationId: currentConversationId,
      apiKey: getApiKey(),
      query: query ?? "",
      action,
      model: currentModel(),
      useKeywords: true,
      hyperparameters: hyperparams,
      ...extra
    };

    const res = await fetch(API_URL, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });

    if (!res.ok) {
      const text = await res.text();
      appendMessage("assistant", `Backend error (${res.status}): ${text}`);
      setStatus("error", 0);
      // On error, ensure input is enabled (unless there are still pending candidates)
      // Don't reset hasPendingCandidates here - let it remain as is to preserve state
      return;
    }

    const data = await readJsonOrText(res);

    // Update currentConversationId from response if provided
    if (data.conversationId) {
      const wasNewConversation = !currentConversationId;
      currentConversationId = data.conversationId;
      // Refresh conversation history - backend may have updated title based on intent
      // Use debounced refresh to avoid too frequent updates
      loadConversationHistory(false);
    }

    // Check if there's an error in the response
    const stage = data.stage || "idle";
    if (stage === "error" || (data.reply && String(data.reply).toLowerCase().startsWith("error:"))) {
      // Display error message in chat
      const errorMsg = data.reply || "An error occurred. Please check your API key and try again.";
      appendMessage("assistant", errorMsg);
      setStatus("error", 0);
      // On error, ensure input is enabled (unless there are still pending candidates)
      // Don't reset hasPendingCandidates here - let it remain as is to preserve state
      return;
    }

    // Render complete assistant response
    renderCompleteAssistantResponse(data, false);
    
    const pending = data.pending_hitl || data.pendingHitl || null;

    // Robust auto-continue logic
    const nextAction = (data.next_action ?? data.nextAction ?? "").toString().toLowerCase();
    const shouldContinue = (nextAction === "continue") && !pending;

    if (shouldContinue) {
      await sendToBackend({ query: "", action: "continue", allowReentry: true });
    }

  } catch (e) {
    appendMessage("assistant", `Network error: ${e}`);
    setStatus("error", 0);
  } finally {
    if (!allowReentry) setSending(false);
  }
}

// Events
if (sendBtn) {
  sendBtn.addEventListener("click", async () => {
    if (isReadOnlyMode) {
      showToast('This is a shared conversation. You can only view it, not send messages.', 'info');
      return;
    }
    
    // Block sending when there are pending candidates (non-auto mode)
    if (hasPendingCandidates) {
      showToast('Please select all candidates above first.', 'info');
      return;
    }
    
    const text = (messageInput?.value ?? "").trim();
    if (!text) return;

    appendMessage("user", text);
    messageInput.value = "";
    autoResizeTextarea(messageInput);
    
    // Scroll to bottom after user message is added
    setTimeout(() => {
      if (chatMessages) {
        chatMessages.scrollTop = chatMessages.scrollHeight;
      }
    }, 50);

    await sendToBackend({ query: text, action: "message" });
  });
}

if (messageInput) {
  messageInput.addEventListener("keydown", async (e) => {
    // Block all keyboard input when there are pending candidates (non-auto mode)
    if (hasPendingCandidates) {
      e.preventDefault();
      showToast('Please select candidates above first. You cannot type when candidates are waiting for selection.', 'info');
      return;
    }
    
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      if (isReadOnlyMode) {
        showToast('This is a shared conversation. You can only view it, not send messages.', 'info');
        return;
      }
      sendBtn?.click();
    }
  });

  messageInput.addEventListener("input", (e) => {
    // Block all input when there are pending candidates (non-auto mode)
    if (hasPendingCandidates) {
      e.preventDefault();
      messageInput.value = '';
      showToast('Please select candidates above first. You cannot type when candidates are waiting for selection.', 'info');
      return;
    }
    autoResizeTextarea(messageInput);
  });
  
  // Prevent paste when there are pending candidates
  messageInput.addEventListener("paste", (e) => {
    if (hasPendingCandidates) {
      e.preventDefault();
      showToast('Please select candidates above first. You cannot paste when candidates are waiting for selection.', 'info');
      return;
    }
  });
}

// Handle hyperparameters details (no longer need click outside handler for left sidebar)
function initHyperparametersDetails() {
  // Hyperparameters are now in left sidebar, no need for click outside handler
  // The details element will work as a standard HTML details element
  
  // Add input restrictions for hyperparameters
  const weightInputs = [
    { id: 'weight-topic', min: 0.01 },
    { id: 'weight-format', min: 0.01 },
    { id: 'weight-license', min: 0.01 },
    { id: 'weight-organization', min: 0.01 },
    { id: 'weight-space', min: 0.01 },
    { id: 'weight-time', min: 0.01 }
  ];
  
  // Add restrictions for weight inputs (must be > 0)
  // Allow typing any value, but validate and correct on blur
  weightInputs.forEach(({ id, min }) => {
    const input = document.getElementById(id);
    if (input) {
      // Store initial value as last valid
      input.dataset.lastValid = input.value || min;
      
      // On blur: validate and correct if out of range, show toast notification
      input.addEventListener('blur', function() {
        const value = parseFloat(this.value);
        const originalValue = this.value;
        let corrected = false;
        let correctedValue = null;
        
        if (isNaN(value) || value <= 0) {
          // Value is invalid or <= 0, correct to minimum
          correctedValue = min;
          corrected = true;
        } else {
          // Round to 0.01 precision (2 decimal places)
          const rounded = Math.round(value * 100) / 100;
          correctedValue = rounded % 1 === 0 ? rounded.toString() : rounded.toFixed(2);
          // Check if value was actually changed by rounding
          if (parseFloat(originalValue) !== parseFloat(correctedValue)) {
            corrected = true;
          }
        }
        
        if (corrected) {
          this.value = correctedValue;
          this.dataset.lastValid = correctedValue;
          
          // Get display name for the weight
          const displayName = id.replace('weight-', '').replace(/([A-Z])/g, ' $1').trim()
            .replace(/^./, str => str.toUpperCase());
          
          if (isNaN(value) || value <= 0) {
            showToast(`${displayName} must be greater than 0. Value corrected to ${correctedValue}.`, 'warning');
          } else {
            showToast(`${displayName} rounded to ${correctedValue} (0.01 precision).`, 'info');
          }
        } else {
          this.dataset.lastValid = correctedValue;
        }
      });
    }
  });
  
  // Similarity Score Threshold: must be between 0.4 and 1
  // Allow typing any value, but validate and correct on blur
  const similarityInput = document.getElementById('similarity-score-threshold');
  if (similarityInput) {
    // Store initial value as last valid
    similarityInput.dataset.lastValid = similarityInput.value || 0.7;
    
    similarityInput.addEventListener('blur', function() {
      const value = parseFloat(this.value);
      const originalValue = this.value;
      let corrected = false;
      let correctedValue = null;
      
      if (isNaN(value)) {
        correctedValue = 0.7;
        corrected = true;
      } else if (value < 0.4) {
        correctedValue = 0.4;
        corrected = true;
      } else if (value > 1) {
        correctedValue = 1;
        corrected = true;
      } else {
        // Round to 0.01 precision (2 decimal places)
        const rounded = Math.round(value * 100) / 100;
        correctedValue = rounded % 1 === 0 ? rounded.toString() : rounded.toFixed(2);
        // Check if value was actually changed by rounding
        if (parseFloat(originalValue) !== parseFloat(correctedValue)) {
          corrected = true;
        }
      }
      
      if (corrected) {
        this.value = correctedValue;
        this.dataset.lastValid = correctedValue;
        
        if (isNaN(value)) {
          showToast('Similarity Score Threshold must be a valid number. Value corrected to 0.7.', 'warning');
        } else if (value < 0.4) {
          showToast(`Similarity Score Threshold must be between 0.4 and 1. Value corrected from ${originalValue} to ${correctedValue}.`, 'warning');
        } else if (value > 1) {
          showToast(`Similarity Score Threshold must be between 0.4 and 1. Value corrected from ${originalValue} to ${correctedValue}.`, 'warning');
        } else {
          showToast(`Similarity Score Threshold rounded to ${correctedValue} (0.01 precision).`, 'info');
        }
      } else {
        this.dataset.lastValid = correctedValue;
      }
    });
  }
  
  // Confidence Threshold: must be between 0.3 and 0.9
  // Allow typing any value, but validate and correct on blur
  const confidenceInput = document.getElementById('confidence-threshold');
  if (confidenceInput) {
    // Store initial value as last valid
    confidenceInput.dataset.lastValid = confidenceInput.value || 0.5;
    
    confidenceInput.addEventListener('blur', function() {
      const value = parseFloat(this.value);
      const originalValue = this.value;
      let corrected = false;
      let correctedValue = null;
      
      if (isNaN(value)) {
        correctedValue = 0.5;
        corrected = true;
      } else if (value < 0.3) {
        correctedValue = 0.3;
        corrected = true;
      } else if (value > 0.9) {
        correctedValue = 0.9;
        corrected = true;
      } else {
        // Round to 0.01 precision (2 decimal places)
        const rounded = Math.round(value * 100) / 100;
        correctedValue = rounded % 1 === 0 ? rounded.toString() : rounded.toFixed(2);
        // Check if value was actually changed by rounding
        if (parseFloat(originalValue) !== parseFloat(correctedValue)) {
          corrected = true;
        }
      }
      
      if (corrected) {
        this.value = correctedValue;
        this.dataset.lastValid = correctedValue;
        
        if (isNaN(value)) {
          showToast('Confidence Threshold must be a valid number. Value corrected to 0.5.', 'warning');
        } else if (value < 0.3) {
          showToast(`Confidence Threshold must be between 0.3 and 0.9. Value corrected from ${originalValue} to ${correctedValue}.`, 'warning');
        } else if (value > 0.9) {
          showToast(`Confidence Threshold must be between 0.3 and 0.9. Value corrected from ${originalValue} to ${correctedValue}.`, 'warning');
        } else {
          showToast(`Confidence Threshold rounded to ${correctedValue} (0.01 precision).`, 'info');
        }
      } else {
        this.dataset.lastValid = correctedValue;
      }
    });
  }
}

// Handle search mode change to update default threshold
function initSearchModeListener() {
  if (!searchModeSelect) return;
  
  searchModeSelect.addEventListener("change", function() {
    const useEmbeddingSearch = this.value === "embedding";
    const similarityThresholdInput = document.getElementById("similarity-score-threshold");
    const similarityThresholdLabel = document.getElementById("similarity-threshold-label");
    
    if (similarityThresholdInput) {
      // Update default value to 0.7 for both embedding and text search modes
      if (!similarityThresholdInput.value || similarityThresholdInput.value === "0.7" || similarityThresholdInput.value === "0.5" || similarityThresholdInput.value === "0.1" || similarityThresholdInput.value === "0.0") {
        similarityThresholdInput.value = "0.7";
      }
    }
    
    if (similarityThresholdLabel) {
      similarityThresholdLabel.textContent = useEmbeddingSearch ? "Similarity Score Threshold" : "Similarity Score Threshold";
    }
  });
}

// Handle sidebar toggle (collapse/expand)
function initSidebarToggles() {
  const appRoot = document.querySelector(".app-root");
  const leftSidebar = document.getElementById("sidebar-left");
  const rightSidebar = document.getElementById("sidebar-right");
  const toggleLeftBtn = document.getElementById("toggle-left-sidebar");
  const toggleRightBtn = document.getElementById("toggle-right-sidebar");
  
  // Function to update grid layout based on collapsed state
  function updateGridLayout() {
    if (!appRoot) return;
    
    const leftCollapsed = leftSidebar?.classList.contains("collapsed");
    const rightCollapsed = rightSidebar?.classList.contains("collapsed");
    
    // Clear any inline width styles that might interfere with collapsed state
    if (leftSidebar) {
      if (leftCollapsed) {
        // When collapsed, clear inline styles to let CSS width: 3rem take effect
        leftSidebar.style.width = "";
        leftSidebar.style.minWidth = "";
        leftSidebar.style.maxWidth = "";
      } else {
        // When expanded, also clear to use default CSS
        leftSidebar.style.width = "";
        leftSidebar.style.minWidth = "";
        leftSidebar.style.maxWidth = "";
      }
    }
    
    if (rightSidebar) {
      if (rightCollapsed) {
        // When collapsed, clear inline styles to let CSS width: 3rem take effect
        rightSidebar.style.width = "";
        rightSidebar.style.minWidth = "";
        rightSidebar.style.maxWidth = "";
      } else {
        // When expanded, also clear to use default CSS
        rightSidebar.style.width = "";
        rightSidebar.style.minWidth = "";
        rightSidebar.style.maxWidth = "";
      }
    }
    
    if (leftCollapsed && rightCollapsed) {
      // Both collapsed: middle takes all space
      appRoot.style.gridTemplateColumns = "3rem 1fr 3rem";
    } else if (leftCollapsed) {
      // Only left collapsed: middle expands, right keeps fixed width
      // Use stored original width or measure current width
      const rightWidth = rightSidebar?.dataset.originalWidth || 
                        (rightSidebar ? Math.max(rightSidebar.offsetWidth || 0, 240) : 240);
      appRoot.style.gridTemplateColumns = `3rem 1fr ${rightWidth}px`;
    } else if (rightCollapsed) {
      // Only right collapsed: middle expands, left keeps fixed width
      // Use stored original width or measure current width
      const leftWidth = leftSidebar?.dataset.originalWidth || 
                       (leftSidebar ? Math.max(leftSidebar.offsetWidth || 0, 200) : 200);
      appRoot.style.gridTemplateColumns = `${leftWidth}px 1fr 3rem`;
    } else {
      // Both expanded: store original widths on first run, then use fixed widths
      let needsMeasurement = false;
      
      if (leftSidebar && !leftSidebar.dataset.originalWidth) {
        needsMeasurement = true;
        // Try to restore from localStorage first
        const savedWidth = localStorage.getItem("sidebar-left-width");
        if (savedWidth) {
          leftSidebar.dataset.originalWidth = savedWidth;
        } else {
        // Try to measure immediately, fallback to default
        const measuredWidth = leftSidebar.offsetWidth || 200;
        if (measuredWidth > 0) {
          leftSidebar.dataset.originalWidth = measuredWidth;
          }
        }
      }
      
      if (rightSidebar && !rightSidebar.dataset.originalWidth) {
        needsMeasurement = true;
        // Try to restore from localStorage first
        const savedWidth = localStorage.getItem("sidebar-right-width");
        if (savedWidth) {
          rightSidebar.dataset.originalWidth = savedWidth;
        } else {
        // Try to measure immediately, fallback to default
        const measuredWidth = rightSidebar.offsetWidth || 240;
        if (measuredWidth > 0) {
          rightSidebar.dataset.originalWidth = measuredWidth;
          }
        }
      }
      
      // If we needed to measure and got valid measurements, re-run once more
      if (needsMeasurement && leftSidebar?.dataset.originalWidth && rightSidebar?.dataset.originalWidth) {
        setTimeout(() => updateGridLayout(), 0);
      }
      
      const leftWidth = leftSidebar?.dataset.originalWidth || 200;
      const rightWidth = rightSidebar?.dataset.originalWidth || 240;
      appRoot.style.gridTemplateColumns = `${leftWidth}px 1fr ${rightWidth}px`;
    }
  }
  
  if (toggleLeftBtn && leftSidebar) {
    toggleLeftBtn.addEventListener("click", () => {
      leftSidebar.classList.toggle("collapsed");
      // Save state to localStorage
      localStorage.setItem("sidebar-left-collapsed", leftSidebar.classList.contains("collapsed"));
      // Clear any inline styles that might interfere
      leftSidebar.style.width = "";
      leftSidebar.style.minWidth = "";
      leftSidebar.style.maxWidth = "";
      // Update grid layout
      updateGridLayout();
    });
    
    // Restore state from localStorage
    const leftCollapsed = localStorage.getItem("sidebar-left-collapsed") === "true";
    if (leftCollapsed) {
      leftSidebar.classList.add("collapsed");
      // Clear inline styles on restore
      leftSidebar.style.width = "";
      leftSidebar.style.minWidth = "";
      leftSidebar.style.maxWidth = "";
    }
  }
  
  if (toggleRightBtn && rightSidebar) {
    toggleRightBtn.addEventListener("click", () => {
      rightSidebar.classList.toggle("collapsed");
      // Save state to localStorage
      localStorage.setItem("sidebar-right-collapsed", rightSidebar.classList.contains("collapsed"));
      // Clear any inline styles that might interfere
      rightSidebar.style.width = "";
      rightSidebar.style.minWidth = "";
      rightSidebar.style.maxWidth = "";
      // Update grid layout
      updateGridLayout();
    });
    
    // Restore state from localStorage
    const rightCollapsed = localStorage.getItem("sidebar-right-collapsed") === "true";
    if (rightCollapsed) {
      rightSidebar.classList.add("collapsed");
      // Clear inline styles on restore
      rightSidebar.style.width = "";
      rightSidebar.style.minWidth = "";
      rightSidebar.style.maxWidth = "";
    }
  }
  
  // Initial grid layout update - use setTimeout to ensure DOM is fully rendered
  setTimeout(() => {
    updateGridLayout();
    // Also update on window resize to handle initial sizing
    window.addEventListener('resize', () => {
      // Only recalculate if both sidebars are expanded
      const leftCollapsed = leftSidebar?.classList.contains("collapsed");
      const rightCollapsed = rightSidebar?.classList.contains("collapsed");
      if (!leftCollapsed && !rightCollapsed) {
        // Clear stored widths to remeasure
        if (leftSidebar) delete leftSidebar.dataset.originalWidth;
        if (rightSidebar) delete rightSidebar.dataset.originalWidth;
        updateGridLayout();
      }
    });
  }, 100);
}

// Handle sidebar resizing (drag to adjust width)
function initSidebarResizers() {
  const appRoot = document.querySelector(".app-root");
  const leftSidebar = document.getElementById("sidebar-left");
  const rightSidebar = document.getElementById("sidebar-right");
  const leftResizer = document.getElementById("sidebar-resizer-left");
  const rightResizer = document.getElementById("sidebar-resizer-right");
  
  if (!appRoot || !leftSidebar || !rightSidebar) return;
  
  // Helper function to update grid layout with new widths
  function updateGridWithWidths(leftWidth, rightWidth) {
    const leftCollapsed = leftSidebar.classList.contains("collapsed");
    const rightCollapsed = rightSidebar.classList.contains("collapsed");
    
    if (leftCollapsed && rightCollapsed) {
      appRoot.style.gridTemplateColumns = "3rem 1fr 3rem";
    } else if (leftCollapsed) {
      appRoot.style.gridTemplateColumns = `3rem 1fr ${rightWidth}px`;
    } else if (rightCollapsed) {
      appRoot.style.gridTemplateColumns = `${leftWidth}px 1fr 3rem`;
    } else {
      appRoot.style.gridTemplateColumns = `${leftWidth}px 1fr ${rightWidth}px`;
    }
    
    // Save widths to localStorage
    if (!leftCollapsed) {
      localStorage.setItem("sidebar-left-width", leftWidth.toString());
      leftSidebar.dataset.originalWidth = leftWidth.toString();
    }
    if (!rightCollapsed) {
      localStorage.setItem("sidebar-right-width", rightWidth.toString());
      rightSidebar.dataset.originalWidth = rightWidth.toString();
    }
  }
  
  // Left resizer
  if (leftResizer) {
    let isResizing = false;
    let startX = 0;
    let startWidth = 0;
    
    leftResizer.addEventListener("mousedown", (e) => {
      if (leftSidebar.classList.contains("collapsed")) return;
      
      isResizing = true;
      startX = e.clientX;
      startWidth = leftSidebar.offsetWidth;
      
      document.body.style.cursor = "col-resize";
      document.body.style.userSelect = "none";
      
      e.preventDefault();
    });
    
    document.addEventListener("mousemove", (e) => {
      if (!isResizing) return;
      
      const diff = e.clientX - startX;
      const newWidth = Math.max(200, Math.min(600, startWidth + diff)); // Min 200px, max 600px
      
      const rightCollapsed = rightSidebar.classList.contains("collapsed");
      const rightWidth = rightCollapsed ? 0 : (parseInt(rightSidebar.dataset.originalWidth) || 240);
      
      updateGridWithWidths(newWidth, rightWidth);
    });
    
    document.addEventListener("mouseup", () => {
      if (isResizing) {
        isResizing = false;
        document.body.style.cursor = "";
        document.body.style.userSelect = "";
      }
    });
  }
  
  // Right resizer
  if (rightResizer) {
    let isResizing = false;
    let startX = 0;
    let startWidth = 0;
    
    rightResizer.addEventListener("mousedown", (e) => {
      if (rightSidebar.classList.contains("collapsed")) return;
      
      isResizing = true;
      startX = e.clientX;
      startWidth = rightSidebar.offsetWidth;
      
      document.body.style.cursor = "col-resize";
      document.body.style.userSelect = "";
      
      e.preventDefault();
    });
    
    document.addEventListener("mousemove", (e) => {
      if (!isResizing) return;
      
      const diff = startX - e.clientX; // Reverse for right sidebar
      const newWidth = Math.max(200, Math.min(600, startWidth + diff)); // Min 200px, max 600px
      
      const leftCollapsed = leftSidebar.classList.contains("collapsed");
      const leftWidth = leftCollapsed ? 0 : (parseInt(leftSidebar.dataset.originalWidth) || 200);
      
      updateGridWithWidths(leftWidth, newWidth);
    });
    
    document.addEventListener("mouseup", () => {
      if (isResizing) {
        isResizing = false;
        document.body.style.cursor = "";
        document.body.style.userSelect = "";
      }
    });
  }
  
  // Restore saved widths from localStorage
  const savedLeftWidth = localStorage.getItem("sidebar-left-width");
  const savedRightWidth = localStorage.getItem("sidebar-right-width");
  
  if (savedLeftWidth && !leftSidebar.classList.contains("collapsed")) {
    leftSidebar.dataset.originalWidth = savedLeftWidth;
  }
  
  if (savedRightWidth && !rightSidebar.classList.contains("collapsed")) {
    rightSidebar.dataset.originalWidth = savedRightWidth;
  }
}

// Initialize API key from localStorage
function initApiKey() {
  const apiKeyInput = document.getElementById("api-key-input");
  const apiKeyToggle = document.getElementById("api-key-toggle");
  const apiKeyHint = document.getElementById("api-key-hint");
  const apiKeyInfoBtn = document.getElementById("api-key-info-btn");
  
  // Handle API key info button click
  if (apiKeyInfoBtn) {
    const apiKeyInfoTooltip = document.getElementById("api-key-info-tooltip");
    apiKeyInfoBtn.addEventListener("click", (e) => {
      e.preventDefault();
      e.stopPropagation();
      if (apiKeyInfoTooltip) {
        const isVisible = apiKeyInfoTooltip.style.display !== 'none';
        if (isVisible) {
          apiKeyInfoTooltip.style.display = 'none';
        } else {
          // Calculate position dynamically to ensure full visibility
          const btnRect = apiKeyInfoBtn.getBoundingClientRect();
          const tooltipWidth = 300; // Estimated width
          const tooltipHeight = 80; // Estimated height
          const padding = 8;
          
          // Position to the right of the button by default
          let left = btnRect.right + padding;
          let top = btnRect.top;
          
          // Check if tooltip would overflow viewport on the right
          if (left + tooltipWidth > window.innerWidth) {
            // Position to the left of the button instead
            left = btnRect.left - tooltipWidth - padding;
            // Adjust arrow to point right (tooltip on left side)
            apiKeyInfoTooltip.style.setProperty('--arrow-left', 'auto');
            apiKeyInfoTooltip.style.setProperty('--arrow-right', '0.5rem');
            apiKeyInfoTooltip.style.setProperty('--arrow-direction', '0');
            apiKeyInfoTooltip.style.setProperty('--arrow-left-border', '0.35rem solid var(--border-subtle)');
            apiKeyInfoTooltip.style.setProperty('--arrow-after-left', 'auto');
            apiKeyInfoTooltip.style.setProperty('--arrow-after-right', '0.6rem');
            apiKeyInfoTooltip.style.setProperty('--arrow-after-direction', '0');
            apiKeyInfoTooltip.style.setProperty('--arrow-after-left-border', '0.35rem solid var(--bg-main)');
          } else {
            // Default: arrow pointing left (tooltip on right side)
            apiKeyInfoTooltip.style.setProperty('--arrow-left', '-0.35rem');
            apiKeyInfoTooltip.style.setProperty('--arrow-right', 'auto');
            apiKeyInfoTooltip.style.setProperty('--arrow-direction', '0.35rem solid var(--border-subtle)');
            apiKeyInfoTooltip.style.setProperty('--arrow-left-border', '0');
            apiKeyInfoTooltip.style.setProperty('--arrow-after-left', '-0.3rem');
            apiKeyInfoTooltip.style.setProperty('--arrow-after-right', 'auto');
            apiKeyInfoTooltip.style.setProperty('--arrow-after-direction', '0.35rem solid var(--bg-main)');
            apiKeyInfoTooltip.style.setProperty('--arrow-after-left-border', '0');
          }
          
          // Check if tooltip would overflow viewport on the bottom
          if (top + tooltipHeight > window.innerHeight) {
            top = window.innerHeight - tooltipHeight - padding;
          }
          
          // Check if tooltip would overflow viewport on the top
          if (top < padding) {
            top = padding;
          }
          
          apiKeyInfoTooltip.style.left = left + 'px';
          apiKeyInfoTooltip.style.top = top + 'px';
          apiKeyInfoTooltip.style.display = 'block';
        }
      }
    });
    
    // Close tooltip when clicking outside
    document.addEventListener("click", (e) => {
      if (apiKeyInfoTooltip && apiKeyInfoBtn && 
          !apiKeyInfoTooltip.contains(e.target) && 
          !apiKeyInfoBtn.contains(e.target)) {
        apiKeyInfoTooltip.style.display = 'none';
      }
    });
  }
  
  if (apiKeyInput) {
    const stored = localStorage.getItem("igdd-api-key");
    if (stored) {
      apiKeyInput.value = stored;
    }
    
    // Show/hide hint based on API key presence
    const updateHint = () => {
      if (apiKeyHint) {
        const hasKey = apiKeyInput.value.trim().length > 0 || localStorage.getItem("igdd-api-key");
        apiKeyHint.style.display = hasKey ? 'none' : 'block';
      }
    };
    
    updateHint();
    
    apiKeyInput.addEventListener("change", (e) => {
      const value = e.target.value.trim();
      if (value) {
        localStorage.setItem("igdd-api-key", value);
      } else {
        localStorage.removeItem("igdd-api-key");
      }
      updateHint();
      // Reload conversation history when API key changes (case-sensitive)
      loadConversationHistory(true); // Immediate refresh
    });
    
    // Also reload on blur to catch any manual edits
    apiKeyInput.addEventListener("blur", () => {
      const value = apiKeyInput.value.trim();
      const stored = localStorage.getItem("igdd-api-key");
      // If value changed (case-sensitive comparison), update and reload
      if (value !== stored) {
        if (value) {
          localStorage.setItem("igdd-api-key", value);
        } else {
          localStorage.removeItem("igdd-api-key");
        }
        loadConversationHistory(true); // Immediate refresh
      }
    });
    
    apiKeyInput.addEventListener("input", () => {
      updateHint();
    });
  }
  
  // Toggle API key visibility
  if (apiKeyToggle && apiKeyInput) {
    apiKeyToggle.addEventListener("click", (e) => {
      e.preventDefault();
      e.stopPropagation();
      const isPassword = apiKeyInput.type === 'password';
      apiKeyInput.type = isPassword ? 'text' : 'password';
      apiKeyToggle.textContent = isPassword ? '○' : '●';
    });
  }
}

// Conversation history management
const conversationHistoryList = document.getElementById("conversation-history-list");
let conversationHistoryExpanded = false; // Track if conversation history is expanded

// Debounce conversation history loading to avoid too frequent refreshes
let conversationHistoryLoadTimeout = null;
async function loadConversationHistory(immediate = false) {
  if (!conversationHistoryList) return;
  
  // Debounce: if not immediate, wait a bit to batch multiple calls
  if (!immediate && conversationHistoryLoadTimeout !== null) {
    clearTimeout(conversationHistoryLoadTimeout);
  }
  
  const doLoad = async () => {
    const apiKey = getApiKey();
    if (!apiKey) {
      conversationHistoryList.innerHTML = '<div class="conversation-history-empty">Please enter an API key to view history.</div>';
      return;
    }
    
    try {
      const conversationsUrl = `${API_URL.replace('/query', '/conversations')}?apiKey=${encodeURIComponent(apiKey)}`;
      const response = await fetch(conversationsUrl);
      
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      }
      
      const data = await response.json();
      const conversations = data.conversations || [];
      
      if (conversations.length === 0) {
        conversationHistoryList.innerHTML = '<div class="conversation-history-empty">No conversations yet.</div>';
      } else {
        renderConversationHistory(conversations);
      }
    } catch (error) {
      conversationHistoryList.innerHTML = '<div class="conversation-history-empty">Failed to load history.</div>';
    }
    conversationHistoryLoadTimeout = null;
  };
  
  if (immediate) {
    await doLoad();
  } else {
    conversationHistoryLoadTimeout = setTimeout(doLoad, 300); // Debounce 300ms
  }
}

function renderConversationHistory(conversations) {
  if (!conversationHistoryList) return;
  
  if (!conversations || conversations.length === 0) {
    conversationHistoryList.innerHTML = '<div class="conversation-history-empty">No conversations yet.</div>';
    conversationHistoryExpanded = false; // Reset expansion state
    return;
  }
  
  const DEFAULT_DISPLAY_COUNT = 5;
  const shouldShowAll = conversationHistoryExpanded;
  const displayCount = shouldShowAll ? conversations.length : Math.min(DEFAULT_DISPLAY_COUNT, conversations.length);
  const hasMore = conversations.length > DEFAULT_DISPLAY_COUNT;
  
  const items = conversations.slice(0, displayCount).map(conv => {
    const conversationId = conv.conversationId || conv.conversation_id || '';
    const title = conv.title || '';
    const createdAt = conv.createdAt || conv.created_at || '';
    const preview = conv.preview || '';
    const displayTitle = title || preview || 'New conversation';
    
    // Format date for display
    let dateDisplay = '';
    if (createdAt) {
      try {
        const date = new Date(createdAt);
        dateDisplay = date.toLocaleDateString() + ' ' + date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
      } catch (e) {
        dateDisplay = createdAt;
      }
    }
    
    const isActive = currentConversationId === conversationId;
    
    const shareable = conv.shareable || false;
    const shareBtnIcon = shareable ? '🔗' : '🔒';
    const shareBtnTitle = shareable ? 'Stop sharing' : 'Share conversation';
    
    return `
      <div class="conversation-history-item ${isActive ? 'active' : ''}" 
           data-conversation-id="${escapeHtml(conversationId)}">
        <div class="conversation-history-item-header">
          <div class="conversation-history-item-title" title="${escapeHtml(displayTitle)}">${escapeHtml(displayTitle)}</div>
          <div class="conversation-history-item-actions">
            <button class="conversation-history-share-btn" title="${shareBtnTitle}" onclick="event.stopPropagation(); toggleConversationShare('${escapeHtml(conversationId)}', ${shareable});">${shareBtnIcon}</button>
            <button class="conversation-history-edit-btn" title="Edit title" onclick="event.stopPropagation(); editConversationTitle('${escapeHtml(conversationId)}', '${escapeHtml(displayTitle)}');">✏️</button>
            <button class="conversation-history-delete-btn" title="Delete" onclick="event.stopPropagation(); deleteConversation('${escapeHtml(conversationId)}');">🗑️</button>
          </div>
        </div>
        <div class="conversation-history-item-meta">
          <span>${escapeHtml(dateDisplay)}</span>
        </div>
      </div>
    `;
  }).join('');
  
  // Add "Show more" / "Show less" button if needed
  let moreButtonHtml = '';
  if (hasMore) {
    const remainingCount = conversations.length - DEFAULT_DISPLAY_COUNT;
    if (shouldShowAll) {
      moreButtonHtml = `<button class="conversation-history-show-more-btn" onclick="toggleConversationHistoryExpansion()">Show less</button>`;
    } else {
      moreButtonHtml = `<button class="conversation-history-show-more-btn" onclick="toggleConversationHistoryExpansion()">Show ${remainingCount} more</button>`;
    }
  }
  
  conversationHistoryList.innerHTML = items + (moreButtonHtml ? `<div class="conversation-history-more-wrapper">${moreButtonHtml}</div>` : '');
  
  // Wire click handlers
  conversationHistoryList.querySelectorAll('.conversation-history-item').forEach(item => {
    item.addEventListener('click', (e) => {
      // Don't switch if clicking on action buttons
      if (e.target.closest('.conversation-history-item-actions')) return;
      const conversationId = item.dataset.conversationId;
      if (conversationId) {
        switchToConversation(conversationId);
      }
    });
  });
}

function toggleConversationHistoryExpansion() {
  conversationHistoryExpanded = !conversationHistoryExpanded;
  // Reload conversation history to re-render with new expansion state
  loadConversationHistory(true);
}

function switchToConversation(conversationId) {
  currentConversationId = conversationId;
  // Clear current chat, pipeline, intent, and logs
  if (chatMessages) {
    chatMessages.innerHTML = '';
  }
  // Reset pipeline to initial state
  initPipelineGraph();
  // Clear intent panel
  if (intentPanel) {
    intentPanel.innerHTML = '<div class="intent-empty">No intent available.</div>';
    intentPanel.style.display = 'none';
  }
  if (intentRaw) {
    intentRaw.textContent = '{}';
  }
  // Clear logs panel
  if (logsPanel) {
    logsPanel.innerHTML = '<div class="logs-empty">No logs yet.</div>';
  }
  seenLogKeys.clear();
  // Update URL with conversation ID
  if (conversationId) {
    const url = new URL(window.location.href);
    url.searchParams.set('conversation', conversationId);
    window.history.pushState({ conversationId }, '', url);
  } else {
    // Remove conversation from URL if no conversation selected
    const url = new URL(window.location.href);
    url.searchParams.delete('conversation');
    window.history.pushState({}, '', url);
  }
  // Reset read-only mode when switching conversations (user clicked from their own history)
  setReadOnlyMode(false);
  // Load conversation messages for this conversation
  loadConversationForSession(conversationId, false); // false = not from URL, so user is owner
  // Update active state
  if (conversationHistoryList) {
    conversationHistoryList.querySelectorAll('.conversation-history-item').forEach(item => {
      item.classList.toggle('active', item.dataset.conversationId === conversationId);
    });
  }
  // Reconnect status stream only if we have a conversationId
  // If conversationId is null (no conversation selected), ensure no SSE connection exists
  if (conversationId) {
    connectStatusStream();
  } else {
    // No conversation selected - close any existing SSE connection
    if (statusEventSource) {
      statusEventSource.close();
      statusEventSource = null;
    }
    // Ensure pipeline is in idle state when no conversation
    if (pipelineGraph) {
      PIPELINE_STEPS.forEach(step => {
        setPipelineStepState(step.id, "idle");
      });
    }
  }
}

// Show 404 page
function show404Page() {
  // Replace entire page content with 404 page
  document.body.innerHTML = `
    <!DOCTYPE html>
    <html lang="en">
    <head>
      <meta charset="UTF-8" />
      <title>404 - Page Not Found</title>
      <link rel="preconnect" href="https://fonts.googleapis.com" />
      <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
      <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap" rel="stylesheet" />
      <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
          font-family: "Inter", system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
          background: #f4f4f7;
          color: #020617;
          display: flex;
          align-items: center;
          justify-content: center;
          min-height: 100vh;
          padding: 2rem;
        }
        .error-container {
          text-align: center;
          max-width: 500px;
        }
        .error-code {
          font-size: 6rem;
          font-weight: 700;
          color: #059669;
          line-height: 1;
          margin-bottom: 1rem;
        }
        .error-title {
          font-size: 1.5rem;
          font-weight: 600;
          color: #020617;
          margin-bottom: 0.5rem;
        }
        .error-message {
          font-size: 1rem;
          color: #6b7280;
          margin-bottom: 2rem;
        }
        .error-actions {
          display: flex;
          gap: 1rem;
          justify-content: center;
        }
        .error-btn {
          padding: 0.75rem 1.5rem;
          border-radius: 0.5rem;
          border: none;
          background: #10b981;
          color: #ffffff;
          font-size: 0.95rem;
          font-weight: 500;
          cursor: pointer;
          text-decoration: none;
          display: inline-block;
          transition: background 0.2s ease;
        }
        .error-btn:hover {
          background: #059669;
        }
      </style>
    </head>
    <body>
      <div class="error-container">
        <div class="error-code">404</div>
        <div class="error-title">Conversation Not Found</div>
        <div class="error-message">
          The conversation you're looking for doesn't exist or is no longer available.
          It may have been deleted or the link is invalid.
        </div>
        <div class="error-actions">
          <a href="${window.location.pathname}" class="error-btn">Go to Home</a>
        </div>
      </div>
    </body>
    </html>
  `;
}

// Handle browser back/forward buttons
window.addEventListener('popstate', (event) => {
  const urlParams = new URLSearchParams(window.location.search);
  const conversationId = urlParams.get('conversation');
  if (conversationId) {
    switchToConversation(conversationId);
  } else {
    // Clear conversation if no conversation ID in URL
    // Close any existing SSE connection
    if (statusEventSource) {
      statusEventSource.close();
      statusEventSource = null;
    }
    currentConversationId = null;
    if (chatMessages) {
      chatMessages.innerHTML = '';
    }
    // Reset pipeline to idle state
    initPipelineGraph();
    if (pipelineGraph) {
      PIPELINE_STEPS.forEach(step => {
        setPipelineStepState(step.id, "idle");
      });
    }
    // Clear intent panel
    if (intentPanel) {
      intentPanel.innerHTML = '<div class="intent-empty">No intent available.</div>';
      intentPanel.style.display = 'none';
    }
    // Clear logs panel
    if (logsPanel) {
      logsPanel.innerHTML = '<div class="logs-empty">No logs yet.</div>';
    }
    seenLogKeys.clear();
    // Show welcome message
    appendMessage(
      "assistant",
      `Hi there!
I'm your intelligent geospatial data discovery assistant.

What data are you looking for today?
You can describe the topic, where and when, or any requirements such as format, license, or data source.

For inspiration, feel free to explore the Quick Examples in the left panel.`
    );
  }
});

async function createNewConversation() {
  // Reset read-only mode for new conversation
  setReadOnlyMode(false);
  try {
    const apiKey = getApiKey();
    if (!apiKey) {
      alert('Please enter an API key first.');
      return;
    }
    const createUrl = `${API_URL.replace('/query', '/conversations')}`;
    const createRes = await fetch(createUrl, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ apiKey })
    });
    if (createRes.ok) {
      const createData = await createRes.json();
      currentConversationId = createData.conversationId;
      // Load conversation history to update list
      await loadConversationHistory();
      // Switch to new conversation (this will update URL and show welcome message if turns are empty)
      switchToConversation(currentConversationId);
    }
  } catch (error) {
    alert('Failed to create new conversation.');
  }
}

function editConversationTitle(conversationId, currentTitle) {
  const item = document.querySelector(`[data-conversation-id="${escapeHtml(conversationId)}"]`);
  if (!item) return;
  
  const titleEl = item.querySelector('.conversation-history-item-title');
  if (!titleEl) return;
  
  const input = document.createElement('input');
  input.type = 'text';
  input.className = 'conversation-history-item-title-input';
  input.value = currentTitle;
  input.style.width = '100%';
  
  const saveEdit = async () => {
    const newTitle = input.value.trim();
    if (newTitle === '') {
      titleEl.textContent = currentTitle;
      titleEl.style.display = '';
      input.remove();
      return;
    }
    
    try {
      const updateUrl = `${API_URL.replace('/query', '/conversations')}/${encodeURIComponent(conversationId)}/title`;
      const updateRes = await fetch(updateUrl, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ title: newTitle })
      });
      if (updateRes.ok) {
        await loadConversationHistory(true); // Immediate refresh for user-initiated title change
        showToast('Title updated', 'success');
      } else {
        titleEl.textContent = currentTitle;
        titleEl.style.display = '';
        input.remove();
        showToast('Failed to update title', 'error');
      }
    } catch (error) {
      titleEl.textContent = currentTitle;
      titleEl.style.display = '';
      input.remove();
      showToast('Failed to update title', 'error');
    }
  };
  
  const cancelEdit = () => {
    titleEl.style.display = '';
    input.remove();
  };
  
  input.addEventListener('blur', saveEdit);
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      input.blur();
    } else if (e.key === 'Escape') {
      e.preventDefault();
      cancelEdit();
    }
  });
  
  titleEl.style.display = 'none';
  titleEl.parentNode.insertBefore(input, titleEl.nextSibling);
  input.focus();
  input.select();
}

async function deleteConversation(conversationId) {
  const confirmed = await showConfirmDialog('Are you sure you want to delete this conversation?');
  if (!confirmed) {
    return;
  }
  
  try {
    const deleteUrl = `${API_URL.replace('/query', '/conversations')}/${encodeURIComponent(conversationId)}`;
    const deleteRes = await fetch(deleteUrl, {
      method: "DELETE"
    });
    if (deleteRes.ok) {
      // If deleted conversation was current, create new one
      if (currentConversationId === conversationId) {
        currentConversationId = null;
        // Remove conversation from URL
        const url = new URL(window.location.href);
        url.searchParams.delete('conversation');
        window.history.pushState({}, '', url);
        if (chatMessages) {
          chatMessages.innerHTML = '';
        }
        appendMessage(
          "assistant",
          `Hi there!
I'm your intelligent geospatial data discovery assistant.

What data are you looking for today?
You can describe the topic, where and when, or any requirements such as format, license, or data source.

For inspiration, feel free to explore the Quick Examples in the left panel.`
        );
      }
      // Reload conversation history to update display (immediate)
      await loadConversationHistory(true);
      showToast('Conversation deleted', 'success');
    } else {
      showToast('Failed to delete conversation', 'error');
    }
  } catch (error) {
    showToast('Failed to delete conversation', 'error');
  }
}

// Check if current user is the owner of the conversation
async function checkConversationOwnership(conversationId) {
  const apiKey = getApiKey();
  if (!apiKey) {
    return false; // No API key means not the owner
  }
  
  try {
    // Try to get conversation list for this API key
    const conversationsUrl = `${API_URL.replace('/query', '/conversations')}?apiKey=${encodeURIComponent(apiKey)}`;
    const response = await fetch(conversationsUrl);
    
    if (!response.ok) {
      return false;
    }
    
    const data = await response.json();
    const conversations = data.conversations || [];
    
    // Check if this conversation ID is in the user's conversation list
    return conversations.some(conv => {
      const convId = conv.conversationId || conv.conversation_id || '';
      return convId === conversationId;
    });
  } catch (error) {
    return false;
  }
}

async function loadConversationForSession(conversationId, fromUrl = false) {
  // Always clear previous state first (pipeline, intent, logs)
  // Reset pipeline to initial state
  initPipelineGraph();
  // Clear intent panel
  if (intentPanel) {
    intentPanel.innerHTML = '<div class="intent-empty">No intent available.</div>';
    intentPanel.style.display = 'none';
  }
  if (intentRaw) {
    intentRaw.textContent = '{}';
  }
  // Clear logs panel
  if (logsPanel) {
    logsPanel.innerHTML = '<div class="logs-empty">No logs yet.</div>';
  }
  seenLogKeys.clear();
  // Reset pending candidates state when loading a conversation (historical conversations are already completed)
  hasPendingCandidates = false;
  // Update input and send button state (use setReadOnlyMode which handles hasPendingCandidates)
  setReadOnlyMode(isReadOnlyMode);
  
  if (!conversationId) {
    // Not a shared conversation, reset read-only mode
    setReadOnlyMode(false);
    appendMessage(
      "assistant",
      `Hi there!
I'm your intelligent geospatial data discovery assistant.

What data are you looking for today?
You can describe the topic, where and when, or any requirements such as format, license, or data source.

For inspiration, feel free to explore the Quick Examples in the left panel.`
    );
    return;
  }
  
  // If loaded from URL (shared link), check if user is the owner
  if (fromUrl) {
    const isOwner = await checkConversationOwnership(conversationId);
    setReadOnlyMode(!isOwner);
  } else {
    // Not from URL, assume user is owner (clicked from their own history)
    setReadOnlyMode(false);
  }
  
  try {
    // Load conversation - if user has API key, include it (in case conversation is not shareable)
    // If shareable, can be accessed without API key
    const apiKey = getApiKey();
    let turnsUrl = `${API_URL.replace('/query', '/conversations')}/${encodeURIComponent(conversationId)}/turns`;
    if (apiKey) {
      turnsUrl += `?apiKey=${encodeURIComponent(apiKey)}`;
    }
    const response = await fetch(turnsUrl);
    
    if (!response.ok) {
      if (response.status === 404) {
        // If loaded from URL (shared link), redirect to 404 page
        if (fromUrl) {
          show404Page();
          return;
        }
        // Otherwise (user clicked from history), show error message
        appendMessage(
          "assistant",
          "Conversation not found. It may have been deleted, not shareable, or the link is invalid."
        );
        return;
      }
      throw new Error(`HTTP ${response.status}: ${response.statusText}`);
    }
    
    const data = await response.json();
    const turns = data.turns || [];
    
    // Always show welcome message first for historical conversations
    // Check if first turn is a user message (meaning no welcome message was saved)
    const firstTurnIsUser = turns.length > 0 && turns[0] && turns[0].role === 'user';
    if (turns.length === 0 || firstTurnIsUser) {
      appendMessage(
        "assistant",
        `Hi there!
I'm your intelligent geospatial data discovery assistant.

What data are you looking for today?
You can describe the topic, where and when, or any requirements such as format, license, or data source.

For inspiration, feel free to explore the Quick Examples in the left panel.`
      );
    }
    
    // Find the last assistant turn with complete response data (for restoring pipeline/intent/logs)
    let lastAssistantTurn = null;
    for (let i = turns.length - 1; i >= 0; i--) {
      const turn = turns[i];
      if (turn.role === 'assistant') {
        const meta = turn.meta || {};
        if (meta.reply !== undefined || meta.datasets !== undefined || meta.pending_hitl !== undefined) {
          lastAssistantTurn = turn;
          break;
        }
      }
    }
    
    // Restore pipeline state, intent, and logs from the last assistant turn (if available)
    if (lastAssistantTurn && lastAssistantTurn.meta) {
      const meta = lastAssistantTurn.meta;
      
      // Restore pipeline states from the last turn
      if (meta.pipeline_states && Array.isArray(meta.pipeline_states)) {
        // Pipeline was already reset at the start of loadConversationForSession
        // Now restore each completed stage in order
        // Sort by pipeline step order to ensure proper restoration
        const stepOrder = ['intent_parsing', 'hitl_confirmation', 'entity_matching', 'spatial_temporal_filter', 'dataset_scoring', 'evidence_collection', 'dataset_selection', 'answer_synthesis'];
        const sortedStates = meta.pipeline_states.slice().sort((a, b) => {
          const aIndex = stepOrder.indexOf(a.stage);
          const bIndex = stepOrder.indexOf(b.stage);
          if (aIndex === -1) return 1;
          if (bIndex === -1) return -1;
          return aIndex - bIndex;
        });
        
        for (const state of sortedStates) {
          if (state.stage && state.status) {
            setPipelineStepState(state.stage, state.status, state.duration);
          }
        }
        
        // Update total time after restoring all states
        updatePipelineTotalTime();
      }
      
      // Restore intent panel if intent is available
      if (meta.intent) {
        renderIntentPanel(meta.intent);
        if (intentPanel) {
          intentPanel.style.display = 'block';
        }
      }
      
      // Restore logs if available (only from last turn to avoid duplicates)
      if (meta.logs && Array.isArray(meta.logs)) {
        // Clear existing logs first, then add restored logs
        if (logsPanel) {
          logsPanel.innerHTML = '';
          seenLogKeys.clear();
        }
        addLogs(meta.logs);
      }
    }
    
    // Collect pipeline messages first (before rendering other messages)
    const pipelineMessages = [];
    for (let i = 0; i < turns.length; i++) {
      const turn = turns[i];
      // Check both 'type' and 'agent' fields (agent is the database field name)
      const turnType = turn.type || turn.agent || '';
      if (turn.role === 'assistant' && turnType === 'pipeline') {
        const meta = turn.meta || {};
        pipelineMessages.push({
          stage: meta.stage || '',
          status: meta.status || 'active',
          message: turn.text || '',
          dimension_candidates: meta.dimension_candidates || null,
          timestamp: turn.timestamp || turn.ts || Date.now()
        });
      }
    }
    
    // Render all turns in order (except pipeline messages)
    // Track dimension candidates from previous assistant turns to convert user selection queries to display text
    let previousDimensionCandidates = {};
    let lastUserMessageElement = null; // Track the last user message element
    
    for (let i = 0; i < turns.length; i++) {
      const turn = turns[i];
      const role = turn.role || 'user';
      const text = turn.text || '';
      const meta = turn.meta || {};
      
      // Skip pipeline messages (will be inserted after last user message)
      const turnType = turn.type || turn.agent || '';
      if (role === 'assistant' && turnType === 'pipeline') {
        continue;
      }
      
      if (role === 'user') {
        if (text.trim()) {
          // Check if this is a dimension selection query (format: "Topic:1,2" or "Topic:continue")
          const isDimensionSelection = text.match(/^((Topic|Format|License|Organization|Source):(.+?)(;\s*)?)+$/i);
          if (isDimensionSelection && Object.keys(previousDimensionCandidates).length > 0) {
            // Convert query format to display text with candidate names
            const displayText = convertDimensionSelectionToDisplayText(text, previousDimensionCandidates);
            const userRow = appendMessage('user', displayText);
            if (userRow) lastUserMessageElement = userRow;
          } else {
            const userRow = appendMessage('user', text);
            if (userRow) lastUserMessageElement = userRow;
          }
        }
      } else if (role === 'assistant') {
        // Store dimension candidates for next user turn
        if (meta.dimension_candidates && Object.keys(meta.dimension_candidates).length > 0) {
          previousDimensionCandidates = meta.dimension_candidates;
        }
        // Check if we have complete response data in meta
        if (meta.reply !== undefined || meta.datasets !== undefined || meta.pending_hitl !== undefined) {
          // Render complete assistant response with all components (restore mode)
          renderCompleteAssistantResponse(meta, true);
          // Note: renderCompleteAssistantResponse already calls initializeTextShowMore
        } else {
          // Fallback to plain text for old data format
          if (text.trim()) {
            appendMessage('assistant', text);
          }
        }
      }
    }
    
    // Rebuild pipeline message box and history from collected messages
    // Insert it after the last user message (or at the end if no user messages)
    if (pipelineMessages.length > 0) {
      window.pipelineMessageHistory = pipelineMessages;
      
      // Create pipeline message box
      window.pipelineMessageBox = document.createElement("div");
      window.pipelineMessageBox.className = "message-row assistant pipeline-progress-box";
      window.pipelineMessageBox.id = "pipeline-progress-box";
      
      const avatarClass = "avatar-assistant";
      // Determine if we should show spinner based on the latest message status
      const latestMessage = pipelineMessages[pipelineMessages.length - 1];
      const isActive = latestMessage && latestMessage.status === "active";
      const avatarContent = isActive 
        ? '<div class="pipeline-avatar-spinner"></div>'
        : '<img src="igdd_logo.png" alt="IGDD" class="avatar-icon" />';
      const avatarActiveClass = isActive ? " pipeline-avatar-active" : "";
      window.pipelineMessageBox.innerHTML = `
        <div class="message-bubble-wrapper">
          <div class="avatar-circle ${avatarClass}${avatarActiveClass}">${avatarContent}</div>
          <div>
            <div class="message-meta">IGDD · Pipeline Progress</div>
            <div class="message-bubble assistant pipeline-progress-bubble">
              <div class="pipeline-progress-content"></div>
            </div>
          </div>
        </div>
      `;
      
      // Display the last pipeline message (done messages replace active)
      const lastMessage = pipelineMessages[pipelineMessages.length - 1];
      const contentDiv = window.pipelineMessageBox.querySelector(".pipeline-progress-content");
      if (contentDiv && lastMessage) {
        const parts = [];
        parts.push(`<div class="assistant-reply">${renderMarkdown(String(lastMessage.message))}</div>`);
        
        // If there are candidates, render them as read-only
        if (lastMessage.dimension_candidates && Object.keys(lastMessage.dimension_candidates).length > 0) {
          const candidatesTimestamp = Date.now();
          const candidatesPanel = renderDimensionCandidates(lastMessage.dimension_candidates, candidatesTimestamp, true);
          if (candidatesPanel) {
            parts.push(candidatesPanel);
          }
        }
        
        contentDiv.innerHTML = parts.join("\n");
      }
      
      // Add click handler to show full history in toast
      const bubble = window.pipelineMessageBox.querySelector(".pipeline-progress-bubble");
      if (bubble) {
        bubble.style.cursor = "pointer";
        bubble.addEventListener("click", () => {
          showPipelineProgressToast(window.pipelineMessageHistory);
        });
      }
      
      // Insert pipeline message box after the last user message (or at the end if no user messages)
      if (chatMessages) {
        if (lastUserMessageElement && lastUserMessageElement.nextSibling) {
          // Insert after the last user message
          chatMessages.insertBefore(window.pipelineMessageBox, lastUserMessageElement.nextSibling);
        } else if (lastUserMessageElement) {
          // Insert after the last user message (it's the last element)
          chatMessages.appendChild(window.pipelineMessageBox);
        } else {
          // No user messages, append at the end
          chatMessages.appendChild(window.pipelineMessageBox);
        }
      }
    }
    
    // Scroll to the last conversation turn after rendering
    // Use setTimeout to ensure DOM is fully rendered (especially for maps and other async content)
    // Use requestAnimationFrame and multiple setTimeout to ensure all content is rendered
    requestAnimationFrame(() => {
      setTimeout(() => {
        requestAnimationFrame(() => {
          if (chatMessages) {
            const messageRows = chatMessages.querySelectorAll('.message-row');
            if (messageRows.length > 0) {
              const lastMessageRow = messageRows[messageRows.length - 1];
              // Scroll to the top of the last message
              lastMessageRow.scrollIntoView({ behavior: 'smooth', block: 'start' });
            }
          }
          // Initialize text show more functionality for loaded conversation
          // Call multiple times to ensure all content is detected
          initializeTextShowMore();
          setTimeout(() => {
            initializeTextShowMore();
          }, 200);
        });
      }, 100);
    });
  } catch (error) {
    appendMessage(
      "assistant",
      `Hi there!
I'm your intelligent geospatial data discovery assistant.

What data are you looking for today?
You can describe the topic, where and when, or any requirements such as format, license, or data source.

For inspiration, feel free to explore the Quick Examples in the left panel.`
    );
  }
}

// Toggle conversation shareable status
async function toggleConversationShare(conversationId, currentShareable) {
  try {
    const apiKey = getApiKey();
    if (!apiKey) {
      alert('Please enter an API key to manage sharing.');
      return;
    }
    
    const newShareable = !currentShareable;
    const shareUrl = `${API_URL.replace('/query', '/conversations')}/${encodeURIComponent(conversationId)}/shareable?apiKey=${encodeURIComponent(apiKey)}`;
    const response = await fetch(shareUrl, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ shareable: newShareable })
    });
    
    if (response.ok) {
      const data = await response.json();
      // Refresh conversation history to update share icon
      await loadConversationHistory(true);
      
      if (newShareable) {
        // Show shareable URL
        const shareUrl = `${window.location.origin}${window.location.pathname}?conversation=${conversationId}`;
        showToast(`Conversation is now shareable. Link: ${shareUrl}`, 'success');
      } else {
        showToast('Conversation sharing disabled.', 'success');
      }
    } else {
      const errorData = await response.json();
      showToast(errorData.error || 'Failed to update sharing status', 'error');
    }
  } catch (error) {
    showToast('Failed to update sharing status', 'error');
  }
}

// Init
initApiKey();
initQuickExamples();
initPipelineGraph();
initHyperparametersDetails();
initSearchModeListener();
initSidebarToggles();
initSidebarResizers();
initPortalCheckboxes();
initUserSettingsPersistence();
setStatus("idle", 0);

// Print support: ensure collapsible panels (Top 20 & Knowledge Graph) are open when printing
function openAllDetailsForPrint() {
  try {
    const detailsList = document.querySelectorAll('details');
    detailsList.forEach(d => {
      // 记录原始 open 状态，方便恢复
      if (!d.hasAttribute('data-was-open')) {
        d.setAttribute('data-was-open', d.open ? '1' : '0');
      }
      d.open = true;
    });
  } catch (e) {
    console.warn('openAllDetailsForPrint error:', e);
  }
}

function restoreDetailsAfterPrint() {
  try {
    const detailsList = document.querySelectorAll('details[data-was-open]');
    detailsList.forEach(d => {
      const wasOpen = d.getAttribute('data-was-open') === '1';
      d.open = wasOpen;
      d.removeAttribute('data-was-open');
    });
  } catch (e) {
    console.warn('restoreDetailsAfterPrint error:', e);
  }
}

// 兼容不同浏览器的打印事件
if (typeof window !== 'undefined') {
  if ('matchMedia' in window) {
    const mediaQueryList = window.matchMedia('print');
    if (mediaQueryList && typeof mediaQueryList.addListener === 'function') {
      mediaQueryList.addListener(mql => {
        if (mql.matches) {
          openAllDetailsForPrint();
        } else {
          restoreDetailsAfterPrint();
        }
      });
    }
  }

  if (typeof window.addEventListener === 'function') {
    window.addEventListener('beforeprint', openAllDetailsForPrint);
    window.addEventListener('afterprint', restoreDetailsAfterPrint);
  }
}

// TEST FEATURE: Render Top 20 datasets before selection (for testing/debugging purposes only)
// This will be removed before production release
function renderTop20BeforeSelection(top20Datasets, userIntent) {
  if (!top20Datasets || !Array.isArray(top20Datasets) || top20Datasets.length === 0) {
    return '';
  }
  
  const timestamp = Date.now();
  const uniqueId = `top20-container-${timestamp}`;
  
  // Use renderDatasetBlocksUnlimited to display all 20 datasets (not limited to 10)
  const datasetsHtml = renderDatasetBlocksUnlimited(top20Datasets, userIntent);
  
  return `
    <div class="top20-before-selection-container" id="${uniqueId}">
      <details class="top20-before-selection-details">
        <summary class="top20-before-selection-summary">
          <span class="top20-icon">📋</span>
          <span>View Graph Retrieval Result (Top 20)</span>
        </summary>
        <div class="top20-before-selection-content">
          ${datasetsHtml || '<p>No datasets available.</p>'}
        </div>
      </details>
    </div>
  `;
}

// TEST FEATURE: Render dataset blocks without the 10-dataset limit (for Top 20 display)
// This will be removed before production release
// This is a copy of renderDatasetBlocks but without the slice(0, 10) limitation
function renderDatasetBlocksUnlimited(datasets, userIntent) {
  if (!datasets || !Array.isArray(datasets) || datasets.length === 0) return "";
  
  // Don't limit to 10 - show all datasets
  const allDatasets = datasets;
  const maxVisible = 2; // Show first 2, collapse the rest
  const hasMore = allDatasets.length > maxVisible;

  const blocks = allDatasets.map((bundle, idx) => {
    const isVisible = idx < maxVisible;
    const props = bundle.datasetProps || bundle.dataset_props || bundle.props || bundle || {};
    const linked = bundle.linkedEntities || bundle.linked_entities || {};
    // Try both camelCase and snake_case for score fields
    const matchScore = bundle.matchScore !== undefined ? bundle.matchScore : 
                      (bundle.match_score !== undefined ? bundle.match_score : null);
    
    const title = props.title || props.name || props.id || bundle.datasetId || `Dataset ${idx + 1}`;
    // Get description and notes separately - notes should be shown in full
    const desc = props.description || props.abstract || props.summary || "";
    const notes = props.notes || "";

    // Build entity information rows
    const rows = [];
    
    // Topic
    const topics = linked.Topic || [];
    if (topics.length > 0) {
      const topicNames = topics.map(t => t.props?.name || t.props?.title || t.name || "Topic").join(", ");
      rows.push(["Topic", topicNames]);
    }
    
    // License - show title with url link if available
    const licenses = linked.License || [];
    let licenseDisplay = null;
    if (licenses.length > 0) {
      const licenseItems = licenses.map(l => {
        const props = l.props || l || {};
        const title = props.title || props.license_title || props.name || l.name || "License";
        const url = props.url || props.license_url || props.link || props.landingPage || props.landing_page || null;
        return { title, url };
      });
      
      // Build license display: if any has URL, show as links
      if (licenseItems.some(item => item.url)) {
        licenseDisplay = {
          type: "links",
          items: licenseItems
        };
      } else {
        licenseDisplay = {
          type: "text",
          text: licenseItems.map(item => item.title).join(", ")
        };
      }
    }
    
    // Organization - show title only, with show more for long text
    const orgs = linked.Organization || [];
    if (orgs.length > 0) {
      const orgNames = orgs.map(o => {
        const props = o.props || o || {};
        return props.title || props.name || o.name || "Organization";
      }).join(", ");
      rows.push(["Organization", orgNames]);
    }
    
    // Source - show title with URL link if available
    const sources = linked.Source || [];
    let isDataGov = false;
    let sourceDisplay = null;
    if (sources.length > 0) {
      const sourceItems = sources.map(s => {
        const props = s.props || s || {};
        const title = props.title || props.name || s.name || "Source";
        const url = props.url || props.link || props.landingPage || props.landing_page || null;
        
        // Check if this is data.gov
        const nameLower = String(title).toLowerCase();
        if (nameLower.includes("data.gov") || nameLower === "data.gov") {
          isDataGov = true;
        }
        
        return { title, url };
      });
      
      // Build source display: if any has URL, show as links
      if (sourceItems.some(item => item.url)) {
        sourceDisplay = {
          type: "links",
          items: sourceItems
        };
      } else {
        sourceDisplay = {
          type: "text",
          text: sourceItems.map(item => item.title).join(", ")
        };
      }
    }
    
    // Space - show bbox if available, with map
    const spaces = linked.Space || [];
    let spaceMapHtml = "";
    if (spaces.length > 0) {
      const spaceBboxes = [];
      const spaceInfo = spaces.map((s, sIdx) => {
        const props = s.props || s || {};
        let bbox = null;
        
        // Try to get bbox first
        if (props.bbox && Array.isArray(props.bbox) && props.bbox.length === 4) {
          bbox = props.bbox;
        }
        // Try east, north, south, west
        else if (props.east != null && props.north != null && props.south != null && props.west != null) {
          bbox = [parseFloat(props.west), parseFloat(props.south), parseFloat(props.east), parseFloat(props.north)];
        }
        
        if (bbox) {
          const [minLon, minLat, maxLon, maxLat] = bbox;
          spaceBboxes.push({ bbox, idx: sIdx });
          return `[${minLon.toFixed(4)}, ${minLat.toFixed(4)}, ${maxLon.toFixed(4)}, ${maxLat.toFixed(4)}]`;
        }
        
        // Fallback to name
        return props.name || props.title || "Space";
      }).join(", ");
      
      rows.push(["Space", spaceInfo]);
      
      // Create map for this dataset if we have bboxes
      if (spaceBboxes.length > 0) {
        // Generate unique map ID
        const datasetId = bundle.datasetId || `dataset-${idx}`;
        const mapId = `space-map-${datasetId.replace(/[^a-zA-Z0-9-]/g, '-')}-${idx}`;
        const safeMapId = escapeHtml(mapId);
        spaceMapHtml = `
          <div class="space-map-container">
            <div class="space-map-toggle" onclick="toggleSpaceMap('${safeMapId}')">
              <span class="space-map-toggle-icon">🗺️</span>
              <span class="space-map-toggle-text">Show bounding box on map</span>
            </div>
            <div id="${safeMapId}" class="space-map" style="display: none;"></div>
          </div>
        `;
        
        // Store bbox data for map initialization
        if (!window.datasetSpaceMaps) window.datasetSpaceMaps = {};
        window.datasetSpaceMaps[safeMapId] = spaceBboxes.map(sb => sb.bbox);
      }
    }
    
    // Time
    const times = linked.Time || [];
    if (times.length > 0) {
      const timeInfo = times.map(t => {
        const begin = t.props?.begin || "";
        const end = t.props?.end || "";
        if (begin && end) return `${begin} to ${end}`;
        return t.props?.name || t.props?.title || t.name || "Time";
      }).join(", ");
      rows.push(["Time", timeInfo]);
    }

    // Build link: if source is data.gov, use catalog.data.gov URL
    let linkUrl = null;
    if (isDataGov) {
      // Get dataset name for data.gov link
      const datasetName = props.name || props.title || props.id || bundle.datasetId || "";
      if (datasetName) {
        // Format dataset name for URL: lowercase, replace spaces/special chars with hyphens
        const cleanName = String(datasetName)
          .toLowerCase()
          .trim()
          .replace(/[^a-z0-9\s-]/g, "")  // Remove special chars except spaces and hyphens
          .replace(/\s+/g, "-")           // Replace spaces with hyphens
          .replace(/-+/g, "-")            // Replace multiple hyphens with single
          .replace(/^-|-$/g, "");         // Remove leading/trailing hyphens
        
        if (cleanName) {
          linkUrl = `https://catalog.data.gov/dataset/${cleanName}`;
        }
      }
    }
    
    // Fallback to existing URL if not data.gov or if data.gov link couldn't be built
    if (!linkUrl) {
      linkUrl = props.url || props.link || props.landingPage || props.landing_page || null;
    }
    
    // Don't add Link to rows - it will be merged into title

    // Resource - collapsible list
    const resources = linked.Resource || [];
    const resourceFormats = linked.Format || [];
    // Create a map of resource_id -> format names for quick lookup
    const resourceFormatMap = new Map();
    resourceFormats.forEach(f => {
      const formatProps = f.props || f || {};
      const resourceId = f.resource_id || f.resourceId || null;
      const formatName = formatProps.name || formatProps.title || f.name || "Format";
      if (resourceId) {
        if (!resourceFormatMap.has(resourceId)) {
          resourceFormatMap.set(resourceId, []);
        }
        resourceFormatMap.get(resourceId).push(formatName);
      }
    });
    
    let resourceDisplay = null;
    if (resources.length > 0) {
      resourceDisplay = {
        items: resources.map(r => {
          const props = r.props || r || {};
          const resourceId = r.id || r.nodeId || r.node_id || null;
          // Get format names from resourceFormatMap using resource_id
          let formatNames = resourceFormatMap.get(resourceId) || [];
          let formatName = formatNames.join(", ") || "";
          
          // Fallback: try to get format from props if still empty
          if (!formatName && props.format) {
            if (typeof props.format === 'string') {
              formatName = props.format;
            } else if (typeof props.format === 'object' && props.format.name) {
              formatName = props.format.name;
            }
          }
          if (!formatName) {
            formatName = props.type || "";
          }
          return {
            name: props.name || props.title || r.name || "Resource",
            url: props.url || props.link || props.landingPage || props.landing_page || null,
            description: props.description || props.notes || "",
            format: formatName,
            size: props.size || props.fileSize || ""
          };
        })
      };
    }
    
    // Add License row if we have license display
    if (licenseDisplay) {
      if (licenseDisplay.type === "links") {
        const licenseLinksHtml = licenseDisplay.items.map(item => {
          if (item.url) {
            return `<a href="${escapeHtml(item.url)}" target="_blank" rel="noopener noreferrer" class="dataset-link">${escapeHtml(item.title)}</a>`;
          }
          return escapeHtml(item.title);
        }).join(", ");
        rows.push(["License", licenseLinksHtml]);
      } else {
        rows.push(["License", licenseDisplay.text]);
      }
    }
    
    // Add Source row if we have source display
    if (sourceDisplay) {
      if (sourceDisplay.type === "links") {
        const sourceLinksHtml = sourceDisplay.items.map(item => {
          if (item.url) {
            return `<a href="${escapeHtml(item.url)}" target="_blank" rel="noopener noreferrer" class="dataset-link">${escapeHtml(item.title)}</a>`;
          }
          return escapeHtml(item.title);
        }).join(", ");
        rows.push(["Source", sourceLinksHtml]);
      } else {
        rows.push(["Source", sourceDisplay.text]);
      }
    }
    
    const listHtml = rows.length
      ? `<div class="dataset-kv">
           ${rows.map(([k, v]) => {
             // Special handling for Source - may contain HTML links (no show more, just display)
             if (k === "Source" && typeof v === "string" && v.includes("<a ")) {
               return `
                 <div class="dataset-kv-row">
                   <div class="dataset-k">${escapeHtml(k)}</div>
                   <div class="dataset-v">${v}</div>
                 </div>
               `;
             }
             // Special handling for License - may contain HTML links (no show more, just display)
             if (k === "License" && typeof v === "string" && v.includes("<a ")) {
               return `
                 <div class="dataset-kv-row">
                   <div class="dataset-k">${escapeHtml(k)}</div>
                   <div class="dataset-v">${v}</div>
                 </div>
               `;
             }
            // For Organization field, use show more; others just show plain text
            if (k === "Organization") {
              const valueId = `dataset-v-${bundle.datasetId || idx}-${k}-${Date.now()}-${Math.floor(Math.random() * 100000)}`;
              const valueHtml = renderTextWithShowMore(String(v), 'dataset-v', valueId);
              return `
                <div class="dataset-kv-row">
                  <div class="dataset-k">${escapeHtml(k)}</div>
                  <div class="dataset-v">${valueHtml}</div>
                </div>
              `;
            } else {
              // Plain text for other fields
              return `
                <div class="dataset-kv-row">
                  <div class="dataset-k">${escapeHtml(k)}</div>
                  <div class="dataset-v">${escapeHtml(String(v))}</div>
                </div>
              `;
            }
           }).join("")}
         </div>`
      : "";
    
    // Add Resource section (collapsible)
    let resourceHtml = "";
    if (resourceDisplay && resourceDisplay.items.length > 0) {
      const resourceId = `resource-${bundle.datasetId || idx}`;
      const resourceCount = resourceDisplay.items.length;
      resourceHtml = `
        <div class="dataset-resource-section">
          <div class="dataset-resource-header" onclick="toggleResource('${resourceId}')">
            <span class="dataset-resource-title">
              <span class="dataset-resource-icon">📥</span>
              <span>Access Resource/Data (${resourceCount})</span>
            </span>
            <span class="dataset-resource-toggle" id="${resourceId}-icon">▲</span>
          </div>
          <div id="${resourceId}" class="dataset-resource-content">
            ${resourceDisplay.items.map((res, resIdx) => {
              const resTitle = res.url 
                ? `<a href="${escapeHtml(res.url)}" target="_blank" rel="noopener noreferrer" class="dataset-link">${escapeHtml(res.name)}</a>`
                : escapeHtml(res.name);
              const resDetails = [];
              if (res.size) resDetails.push(`Size: ${escapeHtml(res.size)}`);
              
              return `
                <div class="dataset-resource-item">
                  <div class="dataset-resource-name">${resTitle}</div>
                  ${res.format ? `<div class="dataset-resource-format">Format: ${escapeHtml(res.format)}</div>` : ""}
                  ${res.description ? `<div class="dataset-resource-description">Description: ${escapeHtml(res.description)}</div>` : ""}
                  ${resDetails.length > 0 ? `<div class="dataset-resource-details">${resDetails.join(" • ")}</div>` : ""}
                </div>
              `;
            }).join("")}
          </div>
        </div>
      `;
    }

    // Build match reasons - only show LLM selection reasons
    const matchReasons = [];
    
    // Add LLM selection reasons if available
    const llmReasons = bundle.llmSelectionReasons || bundle.llm_selection_reasons;
    if (llmReasons && Array.isArray(llmReasons) && llmReasons.length > 0) {
      // Add all LLM selection reasons
      matchReasons.push(...llmReasons);
    }
    
    
    const finalReasons = matchReasons;
    
    const matchReasonHtml = finalReasons.length > 0
      ? `<div class="dataset-match-reason">
           <div class="dataset-match-reason-title">Why it matches:</div>
           ${finalReasons.map((reason, reasonIdx) => {
             const reasonId = `match-reason-${bundle.datasetId || idx}-${reasonIdx}`;
             return `<div class="dataset-match-reason-item">• ${escapeHtml(reason)}</div>`;
           }).join("")}
         </div>`
      : "";

    // Render title with link if available
    const titleHtml = linkUrl
      ? `<a href="${escapeHtml(linkUrl)}" target="_blank" rel="noopener noreferrer" class="dataset-title-link">${escapeHtml(title)}</a>`
      : `<div class="dataset-title">${escapeHtml(title)}</div>`;
    
    const displayStyle = isVisible ? '' : 'style="display: none;"';
    return `
      <div class="dataset-card" ${displayStyle}>
        <div class="dataset-header">
          <span class="dataset-number">${idx + 1}</span>
          ${titleHtml}
        </div>
        ${desc ? `<div class="dataset-desc">${escapeHtml(desc)}</div>` : ""}
        ${notes ? renderTextWithShowMore(notes, 'dataset-notes', `dataset-notes-${bundle.datasetId || idx}-${Date.now()}-${Math.floor(Math.random() * 100000)}`) : ""}
        ${matchReasonHtml}
        ${listHtml}
        ${resourceHtml}
        ${spaceMapHtml}
      </div>
    `;
  }).join("");

  // Add toggle button if there are more than 2 datasets
  let toggleButton = "";
  if (hasMore) {
    const hiddenCount = allDatasets.length - maxVisible;
    const toggleId = `dataset-toggle-${Date.now()}-${Math.floor(Math.random() * 100000)}`;
    toggleButton = `
      <div class="dataset-list-toggle-container">
        <div class="dataset-list-toggle-header" onclick="toggleDatasetList(event, '${toggleId}')">
          <span class="dataset-list-toggle-title">Show ${hiddenCount} more dataset${hiddenCount > 1 ? 's' : ''}</span>
          <span class="dataset-list-toggle-icon" id="${toggleId}-icon">▼</span>
        </div>
      </div>
    `;
  }

  return `<div class="dataset-list">${blocks}</div>${toggleButton}`;
}

// Graph visualization functions
function renderGraphVisualization(subgraph, timestamp, datasets) {
  if (!subgraph || !subgraph.nodes || !subgraph.edges) {
    return '';
  }
  
  const uniqueId = `graph-${timestamp}-${Math.floor(Math.random() * 100000)}`;
  
  // Generate dataset filter checkboxes (use datasetId for filtering, not title/name)
  // This ensures that datasets with duplicate names are correctly distinguished
  // Default: only first dataset is checked
  const datasetCheckboxes = (datasets || []).map((dataset, idx) => {
    const datasetId = dataset.datasetId || dataset.id || `dataset-${idx}`;
    // Get title from different possible locations in dataset object (for display only)
    const props = dataset.datasetProps || dataset.dataset_props || dataset.props || dataset || {};
    let title = props.title || props.name || props.id || dataset.title || dataset.name || datasetId;
    const checkboxId = `graph-filter-${timestamp}-${idx}`;
    const displayTitle = String(title).substring(0, 60) + (String(title).length > 60 ? '...' : '');
    // Default: only first dataset is checked
    const isChecked = idx < 1;
    return `
      <label class="graph-filter-item">
        <input type="checkbox" class="graph-filter-checkbox" 
               data-dataset-title="${escapeHtml(String(title))}" 
               data-dataset-id="${escapeHtml(datasetId)}"
               id="${checkboxId}" 
               ${isChecked ? 'checked' : ''}>
        <span>${escapeHtml(displayTitle)}</span>
      </label>
    `;
  }).join('');
  
  return `
    <div class="graph-visualization-container" data-graph-timestamp="${timestamp}">
      <details class="graph-visualization-details">
        <summary class="graph-visualization-summary">
          <span class="graph-icon">📊</span>
          <span>View Knowledge Graph</span>
        </summary>
        <div class="graph-visualization-content">
          <div class="graph-header-actions">
            <button class="graph-fullscreen-btn" data-timestamp="${timestamp}" title="Fullscreen">
              <span class="graph-fullscreen-icon">⛶</span>
            </button>
          </div>
          ${datasets && datasets.length > 1 ? `
            <div class="graph-filter-panel">
              <div class="graph-filter-header">
                <strong>Filter Datasets:</strong>
                <button class="graph-filter-btn-select-all" data-timestamp="${timestamp}">Select All</button>
                <button class="graph-filter-btn-deselect-all" data-timestamp="${timestamp}">Deselect All</button>
              </div>
              <div class="graph-filter-list">
                ${datasetCheckboxes}
              </div>
            </div>
          ` : ''}
          <div class="graph-legend-container" data-timestamp="${timestamp}">
            <div class="graph-legend-title">Entity Types</div>
            <div class="graph-legend-list" id="graph-legend-${timestamp}"></div>
          </div>
          <div id="${uniqueId}" class="cytoscape-graph"></div>
        </div>
      </details>
    </div>
  `;
}

function initializeGraphVisualization(containerId, subgraph, datasets, timestamp) {
  if (typeof cytoscape === 'undefined') {
    console.error('Cytoscape.js is not loaded');
    return;
  }
  
  const container = document.getElementById(containerId);
  if (!container) return;
  
  // Store original subgraph data in container for filtering
  if (container.dataset) {
    container.dataset.originalNodes = JSON.stringify(subgraph.nodes || []);
    container.dataset.originalEdges = JSON.stringify(subgraph.edges || []);
  }
  
  // Get selected dataset IDs (default: all selected if checkboxes exist)
  // If checkboxes don't exist yet (initial load), use all datasets
  const selectedDatasetIds = getSelectedDatasetIds(timestamp);
  
  // Check if checkboxes exist (they might not be rendered yet during initial load)
  const hasCheckboxes = document.querySelectorAll(`[data-graph-timestamp="${timestamp}"] .graph-filter-checkbox`).length > 0;
  
  let filteredSubgraph;
  if (!hasCheckboxes || selectedDatasetIds.length === 0) {
    // Initial load or no selections - default to first dataset only
    // If checkboxes not rendered yet, use first dataset from datasets array
    if (datasets && datasets.length > 0) {
      const firstDataset = datasets[0];
      const firstDatasetId = firstDataset.datasetId || firstDataset.id || '';
      if (firstDatasetId) {
        filteredSubgraph = filterSubgraphByDatasets(subgraph, [firstDatasetId]);
      } else {
        filteredSubgraph = subgraph;
      }
    } else {
      filteredSubgraph = subgraph;
    }
  } else {
    // Filter based on selected dataset IDs
    filteredSubgraph = filterSubgraphByDatasets(subgraph, selectedDatasetIds);
  }
  
  const nodes = Array.isArray(filteredSubgraph.nodes) ? filteredSubgraph.nodes : [];
  const edges = Array.isArray(filteredSubgraph.edges) ? filteredSubgraph.edges : [];
  
  if (nodes.length === 0) {
    // If no nodes, show message or empty graph
    return;
  }
  
  // Convert nodes to Cytoscape format with proper labels based on type
  const cyNodes = nodes.map(node => {
    const nodeType = node.type || 'Node';
    const props = node.props || {};
    
    // IMPORTANT: Use node.id (which contains the actual node_id from backend) as the unique identifier
    // Do NOT use name/title as id, as they may be duplicated across different entities
    // Backend returns GraphNode with id field set to "type:node_id" format (e.g., "dataset:123", "topic:456")
    const nodeId = node.id || node.nodeId || String(Math.random());
    
    let fullLabel = '';
    
    // Generate full label based on entity type (for display only, not for identification)
    switch (nodeType) {
      case 'Dataset':
        fullLabel = props.title || props.name || node.label || '';
        break;
      case 'Resource':
        fullLabel = props.name || props.title || node.label || '';
        break;
      case 'Time':
        const begin = props.begin || props.until || '';
        const end = props.end || props.until || '';
        // Always show both begin and end if available
        if (begin && end) {
          fullLabel = `${begin} - ${end}`;
        } else if (begin) {
          fullLabel = `From ${begin}`;
        } else if (end) {
          fullLabel = `Until ${end}`;
        } else {
          fullLabel = props.name || props.title || node.label || '';
        }
        break;
      case 'Space':
        const west = props.west || props.minx || '';
        const east = props.east || props.maxx || '';
        const south = props.south || props.miny || '';
        const north = props.north || props.maxy || '';
        const parts = [];
        if (west && east && south && north) {
          parts.push(`W:${west}`, `E:${east}`, `S:${south}`, `N:${north}`);
          fullLabel = parts.join(', ');
        } else if (west || east || south || north) {
          if (west) parts.push(`W:${west}`);
          if (east) parts.push(`E:${east}`);
          if (south) parts.push(`S:${south}`);
          if (north) parts.push(`N:${north}`);
          fullLabel = parts.join(', ');
        } else {
          fullLabel = props.name || props.title || node.label || '';
        }
        break;
      case 'Organization':
        fullLabel = props.title || props.name || node.label || '';
        break;
      case 'Keyword':
        fullLabel = props.name || props.title || node.label || '';
        break;
      case 'Topic':
        fullLabel = props.name || props.title || node.label || '';
        break;
      case 'Format':
        fullLabel = props.name || props.title || node.label || '';
        break;
      case 'License':
        fullLabel = props.title || props.name || node.label || '';
        break;
      case 'Source':
        fullLabel = props.title || props.name || node.label || '';
        break;
      default:
        fullLabel = props.name || props.title || props.value || node.label || '';
    }
    
    // Truncate label for display
    const shortLabel = truncateLabel(fullLabel, 50);
      
      return {
        data: {
          id: nodeId, // Use node.id (contains actual node_id from backend), not name/title
          label: fullLabel, // Full label stored for tooltip (display only)
          shortLabel: shortLabel, // Short label for display (display only)
          type: nodeType,
          props: props
        }
      };
  });
  
  // Backend now creates Resource -> Format edges correctly
  // No need to manually match resource_id and node_id
  const allEdges = edges;
  
  // First, deduplicate edges by source-target pair to avoid multiple edges between same nodes
  const edgeMap = new Map();
  allEdges.forEach(edge => {
    const sourceId = edge.source || edge.from || '';
    const targetId = edge.target || edge.to || '';
    const edgeKey = `${sourceId}->${targetId}`;
    
    // Only keep the first edge for each source-target pair (deduplicate)
    if (!edgeMap.has(edgeKey)) {
      edgeMap.set(edgeKey, edge);
    }
  });
  
  const uniqueEdges = Array.from(edgeMap.values());
  
  // Convert edges to Cytoscape format with corrected labels
  // Note: Backend creates edges from Dataset to linked entities (Resource, Source, etc.)
  // Backend creates Resource -> Format edges correctly (not Dataset -> Format)
  // Backend uses the relationship type from linkedEntities (relType) as the edge label
  // We need to correct relationship labels and directions for proper semantics
  const cyEdges = uniqueEdges.map((edge, index) => {
    const sourceId = edge.source || edge.from || '';
    const targetId = edge.target || edge.to || '';
    const originalLabel = edge.label || edge.type || edge.rel || 'RELATED';
    let edgeLabel = originalLabel;
    let finalSourceId = sourceId;
    let finalTargetId = targetId;
    
    // Find source and target nodes to determine correct relationship label
    const sourceNode = nodes.find(n => (n.id || n.nodeId) === sourceId);
    const targetNode = nodes.find(n => (n.id || n.nodeId) === targetId);
    const sourceType = sourceNode?.type || '';
    const targetType = targetNode?.type || '';
    
    // Correct relationship labels and directions based on semantic relationships
    // Priority: Resource -> Format should always be "HAS_FORMAT" (highest priority)
    // 1. Resource -> Format: Resource has Format (highest priority, always use "HAS_FORMAT")
    if (sourceType === 'Resource' && targetType === 'Format') {
      edgeLabel = 'HAS_FORMAT';
    }
    // 2. Source -> Dataset: Source provides Dataset (reverse if needed)
    else if (sourceType === 'Dataset' && targetType === 'Source') {
      // Wrong direction - reverse: Source provides Dataset
      finalSourceId = targetId;
      finalTargetId = sourceId;
      edgeLabel = 'provides';
    } else if (sourceType === 'Source' && targetType === 'Dataset') {
      // Correct direction: Source provides Dataset
      edgeLabel = 'provides';
    }
    // 3. Dataset -> Format: This should NOT exist directly, should go through Resource
    // Filter out these direct edges - Format should only connect to Resource (not Dataset)
    else if (sourceType === 'Dataset' && targetType === 'Format') {
      // Skip this edge - return null to filter it out
      return null;
    }
    // 4. For other relationships, keep the original label from backend (relType)
    // This preserves the semantic relationship type from the knowledge graph
    else if (originalLabel !== 'RELATED' && originalLabel) {
      edgeLabel = originalLabel;
    }
    // 5. If no original label or default RELATED, try to infer from node types
    else if (sourceType === 'Dataset' && targetType === 'Resource') {
      edgeLabel = 'has';
    } else if (sourceType === 'Dataset') {
      edgeLabel = 'has';
    }
    
    return {
      data: {
        id: edge.id || edge.edgeId || `edge-${index}`,
        source: finalSourceId,
        target: finalTargetId,
        label: edgeLabel,
        type: edge.type || edge.rel || 'RELATED'
      }
    };
  }).filter(edge => {
    // Filter out invalid edges and null edges (Dataset -> Format)
    return edge && edge.data && edge.data.source && edge.data.target;
  });
  
  // Create Cytoscape instance
  const cy = cytoscape({
    container: container,
    elements: [...cyNodes, ...cyEdges],
    style: [
      {
        selector: 'node',
        style: {
          'background-color': function(ele) {
            const type = ele.data('type');
            return getEntityColor(type);
          },
          'label': 'data(shortLabel)',
          'width': function(ele) {
            // Calculate width based on label length, minimum 60px, maximum 200px
            const label = ele.data('shortLabel') || '';
            const minWidth = 60;
            const maxWidth = 200;
            const baseWidth = label.length * 8; // Approximate character width
            return Math.max(minWidth, Math.min(maxWidth, baseWidth));
          },
          'height': function(ele) {
            // Calculate height based on label length
            const label = ele.data('shortLabel') || '';
            const lines = Math.ceil(label.length / 20); // Approximate 20 chars per line
            return Math.max(40, lines * 20);
          },
          'shape': 'ellipse',
          'text-valign': 'center',
          'text-halign': 'center',
          'color': '#fff',
          'font-size': '11px',
          'font-weight': 'bold',
          'text-outline-width': 2,
          'text-outline-color': '#000',
          'text-wrap': 'wrap',
          'text-max-width': function(ele) {
            const width = ele.width();
            return Math.max(80, width - 20); // Leave padding
          }
        }
      },
      {
        selector: 'edge',
        style: {
          'width': 2,
          'line-color': '#94a3b8',
          'target-arrow-color': '#94a3b8',
          'target-arrow-shape': 'triangle',
          'curve-style': 'unbundled-bezier',
          'control-point-distances': [20, -20],
          'control-point-weights': [0.25, 0.75],
          'edge-distances': 'node-position',
          'label': 'data(label)',
          'font-size': '10px',
          'text-rotation': 'autorotate',
          'text-margin-y': -10
        }
      }
    ],
    layout: {
      name: 'cose',
      idealEdgeLength: 180,
      nodeOverlap: 35,
      refresh: 20,
      fit: true,
      padding: 50,
      randomize: false,
      componentSpacing: 200,
      nodeRepulsion: 7000000,
      edgeElasticity: 180,
      nestingFactor: 10,
      gravity: 250,
      numIter: 1600,
      initialTemp: 300,
      coolingFactor: 0.91,
      minTemp: 1.0,
      animate: false,
      quality: 'default'
    }
  });
  
  // Create tooltip element
  const tooltip = document.createElement('div');
  tooltip.className = 'graph-node-tooltip';
  tooltip.style.display = 'none';
  tooltip.style.position = 'absolute';
  tooltip.style.zIndex = '10000';
  tooltip.style.backgroundColor = 'rgba(0, 0, 0, 0.9)';
  tooltip.style.color = '#fff';
  tooltip.style.padding = '8px 12px';
  tooltip.style.borderRadius = '4px';
  tooltip.style.fontSize = '12px';
  tooltip.style.pointerEvents = 'none';
  tooltip.style.maxWidth = '300px';
  tooltip.style.wordWrap = 'break-word';
  tooltip.style.boxShadow = '0 2px 8px rgba(0,0,0,0.3)';
  document.body.appendChild(tooltip);
  
  // Show tooltip on hover (only if label is truncated)
  cy.on('mouseover', 'node', function(evt) {
    const node = evt.target;
    const fullLabel = node.data('label') || '';
    const shortLabel = node.data('shortLabel') || '';
    
    // Only show tooltip if label is truncated
    if (fullLabel !== shortLabel && fullLabel) {
      tooltip.textContent = fullLabel;
      tooltip.style.display = 'block';
      
      // Get node position on screen
      const nodePos = node.renderedPosition();
      const containerRect = container.getBoundingClientRect();
      tooltip.style.left = (containerRect.left + nodePos.x + 20) + 'px';
      tooltip.style.top = (containerRect.top + nodePos.y - 40) + 'px';
    }
  });
  
  // Hide tooltip on mouseout
  cy.on('mouseout', 'node', function(evt) {
    tooltip.style.display = 'none';
  });
  
  // Add click handlers for nodes (show full info)
  cy.on('tap', 'node', function(evt) {
    const node = evt.target;
    const fullLabel = node.data('label');
    const type = node.data('type');
    const props = node.data('props') || {};
    
    // Show full label in alert or better UI
    if (fullLabel && fullLabel.length > 50) {
      // Create a modal or better popup
      showNodeDetailsModal({
        label: fullLabel,
        type: type,
        props: props
      });
    }
  });
  
  // Store cytoscape instance in container for updates
  if (container.dataset) {
    container.dataset.cytoscapeInstance = JSON.stringify({ containerId, timestamp });
  }
  container._cyInstance = cy; // Store reference for filtering updates
  
  // Wire up filter checkboxes event listeners
  if (timestamp && datasets && datasets.length > 1) {
    wireGraphFilterCheckboxes(timestamp, containerId, subgraph);
  }
  
  // Update legend with entity counts
  updateGraphLegend(timestamp, nodes);
  
  // Fit the graph to container
  cy.fit(undefined, 30);
  
  // Wire up fullscreen button
  wireGraphFullscreenButton(timestamp, containerId, subgraph, datasets, cy);
}

/**
 * Get color for entity type.
 */
function getEntityColor(type) {
  const colorMap = {
    'Dataset': '#3b82f6',        // Blue
    'Topic': '#10b981',          // Green
    'Format': '#f59e0b',         // Orange
    'License': '#ef4444',        // Red
    'Organization': '#8b5cf6',   // Purple
    'Source': '#ec4899',         // Pink
    'Resource': '#06b6d4',       // Cyan
    'Time': '#84cc16',           // Lime
    'Space': '#f97316',          // Orange-red
    'Keyword': '#6366f1'         // Indigo
  };
  return colorMap[type] || '#6b7280'; // Gray for unknown types
}

/**
 * Get display name for entity type.
 */
function getEntityDisplayName(type) {
  return type || 'Unknown';
}

/**
 * Update graph legend with entity counts.
 * @param {string} timestamp - Graph timestamp identifier
 * @param {Array} nodes - Array of graph nodes
 * @param {HTMLElement} [legendContainer] - Optional legend container element. If not provided, will look up by ID.
 */
function updateGraphLegend(timestamp, nodes, legendContainer) {
  if (!legendContainer) {
    legendContainer = document.getElementById(`graph-legend-${timestamp}`);
  }
  if (!legendContainer) return;
  
  // Count entities by type
  const typeCounts = {};
  nodes.forEach(node => {
    const type = node.type || 'Unknown';
    typeCounts[type] = (typeCounts[type] || 0) + 1;
  });
  
  // Define entity types in order
  const entityTypes = [
    'Dataset', 'Topic', 'Format', 'License', 'Organization', 
    'Source', 'Resource', 'Time', 'Space', 'Keyword'
  ];
  
  // Generate legend HTML
  let legendItems = entityTypes
    .filter(type => typeCounts[type] > 0)
    .map(type => {
      const color = getEntityColor(type);
      const count = typeCounts[type];
      const displayName = getEntityDisplayName(type);
      return `
        <div class="graph-legend-item">
          <span class="graph-legend-color" style="background-color: ${color}"></span>
          <span class="graph-legend-label">${escapeHtml(displayName)}<span class="graph-legend-count">(${count})</span></span>
        </div>
      `;
    })
    .join('');
  
  // Add any other types not in the predefined list
  Object.keys(typeCounts)
    .filter(type => !entityTypes.includes(type))
    .forEach(type => {
      const color = getEntityColor(type);
      const count = typeCounts[type];
      const displayName = getEntityDisplayName(type);
      legendItems += `
        <div class="graph-legend-item">
          <span class="graph-legend-color" style="background-color: ${color}"></span>
          <span class="graph-legend-label">${escapeHtml(displayName)}<span class="graph-legend-count">(${count})</span></span>
        </div>
      `;
    });
  
  legendContainer.innerHTML = legendItems || '<div class="graph-legend-empty">No entities</div>';
}

/**
 * Truncate label for display in graph nodes.
 */
function truncateLabel(label, maxLength) {
  if (!label) return '';
  const str = String(label);
  if (str.length <= maxLength) return str;
  return str.substring(0, maxLength - 3) + '...';
}

/**
 * Show node details modal/popup.
 */
function showNodeDetailsModal(nodeInfo) {
  // Create modal overlay
  const modal = document.createElement('div');
  modal.className = 'graph-node-modal-overlay';
  modal.style.cssText = `
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    background: rgba(0, 0, 0, 0.5);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 10000;
  `;
  
  // Create modal content
  const content = document.createElement('div');
  content.className = 'graph-node-modal-content';
  content.style.cssText = `
    background: #fff;
    border-radius: 8px;
    padding: 1.5rem;
    max-width: 500px;
    max-height: 80vh;
    overflow-y: auto;
    box-shadow: 0 4px 16px rgba(0,0,0,0.3);
  `;
  
  content.innerHTML = `
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 1rem;">
      <h3 style="margin: 0; color: #020617;">${escapeHtml(nodeInfo.type || 'Entity')}</h3>
      <button class="graph-node-modal-close" style="
        background: none;
        border: none;
        font-size: 1.5rem;
        cursor: pointer;
        color: #6b7280;
        padding: 0;
        width: 32px;
        height: 32px;
        display: flex;
        align-items: center;
        justify-content: center;
      ">×</button>
    </div>
    <div style="margin-bottom: 0.5rem;">
      <strong style="color: #6b7280; font-size: 0.875rem;">Label:</strong>
      <p style="margin: 0.25rem 0 0 0; color: #020617; word-wrap: break-word;">${escapeHtml(nodeInfo.label || 'N/A')}</p>
    </div>
    ${nodeInfo.props && Object.keys(nodeInfo.props).length > 0 ? `
      <div>
        <strong style="color: #6b7280; font-size: 0.875rem;">Properties:</strong>
        <dl style="margin: 0.25rem 0 0 0; padding: 0;">
          ${Object.entries(nodeInfo.props).map(([key, value]) => `
            <div style="margin-bottom: 0.5rem;">
              <dt style="font-weight: 600; color: #020617; margin-bottom: 0.25rem;">${escapeHtml(String(key))}:</dt>
              <dd style="margin: 0; color: #6b7280; word-wrap: break-word;">${escapeHtml(String(value || 'N/A'))}</dd>
            </div>
          `).join('')}
        </dl>
      </div>
    ` : ''}
  `;
  
  modal.appendChild(content);
  document.body.appendChild(modal);
  
  // Close button handler
  const closeBtn = content.querySelector('.graph-node-modal-close');
  const closeModal = () => {
    document.body.removeChild(modal);
  };
  closeBtn.addEventListener('click', closeModal);
  modal.addEventListener('click', (e) => {
    if (e.target === modal) closeModal();
  });
  
  // ESC key handler
  const escHandler = (e) => {
    if (e.key === 'Escape') {
      closeModal();
      document.removeEventListener('keydown', escHandler);
    }
  };
  document.addEventListener('keydown', escHandler);
}

// Feedback functionality
function initFeedback() {
  const feedbackBtn = document.getElementById('feedback-btn');
  const feedbackToggleBtn = document.getElementById('feedback-toggle-btn');
  const feedbackModal = document.getElementById('feedback-modal');
  const feedbackModalClose = document.getElementById('feedback-modal-close');
  const feedbackCancel = document.getElementById('feedback-cancel');
  const feedbackSubmit = document.getElementById('feedback-submit');
  const feedbackContent = document.getElementById('feedback-content');
  const feedbackRating = document.getElementById('feedback-rating');

  if (!feedbackBtn || !feedbackModal) return;

  // Load hidden state from localStorage, default to hidden (true)
  const isHidden = localStorage.getItem('feedback-btn-hidden') !== 'false'; // Default to true (hidden)
  if (isHidden) {
    feedbackBtn.classList.add('hidden');
    if (feedbackToggleBtn) feedbackToggleBtn.classList.add('show');
  }

  // Toggle hide/show feedback button
  function toggleFeedbackButton() {
    const isCurrentlyHidden = feedbackBtn.classList.contains('hidden');
    if (isCurrentlyHidden) {
      // Show button
      feedbackBtn.classList.remove('hidden');
      if (feedbackToggleBtn) feedbackToggleBtn.classList.remove('show');
      localStorage.setItem('feedback-btn-hidden', 'false');
    } else {
      // Hide button
      feedbackBtn.classList.add('hidden');
      if (feedbackToggleBtn) feedbackToggleBtn.classList.add('show');
      localStorage.setItem('feedback-btn-hidden', 'true');
    }
  }

  // Show feedback button when toggle button is clicked
  if (feedbackToggleBtn) {
    feedbackToggleBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      toggleFeedbackButton();
    });
  }

  // Hide feedback button on right-click (context menu)
  feedbackBtn.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    e.stopPropagation();
    toggleFeedbackButton();
  });

  // Open modal on single click
  feedbackBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    feedbackModal.style.display = 'flex';
    feedbackContent.value = '';
    feedbackRating.value = '';
  });

  // Close modal
  function closeModal() {
    feedbackModal.style.display = 'none';
  }

  feedbackModalClose.addEventListener('click', closeModal);
  feedbackCancel.addEventListener('click', closeModal);

  // Close modal when clicking outside
  feedbackModal.addEventListener('click', (e) => {
    if (e.target === feedbackModal) {
      closeModal();
    }
  });

  // Close modal on Escape key
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && feedbackModal.style.display === 'flex') {
      closeModal();
    }
  });

  // Submit feedback
  feedbackSubmit.addEventListener('click', async () => {
    const content = feedbackContent.value.trim();
    const rating = feedbackRating.value ? parseInt(feedbackRating.value) : null;

    if (!content && !rating) {
      showToast('Please provide feedback content or rating', 'info');
      return;
    }

    // Check if API key and conversation ID are available
    const apiKey = getApiKey();
    const conversationId = currentConversationId;

    if (!apiKey || apiKey.trim() === '') {
      showToast('Please enter an API key to submit feedback', 'error');
      return;
    }

    if (!conversationId) {
      showToast('Please start a conversation to submit feedback', 'error');
      return;
    }

    feedbackSubmit.disabled = true;
    feedbackSubmit.textContent = 'Submitting...';

    try {
      const payload = {
        apiKey: apiKey,
        conversationId: conversationId,
        content: content,
        rating: rating,
        metadata: {
          userAgent: navigator.userAgent,
          timestamp: new Date().toISOString()
        }
      };

      const response = await fetch(`${API_URL.replace('/query', '/feedback')}`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(payload)
      });

      const data = await response.json();

      if (data.success) {
        showToast('Thank you for your feedback!', 'success');
        closeModal();
      } else {
        showToast('Failed to submit feedback. Please try again.', 'error');
      }
    } catch (error) {
      console.error('Error submitting feedback:', error);
      showToast('Failed to submit feedback. Please try again.', 'error');
    } finally {
      feedbackSubmit.disabled = false;
      feedbackSubmit.textContent = 'Submit';
    }
  });
}

// Initialize feedback on page load
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', initFeedback);
} else {
  initFeedback();
}

/**
 * Get selected dataset IDs from checkboxes (for filtering by datasetId).
 */
function getSelectedDatasetIds(timestamp) {
  const checkboxes = document.querySelectorAll(`[data-graph-timestamp="${timestamp}"] .graph-filter-checkbox:checked`);
  return Array.from(checkboxes).map(cb => cb.dataset.datasetId).filter(id => id); // Filter out empty/null values
}

/**
 * Get selected dataset titles from checkboxes (for display purposes).
 */
function getSelectedDatasetTitles(timestamp) {
  const checkboxes = document.querySelectorAll(`[data-graph-timestamp="${timestamp}"] .graph-filter-checkbox:checked`);
  return Array.from(checkboxes).map(cb => cb.dataset.datasetTitle || '').filter(title => title); // Filter out empty/null values
}

/**
 * Filter subgraph to only include nodes and edges connected to selected datasets (by datasetId).
 * Uses datasetId from node.id (format: "dataset:datasetId") to match selected datasetIds.
 */
function filterSubgraphByDatasets(subgraph, selectedDatasetIds) {
  if (!selectedDatasetIds || selectedDatasetIds.length === 0) {
    return { nodes: [], edges: [] };
  }
  
  const nodes = Array.isArray(subgraph.nodes) ? subgraph.nodes : [];
  const edges = Array.isArray(subgraph.edges) ? subgraph.edges : [];
  
  // Create set of selected dataset IDs (for comparison)
  // Normalize datasetIds (remove any "dataset:" prefix if present, as node.id has format "dataset:datasetId")
  const selectedIds = new Set(selectedDatasetIds.map(id => {
    const normalized = String(id).trim();
    // Remove "dataset:" prefix if present
    return normalized.startsWith('dataset:') ? normalized.substring(8) : normalized;
  }));
  
  // Find dataset nodes that match selected datasetIds
  // node.id format is "dataset:datasetId" (from EvidencePackBuilder)
  const selectedDatasetNodeIds = new Set();
  nodes.forEach(node => {
    if (node.type === 'Dataset') {
      const nodeId = node.id || node.nodeId || '';
      // Extract datasetId from node.id (format: "dataset:datasetId")
      let datasetId = '';
      if (nodeId.startsWith('dataset:')) {
        datasetId = nodeId.substring(8); // Remove "dataset:" prefix
      } else {
        // Fallback: if node.id doesn't have prefix, use it as-is
        datasetId = nodeId;
      }
      
      // Match by datasetId (not by title/name)
      if (selectedIds.has(datasetId)) {
        selectedDatasetNodeIds.add(nodeId);
      }
    }
  });
  
  // Find all nodes that should be included:
  // 1. Selected dataset nodes
  // 2. Nodes directly connected to selected dataset nodes (Resource, Topic, etc.)
  // 3. Nodes connected to those nodes (Format via Resource, etc.)
  // We only need 2 passes maximum: Dataset -> Resource -> Format
  const includedNodeIds = new Set(selectedDatasetNodeIds);
  
  // First pass: add all nodes directly connected to selected datasets
  for (const edge of edges) {
    const source = edge.source || edge.from || '';
    const target = edge.target || edge.to || '';
    
    if (selectedDatasetNodeIds.has(source)) {
      includedNodeIds.add(target);
    }
    if (selectedDatasetNodeIds.has(target)) {
      includedNodeIds.add(source);
    }
  }
  
  // Second pass: only add Format nodes connected to Resource nodes found in first pass
  // This avoids adding nodes that are already connected to datasets (which would be duplicates)
  for (const edge of edges) {
    const source = edge.source || edge.from || '';
    const target = edge.target || edge.to || '';
    
    // Find node types to determine if this is a Resource -> Format connection
    const sourceNode = nodes.find(n => (n.id || n.nodeId) === source);
    const targetNode = nodes.find(n => (n.id || n.nodeId) === target);
    
    // Only process Resource -> Format edges where Resource was added in first pass
    // and Format is not already included (to avoid duplicates)
    if (sourceNode && sourceNode.type === 'Resource' && 
        targetNode && targetNode.type === 'Format' && 
        includedNodeIds.has(source) && 
        !selectedDatasetNodeIds.has(source) && 
        !includedNodeIds.has(target)) {
      includedNodeIds.add(target);
    }
    // Handle reverse direction (Format -> Resource, though this shouldn't happen)
    if (targetNode && targetNode.type === 'Resource' && 
        sourceNode && sourceNode.type === 'Format' && 
        includedNodeIds.has(target) && 
        !selectedDatasetNodeIds.has(target) && 
        !includedNodeIds.has(source)) {
      includedNodeIds.add(source);
    }
  }
  
  // Filter nodes to only included ones
  const filteredNodes = nodes.filter(node => {
    const nodeId = node.id || node.nodeId || '';
    return includedNodeIds.has(nodeId);
  });
  
  // Filter edges to only those connecting included nodes
  const filteredEdges = edges.filter(edge => {
    const source = edge.source || edge.from || '';
    const target = edge.target || edge.to || '';
    return includedNodeIds.has(source) && includedNodeIds.has(target);
  });
  
  return { nodes: filteredNodes, edges: filteredEdges };
}

/**
 * Wire up filter checkbox event listeners.
 */
function wireGraphFilterCheckboxes(timestamp, containerId, originalSubgraph) {
  const container = document.getElementById(containerId);
  if (!container) return;
  
  // Get all checkboxes for this graph
  const checkboxes = document.querySelectorAll(
    `[data-graph-timestamp="${timestamp}"] .graph-filter-checkbox`
  );
  
  // Get select all/deselect all buttons
  const selectAllBtn = document.querySelector(
    `[data-graph-timestamp="${timestamp}"] .graph-filter-btn-select-all`
  );
  const deselectAllBtn = document.querySelector(
    `[data-graph-timestamp="${timestamp}"] .graph-filter-btn-deselect-all`
  );
  
  // Function to update graph based on selections
  const updateGraph = () => {
    const selectedDatasetTitles = getSelectedDatasetTitles(timestamp);
    
    // If no selections, show empty graph
    let filteredSubgraph;
    const selectedDatasetIds = getSelectedDatasetIds(timestamp);
    if (selectedDatasetIds.length === 0) {
      filteredSubgraph = { nodes: [], edges: [] };
    } else {
      filteredSubgraph = filterSubgraphByDatasets(originalSubgraph, selectedDatasetIds);
    }
    
    // Get cytoscape instance from container
    const cy = container._cyInstance;
    if (!cy) return;
    
    // Remove all existing elements
    cy.elements().remove();
    
    // If no nodes, just update legend and return
    if (!filteredSubgraph.nodes || filteredSubgraph.nodes.length === 0) {
      updateGraphLegend(timestamp, []);
      return;
    }
    
    // Add filtered nodes with proper labels (same logic as initializeGraphVisualization)
    const cyNodes = filteredSubgraph.nodes.map(node => {
      const nodeType = node.type || 'Node';
      const props = node.props || {};
      
      // IMPORTANT: Use node.id (which contains the actual node_id from backend) as the unique identifier
      // Do NOT use name/title as id, as they may be duplicated across different entities
      const nodeId = node.id || node.nodeId || String(Math.random());
      
      let fullLabel = '';
      
      // Generate full label based on entity type (for display only, not for identification)
      switch (nodeType) {
        case 'Dataset':
          fullLabel = props.title || props.name || node.label || '';
          break;
        case 'Resource':
          fullLabel = props.name || props.title || node.label || '';
          break;
        case 'Time':
          const begin = props.begin || props.until || '';
          const end = props.end || props.until || '';
          // Always show both begin and end if available
          if (begin && end) {
            fullLabel = `${begin} - ${end}`;
          } else if (begin) {
            fullLabel = `From ${begin}`;
          } else if (end) {
            fullLabel = `Until ${end}`;
          } else {
            fullLabel = props.name || props.title || node.label || '';
          }
          break;
        case 'Space':
          const west = props.west || props.minx || '';
          const east = props.east || props.maxx || '';
          const south = props.south || props.miny || '';
          const north = props.north || props.maxy || '';
          const parts = [];
          if (west && east && south && north) {
            parts.push(`W:${west}`, `E:${east}`, `S:${south}`, `N:${north}`);
            fullLabel = parts.join(', ');
          } else if (west || east || south || north) {
            if (west) parts.push(`W:${west}`);
            if (east) parts.push(`E:${east}`);
            if (south) parts.push(`S:${south}`);
            if (north) parts.push(`N:${north}`);
            fullLabel = parts.join(', ');
          } else {
            fullLabel = props.name || props.title || node.label || '';
          }
          break;
        case 'Organization':
          fullLabel = props.title || props.name || node.label || '';
          break;
        case 'Keyword':
          fullLabel = props.name || props.title || node.label || '';
          break;
        case 'Topic':
          fullLabel = props.name || props.title || node.label || '';
          break;
        case 'Format':
          fullLabel = props.name || props.title || node.label || '';
          break;
        case 'License':
          fullLabel = props.title || props.name || node.label || '';
          break;
        case 'Source':
          fullLabel = props.title || props.name || node.label || '';
          break;
        default:
          fullLabel = props.name || props.title || props.value || node.label || '';
      }
      
      // Truncate label for display
      const shortLabel = truncateLabel(fullLabel, 50);
      
      return {
        data: {
          id: nodeId, // Use node.id (contains actual node_id from backend), not name/title
          label: fullLabel, // Full label stored for tooltip (display only)
          shortLabel: shortLabel, // Short label for display (display only)
          type: nodeType,
          props: props
        }
      };
    });
    
    // Backend now creates Resource -> Format edges correctly
    // No need to manually match resource_id and node_id
    const allEdges = filteredSubgraph.edges;
    
    // Add filtered edges with corrected labels (same logic as initializeGraphVisualization)
    // First, deduplicate edges by source-target pair to avoid multiple edges between same nodes
    const edgeMap = new Map();
    allEdges.forEach(edge => {
      const sourceId = edge.source || edge.from || '';
      const targetId = edge.target || edge.to || '';
      const edgeKey = `${sourceId}->${targetId}`;
      
      // Only keep the first edge for each source-target pair (deduplicate)
      if (!edgeMap.has(edgeKey)) {
        edgeMap.set(edgeKey, edge);
      }
    });
    
    const uniqueEdges = Array.from(edgeMap.values());
    
    const cyEdges = uniqueEdges.map((edge, index) => {
      const sourceId = edge.source || edge.from || '';
      const targetId = edge.target || edge.to || '';
      const originalLabel = edge.label || edge.type || edge.rel || 'RELATED';
      let edgeLabel = originalLabel;
      let finalSourceId = sourceId;
      let finalTargetId = targetId;
      
      // Find source and target nodes to determine correct relationship label
      const sourceNode = filteredSubgraph.nodes.find(n => (n.id || n.nodeId) === sourceId);
      const targetNode = filteredSubgraph.nodes.find(n => (n.id || n.nodeId) === targetId);
      const sourceType = sourceNode?.type || '';
      const targetType = targetNode?.type || '';
      
      // Correct relationship labels and directions (same logic as initializeGraphVisualization)
      // Priority: Resource -> Format should always be "HAS_FORMAT"
      // 1. Resource -> Format: Resource has Format (highest priority, always use "HAS_FORMAT")
      if (sourceType === 'Resource' && targetType === 'Format') {
        edgeLabel = 'HAS_FORMAT';
      }
      // 2. Source -> Dataset: Source provides Dataset (reverse if needed)
      else if (sourceType === 'Dataset' && targetType === 'Source') {
        finalSourceId = targetId;
        finalTargetId = sourceId;
        edgeLabel = 'provides';
      } else if (sourceType === 'Source' && targetType === 'Dataset') {
        edgeLabel = 'provides';
      }
      // 3. Dataset -> Format: This should NOT exist directly, should go through Resource
      // Filter out these direct edges - Format should only connect to Resource (not Dataset)
      else if (sourceType === 'Dataset' && targetType === 'Format') {
        // Skip this edge - return null to filter it out
        // Format should connect to Resource via resource_id matching
        return null;
      }
      // 4. For other relationships, keep the original label from backend (relType)
      // This preserves the semantic relationship type from the knowledge graph
      else if (originalLabel !== 'RELATED' && originalLabel) {
        edgeLabel = originalLabel;
      }
      // 5. If no original label or default RELATED, try to infer from node types
      else if (sourceType === 'Dataset' && targetType === 'Resource') {
        edgeLabel = 'has';
      } else if (sourceType === 'Dataset') {
        edgeLabel = 'has';
      }
      
      return {
        data: {
          id: edge.id || edge.edgeId || `edge-${index}`,
          source: finalSourceId,
          target: finalTargetId,
          label: edgeLabel,
          type: edge.type || edge.rel || 'RELATED'
        }
      };
    }).filter(edge => {
      // Filter out invalid edges and null edges (Dataset -> Format)
      return edge && edge.data && edge.data.source && edge.data.target;
    });
    
    // Add new elements
    cy.add([...cyNodes, ...cyEdges]);
    
    // Re-apply layout and fit
    cy.layout({
      name: 'cose',
      idealEdgeLength: function(edge) {
        // Increase edge length to reduce crossings
        return 220;
      },
      nodeOverlap: 45,
      refresh: 20,
      fit: true,
      padding: 50,
      randomize: false,
      componentSpacing: 90,
      nodeRepulsion: 6500000,
      edgeElasticity: 220,
      nestingFactor: 5,
      gravity: 150,
      numIter: 1600,
      initialTemp: 300,
      coolingFactor: 0.91,
      minTemp: 1.0,
      animate: false,
      quality: 'default'
    }).run();
    
    cy.fit(undefined, 30);
    
    // Update legend with new entity counts
    updateGraphLegend(timestamp, filteredSubgraph.nodes);
  };
  
  // Wire checkbox change events
  checkboxes.forEach(checkbox => {
    checkbox.addEventListener('change', updateGraph);
  });
  
  // Wire select all button
  if (selectAllBtn) {
    selectAllBtn.addEventListener('click', () => {
      checkboxes.forEach(cb => cb.checked = true);
      updateGraph();
    });
  }
  
  // Wire deselect all button
  if (deselectAllBtn) {
    deselectAllBtn.addEventListener('click', () => {
      checkboxes.forEach(cb => cb.checked = false);
      updateGraph();
    });
  }
}

/**
 * Wire up fullscreen button for graph visualization.
 */
function wireGraphFullscreenButton(timestamp, containerId, subgraph, datasets, cyInstance) {
  const fullscreenBtn = document.querySelector(`[data-timestamp="${timestamp}"].graph-fullscreen-btn`);
  if (!fullscreenBtn) return;
  
  fullscreenBtn.addEventListener('click', () => {
    showGraphFullscreen(timestamp, containerId, subgraph, datasets, cyInstance);
  });
}

/**
 * Show graph in fullscreen modal.
 */
function showGraphFullscreen(timestamp, containerId, subgraph, datasets, cyInstance) {
  // Create fullscreen modal overlay
  const overlay = document.createElement('div');
  overlay.className = 'graph-fullscreen-modal-overlay';
  overlay.id = `graph-fullscreen-${timestamp}`;
  
  // Create fullscreen modal content
  const content = document.createElement('div');
  content.className = 'graph-fullscreen-modal-content';
  
  // Create header with title and close button
  const header = document.createElement('div');
  header.className = 'graph-fullscreen-header';
  header.innerHTML = `
    <div class="graph-fullscreen-title">
      <span class="graph-icon">📊</span>
      <span>Knowledge Graph (Fullscreen)</span>
    </div>
    <button class="graph-fullscreen-close" title="Close fullscreen">✕</button>
  `;
  
  // Create body with filter panel, legend, and graph
  const body = document.createElement('div');
  body.className = 'graph-fullscreen-body';
  
  // Get original filter panel HTML if exists
  const originalFilterPanel = document.querySelector(`[data-graph-timestamp="${timestamp}"] .graph-filter-panel`);
  const originalLegend = document.querySelector(`[data-graph-timestamp="${timestamp}"] .graph-legend-container`);
  
  // Create left sidebar for filter and legend
  const leftSidebar = document.createElement('div');
  leftSidebar.className = 'graph-fullscreen-sidebar';
  
  // Clone filter panel if exists
  if (originalFilterPanel && datasets && datasets.length > 1) {
    const filterClone = originalFilterPanel.cloneNode(true);
    filterClone.classList.add('graph-fullscreen-filter');
    leftSidebar.appendChild(filterClone);
  }
  
  // Clone legend if exists
  if (originalLegend) {
    const legendClone = originalLegend.cloneNode(true);
    legendClone.classList.add('graph-fullscreen-legend');
    leftSidebar.appendChild(legendClone);
  }
  
  // Create graph container
  const graphContainer = document.createElement('div');
  graphContainer.className = 'graph-fullscreen-graph-container';
  const graphDiv = document.createElement('div');
  const fullscreenGraphId = `graph-fullscreen-${timestamp}-${Math.floor(Math.random() * 100000)}`;
  graphDiv.id = fullscreenGraphId;
  graphDiv.className = 'cytoscape-graph-fullscreen';
  graphContainer.appendChild(graphDiv);
  
  // Assemble body
  body.appendChild(leftSidebar);
  body.appendChild(graphContainer);
  
  // Assemble content
  content.appendChild(header);
  content.appendChild(body);
  
  // Assemble overlay
  overlay.appendChild(content);
  
  // Add to document
  document.body.appendChild(overlay);
  
  // Initialize graph in fullscreen - use requestAnimationFrame to ensure DOM is ready
  requestAnimationFrame(() => {
    setTimeout(() => {
      // Get filtered subgraph based on current selections
      const selectedDatasetIds = getSelectedDatasetIds(timestamp);
      const filteredSubgraph = selectedDatasetIds.length > 0 
        ? filterSubgraphByDatasets(subgraph, selectedDatasetIds)
        : subgraph;
      
      // Initialize new cytoscape instance for fullscreen
      initializeGraphVisualizationForFullscreen(fullscreenGraphId, filteredSubgraph, datasets, timestamp, originalFilterPanel, originalLegend);
    }, 200);
  });
  
  // Wire up filter checkboxes in fullscreen modal (after initialization)
  if (datasets && datasets.length > 1 && originalFilterPanel) {
    setTimeout(() => {
      const fullscreenFilterPanel = content.querySelector('.graph-fullscreen-filter');
      if (fullscreenFilterPanel) {
        // Update timestamp attributes to match fullscreen
        const filterCheckboxes = fullscreenFilterPanel.querySelectorAll('.graph-filter-checkbox');
        const filterSelectAll = fullscreenFilterPanel.querySelector('.graph-filter-btn-select-all');
        const filterDeselectAll = fullscreenFilterPanel.querySelector('.graph-filter-btn-deselect-all');
        
        // Wire up fullscreen filter checkboxes
        const updateFullscreenGraph = () => {
          const selectedIds = Array.from(fullscreenFilterPanel.querySelectorAll('.graph-filter-checkbox:checked'))
            .map(cb => cb.getAttribute('data-dataset-id'))
            .filter(id => id); // Filter out null/empty values
          
          const fsFilteredSubgraph = selectedIds.length > 0
            ? filterSubgraphByDatasets(subgraph, selectedIds)
            : { nodes: [], edges: [] };
          
          // Update fullscreen graph
          const fsContainer = document.getElementById(fullscreenGraphId);
          if (fsContainer && fsContainer._cyInstance) {
            const fsCy = fsContainer._cyInstance;
            fsCy.elements().remove();
            
            if (fsFilteredSubgraph.nodes && fsFilteredSubgraph.nodes.length > 0) {
              // Add nodes and edges (same logic as initializeGraphVisualization)
              const cyNodes = fsFilteredSubgraph.nodes.map(node => {
                // IMPORTANT: Use node.id (which contains the actual node_id from backend) as the unique identifier
                // Do NOT use name/title as id, as they may be duplicated across different entities
                const nodeId = node.id || node.nodeId || String(Math.random());
                const props = node.props || node.properties || {};
                const nodeType = node.type || node.label || 'Unknown';
                
                // Generate full label based on node type (for display only, not for identification)
                let fullLabel = '';
                switch (nodeType) {
                  case 'Dataset':
                    fullLabel = props.title || props.name || node.label || '';
                    break;
                  case 'Resource':
                    fullLabel = props.name || props.title || node.label || '';
                    break;
                  case 'Time':
                    const begin = props.begin || props.until || '';
                    const end = props.end || props.until || '';
                    if (begin && end) {
                      fullLabel = `${begin} - ${end}`;
                    } else if (begin) {
                      fullLabel = `From ${begin}`;
                    } else if (end) {
                      fullLabel = `Until ${end}`;
                    } else {
                      fullLabel = props.name || props.title || node.label || '';
                    }
                    break;
                  case 'Space':
                    const west = props.west || props.minx || '';
                    const east = props.east || props.maxx || '';
                    const south = props.south || props.miny || '';
                    const north = props.north || props.maxy || '';
                    const parts = [];
                    if (west && east && south && north) {
                      parts.push(`W:${west}`, `E:${east}`, `S:${south}`, `N:${north}`);
                      fullLabel = parts.join(', ');
                    } else if (west || east || south || north) {
                      if (west) parts.push(`W:${west}`);
                      if (east) parts.push(`E:${east}`);
                      if (south) parts.push(`S:${south}`);
                      if (north) parts.push(`N:${north}`);
                      fullLabel = parts.join(', ');
                    } else {
                      fullLabel = props.name || props.title || node.label || '';
                    }
                    break;
                  case 'Organization':
                    fullLabel = props.title || props.name || node.label || '';
                    break;
                  case 'Keyword':
                    fullLabel = props.name || props.title || node.label || '';
                    break;
                  case 'Topic':
                    fullLabel = props.name || props.title || node.label || '';
                    break;
                  case 'Format':
                    fullLabel = props.name || props.title || node.label || '';
                    break;
                  case 'License':
                    fullLabel = props.title || props.name || node.label || '';
                    break;
                  case 'Source':
                    fullLabel = props.title || props.name || node.label || '';
                    break;
                  default:
                    fullLabel = props.name || props.title || props.value || node.label || '';
                }
                
                // Truncate label for display
                const shortLabel = truncateLabel(fullLabel, 50);
                
                return {
                  data: {
                    id: nodeId, // Use node.id (contains actual node_id from backend), not name/title
                    label: fullLabel, // Full label stored for tooltip (display only)
                    shortLabel: shortLabel, // Short label for display (display only)
                    type: nodeType,
                    props: props
                  }
                };
              });
              
              // Deduplicate edges and validate nodes exist
              const edgeMap = new Map();
              fsFilteredSubgraph.edges.forEach(edge => {
                const sourceId = edge.source || edge.from || edge.sourceId || '';
                const targetId = edge.target || edge.to || edge.targetId || '';
                const edgeKey = `${sourceId}->${targetId}`;
                
                if (!edgeMap.has(edgeKey)) {
                  edgeMap.set(edgeKey, edge);
                }
              });
              
              const uniqueEdges = Array.from(edgeMap.values());
              
              const cyEdges = uniqueEdges.map((edge, index) => {
                const sourceId = edge.source || edge.from || edge.sourceId || '';
                const targetId = edge.target || edge.to || edge.targetId || '';
                const originalLabel = edge.label || edge.type || edge.rel || edge.relationship || 'RELATED';
                let edgeLabel = originalLabel;
                let finalSourceId = sourceId;
                let finalTargetId = targetId;
                
                // Find source and target nodes to verify they exist
                const sourceNode = fsFilteredSubgraph.nodes.find(n => (n.id || n.nodeId) === sourceId);
                const targetNode = fsFilteredSubgraph.nodes.find(n => (n.id || n.nodeId) === targetId);
                
                // Skip edge if nodes don't exist
                if (!sourceNode || !targetNode) {
                  return null;
                }
                
                const sourceType = sourceNode.type || '';
                const targetType = targetNode.type || '';
                
                // Correct relationship labels and directions (same logic as initializeGraphVisualization)
                if (sourceType === 'Resource' && targetType === 'Format') {
                  edgeLabel = 'HAS_FORMAT';
                } else if (sourceType === 'Dataset' && targetType === 'Source') {
                  finalSourceId = targetId;
                  finalTargetId = sourceId;
                  edgeLabel = 'provides';
                } else if (sourceType === 'Source' && targetType === 'Dataset') {
                  edgeLabel = 'provides';
                } else if (sourceType === 'Dataset' && targetType === 'Format') {
                  return null; // Skip Dataset -> Format edges
                } else if (originalLabel !== 'RELATED' && originalLabel) {
                  edgeLabel = originalLabel;
                } else if (sourceType === 'Dataset' && targetType === 'Resource') {
                  edgeLabel = 'has';
                } else if (sourceType === 'Dataset') {
                  edgeLabel = 'has';
                }
                
                return {
                  data: {
                    id: edge.id || edge.edgeId || `edge-${index}`,
                    source: finalSourceId,
                    target: finalTargetId,
                    label: edgeLabel,
                    type: edge.type || edge.rel || 'RELATED'
                  }
                };
              }).filter(edge => {
                // Filter out invalid edges
                return edge && edge.data && edge.data.source && edge.data.target;
              });
              
              fsCy.add([...cyNodes, ...cyEdges]);
              
              // Run layout
              fsCy.layout({
                name: 'cose',
                idealEdgeLength: 220,
                nodeOverlap: 45,
                refresh: 20,
                fit: true,
                padding: 50,
                randomize: false,
                componentSpacing: 90,
                nodeRepulsion: 6500000,
                edgeElasticity: 220,
                nestingFactor: 5,
                gravity: 150,
                numIter: 1600,
                initialTemp: 300,
                coolingFactor: 0.91,
                minTemp: 1.0,
                animate: false,
                quality: 'default'
              }).run();
              
              fsCy.fit(undefined, 30);
              
              // Update legend
              const fsLegendContainer = content.querySelector(`#graph-legend-${timestamp}`);
              if (fsLegendContainer) {
                updateGraphLegend(timestamp, fsFilteredSubgraph.nodes, fsLegendContainer);
              }
            } else {
              // Clear legend
              const fsLegendContainer = content.querySelector(`#graph-legend-${timestamp}`);
              if (fsLegendContainer) {
                updateGraphLegend(timestamp, [], fsLegendContainer);
              }
            }
          }
        };
        
        filterCheckboxes.forEach(checkbox => {
          checkbox.addEventListener('change', updateFullscreenGraph);
        });
        
        if (filterSelectAll) {
          filterSelectAll.addEventListener('click', () => {
            filterCheckboxes.forEach(cb => cb.checked = true);
            updateFullscreenGraph();
          });
        }
        
        if (filterDeselectAll) {
          filterDeselectAll.addEventListener('click', () => {
            filterCheckboxes.forEach(cb => cb.checked = false);
            updateFullscreenGraph();
          });
        }
      }
    }, 400);
  }
  
  // Close button handler
  const closeBtn = content.querySelector('.graph-fullscreen-close');
  const closeModal = () => {
    // Clean up cytoscape instance
    const fsContainer = document.getElementById(fullscreenGraphId);
    if (fsContainer && fsContainer._cyInstance) {
      fsContainer._cyInstance.destroy();
      fsContainer._cyInstance = null;
    }
    // Remove resize handler (cleanup)
    overlay.remove();
  };
  
  closeBtn.addEventListener('click', closeModal);
  overlay.addEventListener('click', (e) => {
    if (e.target === overlay) {
      closeModal();
    }
  });
  
  // ESC key handler
  const escHandler = (e) => {
    if (e.key === 'Escape') {
      closeModal();
      document.removeEventListener('keydown', escHandler);
    }
  };
  document.addEventListener('keydown', escHandler);
}

/**
 * Initialize graph visualization for fullscreen modal.
 */
function initializeGraphVisualizationForFullscreen(containerId, subgraph, datasets, timestamp, originalFilterPanel, originalLegend) {
  if (typeof cytoscape === 'undefined') {
    console.error('Cytoscape.js is not loaded');
    return;
  }
  
  const container = document.getElementById(containerId);
  if (!container) {
    console.error('Container not found:', containerId);
    return;
  }
  
  // Ensure container has dimensions
  const parentContainer = container.parentElement;
  if (parentContainer) {
    const rect = parentContainer.getBoundingClientRect();
    if (rect.height === 0 || rect.width === 0) {
      console.warn('Parent container has no dimensions, waiting...');
      setTimeout(() => initializeGraphVisualizationForFullscreen(containerId, subgraph, datasets, timestamp, originalFilterPanel, originalLegend), 100);
      return;
    }
  }
  
  const nodes = Array.isArray(subgraph.nodes) ? subgraph.nodes : [];
  const edges = Array.isArray(subgraph.edges) ? subgraph.edges : [];
  
  if (nodes.length === 0) {
    console.warn('No nodes to display');
    return;
  }
  
  console.log('Initializing fullscreen graph with', nodes.length, 'nodes and', edges.length, 'edges');
  
  // Convert nodes to Cytoscape format (same as initializeGraphVisualization)
  const cyNodes = nodes.map(node => {
    // IMPORTANT: Use node.id (which contains the actual node_id from backend) as the unique identifier
    // Do NOT use name/title as id, as they may be duplicated across different entities
    const nodeId = node.id || node.nodeId || String(Math.random());
    const props = node.props || node.properties || {};
    const nodeType = node.type || node.label || 'Unknown';
    
    // Generate full label based on node type (for display only, not for identification)
    let fullLabel = '';
    switch (nodeType) {
      case 'Dataset':
        fullLabel = props.title || props.name || node.label || '';
        break;
      case 'Resource':
        fullLabel = props.name || props.title || node.label || '';
        break;
      case 'Time':
        const start = props.start || props.startTime || '';
        const end = props.end || props.endTime || '';
        if (start && end) {
          fullLabel = `${start} to ${end}`;
        } else if (start) {
          fullLabel = `From ${start}`;
        } else if (end) {
          fullLabel = `Until ${end}`;
        } else {
          fullLabel = props.name || props.title || node.label || '';
        }
        break;
      case 'Space':
        const west = props.west || props.minx || '';
        const east = props.east || props.maxx || '';
        const south = props.south || props.miny || '';
        const north = props.north || props.maxy || '';
        const parts = [];
        if (west && east && south && north) {
          parts.push(`W:${west}`, `E:${east}`, `S:${south}`, `N:${north}`);
          fullLabel = parts.join(', ');
        } else if (west || east || south || north) {
          if (west) parts.push(`W:${west}`);
          if (east) parts.push(`E:${east}`);
          if (south) parts.push(`S:${south}`);
          if (north) parts.push(`N:${north}`);
          fullLabel = parts.join(', ');
        } else {
          fullLabel = props.name || props.title || node.label || '';
        }
        break;
      case 'Organization':
        fullLabel = props.title || props.name || node.label || '';
        break;
      case 'Keyword':
        fullLabel = props.name || props.title || node.label || '';
        break;
      case 'Topic':
        fullLabel = props.name || props.title || node.label || '';
        break;
      case 'Format':
        fullLabel = props.name || props.title || node.label || '';
        break;
      case 'License':
        fullLabel = props.title || props.name || node.label || '';
        break;
      case 'Source':
        fullLabel = props.title || props.name || node.label || '';
        break;
      default:
        fullLabel = props.name || props.title || props.value || node.label || '';
    }
    
    // Truncate label for display
    const shortLabel = truncateLabel(fullLabel, 50);
    
    return {
      data: {
        id: nodeId, // Use node.id (contains actual node_id from backend), not name/title
        label: fullLabel, // Full label stored for tooltip (display only)
        shortLabel: shortLabel, // Short label for display (display only)
        type: nodeType,
        props: props
      }
    };
  });
  
  // Convert edges to Cytoscape format (same as initializeGraphVisualization)
  // First, deduplicate edges by source-target pair to avoid multiple edges between same nodes
  const edgeMap = new Map();
  edges.forEach(edge => {
    const sourceId = edge.source || edge.from || edge.sourceId || '';
    const targetId = edge.target || edge.to || edge.targetId || '';
    const edgeKey = `${sourceId}->${targetId}`;
    
    // Only keep the first edge for each source-target pair (deduplicate)
    if (!edgeMap.has(edgeKey)) {
      edgeMap.set(edgeKey, edge);
    }
  });
  
  const uniqueEdges = Array.from(edgeMap.values());
  
  // Convert edges to Cytoscape format with corrected labels (same logic as initializeGraphVisualization)
  const cyEdges = uniqueEdges.map((edge, index) => {
    const sourceId = edge.source || edge.from || edge.sourceId || '';
    const targetId = edge.target || edge.to || edge.targetId || '';
    const originalLabel = edge.label || edge.type || edge.rel || edge.relationship || 'RELATED';
    let edgeLabel = originalLabel;
    let finalSourceId = sourceId;
    let finalTargetId = targetId;
    
    // Find source and target nodes to determine correct relationship label
    const sourceNode = nodes.find(n => (n.id || n.nodeId) === sourceId);
    const targetNode = nodes.find(n => (n.id || n.nodeId) === targetId);
    const sourceType = sourceNode?.type || '';
    const targetType = targetNode?.type || '';
    
    // Verify both nodes exist before creating edge
    if (!sourceNode || !targetNode) {
      // Skip edge if nodes don't exist
      return null;
    }
    
    // Correct relationship labels and directions based on semantic relationships
    // Priority: Resource -> Format should always be "HAS_FORMAT" (highest priority)
    // 1. Resource -> Format: Resource has Format (highest priority, always use "HAS_FORMAT")
    if (sourceType === 'Resource' && targetType === 'Format') {
      edgeLabel = 'HAS_FORMAT';
    }
    // 2. Source -> Dataset: Source provides Dataset (reverse if needed)
    else if (sourceType === 'Dataset' && targetType === 'Source') {
      // Wrong direction - reverse: Source provides Dataset
      finalSourceId = targetId;
      finalTargetId = sourceId;
      edgeLabel = 'provides';
    } else if (sourceType === 'Source' && targetType === 'Dataset') {
      // Correct direction: Source provides Dataset
      edgeLabel = 'provides';
    }
    // 3. Dataset -> Format: This should NOT exist directly, should go through Resource
    // Filter out these direct edges - Format should only connect to Resource (not Dataset)
    else if (sourceType === 'Dataset' && targetType === 'Format') {
      // Skip this edge - return null to filter it out
      return null;
    }
    // 4. For other relationships, keep the original label from backend (relType)
    // This preserves the semantic relationship type from the knowledge graph
    else if (originalLabel !== 'RELATED' && originalLabel) {
      edgeLabel = originalLabel;
    }
    // 5. If no original label or default RELATED, try to infer from node types
    else if (sourceType === 'Dataset' && targetType === 'Resource') {
      edgeLabel = 'has';
    } else if (sourceType === 'Dataset') {
      edgeLabel = 'has';
    }
    
    return {
      data: {
        id: edge.id || edge.edgeId || `edge-${index}`,
        source: finalSourceId,
        target: finalTargetId,
        label: edgeLabel,
        type: edge.type || edge.rel || 'RELATED'
      }
    };
  }).filter(edge => {
    // Filter out invalid edges and null edges (Dataset -> Format, missing nodes)
    return edge && edge.data && edge.data.source && edge.data.target;
  });
  
  // Create Cytoscape instance for fullscreen
  const cy = cytoscape({
    container: container,
    elements: [...cyNodes, ...cyEdges],
    style: [
      {
        selector: 'node',
        style: {
          'background-color': function(ele) {
            const type = ele.data('type');
            return getEntityColor(type);
          },
          'label': 'data(shortLabel)',
          'width': function(ele) {
            const label = ele.data('shortLabel') || '';
            const minWidth = 60;
            const maxWidth = 200;
            const baseWidth = label.length * 8;
            return Math.max(minWidth, Math.min(maxWidth, baseWidth));
          },
          'height': function(ele) {
            const label = ele.data('shortLabel') || '';
            const lines = Math.ceil(label.length / 20);
            return Math.max(40, lines * 20);
          },
          'shape': 'ellipse',
          'text-valign': 'center',
          'text-halign': 'center',
          'color': '#fff',
          'font-size': '11px',
          'font-weight': 'bold',
          'text-outline-width': 2,
          'text-outline-color': '#000',
          'text-wrap': 'wrap',
          'text-max-width': function(ele) {
            const width = ele.width();
            return Math.max(80, width - 20);
          }
        }
      },
      {
        selector: 'edge',
        style: {
          'width': 2,
          'line-color': '#94a3b8',
          'target-arrow-color': '#94a3b8',
          'target-arrow-shape': 'triangle',
          'curve-style': 'unbundled-bezier',
          'control-point-distances': [20, -20],
          'control-point-weights': [0.25, 0.75],
          'edge-distances': 'node-position',
          'label': 'data(label)',
          'font-size': '10px',
          'text-rotation': 'autorotate',
          'text-margin-y': -10
        }
      }
    ],
    layout: {
      name: 'cose',
      idealEdgeLength: 180,
      nodeOverlap: 35,
      refresh: 20,
      fit: true,
      padding: 50,
      randomize: false,
      componentSpacing: 200,
      nodeRepulsion: 7000000,
      edgeElasticity: 180,
      nestingFactor: 10,
      gravity: 250,
      numIter: 1600,
      initialTemp: 300,
      coolingFactor: 0.91,
      minTemp: 1.0,
      animate: false,
      quality: 'default'
    }
  });
  
  // Store instance
  container._cyInstance = cy;
  
  // Update legend in fullscreen modal
  const fsLegendContainer = document.querySelector(`#graph-fullscreen-${timestamp} #graph-legend-${timestamp}`);
  if (fsLegendContainer) {
    updateGraphLegend(timestamp, nodes, fsLegendContainer);
  }
  
  // Ensure graph is resized correctly
  setTimeout(() => {
    cy.resize();
    cy.fit(undefined, 30);
    console.log('Fullscreen graph initialized and fitted');
  }, 100);
  
  // Also resize on window resize
  const resizeHandler = () => {
    if (container._cyInstance) {
      container._cyInstance.resize();
      container._cyInstance.fit(undefined, 30);
    }
  };
  window.addEventListener('resize', resizeHandler);
  
  // Clean up resize handler when modal is closed (handled in closeModal function)
}

// Check URL for conversation ID on page load
const urlParams = new URLSearchParams(window.location.search);
const conversationIdFromUrl = urlParams.get('conversation');

if (conversationIdFromUrl) {
  // Set current conversation ID and load it (may be shareable, doesn't require API key)
  currentConversationId = conversationIdFromUrl;
  // Load conversation from URL first (may be shareable)
  // Pass fromUrl=true to indicate this is from URL, so 404 will redirect to 404 page
  loadConversationForSession(conversationIdFromUrl, true);
  // Also load conversation history if user has API key (won't fail if no API key)
  loadConversationHistory();
} else {
  // No conversation in URL - ensure clean state
  // Ensure no SSE connection exists when no conversation
  if (statusEventSource) {
    statusEventSource.close();
    statusEventSource = null;
  }
  // Ensure pipeline is in idle state
  if (pipelineGraph) {
    PIPELINE_STEPS.forEach(step => {
      setPipelineStepState(step.id, "idle");
    });
  }
  // No conversation in URL, load history and show welcome
  loadConversationHistory();
  // Don't create conversation on page load - user will create one when sending first message
  // or can click to load existing conversation
  appendMessage(
    "assistant",
    `Hi there!
I'm your intelligent geospatial data discovery assistant.

What data are you looking for today?
You can describe the topic, where and when, or any requirements such as format, license, or data source.

For inspiration, feel free to explore the Quick Examples in the left panel.`
  );
  // Initialize textarea to ensure no scrollbar by default
  if (messageInput) {
    autoResizeTextarea(messageInput);
  }
}

// Clean up SSE connection when page is closed or hidden
window.addEventListener('beforeunload', () => {
  if (statusEventSource) {
    // Clear health check interval if exists
    if (statusEventSource._healthCheckInterval) {
      clearInterval(statusEventSource._healthCheckInterval);
    }
    // Close SSE connection
    statusEventSource.close();
    statusEventSource = null;
  }
});

// Also handle page visibility change (tab switch, minimize, etc.)
document.addEventListener('visibilitychange', () => {
  if (document.hidden) {
    // Page is hidden - connection will remain but that's okay
    // Browser will handle connection cleanup if needed
  } else {
    // Page is visible again - ensure connection is still active
    if (currentConversationId) {
      if (!statusEventSource || statusEventSource.readyState === EventSource.CLOSED) {
        connectStatusStream();
      }
    }
  }
});

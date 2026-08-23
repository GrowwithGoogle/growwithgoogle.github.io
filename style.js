/**
 * PromptHub Core Logic & State Management
 * Handles pre-seeded prompts, community uploads, WFM voting, and Owner Verification.
 */

// Initial High-Quality Seed Prompts
const DEFAULT_PROMPTS = [
  {
    id: "p-01",
    title: "Gemini 2.0 Local Review SEO Responder",
    platform: "Gemini",
    category: "Local Business & SEO",
    promptText: `You are an expert customer relations manager for [Business Name] located in [City, State].
Analyze the following customer review:
\"[Paste Customer Review Here]\"

If 4-5 Stars: Write a warm, grateful reply naturally weaving in local SEO keywords: [List 2-3 services, e.g. brake repair, oil change].
If 1-3 Stars: Write a deeply empathetic, de-escalating response with zero defensive language, offering direct contact with the owner at [Owner Email/Phone].
Keep the response under 70 words.`,
    source: "Andrew Kieckhefer (@thepokacloud)",
    sourceUrl: "https://thepoka.cloud",
    instructions: "Replace bracketed fields. Best used with temperature 0.4 on Gemini 2.0 Flash for instant, high-ranking Google Maps review replies.",
    upvotes: 42,
    downvotes: 1,
    verified: true,
    createdAt: "2026-08-20"
  },
  {
    id: "p-02",
    title: "Zero-Shot System Architect & Tool Calling Schema",
    platform: "Claude",
    category: "Coding & Agents",
    promptText: `You are an elite principal software architect specializing in Model Context Protocol (MCP) and Python agent frameworks.
Given the following business objective: \"[Describe Goal]\",
1. Define the exact JSON tool schemas required for the agent.
2. Outline error-handling boundary states (rate limits, context window overflow).
3. Write clean, type-hinted Python functions with docstrings implementing the tool dispatch logic.`,
    source: "Anthropic Engineering Best Practices",
    sourceUrl: "https://docs.anthropic.com",
    instructions: "Feed into Claude 3.7 Sonnet (Thinking Mode enabled). Generates production-ready MCP tool declarations.",
    upvotes: 89,
    downvotes: 3,
    verified: true,
    createdAt: "2026-08-21"
  },
  {
    id: "p-03",
    title: "Google Workspace & DNS Security Auditor",
    platform: "GPT-4o",
    category: "System Prompts",
    promptText: `Act as a senior cybersecurity compliance auditor. 
Review the following DNS configuration:
SPF: \"[Paste SPF Record]\"
DKIM: \"[Paste DKIM Selector]\"
DMARC: \"[Paste DMARC Record]\"

Identify vulnerabilities to email spoofing/phishing and provide the exact corrected DNS records for 100% Google Workspace deliverability.`,
    source: "The Poka Cloud Security Stack",
    sourceUrl: "https://thepoka.cloud",
    instructions: "Paste raw DNS TXT records. Output provides copy-paste ready BIND and Cloudflare formatted records.",
    upvotes: 28,
    downvotes: 0,
    verified: true,
    createdAt: "2026-08-22"
  }
];

// App State
let prompts = [];
let isOwnerMode = false;
const OWNER_PIN = "1234"; // Default PIN for Owner verification toggle

// Initialize Application
document.addEventListener("DOMContentLoaded", () => {
  loadPrompts();
  renderPrompts();
  updateStats();
});

// Load Prompts from LocalStorage or Defaults
function loadPrompts() {
  const saved = localStorage.getItem("prompthub_data");
  if (saved) {
    try {
      prompts = JSON.parse(saved);
    } catch (e) {
      prompts = DEFAULT_PROMPTS;
    }
  } else {
    prompts = DEFAULT_PROMPTS;
    savePrompts();
  }
}

function savePrompts() {
  localStorage.setItem("prompthub_data", JSON.stringify(prompts));
}

// Render Prompts into the 4-Column Layout
function renderPrompts(filteredList = null) {
  const container = document.getElementById("promptsContainer");
  const list = filteredList || prompts;

  if (list.length === 0) {
    container.innerHTML = `
      <div style="text-align: center; padding: 60px 20px; color: var(--text-muted);">
        <i class="fa-solid fa-folder-open" style="font-size: 2.5rem; margin-bottom: 12px; display: block;"></i>
        No prompts found matching your criteria.
      </div>`;
    return;
  }

  container.innerHTML = list.map(p => {
    // Only show unverified prompts if Owner Mode is active
    if (!p.verified && !isOwnerMode) return "";

    const totalVotes = (p.upvotes || 0) + (p.downvotes || 0);
    const wfmRate = totalVotes > 0 ? Math.round(((p.upvotes || 0) / totalVotes) * 100) : 100;
    const badgeClass = getBadgeClass(p.platform);

    return `
      <article class="prompt-card ${!p.verified ? 'pending' : ''}" id="card-${p.id}">
        
        <!-- Column 1: Prompt Text -->
        <div class="col-prompt">
          <div class="col-header">
            <span><i class="fa-solid fa-code"></i> Column 1: Prompt Text</span>
            <button class="btn-copy" onclick="copyPrompt('${p.id}')">
              <i class="fa-regular fa-copy"></i> Copy
            </button>
          </div>
          <h4 style="font-size: 1.1rem; margin-bottom: 8px; color: #fff;">${escapeHtml(p.title)}</h4>
          <div class="prompt-box" id="text-${p.id}">${escapeHtml(p.promptText)}</div>
          <div class="card-tags">
            <span class="badge ${badgeClass}">${p.platform}</span>
            <span class="badge" style="background: rgba(255,255,255,0.08); color: #cbd5e1;">${p.category}</span>
            ${!p.verified ? '<span class="badge" style="background: #f59e0b; color: #000;">Pending Review</span>' : ''}
          </div>
        </div>

        <!-- Column 2: Source & Citation -->
        <div class="col-citation">
          <div class="col-header">
            <span><i class="fa-solid fa-quote-left"></i> Column 2: Citation</span>
          </div>
          <div class="citation-box">
            <p><strong>Origin / Author:</strong><br>${escapeHtml(p.source)}</p>
            ${p.sourceUrl ? `
              <a href="${escapeHtml(p.sourceUrl)}" target="_blank" class="citation-link">
                <i class="fa-solid fa-arrow-up-right-from-square"></i> Verified Source Link
              </a>` : '<span style="color: var(--text-muted); font-size: 0.85rem;">No external URL</span>'}
            <div style="font-size: 0.75rem; color: var(--text-muted); margin-top: 14px;">
              Uploaded: ${p.createdAt || 'Recent'}
            </div>
          </div>
        </div>

        <!-- Column 3: How to Use & Intended Results -->
        <div class="col-usage">
          <div class="col-header">
            <span><i class="fa-solid fa-bullseye"></i> Column 3: How to Use</span>
          </div>
          <div class="usage-box">
            <p>${escapeHtml(p.instructions)}</p>
          </div>
        </div>

        <!-- Column 4: WFM (Works For Me) Voting Widget -->
        <div class="col-wfm">
          <div class="wfm-box">
            <div class="wfm-title">WFM RATING</div>
            <div class="vote-buttons">
              <button class="btn-vote up" onclick="vote('${p.id}', 'up')" title="Works for me!">
                <i class="fa-solid fa-thumbs-up"></i>
                <span class="vote-count" id="up-${p.id}">${p.upvotes || 0}</span>
              </button>
              <button class="btn-vote down" onclick="vote('${p.id}', 'down')" title="Did not produce intended result">
                <i class="fa-solid fa-thumbs-down"></i>
                <span class="vote-count" id="down-${p.id}">${p.downvotes || 0}</span>
              </button>
            </div>
            <div class="wfm-percent" id="pct-${p.id}">
              <i class="fa-solid fa-circle-check"></i> ${wfmRate}% Success
            </div>
          </div>
        </div>

        <!-- Owner Verification Actions (Visible only in Owner Mode) -->
        ${isOwnerMode ? `
          <div class="admin-card-actions">
            ${!p.verified ? `
              <button class="btn btn-sm btn-primary" onclick="verifyPrompt('${p.id}')">
                <i class="fa-solid fa-check"></i> Approve & Verify
              </button>` : ''}
            <button class="btn btn-sm btn-danger" onclick="deletePrompt('${p.id}')">
              <i class="fa-solid fa-trash"></i> Delete
            </button>
          </div>
        ` : ''}

      </article>
    `;
  }).join("");
}

// 1-Click Copy Functionality
function copyPrompt(id) {
  const target = prompts.find(p => p.id === id);
  if (!target) return;

  navigator.clipboard.writeText(target.promptText).then(() => {
    showToast("Prompt copied to clipboard!");
  }).catch(() => {
    showToast("Failed to copy", true);
  });
}

// WFM Voting Logic
function vote(id, type) {
  const prompt = prompts.find(p => p.id === id);
  if (!prompt) return;

  const voteKey = `voted_${id}`;
  if (localStorage.getItem(voteKey)) {
    showToast("You already voted on this prompt!");
    return;
  }

  if (type === "up") prompt.upvotes = (prompt.upvotes || 0) + 1;
  if (type === "down") prompt.downvotes = (prompt.downvotes || 0) + 1;

  localStorage.setItem(voteKey, type);
  savePrompts();
  
  // Live UI Update
  document.getElementById(`up-${id}`).innerText = prompt.upvotes;
  document.getElementById(`down-${id}`).innerText = prompt.downvotes;
  const total = prompt.upvotes + prompt.downvotes;
  const rate = Math.round((prompt.upvotes / total) * 100);
  document.getElementById(`pct-${id}`).innerHTML = `<i class="fa-solid fa-circle-check"></i> ${rate}% Success`;
  
  updateStats();
  showToast(type === "up" ? "Voted: Works For Me! 👍" : "Voted: Needs tuning 👎");
}

// Upload Modal Functions
function openUploadModal() {
  document.getElementById("uploadModal").classList.add("active");
}
function closeUploadModal() {
  document.getElementById("uploadModal").classList.remove("active");
  document.getElementById("promptUploadForm").reset();
}

// Handle Form Submission
function handleFormSubmit(e) {
  e.preventDefault();

  const newPrompt = {
    id: "p-" + Date.now(),
    title: document.getElementById("formTitle").value.trim(),
    platform: document.getElementById("formPlatform").value,
    category: document.getElementById("formCategory").value,
    promptText: document.getElementById("formPromptText").value.trim(),
    source: document.getElementById("formSource").value.trim(),
    sourceUrl: document.getElementById("formSourceUrl").value.trim(),
    instructions: document.getElementById("formInstructions").value.trim(),
    upvotes: 1,
    downvotes: 0,
    verified: isOwnerMode, // Auto-verified if owner submits
    createdAt: new Date().toISOString().split("T")[0]
  };

  prompts.unshift(newPrompt);
  savePrompts();
  closeUploadModal();
  renderPrompts();
  updateStats();

  if (isOwnerMode) {
    showToast("Prompt published & verified live!");
  } else {
    showToast("Prompt submitted! Pending owner review.");
  }
}

// Search & Filter Logic
function handleSearch() {
  applyFilters();
}
function handleFilter() {
  applyFilters();
}

function applyFilters() {
  const query = document.getElementById("searchInput").value.toLowerCase();
  const platform = document.getElementById("platformFilter").value;
  const category = document.getElementById("categoryFilter").value;

  const filtered = prompts.filter(p => {
    const matchesSearch = p.title.toLowerCase().includes(query) ||
                          p.promptText.toLowerCase().includes(query) ||
                          p.source.toLowerCase().includes(query);
    const matchesPlatform = platform === "all" || p.platform === platform;
    const matchesCategory = category === "all" || p.category === category;

    return matchesSearch && matchesPlatform && matchesCategory;
  });

  renderPrompts(filtered);
}

// Owner Mode Verification Functions
function toggleOwnerMode(forceState = null) {
  if (forceState !== null) {
    isOwnerMode = forceState;
  } else if (!isOwnerMode) {
    const pin = prompt("Enter Owner PIN to manage/verify prompts (Default: 1234):");
    if (pin === OWNER_PIN) {
      isOwnerMode = true;
      showToast("Owner Mode Activated: Moderation enabled.");
    } else if (pin !== null) {
      alert("Incorrect PIN.");
      return;
    }
  } else {
    isOwnerMode = false;
    showToast("Exited Owner Mode.");
  }

  document.getElementById("adminBanner").style.display = isOwnerMode ? "flex" : "none";
  document.getElementById("ownerBtnText").innerText = isOwnerMode ? "Owner Active" : "Owner Mode";
  renderPrompts();
  updateStats();
}

function verifyPrompt(id) {
  const prompt = prompts.find(p => p.id === id);
  if (prompt) {
    prompt.verified = true;
    savePrompts();
    renderPrompts();
    updateStats();
    showToast("Prompt verified and published!");
  }
}

function deletePrompt(id) {
  if (confirm("Are you sure you want to delete this prompt?")) {
    prompts = prompts.filter(p => p.id !== id);
    savePrompts();
    renderPrompts();
    updateStats();
    showToast("Prompt deleted.");
  }
}

// Export Prompts to JSON (for committing directly to GitHub repository)
function exportPromptsJSON() {
  const dataStr = "data:text/json;charset=utf-8," + encodeURIComponent(JSON.stringify(prompts, null, 2));
  const downloadAnchor = document.createElement("a");
  downloadAnchor.setAttribute("href", dataStr);
  downloadAnchor.setAttribute("download", "prompts.json");
  document.body.appendChild(downloadAnchor);
  downloadAnchor.click();
  downloadAnchor.remove();
  showToast("Exported prompts.json!");
}

// Update Metrics Counters
function updateStats() {
  const verifiedCount = prompts.filter(p => p.verified).length;
  const pendingCount = prompts.filter(p => !p.verified).length;
  const totalVotes = prompts.reduce((sum, p) => sum + (p.upvotes || 0) + (p.downvotes || 0), 0);

  document.getElementById("totalPromptsCount").innerText = verifiedCount;
  document.getElementById("pendingPromptsCount").innerText = pendingCount;
  document.getElementById("totalVotesCount").innerText = totalVotes;
}

// Utility Helpers
function getBadgeClass(platform) {
  switch (platform) {
    case "Gemini": return "badge-gemini";
    case "Claude": return "badge-claude";
    case "GPT-4o": return "badge-gpt";
    case "DeepSeek": return "badge-deepseek";
    default: return "badge-gemini";
  }
}

function escapeHtml(text) {
  if (!text) return "";
  return text
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}

function showToast(msg, isError = false) {
  const container = document.getElementById("toastContainer");
  const toast = document.createElement("div");
  toast.className = "toast";
  toast.innerHTML = `<i class="fa-solid ${isError ? 'fa-circle-xmark' : 'fa-circle-check'}" style="color: ${isError ? '#f87171' : '#34d399'};"></i> ${msg}`;
  container.appendChild(toast);
  setTimeout(() => {
    toast.remove();
  }, 3000);
}

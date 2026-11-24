// AI Assistant - web administrator UI (equivalent of the Swing ClaudeClientPlugin and
// ClaudeSettingsPlugin). Talks to the same REST API (/api/extensions/claude/...), so masking,
// permissions, action approval and the audit log behave exactly as in the Swing client.
// Plain ES module against platform.React: no build step.

const EXT = "/extensions/claude";
const STYLE_ID = "claude-assistant-style";
// The web admin has no plugin hook on the Global Scripts view, so the Claude page offers this context itself.
const GLOBAL_SCRIPTS_CONTEXT = "Global Scripts (deploy, undeploy, preprocessor and postprocessor). The AI Assistant sees the saved version; unsaved changes in the Global Scripts editor are not visible.";
const GLOBAL_SCRIPTS_SUGGESTION = "Explain what the global scripts do and point out weak spots.";
// Same text as ClaudeClientPlugin.QUEUES_SUGGESTION.
const QUEUES_SUGGESTION = "Check the queues and message storage: which destinations are stuck, and which channels need pruning?";
// Same text as ClaudeClientPlugin.EXPLAIN_ERROR_SUGGESTION.
const EXPLAIN_ERROR_SUGGESTION = "Explain why this message failed. Read the message with its errors and the scripts involved, and answer under these headings: "
    + "Root cause (one or two sentences, from the stored errors); "
    + "Where (the connector, and the filter rule, transformer step or script and the line that failed); "
    + "Which data (the segment and field involved, from the message content; masked values show as ***); "
    + "Fix (what to change, proposed as a script, filter or transformer change when that fixes it). "
    + "If no connector of this message has an error, say so and explain what happened instead.";
// Same text as ClaudeClientPlugin.DESCRIPTION_SUGGESTION.
const DESCRIPTION_SUGGESTION = "Write a description for this channel: a few sentences of plain text on its purpose, source, main destinations and anything notable. "
    + "If it already has a description, improve it and keep what is still true. Propose it with Update channel description.";
// Same text as ExplainCodeMenu.EXPLAIN_CODE_REQUEST.
const EXPLAIN_CODE_REQUEST = "what it does step by step, which message data and maps it reads and writes, and anything that looks wrong or risky. "
    + "It is copied from the editor, so it may include unsaved changes.";
/** Longest piece of code put in the question; the same limit as ExplainCodeMenu.MAX_CODE_CHARS. */
const MAX_CODE_CHARS = 20000;
const DOCUMENT_SUGGESTION ="Write documentation for this channel as Markdown: purpose, source, filter, destinations, field mappings, scripts and code templates, Configuration Map keys and open points.";
/** Contexts that name one channel, so it can be documented. */
const CHANNEL_CONTEXT = /^(Channel open in the editor|Selected channel)/;
const POLL_MS = 800;
const ANTHROPIC_BILLING_URL = "https://platform.claude.com/settings/billing";
const OPENAI_BILLING_URL = "https://platform.openai.com/settings/organization/billing/overview";
/** Mistral's OpenAI-compatible API: the Mistral choice saves the OpenAI-compatible provider with this base URL. Same as ClaudeSettingsPanel. */
const MISTRAL_BASE_URL = "https://api.mistral.ai/v1";

/** The dropdown choice for saved settings: "mistral" when the OpenAI-compatible provider points at api.mistral.ai. Same rule as ClaudeSettingsPanel.providerChoice. */
function providerChoice(provider, baseUrl) {
    if (provider !== "openai-compatible") return "anthropic";
    try {
        return new URL((baseUrl || "").trim()).hostname.toLowerCase() === "api.mistral.ai" ? "mistral" : "openai-compatible";
    } catch (err) {
        return "openai-compatible";
    }
}

/** The provider's billing page: Anthropic's, OpenAI's for api.openai.com, or null for other servers (e.g. Ollama). Same rule as ClaudeSettingsPanel.billingUrl. */
function billingUrl(openAi, baseUrl) {
    if (!openAi) return ANTHROPIC_BILLING_URL;
    try {
        return new URL((baseUrl || "").trim()).hostname.toLowerCase() === "api.openai.com" ? OPENAI_BILLING_URL : null;
    } catch (err) {
        return null;
    }
}
const EFFORTS = ["low", "medium", "high", "xhigh", "max"];
// Stored value (English name, used in the system prompt) and the label shown in the dropdown.
const LANGUAGES = [
    ["Automatic", "Automatic (language of the question)"], ["English", "English"], ["Dutch", "Nederlands (Dutch)"], ["German", "Deutsch (German)"],
    ["French", "Français (French)"], ["Spanish", "Español (Spanish)"], ["Italian", "Italiano (Italian)"], ["Portuguese", "Português (Portuguese)"],
    ["Polish", "Polski (Polish)"], ["Swedish", "Svenska (Swedish)"], ["Danish", "Dansk (Danish)"], ["Norwegian", "Norsk (Norwegian)"], ["Finnish", "Suomi (Finnish)"]
];
// Stored value of "Review before sending" and its label.
const REVIEWS = [["data", "Message content, logs and events (recommended)"], ["all", "Everything, including my question"], ["off", "Off (send without review)"]];
// Stroke-only 24x24 path, same format as the built-in icons: an eight-point burst.
const ICON_PATH = "M12 3v5M12 16v5M3 12h5M16 12h5M5.6 5.6l3.5 3.5M14.9 14.9l3.5 3.5M5.6 18.4l3.5-3.5M14.9 9.1l3.5-3.5";

const CSS = `
.claude-page { display: flex; flex-direction: column; height: 100%; min-height: 0; padding: 12px 16px; box-sizing: border-box; gap: 8px; }
.claude-panel { display: flex; flex-direction: column; flex: 1; min-height: 0; gap: 8px; }
.claude-embedded { height: 100%; min-height: 420px; padding: 8px 0; box-sizing: border-box; }
.claude-dialog { height: 70vh; width: 100%; min-width: 0; padding: 0; }
.modal.wide:has(.claude-dialog) { width: min(1200px, calc(100vw - 40px)); }
.claude-dialog .claude-contextbar .claude-context { flex-basis: 100%; }
.claude-contextbar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; color: var(--text-dim); font-size: 12px; }
.claude-contextbar .claude-context { flex: 1 1 240px; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.claude-contextbar .btn { flex: none; white-space: nowrap; }
.claude-suggestions { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; color: var(--text-dim); font-size: 12px; }
.claude-suggestions .btn { flex: none; white-space: nowrap; }
.claude-transcript { flex: 1; min-height: 160px; overflow: auto; background: var(--bg1); border: 1px solid var(--line); border-radius: var(--radius); padding: 10px 12px; }
.claude-transcript > div { margin: 6px 0; }
.claude-user { background: var(--bg3); border-radius: var(--radius); padding: 6px 10px; white-space: pre-wrap; margin-top: 14px !important; }
.claude-text { color: var(--text); line-height: 1.5; }
.claude-text p { margin: 6px 0; }
.claude-text h2, .claude-text h3, .claude-text h4, .claude-text h5 { margin: 12px 0 6px; }
.claude-text pre { font-family: var(--font-mono); font-size: 12px; background: var(--bg2); border: 1px solid var(--line); border-radius: var(--radius); padding: 8px; overflow: auto; }
.claude-text code { font-family: var(--font-mono); font-size: 12px; background: var(--bg2); padding: 0 3px; border-radius: 2px; }
.claude-text pre code { background: none; padding: 0; }
.claude-text .claude-code { position: relative; }
.claude-text .claude-code .claude-copy { position: absolute; top: 4px; right: 4px; padding: 1px 8px; font-size: 11px; line-height: 18px; }
.claude-text table { border-collapse: collapse; margin: 6px 0; }
.claude-text th, .claude-text td { border: 1px solid var(--line-strong); padding: 3px 8px; text-align: left; vertical-align: top; }
.claude-text th { background: var(--bg2); }
.claude-text ul, .claude-text ol { margin: 4px 0; padding-left: 22px; }
.claude-tool { color: var(--text-faint); font-size: 12px; font-family: var(--font-mono); }
.claude-action { color: var(--ok); }
.claude-error { color: var(--err); }
.claude-info { color: var(--warn); }
.claude-input { display: flex; gap: 8px; align-items: stretch; }
.claude-input textarea { flex: 1; min-height: 64px; resize: vertical; font: inherit; color: var(--text); background: var(--bg1); border: 1px solid var(--line-strong); border-radius: var(--radius); padding: 6px 8px; }
.claude-input .claude-buttons { display: flex; flex-direction: column; gap: 6px; }
.claude-status { color: var(--text-faint); font-size: 12px; min-height: 16px; }
.claude-detail { font-family: var(--font-mono); font-size: 12px; white-space: pre-wrap; max-height: 55vh; overflow: auto; background: var(--bg1); border: 1px solid var(--line); border-radius: var(--radius); padding: 8px; margin: 8px 0 0; }
.claude-diff .add { display: block; background: rgba(46, 160, 67, 0.18); }
.claude-diff .del { display: block; background: rgba(248, 81, 73, 0.18); }
.claude-diff .hunk { display: block; color: var(--text-faint); }
.claude-diff .block { display: block; font-weight: 600; margin-top: 6px; }
.claude-view-toggle { display: flex; gap: 6px; margin-top: 8px; }
.claude-view-toggle button { font: inherit; font-size: 12px; color: var(--text); background: var(--bg1); border: 1px solid var(--line-strong); border-radius: var(--radius); padding: 2px 10px; cursor: pointer; }
.claude-view-toggle button[aria-pressed=true] { background: var(--line-strong); font-weight: 600; }
.claude-review { display: block; width: 100%; box-sizing: border-box; height: 50vh; margin: 8px 0 4px; resize: vertical; font-family: var(--font-mono); font-size: 12px; color: var(--text); background: var(--bg1); border: 1px solid var(--line-strong); border-radius: var(--radius); padding: 8px; }
.claude-hint { color: var(--text-faint); font-size: 12px; }
.claude-settings { padding: 12px 16px; max-width: 760px; }
.claude-settings .claude-row { display: grid; grid-template-columns: 220px 1fr; gap: 10px; align-items: start; margin: 8px 0; }
.claude-settings .claude-row > label { text-align: right; color: var(--text-dim); padding-top: 7px; line-height: 18px; }
.claude-settings .claude-row > div > .claude-hint { margin-top: 4px; }
.claude-settings .claude-text-value { padding-top: 7px; line-height: 18px; }
.claude-settings input[type=password], .claude-settings input[type=text], .claude-settings input[type=number], .claude-settings select, .claude-settings textarea {
  font: inherit; color: var(--text); background: var(--bg1); border: 1px solid var(--line-strong); border-radius: var(--radius); padding: 4px 6px; box-sizing: border-box; }
.claude-settings input[type=password], .claude-settings input[type=text], .claude-settings textarea { width: 100%; }
.claude-settings .claude-hint { color: var(--text-faint); font-size: 12px; }
.claude-settings .claude-test { margin-top: 6px; line-height: 18px; }
.claude-settings .claude-test .ok { color: var(--ok); }
.claude-settings .claude-test .failed { color: var(--err); }
.claude-settings .claude-test .warning { color: var(--warn); }
.claude-settings .claude-test .skipped { color: var(--text-faint); }
.claude-settings h3 { margin: 18px 0 4px; padding-top: 12px; border-top: 1px solid var(--line); }
.claude-settings .claude-spend { font-weight: 600; }
.claude-conversations { width: 100%; font-family: inherit; margin-bottom: 8px; }
.claude-settings .claude-inline { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.claude-settings .claude-warning { color: #b35900; }
`;

let P;
let React;
let e;

// ------------------------------------------------------------------ REST API
// raw:true everywhere: the shell's parseBody unwraps single-key JSON objects.

async function call(method, path, body, params) {
    const api = P.api;
    let text;
    if (method === "GET") {
        text = await api.get(EXT + path, params, { raw: true });
    } else if (method === "POST") {
        text = await api.post(EXT + path, body == null ? "" : JSON.stringify(body), { params, contentType: "text/plain", raw: true });
    } else {
        text = await api.put(EXT + path, JSON.stringify(body), { params, contentType: "text/plain", raw: true });
    }
    return text ? JSON.parse(text) : {};
}

/**
 * The approval dialog's body: for a script, filter or transformer change the diff (Changes, the
 * default) with a toggle to the full details; otherwise the details alone. Plain DOM, so the
 * toggle works whatever the shell's h() returns.
 */
function approvalBody(pending) {
    const full = document.createElement("pre");
    full.className = "claude-detail";
    full.textContent = pending.detail || "";
    if (!pending.changes) return full;
    const diff = document.createElement("pre");
    diff.className = "claude-detail claude-diff";
    let inBlocks = false;
    for (const line of pending.changes.replace(/\n$/, "").split("\n")) {
        inBlocks = inBlocks || line.startsWith("=== ");
        const span = document.createElement("span");
        span.textContent = line;
        if (inBlocks) {
            span.className = line.startsWith("=== ") ? "block" : line.startsWith("@@ ") ? "hunk" : line.startsWith("+ ") ? "add" : line.startsWith("- ") ? "del" : "";
        }
        diff.appendChild(span);
        if (!span.className) diff.appendChild(document.createTextNode("\n"));
    }
    full.style.display = "none";
    const toggle = document.createElement("div");
    toggle.className = "claude-view-toggle";
    const show = (changes) => {
        diff.style.display = changes ? "" : "none";
        full.style.display = changes ? "none" : "";
        changesButton.setAttribute("aria-pressed", String(changes));
        fullButton.setAttribute("aria-pressed", String(!changes));
    };
    const button = (label, changes) => {
        const b = document.createElement("button");
        b.type = "button";
        b.textContent = label;
        b.addEventListener("click", () => show(changes));
        toggle.appendChild(b);
        return b;
    };
    const changesButton = button("Changes", true);
    const fullButton = button("Full details", false);
    show(true);
    const box = document.createElement("div");
    box.append(toggle, diff, full);
    return box;
}

function errorText(err) {
    if (!err) return "Unknown error";
    const text = err.body || err.message || String(err);
    // The server answers with a readable sentence as the response body.
    return String(text).trim().split(/\r?\n/).pop();
}

// ------------------------------------------------------------------ Markdown -> HTML
// Same subset as the Swing client (headings, paragraphs, lists, tables, code, bold, italic);
// everything is escaped first, so the result is safe to insert as HTML.

function esc(s) {
    return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

const INLINE = /`([^`]+)`|\*\*(.+?)\*\*|__(.+?)__|(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])|(?<!\w)_(?!\s)(.+?)(?<!\s)_(?!\w)/g;

function inline(text) {
    let out = "";
    let last = 0;
    for (const m of text.matchAll(INLINE)) {
        out += esc(text.slice(last, m.index));
        if (m[1] != null) out += "<code>" + esc(m[1]) + "</code>";
        else if (m[2] != null || m[3] != null) out += "<b>" + inline(m[2] != null ? m[2] : m[3]) + "</b>";
        else out += "<i>" + inline(m[4] != null ? m[4] : m[5]) + "</i>";
        last = m.index + m[0].length;
    }
    return out + esc(text.slice(last));
}

function cells(row) {
    let r = row.trim();
    if (r.startsWith("|")) r = r.slice(1);
    if (r.endsWith("|")) r = r.slice(0, -1);
    return r.split(/(?<!\\)\|/).map((c) => c.trim().replace(/\\\|/g, "|"));
}

/** @param withCopy give every code block a Copy button (finished answers; not while one is being written) */
function markdown(md, withCopy) {
    const lines = String(md).replace(/\r\n?/g, "\n").split("\n");
    const ORDERED = /^\s*\d+[.)]\s+(.*)$/;
    const UNORDERED = /^\s*[-*+]\s+(.*)$/;
    const SEPARATOR = /^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$/;
    let out = "";
    let para = [];
    const flush = () => {
        if (para.length) out += "<p>" + para.map(inline).join("<br>") + "</p>";
        para = [];
    };
    let i = 0;
    while (i < lines.length) {
        const line = lines[i];
        const t = line.trim();
        if (t.startsWith("```")) {
            flush();
            const code = [];
            i++;
            while (i < lines.length && !lines[i].trim().startsWith("```")) code.push(lines[i++]);
            i++;
            const block = "<pre><code>" + esc(code.join("\n")) + "</code></pre>";
            out += withCopy ? '<div class="claude-code"><button type="button" class="btn claude-copy" data-copy="1" title="Copy this code block">Copy</button>' + block + "</div>" : block;
            continue;
        }
        if (!t) { flush(); i++; continue; }
        const heading = t.match(/^(#{1,6})\s+(.*)$/);
        if (heading) {
            flush();
            const level = Math.min(heading[1].length + 1, 5);
            out += `<h${level}>${inline(heading[2])}</h${level}>`;
            i++;
            continue;
        }
        if (/^(-{3,}|\*{3,}|_{3,})$/.test(t)) { flush(); out += "<hr>"; i++; continue; }
        if (t.startsWith("|") && i + 1 < lines.length && SEPARATOR.test(lines[i + 1])) {
            flush();
            out += "<table><tr>" + cells(t).map((c) => "<th>" + inline(c) + "</th>").join("") + "</tr>";
            i += 2;
            while (i < lines.length && lines[i].trim().startsWith("|")) {
                out += "<tr>" + cells(lines[i]).map((c) => "<td>" + inline(c) + "</td>").join("") + "</tr>";
                i++;
            }
            out += "</table>";
            continue;
        }
        if (UNORDERED.test(line) || ORDERED.test(line)) {
            flush();
            const ordered = ORDERED.test(line);
            const item = ordered ? ORDERED : UNORDERED;
            out += ordered ? "<ol>" : "<ul>";
            while (i < lines.length && item.test(lines[i])) {
                out += "<li>" + inline(lines[i].match(item)[1]);
                i++;
                // Continuation lines and nested items stay inside the same item.
                while (i < lines.length && lines[i].trim() && /^\s/.test(lines[i])) out += "<br>" + inline(lines[i++].trim());
                out += "</li>";
            }
            out += ordered ? "</ol>" : "</ul>";
            continue;
        }
        para.push(t);
        i++;
    }
    flush();
    return out;
}

// ------------------------------------------------------------------ chat controller
// One conversation per browser tab, kept outside React so it survives navigating between
// the Claude page, the channel tab and other views while the AI Assistant is still working.

const chat = {
    conversationId: null,
    context: null,
    suggestion: null,
    // Suggested questions for the context (from the server, in the response language); shown until the first question.
    suggestions: [],
    suggestionsFor: undefined,
    entries: [],
    // Claude's answer while it is being written; replaced by the complete "text" event.
    partial: "",
    jobId: null,
    lastSeq: 0,
    busy: false,
    status: "",
    shownActionId: null,
    timer: null,
    polling: false,
    listeners: new Set(),

    subscribe(fn) {
        this.listeners.add(fn);
        return () => this.listeners.delete(fn);
    },
    notify() {
        for (const fn of this.listeners) fn();
    },
    add(kind, text) {
        this.entries = [...this.entries, { kind, text }];
        this.notify();
    },
    setStatus(text) {
        this.status = text;
        this.notify();
    },

    /** Sets the context (and a suggested question) from an action in the Administrator. */
    /** replace: the suggestion replaces whatever is in the question box (Explain with AI Assistant). */
    open(context, suggestion, replace) {
        this.context = context;
        if (suggestion) {
            this.suggestion = suggestion;
            this.replaceSuggestion = !!replace;
        }
        this.loadSuggestions();
        this.notify();
    },
    clearContext() {
        this.context = null;
        this.loadSuggestions();
        this.notify();
    },
    /** Fetches the suggested questions when the context changed; fixed texts, no model call. */
    loadSuggestions() {
        const context = this.context;
        if (context === this.suggestionsFor) return;
        this.suggestionsFor = context;
        this.suggestions = [];
        if (!context) return;
        call("POST", "/suggestions", { context }).then((res) => {
            if (this.context === context) {
                this.suggestions = (res && res.suggestions) || [];
                this.notify();
            }
        }).catch(() => {});
    },
    /** Suggestions show only before the first question of the conversation. */
    shownSuggestions() {
        return this.context && !this.entries.some((entry) => entry.kind === "user") ? this.suggestions : [];
    },

    async send(text) {
        if (!text.trim() || this.busy) return;
        this.add("user", text);
        this.busy = true;
        this.setStatus("Sending…");
        try {
            const res = await call("POST", "/chat", { conversationId: this.conversationId, message: text, context: this.context });
            this.conversationId = res.conversationId;
            this.jobId = res.jobId;
            this.lastSeq = 0;
            this.shownActionId = null;
            this.setStatus("AI Assistant is thinking…");
            this.timer = setInterval(() => this.poll(), POLL_MS);
        } catch (err) {
            this.busy = false;
            this.status = "";
            this.add("error", errorText(err));
        }
    },

    async poll() {
        if (this.polling || !this.jobId) return;
        this.polling = true;
        const id = this.jobId;
        try {
            const job = await call("GET", "/jobs/" + encodeURIComponent(id), null, { after: this.lastSeq });
            if (id === this.jobId) this.handle(job);
        } catch (err) {
            this.finishJob();
            this.add("error", "Lost connection to the server: " + errorText(err));
        } finally {
            this.polling = false;
        }
    },

    handle(job) {
        for (const ev of job.events || []) {
            this.lastSeq = Math.max(this.lastSeq, ev.seq || 0);
            if (ev.type === "tool") this.status = "AI Assistant is querying the server…";
            this.entries = [...this.entries, { kind: ev.type, text: ev.text }];
        }
        this.partial = job.partial || "";
        if (this.partial) this.status = "AI Assistant is writing…";
        const pending = job.pendingAction;
        if (job.state === "WAITING" && pending && pending.id !== this.shownActionId) {
            this.shownActionId = pending.id;
            this.status = "Waiting for your approval…";
            this.askApproval(pending);
        }
        const review = job.pendingReview;
        if (job.state === "WAITING" && review && review.id !== this.shownActionId) {
            this.shownActionId = review.id;
            this.status = "Waiting for your review…";
            this.askReview(review);
        }
        if (job.state === "DONE" || job.state === "ERROR" || job.state === "CANCELLED") {
            this.finishJob();
        }
        this.notify();
    },

    finishJob() {
        clearInterval(this.timer);
        this.timer = null;
        this.jobId = null;
        this.partial = "";
        this.busy = false;
        this.status = "";
        this.notify();
    },

    stop() {
        const id = this.jobId;
        if (!id) return;
        this.finishJob();
        this.add("info", "Stopped.");
        call("POST", "/jobs/" + encodeURIComponent(id) + "/cancel").catch(() => {});
    },

    newConversation() {
        if (this.jobId) this.stop();
        this.conversationId = null;
        this.entries = [];
        this.partial = "";
        this.notify();
    },

    /** Downloads the conversation as Markdown, as the server has it: what Claude received (masked) and answered. */
    async exportConversation() {
        if (!this.conversationId) {
            this.add("info", "There is no conversation to export yet.");
            return;
        }
        try {
            const res = await call("GET", "/conversations/" + encodeURIComponent(this.conversationId) + "/export");
            download(res.markdown || "", res.fileName || "claude-conversation.md");
        } catch (err) {
            this.add("error", "Export failed: " + errorText(err));
        }
    },

    /** Claude's last complete answer, as Markdown, or null. */
    lastAnswer() {
        for (let i = this.entries.length - 1; i >= 0; i--) {
            if (this.entries[i].kind === "text") return this.entries[i].text;
        }
        return null;
    },

    /** Saves only Claude's last answer as a Markdown file, named after its first heading. */
    saveAnswer() {
        const answer = this.lastAnswer();
        if (answer) download(answer.trim() + "\n", markdownFileName(answer, "claude-answer.md"));
    },

    /** Lists the user's conversations (current and saved) and continues the chosen one. */
    async chooseConversation() {
        if (this.jobId) {
            this.add("info", "Wait until the AI Assistant has finished, or click Stop.");
            return;
        }
        let res;
        try {
            res = await call("GET", "/conversations");
        } catch (err) {
            this.add("error", errorText(err));
            return;
        }
        const list = res.conversations || [];
        const note = res.keepDays > 0
            ? "Conversations are saved on the server for " + res.keepDays + " days."
            : "Conversations are not saved on the server: they are kept for 4 hours and lost on a restart (Settings > AI Assistant).";
        const { h, modal } = P.ui;
        if (list.length === 0) {
            modal({ title: "Conversations", body: h("div", null, "You have no earlier conversations. " + note), buttons: [{ label: "Close", primary: true, onClick: () => true }] });
            return;
        }
        // A plain DOM select, so its value can be read back whatever the shell's h() returns.
        const select = document.createElement("select");
        select.className = "claude-conversations";
        select.size = Math.min(12, Math.max(4, list.length));
        for (const c of list) {
            const option = document.createElement("option");
            option.value = c.id;
            option.textContent = new Date(c.lastUsed).toLocaleString() + "   " + c.title + (c.id === this.conversationId ? "   (open)" : "");
            select.appendChild(option);
        }
        select.selectedIndex = 0;
        const resume = async () => {
            const id = select.value;
            if (!id) return true;
            try {
                this.showConversation(await call("POST", "/conversations/" + encodeURIComponent(id) + "/resume"));
            } catch (err) {
                this.add("error", errorText(err));
            }
            return true;
        };
        select.ondblclick = () => { resume(); };
        modal({
            title: "Conversations",
            size: "lg",
            body: h("div", null, select, h("div.claude-hint", null, note)),
            buttons: [
                { label: "Cancel", onClick: () => true },
                { label: "Continue", primary: true, onClick: resume }
            ]
        });
    },

    showConversation(resumed) {
        this.conversationId = resumed.conversationId;
        this.entries = (resumed.log || []).map((entry) => ({ kind: entry.type === "question" ? "user" : entry.type, text: entry.text }));
        this.entries.push({ kind: "info", text: "Continued conversation. Ask your next question." });
        this.notify();
    },

    askApproval(pending) {
        const jobId = this.jobId;
        const { h, modal } = P.ui;
        let decided = false;
        const decide = async (approved) => {
            if (decided) return true;
            decided = true;
            this.setStatus(approved ? "Running action…" : "AI Assistant continues…");
            try {
                await call("POST", "/jobs/" + encodeURIComponent(jobId) + "/confirm", { actionId: pending.id, approved });
                this.setStatus("AI Assistant continues…");
            } catch (err) {
                this.add("error", "Approval failed: " + errorText(err));
            }
            return true;
        };
        modal({
            title: "Approve: " + pending.title,
            size: "lg",
            body: h("div", null,
                h("div", null, "The AI Assistant wants to run the following action on this server:"),
                approvalBody(pending)),
            buttons: [
                { label: "Reject", onClick: () => decide(false) },
                { label: "Run", danger: true, onClick: () => decide(true) }
            ],
            // Closing the dialog (X, Escape, clicking outside) counts as rejecting, as in Swing.
            onClose: () => { decide(false); }
        });
    },

    /** Shows exactly what Claude would receive; the user can edit it, send it or withhold it. */
    askReview(review) {
        const jobId = this.jobId;
        const { h, modal } = P.ui;
        // A plain DOM textarea, so its value can be read back whatever the shell's h() returns.
        const text = document.createElement("textarea");
        text.className = "claude-review";
        text.value = review.detail || "";
        text.spellcheck = false;
        let decided = false;
        const decide = async (approved) => {
            if (decided) return true;
            decided = true;
            this.setStatus("AI Assistant continues…");
            try {
                await call("POST", "/jobs/" + encodeURIComponent(jobId) + "/review", approved ? { reviewId: review.id, approved, text: text.value } : { reviewId: review.id, approved });
            } catch (err) {
                this.add("error", "Review failed: " + errorText(err));
            }
            return true;
        };
        modal({
            title: "Review before sending: " + review.title,
            size: "lg",
            body: h("div", null,
                h("div", null, "This is exactly what will be sent to the AI model, after masking. Edit it to remove anything the AI model should not see; edited text is masked again before sending."),
                text,
                h("div.claude-hint", null, (review.detail || "").length + " characters. Closing this dialog sends nothing.")),
            buttons: [
                { label: "Don't send", onClick: () => decide(false) },
                { label: "Send to the AI model", primary: true, onClick: () => decide(true) }
            ],
            // Closing the dialog (X, Escape, clicking outside) sends nothing.
            onClose: () => { decide(false); }
        });
    }
};

// ------------------------------------------------------------------ React components

/** Copy button of a code block: copies the code exactly (the text of its <code>, without fences). */
async function copyCodeBlock(ev) {
    const button = ev.target.closest && ev.target.closest("[data-copy]");
    if (!button) return;
    const code = button.parentElement.querySelector("pre code");
    const text = code ? code.textContent : "";
    let copied = false;
    try {
        await navigator.clipboard.writeText(text);
        copied = true;
    } catch (err) {
        // No Clipboard API (plain http): copy through a hidden text area.
        const area = document.createElement("textarea");
        area.value = text;
        area.setAttribute("readonly", "");
        area.style.position = "fixed";
        area.style.opacity = "0";
        document.body.appendChild(area);
        area.select();
        try { copied = document.execCommand("copy"); } catch (e2) { copied = false; }
        area.remove();
    }
    button.textContent = copied ? "Copied" : "Copy failed";
    setTimeout(() => { button.textContent = "Copy"; }, 1500);
}

function useChat() {
    const [, setTick] = React.useState(0);
    React.useEffect(() => chat.subscribe(() => setTick((n) => n + 1)), []);
    return chat;
}

function Entry({ entry }) {
    switch (entry.kind) {
        case "user":
            return e("div", { className: "claude-user" }, e("b", null, "You: "), entry.text);
        case "text":
            return e("div", { className: "claude-text", onClick: copyCodeBlock, dangerouslySetInnerHTML: { __html: markdown(entry.text, !entry.partial) } });
        case "tool":
            return e("div", { className: "claude-tool" }, "→ " + entry.text);
        case "action":
            return e("div", { className: "claude-action" }, e("b", null, "Done: "), entry.text);
        case "error":
            return e("div", { className: "claude-error" }, entry.text);
        default:
            return e("div", { className: "claude-info" }, entry.text);
    }
}

function ChatPanel() {
    const c = useChat();
    const [input, setInput] = React.useState("");
    const transcriptRef = React.useRef(null);
    const inputRef = React.useRef(null);
    const lastSuggestion = React.useRef(null);

    // A suggestion from an action fills the input, unless the user already typed something of
    // their own (an untouched earlier suggestion is replaced).
    React.useEffect(() => {
        if (c.suggestion) {
            if (c.replaceSuggestion || !input.trim() || input === lastSuggestion.current) {
                setInput(c.suggestion);
                lastSuggestion.current = c.suggestion;
                setTimeout(() => inputRef.current && inputRef.current.select(), 0);
            }
            c.suggestion = null;
            c.replaceSuggestion = false;
        }
    });

    React.useEffect(() => {
        const el = transcriptRef.current;
        if (el) el.scrollTop = el.scrollHeight;
    }, [c.entries.length, c.partial.length]);

    const send = () => {
        const text = input.trim();
        if (!text || c.busy) return;
        setInput("");
        c.send(text);
    };

    const welcome = c.entries.length === 0
        ? e("div", { className: "claude-info" },
            "Ask a question about your OIE server, for example ", e("i", null, "Which channels have errors?"), " or ", e("i", null, "Why does this message fail?"),
            e("br"), "Message content is masked before it is sent to the AI model. Actions such as deploying only run after you approve them.")
        : null;

    return e("div", { className: "claude-panel" },
        e("div", { className: "claude-contextbar" },
            e("span", { className: "claude-context", title: c.context || "" },
                c.context ? [e("b", { key: "l" }, "Context: "), c.context] : "No context: the AI Assistant looks at the whole server."),
            e("button", {
                className: "btn",
                title: "Ask about the global deploy, undeploy, preprocessor and postprocessor scripts",
                disabled: c.context === GLOBAL_SCRIPTS_CONTEXT,
                onClick: () => c.open(GLOBAL_SCRIPTS_CONTEXT, GLOBAL_SCRIPTS_SUGGESTION)
            }, "Global Scripts"),
            e("button", {
                className: "btn",
                title: "Let the AI Assistant write documentation for the channel in the context; save it with Save answer",
                disabled: !CHANNEL_CONTEXT.test(c.context || ""),
                onClick: () => c.open(c.context, DOCUMENT_SUGGESTION)
            }, "Document channel"),
            e("button", {
                className: "btn",
                title: "Let the AI Assistant write or improve the Description of the channel in the context; you approve it before it is saved",
                disabled: c.busy || !CHANNEL_CONTEXT.test(c.context || ""),
                onClick: () => c.open(c.context, DESCRIPTION_SUGGESTION)
            }, "Write description"),
            mayUse("claudeQueues") ? e("button", {
                className: "btn",
                title: "Let the AI Assistant look for stuck queues and channels whose stored messages keep growing",
                disabled: c.busy,
                onClick: () => c.open(null, QUEUES_SUGGESTION)
            }, "Queues and storage") : null,
            e("button", { className: "btn", disabled: !c.context, onClick: () => c.clearContext() }, "Clear context"),
            e("button", { className: "btn", title: "Continue one of your earlier conversations", onClick: () => c.chooseConversation() }, "Conversations…"),
            e("button", { className: "btn", title: "Save this conversation as a Markdown file", disabled: !c.conversationId, onClick: () => c.exportConversation() }, "Export"),
            e("button", { className: "btn", title: "Save only the AI Assistant's last answer as a Markdown file, for example a channel's documentation", disabled: c.busy || !c.lastAnswer(), onClick: () => c.saveAnswer() }, "Save answer"),
            e("button", { className: "btn", onClick: () => c.newConversation() }, "New conversation")),
        c.shownSuggestions().length
            ? e("div", { className: "claude-suggestions" },
                e("span", null, "Ask:"),
                c.shownSuggestions().map((question) => e("button", {
                    key: question,
                    className: "btn",
                    title: "Send this question",
                    disabled: c.busy,
                    onClick: () => c.send(question)
                }, question)))
            : null,
        e("div", { className: "claude-transcript", ref: transcriptRef },
            welcome,
            c.entries.map((entry, i) => e(Entry, { key: i, entry })),
            c.partial ? e(Entry, { key: "partial", entry: { kind: "text", text: c.partial, partial: true } }) : null),
        e("div", { className: "claude-input" },
            e("textarea", {
                ref: inputRef,
                value: input,
                placeholder: "Ask the AI Assistant… (Ctrl+Enter to send)",
                onChange: (ev) => setInput(ev.target.value),
                onKeyDown: (ev) => {
                    if (ev.key === "Enter" && (ev.ctrlKey || ev.metaKey)) {
                        ev.preventDefault();
                        send();
                    }
                }
            }),
            e("div", { className: "claude-buttons" },
                e("button", { className: "btn btn-primary", disabled: c.busy || !input.trim(), onClick: send }, "Send"),
                e("button", { className: "btn", disabled: !c.busy, onClick: () => c.stop() }, "Stop"))),
        e("div", { className: "claude-status" }, c.status || " "));
}

/**
 * Whether the user's role grants "Use AI Assistant". The server publishes the Swing task names
 * with that permission, so an RBAC plugin answers checkTask for them; without RBAC everything is allowed.
 */
function mayUse(task = "claudeOpen") {
    return typeof P.checkTask !== "function" || P.checkTask("other", task);
}

/** Shown instead of the chat when the route is opened directly without the permission. */
function NoAccess() {
    return e("div", { className: "claude-error" }, "You do not have the OIE permission \"Use AI Assistant\". Ask an administrator to add it to your role.");
}

function ChatView() {
    return e("div", { className: "claude-page" }, mayUse() ? e(ChatPanel) : e(NoAccess));
}

/** "Claude" tab in the channel editor: the chat, with this channel as context. */
function ChannelTab(props) {
    const channel = props.channel || (props.ctx && props.ctx.channel) || {};
    const allowed = mayUse("claudeChannelEdit");
    React.useEffect(() => {
        if (!channel.id || !allowed) return;
        const context = `Channel open in the editor: '${channel.name}' (${channel.id}, revision ${channel.revision}). The AI Assistant sees the saved version; unsaved changes in the editor are not visible.`;
        if (chat.context !== context) chat.open(context, "Explain what this channel does and point out weak spots in its scripts.");
    }, [channel.id, allowed]);
    return e("div", { className: "claude-panel claude-embedded" }, allowed ? e(ChatPanel) : e(NoAccess));
}

/**
 * Settings > AI Assistant. Hooks into the settings shell like the built-in tabs: setSave for
 * the shell's Save (and its unsaved-changes prompt), markDirty/markClean, and a task pane.
 */
/** Marks of the Test connection checks, by status. */
const TEST_MARKS = { ok: "✔", failed: "✘", warning: "!", skipped: "–" };

function SettingsPanel({ setTasks, setSave, markDirty, markClean }) {
    const [form, setForm] = React.useState(null);
    const [status, setStatus] = React.useState({});
    const [spend, setSpend] = React.useState({ state: "idle" });
    // The model list comes from the server: Anthropic's, or a fixed list when it cannot be read.
    const [models, setModels] = React.useState({ state: "idle" });
    // True after "Other model ID…": the model is typed instead of chosen from the list.
    const [typeModel, setTypeModel] = React.useState(false);
    const [error, setError] = React.useState(null);
    // Test connection: { state: "idle" | "loading" | "ok" | "error", data?, message? }
    const [test, setTest] = React.useState({ state: "idle" });
    const formRef = React.useRef(null);
    formRef.current = form;
    // Whether the form has unsaved changes: Test connection uses the saved settings.
    const unsaved = React.useRef(false);
    const dirty = () => { unsaved.current = true; if (markDirty) markDirty(); };
    const clean = () => { unsaved.current = false; if (markClean) markClean(); };

    const testConnection = async () => {
        if (unsaved.current) {
            setTest({ state: "error", message: "Save the settings first: the test uses the saved settings." });
            return;
        }
        setTest({ state: "loading" });
        try {
            setTest({ state: "ok", data: await call("POST", "/test-connection", null) });
        } catch (err) {
            setTest({ state: "error", message: errorText(err) });
        }
    };

    const loadSpend = React.useCallback(async (refresh) => {
        setSpend({ state: "loading" });
        try {
            setSpend({ state: "ok", data: await call("GET", "/spend", null, refresh ? { refresh: true } : undefined) });
        } catch (err) {
            setSpend({ state: "error", message: errorText(err) });
        }
    }, []);

    const loadModels = React.useCallback(async (refresh) => {
        setModels({ state: "loading" });
        try {
            setModels({ state: "ok", data: await call("GET", "/models", null, refresh ? { refresh: true } : undefined) });
        } catch (err) {
            setModels({ state: "error", message: errorText(err) });
        }
    }, []);

    const show = React.useCallback((s) => {
        setStatus(s);
        setForm({ apiKey: "", provider: providerChoice(s.provider, s.baseUrl), baseUrl: s.baseUrl || "", adminApiKey: "", clearAdminApiKey: false, model: s.model || "", effort: s.effort || "high", maxToolCalls: s.maxToolCalls || 25, maskPatterns: s.maskPatterns || "", responseLanguage: s.responseLanguage || "Automatic", reviewBeforeSending: s.reviewBeforeSending || "data", keepConversationsDays: s.keepConversationsDays || 0 });
        clean();
        setTest({ state: "idle" });
        loadModels(false);
        if (s.adminApiKeySet && s.provider !== "openai-compatible") loadSpend(false);
        else setSpend({ state: "idle" });
    }, [loadSpend, loadModels]);

    const load = React.useCallback(async () => {
        try {
            show(await call("GET", "/settings"));
            setError(null);
        } catch (err) {
            setError("Loading settings failed: " + errorText(err));
        }
    }, [show]);

    /** Returns true when saved, false otherwise (the shell's unsaved-changes prompt relies on it). */
    const save = React.useCallback(async () => {
        const f = formRef.current;
        if (!f) return false;
        const mistral = f.provider === "mistral";
        try {
            show(await call("PUT", "/settings", { ...f, provider: mistral ? "openai-compatible" : f.provider, baseUrl: mistral ? MISTRAL_BASE_URL : f.baseUrl, maxToolCalls: Number(f.maxToolCalls) || 25, keepConversationsDays: Math.max(0, Math.min(365, Number(f.keepConversationsDays) || 0)) }));
            setError(null);
            P.ui.toast("AI Assistant settings saved.", "success");
            return true;
        } catch (err) {
            setError("Saving failed: " + errorText(err));
            return false;
        }
    }, [show]);

    const loadRef = React.useRef(load);
    loadRef.current = load;
    const saveRef = React.useRef(save);
    saveRef.current = save;

    React.useEffect(() => {
        if (setSave) setSave(() => saveRef.current());
        if (setTasks) {
            const { taskButton } = P.ui;
            setTasks("AI Assistant Tasks", [
                taskButton("Refresh", "refresh", () => loadRef.current()),
                taskButton("Save", "save", () => saveRef.current(), { primary: true })
            ]);
        }
        loadRef.current();
    }, []);

    if (!form) return e("div", { className: "claude-settings" }, error ? e("div", { className: "claude-error" }, error) : "Loading…");

    const set = (key) => (ev) => {
        setForm({ ...form, [key]: ev.target.type === "checkbox" ? ev.target.checked : ev.target.value });
        dirty();
    };
    // Labels sit level with the first line of their field; plain text gets the same top padding.
    const row = (label, control, hint) => e("div", { className: "claude-row" }, e("label", null, label),
        e("div", null, typeof control === "string" ? e("div", { className: "claude-text-value" }, control) : control, hint ? e("div", { className: "claude-hint" }, hint) : null));

    // e.g. "1.2M input, 18.4M read from the cache (94% of the input), 0.3M written to the cache, 0.2M output (the plugin's API key)"
    const compact = (n) => n >= 1e6 ? (n / 1e6).toFixed(1) + "M" : n >= 1e3 ? (n / 1e3).toFixed(1) + "k" : String(n);
    const tokenLine = (t) => {
        if (!t) return "–";
        const total = t.input + t.cacheRead + t.cacheWrite;
        const share = total ? ` (${Math.round(100 * t.cacheRead / total)}% of the input)` : "";
        return `${compact(t.input)} input, ${compact(t.cacheRead)} read from the cache${share}, ${compact(t.cacheWrite)} written to the cache, ${compact(t.output)} output`
            + (t.scope === "pluginKey" ? " (the plugin's API key)" : " (organization)");
    };

    let spendLines;
    const mistral = form.provider === "mistral";
    const openAi = form.provider === "openai-compatible" || mistral;
    const billing = billingUrl(openAi, mistral ? MISTRAL_BASE_URL : form.baseUrl);
    if (status.provider === "openai-compatible") {
        spendLines = [row("Spend this month:", "–", "Spend this month is only available with Anthropic as the provider.")];
    } else if (!status.adminApiKeySet) {
        spendLines = [row("Spend this month:", "–", "Set an Admin API key to show the spend this month.")];
    } else if (spend.state === "loading") {
        spendLines = [row("Spend this month:", "Loading…")];
    } else if (spend.state === "error") {
        spendLines = [row("Spend this month:", "–", spend.message)];
    } else if (spend.state === "ok") {
        const d = spend.data;
        const note = d.workspaceUsd != null ? null
            : d.pluginKeyName ? "The plugin's API key has no workspace of its own, so only the organization total is shown."
                : "The plugin's API key was not found in this organization, so only the organization total is shown.";
        spendLines = [
            row("Spend this month:", e("span", { className: "claude-inline" },
                e("span", { className: "claude-spend" }, `$${d.organizationUsd} (organization, since ${d.monthStart})`),
                e("button", { className: "btn", onClick: () => loadSpend(true) }, "Refresh")), note),
            d.workspaceUsd != null ? row("", `of which workspace '${d.workspaceName}' (the plugin's API key): $${d.workspaceUsd}`) : null,
            row("Tokens this month:", tokenLine(d.tokens))
        ];
    }

    const modelList = models.state === "ok" ? models.data.models || [] : [];
    let modelHint;
    if (models.state === "loading") modelHint = "Loading the model list…";
    else if (models.state === "error") modelHint = "The model list could not be loaded: " + models.message + " Type a model ID.";
    else if (models.state === "ok") {
        const d = models.data;
        const updated = d.fetchedAt ? ", updated " + new Date(d.fetchedAt).toLocaleString([], { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }) : "";
        modelHint = d.warning ? e("span", { className: "claude-warning" }, d.warning)
            : d.source === "anthropic" ? `${modelList.length} models from Anthropic${updated}. Choose Other model ID… for one that is not listed.`
                : d.source === "openai-compatible" ? `${modelList.length} models from the model server${updated}. Choose one that supports tool calling, or Other model ID… to type one.`
                : (d.note || "A fixed list is shown.") + " Choose Other model ID… for one that is not listed.";
    }

    // A dropdown of the listed models, so every model shows whatever is saved now (a datalist only
    // suggests models that match the text already in the field). "Other model ID…" switches to a text field.
    const OTHER = "__other_model_id__";
    const modelField = () => {
        if (typeModel || modelList.length === 0) {
            return e("span", { className: "claude-inline" },
                e("input", { type: "text", value: form.model, onChange: set("model"), placeholder: "Model ID", style: { width: 260 } }),
                modelList.length ? e("button", { className: "btn", onClick: () => setTypeModel(false) }, "Choose from the list") : null);
        }
        const options = modelList.map((m) => e("option", { key: m.id, value: m.id }, m.unlisted ? m.displayName + " – not in the list" : m.displayName));
        if (!modelList.some((m) => m.id === form.model)) {
            options.unshift(e("option", { key: "", value: form.model }, form.model ? form.model + " – not in the list" : "Choose a model"));
        }
        options.push(e("option", { key: OTHER, value: OTHER }, "Other model ID…"));
        return e("select", {
            value: form.model, style: { width: 260 },
            onChange: (ev) => {
                if (ev.target.value === OTHER) setTypeModel(true);
                else set("model")(ev);
            }
        }, options);
    };

    return e("div", { className: "claude-settings" },
        error ? e("div", { className: "claude-error" }, error) : null,
        row("Provider:", e("select", {
            value: form.provider, onChange: (ev) => {
                const provider = ev.target.value;
                // Leaving Mistral clears its URL, so the OpenAI-compatible choice starts empty.
                const baseUrl = provider === "mistral" ? MISTRAL_BASE_URL : form.baseUrl.trim() === MISTRAL_BASE_URL ? "" : form.baseUrl;
                setForm({ ...form, provider, baseUrl });
                dirty();
            }
        },
            e("option", { value: "anthropic" }, "Anthropic (Claude)"),
            e("option", { value: "openai-compatible" }, "OpenAI-compatible (OpenAI, Ollama, vLLM)"),
            e("option", { value: "mistral" }, "Mistral")),
            "Save after changing the provider, then refresh the model list."),
        mistral ? row("API base URL:", MISTRAL_BASE_URL,
            "Mistral's API (La Plateforme). Enter a key from console.mistral.ai and choose a model that supports tool calling, for example mistral-large-latest or mistral-medium-latest.") : null,
        openAi && !mistral ? row("API base URL:", e("input", { type: "text", value: form.baseUrl, onChange: set("baseUrl"), placeholder: "https://api.openai.com/v1" }),
            "For example https://api.openai.com/v1, http://ollama-host:11434/v1 (Ollama) or http://vllm-host:8000/v1 (vLLM). The OIE server must reach it, and the model must support tool calling. The API key may stay empty for a local server.") : null,
        row("API key:", e("input", { type: "password", value: form.apiKey, onChange: set("apiKey"), autoComplete: "off" }),
            status.apiKeySet ? "Set: " + status.apiKeyHint : "No API key set yet."),
        row("Model:", e("span", { className: "claude-inline" },
            modelField(),
            e("button", { className: "btn", disabled: models.state === "loading", onClick: () => loadModels(true) }, "Refresh list")), modelHint),
        row("Connection:", e("span", { className: "claude-inline" },
            e("button", { className: "btn", disabled: test.state === "loading", onClick: testConnection }, "Test connection"),
            e("span", { className: "claude-hint" }, "Tests the saved settings. Only a fixed test prompt is sent, no OIE data.")),
            test.state === "idle" ? null : e("div", { className: "claude-test" },
                test.state === "loading" ? "Testing the connection…"
                    : test.state === "error" ? e("span", { className: "failed" }, test.message)
                        : (test.data.checks || []).map((c) => e("div", { key: c.id },
                            e("span", { className: c.status }, TEST_MARKS[c.status] || "–"), " ", e("b", null, c.label + ":"), " ", c.message)))),
        row("Effort:", e("select", { value: form.effort, onChange: set("effort") }, EFFORTS.map((x) => e("option", { key: x, value: x }, x)))),
        row("Response language:", e("select", { value: form.responseLanguage, onChange: set("responseLanguage") }, LANGUAGES.map(([value, label]) => e("option", { key: value, value }, label)))),
        row("Max. tool calls per question:", e("input", { type: "number", min: 1, max: 100, value: form.maxToolCalls, onChange: set("maxToolCalls"), style: { width: 80 } })),
        row("Review before sending:", e("select", { value: form.reviewBeforeSending, onChange: set("reviewBeforeSending") }, REVIEWS.map(([value, label]) => e("option", { key: value, value }, label))),
            "Shows exactly what will be sent to the AI model, after masking, so each user can edit it or withhold it first."),
        row("Keep conversations (days):", e("input", { type: "number", min: 0, max: 365, value: form.keepConversationsDays, onChange: set("keepConversationsDays"), style: { width: 80 } }),
            "Days a conversation is kept on the server after its last use, so it can be continued after a restart of OIE (Conversations…). Only what was sent to the AI model is kept, so masked. 0 = not saved: conversations are gone 4 hours after their last use or on a restart, and saved ones are removed."),
        row("Extra mask patterns:", e("textarea", { rows: 4, value: form.maskPatterns, onChange: set("maskPatterns") }),
            "Regular expressions, separated by ;; . They are masked in addition to the built-in rules (HL7 PID, NTE and text over 30 characters, GDT 3000-3107, BSN). The API keys are stored encrypted on the server and never sent back to the Administrator. Leave a key field empty to keep the current key."),
        // The Admin API key and spend only apply to Anthropic; the billing button follows the provider.
        billing || !openAi ? e("h3", null, "Usage") : null,
        openAi ? null : row("Admin API key (optional):", e("input", { type: "password", value: form.adminApiKey, onChange: set("adminApiKey"), autoComplete: "off" }),
            e("span", { className: "claude-inline" },
                status.adminApiKeySet ? "Set: " + status.adminApiKeyHint : "No Admin API key set.",
                status.adminApiKeySet ? e("label", null, e("input", { type: "checkbox", checked: form.clearAdminApiKey, onChange: set("clearAdminApiKey") }), " Remove the Admin API key") : null)),
        ...(openAi ? [] : spendLines),
        billing ? row("Credits:", e("a", { className: "btn", href: billing, target: "_blank", rel: "noopener noreferrer" }, openAi ? "Open Billing at OpenAI" : "Open Billing in Console")) : null,
        openAi ? null : row("", null, "Spend comes from the Anthropic Cost API and needs an Admin API key (sk-ant-admin…, created by an organization admin in the Console). It is in USD, since the 1st of the month (UTC), and runs up to about 5 minutes behind. The credit balance is not available through the API; the button opens the Console's billing page."));
}

// ------------------------------------------------------------------ registration

/** Lets the browser download text as a file. */
function download(text, fileName) {
    const blob = new Blob([text], { type: "text/markdown;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = fileName;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** A file name for a Markdown document: its first level-1 heading, else the fallback (as Markdown.fileName in Swing). */
function markdownFileName(markdown, fallback) {
    const m = /^#\s+(.+?)[\s#]*$/m.exec(markdown || "");
    if (!m) return fallback;
    // Drop Markdown emphasis, turn characters Windows forbids into " - ", and trim dots and dashes.
    let name = m[1].replace(/\*\*|__|[*`]/g, "").replace(/\s*[\\/:?"<>|]+\s*/g, " - ").replace(/\s+/g, " ");
    name = name.substring(0, 100).replace(/^[\s.\-]+|[\s.\-]+$/g, "");
    return name ? name + ".md" : fallback;
}

function openWith(context, suggestion) {
    chat.open(context, suggestion);
    P.router.navigate("/claude");
}

// ------------------------------------------------------------------ Explain with AI Assistant (script editors)

/** The question: what to explain, then the code itself. Same text as ExplainCodeMenu.request in Swing. */
function explainCodeRequest(location, code, selection, firstLine, lastLine) {
    const text = (code || "").trim();
    const lines = firstLine <= 0 ? "the selected code" : firstLine === lastLine ? `line ${firstLine}` : `lines ${firstLine}-${lastLine}`;
    const what = selection ? `${lines} of the ${location}` : `the whole ${location}`;
    const clipped = text.length > MAX_CODE_CHARS
        ? text.substring(0, MAX_CODE_CHARS) + `\n// ... ${text.length - MAX_CODE_CHARS} more characters left out`
        : text;
    return `Explain this code (${what}): ${EXPLAIN_CODE_REQUEST}\n\n\`\`\`javascript\n${clipped || "// (empty)"}\n\`\`\``;
}

/** The labels of the tabs around an editor, outermost first, e.g. ["Scripts", "Deploy"]. */
function tabLabels(node) {
    const labels = [];
    for (let el = node; el; el = el.parentElement) {
        if (el.getAttribute && el.getAttribute("role") === "tabpanel") {
            const tab = el.getAttribute("aria-labelledby") && document.getElementById(el.getAttribute("aria-labelledby"));
            const label = tab && tab.textContent.trim();
            if (label) labels.unshift(label);
        }
    }
    return labels;
}

/** "#2 'Map PID'" for the selected row of the filter or transformer table, or null. */
function selectedElement() {
    const row = document.querySelector("tr.selected[data-path]");
    if (!row) return null;
    const num = row.querySelector("td.num");
    const name = row.querySelector("input.grid-name");
    const label = name && (name.value || name.placeholder);
    return `${num ? "#" + num.textContent.trim() + " " : ""}${label ? "'" + label + "'" : ""}`.trim() || null;
}

/** Where an editor is: { context, location }, or null where the item does not apply. */
function explainTarget(editor) {
    const path = window.location.pathname;
    const node = editor.getDomNode && editor.getDomNode();
    const tabs = node ? tabLabels(node) : [];
    const channelRoute = path.match(/\/channels\/([^/]+)\/(edit|filter|transformer|response)(?:\/(\d+))?/);
    if (channelRoute) {
        if (!mayUse("claudeChannelEdit")) return null;
        const channel = (P.store && P.store.getState && P.store.getState("editingChannel")) || {};
        const id = channel.id || decodeURIComponent(channelRoute[1]);
        const context = `Channel open in the editor: ${channel.name ? "'" + channel.name + "' " : ""}(${id}${channel.revision != null ? ", revision " + channel.revision : ""}). `
            + "The AI Assistant sees the saved version; unsaved changes in the editor are not visible.";
        if (channelRoute[2] === "edit") {
            return { context, location: `${tabs.length ? "'" + tabs.join(" > ") + "' " : ""}script of the channel` };
        }
        const kind = { filter: "filter rule", transformer: "transformer step", response: "response transformer step" }[channelRoute[2]];
        const element = selectedElement();
        const connector = channelRoute[3] == null ? "" : ` of connector ${channelRoute[3]}${channelRoute[3] === "0" ? " (source)" : ""}`;
        return { context, location: `${kind}${element ? " " + element : ""}${connector}` };
    }
    if (/\/code-templates(\/|$)/.test(path)) {
        return mayUse("claudeCodeTemplate") ? { context: "Code template open in the Code Templates view.", location: "code template" } : null;
    }
    if (/\/global-scripts(\/|$)/.test(path)) {
        return mayUse("claudeGlobalScripts") ? { context: GLOBAL_SCRIPTS_CONTEXT, location: `global ${tabs.length ? "'" + tabs.join(" > ") + "' " : ""}script` } : null;
    }
    return null;
}

/** The chat in a dialog over the editor: leaving the editor would ask to save or discard unsaved edits. */
async function openOverEditor(context, question) {
    chat.open(context, question, true);
    try {
        const ui = await import("@oie/web-ui");
        const view = await P.reactView(ExplainDialogView)({ path: "/claude", params: {}, query: {}, meta: {} });
        const body = view && (view.el || (view instanceof Node ? view : null));
        if (!body || typeof ui.modal !== "function") throw new Error("no dialog");
        ui.modal({ title: "AI Assistant", body, size: "wide", onClose: () => { try { view.teardown && view.teardown(); } catch (e) { /* closed */ } } });
    } catch (e) {
        // No dialog support in this web admin: show the chat page instead.
        P.router.navigate("/claude");
    }
}

function ExplainDialogView() {
    return e("div", { className: "claude-panel claude-embedded claude-dialog" }, e(ChatPanel));
}

function explainCode(editor) {
    const target = explainTarget(editor);
    const model = editor.getModel && editor.getModel();
    if (!target || !model) return;
    const range = editor.getSelection();
    const selection = !!range && !range.isEmpty();
    const code = selection ? model.getValueInRange(range) : model.getValue();
    let firstLine = 0, lastLine = 0;
    if (selection) {
        firstLine = range.startLineNumber;
        // A selection that ends at the start of a line does not include that line.
        lastLine = range.endColumn === 1 && range.endLineNumber > range.startLineNumber ? range.endLineNumber - 1 : range.endLineNumber;
    }
    openOverEditor(target.context, explainCodeRequest(target.location, code, selection, firstLine, lastLine));
}

/** Marks per editor whether "Explain with AI Assistant" applies: JavaScript in a place explainTarget knows. */
function watchEditor(editor) {
    if (!editor || editor.__claudeExplain || typeof editor.createContextKey !== "function") return;
    editor.__claudeExplain = true;
    try {
        const applies = editor.createContextKey("claudeAssistantExplain", false);
        const update = () => { try { applies.set(!!explainTarget(editor)); } catch (e) { applies.set(false); } };
        editor.onMouseDown(update);
        editor.onDidFocusEditorText(update);
        // The view may still be settling (route, selected step) when the editor is created.
        setTimeout(update, 0);
    } catch (e) {
        // Never break the host's editor.
    }
}

/**
 * The web admin loads Monaco on first use and exposes it as window.monaco (its own drag and drop
 * finds editors there too). Wait for it without loading it, then add the menu item.
 *
 * The item is registered once with monaco.editor.addEditorAction: the per-editor addAction does
 * nothing in the web admin, because Monaco only allows it with its own keybinding service and the
 * web admin sets up Monaco with another one. A context key per editor decides where it shows.
 */
function hookScriptEditors() {
    const attach = () => {
        const monaco = window.monaco;
        if (!monaco || !monaco.editor || typeof monaco.editor.addEditorAction !== "function" || typeof monaco.editor.onDidCreateEditor !== "function") return false;
        try {
            monaco.editor.addEditorAction({
                id: "claude-assistant.explain-code",
                label: "Explain with AI Assistant",
                precondition: "claudeAssistantExplain && editorLangId == javascript",
                contextMenuGroupId: "navigation",
                contextMenuOrder: 0,
                run: (editor) => explainCode(editor)
            });
            monaco.editor.onDidCreateEditor(watchEditor);
            (typeof monaco.editor.getEditors === "function" ? monaco.editor.getEditors() : []).forEach(watchEditor);
        } catch (e) {
            // Never break the host's editors.
        }
        return true;
    };
    if (attach()) return;
    const timer = setInterval(() => { if (attach()) clearInterval(timer); }, 1000);
}

export function register(platform) {
    P = platform;
    React = platform.React;
    e = React.createElement;

    if (!document.getElementById(STYLE_ID)) {
        const style = document.createElement("style");
        style.id = STYLE_ID;
        style.textContent = CSS;
        document.head.appendChild(style);
    }

    platform.registerIcon("claude", ICON_PATH);

    // task/rbac: the shell hides the entry when an RBAC plugin denies the task; the server publishes
    // these task names with "Use AI Assistant" (ClaudeServletInterface.USE_TASKS).
    platform.registerNavItem({ id: "claude-assistant", label: "AI Assistant", icon: "claude", path: "/claude", section: "Plugins", order: 30, task: "claudeOpen", rbac: "other" });
    platform.registerView("/claude", platform.reactView(ChatView), { title: "AI Assistant" });

    // Channel tabs have no task gate in the shell, so the tab is only registered when allowed.
    if (mayUse("claudeChannelEdit")) {
        platform.registerChannelTab({ id: "claude-assistant", label: "AI Assistant", order: 90, component: ChannelTab });
    }

    platform.registerChannelAction({
        id: "claude-assistant.ask",
        label: "Ask AI Assistant",
        icon: "claude",
        order: 60,
        task: "claudeDashboard",
        onInvoke: (channel) => {
            if (!channel) return;
            openWith(`Selected channel: '${channel.name}' (${channel.id}).`, "Analyze the status and errors of this channel.");
        }
    });

    platform.registerChannelAction({
        id: "claude-assistant.document",
        label: "Document channel",
        icon: "claude",
        order: 61,
        task: "claudeDocumentChannel",
        onInvoke: (channel) => {
            if (!channel) return;
            openWith(`Selected channel: '${channel.name}' (${channel.id}).`, DOCUMENT_SUGGESTION);
        }
    });

    platform.registerChannelAction({
        id: "claude-assistant.describe",
        label: "Write description",
        icon: "claude",
        order: 62,
        task: "claudeDescribeChannel",
        onInvoke: (channel) => {
            if (!channel) return;
            openWith(`Selected channel: '${channel.name}' (${channel.id}).`, DESCRIPTION_SUGGESTION);
        }
    });

    // The web admin has no hook on the Global Scripts view, so these go in the command palette.
    if (typeof platform.registerCommand === "function") {
        platform.registerCommand({ id: "claude-assistant.open", label: "AI Assistant", icon: "claude", section: "Plugins", keywords: ["claude", "ai", "assistant"], path: "/claude", task: "claudeOpen", rbac: "other" });
        platform.registerCommand({
            id: "claude-assistant.queues",
            label: "Check queues and storage",
            icon: "claude",
            section: "Plugins",
            keywords: ["claude", "queue", "queued", "storage", "pruning", "pruner", "disk"],
            task: "claudeQueues",
            rbac: "other",
            run: () => openWith(null, QUEUES_SUGGESTION)
        });
        platform.registerCommand({
            id: "claude-assistant.global-scripts",
            label: "Ask AI Assistant about Global Scripts",
            icon: "claude",
            section: "Plugins",
            keywords: ["claude", "global scripts", "deploy", "preprocessor", "postprocessor"],
            task: "claudeGlobalScripts",
            rbac: "script",
            run: () => openWith(GLOBAL_SCRIPTS_CONTEXT, GLOBAL_SCRIPTS_SUGGESTION)
        });
    }

    platform.registerCodeTemplateAction({
        id: "claude-assistant.ask",
        label: "Ask AI Assistant",
        icon: "claude",
        order: 60,
        task: "claudeCodeTemplate",
        onInvoke: (template, ctx) => {
            if (!template || !template.id) return;
            const library = ctx && ctx.library && ctx.library.name ? ` in library '${ctx.library.name}'` : "";
            openWith(`Selected code template: '${template.name}' (${template.id})${library}. The AI Assistant sees the saved version; unsaved changes in the code template editor are not visible.`,
                "Explain what this code template does, which channels use it, and point out weak spots.");
        }
    });

    // Message actions arrived in @oie API 4.7; on an older web admin the option is simply absent.
    if (typeof platform.registerMessageAction === "function") {
        const messageContext = (message, ctx) => {
            const messageId = message && (message.messageId != null ? message.messageId : message.id);
            if (messageId == null) return null;
            const channelId = (ctx && ctx.channelId) || (message && message.channelId);
            let context = `Selected message in the message browser: channel ID ${channelId}, message ID ${messageId}`;
            if (ctx && ctx.metaDataId != null) context += `, connector (metaDataId) ${ctx.metaDataId}`;
            return context + ".";
        };
        platform.registerMessageAction({
            id: "claude-assistant.ask",
            label: "Ask AI Assistant",
            icon: "claude",
            order: 60,
            task: "claudeMessage",
            onInvoke: (message, ctx) => {
                const context = messageContext(message, ctx);
                if (context) openWith(context, "What happened to this message? If it failed: why, and how do I fix it?");
            }
        });
        platform.registerMessageAction({
            id: "claude-assistant.explain-error",
            label: "Explain error",
            icon: "claude",
            order: 61,
            task: "claudeExplainError",
            onInvoke: (message, ctx) => {
                const context = messageContext(message, ctx);
                if (context) openWith(context, EXPLAIN_ERROR_SUGGESTION);
            }
        });
    }

    // Right-click "Explain with AI Assistant" in the Monaco script editors.
    hookScriptEditors();

    // Hidden by the shell unless "settings_AI Assistant" doRefresh/doSave is allowed ("Manage AI Assistant settings").
    platform.registerSettingsPanel({ label: "AI Assistant", component: SettingsPanel });
}

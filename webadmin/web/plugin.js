// Claude Assistant - web administrator UI (equivalent of the Swing ClaudeClientPlugin and
// ClaudeSettingsPlugin). Talks to the same REST API (/api/extensions/claude/...), so masking,
// permissions, action approval and the audit log behave exactly as in the Swing client.
// Plain ES module against platform.React: no build step.

const EXT = "/extensions/claude";
const STYLE_ID = "claude-assistant-style";
const POLL_MS = 800;
const BILLING_URL = "https://platform.claude.com/settings/billing";
const MODELS = ["claude-opus-5", "claude-opus-5-5", "claude-sonnet-5", "claude-fable-5-1", "claude-haiku-4-5"];
const EFFORTS = ["low", "medium", "high", "xhigh", "max"];
// Stored value (English name, used in the system prompt) and the label shown in the dropdown.
const LANGUAGES = [
    ["Automatic", "Automatic (language of the question)"], ["English", "English"], ["Dutch", "Nederlands (Dutch)"], ["German", "Deutsch (German)"],
    ["French", "Français (French)"], ["Spanish", "Español (Spanish)"], ["Italian", "Italiano (Italian)"], ["Portuguese", "Português (Portuguese)"],
    ["Polish", "Polski (Polish)"], ["Swedish", "Svenska (Swedish)"], ["Danish", "Dansk (Danish)"], ["Norwegian", "Norsk (Norwegian)"], ["Finnish", "Suomi (Finnish)"]
];
// Stroke-only 24x24 path, same format as the built-in icons: an eight-point burst.
const ICON_PATH = "M12 3v5M12 16v5M3 12h5M16 12h5M5.6 5.6l3.5 3.5M14.9 14.9l3.5 3.5M5.6 18.4l3.5-3.5M14.9 9.1l3.5-3.5";

const CSS = `
.claude-page { display: flex; flex-direction: column; height: 100%; min-height: 0; padding: 12px 16px; box-sizing: border-box; gap: 8px; }
.claude-panel { display: flex; flex-direction: column; flex: 1; min-height: 0; gap: 8px; }
.claude-embedded { height: 100%; min-height: 420px; padding: 8px 0; box-sizing: border-box; }
.claude-contextbar { display: flex; align-items: center; gap: 8px; color: var(--text-dim); font-size: 12px; }
.claude-contextbar .claude-context { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.claude-transcript { flex: 1; min-height: 160px; overflow: auto; background: var(--bg1); border: 1px solid var(--line); border-radius: var(--radius); padding: 10px 12px; }
.claude-transcript > div { margin: 6px 0; }
.claude-user { background: var(--bg3); border-radius: var(--radius); padding: 6px 10px; white-space: pre-wrap; margin-top: 14px !important; }
.claude-text { color: var(--text); line-height: 1.5; }
.claude-text p { margin: 6px 0; }
.claude-text h2, .claude-text h3, .claude-text h4, .claude-text h5 { margin: 12px 0 6px; }
.claude-text pre { font-family: var(--font-mono); font-size: 12px; background: var(--bg2); border: 1px solid var(--line); border-radius: var(--radius); padding: 8px; overflow: auto; }
.claude-text code { font-family: var(--font-mono); font-size: 12px; background: var(--bg2); padding: 0 3px; border-radius: 2px; }
.claude-text pre code { background: none; padding: 0; }
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
.claude-settings { padding: 12px 16px; max-width: 760px; }
.claude-settings .claude-row { display: grid; grid-template-columns: 220px 1fr; gap: 10px; align-items: center; margin: 8px 0; }
.claude-settings .claude-row > label { text-align: right; color: var(--text-dim); }
.claude-settings input[type=password], .claude-settings input[type=text], .claude-settings input[type=number], .claude-settings select, .claude-settings textarea {
  font: inherit; color: var(--text); background: var(--bg1); border: 1px solid var(--line-strong); border-radius: var(--radius); padding: 4px 6px; box-sizing: border-box; }
.claude-settings input[type=password], .claude-settings input[type=text], .claude-settings textarea { width: 100%; }
.claude-settings .claude-hint { color: var(--text-faint); font-size: 12px; }
.claude-settings h3 { margin: 18px 0 4px; padding-top: 12px; border-top: 1px solid var(--line); }
.claude-settings .claude-spend { font-weight: 600; }
.claude-settings .claude-inline { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
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

function markdown(md) {
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
            out += "<pre><code>" + esc(code.join("\n")) + "</code></pre>";
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
// the Claude page, the channel tab and other views while Claude is still working.

const chat = {
    conversationId: null,
    context: null,
    suggestion: null,
    entries: [],
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
    open(context, suggestion) {
        this.context = context;
        if (suggestion) this.suggestion = suggestion;
        this.notify();
    },
    clearContext() {
        this.context = null;
        this.notify();
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
            this.setStatus("Claude is thinking…");
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
            if (ev.type === "tool") this.status = "Claude is querying the server…";
            this.entries = [...this.entries, { kind: ev.type, text: ev.text }];
        }
        const pending = job.pendingAction;
        if (job.state === "WAITING" && pending && pending.id !== this.shownActionId) {
            this.shownActionId = pending.id;
            this.status = "Waiting for your approval…";
            this.askApproval(pending);
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
        this.notify();
    },

    askApproval(pending) {
        const jobId = this.jobId;
        const { h, modal } = P.ui;
        let decided = false;
        const decide = async (approved) => {
            if (decided) return true;
            decided = true;
            this.setStatus(approved ? "Running action…" : "Claude continues…");
            try {
                await call("POST", "/jobs/" + encodeURIComponent(jobId) + "/confirm", { actionId: pending.id, approved });
                this.setStatus("Claude continues…");
            } catch (err) {
                this.add("error", "Approval failed: " + errorText(err));
            }
            return true;
        };
        modal({
            title: "Approve: " + pending.title,
            size: "lg",
            body: h("div", null,
                h("div", null, "Claude wants to run the following action on this server:"),
                h("pre.claude-detail", null, pending.detail || "")),
            buttons: [
                { label: "Reject", onClick: () => decide(false) },
                { label: "Run", danger: true, onClick: () => decide(true) }
            ],
            // Closing the dialog (X, Escape, clicking outside) counts as rejecting, as in Swing.
            onClose: () => { decide(false); }
        });
    }
};

// ------------------------------------------------------------------ React components

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
            return e("div", { className: "claude-text", dangerouslySetInnerHTML: { __html: markdown(entry.text) } });
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
            if (!input.trim() || input === lastSuggestion.current) {
                setInput(c.suggestion);
                lastSuggestion.current = c.suggestion;
                setTimeout(() => inputRef.current && inputRef.current.select(), 0);
            }
            c.suggestion = null;
        }
    });

    React.useEffect(() => {
        const el = transcriptRef.current;
        if (el) el.scrollTop = el.scrollHeight;
    }, [c.entries.length]);

    const send = () => {
        const text = input.trim();
        if (!text || c.busy) return;
        setInput("");
        c.send(text);
    };

    const welcome = c.entries.length === 0
        ? e("div", { className: "claude-info" },
            "Ask a question about your OIE server, for example ", e("i", null, "Which channels have errors?"), " or ", e("i", null, "Why does this message fail?"),
            e("br"), "Message content is masked before it is sent to Claude. Actions such as deploying only run after you approve them.")
        : null;

    return e("div", { className: "claude-panel" },
        e("div", { className: "claude-contextbar" },
            e("span", { className: "claude-context", title: c.context || "" },
                c.context ? [e("b", { key: "l" }, "Context: "), c.context] : "No context: Claude looks at the whole server."),
            e("button", { className: "btn", disabled: !c.context, onClick: () => c.clearContext() }, "Clear context"),
            e("button", { className: "btn", onClick: () => c.newConversation() }, "New conversation")),
        e("div", { className: "claude-transcript", ref: transcriptRef },
            welcome,
            c.entries.map((entry, i) => e(Entry, { key: i, entry }))),
        e("div", { className: "claude-input" },
            e("textarea", {
                ref: inputRef,
                value: input,
                placeholder: "Ask Claude… (Ctrl+Enter to send)",
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

function ChatView() {
    return e("div", { className: "claude-page" }, e(ChatPanel));
}

/** "Claude" tab in the channel editor: the chat, with this channel as context. */
function ChannelTab(props) {
    const channel = props.channel || (props.ctx && props.ctx.channel) || {};
    React.useEffect(() => {
        if (!channel.id) return;
        const context = `Channel open in the editor: '${channel.name}' (${channel.id}, revision ${channel.revision}). Claude sees the saved version; unsaved changes in the editor are not visible.`;
        if (chat.context !== context) chat.open(context, "Explain what this channel does and point out weak spots in its scripts.");
    }, [channel.id]);
    return e("div", { className: "claude-panel claude-embedded" }, e(ChatPanel));
}

/**
 * Settings > Claude Assistant. Hooks into the settings shell like the built-in tabs: setSave for
 * the shell's Save (and its unsaved-changes prompt), markDirty/markClean, and a task pane.
 */
function SettingsPanel({ setTasks, setSave, markDirty, markClean }) {
    const [form, setForm] = React.useState(null);
    const [status, setStatus] = React.useState({});
    const [spend, setSpend] = React.useState({ state: "idle" });
    const [error, setError] = React.useState(null);
    const formRef = React.useRef(null);
    formRef.current = form;
    const dirty = () => markDirty && markDirty();
    const clean = () => markClean && markClean();

    const loadSpend = React.useCallback(async (refresh) => {
        setSpend({ state: "loading" });
        try {
            setSpend({ state: "ok", data: await call("GET", "/spend", null, refresh ? { refresh: true } : undefined) });
        } catch (err) {
            setSpend({ state: "error", message: errorText(err) });
        }
    }, []);

    const show = React.useCallback((s) => {
        setStatus(s);
        setForm({ apiKey: "", adminApiKey: "", clearAdminApiKey: false, model: s.model || MODELS[0], effort: s.effort || "high", maxToolCalls: s.maxToolCalls || 25, maskPatterns: s.maskPatterns || "", responseLanguage: s.responseLanguage || "Automatic" });
        clean();
        if (s.adminApiKeySet) loadSpend(false);
        else setSpend({ state: "idle" });
    }, [loadSpend]);

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
        try {
            show(await call("PUT", "/settings", { ...f, maxToolCalls: Number(f.maxToolCalls) || 25 }));
            setError(null);
            P.ui.toast("Claude Assistant settings saved.", "success");
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
            setTasks("Claude Assistant Tasks", [
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
    const row = (label, control, hint) => e("div", { className: "claude-row" }, e("label", null, label), e("div", null, control, hint ? e("div", { className: "claude-hint" }, hint) : null));

    let spendLines;
    if (!status.adminApiKeySet) {
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
            d.workspaceUsd != null ? row("", `of which workspace '${d.workspaceName}' (the plugin's API key): $${d.workspaceUsd}`) : null
        ];
    }

    return e("div", { className: "claude-settings" },
        error ? e("div", { className: "claude-error" }, error) : null,
        row("Anthropic API key:", e("input", { type: "password", value: form.apiKey, onChange: set("apiKey"), autoComplete: "off" }),
            status.apiKeySet ? "Set: " + status.apiKeyHint : "No API key set yet."),
        row("Model:", e("span", null,
            e("input", { type: "text", list: "claude-models", value: form.model, onChange: set("model"), style: { width: 260 } }),
            e("datalist", { id: "claude-models" }, MODELS.map((m) => e("option", { key: m, value: m }))))),
        row("Effort:", e("select", { value: form.effort, onChange: set("effort") }, EFFORTS.map((x) => e("option", { key: x, value: x }, x)))),
        row("Response language:", e("select", { value: form.responseLanguage, onChange: set("responseLanguage") }, LANGUAGES.map(([value, label]) => e("option", { key: value, value }, label)))),
        row("Max. tool calls per question:", e("input", { type: "number", min: 1, max: 100, value: form.maxToolCalls, onChange: set("maxToolCalls"), style: { width: 80 } })),
        row("Extra mask patterns:", e("textarea", { rows: 4, value: form.maskPatterns, onChange: set("maskPatterns") }),
            "Regular expressions, separated by ;; . They are masked in addition to the built-in rules (HL7 PID, GDT 3000-3107, BSN). The API keys are stored encrypted on the server and never sent back to the Administrator. Leave a key field empty to keep the current key."),
        e("h3", null, "Usage"),
        row("Admin API key (optional):", e("input", { type: "password", value: form.adminApiKey, onChange: set("adminApiKey"), autoComplete: "off" }),
            e("span", { className: "claude-inline" },
                status.adminApiKeySet ? "Set: " + status.adminApiKeyHint : "No Admin API key set.",
                status.adminApiKeySet ? e("label", null, e("input", { type: "checkbox", checked: form.clearAdminApiKey, onChange: set("clearAdminApiKey") }), " Remove the Admin API key") : null)),
        ...spendLines,
        row("Credits:", e("a", { className: "btn", href: BILLING_URL, target: "_blank", rel: "noopener noreferrer" }, "Open Billing in Console")),
        row("", null, "Spend comes from the Anthropic Cost API and needs an Admin API key (sk-ant-admin…, created by an organization admin in the Console). It is in USD, since the 1st of the month (UTC), and runs up to about 5 minutes behind. The credit balance is not available through the API; the button opens the Console's billing page."));
}

// ------------------------------------------------------------------ registration

function openWith(context, suggestion) {
    chat.open(context, suggestion);
    P.router.navigate("/claude");
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

    platform.registerNavItem({ id: "claude-assistant", label: "Claude Assistant", icon: "claude", path: "/claude", section: "Plugins", order: 30 });
    platform.registerView("/claude", platform.reactView(ChatView), { title: "Claude Assistant" });

    platform.registerChannelTab({ id: "claude-assistant", label: "Claude", order: 90, component: ChannelTab });

    platform.registerChannelAction({
        id: "claude-assistant.ask",
        label: "Ask Claude",
        icon: "claude",
        order: 60,
        onInvoke: (channel) => {
            if (!channel) return;
            openWith(`Selected channel: '${channel.name}' (${channel.id}).`, "Analyze the status and errors of this channel.");
        }
    });

    // Message actions arrived in @oie API 4.7; on an older web admin the option is simply absent.
    if (typeof platform.registerMessageAction === "function") {
        platform.registerMessageAction({
            id: "claude-assistant.ask",
            label: "Ask Claude",
            icon: "claude",
            order: 60,
            onInvoke: (message, ctx) => {
                const messageId = message && (message.messageId != null ? message.messageId : message.id);
                if (messageId == null) return;
                const channelId = (ctx && ctx.channelId) || (message && message.channelId);
                let context = `Selected message in the message browser: channel ID ${channelId}, message ID ${messageId}`;
                if (ctx && ctx.metaDataId != null) context += `, connector (metaDataId) ${ctx.metaDataId}`;
                openWith(context + ".", "What happened to this message? If it failed: why, and how do I fix it?");
            }
        });
    }

    platform.registerSettingsPanel({ label: "Claude Assistant", component: SettingsPanel });
}

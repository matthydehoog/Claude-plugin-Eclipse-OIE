# AI Assistant for Open Integration Engine

<img width="1469" height="780" alt="image" src="https://github.com/user-attachments/assets/e1e8a424-7fa6-4dc2-87d9-90db967c58db" />

An extension for the OIE Administrator that lets you talk to an AI model about your server from inside
the GUI: channels, messages, errors, scripts, code templates and server status. It works with Claude
(Anthropic), OpenAI, Mistral or a local model behind an OpenAI-compatible API. The assistant looks the
facts up itself through the same tools as `oie-mcp-server`, and can propose actions that only run
after you approve them.

**New here?** Read the [User Manual](docs/USER_MANUAL.md) for installation, setup, daily use and
troubleshooting.

**Wiki:** the [project wiki](https://github.com/matthydehoog/Claude-plugin-Eclipse-OIE/wiki) has the same
information in short pages per topic: setup, where to find the assistant, features, approvals, privacy,
permissions, costs and troubleshooting.

**Training:** the online course
[AI Assistant for Eclipse OIE](https://eclipse-oie.moodiy.cloud/course/view.php?id=3) at the
Academy for Eclipse OIE covers the same topics, with quizzes and hands-on exercises on a test channel.

<img width="1200" height="675" alt="course-claude-assistant-card" src="https://github.com/user-attachments/assets/019a959c-af9c-40f1-8b20-000b6ad887f2" />


> **Unofficial plugin.** This is a community-built plugin. It is not affiliated with, endorsed by or
> supported by Anthropic. Claude and Anthropic are trademarks of Anthropic, PBC.
>
> **Your responsibility.** Each organization that installs the plugin is responsible for its own use
> of it: the legal basis and the data processing agreement with Anthropic or the other AI provider for any patient data it
> sends, and the changes its users approve in OIE. The plugin is provided as is, without warranty
> (see the [Mozilla Public License 2.0](LICENSE)).

## Where to find the AI Assistant in the Administrator

| Place | Task | Context sent along |
|---|---|---|
| Left menu, **Other** | *AI Assistant* | none: questions about the whole server |
| **Dashboard** (task + right-click) | *Ask AI Assistant* | selected channel(s)/connectors |
| **Dashboard** (task + right-click) | *Check channels* | selected channels, or all; the assistant checks them for weak spots |
| **Dashboard** (task + right-click) | *Check queues and storage* | none: the assistant looks for stuck queues and channels that need pruning |
| **Message browser** (task + right-click) | *Ask AI Assistant* | selected message (channel, message ID, connector) |
| **Message browser** (task + right-click) | *Explain error* | selected message; a diagnosis: root cause, where, which data, fix |
| **Channel editor** (task + right-click) | *Ask AI Assistant* | the channel that is open (saved version) |
| **Channel editor** (task + right-click) | *Document channel* | the open channel; the assistant writes its documentation as Markdown |
| **Channel editor** (task + right-click) | *Check channel* | the open channel; the assistant checks it for weak spots |
| **Channel editor** (task + right-click) | *Write description* | the open channel; the assistant proposes a new Description, saved after your approval |
| **Code Templates** (task + right-click, one template selected) | *Ask AI Assistant* | the selected code template and its library |
| **Global Scripts** (task + right-click) | *Ask AI Assistant* | the global deploy/undeploy/pre/postprocessor scripts |
| **JavaScript editors** (right-click in the code) | *Explain with AI Assistant* | the channel, template or global scripts; explains the selected code (unsaved edits included), or the whole step or script |

The chat window stays open next to the Administrator. Ctrl+Enter sends; *New conversation* starts
over; *Stop* aborts a running question. *Export…* saves the conversation as a Markdown file, *Save answer…* only
the assistant's last answer (for example a channel's documentation), and *Conversations…* continues one of your earlier conversations.
*Copy* on a code block in an answer copies it exactly, without the Markdown fences (Swing and web).

### Web administrator

The extension also ships a UI for the OIE web administrator (`oie-webadmin`). It needs the
**Web Support** (`websupport`) extension on the engine, which serves the plugin's `webadmin/` folder.

<img width="256" height="160" alt="image" src="https://github.com/user-attachments/assets/4cb66688-4c6f-4b06-9a63-c85ed1057d37" />


| Place | What |
|---|---|
| Left rail, **Plugins > AI Assistant** | the chat as a full page |
| **Channel editor**, tab **AI Assistant** | the chat with the open channel as context |
| **Channels** view, right-click | *Ask AI Assistant* about the selected channel, *Document channel*, *Check channel* or *Write description* |
| **Dashboard**, tab **AI Assistant** | *Ask AI Assistant* about the selected channels or connector, *Check channels*, or *Check queues and storage* (the web admin has no hook on the Dashboard's tasks or menu) |
| **Message browser**, right-click | *Ask AI Assistant* about the selected message, or *Explain error* (web admin API 4.7+) |
| **Code Templates**, right-click | *Ask AI Assistant* about the selected code template |
| **Script editors**, right-click in the code | *Explain with AI Assistant*: the selected code (unsaved edits included), in a dialog over the editor |
| **AI Assistant page**, button **Global Scripts** | the global scripts as context (the web admin has no hook on the Global Scripts view itself) |
| **AI Assistant page**, button **Document channel** | documents the channel in the context |
| **AI Assistant page**, button **Write description** | proposes a Description for the channel in the context |
| **AI Assistant page**, button **Queues and storage** | checks queues and message storage |
| **Command palette** (Ctrl+K) | *AI Assistant*, *Check queues and storage*, *Check all channels for weak spots*, and *Ask AI Assistant about Global Scripts* |
| **Settings**, tab **AI Assistant** | the same settings and usage as in Swing, using the settings page's own Save |

Both UIs use the same REST API, so masking, permissions, action approval and the audit log are
identical; a conversation stays open while you move between the AI Assistant page and other views.

## What the assistant can do

**Read** (immediately): server info, channels with status and counters, channel configuration and
scripts, the global scripts, statistics per connector, queues and message storage, searching and viewing messages, events, the server log, code
templates and the keys of the Configuration Map (never the values). *Find usages* shows, in one search over all
channels, code templates and global scripts, where a Configuration Map key, a map variable, a code template function
or a channel is used: only the locations, never code or values.

**Channel documentation:** *Document channel* has the assistant write a Markdown description of a channel
(purpose, source, filter, destinations, field mappings, scripts, code templates, Configuration Map keys
and open points) from a readable overview in which passwords and other secrets are hidden. Save it as
a separate file with *Save answer*.

**Weak spots:** *Check channel* and *Check channels* have the assistant check one channel, the selected ones or all
of them in one go for common mistakes: credentials typed into scripts or settings (reported by location, never by
value), calls to code templates whose library is not linked, network destinations without a queue, Development
storage or pruning switched off, logging in scripts that run for every message, and disabled destinations, rules
and steps.

**Queues and storage:** *Check queues and storage* has the assistant look for slow-burning problems: destinations
that are stopped or have not moved their queue for a long time (with the age of the oldest queued message
and the last error), channels without pruning and how many messages they keep, and the database size.
It reads counts, dates and settings only, never message content.

**Actions** (only after clicking *Run* in an approval dialog): deploy/undeploy a channel,
start/stop/pause/resume, reset statistics, reprocess a message, send a message to a channel and
change a JavaScript script (deploy/undeploy/pre/postprocessor, filter rule or transformer step) or a
global script, change the filter rules or transformer steps of a connector (any type: Mapper, Message
Builder, Rule Builder, Iterator, XSLT, ...), write a channel's Description, and create a new channel (or a changed copy of one).
A script, filter, transformer or description change saves the channel with a new revision but does not deploy it;
if the channel changed in the meantime, nothing is saved. The approval dialog opens on a diff of the
change (added lines green, removed lines red, unchanged code folded); *Full details* shows the current and new
script, or the current and new rules or steps and the script OIE generates for each new one. A new channel is created disabled
and undeployed, with credentials left empty for you to fill in. Every executed action is written to the audit log as
event `AI Assistant: …`, in the name of the user who approved it. Before the dialog opens, every
proposed script is compiled with OIE's own Rhino and language version (nothing is run). A script that
does not compile goes back to the assistant once to be corrected; if it still fails, the dialog shows a
warning with the compile error.

## Privacy

Everything sent to the AI provider (your questions, context, message content, logs, errors,
channel configuration) first goes through a masker with the same rules as `mask.ts` in
oie-mcp-server:

- HL7 v2 PID fields 2-7, 9, 11, 13, 14, 18-21, 23, 29 and PV1 fields 7-9, 17, 19, 50 (doctors, visit
  number); whole NK1, GT1, IN1, IN2 and MRG segments except the set ID (ER7 and XML)
- FHIR resources in JSON and XML: in Patient, RelatedPerson, Person and Practitioner the name,
  identifier, telecom, address, birthDate, photo, contact and personal extensions (e.g. mother's
  maiden name); in every resource identifier values, reference displays, the narrative (`text.div`)
  and XML comments. Structure, codes and other resources stay readable
- HL7 v2 NTE comments (NTE-3 onwards) and every HL7 text value longer than 30 characters, such as
  free-text notes or a base64 PDF in OBX-5; shorter coded values stay readable (ER7 and XML)
- GDT patient fields 3000-3107 (raw and XML from the GDT data type plugin)
- EDIFACT health messages, raw and XML from the EDIFACT data type plugin: in MEDLAB the patient
  (PID, PAD), the doctors (ART, ARA, KOP) and the sample number (IDE); in other MED* messages PID,
  NAD, PNA, ADR, COM, the date of birth and the FTX text; long values in both (not in ZKH, AFD). Tags, qualifiers and
  results (BEP) stay readable; trade messages (ORDERS, INVOIC) are not masked
- 9-digit numbers that pass the eleven-test (Dutch BSN)
- credentials in channel configuration: elements named like password, passphrase, secret, token or apiKey
- your own patterns under *Settings > AI Assistant*

**Review before sending.** Before message content, log lines or events go to the AI model, a dialog shows
exactly what would be sent, after masking. You can edit the text, send it (*Send to the AI model*) or
withhold it (*Don't send*, or close the dialog); edited text is masked again. *Settings > AI
Assistant > Review before sending* sets the scope: *Message content, logs and events* (default),
*Everything, including my question*, or *Off*.

This is a safety net, not anonymisation. Only send real patient data through it if you have a legal
basis and a data processing agreement with the AI provider. Conversations are only visible to the user who
started them. By default they live only in the server's memory (cleared 4 hours after last use or on a
restart); with *Keep conversations* (days) set, they are also saved in the OIE application data folder
(`claude-assistant/conversations/`), masked exactly as sent to the AI model, so they can be continued after
a restart. Setting it back to 0 removes the saved ones.

## Permissions

The extension adds three permissions (visible in the RBAC extension):

- **Use AI Assistant**: chat (read only)
- **Run AI Assistant actions**: approve proposed actions
- **Manage AI Assistant settings**: change the API key, model and mask patterns, test the connection

Before 2.0.0 they were called *... Claude Assistant ...*. An access-control extension sees the new names as new permissions: after upgrading from 1.x, grant them once to the roles that had the old ones. The names stay the same from 2.0.0 on.

The assistant only gets what the user could see or do in OIE: every tool checks the user's own OIE
permission (via the RBAC extension when installed), e.g. *View Messages* for message search. Tools
the user lacks are skipped and the model is told which permission is missing; an action is checked
again when it is approved. Users with channel restrictions cannot use the assistant, because the
tools see all channels.

Every time message data, log lines or events are sent to the AI provider, the audit log gets an event
`AI Assistant: data sent to Anthropic` (or to the host of the OpenAI-compatible server) with the user, tool, channel, message IDs and whether it
was reviewed. The content itself is never logged.

## Setup

1. Install `claude-assistant-<version>.zip` via *Settings > Extensions > Install Extension* (or unzip
   it into `<OIE_HOME>/extensions/`) and restart the OIE service. Check that
   `extensions/claude-assistant/lib/` contains the engine jars.
2. Go to *Settings > AI Assistant*. For Claude, enter an Anthropic API key (from
   [console.anthropic.com](https://console.anthropic.com)) and click *Save*. The key is stored
   encrypted and never sent back to the Administrator; the field is emptied after saving and the
   line below it shows *Set: sk-ant-…xxxx*. Then click *Test connection*: it checks that the provider
   is reachable, accepts the key and that the model supports tool calling, with a fixed test prompt
   and no OIE data.
   
   <img width="1060" height="1092" alt="image" src="https://github.com/user-attachments/assets/c66901a0-16f4-4f53-ae35-5b3030cc1d5a" />

4. Defaults: model `claude-opus-5`, effort `high`, at most 25 tool calls per question, response
   language *Automatic* (the assistant answers in the language of the question). The **Model** list comes
   from Anthropic's Models API for your key (newest first, refreshed daily), so new models appear
   without a plugin update; without a connection a fixed list is shown. Pick a fixed language in
   **Response language** to have the assistant always answer in that language, whatever language the
   question or the server data is in.

### Other models: OpenAI-compatible providers

Instead of Claude, the assistant can use a model behind an OpenAI-compatible Chat Completions or Responses API:
OpenAI itself, or a local server such as Ollama or vLLM. Set **Provider** to *OpenAI-compatible*,
enter the **API base URL** (for example `https://api.openai.com/v1`, `http://ollama-host:11434/v1`
or `http://vllm-host:8000/v1`) and, if the server needs one, the **API key**. Save, then pick a
model with *Refresh list*. The model must support tool calling, and the OIE server must reach the
URL.

Choose **API mode** to match your server and model: *Chat Completions* (the default)
or *Responses*. For GPT-6.2 Sol, select *Responses*. Save before using *Test connection*;
the chat and connection test use the same saved mode. Existing settings without an API mode
continue to use Chat Completions. Changing the mode starts a new model context for existing
conversations. Responses requests use `store: false`.

For Mistral, choose *Mistral* as the **Provider**: it uses Mistral's API at
`https://api.mistral.ai/v1`, so only an API key from console.mistral.ai is needed. Choose a model
that supports tool calling, such as `mistral-large-latest` or `mistral-medium-latest`.

Masking, review before sending, approval of actions and permissions work exactly as with Claude;
the masked data then goes to that provider instead of Anthropic. Effort and spend this month only
apply to Anthropic, and answers appear at once instead of streaming. Contributed by Miguel Duarte
([@SoulOnFire](https://github.com/SoulOnFire)).

### Usage: spend and credits

The *Usage* section of *Settings > AI Assistant* shows **Spend this month** for the whole
organization and, separately, for the workspace the plugin's API key belongs to. It comes from the
Anthropic [Cost API](https://platform.claude.com/docs/en/manage-claude/usage-cost-api) and needs an
optional **Admin API key** (`sk-ant-admin01-…`), which only an organization admin can create in the
Console (not available for individual accounts). Amounts are in USD since the 1st of the month (UTC),
run up to about 5 minutes behind and are cached for a minute; *Refresh* fetches them again.
**Tokens this month** (from the Usage API, same key) shows the plugin key's input, prompt-cache reads
and writes, and output, so you can see how much of the input the cache serves.
Viewing spend requires the *Manage AI Assistant settings* permission.

The organization's **credit balance** is not available through any Anthropic API, so the tab offers
an *Open Billing in Console* button instead.

### Without internet

Only the assistant stops working; channels, message processing and both Administrators are not
affected, and the plugin makes no connections at startup or in the background. A question then ends
with *Cannot reach the Anthropic API from the OIE server (…)*, naming the cause (for example
`UnknownHostException` or `Connect timed out`); the conversation is kept, so you can ask again once
the connection is back. Connecting times out after 10 seconds and is tried three times, so even a
firewall that silently drops packets gives the message within about half a minute. Answers
themselves may still take up to 10 minutes.

The OIE server must be able to reach `https://api.anthropic.com`. Behind a gateway or proxy? Add for
example `-Doie.claude.baseUrl=https://gateway.example/anthropic` to `conf/custom.vmoptions`.

## Building

JDK 11+ and Maven; the engine jars are taken from an OIE installation (`-Doie.home=...`, default
`C:/Program Files/OpenIntegrationEngine`).

```bash
mvn package
```

Result: `target/claude-assistant-<version>.zip`.

See the [development guide](docs/development.md) for the engine jars to install, the architecture (the isolated engine, tools, approval, masking, permissions), testing and releasing.

### Structure

- `shared` – the REST interface (`/api/extensions/claude`); JSON as text, so no plugin classes have
  to pass through OIE's XStream serializer.
- `server` – service plugin, servlet, tools, masker, conversations and jobs. Runs on the OIE classpath.
- `engine` – the loop on the Anthropic Java SDK. It sits in `lib/` together with the SDK, Jackson
  2.19, Kotlin and OkHttp and is loaded in its own child-first class loader: OIE ships Jackson 2.14,
  and the SDK's Kotlin reflection does not work after relocation (shading).
- `client` – Swing: chat window, tasks in dashboard/message browser/channel editor, settings screen.

The engine streams each answer from the API; the client polls for progress every 0.8 s
(`GET /jobs/{id}`) and shows the answer growing while the model writes it (`partial` in the job), so
long answers do not run into the Administrator's HTTP timeout.

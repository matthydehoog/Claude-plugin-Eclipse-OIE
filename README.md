# Claude Assistant for Open Integration Engine

An extension for the OIE Administrator that lets you talk to Claude about your server from inside
the GUI: channels, messages, errors, scripts, code templates and server status. Claude looks the
facts up itself through the same tools as `oie-mcp-server`, and can propose actions that only run
after you approve them.

## Where to find Claude in the Administrator

| Place | Task | Context sent along |
|---|---|---|
| Left menu, **Other** | *Claude Assistant* | none: questions about the whole server |
| **Dashboard** (task + right-click) | *Ask Claude* | selected channel(s)/connectors |
| **Message browser** (task + right-click) | *Ask Claude* | selected message (channel, message ID, connector) |
| **Channel editor** (task + right-click) | *Ask Claude* | the channel that is open (saved version) |

The chat window stays open next to the Administrator. Ctrl+Enter sends; *New conversation* starts
over; *Stop* aborts a running question.

### Web administrator

The extension also ships a UI for the OIE web administrator (`oie-webadmin`). It needs the
**Web Support** (`websupport`) extension on the engine, which serves the plugin's `webadmin/` folder.

| Place | What |
|---|---|
| Left rail, **Plugins > Claude Assistant** | the chat as a full page |
| **Channel editor**, tab **Claude** | the chat with the open channel as context |
| **Channels** view, right-click | *Ask Claude* about the selected channel |
| **Message browser**, right-click | *Ask Claude* about the selected message (web admin API 4.7+) |
| **Settings**, tab **Claude Assistant** | the same settings and usage as in Swing, using the settings page's own Save |

Both UIs use the same REST API, so masking, permissions, action approval and the audit log are
identical; a conversation stays open while you move between the Claude page and other views.

## What Claude can do

**Read** (immediately): server info, channels with status and counters, channel configuration and
scripts, statistics per connector, searching and viewing messages, events, the server log, code
templates and the keys of the Configuration Map (never the values).

**Actions** (only after clicking *Run* in an approval dialog): deploy/undeploy a channel,
start/stop/pause/resume, reset statistics, reprocess a message, send a message to a channel and
change a JavaScript script (deploy/undeploy/pre/postprocessor, filter rule or transformer step).
A script change saves the channel with a new revision but does not deploy it; if the channel
changed in the meantime, nothing is saved. Every executed action is written to the audit log as
event `Claude Assistant: …`, in the name of the user who approved it.

## Privacy

Everything sent to the Anthropic API (your questions, context, message content, logs, errors,
channel configuration) first goes through a masker with the same rules as `mask.ts` in
oie-mcp-server:

- HL7 v2 PID fields 2-7, 9, 11, 13, 14, 19 (ER7 and XML)
- GDT patient fields 3000-3107 (raw and XML from the GDT data type plugin)
- 9-digit numbers that pass the eleven-test (Dutch BSN)
- your own patterns under *Settings > Claude Assistant*

This is a safety net, not anonymisation. Only send real patient data through it if you have a legal
basis and a data processing agreement with Anthropic. Conversations live only in the server's memory
(cleared 4 hours after last use) and are only visible to the user who started them.

## Permissions

The extension adds three permissions (visible in the RBAC extension):

- **Use Claude Assistant**: chat (read only)
- **Run Claude Assistant actions**: approve proposed actions
- **Manage Claude Assistant settings**: change the API key, model and mask patterns

Users with channel restrictions cannot use the assistant, because the tools see all channels.

## Setup

1. Install `claude-assistant-<version>.zip` via *Settings > Extensions > Install Extension* (or unzip
   it into `<OIE_HOME>/extensions/`) and restart the OIE service. Check that
   `extensions/claude-assistant/lib/` contains the engine jars.
2. Go to *Settings > Claude Assistant*, enter an Anthropic API key (from
   [console.anthropic.com](https://console.anthropic.com)) and click *Save*. The key is stored
   encrypted and never sent back to the Administrator; the field is emptied after saving and the
   line below it shows *Set: sk-ant-…xxxx*.
3. Defaults: model `claude-opus-5`, effort `high`, at most 25 tool calls per question, response
   language *Automatic* (Claude answers in the language of the question). Pick a fixed language in
   **Response language** to have Claude always answer in that language, whatever language the
   question or the server data is in.

### Usage: spend and credits

The *Usage* section of *Settings > Claude Assistant* shows **Spend this month** for the whole
organization and, separately, for the workspace the plugin's API key belongs to. It comes from the
Anthropic [Cost API](https://platform.claude.com/docs/en/manage-claude/usage-cost-api) and needs an
optional **Admin API key** (`sk-ant-admin01-…`), which only an organization admin can create in the
Console (not available for individual accounts). Amounts are in USD since the 1st of the month (UTC),
run up to about 5 minutes behind and are cached for a minute; *Refresh* fetches them again.
Viewing spend requires the *Manage Claude Assistant settings* permission.

The organization's **credit balance** is not available through any Anthropic API, so the tab offers
an *Open Billing in Console* button instead.

The OIE server must be able to reach `https://api.anthropic.com`. Behind a gateway or proxy? Add for
example `-Doie.claude.baseUrl=https://gateway.example/anthropic` to `conf/custom.vmoptions`.

## Building

JDK 11+ and Maven; the engine jars are taken from an OIE installation (`-Doie.home=...`, default
`C:/Program Files/OpenIntegrationEngine`).

```bash
mvn package
```

Result: `target/claude-assistant-<version>.zip`.

### Structure

- `shared` – the REST interface (`/api/extensions/claude`); JSON as text, so no plugin classes have
  to pass through OIE's XStream serializer.
- `server` – service plugin, servlet, tools, masker, conversations and jobs. Runs on the OIE classpath.
- `engine` – the loop on the Anthropic Java SDK. It sits in `lib/` together with the SDK, Jackson
  2.19, Kotlin and OkHttp and is loaded in its own child-first class loader: OIE ships Jackson 2.14,
  and the SDK's Kotlin reflection does not work after relocation (shading).
- `client` – Swing: chat window, tasks in dashboard/message browser/channel editor, settings screen.

The client polls for progress every 0.8 s (`GET /jobs/{id}`), so long answers do not run into the
Administrator's HTTP timeout.

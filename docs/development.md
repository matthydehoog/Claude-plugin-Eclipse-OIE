# Development guide

This guide is for people who build, change or release the Claude Assistant. For installing and using it, see the [README](../README.md) and the [User Manual](USER_MANUAL.md).

## Contents

- [Repository layout](#repository-layout)
- [Building](#building)
- [How the plugin is put together](#how-the-plugin-is-put-together)
- [The isolated engine](#the-isolated-engine)
- [A question, step by step](#a-question-step-by-step)
- [Tools](#tools)
- [Actions and approval](#actions-and-approval)
- [Changing channels: ChannelEditor](#changing-channels-channeleditor)
- [Masking](#masking)
- [Review before sending](#review-before-sending)
- [Permissions and audit](#permissions-and-audit)
- [Conversations](#conversations)
- [Settings, usage and spend](#settings-usage-and-spend)
- [The REST API](#the-rest-api)
- [The Swing client](#the-swing-client)
- [The web administrator](#the-web-administrator)
- [Testing](#testing)
- [Trying it in OIE](#trying-it-in-oie)
- [GitHub workflows](#github-workflows)
- [Releasing](#releasing)
- [Conventions](#conventions)

## Repository layout

| Path | What it is |
| --- | --- |
| `src/main/java/.../claude/shared/` | `ClaudeServletInterface`: the REST interface, used by the server and the Swing client. `claude-assistant-shared.jar`. |
| `src/main/java/.../claude/server/` | Service plugin, servlet, assistant service, tools, maskers, conversations. Runs on the OIE classpath. `claude-assistant-server.jar`. |
| `src/main/java/.../claude/engine/` | The model loop and the spend reader on the Anthropic Java SDK. Loaded in isolation from `lib/`. `lib/claude-assistant-engine.jar`. |
| `src/main/java/.../claude/client/` | The Swing Administrator: tasks, chat window, settings panel, Markdown rendering. `claude-assistant-client.jar`. |
| `src/main/resources/plugin.xml` | The extension descriptor the engine reads. |
| `src/test/` | JUnit 4 tests, with HL7, FHIR and EDIFACT test messages in `resources/`. |
| `webadmin/` | The UI for the OIE web administrator (`plugin.json`, `web/plugin.js`). |
| `docs/USER_MANUAL.md` | The user manual. |
| `assembly.xml` | How the extension zip is laid out. |
| `oie.json` | The manifest for the OIE Community Store catalog. |
| `.github/` | The catalog and Jira workflows and their scripts. |

`...` is `com/matthy/oie`. Unlike the data types and connectors, this plugin does not need a `com.mirth.connect` package: none of its classes pass through the engine's XStream serializer, because every request and response is JSON text (see [The REST API](#the-rest-api)). Keep it that way.

## Building

### Prerequisites

- **JDK 11 or later** and **Maven**. The first build downloads the Anthropic SDK and its dependencies.
- **The OIE engine jars** `com.mirth.connect:server-api`, `donkey-server`, `donkey-model`, `client` and `client-core` (version `oie.version`, now 4.6.0) in your local Maven repository. Install them from an OIE installation as described in the development guides of the other plugins, for example:

  ```bash
  mvn install:install-file -Dfile="$OIE_HOME/server-lib/mirth-server.jar" -DgroupId=com.mirth.connect -DartifactId=server-api -Dversion=4.6.0 -Dpackaging=jar
  ```

  and the same for `server-lib/donkey/donkey-server.jar`, `server-lib/donkey/donkey-model.jar`, `client-lib/mirth-client.jar` and `client-lib/mirth-client-core.jar`.
- **An OIE installation** on the build machine. Some jars are referenced directly from it as `system` dependencies: `mirth-crypto` (the encryptor for the API keys), `javax.ws.rs-api`, `javax.servlet-api`, `swagger-annotations`, `swingx-core` and `miglayout`. The path is the property `oie.home`, default `C:/Program Files/OpenIntegrationEngine`:

  ```bash
  mvn package -Doie.home=/opt/openintegrationengine
  ```

### Build

```bash
mvn clean package
```

The result is `target/claude-assistant-<version>.zip`, with the Anthropic SDK and its libraries. Use `clean`: `target/lib` keeps jars from an earlier build otherwise.

### What is in the zip

```
claude-assistant/
├── plugin.xml
├── claude-assistant-shared.jar   claude/shared/**
├── claude-assistant-server.jar   claude/server/**
├── claude-assistant-client.jar   claude/client/**
├── lib/
│   ├── claude-assistant-engine.jar   claude/engine/**
│   └── *.jar                         anthropic-java, Jackson 2.19, Kotlin, OkHttp, ...
└── webadmin/                     the web administrator UI
```

`lib/` is not listed in `plugin.xml`: the engine never loads it; `EngineLoader` does. The folder name `claude-assistant` must equal the `path` attribute in `plugin.xml`.

## How the plugin is put together

`plugin.xml` registers:

| Entry | Class | Purpose |
| --- | --- | --- |
| `serverClasses` | `server.ClaudeServicePlugin` | A `ServicePlugin`: loads the settings, creates the `AssistantService`, declares the three permissions. |
| `clientClasses` | `client.ClaudeClientPlugin` | The tasks in the Swing Administrator. |
| `clientClasses` | `client.ClaudeSettingsPlugin` | The *Settings > Claude Assistant* tab. |
| `apiProvider` `SERVLET_INTERFACE` | `shared.ClaudeServletInterface` | The REST interface under `/api/extensions/claude`. |
| `apiProvider` `SERVER_CLASS` | `server.ClaudeServlet` | Its implementation. |

The server side:

| Class | Role |
| --- | --- |
| `ClaudeServicePlugin` | Life cycle and settings storage (extension properties). |
| `ClaudeServlet` | The REST endpoints; resolves the user and their address and hands over to `AssistantService`. |
| `AssistantService` | Conversations, jobs, tool calls, action approval, review, audit, spend. The heart of the plugin. |
| `ChatJob`, `Conversation` | One question and its events; one conversation and its log and history. |
| `OieTools` | The tools Claude can call: read tools and action tools. |
| `ChannelEditor` | Makes channel XML that Claude wrote safe for OIE's serializer. |
| `Masker`, `FhirMasker`, `EdifactMasker` | Masking before anything leaves the server. |
| `ConversationStore` | Saved conversations on disk. |
| `Settings` | The settings, with the API keys encrypted. |
| `EngineLoader`, `ModelLoop`, `SpendReader` | The boundary to the isolated engine. |

## The isolated engine

The Anthropic Java SDK needs Jackson 2.19, Kotlin and OkHttp. OIE ships Jackson 2.14 and no Kotlin. Shading (relocating) the SDK does not work: Kotlin reflection, used by `jackson-module-kotlin`, reads the original class names from Kotlin metadata. So the SDK and the code that uses it run in their own class loader.

- **`ModelLoop`**, **`SpendReader`** and **`ModelReader`** are the interfaces the server uses. Only JDK types and host-side classes (`ChatJob`, `Conversation`, `ModelLoop.ToolCaller`) cross them; never SDK or Jackson types. A conversation's SDK history is kept on the host as an opaque `Object`.
- **`EngineLoader`** creates a child-first `URLClassLoader` over every jar in `lib/` and instantiates `engine.SdkModelLoop`, `engine.SdkSpendReader` and `engine.SdkModelReader`.
- These prefixes always come from the parent: `java.`, `javax.`, `jdk.`, `sun.`, `com.sun.`, `org.w3c.`, `org.xml.`, `com.matthy.oie.claude.server.`, `com.matthy.oie.claude.shared.`, `com.mirth.`, `org.apache.logging.`, `org.slf4j.`.
- Jackson and Kotlin find classes through the thread's context class loader, so `SdkModelLoop` sets it to the engine loader while it works.

**`SdkModelLoop`** runs the conversation with the Messages API:

- **Streaming**: each answer is streamed; text fragments go to the job (`partial`) as they arrive, and a `BetaMessageAccumulator` builds the complete message the loop continues with.
- **Tools**: when Claude asks for a tool, the loop calls the host through `ToolCaller` and sends the result back, up to *Max tool calls* per question.
- **Timeouts**: 10 seconds to connect, 10 minutes per request; 2 retries for rate limits, overloads and dropped connections. Without internet the user gets a clear error within about half a minute. A connection error names the root cause (DNS, refused, timeout).
- **Fallback**: after a mid-answer fallback, the declined attempt's thinking and tool calls are not echoed back into the history.
- **Prompt caching**: the system prompt only changes with the settings, so it stays byte-identical within a conversation and the cache keeps working. One line per API call and a total per question go to the server log with input, cache read, cache write and output tokens.
- **Base URL**: the system property `oie.claude.baseUrl` points the SDK at a gateway or proxy instead of `api.anthropic.com`.
- `saveHistory` / `loadHistory` turn the opaque history into JSON and back, for saved conversations.

## A question, step by step

1. The client posts `POST /chat` with the question, an optional conversation ID and the context of the screen it was asked from.
2. `AssistantService.startChat` checks that the engine is loaded and an API key is set, finds or creates the conversation (only the user's own), refuses a second question while one is running, and creates a `ChatJob`.
3. The job runs on an executor: optional review of the question (with review set to *Everything*), then `ModelLoop.run`.
4. During the loop:
   - **read tools** run at once, as the user, through `OieTools.run`; the result is masked, clipped to 60,000 characters, and possibly reviewed;
   - **action tools** are prepared and the job **waits** for the user's decision (up to 15 minutes);
   - text arrives as `partial` and finally as a `text` event.
5. The client polls `GET /jobs/{id}` every 0.8 seconds and shows the events: `text`, `tool`, `action`, `info`, `error`, plus a pending action or review.
6. When the job ends, the conversation is saved if *Keep conversations* is on.

Polling instead of one long request keeps long answers clear of the Administrator's HTTP timeout. `POST /jobs/{id}/cancel` stops a job, also mid-answer.

## Tools

`OieTools` offers these tools to Claude, each mapped to the OIE operation the Administrator itself would call (`OPERATIONS`):

| Read tools (run at once) | Action tools (run after approval) |
| --- | --- |
| `oie_server_info`, `oie_list_channels`, `oie_get_channel`, `oie_channel_scripts`, `oie_channel_overview`, `oie_global_scripts`, `oie_channel_statistics`, `oie_search_messages`, `oie_get_message`, `oie_events`, `oie_server_log`, `oie_list_code_templates`, `oie_get_code_template`, `oie_configuration_map` (keys only) | `oie_deploy_channel`, `oie_undeploy_channel`, `oie_start_channel`, `oie_stop_channel`, `oie_pause_channel`, `oie_resume_channel`, `oie_reset_statistics`, `oie_reprocess_message`, `oie_send_message`, `oie_update_channel_script`, `oie_update_global_script`, `oie_update_filter`, `oie_update_transformer`, `oie_create_channel` |

The tools mirror those of `oie-mcp-server`. A channel can be given by ID or by name (case-insensitive); dates are ISO dates or date-times, a plain date meaning midnight in the server's time zone.

**Channel overview.** `oie_channel_overview` (built by `ChannelOverview`, free of engine calls) describes a channel for explanation and documentation: per connector OIE's own readable settings (`ConnectorProperties.toFormattedString()`, or the properties XML with credentials emptied and plugin properties left out when a connector has none, as receivers do), the filter rules and transformer steps with the script OIE generates for each, the non-default channel scripts, the linked code template libraries and the Configuration Map keys referenced (`$cfg`, `configurationMap.get`, `$()`, `${key}`). `toFormattedString()` shows HTTP headers and JDBC URLs in full, so settings lines named like a secret (`Authorization:`, `…password…:`) and, everywhere, `password=` parameters and `user:password@` in URLs are hidden before the masker runs. `metaDataIds` limits it to some connectors, for large channels.

**Adding a tool:**

1. Add a `ReadTool` or `ActionTool` in `OieTools`, with a `Spec` (name, description, JSON schema of the input).
2. Map it in `OPERATIONS` to the OIE operation that guards the same data or change; leave it out only if OIE itself needs no permission for it (like server info).
3. If its result can carry message content, log lines or events, add it to `DATA_TOOLS` in `AssistantService`, so it is reviewed and audited.
4. Make sure the result goes through the masker (`run` does that for read tools).
5. Describe it in the README and the user manual.

## Actions and approval

An action tool never runs from the model loop. `OieTools.prepare` builds a `PreparedAction`: a description for the approval dialog (for changes: the current and new rules or steps, and the script OIE generates for each new one) and a runner.

The job then waits. The user decides with `POST /jobs/{id}/confirm`, which needs the **Run Claude Assistant actions** permission. `AssistantService.decide` runs the action **on the servlet thread, as the approving user**, checks the permission for the underlying OIE operation again, writes the audit event `Claude Assistant: …`, and gives the result to Claude. A rejected or timed-out action is reported to Claude as not run.

Script, filter and transformer changes save the channel with a new revision but do not deploy it. The save is **optimistic**: it only happens if the channel is still at the revision Claude read. The check and the save hold the channel controller's lock, so nobody can save in between. The stored metadata (enabled, pruning) and the channel's tags travel along, as the Administrator sends them; without them OIE would reset them to defaults.

## Changing channels: ChannelEditor

Claude writes filter rules, transformer steps and whole channels as OIE XML. `ChannelEditor` makes that safe for OIE's serializer, without engine calls, so it is tested on its own:

- It accepts `<elements>…</elements>`, `<list>…</list>` or the element XML itself.
- It gives **every object a `version` attribute**. OIE reads an object without one as version 3.0.0 and migrates it, which can garble XML in the current format.
- It **empties credential elements** in connector properties: Claude has only seen them masked and cannot set them. The user fills them in.
- It describes the result for the approval dialog, one line per element, for example `2. OR [Rule Builder] 'Is ADT' (disabled)`.

A new channel (`oie_create_channel`, also a changed copy) is created **disabled and undeployed**.

## Masking

Everything that goes to Anthropic (questions, context, tool results) goes through `Masker.mask` first. It is a port of `mask.ts` from `oie-mcp-server`: a safety net, not anonymisation. The order matters:

1. **XML-escaped text first.** Tool results are often XStream XML, in which a message is escaped (`&lt;`, `&#xd;`). Escaped text between tags is unescaped, masked recursively and escaped again. Without this, HL7 inside `oie_get_message` would never match (a bug fixed in 0.2.7).
2. **Credentials** in channel XML: elements named like password, passphrase, secret, token or apiKey.
3. **EDIFACT** (`EdifactMasker`): MEDLAB and other MED* health messages, raw and as XML from the EDIFACT data type; trade messages are left alone. It runs before HL7, whose patterns are written not to match EDIFACT.
4. **HL7 v2** ER7 and XML: PID and PV1 fields, whole NK1, GT1, IN1, IN2 and MRG segments except the set ID, NTE comments, and any value longer than 30 characters.
5. **GDT** patient fields 3000–3107, raw and as XML from the GDT data type.
6. **FHIR** (`FhirMasker`): JSON parsed with positions and XML by pattern; personal elements of Patient, RelatedPerson, Person and Practitioner, identifier values and reference displays in every resource, narratives and XML comments.
7. **Dutch BSN**: nine digits passing the eleven-test.
8. **The user's own patterns** from the settings.

The README's *Privacy* section is the user-facing list; keep it in step. Add a test with a realistic message for every rule, and check that structure, codes and other resources stay readable.

## Review before sending

With *Review before sending* on, the job pauses before data leaves and shows the exact, already masked text in a dialog:

| Setting | Reviewed |
| --- | --- |
| `data` (default) | Results of `DATA_TOOLS`: message content, search results, log lines, events |
| `all` | Every tool result and the question itself |
| `off` | Nothing |

The user sends, edits or withholds it (`POST /jobs/{id}/review`). **Edited text is masked again**, so the user cannot put patient data back in by accident. Withheld data is reported to Claude as not sent.

## Permissions and audit

`ClaudeServicePlugin` declares three permissions; the servlet operations are bound to them in `ClaudeServletInterface`:

| Permission | Operations |
| --- | --- |
| Use Claude Assistant (`PERMISSION_USE`) | chat, jobs, review, cancel, conversations, read settings, model list |
| Run Claude Assistant actions (`PERMISSION_ACTIONS`) | confirm an action |
| Manage Claude Assistant settings (`PERMISSION_SETTINGS`) | change settings, read spend |

On top of that, **every tool checks the user's own OIE permission** for its operation (`AuthorizationController.isUserAuthorized`, through the RBAC extension when installed). A tool the user lacks is skipped and Claude is told which permission is missing. The check fails closed on errors. Users with channel restrictions cannot use the assistant, because the tools see all channels.

Audit events:

- every executed action: `Claude Assistant: …`, in the name of the user who approved it;
- every time message data, log lines or events are sent: `Claude Assistant: data sent to Anthropic`, with user, tool, channel, message IDs and whether it was reviewed. **Never the content itself.**

## Conversations

- **In memory**: `Conversation` objects, per user, removed 4 hours after last use and on a restart. A conversation is only visible to the user who started it.
- **Saved** (with *Keep conversations* > 0 days, at most 365): `ConversationStore` writes one JSON file per conversation to `<appdata>/claude-assistant/conversations/<user>/<id>.json`, atomically (a temporary file, then a move). A file holds the readable log and the model history, **masked exactly as sent to Claude**. Old files are removed after the set number of days; setting 0 removes them all.
- **Export** gives the log as Markdown; **resume** loads a saved conversation into memory, history included.

## Settings, usage and spend

`Settings` is stored as extension properties: `apiKey`, `adminApiKey`, `model`, `effort`, `maxToolCalls`, `maskPatterns`, `responseLanguage`, `reviewBeforeSending`, `keepConversationsDays`.

- **The API keys are encrypted** with OIE's encryptor (`mirth-crypto`) and **never sent back** to a client: the client only sees a hint (`sk-ant-…xxxx`). An empty key in an update keeps the current one; `clearAdminApiKey` removes the Admin key.
- Defaults: model `claude-opus-5`, effort `high`, 25 tool calls, language *Automatic*, review `data`, keep 0 days.
- The response language is written into the system prompt.

**Spend** (`GET /spend`, *Manage settings* permission) uses the optional Admin API key: `SdkSpendReader` reads the Cost API for the organization and the plugin key's workspace, and the Usage API for the tokens this month. The result is cached for a minute, as Anthropic asks. The cost report's buckets end *before* `ending_at`, so the request must reach past today.

**The model list** (`GET /models`, *Use* permission) comes from Anthropic's Models API: `SdkModelReader` lists the models the plugin's API key can use (all pages), `AssistantService.models` caches the result for a day per API key (*Refresh list* bypasses the cache), and `ModelList` sorts it newest first. When the list cannot be read (no API key, no engine, an error from Anthropic) `ModelList.FALLBACK` is used instead, with the reason in `note`; failures are not cached. A saved model that is not in the list is kept at the top, marked `unlisted`, and with Anthropic's list also gets a `warning`. Both clients only show what the server returns, so the fixed list lives in one place: `ModelList.FALLBACK`. Keep `Settings.DEFAULT_MODEL` in it (a test checks that).

## The REST API

`ClaudeServletInterface`, under `/api/extensions/claude`:

| Method and path | Purpose |
| --- | --- |
| `POST /chat` | Start a question (body: question, conversation ID, context). Returns the job. |
| `GET /jobs/{id}` | Events, partial answer and pending decision of a job. |
| `POST /jobs/{id}/confirm` | Approve or reject a proposed action. |
| `POST /jobs/{id}/review` | Send, edit or withhold data under review. |
| `POST /jobs/{id}/cancel` | Stop a job. |
| `GET /conversations` | The user's conversations. |
| `GET /conversations/{id}/export` | A conversation as Markdown. |
| `POST /conversations/{id}/resume` | Continue a conversation. |
| `GET /settings`, `PUT /settings` | Read and change the settings (keys only as hints). |
| `GET /models` | The models for the settings screens (Anthropic's list or the fixed one). |
| `GET /spend` | Spend and tokens this month. |

Bodies and responses are **JSON as text** (`text/plain`), so no plugin class passes through the engine's XStream serializer. Errors carry a readable explanation in the response body; both clients show that rather than a stack trace.

## The Swing client

- `ClaudeClientPlugin` adds the tasks: *Claude Assistant* under **Other**, and *Ask Claude* in the dashboard, message browser, channel editor, code templates and global scripts (plus *Document channel* in the channel editor), each with its context (selected channels, message, channel, template, scripts) and a suggested question.
- `ChatDialog` is a non-modal window, one per Administrator session. It polls every 800 ms, renders Claude's Markdown with `Markdown`, shows the approval and review dialogs, and offers *New conversation*, *Stop*, *Export…*, *Save answer…* (only Claude's last answer, named after its first heading by `Markdown.fileName`) and *Conversations…*.
- `ClaudeApi` wraps the servlet (`mirthClient.getServlet(ClaudeServletInterface.class)`). All calls block; never call them on the event thread.
- `ClaudeSettingsPlugin` and `ClaudeSettingsPanel` make the settings tab, with *Usage* and *Open Billing in Console*.

## The web administrator

`webadmin/web/plugin.js` is a plain ES module against the web shell's `platform`, without a build step, served by the engine through the **Web Support** extension. It registers:

| Registration | What |
| --- | --- |
| `registerNavItem` / `reactView` | The Claude page (chat), with a *Global Scripts* button |
| `registerChannelTab` | The *Claude* tab in the channel editor |
| `registerChannelAction`, `registerMessageAction`, `registerCodeTemplateAction` | *Ask Claude* in context menus (message actions need web admin API 4.7+) |
| `registerCommand` | Command palette entries |
| `registerSettingsPanel` | The settings tab, using the settings page's own *Save* |

It uses the same REST API and the same 800 ms polling, so masking, permissions, approval and audit are identical. Keep the two UIs in step when you add a feature; differences that cannot be avoided (the web admin has no hook on the Global Scripts view) belong in the README.

## Testing

```bash
mvn test
```

There are 84 tests (JUnit 4). They need no engine, no network and no API key.

| Test | Covers |
| --- | --- |
| `MaskerTest` (16) | HL7 ER7 and XML rules, the encoded separator after a masked last field, person segments, credentials, GDT raw and XML, BSN, own patterns |
| `EdifactMaskerTest` (14) | MEDLAB and MEDSPE, raw and XML, trade messages untouched, ZKH and AFD readable |
| `FhirMaskerTest` (9) | Person resources in JSON and XML, personal extensions, identifiers, reference displays, narratives, Bundles, FHIR escaped inside tool XML, other resources left readable |
| `ChannelEditorTest` (8) | Version attributes (also nested), wrappers, malformed XML, credentials emptied, descriptions and generated scripts |
| `ConversationStoreTest` (7) | Saving, listing and loading per user, replacing, expiry, damaged files, Markdown export, titles |
| `ChannelOverviewTest` (7) | The overview of a channel, default scripts, connectors limited by `metaDataIds`, secrets hidden in settings and URLs, Configuration Map keys found |
| `ModelListTest` (6) | Newest first without duplicates, the fixed list with its reason, a saved model that is no longer listed |
| `SettingsTest` (7) | Defaults and limits (review, keep days, language), updates that keep what is not given, the language in the system prompt |
| `engine/FallbackTest`, `HistoryTest`, `UsageTest` (2 each) | Fallback handling, history JSON, usage lines |
| `client/MarkdownTest` (3) | The file name *Save answer* gives: the first heading, cleaned of emphasis and characters Windows forbids |
| `client/ClaudeSettingsPanelTest` (1) | The cache share in the token figures |

Masking is the part where a mistake hurts most: when you touch a masker, run it over the test messages in `src/test/resources` and check the output by eye as well.

## Trying it in OIE

1. `mvn clean package`.
2. Install `target/claude-assistant-<version>.zip` on a **test** server and restart the service. Check that `extensions/claude-assistant/lib/` holds the engine jars.
3. Set an API key under *Settings > Claude Assistant*. Use test data only.
4. Ask a question from each place (Other, dashboard, message browser, channel editor, code templates, global scripts), and in the web administrator if Web Support is installed.
5. Check: the review dialog shows masked data; an approved action runs and appears in the audit log; a change keeps the channel's tags and enabled state; the server log has one usage line per API call.

## GitHub workflows

There is no build workflow in this repository; build and test locally before you push.

| Workflow | Does |
| --- | --- |
| `catalog-pr.yml` | On a published release, files a pull request to the [OIE Community Store catalog](https://github.com/gibson9583/oie-community-catalog) from the fork `<owner>/oie-community-catalog`, using `oie.json` at the tag. Needs the secret `CATALOG_TOKEN` (a classic PAT with `public_repo`); skipped without it. |
| `jira-issue.yml` | Creates a Jira issue for every new GitHub issue and comments its key. Needs the variables `JIRA_BASE_URL`, `JIRA_EMAIL`, `JIRA_PROJECT_KEY` and the secret `JIRA_API_TOKEN`. |

`main` is protected against force pushes and deletion (ruleset *Protect main*).

## Releasing

1. **Branch and pull request.** Reference the issue (`Closes #n`).
2. **Version.** Set the new version in all four places; nothing checks this automatically, so compare them:
   - `pom.xml` (`<version>`)
   - `src/main/resources/plugin.xml` (`<pluginVersion>`)
   - `oie.json` (`"version"`)
   - `webadmin/plugin.json` (`"version"`)
3. **Engine versions.** `<mirthVersion>` in `plugin.xml` lists every OIE version the extension may load on (now `4.6.0,4.7.0`). OIE only loads an extension whose list contains its **exact** version, so a new OIE release needs a new plugin release.
4. **Build and test** with `mvn clean package`, and try the zip in OIE.
5. **Merge** the pull request.
6. **Release** on GitHub with the zip and its SHA-256 in the notes:

   ```bash
   sha256sum target/claude-assistant-1.0.2.zip
   gh release create v1.0.2 target/claude-assistant-1.0.2.zip --target main \
     --title "1.0.2: …" --notes-file notes.md
   ```

7. **Catalog.** Publishing the release runs `catalog-pr.yml`. Check its pull request, and close earlier ones of this plugin that are still open.
8. **Jira.** Closing a GitHub issue does not close its Jira issue: set it to *Done* yourself.
9. **Documentation.** Update the README, the user manual and the [online course](https://eclipse-oie.moodiy.cloud/course/view.php?id=3) when behaviour changes. The disclaimer (unofficial, not affiliated with Anthropic; each organization is responsible) stays in the README, the manual, `plugin.xml` and `webadmin/plugin.json`.

## Conventions

- Every source file starts with the MPL 2.0 header ([LICENSE](../LICENSE)).
- Only JDK and host types cross the engine boundary; nothing outside `engine/` imports the SDK.
- Nothing leaves the server without going through the masker; nothing changes in OIE without the user's approval.
- A tool never gives Claude more than the user could see or do in OIE.
- Secrets (API keys, credentials) are never sent to a client, logged or audited; audit events hold IDs, never content.
- Requests and responses are JSON text, never plugin classes.
- Error messages say what is wrong and what to do, for example *"No Anthropic API key is set yet. Enter one under Settings > Claude Assistant."*
- Comments explain *why*; keep them short.

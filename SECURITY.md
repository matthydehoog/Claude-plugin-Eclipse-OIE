# Security Policy

The AI Assistant runs inside Open Integration Engine (OIE) servers that often process patient data, and it holds API keys for AI providers. Security reports are welcome and are handled before other work.

## Supported versions

Only the latest release gets security fixes. Fixes are not backported to older versions.

| Version | Supported |
| ------- | --------- |
| 2.1.x (latest) | Yes |
| Older than 2.1.0 | No: please upgrade |

## Reporting a vulnerability

**Do not open a public issue for a vulnerability.** Report it privately:

1. Go to the [Security tab](https://github.com/matthydehoog/Claude-plugin-Eclipse-OIE/security) of this repository.
2. Click **Report a vulnerability**.
3. Describe the problem, the plugin and OIE versions, and the steps to reproduce it. Use made-up data and fake API keys, never real patient data or real keys.

The report is only visible to you and the maintainer. You can follow and discuss the fix in the draft advisory.

This is a community project maintained on a best-effort basis. You can expect a first reply within a week. Once a fix is released, a GitHub security advisory is published (with a CVE when that is warranted), crediting you unless you prefer otherwise.

## What to report

Examples of what counts as a vulnerability in this plugin:

- Patient or other sensitive data sent to the AI provider unmasked, when masking applies to it (HL7, FHIR, GDT, EDIFACT).
- API keys or other credentials exposed: in logs, the OIE event log, responses, exports or saved conversations.
- An action (deploy, script, filter or transformer change, and so on) that runs without the user's approval.
- A user reaching data or actions that their OIE permissions do not allow.
- Anything that lets text from messages, channels or a model's answer make the assistant act outside what the user asked (prompt injection leading to actions or data leaks).

Not in scope here:

- Vulnerabilities in OIE itself: report them as described in the [Open Integration Engine security policy](https://github.com/OpenIntegrationEngine/engine/blob/main/SECURITY.md).
- Vulnerabilities in an AI provider's service or SDK: report them to that provider.
- Answers from the model that are wrong or unhelpful, without a security impact: open a normal issue.

## Past advisories

- **API key in the OIE event log (fixed in 2.1.0, [#81](https://github.com/matthydehoog/Claude-plugin-Eclipse-OIE/issues/81)).** Up to 2.0.2, saving the settings wrote the API key in plain text into the OIE event log. If you saved a key with an earlier version, create a new key at your provider, save it in *Settings > AI Assistant* and revoke the old one.

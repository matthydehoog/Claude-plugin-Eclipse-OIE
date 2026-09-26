// Creates a Jira issue for a newly opened GitHub issue and comments the Jira key back.
//
// Runs in the "Create Jira issue" workflow. Configuration (repository settings):
//   variables: JIRA_BASE_URL (e.g. https://x-connections.atlassian.net), JIRA_EMAIL, JIRA_PROJECT_KEY
//   secret:    JIRA_API_TOKEN (an Atlassian API token of JIRA_EMAIL)
//
// The Jira issue type follows the GitHub labels: bug -> Bug, enhancement/feature -> Feature,
// anything else -> Task. When the project has no such type, Task is used.

import { execFileSync } from "node:child_process";
import { markdownToJira } from "./markdown-to-jira.mjs";

const LABEL_TYPES = { bug: "Bug", enhancement: "Feature", feature: "Feature" };
const FALLBACK_TYPE = "Task";
const MAX_DESCRIPTION = 30000; // Jira's limit is 32767 characters

function issueType(labels) {
  for (const label of labels) {
    const mapped = LABEL_TYPES[label.toLowerCase()];
    if (mapped) return mapped;
  }
  return FALLBACK_TYPE;
}

function description(issue, repo) {
  let body = (issue.body || "").trim() || "(no description)";
  if (body.length > MAX_DESCRIPTION) body = body.slice(0, MAX_DESCRIPTION) + "\n\n(... cut off, see GitHub for the full text)";
  body = markdownToJira(body);
  const labels = (issue.labels || []).map((l) => l.name).join(", ") || "none";
  return `${body}\n\n----\nGitHub issue: [${repo}#${issue.number}|${issue.html_url}]\nOpened by: ${issue.user.login}\nLabels: ${labels}`;
}

async function create(baseUrl, auth, project, summary, text, type) {
  const response = await fetch(baseUrl.replace(/\/+$/, "") + "/rest/api/2/issue", {
    method: "POST",
    headers: { Authorization: "Basic " + auth, "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify({
      fields: { project: { key: project }, summary: summary.slice(0, 250), description: text, issuetype: { name: type }, labels: ["github"] },
    }),
  });
  const detail = await response.text();
  if (!response.ok) {
    const error = new Error(`Jira refused the issue (${response.status}): ${detail}`);
    error.status = response.status;
    error.detail = detail;
    throw error;
  }
  return JSON.parse(detail).key;
}

async function main() {
  const env = process.env;
  const auth = Buffer.from(`${env.JIRA_EMAIL}:${env.JIRA_API_TOKEN}`).toString("base64");
  const issue = JSON.parse(env.ISSUE_JSON);
  const repo = env.GITHUB_REPOSITORY;
  const wanted = issueType((issue.labels || []).map((l) => l.name));
  const text = description(issue, repo);

  let key;
  try {
    key = await create(env.JIRA_BASE_URL, auth, env.JIRA_PROJECT_KEY, issue.title, text, wanted);
  } catch (e) {
    if (e.status === 400 && /issuetype/i.test(e.detail) && wanted !== FALLBACK_TYPE) {
      console.log(`Issue type ${wanted} not available in ${env.JIRA_PROJECT_KEY}, using ${FALLBACK_TYPE}.`);
      key = await create(env.JIRA_BASE_URL, auth, env.JIRA_PROJECT_KEY, issue.title, text, FALLBACK_TYPE);
    } else {
      throw e;
    }
  }

  console.log(`Created ${key} for ${repo}#${issue.number}.`);
  // Only the key, not the site URL: the GitHub repositories are public, the Jira site is not.
  execFileSync(env.GH_CMD || "gh", ["issue", "comment", String(issue.number), "--repo", repo, "--body", `Tracked in Jira as **${key}**.`], { stdio: "inherit" });
}

main().catch((e) => {
  console.error(e.message);
  process.exit(1);
});

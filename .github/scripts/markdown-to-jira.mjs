// GitHub Markdown to Jira wiki markup (what Jira's REST API v2 takes as a description).
// Covers what issues use: headings, bold, italic, strikethrough, inline code, code blocks, lists
// (nested, bullets and numbers, task lists), links, images, tables, quotes and rules. Code is never
// converted inside.

const PLACEHOLDER = "\u0000";

export function markdownToJira(markdown) {
  const lines = markdown.replace(/\r\n?/g, "\n").split("\n");
  const out = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];

    // Fenced code block: kept as it is.
    const fence = line.match(/^\s*(```|~~~)\s*([\w+#.-]*)\s*$/);
    if (fence) {
      const code = [];
      i++;
      while (i < lines.length && !lines[i].trim().startsWith(fence[1])) code.push(lines[i++]);
      i++;
      out.push(fence[2] ? `{code:${fence[2]}}` : "{code}", ...code, "{code}");
      continue;
    }

    // Table: a header row followed by a | --- | row.
    if (/^\s*\|.*\|\s*$/.test(line) && i + 1 < lines.length && /^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$/.test(lines[i + 1])) {
      out.push("||" + cells(line).map(inline).join("||") + "||");
      i += 2;
      while (i < lines.length && /^\s*\|.*\|\s*$/.test(lines[i])) {
        out.push("|" + cells(lines[i]).map((c) => inline(c) || " ").join("|") + "|");
        i++;
      }
      continue;
    }

    out.push(block(line));
    i++;
  }
  return out.join("\n");
}

function block(line) {
  let m;
  if ((m = line.match(/^(#{1,6})\s+(.*?)\s*#*\s*$/))) return `h${m[1].length}. ${inline(m[2])}`;
  if (/^\s*([-*_])(\s*\1){2,}\s*$/.test(line)) return "----";
  if ((m = line.match(/^\s*>\s?(.*)$/))) return `bq. ${inline(m[1])}`;
  // Lists: two spaces of indent per level (GitHub also accepts three or four for numbers).
  if ((m = line.match(/^(\s*)([-*+]|\d+[.)])\s+(.*)$/))) {
    const level = Math.floor(m[1].replace(/\t/g, "    ").length / 2) + 1;
    const marker = /\d/.test(m[2]) ? "#" : "*";
    let text = m[3];
    const task = text.match(/^\[([ xX])\]\s+(.*)$/);
    if (task) text = (task[1] === " " ? "(off) " : "(/) ") + task[2];
    return `${marker.repeat(level)} ${inline(text)}`;
  }
  return inline(line);
}

function inline(text) {
  // Inline code first, so nothing inside it is converted.
  const codes = [];
  let s = text.replace(/`([^`]+)`/g, (_, code) => {
    codes.push(code);
    return `${PLACEHOLDER}${codes.length - 1}${PLACEHOLDER}`;
  });
  s = s
    .replace(/!\[([^\]]*)\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g, "!$2!")
    .replace(/\[([^\]]+)\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g, "[$1|$2]")
    .replace(/<(https?:\/\/[^>\s]+)>/g, "[$1]")
    .replace(/(\*\*|__)(?=\S)(.+?)(?<=\S)\1/g, `${PLACEHOLDER}b$2${PLACEHOLDER}b`)
    .replace(/(^|[^*\w])\*(?=\S)([^*]+?)(?<=\S)\*(?!\*)/g, "$1_$2_")
    .replace(/(^|[^_\w])_(?=\S)([^_]+?)(?<=\S)_(?!\w)/g, "$1_$2_")
    .replace(/~~(?=\S)(.+?)(?<=\S)~~/g, "-$1-")
    .replace(new RegExp(`${PLACEHOLDER}b`, "g"), "*");
  return s.replace(new RegExp(`${PLACEHOLDER}(\\d+)${PLACEHOLDER}`, "g"), (_, n) => `{{${codes[Number(n)]}}}`);
}

function cells(row) {
  return row.trim().replace(/^\|/, "").replace(/\|$/, "").split("|").map((c) => c.trim());
}

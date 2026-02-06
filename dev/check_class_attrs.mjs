#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

const ROOT = process.cwd();
const SRC_DIR = path.join(ROOT, "src");
const IGNORED_DIRS = new Set([
  "node_modules",
  ".git",
  ".shadow-cljs",
  "out",
  "output",
  "tmp",
]);

const violations = [];

function walkCljsFiles(dir) {
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  const files = [];

  for (const entry of entries) {
    if (entry.isDirectory()) {
      if (IGNORED_DIRS.has(entry.name)) {
        continue;
      }
      files.push(...walkCljsFiles(path.join(dir, entry.name)));
      continue;
    }

    if (entry.isFile() && entry.name.endsWith(".cljs")) {
      files.push(path.join(dir, entry.name));
    }
  }

  return files;
}

function isBoundary(ch) {
  if (ch == null) {
    return true;
  }
  return /[\s,()[\]{}";]/.test(ch);
}

function skipWhitespaceAndComments(text, start) {
  let i = start;
  while (i < text.length) {
    const ch = text[i];
    if (/[\s,]/.test(ch)) {
      i += 1;
      continue;
    }
    if (ch === ";") {
      while (i < text.length && text[i] !== "\n") {
        i += 1;
      }
      continue;
    }
    break;
  }
  return i;
}

function parseStringEnd(text, start) {
  let i = start + 1;
  let escaped = false;
  while (i < text.length) {
    const ch = text[i];
    if (escaped) {
      escaped = false;
      i += 1;
      continue;
    }
    if (ch === "\\") {
      escaped = true;
      i += 1;
      continue;
    }
    if (ch === "\"") {
      return i + 1;
    }
    i += 1;
  }
  return text.length;
}

function parseFormEnd(text, start) {
  if (start >= text.length) {
    return text.length;
  }

  const pairs = { "(": ")", "[": "]", "{": "}" };
  const first = text[start];

  if (first === "\"") {
    return parseStringEnd(text, start);
  }

  if (pairs[first]) {
    const stack = [pairs[first]];
    let i = start + 1;
    let inString = false;
    let inComment = false;
    let escaped = false;

    while (i < text.length) {
      const ch = text[i];

      if (inComment) {
        if (ch === "\n") {
          inComment = false;
        }
        i += 1;
        continue;
      }

      if (inString) {
        if (escaped) {
          escaped = false;
          i += 1;
          continue;
        }
        if (ch === "\\") {
          escaped = true;
          i += 1;
          continue;
        }
        if (ch === "\"") {
          inString = false;
          i += 1;
          continue;
        }
        i += 1;
        continue;
      }

      if (ch === ";") {
        inComment = true;
        i += 1;
        continue;
      }

      if (ch === "\"") {
        inString = true;
        escaped = false;
        i += 1;
        continue;
      }

      if (pairs[ch]) {
        stack.push(pairs[ch]);
        i += 1;
        continue;
      }

      if (ch === stack[stack.length - 1]) {
        stack.pop();
        i += 1;
        if (stack.length === 0) {
          return i;
        }
        continue;
      }

      i += 1;
    }

    return text.length;
  }

  let i = start;
  while (i < text.length) {
    const ch = text[i];
    if (/[\s,()[\]{};]/.test(ch)) {
      break;
    }
    i += 1;
  }
  return i === start ? start + 1 : i;
}

function findClassKeywordPositions(text) {
  const positions = [];
  let i = 0;
  let inString = false;
  let inComment = false;
  let escaped = false;

  while (i < text.length) {
    const ch = text[i];

    if (inComment) {
      if (ch === "\n") {
        inComment = false;
      }
      i += 1;
      continue;
    }

    if (inString) {
      if (escaped) {
        escaped = false;
        i += 1;
        continue;
      }
      if (ch === "\\") {
        escaped = true;
        i += 1;
        continue;
      }
      if (ch === "\"") {
        inString = false;
      }
      i += 1;
      continue;
    }

    if (ch === ";") {
      inComment = true;
      i += 1;
      continue;
    }

    if (ch === "\"") {
      inString = true;
      escaped = false;
      i += 1;
      continue;
    }

    if (text.startsWith(":class", i)) {
      const prev = i > 0 ? text[i - 1] : undefined;
      const next = i + 6 < text.length ? text[i + 6] : undefined;
      if (isBoundary(prev) && isBoundary(next)) {
        positions.push(i);
      }
      i += 6;
      continue;
    }

    i += 1;
  }

  return positions;
}

function buildLineStarts(text) {
  const starts = [0];
  for (let i = 0; i < text.length; i += 1) {
    if (text[i] === "\n") {
      starts.push(i + 1);
    }
  }
  return starts;
}

function lineForIndex(lineStarts, index) {
  let low = 0;
  let high = lineStarts.length - 1;
  while (low <= high) {
    const mid = Math.floor((low + high) / 2);
    if (lineStarts[mid] <= index) {
      low = mid + 1;
    } else {
      high = mid - 1;
    }
  }
  return high + 1;
}

function collectViolationsInClassForm(filePath, text, lineStarts, formStart, formEnd) {
  let i = formStart;
  let inComment = false;

  while (i < formEnd) {
    const ch = text[i];

    if (inComment) {
      if (ch === "\n") {
        inComment = false;
      }
      i += 1;
      continue;
    }

    if (ch === ";") {
      inComment = true;
      i += 1;
      continue;
    }

    if (ch === "\"") {
      const strEnd = parseStringEnd(text, i);
      const value = text.slice(i + 1, Math.max(i + 1, strEnd - 1));
      if (/\s/.test(value)) {
        violations.push({
          filePath,
          line: lineForIndex(lineStarts, i),
          literal: value,
        });
      }
      i = strEnd;
      continue;
    }

    i += 1;
  }
}

function inspectFile(filePath) {
  const text = fs.readFileSync(filePath, "utf8");
  const lineStarts = buildLineStarts(text);
  const positions = findClassKeywordPositions(text);

  for (const keywordPos of positions) {
    const formStart = skipWhitespaceAndComments(text, keywordPos + 6);
    const formEnd = parseFormEnd(text, formStart);
    collectViolationsInClassForm(filePath, text, lineStarts, formStart, formEnd);
  }
}

function main() {
  if (!fs.existsSync(SRC_DIR)) {
    console.log("No src directory found; skipping class attr check.");
    return;
  }

  const files = walkCljsFiles(SRC_DIR);
  for (const filePath of files) {
    inspectFile(filePath);
  }

  if (violations.length > 0) {
    violations.sort((a, b) => {
      if (a.filePath !== b.filePath) {
        return a.filePath.localeCompare(b.filePath);
      }
      return a.line - b.line;
    });

    console.error("Found space-separated class strings in :class attrs:");
    for (const violation of violations) {
      const relPath = path.relative(ROOT, violation.filePath);
      console.error(
        `${relPath}:${violation.line} ${JSON.stringify(violation.literal)}`,
      );
    }
    process.exit(1);
  }

  console.log("No space-separated class strings found in :class attrs.");
}

main();

import { clamp } from "./util.mjs";

export function compileTextRules(textRules = []) {
  return textRules
    .map((rule) => {
      try {
        return {
          regex: new RegExp(rule.pattern, rule.flags || "g"),
          replace: rule.replace ?? ""
        };
      } catch (_err) {
        return null;
      }
    })
    .filter(Boolean);
}

export function normalizeText(raw, compiledRules = []) {
  if (typeof raw !== "string") {
    return "";
  }
  let text = raw.replace(/\s+/g, " ").trim();
  for (const rule of compiledRules) {
    text = text.replace(rule.regex, rule.replace);
  }
  return text;
}

export function normalizeClassList(className) {
  if (!className) {
    return [];
  }
  if (Array.isArray(className)) {
    return [...className].map((v) => String(v)).sort();
  }
  return String(className)
    .split(/\s+/)
    .map((item) => item.trim())
    .filter(Boolean)
    .sort();
}

export function semanticKey(node) {
  if (node?.parityId) {
    return `parity:${node.parityId}`;
  }
  if (node?.dataRole) {
    return `role:${node.dataRole}`;
  }
  return `path:${node.path || "unknown"}`;
}

export function nodeMasked(node) {
  return Boolean(node?.masked);
}

export function normalizeRect(rect, width, height) {
  const x = clamp(Math.round(rect.x || 0), 0, width);
  const y = clamp(Math.round(rect.y || 0), 0, height);
  const w = clamp(Math.round(rect.width || 0), 0, width - x);
  const h = clamp(Math.round(rect.height || 0), 0, height - y);
  if (w <= 0 || h <= 0) {
    return null;
  }
  return { x, y, width: w, height: h };
}

export function mergeMaskRects(leftRects = [], rightRects = [], width, height) {
  const all = [...leftRects, ...rightRects]
    .map((rect) => normalizeRect(rect, width, height))
    .filter(Boolean);

  const seen = new Set();
  const out = [];
  for (const rect of all) {
    const key = `${rect.x}:${rect.y}:${rect.width}:${rect.height}`;
    if (!seen.has(key)) {
      seen.add(key);
      out.push(rect);
    }
  }
  return out;
}

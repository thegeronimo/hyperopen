const SENSITIVE_KEY_RE = /(authorization|cookie|token|secret|password|signature|private|wallet|address|key)/i;
const HEX_ADDRESS_RE = /0x[a-fA-F0-9]{40}/g;
const LONG_HEX_RE = /0x[a-fA-F0-9]{64,}/g;
const JWT_RE = /[A-Za-z0-9-_]+\.[A-Za-z0-9-_]+\.[A-Za-z0-9-_]+/g;

function maskMiddle(value, keep = 4) {
  if (typeof value !== "string") {
    return value;
  }
  if (value.length <= keep * 2) {
    return "*".repeat(value.length);
  }
  return `${value.slice(0, keep)}***${value.slice(-keep)}`;
}

export function redactString(input) {
  if (typeof input !== "string") {
    return input;
  }

  let output = input;
  output = output.replace(HEX_ADDRESS_RE, (m) => `${m.slice(0, 6)}***${m.slice(-4)}`);
  output = output.replace(LONG_HEX_RE, (m) => `${m.slice(0, 10)}***${m.slice(-8)}`);
  output = output.replace(JWT_RE, (m) => maskMiddle(m, 6));
  return output;
}

export function redactHeaders(headers = {}) {
  const out = {};
  for (const [key, value] of Object.entries(headers || {})) {
    if (SENSITIVE_KEY_RE.test(key)) {
      out[key] = "<redacted>";
    } else if (Array.isArray(value)) {
      out[key] = value.map((item) => redactString(item));
    } else {
      out[key] = redactString(value);
    }
  }
  return out;
}

export function redactValue(value, keyHint = "", seen = new WeakSet()) {
  if (value === null || value === undefined) {
    return value;
  }

  if (typeof value === "string") {
    if (SENSITIVE_KEY_RE.test(keyHint)) {
      return "<redacted>";
    }
    return redactString(value);
  }

  if (typeof value !== "object") {
    return value;
  }

  if (seen.has(value)) {
    return "<circular>";
  }
  seen.add(value);

  if (Array.isArray(value)) {
    return value.map((item) => redactValue(item, keyHint, seen));
  }

  const out = {};
  for (const [key, child] of Object.entries(value)) {
    if (SENSITIVE_KEY_RE.test(key)) {
      out[key] = "<redacted>";
      continue;
    }
    if (key.toLowerCase() === "headers" && child && typeof child === "object") {
      out[key] = redactHeaders(child);
      continue;
    }
    out[key] = redactValue(child, key, seen);
  }
  return out;
}

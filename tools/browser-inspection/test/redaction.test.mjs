import test from "node:test";
import assert from "node:assert/strict";
import { redactHeaders, redactString, redactValue } from "../src/redaction.mjs";

test("redactHeaders masks sensitive keys", () => {
  const redacted = redactHeaders({ Authorization: "Bearer abc", Accept: "application/json" });
  assert.equal(redacted.Authorization, "<redacted>");
  assert.equal(redacted.Accept, "application/json");
});

test("redactString masks hex addresses", () => {
  const value = "wallet 0x1234567890abcdef1234567890abcdef12345678";
  const redacted = redactString(value);
  assert.match(redacted, /0x1234\*\*\*5678/);
});

test("redactValue walks nested objects", () => {
  const value = {
    token: "abc",
    nested: { signature: "0xabcdef", ok: "value" }
  };
  const out = redactValue(value);
  assert.equal(out.token, "<redacted>");
  assert.equal(out.nested.signature, "<redacted>");
  assert.equal(out.nested.ok, "value");
});

import test from "node:test";
import assert from "node:assert/strict";
import { compileTextRules, mergeMaskRects, normalizeClassList, normalizeText } from "../src/masking.mjs";

test("compileTextRules and normalizeText apply replacements", () => {
  const rules = compileTextRules([
    { pattern: "\\d+", flags: "g", replace: "<n>" }
  ]);
  const out = normalizeText("Price 123 and 456", rules);
  assert.equal(out, "Price <n> and <n>");
});

test("normalizeClassList sorts classes", () => {
  assert.deepEqual(normalizeClassList("b a c"), ["a", "b", "c"]);
});

test("mergeMaskRects removes duplicates", () => {
  const rects = mergeMaskRects(
    [{ x: 0, y: 0, width: 10, height: 10 }],
    [{ x: 0, y: 0, width: 10, height: 10 }],
    100,
    100
  );
  assert.equal(rects.length, 1);
});

// Every Playwright spec must run under the HyperEVM RPC guard, so it must
// take `test` from `guarded_test.mjs`, never from `@playwright/test` itself:
// a spec that did would open pages whose HyperEVM balance reads reach the
// public RPC with nothing to answer or catch them.
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const specDir = path.resolve(here, "../test");

function specFiles() {
  return fs
    .readdirSync(specDir)
    .filter((name) => name.endsWith(".spec.mjs"))
    .sort();
}

export function playwrightImportProblems(source) {
  const problems = [];
  if (/from\s+["']@playwright\/test["']/.test(source)) {
    problems.push("imports @playwright/test directly");
  }
  if (!/import\s*\{[^}]*\btest\b[^}]*\}\s*from\s*["']\.\.\/support\/guarded_test\.mjs["']/.test(source)) {
    problems.push("does not import `test` from ../support/guarded_test.mjs");
  }
  return problems;
}

test("the import check flags a spec that bypasses the guard", () => {
  assert.deepEqual(
    playwrightImportProblems('import { expect, test } from "@playwright/test";\n'),
    ["imports @playwright/test directly", "does not import `test` from ../support/guarded_test.mjs"]
  );
  assert.deepEqual(
    playwrightImportProblems('import { expect } from "../support/guarded_test.mjs";\nimport { test } from "@playwright/test";\n'),
    ["imports @playwright/test directly", "does not import `test` from ../support/guarded_test.mjs"]
  );
  assert.deepEqual(playwrightImportProblems('import { expect, test } from "../support/guarded_test.mjs";\n'), []);
});

test("every Playwright spec takes `test` from the HyperEVM-guarded fixture", () => {
  const files = specFiles();
  assert.ok(files.length > 0, `no specs found in ${specDir}`);
  const offenders = files
    .map((name) => [name, playwrightImportProblems(fs.readFileSync(path.join(specDir, name), "utf8"))])
    .filter(([, problems]) => problems.length > 0);
  assert.deepEqual(offenders, []);
});

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";

import {
  edits,
  patchOsqpSource,
  readUpstream,
  repoRoot,
  vendoredPath,
} from "./patch_osqp.mjs";

const vendored = () => fs.readFileSync(vendoredPath, "utf8");

test("every patch anchor still matches the installed osqp exactly once", () => {
  const upstream = readUpstream();
  for (const edit of edits) {
    const occurrences = upstream.split(edit.from).length - 1;
    assert.equal(
      occurrences,
      1,
      `anchor "${edit.name}" matched ${occurrences} times in node_modules/osqp. ` +
        "Upstream has changed shape -- re-derive the patch rather than adjusting this test.",
    );
  }
});

test("the committed vendored copy is exactly what the transform produces", () => {
  assert.equal(
    vendored(),
    patchOsqpSource(readUpstream()),
    "vendor/osqp/osqp_patched.mjs is stale. Run: node tools/optimizer/patch_osqp.mjs --write",
  );
});

test("the vendored copy frees the arrays the published package leaks", () => {
  const source = vendored();
  assert.match(
    source,
    /csc_array_pointers/,
    "the collector is missing -- the vendored copy is not actually patched",
  );
  assert.match(
    source,
    /for\(let \$k=0;\$k<\$p\.length;\$k\+\+\)D\._free\(\$p\[\$k\]\)/,
    "cleanup() no longer frees the collected pointers",
  );
  assert.ok(
    source.indexOf("D._osqp_cleanup(A)") < source.indexOf("csc_array_pointers;if($p)"),
    "the arrays are freed before _osqp_cleanup has released them",
  );
});

test("the published package is still the leaky one, so the patch is still needed", () => {
  const upstream = readUpstream();
  assert.doesNotMatch(
    upstream,
    /csc_array_pointers/,
    "upstream osqp appears to track the CSC arrays now -- check whether it frees " +
      "them, and if so drop vendor/osqp and the :js-options resolves entirely",
  );
});

function shadowCljsBuilds() {
  const source = fs.readFileSync(path.join(repoRoot, "shadow-cljs.edn"), "utf8");
  const buildsKey = source.indexOf(":builds");
  assert.notEqual(buildsKey, -1, "shadow-cljs.edn has no :builds map");

  const open = source.indexOf("{", buildsKey);
  let depth = 0;
  let inString = false;
  let inComment = false;
  const entries = [];
  let pendingName = null;
  let bodyStart = null;

  for (let i = open; i < source.length; i++) {
    const ch = source[i];
    if (inComment) {
      if (ch === "\n") inComment = false;
      continue;
    }
    if (inString) {
      if (ch === "\\") i++;
      else if (ch === '"') inString = false;
      continue;
    }
    if (ch === '"') { inString = true; continue; }
    if (ch === ";") { inComment = true; continue; }

    if (ch === "{") {
      depth++;
      if (depth === 2) {
        const before = source.slice(0, i);
        pendingName = before.match(/:([A-Za-z0-9*+!_'?<>=-]+)\s*$/)?.[1] ?? null;
        bodyStart = i;
      }
      continue;
    }
    if (ch === "}") {
      depth--;
      if (depth === 1 && pendingName !== null) {
        entries.push([pendingName, source.slice(bodyStart, i + 1)]);
        pendingName = null;
      }
      if (depth === 0) break;
      continue;
    }
  }
  return entries;
}

test("every shadow-cljs build resolves osqp to the vendored copy", () => {
  const builds = shadowCljsBuilds();
  assert.ok(builds.length >= 8, `expected to find the builds, found ${builds.length}`);

  const missing = builds
    .filter(([, body]) => !body.includes('"osqp"') || !body.includes("vendor/osqp/osqp_patched.mjs"))
    .map(([name]) => name);

  assert.deepEqual(
    missing,
    [],
    `these shadow-cljs builds do not resolve "osqp" to the vendored, leak-fixed copy: ` +
      `${missing.join(", ")}. Add :js-options {:resolve {"osqp" {:target :file ` +
      `:file "vendor/osqp/osqp_patched.mjs"}}} to each.`,
  );
});

test("the vendored copy passes eps_prim_inf to the solver", () => {
  assert.match(
    vendored(),
    /c\.eps_abs,c\.eps_rel,c\.eps_prim_inf,c\.eps_dual_inf,c\.alpha/,
    "the vendored copy no longer passes eps_prim_inf in the sixth settings slot",
  );
  assert.doesNotMatch(
    readUpstream(),
    /c\.eps_abs,c\.eps_rel,c\.eps_prim_inf,c\.eps_dual_inf/,
    "upstream osqp appears to pass eps_prim_inf correctly now -- drop this edit",
  );
});

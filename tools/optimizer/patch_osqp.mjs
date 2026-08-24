// Regenerates the vendored, heap-leak-fixed copy of the `osqp` package.
//
// WHY THIS EXISTS
//
// `osqp@0.0.2` leaks WebAssembly heap on every solve. Its CSC helper `o()`
// issues three `_malloc` calls per matrix -- one each for the values, the row
// indices and the column pointers -- and returns only the pointer to the `csc`
// struct that wraps them. `cleanup()` frees that struct and the three vectors
// allocated by `w()`, but never the six arrays behind the two matrices.
//
// The module is built against a fixed, non-growable 16 MiB heap whose
// `emscripten_resize_heap` aborts rather than grows, so the leak is a hard
// per-run solve budget rather than a slow degradation. Measured against the
// production problem shape (long-only bounds plus a turnover cap, so
// problem_adapter takes the split-variable path and var-count is 4N), the
// module aborts after 36 solves at N=100 and after 13 at N=150. A default run
// issues 56. `infrastructure/osqp.cljs` catches the abort and falls back to
// pure-JavaScript quadprog, so the symptom is not a crash -- it is a large run
// quietly finishing on the solver the CSP fix in 9d85e56c0 existed to escape.
//
// The package cannot be upgraded out of this: 0.0.2 is `latest` and was last
// published 2022-05-12. It also cannot be fixed from our side at runtime. The
// Emscripten Module is closure-private (`let D` in the bundle, never exported),
// so `_free` and the heap views are unreachable from application code, and
// `o()` discards the three pointers before anything we can observe. Patching
// the package is the only way to free them.
//
// WHAT THE PATCH CHANGES
//
// Four edits for the leak, all mechanical: `o()` takes a collector array and
// pushes the three pointers it allocates; `setup()` passes one collector to
// both `o()` calls and stores it on `_state`; `cleanup()` frees everything in
// it, after `_osqp_cleanup` and `_cleanup_data` have already torn down the
// workspace that referenced them. No numerics are touched.
//
// One further edit for an unrelated defect in the same package: `setup()`
// passed `eps_dual_inf` into both the sixth and seventh slots of
// `_create_settings`, so `eps_prim_inf` was silently dropped. That one is a
// no-op at our current settings -- we set neither tolerance, so both take the
// same 1e-4 default -- and exists to stop the setting being a silent no-op if
// it is ever wanted.
//
// WHY IT IS VENDORED, AND WHY IT IS WIRED IN THROUGH :js-options
//
// Rewriting node_modules from a lifecycle hook was the alternative. It loses on
// `npm run build`, which a fresh CI checkout runs with no hook we could hang the
// rewrite off, and it makes the state of an install invisible.
//
// Given a committed artifact, the remaining question is how the build finds it,
// and the obvious answer is wrong in a way that only shows up in production. A
// relative require of this file from osqp.cljs cannot be forgotten by any build
// and compiles clean -- but it routes the file through Closure's `closure-js`
// pipeline instead of shadow-cljs's `shadow-js` pipeline, and `:advanced` then
// renames the Emscripten glue. Measured in the release bundle built that way:
// `_malloc`, `HEAPF64` and the `asm.j` WASM export accessor go from 7, 7 and 1
// occurrences to zero. It works in development and throws in production.
//
// So the wiring is `:js-options {:resolve {"osqp" ...}}`, which keeps the file
// on the npm path where Closure leaves it alone. That is per-build, and the
// optimizer is compiled by eight builds, so one omission silently reinstates
// the leak -- and a top-level `:js-options` does NOT cover them, which was
// tried: `portfolio-optimizer-worker` still resolved the published package,
// with no warning. `patch_osqp.test.mjs` therefore asserts that EVERY build
// resolves to this file, and re-runs this transform against the installed
// package so bumping `osqp` cannot leave a stale copy behind either.

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../..",
);

export const upstreamPath = path.join(
  repoRoot,
  "node_modules/osqp/dist/osqp.min.js",
);

export const vendoredPath = path.join(
  repoRoot,
  "vendor/osqp/osqp_patched.mjs",
);

// Each edit is anchored on a byte-exact slice of the minified bundle and must
// match exactly once. An `osqp` release that renames a minifier temporary or
// reshapes `setup()` will fail the arity check here rather than produce a file
// that looks patched and is not.
export const edits = [
  {
    name: "o() collects the pointers it allocates",
    from:
      "function o(A,I,g){const C=D._malloc(8*A.data.length);D.HEAPF64.set(A.data,C/8);" +
      "const Q=D._malloc(4*A.row_indices.length);D.HEAP32.set(A.row_indices,Q/4);" +
      "const B=D._malloc(4*A.column_pointers.length);" +
      "return D.HEAP32.set(A.column_pointers,B/4),D._create_csc_matrix(I,g,A.data.length,C,Q,B)}",
    to:
      "function o(A,I,g,$csc){const C=D._malloc(8*A.data.length);D.HEAPF64.set(A.data,C/8);" +
      "const Q=D._malloc(4*A.row_indices.length);D.HEAP32.set(A.row_indices,Q/4);" +
      "const B=D._malloc(4*A.column_pointers.length);" +
      "return D.HEAP32.set(A.column_pointers,B/4),$csc&&$csc.push(C,Q,B)," +
      "D._create_csc_matrix(I,g,A.data.length,C,Q,B)}",
  },
  {
    name: "setup() threads one collector through both matrices",
    from:
      "const{P:C,A:B,q:i,l:R,u:s}=I,F=i.length,h=R.length,K=o(C,F,F),y=o(B,h,F),",
    to:
      "const $csc=[],{P:C,A:B,q:i,l:R,u:s}=I,F=i.length,h=R.length," +
      "K=o(C,F,F,$csc),y=o(B,h,F,$csc),",
  },
  {
    name: "the collector is reachable from _state",
    from: "pointer_to_u:S,l:R,u:s,num_variables:F}",
    to: "pointer_to_u:S,l:R,u:s,num_variables:F,csc_array_pointers:$csc}",
  },
  {
    // A second defect in the same package, unrelated to the leak. `setup()`
    // passes `eps_dual_inf` into BOTH the sixth and seventh parameters of
    // `_create_settings`, so `eps_prim_inf` never reaches the solver at all.
    //
    // The sixth slot is `eps_prim_inf`: it is where the package's own
    // `SettingsConfig` interface puts it, where OSQP's `OSQPSettings` struct
    // puts it, and -- measured -- where a value behaves like a primal
    // infeasibility tolerance. On a primal-infeasible problem, patching this
    // slot and then loosening `eps_prim_inf` from 1e-15 to 1e-1 detects the
    // infeasibility 2.7x sooner, while leaving the slot unpatched makes the
    // same change do nothing (0.82x, inside noise). A feasible problem is
    // unaffected either way, which rules out the slot being `alpha`.
    //
    // No behaviour change today: `osqp.cljs/settings` sets neither tolerance,
    // so both come from `SettingsDefault` where both are 1e-4 and the value
    // landing in slot six is identical before and after. This makes the
    // setting usable rather than changing what it currently does.
    name: "setup() passes eps_prim_inf rather than eps_dual_inf twice",
    from: "c.eps_abs,c.eps_rel,c.eps_dual_inf,c.eps_dual_inf,c.alpha",
    to: "c.eps_abs,c.eps_rel,c.eps_prim_inf,c.eps_dual_inf,c.alpha",
  },
  {
    // Appended after the existing frees on purpose: the arrays are still
    // referenced by the workspace and the data struct until `_osqp_cleanup` and
    // `_cleanup_data` have run. Emptying the collector makes a second
    // `cleanup()` a no-op rather than a double free.
    name: "cleanup() frees the six leaked arrays",
    from:
      "D._osqp_cleanup(A),D._cleanup_data(I),D._cleanup_settings(g)," +
      "D._free(C),D._free(Q),D._free(B),D._free(E),D._free(i)}}",
    to:
      "D._osqp_cleanup(A),D._cleanup_data(I),D._cleanup_settings(g)," +
      "D._free(C),D._free(Q),D._free(B),D._free(E),D._free(i);" +
      "const $p=this._state.csc_array_pointers;" +
      "if($p){for(let $k=0;$k<$p.length;$k++)D._free($p[$k]);$p.length=0}}}",
  },
];

const banner = [
  "// GENERATED -- do not edit by hand.",
  "// `osqp@0.0.2` with its per-solve WebAssembly heap leak fixed.",
  "// Regenerate with: node tools/optimizer/patch_osqp.mjs --write",
  "// Rationale and the exact edits: tools/optimizer/patch_osqp.mjs",
  "",
].join("\n");

/** Applies every edit to `source`, throwing if an anchor does not match exactly once. */
export function patchOsqpSource(source) {
  // The published bundle points at a sourcemap that is not published with it.
  // Harmless in node_modules, but as a classpath resource shadow-cljs tries to
  // resolve it and warns on every compile.
  let out = source.replace(/\n?\/\/# sourceMappingURL=osqp\.min\.js\.map\s*$/, "\n");
  for (const edit of edits) {
    const occurrences = out.split(edit.from).length - 1;
    if (occurrences !== 1) {
      throw new Error(
        `osqp patch anchor "${edit.name}" matched ${occurrences} times, expected exactly 1. ` +
          "The upstream package has changed shape; re-derive the patch before trusting it.",
      );
    }
    out = out.replace(edit.from, edit.to);
  }
  return banner + out;
}

export function readUpstream() {
  return fs.readFileSync(upstreamPath, "utf8");
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const patched = patchOsqpSource(readUpstream());
  if (process.argv.includes("--write")) {
    fs.mkdirSync(path.dirname(vendoredPath), { recursive: true });
    fs.writeFileSync(vendoredPath, patched);
    console.log(`wrote ${path.relative(repoRoot, vendoredPath)} (${patched.length} bytes)`);
  } else {
    const current = fs.existsSync(vendoredPath)
      ? fs.readFileSync(vendoredPath, "utf8")
      : null;
    if (current === patched) {
      console.log("vendor/osqp/osqp-patched.mjs is up to date");
    } else {
      console.error(
        "vendor/osqp/osqp-patched.mjs is stale. Run: node tools/optimizer/patch_osqp.mjs --write",
      );
      process.exit(1);
    }
  }
}

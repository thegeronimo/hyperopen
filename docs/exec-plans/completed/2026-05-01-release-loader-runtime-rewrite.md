# Fix Release Loader Runtime Rewrite

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md`. The live `bd` issue for this work is `hyperopen-yk1a`.

## Purpose / Big Picture

Cloudflare Pages failed to deploy Hyperopen on 2026-05-01 even though Shadow CLJS compiled the application and worker builds successfully. The failure happened in the release artifact generator, which rewrites Shadow CLJS module loading so deployed route chunks load through script tags instead of eval-like source URL injection under the production content security policy.

After this change, `npm run build` should complete and produce `out/release-public` again. The generated main JavaScript bundle should have the Shadow module loader configured for script-tag loading even when Closure Advanced renames private fields differently from previous builds. The release artifact root should also include the portfolio optimizer worker that the current app can request.

## Progress

- [x] (2026-05-01 19:08Z) Created and claimed `bd` issue `hyperopen-yk1a`.
- [x] (2026-05-01 19:09Z) Wrote this active ExecPlan with scope, evidence, and validation expectations.
- [x] (2026-05-01 19:11Z) Added failing release-asset tests for inferred Shadow loader flag names and the optimizer worker release asset.
- [x] (2026-05-01 19:11Z) Verified the new tests fail for the current implementation with three expected failures.
- [x] (2026-05-01 19:13Z) Implemented the minimal release generator changes.
- [x] (2026-05-01 19:16Z) Verified the focused release-asset tests pass, including end-to-end current-minifier and missing optimizer worker coverage.
- [x] (2026-05-01 19:15Z) Ran `npm run build` and confirmed the original deployment failure is gone.
- [x] (2026-05-01 19:21Z) Ran required gates after code changes: `npm run check`, `npm test`, and `npm run test:websocket`.
- [x] (2026-05-01 19:22Z) Prepared this ExecPlan for completion after validation passed.

## Surprises & Discoveries

- Observation: The deployment log's first fatal error is not from Shadow CLJS compilation. It occurs after all release builds report `0 warnings`, when `tools/release-assets/generate_release_artifacts.mjs` rewrites the generated main bundle.
  Evidence: The log contains `[:vault-detail-worker] Build completed` followed by `Error: Expected the main module to contain the shadow-cljs eval-based loader runtime.`

- Observation: The current generated main bundle still has the Shadow module loader runtime, but Closure renamed the relevant private flags. The actual runtime assignment is `var qic=new Wca;qic.yk=!0`, while the release generator only knows about `.pk` and `.tk`.
  Evidence: Local `npm run build` reproduced the same failure and inspecting `resources/public/js/main.E2FA7D58D0BDB324770E4EBF0FF034FB.js` showed `var qic=new Wca;qic.yk=!0`.

- Observation: A bisect found `45658436 Save optimizer scenarios locally` as the first commit where the generated main bundle no longer matches the hardcoded `.pk` pattern. That commit did not edit the release generator; it changed enough application code to perturb Closure's private-name allocation.
  Evidence: The bisect checked `rewriteMainModuleLoaderRuntime` against `npx shadow-cljs --force-spawn release app`; `cd4dc123` passed and `45658436` failed with `var A8b=new Wca;A8b.yk=!0`.

- Observation: The release JavaScript collection currently omits `portfolio_optimizer_worker.js`, even though `npm run build` compiles `portfolio-optimizer-worker`.
  Evidence: Running `collectReleaseJavaScriptFiles` against current generated metadata listed `portfolio_worker.js` and `vault_detail_worker.js`, but not `portfolio_optimizer_worker.js`.

- Observation: After the implementation, the live release build rewrites the actual generated main module from source URL injection to script-tag loading and copies the optimizer worker.
  Evidence: `npm run build` exited 0 and printed `Generated release artifacts in /Users/barry/.codex/worktrees/dc1b/hyperopen/out/release-public with main.EB9A4BFB8D67DCBE.css`; inspecting the generated files showed input `var qic=new Wca;qic.yk=!0`, output `var qic=new Wca;qic.Ck=!0`, and `out/release-public/js/portfolio_optimizer_worker.js` present.

## Decision Log

- Decision: Fix the release generator by inferring minified Shadow `ModuleLoader` field names from the generated prototype defaults instead of adding another hardcoded `.yk -> .Ck` special case.
  Rationale: Closure Advanced private property names are not stable. The generated bundle contains the prototype defaults in source order: debug mode, source URL injection, then script-tag loading. Deriving names from that local shape preserves the existing fail-closed behavior without depending on one minified spelling.
  Date/Author: 2026-05-01 / Codex

- Decision: Include `portfolio_optimizer_worker.js` in the required worker files for release artifact generation.
  Rationale: The app now compiles and can request this worker. Leaving it out would make a successful deployment serve a release root with a missing optimizer worker asset.
  Date/Author: 2026-05-01 / Codex

## Outcomes & Retrospective

The release build is restored. `npm run build` now exits 0, writes `out/release-public`, rewrites the actual generated Shadow loader assignment from `var qic=new Wca;qic.yk=!0` to `var qic=new Wca;qic.Ck=!0`, and copies `out/release-public/js/portfolio_optimizer_worker.js`.

The release-asset test suite now covers the old `.pk/.tk` fixture, the current `.yk/.Ck` fixture, idempotency when the script-tag flag is already enabled, explicit optimizer worker collection, end-to-end generation with the current minifier shape, and fail-closed behavior when the optimizer worker is missing.

The implementation increases local complexity inside `rewriteMainModuleLoaderRuntime` because it now infers minified loader field names instead of replacing a fixed string. That complexity is justified by the root cause: Closure Advanced private names are unstable, and the previous fixed-name implementation failed when unrelated application code changed the minified property allocation. The release generator remains fail-closed when it cannot prove the loader shape.

## Context and Orientation

The Cloudflare Pages build runs `bash ./tools/pages-build.sh`. That script installs or locates Java and then runs `npm run build`. The `build` script in `package.json` refreshes the build id, builds CSS, runs `npx shadow-cljs release app portfolio-worker portfolio-optimizer-worker vault-detail-worker`, and finally runs `node tools/release-assets/generate_release_artifacts.mjs`.

Shadow CLJS is the ClojureScript build tool. The `:app` build in `shadow-cljs.edn` emits hashed JavaScript modules and metadata under `resources/public/js`, including `manifest.json` and `module-loader.json`. The release artifact generator reads those files and writes a deployment root under `out/release-public`.

The function `rewriteMainModuleLoaderRuntime` in `tools/release-assets/generate_release_artifacts.mjs` currently searches the generated main bundle for a minified string shaped like `var loader=new Ctor;loader.pk=!0;` and rewrites it to `loader.tk=!0;`. In older generated bundles, `.pk` was Shadow's source URL injection flag and `.tk` was Shadow's script-tag loading flag. In the current generated bundle, those names are `.yk` and `.Ck`. The robust fix is to infer the current names from the generated `ModuleLoader` prototype defaults for the same constructor.

The release generator also has `REQUIRED_WORKER_FILES`. These stable worker filenames are copied into `out/release-public/js` even though they are not route modules listed in `module-loader.json`. The optimizer worker is now compiled as `portfolio_optimizer_worker.js` and must be included there.

## Plan of Work

First, extend `tools/release-assets/generate_release_artifacts.test.mjs` with RED tests. Add one unit test that passes a minimal generated-bundle excerpt with prototype defaults like `$APP.d=Wca.prototype;$APP.d.ak=!1;$APP.d.yk=!1;$APP.d.Ck=!1;` plus `var loaderManager=new Wca;loaderManager.yk=!0;` and expects the result to set `loaderManager.Ck=!0`. Add another test proving the rewrite remains idempotent when the input already sets the inferred script-tag flag. Update the release file collection test to expect `portfolio_optimizer_worker.js`.

Second, run `npm run test:release-assets` and verify those tests fail against the current implementation for the expected reasons: the loader rewrite cannot find `.pk`, and the worker list lacks `portfolio_optimizer_worker.js`.

Third, update `tools/release-assets/generate_release_artifacts.mjs`. Replace the fixed `SHADOW_LOADER_EVAL_RUNTIME_PATTERN` and `SHADOW_LOADER_SCRIPT_TAG_RUNTIME_PATTERN` approach with a helper that scans for loader assignments shaped like `var <loader>=new <ctor>;<loader>.<flag>=!0;`, finds the same constructor's prototype defaults shaped like `<proto>=<ctor>.prototype;<proto>.<debug>=!1;<proto>.<sourceUrlInjection>=!1;<proto>.<useScriptTags>=!1;`, and rewrites only when the assignment flag equals the inferred source URL injection flag. If the assignment flag already equals the inferred script-tag flag, return the source unchanged. If no valid match exists, keep the existing fail-closed error.

Fourth, add `portfolio_optimizer_worker.js` to `REQUIRED_WORKER_FILES`.

Fifth, run focused validation. `npm run test:release-assets` must pass. `npm run build` must pass and print `Generated release artifacts in ...`.

Finally, run the required repository gates because code changed: `npm run check`, `npm test`, and `npm run test:websocket`. If they pass, update this plan's living sections, move it to `docs/exec-plans/completed/`, and close `hyperopen-yk1a`.

## Concrete Steps

All commands run from `/Users/barry/.codex/worktrees/dc1b/hyperopen`.

1. Add failing tests:

       npm run test:release-assets

   Expected before implementation: the new loader test fails with the existing `Expected the main module to contain the shadow-cljs eval-based loader runtime.` error or the worker collection assertion fails because `portfolio_optimizer_worker.js` is absent.

2. Implement the release generator changes in `tools/release-assets/generate_release_artifacts.mjs`.

3. Run focused validation:

       npm run test:release-assets
       npm run build

   Result after implementation: `npm run test:release-assets` passed with 28 tests. `npm run build` exited 0 and printed `Generated release artifacts in /Users/barry/.codex/worktrees/dc1b/hyperopen/out/release-public with main.EB9A4BFB8D67DCBE.css`.

4. Run full required validation:

       npm run check
       npm test
       npm run test:websocket

   Result after implementation: all commands exited 0. `npm test` reported `3680 tests containing 20281 assertions. 0 failures, 0 errors.` `npm run test:websocket` reported `461 tests containing 2798 assertions. 0 failures, 0 errors.`

## Validation and Acceptance

Acceptance criterion 1: `npm run test:release-assets` contains a regression test for a generated Shadow loader assignment using `.yk` as source URL injection and `.Ck` as script-tag loading. The test must fail before the implementation and pass after.

Acceptance criterion 2: `npm run build` must no longer fail with `Expected the main module to contain the shadow-cljs eval-based loader runtime.`

Acceptance criterion 3: `out/release-public/js/portfolio_optimizer_worker.js` must exist after `npm run build`.

Acceptance criterion 4: The required gates `npm run check`, `npm test`, and `npm run test:websocket` must pass, or any blocker must be captured here with the exact error.

All four acceptance criteria passed on 2026-05-01.

## Idempotence and Recovery

The edits are safe to rerun. `npm run build` overwrites generated files under `resources/public/css`, `resources/public/js`, and `out/release-public`. Those paths are generated artifacts and should not be committed unless the repository already tracks a file there. If a validation command fails, keep this ExecPlan active, record the failure in `Surprises & Discoveries`, and either adjust the tests or implementation based on the observed evidence.

If the loader rewrite becomes uncertain, do not broaden it to blindly rewrite every `var x=new Y;x.<flag>=!0` assignment. Keep the fail-closed error so release generation refuses bundles it cannot prove safe.

## Artifacts and Notes

Primary failure from the deployment log:

    Error: Expected the main module to contain the shadow-cljs eval-based loader runtime.
        at rewriteMainModuleLoaderRuntime (tools/release-assets/generate_release_artifacts.mjs:259:11)

Local generated assignment from the current main bundle:

    var qic=new Wca;qic.yk=!0
    $APP.d=Wca.prototype;$APP.d.ak=!1;$APP.d.yk=!1;$APP.d.Ck=!1;

Post-fix release output from the same main bundle:

    var qic=new Wca;qic.Ck=!0
    out/release-public/js/portfolio_optimizer_worker.js exists

Bisect result:

    456584369b6aba369fb5cd59a2003fd37e601328 is the first bad commit
    commit 456584369b6aba369fb5cd59a2003fd37e601328
    Save optimizer scenarios locally

## Interfaces and Dependencies

The public JavaScript interface is unchanged. The internal function `rewriteMainModuleLoaderRuntime(mainModuleSource)` must continue to accept a string and return the rewritten string or throw if the bundle does not contain a recognizable Shadow module loader runtime.

The release generator must continue to write the same deployment root and headers, except it now must also copy `portfolio_optimizer_worker.js` when present and fail if the required worker is missing.

## Revision Notes

2026-05-01: Created the plan, implemented the fix, recorded validation evidence, and marked the plan ready to move from active to completed because the build and required gates passed.

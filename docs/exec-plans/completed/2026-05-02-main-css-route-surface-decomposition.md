# Decompose main.css by route and surface

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This plan follows `/hyperopen/.agents/PLANS.md` and the public planning contract in `/hyperopen/docs/PLANS.md`. The live `bd` tracking item is `hyperopen-rkyz`.

## Purpose / Big Picture

`/hyperopen/src/styles/main.css` is the Tailwind CSS entrypoint for the app and is currently 3,346 lines long. The optimizer route styles start near line 108 and dominate the file, which makes unrelated UI changes harder to review and increases the chance of accidental cascade regressions. After this change, `main.css` is a small import manifest, and the actual style rules live in focused source files for base styles, optimizer, app shell, account, trading, chart, vaults, and utilities.

The behavior must remain unchanged. The primary proof is that `npm run css:build` writes the same minified `/hyperopen/resources/public/css/main.css` before and after the split. Additional source-organization tests guard the new structure.

## Progress

- [x] (2026-05-02 01:45Z) Created live `bd` item `hyperopen-rkyz` for this refactor.
- [x] (2026-05-02 01:45Z) Mapped the current CSS entrypoint and confirmed `npm run css:build` uses Tailwind CLI with `/hyperopen/src/styles/main.css` as input and `/hyperopen/resources/public/css/main.css` as generated output.
- [x] (2026-05-02 01:45Z) Identified current top-level ranges: font/Tailwind/base rules at lines 1-84, `@layer components` at lines 86-1920, optimizer rules starting at line 108, and `@layer utilities` at lines 1922-3346.
- [x] (2026-05-02 01:45Z) Wrote this active ExecPlan.
- [x] (2026-05-02 01:50Z) Added `tools/styles/main_css_split.test.mjs`, wired `npm run test:styles` into `npm run check`, and verified RED: `npm run test:styles` fails because `main.css` has 2,884 significant lines instead of 10 imports.
- [x] (2026-05-02 01:51Z) Captured pre-split generated CSS baseline at `tmp/css-split/main.before.css`; SHA-256 is `eb9a4bfb8d67dcbe39eb405517b1307fbe97b7953f605902bcd6091a806c316b`.
- [x] (2026-05-02 01:54Z) Split the CSS source into `base.css` plus route/surface files under `src/styles/surfaces/`, with `main.css` reduced to the import manifest.
- [x] (2026-05-02 01:55Z) Rebuilt CSS and compared generated output against the baseline; `cmp -s tmp/css-split/main.before.css resources/public/css/main.css` exited 0 and the SHA-256 remained `eb9a4bfb8d67dcbe39eb405517b1307fbe97b7953f605902bcd6091a806c316b`.
- [x] (2026-05-02 02:02Z) Fixed existing source-level typography tests to resolve CSS imports recursively after the split.
- [x] (2026-05-02 02:05Z) Ran required gates: `npm run check`, `npm test`, and `npm run test:websocket` all exited 0.
- [x] (2026-05-02 02:09Z) Addressed reviewer feedback by making `npm run test:styles` assert Tailwind directives and compile the split stylesheet through Tailwind CLI into a temporary output.
- [x] (2026-05-02 02:13Z) Re-ran required gates after the reviewer-feedback test change: `npm run check`, `npm test`, and `npm run test:websocket` all exited 0.
- [x] (2026-05-02 02:14Z) Completed review and final evidence updates. This plan is ready to move to `/hyperopen/docs/exec-plans/completed/`.

## Surprises & Discoveries

- Observation: There is no Vite CSS path for this repository. Tailwind CLI is the only CSS build path in `package.json`.
  Evidence: `package.json` defines `css:build` as `tailwindcss -i ./src/styles/main.css -o ./resources/public/css/main.css --minify`.
- Observation: Tailwind 3.4.17 includes `postcss-import` in its dependency tree, so local CSS imports are a supported shape for this entrypoint.
  Evidence: `package-lock.json` contains `node_modules/postcss-import` and `node_modules/tailwindcss` depends on `postcss-import`.
- Observation: The requested `vaults` bucket has no obvious standalone selector block in the current `main.css`.
  Evidence: searching `main.css` for `vault` only finds optimizer-scoped frontier marker naming such as `.portfolio-frontier-vault-box`.
- Observation: Existing typography contract tests read `src/styles/main.css` directly.
  Evidence: The first post-split `npm test` run failed 17 assertions in `hyperopen.views.typography-scale-test` because the tests saw only the import manifest. Updating the helper to resolve local CSS imports restored the same contract assertions against the composed source.
- Observation: The first version of `test:styles` guarded only file shape, not stylesheet compilability.
  Evidence: Read-only review noted that removing `@tailwind utilities;` or adding invalid CSS in an imported file would not be caught by the manifest-only test. The test now checks Tailwind directives in `base.css` and runs Tailwind CLI against `main.css`.

## Decision Log

- Decision: Keep `main.css` as a manifest of top-level `@import` statements and move all Tailwind directives into the first imported file, `/hyperopen/src/styles/base.css`.
  Rationale: CSS imports must appear before non-import statements in the importing stylesheet. A manifest-only entrypoint keeps import ordering explicit and lets Tailwind inline the source files before processing `@tailwind` and `@layer`.
  Date/Author: 2026-05-02 / Codex
- Decision: Preserve original source order over ideal taxonomy when those two goals conflict.
  Rationale: The issue requires preserving the same compiled output. CSS cascade order is observable behavior, so moving disjoint trading or app-shell chunks together is not safe unless the generated output comparison proves no change.
  Date/Author: 2026-05-02 / Codex
- Decision: Add a small source-organization test rather than a permanent golden generated CSS fixture.
  Rationale: The generated CSS is ignored and large. Byte-for-byte preservation is best verified during this refactor with a temporary baseline, while the committed test should enforce the maintainable source shape going forward.
  Date/Author: 2026-05-02 / Codex
- Decision: Update `hyperopen.views.typography-scale-test` to compose imported CSS sources before applying its existing regex contracts.
  Rationale: Those tests are source-level design contract tests, not tests of the entrypoint file shape. Resolving imports keeps the same assertions meaningful after `main.css` becomes an import manifest.
  Date/Author: 2026-05-02 / Codex

## Outcomes & Retrospective

The implementation now splits the former monolithic stylesheet into focused imported files while preserving generated CSS output byte-for-byte. Complexity is reduced for future CSS work because route and surface ownership is visible in filenames and guarded by `npm run test:styles`; the build path remains unchanged. Browser QA is intentionally skipped because the compiled stylesheet hash is identical before and after the split, so this is source organization rather than a UI behavior change.

## Context and Orientation

The app loads `/css/main.css` from `/hyperopen/resources/public/index.html`. That file is generated, not source-controlled, from `/hyperopen/src/styles/main.css`. The build command is:

    cd /hyperopen
    npm run css:build

The `css:build` script runs:

    tailwindcss -i ./src/styles/main.css -o ./resources/public/css/main.css --minify

Tailwind CSS processes three special directives: `@tailwind base`, `@tailwind components`, and `@tailwind utilities`. It also processes `@layer base`, `@layer components`, and `@layer utilities` blocks and emits those rules in Tailwind's layer order. Within a layer, source order still matters for cascade conflicts. This plan therefore preserves the current order of rules inside each layer.

The current source file has this shape:

- `/hyperopen/src/styles/main.css` lines 1-23: font faces.
- `/hyperopen/src/styles/main.css` lines 25-27: Tailwind directives.
- `/hyperopen/src/styles/main.css` lines 30-84: `@layer base`.
- `/hyperopen/src/styles/main.css` lines 86-1920: `@layer components`.
- `/hyperopen/src/styles/main.css` lines 108-762: optimizer-specific component rules inside the components layer.
- `/hyperopen/src/styles/main.css` lines 1922-3346: `@layer utilities`.

The required source files are:

- `/hyperopen/src/styles/base.css` for font faces, Tailwind directives, and base layer tokens/html/body rules.
- `/hyperopen/src/styles/surfaces/trading.css` for the initial generic trading component classes that currently appear before the optimizer block.
- `/hyperopen/src/styles/surfaces/optimizer.css` for the existing `.portfolio-optimizer-v4` block.
- `/hyperopen/src/styles/surfaces/app-shell.css` for the contiguous app-shell and global UI component block after the optimizer, including header nav, dropdowns, confirmation animations, global toasts, order notification surfaces, loading shimmer, and reduced-motion rules.
- `/hyperopen/src/styles/surfaces/account.css` for account tab strip styles.
- `/hyperopen/src/styles/surfaces/trading-controls.css` for order size sliders, leverage sliders, trade toggles, order book rows, and dense trading tables.
- `/hyperopen/src/styles/surfaces/chart.css` for chart container and lightweight chart cursor styling.
- `/hyperopen/src/styles/surfaces/trading-layout.css` for data-table and trading grid layout rules that currently follow the chart styles.
- `/hyperopen/src/styles/surfaces/utilities.css` for the current `@layer utilities` block.
- `/hyperopen/src/styles/surfaces/vaults.css` as the vault route bucket. It may contain only a short source comment in this refactor because no standalone vault route rules currently exist in `main.css`; comments do not affect minified output.

The final `/hyperopen/src/styles/main.css` import order must be:

    @import "./base.css";
    @import "./surfaces/trading.css";
    @import "./surfaces/optimizer.css";
    @import "./surfaces/app-shell.css";
    @import "./surfaces/account.css";
    @import "./surfaces/trading-controls.css";
    @import "./surfaces/chart.css";
    @import "./surfaces/trading-layout.css";
    @import "./surfaces/utilities.css";
    @import "./surfaces/vaults.css";

This order mirrors the current source order. The empty or comment-only vaults file is imported last so it cannot change output.

## Plan of Work

First, add a source-organization regression test under `/hyperopen/tools/styles/`. The test should read `/hyperopen/src/styles/main.css`, confirm that it is a short import manifest with the exact imports above, and confirm that every imported file exists. Add an `npm` script named `test:styles` and include it in `npm run check` so future large CSS edits keep the split structure. Run `npm run test:styles` before the split and observe it fail because `main.css` still contains thousands of lines and no import manifest.

Second, capture the generated CSS baseline. If dependencies are not installed, run `npm ci` once. Then run:

    cd /hyperopen
    npm run css:build
    mkdir -p tmp/css-split
    cp resources/public/css/main.css tmp/css-split/main.before.css
    shasum -a 256 tmp/css-split/main.before.css

Record the hash in this plan's `Artifacts and Notes` section.

Third, split `/hyperopen/src/styles/main.css` without reformatting CSS rule bodies. Use mechanical extraction and preserve exact source text as much as possible. Each imported file should contain complete `@layer` blocks rather than relying on braces spread across files. Do not normalize colors, selectors, comments, or whitespace unless the extraction requires adding the opening or closing `@layer` wrapper.

Fourth, run:

    cd /hyperopen
    npm run test:styles
    npm run css:build
    cmp -s tmp/css-split/main.before.css resources/public/css/main.css
    shasum -a 256 resources/public/css/main.css

The source test should pass. `cmp -s` should exit 0, proving the generated CSS is byte-for-byte identical to the baseline. If the compare fails, inspect with `diff -u tmp/css-split/main.before.css resources/public/css/main.css | sed -n '1,120p'` and fix import order or extraction boundaries before proceeding.

Finally, run the repository gates required by `/hyperopen/AGENTS.md` for code changes:

    cd /hyperopen
    npm run check
    npm test
    npm run test:websocket

Because this is a source-only CSS organization change that must prove compiled CSS is unchanged, deterministic browser QA may be recorded as skipped when the byte-for-byte generated CSS comparison passes. If the comparison does not pass, this becomes a UI-facing style behavior change and the browser QA contract in `/hyperopen/docs/BROWSER_TESTING.md` applies before signoff.

## Concrete Steps

1. Create `/hyperopen/tools/styles/main_css_split.test.mjs` using Node's built-in `node:test` module. The test should use `node:fs`, `node:path`, and `node:assert/strict`, compute the repository root from `import.meta.url`, and assert the exact import list in `main.css`.

2. Modify `/hyperopen/package.json` so `"test:styles": "node --test tools/styles/*.test.mjs"` exists and the `"check"` script runs `npm run test:styles` before the ClojureScript compile steps.

3. Run `npm run test:styles`. Expected RED result before the CSS split: the test fails because `src/styles/main.css` has more than the allowed manifest lines or because its import list is empty.

4. Generate and save the CSS baseline with `npm run css:build`, `mkdir -p tmp/css-split`, and `cp resources/public/css/main.css tmp/css-split/main.before.css`.

5. Create `/hyperopen/src/styles/surfaces/`.

6. Move the current lines 1-84 from `main.css` into `/hyperopen/src/styles/base.css`.

7. Move the current component-layer rules into complete `@layer components` blocks in the surface files, preserving their order:

    - current lines 86-107 into `/hyperopen/src/styles/surfaces/trading.css`
    - current lines 108-762 into `/hyperopen/src/styles/surfaces/optimizer.css`
    - current lines 763-1597 into `/hyperopen/src/styles/surfaces/app-shell.css`
    - current lines 1599-1644 into `/hyperopen/src/styles/surfaces/account.css`
    - current lines 1646-1869 into `/hyperopen/src/styles/surfaces/trading-controls.css`
    - current lines 1870-1881 into `/hyperopen/src/styles/surfaces/chart.css`
    - current lines 1883-1919 into `/hyperopen/src/styles/surfaces/trading-layout.css`

8. Move the current utilities-layer block lines 1922-3346 into `/hyperopen/src/styles/surfaces/utilities.css`.

9. Create `/hyperopen/src/styles/surfaces/vaults.css` with only a brief source comment explaining that `main.css` currently has no standalone vault route rules.

10. Replace `/hyperopen/src/styles/main.css` with the exact import manifest listed in `Context and Orientation`.

11. Run the GREEN and preservation commands listed in `Plan of Work`.

12. Run the required repository gates. Update `Progress`, `Artifacts and Notes`, and `Outcomes & Retrospective` with the exact results.

## Validation and Acceptance

Acceptance requires all of the following:

- `/hyperopen/src/styles/main.css` is a short import manifest and no longer contains the large route-scoped optimizer block.
- Source files exist for base, utilities, app shell, trading, account, vaults, optimizer, and chart styling.
- `npm run test:styles` fails before the split and passes after the split.
- `npm run css:build` succeeds after the split.
- `cmp -s tmp/css-split/main.before.css resources/public/css/main.css` exits 0 after the split.
- `npm run check`, `npm test`, and `npm run test:websocket` pass, or exact environmental blockers are recorded.

## Idempotence and Recovery

The split is safe to retry because all generated CSS is written to ignored paths under `/hyperopen/resources/public/css/` and temporary baselines live under `/hyperopen/tmp/css-split/`. If extraction goes wrong, use `git diff` to inspect the changed source files and restore only the CSS chunks being worked on; do not run destructive git commands. If the generated CSS comparison fails, keep the baseline file, inspect the first diff hunk, and adjust only import order or chunk boundaries until the generated output matches.

## Artifacts and Notes

Artifacts will be recorded here during implementation. Expected important artifacts are:

    tmp/css-split/main.before.css
    resources/public/css/main.css
    tools/styles/main_css_split.test.mjs

RED source-organization test:

    npm run test:styles
    AssertionError [ERR_ASSERTION]: main.css should contain only the expected import statements
    2884 !== 10

Pre-split generated CSS baseline:

    npm run css:build
    Done in 1780ms.
    shasum -a 256 tmp/css-split/main.before.css
    eb9a4bfb8d67dcbe39eb405517b1307fbe97b7953f605902bcd6091a806c316b  tmp/css-split/main.before.css

Post-split source and compiled CSS checks:

    npm run test:styles
    pass 3, fail 0

    npm run css:build
    Done in 1949ms.

    cmp -s tmp/css-split/main.before.css resources/public/css/main.css
    exit 0

    shasum -a 256 resources/public/css/main.css
    eb9a4bfb8d67dcbe39eb405517b1307fbe97b7953f605902bcd6091a806c316b  resources/public/css/main.css

Reviewer-feedback check:

    npm run test:styles
    main.css remains a route/surface import manifest
    base stylesheet keeps the Tailwind entry directives
    split stylesheet compiles through Tailwind CLI
    pass 3, fail 0

    npm run css:build && cmp -s tmp/css-split/main.before.css resources/public/css/main.css
    exit 0

Required gate evidence:

    npm run check
    exit 0
    test:styles pass 3, fail 0
    [:test] Build completed. (1557 files, 4 compiled, 0 warnings, 8.61s)

    npm test
    exit 0
    Ran 3681 tests containing 20285 assertions.
    0 failures, 0 errors.

    npm run test:websocket
    exit 0
    Ran 520 tests containing 3027 assertions.
    0 failures, 0 errors.

Earlier required gate evidence before the reviewer-feedback tightening:

    npm run check
    exit 0
    [:test] Build completed. (1557 files, 4 compiled, 0 warnings, 7.14s)

    npm test
    Ran 3681 tests containing 20285 assertions.
    0 failures, 0 errors.

    npm run test:websocket
    Ran 520 tests containing 3027 assertions.
    0 failures, 0 errors.

## Interfaces and Dependencies

This change depends on Tailwind CLI and Node's built-in test runner. It does not introduce a runtime API, a browser storage change, or a new user-facing interaction. The only source interface is the CSS entrypoint contract: `/hyperopen/src/styles/main.css` remains the input to `npm run css:build`, and `/hyperopen/resources/public/css/main.css` remains the generated app stylesheet.

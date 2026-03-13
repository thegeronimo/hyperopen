# Add Delimiter Preflight For CLJS Test Runs

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

Tracking issue: `hyperopen-9v4g`.

## Purpose / Big Picture

After this change, local contributors and agents can run a cheap Babashka preflight before `shadow-cljs` compiles or test runs and get immediate, file-local reader failure output when a Clojure or ClojureScript edit introduces an unmatched delimiter or other parse break. The observable improvement is a new command surface, `bb -m dev.check-delimiters` or `npm run lint:delimiters`, that fails quickly on broken source files and shows a compact delimiter-depth context window around the failing line.

## Progress

- [x] (2026-03-13 18:53Z) Created and claimed `bd` issue `hyperopen-9v4g`, audited existing Babashka lint/tooling patterns, and confirmed the repo already uses `edamame`-based source parsing in local tooling.
- [x] (2026-03-13 18:56Z) Implemented `/hyperopen/dev/delimiter_preflight.clj` and `/hyperopen/dev/check_delimiters.clj` with explicit-path, changed-file, and default-scan modes plus compact failure-context rendering.
- [x] (2026-03-13 18:57Z) Added `/hyperopen/dev/delimiter_preflight_test.clj` covering parse failures, delimiter-depth diagnostics, skip behavior, and file selection precedence.
- [x] (2026-03-13 18:59Z) Wired `/hyperopen/package.json`, `/hyperopen/docs/tools.md`, `/hyperopen/docs/references/toolchain.md`, and `/hyperopen/README.md` to expose and document the new preflight workflow.
- [x] (2026-03-13 19:02Z) Ran `bb dev/delimiter_preflight_test.clj`, `npm run lint:delimiters -- --changed`, `npm run check`, `npm test`, and `npm run test:websocket` successfully after restoring missing npm dependencies with `npm ci`.

## Surprises & Discoveries

- Observation: The repository already has a precedent for Babashka static-analysis helpers that parse source with `edamame`, including CLJS-aware reader options.
  Evidence: `/hyperopen/dev/hiccup_lint.clj` uses `edamame/parse-string-all` with `:read-cond :allow`, `:features #{:cljs}`, and reader shims for `js`, `inst`, and `uuid`.

- Observation: Cold semantic diagnostics are much slower than a syntax-only preflight in this worktree.
  Evidence: `clojure-lsp diagnostics --project-root .` took about `44s` during planning, while the new tool only needs reader parsing and compact context reporting.

- Observation: This worktree initially lacked `node_modules`, so the required repo validation gates could not reach the compile phase until dependencies were restored.
  Evidence: the first `npm run check` failed with missing dependency `@noble/secp256k1`; `npm ci` restored the workspace and the rerun passed.

## Decision Log

- Decision: Keep the new delimiter tool local-only and standalone instead of adding it to `npm run check`.
  Rationale: The user request and plan target a cheap pre-test/pre-compile workflow improvement without changing CI semantics or required validation gates.
  Date/Author: 2026-03-13 / Codex

- Decision: Use `edamame` parsing as the pass/fail authority and use delimiter counting only for failure diagnostics.
  Rationale: Raw delimiter counting is useful for local debugging but not reliable enough to replace a real reader in CLJS source.
  Date/Author: 2026-03-13 / Codex

- Decision: Treat explicit missing or unsupported path arguments as hard selection errors, while empty `--changed` and default scans remain successful skips.
  Rationale: A typo in an explicit target should fail loudly, but no changed/default candidates should not block a normal local workflow.
  Date/Author: 2026-03-13 / Codex

## Outcomes & Retrospective

The delimiter preflight shipped as a repo-local Babashka tool with a stable command surface:

- `bb -m dev.check-delimiters`
- `bb -m dev.check-delimiters --changed`
- `bb -m dev.check-delimiters <file>...`
- `npm run lint:delimiters -- --changed`

The implementation added a reusable parsing/selection/reporting namespace under `/hyperopen/dev/delimiter_preflight.clj`, a thin CLI wrapper under `/hyperopen/dev/check_delimiters.clj`, and focused Babashka tests under `/hyperopen/dev/delimiter_preflight_test.clj`. The docs and README now recommend running the delimiter preflight before expensive compile or test commands after CLJ/CLJS/EDN edits.

Validation results:

- `bb dev/delimiter_preflight_test.clj`: pass (`11` tests, `33` assertions, `0` failures, `0` errors).
- `npm run lint:delimiters -- --changed`: pass.
- `npm run check`: pass.
- `npm test`: pass (`2363` tests, `12406` assertions, `0` failures, `0` errors).
- `npm run test:websocket`: pass (`385` tests, `2187` assertions, `0` failures, `0` errors).

Complexity impact: overall repo complexity increased slightly because a new local tooling module and test file were added, but the change reduces operational complexity for agents and contributors by providing a cheap, deterministic syntax preflight before costly `shadow-cljs` work.

## Context and Orientation

This repository already keeps local developer tooling under `/hyperopen/dev` and `/hyperopen/tools`. Babashka entry points such as `/hyperopen/dev/check_hiccup_attrs.clj` are thin wrappers around reusable library namespaces such as `/hyperopen/dev/hiccup_lint.clj`. The local tooling reference lives in `/hyperopen/docs/tools.md`, the quick toolchain summary lives in `/hyperopen/docs/references/toolchain.md`, and `package.json` exposes user-facing command aliases.

For this task, “delimiter preflight” means a command that reads Clojure-family source files before `shadow-cljs` compiles them. The pass/fail signal comes from the Clojure reader layer via `edamame`, which can detect unmatched delimiters and EOF reader errors with row/column metadata. The human-facing debugging aid is a per-line running depth summary for `()`, `[]`, and `{}` that ignores strings and `;` line comments.

## Plan of Work

First, add a new reusable namespace at `/hyperopen/dev/delimiter_preflight.clj`. It should own file discovery, git changed-file lookup, source parsing, delimiter-depth scanning, failure-context rendering, and top-level report assembly. The parser options must mirror the CLJS-friendly `edamame` options already used by `/hyperopen/dev/hiccup_lint.clj`.

Next, add the thin CLI wrapper `/hyperopen/dev/check_delimiters.clj` so contributors can run `bb -m dev.check-delimiters`, `bb -m dev.check-delimiters --changed`, or `bb -m dev.check-delimiters <file>...`. Explicit file arguments must override `--changed`; `--changed` must look at changed and untracked git paths; and the default mode must scan the repository’s Clojure/ClojureScript/EDN source and local config files while skipping generated directories like `node_modules`, `.git`, `.shadow-cljs`, `out`, `output`, and `tmp`.

Then, add focused Babashka tests in `/hyperopen/dev/delimiter_preflight_test.clj` for parse-failure metadata, delimiter-depth behavior, and file-selection rules. Use temporary directories and real files for selection tests so the results match the actual filesystem behavior.

Finally, expose the command as `npm run lint:delimiters`, update `/hyperopen/docs/tools.md`, `/hyperopen/docs/references/toolchain.md`, and `/hyperopen/README.md`, then run the required repository validation gates before closing the issue and moving this plan out of `active`. Completed on 2026-03-13.

## Concrete Steps

Run from `/Users/barry/.codex/worktrees/b1df/hyperopen`.

1. Create `/hyperopen/dev/delimiter_preflight.clj` and `/hyperopen/dev/check_delimiters.clj`.
2. Add `/hyperopen/dev/delimiter_preflight_test.clj`.
3. Update `/hyperopen/package.json`, `/hyperopen/docs/tools.md`, `/hyperopen/docs/references/toolchain.md`, and `/hyperopen/README.md`.
4. Run:
   - `bb dev/delimiter_preflight_test.clj`
   - `npm run lint:delimiters -- --changed`
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

Expected observable outcomes:

- `bb -m dev.check-delimiters` prints a short success line when all candidate files parse cleanly.
- `bb -m dev.check-delimiters <broken-file>` exits non-zero and prints file-relative reader failure details plus a small delimiter-depth context window.
- `npm run lint:delimiters -- --changed` works as a one-command local preflight before expensive compile or test runs.

## Validation and Acceptance

Acceptance criteria:

- Unmatched closing delimiters report the failing row and column.
- EOF reader failures report the expected delimiter and opening location when `edamame` provides them.
- Delimiter-depth diagnostics ignore delimiters inside strings and `;` line comments.
- Explicit file arguments override `--changed`, and `--changed` only checks existing changed or untracked supported files.
- No candidate files in `--changed` or default mode exit cleanly with a short skip message.
- Repository validation gates (`npm run check`, `npm test`, `npm run test:websocket`) remain green.

## Idempotence and Recovery

The new tool is read-only with respect to repository source files. Re-running it is safe and only repeats parsing and console output. If a selection mode finds no candidate files, it should exit successfully without changing state. If a test or validation command fails midway, fix the code and re-run the same commands; no migrations or destructive cleanup are required.

## Artifacts and Notes

Expected user-facing command surface:

- `bb -m dev.check-delimiters`
- `bb -m dev.check-delimiters --changed`
- `bb -m dev.check-delimiters src/hyperopen/example.cljs`
- `npm run lint:delimiters -- --changed`

## Interfaces and Dependencies

New interfaces to add:

- `dev.delimiter-preflight/run-preflight` returning a report with candidate files, failures, and exit code semantics.
- `dev.delimiter-preflight/delimiter-depths-by-line` returning per-line running depth diagnostics for `()`, `[]`, and `{}`.
- `dev.delimiter-preflight/resolve-candidate-files` implementing explicit-path, changed-file, and default-scan selection.
- `dev.check-delimiters/-main` as the thin Babashka CLI wrapper.

Dependencies:

- Use `edamame.core` for authoritative parsing because it is already proven in repo-local tooling.
- Use `clojure.java.shell` only for git path discovery in `--changed` mode.
- Do not add runtime or browser-facing dependencies.

Revision note: 2026-03-13 initial plan created at implementation start for `hyperopen-9v4g`.
Revision note: 2026-03-13 completed plan updated with implementation details, validation results, and final retrospective before moving to `completed`.

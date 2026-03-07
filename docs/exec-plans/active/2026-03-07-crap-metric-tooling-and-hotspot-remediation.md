# Implement CRAP Hotspot Analysis for ClojureScript Functions

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, a developer can generate repository coverage, run one local command against the whole project or one module, and immediately see which functions are the riskiest blend of branchiness and missing test coverage. The key user-visible behavior is a new CRAP report command that prints the highest-risk functions and modules with their complexity, coverage, CRAP score, and a machine-readable JSON form that Codex can consume directly.

The target workflow from `/hyperopen` is:

    npm run coverage
    bb tools/crap_report.clj --scope src --top-functions 25 --top-modules 10
    bb tools/crap_report.clj --module src/hyperopen/websocket/application/runtime_reducer.cljs --format json

The first command refreshes `coverage/lcov.info`. The second command prints project-wide hotspots. The third command scopes the report to one source file so Codex or a human can focus on one module and then add tests or refactor the worst offenders.

## Progress

- [x] (2026-03-07 15:23Z) Reviewed `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, `/hyperopen/ARCHITECTURE.md`, `/hyperopen/docs/tools.md`, and `/hyperopen/docs/QUALITY_SCORE.md` for planning, quality-gate, and tooling requirements.
- [x] (2026-03-07 15:23Z) Researched the classic CRAP metric formula and threshold semantics so this plan can embed the metric definition instead of outsourcing it.
- [x] (2026-03-07 15:23Z) Audited current repository tooling: `package.json`, `.c8rc.json`, `shadow-cljs.edn`, and the existing Babashka static-analysis tool under `/hyperopen/tools/anonymous_function_duplication/`.
- [x] (2026-03-07 15:23Z) Confirmed the Shadow test build emits source maps whose `names` include fully qualified ClojureScript var names, which makes source-level function correlation feasible.
- [x] (2026-03-07 15:23Z) Created the active ExecPlan for the new tool and remediation workflow.
- [x] (2026-03-07 15:27Z) Created `bd` epic `hyperopen-5fq` and linked it to this ExecPlan via `spec_id`.
- [x] (2026-03-07 18:22Z) Implemented the Babashka source analyzer, LCOV parser, analyzer, report output, CLI, and entrypoint under `/hyperopen/tools/crap/**` plus `/hyperopen/tools/crap_report.clj`.
- [x] (2026-03-07 18:22Z) Added deterministic tool coverage via `/hyperopen/dev/crap_test.clj`, wired `npm run test:crap`, and documented the new command surface in `/hyperopen/docs/tools.md` and `/hyperopen/docs/references/toolchain.md`.
- [x] (2026-03-07 18:22Z) Generated repository coverage, ran whole-project and module CRAP reports, and hardened the analyzer until the whole-project run completed with `parse_errors=0`.
- [x] (2026-03-07 18:22Z) Filed follow-up `bd` tasks for the first real hotspot set: `hyperopen-6cp`, `hyperopen-1qj`, and `hyperopen-45n`.
- [x] (2026-03-07 18:22Z) Ran required validation gates: `npm run check`, `npm test`, and `npm run test:websocket`.
- [x] (2026-03-07 18:24Z) Closed `bd` epic `hyperopen-5fq` after implementation, validation, and hotspot-fanout follow-up issue creation.

## Surprises & Discoveries

- Observation: The repository already has a precedent for repository-local static analysis built with Babashka and `edamame`.
  Evidence: `/hyperopen/tools/anonymous_function_duplication/analyzer.clj` parses `.cljs` source forms directly and reports results without introducing a second runtime stack.
- Observation: Shadow source maps preserve original source file identity and fully qualified var names for compiled functions.
  Evidence: `/hyperopen/.shadow-cljs/builds/test/dev/out/cljs-runtime/hyperopen.api.gateway.account.js.map` lists source `hyperopen/api/gateway/account.cljs` and names such as `hyperopen.api.gateway.account/request-user-funding-history-data!`.
- Observation: The current local workspace does not have installed Node modules, so a full `npm run coverage` or `node out/test.js` run cannot succeed until `npm ci` is performed.
  Evidence: `node out/test.js` failed with `Cannot find module '@noble/secp256k1'` after compiling the `:test` build.
- Observation: Existing coverage-focused ExecPlans show that one `.cljs` source file can appear more than once in `coverage/lcov.info`, once under `.shadow-cljs/builds/test/...` and once under `.shadow-cljs/builds/ws-test/...`.
  Evidence: `/hyperopen/docs/exec-plans/completed/2026-02-18-ws-test-address-watcher-coverage-lift.md` records both `test` and `ws-test` rows for `address_watcher.cljs`.
- Observation: `coverage/lcov.info` already reports original source line numbers for source-mapped `.cljs` files, so the runtime report path only needs logical-path resolution, not source-map decoding.
  Evidence: The generated LCOV contains entries such as `SF:.shadow-cljs/builds/test/dev/out/cljs-runtime/hyperopen/api.cljs` with `FN:12,...` and `DA:12,0`, which line up with the original `src/hyperopen/api.cljs` source lines.
- Observation: The initial whole-project report produced 21 parse errors because the recursive branch scanner assumed every list head implemented `clojure.lang.Named`.
  Evidence: The first report printed `parse_errors=21`; after guarding `head-name` against composite list heads, the same report printed `parse_errors=0`.
- Observation: One function overwhelmingly dominates current project CRAPload: `hyperopen.funding.application.modal-vm/funding-modal-view-model`.
  Evidence: The first whole-project report scored it at `CRAP 3824.41`, `complexity 93`, `coverage 0.24`, with its module reporting `crapload 3794.41`.

## Decision Log

- Decision: Implement the tool as a Babashka utility rooted at `/hyperopen/tools/crap_report.clj` with helper namespaces under `/hyperopen/tools/crap/`.
  Rationale: The repository already ships Babashka-based static-analysis tools, and parsing Lisp forms in Clojure is more reliable and maintainable than introducing a JavaScript parser for ClojureScript source.
  Date/Author: 2026-03-07 / Codex
- Decision: Compute complexity from source forms, not from compiled JavaScript.
  Rationale: CRAP is intended to describe source maintainability. Shadow output adds destructuring helpers and generated control flow that would distort a maintainability score if treated as the source of truth.
  Date/Author: 2026-03-07 / Codex
- Decision: Use the classic CRAP formula, expressed for a coverage ratio in `[0,1]`, as `crap = (complexity * complexity * (Math/pow (- 1 coverage) 3)) + complexity`.
  Rationale: This is equivalent to the classic `comp^2 * (1 - cov/100)^3 + comp` formulation while keeping the implementation natural once per-function coverage is stored as a ratio instead of a percentage integer.
  Date/Author: 2026-03-07 / Codex
- Decision: Default the “crappy” threshold to `30` and calculate project/module “crapload” as the sum of `max(0, crap - 30)` across all analyzed functions.
  Rationale: That matches the historic Crap4J framing and gives one scalar “how much work is above the line” summary for the project and each module.
  Date/Author: 2026-03-07 / Codex
- Decision: The first version will score top-level named functions only: `defn`, `defn-`, `defmethod`, and `def` forms whose value is a named or anonymous `fn`.
  Rationale: Those are the functions developers can directly target for tests and refactors. Scoring local anonymous functions in the first pass would add noise and unstable names without improving remediation decisions.
  Date/Author: 2026-03-07 / Codex
- Decision: Derive per-function coverage from instrumented source lines in `coverage/lcov.info`, merged across `:test` and `:ws-test` by default.
  Rationale: This repository already generates LCOV from `npm run coverage`, and merged line coverage best matches the developer question “is this function exercised anywhere in our automated tests?”
  Date/Author: 2026-03-07 / Codex
- Decision: Ship text output for humans and JSON output for Codex or other automation, but do not make CRAP a failing quality gate in the first version.
  Rationale: The immediate need is prioritization and navigation. Gating should wait until the scoring rules are validated against real repository hotspots and false-positive risk is understood.
  Date/Author: 2026-03-07 / Codex
- Decision: Resolve LCOV `SF:` entries to repo files by stripping the Shadow build prefix and then probing `src/`, `test/`, and `dev/` instead of decoding `.js.map` files in the hot path.
  Rationale: The generated LCOV already exposes original source line numbers. A lightweight logical-path resolver keeps the implementation fast and simple while still handling the repo’s source roots correctly.
  Date/Author: 2026-03-07 / Codex
- Decision: For multi-arity functions, sum per-arity complexity scores rather than collapsing the entire definition to a single base-path score of `1`.
  Rationale: Multiple arities are separate callable branches in practice. Summing per-arity complexity better matches the maintenance cost of a multi-arity `defn`.
  Date/Author: 2026-03-07 / Codex

## Outcomes & Retrospective

Implementation completed. Hyperopen now has a repo-local CRAP tool implemented in `/hyperopen/tools/crap/**` with a thin entrypoint at `/hyperopen/tools/crap_report.clj`, a fast Babashka test suite in `/hyperopen/dev/crap_test.clj`, an npm surface via `npm run test:crap` and `npm run crap:report -- ...`, and documentation in `/hyperopen/docs/tools.md` plus `/hyperopen/docs/references/toolchain.md`.

The first whole-project report completed against fresh coverage with `parse_errors=0`, `functions_scanned=5209`, `crappy_functions=55`, and `project_crapload=6051.17`. The three highest-CRAP functions were:

- `hyperopen.funding.application.modal-vm/funding-modal-view-model` in `src/hyperopen/funding/application/modal_vm.cljs` at `CRAP 3824.41`, `complexity 93`, `coverage 0.24`
- `hyperopen.views.funding-modal/withdraw-content` in `src/hyperopen/views/funding_modal.cljs` at `CRAP 342.00`, `complexity 18`, `coverage 0.00`
- `hyperopen.views.vaults.detail.chart-view/chart-tooltip-benchmark-values` in `src/hyperopen/views/vaults/detail/chart_view.cljs` at `CRAP 193.22`, `complexity 16`, `coverage 0.12`

The three highest-crapload modules were:

- `src/hyperopen/funding/application/modal_vm.cljs` with `crapload 3794.41`
- `src/hyperopen/views/funding_modal.cljs` with `crapload 399.30`
- `src/hyperopen/views/portfolio/vm/summary.cljs` with `crapload 196.73`

Follow-up work was recorded in `bd` as `hyperopen-6cp`, `hyperopen-1qj`, and `hyperopen-45n`. Validation evidence:

- `npm run check`: pass
- `npm test`: pass (`1997` tests, `10232` assertions)
- `npm run test:websocket`: pass (`339` tests, `1852` assertions)
- `npm run coverage`: pass before the final report run, producing the LCOV artifact consumed by the CRAP tool

The implementation epic `hyperopen-5fq` is closed. Remaining work lives in the follow-up issues, not in this plan.

## Context and Orientation

Hyperopen is a ClojureScript application compiled with Shadow CLJS. Repository tests run in two Node-oriented builds defined in `/hyperopen/shadow-cljs.edn`: `:test` and `:ws-test`. Coverage is generated by `npm run coverage` in `/hyperopen/package.json`, which runs both test builds under V8 coverage and then emits LCOV, HTML, and JSON summary artifacts according to `/hyperopen/.c8rc.json`.

For this plan, “cyclomatic complexity” means a count of independent control-flow choices through one function. “Coverage” means the fraction of instrumented executable source lines inside that function’s source range that were hit by tests. “CRAP” means a function-level risk score that rises when complexity rises and coverage falls. The formula to implement is:

    CRAP = complexity^2 * (1 - coverage)^3 + complexity

where `coverage` is a ratio from `0.0` to `1.0`. A few anchor examples must become test fixtures for the formula:

    complexity=1, coverage=1.0 -> CRAP=1
    complexity=8, coverage=0.0 -> CRAP=72
    complexity=12, coverage=0.5 -> CRAP=30

The architecture and tooling seams relevant to this plan are:

- `/hyperopen/package.json`: existing `coverage`, `check`, `test`, and `test:websocket` commands; this is where a small convenience script for the new tool should be added.
- `/hyperopen/.c8rc.json`: includes Shadow runtime files under `.shadow-cljs/builds/**/cljs-runtime/hyperopen*.js` and produces `coverage/lcov.info`.
- `/hyperopen/tools/anonymous_function_duplication_report.clj` and `/hyperopen/tools/anonymous_function_duplication/**`: existing precedent for a Babashka entrypoint plus helper namespaces and printed report output.
- `/hyperopen/src/**` and `/hyperopen/test/**`: the source trees the tool must scan. The default target for developer triage should be `src`.
- `/hyperopen/dev/check_docs_test.clj` and `/hyperopen/dev/hiccup_lint_test.clj`: examples of fast Babashka-driven tool tests that can run inside repository scripts without needing a separate JVM test harness.

The new implementation should live entirely in repo-local tooling space so that application runtime code remains untouched. The expected new files are:

- `/hyperopen/tools/crap_report.clj`
- `/hyperopen/tools/crap/cli_options.clj`
- `/hyperopen/tools/crap/filesystem.clj`
- `/hyperopen/tools/crap/complexity.clj`
- `/hyperopen/tools/crap/coverage.clj`
- `/hyperopen/tools/crap/analyzer.clj`
- `/hyperopen/tools/crap/report_output.clj`
- `/hyperopen/dev/crap_test.clj`

If additional tiny helper files or fixtures are needed, keep them under `/hyperopen/tools/crap/` or `/hyperopen/dev/` and do not spread the feature into application namespaces.

## Plan of Work

Milestone 1 establishes source parsing and complexity counting. Create `/hyperopen/tools/crap/filesystem.clj` by reusing the same canonical-path and `.cljs` file discovery shape already used by the anonymous-function-duplication tool. Then implement `/hyperopen/tools/crap/complexity.clj` to parse source forms with `edamame` metadata enabled and collect top-level named function definitions with `:file`, `:line`, `:end-line`, `:namespace`, `:var`, `:display-name`, `:arity-count`, and `:complexity`. The complexity counter must stay source-level and explainable: count one base path per function, then add decisions for `if`-family forms, `cond` and `case` branches, short-circuit `and` and `or`, `catch`, and comprehension filters such as `:when` or `:while`. The implementation must be explicit about which forms count and must test them directly.

Milestone 2 establishes coverage ingestion. Create `/hyperopen/tools/crap/coverage.clj` to parse `coverage/lcov.info`, preserve row identity for both `test` and `ws-test`, and then offer a merged view keyed by source file and line number. The merged view must not double-count denominator lines when the same source line appears in both builds. Instead, coverage for a given source line should be considered hit if either build reports a hit. This namespace must expose one function that returns per-file executable line maps and another that derives per-function coverage by intersecting those maps with the source line ranges discovered in Milestone 1.

Milestone 3 combines the source and coverage data into a report. Implement `/hyperopen/tools/crap/analyzer.clj` to join function metadata with per-function coverage, compute CRAP, mark functions above the default threshold, and aggregate module-level summaries such as `function-count`, `covered-function-count`, `crappy-function-count`, `max-crap`, `avg-crap`, and `crapload`. Default sorting should be descending CRAP for functions and descending crapload for modules. The analyzer must support whole-scope analysis and `--module <path>` analysis.

Milestone 4 adds the command-line surface. Implement `/hyperopen/tools/crap/cli_options.clj` and `/hyperopen/tools/crap/report_output.clj`, then create the thin entrypoint `/hyperopen/tools/crap_report.clj`. The command-line surface must support `--scope <src|test|all>`, `--module <repo-relative-path>`, `--coverage-file <path>`, `--build <merged|test|ws-test>`, `--top-functions N`, `--top-modules N`, `--threshold N`, and `--format <text|json>`. Missing or conflicting arguments must fail with a short usage message. Missing `coverage/lcov.info` must fail with a direct instruction to run `npm run coverage`.

Milestone 5 hardens and documents the workflow. Add `/hyperopen/dev/crap_test.clj` with fast, deterministic tests for formula math, complexity counting, LCOV parsing and merge behavior, per-function coverage derivation, sorting, and CLI argument handling. Update `/hyperopen/package.json` with a lightweight script for the tool test and a convenience script that runs the report entrypoint. Keep the required validation gates intact. Then add a short section to `/hyperopen/docs/tools.md` that shows the canonical commands for running whole-project and module reports.

Milestone 6 proves the tool on real repository data. After `npm ci` and `npm run coverage` succeed, run the whole-project report and at least one focused module report, capture the top hotspots in this ExecPlan, and create follow-up `bd` issues for any clearly actionable refactor or coverage-lift work that should survive beyond the implementation session.

## Concrete Steps

From `/hyperopen`:

1. Install dependencies if needed:

       npm ci

2. Implement the new tooling files listed in the Context section.

3. Run the fast tool tests while iterating:

       bb -m dev.crap-test

4. Generate fresh coverage artifacts once the tool compiles:

       npm run coverage

5. Run a whole-project text report:

       bb tools/crap_report.clj --scope src --top-functions 25 --top-modules 10

   The expected output shape is:

       scope=src
       build=merged
       functions_scanned=<number>
       crappy_functions=<number>
       project_crapload=<number>

       top_functions:
         crap=<number> coverage=<ratio> complexity=<number> file=<path> fn=<ns/var>

       top_modules:
         crapload=<number> max_crap=<number> file=<path>

6. Run a focused JSON report:

       bb tools/crap_report.clj --module src/hyperopen/websocket/application/runtime_reducer.cljs --format json

   The expected output shape is a single JSON object with `summary`, `functions`, and `modules` keys. Each function entry must include at least `file`, `line`, `end-line`, `display-name`, `complexity`, `coverage`, `crap`, and `crappy`.

7. Run the repository validation gates after the tool and docs are in place:

       npm run check
       npm test
       npm run test:websocket

8. Create or update any follow-up `bd` issues discovered by the first whole-project CRAP report.

## Validation and Acceptance

The tool is complete when all of the following are true:

- `bb -m dev.crap-test` passes and includes direct assertions for the CRAP formula anchor values, source-level complexity counting rules, LCOV merge behavior, and CLI argument validation.
- `bb tools/crap_report.clj --scope src --top-functions 25 --top-modules 10` exits successfully after `npm run coverage` and prints a stable text report with project summary plus sorted function/module hotspots.
- `bb tools/crap_report.clj --module <repo-relative-path> --format json` exits successfully and emits valid JSON that a machine can parse without scraping the text report.
- The default merged-build report treats a source line as covered if it is covered in either `:test` or `:ws-test`, without double-counting denominator lines.
- The report marks functions above the threshold of `30` as `crappy` and computes module/project crapload from excess-over-threshold totals.
- `/hyperopen/docs/tools.md` documents the new command surface and references the same canonical commands used in this plan.
- Required repository gates pass:

      npm run check
      npm test
      npm run test:websocket

## Idempotence and Recovery

All source-analysis and report commands are read-only with respect to application behavior. Re-running them is safe. If `coverage/lcov.info` is missing or stale, the tool must fail with a clear message telling the user to run `npm run coverage`. If one source file cannot be parsed, the report should continue collecting other files, print the parse error count and file list, and leave the process behavior consistent across reruns. If the user requests a single module and that module cannot be parsed or is absent from coverage, the tool should fail fast because a partial single-file report would be misleading.

The only mutable artifacts during normal use are coverage outputs under `/hyperopen/.coverage`, `/hyperopen/coverage`, and generated Shadow outputs under `/hyperopen/out` and `/hyperopen/.shadow-cljs`. Those are already safe to regenerate by rerunning `npm run coverage`.

## Artifacts and Notes

The first implementation should preserve the output style already used by the repository’s other static-analysis tools: a concise text report for humans and a JSON report for automation. Use repository-relative file paths everywhere so the output can be pasted into docs, issues, or Codex prompts without machine-specific path cleanup.

The complexity rules must be written down directly in the tool tests. Do not leave “cyclomatic complexity” as an implied term. A future contributor should be able to read `/hyperopen/dev/crap_test.clj` and understand exactly why a given source form scored `3` instead of `5`.

First real report evidence from `npm run crap:report -- --scope src --top-functions 10 --top-modules 5`:

- Top functions:
  - `hyperopen.funding.application.modal-vm/funding-modal-view-model` => `CRAP 3824.41`, `complexity 93`, `coverage 0.24`
  - `hyperopen.views.funding-modal/withdraw-content` => `CRAP 342.00`, `complexity 18`, `coverage 0.00`
  - `hyperopen.views.vaults.detail.chart-view/chart-tooltip-benchmark-values` => `CRAP 193.22`, `complexity 16`, `coverage 0.12`
- Top modules:
  - `src/hyperopen/funding/application/modal_vm.cljs` => `crapload 3794.41`
  - `src/hyperopen/views/funding_modal.cljs` => `crapload 399.30`
  - `src/hyperopen/views/portfolio/vm/summary.cljs` => `crapload 196.73`
- Follow-up issues created from this report:
  - `hyperopen-6cp`
  - `hyperopen-1qj`
  - `hyperopen-45n`

## Interfaces and Dependencies

In `/hyperopen/tools/crap/complexity.clj`, define a source collector with a stable result map shape equivalent to:

    {:file "src/hyperopen/api/gateway/account.cljs"
     :line 8
     :end-line 18
     :namespace "hyperopen.api.gateway.account"
     :var "request-user-funding-history-data!"
     :display-name "hyperopen.api.gateway.account/request-user-funding-history-data!"
     :arity-count 1
     :complexity 3}

In `/hyperopen/tools/crap/coverage.clj`, define functions equivalent to:

    (read-lcov coverage-file) => collection of raw LCOV source-file records
    (merge-file-coverage records {:build :merged|:test|:ws-test}) => {file {line hit-count}}
    (function-coverage merged-file-lines fn-record) => coverage-ratio

In `/hyperopen/tools/crap/analyzer.clj`, define an entry function equivalent to:

    (build-report {:root root
                   :scope "src"
                   :module nil
                   :coverage-file "coverage/lcov.info"
                   :build :merged
                   :top-functions 25
                   :top-modules 10
                   :threshold 30.0})

That report map must contain:

    {:summary {...}
     :functions [...]
     :modules [...]
     :parse-errors [...]}

In `/hyperopen/tools/crap/report_output.clj`, define text and JSON printers that accept exactly the analyzer’s report map so presentation is cleanly separated from analysis.

Dependencies should stay minimal and repo-local. Use Babashka-bundled libraries already proven in this repo, especially `edamame.core` for source parsing and `cheshire.core` for JSON output if needed. Do not add browser/runtime dependencies or application-layer namespaces for this work.

Plan revision note: 2026-03-07 15:23Z - Initial plan created after reviewing planning docs, repository tooling, Shadow source-map output, and classic CRAP metric references.
Plan revision note: 2026-03-07 18:22Z - Updated after implementation, validation, first whole-project report, and follow-up `bd` issue creation.
Plan revision note: 2026-03-07 18:24Z - Marked the implementation epic closed after landing the tool and spinning out remaining hotspot remediation tasks.

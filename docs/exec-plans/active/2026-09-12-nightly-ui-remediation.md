# Make nightly UI QA distinguish product defects from read-only and retention behavior

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept current as work proceeds. Maintain it according to `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

The September 10 nightly run found two separate problems: the desktop `/trade` asset-statistics strip allowed its values to overlap at a 1280-pixel-wide viewport, and the mobile margin-sheet scenario expected a spectating account to open an editing surface. The second expectation is wrong because Spectate Mode is deliberately read-only. The run also treated a short-lived browser-artifact directory as the only comparison baseline, so retention pruning could make a repeated failure appear new and create another local `bd` issue.

After this work, a trader can read every desktop market statistic at 1280 pixels without text colliding, and can still reach every statistic through horizontal scrolling at constrained desktop widths. A spectating viewer cannot open the position-margin editor, while the same mobile presentation remains proven with a simulated editable account. Nightly QA will keep a compact comparison record outside of run-directory retention and will not create a second issue for an unchanged finding.

This plan records the direct maintainer request on 2026-09-12 to implement that remediation. It is correctness and QA-reliability work; it makes no performance claim, introduces no performance-sensitive algorithm, and therefore requires no throughput baseline or profiling milestone.

## Context References

Public refs:

- Direct maintainer request, 2026-09-12: proceed with the nightly UI QA remediation proposed after the 2026-09-10 run.

Repo artifacts:

- `/hyperopen/tools/browser-inspection/src/nightly_ui_qa.mjs` is the `npm run qa:nightly-ui` entry point. It enforces `main`, runs the scenario bundle and the governed design review, writes the per-run report artifacts, classifies failures, and files qualifying local issues.
- `/hyperopen/tools/browser-inspection/src/nightly_ui_coverage.mjs` defines the nightly contract: `/trade` and `/portfolio` inspect each of the three required spectate addresses; `/vaults` has baseline coverage without Spectate Mode.
- `/hyperopen/tools/browser-inspection/scenarios/mobile-position-margin-presentation.json` currently navigates to `/trade?spectate=0x4096d3377ae5ade578daae8188804740c8b1da3e` and incorrectly expects the margin sheet to open.
- `/hyperopen/tools/playwright/test/mobile-regressions.spec.mjs` already uses the debug bridge to seed a mobile position and verify the bottom-sheet presentation deterministically.
- `/hyperopen/src/hyperopen/views/active_asset/row.cljs` renders the desktop asset strip. Its `active-asset-grid-template` permits six statistic columns to shrink below their content width.
- `/hyperopen/docs/agent-guides/browser-qa.md` requires browser evidence at 375, 768, 1280, and 1440 pixels for UI work.

Local scratch refs (non-authoritative):

- The September 10 wrapper run created four duplicate local records for the misclassified margin-sheet result: `hyperopen-hbzq`, `hyperopen-su40`, `hyperopen-jtvt`, and `hyperopen-w1vw`. `bd` is local scratch only; no contributor needs these identifiers to understand or continue the work.

## Scope and Non-Goals

The implementation changes four connected surfaces: the desktop market-stat layout; the Spectate Mode margin scenario and deterministic Playwright coverage; compact nightly comparison persistence and issue deduplication; and the local misclassified `bd` record when that command is available.

This work does not relax the read-only rules for a spectated account, remove the three-day run-directory retention policy, alter the `/trade`, `/portfolio`, or `/vaults` coverage matrix, add wallet-extension automation, or redesign the trade shell. It also does not make network, remote repository, or `bd` synchronization calls.

## Progress

- [x] (2026-09-12) Captured the approved scope, current failure mechanism, affected files, and validation contract in this ExecPlan.
- [x] (2026-09-12) Ran `npm run setup:worktree`; dependency setup completed successfully.
- [x] (2026-09-12) Implemented and ran the focused nightly baseline and filing contracts: 19 tests passed.
- [x] (2026-09-12) Ran the browser-inspection suite after resolving sandbox loopback setup: 94 tests passed and 2 were skipped (96 total).
- [x] (2026-09-12) Added the nonshrinking, keyboard-focusable desktop asset-statistics layout and its layout assertion. The source helper keeps the owning namespace below 511 lines. Automated design review passed 24/24 checks; manual validation found no statistics overlap or document overflow and confirmed `tabindex="0"` with horizontal keyboard access.
- [x] (2026-09-12) Split read-only Spectate Mode margin coverage from simulated editable-account coverage. The final three changed/new Playwright tests passed in 27.3 seconds, including the expanded visible Spectate-card action and the real owner Edit Margin click. A final isolated Spectate check passed 1/1 in 8.4 seconds and asserts `aria-expanded="true"` before using the action.
- [x] (2026-09-12) Added compact-baseline persistence, comparison provenance, and fingerprint-based local issue filing; integrated browser and repository validation are complete.
- [x] (2026-09-12) Confirmed all four duplicate local records matched the misclassified assertion, reclassified them as tasks, and closed them as duplicates of `hyperopen-cyoz`.
- [x] (2026-09-12) Ran final `npm run gates` rerun 4050 after the runtime, wrapped-placeholder, and accessibility changes: 34/34 gates passed in 2m19s, covering 7,130 tests and 39,485 assertions. The app compiled with zero warnings; two pre-existing test warnings were unchanged.
- [x] (2026-09-12) Completed the automated design review `design-review-2026-09-12T15-08-19-034Z-ecd71a10`: all 24 required checks passed across 375, 768, 1280, and 1440 pixels.
- [x] (2026-09-12) Completed and closed the visual validation session. Scoped manual evidence is at `tmp/browser-inspection/manual-ui-visual-validation-2026-09-12T15-04-17-096Z-0a42111e/manual-validation-summary.json` and `tmp/browser-inspection/manual-ui-keyboard-validation-2026-09-12T15-07-30-419Z-88a30df8/keyboard-focus-summary.json`.
- [x] (2026-09-12) Completed and closed the focused tooltip visual session: manual tooltip behavior passed at desktop 1280/1440 and mobile 375/768. Evidence is at `tmp/browser-inspection/direct-tooltip-visual-validation-2026-09-12T15-40-53-305Z-da56ea26/direct-tooltip-summary.json` and `tmp/browser-inspection/manual-tooltip-validation-2026-09-12T15-39-13-379Z-987d4ca2/tooltip-validation-summary.json`.
- [x] (2026-09-12) Completed final reviewer pass with no findings.
- [x] (2026-09-12) Eliminated the owner-fixture bootstrap race by freezing synchronization before connecting the test wallet. The complete mobile regression file now passes 6/6 in 43.7 seconds, and the unchanged real-click owner path passed three consecutive repetitions (3/3) in 21 seconds.
- [x] (2026-09-12) Ran the repaired `trade-funding-tooltip-layering` scenario through its actual scroll, settle, focus, and trigger-click path: `tooltip-remediation-2026-09-12T15-49-13-066Z-f28cf68d` passed the real hit-ownership contract.
- [x] (2026-09-12) Completed the clean serialized worktree matrix `remediation-ui-qa-2026-09-12T15-50-17-621Z-0c49e6a9`: 23/23 scenarios passed, its coverage contract was satisfied for `/trade`, `/portfolio`, and `/vaults`, and all three required spectate addresses were exercised. The summary is `state: "pass"` with no product, automation, or manual classifications.
- [x] (2026-09-12) Completed focused Playwright, worktree scenario/design verification, the non-`main` wrapper guard, final browser-inspection suite, and cleanup. The clean matrix passed 23/23; fresh `npm run qa:nightly-ui` exited 1 before browser work with `Nightly UI QA must run from main. Current branch is HEAD. Pass --allow-non-main to override.` (no override was used); the final browser-inspection suite passed 94 with 2 environment-gated skips; and `npm run browser:cleanup` reported `ok: true`, `stopped: []`. The task dev supervisor PID 5427 received `SIGTERM`, root PID 81937 exited, and watchers were clean.
- [ ] Let the scheduled automation run the wrapper on `main` after reviewed changes are integrated, then record its report and run IDs.

## Surprises & Discoveries

- Observation: Nightly comparison previously scanned only sibling `nightly-ui-qa-*` directories under `tmp/browser-inspection`.
  Evidence: `findPreviousNightlyRun` in `/hyperopen/tools/browser-inspection/src/nightly_ui_qa.mjs` reads `summary.json` from the previous run, while `ArtifactStore.pruneExpiredRuns` removes run directories older than the configured 72-hour retention period.

- Observation: The failed mobile scenario was already in a Spectate Mode URL yet dispatched `:actions/open-position-margin-modal` and expected `{ open: true, presentationMode: "mobile-sheet" }`.
  Evidence: `/hyperopen/tools/browser-inspection/scenarios/mobile-position-margin-presentation.json` has that URL and expectation. The browser policy in `/hyperopen/docs/qa/spectate-mode-manual-matrix.md` says position margin updates are blocked while spectating.

- Observation: The asset-stat strip becomes a desktop grid at 1024 pixels and allocates multiple `minmax(0, …fr)` statistic tracks, which explicitly permits the tracks to contract below the unbroken numeric contents.
  Evidence: `active-asset-grid-template` in `/hyperopen/src/hyperopen/views/active_asset/row.cljs` and the `lg:grid` desktop strip in the same file.

- Observation: A comparison baseline needs complete nightly identity, not just a result list, and filing cannot rely only on the `NEW` novelty label.
  Evidence: A missing `runId` or `runDir` cannot identify a comparison source. A matching failure becomes `EXISTING` on a later run, so it must remain eligible to retry issue lookup and creation after a prior tracker outage.

- Observation: Local issue history contains four copies of the margin-sheet assertion and one primary automation task.
  Evidence: `bd show` confirmed `hyperopen-hbzq`, `hyperopen-su40`, `hyperopen-jtvt`, and `hyperopen-w1vw` matched the former scenario; each was reclassified as a task and closed as a duplicate of `hyperopen-cyoz` on 2026-09-12.

- Observation: The statistics-strip visual and keyboard checks now pass for the changed surface.
  Evidence: `/hyperopen/tmp/browser-inspection/design-review-2026-09-12T15-08-19-034Z-ecd71a10/summary.json` records 24/24 automated checks passing across 375, 768, 1280, and 1440 pixels. `/hyperopen/tmp/browser-inspection/manual-ui-visual-validation-2026-09-12T15-04-17-096Z-0a42111e/manual-validation-summary.json` records no asset-stat text overlap or document overflow. `/hyperopen/tmp/browser-inspection/manual-ui-keyboard-validation-2026-09-12T15-07-30-419Z-88a30df8/keyboard-focus-summary.json` records `tabIndex: 0`, visible focus styling, and the manual horizontal-scroll exercise.

- Observation: A manual visual/layout review still has a qualified `FAIL` because the header navigation overlaps at 768 pixels.
  Evidence: The validator found this condition outside the active-statistics strip before and after this change. It is documented as pre-existing, outside this plan's touched surface, and must not be represented as a pass for global trade-route visual QA.

- Observation: The first 23-scenario browser matrix is invalid evidence.
  Evidence: An unprivileged session-list call received `EPERM` and removed the registry for Chrome started through an elevated path. The affected matrix must be rerun serially after the active visual browser session is closed; its result must not be reported as a product or automation verdict.

- Observation: There is no clean pre-source-change RED test run for the new deterministic browser assertions.
  Evidence: The original September 10 nightly result is the diagnosis baseline for the bad Spectate expectation and 1280 overlap. The test contract was strengthened during implementation to require rendered position cards and real trigger clicks, so it was not run against a clean pre-change checkout. Treat the original nightly evidence as the known failure source and the focused 3/3 Playwright run as current behavior evidence; do not represent the latter as a captured RED result.

- Observation: The changed margin tests pass, and the earlier broader mobile-file failure did not reproduce in isolation.
  Evidence: `tools/playwright/test/mobile-regressions.spec.mjs` first completed with 5 passes and 1 failure in 57.7 seconds because the unchanged balances bottom-navigation test did not find a visible balance card within its 15-second timeout. Its isolated rerun passed 1/1 in 6.2 seconds, which points to a transient missing live fixture rather than a measured bottom-navigation layout defect. The final changed/new three-test margin selection passed in 27.3 seconds, and the final isolated Spectate check passed 1/1 in 8.4 seconds after asserting `aria-expanded="true"`.

- Observation: Final review found no scoped correctness, regression, security, or test-coverage findings.
  Evidence: The reviewer completed with `PASS` and no findings on 2026-09-12. This does not replace the still-running serialized browser matrix or the scheduled main-only nightly verification.

- Observation: The serialized matrix revealed a funding-tooltip regression after the asset-statistics scroller change.
  Evidence: Run `remediation-ui-qa-2026-09-12T15-09-34-367Z-f5fd391a` finished 22/23 and met every required route, viewport, and spectate-address coverage obligation. `trade-funding-tooltip-layering` alone failed: its tooltip center and right hit tests returned false, and the right-point owner was `asset-stat-cell`. A Playwright RED test reproduced the result after scrolling the trigger into view and clicking normally; the viewport contained the tooltip but its center did not belong to it. The worker is correcting the fixed anchored desktop-tooltip behavior, then must rerun the affected tests.

- Observation: The tooltip correction keeps measurement at the interaction boundary and the desktop layer pure.
  Evidence: The saved core compiles with zero warnings and the row namespace remains at the 511-line limit. The trigger action captures its anchor rect; the pure fixed desktop popover consumes that rect; scrolling the statistics viewport dismisses both pinned and hover tooltip state. No new listeners were introduced. The added coverage includes funding-draft and tooltip-action-bound tests plus trade regression assertions for tooltip hit testing, scroll-close, and reopen behavior.

- Observation: The remaining tooltip failure is an event-binding shape error, not a popover-layer, action, reducer, or CSS defect.
  Evidence: The Nexus action trace printed the event argument and showed the bare `:event.currentTarget/bounds` keyword arriving literally instead of resolving a target rectangle. The funding trigger is being changed to the required wrapped placeholder `[:event.currentTarget/bounds]` only. The new scoped runtime lifecycle module closes the tooltip on window resize and component unmount cleanup; review accepts the source shape, while deterministic browser evidence is still pending.

- Observation: The repaired layer and responsive dismissal already satisfy their interaction assertions; only the closed trigger's explicit accessibility state remains.
  Evidence: Browser coverage now passes initial fixed-layer hit testing and a direct 1440-to-1023 transition that dismisses the desktop tooltip for mobile. At 1024 after close, the trigger currently omits `aria-expanded`, producing `null` instead of the required string `"false"`. The implementation will render the boolean accessibility attribute explicitly and retain the assertion unchanged.

- Observation: Fingerprint deduplication has no cross-process lock around local issue lookup and creation.
  Evidence: A single scheduled wrapper run lists local issues, checks markers, and creates candidates sequentially. Two overlapping manual or scheduled wrapper processes could both list before either creates the shared fingerprint marker. No overlapping invocation is requested or evidenced in this work, so this is a bounded operational residual rather than a source-change requirement.

- Observation: The repaired tooltip passes its final deterministic browser contract.
  Evidence: The focused Playwright run passed 2/2 in 43 seconds, covering fixed-layer hit ownership, viewport containment, direct 1440-to-1023 mobile-summary dismissal, 1440-to-1024 closed `aria-expanded="false"`, scroll close/reopen, and mobile-sheet presentation. The reviewer completed a final `PASS` with no blockers; final broader validation remains running.

- Observation: Manual tooltip validation now passes at every relevant desktop and mobile width, but the old browser scenario fixture needs to exercise the repaired interaction correctly.
  Evidence: The direct desktop tooltip session passed at 1280 and 1440, and the mobile tooltip session passed at 375 and 768; both sessions are closed. The prior `trade-funding-tooltip-layering` fixture uses a raw DOM click without focus or scroll settlement and still expects the historical `rightOverlap: true` condition. The fixture is being changed to scroll, settle, focus, and click the actual trigger, then sample actual overlap ownership against chart, orderbook, and account targets while asserting fixed, viewport-contained placement. Review is in progress before the final matrix rerun.

- Observation: The broader mobile file exposed a new owner-fixture bootstrap race and cannot be accepted as a residual flake.
  Evidence: The full mobile file had 5 passes and one failure when the newly seeded owner position disappeared after the card was expanded. The isolated owner path passes. TDD is freezing synchronization before wallet connection so an in-flight bootstrap cannot replace the seeded state, then will rerun the full file and repeat the test three times. No green result is claimed until those deterministic repeats pass.

- Observation: Freezing synchronization before wallet connection eliminated the owner-fixture race without weakening the visible-control contract.
  Evidence: The complete `mobile-regressions` file passed 6/6 in 43.7 seconds after the ordering change. The unchanged owner test, which seeds the position, expands the card, and clicks the actual Edit Margin control, then passed three consecutive repetitions (3/3) in 21 seconds.

- Observation: The repaired tooltip scenario passes when it follows the product-visible interaction path; an intervening managed-app startup error is invalid infrastructure evidence, not a product failure.
  Evidence: `tooltip-remediation-2026-09-12T15-49-13-066Z-f28cf68d` passed after scrolling, settling, focusing, and clicking the actual trigger, then sampling hit ownership. The earlier `scenario-2026-09-12T15-48-07-720Z-656a5517` attempt failed only because it attempted a second managed local-app startup while a development server already ran; its redundant Node process (PID 45755) was stopped and the retained server is used by the final serialized matrix. All isolated browser-inspection sessions were closed before that matrix began.

- Observation: The final clean serialized matrix passes the complete worktree scenario contract.
  Evidence: `tmp/browser-inspection/remediation-ui-qa-2026-09-12T15-50-17-621Z-0c49e6a9/summary.json` is `state: "pass"`, began at `2026-09-12T15:50:17.704Z`, ended at `2026-09-12T15:59:35.287Z`, and contains 23 passing scenario results. Its coverage contract is satisfied across `/trade`, `/portfolio`, and `/vaults`, including all three required spectate addresses; product, automation, and manual classification counts are zero.

- Observation: The main-only wrapper guard and all final browser resource checks behave as required in this worktree.
  Evidence: Fresh `npm run qa:nightly-ui` exited 1 before browser work with `Nightly UI QA must run from main. Current branch is HEAD. Pass --allow-non-main to override.` No `--allow-non-main` override was used. The final browser-inspection Node suite passed 94 tests with 2 environment-gated skips. `npm run browser:cleanup` reported `ok: true` and `stopped: []`; task dev supervisor PID 5427 received `SIGTERM`, root PID 81937 exited, and no watcher remained.

## Decision Log

- Decision: Preserve a single compact baseline JSON file at `tmp/browser-inspection/nightly-baseline.json`, outside all run directories, rather than retaining more screenshots or run directories.
  Rationale: Novelty needs only stable scenario-result fields. A root-level JSON file survives `ArtifactStore` directory pruning, adds negligible storage, and does not extend retention of screenshots or snapshots.
  Date/Author: 2026-09-12 / Codex and maintainer-approved scope.

- Decision: Define a finding fingerprint as SHA-256 over the normalized scenario identity, viewport, route, state, severity, and message. Include the fingerprint in the local issue description and query existing local records before issuing `bd create`.
  Rationale: A scenario result is the actual unit being classified and filed. Persisting the marker `Nightly finding fingerprint: <sha256>` in the issue evidence makes duplicate prevention deterministic and auditable. An exact historical match deduplicates regardless of whether its local issue is open or closed; the report must display that status.
  Date/Author: 2026-09-12 / Codex and maintainer-approved scope.

- Decision: Re-attempt filing every qualifying current product regression, automation gap, or coverage gap that has no matching fingerprint, even when the comparison classifies it as `EXISTING`.
  Rationale: `NEW` communicates novelty, not whether an issue was successfully created. A lookup or create outage must leave a persistent failure eligible for a later successful filing attempt.
  Date/Author: 2026-09-12 / Codex and maintainer-approved scope.

- Decision: Accept a persisted baseline only when it has a nonempty `nightly-ui-qa-*` run ID, nonempty run directory, supported top-level state, and valid compact result states. Let callers request `{ withSource: true }` to receive the retained-run, durable-baseline, or none provenance alongside the summary.
  Rationale: A malformed or incomplete persisted file must not affect novelty. Explicit provenance makes an unexpected first-run classification explainable in the run artifact and report.
  Date/Author: 2026-09-12 / Codex and maintainer-approved scope.

- Decision: Treat the margin editor as unavailable in the Spectate Mode scenario and prove editor presentation separately with a simulated writable account.
  Rationale: A deterministic test must validate the actual permissions of the state it creates. Spectating is a read-only product rule, while bottom-sheet rendering is still valuable coverage when editing is permitted.
  Date/Author: 2026-09-12 / Codex and maintainer-approved scope.

- Decision: Prevent statistic-cell shrinkage and place the desktop strip in an explicit horizontal scroll viewport rather than truncating or clipping prices.
  Rationale: Trade values must remain legible. The visible strip can fit naturally at wide desktop sizes; at narrower desktop sizes, scrolling preserves every complete label and number without letting neighboring cells paint over each other.
  Date/Author: 2026-09-12 / Codex and maintainer-approved scope.

- Decision: Treat only a serial browser run with an intact session registry as validation evidence for this change.
  Rationale: An `EPERM` registry-prune event prevents trustworthy attribution of failures and can disrupt an unrelated elevated browser. Closing the active visual session and rerunning avoids that shared-state interference.
  Date/Author: 2026-09-12 / Codex and maintainer-approved scope.

- Decision: Operate `npm run qa:nightly-ui` as one wrapper run at a time; do not add a cross-process issue-lock in this change.
  Rationale: The fingerprint lookup/create sequence is deterministic within one run and the scheduled automation is single-run by design. No concurrent execution was requested or observed. A lock would add separate failure and recovery behavior without evidence that it is needed for this remediation.
  Date/Author: 2026-09-12 / Codex and reviewer-bounded residual.

## Context and Orientation

The nightly command is `npm run qa:nightly-ui`, which starts at `/hyperopen/tools/browser-inspection/src/nightly_ui_qa.mjs`. It first checks that the current branch is exactly `main`, then builds the scenario list from `/hyperopen/tools/browser-inspection/scenarios`. A scenario is a JSON instruction sequence that navigates the app, uses the debug bridge for deterministic state only, captures artifacts, and reports `pass`, `product-regression`, `automation-gap`, or `manual-exception`. The runner writes full artifact bundles below `tmp/browser-inspection/<run-id>/`; that directory is intentionally pruned after 72 hours.

`/hyperopen/tools/browser-inspection/src/nightly_ui_coverage.mjs` clones the `/trade` and `/portfolio` route smokes for these three addresses: `0x162cc7c861ebd0c06b3d72319201150482518185`, `0x2ba553d9f990a3b66b03b2dc0d030dfc1c061036`, and `0x4096d3377ae5ade578daae8188804740c8b1da3e`. `/vaults` remains a baseline route with no spectate query. The mobile margin scenario intentionally uses the last address because it has a populated position fixture, but a populated position does not grant editing rights.

The `/trade` active-asset strip is rendered by `/hyperopen/src/hyperopen/views/active_asset/row.cljs`. `data-column` produces one label/value pair and each desktop `asset-stat-cell` contains one such pair. The existing CSS-class grid gives the numeric columns zero as their minimum width. The corrective layout must expose a stable `data-role` on the scrollable statistics region and on individual statistic cells or value containers so a browser test can compare actual rectangles rather than infer readability from a screenshot.

## Plan of Work

### Milestone 1: Preserve readable trade statistics

In `/hyperopen/src/hyperopen/views/active_asset/row.cljs`, wrap the desktop strip in a container whose horizontal overflow is deliberate and accessible. Give its inner grid a content-based minimum width and give every numeric statistic cell a nonzero minimum inline size; preserve the existing visual order, `lg` breakpoint, market selector, values, and funding cell. The helper extracted for this layout must remain in the dedicated source namespace so the owning namespace stays below the 511-line project limit. Use a named `data-role` for the scroll container and per-column anchors suitable for Playwright. The scroll viewport must receive normal keyboard focus; it cannot use `tabindex="-1"`. The exact class values may follow repository Tailwind conventions, but the behavior must be that a cell never narrows until its contents collide with another cell.

Add a deterministic Playwright regression in `/hyperopen/tools/playwright/test/trade-regressions.spec.mjs` or the narrowly appropriate trade spec. Seed a representative active market with long Mark, Oracle, change, volume, open-interest, and funding values through the existing debug bridge. At 1280 and 1440 pixels, collect each statistic label/value rectangle and assert that adjacent rectangles do not intersect and remain inside the strip. At the smallest desktop width where content exceeds the viewport, assert the scroll container has horizontal overflow and that changing its scroll position makes the rightmost Funding cell visible. This test must use the new stable anchors rather than text-only selectors.

### Milestone 2: Make margin assertions match account permissions

Change `/hyperopen/tools/browser-inspection/scenarios/mobile-position-margin-presentation.json` so its Spectate Mode route still verifies that the rich position loads, then verifies the position-margin action does not create an open margin overlay or mobile sheet. It must not use a direct dispatch to claim a sheet should open for a spectated account. If a product-visible disabled affordance exists, the scenario may additionally assert it is unavailable, but its core proof is that the overlay oracle remains closed.

Update `/hyperopen/tools/playwright/test/mobile-regressions.spec.mjs` to make two independent assertions with the existing debug bridge. Both must seed and expand a visible mobile position card and operate the user-visible position-card control; neither may open the editor by directly dispatching the edit action. The Spectate Mode test loads the spectated route, expands its position card, attempts the reachable margin action, and asserts that `[data-role='position-margin-mobile-sheet-layer']` and the open overlay state are absent. The editable-account test starts from an explicitly simulated connected, non-spectating owner account with the same seeded position, expands the card, clicks the actual owner Edit Margin trigger, and asserts the overlay oracle reports `open: true` and `presentationMode: "mobile-sheet"`, with the mobile sheet layer visible. The simulation must set sufficient account capability for the UI control rather than relying on a URL query or live wallet.

### Milestone 3: Make nightly novelty and filing durable

Add `/hyperopen/tools/browser-inspection/src/nightly_baseline.mjs`. It must define the compact persisted shape and validate it before use. A valid baseline has a nonempty `nightly-ui-qa-*` `runId`, a nonempty `runDir`, one supported top-level state, and compact result entries with nonempty scenario and viewport values and supported states. It stores only run identity, state, and the scenario fields required for comparison; it must not store screenshots, snapshots, secrets, or raw browser payloads. Read the baseline before `BrowserInspectionService.create()` so pruning cannot erase a valid comparison source, prefer any newer complete nightly run that still exists, and write the current compact baseline atomically only after the current scenario summary is complete. Callers requesting `withSource` receive the summary plus `retained-run`, `durable-baseline`, or `none` provenance and its path.

Integrate that module into `/hyperopen/tools/browser-inspection/src/nightly_ui_qa.mjs`. `classifyNightlyFailures` must receive the durable previous summary. Keep the existing per-run `run-meta.json`, `attempt-summary.tsv`, and `failure-classification.json` contract, but add enough baseline provenance to `run-meta.json` and the markdown report to show whether novelty came from a retained run or the durable baseline. A missing or malformed baseline is a first-run condition; it must not crash the wrapper and must be written only after a successful summary is available.

Extend `fileNightlyIssues` in the same entry point with an injected or otherwise testable lookup that reads local `bd` records. Compute the stable fingerprint for every qualifying current candidate before filing. Put `Nightly finding fingerprint: <sha256>` in a new issue description. If a local record in any status contains that exact marker, return a structured deduplicated result with its status and do not call `bd create`; render that outcome in the report. A qualifying persistent result without a matching record remains a filing candidate, so it retries after a lookup or create outage. Continue the established priority mapping for Critical/High product regressions and automation gaps. Treat a `bd` command that is absent or returns unreadable data as an explicit non-fatal filing error recorded in the report, rather than silently claiming deduplication.

Add focused Node tests under `/hyperopen/tools/browser-inspection/test/` for: a baseline surviving run-directory pruning; corrupt, missing, and incomplete-identity baselines; retained-run, durable-baseline, and none provenance through `withSource`; prior-result novelty from a saved baseline; stable fingerprint output; an exact closed or open historical record preventing a duplicate create; a different fingerprint creating an issue; persistent retry after lookup or creation outage; and report rendering for created, deduplicated, and lookup-error outcomes. Preserve the existing coverage-matrix tests.

### Milestone 4: Reconcile the local duplicate record and prove the complete path

Completed on 2026-09-12: `bd show` confirmed that `hyperopen-hbzq`, `hyperopen-su40`, `hyperopen-jtvt`, and `hyperopen-w1vw` corresponded only to the former Spectate Mode margin-sheet expectation and contained no independent product finding. Each was reclassified as a task and closed as a duplicate of `hyperopen-cyoz`; the primary automation tasks remain open while this change is validated. No `bd sync`, remote tracker operation, or unverified record update occurred.

Run the focused Node and Playwright tests first. The visual browser session is closed and the automated design review has passed 24/24, but the separate manual review remains a qualified `FAIL` for the pre-existing 768 header overlap. The completed serialized replacement matrix, `remediation-ui-qa-2026-09-12T15-09-34-367Z-f5fd391a`, met all required route and address coverage but finished 22/23 due only to a real `trade-funding-tooltip-layering` hit-test regression. The final focused Playwright correction is GREEN 2/2 in 43 seconds across fixed-layer hit ownership, viewport containment, responsive dismissal, closed `aria-expanded="false"`, scroll close/reopen, and mobile-sheet behavior; manual tooltip validation also passes at 1280/1440 and 375/768, with both visual sessions closed. The repaired scenario itself now passes as `tooltip-remediation-2026-09-12T15-49-13-066Z-f28cf68d` after explicit scroll, settle, focus, real-trigger click, and actual ownership samples. Do not use `scenario-2026-09-12T15-48-07-720Z-656a5517` as QA evidence: it failed only from a duplicate managed-app startup; its redundant process was stopped. Gates rerun 4050 passes. The owner-fixture bootstrap race is resolved by freezing synchronization before wallet connection: the full mobile file passes 6/6 in 43.7 seconds and the unchanged real-click owner test passed 3/3 repetitions in 21 seconds. The clean serialized matrix `remediation-ui-qa-2026-09-12T15-50-17-621Z-0c49e6a9`, run with `manageLocalApp=false`, passes 23/23 and satisfies coverage for all required routes and spectate addresses with zero product, automation, or manual classifications. Fresh `npm run qa:nightly-ui` exits 1 before browser work on this `HEAD` worktree with its clear main-only message (no override), and final browser inspection passes 94 with 2 environment-gated skips; cleanup reports `ok: true` and `stopped: []`. The scheduled nightly automation performs the final main-only wrapper execution after reviewed changes are integrated; that future run is evidence to record, not a current worktree acceptance precondition. The reviewed change is not authorized to be landed as part of this plan. Clean up browser sessions after every inspection run.

## Concrete Steps

Run all commands from `/hyperopen` after `npm run setup:worktree` has made local dependencies available.

1. Run the focused Node contract tests while changing the baseline and issue logic:

       node --test tools/browser-inspection/test/nightly_ui_qa.test.mjs tools/browser-inspection/test/nightly_ui_coverage.test.mjs tools/browser-inspection/test/nightly_baseline.test.mjs

   Expect all 19 named tests to pass, including the retained-baseline, provenance, duplicate-fingerprint, and outage-retry cases.

2. Run the two deterministic mobile assertions and the new trade-strip layout assertion:

       npx playwright test tools/playwright/test/mobile-regressions.spec.mjs --grep "margin"
       npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "market statistics"

   The final changed/new margin selection passed 3/3 in 27.3 seconds: the Spectate test asserts `aria-expanded="true"`, clicks an expanded visible position-card action, and shows no margin sheet, while the simulated writable owner-account test clicks its actual margin trigger and shows a mobile sheet. The isolated Spectate check also passed 1/1 in 8.4 seconds. The initial broader `mobile-regressions` 5/6 result came from a missing live balance fixture, and its isolated rerun passed 1/1 in 6.2 seconds; do not characterize this transient as a measured bottom-navigation defect.

3. Run governed visual validation for the changed trade shell:

       npm run qa:design-ui -- --targets trade-route --manage-local-app

   The completed automated artifact `design-review-2026-09-12T15-08-19-034Z-ecd71a10` records PASS for all 24 required checks at 375, 768, 1280, and 1440 pixels. Scoped manual validation confirms no strip overlap or document overflow and a keyboard-focusable scroll viewport. Record the manual review's qualified overall FAIL for the pre-existing 768 header-navigation overlap separately without attributing it to this change or calling global trade-route visual QA a pass.

4. Run the repository gates required for source or browser-tooling changes:

       npm run gates

   Expect the final matrix to report PASS for `npm run check`, `npm test`, and `npm run test:websocket`.

5. Run the full browser scenario and design-review path in the worktree, then verify the nightly branch guard:

       npm run qa:pr-ui -- --include-compare --manage-local-app
       npm run qa:nightly-ui -- --dry-run

   Expect the worktree command to complete the selected scenario bundle and its design review with inspectable artifacts. In a non-`main` worktree, expect the nightly command to fail before browser work with a clear `main`-branch safety error. The scheduled main-only nightly run after integration must produce `preflight.json`, `attempt-summary.tsv`, `run-meta.json`, `failure-classification.json`, scenario artifacts, and `/hyperopen/docs/qa/nightly-ui-report-<YYYY-MM-DD>.md`; its report must list `/trade`, `/portfolio`, and `/vaults`, and all three required spectate addresses.

6. Stop inspection resources even after a failure:

       npm run browser:cleanup

   Expect the command to report zero active browser-inspection sessions.

## Validation and Acceptance

The change is accepted only when all of the following observable outcomes hold:

- At 1280 and 1440 pixels on `/trade`, every rendered desktop asset statistic has a non-intersecting label/value rectangle. At constrained desktop width, the designated strip exposes horizontal scrolling, has normal keyboard focusability, and lets the user scroll to the complete rightmost cell; no value is clipped, truncated as a replacement for scrolling, or painted over a neighbor. This is evidenced by the 24/24 automated review and the two manual validation summaries named in `Progress`.
- At 375 pixels in a Spectate Mode `/trade` session for `0x4096d3377ae5ade578daae8188804740c8b1da3e`, a visible seeded position card exposes the user-reachable margin action, but that action does not reveal `position-margin-mobile-sheet-layer` and the overlay oracle remains closed.
- At 375 pixels with the explicitly simulated connected writable owner account and the same seeded position, clicking the visible margin trigger on the position card opens `position-margin-mobile-sheet-layer`, and the overlay oracle returns `open: true` and `presentationMode: "mobile-sheet"`.
- The saved root-level baseline remains readable after a run-directory prune, lets the next equivalent failure classify as `EXISTING`, and a missing, corrupt, or identity-incomplete baseline behaves as a safe first run. `run-meta.json` and the report identify whether comparison used a retained run, durable baseline, or no source.
- Two runs containing the exact same fingerprint create at most one local `bd` record even when the matching historical record is closed. The second report clearly identifies the marker `Nightly finding fingerprint: <sha256>`, existing record, and its status. A different fingerprint remains eligible for a new record under the existing severity-to-priority mapping.
- A qualifying persistent failure without a recorded issue retries local lookup and creation after a previous lookup or creation outage; the report records the prior error rather than treating it as a successful deduplication.
- The four resolved duplicate records remain closed as duplicates of `hyperopen-cyoz`; the final main-only report does not recreate them for the corrected margin assertion.
- Focused Node and Playwright tests, worktree scenario/design validation, `npm run gates`, non-`main` wrapper-guard verification, and browser cleanup complete with their outcomes recorded in `Progress`, `Surprises & Discoveries`, and `Outcomes & Retrospective`. The scheduled automation then performs and records the final `main` run after integration.

## Idempotence and Recovery

The baseline write must use a unique adjacent temporary file and atomic rename. If the process stops before rename, only the temporary file remains and the previous valid baseline continues to be read. A malformed baseline is ignored as a first-run comparison source and must be overwritten only after a valid current summary finishes. Re-running the nightly command replaces the compact current baseline and creates a new dated report for that run; it does not require restoring pruned artifact directories.

The change must not delete old QA reports or extend artifact retention. If browser startup or local bind fails, preserve the generated preflight and classification artifacts, then use the wrapper's trusted Chrome attach fallback documented by the nightly command. Always run `npm run browser:cleanup` after an interrupted inspection. If `bd` is unavailable, preserve the report's error and leave local issue state unchanged.

## Artifacts and Notes

The September 10 observed symptom is captured here for continuity:

    /trade at 1280px: market-stat values overlap.
    mobile-position-margin-presentation at Spectate URL: expected margin sheet open, observed closed.
    Root cause of repeated novelty: previous summary lived only in a 72-hour-pruned run directory.

The expected durable-baseline shape is intentionally small:

    {
      "runId": "nightly-ui-qa-…",
      "runDir": "/hyperopen/tmp/browser-inspection/nightly-ui-qa-…",
      "state": "pass | product-regression | automation-gap | manual-exception",
      "results": [
        { "scenarioId": "…", "viewport": "…", "route": "…", "state": "…", "severity": "…", "message": "…", "url": "…" }
      ]
    }

The data is sufficient to derive novelty but intentionally contains no image or browser-capture content.

## Interfaces and Dependencies

`/hyperopen/tools/browser-inspection/src/nightly_baseline.mjs` exports deterministic `findingFingerprint(result)` and the load/save operations used by the wrapper. `loadNightlyBaseline(artifactRoot)` returns either a validated compact summary or `null`; `loadNightlyBaseline(artifactRoot, { withSource: true })` returns `{ summary, source, sourcePath }`, where source is `retained-run`, `durable-baseline`, or `none`; and `saveNightlyBaseline(artifactRoot, summary)` writes only a validated compact summary atomically. Its path resolves to `<artifactRoot>/nightly-baseline.json`, which is a root-level file and therefore is not selected by `ArtifactStore.pruneExpiredRuns`.

`classifyNightlyFailures(summary, previousSummary, expectedRouteCoverage)` retains its public return fields and classifies novelty against the durable previous summary supplied by the wrapper. `fileNightlyIssues(...)` considers every qualifying current failure, uses `Nightly finding fingerprint: <sha256>` for all-status deduplication, and returns enough structured information for the report to distinguish a created issue, an existing record and status, and a failed lookup or creation. It is an operational single-wrapper-run contract, not a cross-process locking mechanism. Tests must be able to invoke this behavior without a real `bd` binary.

The browser anchors introduced for the strip must be stable product-test contracts: one scroll-container role and one role or indexed selector per asset statistic. They should not expose debug-only state. The Playwright tests continue using the existing `HYPEROPEN_DEBUG` bridge only to seed deterministic local state.

## Outcomes & Retrospective

### Handoff inventory (current worktree)

The changed-file inventory is complete at this handoff point; it must be rechecked only if a subsequent reviewed fix changes the worktree.

Application and UI behavior:

- `src/hyperopen/asset_selector/funding_drafts.cljs`
- `src/hyperopen/schema/contracts/common.cljs`
- `src/hyperopen/views/active_asset/funding_tooltip.cljs`
- `src/hyperopen/views/active_asset/funding_tooltip_runtime.cljs` (new)
- `src/hyperopen/views/active_asset/row.cljs`
- `src/hyperopen/views/active_asset/statistics.cljs` (new)
- `src/hyperopen/views/active_asset/vm.cljs`

Unit and browser contracts:

- `test/hyperopen/asset_selector/funding_drafts_test.cljs`
- `test/hyperopen/views/active_asset/funding_tooltip_popover_test.cljs`
- `test/hyperopen/views/active_asset/row_test.cljs`
- `tools/browser-inspection/scenarios/mobile-position-margin-presentation.json`
- `tools/browser-inspection/scenarios/trade-funding-tooltip-layering.json`
- `tools/playwright/test/mobile-regressions.spec.mjs`
- `tools/playwright/test/trade-regressions.spec.mjs`

Nightly tooling and documentation:

- `tools/browser-inspection/src/nightly_baseline.mjs` (new)
- `tools/browser-inspection/src/nightly_ui_qa.mjs`
- `tools/browser-inspection/test/nightly_baseline.test.mjs` (new)
- `docs/runbooks/browser-live-inspection.md`
- `docs/exec-plans/active/2026-09-12-nightly-ui-remediation.md` (this plan)

Observed validation commands and results:

- `npm run setup:worktree` completed.
- `node --test tools/browser-inspection/test/nightly_ui_qa.test.mjs tools/browser-inspection/test/nightly_ui_coverage.test.mjs tools/browser-inspection/test/nightly_baseline.test.mjs` passed 19 focused contracts.
- The full browser-inspection suite passed 94 tests with 2 environment-gated skips (96 total).
- `npx playwright test tools/playwright/test/mobile-regressions.spec.mjs --grep "margin"` passed the three changed/new margin assertions in 27.3 seconds; the full mobile file later passed 6/6 in 43.7 seconds, with the unchanged real-click owner path also passing three consecutive runs (3/3) in 21 seconds.
- The changed tooltip Playwright coverage passed 2/2 in 43 seconds; `tooltip-remediation-2026-09-12T15-49-13-066Z-f28cf68d` passed the repaired real-trigger scenario contract.
- `npm run qa:design-ui -- --targets trade-route --manage-local-app` produced `design-review-2026-09-12T15-08-19-034Z-ecd71a10` with 24/24 checks passing. The manual statistics, keyboard, and tooltip evidence cited above passed within the changed surface; global manual trade-route review remains qualified `FAIL` solely for the documented pre-existing 768 header overlap.
- `npm run gates` rerun 4050 passed 34/34 gates in 2m19s (7,130 tests, 39,485 assertions); compilation produced zero warnings and the two pre-existing test warnings remained unchanged.
- Fresh `npm run qa:nightly-ui` exited 1 before browser work with `Nightly UI QA must run from main. Current branch is HEAD. Pass --allow-non-main to override.` No override was used, which verifies the main-only guard.
- The final browser-inspection Node suite passed 94 with 2 environment-gated skips; `npm run browser:cleanup` reported `ok: true`, `stopped: []`. The task dev supervisor PID 5427 received `SIGTERM`, root PID 81937 exited, and watchers were clean.
- `git diff --check` and `npm run lint:docs` pass; the docs lint output contains only existing repository-wide stale-document advisories.

The clean final matrix is recorded: `remediation-ui-qa-2026-09-12T15-50-17-621Z-0c49e6a9`, launched through `tmp/run-nightly-remediation.mjs` with `manageLocalApp=false` after all isolated browser sessions were closed, passed 23/23 with its full coverage contract satisfied. The earlier duplicate-managed-app attempt is explicitly excluded as infrastructure-only evidence. The non-`main` wrapper guard, final browser-inspection suite, and cleanup evidence are recorded above. This plan remains active only for reviewed integration and the subsequent scheduled `main` run; neither landing nor remote sync is authorized in this worktree.

Worktree implementation and verification are complete. Baseline and filing contracts have 19 focused passing tests; the final browser-inspection suite has 94 passes and 2 environment-gated skips; final `npm run gates` rerun 4050 has 34/34 passes in 2m19s across 7,130 tests and 39,485 assertions; the app compiled with zero warnings and two pre-existing test warnings remained unchanged; the final three changed/new Playwright margin tests have passed in 27.3 seconds through an expanded Spectate-card action and a real owner Edit Margin click; the final isolated Spectate test passed 1/1 in 8.4 seconds after asserting `aria-expanded="true"`; final tooltip Playwright passed 2/2 in 43 seconds across hit ownership, viewport containment, responsive dismissal, closed `aria-expanded="false"`, scroll close/reopen, and mobile sheet; final review passed with no blockers; and the four duplicate local issues are reconciled. The active-statistics surface has passed its 24/24 automated design review and its manual no-overlap, no-document-overflow, and keyboard-focus validation; desktop tooltip validation passes at 1280/1440 and mobile tooltip validation passes at 375/768; each visual session is closed. The repaired real-trigger tooltip scenario passes, and the clean serialized matrix `remediation-ui-qa-2026-09-12T15-50-17-621Z-0c49e6a9` passed 23/23 with its full route and spectate-address coverage contract satisfied and no product, automation, or manual classifications. Fresh `npm run qa:nightly-ui` verifies that the wrapper stops before browser work on a non-`main` `HEAD` checkout; no override was used. Cleanup reports no active browser-inspection session and no watcher survives. The separate manual review remains a qualified FAIL due solely to the documented pre-existing 768 header overlap, so global trade-route visual QA is not complete. No clean pre-change RED run was obtained, so the original nightly result remains the failure baseline. The initial balances fixture result was transient and isolated green. The newly observed owner-fixture race is now resolved without weakening the test: freezing synchronization before wallet connection yielded a 6/6 full mobile-file pass in 43.7 seconds and three consecutive real-click owner passes in 21 seconds. A prior duplicate managed-app startup error is invalid infrastructure evidence; the redundant process was stopped. This plan stays active only for reviewed main integration and the scheduled main-only verification; neither landing nor remote sync is authorized in this worktree. The scheduled run must record its report path, run IDs, and durable-baseline provenance. This work reduces operational complexity by separating two permissions-valid UI checks from a single incorrect scenario and by replacing retention-dependent novelty with one compact, validated record.

Plan updated on 2026-09-12 to record the final post-tooltip gates rerun 54570, the 22/23 serialized coverage-complete matrix, deterministic tooltip RED evidence, expanded Spectate evidence, an isolated pass for the transient missing-balance-fixture result, the 24/24 automated review, scoped manual no-overlap and keyboard evidence, pre-existing qualified 768 manual FAIL, the single-wrapper operational dedup contract, and the pending scheduled main verification.

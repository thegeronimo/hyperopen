# Implement Agent-First UI QA Gate

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, Hyperopen no longer depends on ad hoc manual browser QA as the default final validator for risky frontend work. Contributors can run one PR-focused command for a deterministic critical UI bundle and one nightly command for the broader matrix. The browser tooling drives the app through dev-only `HYPEROPEN_DEBUG` hooks, reads named UI oracles, captures artifact bundles, and classifies failures as product regressions, automation gaps, or manual-exception cases instead of forcing a human to rediscover the same state by hand.

## Progress

- [x] (2026-03-09 20:04Z) Claimed `bd` issue `hyperopen-zud` for the agent-first UI QA gate implementation.
- [x] (2026-03-09 20:07Z) Re-read `/hyperopen/.agents/PLANS.md`, audited the existing browser-inspection CLI/MCP/nightly tooling, and confirmed there was no existing checked-in scenario runner or simulator lane to extend directly.
- [x] (2026-03-09 21:08Z) Implemented the browser-inspection scenario framework, checked-in manifests, CLI/MCP scenario commands, PR bundle wrapper, nightly bundle wrapper, and scenario-routing rules under `/hyperopen/tools/browser-inspection/`.
- [x] (2026-03-09 21:08Z) Added dev-only `HYPEROPEN_DEBUG` QA hooks for batched dispatch, idle waits, parity oracles, compact QA snapshots, simulator installation/reset, and wallet-connected-handler suppression for controlled pre-enable scenarios.
- [x] (2026-03-09 21:08Z) Added simulated wallet/provider and exchange seams that preserve the real runtime action/effect path while covering wallet connect, enable-trading, and order submit/cancel gating flows.
- [x] (2026-03-09 21:08Z) Converted the critical funding/mobile overlay/account-surface/agent-wallet/manual smoke flows into executable scenarios and added `/hyperopen/docs/qa/agent-first-ui-manual-exceptions.md` for the unsupported real-provider cases.
- [x] (2026-03-09 21:08Z) Ran `npm run check`, `npm test`, `npm run test:websocket`, `npm run test:browser-inspection`, `npm run qa:pr-ui`, and `npm run qa:nightly-ui -- --allow-non-main`; both PR and nightly scenario bundles passed and `/hyperopen/docs/qa/nightly-ui-report-2026-03-09.md` was generated.

## Surprises & Discoveries

- Observation: The current browser-inspection subsystem can already capture `HYPEROPEN_DEBUG.snapshot()` during snapshot runs.
  Evidence: `/hyperopen/tools/browser-inspection/src/capture_pipeline.mjs` evaluates `globalThis.HYPEROPEN_DEBUG.snapshot()` when available.

- Observation: The current nightly UI QA wrapper is still a hardcoded matrix of inspect captures, not a general scenario system.
  Evidence: `/hyperopen/tools/browser-inspection/src/nightly_ui_qa.mjs` defines `MATRIX_ATTEMPTS` inline and only calls `service.capture(...)`.

- Observation: High-risk overlay and funding flows already document why CDP click simulation is insufficient for anchor-sensitive UI.
  Evidence: `/hyperopen/docs/qa/funding-modal-workflow-slices-2026-03-07.md` and `/hyperopen/docs/qa/mobile-position-overlay-parity-qa-2026-03-08.md` both note that headless clicks do not preserve `:event.currentTarget/bounds`, while explicit debug dispatch with viewport-matched anchors does.

- Observation: Full `HYPEROPEN_DEBUG.snapshot()` payloads were too large for scenario capture on subscription-heavy trade routes and produced multi-hundred-megabyte artifacts.
  Evidence: Early `qa:pr-ui` runs wrote snapshot files approaching gigabyte-scale totals and one capture failed with `Invalid string length` before the capture pipeline switched to the compact `qaSnapshot()` payload.

- Observation: The order-form view-model intentionally does not surface `:agent-not-ready` as a disabled submit reason in `:view` mode.
  Evidence: `/hyperopen/src/hyperopen/state/trading.cljs` only applies `:agent-not-ready` inside `submit-policy` when `mode` is `:submit`; the scenario had to assert the runtime error after the submit action rather than expecting a pre-submit disabled reason.

## Decision Log

- Decision: Build the agent-first gate on top of the existing browser-inspection toolchain instead of introducing a second browser automation stack.
  Rationale: The repo already has session management, snapshot capture, compare artifacts, nightly classification, and MCP integration. Extending those surfaces keeps the operational model coherent.
  Date/Author: 2026-03-09 / Codex

- Decision: Use dev-only `HYPEROPEN_DEBUG` hooks plus named oracles as the primary pass/fail source, with screenshot and Hyperliquid compare artifacts as supporting evidence only.
  Rationale: Internal state and deterministic UI-mode oracles are less brittle than screenshot-only approval and already align with the repository’s pure-decision architecture.
  Date/Author: 2026-03-09 / Codex

- Decision: Simulate wallet behavior at the provider and exchange seam instead of mutating store state directly from the browser runner.
  Rationale: That preserves the real action/effect/runtime path and validates the application-owned boundaries that matter for UI correctness.
  Date/Author: 2026-03-09 / Codex

## Outcomes & Retrospective

Completed. Hyperopen now has a repo-owned agent-first UI QA gate with checked-in scenario manifests, deterministic debug oracles, simulator-backed wallet flows, PR/nightly entry points, CLI/MCP access, and generated nightly reporting. The main implementation risk was scenario determinism across async UI flows; that was handled by adding wallet-handler suppression for pre-enable flows, a `wait_for_oracle` scenario step for data-dependent mobile overlays, and a compact `qaSnapshot()` path to keep artifacts bounded.

## Context and Orientation

The existing browser tooling lives under `/hyperopen/tools/browser-inspection/`. `/hyperopen/tools/browser-inspection/src/cli.mjs` exposes the current command surface, `/hyperopen/tools/browser-inspection/src/mcp_server.mjs` registers Codex MCP tools, `/hyperopen/tools/browser-inspection/src/service.mjs` owns run creation and capture/compare workflows, and `/hyperopen/tools/browser-inspection/src/nightly_ui_qa.mjs` is the current hardcoded nightly wrapper.

The development debug bridge lives in `/hyperopen/src/hyperopen/telemetry/console_preload.cljs`. It already installs `globalThis.HYPEROPEN_DEBUG`, exposes a snapshot of the application store/runtime, and lets a browser session dispatch registered actions through the real runtime. This file is only loaded in debug builds, so it is the correct place to expose additional QA-only helpers.

Wallet connection currently flows through `/hyperopen/src/hyperopen/wallet/core.cljs`, which reads `window.ethereum` and uses EIP-1193 requests such as `eth_accounts` and `eth_requestAccounts`. Agent-wallet approval and signed order submission flow through `/hyperopen/src/hyperopen/api/trading.cljs`. Those two boundaries are the correct simulator seams because they preserve the app’s real runtime logic while swapping only the outside-world dependencies.

## Plan of Work

First, add a scenario manifest format and a scenario runner inside `/hyperopen/tools/browser-inspection/`. The runner will load one or more checked-in JSON manifests, start or attach a browser session using the existing service/session manager, execute each scenario step against the live page, evaluate named QA oracles through `HYPEROPEN_DEBUG`, capture current-page artifacts, optionally run route-level compare evidence, and write structured JSON plus markdown summaries for each scenario and the overall run.

Next, extend `/hyperopen/src/hyperopen/telemetry/console_preload.cljs` so a live browser session can drive deterministic QA flows without brittle DOM clicks. The additive API will include batched dispatch, idle waiting, parity-id rectangle lookup, named oracle lookup, and a reset helper that clears recent action traces and simulator state between scenarios. The oracle layer will read existing state and view-model seams for funding modal state, selected account surface/tab, wallet status, and position overlay presentation/counts.

Then, add simulated wallet and exchange seams. The wallet provider seam will allow a fake EIP-1193 provider to be installed for the debug session, while the trading API seam will allow predictable approve-agent and signed-action responses. These hooks must stay dormant unless explicitly enabled by the debug API.

After that, convert the first set of high-risk flows into checked-in scenario manifests: route smoke for `/trade`, `/portfolio`, and `/vaults`; funding modal workflow; mobile account-surface switch; mobile position overlay presentation; and simulated wallet enable-trading flow. PR validation will run the `critical` tag by default. Nightly validation will run the broader tag set and keep the existing classification/report behavior, but driven by scenario results instead of hardcoded capture attempts.

Finally, update operator-facing docs, keep a short runbook for manual-exception cases such as real extension prompts and hardware-wallet latency, run the full validation commands, and update both this ExecPlan and the `bd` issue with the results.

## Concrete Steps

Run all commands from `/Users/barry/.codex/worktrees/49f4/hyperopen`.

1. Implement the scenario framework and checked-in manifests.
2. Implement the debug QA hooks and simulator seams.
3. Update package scripts and docs.
4. Run:
   - `npm run test:browser-inspection`
   - `npm run qa:pr-ui -- --dry-run`
   - `npm run qa:nightly-ui -- --allow-non-main --dry-run`
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`
   - `npm run qa:pr-ui`
   - `npm run qa:nightly-ui -- --allow-non-main`

Expected observable outcomes include a `tmp/browser-inspection/scenario-*` run directory with per-scenario JSON and markdown outputs, deterministic `HYPEROPEN_DEBUG` oracle results in snapshot payloads, and a dated nightly report under `/hyperopen/docs/qa/`.

## Validation and Acceptance

Acceptance is behavioral. After the implementation, `npm run qa:pr-ui` should execute the fixed `critical` scenario bundle and exit successfully when the local app satisfies the encoded UI oracles. `npm run qa:nightly-ui -- --allow-non-main` should run the broader bundle, produce a machine-readable classification artifact, and write `/hyperopen/docs/qa/nightly-ui-report-YYYY-MM-DD.md` without relying on a hardcoded list of capture URLs.

The debug API must remain dev-only. In debug builds, `HYPEROPEN_DEBUG` should expose new methods for batched dispatch, waiting for idle, querying a parity-id rectangle, reading named oracles, and managing QA simulator state. Existing console-preload tests must keep passing, and new tests must prove the added helpers work in isolation.

The simulator acceptance is that a browser-driven QA scenario can connect a fake wallet, enable trading through the normal runtime action/effect flow, and observe the expected wallet state transitions and effect-order trace without requiring a real extension prompt.

## Idempotence and Recovery

The scenario runner only writes additive artifacts under `/hyperopen/tmp/browser-inspection/` and dated docs under `/hyperopen/docs/qa/`. Re-running either QA command creates a fresh timestamped run directory. The simulator hooks are resettable through the debug API and must not persist across page reloads. If a scenario run fails midway, rerunning the same command should be safe; previous artifacts remain as evidence.

## Artifacts and Notes

Initial tracked issue:

    bd id: hyperopen-zud
    title: Implement agent-first UI QA gate

Key existing source files:

    tools/browser-inspection/src/cli.mjs
    tools/browser-inspection/src/mcp_server.mjs
    tools/browser-inspection/src/service.mjs
    tools/browser-inspection/src/nightly_ui_qa.mjs
    src/hyperopen/telemetry/console_preload.cljs
    src/hyperopen/wallet/core.cljs
    src/hyperopen/api/trading.cljs

## Interfaces and Dependencies

The new scenario framework will define checked-in JSON manifests under `/hyperopen/tools/browser-inspection/scenarios/`. Each manifest must have a stable `id`, `title`, `tags`, `viewports`, `url`, and `steps`. The runner will support at least `navigate`, `dispatch`, `dispatch_many`, `wait_for_idle`, `oracle`, `capture`, and `compare` step types.

`HYPEROPEN_DEBUG` must expose additive dev-only functions with stable names:

    registeredActionIds() -> string[]
    dispatch(actionVector) -> {dispatched, actionId, argCount}
    dispatchMany(actionVectors) -> {dispatchedCount, actionIds}
    waitForIdle(options) -> Promise<object>
    elementRect(parityId) -> object|null
    oracle(name, args) -> object
    qaReset() -> object

The simulator boundary must keep the existing public runtime actions and effects intact. It may add additive helper functions in `/hyperopen/src/hyperopen/wallet/core.cljs` and `/hyperopen/src/hyperopen/api/trading.cljs`, but no production user-facing interface should change.

Revision note: 2026-03-09 initial ExecPlan created after claiming `hyperopen-zud` and auditing the existing browser-inspection, console-preload, wallet, and trading seams that the implementation will extend.

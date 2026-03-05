# Info POST Telemetry Foundation and WS Migration Phase 1

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, `/info` POST traffic will be observable by request type and calling path so we can measure where bursty polling behavior is concentrated before migrating those flows to websocket subscriptions. A developer will be able to inspect runtime request stats and see which request types and sources are creating the most load, how long those requests take, and where 429 rate-limit responses are clustered.

You can verify this by running API tests that assert request stats include per-type and per-source counters and latency aggregates, then by exercising startup/runtime paths and observing the startup summary log include the expanded request telemetry structure.

## Progress

- [x] (2026-03-05 02:44Z) Claimed `hyperopen-nhv.1` in `bd` and confirmed execution plan requirement from `/hyperopen/docs/PLANS.md` and `/hyperopen/.agents/PLANS.md`.
- [x] (2026-03-05 02:46Z) Audited `/hyperopen/src/hyperopen/api/info_client.cljs` and request flow modules to identify the safest instrumentation seam.
- [x] (2026-03-05 02:48Z) Authored this ExecPlan and defined milestone scope for telemetry-first implementation.
- [x] (2026-03-05 03:06Z) Implemented telemetry extensions in `/hyperopen/src/hyperopen/api/info_client.cljs` with per-type/per-source counters, latency aggregates, and 429 attribution while preserving legacy stats keys.
- [x] (2026-03-05 03:09Z) Added deterministic regression coverage in `/hyperopen/test/hyperopen/api_test.cljs` for type/source counting, unknown fallback attribution, latency aggregates, and rate-limit attribution.
- [ ] (2026-03-05 03:12Z) Required gate execution is partially blocked by environment dependency/tooling gaps (`@noble/secp256k1` missing; scripts depend on global `shadow-cljs` binary). Equivalent `npx` websocket suite passes.
- [ ] Update `bd` issue status and summarize baseline hotspots from new telemetry outputs.

## Surprises & Discoveries

- Observation: Existing request stats in `info_client` only track priority-level started/completed counts, rate-limited count, and max inflight, so there is no direct way to identify which `/info` payload type is causing pressure.
  Evidence: `/hyperopen/src/hyperopen/api/info_client.cljs` stores `:stats` with only `:started`, `:completed`, `:rate-limited`, and `:max-inflight-observed`.

- Observation: Startup already logs `get-request-stats` after boot, which gives us an immediate reporting surface once stats are expanded.
  Evidence: `/hyperopen/src/hyperopen/startup/runtime.cljs` function `schedule-startup-summary-log!` includes `:request-stats` in emitted log payload.

- Observation: Endpoint tests frequently assert exact opts maps passed to `post-info!`, so globally adding new opt keys at endpoint layer would create broad churn.
  Evidence: `test/hyperopen/api/endpoints/*_test.cljs` includes equality assertions like `{:priority :high}` and exact dedupe maps.

- Observation: A 429 retry path can produce two sleeps (explicit retry delay plus cooldown wait before the next enqueue) in the current queue/cooldown design.
  Evidence: `info-client-attributes-rate-limits-by-type-and-source-test` observed `count(sleeps) = 2` and passed after asserting this behavior.

- Observation: Repository scripts rely on globally installed `shadow-cljs` in some npm commands and environment lacks `@noble/secp256k1`, which blocks full `npm run check` and `npm test` verification.
  Evidence: `npm run check` failed during app compile with missing `@noble/secp256k1`; `npm test` and `npm run test:websocket` failed with `shadow-cljs: command not found`.

## Decision Log

- Decision: Instrument request telemetry in `/hyperopen/src/hyperopen/api/info_client.cljs` instead of changing every endpoint call option immediately.
  Rationale: `info_client` is the single fan-in for all `/info` POST requests and can derive type from body while accepting optional source tags; this yields broad coverage with lower regression risk.
  Date/Author: 2026-03-05 / Codex

- Decision: Use an optional `:request-source` option with fallback source classification (`:unknown` when omitted) rather than mandatory callsite changes in this milestone.
  Rationale: This preserves existing call signatures and allows incremental callsite tagging in later migration subtasks without blocking telemetry rollout.
  Date/Author: 2026-03-05 / Codex

- Decision: When `:request-source` is absent, derive source from `:dedupe-key` before dispatching attempts, and fall back to `"unknown"` only when neither is present.
  Rationale: This improves source granularity immediately without requiring broad callsite edits.
  Date/Author: 2026-03-05 / Codex

## Outcomes & Retrospective

Milestone 1 is implemented and test-covered. `info_client` now records request attribution and latency details required for migration planning:

- Added stats maps for `:started-by-type`, `:completed-by-type`, `:started-by-source`, `:completed-by-source`, `:latency-ms-by-type`, `:latency-ms-by-source`, `:rate-limited-by-type`, and `:rate-limited-by-source`.
- Preserved existing keys (`:started`, `:completed`, `:rate-limited`, `:max-inflight-observed`) for compatibility.
- Added source fallback path from `:dedupe-key`, then `"unknown"`.

Validation outcome:

- `npx shadow-cljs compile ws-test && node out/ws-test.js` passed (`Ran 298 tests containing 1718 assertions. 0 failures, 0 errors.`), including the new info-client telemetry tests in `hyperopen.api-test`.
- Full required gates remain environment-blocked and need dependency/tooling remediation before this issue can be closed with strict gate compliance.

## Context and Orientation

`/hyperopen/src/hyperopen/api/info_client.cljs` manages queueing, retry/backoff, dedupe, and request stats for all Hyperliquid `/info` POST calls. Every endpoint request eventually flows through `request-attempt!` in this file.

In this plan:

- “Request type” means the Hyperliquid payload `"type"` field (for example `"frontendOpenOrders"`, `"clearinghouseState"`, `"metaAndAssetCtxs"`).
- “Calling path” means a stable source tag associated with the request (via optional opts key `:request-source`).
- “Latency aggregate” means count/total/max observed duration in milliseconds for completed requests.
- “Rate-limit attribution” means counters that identify which type/source was associated with HTTP 429 responses.

`/hyperopen/src/hyperopen/startup/runtime.cljs` already emits request stats in a startup summary log; once info-client stats are expanded, this log becomes an immediate baseline report artifact for local profiling.

## Plan of Work

### Milestone 1: Expand info-client stats model with type/source and latency attribution

Update `info_client` runtime state to track request metadata (type and source) when a task is dequeued and to record completion latency and rate-limit attribution. Keep existing keys (`:started`, `:completed`, `:rate-limited`, `:max-inflight-observed`) unchanged to preserve compatibility.

Add new aggregates under `:stats`:

- `:started-by-type`
- `:completed-by-type`
- `:started-by-source`
- `:completed-by-source`
- `:rate-limited-by-type`
- `:rate-limited-by-source`
- `:latency-ms-by-type` (count/total/max)
- `:latency-ms-by-source` (count/total/max)

When source is not provided, classify as `:unknown`.

### Milestone 2: Add deterministic tests for new telemetry behavior

Add or extend tests to verify:

- request type is counted from body payload;
- source tag is counted from opts (`:request-source`) and defaults to `:unknown`;
- completion updates latency aggregates;
- rate-limit events increment both top-level and attributed counters.

### Milestone 3: Validate and prepare follow-on migration work

Run required quality gates, then summarize telemetry capabilities and hand off to next subtasks (`hyperopen-nhv.2` onward) that will replace high-churn request paths with websocket-first updates.

## Concrete Steps

From `/hyperopen`:

1. Edit `/hyperopen/src/hyperopen/api/info_client.cljs`.

   - Introduce helper functions for request type/source extraction and latency aggregate updates.
   - Track task metadata (`request-type`, `request-source`, `started-at-ms`) at dequeue/start.
   - Attribute started/completed/rate-limit counters by type/source.
   - Preserve existing stats keys and semantics.

2. Edit tests under `/hyperopen/test/hyperopen` (new info-client test file or existing API tests).

   - Add deterministic tests for type/source counters, latency, and 429 attribution.

3. Run validation commands:

   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

4. Update this ExecPlan with outcomes and evidence snippets.

## Validation and Acceptance

This milestone is accepted when all conditions are true:

1. `get-request-stats` still includes existing keys and now includes per-type/per-source counters and latency aggregates.
2. Requests without source metadata are attributed to `:unknown` source.
3. HTTP 429 handling increments top-level `:rate-limited` and attributed per-type/per-source counters.
4. New or updated tests verify telemetry behavior deterministically.
5. Required quality gates pass.

## Idempotence and Recovery

Changes are additive and local to request instrumentation. Re-running tests is safe. If any telemetry map growth becomes problematic, we can add bounded pruning by source/type in a follow-up without reverting core functionality.

## Artifacts and Notes

Key command results:

- `npm run check` failed: missing npm dependency `@noble/secp256k1` during app compile.
- `npm test` failed: `shadow-cljs` not on PATH in this environment.
- `npm run test:websocket` failed: same PATH issue.
- `npx shadow-cljs compile ws-test && node out/ws-test.js` passed with 0 failures.

## Interfaces and Dependencies

No public API signatures are removed. `request-info!` options gain optional, non-breaking support for `:request-source` attribution. Existing callers can remain unchanged.

Revision note (2026-03-05): Updated after implementing milestone 1 telemetry changes, adding regression tests, and recording validation blockers/evidence.

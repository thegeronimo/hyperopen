# Indicator SOLID/DDD Phase E Finalization

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

This phase completes the remaining SOLID/DDD hardening for indicators by finishing semantic module decomposition, introducing a math-engine abstraction boundary, strengthening domain contracts with semantic checks, removing migration-era terminology from domain metadata, and formalizing benchmark trend tracking workflows. After this phase, indicator domain code should be more cohesive, less coupled to low-level infrastructure details, and easier to evolve safely.

## Progress

- [x] (2026-02-15 19:10Z) Created active ExecPlan for Phase E finalization.
- [x] (2026-02-15 19:18Z) Milestone 1 completed: extracted semantic submodules for volatility (`volatility/range.cljs`, `volatility/dispersion.cljs`) and flow (`flow/volume.cljs`, `flow/money.cljs`), plus oscillator pattern extraction (`oscillators/patterns.cljs`) and family rewiring.
- [x] (2026-02-15 19:20Z) Milestone 2 completed: introduced math-engine boundary (`math_engine.cljs`) and rewired domain calculators to depend on `math-engine` instead of direct adapter symbols.
- [x] (2026-02-15 19:28Z) Milestone 3 completed: hardened contracts with known-indicator checks, strict numeric-string parsing, finite-series validation, unique-series-id checks, and aligned tests.
- [x] (2026-02-15 19:21Z) Milestone 4 completed: removed `:migrated-from :wave2/:wave3` residue from indicator catalog metadata.
- [x] (2026-02-15 19:31Z) Milestone 5 completed: documented kernel benchmark governance and baseline refresh workflow in `/hyperopen/docs/QUALITY_SCORE.md`; strict mode remains opt-in through `HYPEROPEN_STRICT_KERNEL_BENCH`.
- [x] (2026-02-15 19:32Z) Milestone 6 completed: required validation gates passed and evidence captured.

## Surprises & Discoveries

- Observation: strict contract changes surfaced stale test assumptions around unknown indicators and numeric-string parsing.
  Evidence: `contracts_test.cljs` initially expected `:unknown-indicator` inputs to be valid and exposed a regex over-escaping bug that rejected `"14"` string periods.
- Observation: catalog metadata cleanup is safest as token-level removal, not full-line deletion.
  Evidence: first bulk deletion attempt broke map delimiters in catalog files; recovery and token-only replacement preserved syntax.

## Decision Log

- Decision: run all five requested hardening items in one phase plan with milestone checkpoints.
  Rationale: the tasks are coupled (module extraction and dependency inversion overlap), and one plan keeps design intent and migration evidence coherent.
  Date/Author: 2026-02-15 / Codex
- Decision: keep benchmark trend enforcement opt-in via environment flag rather than default hard-fail.
  Rationale: benchmark timing varies across machines/CI classes; default soft tracking avoids flaky gates while preserving visibility.
  Date/Author: 2026-02-15 / Codex
- Decision: require `schema/known-indicator?` for both input and output contract validation.
  Rationale: this prevents silent acceptance of unsupported indicator IDs and tightens domain boundary semantics.
  Date/Author: 2026-02-15 / Codex

## Outcomes & Retrospective

Phase E achieved the targeted SOLID/DDD hardening outcomes. Remaining large indicator modules were semantically decomposed into cohesive domain submodules, domain calculators now depend on an explicit math-engine boundary rather than low-level adapter symbols, and contract validation is stricter and more semantically explicit. Migration-era wave terminology was removed from catalogs, benchmark governance is now documented with a baseline refresh workflow, and required quality gates passed.

## Context and Orientation

Relevant current files:

- `/hyperopen/src/hyperopen/domain/trading/indicators/volatility.cljs` remains a large mixed module even after channel extraction.
- `/hyperopen/src/hyperopen/domain/trading/indicators/flow.cljs` still combines multiple families (volume, money-flow, accumulation) in one file.
- `/hyperopen/src/hyperopen/domain/trading/indicators/oscillators/structure.cljs` remains a large composite module.
- `/hyperopen/src/hyperopen/domain/trading/indicators/math_adapter.cljs` is a direct infrastructure adapter currently depended on by domain modules.
- `/hyperopen/src/hyperopen/domain/trading/indicators/contracts.cljs` currently validates result shape/cardinality but still allows some semantic laxness.
- `/hyperopen/src/hyperopen/domain/trading/indicators/catalog/*.cljs` still include migration-era `:migrated-from :wave2/:wave3` metadata.
- `/hyperopen/test/hyperopen/domain/trading/indicators/math_kernels_test.cljs` includes micro-bench checks; baseline governance needs explicit workflow documentation.

## Plan of Work

Milestone 1 will continue semantic partitioning by extracting cohesive volatility and flow subdomains and splitting `oscillators/structure.cljs` further where practical.

Milestone 2 will introduce a math-engine abstraction layer (domain-facing port) and move domain modules to that interface so infrastructure swap/testing seams are explicit.

Milestone 3 will harden domain contracts for semantic correctness and drift prevention.

Milestone 4 will remove obsolete migration metadata from catalogs to clean ubiquitous language.

Milestone 5 will formalize benchmark baseline operations for long-term regression visibility.

Milestone 6 will run required gates and record evidence.

## Concrete Steps

From `/hyperopen`:

1. Extract and rewire semantic modules in `volatility`, `flow`, and `oscillators/structure`.
2. Add a math-engine protocol namespace and default adapter-backed implementation; migrate module requires/calls.
3. Strengthen contracts and update tests as needed.
4. Remove migration-residue metadata from all indicator catalogs.
5. Add benchmark-baseline governance docs/workflow artifacts and ensure optional strict mode remains opt-in.
6. Run:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`
7. Update this plan and move it to `completed` when done.

## Validation and Acceptance

Acceptance criteria:

- Remaining large modules are decomposed into semantic submodules with stable public family interfaces.
- Domain calculator modules no longer depend directly on `math_adapter`; they depend on the math-engine boundary.
- Contract checks reject semantically invalid numeric strings and non-finite series payloads.
- Catalog metadata contains no `wave2/wave3` migration residue.
- Benchmark trend workflow is documented and operational with optional strict gating.
- Required gates pass.

## Idempotence and Recovery

Refactors are additive and reversible by re-pointing calculator maps and aliases if a regression is found. No persistent migrations or irreversible data changes are involved.

## Artifacts and Notes

Validation evidence from `/hyperopen`:

- `npm run check` passed (`shadow-cljs compile app` and `shadow-cljs compile test` with 0 warnings).
- `npm test` passed (`Ran 789 tests containing 3062 assertions. 0 failures, 0 errors.`).
- `npm run test:websocket` passed (`Ran 86 tests containing 267 assertions. 0 failures, 0 errors.`).

Key artifacts:

- New semantic modules:
  - `/hyperopen/src/hyperopen/domain/trading/indicators/volatility/range.cljs`
  - `/hyperopen/src/hyperopen/domain/trading/indicators/volatility/dispersion.cljs`
  - `/hyperopen/src/hyperopen/domain/trading/indicators/flow/volume.cljs`
  - `/hyperopen/src/hyperopen/domain/trading/indicators/flow/money.cljs`
  - `/hyperopen/src/hyperopen/domain/trading/indicators/oscillators/patterns.cljs`
- New math-engine boundary:
  - `/hyperopen/src/hyperopen/domain/trading/indicators/math_engine.cljs`
- Benchmark governance documentation:
  - `/hyperopen/docs/QUALITY_SCORE.md`

## Interfaces and Dependencies

Public interfaces to preserve:

- `hyperopen.domain.trading.indicators.*-family` public accessor/calculate functions.
- `hyperopen.views.trading-chart.utils.indicators/*` facade entrypoints.

Primary internal dependencies:

- `hyperopen.domain.trading.indicators.family-runtime`
- `hyperopen.domain.trading.indicators.registry`
- `hyperopen.domain.trading.indicators.contracts`
- new math-engine boundary namespace introduced in this phase.

Plan revision note: 2026-02-15 19:10Z - Initial Phase E plan created for final SOLID/DDD hardening across semantic decomposition, dependency inversion, contracts, metadata cleanup, and benchmark governance.
Plan revision note: 2026-02-15 19:32Z - Completed milestones 1-6 with semantic extraction, math-engine inversion, contract hardening, catalog cleanup, benchmark governance docs, and passing required validation gates.

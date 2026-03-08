# Mobile Viewport Parity Audit for Trade, Portfolio, and Vaults

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md` and must be maintained in accordance with that file.

## Purpose / Big Picture

After this work, contributors will have a repository-native audit that compares HyperOpen and Hyperliquid on smaller viewports for `/trade`, `/portfolio`, and `/vaults`. The useful outcome is a written report in `/hyperopen/docs/qa/` backed by browser-inspection artifacts, so someone can see where the mobile and tablet layouts diverge visually and functionally without rerunning the entire investigation from scratch.

## Progress

- [x] (2026-03-07 23:14Z) Read repository entry-point guidance, planning/work-tracking policy, frontend policy, and browser-inspection runbook.
- [x] (2026-03-07 23:14Z) Created and claimed `bd` task `hyperopen-7fd` for this viewport parity audit.
- [x] (2026-03-07 23:14Z) Audited current HyperOpen page composition in `/hyperopen/src/hyperopen/views/trade_view.cljs`, `/hyperopen/src/hyperopen/views/portfolio_view.cljs`, `/hyperopen/src/hyperopen/views/vaults/list_view.cljs`, `/hyperopen/src/hyperopen/views/app_view.cljs`, and `/hyperopen/src/hyperopen/views/header_view.cljs`.
- [x] (2026-03-07 23:26Z) Captured successful phone and tablet browser-inspection evidence for `/trade`, `/portfolio`, and `/vaults`, using corrected reruns for vaults after an invalid concurrent-session capture.
- [x] (2026-03-07 23:27Z) Wrote the QA report in `/hyperopen/docs/qa/hyperopen-vs-hyperliquid-mobile-tablet-audit-2026-03-07.md` with page-by-page visual and functional findings plus artifact paths.
- [x] (2026-03-07 23:27Z) Filed follow-up epic `hyperopen-wrr` for implementation work discovered by the audit.
- [x] (2026-03-07 23:27Z) Ran documentation validation (`npm run lint:docs` and `npm run lint:docs:test`).
- [x] (2026-03-07 23:27Z) Closed `hyperopen-7fd` and moved this ExecPlan to `/hyperopen/docs/exec-plans/completed/`.

## Surprises & Discoveries

- Observation: The bundled browser-inspection compare command only knows `desktop` and `mobile` viewports from `/hyperopen/tools/browser-inspection/config/defaults.json`.
  Evidence: `defaults.json` defines `desktop` (`1440x900`) and `mobile` (`390x844`) only, so tablet capture requires using the lower-level session/capture modules directly.

- Observation: HyperOpen’s header navigation is hidden below `md`, leaving only the brand, wallet controls, utility icons, and a hamburger button on phones.
  Evidence: `/hyperopen/src/hyperopen/views/header_view.cljs` renders the primary nav with `hidden md:flex`.

- Observation: Reusing one live browser-inspection session for multiple compare commands in parallel can contaminate captures across routes.
  Evidence: the initial vaults compare pass produced `/portfolio` URLs in the saved vault snapshots and had to be rerun sequentially.

- Observation: HyperOpen’s local runtime was reachable during the successful compare captures but later dropped during follow-up live DOM probes.
  Evidence: later live evaluation attempts returned `chrome-error://chromewebdata/`, while the earlier compare artifacts already contained valid `http://localhost:8080/*` snapshots for the audited pages.

## Decision Log

- Decision: Treat this as a QA/reporting task with a checked-in report and inspection artifacts, rather than an implementation task.
  Rationale: The user asked for a comparative analysis and report, not UI changes. The highest-value deliverable is documented findings with evidence.
  Date/Author: 2026-03-07 / Codex

- Decision: Audit the `/vaults` list page, not a specific vault detail route.
  Rationale: The user requested the “vaults page,” and the primary comparable surface on both products is the vaults listing/overview entry route.
  Date/Author: 2026-03-07 / Codex

- Decision: Use a phone viewport at `390x844` and a tablet viewport at `1024x1366`.
  Rationale: `390x844` matches the existing mobile inspection defaults already used elsewhere in the repo, and `1024x1366` matches prior parity artifacts referenced in the portfolio PRD.
  Date/Author: 2026-03-07 / Codex

- Decision: Base the final report on the successful compare artifacts plus HyperOpen source review, and exclude later live-probe failures from the findings.
  Rationale: The compare artifacts are the reliable evidence set for this audit; the later localhost failures happened after capture and would otherwise add noise rather than insight.
  Date/Author: 2026-03-07 / Codex

## Outcomes & Retrospective

The audit is complete. The main deliverable is `/hyperopen/docs/qa/hyperopen-vs-hyperliquid-mobile-tablet-audit-2026-03-07.md`, which documents trade, portfolio, and vaults differences across phone and tablet viewports with explicit artifact paths under `/hyperopen/tmp/browser-inspection/`.

The strongest findings are:

- `/trade` diverges most in information architecture: Hyperliquid compresses more aggressively for smaller viewports, while HyperOpen keeps more raw desktop surface area visible or stacked.
- `/portfolio` diverges most at tablet width because HyperOpen remains single-column until `xl`, while Hyperliquid is already multi-column at `1024px`.
- `/vaults` is the nearest parity surface; the remaining gaps are mostly shell chrome, CTA/control treatment, and mobile presentation style rather than IA.

A follow-up epic, `hyperopen-wrr`, was created so the audit has an explicit implementation handoff rather than leaving the findings untracked.

## Context and Orientation

HyperOpen page routing is selected in `/hyperopen/src/hyperopen/views/app_view.cljs`. Routes beginning with `/trade` render `hyperopen.views.trade-view/trade-view`, routes beginning with `/portfolio` render `hyperopen.views.portfolio-view/portfolio-view`, and `/vaults` renders `hyperopen.views.vaults.list-view/vaults-view` unless the path matches a vault detail route. The global header lives in `/hyperopen/src/hyperopen/views/header_view.cljs`.

This audit relies on the browser-inspection subsystem under `/hyperopen/tools/browser-inspection/`. That subsystem can start or attach to a Chrome instance, navigate to pages, capture screenshots plus semantic DOM snapshots, and compare targets. Its runbook is `/hyperopen/docs/runbooks/browser-live-inspection.md`. Artifacts are stored under `/hyperopen/tmp/browser-inspection/`.

For this task, “visual differences” means observable layout, hierarchy, density, spacing, control grouping, and responsive adaptation at narrow widths. “Functional differences” means what actions or controls are exposed, hidden, collapsed, or reorganized in those viewports, plus any route- or interaction-level capability differences that are visible without authenticated trading actions.

## Plan of Work

First, capture reference evidence for Hyperliquid and HyperOpen on `/trade`, `/portfolio`, and `/vaults` using the browser-inspection tooling. Use the built-in compare workflow for the phone viewport and a lower-level scripted capture path for the tablet viewport because the stock compare command does not include a tablet preset. Persist every run under `/hyperopen/tmp/browser-inspection/` and record the final run directories in the QA report.

Second, inspect the generated screenshots, compare manifests, and semantic snapshots to extract the differences that matter to the user. Cross-check those findings against the HyperOpen view source so the report can distinguish intentional structural differences from capture noise or unauthenticated-state gaps.

Third, write a QA report in `/hyperopen/docs/qa/` that is organized by page (`/trade`, `/portfolio`, `/vaults`) and viewport (`phone`, `tablet`). Each section should summarize both visual layout differences and functional/interaction surface differences, then finish with a short set of cross-page themes and follow-up opportunities if the evidence suggests them.

## Concrete Steps

Run all commands from `/hyperopen`.

1. Start or reuse a browser-inspection session with local-app management so HyperOpen is reachable on localhost.
2. Capture phone viewport evidence for each target page with the browser-inspection compare flow.
3. Capture tablet viewport evidence for each target page with a one-off Node script that imports the existing session/capture modules and uses a `1024x1366` viewport override.
4. Review the generated `snapshot.json`, screenshots, and compare reports under `/hyperopen/tmp/browser-inspection/`.
5. Write the QA report in `/hyperopen/docs/qa/`.
6. Update this ExecPlan, close `hyperopen-7fd`, and move this file to `/hyperopen/docs/exec-plans/completed/`.

## Validation and Acceptance

Acceptance for this task is documentation-based rather than product-behavior-based:

1. `/hyperopen/docs/qa/` contains a dated report covering `/trade`, `/portfolio`, and `/vaults`.
2. The report includes both phone and tablet findings.
3. The report cites concrete evidence paths under `/hyperopen/tmp/browser-inspection/`.
4. The findings clearly separate visual/layout differences from functional/control-surface differences.
5. `bd` task `hyperopen-7fd` is closed after the report is written.

## Idempotence and Recovery

Browser-inspection captures are additive. Re-running the capture commands creates new timestamped artifact directories without mutating application source. If a capture run fails, retry by starting a fresh inspection session and note the successful run directory in the report. Editing the report and this plan is also safe to repeat; the latest checked-in content should always describe the final successful capture set.

## Artifacts and Notes

Planned artifacts:

- Browser-inspection run directories under `/hyperopen/tmp/browser-inspection/`
- QA report under `/hyperopen/docs/qa/`
- `bd` task `hyperopen-7fd`
- Follow-up epic `hyperopen-wrr`

## Interfaces and Dependencies

This work depends on:

- `/hyperopen/tools/browser-inspection/src/service.mjs`
- `/hyperopen/tools/browser-inspection/src/capture_pipeline.mjs`
- `/hyperopen/tools/browser-inspection/src/parity_compare.mjs`
- `/hyperopen/tools/browser-inspection/config/defaults.json`

No application runtime interfaces should change. The deliverables are documentation and captured evidence only.

Plan revision note: 2026-03-07 23:27Z - Completed the audit after writing the QA report, filing follow-up epic `hyperopen-wrr`, validating docs, and moving the plan to `/hyperopen/docs/exec-plans/completed/`.

---
owner: product+platform
status: completed
source_of_truth: true
tracked_issue: hyperopen-uqmy
---

# Black-Litterman Use My Views Editor ExecPlan

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept current while the work proceeds.

This document follows `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. It is self-contained so an engineer can execute the work without relying on the conversation that produced it.

Tracked issue: `hyperopen-uqmy` ("Implement Black-Litterman Use My Views editor").

## Purpose / Big Picture

Users building a new portfolio optimizer scenario should be able to choose `Use my views`, express absolute or relative Black-Litterman beliefs in a compact right-side editor, see exactly how each belief will be interpreted, and run the optimizer with those beliefs feeding expected returns. After this change, a user on `/portfolio/optimize/new` can add a statement such as "ETH outperforms SOL by 5% annualized", see it in an active views list, and know that it changes expected returns while leaving covariance risk unchanged.

The repository already has the important math seam. `src/hyperopen/portfolio/optimizer/domain/black_litterman.cljs` computes posterior returns from `P` rows, `Q` returns, covariance, prior weights, tau, and confidence variance. `src/hyperopen/portfolio/optimizer/application/request_builder.cljs` already attaches a Black-Litterman prior and normalizes draft views into `:weights` rows. The current user interface in `src/hyperopen/views/portfolio/optimize/black_litterman_views_panel.cljs` is only a primitive row editor that creates incomplete rows immediately. This plan replaces that primitive editor with a draft-based `Edit Views` rail, preserves the existing model pipeline, and adds deterministic tests plus governed browser QA.

## Progress

- [x] (2026-04-30 16:08Z) Read `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, `/hyperopen/docs/MULTI_AGENT.md`, `/hyperopen/docs/FRONTEND.md`, `/hyperopen/docs/BROWSER_TESTING.md`, and the UI/browser QA guides that govern this plan.
- [x] (2026-04-30 16:08Z) Inspected the optimizer setup route, existing Black-Litterman actions, request builder, domain math, engine integration, current panel tests, setup layout tests, and Playwright optimizer smoke coverage.
- [x] (2026-04-30 16:08Z) Created and claimed tracked issue `hyperopen-uqmy`.
- [x] (2026-04-30 16:08Z) Wrote this active ExecPlan.
- [x] (2026-04-30 17:13Z) Implemented the Black-Litterman editor state, validation, active view actions, right rail UI, preview model, deterministic tests, Playwright regression, and browser QA described below.
- [x] (2026-04-30 17:13Z) Added request normalization for new absolute/relative view shapes while preserving legacy long/short relative views.
- [x] (2026-04-30 17:13Z) Added center setup preview and saved-scenario input audit summaries stating that views affect expected returns only.
- [x] (2026-04-30 17:13Z) Ran CLJS tests, the new Playwright regression, design QA, browser cleanup, `npm test`, and `npm run test:websocket`.

## Surprises & Discoveries

- Observation: Black-Litterman is already implemented as a return-model mode, not as an objective.
  Evidence: `src/hyperopen/portfolio/optimizer/actions.cljs` maps `:black-litterman` under `return-models`, and `src/hyperopen/portfolio/optimizer/application/engine.cljs` calls `black-litterman/posterior-returns` only when `[:return-model :kind]` is `:black-litterman`.

- Observation: The existing editor writes active model views immediately, which conflicts with the desired preview-before-add workflow.
  Evidence: `src/hyperopen/portfolio/optimizer/black_litterman_actions.cljs` exposes `add-portfolio-optimizer-black-litterman-view`, which appends a default view with `:return 0.0` directly into `[:portfolio :optimizer :draft :return-model :views]`.

- Observation: Current persisted views use a low-level Clojure map shape, not the TypeScript shape in the supplied product outline.
  Evidence: Existing tests in `test/hyperopen/portfolio/optimizer/application/request_builder_test.cljs` expect `{:kind :relative, :long-instrument-id ..., :short-instrument-id ..., :return 0.04, :confidence 0.8, :weights {...}}`.

- Observation: New optimizer UI work must account for browser QA, including native-control review, because `/portfolio/optimize/new` is listed as `portfolio-optimizer-route` in `tools/browser-inspection/config/design-review-routing.json` with an empty `nativeControlAllowlist`.
  Evidence: `/hyperopen/docs/agent-guides/browser-qa.md` requires visual, native-control, styling-consistency, interaction, layout-regression, and jank/perf passes at widths `375`, `768`, `1280`, and `1440`.

- Observation: The required `npm run check` gate is currently blocked before build compilation by an unrelated active ExecPlan docs lint failure.
  Evidence: `npm run check` reports `[active-exec-plan-no-open-bd-issue]` and `[active-exec-plan-no-unchecked-progress]` for `docs/exec-plans/active/2026-04-30-optimizer-vault-search-latency.md`, which references only closed issue `hyperopen-8vx4`.

## Decision Log

- Decision: Keep Black-Litterman as a return-model mode and keep covariance/risk untouched by views.
  Rationale: This matches the existing architecture and the product requirement that views adjust expected returns only. The implementation should not modify `:risk-model`, covariance estimation, or objective selection when views change.
  Date/Author: 2026-04-30 / Codex

- Decision: Store user-entered view values as decimals in model state, while the rail accepts and displays percent text.
  Rationale: Existing model code expects `0.05` for 5%. The right rail should accept `5`, `5%`, and localized decimal text as a user-facing percent input, then normalize to `0.05` when saving a view.
  Date/Author: 2026-04-30 / Codex

- Decision: Use three confidence levels for V1: `:low`, `:medium`, and `:high`, mapped to numeric weights `0.25`, `0.50`, and `0.75`.
  Rationale: This matches the current visual design and supplied PRD. Existing math already interprets stronger confidence through lower `:confidence-variance`; use `1 - confidence-weight` as the first calibration and keep it centralized for later tuning.
  Date/Author: 2026-04-30 / Codex

- Decision: Store horizon as metadata only in V1.
  Rationale: The current optimizer annualizes expected returns and has no horizon-specific covariance or return horizon transform. The UI must display "annualized" plus the selected horizon, but the math should not pretend to apply a horizon adjustment until a model-level calibration decision exists.
  Date/Author: 2026-04-30 / Codex

- Decision: For relative underperformance, keep the displayed spread positive and encode direction in the `P` row by reversing weights.
  Rationale: `ETH < SOL by 5%` is easiest for users to read when the input remains `5%`. Store `:weights {"perp:ETH" -1, "perp:SOL" 1}` and `:return 0.05` for underperform; store `:weights {"perp:ETH" 1, "perp:SOL" -1}` and `:return 0.05` for outperform.
  Date/Author: 2026-04-30 / Codex

- Decision: Implement edit-on-card-click in V1.
  Rationale: The existing primitive editor already supports modifying rows after creation, and the supplied workflow strongly recommends editing. A draft-based form with `:editing-view-id` keeps the behavior explicit without adding drag/reorder or basket views.
  Date/Author: 2026-04-30 / Codex

- Decision: Do not build product analytics in this slice unless a product analytics adapter already exists at implementation time.
  Rationale: The repo currently exposes dev-only `hyperopen.telemetry/emit!`, but no product/privacy-reviewed analytics effect for user behavior. The implementation can keep event-name constants or dev telemetry hooks out of notes content, but shipping a real analytics path should be a separate product/privacy decision.
  Date/Author: 2026-04-30 / Codex

## Outcomes & Retrospective

Implemented. The feature increases UI state surface modestly by adding per-mode drafts, edit state, validation errors, and clear-confirmation state under `:portfolio-ui`, but keeps optimizer complexity bounded by reusing the existing Black-Litterman request and posterior-return pipeline. The primitive immediate-row editor is replaced with a clearer add/edit workflow, and the model layer still treats views as expected-return inputs only.

Validation completed:

    npm run test:runner:generate && npx shadow-cljs --force-spawn compile test
    node out/test.js
    npx playwright test tools/playwright/test/optimizer-black-litterman-views.spec.mjs
    npm run qa:design-ui -- --targets portfolio-optimizer-route --manage-local-app
    npm run browser:cleanup
    npm test
    npm run test:websocket

`npm run check` was attempted and is blocked by the unrelated active ExecPlan docs lint issue recorded in `Surprises & Discoveries`.

## Context And Orientation

The Portfolio Optimizer is a ClojureScript bounded context. Domain and application namespaces live under `src/hyperopen/portfolio/optimizer/**`; render components live under `src/hyperopen/views/portfolio/optimize/**`; runtime action registration lives in `src/hyperopen/runtime/action_adapters.cljs`, `src/hyperopen/app/actions.cljs`, `src/hyperopen/schema/contracts/action_args.cljs`, and `src/hyperopen/schema/runtime_registration/portfolio.cljs`.

The setup route is `/portfolio/optimize/new`. `src/hyperopen/views/portfolio/optimize/workspace_view.cljs` builds the v4 setup screen. It renders a three-column grid: setup controls from `setup_v4_sections.cljs`, center summary content from `setup_v4_sections.cljs`, and the right rail from `setup_v4_context.cljs`. When the draft return model is `:black-litterman`, `setup_v4_context.cljs` renders `black_litterman_views_panel.cljs` inside the right rail.

The current Black-Litterman draft state is stored at `[:portfolio :optimizer :draft :return-model]`. Existing return-model examples are `{:kind :historical-mean}`, `{:kind :ew-mean, :alpha ...}`, and `{:kind :black-litterman, :views [...]}`. Scenario persistence stores the draft map as EDN through `src/hyperopen/portfolio/optimizer/infrastructure/persistence.cljs`, so any fields added to views will be saved with the scenario.

The current Black-Litterman view shape is low level:

    Absolute view:
      {:id "bl_view_1"
       :kind :absolute
       :instrument-id "perp:HYPE"
       :return 0.45
       :confidence 0.25
       :confidence-variance 0.75
       :weights {"perp:HYPE" 1}}

    Relative outperform view:
      {:id "bl_view_2"
       :kind :relative
       :long-instrument-id "perp:ETH"
       :short-instrument-id "perp:SOL"
       :return 0.05
       :confidence 0.5
       :confidence-variance 0.5
       :weights {"perp:ETH" 1
                 "perp:SOL" -1}}

This plan evolves the view shape in a backward-compatible way. New relative views should include user-intent fields so the UI can preserve primary/comparator/direction even when weights are reversed:

    {:id "bl_view_2"
     :kind :relative
     :instrument-id "perp:ETH"
     :comparator-instrument-id "perp:SOL"
     :direction :outperform
     :return 0.05
     :confidence-level :medium
     :confidence 0.5
     :confidence-variance 0.5
     :horizon :3m
     :notes "ETH fundamentals improving vs SOL"
     :weights {"perp:ETH" 1
               "perp:SOL" -1}}

Legacy fields `:long-instrument-id` and `:short-instrument-id` must still be accepted by `request_builder.cljs` and by the editor when loading old saved scenarios. Do not require a migration to load existing scenarios.

Black-Litterman terms used in this plan:

`Prior` means the market reference return estimate implied by covariance, risk aversion, and prior weights. In this repo, prior weights are resolved in `src/hyperopen/portfolio/optimizer/infrastructure/prior_data.cljs`, preferring market-cap weights and falling back to current portfolio or equal weights.

`P row` means one row in the Black-Litterman pick matrix. It is represented in this codebase as a map in `:weights`, such as `{"perp:ETH" 1, "perp:SOL" -1}`.

`Q` means the return belief value for one view. It is represented as `:return`, stored as a decimal annualized return.

`Omega` means the view uncertainty matrix. This repo currently builds a diagonal Omega from each view's `:confidence-variance`.

## Plan of Work

Milestone 1 creates the durable state and action contract. Update `src/hyperopen/portfolio/optimizer/defaults.cljs` so `default-optimizer-ui-state` includes a `:black-litterman-editor` map. The map should hold `:selected-kind`, separate per-kind drafts, `:editing-view-id`, open menu keys for custom selector controls, and `:clear-confirmation-open?`. Draft text fields must stay as strings so invalid percent input can be displayed and validated without corrupting active model views.

Refactor `src/hyperopen/portfolio/optimizer/black_litterman_actions.cljs` around pure helpers for normalizing, validating, previewing, adding, editing, removing, and clearing views. Preserve the existing public action functions as compatibility wrappers where practical, but add explicit draft-editor actions for the new UI:

    :actions/set-portfolio-optimizer-black-litterman-editor-type
    :actions/set-portfolio-optimizer-black-litterman-editor-field
    :actions/open-portfolio-optimizer-black-litterman-editor-menu
    :actions/close-portfolio-optimizer-black-litterman-editor-menu
    :actions/save-portfolio-optimizer-black-litterman-editor-view
    :actions/edit-portfolio-optimizer-black-litterman-view
    :actions/cancel-portfolio-optimizer-black-litterman-edit
    :actions/remove-portfolio-optimizer-black-litterman-view
    :actions/request-clear-portfolio-optimizer-black-litterman-views
    :actions/cancel-clear-portfolio-optimizer-black-litterman-views
    :actions/confirm-clear-portfolio-optimizer-black-litterman-views

Register any new actions in `src/hyperopen/runtime/action_adapters.cljs`, `src/hyperopen/app/actions.cljs`, `src/hyperopen/schema/runtime_registration/portfolio.cljs`, and `src/hyperopen/schema/contracts/action_args.cljs`. Update `test/hyperopen/portfolio/optimizer/black_litterman_actions_test.cljs` first. The tests must cover valid absolute add, valid relative outperform add, valid relative underperform add, edit preserving view id, cancel edit, remove, clear confirmation, max ten views, invalid comparator equal to asset, missing value, negative relative spread, duplicate absolute view, legacy long/short normalization, and dirty metadata writes.

Milestone 2 keeps universe changes safe. Update `src/hyperopen/portfolio/optimizer/actions.cljs` so removing an instrument or replacing the universe from current holdings removes active Black-Litterman views that reference instruments no longer in the draft universe. It must also clear editor draft asset fields that point to removed instruments. Add regression coverage to `test/hyperopen/portfolio/optimizer/actions_test.cljs` proving that `remove-portfolio-optimizer-universe-instrument` removes referenced BL views and still removes allowlist, blocklist, locks, asset overrides, and perp leverage entries as it does today.

Milestone 3 upgrades request and model normalization without changing the solver contract. Update `src/hyperopen/portfolio/optimizer/application/request_builder.cljs` so `normalize-black-litterman-view` accepts the new UI-intent fields and old persisted fields. The normalized request view must always include valid `:weights`, decimal `:return`, numeric `:confidence`, and numeric `:confidence-variance`. Invalid or incomplete views should be dropped from the engine request and surfaced as request warnings with codes such as `:invalid-black-litterman-view`, because the editor should prevent them but persisted legacy data may still be malformed. Add tests in `test/hyperopen/portfolio/optimizer/application/request_builder_test.cljs` for new absolute, relative outperform, relative underperform, and malformed legacy views. Keep `src/hyperopen/portfolio/optimizer/domain/black_litterman.cljs` focused on pure math; add domain tests only if the underperform convention requires an explicit `P`/`Q` regression.

Milestone 4 replaces the right rail UI. Rework `src/hyperopen/views/portfolio/optimize/black_litterman_views_panel.cljs` into a draft-based `Edit Views` panel with this structure:

    EDIT VIEWS
    Tell the model what you believe
    ABSOLUTE / RELATIVE segmented tabs
    ASSET custom compact selector
    COMPARATOR custom compact selector only in relative mode
    DIRECTION segmented control
    EXPECTED RETURN or EXPECTED RETURN / SPREAD text input with percent unit
    CONFIDENCE segmented control LOW / MEDIUM / HIGH
    HORIZON segmented or compact menu with 1M / 3M / 6M / 1Y
    NOTES optional textarea with 280 character limit
    VIEW PREVIEW
    Add view or Save changes button
    ACTIVE VIEWS (n/10) and Clear all
    Active view cards
    Helper note that views adjust expected returns only

Do not add new native `select` elements in this rail. Use compact button/listbox or details-menu controls patterned after existing project dropdowns, with `aria-expanded`, `role="listbox"` where appropriate, per-option buttons, and keyboard-reachable actions. Existing optimizer surfaces may still contain native controls; browser QA should report those separately if they are pre-existing, but this feature should not introduce new native selects.

The panel should not be a card nested inside another card. When `Use my views` is active, `src/hyperopen/views/portfolio/optimize/setup_v4_context.cljs` should let the `Edit Views` rail be the primary right-rail content, with a scrollable body on desktop. Use the existing v4 optimizer colors in `src/styles/main.css`; add only scoped selectors under `.portfolio-optimizer-v4` and `data-role` anchors. The rail must remain usable at narrow heights by scrolling internally, and it must stack without right-side cutoff at smaller widths.

Update `test/hyperopen/views/portfolio/optimize/black_litterman_views_panel_test.cljs` to assert the new copy, roles, action payloads, disabled states, inline validation text, edit mode, active cards, clear confirmation, and helper note. Update `test/hyperopen/views/portfolio/optimize/setup_v4_layout_test.cljs` if the right-rail structure changes.

Milestone 5 adds setup preview and summary behavior. Create `src/hyperopen/portfolio/optimizer/application/black_litterman_preview.cljs` as a pure application namespace that accepts a readiness request and returns a small preview model. It should return prior expected returns, posterior expected returns when history and covariance are available, active view summaries, warnings, and stale/unavailable reasons. It must not run the optimizer solver and must not change covariance. Use existing `risk/estimate-risk-model`, `black-litterman/posterior-returns`, and current request data; cap the preview to the eligible request universe and return `{:status :unavailable}` when history is not ready.

Create `src/hyperopen/views/portfolio/optimize/black_litterman_preview_chart.cljs` to render the center-panel preview when the return model is `:black-litterman`. The chart should be a compact SVG or dense table-like chart, not a heavy new charting dependency. It should compare market reference and combined output per asset, and show a clear empty/unavailable state when no views exist or no eligible history is loaded. Update `src/hyperopen/views/portfolio/optimize/setup_v4_sections.cljs` so the center summary for `Use my views` shows the preview and a "Your views" summary instead of only static education copy. Update `src/hyperopen/views/portfolio/optimize/inputs_tab.cljs` so saved scenarios list active view summaries rather than only `Black-Litterman views: n`.

Add tests for the preview model in `test/hyperopen/portfolio/optimizer/application/black_litterman_preview_test.cljs`. Add view tests in `test/hyperopen/views/portfolio/optimize/setup_v4_layout_test.cljs` or a new `black_litterman_preview_chart_test.cljs` covering zero views, active views, posterior-unavailable copy, and the statement that risk covariance is unchanged.

Milestone 6 adds deterministic browser regression coverage. Add `tools/playwright/test/optimizer-black-litterman-views.spec.mjs`. Use the existing `visitRoute` and `waitForIdle` helpers from `tools/playwright/support/hyperopen.mjs`. Seed at least BTC, ETH, SOL, and HYPE markets through the debug bridge, navigate to `/portfolio/optimize/new`, select `Use my views`, add an absolute HYPE view, add a relative ETH outperform SOL view, assert the live preview text, assert `ACTIVE VIEWS (2/10)`, remove one view, clear all with confirmation, and assert the helper copy remains visible. If the custom selector interaction is too much for the first browser test, dispatch actions through `HYPEROPEN_DEBUG` only for state setup and still assert the rendered UI path with real clicks for add/remove/clear.

Milestone 7 runs validation and browser QA. Before signoff, run targeted tests first, then the required gates. From `/Users/barry/.codex/worktrees/238f/hyperopen`, run:

    npm run test:runner:generate
    npx shadow-cljs --force-spawn compile test
    node out/test.js
    npx playwright test tools/playwright/test/optimizer-black-litterman-views.spec.mjs
    npm run qa:design-ui -- --targets portfolio-optimizer-route --manage-local-app
    npm run browser:cleanup
    npm run check
    npm test
    npm run test:websocket

If `qa:design-ui` reports pre-existing native-control failures outside the new `Edit Views` rail, record them as residual risk in this ExecPlan and in the final handoff; do not silently ignore them. If it reports new native controls or layout clipping in the rail, fix those before broader gates.

## Validation and Acceptance

The feature is accepted when a human can navigate to `/portfolio/optimize/new`, choose `Use my views`, and complete these behaviors:

An absolute view can be added by selecting one asset, entering a percent expected return, choosing confidence and horizon, reading a live preview such as `HYPE expected return +45% annualized`, and clicking `+ Add view`. The active view list then shows the view, increments the count, marks the draft dirty, and the setup preview reflects an active view.

A relative view can be added by selecting a primary asset and comparator, choosing outperform or underperform, entering a positive annualized spread, reading a live preview such as `ETH > SOL by 5% annualized`, and clicking `+ Add view`. Comparator equal to asset must be blocked with a local error, and a negative relative spread must be blocked with a local message that direction controls sign.

Active views can be edited by clicking the card, saved without changing the id, removed by the card remove button, and cleared only after confirmation. At ten active views, add is disabled and the helper text explains the maximum.

The model request produced by `request_builder.cljs` must contain normalized view rows with consistent `:weights`, decimal `:return`, and `:confidence-variance`. For `ETH > SOL by 5%`, the request contains `:weights {"perp:ETH" 1, "perp:SOL" -1}` and `:return 0.05`. For `ETH < SOL by 5%`, the request contains `:weights {"perp:ETH" -1, "perp:SOL" 1}` and `:return 0.05`.

The center setup summary and inputs audit must make clear that views affect expected returns only. The implementation must not modify risk-model selection, covariance estimation, or objective semantics when views change.

All required validation commands in the previous section must pass, or any failure must be recorded here with the failing command, observed output, and the concrete blocker.

## Idempotence and Recovery

The implementation should be additive and safe to rerun. New UI state lives under `:portfolio-ui :optimizer :black-litterman-editor`; active model views remain under `:portfolio :optimizer :draft :return-model :views`. If a user switches away from `:black-litterman`, the draft views may remain in the return-model object only if the return-model object remains `:black-litterman`; switching to another return model should preserve the old behavior of replacing the return-model map. If later product wants cross-model preservation, that should be a separate decision.

If a saved scenario contains legacy views with `:long-instrument-id` and `:short-instrument-id`, the editor and request builder must load them. If a saved scenario contains malformed views, the request builder should drop them from the engine request and emit warnings rather than throwing during setup render or worker execution.

If browser QA leaves sessions open, run `npm run browser:cleanup`. Do not run `git pull --rebase` or `git push` unless the user explicitly requests remote sync in the same session.

## Artifacts and Notes

Expected new or modified files:

- Modify `src/hyperopen/portfolio/optimizer/defaults.cljs` for editor UI defaults.
- Modify `src/hyperopen/portfolio/optimizer/black_litterman_actions.cljs` for draft editor and active view actions.
- Modify `src/hyperopen/portfolio/optimizer/actions.cljs` to prune views on universe removal or replacement.
- Modify `src/hyperopen/portfolio/optimizer/application/request_builder.cljs` for new and legacy view normalization.
- Create `src/hyperopen/portfolio/optimizer/application/black_litterman_preview.cljs` for pure setup preview data.
- Modify `src/hyperopen/views/portfolio/optimize/black_litterman_views_panel.cljs` for the new right rail.
- Create `src/hyperopen/views/portfolio/optimize/black_litterman_preview_chart.cljs` for center summary preview.
- Modify `src/hyperopen/views/portfolio/optimize/setup_v4_context.cljs` so the right rail is not nested-card-heavy and can scroll.
- Modify `src/hyperopen/views/portfolio/optimize/setup_v4_sections.cljs` for Use My Views preview summary.
- Modify `src/hyperopen/views/portfolio/optimize/inputs_tab.cljs` to audit saved views.
- Modify `src/styles/main.css` only with `.portfolio-optimizer-v4` scoped rules.
- Modify `src/hyperopen/runtime/action_adapters.cljs`, `src/hyperopen/app/actions.cljs`, `src/hyperopen/schema/contracts/action_args.cljs`, and `src/hyperopen/schema/runtime_registration/portfolio.cljs` for new action registration.
- Modify `test/hyperopen/portfolio/optimizer/black_litterman_actions_test.cljs`.
- Modify `test/hyperopen/portfolio/optimizer/actions_test.cljs`.
- Modify `test/hyperopen/portfolio/optimizer/application/request_builder_test.cljs`.
- Create `test/hyperopen/portfolio/optimizer/application/black_litterman_preview_test.cljs`.
- Modify `test/hyperopen/views/portfolio/optimize/black_litterman_views_panel_test.cljs`.
- Modify or add setup preview view tests under `test/hyperopen/views/portfolio/optimize/**`.
- Create `tools/playwright/test/optimizer-black-litterman-views.spec.mjs`.

Revision note, 2026-04-30 / Codex: Initial active ExecPlan created from the supplied product outline, screenshots, and source-code inspection. The plan intentionally preserves the existing Black-Litterman math and request architecture while replacing the primitive editor workflow.

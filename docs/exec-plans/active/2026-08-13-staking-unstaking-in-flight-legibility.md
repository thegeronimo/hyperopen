# Make In-Flight HYPE Unstaking Legible Everywhere A HYPE Balance Appears

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md`. Keep it self-contained, update it whenever implementation discoveries change the plan, and leave at least one unchecked progress item while the plan remains active.

## Purpose / Big Picture

A user who has started unstaking HYPE cannot currently tell that from the Hyperopen UI. Their HYPE stops appearing in the Balances table entirely, the Portfolio "Staking Account" figure silently folds the in-flight amount into one opaque number, and the only acknowledgement anywhere in the product is a single unadorned row on the `/staking` page labelled "Pending Transfers to Spot Balance" showing a bare eight-decimal number. Nothing states that the amount is locked, nothing says how long the lock lasts, and nothing says when the HYPE will arrive.

The result is the reported failure: a user looked for their HYPE, could not find it, and only later worked out for themselves that it had not finished unstaking. Their own suggestion was that the UI "should just show HYPE 0 balance". That is half of the answer — a visible zero is better than a vanished row — but a bare zero is still a dead end, because it does not tell the user that the missing HYPE exists, that it is deliberately immobilised, or when it comes back.

After this change, a user who has unstaking in progress sees, without hunting for it: the exact amount of HYPE that is immobilised; plain-language confirmation that it cannot be traded or transferred yet; how many separate transfers are in the queue; and, when the data supports it, the date and time the HYPE arrives plus an approximate time remaining. They see this on the `/staking` page as a distinct, visually-separated block rather than a table row; on the Portfolio summary card as an explicit breakdown line under "Staking Account"; and in the Balances tab as a HYPE row that no longer disappears. They also learn, at the moment they press Unstake, that unstaking is a two-step process — a fact the product currently never states — so the confusion is prevented rather than explained after the fact.

## Context References

Public refs:

- Direct user/maintainer request on 2026-08-13, supplied with a screenshot of a chat conversation. Durable facts reproduced here so no future contributor needs the image: a user writes "oh wait nvm, my hype hasnt unstaked yet"; the maintainer (Geronimo) replies "you think the UI could better help you understand that?"; the user answers "should just show HYPE 0 balance". The maintainer's instruction to this plan's author was that showing a bare zero is itself confusing, and that the UI should instead communicate that the balance is locked or frozen for the duration of the unstaking period. The screenshot is a visual reference only; the three quoted lines above are the whole of its durable content.

Repo artifacts:

- `/hyperopen/docs/exec-plans/completed/2026-08-03-master-account-unstake-regression.md` is the immediately prior staking plan. It established the master/owner staking identity, the staking-specific mutation blocker, the strict-future delegation-lock guard, the nested Replicant event envelope requirement for dynamic controls, and the committed Playwright staking regression file. This plan builds on that work and must not regress it.
- `/hyperopen/AGENTS.md` requires this ExecPlan for UI work of this size and requires `npm run check`, `npm test`, and `npm run test:websocket` after code changes.
- `/hyperopen/docs/FRONTEND.md`, `/hyperopen/docs/agent-guides/ui-foundations.md`, `/hyperopen/docs/agent-guides/trading-ui-policy.md`, and `/hyperopen/docs/agent-guides/browser-qa.md` govern the UI rules and the six-pass browser-QA matrix this plan must satisfy.
- `/hyperopen/docs/BROWSER_TESTING.md` governs browser-tool routing and the mandatory cleanup step.

Local scratch refs (non-authoritative):

- None.

## Progress

- [x] (2026-08-13 18:05Z) Mapped the staking data layer, every HYPE-rendering surface, the reusable UI patterns, the test/gate surface, the contract cost of new state, and the repo's clock conventions.
- [x] (2026-08-13 18:15Z) Verified the three corrections that shape the design: the withdrawal phase vocabulary is absent from this repo, the 7-day queue is started by `cWithdraw` (history kind `:withdraw`, no phase), and the off-route delegator-summary fetch resolves a different address than the `/staking` route does. PARTLY SUPERSEDED 2026-08-14: the second is true of the *submit* path but false as a *history* claim — `cWithdraw` never appears in `delegatorHistory`. See Surprises.
- [x] (2026-08-13 18:20Z) Created this active ExecPlan. No source or test file has been changed by the planning phase.
- [x] (2026-08-14 10:00Z) Milestone 1 — `src/hyperopen/staking/unstaking.cljs` and `format-duration-approx`, with 16 deterministic cases in `test/hyperopen/staking/unstaking_test.cljs`.
- [x] (2026-08-14 10:20Z) Milestone 2 — unstaking block, popover split, two-step unstake copy, pre-submit lock notice, concrete transfer arrival, humanised history status.
- [x] (2026-08-14 10:35Z) Milestone 3 — delegator-summary address stamp plus the Portfolio "Staking Account" breakdown line.
- [x] (2026-08-14 10:55Z) Milestone 4 — Balances tab keeps an annotated HYPE row; tab badge kept consistent with what renders.
- [x] (2026-08-14 11:10Z) Final validation — `npm run gates` PASS 34/34 (6,563 tests / 35,601 assertions); staking + feature-story Playwright PASS 19/19; browser QA recorded below at 375/768/1280/1440.
- [x] (2026-08-14 12:05Z) Closed the remaining identity exposure. `staking-account-hype` stays a pure ungated sum with its existing contract tests intact; the new `verified-staking-account-hype` and `verified-staking-unstaking-hype` are what the Portfolio VM calls, and the card renders `--` rather than a fabricated `0 HYPE` when the summary cannot be verified. Headline and breakdown are now gated together.
- [x] (2026-08-14 12:25Z) Verified the provider's `delegatorHistory` vocabulary against live data and corrected the ETA anchor, which was wrong (see Surprises). Unit, view and Playwright fixtures now use the real wire shape.
- [ ] Owner sign-off on the two judgement calls recorded in the Decision Log: no ticking countdown, and a synthesized (rather than omitted) HYPE row in the Balances tab.

## Surprises & Discoveries

- Observation: The withdrawal "phase" vocabulary that an implementer would naturally assume — `initiatedWithdrawal` and `finalizedWithdrawal` — does not appear anywhere in this repository. The API layer keywordises whatever string the provider sends, with no vocabulary knowledge at all, and the only phase string evidenced anywhere is `"ready"` in a test fixture.
  Evidence: `src/hyperopen/api/endpoints/account/staking.cljs:203-207` is `{:kind :withdrawal :amount (…) :phase (keyword (str (or (:phase withdrawal) "unknown")))}`. A repo-wide grep for `initiatedWithdrawal` and `finalizedWithdrawal` across `src/`, `test/`, `tools/`, and `docs/` returns zero hits. `test/hyperopen/api/endpoints/account_staking_test.cljs:209` supplies `:phase "ready"` and `:222` asserts `:phase :ready`.
  Consequence: any ETA logic that keys off phase names is writing against an unverified external contract. The derivation in this plan therefore never matches on a phase name. RESOLVED 2026-08-14 by a live capture — the vocabulary is `initiated`/`finalized`, and the derivation now matches on normalised phase names with that evidence behind it. See the two entries below.

- Observation: The 7-day queue is started by a different wire action, and lands in a different history shape, than the phase-carrying `:withdrawal` rows. `submit-staking-withdraw` sends `{:type "cWithdraw" :wei …}`, and the history normaliser maps a `:cWithdraw` delta to `{:kind :withdraw :amount …}` with **no phase field at all**. The phase-carrying `{:kind :withdrawal …}` rows come from a separate `:withdrawal` delta key.
  Evidence: `src/hyperopen/staking/actions.cljs:612-615` builds the `cWithdraw` action; `src/hyperopen/api/endpoints/account/staking.cljs:198-201` normalises `:cWithdraw` to `{:kind :withdraw}`; `:203-207` normalises `:withdrawal` to the phase-carrying shape; `:167-181` and `:209-218` show only the first matching delta key wins, in the fixed order `[:delegate :cDeposit :cWithdraw :withdrawal]`.
  Consequence: the queue-start timestamp this plan anchors ETAs to is the `:withdraw` row's `:time-ms`, because that row *is* the user's own "transfer to spot" action. This is the one interpretation that follows directly from code in this repo rather than from assumed provider semantics.

- Observation: The delegator summary is fetched for one address off-route and a potentially different address on `/staking`, and both writes land in the same `[:staking :delegator-summary]` bucket with no record of which address they describe.
  Evidence: `src/hyperopen/account/surface_service.cljs:141` calls `fetch-staking-delegator-summary!` inside `bootstrap-visible-account-surfaces!` for every connected account regardless of route, resolving the *effective* account address (selected subaccount or spectated address). `src/hyperopen/account/context.cljs:267-272` shows `/staking` instead resolves `native-staking-account-address`, which is the *owner* address unless spectating. `src/hyperopen/api/projections/staking.cljs:56-65` stores the summary with no address stamp.
  Consequence: any off-route surface that displays staking numbers can silently describe a different account than the surface it sits on. The Portfolio "Staking Account" card already has this defect today. Milestone 3 adds the missing address stamp before Milestones 3 and 4 make the data more prominent.

- Observation: Three source files this work naturally wants to edit sit at or within one line of the 500-line namespace-size gate, and the staking actions namespace has literally zero headroom against its registered exception.
  Evidence: `src/hyperopen/staking/actions.cljs` is 700 lines against a `:max-lines 700` entry in `dev/namespace_size_exceptions.edn:18`. `src/hyperopen/views/staking/popovers.cljs` is exactly 500. `src/hyperopen/state/app_defaults.cljs` is 499. The gate is `dev/check_namespace_sizes.clj`, threshold 500, run by `npm run lint:namespace-sizes` inside `npm run check`.
  Consequence: new logic goes in new namespaces, and the popover content is split out of `popovers.cljs` rather than grown in place.

- Observation: A typography gate nobody named in the plan rejected the first version of the block. `test/hyperopen/views/typography_scale_test.cljs` forbids any `text-[10px|11px|13px|14px|15px]` utility anywhere under `src/hyperopen/views/**`; the app's `text-xs` and `text-sm` are both pinned to 12px/16px.
  Evidence: The first `npm test` after Milestone 2 reported `views-do-not-use-forbidden-sub-16px-explicit-text-utilities-test` failing with `{:file "src/hyperopen/views/staking/unstaking_panel.cljs", :matches ["text-[10px]" "text-[11px]" "text-[11px]" "text-[11px]"]}`. Replacing all four with `text-xs` made it green.

- Observation: Splitting a view file moves its hardcoded-colour debt to a new path, and the colour ratchet treats the new path as having a baseline of zero.
  Evidence: `npm run gates` failed only on `lint:theme-colors` with `src/hyperopen/views/staking/popover_content.cljs has 17 raw color literals (baseline 0)`. The fix was to move the accounting with the code in `dev/theme_color_baseline.edn` — `popovers.cljs` 20 became `popovers.cljs` 3 plus `popover_content.cljs` 17. Repo-wide total is unchanged at 1,426 literals; no new literal was introduced.

- Observation: Putting the unstaking annotation beside the coin ticker made the ticker itself disappear. The Balances coin column is `minmax(84px,0.78fr)` and its label uses `truncate`, so a `whitespace-nowrap` chip in the same flex row wins all the space and the label shrinks to nothing.
  Evidence: Browser verification at 1440 rendered the row's coin cell as `25.00 unstak…` with no `HYPE` visible at all. Moving the chip under the Available Balance value produced `HYPE | 0.00 HYPE | 0.00 HYPE + [25.00 unstaking] | $0.00`, which also places the annotation next to the zero it explains.

- Observation: Leaving the tab badge alone made the UI contradict itself. The badge counts non-zero spot balances, so a synthesized HYPE row rendered under a label reading `Balances (0)`.
  Evidence: Browser verification showed one visible row with the badge at `(0)`. `balance-tab-count` now adds the difference between the annotated and unannotated row counts, which is 1 only when the row was synthesized and 0 when an existing HYPE row was merely annotated. The badge then read `Balances (1)`.

- Observation: The reconciliation tolerance in the plan (`1e-8`) was one wei-unit of HYPE, which is the same order as the accumulated floating-point error when summing 8-decimal amounts. It was widened to `1e-6` before any test was written — still far tighter than any meaningful HYPE amount.
  Evidence: `reconcile-epsilon` in `src/hyperopen/staking/unstaking.cljs` carries this reasoning as a comment.

- Observation: The new `[:staking :delegator-summary-address]` key is deliberately not declared in `src/hyperopen/state/app_defaults.cljs`. That file is 499 lines against a 500-line gate with no registered exception, and the key lives inside the already-declared `:staking` bucket where a missing default reads as nil anyway.
  Evidence: `src/hyperopen/state/app_defaults.cljs:499`; `dev/check_namespace_sizes.clj` default threshold 500. It is cleared on account switch via `hyperopen.staking.account-scope/cleared-user-projections`.

- Observation: **The original ETA anchor was wrong, and only live data revealed it.** The plan anchored arrivals on history rows of kind `:withdraw` — the normalized `cWithdraw` transfer — reasoning that `cWithdraw` is the action this product submits to start the queue. `delegatorHistory` never returns that shape. Every queue event arrives as `{:kind :withdrawal :phase …}` instead. The consequence was severe and silent: `:timing` would have been `:unknown` for every real user, so the arrival date, the countdown and the progress bar would never once have rendered in production, while every unit and browser test passed against fixtures that shared the same wrong assumption.
  Evidence: live `delegatorHistory` for the first 25 validator accounts on 2026-08-14, 1,330 rows total. Delta keys observed: `withdrawal` 788, `delegate` 499, `cDeposit` 43, **`cWithdraw` 0**. Phase values observed: `initiated` 400, `finalized` 388 — neither the `initiatedWithdrawal`/`finalizedWithdrawal` forms an implementer would assume, nor the `"ready"` in this repo's test fixture at `test/hyperopen/api/endpoints/account_staking_test.cljs:209`.

- Observation: The correct queue model is an `initiated` row with no later matching `finalized` row, and it reconciles perfectly against the summary.
  Evidence: FIFO-pairing initiated against later finalized rows of equal amount, the unpaired remainder equalled `nPendingWithdrawals` exactly for every account sampled (ValiDAO 3=3, B-Harvest 1=1, Bitwise 0=0, Alphaticks 0=0, Anchorage 4=4, Luganodes 0=0). Every paired initiated→finalized gap measured 7.000 days at both minimum and maximum, which independently confirms `queue-duration-ms` = 604800000 rather than taking the popover's prose on trust.

- Observation: Changing `staking-account-hype` in place to apply the identity gate broke four pre-existing assertions that were testing its summation contract, not its identity behaviour.
  Evidence: `test/hyperopen/views/portfolio/vm/equity_helpers_test.cljs:21-28` calls it with bare states such as `{:staking {:total-hype "9"}}` and expects `9`. Splitting into an ungated `staking-account-hype` plus a `verified-*` accessor kept those contracts meaningful and made the safe accessor the obvious one to reach for.

## Decision Log

- Decision: Do not hardcode or match on any withdrawal "phase" string when deriving in-flight unstaking.
  Rationale: The vocabulary is not evidenced anywhere in this repository (see Surprises), so any match would be a guess about an external contract. The derivation instead treats `delegatorSummary`'s `totalPendingWithdrawal` as the authoritative in-flight amount — it is the number the product already trusts and already displays — and uses history only to *attribute timestamps* to that amount.
  Date/Author: 2026-08-13 / Claude

- Decision: Present unstaking through a two-tier confidence model. Tier one, always shown when the amount is positive, is the exact amount, the transfer count, the fact that the HYPE is locked, and the 7-day protocol duration. Tier two, the arrival date/time and approximate time remaining, is shown only when history reconciles against the authoritative amount and count.
  Rationale: Tier one depends only on data this repo already parses and already trusts, so the feature cannot be blocked or made wrong by an unverified provider contract. Tier two is genuinely useful but must never be fabricated; when reconciliation fails the UI says the arrival time is not available rather than inventing one. `docs/agent-guides/trading-ui-policy.md` requires communicating runtime truth and data freshness, which forbids presenting an inferred timestamp as a known one.
  Date/Author: 2026-08-13 / Claude

- Decision: SUPERSEDED 2026-08-14 — anchor the arrival estimate on history rows of kind `:withdraw` (the normalised `cWithdraw` transfer).
  Rationale at the time: `cWithdraw` is the action that starts the queue and the action this product submits, so its timestamp looked like the queue-entry time under this repo's own semantics. This was sound reasoning from the code and still wrong, because `delegatorHistory` does not report that shape at all. It is left here rather than deleted because the failure mode it created — a feature that silently never renders while every test passes — is the lesson.
  Date/Author: 2026-08-13 / Claude

- Decision: Anchor arrivals on unpaired `initiated` withdrawal rows, pairing FIFO on equal amount against later `finalized` rows, and keep reconciliation against the summary as the confidence gate.
  Rationale: This is what the live data shows the queue actually is, and the unpaired count matched `nPendingWithdrawals` exactly across every account sampled. Phase names are normalised (lower-cased, trailing `withdrawal` stripped) rather than compared verbatim, so a provider rename to `initiatedWithdrawal` still resolves and anything unrecognised is treated as non-terminal — which degrades to a hedged or absent ETA instead of a wrong one. The reasoning and the sample sizes are recorded in the `hyperopen.staking.unstaking` namespace docstring so the next contributor does not have to re-derive them.
  Date/Author: 2026-08-14 / Claude

- Decision: Verify the external contract with a live read before trusting any inference about it.
  Rationale: The plan explicitly flagged the phase vocabulary as unverified and routed around it, which was right, but routing around an unknown is not the same as knowing it. One read-only call to the public `info` endpoint invalidated the anchor, pinned the phase vocabulary, and independently confirmed the 7-day constant. Any future change to this attribution should repeat that capture rather than reason from the repo alone.
  Date/Author: 2026-08-14 / Claude

- Decision: Keep `staking-account-hype` ungated and add `verified-*` accessors alongside it, rather than gating the existing function in place.
  Rationale: The existing function has contract tests covering its summation fallbacks, which are about arithmetic and not identity; gating it in place broke four of them for the wrong reason. A `verified-` prefix also makes the safe accessor the obvious default at every call site, instead of leaving two similarly named functions where only one is safe to render.
  Date/Author: 2026-08-14 / Claude

- Decision: Do not add a ticking clock (a `setInterval` store watcher) for the countdown. Render the absolute arrival timestamp as the primary fact and an approximate remaining duration ("about 4d 6h left") as secondary, computed at render time through an injectable `now-ms` seam.
  Rationale: The countdown spans seven days and is displayed at day/hour granularity, so a value computed at render time cannot drift enough to mislead, whereas the absolute arrival timestamp is never stale at all. Adding a ticker would mean a new infrastructure namespace plus edits to `src/hyperopen/runtime/bootstrap.cljs` and `src/hyperopen/app/bootstrap.cljs` plus its own arm/disarm tests, for no user-visible benefit at this granularity. The word "about" on the relative figure keeps the claim truthful between renders. This was explicitly considered and rejected, not overlooked; the established precedent if it is ever needed is `src/hyperopen/portfolio/optimizer/infrastructure/progress_ticker.cljs`.
  Date/Author: 2026-08-13 / Claude

- Decision: Introduce no new `:actions/*` or `:effects/*` identifiers, and no new fetch.
  Rationale: Every new action or effect id in this repo forces a coordinated edit across the registration catalog, both argument-contract namespaces, the collaborator maps, the effect adapters, and — for anything entering the effect-order policy — the Lean model at `spec/lean/Hyperopen/Formal/EffectOrderContract.lean` plus a regenerated committed vector file. Load-time drift assertions throw if any link is missed. All the data this feature needs is already fetched, so paying that cost would buy nothing. The work is therefore derivation plus rendering.
  Date/Author: 2026-08-13 / Claude

- Decision: In the Balances tab, keep a HYPE row present whenever the account has staking-side HYPE, showing zero available with an explicit unstaking annotation, rather than letting the row vanish.
  Rationale: This is the reporter's literal request ("should just show HYPE 0 balance") upgraded to the maintainer's better version (locked/frozen rather than a bare zero). The row is display-only by construction: it carries no USD value, no P&L, and zero available balance, which means the existing `send-enabled?`/`transfer-enabled?` predicates leave its actions disabled and the existing `portfolio-usdc-value` sum is unaffected because `parse-num` maps a nil `:usdc-value` to 0. The tab's count badge is computed separately from spot balances and is deliberately left alone.
  Date/Author: 2026-08-13 / Claude

- Decision: Split the three popover content builders out of `src/hyperopen/views/staking/popovers.cljs` into a new `src/hyperopen/views/staking/popover_content.cljs`, leaving geometry and chrome behind.
  Rationale: `popovers.cljs` is exactly 500 lines against a 500-line gate, so any added copy trips `lint:namespace-sizes`. Splitting on the existing seam between "where the panel goes" and "what is inside the panel" is the change that gives both files headroom without inventing an artificial boundary.
  Date/Author: 2026-08-13 / Claude

- Decision: Move the Balances annotation from the coin cell to the Available Balance cell, and keep the tab badge consistent with the rendered rows.
  Rationale: Both were found by looking at the running app rather than by reasoning. The coin column is too narrow to host a chip without evicting the ticker, which inverted the whole point of the change; and a badge reading zero above a visible row is exactly the kind of internal contradiction `docs/agent-guides/trading-ui-policy.md` forbids. The badge change is scoped to add one only when the row was synthesized, so an annotated real HYPE row still counts once.
  Date/Author: 2026-08-14 / Claude

- Decision: Move the hardcoded-colour baseline with the split file rather than migrating 17 literals to tokens in the same change.
  Rationale: The literals are pre-existing styling for the validator dropdown and inputs, unrelated to unstaking. Converting them would mean unreviewed visual change across three popovers inside a UX plan, and the ratchet's purpose — never adding new hardcoded colour — is fully preserved: the repo-wide total is identical and every colour this plan introduced uses `ho-warn`.
  Date/Author: 2026-08-14 / Claude

## Outcomes & Retrospective

All four milestones are implemented and validated. A user with HYPE in the 7-day queue now sees, on `/staking`, a bordered and separated block stating the amount, a "Locked" pill, the sentence that it is not tradable or transferable, a progress bar with a text label, the arrival timestamp, an approximate remaining span, and the number of queued transfers — and sees "None" in the same always-present slot when nothing is in flight. The Unstake popover now says, before the user commits, that unstaking only returns HYPE to the Staking Balance and that reaching the Spot Balance is a separate seven-day transfer; a live delegation lock is warned about up front instead of only after the submit is rejected. The Transfer popover projects the concrete arrival of a transfer started now. The action history no longer prints raw provider tokens. The Portfolio card breaks the queued amount out of "Staking Account". The Balances tab keeps an annotated HYPE row instead of dropping it.

Evidence: `npm run gates` PASS 34/34 in 1m55s, 6,563 tests / 35,601 assertions. `npx playwright test tools/playwright/test/staking-regressions.spec.mjs tools/playwright/test/feature-user-story-remaining.spec.mjs` PASS 19/19, which includes all six pre-existing staking regressions from the prior plan unmodified. Three intended RED failures were observed and fixed on the way: two history-label assertions and the typography gate.

Browser QA on `/staking` and the trade-route Balances tab, at 375, 768, 1280 and 1440. Visual: PASS — the block is clearly separated from the spendable rows at every width and the amber tone reads as caution without alarm. Native-control: PASS — the progress bar carries `role="progressbar"` with `aria-valuemin`/`aria-valuemax`/`aria-valuenow`, and every state is also stated in text, so no meaning is carried by colour alone. Styling-consistency: PASS — the block uses the `ho-warn` token, which resolves correctly in all three themes (dark `#fbbd23`, institutional `#fbbf24`, hyperdegen `#ffd23f`); all typography is on the pinned 12px scale. Interaction: PASS — the Unstake and Transfer popovers open, show their new content, and the existing Escape-close and submit paths are unchanged. Layout-regression: PASS — `document.scrollWidth <= innerWidth` at 375 and 1440 with the block rendered; the two popover height estimates in `action-popover-layout-style` were raised from 440/400 to 620/480 after measuring the fuller panels at 590px and 414px, so neither can be clamped past the bottom of a short viewport. Jank/perf: PASS — the block has no animation or timer; it renders from state like every other panel.

Residual notes. First, the arrival countdown is computed at render time and is phrased "about Nd Nh left"; the absolute arrival timestamp beside it is never stale. Second, the withdrawal `:phase` vocabulary remains unverified against live data (see Surprises), which is precisely why nothing in this change depends on it. Third, off `/staking` only the queued amount is available, never an arrival time, because `delegatorHistory` is route-gated; both off-route surfaces degrade to amount-only by design.

Complexity: net reduction. Four view layers previously each held their own partial, inconsistent idea of pending withdrawals — one bare number on `/staking`, a silent sum on the Portfolio card, a dropped row in the Balances tab, and a raw provider token in the history table. They now share one pure derivation with an explicit confidence tier, and the two files that grew past their size gates were split along a boundary that already existed. No new action, effect, wire contract, fetch, timer, or app-db bucket was added.

## Context and Orientation

A reader new to this repository needs the following vocabulary before the plan makes sense.

Hyperliquid native staking moves HYPE through three places. The **spot balance** is ordinary tradable HYPE in the user's wallet on the exchange. The **staking balance** is HYPE that has been moved out of the spot balance and into the staking system but is not yet delegated to anyone; the API calls this `undelegated`. **Delegated** HYPE is staking-balance HYPE that has been assigned to a specific validator and is earning rewards.

There are two separate time locks, and conflating them is the central source of user confusion.

The first lock is the **delegation lock**, roughly one day. After delegating to a validator, that delegation cannot be undelegated until the lock expires. The API supplies the exact expiry per delegation and this repository normalises it at `src/hyperopen/api/endpoints/account/staking.cljs:129-139` into `:locked-until-timestamp`, an epoch-milliseconds number on each row of `[:staking :delegations]`. Today its only consumer in the entire codebase is the submit guard at `src/hyperopen/staking/actions.cljs:688-691`, which refuses the submission and produces the string `This delegation is locked until <M/D/YYYY - HH:MM:SS>.` after the user has already pressed the button. No view reads the timestamp.

The second lock is the **unstaking queue**, seven days. Moving HYPE from the staking balance back to the spot balance is a queued operation; the HYPE leaves the staking balance immediately and appears in the spot balance seven days later. During that window it is in neither place from the user's point of view. The API reports the aggregate at `src/hyperopen/api/endpoints/account/staking.cljs:116-127` as `:total-pending-withdrawal` (the amount) and `:pending-withdrawals` (the count of separate in-flight transfers, from the provider's `nPendingWithdrawals`). The count is parsed and then dropped by the view model.

Crucially, the user-facing word "unstake" and the product's Unstake button only perform the *first* step: `submit-staking-undelegate` sends `{:type "tokenDelegate" … :isUndelegate true}`, which moves HYPE from a validator back to the staking balance. Getting HYPE back to the spot balance requires a *second*, separate action — the Transfer popover's staking-to-spot direction, which sends `{:type "cWithdraw" :wei …}` at `src/hyperopen/staking/actions.cljs:612-615` — and that is what starts the seven-day queue. The product never tells the user this. A user who presses Unstake and then goes looking for their HYPE in the Balances tab will not find it, and nothing explains why.

The relevant application state lives under two top-level keys. `[:staking …]` holds `:account-address`, `:validator-summaries`, `:delegator-summary`, `:delegations`, `:rewards`, `:history`, `:spot-state`, and per-resource `:loading`, `:errors`, `:loaded-at-ms`, and `:loaded-for` maps; defaults are declared in `src/hyperopen/state/app_defaults.cljs:345-376`. `[:staking-ui …]` holds transient form and popover state, declared at `:319-343`.

Fetching is route-gated. `src/hyperopen/staking/effects.cljs:9-21` makes every staking fetch a no-op unless the current route is exactly `/staking` or the caller passes `{:skip-route-gate? true}`. `load-staking` at `src/hyperopen/staking/actions.cljs:399-413` issues all five user-scoped fetches — summary, delegations, rewards, history, and a staking-private copy of the spot clearinghouse — in one batch on route entry, so on `/staking` the history data this plan needs is already present at no extra request cost. There is exactly one exception to the route gate: the delegator summary alone is also fetched on every account bootstrap regardless of route, at `src/hyperopen/account/surface_service.cljs:141`. That is why the amount can be shown app-wide but the arrival estimate cannot.

The view layer is Replicant, which renders hiccup-style vectors. Rendering is driven only by application-state mutation: a store watcher batches changes into a `requestAnimationFrame` and re-renders. Nothing re-renders on a wall clock. The `/staking` route view receives the entire application state, so a view model there can read anything; the `/trade` route instead narrows state through `select-keys` allowlists in `src/hyperopen/views/trade_view.cljs:15-63`, which is why Milestone 4 must widen one of those allowlists or its new data will silently never arrive.

Four repository gates constrain the edits. `lint:namespace-sizes` fails any `.cljs` namespace over 500 lines without a dated exception in `dev/namespace_size_exceptions.edn`. `lint:namespace-boundaries` forbids non-view namespaces from requiring `hyperopen.views.*`, absolutely so for any path containing `/domain/`. `lint:hiccup` fails any `:class` string literal containing whitespace, so multi-token class lists must be collections of single-token strings. `lint:theme-colors` is a per-file ratchet on hardcoded colour literals with the staking files pinned at their current counts, so new styling must use the `ho-*` design tokens rather than raw hex.

## Plan of Work

### Milestone 1 — A pure model of in-flight unstaking

At the end of this milestone a single namespace can answer, from application state and an explicit clock, exactly what is immobilised and when it arrives, and nothing in the UI has changed yet.

Create `src/hyperopen/staking/unstaking.cljs`, namespace `hyperopen.staking.unstaking`. It must not require anything under `hyperopen.views.*`. It holds the seven-day queue duration and the one-day delegation lockup as named constants — these exist today only as English prose in two popover strings, which is why the same number cannot currently be used in a computation — and it exposes two pure derivations.

The first derivation answers "what is in the queue". Give it a two-arity function `pending-unstake` taking the whole application state, with the one-arity defaulting the clock to the live time and the two-arity taking an explicit `now-ms` so tests stay deterministic. This mirrors the seam the existing undelegate guard already uses at `src/hyperopen/staking/actions.cljs:655`. It reads `[:staking :delegator-summary]` for the authoritative amount `:total-pending-withdrawal` and count `:pending-withdrawals`, and `[:staking :history]` for candidate timestamps.

Its returned shape is a map with `:amount` (the authoritative in-flight HYPE, zero when absent), `:count` (the authoritative number of in-flight transfers, or nil when the provider did not supply it), `:in-flight?` (true when the amount is positive), `:timing` (one of `:known`, `:estimated`, or `:unknown`), `:entries` (a vector, newest first, of `{:amount :started-at-ms :arrives-at-ms :remaining-ms :ready?}`), `:next-arrival-ms` (the soonest arrival, meaning the oldest queue entry plus seven days), and `:last-arrival-ms`.

The candidate set is history rows whose delta `:kind` is `:withdraw` and whose `:time-ms` is within the last seven days of `now-ms`, sorted newest first. Rows older than that have necessarily already landed. `:timing` is `:known` when the candidate count equals the authoritative count and the candidate amounts sum to the authoritative amount within a tolerance of `1e-8`; in that case `:entries` is fully populated. It is `:estimated` when there is at least one candidate but reconciliation fails, in which case only `:next-arrival-ms` is offered and the UI must qualify it. It is `:unknown` when there are no candidates at all, in which case no date is offered anywhere. Each entry's `:arrives-at-ms` is its `:started-at-ms` plus the seven-day constant, `:remaining-ms` is that minus `now-ms` floored at zero to survive client clock skew, and `:ready?` is true once the arrival time has passed while the summary still reports the amount as pending.

The second derivation answers "which delegations cannot be unstaked yet". A function `locked-delegation` taking state, a validator address, and `now-ms` returns the matching delegation row together with its `:locked-until-timestamp` and remaining milliseconds when the lock is in the future, and nil otherwise. Reuse the existing predicate `hyperopen.staking.account-scope/delegation-locked-after?` rather than restating the comparison, so the pre-submit warning and the submit guard cannot disagree about the boundary. That predicate is strict: a lock exactly equal to the evaluation time is *not* locked, and the existing regression test at `test/hyperopen/staking/unstake_actions_regression_test.cljs:132` pins that boundary.

Add one formatter to `src/hyperopen/utils/formatting.cljs`, which the trading UI policy designates as the place formatting is centralised: `format-duration-approx`, taking milliseconds and returning a short human span — `"6d 4h"`, `"4h 12m"`, `"12m"`, and `"less than a minute"` below sixty seconds. The repo currently has four separate ad-hoc duration formatters in unrelated namespaces; this one is the shared version for spans that can exceed a day.

Cover all of it in a new `test/hyperopen/staking/unstaking_test.cljs`. The cases that matter are: no summary at all; a positive amount with empty history (`:unknown`, no dates); a positive amount with candidates that reconcile exactly (`:known`, per-entry arrivals); candidates that reconcile in count but not amount, and vice versa (`:estimated`); a candidate older than seven days being excluded; an arrival already in the past reporting `:ready?` true with `:remaining-ms` zero; and the formatter's four bands including the exact boundaries. Then regenerate the committed test index with `npm run test:runner:generate`.

### Milestone 2 — The `/staking` page tells the whole story

At the end of this milestone a user on `/staking` with unstaking in progress can see the amount, that it is locked, how many transfers are queued, and when they arrive; and a user about to press Unstake is told what unstaking actually does before they press it.

First, give the balance panel's rows stable test anchors. `key-value-row` in `src/hyperopen/views/staking/shared.cljs:104-110` currently emits no `data-role`, which is why no test can target an individual row. Add an optional trailing argument carrying a `data-role`, keeping the existing two-argument call shape working, and give each of the four existing rows a stable role.

Second, replace the fourth row of the balance panel in `src/hyperopen/views/staking_view.cljs:88-103`. Today it is `key-value-row "Pending Transfers to Spot Balance"` rendering a bare number that looks exactly like the three spendable rows above it. Instead render a visually separated block, under `data-role "staking-unstaking-block"`, above a divider from the available rows. The block is an **always-present slot**: it renders in every state rather than being conditionally omitted, because conditional siblings among keyed children are a known crash and state-loss hazard in this renderer. When nothing is in flight it renders one quiet line, "Unstaking to Spot Balance — None". When something is in flight it renders the amount with the unit, a "Locked" pill using the `ho-warn` token so the state is carried by more than colour alone, the sentence "Not tradable or transferable until it arrives", the count of queued transfers when the provider supplied one, and — under `:known` — the arrival timestamp through the existing `format-local-date-time` plus "about <span> left" through the new formatter, or under `:estimated` the soonest arrival explicitly qualified as an estimate, or under `:unknown` the plain statement that transfers take up to seven days and the arrival time is unavailable. Include a progress bar only in the `:known` case, with `role "progressbar"` and the `aria-valuemin`/`aria-valuemax`/`aria-valuenow` attributes the repo already pairs with progress bars, and a text label beside it so the information is not colour-only.

Third, fix the actual root cause in the Unstake popover. Split the content builders out of `src/hyperopen/views/staking/popovers.cljs` into a new `src/hyperopen/views/staking/popover_content.cljs` first, for the size reasons recorded in the Decision Log, keeping the geometry, anchoring, chrome, and the exported `action-popover-layer` in the original file. Then add to the Unstake content: a balance readout showing what is staked with the selected validator and where the HYPE lands, and the two-step explanation — that unstaking returns HYPE to the Staking Balance straight away, and that moving it to the Spot Balance is a separate transfer that then takes seven days. Add a pre-submit lock notice, distinct from the existing error box, that appears as soon as a locked validator is selected and states when it unlocks and roughly how long that is; the trading UI policy requires preferring error prevention over post-submit error messaging, and today the lock is only ever reported after the user presses the button. Leave the existing post-submit error box, its `data-role "staking-unstake-error"`, and the exact existing lock error string untouched: that string is asserted in the Playwright suite and in the unit regression suite, and changing it would break the prior plan's coverage for no benefit.

Fourth, make the Transfer popover concrete. Replace the static sentence "Transfers from Staking Balance to Spot Balance are locked for 7 days." with a statement that also projects the arrival of a transfer started now, computed from the same seven-day constant, and show the existing queue with its arrival information rather than the bare pending number.

Fifth, humanise the Staking Action History table. `src/hyperopen/views/staking/vm.cljs:167-178` currently projects the provider's raw phase keyword into the Status column, so users read literal strings like `initiatedWithdrawal`. Map the withdrawal kind label from the bare word "Withdrawal" to "Transfer to Spot", and pass unknown phase values through a readable title-casing fallback rather than a fixed vocabulary, for the reason recorded in Surprises. The existing view test asserts the raw string `"pending"` appears in that column; under a title-casing fallback it becomes `"Pending"`, and that test must be updated to match.

Extend `test/hyperopen/views/staking_view_test.cljs` to cover the block in all three timing states and both the zero and positive amounts, and add cases to `tools/playwright/test/staking-regressions.spec.mjs` that seed a pending amount with reconciling history and assert the rendered block, using the file's existing `setAppState` seeding helper and its pinned UTC timezone.

### Milestone 3 — The Portfolio card stops hiding the queue

At the end of this milestone the Portfolio summary card's "Staking Account" figure is broken down rather than opaque, and the number it shows is verifiably about the account the card is describing.

The address defect recorded in Surprises must be closed first, because this milestone makes the affected number more prominent. In `src/hyperopen/api/projections/staking.cljs`, have `apply-staking-delegator-summary-success` also record the address the summary describes at `[:staking :delegator-summary-address]`, and clear it in the matching error and account-clearing paths — `src/hyperopen/staking/account_scope.cljs:4-24` is the list of per-user projections that must be cleared on an account switch, and the new key belongs in it. Both call sites already know the address they requested: the bootstrap path at `src/hyperopen/startup/collaborators.cljs:338-357` and the route path in `src/hyperopen/staking/effects.cljs`. This is an additive key written through the existing generic save path, so it needs no new action, effect, or contract entry.

Then, in `src/hyperopen/views/portfolio/vm/equity.cljs`, expose the unstaking portion alongside the existing total instead of only the sum, and in `src/hyperopen/views/portfolio/vm.cljs` publish it on the summary map. In `src/hyperopen/views/portfolio/summary_cards.cljs`, render an indented breakdown line under the existing "Staking Account" row — "In 7-day unstaking queue" with the amount — shown only when the amount is positive and the recorded summary address matches the address the card is describing. Leave `staking-value-usd` returning zero and leave total equity untouched: valuing HYPE in USD is a separate question and this plan must not quietly change a headline equity figure.

### Milestone 4 — HYPE stops vanishing from the Balances tab

At the end of this milestone a user whose HYPE is entirely in the unstaking queue sees a HYPE row in the Balances tab instead of no row at all.

The Balances rows are built in `src/hyperopen/views/account_info/projections/balances.cljs` from the spot clearinghouse only, and rows whose total, available, USD value, and P&L are all zero are filtered out, which is precisely why the row disappears. Thread the staking summary into `build-balance-rows` and its memoisation key in `src/hyperopen/views/account_info/derived_cache.cljs`, then, *after* the existing zero-row filter so no existing row's treatment changes, attach the unstaking amount to the HYPE row — assoc'ing it onto the existing row when one is present, and appending a synthetic zero row when one is not. The synthetic row carries a nil `:usdc-value` and nil P&L, which keeps `portfolio-usdc-value` unchanged because `parse-num` maps nil to zero, and a zero `:available-balance`, which leaves the existing send and transfer predicates disabled without special-casing them.

Render the annotation as a sub-line under the coin in both `tabs/balances/desktop.cljs` and `tabs/balances/mobile.cljs`, reading "N unstaking" with the locked framing, so the row explains itself rather than presenting an unexplained zero. Gate the whole thing on the address stamp added in Milestone 3 matching the account the table is describing.

Finally, the `/trade` route narrows application state through `select-keys` allowlists in `src/hyperopen/views/trade_view.cljs:15-63`, and neither `:staking` nor `:staking-ui` appears in any of them. Add `:staking` to `account-info-view-base-state-keys`. Without this the new data will silently never reach the trade view's Balances tab and the change will appear to work on the portfolio route only — a failure mode this repository has hit before.

## Concrete Steps

Run all commands from the repository root, `/Users/barry/projects/hyperopen/.claude/worktrees/margin-collateral-ui-redesign-889539`.

A fresh worktree has no `node_modules` and `shadow-cljs` is not on `PATH`, which makes every gate fail with an opaque error that is environmental rather than a code defect. Bootstrap first:

    npm run setup:worktree

Expect either `[setup:worktree] node_modules present (shadow-cljs resolvable).` or a message telling you to run `npm ci`.

After adding any test namespace, regenerate the committed test index and commit the result:

    npm run test:runner:generate

Run the ClojureScript suite:

    npm test

Run the focused browser regressions for this surface:

    npx playwright test tools/playwright/test/staking-regressions.spec.mjs

Run the full mandated gate matrix, which does not short-circuit on the first failure and prints a PASS/FAIL table:

    npm run gates

Run the governed design review and then clean up every browser session created:

    npm run qa:design-ui -- --targets staking-route --manage-local-app
    npm run browser:cleanup

## Validation and Acceptance

Acceptance is behavioural. Every item below must be observable.

A deterministic unit fixture with `:total-pending-withdrawal` 25, `:pending-withdrawals` 2, and two `:withdraw` history rows of 10 and 15 inside the last seven days yields `:timing :known`, `:in-flight?` true, two entries whose `:arrives-at-ms` equal their `:time-ms` plus exactly `604800000`, and a `:next-arrival-ms` equal to the older row's arrival. Changing the summary amount to 30 without changing history yields `:timing :estimated` with `:next-arrival-ms` still present and no per-entry arrivals presented by the UI. Emptying history yields `:timing :unknown` with the amount and count still reported. Moving a candidate row eight days into the past excludes it. `test/hyperopen/staking/unstaking_test.cljs` is the proof surface, and it fails before Milestone 1 and passes after.

On `/staking` with a positive pending amount, the element at `data-role="staking-unstaking-block"` reads the amount with its unit, shows a "Locked" pill, states that the HYPE is not tradable or transferable until it arrives, and — in the reconciling case — shows an absolute arrival timestamp and an "about …" remaining span. With a zero pending amount the same element is present and reads "None"; it is never absent from the tree. `test/hyperopen/views/staking_view_test.cljs` and `tools/playwright/test/staking-regressions.spec.mjs` are the proof surfaces.

Opening the Unstake popover states that unstaked HYPE returns to the Staking Balance and that reaching the Spot Balance is a separate transfer taking seven days. Selecting a validator whose delegation lock is still in the future shows the unlock time and approximate remaining span *before* the user presses Unstake. Pressing Unstake on that validator still produces exactly the existing message `This delegation is locked until <M/D/YYYY - HH:MM:SS>.` in the existing `data-role="staking-unstake-error"` box, still emits no submit effect, and the existing assertions in `test/hyperopen/staking/unstake_actions_regression_test.cljs` and the Playwright future-lock case still pass unmodified.

The Staking Action History Status column contains no raw camelCase provider token; a withdrawal row reads as a human phrase and an unrecognised phase renders title-cased rather than verbatim.

On the Portfolio route with a positive pending amount and a matching recorded summary address, the summary card shows an indented "In 7-day unstaking queue" line under "Staking Account". With a zero amount, or when the recorded address does not match, the line is absent. Total equity is numerically unchanged by this plan in every case.

In the Balances tab, an account with zero spot HYPE and a positive unstaking amount shows a HYPE row with zero available and an unstaking annotation, and its Send and Transfer controls are disabled. The tab's count badge, the Account Equity panel's spot and portfolio values, the order form's available-to-trade and maximum sell size, and the optimizer's seeded holdings are all numerically unchanged, because none of them reads the annotation.

`npm run gates` passes, reporting a PASS matrix with no FAIL rows. The staking Playwright file passes in full. The six browser-QA passes — visual, native-control, styling-consistency, interaction, layout-regression, and jank/perf — are each explicitly recorded as PASS, FAIL, or BLOCKED at 375, 768, 1280, and 1440, and `npm run browser:cleanup` reports no sessions remaining.

## Idempotence and Recovery

Every step is additive and repeatable. The derivation namespace is pure and side-effect free, so re-running its tests cannot drift. `npm run test:runner:generate` is deterministic and safe to re-run; its output is committed. No migration, destructive operation, or wire-contract change is involved, and no live wallet transaction is ever required — the Playwright coverage uses the repository's existing wallet and exchange simulators through the debug bridge, exactly as the prior staking plan established.

If the namespace-size gate trips, the fix is to split the namespace rather than to register a new exception; three of the files in scope are at or near the threshold by design of this plan. If `lint:theme-colors` trips, the cause is a raw hex literal added to a staking view, and the fix is to use the `ho-*` token instead. If a Playwright case becomes flaky, run the file with `--workers=1` before treating it as a real failure.

## Artifacts and Notes

New files:

    src/hyperopen/staking/unstaking.cljs        pure queue and lock derivation, no view requires
    src/hyperopen/views/staking/popover_content.cljs   popover bodies split out for size headroom
    test/hyperopen/staking/unstaking_test.cljs  deterministic coverage with an injected clock

Modified files, by milestone:

    M1  src/hyperopen/utils/formatting.cljs
    M2  src/hyperopen/views/staking/shared.cljs, staking_view.cljs, popovers.cljs, vm.cljs, history.cljs
    M3  src/hyperopen/api/projections/staking.cljs, staking/account_scope.cljs,
        views/portfolio/vm/equity.cljs, views/portfolio/vm.cljs, views/portfolio/summary_cards.cljs
    M4  src/hyperopen/views/account_info/projections/balances.cljs, derived_cache.cljs,
        tabs/balances/desktop.cljs, tabs/balances/mobile.cljs, views/trade_view.cljs

## Interfaces and Dependencies

In `src/hyperopen/staking/unstaking.cljs`, define:

    (def queue-duration-ms 604800000)        ; seven days
    (def delegation-lockup-ms 86400000)      ; one day

    (defn pending-unstake
      ([state])
      ([state now-ms]))
    ;; => {:amount number, :count number|nil, :in-flight? boolean,
    ;;     :timing :known|:estimated|:unknown,
    ;;     :entries [{:amount :started-at-ms :arrives-at-ms :remaining-ms :ready?}],
    ;;     :next-arrival-ms number|nil, :last-arrival-ms number|nil}

    (defn locked-delegation
      [state validator now-ms])
    ;; => {:validator :amount :locked-until-timestamp :remaining-ms} | nil

In `src/hyperopen/utils/formatting.cljs`, define `(defn format-duration-approx [ms])` returning a short human span for values that may exceed one day.

No new library, endpoint, startup fetch, public action identifier, effect identifier, wire schema, or performance optimisation is introduced. `hyperopen.staking.account-scope/delegation-locked-after?` remains the single boundary predicate for the delegation lock and is reused rather than restated.

Plan revision note, 2026-08-14 11:15Z / Claude: Recorded the four implemented milestones with their validation evidence, the six discoveries found while implementing (the typography gate, the colour-baseline accounting for a split file, the coin-column eviction, the contradictory tab badge, the widened reconciliation tolerance, and the deliberately undeclared address key), the two follow-on decisions those forced, the full browser-QA matrix, and the retrospective. The single remaining unchecked item is owner sign-off on the two judgement calls, not outstanding implementation work.

Plan revision note, 2026-08-14 12:30Z / Claude: Closed the two follow-ups. A live read of the public delegatorHistory endpoint (25 accounts, 1,330 rows) invalidated the ETA anchor this plan shipped with: cWithdraw never appears there, so the arrival date, countdown and progress bar would have been permanently absent in production while every test passed against fixtures carrying the same wrong assumption. The anchor is now unpaired `initiated` withdrawal rows, which matched nPendingWithdrawals exactly for every account sampled, and the same capture confirmed the 7-day constant at 7.000 days min and max. Separately, the Portfolio headline figure is now gated on the same identity check as its breakdown via new verified-* accessors, so the card can no longer show one verified and one unverified number. Superseded the original anchor decision in place rather than deleting it, because the silent-failure mode it produced is the durable lesson. Gates PASS 34/34 (6,567 tests / 35,618 assertions); staking Playwright PASS 10/10.

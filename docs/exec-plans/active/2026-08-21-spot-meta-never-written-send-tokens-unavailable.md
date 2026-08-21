# Make spot token ids resolve so Send Tokens stops showing "unavailable"

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds. This document must be maintained in accordance with `/hyperopen/docs/PLANS.md` and the detailed writing contract at `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

A Hyperopen user with a unified (portfolio-margin) master account opens Sub-Accounts, clicks Transfer on a sub-account, and opens the token dropdown in the "Send Tokens" dialog. They see USDC with a real balance, and every other token they hold — KHYPE, HYPE, HAR — greyed out with the word "unavailable" where the balance should be. The tokens are listed, so their balances clearly loaded, but they cannot be selected and cannot be sent.

After this change, that same dropdown shows a real balance next to every spot token the source side holds, and each one can be selected and sent. The user verifies it by opening Sub-Accounts with a master that holds spot HYPE, clicking Transfer, and opening the token dropdown: HYPE shows a number, not the word "unavailable", and clicking it selects it.

This plan fixes the cause of that word appearing. It is a follow-up to the 2026-08-20 change that first made non-USDC tokens appear in this dialog at all; that change was correct in the view layer but depended on a piece of application state that, it turns out, production never populates.

Terms used in this plan, defined once in plain language:

A *wire token id* is the string Hyperliquid expects when an exchange action names a spot token. It has the form `NAME:0x<hash>`, for example `USDC:0x6d1e7cde53ba9467b783cb7c530ce054`. A bare symbol such as `HYPE`, and a bare numeric index such as `150`, are both rejected by the exchange. Hyperopen must send the joined form or not send at all.

*spotMeta* is a Hyperliquid `/info` endpoint response. Hyperopen requests it with the JSON body `{"type": "spotMeta"}`. Its `:tokens` entries each carry `:name`, `:index`, and `:tokenId` as three separate fields. It is the only source that can join a balance row to a wire token id, because a `spotClearinghouseState` balance row carries only a numeric token index and a human coin name.

*App state* is the single Clojure map inside the application's store atom. Hyperopen stores the spotMeta payload verbatim at the path `[:spot :meta]`.

A *projection* is a pure function that takes app state and a payload and returns new app state. They live under `/hyperopen/src/hyperopen/api/projections/`. `apply-spot-meta-success` in `/hyperopen/src/hyperopen/api/projections/market.cljs` is the only function anywhere in `src/` that writes `[:spot :meta]`.

An *effect* is a named side-effecting operation the runtime can dispatch, written as a keyword such as `:effects/fetch-asset-selector-markets`. An *effect adapter* is the concrete function bound to that keyword. Adapters live in `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs` and are bound to effect ids in `/hyperopen/src/hyperopen/app/effects.cljs`.

A *demand path* is a place in the app that requests data only when something needs it, rather than at startup. Hyperopen deliberately defers the full, spot-inclusive market catalog (~320KB of `/info` JSON) out of the startup paint window and fetches it from demand paths instead.

## Context References

Public refs:

- Direct maintainer report on 2026-08-21 in this Codex session: a user transferring spot HYPE from master to sub-account now sees the token in the Send Tokens dropdown, but it reads "unavailable" and cannot be selected. A screenshot accompanied the report showing `USDC 0.004265` selectable and `KHYPE`, `HYPE`, `HAR` each reading `unavailable`.

Repo artifacts:

- Parent ExecPlan: `/hyperopen/docs/exec-plans/completed/2026-08-20-subaccount-spot-token-transfer.md`, shipped as commit `5c1558de4`. That plan introduced `/hyperopen/src/hyperopen/funding/domain/spot_tokens.cljs` and the `:unresolved?` / "unavailable" affordance this plan is removing the cause of. Its reasoning is incorporated by reference and the important parts are restated below so this plan stands alone.
- `/hyperopen/AGENTS.md` for the validation gates this plan must pass.

Local scratch refs (non-authoritative):

- None.

## Background: exactly why the word "unavailable" appears

The dialog builds its token list in `transfer-asset-row` at `/hyperopen/src/hyperopen/views/subaccounts_view.cljs`. For each balance row it calls `spot-tokens/resolve-with` to get a wire token id, and sets `:unresolved? (nil? token)` when that returns nothing. The dropdown in `/hyperopen/src/hyperopen/views/subaccounts_view/transfer_dropdowns.cljs` renders the literal string `"unavailable"` in place of the balance whenever `:unresolved?` is true, and disables the option. That behavior is deliberate and correct: the parent plan chose to show a disabled row rather than hide the token or sign a known-rejected identifier.

`resolve-with` in `/hyperopen/src/hyperopen/funding/domain/spot_tokens.cljs` tries five things in order: pass through an id that already contains a colon; look up the balance's numeric index in a map built from spotMeta; look up the token symbol by name; look up the coin name by name; and finally, only for USDC, fall back to the hard-coded mainnet constant `mainnet-usdc-token`.

The lookup maps come from `resolver`, which reads `(get-in state [:spot :meta :tokens])`. When `[:spot :meta]` is nil, both maps are empty, clauses two through four all miss, and only USDC survives — on the hard-coded constant alone.

That asymmetry is the proof of diagnosis. There is no branch anywhere that consults metadata for USDC and skips it for other tokens. A partially loaded or malformed spotMeta could not produce "USDC priced, all three siblings unavailable". Only a completely empty resolver can. The balances themselves render because they arrive on an entirely disjoint path: `load-owner-snapshot!` in `/hyperopen/src/hyperopen/subaccounts/effects.cljs` writes them to `[:account-context :subaccounts :owner-snapshot :spot-state]`, which never touches `[:spot :meta]`.

So the whole question is why `[:spot :meta]` is nil. There are two independent reasons, and both must be fixed. Fixing either alone leaves a reachable broken case.

### Cause one: the demand path fetches spotMeta and discards it

`fetch-asset-selector-markets!` in `/hyperopen/src/hyperopen/runtime/api_effects.cljs` writes spot metadata behind this guard:

        (when (and apply-spot-meta-success (seq spot-meta))
          (swap! store apply-spot-meta-success spot-meta))

It only writes if the caller supplied an `:apply-spot-meta-success` function in the dependency map. Two callers build that map.

The startup caller at `/hyperopen/src/hyperopen/startup/collaborators.cljs` supplies it correctly.

The effect adapter `fetch-asset-selector-markets-effect` in `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs` does not. It passes `:begin-asset-selector-load`, `:apply-asset-selector-success`, `:apply-asset-selector-error` and `:after-asset-selector-success!`, but never `:apply-spot-meta-success`. With the key absent, the guard is fail-closed on every single invocation.

That adapter is the live handler. `/hyperopen/src/hyperopen/app/effects.cljs` binds it under `:api {:fetch-asset-selector-markets ...}`, and `/hyperopen/src/hyperopen/schema/runtime_registration/trade.cljs` maps the effect id `:effects/fetch-asset-selector-markets` to that leaf. `/hyperopen/src/hyperopen/runtime/collaborators.cljs` supplies no competing entry.

Therefore every demand path in the app — trade route entry, portfolio route entry, asset-selector open, optimizer runs — pays for the full spotMeta fetch over the network and then throws the response away without writing it to app state. Running `git log -S "apply-spot-meta-success" -- src/hyperopen/runtime/effect_adapters.cljs` returns nothing: the key has never been in that file.

The startup caller, which is wired correctly, does not save the day. `start-critical-bootstrap!` in `/hyperopen/src/hyperopen/startup/runtime.cljs` calls it with `{:phase :bootstrap}`, and `/hyperopen/src/hyperopen/api/market_loader.cljs` resolves spot-meta to an empty map `{}` in the bootstrap phase, which the `(seq spot-meta)` half of the guard correctly discards. The same function chases `{:phase :full}` immediately afterwards only when `(and (some? (:active-asset state)) (nil? (:active-market state)))` — a cold landing on a spot or outcome trade pair that the perp-only bootstrap catalog cannot resolve. In an ordinary session that condition is false.

The practical consequence is larger than the reported bug: `[:spot :meta]` is nil for essentially the entire session, on every route. A user who visits the trade page first and then goes to Sub-Accounts still sees "unavailable".

### Cause two: the Sub-Accounts route never asks for the catalog at all

`account-info-markets-needed?` in `/hyperopen/src/hyperopen/startup/route_refresh.cljs` is the gate on both dispatch sites in `/hyperopen/src/hyperopen/app/startup.cljs` (the route-change bucket and the post-render idle bucket). It calls `account-info-route?`, which is trade-or-portfolio only. `/subAccounts` is neither.

Nothing on the route makes up for it. `load-subaccounts-route` in `/hyperopen/src/hyperopen/subaccounts/actions.cljs` emits only a save and `[:effects/api-load-subaccounts]`. Opening the dialog via `start-transfer-subaccount` in `/hyperopen/src/hyperopen/subaccounts/management.cljs`, and toggling the token dropdown, emit only saves. The asset-selector open demand path is unreachable because the selector is rendered exclusively by the trade view.

### A third, smaller dishonesty

When a token is unresolved the dropdown sets a tooltip reading `"Asset details unavailable - refresh balances"`. The Refresh button on the Sub-Accounts page dispatches `:actions/refresh-subaccounts`, which leads to `load-owner-snapshot!` re-fetching clearinghouse and spot clearinghouse state. It never touches `[:spot :meta]`. The remedy the UI advertises is a guaranteed no-op.

### Why the existing tests did not catch any of this

Every test layer supplies `[:spot :meta]` by construction, so none of them exercise the path that populates it.

`test/hyperopen/runtime/api_effects_test.cljs` has a test named `fetch-asset-selector-markets-persists-spot-meta-when-returned-test`. It injects its own `:apply-spot-meta-success` into the dependency map and asserts the generic function honours it. That function is not the defect; the adapter that fails to supply the key is. No test anywhere calls `effect_adapters/fetch-asset-selector-markets-effect`, so the missing key is structurally invisible.

`test/hyperopen/views/subaccounts_view_test.cljs` renders the real dialog with `[:spot :meta]` absent, which should have failed. It passes because its balance fixtures carry `:token "USDH:0xabc"` and `:token "MEOW:0xdef"` — prejoined wire ids in a field where Hyperliquid returns a bare numeric index. That string satisfies clause one of `resolve-with` and short-circuits the lookup before metadata is ever consulted. This is the same "fixture invents a shape and performs the join under test" defect the parent plan recorded and fixed for two other fixture families; this third one was missed.

## Milestone 5: hide zero-balance tokens behind an opt-in checkbox

Live data showed the reporting master carrying four tokens at exactly zero (`KHYPE`, `USDT0`, `UENA`, `USDH`) alongside two it can actually send. None of the zero rows can be submitted — `transfer-popover` disables Send whenever the selected balance is not positive — so they were pure noise in the list. After this milestone the dropdown offers only what the user can act on, and a checkbox reading "Show N zero-balance tokens" reveals the rest.

The filter lives in `visible-transfer-assets` in `/hyperopen/src/hyperopen/views/subaccounts_view/transfer_dropdowns.cljs`, with `hidden-zero-balance-count` alongside it for the label. It falls back to the unfiltered list when every balance is zero, because an empty dropdown would leave `selected-transfer-token` on its bare-symbol placeholder and a bare symbol is not a sendable wire token id. `transfer-popover` resolves its selection against the same filtered list, so the MAX label and the Send button can never describe a token the user cannot see.

State is `[:account-context :subaccounts :show-zero-balances?]`, reached through the existing `:actions/set-subaccount-form-field` action, so no new action or effect contract was needed. It must be registered in both `normalize-form-field` and `normalize-form-value` in `/hyperopen/src/hyperopen/subaccounts/management.cljs`: the default branch of `normalize-form-value` stringifies, and `"false"` is truthy in ClojureScript, which would pin the list permanently open.

Acceptance: opening Send Tokens on a master holding a zero-balance token shows a shorter list and a checkbox naming the count; ticking it reveals the hidden rows without closing an open dropdown; unticking hides them again; the checkbox is absent when nothing is hidden.

## Milestones

### Milestone 1: the demand path actually writes spot metadata

At the end of this milestone, any route that requests the full market catalog leaves `[:spot :meta]` populated, and a test proves it through the real adapter dependency map rather than a hand-injected one.

The change in `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs` is to add the missing projection to the dependency map. To make the map testable without performing real network I/O, extract its construction into a named public function `asset-selector-markets-effect-deps` that takes the store and options and returns the map, and have `fetch-asset-selector-markets-effect` call `api-effects/fetch-asset-selector-markets!` with the result. The extraction is what allows a test to assert on the real map; adding the key without it would leave the regression just as invisible as before.

Do not switch the adapter to `api/fetch-asset-selector-markets!` in `/hyperopen/src/hyperopen/api/default.cljs`. That path supplies no `:after-asset-selector-success!` hook, and the adapter relies on that hook to run `sync-asset-selector-active-ctx-subscriptions` and `sync-active-outcome-market-side-streams!`. Losing them would silently stop websocket subscription syncing.

Acceptance: a new test in `test/hyperopen/runtime/effect_adapters_test.cljs` builds the real dependency map by calling `effect-adapters/asset-selector-markets-effect-deps`, replaces only `:request-asset-selector-markets-fn` with a stub that resolves a fixed spotMeta payload, passes the map to `api-effects/fetch-asset-selector-markets!`, and asserts `[:spot :meta]` in the store equals the payload. Reverting the one-key addition must turn this test red.

### Milestone 2: the Sub-Accounts route demands the catalog, and can retry

At the end of this milestone, landing directly on `/subAccounts` requests the full catalog, and a load that fails can be retried instead of latching off permanently.

Rename `account-info-route?` to `spot-catalog-route?` and add the sub-accounts route to it, because the predicate's meaning genuinely changes: it is no longer "routes that render the account-info panel" but "routes that render spot instruments whose names or wire token ids only resolve from the full catalog". The sub-accounts route qualifies for the second reason, not the first. `subaccounts-actions/subaccounts-route?` is already required by that namespace, so no new dependency is introduced. Rename `account-info-markets-needed?` to `spot-catalog-markets-needed?` and update both call sites in `/hyperopen/src/hyperopen/app/startup.cljs`.

Change what the predicate gates on. Today it is `(not= :full (get-in state [:asset-selector :phase]))`. `begin-asset-selector-load` sets the phase to the requested phase *before* the request is issued, and `apply-asset-selector-error` does not roll it back. So any rejection inside the loader's `Promise.all` — including an `/info` rate-limit response on spotMeta itself — leaves the phase reading `:full` forever, and every later demand path becomes a permanent no-op for the rest of the page's life. Gate instead on the data actually needed, plus an in-flight guard:

        (and (spot-catalog-route? route)
             (nil? (get-in state [:spot :meta]))
             (not (get-in state [:asset-selector :loading?])))

`:loading?` is set in the same synchronous `swap!` as the phase, so it dedupes the route-change bucket against the post-render idle bucket exactly as the phase check did, while allowing a retry on the next route change after a failure.

Also make the tooltip's advertised remedy real: `refresh-subaccounts` in `/hyperopen/src/hyperopen/subaccounts/actions.cljs` should additionally dispatch `[:effects/fetch-asset-selector-markets {:phase :full}]` when `[:spot :meta]` is nil, so pressing Refresh recovers a session whose catalog load failed.

Acceptance: `spot-catalog-markets-needed?` returns true for `/subAccounts` when `[:spot :meta]` is nil, false once it is populated, false while a load is in flight, and true again after a failed load leaves the phase at `:full` with metadata still nil. `refresh-subaccounts` includes the catalog effect when metadata is missing and omits it when present.

### Milestone 3: retire the fixture that hid the bug

At the end of this milestone, the view test exercises the real join instead of bypassing it.

Change the balance fixtures in `test/hyperopen/views/subaccounts_view_test.cljs` to the production shape — `:token` as a bare numeric index — and seed `[:spot :meta :tokens]` with matching production-shaped entries carrying `:name`, `:index` and `:tokenId`. The existing assertions that expect `"MEOW:0xdef"` stay exactly as they are; they now pass because the join genuinely happened rather than because the fixture pre-supplied the answer.

Acceptance: the existing assertions still pass, and deleting the seeded `[:spot :meta]` from the fixture turns them red.

## Progress

- [x] (2026-08-21) Diagnosed the defect end to end and confirmed each claim by direct file reading: the missing dependency key, the route gate, the phase latch, the fixture short-circuit, and the empty `git log -S` result proving the key never existed.
- [x] (2026-08-21) Milestone 1: extracted `asset-selector-markets-effect-deps`, added `:apply-spot-meta-success`, added two adapter regression tests. Falsified both: removing the one key turns both red.
- [x] (2026-08-21) Milestone 2: renamed and re-gated the route predicate, updated both call sites, added the Refresh recovery dispatch, updated and extended `route_refresh_test` and `actions_test`.
- [x] (2026-08-21) Milestone 3: converted the view-test balance fixtures to production shape and seeded real spotMeta. Falsified: nil-ing the seeded metadata turns four assertions red, two of which are pre-existing assertions that used to pass with metadata absent.
- [x] (2026-08-21) Milestone 4 (added mid-flight): excluded outcome-position rows from the transfer token list, with a regression test built from the reporting user's live balances.
- [x] (2026-08-21) Ran `npm run gates`: 34/34 PASS, 6,653 tests, 36,008 assertions.
- [x] (2026-08-21) Ran the sub-accounts Playwright spec: 6 passed, 1 failed. The failure is a pre-existing layout-geometry assertion unrelated to this change (see `Surprises & Discoveries`).
- [x] (2026-08-21) Milestone 5 (added on maintainer request): zero-balance tokens are hidden behind an opt-in checkbox in the Send Tokens dialog. Five unit tests plus one committed Playwright regression; verified visually in both states.
- [x] (2026-08-21) Re-ran `npm run gates` (34/34) and the sub-accounts Playwright spec (8/8) after the CSS build was corrected.
- [ ] Confirm one real non-USDC transfer against the live exchange — the maintainer's to perform, since it moves real funds.

## Surprises & Discoveries

- Observation: confirmed against live mainnet data for the reporting user. The master (`0x49208e12...`) holds `USDC 0.00426478`, which matches the `0.004265` in the screenshot exactly, plus `KHYPE` (token 121), `HYPE` (token 150) and `HAR` (token 205) — the three rows that read "unavailable". All three resolve cleanly through spotMeta, so the fix addresses the reported symptom directly.
  Evidence: `spotMeta` carries 492 tokens; index 150 joins to `HYPE:0x0d01dc56dcaaca66ad901c959b4011ec`, 121 to `KHYPE:0xbc8a22f25703a03101630ce6b09f4baa`, 205 to `HAR:0x9325025f805731935c1df7f97e654cda`. Every balance row on both accounts carries `:token` as a bare integer, confirming the production shape the view fixtures were missing.

- Observation: the destination sub-account, not the master, is the portfolio-margin account here. `0x2bec7339...` returns `"portfolioMarginEnabled": true`; the master does not. The diagnosis is unaffected — the failure is in metadata resolution, which is account-mode independent — but it is worth recording, since the parent plan's framing assumed a unified *master*.
  Evidence: `spotClearinghouseState` for `0x2bec7339...` includes `"portfolioMarginEnabled":true`; the same call for `0x49208e12...` has no such field.

- Observation: live data exposed a second class of unresolvable row that no fixture had. `spotClearinghouseState` returns outcome (prediction-market) positions in the same `:balances` array as real spot tokens — `o458`, `o459`, `o468`, `o469`, `o474`, `o475` on the reporting master. They carry no `:token` field at all and have no `spotMeta` entry, so they would still have rendered "unavailable" after the metadata fix, with a tooltip promising a refresh that could never help.
  Evidence: `{"coin":"o458","total":"0.0","hold":"0.0","entryNtl":"0.0"}` — no `token` key, and no `spotMeta` entry matches the name.

- Observation: the reported bug is a strict subset of a much larger one. `[:spot :meta]` is nil on *every* route in an ordinary session, not just `/subAccounts`, because the only live demand-path adapter drops the payload.
  Evidence: `git log -S "apply-spot-meta-success" -- src/hyperopen/runtime/effect_adapters.cljs` returns no commits, while `/hyperopen/src/hyperopen/startup/collaborators.cljs` has carried the key all along. The two dependency maps were written separately and drifted from the start.

- Observation: the existing spot-meta test tests the wrong half of the seam. `fetch-asset-selector-markets-persists-spot-meta-when-returned-test` injects the projection it is meant to be verifying is present.
  Evidence: `test/hyperopen/runtime/api_effects_test.cljs` passes `:apply-spot-meta-success (fn [state spot-meta] (assoc-in state [:spot :meta] spot-meta))` in its own dependency map.

- Observation: the phase-based idempotency guard silently converts any catalog fetch failure into a permanent one, because the phase is advanced before the request and never rolled back on error.
  Evidence: `begin-asset-selector-load` sets `[:asset-selector :phase]`; `apply-asset-selector-error` in `/hyperopen/src/hyperopen/api/projections/asset_selector.cljs` sets `:loading?`, `:error` and `:error-category` but not the phase.

## Surprises & Discoveries (continued)

- Observation (superseded): `subaccounts transfer opens a compact send tokens popover @regression` initially failed with `popoverWidth` 359 against a 347 limit, and reproduced on the stashed baseline, which made it look like a pre-existing UI defect. It was neither pre-existing nor a defect: the run served the build from a plain static server on port 8090 (port 8080 was held by a different worktree's dev server) and so never ran `npm run css:build`. The page was rendering completely unstyled. After building the CSS the spec passes 8/8. The lesson is that a stash-verify proves "not caused by my diff" but says nothing about whether the harness itself is sound — an environmental fault reproduces on both sides of the stash just as happily as a real bug does.
  Evidence: full-page screenshot of the unstyled render, then `npm run css:themes:generate && npm run css:build` followed by a clean 8/8 run.

## Decision Log

- Decision: place the checkbox in the dialog body, under the account/token row, rather than inside the token dropdown.
  Why: this was arrived at empirically after both in-dropdown positions failed in the browser. Appended below the list it was clipped by the popover, which sets `overflow-y-auto` under a viewport-relative max height while the token field sits low inside it — the control rendered half-cut and was reachable only by scrolling the dialog. Prepended above the list it pushed the options down far enough that at 768px width the fixed mobile footer intercepted clicks on them, which turned a pre-existing Playwright regression red. In the body it can be clipped by neither, and it has the side benefit of naming the hidden rows before the user opens the dropdown and wonders where they went. Worth recording that the two failures were only visible in a real browser; every unit test passed in all three layouts.

- Decision: default to hiding zero balances, and keep the preference across dialog opens within a page visit.
  Why: hiding is the point of the request, and a zero balance is never actionable in this dialog. The preference is initialised in `load-route-path-values` so a fresh route entry starts with the short list, but deliberately not reset by `start-transfer-subaccount` or `cancel-transfer-subaccount`, so a user who wants to see dust does not have to re-tick it for every sub-account. It is a view preference, not transfer data.

- Decision: exclude balance rows that carry no token identifier at all, rather than listing them disabled.
  Why: the "unavailable" affordance exists to describe a *transient* failure — metadata has not loaded, try refreshing. An outcome position is not a spot token, has no wire token id, and can never travel the spot `sendAsset` path, so that affordance would be a standing lie. A row that has an identifier but cannot be resolved right now keeps the disabled treatment, because for that row the affordance is true. The two cases are distinguished by the presence of `:token`, which every real spot balance row carries and no outcome row does.

- Decision: reuse the existing `:effects/fetch-asset-selector-markets {:phase :full}` effect for the sub-accounts demand path rather than introducing a dedicated lightweight spot-meta effect.
  Why: no spot-meta effect id exists today, and adding one means touching the full effect contract surface — registration, `effect_args` contracts, the effect-order contract, and the Lean formal sync gate. The catalog fetch is already the established demand-path pattern, is deduped and idempotent, and runs at most once per session. The extra payload is real but is the same cost the trade route already pays. Revisit only if the sub-accounts route proves latency-sensitive.

- Decision: gate the demand path on `(nil? [:spot :meta])` plus `:loading?` rather than on `[:asset-selector :phase]`.
  Why: it fixes the failure latch as a side effect of expressing the condition honestly. The predicate should ask "is the data I need missing?", not "did something once claim to have started fetching it?". Rolling the phase back inside `apply-asset-selector-error` was considered and rejected: it cannot distinguish a cache-hydrated catalog from a fully loaded one, so it risks either failing to unlatch or clobbering a good `:full` phase with a late bootstrap error.

- Decision: extract `asset-selector-markets-effect-deps` instead of just adding the missing key inline.
  Why: the bug is that two hand-written dependency maps drifted. Adding a key inline fixes this instance and leaves the next drift equally undetectable. A named function makes the real map assertable from a test without network I/O.

- Decision: rename `account-info-route?` to `spot-catalog-route?` rather than adding sub-accounts to a predicate whose name and docstring would then be wrong.
  Why: the sub-accounts route needs the catalog for wire token ids, not for the account-info panel. Keeping the old name would require a docstring that contradicts the function name, and the next reader would reasonably delete the sub-accounts clause as a mistake. Two call sites and one test namespace are affected.

- Decision: make Refresh dispatch the catalog fetch when metadata is missing, rather than rewording the tooltip.
  Why: the wording is fine and the affordance is the right one; it simply was not wired. Making it true is a smaller change than explaining why it is false, and it gives a user whose catalog load failed a way to recover without reloading the page.

## Open Questions

(Resolved 2026-08-21 by maintainer direction: hide zero-balance tokens by default, with an opt-in checkbox to show them. Implemented as Milestone 5.)


Two observations from the reporting user would upgrade this from "proven by reading the code" to "proven against the failing session". Neither blocks the fix.

First, the decisive behavioural test: load `/trade`, wait several seconds, then navigate to Sub-Accounts and open Send Tokens. Under this diagnosis the tokens still read "unavailable", because cause one discards the metadata regardless of route. If instead HYPE becomes selectable, cause one is wrong and only cause two is real.

Second, the network observation: on the Sub-Accounts page with the dialog open, filter DevTools' network tab for `info` and look for a request whose body contains `"type":"spotMeta"`. Absent confirms cause two; present with status 200 confirms cause one; present with status 429 or 5xx would add a third cause — `apply-spot-meta-error` leaves the key nil and nothing retries — which Milestone 2's retry-capable gate and Refresh dispatch already mitigate.

Note that the `HYPEROPEN_DEBUG` console API cannot be used for this. It is defined inside `(when ^boolean goog.DEBUG ...)` in `/hyperopen/src/hyperopen/telemetry/console_preload.cljs` and is therefore stripped from the `:release` build the user is running.

## Validation

Live confirmation performed on 2026-08-21 against Hyperliquid's public `/info` endpoint for the two addresses the maintainer supplied. This replaces the first two items under `Open Questions`, which are now answered: the balance rows carry numeric token indices as assumed, and every token in the screenshot resolves through spotMeta once `[:spot :meta]` is populated. The remaining question — whether the user arrived via `/trade` — is now moot, because the primary defect made the route irrelevant.

Run from the repository root. A fresh worktree has no `node_modules`, so run the bootstrap first; `npm test` and `npm run check` invoke it automatically but running it explicitly makes an environmental failure obvious rather than opaque.

        npm run setup:worktree
        npm run gates

`npm run gates` runs `npm run check`, `npm test` and `npm run test:websocket` without short-circuiting on the first failure, and prints a single PASS/FAIL matrix.

Then run the narrowest relevant browser coverage before broadening:

        npx playwright test browser-tests/test/subaccounts-regressions.spec.mjs

Expected results are recorded in `Outcomes & Retrospective` as they are obtained.

To see the fix by hand: start the dev server, connect a wallet whose master holds a non-USDC spot token, navigate to Sub-Accounts, click Transfer on a sub-account, and open the token dropdown. Every token should show a numeric balance. Before the fix, every token except USDC shows the word "unavailable".

## Outcomes & Retrospective

The reported symptom is fixed, and the fix is larger in reach than the report implied. `[:spot :meta]` was nil for whole sessions on every route, so everything in the app that resolves a spot token through metadata was silently degraded, not only the Send Tokens dialog. One missing key in one hand-written dependency map caused it, and it had been missing since that map was first written.

Confirmed against the reporting user's live accounts: all three tokens in the screenshot (`KHYPE` 121, `HYPE` 150, `HAR` 205) resolve cleanly through spotMeta once the key is populated, and `HYPE` joins to `HYPE:0x0d01dc56dcaaca66ad901c959b4011ec`.

On complexity: this reduced it slightly. Four production files changed, three of them by a handful of lines. The one structural addition — naming the dependency map — exists to make a whole class of defect testable rather than to add behavior, and it replaced an inline map of the same size. The route predicate got a more honest name and a simpler gating condition that also removed a latent failure latch. Against that, `transfer-asset-row` gained a real branch for outcome rows, which is genuine new logic that live data proved necessary.

The lesson worth carrying: three separate test layers covered this dialog and all three seeded `[:spot :meta]` by construction, so none of them could observe that nothing in production ever wrote it. Tests that supply the output of the code path under test cannot fail when that path is missing entirely. The parent plan had already caught this exact pattern twice and retired two fixture families for it; a third survived, and the defect it hid was worse than either of the first two.

Two items remain open and are the maintainer's to close: signing one real non-USDC sub-account transfer against the live exchange, and deciding the zero-balance question above.

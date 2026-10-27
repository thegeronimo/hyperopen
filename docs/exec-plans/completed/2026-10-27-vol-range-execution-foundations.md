# Vol Range stage 4: execution foundations (journal, nonce and account coordination, strict results, read barriers)

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds. It is maintained in accordance with `/hyperopen/docs/PLANS.md`.

## Purpose / Big Picture

HO-SPEC-014 v2.1 section 15.1, PR 4: the shared foundations Release A placement needs before any Vol Range order can be sent. Nothing in this plan enables a Vol Range send or any new UI. After it lands:

- Every agent-signed action in HyperOpen (manual orders, cancels, optimizer, vaults, staking, funding) reserves its nonce BEFORE signing through one per-signer coordinator shared across tabs, so overlapping producers can never sign with the same nonce (spec 2.2 item 3, 8.2; VR-10).
- A testnet signature can never be sent to the mainnet endpoint (spec 3.2).
- IndexedDB has a committed multi-record write that resolves only on transaction completion (spec 2.2 item 4, 7.4; VR-11), and a journal store for Vol Range intents and attempts.
- Order and cancel responses have a strict, pure interpretation where nothing unfamiliar counts as success (spec 2.2 item 2, 8.4; VR-12).
- A send path reports delivery (`:not-sent`, `:response`, `:unknown`) separately from the venue's answer, with a final pre-send guard and a committed send-attempt hook (spec 8.1 steps 7-8; VR-09, VR-13).
- Account admission has its own per-account critical section that returns `:busy` instead of waiting (spec 8.2).
- Order-status lookups and an awaitable account snapshot read return raw rows with provenance without touching the app store (spec 2.2 items 5-6).

The design and its trade-offs are recorded in `/hyperopen/docs/design-docs/vol-range-execution-coordination.md`.

## Context References

- Direct user request in this session (2026-10-27): implement the next Vol Range stage (spec section 15.1 PR 4) with a single implementer plus an independent fresh-context review.
- Parent ExecPlan: `/hyperopen/docs/exec-plans/active/2026-10-21-vol-range-release-a0-preview.md`.
- Product spec: `/hyperopen/docs/product-specs/vol-range-implementation-spec.md` (HO-SPEC-014 v2.1).

## Progress

- [x] (2026-10-27) Platform locks (Web Locks with an in-tab fallback that reports `:in-tab-only`).
- [x] (2026-10-27) Nonce coordinator; shared agent signing path reserves before signing; network guard.
- [x] (2026-10-27) Committed IndexedDB writes, strict reads, `vol-range-journal` store (DB version 8).
- [x] (2026-10-27) Strict order/cancel result normalization.
- [x] (2026-10-27) Delivery-tracking send path.
- [x] (2026-10-27) Account admission coordinator.
- [x] (2026-10-27) CLOIDs, entropy, journal records and committed journal persistence.
- [x] (2026-10-27) Order-status lookup and account snapshot read barrier.
- [x] (2026-10-27) Independent Opus review (1 blocker, 5 major) and fixes.
- [x] (2026-10-27) Second, race-focused Opus review (1 blocker, 3 major) and fixes.

## Surprises & Discoveries

- Existing signing tests passed `:is-mainnet false` only to check option pass-through; with the network guard they now use mainnet, and a dedicated test asserts testnet is refused before signing.
- `http/parse-json!` turns an unreadable body into `{:status "err"}`, indistinguishable from a venue rejection. The delivery path parses strictly so such bodies are `:unknown`.

## Decision Log

- Nonce cursor in `localStorage`, not IndexedDB, so a failed journal store cannot block identified cancellation (spec 7.4).
- The manual single nonce retry is kept unchanged; a retry now always reserves a fresh nonce through the coordinator. Vol Range operations pass `:max-nonce-retries 0` and use the delivery path, which never retries.
- Wallet-signed user actions keep their existing in-tab reservation-before-signing; they use the owner wallet, a different signer, behind a wallet prompt.
- Existing manual `submit-outcome` behavior is unchanged in this PR (spec 2.2: no unrelated refactors).
- The A0 boundary test now excludes the execution-foundation namespaces from its storage/nonce scan and separately asserts no preview namespace requires them, so VR-24 still holds.

## Outcomes & Retrospective

Two independent reviews found real defects that the first implementation missed; all are fixed with regression tests:

- Storage exceptions no longer block signing or cancellation; they mark the reservation `:degraded`.
- Cross-tab nonce uniqueness no longer assumes localStorage coherence: a short synchronous wait until the clock passes the nonce, and `:degraded` whenever the nonce leads the clock by more than 2 ms. No more one-second holds that could throttle manual trading.
- `put-many-committed!` can no longer partially commit while reporting failure, and uses strict durability.
- Delivery follows spec 8.1 order (attempt record, then final guard), requires a deadline and an attempt record, bounds both the pre-send and HTTP phases, and treats non-2xx or unreadable bodies as `:unknown`.
- Journal rows are EDN, round-trip losslessly, and corrupt or foreign-schema rows are reported as unreadable.
- A timed-out admission keeps its section until the body settles; in-tab-only admission is refused.

Remaining for PR 5 (lifecycle): reload recovery and reconciliation by original ids end to end, the cancel path through the journal, operation-level "never blindly retry" tests, and a real browser cross-tab test (Node has neither Web Locks nor localStorage, so cross-tab behaviour is covered by reasoning and unit tests only).

## Validation

`npm test`, `npm run gates`, and the lints listed in AGENTS.md.

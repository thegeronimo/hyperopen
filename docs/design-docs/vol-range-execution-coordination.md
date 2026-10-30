---
owner: trading
status: canonical
last_reviewed: 2026-10-28
review_cycle_days: 90
source_of_truth: true
---

# ADR: Signer nonce reservation, account admission, committed journal writes and strict order results

Context: HO-SPEC-014 v2.1 section 2.2 items 2-6, sections 7.4, 8.1-8.4, PR 4 of section 15.1. These are shared
foundations: every agent-signed action in HyperOpen uses them, not only Vol Range.

## Decisions

### 1. Nonce reservation is per network + signer, before signing, outside IndexedDB

- `hyperopen.api.trading.nonce-coordinator/reserve-nonce!` returns a Promise of a nonce that is strictly greater
  than every nonce previously reserved for the same `[network signer-address]` in any tab of this origin.
- Critical section: the Web Locks API (`navigator.locks.request`, exclusive, lock name
  `hyperopen:signer-nonce-lock:<network>:<signer>`). Inside it the new nonce
  `max(now, cursor + 1, session-cursor + 1)` is computed and the cursor is written to `localStorage` and memory.
  localStorage is NOT relied on for cross-process coherence (browsers propagate writes between renderer processes
  asynchronously). When the nonce is at most 2 ms ahead of the clock, the holder spins synchronously (immune to
  background-tab timer throttling, bounded at 10 ms of real time) until the clock passes it, so the next holder in
  any tab gets a larger nonce from `now` alone. When the nonce is further ahead (a cursor or retry floor ahead of the
  clock, or a clock stepped backwards) that argument fails: the lock is released at once and the reservation is
  `:degraded`. A cursor more than one day ahead (the venue's window) is ignored.
- Storage exceptions (quota, blocked site data) never block signing: the in-memory cursor is used and the result
  reports `:coordination :degraded`. Where Web Locks is missing, an in-tab promise queue serializes
  reservations and reports `:in-tab-only`. Manual trading proceeds in both cases; the Vol Range delivery path
  refuses anything but `:web-locks`.
- The signer is the agent address derived from the session's private key, never the owner address.
- Storage choice (docs/BROWSER_STORAGE.md): the cursor is one small number per signer, read synchronously,
  written once per signed action. It is NOT stored in IndexedDB so that a failed or blocked journal store can
  never block identified-order cancellation (spec 7.4: moving nonce reservation into the failed store and
  routing cancel through it does not satisfy the requirement).
- `sign-and-post-agent-action!` reserves through the coordinator for every producer (manual orders, cancels,
  optimizer, vaults, staking, funding). The existing post-response cursor persistence remains as a second,
  monotonic write. The existing single nonce retry for manual flows is kept unchanged; callers that pass
  `:max-nonce-retries 0` (all Vol Range operations) never retry, and a retry always reserves a NEW nonce.
- Wallet-signed user actions use the owner wallet as signer and go through the same coordinator keyed by the
  owner address.

### 2. Network identity agrees end to end

The exchange transport is mainnet-only. `sign-and-post-agent-action!` rejects before signing when
`:is-mainnet false` would be sent to the mainnet endpoint.

### 3. Account admission is a separate critical section per network + target account

`hyperopen.api.trading.action-coordinator/with-account-admission!` runs an async body under an exclusive Web
Lock named for the target account; a holder elsewhere returns `:busy` at once. A body that does not settle within
its time bound returns `:timeout` to the caller, but the section stays held until the body really settles, so no
other tab can allocate the same funds meanwhile; the caller treats `:timeout` as reconciliation required. With only
in-tab coordination the section returns `:unsafe-coordination` and does not run the body. Vol Range placement reads pending reservations, revalidates, and commits its
intent and reservations to the journal inside this section, so two placements cannot allocate the same free
USDC before either venue hold appears. Cancellation never takes this lock. Busy/unavailable coordination is
returned as a typed result, never as a silent wait without bound (a timeout yields `:busy`).

### 4. Committed IndexedDB writes

`hyperopen.platform.indexed-db/put-many-committed!` writes several records in ONE readwrite transaction with
`durability: "strict"` and resolves only on `transaction.oncomplete`; it rejects on `onabort`, `onerror`, quota errors, a blocked open,
or missing IndexedDB, and a synchronous put failure aborts the transaction. Request-level `onsuccess` is never
treated as durability. The `vol-range-journal` store arrives with DB version 8 (additive; a rollback would leave
users on version 8, as earlier bumps did). Records are stored as EDN strings so keywords, vectors and
string-keyed maps round-trip losslessly.

### 5. Journal records and CLOIDs

`hyperopen.vol-range.infrastructure.journal` writes versioned records (`:schema-version`, `:scope`,
`:record-id`, `:saved-at-ms`, monetary values as strings, no signing material). CLOIDs are the feature prefix
`0x484f5652` plus a 96-bit suffix from `crypto.getRandomValues`, generated only at the effect boundary; a pure
function validates and concatenates.

### 6. Delivery and per-leg outcomes are separate, strict and pure

`hyperopen.api.trading.order-result/normalize-order-response` maps a response to the spec 8.4 table:
recognized resting/filled/error entries per leg, whole-action rejection, and `:unknown` for empty vectors,
wrong length, unfamiliar entry shapes or unreadable bodies. `sign-and-post-with-delivery!` runs the steps in
spec 8.1 order: reserve nonce, sign, commit the send-attempt record, final admission guard and deadline check,
then HTTP. It reports `:delivery :not-sent` only when it can prove the failure preceded the HTTP call,
`:response` only for a readable JSON body on a 2xx status, and `:unknown` for any rejection, timeout, non-2xx
status or unreadable body after invocation. Every attempt requires a deadline and a committed send-attempt
record; the whole pre-send phase is time-bounded, and an abandoned pre-send phase can no longer send; the HTTP
phase has its own timeout that yields `:unknown`. Only `:web-locks` nonce coordination is accepted.
Existing manual `submit-outcome` behavior is unchanged in this PR.

### 7. Read barriers

`hyperopen.api.endpoints.orders/request-order-status!` and an awaitable
`hyperopen.vol-range.infrastructure.reads/read-account-snapshot!` return raw rows with provenance
(`:requested-at-ms`, `:received-at-ms`, `:account-address`, `:complete?`) without writing to the app store.

### 8. Grid lifecycle records (stage 5)

One `grid:<id>` journal record holds a logical grid: explicit membership (every CLOID ever sent for a
rung, the last one current), per-leg evidence, operations, ingested fills and released reservations.
Every write is load + merge + write under a per-grid lock (`hyperopen:vol-range-grid:<id>`), and
`lifecycle-recovery/merge-grids` is monotonic: membership, operations, fills and releases are unions,
operations only advance, and a leg keeps its most advanced evidence. A flow holding an older copy can
therefore never erase another flow's operation, repair or fill. Recovery after a reload treats any
operation with a committed send-attempt record as possibly sent, whatever the grid record says, and
decides an operation only after its action deadline plus the HTTP timeout, so a live tab's send is never
declared unsent.

Pending reservations are released only for resolved operations, and only after a balance read
REQUESTED after the newest evidence that changed the operation's legs. Fill evidence is stamped with the
fills window's end, and fills are read last in a reconciliation so a complete read covers every state
change seen before it. Cancelling records a request first; a placement or repair still before its final
pre-send guard is then refused, and cancel returns `:busy` while one is between the guard and its outcome.
Cancel never takes the admission section and never needs a journal commit.

### 9. Live evidence, monitoring and shared fill records

Context: spec sections 9.1-9.4, 7.4, 11.2; VR-14, VR-18, VR-19. Plan:
`/hyperopen/docs/exec-plans/completed/2026-10-28-vol-range-live-fills.md`.

- Raw taps, not the display path. `websocket.user-runtime.handlers` offers every raw `userFills` and
  `openOrders` message to an optional tap before the live-address filter, the empty-snapshot skip, the
  200-row display truncation, `tid`-only de-duplication and cancel-guard pruning. A throwing tap is
  logged and never changes the display path. Only a dev build that includes placement injects taps
  (`live-ingress/user-stream-taps`).
- Spec 9.1 envelopes and conservative routing. `live-evidence` wraps each message as an envelope
  (scope, source, channel, received/observed times, `:snapshot?`, `:complete? false`, address source,
  `"<epoch>:<seq>"` revision) and routes rows only to ladders with the same network, account and coin and
  a known oid or CLOID. Fills are never routed to a leg still `:pending`. Open-order frames are presence
  only: they can accept an `:unknown` leg or lower an accepted resting leg's remaining, never touch an
  `:unconfirmed`, pending or terminal leg, never raise a remaining size, and absence only hints a
  rate-limited REST reconciliation (default dex, coins this connection has shown, 2 s margin). REST reads
  keep their existing provenance maps.
- In memory only. Stream evidence is merged into the store copy of a ladder through the pure, monotonic
  `grid-store/merge-grid-into-state`, never written to the journal; every adapter write merges the same
  way, so an older reconcile result can no longer erase stream evidence. Durable state still comes from
  REST reconciliation, whose fills window re-reads everything since `:fills-through-ms` minus 60 s.
- One fill key. `lifecycle-evidence/fill-key` (network | account | coin | time | tid | oid-or-CLOID) is the
  key everywhere; only its inputs are canonicalized (integer text for time and tid, byte-identical for
  numeric input). A stream increment, a snapshot, the order response and the REST window count a fill once.
- Monitoring per trading account at `[:vol-range :execution :monitoring]` (pure `monitoring`, wired by the
  execution watcher). It is `:live` only after the transport and both account streams are usable for the
  account, a `userFills` snapshot arrived after the last socket close, and a `:resync` (journal sync plus a
  reconcile of every open ladder, nothing sent) finished with complete reads in the same epoch. A
  disconnect, socket close, re-subscription snapshot, account change, loss of the account's streams or a
  watcher tick gap over 45 s starts a new epoch. Retries back off 15, 30, 60, 120 s with ±20 % jitter and
  run only while visible, on the existing 15 s tick; no new timer, socket or registry.
- Placement gate. `executor-place` `refusal` returns `:monitoring/stale`, `:monitoring/resync-required` or
  `:monitoring/suspended` while monitoring is not `:live`, at the click and again in the final pre-HTTP
  guard (recorded as provably not sent, reservation released). Cancel, Refresh status and sync never
  consult it. The dock shows one non-live-region monitoring line; the review and repair submit are
  disabled with a note. Successful background ops no longer write `[:vol-range :execution :last]`.
- Shared fill records. `fill-records/normalize-fill` is the one canonical fill record (venue money strings,
  signed fee including the builder fee, the shared fill key) for WebSocket and REST rows, and
  `live-ingress/register-fills-consumer!` is the one way to receive raw `userFills` envelopes (every row,
  before truncation, each consumer guarded). The durable ledger, its coverage model and their failure
  isolation belong to Reporting (`vol-range-ledger`); this work writes no store.

## Consequences

- Manual and optimizer submits now reserve nonces before signing; behavior is otherwise unchanged.
- No Vol Range UI is enabled by this PR; the foundations are exercised by unit tests and fakes.
- Browser-only storage still cannot give a global lock across devices (spec 3.3); conflicts are detected by
  reconciliation, not prevented.
- Stage 5 adds the grid lifecycle and executor; it is not wired to any UI or effect adapter yet.
- Live evidence (section 9) adds no journal write, REST loop or signing path; it can only make the dock
  learn sooner and make placement wait until monitoring has caught up.

---
owner: trading
status: canonical
last_reviewed: 2026-10-27
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

## Consequences

- Manual and optimizer submits now reserve nonces before signing; behavior is otherwise unchanged.
- No Vol Range UI is enabled by this PR; the foundations are exercised by unit tests and fakes.
- Browser-only storage still cannot give a global lock across devices (spec 3.3); conflicts are detected by
  reconciliation, not prevented.

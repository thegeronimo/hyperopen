---
owner: security
status: canonical
last_reviewed: 2026-02-13
review_cycle_days: 90
source_of_truth: true
---

# Security and Signing Safety

## Crypto Signing and Exchange Application Programming Interface Rules (MUST)
- MUST treat signing payload serialization as consensus-critical behavior; any wire-format change requires explicit parity tests against known vectors.
- MUST preserve integer fidelity for signing-critical fields (`oid`, nonces, asset indexes, sizes where applicable) and MUST NOT route them through lossy float encoding.
- MUST keep signing identity deterministic: the signing private key and persisted `agent-address` must be reconciled before signing/submit.
- MUST fail fast or reconcile when persisted session identity drifts, and MUST NOT silently continue with mismatched key/address pairs.
- MUST verify missing-wallet/missing-agent exchange errors before clearing local agent credentials (for example via `userRole` or equivalent info lookup).
- MUST avoid destructive key invalidation on ambiguous errors; only clear local credentials when invalidity is confirmed.
- MUST keep signing diagnostics non-sensitive: log hashes, action types, and derived signer metadata only; MUST NOT log raw private keys or raw secret material.
- MUST keep protocol-shape translation and exchange error normalization centralized in Anti-Corruption Layer and Application Programming Interface boundaries (for example `/hyperopen/src/hyperopen/api/trading.cljs` and signing utilities), not UI callbacks.
- MUST cross-check signing and exchange action behavior against reference software development kits whenever signing or Application Programming Interface code is changed.
- MUST document any intentional divergence from reference SDK behavior in PR notes and include compensating regression coverage.

## Signing Rules
- Treat payload serialization as consensus-critical.
- Preserve integer fidelity for signing-critical fields.
- Reconcile signing key identity and persisted `agent-address` before submit.
- Fail fast or reconcile on signer/session drift; do not silently continue.

## Credential Invalidation Rules
- Validate missing-wallet/missing-agent conditions before clearing local credentials.
- Do not destructively invalidate keys on ambiguous errors.
- Clear local credentials only when invalidity is confirmed.

## Diagnostics and Logging
- Log only non-sensitive signing diagnostics (hashes, action types, derived signer metadata).
- Never log raw private keys or secret material.

## Protocol and SDK Parity
When signing or exchange behavior changes, verify against at least two reference SDK and document intentional divergence with regression coverage:
- [nktkas/hyperliquid](https://github.com/nktkas/hyperliquid)
- [nomeida/hyperliquid](https://github.com/nomeida/hyperliquid)
- [hyperliquid-dex/hyperliquid-python-sdk](https://github.com/hyperliquid-dex/hyperliquid-python-sdk)

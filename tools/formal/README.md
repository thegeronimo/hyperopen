# Formal Tooling

This directory holds the repo-local formal-verification workspace for Hyperopen.

## Commands

Use the npm scripts from the repository root:

```sh
npm run formal:verify -- --surface vault-transfer
npm run formal:sync -- --surface order-request-standard
```

Supported surfaces are:

- `vault-transfer`
- `order-request-standard`
- `order-request-advanced`

## What It Does

- `verify` builds the Lean 4 workspace in `tools/formal/lean/` and checks the selected surface manifest.
- For modeled surfaces, `verify` also regenerates transient output under `target/formal/` and fails if the checked-in generated namespace is stale.
- `sync` refreshes the deterministic manifest for the selected surface under `tools/formal/generated/`.
- For modeled surfaces, `sync` also copies the transient generated namespace into the checked-in `test/hyperopen/formal/*.cljs` bridge.
- Both commands fail fast with a Lean install hint if `lean` and `lake` are unavailable.

Current surface state:

- `vault-transfer`: modeled, emits `target/formal/vault-transfer-vectors.cljs`, and syncs `test/hyperopen/formal/vault_transfer_vectors.cljs`
- `order-request-standard`: modeled, emits `target/formal/order-request-standard-vectors.cljs`, and syncs `test/hyperopen/formal/order_request_standard_vectors.cljs`
- `order-request-advanced`: bootstrap manifest only

## Layout

- `tools/formal.clj` is the repo-local entry point.
- `tools/formal/core.clj` implements argument parsing and Lean process management.
- `tools/formal/lean/` is the Lean 4 workspace for the proof entrypoints.
- `tools/formal/generated/` holds the committed surface manifests used by `verify`.
- `target/formal/` holds transient generated source during verify and sync runs.

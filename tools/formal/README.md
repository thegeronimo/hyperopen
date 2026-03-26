# Formal Tooling

This directory holds the repo-local bootstrap for the first formal-verification work on Hyperopen.

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
- `sync` regenerates the deterministic manifest for the selected surface under `tools/formal/generated/`.
- Both commands fail fast with a Lean install hint if `lean` and `lake` are unavailable.

## Layout

- `tools/formal.clj` is the repo-local entry point.
- `tools/formal/core.clj` implements argument parsing and Lean process management.
- `tools/formal/lean/` is the Lean 4 workspace for the proof entrypoints.
- `tools/formal/generated/` holds the committed bootstrap manifests used by `verify`.

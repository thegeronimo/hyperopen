# ADR 0016: API Default Facade Namespace Split

- Status: Accepted
- Date: 2026-02-13

## Context

`/hyperopen/src/hyperopen/api.cljs` mixed two concerns:

- Instance API construction via `make-api`
- Global mutable default facade ownership via `api-facade-state`

That made `hyperopen.api` both an injectable API boundary and a process-global service locator.

## Decision

1. Move global default facade ownership and wrappers to:
   - `/hyperopen/src/hyperopen/api/default.cljs`
2. Make `/hyperopen/src/hyperopen/api.cljs` instance-first only:
   - `make-api`
   - immutable construction exports (`info-url`, default config, default service constructor)
3. Update internal runtime/application call sites that intentionally rely on global defaults to require:
   - `/hyperopen/src/hyperopen/api/default.cljs`

## Consequences

- `hyperopen.api` no longer owns hidden global mutable runtime state.
- Dependency injection boundaries become clearer: instance consumers use `make-api`; legacy/global consumers opt in via `hyperopen.api.default`.
- Existing global behavior remains available without breaking legacy flows, but it is explicitly named as a default facade.

## Invariant Ownership

- Global default API runtime ownership:
  - `/hyperopen/src/hyperopen/api/default.cljs`
- Injectable API instance ownership:
  - `/hyperopen/src/hyperopen/api.cljs`
  - `/hyperopen/src/hyperopen/api/instance.cljs`

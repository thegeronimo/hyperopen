# Dev Telemetry and Console Snapshot Guide

## Purpose

Explain the development telemetry feature in Hyperopen:

- where telemetry lives,
- what telemetry events exist,
- how to request a full debug snapshot from the browser console,
- how to capture/replay websocket runtime flight recordings,
- and how production behavior differs.

## Scope and Environment

Telemetry in this implementation is **dev-only**.

- Enabled in development builds via `^boolean goog.DEBUG`.
- Disabled in production/release builds by default.
- Console snapshot globals are installed only in browser app dev preload.

Relevant implementation:

- `/hyperopen/src/hyperopen/telemetry.cljs`
- `/hyperopen/src/hyperopen/telemetry/console_preload.cljs`
- `/hyperopen/src/hyperopen/websocket/flight_recorder.cljs`
- `/hyperopen/src/hyperopen/websocket/client.cljs`
- `/hyperopen/shadow-cljs.edn`

## Architecture

### 1. Telemetry boundary

`/hyperopen/src/hyperopen/telemetry.cljs` defines the boundary API:

- `emit!` for structured events.
- `log!` for human-readable log lines (internally emits `:log/message`).
- `events` to read current in-memory telemetry entries.
- `events-json` to serialize telemetry event history.
- `clear-events!` to reset local telemetry history.

All app-source `println` call sites were migrated to this boundary.

### 2. Event buffer

Telemetry keeps an in-memory bounded event log:

- max events: `2000`
- each event includes:
  - `:seq` (monotonic local sequence),
  - `:event` (keyword event type),
  - `:at-ms` (event timestamp),
  - plus event-specific attributes.

### 3. Console preload integration

`/hyperopen/src/hyperopen/telemetry/console_preload.cljs` installs dev globals:

- `globalThis.HYPEROPEN_DEBUG`
- `globalThis.hyperopenSnapshot`
- `globalThis.hyperopenSnapshotJson`
- `globalThis.hyperopenDownloadSnapshot`

The preload is configured only for app devtools:

- `:devtools {:preloads [hyperopen.telemetry.console-preload] ...}`

No preload is configured for `:test`, `:ws-test`, or `:release`.

### 4. Runtime flight recorder

`/hyperopen/src/hyperopen/websocket/flight_recorder.cljs` provides a bounded in-memory recorder for normalized websocket runtime transitions:

- runtime messages (`:runtime/msg`),
- emitted effect batches (`:runtime/effects`),
- enqueue/interpreter drops (`:runtime/drop`).

The recorder is enabled by default in dev builds (`goog.DEBUG`) through `/hyperopen/src/hyperopen/websocket/client.cljs` config and is available from `HYPEROPEN_DEBUG`.

## Console API

`HYPEROPEN_DEBUG` exposes:

- `registeredActionIds()` => sorted array of registered runtime action id strings such as `:actions/start-ghost-mode`.
- `dispatch(actionVector)` => dispatches one runtime action vector through the canonical app runtime. `actionVector` must be a JavaScript array whose first item is a registered action id string such as `":actions/stop-ghost-mode"`.
- `snapshot()` => returns a JS object with:
  - app state,
  - runtime state,
  - websocket states,
  - telemetry events.
- `snapshotJson()` => pretty JSON string of `snapshot()`.
- `downloadSnapshot()` => downloads JSON snapshot file.
- `flightRecording()` => raw in-memory websocket runtime flight recording.
- `flightRecordingRedacted()` => redacted recording safe for sharing.
- `clearFlightRecording()` => clears current recording buffer.
- `replayFlightRecording()` => deterministic replay summary for captured runtime messages.
- `downloadFlightRecording()` => downloads redacted flight recording JSON.
- `events()` => telemetry events only.
- `eventsJson()` => telemetry events JSON string.
- `clearEvents()` => clears telemetry event buffer.

### Deterministic Ghost Mode example

Use the debug dispatch bridge for deterministic state transitions and prefer it over DOM targeting when the goal is to drive app state rather than validate a click path.

Examples:

- Enumerate available Ghost Mode action ids:
  - `HYPEROPEN_DEBUG.registeredActionIds().filter((id) => id.includes("ghost-mode"))`
- Start Ghost Mode directly for a known address:
  - `HYPEROPEN_DEBUG.dispatch([":actions/start-ghost-mode", "0x1234..."])`
- Stop Ghost Mode directly:
  - `HYPEROPEN_DEBUG.dispatch([":actions/stop-ghost-mode"])`

This is especially useful for automation because the UI-level Ghost Mode open action (`:actions/open-ghost-mode-modal`) depends on popover anchor bounds, while `:actions/start-ghost-mode` and `:actions/stop-ghost-mode` express the deterministic state transition directly.

Aliases:

- `hyperopenSnapshot()` => `HYPEROPEN_DEBUG.snapshot()`
- `hyperopenSnapshotJson()` => `HYPEROPEN_DEBUG.snapshotJson()`
- `hyperopenDownloadSnapshot()` => `HYPEROPEN_DEBUG.downloadSnapshot()`

## Current Telemetry Event Types

Current high-level event types include:

- `:log/message`
- `:websocket/runtime-log`
- `:websocket/dead-letter`
- `:websocket/topic-handler-failure`
- `:websocket/runtime-interpreter-failure`

## How To Use Day-to-Day

1. Start dev app:
   - `npm run dev`
2. Reproduce behavior.
3. In browser console, request snapshot:
   - `HYPEROPEN_DEBUG.snapshot()`
4. Inspect runtime transitions:
   - `HYPEROPEN_DEBUG.flightRecording()`
5. Re-run reducer deterministically on captured messages:
   - `HYPEROPEN_DEBUG.replayFlightRecording()`
6. If needed, export/share:
   - `HYPEROPEN_DEBUG.snapshotJson()`
   - `HYPEROPEN_DEBUG.downloadSnapshot()`
   - `HYPEROPEN_DEBUG.flightRecordingRedacted()`
   - `HYPEROPEN_DEBUG.downloadFlightRecording()`

## Security and Data Hygiene

Telemetry must remain non-sensitive:

- Never emit raw private keys, signatures, or secret tokens.
- Prefer hashes/derived identifiers for sensitive domains.
- Treat wallet addresses as sensitive when preparing shared bundles.

If user-facing support export is exposed, add explicit redaction before download.

Current recorder exports use diagnostics redaction rules (`sanitize-value :redact`) for sensitive fields and addresses.

## Production Behavior (Current)

Current production behavior is intentionally conservative:

- telemetry API is a no-op when `goog.DEBUG` is false,
- no dev console preload globals in release,
- flight recorder is disabled by default when `goog.DEBUG` is false,
- no runtime telemetry transport enabled in production yet.

## Suggested Production Strategy (Next Step)

When ready, add explicit support capture mode:

1. Add runtime flag (for example `ENABLE_SUPPORT_EVENT_CAPTURE`).
2. Keep capture off by default.
3. In support mode:
   - capture bounded redacted event log,
   - expose support bundle export:
     - full snapshot JSON,
     - websocket diagnostics payload,
     - app/build metadata.
4. Require user consent before export.

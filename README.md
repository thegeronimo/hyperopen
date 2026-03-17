# Hyperopen

> Open-source Hyperliquid client designed to maximize user control.

[![Tests](https://github.com/thegeronimo/hyperopen/actions/workflows/tests.yml/badge.svg)](https://github.com/thegeronimo/hyperopen/actions/workflows/tests.yml)
[![Coverage](.github/badges/coverage.svg)](https://github.com/thegeronimo/hyperopen/actions/workflows/tests.yml)
[![Tests Total](.github/badges/tests-total.svg)](https://github.com/thegeronimo/hyperopen/actions/workflows/tests.yml)
[![Assertions Total](.github/badges/assertions-total.svg)](https://github.com/thegeronimo/hyperopen/actions/workflows/tests.yml)

Hyperopen is a community-driven trading interface for Hyperliquid. The project is built around a simple idea: traders should have control over the software they trade through.

Today, that means an inspectable, forkable client with deterministic state transitions, explicit runtime behavior, and practical parity across trading, portfolio, funding, and market-data workflows. Longer term, the same principle should guide optional copilots and automation, but no model integration is shipped in the codebase today.

## Why Hyperopen

- Inspectable and forkable: the frontend is open source and meant to be studied, modified, and improved by the community.
- Deterministic by default: websocket flows, state transitions, and trading-critical logic are implemented in testable code paths rather than hidden heuristics.
- Execution stays explicit: signing, funding, and order flows are treated as safety-critical surfaces.
- Built for practical workflows: trade, portfolio, funding, vault, and realtime market views live in one codebase.
- Developed in the open: architecture, reliability rules, and product intent are documented in the repository.

## Current focus

- Trade surfaces with charting, order entry, and orderbook workflows
- Portfolio and account views
- Funding and wallet-related flows
- Websocket/runtime reliability and parity testing
- Open architecture with contributor-facing documentation

## Project status

Hyperopen is under active development. APIs, UX details, and internal boundaries are still evolving, but the project already enforces strict validation gates for reliability and signing-sensitive changes.

If you are evaluating the project on GitHub, start with:

- [Architecture Map](ARCHITECTURE.md)
- [Security and Signing Safety](docs/SECURITY.md)
- [Reliability Invariants](docs/RELIABILITY.md)
- [Product specs and roadmap](docs/product-specs/index.md)

## Architecture

Hyperopen is built around plain ClojureScript data, explicit state transitions, and narrow interop boundaries. The UI is rendered with Replicant and action/effect handlers are registered through Nexus, but the practical design choice is simpler than any framework label: most product logic works on maps, vectors, and keywords, then hands off side effects to named boundary namespaces.

That wiring is visible in:

- [src/hyperopen/app/bootstrap.cljs](src/hyperopen/app/bootstrap.cljs), which connects Replicant rendering to Nexus dispatch
- [src/hyperopen/runtime/wiring.cljs](src/hyperopen/runtime/wiring.cljs) and [src/hyperopen/runtime/bootstrap.cljs](src/hyperopen/runtime/bootstrap.cljs), which register actions, effects, system state, and runtime watchers
- feature namespaces such as [src/hyperopen/chart/actions.cljs](src/hyperopen/chart/actions.cljs), [src/hyperopen/runtime/action_adapters.cljs](src/hyperopen/runtime/action_adapters.cljs), and [src/hyperopen/runtime/app_effects.cljs](src/hyperopen/runtime/app_effects.cljs)

### Data-oriented state and actions

Feature actions usually return effect descriptors instead of mutating UI objects directly. That keeps the "what should happen next?" part of the code visible and testable.

```clj
(defn select-chart-type
  [state chart-type]
  [(chart-dropdown-projection-effect nil [[[:chart-options :selected-chart-type] chart-type]])
   [:effects/local-storage-set "chart-type" (name chart-type)]])
```

That example comes from [src/hyperopen/chart/actions.cljs](src/hyperopen/chart/actions.cljs). The action computes the next projection and the persistence effect as plain data. The effect is interpreted later by runtime adapters such as [src/hyperopen/runtime/app_effects.cljs](src/hyperopen/runtime/app_effects.cljs), which is where `swap!`, `localStorage`, history updates, and other side effects actually happen.

### Explicit state flow

The websocket runtime makes this pattern especially explicit. [src/hyperopen/websocket/application/runtime_reducer.cljs](src/hyperopen/websocket/application/runtime_reducer.cljs) reduces runtime messages such as `:cmd/send-message`, `:evt/socket-open`, and `:evt/socket-close` into a new state plus a vector of runtime effects. Those effects are plain data values created by [src/hyperopen/websocket/domain/model.cljs](src/hyperopen/websocket/domain/model.cljs), for example `:fx/socket-send`, `:fx/timer-set-interval`, and `:fx/project-runtime-view`.

This makes cause and effect easier to follow than in a mutable event-handler style architecture:

- the reducer owns the state transition
- the effect interpreter owns timers, sockets, and dispatching
- tests can drive the reducer and interpreter separately

The reducer is even written so it can be called directly in tests and replay-like tooling, which is why runtime config normalization and message typing live inside the reducer layer instead of being spread across UI callbacks.

### WebSocket runtime, not callback soup

The websocket stack is one of the clearest examples of this repo's architecture. Hyperopen does not treat the socket as a single `onmessage` callback that mutates app state directly. It treats realtime handling as a small runtime with explicit stages:

- [src/hyperopen/websocket/client.cljs](src/hyperopen/websocket/client.cljs) assembles the transport, scheduler, clock, router, and runtime configuration.
- [src/hyperopen/websocket/application/runtime.cljs](src/hyperopen/websocket/application/runtime.cljs) uses `core.async` channels and topic routing to move commands and envelopes through the system.
- [src/hyperopen/websocket/application/runtime_engine.cljs](src/hyperopen/websocket/application/runtime_engine.cljs) runs the mailbox/effects loop.
- [src/hyperopen/websocket/application/runtime_reducer.cljs](src/hyperopen/websocket/application/runtime_reducer.cljs) decides state transitions and emitted effects.
- [src/hyperopen/websocket/infrastructure/runtime_effects.cljs](src/hyperopen/websocket/infrastructure/runtime_effects.cljs) executes transport, timer, router, and projection side effects.

The runtime also applies explicit policy by channel type. [src/hyperopen/websocket/domain/policy.cljs](src/hyperopen/websocket/domain/policy.cljs) separates high-volume `:market` topics such as `l2Book`, `trades`, `candle`, and `activeAssetCtx` from `:lossless` topics such as `webData2`, `openOrders`, and `userFills`. In [src/hyperopen/websocket/application/runtime.cljs](src/hyperopen/websocket/application/runtime.cljs), market handlers get sliding buffers while lossless handlers get regular buffers, so high-frequency market traffic can be smoothed without treating account and order streams as disposable.

That policy carries through to UI projection. [src/hyperopen/websocket/market_projection_runtime.cljs](src/hyperopen/websocket/market_projection_runtime.cljs) coalesces market updates per animation frame and keeps the latest update per coalesce key, so heavy market traffic does not force one store write per websocket message. The tests in [test/hyperopen/websocket/market_projection_runtime_test.cljs](test/hyperopen/websocket/market_projection_runtime_test.cljs) cover those coalescing guarantees directly.

This structure gives the websocket layer a few practical benefits:

- account and order data can keep stricter handling than market tick traffic
- reconnect, backoff, freshness, and gap detection live in named runtime modules instead of scattered callbacks
- raw provider payloads are normalized once at the edge in [src/hyperopen/websocket/acl/hyperliquid.cljs](src/hyperopen/websocket/acl/hyperliquid.cljs)
- diagnostics are first-class, with health projections in [src/hyperopen/websocket/health_projection.cljs](src/hyperopen/websocket/health_projection.cljs) and message/effect capture in [src/hyperopen/websocket/flight_recorder.cljs](src/hyperopen/websocket/flight_recorder.cljs)

### Boundaries stay at the edge

Browser, network, and persistence code are intentionally pushed to the edges:

- [src/hyperopen/websocket/acl/hyperliquid.cljs](src/hyperopen/websocket/acl/hyperliquid.cljs) parses raw provider JSON into domain envelopes before the rest of the websocket runtime consumes it.
- [src/hyperopen/websocket/infrastructure/runtime_effects.cljs](src/hyperopen/websocket/infrastructure/runtime_effects.cljs) owns socket lifecycle, timers, routing, projection publication, and telemetry.
- [src/hyperopen/runtime/app_effects.cljs](src/hyperopen/runtime/app_effects.cljs) owns app-level `save!`, `localStorage`, history, and async fetch effects.
- [src/hyperopen/platform/indexed_db.cljs](src/hyperopen/platform/indexed_db.cljs) centralizes IndexedDB access instead of scattering raw `js/indexedDB` calls.

One practical benefit is that mutable foreign objects stay out of core reducer state:

```clj
(defn- connection-projection [state]
  {:status (:status state)
   :attempt (:attempt state)
   :ws nil})

(defn- attach-active-socket [io-state runtime-view]
  (assoc-in runtime-view [:connection :ws]
            (get-in @io-state [:sockets (:active-socket-id runtime-view)])))
```

The reducer publishes plain data first, and the effect interpreter attaches the actual websocket object later when updating the runtime view. That keeps browser objects out of the part of the system that needs to stay deterministic.

### Projections and view models

Rendering code generally consumes derived data instead of re-parsing raw exchange payloads inline:

- [src/hyperopen/views/account_info/projections](src/hyperopen/views/account_info/projections) normalizes, filters, dedupes, and labels balances, positions, orders, and trades for tables.
- [src/hyperopen/views/portfolio/vm.cljs](src/hyperopen/views/portfolio/vm.cljs) builds a portfolio view model from account metrics, summary state, benchmark context, and chart helpers.
- the websocket reducer emits a public runtime projection via `:fx/project-runtime-view` instead of exposing raw internal runtime state directly.

This is useful at the product level because a UI change often becomes "update the projection contract" instead of "thread parsing logic through several components."

### Predictable rendering

Replicant fits this codebase because it keeps the rendering model close to the state model. In [src/hyperopen/app/bootstrap.cljs](src/hyperopen/app/bootstrap.cljs), `render-app!` renders `(app-view/app-view state)`, and [src/hyperopen/runtime/bootstrap.cljs](src/hyperopen/runtime/bootstrap.cljs) installs a render loop that watches the store and schedules the next render from the latest state value.

In practice, that means the main mental model is still:

- state changes
- projections or view models derive UI-facing data
- the view renders from that data

This repo still uses lifecycle hooks and runtime sidecars where browser integration requires them, but they are treated as explicit exceptions rather than the default programming model. That keeps the UI more predictable: when behavior changes, there is usually a reducer, action, projection, or view-model change you can point to, instead of a chain of implicit component-local behavior spread across timing and escape hatches.

This is easier to reason about than frontend styles where behavior is spread across component-local state, effect timing, refs, memoization, and imperative escape hatches. Hyperopen keeps more of that logic in explicit data flow, which makes the product easier for humans and agents to reason about and safer to change.

## Why these choices reduce complexity

- State transitions are explicit return values, not hidden mutations buried in callbacks.
- Data normalization happens once at boundaries and projection helpers, which reduces duplicate parsing logic across views.
- Core state stays plain and serializable, which makes debugging and reducer tests much simpler.
- Side effects are concentrated in a few namespaces, so bugs involving sockets, timers, storage, or fetches are easier to isolate.
- The same seams are tested directly. Good examples are [test/hyperopen/websocket/acl/hyperliquid_test.cljs](test/hyperopen/websocket/acl/hyperliquid_test.cljs), [test/hyperopen/websocket/infrastructure/runtime_effects_test.cljs](test/hyperopen/websocket/infrastructure/runtime_effects_test.cljs), [test/hyperopen/views/account_info/projections_test.cljs](test/hyperopen/views/account_info/projections_test.cljs), and [test/hyperopen/websocket/market_projection_runtime_test.cljs](test/hyperopen/websocket/market_projection_runtime_test.cljs).

## Why this codebase is easier for humans and agents to work on

The architecture is not "AI magic." It is useful for humans and agents for the same reason: the code gives you stable places to look.

- User actions usually start in feature action namespaces such as [src/hyperopen/chart/actions.cljs](src/hyperopen/chart/actions.cljs) or in [src/hyperopen/runtime/action_adapters.cljs](src/hyperopen/runtime/action_adapters.cljs).
- Boundary work usually lives in [src/hyperopen/runtime/effect_adapters](src/hyperopen/runtime/effect_adapters), [src/hyperopen/websocket/infrastructure](src/hyperopen/websocket/infrastructure), or [src/hyperopen/platform](src/hyperopen/platform).
- Derived UI state usually lives in [src/hyperopen/views](src/hyperopen/views) under `projections` or `vm`.
- Tests are named after those same seams, which makes targeted verification straightforward.

That locality matters in practice. A contributor or agent can usually implement a feature by changing one action or reducer, one boundary adapter if I/O is involved, one projection or view-model if the UI shape changes, and one nearby test. That is safer than hunting through large mutable controller objects with hidden side effects.

## Why ClojureScript

We chose ClojureScript because it reinforces the architectural habits this repo depends on. In Hyperopen, product logic is usually written as functions over plain values. When a function takes a state map or payload map, the default expectation is that it returns a new value or a vector of effects. If something mutates shared state, that mutation is usually obvious because you can see `swap!`, a runtime sidecar, or a boundary namespace that owns the interop.

That is visible in the code:

- [src/hyperopen/websocket/domain/model.cljs](src/hyperopen/websocket/domain/model.cljs) models runtime messages, envelopes, and effects as plain maps with keywords.
- [src/hyperopen/websocket/application/runtime_reducer.cljs](src/hyperopen/websocket/application/runtime_reducer.cljs) updates runtime state with `assoc`, `assoc-in`, `update`, and `update-in` rather than hiding behavior behind mutable objects.
- [src/hyperopen/views/portfolio/vm.cljs](src/hyperopen/views/portfolio/vm.cljs) and [src/hyperopen/views/account_info/projections](src/hyperopen/views/account_info/projections) build derived UI data from state instead of teaching components to mutate shared objects.
- [src/hyperopen/runtime/app_effects.cljs](src/hyperopen/runtime/app_effects.cljs) and [src/hyperopen/websocket/infrastructure/runtime_effects.cljs](src/hyperopen/websocket/infrastructure/runtime_effects.cljs) make the mutation points obvious because they are the places that actually call `swap!`, browser APIs, sockets, and timers.

This matters for humans and LLM-assisted development for the same reason:

- reasoning stays local more often
- it is easier to tell whether a function transforms data or performs I/O
- there are fewer hidden aliasing questions about who else might be mutating the same object
- targeted edits are safer because the mutation and interop points are named and limited

When reasoning is not local, both humans and agents have to spend context answering questions like:

- Did this function mutate its input?
- Does this library mutate arguments?
- Are there multiple references to the same object?
- Could another async path be mutating this state?

Hyperopen leans on ClojureScript because it reduces that uncertainty. Most product logic is written as transformations of plain values, while mutation and I/O are pushed into explicit effect and infrastructure boundaries. That means more of the available context can go toward implementing the change itself, instead of first proving whether the surrounding code is safe to modify.

ClojureScript does not remove the need for discipline, and this repo still uses atoms where runtime state is required. The benefit is that the language makes the pure-data path natural and makes the mutable path explicit. That matches the design goals of this codebase.

Another reason we chose ClojureScript is long-term stability. The language encourages plain data, small composable functions, and a relatively stable core model. In Hyperopen, that matters because most product logic lives in reducers, projections, view models, and boundary adapters instead of framework-heavy object lifecycles. We still depend on browser APIs, exchange protocols, and JavaScript tooling at the edges, but less of the application code has to be rewritten when those surrounding tools change.

## Quick start

### Prerequisites

- [Node.js and npm](https://nodejs.org/)
- [Java 11+](https://adoptium.net/)
- [Clojure CLI](https://clojure.org/guides/install_clojure)

### Install dependencies

```bash
npm ci
clojure -P
```

### Start development

```bash
# Main development workflow
npm run dev

# Main app plus UI workbench
npm run dev:portfolio

# Same-origin HyperUnit proxy for funding flows
npm run dev:proxy
```

Open http://localhost:8080 in your browser.
When using `npm run dev:proxy`, open http://localhost:8081.

### Build

```bash
npm run build
```

The release-ready static artifact root is generated at `out/release-public`.
To smoke-test the release artifact locally:

```bash
npx serve -s out/release-public -l 8082
```

## Validation

Run validation from the repository root:

| Command | What it does |
| --- | --- |
| `npm run check` | Runs lint and compile gates for the app, worker, docs, and test builds. |
| `npm test` | Compiles the `:test` Shadow build and runs the main Node test suite. |
| `npm run lint:delimiters -- --changed` | Runs a fast reader-level syntax preflight on changed CLJ/CLJS/EDN files before expensive compiles or tests. |
| `npm run test:websocket` | Compiles and runs the websocket-focused test suite. |
| `npm run test:ci` | Runs the local CI gate: `npm run check` followed by `npm test`. |
| `npm run test:watch` | Watches and recompiles the `:test` build while you iterate. |
| `npm run test:repl` | Starts a ClojureScript REPL connected to the `:test` build. |

For local ClojureScript edit loops, prefer `npm run lint:delimiters -- --changed` before `npm test`, `npm run test:websocket`, or manual `shadow-cljs` compile commands when you want quick unmatched-delimiter and reader-error feedback.

## Design direction

Hyperopen is being developed to increase user agency in a few concrete ways:

- Interface control: open code, clear architecture, and room for community modification
- Data comprehension: deterministic computation and explicit runtime truth
- Execution control: no hidden trading-critical behavior behind opaque UI flows
- Community accountability: roadmap, plans, and quality standards live in the repo

Future work may extend that philosophy further into user-controlled analytics copilots and automation. That direction is intentional, but it is not a shipped feature today.

## Contributing

Contributions are welcome. The best starting points are:

- [Architecture Map](ARCHITECTURE.md)
- [Planning and execution](docs/PLANS.md)
- [Work tracking](docs/WORK_TRACKING.md)
- [Quality scorecard](docs/QUALITY_SCORE.md)
- [Frontend policy](docs/FRONTEND.md)

The repository uses beads (`bd`) for local issue tracking. If you are working in the repo directly, `bd ready --json` shows unblocked work and [docs/WORK_TRACKING.md](docs/WORK_TRACKING.md) describes the contributor workflow.

## License

GNU AGPL v3. See [LICENSE](LICENSE).

## When changing code here

- Keep pure decision logic in action, reducer, domain, projection, or view-model namespaces. Keep browser APIs, sockets, timers, storage, and fetches in effect or infrastructure namespaces.
- Normalize external payloads once at the boundary or projection helper. Do not let multiple views invent their own parsing rules.
- Prefer explicit effects and derived values over ad hoc mutation of shared state.
- Do not put live websocket, DOM, or timer objects into core app state when a sidecar or interpreter boundary already exists.
- Add or update the focused test nearest the seam you touched before reaching for broader fixes.

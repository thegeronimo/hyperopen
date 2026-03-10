# Hyperopen

> Open-source Hyperliquid client designed to maximize user control.

[![Tests](https://github.com/thegeronimo/hyperopen/actions/workflows/tests.yml/badge.svg)](https://github.com/thegeronimo/hyperopen/actions/workflows/tests.yml)
[![Coverage](.github/badges/coverage.svg)](https://github.com/thegeronimo/hyperopen/actions/workflows/tests.yml)
[![Tests Total](.github/badges/tests-total.svg)](https://github.com/thegeronimo/hyperopen/actions/workflows/tests.yml)
[![Assertions Total](.github/badges/assertions-total.svg)](https://github.com/thegeronimo/hyperopen/actions/workflows/tests.yml)

Hyperopen is a community-driven trading interface for Hyperliquid. The project is built around a simple idea: traders should have more control over the software they trade through than they get from a closed, operator-controlled frontend.

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

# Same-origin HyperUnit proxy for funding flows
npm run dev:proxy
```

Open http://localhost:8080 in your browser.
When using `npm run dev:proxy`, open http://localhost:8081.

### Build

```bash
npm run build
```

## Validation

Run validation from the repository root:

| Command | What it does |
| --- | --- |
| `npm run check` | Runs lint and compile gates for the app, worker, docs, and test builds. |
| `npm test` | Compiles the `:test` Shadow build and runs the main Node test suite. |
| `npm run test:websocket` | Compiles and runs the websocket-focused test suite. |
| `npm run test:ci` | Runs the local CI gate: `npm run check` followed by `npm test`. |
| `npm run test:watch` | Watches and recompiles the `:test` build while you iterate. |
| `npm run test:repl` | Starts a ClojureScript REPL connected to the `:test` build. |

## Design direction

Hyperopen is being developed to increase user agency in a few concrete ways:

- Interface control: open code, clear architecture, and room for community modification
- Data comprehension: deterministic computation and explicit runtime truth
- Execution control: no hidden trading-critical behavior behind opaque UI flows
- Community accountability: roadmap, plans, and quality standards live in the repo

Future work may extend that philosophy further into user-controlled analytics copilots and automation. That direction is intentional, but it is not a shipped feature today.

## Contributing

Contributions are welcome. The best starting points are:

- [Planning and execution](docs/PLANS.md)
- [Work tracking](docs/WORK_TRACKING.md)
- [Quality scorecard](docs/QUALITY_SCORE.md)
- [Frontend policy](docs/FRONTEND.md)

The repository uses beads (`bd`) for local issue tracking. If you are working in the repo directly, `bd ready --json` shows unblocked work and [docs/WORK_TRACKING.md](docs/WORK_TRACKING.md) describes the contributor workflow.

## License

GNU AGPL v3. See [LICENSE](LICENSE).

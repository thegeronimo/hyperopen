# Hyperopen

> An open source hyperliquid client

[![Tests](https://github.com/thegeronimo/hyperopen/actions/workflows/tests.yml/badge.svg)](https://github.com/thegeronimo/hyperopen/actions/workflows/tests.yml)
[![Coverage](.github/badges/coverage.svg)](https://github.com/thegeronimo/hyperopen/actions/workflows/tests.yml)

A ClojureScript trading interface built with [Replicant](https://github.com/cjohansen/replicant) for data-driven rendering and [Nexus](https://github.com/cjohansen/nexus) for action-based state management.

## Architecture

This project follows a pure functional architecture with strict separation between components and side effects. For the top-level architecture map, see [ARCHITECTURE.md](ARCHITECTURE.md). For detailed implementation patterns, see the [Technical Implementation Guide](docs/product-specs/technical-implementation-guide.md).

Key architectural principles:

- **Pure Components**: No direct state mutation in UI components
- **Action-based State Management**: All state changes flow through registered actions and effects
- **Data-driven**: Event handlers declare actions as data structures
- **Testable**: Actions are pure functions, effects are isolated side effects

## Development

### Prerequisites

- [Java 11+](https://adoptium.net/)
- [Clojure CLI](https://clojure.org/guides/install_clojure)

### Setup

```bash
# Install dependencies
clojure -P

# Start development server
npm run dev
# or
clj -M:dev -m shadow.cljs.devtools.cli watch app
```

Open http://localhost:8080 in your browser.

### Build

```bash
# Production build
npm run build
# or
clj -M:dev -m shadow.cljs.devtools.cli release app
```

### Testing

Run test commands from the repository root:

```bash
npm ci
```

> Security notice: use `npm ci` for CI and production/release builds. It installs exactly from `package-lock.json` and verifies package integrity data, failing fast on lockfile drift or integrity mismatch.

| Command | What it does |
| --- | --- |
| `npm test` | Compiles the `:test` Shadow build and runs the full Node test suite (`out/test.js`). |
| `npm run test:websocket` | Compiles the websocket-focused test build and runs only websocket/address-watcher tests. |
| `npm run check` | Runs class-attr lint checks and compile gates for both app and test builds (no test execution). |
| `npm run test:ci` | Runs the CI gate locally: `npm run check` followed by `npm test`. |
| `npm run test:watch` | Watches and recompiles the `:test` build while you iterate. |
| `npm run test:repl` | Starts a ClojureScript REPL connected to the `:test` build. |

## Typography

- Default app typography uses system UI fonts (`--font-ui`) for fast initial render and no webfont blocking.
- Apply `.num` to any live numeric data (prices, sizes, PnL, balances, chart values) for tabular lining numerals.
- Apply `.num-right` to numeric table columns/cells to improve scan alignment.
- Use `.num-dense` only for dense numeric panes (orderbook/trades), not general UI text.
- Optional Inter support is runtime-only and off by default:
  - Root attribute: `data-ui-font="system"` or `data-ui-font="inter"`.
  - Persisted key: `hyperopen-ui-font` (`system` or `inter`).

## License

GNU AGPL v3

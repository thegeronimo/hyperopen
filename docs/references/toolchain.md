---
owner: platform
status: canonical
last_reviewed: 2026-03-10
review_cycle_days: 90
source_of_truth: true
---

# Toolchain and Build Reference

Repository build and test entry points:
- `npm run check`
- `npm test`
- `npm run test:crap`
- `npm run test:mutation`
- `npm run test:multi-agent`
- `npm run lint:delimiters -- --changed`
- `npm run mutate:nightly`
- `npm run test:websocket`
- `npm run agent:dry-run -- --issue <bd-id>`
- `npm run agent:ticket -- --issue <bd-id>`
- `npm run agent:resume-ticket -- --issue <bd-id>`
- `npm run dev`
- `npm run dev:portfolio`
- `npm run portfolio`
- `bb tools/crap_report.clj --scope src`
- `bb tools/mutate.clj --scan --module src/hyperopen/...`
- `bb tools/mutate_nightly.clj`
- `npm run test:browser-inspection`

Local discovery and semantic analysis commands:
- `rg -n "<pattern>" src test`
- `bb -m dev.check-delimiters --changed`
- `clojure-lsp diagnostics --project-root .`
- `clojure-lsp references --project-root . --from <fqns> --raw`
- `clojure-lsp rename --project-root . --from <old-fqns> --to <new-fqns> --dry`
- `tools/shadow-nrepl-port` or `npm run nrepl:port`

Usage guidance:
- Prefer `rg` for fast first-pass discovery, broad audits, and cases where the exact fully qualified symbol is not known yet.
- Prefer `bb -m dev.check-delimiters --changed` immediately after CLJ/CLJS/EDN edits when you want a cheap reader-level syntax preflight before `shadow-cljs` compiles or tests.
- Prefer `clojure-lsp` for symbol-accurate references, rename planning, and editor-backed definition jumps once a persistent LSP session is available.
- Prefer `tools/shadow-nrepl-port` over global process scans when you need the live Shadow nREPL for the current worktree; it reads `target/shadow-cljs/nrepl.port` from the worktree root and verifies the listener is still alive.
- For live local bug investigation, start with the browser attach/inspection commands when the question is about a running tab, console output, DOM state, or user interaction.
- Browser attach requires a compatible Chromium/CDP endpoint, such as Chrome or Brave launched with `--remote-debugging-port=<port>`. It cannot retroactively attach to a normal browser session that was started without remote debugging.
- Escalate to `tools/shadow-nrepl-port` only when you need to inspect runtime state or evaluate ClojureScript inside the current local build.
- Standalone `clj-kondo` is optional in local environments; use `clojure-lsp diagnostics` as the repo-safe semantic analysis default unless `clj-kondo` is explicitly installed and required.

Browser inspection and parity commands:
- `npm run browser:inspect -- --url <target-url> --target <label>`
- `npm run browser:compare`
- `npm run browser:mcp`
- `node tools/browser-inspection/src/cli.mjs session targets --attach-port <cdp-port>`
- `node tools/browser-inspection/src/cli.mjs session attach --attach-port <cdp-port>`
- `node tools/browser-inspection/src/cli.mjs session attach --attach-port <cdp-port> --target-id <cdp-target-id>`

Multi-agent orchestration commands:
- `node tools/multi-agent/src/cli.mjs dry-run --issue <bd-id>`
- `node tools/multi-agent/src/cli.mjs ticket --issue <bd-id>`
- `node tools/multi-agent/src/cli.mjs resume-ticket --issue <bd-id>`

Comprehensive tool surface and use guidance:
- `/hyperopen/docs/tools.md`

UI workbench reference:
- URL: `http://localhost:8080/ui-workbench.html`
- Shadow build: `:portfolio`
- Watch commands: `npm run dev:portfolio` or `npm run portfolio`
- Scene tree: `/hyperopen/portfolio/hyperopen/workbench/scenes/**`
- Shared workbench helpers: `/hyperopen/portfolio/hyperopen/workbench/support/**`
- Naming convention: file names end with `_scenes.cljs`, namespaces end with `-scenes`

Deterministic target selection workflow:
- `/hyperopen/docs/runbooks/browser-live-inspection.md` (Attach to Your Own Browser and Deterministic Tab Identification)

CI workflow reference:
- `/hyperopen/.github/workflows/tests.yml`

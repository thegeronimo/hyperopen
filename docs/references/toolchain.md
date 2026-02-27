---
owner: platform
status: canonical
last_reviewed: 2026-02-13
review_cycle_days: 90
source_of_truth: true
---

# Toolchain and Build Reference

Repository build and test entry points:
- `npm run check`
- `npm test`
- `npm run test:websocket`
- `npm run test:browser-inspection`

Browser inspection and parity commands:
- `npm run browser:inspect -- --url <target-url> --target <label>`
- `npm run browser:compare`
- `npm run browser:mcp`
- `node tools/browser-inspection/src/cli.mjs session targets --attach-port <cdp-port>`
- `node tools/browser-inspection/src/cli.mjs session attach --attach-port <cdp-port>`
- `node tools/browser-inspection/src/cli.mjs session attach --attach-port <cdp-port> --target-id <cdp-target-id>`

Comprehensive tool surface and use guidance:
- `/hyperopen/docs/tools.md`

Deterministic target selection workflow:
- `/hyperopen/docs/runbooks/browser-live-inspection.md` (Attach to Your Own Browser and Deterministic Tab Identification)

CI workflow reference:
- `/hyperopen/.github/workflows/tests.yml`

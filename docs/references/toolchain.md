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

CI workflow reference:
- `/hyperopen/.github/workflows/tests.yml`

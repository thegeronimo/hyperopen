# Tech Debt Tracker

## Purpose
Track known debt with clear owner and retirement path.

## Entries
- Debt summary: Locale-aware numeric parsing now covers audited high-traffic decimal boundaries and low-traffic integer input boundaries. Remaining debt is primarily regression prevention for newly added input surfaces.
  Owner team: Platform
  Impact: Uncovered/new input surfaces can still regress for international users if they bypass locale-aware parsing utilities.
  Retirement criteria: Maintain a recurring boundary audit for user-entered decimal paths, require locale-aware parsing in new numeric input transitions, and keep regression coverage plus gate validation (`npm test`, `npm run check`, `npm run test:websocket`) for each new input feature.
  Tracking reference: `/hyperopen/docs/exec-plans/completed/2026-03-02-international-number-formatting-migration.md`; `/hyperopen/docs/exec-plans/completed/2026-03-02-locale-input-parsing-boundaries-order-and-position-modals.md`; `/hyperopen/docs/exec-plans/completed/2026-03-02-locale-input-parsing-order-form-leverage.md`; `/hyperopen/docs/exec-plans/completed/2026-03-02-locale-input-parsing-currency-helper-standardization.md`; `/hyperopen/docs/exec-plans/completed/2026-03-02-locale-input-parsing-low-traffic-integer-boundaries.md`

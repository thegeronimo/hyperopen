# Tech Debt Tracker

## Purpose
Track known debt with clear owner and retirement path.

## Entries
- Debt summary: Locale-aware numeric parsing is only wired for vault transfer input paths; other user-entered numeric inputs still rely on direct dot-decimal `parseFloat` assumptions.
  Owner team: Platform
  Impact: International users can still hit inconsistent input behavior in non-vault forms when using locale decimal separators.
  Retirement criteria: Introduce shared locale-aware input parsing adapters for remaining user-input boundaries (starting with account-info modals and order-form numeric inputs), add regression tests per boundary, and validate with `npm test`, `npm run check`, and `npm run test:websocket`.
  Tracking reference: `/hyperopen/docs/exec-plans/completed/2026-03-02-international-number-formatting-migration.md`

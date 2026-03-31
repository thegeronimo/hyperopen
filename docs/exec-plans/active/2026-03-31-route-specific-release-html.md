# Route-Specific Release HTML

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, and `/hyperopen/docs/WORK_TRACKING.md`. The tracked `bd` issue for this work is `hyperopen-1xrj`, and `bd` remains the lifecycle source of truth until this plan is moved to `completed`.

## Purpose / Big Picture

Hyperopen's release artifact currently serves one generic HTML shell for every public route, which means `/trade`, `/portfolio`, `/leaderboard`, `/vaults`, `/staking`, `/funding-comparison`, and `/api` all ship the same initial head metadata and the same visible pre-boot HTML. After this change, `out/release-public/` will contain route-specific HTML entry pages for those static routes, each with its own route-specific head tags and one visible loading hero that matches the route users requested while still booting the SPA from the hashed CSS and direct hashed `main` script.

## Progress

- [x] (2026-03-31 18:01Z) Created and claimed `bd` issue `hyperopen-1xrj` for route-specific release HTML generation.
- [x] (2026-03-31 18:01Z) Audited the current release metadata module, generator, tests, and app entry shell to confirm that the artifact still emits one generic HTML shell.
- [x] (2026-03-31 18:44Z) Extended the release metadata source of truth with route-specific SEO and loading-shell content, then generated per-route HTML files in `out/release-public`.
- [x] (2026-03-31 18:55Z) Extended the node tests, ran the requested release-asset validations, and completed the required repo gates.

## Surprises & Discoveries

- Observation: the current release artifact already has a route metadata registry and client-side metadata updater, but it still writes only one `index.html`.
  Evidence: `tools/release-assets/site_metadata.mjs` plus `tools/release-assets/generate_release_artifacts.mjs`.

- Observation: the tracked app entry already swaps the dev bootstrap script out during release generation, so the new route-specific loading shell can be injected into `#app` without changing the vault-only inline boot-shell code path.
  Evidence: `rewriteAppIndexHtml()` in `tools/release-assets/generate_release_artifacts.mjs` replaces the inline manifest bootstrap with a direct hashed `main` script for release output.

## Decision Log

- Decision: keep the per-route SEO metadata and the visible loading-shell copy in the same release metadata source of truth.
  Rationale: titles, descriptions, canonical URLs, and route-specific loading shells all need to stay aligned with the same static routes and canonical lowercase `/api` surface.
  Date/Author: 2026-03-31 / Codex

## Outcomes & Retrospective

`out/release-public/` now contains one static HTML entry per public route for `/`, `/trade`, `/portfolio`, `/leaderboard`, `/vaults`, `/staking`, `/funding-comparison`, and `/api`. Each generated page ships route-specific `<title>`, description, canonical URL, Open Graph fields, Twitter fields, and one visible loading hero inside `#app` while continuing to boot the SPA from the hashed CSS asset and direct hashed main-script include.

`tools/release-assets/generate_release_artifacts.test.mjs` now asserts route-file existence, route-specific title and description differences, canonical/OG route URLs, lowercase `/api`, required root asset copying, and the existing release-sidecar behavior. Validation passed for `npm run test:release-assets`, `node tools/release-assets/generate_release_artifacts.mjs`, `npm run check`, `npm test`, and `npm run test:websocket`.

## Context and Orientation

`tools/release-assets/site_metadata.mjs` currently owns the canonical origin, the static public route list, and the generated sitemap/robots metadata. `tools/release-assets/generate_release_artifacts.mjs` fingerprints CSS, rewrites the HTML shell, writes `site-metadata.json`, `robots.txt`, and `sitemap.xml`, copies the explicit release JS/root assets, and currently emits only `/hyperopen/out/release-public/index.html`. `resources/public/index.html` is still the shared tracked template used by dev and release rewriting, so any release-only HTML injection must keep dev behavior intact.

Cloudflare Pages will serve `/route/index.html` when it exists, so the release artifact should emit one HTML entry for each static public route instead of relying on a single SPA fallback shell. The generated route HTML pages must keep the same hashed CSS and direct hashed `main` script pattern, but they need route-specific head tags and a visible route-specific hero inside the `#app` root so humans and crawlers do not receive the same generic shell everywhere.

## Plan of Work

Extend `tools/release-assets/site_metadata.mjs` so each static public route includes all required SEO fields plus route-specific hero copy. Update `resources/public/index.html` with a release-only app-shell placeholder inside `#app`, and adjust the existing vault boot-mount logic so it only mounts the template when the app root is still empty.

Update `tools/release-assets/generate_release_artifacts.mjs` so it writes one HTML file per static route, not just one root `index.html`. Each generated route HTML must inject route-specific `<title>`, description, canonical, Open Graph tags, Twitter tags, and a route-specific loading shell/hero while still using the same hashed CSS and direct hashed main-script bootstrap. Preserve the sitemap as static routes only, and keep `/api` lowercase in both the metadata and generated HTML.

Extend `tools/release-assets/generate_release_artifacts.test.mjs` so the node tests assert that every route file exists, route-specific titles and descriptions differ, canonical URLs are route-specific and use the canonical origin, and `/api` never regresses to uppercase metadata.

## Concrete Steps

Run from `/Users/barry/.codex/worktrees/dfa4/hyperopen`:

1. `npm run test:release-assets`
2. `node tools/release-assets/generate_release_artifacts.mjs`
3. `npm run check`
4. `npm test`
5. `npm run test:websocket`

Expected observable outcomes:

- `out/release-public/index.html` plus `/trade/index.html`, `/portfolio/index.html`, `/leaderboard/index.html`, `/vaults/index.html`, `/staking/index.html`, `/funding-comparison/index.html`, and `/api/index.html` all exist
- `/trade/index.html` and `/portfolio/index.html` have different titles and descriptions
- the generated canonical URLs use the canonical origin and route-specific paths
- the `/api` route metadata and canonical tags use lowercase `/api`

## Validation and Acceptance

This work is complete when the static public routes each have their own generated HTML entry page in `out/release-public`, each route page has the required unique head tags and one visible H1/paragraph loading shell, and the requested release-asset validations plus the required repo gates pass. Dynamic routes such as `/vaults/:address`, `/portfolio/trader/:address`, and `/trade/:asset` must remain out of sitemap/static-page scope for this task.

## Idempotence and Recovery

The release generator already recreates `out/release-public/`, so rerunning it is safe. The new route-page generation should remain purely build-time and deterministic. If a route HTML assertion fails, update the metadata source of truth and rerun the focused release-assets tests before rerunning broader repo gates.

## Artifacts and Notes

Issue creation:

    bd create "Generate route-specific release HTML entry pages" --description="Extend the release artifact generator so out/release-public includes route-specific HTML entry files for /, /trade, /portfolio, /leaderboard, /vaults, /staking, /funding-comparison, and /api. Each route page must ship unique head metadata, canonical/OG/Twitter tags, and a truthful visible loading shell/hero while preserving the hashed CSS and direct hashed main-script bootstrap." -t feature -p 1 --json

Claim:

    bd update hyperopen-1xrj --claim --json

## Interfaces and Dependencies

`tools/release-assets/site_metadata.mjs` must remain the release metadata source of truth and should export enough route data for both HTML head generation and route-shell generation. `tools/release-assets/generate_release_artifacts.mjs` must keep returning deterministic release output data that the node tests can inspect directly. The shared tracked `resources/public/index.html` template must continue to work in dev mode even after the release-only placeholders are added.

Plan revision note: 2026-03-31 18:01Z - Created the active ExecPlan after auditing the current release metadata/generator path and confirming that the artifact still serves one generic HTML shell for all static public routes.

# Source-Control Release-Public Security Headers

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `/hyperopen/docs/PLANS.md` and `/hyperopen/.agents/PLANS.md`.

Linked live work: `hyperopen-d1hk` ("Source-control release-public security headers and staging verification").

## Purpose / Big Picture

Hyperopen currently generates the production-ready static bundle in `/hyperopen/out/release-public`, but the repository does not also generate the deployment header policy that protects that bundle in Cloudflare Pages-style hosting. After this change, a contributor can run `npm run build` and see a checked-in, deterministic `_headers` artifact in the generated release root, then run a repo-owned verification command against a staging URL and prove that strict script execution policy, anti-framing policy, and cache rules are present before launch.

The user-visible outcome is release hardening rather than a new feature surface. The concrete proof is that the generated release output contains a Cloudflare Pages `_headers` file, the local Pages-style static server serves those headers during Playwright validation, the smoke suite proves the release still loads under that policy, and a staging-verification command fails closed if deployed headers drift.

## Progress

- [x] (2026-04-07 16:44Z) Audited the current release path in `/hyperopen/tools/release-assets/generate_release_artifacts.mjs`, `/hyperopen/tools/release-assets/site_metadata.mjs`, `/hyperopen/resources/public/index.html`, and `/hyperopen/src/hyperopen/startup/runtime.cljs`; confirmed that `out/release-public` exists, `sw.js` is root-scoped, and no checked-in Pages or Workers header policy currently ships with the release artifact.
- [x] (2026-04-07 16:45Z) Created and claimed live `bd` issue `hyperopen-d1hk` for this deployment-hardening work.
- [x] (2026-04-07 16:53Z) Added `/hyperopen/tools/release-assets/security_headers.mjs`, externalized the release metadata sync into `/js/release-route-metadata.js`, and taught `/hyperopen/tools/release-assets/generate_release_artifacts.mjs` to emit `_headers` plus exact immutable-asset rules for the generated release root.
- [x] (2026-04-07 16:56Z) Updated `/hyperopen/tools/playwright/static_server.mjs` to parse `_headers`, extended `/hyperopen/tools/playwright/test/seo.smoke.spec.mjs` with response-header assertions, and switched `/hyperopen/playwright.config.mjs` to build the actual release artifact before serving it.
- [x] (2026-04-07 17:10Z) Added `/hyperopen/tools/release-assets/verify_deployment_headers.mjs` plus tests, updated `/hyperopen/README.md`, installed missing Node dependencies with `npm ci`, and validated the change with `npm run test:release-assets`, focused Playwright smoke, `npm run check`, `npm test`, `npm run test:websocket`, `npm run build`, and a local run of `node tools/release-assets/verify_deployment_headers.mjs http://127.0.0.1:4173`.

## Surprises & Discoveries

- Observation: the release artifact is not fully inline-script-free even though `generate_release_artifacts.mjs` removes the dev bootstrap script from `resources/public/index.html`.
  Evidence: `/hyperopen/tools/release-assets/site_metadata.mjs` currently injects `<script id="hyperopen-site-metadata" type="application/json">` plus an inline metadata-sync script in `buildReleaseSeoHeadMarkup`.

- Observation: a strict `style-src 'self'` policy would break the app immediately because the UI still uses many inline `style` attributes for layout, animation, gradients, and CSS custom-property wiring.
  Evidence: `rg -n "\\:style\\s*\\{" /hyperopen/src/hyperopen` returns many matches across trading, portfolio, staking, vault, and account views.

- Observation: the existing release/performance work intentionally deferred deployment-owned header policy, so this fix should extend the release artifact contract rather than inventing a parallel deployment path.
  Evidence: `/hyperopen/docs/exec-plans/completed/2026-03-16-release-build-performance-leverage-plan.md` records that cache-header policy for `release-public` was deferred under `hyperopen-c2xn`.

- Observation: the Playwright web server was serving a `compile app` artifact, which produced stable-name JS during smoke validation and hid the intended immutable-cache behavior for the real release artifact.
  Evidence: the first focused `seo.smoke.spec.mjs` run failed because `/trade` referenced a control-cached main script; after switching `/hyperopen/playwright.config.mjs` to `npm run build`, the same smoke suite passed and verified hashed CSS plus hashed main JS as immutable assets.

- Observation: the current worktree did not have `node_modules/`, so several validation commands initially failed on missing packages rather than code regressions.
  Evidence: Playwright could not import `@playwright/test`, `npm run check` initially failed in `tools/multi-agent` tests on missing `zod` and `smol-toml`, and `npm run build` initially failed because `tailwindcss` was not installed; a local `npm ci` resolved those environment blockers.

## Decision Log

- Decision: keep the source of truth for Cloudflare Pages headers inside the release-artifact pipeline under `/hyperopen/tools/release-assets/` rather than checking in a manually maintained standalone file under `/hyperopen/resources/public`.
  Rationale: the release generator already owns the exact set of files that ship in `out/release-public`, including route HTML, hashed CSS, hashed JS, root assets, robots, sitemap, and `site-metadata.json`. Generating `_headers` from the same module keeps cache rules aligned with the actual artifact shape and fails closed in tests when the asset topology changes.
  Date/Author: 2026-04-07 / Codex

- Decision: remove avoidable inline release-only scripts instead of weakening the CSP with `'unsafe-inline'` for scripts.
  Rationale: the reported risk is XSS and same-origin script compromise reaching browser-stored agent keys. Script execution policy is the control that matters most here, so the release HTML should become compatible with `script-src 'self'` and the repo should keep inline-script exceptions out of the Pages policy.
  Date/Author: 2026-04-07 / Codex

- Decision: validate the generated `_headers` file locally by serving it through the repo's Pages-style static server and asserting the response headers in Playwright, then provide a second command that verifies a deployed staging URL.
  Rationale: local validation prevents regressions in the artifact contract, while staging verification proves that the deployment still honors the policy after upload. Both are required because the finding is about drift or omission between repo intent and deployed behavior.
  Date/Author: 2026-04-07 / Codex

- Decision: use `npm run build` as the Playwright web-server build step instead of a faster `shadow-cljs compile` sequence.
  Rationale: this work is about the shipping release artifact, not a compile-time approximation. Building the actual release root ensures browser smoke checks observe the same hashed CSS/JS filenames and `_headers` cache rules that staging and production will use.
  Date/Author: 2026-04-07 / Codex

## Outcomes & Retrospective

Implementation is complete. `/hyperopen/tools/release-assets/security_headers.mjs` now owns the Cloudflare Pages `_headers` contract, `/hyperopen/tools/release-assets/generate_release_artifacts.mjs` emits that file on every build, and `/hyperopen/tools/release-assets/site_metadata.mjs` no longer depends on executable inline HTML script for route metadata synchronization. The generated release root now includes `_headers`, a stable same-origin `release-route-metadata.js`, route HTML that is compatible with `script-src 'self'`, and explicit immutable-cache rules only for fingerprinted CSS/JS assets.

The validation result is strong. The focused release-assets suite passes, the Pages-style local smoke suite passes while asserting response headers, the repo's required gates (`npm run check`, `npm test`, and `npm run test:websocket`) all pass, `npm run build` succeeds, and the new deployment-verification command succeeds against a local Pages-style server serving the built release artifact. Complexity is lower overall because deployment hardening moved from dashboard-only drift risk into the same deterministic release pipeline, local server, smoke tests, and command surface that already define what a valid shipped artifact looks like.

## Context and Orientation

The production-like artifact path in this repository is `/hyperopen/out/release-public`. It is built by `npm run build`, which compiles the ClojureScript entrypoints and then runs `/hyperopen/tools/release-assets/generate_release_artifacts.mjs`. That generator fingerprints CSS, chooses the hashed `main` JS module from Shadow's manifest, rewrites route HTML, copies the root assets declared by `/hyperopen/tools/release-assets/site_metadata.mjs`, and writes `robots.txt`, `sitemap.xml`, and `site-metadata.json`.

Cloudflare Pages recognizes a plain-text file named `_headers` in the published static root. That file maps URL patterns to response headers. In this repository, that means the generated release root can own the Pages header policy without a separate dashboard-only step. The same release root is also what Playwright serves through `/hyperopen/tools/playwright/static_server.mjs`, so that local static server is the right place to simulate Pages header behavior during smoke tests.

The report that triggered this plan points at two existing risk multipliers. First, `/hyperopen/src/hyperopen/startup/runtime.cljs` registers `/sw.js`, which makes the shipped service worker root-scoped. Second, the app stores sensitive trading-adjacent state in browser storage elsewhere in the repo. If deployment headers drift, an otherwise limited DOM injection or same-origin script compromise becomes materially worse. The fix therefore needs three concrete controls in-repo: a strict-enough Content Security Policy that forbids arbitrary script execution, anti-framing protection so the app cannot be embedded and clickjacked, and explicit cache rules so HTML, service worker, metadata, and stable-name control assets are not accidentally made immutable while the hashed assets can still cache aggressively.

The current release HTML also matters. `/hyperopen/resources/public/index.html` still contains a dev bootstrap inline script, but release rewriting replaces that block with a direct hashed script include. However, `/hyperopen/tools/release-assets/site_metadata.mjs` currently injects another inline script for route metadata synchronization. That must be externalized or otherwise eliminated before the release CSP can require `script-src 'self'`.

## Plan of Work

First, add a release-header module under `/hyperopen/tools/release-assets/` that owns the Cloudflare Pages `_headers` content. That module must define the security policy in plain data and export helpers that generate the final `_headers` text plus any supporting assets needed by the release HTML. The policy should include:

1. A document policy for HTML routes and SPA-style route prefixes that sets `Content-Security-Policy`, `X-Frame-Options`, `X-Content-Type-Options`, and `Referrer-Policy`. The CSP must keep `script-src 'self'`, `object-src 'none'`, `base-uri 'self'`, `form-action 'self'`, and `frame-ancestors 'none'`. It must also allow the app's real network dependencies through `connect-src` and `img-src` without using wildcard hosts.
2. Cache rules that treat route HTML, `site-metadata.json`, `robots.txt`, `sitemap.xml`, `module-loader.json`, stable-name workers, and `sw.js` as revalidated control assets.
3. Cache rules that treat hashed `/css/main.*.css` and hashed JS module outputs as immutable assets.

Second, remove the remaining inline release-only metadata script from `/hyperopen/tools/release-assets/site_metadata.mjs`. The lowest-risk path is to generate a small external same-origin script in the release artifact and reference it from the rewritten HTML, or to otherwise restructure the metadata handoff so the release HTML no longer needs executable inline code. Keep the public behavior the same: direct document requests for `/`, `/trade`, `/portfolio`, `/leaderboard`, `/vaults`, `/staking`, `/funding-comparison`, and `/api` must still ship route-specific SEO tags, and client-side navigation must still keep those tags synchronized.

Third, update `/hyperopen/tools/release-assets/generate_release_artifacts.mjs` and its tests so `generateReleaseArtifacts` writes `_headers` into `out/release-public` every time. The tests in `/hyperopen/tools/release-assets/generate_release_artifacts.test.mjs` should fail closed if `_headers` is missing, if the policy omits the route HTML rules, or if the cache rules stop matching the artifact shape.

Fourth, update `/hyperopen/tools/playwright/static_server.mjs` so it parses the generated `_headers` file and applies the matching headers when serving local release output. Then extend `/hyperopen/tools/playwright/test/seo.smoke.spec.mjs` with deterministic assertions that the response for `/trade` carries the CSP and anti-framing headers and that immutable versus revalidated cache rules appear on representative assets such as hashed CSS, hashed JS, and `sw.js`.

Fifth, add a staging-verification command under `/hyperopen/tools/release-assets/` or a nearby repo-owned location. That command must accept a deployment origin, request the representative URLs from the deployed site, and exit non-zero if the expected header contract is not present. It should verify both HTML and asset paths, because a deployment can partially honor `_headers` while still drifting on caching or service-worker headers.

Finally, document the workflow in `/hyperopen/README.md` and this plan. The release section should say that `npm run build` now emits `_headers`, that local smoke tests exercise those headers, and that staging verification must run against the real deployment origin before launch.

## Concrete Steps

Work from `/hyperopen`.

1. Implement the generator and release-HTML cleanup.

       npm run test:release-assets

   Expect the new release-assets assertions to pass and to include `_headers` in the generated fixture output.

2. Exercise the Pages-style static server and smoke suite against `out/release-public`.

       npx playwright test --grep "@smoke"

   Expect the smoke suite to prove both route metadata and response-header behavior.

3. Run the required repository gates after code changes settle.

       npm run check
       npm test
       npm run test:websocket

4. Verify a deployed environment before launch by passing its origin to the new command.

       HYPEROPEN_VERIFY_ORIGIN="https://staging.hyperopen.example" npm run verify:deployment-headers

   Expect a zero exit status plus a short summary of the verified HTML and asset endpoints. A missing CSP, anti-framing header, or cache mismatch must produce a non-zero exit and a clear error message naming the failing URL and header.

## Validation and Acceptance

This change is complete when all of the following are true:

- `npm run build` writes `/hyperopen/out/release-public/_headers` alongside the existing route HTML, CSS, JS, `robots.txt`, `sitemap.xml`, `site-metadata.json`, and root assets.
- The generated release HTML no longer requires executable inline script in order to bootstrap route metadata updates, so the document policy can keep `script-src 'self'`.
- The local Pages-style static server serves the generated headers and the committed Playwright smoke coverage proves those headers exist on representative responses.
- `npm run check`, `npm test`, and `npm run test:websocket` all pass.
- The staging-verification command fails closed when expected headers are absent and succeeds when they are present.

Acceptance should be readable from both local and deployed evidence. Locally, the `_headers` file contents and Playwright response-header assertions prove the artifact contract. On staging, the verification command proves that the deployment has not silently dropped or altered the policy.

## Idempotence and Recovery

The release generator already removes and recreates `/hyperopen/out/release-public`, so regenerating the artifact is safe and expected. The header generator must remain deterministic: rerunning `npm run build` without source changes should rewrite the same `_headers` content. If a CSP change breaks a route, fix the release HTML or header source of truth and rerun the same focused validation commands; do not hand-edit generated files under `out/`.

The staging-verification command must be read-only. If it fails, the recovery path is to fix the repo-owned `_headers` generation or the deployment configuration and redeploy, then rerun the same verification command against the same origin.

## Artifacts and Notes

Initial evidence captured before implementation:

    rg -n "generate_release_artifacts|startup/runtime|_headers|wrangler\\.toml|wrangler\\.json|wrangler\\.jsonc|Content-Security-Policy" /hyperopen
    # confirmed release generator and service-worker registration, but no checked-in deployment header policy

    bd create "Source-control release-public security headers and staging verification" ... --json
    # created hyperopen-d1hk

Final validation evidence:

    npm run test:release-assets
    # 13 tests passed, including _headers generation and deployment-verifier tests

    npx playwright test tools/playwright/test/seo.smoke.spec.mjs --grep @smoke
    # 6 smoke tests passed against out/release-public after switching Playwright to npm run build

    npm run check
    # passed

    npm test
    # Ran 3062 tests containing 16320 assertions. 0 failures, 0 errors.

    npm run test:websocket
    # Ran 433 tests containing 2478 assertions. 0 failures, 0 errors.

    npm run build
    # Generated release artifacts in /hyperopen/out/release-public with fingerprinted CSS

    PLAYWRIGHT_STATIC_ROOT=out/release-public PLAYWRIGHT_WEB_PORT=4173 node tools/playwright/static_server.mjs
    node tools/release-assets/verify_deployment_headers.mjs http://127.0.0.1:4173
    # verified /trade plus fingerprinted CSS, fingerprinted main JS, release-route-metadata.js, site-metadata.json, and sw.js
Plan revision note: updated on 2026-04-07 after implementation and validation to record the final generator, local Pages-style verification path, dependency-setup discovery, and successful gate results before moving this ExecPlan out of `active`.

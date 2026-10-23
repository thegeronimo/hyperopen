# Hyperopen Theming

## Purpose
This document defines how Hyperopen themes work: the semantic token vocabulary, the single source of truth, how each styling layer consumes tokens, and the exact checklist for adding a new theme. The execution history and remaining migration phases live in `docs/exec-plans/active/2026-06-10-ui-theming-foundation.md`.

## How theming works

One attribute drives everything: `data-theme` on `<html>`.

- `resources/public/index.html` ships `data-theme="dark"` (the default HyperOpen look) and a tiny pre-paint script that re-applies the stored preference before first render.
- `hyperopen.ui.preferences/restore-ui-theme-preference!` restores the preference at startup (localStorage key `hyperopen-ui-theme`) and mirrors it into app state at `[:ui :theme]`.
- `[:actions/set-ui-theme "<id>"]` switches at runtime (Settings → Appearance). It saves state, persists the preference, and emits `:effects/apply-ui-theme`, which sets the attribute.
- daisyui (`themeRoot: ":root"`) and the `--ho-*` token blocks both key off the same attribute, so daisyui components and custom utilities switch together.

## Single source of truth

`src/styles/themes/palette.js` defines every theme: label, color tokens, chart tokens, radius scale, font overrides, and the daisyui mapping. Three consumers derive from it — never hand-edit the derived artifacts:

| Consumer | Artifact | Sync mechanism |
| --- | --- | --- |
| CSS generator | `src/styles/themes/<id>.css` (committed) | `node tools/styles/generate_theme_css.mjs`; drift fails `npm run test:styles` |
| Tailwind | `ho-*` color utilities, `trading-*` aliases, `borderRadius` scale, daisyui themes | `tailwind.config.js` requires `palette.js` at build |
| ClojureScript catalog | `src/hyperopen/ui/theme.cljs` (ids + labels for the picker) | drift test greps the catalog in `tools/styles/theme_css_sync.test.mjs` |

Token value formats:

- **Color tokens** are RGB channel triplets (`--ho-bg: 15 26 31`) so Tailwind opacity modifiers work: `bg-ho-bg/80` → `rgb(var(--ho-bg) / 0.8)`. In plain CSS write `rgb(var(--ho-bg))` or `rgb(var(--ho-bg) / 0.8)`.
- **Chart tokens** (`--ho-chart-*`) are complete CSS color strings (alpha baked in) because ClojureScript chart code reads them with `getComputedStyle` and hands them to lightweight-charts verbatim.
- **Radius tokens** (`--ho-radius*`) are lengths mapped onto the Tailwind `rounded-*` scale; default theme values equal Tailwind defaults.
- **Font overrides** are optional per theme (`--font-ui`, `--font-mono`). A theme font override intentionally wins over the `data-ui-font` user preference.

## Token vocabulary

Names describe role, not color. Default-theme values are the exact hexes the app used before tokenization (pixel-identical default).

| Token | Default | Role |
| --- | --- | --- |
| `--ho-bg` | `#0f1a1f` | App background (`trading-bg`, daisy `base-100`) |
| `--ho-bg-deep` | `#06131a` | Recessed areas, table heads, wells |
| `--ho-surface` | `#1b2429` | Cards, popovers, dropdowns |
| `--ho-surface-raised` | `#273035` | Hover rows, raised chips |
| `--ho-border` | `#30363d` | Default hairline (`trading-border`, daisy `base-300`) |
| `--ho-text` | `#f6fefd` | Primary copy |
| `--ho-text-hi` | `#ffffff` | High-emphasis text (`trading-text`) |
| `--ho-text-secondary` | `#8b949e` | Secondary copy (`trading-text-secondary`) |
| `--ho-text-muted` | `#8a96a6` | Labels, hints |
| `--ho-text-dim` | `#6f7a88` | Faint hints, disabled |
| `--ho-accent` | `#50d2c1` | Brand accent (links, active tabs) |
| `--ho-accent-hi` | `#66e3c5` | Accent hover / spinners |
| `--ho-accent-bright` | `#97fce4` | Brightest accent text on tinted fills |
| `--ho-accent-soft` | `#0d3a35` | Accent-tinted fill (selected/primary chips) |
| `--ho-accent-soft-hi` | `#115046` | Hover state of `accent-soft` |
| `--ho-border-accent` | `#1f3b3c` | Accent-tinted hairline |
| `--ho-border-accent-muted` | `#17313d` | Subtler accent hairline |
| `--ho-buy` | `#00d4aa` | Buy/long/positive (`trading-green`, daisy `primary`/`success`) |
| `--ho-sell` | `#ff6b6b` | Sell/short/negative (`trading-red`, daisy `accent`/`error`) |
| `--ho-sell-hi` | `#ed7088` | Sell hover/bright |
| `--ho-sell-tint` | `#f2b8c5` | Text on sell-tinted fills |
| `--ho-sell-soft` | `#3a1b22` | Sell/error-tinted fill |
| `--ho-sell-soft-deep` | `#2b1118` | Deeper sell/error fill |
| `--ho-border-sell` | `#7b3340` | Sell/error-tinted hairline |
| `--ho-warn` | `#fbbd23` | Warning (daisy `warning`) |
| `--ho-info` | `#3abff8` | Info (daisy `info`) |

Chart tokens: `--ho-chart-bg`, `--ho-chart-text`, `--ho-chart-grid`, `--ho-chart-grid-strong`, `--ho-chart-grid-soft`, `--ho-chart-border-soft`, `--ho-chart-separator`, `--ho-chart-separator-hover`, `--ho-chart-up`, `--ho-chart-down` (volume fills), `--ho-chart-long`, `--ho-chart-short` (the theme's solid directional pair: position markers AND candle-family series bodies/wicks), `--ho-chart-liquidation` (amber liquidation-fill markers in the trades-on-chart layer). A mounted chart restyles live on theme switch via a `data-theme` MutationObserver in the chart runtime.

Radius tokens: `--ho-radius-sm`, `--ho-radius`, `--ho-radius-md`, `--ho-radius-lg`, `--ho-radius-xl`, `--ho-radius-2xl`, `--ho-radius-3xl` (mapped to `rounded-sm` … `rounded-3xl`; `rounded-full` stays unthemed).

## Using tokens in code

- In views, prefer semantic utilities: `bg-ho-surface`, `text-ho-text-muted`, `border-ho-border-accent`, `bg-ho-accent-soft/55`, `hover:bg-ho-accent-soft-hi`. The `trading-*` utilities remain valid aliases of their tokens.
- daisyui utilities (`bg-base-100`, `border-base-300`, `text-primary`, `btn`, `badge`, `toggle`) are theme-aware automatically.
- In `src/styles/surfaces/*.css`, write `rgb(var(--ho-token))` / `rgb(var(--ho-token) / a)`. Exception: `optimizer.css` keeps its scoped `--o-*` system (see ARCHITECTURE.md).
- In ClojureScript chart/canvas code, read tokens through `hyperopen.views.trading-chart.utils.chart-options/theme-color` (falls back to the default palette when no DOM is present, e.g. in tests).
- Do not add new raw color literals (`#hex`, `rgb(...)`) to views or style surfaces. `npm run lint:theme-colors` ratchets the per-file counts in `dev/theme_color_baseline.edn` downward; it fails any file whose count rises.

## Adding a new theme

1. Add an entry to `src/styles/themes/palette.js` (copy an existing theme, change `label`, `colors`, `chart`, optionally `radius`, `fonts`, `daisy`). Every color token key is required; the generator fails on missing keys.
2. Run `node tools/styles/generate_theme_css.mjs` to write `src/styles/themes/<id>.css`, and add the import to `src/styles/main.css` plus the manifest in `tools/styles/main_css_split.test.mjs`.
3. Add `{:id "<id>" :label "<Label>"}` to `hyperopen.ui.theme/themes` (`src/hyperopen/ui/theme.cljs`). Order defines picker order.
4. Run `npm run test:styles` (sync + manifest), `npm test`, and `npm run css:build`.
5. QA the trade route, portfolio, and modals under the new theme (`npm run dev`, Settings → Appearance). Long-tail surfaces still carrying hardcoded colors are listed in `dev/theme_color_baseline.edn` — expect those to keep default-theme colors until the consolidation pass migrates them.

## Scope boundaries

- Themes change tokens (color, type, radius). Layout, density, copy, and illustrations are not theme concerns.
- Copy is *voiced*, not themed: `hyperopen.ui.voice` maps label keys to per-voice strings, and the active voice derives from the active theme (`hyperdegen` → `:degen`, everything else → the canonical `:default`). Tokens never carry copy; the voice catalog never carries colors. The catalog's `:default` strings are sync-tested against the surfaces that own them (header nav, account-info tab registry).
- The portfolio optimizer keeps its scoped `--o-*` visual system.
- The chart picks up theme tokens when its options are (re)applied — on creation, asset/timeframe change, or reload. Live re-theme of a mounted chart is a tracked follow-up.

/**
 * Single source of truth for Hyperopen themes.
 *
 * Consumers (never hand-edit the derived artifacts):
 * - tools/styles/generate_theme_css.mjs -> src/styles/themes/<id>.css
 * - tailwind.config.js -> ho-* utilities, trading-* aliases, radius scale, daisyui themes
 * - src/hyperopen/ui/theme.cljs mirrors ids/labels (drift-tested by
 *   tools/styles/theme_css_sync.test.mjs)
 *
 * Token formats: `colors` are hex (emitted as RGB channel triplets for
 * Tailwind alpha support), `chart` are complete CSS color strings (read by
 * ClojureScript at runtime), `radius` are CSS lengths, `fonts` are optional
 * CSS font-family overrides. See docs/THEMING.md.
 */

const TAILWIND_DEFAULT_RADIUS = {
  sm: "0.125rem",
  DEFAULT: "0.25rem",
  md: "0.375rem",
  lg: "0.5rem",
  xl: "0.75rem",
  "2xl": "1rem",
  "3xl": "1.5rem",
};

const COLOR_TOKEN_KEYS = [
  "bg",
  "bg-deep",
  "surface",
  "surface-raised",
  "border",
  "text",
  "text-hi",
  "text-secondary",
  "text-muted",
  "text-dim",
  "accent",
  "accent-hi",
  "accent-bright",
  "accent-soft",
  "accent-soft-hi",
  "border-accent",
  "border-accent-muted",
  "buy",
  "sell",
  "sell-hi",
  "sell-tint",
  "sell-soft",
  "sell-soft-deep",
  "border-sell",
  "warn",
  "info",
];

const CHART_TOKEN_KEYS = [
  "bg",
  "text",
  "grid",
  "grid-strong",
  "grid-soft",
  "border-soft",
  "separator",
  "separator-hover",
  "up",
  "down",
  "long",
  "short",
  "liquidation",
];

const themes = {
  // Default HyperOpen look. Values are the exact hexes the app used before
  // tokenization; changing one changes the default theme's rendering.
  dark: {
    label: "HyperLiquid",
    colors: {
      bg: "#0f1a1f",
      "bg-deep": "#06131a",
      surface: "#1b2429",
      "surface-raised": "#273035",
      border: "#30363d",
      text: "#f6fefd",
      "text-hi": "#ffffff",
      "text-secondary": "#8b949e",
      "text-muted": "#8a96a6",
      "text-dim": "#6f7a88",
      accent: "#50d2c1",
      "accent-hi": "#66e3c5",
      "accent-bright": "#97fce4",
      "accent-soft": "#0d3a35",
      "accent-soft-hi": "#115046",
      "border-accent": "#1f3b3c",
      "border-accent-muted": "#17313d",
      buy: "#00d4aa",
      sell: "#ff6b6b",
      "sell-hi": "#ed7088",
      "sell-tint": "#f2b8c5",
      "sell-soft": "#3a1b22",
      "sell-soft-deep": "#2b1118",
      "border-sell": "#7b3340",
      warn: "#fbbd23",
      info: "#3abff8",
    },
    chart: {
      bg: "rgb(15, 26, 31)",
      text: "#e5e7eb",
      grid: "#374151",
      "grid-strong": "#4b5563",
      "grid-soft": "rgba(139, 148, 158, 0.16)",
      "border-soft": "rgba(139, 148, 158, 0.24)",
      separator: "rgba(139, 148, 158, 0.22)",
      "separator-hover": "rgba(139, 148, 158, 0.30)",
      up: "rgba(34, 171, 148, 0.5)",
      down: "rgba(247, 82, 95, 0.5)",
      long: "#26a69a",
      short: "#ef5350",
      liquidation: "#f5a524",
    },
    radius: TAILWIND_DEFAULT_RADIUS,
    fonts: {},
    daisy: {
      primary: "#00d4aa",
      secondary: "#8b949e",
      accent: "#ff6b6b",
      neutral: "#0f1a1f",
      "base-100": "#0f1a1f",
      "base-200": "#0f1a1f",
      "base-300": "#30363d",
      info: "#3abff8",
      success: "#00d4aa",
      warning: "#fbbd23",
      error: "#ff6b6b",
    },
  },

  // Institutional terminal look from the 2026-06 design handoff: near-black
  // base, hairline borders, cyan-teal accent, green/red signal pair, square
  // corners, JetBrains Mono data type. See
  // docs/exec-plans/active/2026-06-10-institutional-theme-buildout.md.
  institutional: {
    label: "Institutional",
    colors: {
      bg: "#07090b",
      "bg-deep": "#05070a",
      surface: "#0d1215",
      "surface-raised": "#121a1e",
      border: "#1a2328",
      text: "#d8e1e6",
      "text-hi": "#ffffff",
      "text-secondary": "#7b8a92",
      "text-muted": "#7b8a92",
      "text-dim": "#4d5a60",
      accent: "#3ddbc4",
      "accent-hi": "#5ce8d4",
      "accent-bright": "#a8f4e9",
      "accent-soft": "#102624",
      "accent-soft-hi": "#163632",
      "border-accent": "#1f6b62",
      "border-accent-muted": "#17453f",
      buy: "#4ade80",
      sell: "#f87171",
      // The design has no brightened-sell distinction; collapsing sell-hi onto
      // sell keeps token-driven ask-side surfaces (orderbook prices/bars) on
      // the design's single red.
      "sell-hi": "#f87171",
      "sell-tint": "#fecaca",
      "sell-soft": "#2e151b",
      "sell-soft-deep": "#1f0d12",
      "border-sell": "#4a1a22",
      warn: "#fbbf24",
      info: "#38bdf8",
    },
    chart: {
      bg: "rgb(7, 9, 11)",
      text: "#7b8a92",
      grid: "#1a2328",
      "grid-strong": "#263238",
      "grid-soft": "rgba(123, 138, 146, 0.12)",
      "border-soft": "rgba(123, 138, 146, 0.20)",
      separator: "rgba(123, 138, 146, 0.18)",
      "separator-hover": "rgba(123, 138, 146, 0.28)",
      up: "rgba(74, 222, 128, 0.35)",
      down: "rgba(248, 113, 113, 0.35)",
      long: "#4ade80",
      short: "#f87171",
      liquidation: "#f59e0b",
    },
    radius: {
      sm: "0px",
      DEFAULT: "0px",
      md: "0px",
      lg: "0px",
      xl: "0px",
      "2xl": "0px",
      "3xl": "0px",
    },
    fonts: {
      ui: "var(--font-mono)",
      mono: '"JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace',
    },
    daisy: {
      primary: "#4ade80",
      secondary: "#7b8a92",
      accent: "#f87171",
      neutral: "#07090b",
      "base-100": "#07090b",
      "base-200": "#0b0f12",
      "base-300": "#1a2328",
      info: "#38bdf8",
      success: "#4ade80",
      warning: "#fbbf24",
      error: "#f87171",
      "--rounded-box": "0",
      "--rounded-btn": "0",
      "--rounded-badge": "0",
      "--tab-radius": "0",
    },
  },

  // Meme-terminal look matching the HyperDegen reference prototype: the
  // prototype reuses the default green/red signal hexes on a near-black
  // blue-teal base, adds gold/blue accents, mono-first type, and tight
  // 4-8px chrome. Parody copy/widgets are an experience layer, not tokens.
  hyperdegen: {
    label: "HyperDegen",
    colors: {
      bg: "#07090a",
      "bg-deep": "#040609",
      surface: "#0d1418",
      "surface-raised": "#17232b",
      border: "#1d2b32",
      text: "#e6efec",
      "text-hi": "#ffffff",
      "text-secondary": "#8b949e",
      "text-muted": "#7a8791",
      "text-dim": "#5b6772",
      accent: "#00d4aa",
      "accent-hi": "#2ee6bd",
      "accent-bright": "#9ff2e0",
      "accent-soft": "#0c332c",
      "accent-soft-hi": "#114a3f",
      "border-accent": "#1c5a4c",
      "border-accent-muted": "#153f38",
      buy: "#00d4aa",
      sell: "#ff6b6b",
      "sell-hi": "#ff8a8a",
      "sell-tint": "#ffc6c6",
      "sell-soft": "#3a1818",
      "sell-soft-deep": "#281010",
      "border-sell": "#7b3636",
      warn: "#ffd23f",
      info: "#62a8ff",
    },
    chart: {
      bg: "rgb(7, 9, 10)",
      text: "#dce6e1",
      grid: "#18242b",
      "grid-strong": "#1d2b32",
      "grid-soft": "rgba(139, 148, 158, 0.14)",
      "border-soft": "rgba(139, 148, 158, 0.22)",
      separator: "rgba(139, 148, 158, 0.20)",
      "separator-hover": "rgba(139, 148, 158, 0.28)",
      up: "rgba(0, 212, 170, 0.5)",
      down: "rgba(255, 107, 107, 0.5)",
      long: "#00d4aa",
      short: "#ff6b6b",
      liquidation: "#ffb020",
    },
    radius: {
      sm: "0.25rem",
      DEFAULT: "0.375rem",
      md: "0.5rem",
      lg: "0.5rem",
      xl: "0.625rem",
      "2xl": "0.75rem",
      "3xl": "1rem",
    },
    fonts: {
      ui: "var(--font-mono)",
    },
    daisy: {
      primary: "#00d4aa",
      secondary: "#8b949e",
      accent: "#ff6b6b",
      neutral: "#07090a",
      "base-100": "#07090a",
      "base-200": "#07090a",
      "base-300": "#1d2b32",
      info: "#62a8ff",
      success: "#00d4aa",
      warning: "#ffd23f",
      error: "#ff6b6b",
      "--rounded-box": "0.5rem",
      "--rounded-btn": "0.375rem",
      "--rounded-badge": "1.9rem",
      "--tab-radius": "0.25rem",
    },
  },
};

const themeOrder = ["dark", "institutional", "hyperdegen"];
const defaultTheme = "dark";

function hexToChannels(hex) {
  const normalized = hex.replace("#", "");
  const expanded =
    normalized.length === 3
      ? normalized
          .split("")
          .map((c) => c + c)
          .join("")
      : normalized;
  if (!/^[0-9a-fA-F]{6}$/.test(expanded)) {
    throw new Error(`palette: expected 3/6-digit hex color, got "${hex}"`);
  }
  const r = parseInt(expanded.slice(0, 2), 16);
  const g = parseInt(expanded.slice(2, 4), 16);
  const b = parseInt(expanded.slice(4, 6), 16);
  return `${r} ${g} ${b}`;
}

function assertCompleteTheme(id, theme) {
  for (const key of COLOR_TOKEN_KEYS) {
    if (!theme.colors[key]) {
      throw new Error(`palette: theme "${id}" missing color token "${key}"`);
    }
  }
  for (const key of CHART_TOKEN_KEYS) {
    if (!theme.chart[key]) {
      throw new Error(`palette: theme "${id}" missing chart token "${key}"`);
    }
  }
  for (const key of Object.keys(TAILWIND_DEFAULT_RADIUS)) {
    if (!theme.radius[key]) {
      throw new Error(`palette: theme "${id}" missing radius token "${key}"`);
    }
  }
}

for (const id of themeOrder) {
  if (!themes[id]) {
    throw new Error(`palette: themeOrder lists unknown theme "${id}"`);
  }
  assertCompleteTheme(id, themes[id]);
}

module.exports = {
  themes,
  themeOrder,
  defaultTheme,
  COLOR_TOKEN_KEYS,
  CHART_TOKEN_KEYS,
  hexToChannels,
};

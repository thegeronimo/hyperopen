/** @type {import('tailwindcss').Config} */
const HYPERLIQUID_BG = "#0f1a1f";

module.exports = {
  content: ["./src/**/*.{cljs,clj}", "./resources/public/**/*.html"],
  theme: {
    extend: {
      fontSize: {
        xs: ["12px", { lineHeight: "16px" }],
        sm: ["12px", { lineHeight: "16px" }],
        m: ["13px", { lineHeight: "17px" }],
      },
      colors: {
        // Custom colors for trading interface
        "trading-bg": HYPERLIQUID_BG,
        "trading-surface": HYPERLIQUID_BG,
        "trading-border": "#30363d",
        "trading-green": "#00d4aa",
        "trading-red": "#ff6b6b",
        "trading-text": "#ffffff",
        "trading-text-secondary": "#8b949e",
      },
      fontFamily: {
        mono: ["JetBrains Mono", "Fira Code", "monospace"],
        splash: ["Splash", "ui-sans-serif", "system-ui", "sans-serif"],
      },
    },
  },
  plugins: [
    require("daisyui"),
    require("@tailwindcss/forms"),
    require("@tailwindcss/typography"),
  ],
  daisyui: {
    themes: [
      {
        dark: {
          ...require("daisyui/src/theming/themes")["dark"],
          primary: "#00d4aa",
          secondary: "#8b949e",
          accent: "#ff6b6b",
          neutral: HYPERLIQUID_BG,
          "base-100": HYPERLIQUID_BG,
          "base-200": HYPERLIQUID_BG,
          "base-300": "#30363d",
          info: "#3abff8",
          success: "#00d4aa",
          warning: "#fbbd23",
          error: "#ff6b6b",
        },
      },
    ],
    darkTheme: "dark",
    base: true,
    styled: true,
    utils: true,
    prefix: "",
    logs: true,
    themeRoot: ":root",
  },
};

/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ["./src/**/*.{cljs,clj}", "./resources/public/**/*.html"],
  theme: {
    extend: {
      colors: {
        // Custom colors for trading interface
        "trading-bg": "#0b0e11",
        "trading-surface": "#161a1e",
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
          neutral: "#161a1e",
          "base-100": "#0b0e11",
          "base-200": "#161a1e",
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

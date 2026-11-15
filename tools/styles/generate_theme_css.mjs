import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
  "..",
);
const themesDir = path.join(repoRoot, "src", "styles", "themes");
const palette = require(path.join(themesDir, "palette.js"));

const RADIUS_VAR_BY_KEY = {
  sm: "--ho-radius-sm",
  DEFAULT: "--ho-radius",
  md: "--ho-radius-md",
  lg: "--ho-radius-lg",
  xl: "--ho-radius-xl",
  "2xl": "--ho-radius-2xl",
  "3xl": "--ho-radius-3xl",
};

const FONT_VAR_BY_KEY = {
  ui: "--font-ui",
  mono: "--font-mono",
};

export function themeCssText(id) {
  const theme = palette.themes[id];
  if (!theme) {
    throw new Error(`unknown theme "${id}"`);
  }
  const selector =
    id === palette.defaultTheme
      ? `:root,\n:root[data-theme="${id}"]`
      : `:root[data-theme="${id}"]`;
  const lines = [];
  lines.push("/* GENERATED FILE - do not edit by hand.");
  lines.push(" * Source: src/styles/themes/palette.js");
  lines.push(" * Regenerate: node tools/styles/generate_theme_css.mjs");
  lines.push(" */");
  lines.push(`${selector} {`);
  lines.push("  /* Color tokens: RGB channel triplets (Tailwind alpha). */");
  for (const key of palette.COLOR_TOKEN_KEYS) {
    lines.push(`  --ho-${key}: ${palette.hexToChannels(theme.colors[key])};`);
  }
  lines.push("  /* Chart tokens: complete CSS colors, read from cljs. */");
  for (const key of palette.CHART_TOKEN_KEYS) {
    lines.push(`  --ho-chart-${key}: ${theme.chart[key]};`);
  }
  lines.push("  /* Radius tokens: mapped onto the Tailwind rounded scale. */");
  for (const [key, varName] of Object.entries(RADIUS_VAR_BY_KEY)) {
    lines.push(`  ${varName}: ${theme.radius[key]};`);
  }
  const fontEntries = Object.entries(theme.fonts ?? {});
  if (fontEntries.length > 0) {
    lines.push("  /* Theme typography overrides. */");
    for (const [key, value] of fontEntries) {
      const varName = FONT_VAR_BY_KEY[key];
      if (!varName) {
        throw new Error(`theme "${id}" has unknown font key "${key}"`);
      }
      lines.push(`  ${varName}: ${value};`);
    }
  }
  lines.push("}");
  lines.push("");
  return lines.join("\n");
}

export function generatedFiles() {
  return palette.themeOrder.map((id) => ({
    id,
    file: path.join(themesDir, `${id}.css`),
    css: themeCssText(id),
  }));
}

export function writeThemeCss() {
  for (const { file, css } of generatedFiles()) {
    fs.writeFileSync(file, css);
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  writeThemeCss();
  for (const { file } of generatedFiles()) {
    console.log(`wrote ${path.relative(repoRoot, file)}`);
  }
}

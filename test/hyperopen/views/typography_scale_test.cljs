(ns hyperopen.views.typography-scale-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]))

(def fs (js/require "fs"))
(def path (js/require "path"))

(def forbidden-small-text-pattern #"text-\[(10|11|13|14|15)px\]")

(defn- project-root []
  (.cwd js/process))

(defn- join-path [& parts]
  (reduce (fn [acc part]
            (.join path acc part))
          (first parts)
          (rest parts)))

(defn- read-text [file-path]
  (.readFileSync fs file-path "utf8"))

(defn- relative-path [file-path]
  (.relative path (project-root) file-path))

(defn- cljs-files-under [dir-path]
  (let [entries (.readdirSync fs dir-path #js {:withFileTypes true})]
    (->> (array-seq entries)
         (mapcat (fn [entry]
                   (let [entry-name (.-name entry)
                         entry-path (.join path dir-path entry-name)]
                     (cond
                       (.isDirectory entry) (cljs-files-under entry-path)
                       (and (.isFile entry) (.endsWith entry-name ".cljs")) [entry-path]
                       :else []))))
         sort
         vec)))

(defn- forbidden-small-text-violations []
  (let [views-dir (join-path (project-root) "src" "hyperopen" "views")]
    (->> (cljs-files-under views-dir)
         (keep (fn [file-path]
                 (let [contents (read-text file-path)
                       matches (re-seq forbidden-small-text-pattern contents)]
                   (when (seq matches)
                     {:file (relative-path file-path)
                      :matches (mapv first matches)}))))
         vec)))

(deftest tailwind-font-scale-defines-12px-baseline-for-xs-and-sm-test
  (let [tailwind-config-path (join-path (project-root) "tailwind.config.js")
        tailwind-config (read-text tailwind-config-path)]
    (testing "text-xs utility is pinned to 12px/16px"
      (is (re-find #"xs\s*:\s*\[\s*[\"']12px[\"']\s*,\s*\{\s*lineHeight\s*:\s*[\"']16px[\"']\s*\}\s*\]"
                   tailwind-config)))
    (testing "text-sm utility is pinned to 12px/16px"
      (is (re-find #"sm\s*:\s*\[\s*[\"']12px[\"']\s*,\s*\{\s*lineHeight\s*:\s*[\"']16px[\"']\s*\}\s*\]"
                   tailwind-config)))))

(deftest views-do-not-use-forbidden-sub-16px-explicit-text-utilities-test
  (let [violations (forbidden-small-text-violations)]
    (is (empty? violations)
        (str "Forbidden explicit text utilities found: "
             (str/join ", " (map (fn [{:keys [file matches]}]
                                   (str file " => " matches))
                                 violations))))))

(deftest balances-tab-uses-12px-typography-for-toggle-and-rows-test
  (let [account-info-actions-path (join-path (project-root) "src" "hyperopen" "views" "account_info" "tab_actions.cljs")
        balances-desktop-path (join-path (project-root) "src" "hyperopen" "views" "account_info" "tabs" "balances" "desktop.cljs")
        account-info-actions-source (read-text account-info-actions-path)
        balances-desktop-source (read-text balances-desktop-path)]
    (testing "hide small balances label uses text-sm"
      (is (re-find #"\[:label\.text-sm\.text-trading-text(?:\.[A-Za-z0-9_-]+)*\s+\{:for \"hide-small-balances\"\}\s+\"Hide Small Balances\"\]"
                   account-info-actions-source)))
    (testing "balance desktop grid declares the dedicated parity template"
      (is (re-find #"\(def \^:private balances-desktop-grid-template-class\s+\"grid-cols-\["
                   balances-desktop-source)))
    (testing "balance row wrapper includes text-sm for 12px baseline"
      (is (re-find #"\[:div\s+\{:class\s+\[\"grid\"\s+(?:\(desktop-grid-template-class read-only\?\)|balances-desktop-grid-template-class)[\s\S]*?\"text-sm\""
                   balances-desktop-source)))))

(deftest header-nav-link-css-uses-14px-and-600-weight-test
  (let [styles-path (join-path (project-root) "src" "styles" "main.css")
        styles-source (read-text styles-path)]
    (testing "header nav links use 14px and 600 weight with ligatures disabled"
      (is (re-find #"\.header-nav-link\s*\{[\s\S]*?font-weight:\s*600;[\s\S]*?font-size:\s*14px;[\s\S]*?line-height:\s*15px;[\s\S]*?font-feature-settings:\s*\"calt\"\s*off;[\s\S]*?font-variant-ligatures:\s*no-contextual;[\s\S]*?\}"
                   styles-source)))
    (testing "active header link color matches target accent"
      (is (re-find #"\.header-nav-link-active\s*\{[\s\S]*?color:\s*rgb\(151 252 228\);[\s\S]*?\}"
                   styles-source)))))

(deftest order-size-slider-css-uses-progress-track-and-no-filler-trail-test
  (let [styles-path (join-path (project-root) "src" "styles" "main.css")
        styles-source (read-text styles-path)]
    (testing "order size slider active fill stays darker than notch accents"
      (is (re-find #"\.order-size-slider\.range\s*\{[^}]*--order-size-slider-active:\s*rgb\(15,\s*51,\s*51\);"
                   styles-source)))
    (testing "order size slider allows aura to render outside control bounds"
      (is (re-find #"\.order-size-slider\.range\s*\{[^}]*overflow:\s*visible;"
                   styles-source)))
    (testing "order size slider track fills from explicit progress variable"
      (is (re-find #"\.order-size-slider\.range::-webkit-slider-runnable-track\s*\{[^}]*var\(--order-size-slider-progress\)"
                   styles-source))
      (is (re-find #"\.order-size-slider\.range::-moz-range-track\s*\{[^}]*var\(--order-size-slider-progress\)"
                   styles-source)))
    (testing "order size slider thumb avoids daisy filler spectate trail"
      (is (not (re-find #"\.order-size-slider\.range::-webkit-slider-thumb\s*\{[^}]*calc\(var\(--filler-size\)"
                        styles-source)))
      (is (not (re-find #"\.order-size-slider\.range::-moz-range-thumb\s*\{[^}]*calc\(var\(--filler-size\)"
                        styles-source))))
    (testing "order size slider thumb animates aura from base on mouse down"
      (is (re-find #"\.order-size-slider\.range::-webkit-slider-thumb\s*\{[^}]*transition:\s*box-shadow\s*180ms\s*cubic-bezier\(0\.16,\s*1,\s*0\.3,\s*1\);"
                   styles-source))
      (is (re-find #"\.order-size-slider\.range::-moz-range-thumb\s*\{[^}]*transition:\s*box-shadow\s*180ms\s*cubic-bezier\(0\.16,\s*1,\s*0\.3,\s*1\);"
                   styles-source))
      (is (re-find #"\.order-size-slider\.range:active::-webkit-slider-thumb\s*\{[^}]*0 0 0 12px rgba\(0,\s*212,\s*170,\s*0\.18\);"
                   styles-source))
      (is (re-find #"\.order-size-slider\.range:active::-moz-range-thumb\s*\{[^}]*0 0 0 12px rgba\(0,\s*212,\s*170,\s*0\.18\);"
                   styles-source)))
    (testing "order size slider focus-visible state stays smaller than active aura"
      (is (not (re-find #"\.order-size-slider\.range:focus-visible::-webkit-slider-thumb\s*\{[^}]*0 0 0 12px"
                        styles-source)))
      (is (not (re-find #"\.order-size-slider\.range:focus-visible::-moz-range-thumb\s*\{[^}]*0 0 0 12px"
                        styles-source))))))

(deftest typography-defaults-use-system-ui-token-and-body-font-variable-test
  (let [styles-path (join-path (project-root) "src" "styles" "main.css")
        styles-source (read-text styles-path)
        index-path (join-path (project-root) "resources" "public" "index.html")
        index-source (read-text index-path)]
    (testing "root typography variables include system/ui and monospace tokens"
      (is (re-find #"--font-ui-system:\s*system-ui" styles-source))
      (is (re-find #"--font-ui:\s*var\(--font-ui-system\)" styles-source))
      (is (re-find #"--font-mono:\s*ui-monospace" styles-source)))
    (testing "body font is driven by typography token"
      (is (re-find #"body\s*\{[\s\S]*?font-family:\s*var\(--font-ui\);" styles-source)))
    (testing "html defaults to system UI font mode"
      (is (re-find #"<html[^>]*data-ui-font=\"system\"" index-source)))))

(deftest numeric-utility-and-inter-font-face-contract-test
  (let [styles-path (join-path (project-root) "src" "styles" "main.css")
        styles-source (read-text styles-path)]
    (testing "num utility enforces tabular lining numerals with fallback features"
      (is (re-find #"\.num\s*\{[\s\S]*?font-variant-numeric:\s*tabular-nums\s+lining-nums;[\s\S]*?font-feature-settings:\s*\"tnum\"\s*1,\s*\"lnum\"\s*1;" styles-source)))
    (testing "num-right utility right aligns numeric columns"
      (is (re-find #"\.num-right\s*\{[\s\S]*?text-align:\s*right;" styles-source)))
    (testing "Inter Variable font-face uses swap display behavior"
      (is (re-find #"@font-face\s*\{[\s\S]*?font-family:\s*\"Inter Variable\";[\s\S]*?font-display:\s*swap;" styles-source)))))

(deftest cold-load-font-contract-avoids-custom-brand-font-and-hard-coded-inter-measurement-test
  (let [styles-path (join-path (project-root) "src" "styles" "main.css")
        styles-source (read-text styles-path)
        tailwind-config-path (join-path (project-root) "tailwind.config.js")
        tailwind-config (read-text tailwind-config-path)
        header-source (read-text (join-path (project-root) "src" "hyperopen" "views" "header_view.cljs"))
        account-info-actions-source (read-text (join-path (project-root) "src" "hyperopen" "views" "account_info" "tab_actions.cljs"))
        portfolio-format-source (read-text (join-path (project-root) "src" "hyperopen" "views" "portfolio" "format.cljs"))
        vault-chart-source (read-text (join-path (project-root) "src" "hyperopen" "views" "vaults" "detail" "chart_view.cljs"))]
    (testing "default stylesheet no longer defines the retired Splash font face"
      (is (not (re-find #"font-family:\s*\"Splash\"" styles-source)))
      (is (not (re-find #"Splash-Regular\.ttf" styles-source))))
    (testing "tailwind font families no longer expose a splash font utility"
      (is (not (re-find #"splash\s*:" tailwind-config))))
    (testing "header no longer uses the retired font-splash utility"
      (is (not (re-find #"font-splash" header-source))))
    (testing "canvas measurement paths use the shared UI font resolver instead of hard-coded Inter"
      (is (re-find #"fonts/canvas-font 12" account-info-actions-source))
      (is (re-find #"fonts/canvas-font 12" portfolio-format-source))
      (is (re-find #"fonts/canvas-font 12" vault-chart-source))
      (is (not (re-find #"Inter Variable" account-info-actions-source)))
      (is (not (re-find #"Inter Variable" portfolio-format-source)))
      (is (not (re-find #"Inter Variable" vault-chart-source))))))

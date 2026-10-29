(ns hyperopen.views.trading-chart.utils.theme-colors
  "Chart color access for the active UI theme.

   Reads --ho-chart-* custom properties (full CSS color strings, defined per
   theme in src/styles/themes/*.css) from the document root, with static
   fallbacks matching the default theme for DOM-less contexts such as node
   tests. Values are cached per data-theme value; the chart picks up a theme
   change when its options are next rebuilt.")

(def fallbacks
  {"--ho-chart-bg" "rgb(15, 26, 31)"
   "--ho-chart-text" "#e5e7eb"
   "--ho-chart-grid" "#374151"
   "--ho-chart-grid-strong" "#4b5563"
   "--ho-chart-grid-soft" "rgba(139, 148, 158, 0.16)"
   "--ho-chart-border-soft" "rgba(139, 148, 158, 0.24)"
   "--ho-chart-separator" "rgba(139, 148, 158, 0.22)"
   "--ho-chart-separator-hover" "rgba(139, 148, 158, 0.30)"
   "--ho-chart-up" "rgba(34, 171, 148, 0.5)"
   "--ho-chart-down" "rgba(247, 82, 95, 0.5)"
   "--ho-chart-long" "#26a69a"
   "--ho-chart-short" "#ef5350"
   "--ho-chart-liquidation" "#f5a524"
   ;; Semantic tokens are space-separated RGB triplets (see token-rgb).
   "--ho-buy" "0 212 170"
   "--ho-sell" "255 107 107"
   "--ho-warn" "255 210 63"})

(defonce ^:private cache
  (atom {:theme ::none :values {}}))

(defn- current-theme []
  (when (exists? js/document)
    (some-> (.-documentElement js/document) .-dataset .-theme)))

(defn- read-token [token-name]
  (when (and (exists? js/document)
             (exists? js/getComputedStyle))
    (try
      (let [styles (js/getComputedStyle (.-documentElement js/document))
            value (.trim (.getPropertyValue styles token-name))]
        (when (seq value)
          value))
      (catch :default _
        nil))))

(defn token
  "Returns the active theme's value for a --ho-chart-* token name."
  [token-name]
  (let [theme (current-theme)
        state @cache
        cached (when (= theme (:theme state))
                 (get (:values state) token-name))]
    (or cached
        (let [value (or (read-token token-name)
                        (get fallbacks token-name))]
          (swap! cache (fn [{:as state}]
                         (if (= theme (:theme state))
                           (assoc-in state [:values token-name] value)
                           {:theme theme :values {token-name value}})))
          value))))

(defn token-rgb
  "Returns a CSS color for a space-separated RGB triplet token such as
   --ho-buy (\"0 212 170\") at the given alpha: \"rgb(0 212 170 / 0.35)\"."
  [token-name alpha]
  (let [triplet (or (token token-name) "128 128 128")]
    (str "rgb(" triplet " / " alpha ")")))

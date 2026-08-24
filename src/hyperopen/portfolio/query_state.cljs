(ns hyperopen.portfolio.query-state
  (:require [clojure.string :as str]
            [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.portfolio.custom-range :as custom-range]))

(def owned-query-keys
  ;; "from"/"to" carry the custom chart range. They are ALWAYS written (empty
  ;; when no custom range is applied, exactly like "bench"), so that navigating
  ;; back to a URL without a custom window actually clears one.
  #{"range" "scope" "chart" "bench" "tab" "from" "to"})

(def ^:private range-token-by-key
  {:day "24h"
   :week "7d"
   :month "30d"
   :three-month "3m"
   :six-month "6m"
   :one-year "1y"
   :two-year "2y"
   :all-time "all"})

(def ^:private range-key-by-token
  (reduce-kv (fn [acc range-key token]
               (assoc acc token range-key))
             {}
             range-token-by-key))

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- normalize-search
  [search]
  (let [search* (some-> search str str/trim)]
    (if-not (seq search*)
      ""
      (let [without-fragment (or (first (str/split search* #"#" 2))
                                 "")
            query-index (.indexOf without-fragment "?")
            query-text (if (>= query-index 0)
                         (subs without-fragment query-index)
                         without-fragment)]
        (if (str/starts-with? query-text "?")
          query-text
          (str "?" query-text))))))

(defn- search-params
  [query]
  (if (string? query)
    (js/URLSearchParams. (normalize-search query))
    query))

(defn- param-value
  [params key]
  (some-> params (.get key) non-blank-text))

(defn- param-values
  [params key]
  (when params
    (js->clj (.getAll params key))))

(defn parse-range-value
  ([value]
   (parse-range-value value portfolio-actions/default-summary-time-range))
  ([value fallback]
   (let [token (some-> value str str/trim str/lower-case)]
     (or (get range-key-by-token token)
         (portfolio-actions/normalize-summary-time-range value fallback)))))

(defn range-token
  ([value]
   (range-token value portfolio-actions/default-summary-time-range))
  ([value fallback]
   (get range-token-by-key
        (portfolio-actions/normalize-summary-time-range value fallback))))

(defn- selected-benchmark-coins
  [state]
  (let [coins (portfolio-actions/normalize-portfolio-returns-benchmark-coins
               (get-in state [:portfolio-ui :returns-benchmark-coins]))]
    (if (seq coins)
      coins
      (if-let [legacy-coin (portfolio-actions/normalize-portfolio-returns-benchmark-coin
                            (get-in state [:portfolio-ui :returns-benchmark-coin]))]
        [legacy-coin]
        []))))

(defn- benchmark-query-params
  [coins]
  (if (seq coins)
    (map (fn [coin] ["bench" coin]) coins)
    [["bench" ""]]))

(defn- parse-custom-range
  [params]
  (custom-range/normalize {:from (custom-range/parse-iso (param-value params "from"))
                           :to (custom-range/parse-iso (param-value params "to"))}))

(defn parse-portfolio-query
  [query]
  (let [params (search-params query)
        bench-values (param-values params "bench")]
    ;; The custom range is ALWAYS reported, nil included. `from`/`to` are only
    ;; written to the URL while a custom window is live, so their absence has to
    ;; mean "clear it" — otherwise navigating back to a preset URL would leave a
    ;; stale custom window applied.
    (cond-> {:summary-custom-range (parse-custom-range params)}
      (some? (param-value params "range"))
      (assoc :summary-time-range (parse-range-value (param-value params "range")))

      (some? (param-value params "scope"))
      (assoc :summary-scope (portfolio-actions/normalize-summary-scope
                             (param-value params "scope")))

      (some? (param-value params "chart"))
      (assoc :chart-tab (portfolio-actions/normalize-portfolio-chart-tab
                         (param-value params "chart")))

      (seq bench-values)
      (assoc :returns-benchmark-coins
             (portfolio-actions/normalize-portfolio-returns-benchmark-coins
              bench-values))

      (some? (param-value params "tab"))
      (assoc :account-info-tab (portfolio-actions/normalize-portfolio-account-info-tab
                                (param-value params "tab"))))))

(defn apply-portfolio-query-state
  [state query-state]
  (let [query-state* (or query-state {})]
    (cond-> state
      (contains? query-state* :summary-time-range)
      (assoc-in [:portfolio-ui :summary-time-range] (:summary-time-range query-state*))

      (contains? query-state* :summary-custom-range)
      (assoc-in portfolio-actions/summary-custom-range-path
                (:summary-custom-range query-state*))

      ;; A shared link that pins a window should not also open the editor.
      (contains? query-state* :summary-custom-range)
      (assoc-in portfolio-actions/summary-range-strip-path nil)

      (contains? query-state* :summary-scope)
      (assoc-in [:portfolio-ui :summary-scope] (:summary-scope query-state*))

      (contains? query-state* :chart-tab)
      (assoc-in [:portfolio-ui :chart-tab] (:chart-tab query-state*))

      (contains? query-state* :returns-benchmark-coins)
      (assoc-in [:portfolio-ui :returns-benchmark-coins]
                (:returns-benchmark-coins query-state*))

      (contains? query-state* :returns-benchmark-coins)
      (assoc-in [:portfolio-ui :returns-benchmark-coin]
                (first (:returns-benchmark-coins query-state*)))

      (contains? query-state* :account-info-tab)
      (assoc-in [:portfolio-ui :account-info-tab] (:account-info-tab query-state*)))))

(defn portfolio-query-state
  [state]
  (let [benchmark-coins (selected-benchmark-coins state)]
    {:summary-time-range (portfolio-actions/normalize-summary-time-range
                          (get-in state [:portfolio-ui :summary-time-range]))
     :summary-scope (portfolio-actions/normalize-summary-scope
                     (get-in state [:portfolio-ui :summary-scope]))
     :chart-tab (portfolio-actions/normalize-portfolio-chart-tab
                 (get-in state [:portfolio-ui :chart-tab]))
     :returns-benchmark-coins benchmark-coins
     :summary-custom-range (portfolio-actions/summary-custom-range state)
     :account-info-tab (portfolio-actions/normalize-portfolio-account-info-tab
                        (get-in state [:portfolio-ui :account-info-tab]))}))

(defn portfolio-query-params
  [state]
  (let [{:keys [summary-time-range
                summary-scope
                chart-tab
                returns-benchmark-coins
                summary-custom-range
                account-info-tab]} (portfolio-query-state state)]
    (into (cond-> [["range" (range-token summary-time-range)]
                   ["scope" (name summary-scope)]
                   ["chart" (name chart-tab)]]
            summary-custom-range
            (conj ["from" (custom-range/format-iso (:from summary-custom-range))]
                  ["to" (custom-range/format-iso (:to summary-custom-range))]))
          (concat (benchmark-query-params returns-benchmark-coins)
                  [["tab" (name account-info-tab)]]))))

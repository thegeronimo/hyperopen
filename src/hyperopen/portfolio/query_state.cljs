(ns hyperopen.portfolio.query-state
  (:require [clojure.string :as str]
            [hyperopen.portfolio.actions :as portfolio-actions]))

(def owned-query-keys
  #{"range" "scope" "chart" "bench" "tab"})

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

(defn parse-portfolio-query
  [query]
  (let [params (search-params query)
        bench-values (param-values params "bench")]
    (cond-> {}
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
     :account-info-tab (portfolio-actions/normalize-portfolio-account-info-tab
                        (get-in state [:portfolio-ui :account-info-tab]))}))

(defn portfolio-query-params
  [state]
  (let [{:keys [summary-time-range
                summary-scope
                chart-tab
                returns-benchmark-coins
                account-info-tab]} (portfolio-query-state state)]
    (into [["range" (range-token summary-time-range)]
           ["scope" (name summary-scope)]
           ["chart" (name chart-tab)]]
          (concat (benchmark-query-params returns-benchmark-coins)
                  [["tab" (name account-info-tab)]]))))

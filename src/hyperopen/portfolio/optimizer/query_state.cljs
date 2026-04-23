(ns hyperopen.portfolio.optimizer.query-state
  (:require [clojure.string :as str]))

(def owned-query-keys
  #{"ofilter" "osort" "oview" "otab" "odiag"})

(def ^:private default-list-filter
  :active)

(def ^:private default-list-sort
  :updated-desc)

(def ^:private default-workspace-panel
  :setup)

(def ^:private default-results-tab
  :allocation)

(def ^:private default-diagnostics-tab
  :conditioning)

(def ^:private list-filter-values
  #{:active :saved :computed :executed :partially-executed :archived :all})

(def ^:private list-filter-aliases
  {:partial :partially-executed
   :partiallyexecuted :partially-executed
   :partial-executed :partially-executed})

(def ^:private list-sort-values
  #{:updated-desc :updated-asc :name-asc :name-desc :status :objective})

(def ^:private workspace-panel-values
  #{:setup :results :rebalance :tracking :diagnostics})

(def ^:private results-tab-values
  #{:allocation :frontier :diagnostics :rebalance :tracking})

(def ^:private diagnostics-tab-values
  #{:conditioning :constraints :sensitivity :data :returns})

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

(defn- normalize-keyword-like
  [value]
  (if (keyword? value)
    value
    (some-> value non-blank-text str/lower-case keyword)))

(defn- normalize-enum
  [value valid-values fallback]
  (let [value* (normalize-keyword-like value)]
    (if (contains? valid-values value*)
      value*
      fallback)))

(defn normalize-list-filter
  [value]
  (let [value* (normalize-keyword-like value)
        aliased-value (get list-filter-aliases value* value*)]
    (if (contains? list-filter-values aliased-value)
      aliased-value
      default-list-filter)))

(defn normalize-list-sort
  [value]
  (normalize-enum value list-sort-values default-list-sort))

(defn normalize-workspace-panel
  [value]
  (normalize-enum value workspace-panel-values default-workspace-panel))

(defn normalize-results-tab
  [value]
  (normalize-enum value results-tab-values default-results-tab))

(defn normalize-diagnostics-tab
  [value]
  (normalize-enum value diagnostics-tab-values default-diagnostics-tab))

(defn parse-optimizer-query
  [query]
  (let [params (search-params query)]
    (cond-> {}
      (some? (param-value params "ofilter"))
      (assoc :list-filter (normalize-list-filter (param-value params "ofilter")))

      (some? (param-value params "osort"))
      (assoc :list-sort (normalize-list-sort (param-value params "osort")))

      (some? (param-value params "oview"))
      (assoc :workspace-panel (normalize-workspace-panel (param-value params "oview")))

      (some? (param-value params "otab"))
      (assoc :results-tab (normalize-results-tab (param-value params "otab")))

      (some? (param-value params "odiag"))
      (assoc :diagnostics-tab (normalize-diagnostics-tab (param-value params "odiag"))))))

(defn apply-optimizer-query-state
  [state query-state]
  (let [query-state* (or query-state {})]
    (cond-> state
      (contains? query-state* :list-filter)
      (assoc-in [:portfolio-ui :optimizer :list-filter] (:list-filter query-state*))

      (contains? query-state* :list-sort)
      (assoc-in [:portfolio-ui :optimizer :list-sort] (:list-sort query-state*))

      (contains? query-state* :workspace-panel)
      (assoc-in [:portfolio-ui :optimizer :workspace-panel] (:workspace-panel query-state*))

      (contains? query-state* :results-tab)
      (assoc-in [:portfolio-ui :optimizer :results-tab] (:results-tab query-state*))

      (contains? query-state* :diagnostics-tab)
      (assoc-in [:portfolio-ui :optimizer :diagnostics-tab] (:diagnostics-tab query-state*)))))

(defn optimizer-query-state
  [state]
  (let [optimizer-state (get-in state [:portfolio-ui :optimizer])]
    {:list-filter (normalize-list-filter (:list-filter optimizer-state))
     :list-sort (normalize-list-sort (:list-sort optimizer-state))
     :workspace-panel (normalize-workspace-panel (:workspace-panel optimizer-state))
     :results-tab (normalize-results-tab (:results-tab optimizer-state))
     :diagnostics-tab (normalize-diagnostics-tab (:diagnostics-tab optimizer-state))}))

(defn optimizer-query-params
  [state]
  (let [{:keys [list-filter
                list-sort
                workspace-panel
                results-tab
                diagnostics-tab]} (optimizer-query-state state)]
    [["ofilter" (name list-filter)]
     ["osort" (name list-sort)]
     ["oview" (name workspace-panel)]
     ["otab" (name results-tab)]
     ["odiag" (name diagnostics-tab)]]))

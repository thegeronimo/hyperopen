(ns hyperopen.portfolio.optimizer.query-state
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.coercion :as coercion]))

(def owned-query-keys
  #{"ofilter" "osort" "oview" "otab" "odiag"})

(def ^:private default-list-filter
  :active)

(def ^:private default-list-sort
  :updated-desc)

(def ^:private default-workspace-panel
  :setup)

(def ^:private default-results-tab
  :recommendation)

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
  #{:recommendation :execution :tracking :inputs})

(def ^:private results-tab-aliases
  {:allocation :recommendation
   :frontier :recommendation
   :diagnostics :recommendation
   :rebalance :execution})

(def ^:private diagnostics-tab-values
  #{:conditioning :constraints :sensitivity :data :returns})

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
  (some-> params (.get key) coercion/non-blank-text))

(defn normalize-list-filter
  [value]
  (let [value* (coercion/normalize-keyword-like value)
        aliased-value (get list-filter-aliases value* value*)]
    (if (contains? list-filter-values aliased-value)
      aliased-value
      default-list-filter)))

(defn normalize-list-sort
  [value]
  (coercion/normalize-enum value list-sort-values default-list-sort))

(defn normalize-workspace-panel
  [value]
  (coercion/normalize-enum value workspace-panel-values default-workspace-panel))

(defn normalize-results-tab
  [value]
  (let [value* (coercion/normalize-keyword-like value)
        aliased-value (get results-tab-aliases value* value*)]
    (if (contains? results-tab-values aliased-value)
      aliased-value
      default-results-tab)))

(defn normalize-diagnostics-tab
  [value]
  (coercion/normalize-enum value diagnostics-tab-values default-diagnostics-tab))

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
      (assoc-in contracts/ui-list-filter-path (:list-filter query-state*))

      (contains? query-state* :list-sort)
      (assoc-in contracts/ui-list-sort-path (:list-sort query-state*))

      (contains? query-state* :workspace-panel)
      (assoc-in contracts/ui-workspace-panel-path (:workspace-panel query-state*))

      (contains? query-state* :results-tab)
      (assoc-in contracts/ui-results-tab-path (:results-tab query-state*))

      (contains? query-state* :diagnostics-tab)
      (assoc-in contracts/ui-diagnostics-tab-path (:diagnostics-tab query-state*)))))

(defn optimizer-query-state
  [state]
  (let [optimizer-state (get-in state contracts/optimizer-ui-path)]
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

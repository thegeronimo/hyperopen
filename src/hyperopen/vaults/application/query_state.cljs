(ns hyperopen.vaults.application.query-state
  (:require [clojure.string :as str]
            [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.portfolio.query-state :as portfolio-query-state]
            [hyperopen.vaults.application.ui-state :as ui-state]))

(def list-owned-query-keys
  #{"range" "q" "roles" "closed" "sort" "page" "pageSize"})

(def detail-owned-query-keys
  #{"range" "chart" "bench" "tab" "activity" "side"})

(def ^:private role-filter-keys
  [:leading :deposited :others])

(def ^:private role-token-set
  (set role-filter-keys))

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

(defn- param-present?
  [params key]
  (and params (.has params key)))

(defn- param-value
  [params key]
  (when (param-present? params key)
    (.get params key)))

(defn- param-values
  [params key]
  (when params
    (js->clj (.getAll params key))))

(defn- vault-range-value
  [value]
  (let [token (some-> value str str/trim str/lower-case)
        public-range (portfolio-query-state/parse-range-value
                      token
                      ui-state/default-vault-snapshot-range)]
    (if (some? token)
      (ui-state/normalize-vault-snapshot-range public-range)
      ui-state/default-vault-snapshot-range)))

(defn- vault-range-token
  [value]
  (portfolio-query-state/range-token
   (ui-state/normalize-vault-snapshot-range value)
   ui-state/default-vault-snapshot-range))

(defn- role-token
  [value]
  (let [token (cond
                (keyword? value) value
                (string? value) (-> value
                                    str/trim
                                    str/lower-case
                                    (str/replace #"[^a-z0-9]+" "-")
                                    keyword)
                :else nil)]
    (when (contains? role-token-set token)
      token)))

(defn- parse-roles
  [value]
  (let [raw (str (or value ""))
        tokens (->> (str/split raw #",")
                    (keep role-token)
                    distinct
                    vec)]
    (cond
      (seq tokens) (set tokens)
      (str/blank? raw) #{}
      :else nil)))

(defn- role-filter-path
  [role]
  [:vaults-ui (case role
                :leading :filter-leading?
                :deposited :filter-deposited?
                :others :filter-others?)])

(defn- sort-state
  [value]
  (let [[raw-column raw-direction] (str/split (str (or value "")) #":" 2)]
    {:column (ui-state/normalize-vault-sort-column raw-column)
     :direction (ui-state/normalize-sort-direction raw-direction)}))

(defn- selected-detail-benchmark-coins
  [state]
  (let [coins (portfolio-actions/normalize-portfolio-returns-benchmark-coins
               (get-in state [:vaults-ui :detail-returns-benchmark-coins]))]
    (if (seq coins)
      coins
      (if-let [legacy-coin (portfolio-actions/normalize-portfolio-returns-benchmark-coin
                            (get-in state [:vaults-ui :detail-returns-benchmark-coin]))]
        [legacy-coin]
        []))))

(defn- benchmark-query-params
  [coins]
  (if (seq coins)
    (map (fn [coin] ["bench" coin]) coins)
    [["bench" ""]]))

(defn parse-vault-list-query
  [query]
  (let [params (search-params query)
        roles (when (param-present? params "roles")
                (parse-roles (param-value params "roles")))]
    (cond-> {}
      (param-present? params "range")
      (assoc :snapshot-range (vault-range-value (param-value params "range")))

      (param-present? params "q")
      (assoc :search-query (str (or (param-value params "q") "")))

      (some? roles)
      (assoc :roles roles)

      (param-present? params "closed")
      (assoc :filter-closed? (= "1" (str/trim (str (or (param-value params "closed") "")))))

      (param-present? params "sort")
      (assoc :sort (sort-state (param-value params "sort")))

      (param-present? params "page")
      (assoc :user-vaults-page (ui-state/normalize-vault-user-page
                                (param-value params "page")))

      (param-present? params "pageSize")
      (assoc :user-vaults-page-size (ui-state/normalize-vault-user-page-size
                                     (param-value params "pageSize"))))))

(defn parse-vault-detail-query
  [query]
  (let [params (search-params query)
        bench-values (param-values params "bench")]
    (cond-> {}
      (param-present? params "range")
      (assoc :snapshot-range (vault-range-value (param-value params "range")))

      (param-present? params "chart")
      (assoc :detail-chart-series (ui-state/normalize-vault-detail-chart-series
                                   (param-value params "chart")))

      (seq bench-values)
      (assoc :detail-returns-benchmark-coins
             (portfolio-actions/normalize-portfolio-returns-benchmark-coins
              bench-values))

      (param-present? params "tab")
      (assoc :detail-tab (ui-state/normalize-vault-detail-tab
                          (param-value params "tab")))

      (param-present? params "activity")
      (assoc :detail-activity-tab (ui-state/normalize-vault-detail-activity-tab
                                   (param-value params "activity")))

      (param-present? params "side")
      (assoc :detail-activity-direction-filter
             (ui-state/normalize-vault-detail-activity-direction-filter
              (param-value params "side"))))))

(defn apply-vault-query-state
  [state query-state]
  (let [query-state* (or query-state {})]
    (cond-> state
      (contains? query-state* :snapshot-range)
      (assoc-in [:vaults-ui :snapshot-range] (:snapshot-range query-state*))

      (contains? query-state* :search-query)
      (assoc-in [:vaults-ui :search-query] (:search-query query-state*))

      (contains? query-state* :roles)
      (assoc-in (role-filter-path :leading)
                (contains? (:roles query-state*) :leading))

      (contains? query-state* :roles)
      (assoc-in (role-filter-path :deposited)
                (contains? (:roles query-state*) :deposited))

      (contains? query-state* :roles)
      (assoc-in (role-filter-path :others)
                (contains? (:roles query-state*) :others))

      (contains? query-state* :filter-closed?)
      (assoc-in [:vaults-ui :filter-closed?] (:filter-closed? query-state*))

      (contains? query-state* :sort)
      (assoc-in [:vaults-ui :sort] (:sort query-state*))

      (contains? query-state* :user-vaults-page)
      (assoc-in [:vaults-ui :user-vaults-page] (:user-vaults-page query-state*))

      (contains? query-state* :user-vaults-page-size)
      (assoc-in [:vaults-ui :user-vaults-page-size] (:user-vaults-page-size query-state*))

      (contains? query-state* :detail-chart-series)
      (assoc-in [:vaults-ui :detail-chart-series] (:detail-chart-series query-state*))

      (contains? query-state* :detail-returns-benchmark-coins)
      (assoc-in [:vaults-ui :detail-returns-benchmark-coins]
                (:detail-returns-benchmark-coins query-state*))

      (contains? query-state* :detail-returns-benchmark-coins)
      (assoc-in [:vaults-ui :detail-returns-benchmark-coin]
                (first (:detail-returns-benchmark-coins query-state*)))

      (contains? query-state* :detail-tab)
      (assoc-in [:vaults-ui :detail-tab] (:detail-tab query-state*))

      (contains? query-state* :detail-activity-tab)
      (assoc-in [:vaults-ui :detail-activity-tab] (:detail-activity-tab query-state*))

      (contains? query-state* :detail-activity-direction-filter)
      (assoc-in [:vaults-ui :detail-activity-direction-filter]
                (:detail-activity-direction-filter query-state*)))))

(defn vault-list-query-state
  [state]
  (let [roles (into #{}
                    (keep (fn [role]
                            (when (true? (get-in state (role-filter-path role)))
                              role)))
                    role-filter-keys)
        sort (or (get-in state [:vaults-ui :sort])
                 {:column ui-state/default-vault-sort-column
                  :direction ui-state/default-vault-sort-direction})]
    {:snapshot-range (ui-state/normalize-vault-snapshot-range
                      (get-in state [:vaults-ui :snapshot-range]))
     :search-query (str (or (get-in state [:vaults-ui :search-query]) ""))
     :roles roles
     :filter-closed? (true? (get-in state [:vaults-ui :filter-closed?]))
     :sort {:column (ui-state/normalize-vault-sort-column (:column sort))
            :direction (ui-state/normalize-sort-direction (:direction sort))}
     :user-vaults-page-size (ui-state/normalize-vault-user-page-size
                             (get-in state [:vaults-ui :user-vaults-page-size]))
     :user-vaults-page (ui-state/normalize-vault-user-page
                        (get-in state [:vaults-ui :user-vaults-page]))}))

(defn vault-detail-query-state
  [state]
  {:snapshot-range (ui-state/normalize-vault-snapshot-range
                    (get-in state [:vaults-ui :snapshot-range]))
   :detail-chart-series (ui-state/normalize-vault-detail-chart-series
                         (get-in state [:vaults-ui :detail-chart-series]))
   :detail-returns-benchmark-coins (selected-detail-benchmark-coins state)
   :detail-tab (ui-state/normalize-vault-detail-tab
                (get-in state [:vaults-ui :detail-tab]))
   :detail-activity-tab (ui-state/normalize-vault-detail-activity-tab
                         (get-in state [:vaults-ui :detail-activity-tab]))
   :detail-activity-direction-filter
   (ui-state/normalize-vault-detail-activity-direction-filter
    (get-in state [:vaults-ui :detail-activity-direction-filter]))})

(defn- role-param-value
  [roles]
  (->> role-filter-keys
       (filter (set roles))
       (map name)
       (str/join ",")))

(defn vault-list-query-params
  [state]
  (let [{:keys [snapshot-range
                search-query
                roles
                filter-closed?
                sort
                user-vaults-page
                user-vaults-page-size]} (vault-list-query-state state)
        sort-value (str (name (:column sort)) ":" (name (:direction sort)))]
    (cond-> [["range" (vault-range-token snapshot-range)]]
      (seq search-query)
      (conj ["q" search-query])

      true
      (conj ["roles" (role-param-value roles)])

      filter-closed?
      (conj ["closed" "1"])

      true
      (conj ["sort" sort-value])

      true
      (conj ["page" (str user-vaults-page)])

      true
      (conj ["pageSize" (str user-vaults-page-size)]))))

(defn vault-detail-query-params
  [state]
  (let [{:keys [snapshot-range
                detail-chart-series
                detail-returns-benchmark-coins
                detail-tab
                detail-activity-tab
                detail-activity-direction-filter]} (vault-detail-query-state state)]
    (into [["range" (vault-range-token snapshot-range)]
           ["chart" (name detail-chart-series)]]
          (concat (benchmark-query-params detail-returns-benchmark-coins)
                  [["tab" (name detail-tab)]
                   ["activity" (name detail-activity-tab)]
                   ["side" (name detail-activity-direction-filter)]]))))

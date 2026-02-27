(ns hyperopen.vaults.actions
  (:require [clojure.string :as str]
            [hyperopen.utils.parse :as parse-utils]))

(def default-vault-snapshot-range
  :month)

(def default-vault-sort-column
  :tvl)

(def default-vault-sort-direction
  :desc)

(def default-vault-user-page-size
  10)

(def default-vault-user-page
  1)

(def default-vault-detail-tab
  :about)

(def default-vault-detail-activity-tab
  :positions)

(def default-vault-detail-chart-series
  :pnl)

(def ^:private vault-snapshot-ranges
  #{:day :week :month :all-time})

(def ^:private vault-sort-columns
  #{:vault :leader :apr :tvl :your-deposit :age :snapshot})

(def ^:private vault-detail-tabs
  #{:about :vault-performance :your-performance})

(def ^:private vault-detail-activity-tabs
  #{:balances
    :positions
    :open-orders
    :twap
    :trade-history
    :funding-history
    :order-history
    :deposits-withdrawals
    :depositors})

(def ^:private vault-detail-chart-series-options
  #{:account-value
    :pnl})

(def ^:private projection-effect-ids
  #{:effects/save
    :effects/save-many})

(def vault-user-page-size-options
  [5 10 25 50])

(def ^:private vault-user-page-size-option-set
  (set vault-user-page-size-options))

(def ^:private vault-filter-paths
  {:leading [:vaults-ui :filter-leading?]
   :deposited [:vaults-ui :filter-deposited?]
   :others [:vaults-ui :filter-others?]
   :closed [:vaults-ui :filter-closed?]})

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn normalize-vault-address
  [value]
  (let [text (some-> value non-blank-text str/lower-case)]
    (when (and (string? text)
               (re-matches #"^0x[0-9a-f]{40}$" text))
      text)))

(defn- split-route-from-query-fragment
  [path]
  (let [path* (if (string? path) path (str (or path "")))]
    (or (first (str/split path* #"[?#]" 2))
        "")))

(defn- trim-trailing-slashes
  [path]
  (loop [path* path]
    (if (and (> (count path*) 1)
             (str/ends-with? path* "/"))
      (recur (subs path* 0 (dec (count path*))))
      path*)))

(defn normalize-vault-route-path
  [path]
  (-> path
      split-route-from-query-fragment
      str/trim
      trim-trailing-slashes))

(defn parse-vault-route
  [path]
  (let [path* (normalize-vault-route-path path)]
    (cond
      (= path* "/vaults")
      {:kind :list
       :path path*}

      :else
      (if-let [[_ raw-address] (re-matches #"^/vaults/([^/]+)$" path*)]
        {:kind :detail
         :path path*
         :raw-vault-address raw-address
         :vault-address (normalize-vault-address raw-address)}
        {:kind :other
         :path path*}))))

(defn normalize-vault-snapshot-range
  [value]
  (let [token (cond
                (keyword? value) value
                (string? value) (-> value
                                    str/trim
                                    str/lower-case
                                    (str/replace #"[^a-z0-9]+" "-")
                                    keyword)
                :else nil)
        normalized (case token
                     :alltime :all-time
                     token)]
    (if (contains? vault-snapshot-ranges normalized)
      normalized
      default-vault-snapshot-range)))

(defn normalize-vault-sort-column
  [value]
  (let [token (cond
                (keyword? value) value
                (string? value) (-> value
                                    str/trim
                                    str/lower-case
                                    (str/replace #"[^a-z0-9]+" "-")
                                    keyword)
                :else nil)]
    (if (contains? vault-sort-columns token)
      token
      default-vault-sort-column)))

(defn normalize-vault-detail-tab
  [value]
  (let [token (cond
                (keyword? value) value
                (string? value) (-> value
                                    str/trim
                                    str/lower-case
                                    (str/replace #"[^a-z0-9]+" "-")
                                    keyword)
                :else nil)
        normalized (case token
                     :vaultperformance :vault-performance
                     :yourperformance :your-performance
                     token)]
    (if (contains? vault-detail-tabs normalized)
      normalized
      default-vault-detail-tab)))

(defn normalize-vault-detail-activity-tab
  [value]
  (let [token (cond
                (keyword? value) value
                (string? value) (-> value
                                    str/trim
                                    str/lower-case
                                    (str/replace #"[^a-z0-9]+" "-")
                                    keyword)
                :else nil)
        normalized (case token
                     :openorders :open-orders
                     :tradehistory :trade-history
                     :fundinghistory :funding-history
                     :orderhistory :order-history
                     :depositswithdrawals :deposits-withdrawals
                     token)]
    (if (contains? vault-detail-activity-tabs normalized)
      normalized
      default-vault-detail-activity-tab)))

(defn normalize-vault-detail-chart-series
  [value]
  (let [token (cond
                (keyword? value) value
                (string? value) (-> value
                                    str/trim
                                    str/lower-case
                                    (str/replace #"[^a-z0-9]+" "-")
                                    keyword)
                :else nil)
        normalized (case token
                     :accountvalue :account-value
                     token)]
    (if (contains? vault-detail-chart-series-options normalized)
      normalized
      default-vault-detail-chart-series)))

(defn normalize-vault-user-page-size
  [value]
  (let [candidate (parse-utils/parse-int-value value)]
    (if (contains? vault-user-page-size-option-set candidate)
      candidate
      default-vault-user-page-size)))

(defn normalize-vault-user-page
  ([value]
   (normalize-vault-user-page value nil))
  ([value max-page]
   (let [candidate (max default-vault-user-page
                        (or (parse-utils/parse-int-value value)
                            default-vault-user-page))
         max-page* (when (some? max-page)
                     (max default-vault-user-page
                          (or (parse-utils/parse-int-value max-page)
                              default-vault-user-page)))]
     (if max-page*
       (min candidate max-page*)
       candidate))))

(defn- vault-wallet-address
  [state]
  (normalize-vault-address (get-in state [:wallet :address])))

(defn- save-vault-ui-with-user-page-reset
  [path value]
  [[:effects/save-many [[path value]
                        [[:vaults-ui :user-vaults-page] default-vault-user-page]]]])

(defn- load-vault-list-effects
  [state]
  (let [wallet-address (vault-wallet-address state)]
    (cond-> [[:effects/save [:vaults-ui :list-loading?] true]
             [:effects/api-fetch-vault-index]
             [:effects/api-fetch-vault-summaries]]
      wallet-address
      (conj [:effects/api-fetch-user-vault-equities wallet-address]))))

(defn- relationship-child-addresses
  [relationship]
  (->> (or (:child-addresses relationship) [])
       (keep normalize-vault-address)
       distinct
       vec))

(defn- merged-vault-row
  [state vault-address]
  (some (fn [row]
          (when (= vault-address (normalize-vault-address (:vault-address row)))
            row))
        (or (get-in state [:vaults :merged-index-rows]) [])))

(defn- component-vault-addresses
  [state vault-address]
  (let [vault-address* (normalize-vault-address vault-address)
        row (merged-vault-row state vault-address*)
        details (get-in state [:vaults :details-by-address vault-address*])]
    (->> (concat (relationship-child-addresses (:relationship row))
                 (relationship-child-addresses (:relationship details)))
         (remove #(= % vault-address*))
         distinct
         vec)))

(defn- component-vault-history-effects
  [state vault-address]
  (let [component-addresses (component-vault-addresses state vault-address)]
    (->> component-addresses
         (mapcat (fn [address]
                   [[:effects/api-fetch-vault-fills address]
                    [:effects/api-fetch-vault-funding-history address]
                    [:effects/api-fetch-vault-order-history address]]))
         vec)))

(defn load-vaults
  [state]
  (load-vault-list-effects state))

(defn load-vault-detail
  [state vault-address]
  (if-let [vault-address* (normalize-vault-address vault-address)]
    (into [[:effects/save [:vaults-ui :detail-loading?] true]
           [:effects/api-fetch-vault-details vault-address* (vault-wallet-address state)]
           [:effects/api-fetch-vault-webdata2 vault-address*]
           [:effects/api-fetch-vault-fills vault-address*]
           [:effects/api-fetch-vault-funding-history vault-address*]
           [:effects/api-fetch-vault-order-history vault-address*]
           [:effects/api-fetch-vault-ledger-updates vault-address*]]
          (component-vault-history-effects state vault-address*))
    []))

(defn- projection-effect?
  [effect]
  (contains? projection-effect-ids (first effect)))

(defn- projection-first-effects
  [effects]
  (let [effects* (vec (or effects []))]
    (into []
          (concat (filter projection-effect? effects*)
                  (remove projection-effect? effects*)))))

(defn load-vault-route
  [state path]
  (let [{:keys [kind vault-address]} (parse-vault-route path)]
    (case kind
      :list
      (load-vault-list-effects state)

      :detail
      (projection-first-effects
       (into (load-vault-list-effects state)
             (load-vault-detail state vault-address)))

      [])))

(defn set-vaults-search-query
  [_state query]
  (save-vault-ui-with-user-page-reset [:vaults-ui :search-query] (str (or query ""))))

(defn toggle-vaults-filter
  [state filter-key]
  (if-let [path (get vault-filter-paths filter-key)]
    (let [next-value (not (true? (get-in state path)))]
      (save-vault-ui-with-user-page-reset path next-value))
    []))

(defn set-vaults-snapshot-range
  [_state snapshot-range]
  (save-vault-ui-with-user-page-reset
   [:vaults-ui :snapshot-range]
   (normalize-vault-snapshot-range snapshot-range)))

(defn set-vaults-sort
  [state sort-column]
  (let [column* (normalize-vault-sort-column sort-column)
        current (or (get-in state [:vaults-ui :sort])
                    {:column default-vault-sort-column
                     :direction default-vault-sort-direction})
        next-direction (if (= column* (:column current))
                         (if (= :asc (:direction current)) :desc :asc)
                         :desc)]
    (save-vault-ui-with-user-page-reset
     [:vaults-ui :sort]
     {:column column*
      :direction next-direction})))

(defn set-vaults-user-page-size
  [_state page-size]
  [[:effects/save-many [[[:vaults-ui :user-vaults-page-size]
                         (normalize-vault-user-page-size page-size)]
                        [[:vaults-ui :user-vaults-page]
                         default-vault-user-page]
                        [[:vaults-ui :user-vaults-page-size-dropdown-open?]
                         false]]]])

(defn toggle-vaults-user-page-size-dropdown
  [state]
  [[:effects/save [:vaults-ui :user-vaults-page-size-dropdown-open?]
    (not (true? (get-in state [:vaults-ui :user-vaults-page-size-dropdown-open?])))]])

(defn close-vaults-user-page-size-dropdown
  [_state]
  [[:effects/save [:vaults-ui :user-vaults-page-size-dropdown-open?] false]])

(defn set-vaults-user-page
  [_state page max-page]
  [[:effects/save [:vaults-ui :user-vaults-page]
    (normalize-vault-user-page page max-page)]])

(defn next-vaults-user-page
  [state max-page]
  (let [current-page (normalize-vault-user-page
                      (get-in state [:vaults-ui :user-vaults-page]))]
    (set-vaults-user-page state (inc current-page) max-page)))

(defn prev-vaults-user-page
  [state max-page]
  (let [current-page (normalize-vault-user-page
                      (get-in state [:vaults-ui :user-vaults-page]))]
    (set-vaults-user-page state (dec current-page) max-page)))

(defn set-vault-detail-tab
  [_state tab]
  [[:effects/save [:vaults-ui :detail-tab]
    (normalize-vault-detail-tab tab)]])

(defn set-vault-detail-activity-tab
  [_state tab]
  [[:effects/save [:vaults-ui :detail-activity-tab]
    (normalize-vault-detail-activity-tab tab)]])

(defn set-vault-detail-chart-series
  [_state series]
  [[:effects/save [:vaults-ui :detail-chart-series]
    (normalize-vault-detail-chart-series series)]])

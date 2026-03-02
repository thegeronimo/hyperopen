(ns hyperopen.vaults.actions
  (:require [clojure.string :as str]
            [hyperopen.platform :as platform]
            [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.vaults.detail.activity :as activity-model]
            [hyperopen.vaults.detail.types :as detail-types]
            [hyperopen.utils.parse :as parse-utils]))

(def ^:private vaults-snapshot-range-storage-key
  "vaults-snapshot-range")

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
  :performance-metrics)

(def default-vault-detail-activity-direction-filter
  :all)

(def default-vault-detail-activity-sort-direction
  :desc)

(def default-vault-detail-chart-series
  :returns)

(def default-vault-transfer-mode
  :deposit)

(def ^:private vault-usdc-micros-scale
  1000000)

(def ^:private vault-snapshot-ranges
  #{:day :week :month :three-month :six-month :one-year :two-year :all-time})

(def ^:private vault-sort-columns
  #{:vault :leader :apr :tvl :your-deposit :age :snapshot})

(def ^:private vault-detail-tabs
  #{:about :vault-performance :your-performance})

(def ^:private vault-detail-activity-tabs
  #{:performance-metrics
    :balances
    :positions
    :open-orders
    :twap
    :trade-history
    :funding-history
    :order-history
    :deposits-withdrawals
    :depositors})

(def ^:private vault-detail-activity-direction-filters
  #{:all
    :long
    :short})

(def ^:private sort-directions
  #{:asc :desc})

(def ^:private vault-detail-chart-series-options
  #{:account-value
    :pnl
    :returns})

(def ^:private vault-transfer-modes
  #{:deposit :withdraw})

(def ^:private projection-effect-ids
  #{:effects/save
    :effects/save-many})

(def ^:private vault-detail-chart-hover-index-path
  [:vaults-ui :detail-chart-hover-index])

(def ^:private vault-detail-activity-sort-by-tab-path
  [:vaults-ui :detail-activity-sort-by-tab])

(def ^:private vault-detail-activity-direction-filter-path
  [:vaults-ui :detail-activity-direction-filter])

(def ^:private vault-detail-activity-filter-open-path
  [:vaults-ui :detail-activity-filter-open?])

(def ^:private vault-transfer-modal-path
  [:vaults-ui :vault-transfer-modal])

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
  (let [normalized (portfolio-actions/normalize-summary-time-range value)]
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
                     :performancemetrics :performance-metrics
                     :performancemetric :performance-metrics
                     :openorders :open-orders
                     :tradehistory :trade-history
                     :fundinghistory :funding-history
                     :orderhistory :order-history
                     :depositswithdrawals :deposits-withdrawals
                     token)]
    (if (contains? vault-detail-activity-tabs normalized)
      normalized
      default-vault-detail-activity-tab)))

(defn normalize-vault-detail-activity-direction-filter
  [value]
  (let [token (cond
                (keyword? value) value
                (string? value) (-> value
                                    str/trim
                                    str/lower-case
                                    keyword)
                :else nil)]
    (if (contains? vault-detail-activity-direction-filters token)
      token
      default-vault-detail-activity-direction-filter)))

(defn- normalize-sort-direction
  [value]
  (let [direction (cond
                    (keyword? value) value
                    (string? value) (keyword (str/lower-case (str/trim value)))
                    :else nil)]
    (if (contains? sort-directions direction)
      direction
      default-vault-detail-activity-sort-direction)))

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
                     :return :returns
                     token)]
    (if (contains? vault-detail-chart-series-options normalized)
      normalized
      default-vault-detail-chart-series)))

(defn normalize-vault-transfer-mode
  [value]
  (let [mode (cond
               (keyword? value) value
               (string? value) (some-> value str/trim str/lower-case keyword)
               :else nil)]
    (if (contains? vault-transfer-modes mode)
      mode
      default-vault-transfer-mode)))

(defn default-vault-transfer-modal-state
  []
  {:open? false
   :mode default-vault-transfer-mode
   :vault-address nil
   :amount-input ""
   :withdraw-all? false
   :submitting? false
   :error nil})

(defn- selected-vault-detail-returns-benchmark-coins
  [state]
  (let [coins (portfolio-actions/normalize-portfolio-returns-benchmark-coins
               (get-in state [:vaults-ui :detail-returns-benchmark-coins]))]
    (if (seq coins)
      coins
      (if-let [legacy-coin (portfolio-actions/normalize-portfolio-returns-benchmark-coin
                            (get-in state [:vaults-ui :detail-returns-benchmark-coin]))]
        [legacy-coin]
        []))))

(defn- vault-detail-returns-chart-selected?
  [state]
  (= :returns
     (normalize-vault-detail-chart-series
      (get-in state [:vaults-ui :detail-chart-series]))))

(defn- vault-detail-performance-metrics-selected?
  [state]
  (= :performance-metrics
     (normalize-vault-detail-activity-tab
      (get-in state [:vaults-ui :detail-activity-tab]))))

(defn- vault-detail-benchmark-fetch-enabled?
  [state]
  (let [{:keys [kind]} (parse-vault-route (get-in state [:router :path]))]
    (and (= :detail kind)
         (or (vault-detail-returns-chart-selected? state)
             (vault-detail-performance-metrics-selected? state)))))

(defn- vault-detail-returns-benchmark-fetch-effects
  [snapshot-range benchmark-coins]
  (let [{:keys [interval bars]} (portfolio-actions/returns-benchmark-candle-request
                                 (normalize-vault-snapshot-range snapshot-range))]
    (->> (portfolio-actions/normalize-portfolio-returns-benchmark-coins benchmark-coins)
         (remove (fn [coin]
                   (some? (detail-types/vault-benchmark-address coin))))
         (mapv (fn [coin]
                 [:effects/fetch-candle-snapshot
                  :coin coin
                  :interval interval
                  :bars bars])))))

(defn- normalize-vault-detail-returns-benchmark-search
  [value]
  (if (string? value)
    value
    (str (or value ""))))

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

(defn- finite-number
  [value]
  (let [n (cond
            (number? value) value
            (string? value) (js/Number value)
            :else js/NaN)]
    (when (and (number? n)
               (js/isFinite n))
      n)))

(defn- positive-point-count
  [value]
  (when-let [n (finite-number value)]
    (let [count* (js/Math.floor n)]
      (when (pos? count*)
        count*))))

(defn- clamp
  [value min-value max-value]
  (cond
    (< value min-value) min-value
    (> value max-value) max-value
    :else value))

(defn- hover-index-from-pointer
  [client-x bounds point-count]
  (let [point-count* (positive-point-count point-count)]
    (when point-count*
      (if (= point-count* 1)
        0
        (let [client-x* (finite-number client-x)
              left (finite-number (:left bounds))
              width (finite-number (:width bounds))]
          (when (and (number? client-x*)
                     (number? left)
                     (number? width)
                     (pos? width))
            (let [x-ratio (clamp (/ (- client-x* left) width) 0 1)
                  max-index (dec point-count*)
                  nearest-index (js/Math.round (* x-ratio max-index))]
              (clamp nearest-index 0 max-index))))))))

(defn- normalize-hover-index
  [value point-count]
  (let [point-count* (positive-point-count point-count)
        idx (finite-number value)]
    (when (and point-count*
               (number? idx))
      (let [max-index (dec point-count*)
            idx* (js/Math.floor idx)]
        (clamp idx* 0 max-index)))))

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

(defn- vault-details-record
  [state vault-address]
  (get-in state [:vaults :details-by-address vault-address]))

(defn- vault-entity-name
  [state vault-address]
  (or (some-> (vault-details-record state vault-address) :name non-blank-text)
      (some-> (merged-vault-row state vault-address) :name non-blank-text)))

(defn- vault-leader-address
  [state vault-address]
  (or (some-> (vault-details-record state vault-address) :leader normalize-vault-address)
      (some-> (merged-vault-row state vault-address) :leader normalize-vault-address)))

(defn vault-transfer-deposit-allowed?
  [state vault-address]
  (let [vault-address* (normalize-vault-address vault-address)
        allow-deposits? (true? (get-in state [:vaults :details-by-address vault-address* :allow-deposits?]))
        wallet-address (vault-wallet-address state)
        leader-address (vault-leader-address state vault-address*)
        leader? (and (string? wallet-address)
                     (= wallet-address leader-address))
        liquidator-vault? (= "liquidator"
                             (some-> (vault-entity-name state vault-address*)
                                     str/lower-case
                                     str/trim))]
    (and (string? vault-address*)
         (not liquidator-vault?)
         (or leader? allow-deposits?))))

(defn- parse-usdc-micros
  ([value]
   (parse-usdc-micros value nil))
  ([value locale]
   (let [text (parse-utils/normalize-localized-decimal-input value locale)]
    (when-let [[_ int-part frac-part frac-only]
               (and (seq text)
                    (re-matches #"^(?:(\d+)(?:\.(\d*))?|\.(\d+))$" text))]
      (let [whole (or (parse-utils/parse-int-value int-part) 0)
            fraction-source (or frac-part frac-only "")
            fraction-padded (subs (str fraction-source "000000") 0 6)
            fraction (or (parse-utils/parse-int-value fraction-padded) 0)
            whole-micros (* whole vault-usdc-micros-scale)
            micros (+ whole-micros fraction)]
        (when (and (number? micros)
                   (<= micros js/Number.MAX_SAFE_INTEGER))
          micros))))))

(defn vault-transfer-preview
  [state modal]
  (let [modal* (merge (default-vault-transfer-modal-state)
                      (if (map? modal) modal {}))
        route-vault-address (-> state
                                (get-in [:router :path])
                                parse-vault-route
                                :vault-address)
        vault-address (or (normalize-vault-address (:vault-address modal*))
                          route-vault-address)
        mode (normalize-vault-transfer-mode (:mode modal*))
        withdraw-all? (and (= mode :withdraw)
                           (true? (:withdraw-all? modal*)))
        amount-input (:amount-input modal*)
        locale (get-in state [:ui :locale])
        amount-micros (if withdraw-all?
                        0
                        (parse-usdc-micros amount-input locale))
        deposit-allowed? (vault-transfer-deposit-allowed? state vault-address)]
    (cond
      (nil? vault-address)
      {:ok? false
       :display-message "Invalid vault address."}

      (and (= mode :deposit)
           (not deposit-allowed?))
      {:ok? false
       :display-message "Deposits are disabled for this vault."}

      (and (not withdraw-all?)
           (or (nil? amount-micros)
               (<= amount-micros 0)))
      {:ok? false
       :display-message "Enter an amount greater than 0."}

      :else
      {:ok? true
       :mode mode
       :vault-address vault-address
       :display-message nil
       :request {:vault-address vault-address
                 :action {:type "vaultTransfer"
                          :vaultAddress vault-address
                          :isDeposit (= mode :deposit)
                          :usd amount-micros}}})))

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
    (let [snapshot-range (normalize-vault-snapshot-range
                          (get-in state [:vaults-ui :snapshot-range]))
          benchmark-fetch-effects (vault-detail-returns-benchmark-fetch-effects
                                   snapshot-range
                                   (selected-vault-detail-returns-benchmark-coins state))]
      (into [[:effects/save [:vaults-ui :detail-loading?] true]
             [:effects/save vault-detail-chart-hover-index-path nil]
             [:effects/api-fetch-vault-details vault-address* (vault-wallet-address state)]
             [:effects/api-fetch-vault-webdata2 vault-address*]
             [:effects/api-fetch-vault-fills vault-address*]
             [:effects/api-fetch-vault-funding-history vault-address*]
             [:effects/api-fetch-vault-order-history vault-address*]
             [:effects/api-fetch-vault-ledger-updates vault-address*]]
            (concat (component-vault-history-effects state vault-address*)
                    benchmark-fetch-effects)))
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
  (let [{:keys [kind vault-address path]} (parse-vault-route path)]
    (case kind
      :list
      (load-vault-list-effects state)

      :detail
      (projection-first-effects
       (into (load-vault-list-effects state)
             (load-vault-detail state vault-address)))

      :other
      (if (str/starts-with? (or path "") "/portfolio")
        (load-vault-list-effects state)
        [])

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
  [state snapshot-range]
  (let [snapshot-range* (normalize-vault-snapshot-range snapshot-range)
        projection-effect [:effects/save-many
                           [[[:vaults-ui :snapshot-range] snapshot-range*]
                            [[:vaults-ui :user-vaults-page] default-vault-user-page]
                            [vault-detail-chart-hover-index-path nil]]]
        fetch-effects (if (vault-detail-benchmark-fetch-enabled? state)
                        (vault-detail-returns-benchmark-fetch-effects snapshot-range*
                                                                      (selected-vault-detail-returns-benchmark-coins state))
                        [])]
    (into [projection-effect
           [:effects/local-storage-set
            vaults-snapshot-range-storage-key
            (name snapshot-range*)]]
          fetch-effects)))

(defn restore-vaults-snapshot-range!
  [store]
  (let [snapshot-range (normalize-vault-snapshot-range
                        (platform/local-storage-get vaults-snapshot-range-storage-key))]
    (swap! store assoc-in [:vaults-ui :snapshot-range] snapshot-range)))

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
  [[:effects/save-many [[[:vaults-ui :detail-activity-tab]
                         (normalize-vault-detail-activity-tab tab)]
                        [vault-detail-activity-filter-open-path false]]]])

(defn sort-vault-detail-activity
  [state tab column]
  (let [tab* (normalize-vault-detail-activity-tab tab)
        column* (activity-model/normalize-sort-column tab* column)
        current-sort (or (get-in state (conj vault-detail-activity-sort-by-tab-path tab*))
                         {})
        current-column (activity-model/normalize-sort-column tab* (:column current-sort))
        current-direction (normalize-sort-direction (:direction current-sort))
        next-direction (if (= column* current-column)
                         (if (= :asc current-direction) :desc :asc)
                         default-vault-detail-activity-sort-direction)]
    (if (nil? column*)
      []
      [[:effects/save-many [[(conj vault-detail-activity-sort-by-tab-path tab*)
                             {:column column*
                              :direction next-direction}]
                            [vault-detail-activity-filter-open-path false]]]])))

(defn toggle-vault-detail-activity-filter-open
  [state]
  [[:effects/save vault-detail-activity-filter-open-path
    (not (true? (get-in state vault-detail-activity-filter-open-path)))]])

(defn close-vault-detail-activity-filter
  [_state]
  [[:effects/save vault-detail-activity-filter-open-path false]])

(defn set-vault-detail-activity-direction-filter
  [_state direction-filter]
  [[:effects/save-many [[vault-detail-activity-direction-filter-path
                         (normalize-vault-detail-activity-direction-filter direction-filter)]
                        [vault-detail-activity-filter-open-path false]]]])

(defn set-vault-detail-chart-series
  [state series]
  (let [series* (normalize-vault-detail-chart-series series)
        snapshot-range (normalize-vault-snapshot-range
                        (get-in state [:vaults-ui :snapshot-range]))
        projection-effect [:effects/save-many
                           [[[:vaults-ui :detail-chart-series] series*]
                            [vault-detail-chart-hover-index-path nil]]]
        fetch-effects (if (= :returns series*)
                        (vault-detail-returns-benchmark-fetch-effects snapshot-range
                                                                      (selected-vault-detail-returns-benchmark-coins state))
                        [])]
    (into [projection-effect] fetch-effects)))

(defn set-vault-detail-returns-benchmark-search
  [_state search]
  [[:effects/save
    [:vaults-ui :detail-returns-benchmark-search]
    (normalize-vault-detail-returns-benchmark-search search)]])

(defn set-vault-detail-returns-benchmark-suggestions-open
  [_state open?]
  [[:effects/save
    [:vaults-ui :detail-returns-benchmark-suggestions-open?]
    (boolean open?)]])

(declare clear-vault-detail-returns-benchmark)

(defn select-vault-detail-returns-benchmark
  [state benchmark]
  (if-let [coin (portfolio-actions/normalize-portfolio-returns-benchmark-coin benchmark)]
    (let [snapshot-range (normalize-vault-snapshot-range
                          (get-in state [:vaults-ui :snapshot-range]))
          selected-coins (selected-vault-detail-returns-benchmark-coins state)
          already-selected? (contains? (set selected-coins) coin)
          next-coins (if already-selected?
                       selected-coins
                       (conj selected-coins coin))
          projection-effect [:effects/save-many
                             [[[:vaults-ui :detail-returns-benchmark-coins] next-coins]
                              [[:vaults-ui :detail-returns-benchmark-coin] (first next-coins)]
                              [[:vaults-ui :detail-returns-benchmark-search] ""]
                              [[:vaults-ui :detail-returns-benchmark-suggestions-open?] true]]]
          fetch-effects (if (and (not already-selected?)
                                 (vault-detail-benchmark-fetch-enabled? state))
                          (vault-detail-returns-benchmark-fetch-effects snapshot-range [coin])
                          [])]
      (into [projection-effect] fetch-effects))
    (clear-vault-detail-returns-benchmark state)))

(defn remove-vault-detail-returns-benchmark
  [state benchmark]
  (if-let [coin (portfolio-actions/normalize-portfolio-returns-benchmark-coin benchmark)]
    (let [next-coins (->> (selected-vault-detail-returns-benchmark-coins state)
                          (remove #(= % coin))
                          vec)]
      [[:effects/save-many
        [[[:vaults-ui :detail-returns-benchmark-coins] next-coins]
         [[:vaults-ui :detail-returns-benchmark-coin] (first next-coins)]]]])
    []))

(defn handle-vault-detail-returns-benchmark-search-keydown
  [state key top-coin]
  (cond
    (= key "Enter")
    (if-let [coin (portfolio-actions/normalize-portfolio-returns-benchmark-coin top-coin)]
      (select-vault-detail-returns-benchmark state coin)
      [])

    (= key "Escape")
    [[:effects/save [:vaults-ui :detail-returns-benchmark-suggestions-open?] false]]

    :else
    []))

(defn clear-vault-detail-returns-benchmark
  [_state]
  [[:effects/save-many
    [[[:vaults-ui :detail-returns-benchmark-coins] []]
     [[:vaults-ui :detail-returns-benchmark-coin] nil]
     [[:vaults-ui :detail-returns-benchmark-search] ""]
     [[:vaults-ui :detail-returns-benchmark-suggestions-open?] false]]]])

(defn- vault-transfer-modal
  [state]
  (merge (default-vault-transfer-modal-state)
         (if (map? (get-in state vault-transfer-modal-path))
           (get-in state vault-transfer-modal-path)
           {})))

(defn open-vault-transfer-modal
  [_state vault-address mode]
  (if-let [vault-address* (normalize-vault-address vault-address)]
    [[:effects/save vault-transfer-modal-path
      (assoc (default-vault-transfer-modal-state)
             :open? true
             :mode (normalize-vault-transfer-mode mode)
             :vault-address vault-address*)]]
    []))

(defn close-vault-transfer-modal
  [_state]
  [[:effects/save vault-transfer-modal-path
    (default-vault-transfer-modal-state)]])

(defn handle-vault-transfer-modal-keydown
  [state key]
  (if (= key "Escape")
    (close-vault-transfer-modal state)
    []))

(defn set-vault-transfer-amount
  [state amount]
  (let [modal (vault-transfer-modal state)
        amount* (if (string? amount)
                  amount
                  (str (or amount "")))
        mode* (normalize-vault-transfer-mode (:mode modal))
        next-modal (-> modal
                       (assoc :amount-input amount*
                              :error nil)
                       (cond->
                         (and (= mode* :withdraw)
                              (seq (str/trim amount*)))
                         (assoc :withdraw-all? false)))]
    [[:effects/save vault-transfer-modal-path next-modal]]))

(defn set-vault-transfer-withdraw-all
  [state withdraw-all?]
  (let [modal (vault-transfer-modal state)
        mode* (normalize-vault-transfer-mode (:mode modal))
        enabled? (= mode* :withdraw)
        withdraw-all?* (and enabled?
                            (true? withdraw-all?))
        next-modal (cond-> (assoc modal
                                  :withdraw-all? withdraw-all?*
                                  :error nil)
                     withdraw-all?* (assoc :amount-input ""))]
    [[:effects/save vault-transfer-modal-path next-modal]]))

(defn submit-vault-transfer
  [state]
  (let [modal (vault-transfer-modal state)
        result (vault-transfer-preview state modal)]
    (if-not (:ok? result)
      [[:effects/save-many [[(conj vault-transfer-modal-path :submitting?) false]
                            [(conj vault-transfer-modal-path :error) (:display-message result)]]]]
      [[:effects/save-many [[(conj vault-transfer-modal-path :submitting?) true]
                            [(conj vault-transfer-modal-path :error) nil]]]
       [:effects/api-submit-vault-transfer (:request result)]])))

(defn set-vault-detail-chart-hover
  [state client-x bounds point-count]
  (let [current-index (normalize-hover-index (get-in state vault-detail-chart-hover-index-path)
                                             point-count)
        pointer-index (hover-index-from-pointer client-x bounds point-count)
        next-index (or pointer-index
                       current-index
                       (normalize-hover-index 0 point-count))]
    (if (= current-index next-index)
      []
      [[:effects/save vault-detail-chart-hover-index-path next-index]])))

(defn clear-vault-detail-chart-hover
  [state]
  (if (nil? (get-in state vault-detail-chart-hover-index-path))
    []
    [[:effects/save vault-detail-chart-hover-index-path nil]]))

(ns hyperopen.vaults.detail.activity
  (:require [clojure.string :as str]
            [hyperopen.views.account-info.sort-kernel :as sort-kernel]))

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- optional-number
  [value]
  (cond
    (number? value)
    (when (js/isFinite value)
      value)

    (string? value)
    (let [trimmed (str/trim value)]
      (when (seq trimmed)
        (let [parsed (js/Number trimmed)]
          (when (js/isFinite parsed)
            parsed))))

    :else nil))

(defn- sortable-text
  [value]
  (some-> value non-blank-text str/lower-case))

(defn- sortable-number
  [value]
  (or (optional-number value) 0))

(defn- sortable-abs-number
  [value]
  (js/Math.abs (sortable-number value)))

(def tabs
  [{:value :performance-metrics
    :label "Performance Metrics"}
   {:value :balances
    :label "Balances"}
   {:value :positions
    :label "Positions"}
   {:value :open-orders
    :label "Open Orders"}
   {:value :twap
    :label "TWAP"}
   {:value :trade-history
    :label "Trade History"}
   {:value :funding-history
    :label "Funding History"}
   {:value :order-history
    :label "Order History"}
   {:value :deposits-withdrawals
    :label "Deposits and Withdrawals"}
   {:value :depositors
    :label "Depositors"}])

(def activity-filter-options
  [{:value :all
    :label "All"}
   {:value :long
    :label "Long"}
   {:value :short
    :label "Short"}])

(def ^:private tab-config
  {:performance-metrics {:supports-direction? false
                         :columns []
                         :default-sort {:column nil
                                        :direction :desc}}
   :balances {:supports-direction? false
              :columns [{:id :coin
                         :label "Coin"
                         :accessor (fn [row]
                                     (or (sortable-text (:coin row)) ""))}
                        {:id :total-balance
                         :label "Total Balance"
                         :accessor (fn [row]
                                     (sortable-abs-number (:total row)))}
                        {:id :available-balance
                         :label "Available Balance"
                         :accessor (fn [row]
                                     (sortable-abs-number (:available row)))}
                        {:id :usdc-value
                         :label "USDC Value"
                         :accessor (fn [row]
                                     (sortable-abs-number (:usdc-value row)))}]
              :default-sort {:column :usdc-value
                             :direction :desc}}
   :positions {:supports-direction? true
               :columns [{:id :coin
                          :label "Coin"
                          :accessor (fn [row]
                                      (or (sortable-text (:coin row)) ""))}
                         {:id :size
                          :label "Size"
                          :accessor (fn [row]
                                      (sortable-abs-number (:size row)))}
                         {:id :position-value
                          :label "Position Value"
                          :accessor (fn [row]
                                      (sortable-abs-number (:position-value row)))}
                         {:id :entry-price
                          :label "Entry Price"
                          :accessor (fn [row]
                                      (sortable-number (:entry-price row)))}
                         {:id :mark-price
                          :label "Mark Price"
                          :accessor (fn [row]
                                      (sortable-number (:mark-price row)))}
                         {:id :pnl-roe
                          :label "PNL (ROE %)"
                          :accessor (fn [row]
                                      (sortable-number (:pnl row)))}
                         {:id :liq-price
                          :label "Liq. Price"
                          :accessor (fn [row]
                                      (sortable-number (:liq-price row)))}
                         {:id :margin
                          :label "Margin"
                          :accessor (fn [row]
                                      (sortable-abs-number (:margin row)))}
                         {:id :funding
                          :label "Funding"
                          :accessor (fn [row]
                                      (sortable-number (:funding row)))}]
               :default-sort {:column :position-value
                              :direction :desc}}
   :open-orders {:supports-direction? true
                 :columns [{:id :time
                            :label "Time"
                            :accessor (fn [row]
                                        (sortable-number (:time-ms row)))}
                           {:id :coin
                            :label "Coin"
                            :accessor (fn [row]
                                        (or (sortable-text (:coin row)) ""))}
                           {:id :side
                            :label "Side"
                            :accessor (fn [row]
                                        (or (sortable-text (:side row)) ""))}
                           {:id :size
                            :label "Size"
                            :accessor (fn [row]
                                        (sortable-abs-number (:size row)))}
                           {:id :price
                            :label "Price"
                            :accessor (fn [row]
                                        (sortable-number (:price row)))}
                           {:id :trigger
                            :label "Trigger"
                            :accessor (fn [row]
                                        (sortable-number (:trigger-price row)))}]
                 :default-sort {:column :time
                                :direction :desc}}
   :twap {:supports-direction? true
          :columns [{:id :coin
                     :label "Coin"
                     :accessor (fn [row]
                                 (or (sortable-text (:coin row)) ""))}
                    {:id :size
                     :label "Size"
                     :accessor (fn [row]
                                 (sortable-abs-number (:size row)))}
                    {:id :executed-size
                     :label "Executed Size"
                     :accessor (fn [row]
                                 (sortable-abs-number (:executed-size row)))}
                    {:id :average-price
                     :label "Average Price"
                     :accessor (fn [row]
                                 (sortable-number (:average-price row)))}
                    {:id :running-time-total
                     :label "Running Time / Total"
                     :accessor (fn [row]
                                 (sortable-number (:running-ms row)))}
                    {:id :reduce-only
                     :label "Reduce Only"
                     :accessor (fn [row]
                                 (if (true? (:reduce-only? row)) 1 0))}
                    {:id :creation-time
                     :label "Creation Time"
                     :accessor (fn [row]
                                 (sortable-number (:creation-time-ms row)))}
                    {:id :terminate
                     :label "Terminate"
                     :accessor (fn [_row] 0)}]
          :default-sort {:column :creation-time
                         :direction :desc}}
   :trade-history {:supports-direction? true
                   :columns [{:id :time
                              :label "Time"
                              :accessor (fn [row]
                                          (sortable-number (:time-ms row)))}
                             {:id :coin
                              :label "Coin"
                              :accessor (fn [row]
                                          (or (sortable-text (:coin row)) ""))}
                             {:id :side
                              :label "Side"
                              :accessor (fn [row]
                                          (or (sortable-text (:side row)) ""))}
                             {:id :price
                              :label "Price"
                              :accessor (fn [row]
                                          (sortable-number (:price row)))}
                             {:id :size
                              :label "Size"
                              :accessor (fn [row]
                                          (sortable-abs-number (:size row)))}
                             {:id :trade-value
                              :label "Trade Value"
                              :accessor (fn [row]
                                          (sortable-abs-number (:trade-value row)))}
                             {:id :fee
                              :label "Fee"
                              :accessor (fn [row]
                                          (sortable-number (:fee row)))}
                             {:id :closed-pnl
                              :label "Closed PNL"
                              :accessor (fn [row]
                                          (sortable-number (:closed-pnl row)))}]
                   :default-sort {:column :time
                                  :direction :desc}}
   :funding-history {:supports-direction? true
                     :columns [{:id :time
                                :label "Time"
                                :accessor (fn [row]
                                            (sortable-number (:time-ms row)))}
                               {:id :coin
                                :label "Coin"
                                :accessor (fn [row]
                                            (or (sortable-text (:coin row)) ""))}
                               {:id :funding-rate
                                :label "Funding Rate"
                                :accessor (fn [row]
                                            (sortable-number (:funding-rate row)))}
                               {:id :position-size
                                :label "Position Size"
                                :accessor (fn [row]
                                            (sortable-abs-number (:position-size row)))}
                               {:id :payment
                                :label "Payment"
                                :accessor (fn [row]
                                            (sortable-number (:payment row)))}]
                     :default-sort {:column :time
                                    :direction :desc}}
   :order-history {:supports-direction? true
                   :columns [{:id :time
                              :label "Time"
                              :accessor (fn [row]
                                          (sortable-number (:time-ms row)))}
                             {:id :coin
                              :label "Coin"
                              :accessor (fn [row]
                                          (or (sortable-text (:coin row)) ""))}
                             {:id :side
                              :label "Side"
                              :accessor (fn [row]
                                          (or (sortable-text (:side row)) ""))}
                             {:id :type
                              :label "Type"
                              :accessor (fn [row]
                                          (or (sortable-text (:type row)) ""))}
                             {:id :size
                              :label "Size"
                              :accessor (fn [row]
                                          (sortable-abs-number (:size row)))}
                             {:id :price
                              :label "Price"
                              :accessor (fn [row]
                                          (sortable-number (:price row)))}
                             {:id :status
                              :label "Status"
                              :accessor (fn [row]
                                          (or (sortable-text (:status row)) ""))}]
                   :default-sort {:column :time
                                  :direction :desc}}
   :deposits-withdrawals {:supports-direction? false
                          :columns [{:id :time
                                     :label "Time"
                                     :accessor (fn [row]
                                                 (sortable-number (:time-ms row)))}
                                    {:id :type
                                     :label "Type"
                                     :accessor (fn [row]
                                                 (or (sortable-text (:type-label row)) ""))}
                                    {:id :amount
                                     :label "Amount"
                                     :accessor (fn [row]
                                                 (sortable-number (:amount row)))}
                                    {:id :tx-hash
                                     :label "Tx Hash"
                                     :accessor (fn [row]
                                                 (or (sortable-text (:hash row)) ""))}]
                          :default-sort {:column :time
                                         :direction :desc}}
   :depositors {:supports-direction? false
                :columns [{:id :depositor
                           :label "Depositor"
                           :accessor (fn [row]
                                       (or (sortable-text (:address row)) ""))}
                          {:id :vault-amount
                           :label "Vault Amount"
                           :accessor (fn [row]
                                       (sortable-abs-number (:vault-amount row)))}
                          {:id :unrealized-pnl
                           :label "Unrealized PNL"
                           :accessor (fn [row]
                                       (sortable-number (:unrealized-pnl row)))}
                          {:id :all-time-pnl
                           :label "All-time PNL"
                           :accessor (fn [row]
                                       (sortable-number (:all-time-pnl row)))}
                          {:id :days-following
                           :label "Days Following"
                           :accessor (fn [row]
                                       (sortable-number (:days-following row)))}]
                :default-sort {:column :vault-amount
                               :direction :desc}}})

(defn normalize-sort-direction
  [value]
  (if (#{:asc :desc} value)
    value
    :desc))

(defn tab-columns
  [tab]
  (vec (or (get-in tab-config [tab :columns]) [])))

(defn columns-by-tab
  []
  (into {}
        (map (fn [{:keys [value]}]
               [value (mapv (fn [{:keys [id label]}]
                              {:id id
                               :label label})
                            (tab-columns value))]))
        tabs))

(defn supports-direction-filter?
  [tab]
  (true? (get-in tab-config [tab :supports-direction?])))

(defn normalize-direction-filter
  [value]
  (let [token (cond
                (keyword? value) value
                (string? value) (-> value str/trim str/lower-case keyword)
                :else nil)]
    (if (#{:all :long :short} token)
      token
      :all)))

(defn- normalize-side-key
  [value]
  (case (some-> value str str/trim str/lower-case)
    ("long" "buy" "b") :long
    ("short" "sell" "a" "s") :short
    nil))

(defn- direction-key-from-size
  [value]
  (when-let [n (optional-number value)]
    (if (neg? n) :short :long)))

(defn- row-direction-key
  [tab row]
  (case tab
    :positions (direction-key-from-size (:size row))
    :open-orders (normalize-side-key (:side row))
    :twap (direction-key-from-size (:size row))
    :trade-history (normalize-side-key (:side row))
    :funding-history (direction-key-from-size (:position-size row))
    :order-history (normalize-side-key (:side row))
    nil))

(defn- direction-match?
  [direction row-direction]
  (case direction
    :long (= :long row-direction)
    :short (= :short row-direction)
    true))

(defn filter-rows-by-direction
  [rows tab direction-filter]
  (let [rows* (vec (or rows []))
        filter* (normalize-direction-filter direction-filter)]
    (if (or (= :all filter*)
            (not (supports-direction-filter? tab)))
      rows*
      (->> rows*
           (filter (fn [row]
                     (direction-match? filter* (row-direction-key tab row))))
           vec))))

(defn- column-id-set
  [tab]
  (into #{}
        (map :id)
        (tab-columns tab)))

(defn- label->id
  [tab]
  (into {}
        (map (fn [{:keys [id label]}]
               [(some-> label str/lower-case) id]))
        (tab-columns tab)))

(defn normalize-sort-column
  [tab value]
  (let [valid-column-ids (column-id-set tab)]
    (cond
      (keyword? value)
      (when (contains? valid-column-ids value)
        value)

      (string? value)
      (let [token (some-> value non-blank-text)
            token-lower (some-> token str/lower-case)
            parsed-keyword (some-> token
                                  str/lower-case
                                  (str/replace #"[^a-z0-9]+" "-")
                                  keyword)]
        (or (get (label->id tab) token-lower)
            (when (contains? valid-column-ids parsed-keyword)
              parsed-keyword)))

      :else nil)))

(defn sort-state
  [state tab]
  (let [columns (tab-columns tab)
        default-sort (or (get-in tab-config [tab :default-sort])
                         {:column (some-> columns first :id)
                          :direction :desc})
        fallback-column (or (:column default-sort)
                            (some-> columns first :id))
        saved (or (get-in state [:vaults-ui :detail-activity-sort-by-tab tab])
                  {})
        column (or (normalize-sort-column tab (:column saved))
                   fallback-column)]
    {:column column
     :direction (normalize-sort-direction (or (:direction saved)
                                              (:direction default-sort)))}))

(defn- row-sort-tie-breaker
  [row]
  (str (or (:coin row)
           (:address row)
           (:hash row)
           (:time-ms row)
           (:creation-time-ms row)
           (:type row)
           "")))

(defn sort-rows
  [rows tab sort-state*]
  (let [rows* (vec (or rows []))
        columns (tab-columns tab)
        accessor-by-column (into {}
                                 (map (fn [{:keys [id accessor]}]
                                        [id accessor]))
                                 columns)
        column (:column sort-state*)]
    (if (and (seq rows*)
             (contains? accessor-by-column column))
      (->> (sort-kernel/sort-rows-by-column
            rows*
            {:column column
             :direction (normalize-sort-direction (:direction sort-state*))
             :accessor-by-column accessor-by-column
             :tie-breaker row-sort-tie-breaker})
           vec)
      rows*)))

(defn project-rows
  [rows tab direction-filter sort-state*]
  (-> rows
      (filter-rows-by-direction tab direction-filter)
      (sort-rows tab sort-state*)))

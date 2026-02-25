(ns hyperopen.views.account-info.vm
  (:require [clojure.string :as str]
            [hyperopen.views.account-info.derived-cache :as derived-cache]
            [hyperopen.views.account-info.projections :as projections]
            [hyperopen.views.websocket-freshness :as ws-freshness]))

(defn- unified-account-mode? [account]
  (= :unified (:mode account)))

(defn- non-zero-spot-balance? [balance]
  (let [total-num (projections/parse-num (:total balance))
        hold-num (projections/parse-num (:hold balance))
        available-num (- total-num hold-num)]
    (or (not (zero? total-num))
        (not (zero? available-num)))))

(defn- non-zero-perps-usdc? [webdata2]
  (when-let [clearinghouse-state (:clearinghouseState webdata2)]
    (let [account-value (projections/parse-num (get-in clearinghouse-state [:marginSummary :accountValue]))
          total-margin-used (projections/parse-num (get-in clearinghouse-state [:marginSummary :totalMarginUsed]))
          available (- account-value total-margin-used)]
      (or (not (zero? account-value))
          (not (zero? available))))))

(defn- balance-tab-count [webdata2 spot-data account]
  (let [balances (or (get-in spot-data [:clearinghouse-state :balances]) [])
        non-zero-spot-balances (filter non-zero-spot-balance? balances)
        perps-usdc-visible? (boolean (non-zero-perps-usdc? webdata2))]
    (if (unified-account-mode? account)
      (let [non-usdc-count (count (remove #(= "USDC" (:coin %)) non-zero-spot-balances))
            has-non-zero-spot-usdc? (boolean (some #(and (= "USDC" (:coin %))
                                                         (non-zero-spot-balance? %))
                                                   balances))
            usdc-count (if (or has-non-zero-spot-usdc? perps-usdc-visible?) 1 0)]
        (+ non-usdc-count usdc-count))
      (+ (count non-zero-spot-balances)
         (if perps-usdc-visible? 1 0)))))

(defn- positions-tab-count [webdata2 perp-dex-states]
  (let [base-positions (->> (or (get-in webdata2 [:clearinghouseState :assetPositions]) [])
                            (map #(assoc % :dex nil)))
        extra-positions (->> (or perp-dex-states {})
                             (mapcat (fn [[dex state]]
                                       (->> (or (:assetPositions state) [])
                                            (map #(assoc % :dex dex))))))]
    (count
     (reduce (fn [seen pos]
               (if (map? pos)
                 (conj seen (projections/position-unique-key pos))
                 seen))
             #{}
             (concat base-positions extra-positions)))))

(defn- open-orders-tab-count [orders snapshot snapshot-by-dex pending-cancel-oids]
  (let [pending-set (projections/pending-cancel-oid-set pending-cancel-oids)]
    (->> (projections/open-orders-source orders snapshot snapshot-by-dex)
         (remove #(projections/order-pending-cancel? % pending-set))
         (reduce (fn [acc order]
                   (let [root (or (:order order) order)]
                     (if (and (:coin root) (:oid root))
                       (inc acc)
                       acc)))
                 0))))

(defn- prefer-orders-value [orders webdata2 k]
  (if (contains? orders k)
    (get orders k)
    (get webdata2 k)))

(def ^:private tp-side-markers
  #{"tp" "takeprofit" "take-profit" "take profit"})

(def ^:private sl-side-markers
  #{"sl" "stoploss" "stop-loss" "stop loss"})

(defn- normalize-dex-value
  [value]
  (some-> value projections/non-blank-text str/lower-case))

(defn- order-matches-position?
  [position order]
  (let [position-coin (get-in position [:position :coin])
        position-dex (normalize-dex-value (:dex position))
        order-dex (normalize-dex-value (:dex order))]
    (and (projections/open-order-for-active-asset? position-coin order)
         (or (nil? position-dex)
             (nil? order-dex)
             (= position-dex order-dex)))))

(defn- parse-order-tpsl-side
  [value]
  (let [text (some-> value str str/trim str/lower-case)]
    (cond
      (contains? tp-side-markers text) :tp
      (contains? sl-side-markers text) :sl
      :else nil)))

(defn- infer-order-tpsl-side
  [order]
  (or (parse-order-tpsl-side (:tpsl order))
      (let [type-text (some-> (:type order) str str/trim str/lower-case)]
        (cond
          (and type-text
               (or (str/includes? type-text "take profit")
                   (str/includes? type-text "take-profit")
                   (str/includes? type-text "take")))
          :tp

          (and type-text
               (or (str/includes? type-text "stop loss")
                   (str/includes? type-text "stop-loss")
                   (str/includes? type-text "stop")
                   (str/includes? type-text "loss")))
          :sl

          :else nil))))

(defn- position-tpsl-order?
  [order]
  (let [trigger-px (projections/parse-optional-num (:trigger-px order))
        tpsl-side (infer-order-tpsl-side order)]
    (and (#{:tp :sl} tpsl-side)
         (number? trigger-px)
         (pos? trigger-px)
         (or (:is-position-tpsl order)
             (:reduce-only order)))))

(defn- order-time-ms
  [order]
  (or (projections/parse-time-ms (:time-ms order))
      (projections/parse-time-ms (:time order))
      0))

(defn- newest-order
  [orders]
  (reduce (fn [best order]
            (if (or (nil? best)
                    (> (order-time-ms order)
                       (order-time-ms best)))
              order
              best))
          nil
          orders))

(defn- attach-position-tpsl-trigger-prices
  [positions normalized-open-orders]
  (let [orders* (or normalized-open-orders [])]
    (->> (or positions [])
         (mapv (fn [position]
                 (let [matching (->> orders*
                                     (filter (fn [order]
                                               (and (position-tpsl-order? order)
                                                    (order-matches-position? position order)))))
                       tp-order (->> matching
                                     (filter #(= :tp (infer-order-tpsl-side %)))
                                     newest-order)
                       sl-order (->> matching
                                     (filter #(= :sl (infer-order-tpsl-side %)))
                                     newest-order)]
                   (cond-> position
                     (some? tp-order) (assoc :position-tp-trigger-px (:trigger-px tp-order))
                     (some? sl-order) (assoc :position-sl-trigger-px (:trigger-px sl-order)))))))))

(defn- selected-tab-derivations [selected-tab
                                 webdata2
                                 spot-data
                                 account
                                 perp-dex-states
                                 open-orders
                                 open-orders-snapshot
                                 open-orders-snapshot-by-dex
                                 pending-cancel-oids
                                 market-by-key]
  (case selected-tab
    :balances {:balance-rows (derived-cache/memoized-balance-rows webdata2 spot-data account market-by-key)}
    :positions (let [positions (derived-cache/memoized-positions webdata2 perp-dex-states)
                     normalized-open-orders (derived-cache/memoized-open-orders open-orders
                                                                                 open-orders-snapshot
                                                                                 open-orders-snapshot-by-dex
                                                                                 pending-cancel-oids)]
                 {:positions (attach-position-tpsl-trigger-prices positions
                                                                  normalized-open-orders)})
    :open-orders {:open-orders (derived-cache/memoized-open-orders open-orders
                                                                   open-orders-snapshot
                                                                   open-orders-snapshot-by-dex
                                                                   pending-cancel-oids)}
    {}))

(defn reset-account-info-vm-cache! []
  (derived-cache/reset-derived-cache!))

(defn account-info-vm [state]
  (let [selected-tab (get-in state [:account-info :selected-tab] :balances)
        webdata2 (or (:webdata2 state) {})
        orders (or (:orders state) {})
        loading? (get-in state [:account-info :loading] false)
        error (get-in state [:account-info :error])
        positions-sort (get-in state [:account-info :positions-sort] {:column nil :direction :asc})
        balances-sort (get-in state [:account-info :balances-sort] {:column nil :direction :asc})
        balances-coin-search (get-in state [:account-info :balances-coin-search] "")
        hide-small? (get-in state [:account-info :hide-small-balances?] false)
        perp-dex-states (or (:perp-dex-clearinghouse state) {})
        spot-data (or (:spot state) {})
        account (or (:account state) {})
        market-by-key (get-in state [:asset-selector :market-by-key] {})
        open-orders-source (prefer-orders-value orders webdata2 :open-orders)
        open-orders-snapshot-source (prefer-orders-value orders webdata2 :open-orders-snapshot)
        open-orders-snapshot-by-dex-source (prefer-orders-value orders webdata2 :open-orders-snapshot-by-dex)
        pending-cancel-oids (:pending-cancel-oids orders)
        {:keys [balance-rows positions open-orders]} (selected-tab-derivations selected-tab
                                                                                webdata2
                                                                                spot-data
                                                                                account
                                                                                perp-dex-states
                                                                                open-orders-source
                                                                                open-orders-snapshot-source
                                                                                open-orders-snapshot-by-dex-source
                                                                                pending-cancel-oids
                                                                                market-by-key)
        trade-history-state (-> (merge {:direction-filter :all
                                        :filter-open? false}
                                       (get-in state [:account-info :trade-history] {}))
                                (assoc :market-by-key market-by-key))
        funding-history-state (get-in state [:account-info :funding-history] {})
        order-history-state (-> (merge {:status-filter :all
                                        :filter-open? false
                                        :coin-search ""}
                                       (get-in state [:account-info :order-history] {}))
                                (assoc :market-by-key market-by-key))
        tab-counts {:open-orders (open-orders-tab-count open-orders-source
                                                        open-orders-snapshot-source
                                                        open-orders-snapshot-by-dex-source
                                                        pending-cancel-oids)
                    :positions (positions-tab-count webdata2 perp-dex-states)
                    :balances (balance-tab-count webdata2 spot-data account)}
        open-orders-sort (get-in state [:account-info :open-orders-sort] {:column "Time" :direction :desc})
        positions-state (merge {:direction-filter :all
                                :coin-search ""
                                :filter-open? false}
                               (get-in state [:account-info :positions] {}))
        open-orders-state (merge {:direction-filter :all
                                  :filter-open? false}
                                 (get-in state [:account-info :open-orders] {}))
        websocket-health (get-in state [:websocket :health])
        wallet-address (get-in state [:wallet :address])
        show-surface-freshness-cues?
        (boolean (get-in state [:websocket-ui :show-surface-freshness-cues?] false))
        freshness-cues (when show-surface-freshness-cues?
                        {:positions (ws-freshness/surface-cue websocket-health
                                                             {:topic "webData2"
                                                              :selector (when wallet-address
                                                                          {:user wallet-address})
                                                              :live-prefix "Updated"
                                                              :na-prefix "Last update"})
                         :open-orders (ws-freshness/surface-cue websocket-health
                                                               {:topic "openOrders"
                                                                :selector (when wallet-address
                                                                            {:user wallet-address})
                                                                :live-prefix "Updated"
                                                                :na-prefix "Last update"})})]
    {:selected-tab selected-tab
     :loading? loading?
     :error error
     :positions-sort positions-sort
     :balances-sort balances-sort
     :balances-coin-search balances-coin-search
     :open-orders-sort open-orders-sort
     :positions-state positions-state
     :open-orders-state open-orders-state
     :position-tpsl-modal (get-in state [:positions-ui :tpsl-modal])
     :position-reduce-popover (get-in state [:positions-ui :reduce-popover])
     :hide-small? hide-small?
     :perp-dex-states perp-dex-states
     :webdata2 webdata2
     :balance-rows (or balance-rows [])
     :positions (or positions [])
     :open-orders (or open-orders [])
     :trade-history-rows (prefer-orders-value orders webdata2 :fills)
     :trade-history-state trade-history-state
     :funding-history-rows (prefer-orders-value orders webdata2 :fundings)
     :funding-history-raw (prefer-orders-value orders webdata2 :fundings-raw)
     :funding-history-state funding-history-state
     :order-history-rows (prefer-orders-value orders webdata2 :order-history)
     :order-history-state order-history-state
     :tab-counts tab-counts
     :freshness-cues freshness-cues}))

(ns hyperopen.views.account-info.vm
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.order.cancel-visible-confirmation :as cancel-visible-confirmation]
            [hyperopen.platform :as platform]
            [hyperopen.portfolio.routes :as portfolio-routes]
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
        non-zero-spot-balances (->> balances
                                    (remove projections/outcome-balance?)
                                    (filter non-zero-spot-balance?))
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

(defn- existing-position-mark-price
  [position-row]
  (or (projections/parse-optional-num (get-in position-row [:position :markPx]))
      (projections/parse-optional-num (get-in position-row [:position :markPrice]))
      (projections/parse-optional-num (:markPx position-row))
      (projections/parse-optional-num (:markPrice position-row))))

(defn- position-coin-candidates
  [position-row]
  (let [coin (projections/non-blank-text (get-in position-row [:position :coin]))
        dex (projections/non-blank-text (:dex position-row))
        base-coin (some-> coin projections/parse-coin-namespace :base projections/non-blank-text)]
    (->> [coin
          (when (and (seq dex) (seq base-coin))
            (str dex ":" base-coin))
          base-coin]
         (remove nil?)
         distinct)))

(defn- resolve-position-market-mark-price
  [market-by-key position-row]
  (some (fn [coin]
          (let [market (markets/resolve-market-by-coin market-by-key coin)
                mark (projections/parse-optional-num (:mark market))]
            (when (and (number? mark) (pos? mark))
              mark)))
        (position-coin-candidates position-row)))

(defn- attach-position-market-mark-prices
  [positions market-by-key]
  (if-not (map? market-by-key)
    (vec (or positions []))
    (->> (or positions [])
         (mapv (fn [position-row]
                 (if-not (map? position-row)
                   position-row
                   (let [existing-mark (existing-position-mark-price position-row)
                         resolved-mark (or existing-mark
                                           (resolve-position-market-mark-price market-by-key position-row))
                         nested-mark (projections/parse-optional-num (get-in position-row [:position :markPx]))]
                     (if (and (number? resolved-mark)
                              (map? (:position position-row))
                              (not (number? nested-mark)))
                       (assoc-in position-row [:position :markPx] resolved-mark)
                       position-row))))))))

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
                                 twap-states
                                 twap-history
                                 twap-slice-fills
                                 market-by-key
                                 outcome-options]
  (let [now-ms (platform/now-ms)]
    (case selected-tab
      :balances {:balance-rows (derived-cache/memoized-balance-rows webdata2 spot-data account market-by-key)}
      :outcomes {:outcomes (projections/build-outcome-rows spot-data market-by-key outcome-options)}
      :positions (let [positions (derived-cache/memoized-positions webdata2 perp-dex-states)
                       positions (attach-position-market-mark-prices positions market-by-key)
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
      :twap {:twap-active-rows (projections/normalized-active-twaps twap-states now-ms)
             :twap-history-rows (projections/normalized-twap-history twap-history)
             :twap-fill-rows (projections/normalized-twap-slice-fills twap-slice-fills)}
      {})))

(defn reset-account-info-vm-cache! []
  (derived-cache/reset-derived-cache!))

(defn account-info-vm [state]
  (let [selected-tab (get-in state [:account-info :selected-tab] :balances)
        route (get-in state [:router :path])
        webdata2 (or (:webdata2 state) {})
        orders (or (:orders state) {})
        loading? (get-in state [:account-info :loading] false)
        error (get-in state [:account-info :error])
        mobile-expanded-card (get-in state [:account-info :mobile-expanded-card] {})
        positions-sort (get-in state [:account-info :positions-sort] {:column nil :direction :asc})
        balances-sort (get-in state [:account-info :balances-sort] {:column nil :direction :asc})
        balances-coin-search (get-in state [:account-info :balances-coin-search] "")
        hide-small? (get-in state [:account-info :hide-small-balances?] false)
        perp-dex-states (or (:perp-dex-clearinghouse state) {})
        spot-data (or (:spot state) {})
        account (or (:account state) {})
        market-by-key (get-in state [:asset-selector :market-by-key] {})
        outcome-options {:active-market (:active-market state)
                         :selector-active-market (get-in state [:asset-selector :active-market])
                         :active-contexts (get-in state [:active-assets :contexts] {})}
        open-orders-source (prefer-orders-value orders webdata2 :open-orders)
        open-orders-snapshot-source (prefer-orders-value orders webdata2 :open-orders-snapshot)
        open-orders-snapshot-by-dex-source (prefer-orders-value orders webdata2 :open-orders-snapshot-by-dex)
        twap-states-source (:twap-states orders)
        twap-history-source (:twap-history orders)
        twap-slice-fills-source (:twap-slice-fills orders)
        pending-cancel-oids (:pending-cancel-oids orders)
        {:keys [balance-rows outcomes positions open-orders twap-active-rows twap-history-rows twap-fill-rows]}
        (selected-tab-derivations selected-tab
                                  webdata2
                                  spot-data
                                  account
                                  perp-dex-states
                                  open-orders-source
                                  open-orders-snapshot-source
                                  open-orders-snapshot-by-dex-source
                                  pending-cancel-oids
                                  twap-states-source
                                  twap-history-source
                                  twap-slice-fills-source
                                  market-by-key
                                  outcome-options)
        trade-history-state (-> (merge {:direction-filter :all
                                        :coin-search ""
                                        :filter-open? false}
                                       (get-in state [:account-info :trade-history] {}))
                                (assoc :market-by-key market-by-key))
        read-only? (account-context/inspected-account-read-only? state)
        read-only-message (account-context/mutations-blocked-message state)
        twap-state (cond-> (merge {:selected-subtab :active}
                                  (get-in state [:account-info :twap] {}))
                     true (assoc :read-only? read-only?)
                     (seq read-only-message) (assoc :read-only-message read-only-message))
        twap-fill-state (-> (merge {:sort {:column "Time" :direction :desc}
                                    :direction-filter :all
                                    :coin-search ""
                                    :filter-open? false
                                    :page-size 50
                                    :page 1
                                    :page-input "1"}
                                   (get-in state [:account-info :trade-history] {}))
                            (assoc :direction-filter :all
                                   :coin-search ""
                                   :filter-open? false
                                   :market-by-key market-by-key))
        funding-history-state (get-in state [:account-info :funding-history] {})
        order-history-state (-> (merge {:status-filter :all
                                        :filter-open? false
                                        :coin-search ""}
                                       (get-in state [:account-info :order-history] {}))
                                (assoc :market-by-key market-by-key))
        outcome-count (count (projections/build-outcome-rows spot-data market-by-key outcome-options))
        tab-counts {:open-orders (open-orders-tab-count open-orders-source
                                                        open-orders-snapshot-source
                                                        open-orders-snapshot-by-dex-source
                                                        pending-cancel-oids)
                    :positions (positions-tab-count webdata2 perp-dex-states)
                    :outcomes outcome-count
                    :balances (balance-tab-count webdata2 spot-data account)
                    :twap (count (or twap-states-source []))}
        open-orders-sort (get-in state [:account-info :open-orders-sort] {:column "Time" :direction :desc})
        positions-state (cond-> (merge {:direction-filter :all
                                        :coin-search ""
                                        :filter-open? false
                                        :navigate-to-trade-on-coin-click?
                                        (portfolio-routes/portfolio-route? route)}
                                       (get-in state [:account-info :positions] {}))
                          true (assoc :read-only? read-only?)
                          (seq read-only-message) (assoc :read-only-message read-only-message))
        open-orders-state (cond-> (-> (merge {:direction-filter :all
                                              :coin-search ""
                                              :filter-open? false
                                              :cancel-visible-confirmation (cancel-visible-confirmation/default-state)}
                                             (get-in state [:account-info :open-orders] {}))
                                      (assoc :market-by-key market-by-key
                                             :cancel-error (get-in state [:orders :cancel-error])))
                            true (assoc :read-only? read-only?)
                            (seq read-only-message) (assoc :read-only-message read-only-message))
        websocket-health (get-in state [:websocket :health])
        effective-address (account-context/effective-account-address state)
        show-surface-freshness-cues?
        (boolean (get-in state [:websocket-ui :show-surface-freshness-cues?] false))
        freshness-cues (when show-surface-freshness-cues?
                        {:positions (ws-freshness/surface-cue websocket-health
                                                             {:topic "webData2"
                                                              :selector (when effective-address
                                                                          {:user effective-address})
                                                              :live-prefix "Updated"
                                                              :na-prefix "Last update"})
                         :open-orders (ws-freshness/surface-cue websocket-health
                                                               {:topic "openOrders"
                                                                :selector (when effective-address
                                                                            {:user effective-address})
                                                                :live-prefix "Updated"
                                                                :na-prefix "Last update"})})]
    {:selected-tab selected-tab
     :loading? loading?
     :error error
     :mobile-expanded-card mobile-expanded-card
     :read-only? read-only?
     :read-only-message read-only-message
     :positions-sort positions-sort
     :balances-sort balances-sort
     :balances-coin-search balances-coin-search
     :open-orders-sort open-orders-sort
     :positions-state positions-state
     :open-orders-state open-orders-state
     :position-tpsl-modal (get-in state [:positions-ui :tpsl-modal])
     :position-reduce-popover (get-in state [:positions-ui :reduce-popover])
     :position-margin-modal (get-in state [:positions-ui :margin-modal])
     :hide-small? hide-small?
     :perp-dex-states perp-dex-states
     :webdata2 webdata2
     :balance-rows (or balance-rows [])
     :outcomes (or outcomes [])
     :positions (or positions [])
     :open-orders (or open-orders [])
     :trade-history-rows (prefer-orders-value orders webdata2 :fills)
     :trade-history-state trade-history-state
     :twap-state twap-state
     :twap-fill-state twap-fill-state
     :twap-active-rows (or twap-active-rows [])
     :twap-history-rows (or twap-history-rows [])
     :twap-fill-rows (or twap-fill-rows [])
     :funding-history-rows (prefer-orders-value orders webdata2 :fundings)
     :funding-history-raw (prefer-orders-value orders webdata2 :fundings-raw)
     :funding-history-state funding-history-state
     :order-history-rows (prefer-orders-value orders webdata2 :order-history)
     :order-history-state order-history-state
     :tab-counts tab-counts
     :freshness-cues freshness-cues}))

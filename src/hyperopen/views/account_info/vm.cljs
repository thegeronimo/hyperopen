(ns hyperopen.views.account-info.vm
  (:require [hyperopen.views.account-info.derived-cache :as derived-cache]
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
    :positions {:positions (derived-cache/memoized-positions webdata2 perp-dex-states)}
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
        trade-history-state (assoc (get-in state [:account-info :trade-history] {})
                                   :market-by-key market-by-key)
        funding-history-state (get-in state [:account-info :funding-history] {})
        order-history-state (assoc (get-in state [:account-info :order-history] {})
                                   :market-by-key market-by-key)
        tab-counts {:open-orders (open-orders-tab-count open-orders-source
                                                        open-orders-snapshot-source
                                                        open-orders-snapshot-by-dex-source
                                                        pending-cancel-oids)
                    :positions (positions-tab-count webdata2 perp-dex-states)
                    :balances (balance-tab-count webdata2 spot-data account)}
        open-orders-sort (get-in state [:account-info :open-orders-sort] {:column "Time" :direction :desc})
        websocket-health (or (:websocket-health state)
                             (get-in state [:websocket :health]))
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
     :open-orders-sort open-orders-sort
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

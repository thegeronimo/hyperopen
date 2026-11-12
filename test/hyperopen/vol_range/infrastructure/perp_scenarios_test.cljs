(ns hyperopen.vol-range.infrastructure.perp-scenarios-test
  "Simulator scenarios for a ladder on a HIP-3 perp market: admission,
  journal commit, final guard, campaign baseline, send, reconcile, cancel.
  The simulator's venue rests the orders; this wrapper answers the perp reads
  (`clearinghouseState`, `activeAssetData`, dex-scoped `frontendOpenOrders`)
  and names the orders by the perp coin. Nothing reaches a real exchange."
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.platform.locks :as locks]
            [hyperopen.vol-range.application.campaign :as campaign]
            [hyperopen.vol-range.application.planner :as planner]
            [hyperopen.vol-range.application.snapshots :as snapshots]
            [hyperopen.vol-range.domain.fees :as vr-fees]
            [hyperopen.vol-range.fixtures :as fixtures]
            [hyperopen.vol-range.infrastructure.executor :as ex]
            [hyperopen.vol-range.infrastructure.executor-cancel :as cancel]
            [hyperopen.vol-range.infrastructure.executor-place :as place]
            [hyperopen.vol-range.simulator :as sim]))

(def coin "xyz:NVDA")

(def scope
  {:network :hyperliquid-mainnet :owner-address "0xabc" :account-address "0xabc" :vault-address nil
   :market-type :perp :dex "xyz" :spot-pair-index 3 :asset-id 110003 :coin coin
   :base-token-id "perp:xyz:NVDA" :quote-token-id nil :symbol "NVDA" :quote-symbol "USDC" :sz-decimals 2
   :metadata-version nil})

(defn- fail [done] (fn [e] (is false (str e (when (instance? js/Error e) (.-stack e)))) (done)))

(defn- fresh []
  (locks/reset-in-tab-state!)
  (sim/new-sim))

(defn- named [row] (cond-> row (map? row) (assoc :coin coin)))

(defn- post-info
  "The simulator's venue, seen as the xyz perp dex. `venue` is an atom of
  {:position :withdrawable :leverage :type}; `calls` logs request bodies."
  [s venue calls]
  (let [base (:post-info! (sim/deps s))]
    (fn [body opts]
      (swap! calls conj body)
      (case (get body "type")
        "clearinghouseState"
        (js/Promise.resolve {:withdrawable (:withdrawable @venue)
                             :assetPositions [{:position {:coin coin :szi (:position @venue)}}]})
        "activeAssetData"
        (js/Promise.resolve {:coin coin :markPx "80"
                             :leverage {:type (:type @venue) :value (:leverage @venue)}})
        "frontendOpenOrders"
        (if (= "xyz" (get body "dex"))
          (.then (base body opts) (fn [rows] (mapv named rows)))
          ;; The first perp dex (and spot) has none of this ladder's orders.
          (js/Promise.resolve []))
        "orderStatus"
        (.then (base body opts) (fn [r] (cond-> r (get-in r [:order :order]) (update-in [:order :order] named))))
        (base body opts)))))

(def fees
  (vr-fees/fee-capability {:quote {:effective {:maker 0.04}} :settlement (vr-fees/perp-settlement)
                           :settlement-verified? true :source :user-fees}))

(defn- account [venue]
  (fixtures/account-snapshot
   (snapshots/perp-balances {:position (:position venue) :withdrawable (:withdrawable venue)
                             :leverage (:leverage venue) :leverage-type (keyword (:type venue))
                             :mid "80" :sz-decimals 2})))

(defn- live-input [s reviewed]
  (let [t (sim/now s)]
    {:plan-id nil :scope scope
     :draft (assoc fixtures/draft-13-2 :inventory {:tilt-enabled? false})
     :market (fixtures/market-snapshot {:coin coin :market-type :perp :base-symbol "NVDA"
                                        :bbo {:bid "79.99" :ask "80.01" :observed-at-ms t}})
     ;; What the ticket shows: the account as the store last saw it.
     :account (account reviewed)
     :fees (assoc fees :loaded-at-ms t)
     :pending-intents [] :clock t :model-version 1 :precision-version 1}))

(defn- deps [s venue calls reviewed]
  (assoc (sim/deps s)
         :post-info! (post-info s venue calls)
         :live! (fn [] {:input (live-input s reviewed) :raw-account-mode "default" :read-only? false
                        :execution-enabled? true :agent-ready? true})))

(defn- frozen-plan [s reviewed]
  (planner/prepare-plan (assoc (live-input s reviewed) :plan-id "plan-1" :remediations? false)))

(defn- testing-reads [calls]
  (let [types (frequencies (map #(get % "type") calls))]
    (is (pos? (get types "clearinghouseState" 0)))
    (is (pos? (get types "activeAssetData" 0)))
    (is (some #(and (= "frontendOpenOrders" (get % "type")) (= "xyz" (get % "dex"))) calls))
    (is (every? #(= "xyz" (get % "dex")) (filter #(= "clearinghouseState" (get % "type")) calls)))))

(def flat {:position "0" :withdrawable "1000" :leverage 5 :type "cross"})

(deftest a-hip3-ladder-is-placed-tracked-reconciled-and-canceled-test
  (async done
    (let [s (fresh)
          venue (atom flat)
          calls (atom [])
          d (deps s venue calls flat)
          plan (frozen-plan s flat)]
      (is (= :ready (:status plan)) (pr-str (:errors plan)))
      (-> (place/place! d {:frozen-plan plan :frozen-scope scope})
          (.then
           (fn [{:keys [status counts grid] :as r}]
             (is (= :live status) (pr-str (select-keys r [:status :errors :error])))
             (is (= 7 (:live counts)))
             (let [[order] (sim/order-sends s)]
               (is (= 7 (count (:orders order))))
               (is (= #{110003} (set (map :a (:orders order)))) "the HIP-3 asset id")
               (is (every? #(false? (:r %)) (:orders order)) "never reduce-only")
               (is (= #{{:limit {:tif "Alo"}}} (set (map :t (:orders order))))))
             (testing-reads @calls)
             (let [[c] (filterv #(= :campaign (:kind %)) (sim/records s))]
               (is (= "hyperliquid-mainnet|0xabc|perp|xyz:NVDA|" (:campaign-key c)))
               (is (= "0" (get-in c [:baseline :q0])) "the baseline is the position")
               (is (= [:perp "xyz" "perp:xyz:NVDA"]
                      ((juxt :market-type :dex :base-token-id) (:scope c)))))
             (ex/reconcile! d grid)))
          (.then
           (fn [{:keys [status counts grid reads]}]
             (is (true? (:orders-complete? reads)))
             (is (= :live status) "the dex's open orders prove the ladder is resting")
             (is (= 7 (:live counts)))
             (cancel/cancel! d {:grid grid})))
          (.then
           (fn [{:keys [status counts]}]
             (is (= :closed status))
             (is (= 7 (:canceled counts)))
             (is (= #{110003} (set (map #(or (:a %) (:asset %)) (mapcat :cancels (sim/cancel-sends s))))))
             (done)))
          (.catch (fail done))))))

(deftest a-venue-leverage-the-user-did-not-review-refuses-the-send-test
  (async done
    (let [s (fresh)
          ;; The ticket planned at 5x; the exchange has this market at 3x.
          venue (atom (assoc flat :leverage 3))
          d (deps s venue (atom []) flat)
          plan (frozen-plan s flat)]
      (-> (place/place! d {:frozen-plan plan :frozen-scope scope})
          (.then
           (fn [r]
             (is (= :admission-failed (:status r)))
             (is (= [:guard/leverage-changed] (:errors r)))
             (is (empty? (sim/order-sends s)) "nothing is sent")
             (done)))
          (.catch (fail done))))))

(deftest margin-that-shrank-since-review-refuses-the-send-test
  (async done
    (let [s (fresh)
          venue (atom (assoc flat :withdrawable "50"))
          d (deps s venue (atom []) flat)
          plan (frozen-plan s flat)]
      (-> (place/place! d {:frozen-plan plan :frozen-scope scope})
          (.then
           (fn [r]
             (is (= :admission-failed (:status r)))
             (is (= [:guard/plan-not-admissible] (:errors r)))
             (is (empty? (sim/order-sends s)))
             (is (empty? (filterv #(= :campaign (:kind %)) (sim/records s))) "no campaign without a placement")
             (done)))
          (.catch (fail done))))))

(deftest campaign-baseline-reads-the-position-test
  (is (= "-2.5" (campaign/base-total {:perps {coin {:position "-2.5"}}} scope)))
  (is (nil? (campaign/base-total {:perps {}} scope))))

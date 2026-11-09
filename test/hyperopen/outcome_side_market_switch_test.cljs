(ns hyperopen.outcome-side-market-switch-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.asset-selector.actions :as asset-actions]
            [hyperopen.order.actions :as order-actions]
            [hyperopen.state.trading :as trading]))

(defn- save-many-path-values
  [effects]
  (-> effects first second))

(defn- saved-path-map
  [effects]
  (into {} (save-many-path-values effects)))

(defn- path-value
  [effects target-path]
  (get (saved-path-map effects) target-path))

(def ^:private nba-finals-market
  {:key "outcome:142"
   :coin "#1420"
   :market-type :outcome
   :outcome-sides [{:side-index 0
                    :side-name "San Antonio"
                    :coin "#1420"
                    :asset-id 100001420
                    :mark 0.3742
                    :markRaw "0.3742"}
                   {:side-index 1
                    :side-name "New York"
                    :coin "#1421"
                    :asset-id 100001421
                    :mark 0.6176
                    :markRaw "0.6176"}]
   :outcome-side-aliases {"#1420" {:outcome-id 142
                                   :side-index 0
                                   :sibling-coins ["#1420" "#1421"]}
                          "#1421" {:outcome-id 142
                                   :side-index 1
                                   :sibling-coins ["#1420" "#1421"]}}
   :outcome-subscription-coins ["#1420" "#1421"]})

(defn- outcome-state
  []
  {:active-asset "#1420"
   :active-market nba-finals-market
   :asset-selector {:market-by-key {"outcome:142" nba-finals-market}}
   :order-form (assoc (trading/default-order-form)
                      :type :limit
                      :side :buy
                      :outcome-side 0)})

(deftest select-outcome-side-asset-syncs-active-market-and-form-side-test
  (let [effects (asset-actions/select-asset (outcome-state) "#1421")
        active-market (path-value effects [:active-market])]
    (is (= "#1421" (:coin active-market)))
    (is (= 1 (:outcome-side-index active-market)))
    (is (= 100001421 (:asset-id active-market)))
    (is (= 0.6176 (:mark active-market)))
    (is (= "#1421" (path-value effects [:active-asset])))
    (is (= "#1421" (path-value effects [:selected-asset])))
    (is (= 1 (path-value effects [:order-form :outcome-side])))
    (is (= [[:effects/unsubscribe-active-asset "#1420"]
            [:effects/unsubscribe-orderbook "#1420"]
            [:effects/unsubscribe-trades "#1420"]
            [:effects/subscribe-active-asset "#1421"]
            [:effects/subscribe-orderbook "#1421"]
            [:effects/subscribe-trades "#1421"]
            [:effects/sync-active-asset-funding-predictability "#1421"]]
           (subvec effects 2)))))

(deftest update-order-form-outcome-side-switches-active-side-market-test
  (let [effects (order-actions/update-order-form (outcome-state)
                                                 [:outcome-side]
                                                 1)
        saved (saved-path-map effects)]
    (is (= "#1421" (get saved [:active-asset])))
    (is (= "#1421" (get saved [:selected-asset])))
    (is (= "#1421" (get-in saved [[:active-market] :coin])))
    (is (= 1 (get saved [:order-form :outcome-side])))
    (is (some #{[:effects/subscribe-active-asset "#1421"]} effects))))

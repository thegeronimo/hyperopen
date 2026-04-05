(ns hyperopen.core-bootstrap.asset-selector-actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.core.compat :as core]
            [hyperopen.core-bootstrap.test-support.effect-extractors :as effect-extractors]
            [hyperopen.runtime.effect-adapters :as effect-adapters]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.state.trading :as trading]))

(def ^:private select-asset-heavy-effect-ids
  #{:effects/unsubscribe-active-asset
    :effects/unsubscribe-orderbook
    :effects/unsubscribe-trades
    :effects/subscribe-active-asset
    :effects/subscribe-orderbook
    :effects/subscribe-trades
    :effects/sync-active-asset-funding-predictability})

(deftest mark-loaded-asset-icon-promotes-key-to-loaded-and-clears-missing-test
  (let [state {:asset-selector {:loaded-icons #{}
                                :missing-icons #{"perp:BTC"}}}
        effects (core/mark-loaded-asset-icon state "perp:BTC")]
    (is (= [[:effects/queue-asset-icon-status {:market-key "perp:BTC"
                                               :icon-status :loaded}]]
           effects))))

(deftest mark-loaded-asset-icon-noop-when-key-already-loaded-and-not-missing-test
  (let [state {:asset-selector {:loaded-icons #{"perp:BTC"}
                                :missing-icons #{}}}
        effects (core/mark-loaded-asset-icon state "perp:BTC")]
    (is (= [] effects))))

(deftest mark-missing-asset-icon-promotes-key-to-missing-and-clears-loaded-test
  (let [state {:asset-selector {:loaded-icons #{"perp:BTC"}
                                :missing-icons #{}}}
        effects (core/mark-missing-asset-icon state "perp:BTC")]
    (is (= [[:effects/queue-asset-icon-status {:market-key "perp:BTC"
                                               :icon-status :missing}]]
           effects))))

(deftest mark-missing-asset-icon-noop-when-key-already-missing-and-not-loaded-test
  (let [state {:asset-selector {:loaded-icons #{}
                                :missing-icons #{"perp:BTC"}}}
        effects (core/mark-missing-asset-icon state "perp:BTC")]
    (is (= [] effects))))

(deftest apply-asset-icon-status-updates-merges-statuses-test
  (let [state {:asset-selector {:loaded-icons #{"perp:BTC"}
                                :missing-icons #{"perp:ETH"}}}
        result (core/apply-asset-icon-status-updates state {"perp:BTC" :missing
                                                            "perp:ETH" :loaded
                                                            "perp:SOL" :loaded})]
    (is (= #{"perp:ETH" "perp:SOL"} (:loaded-icons result)))
    (is (= #{"perp:BTC"} (:missing-icons result)))
    (is (= true (:changed? result)))))

(deftest queue-asset-icon-status-batches-updates-into-single-flush-test
  (let [store (atom {:asset-selector {:loaded-icons #{}
                                      :missing-icons #{}}})
        scheduled-callback (atom nil)]
    (swap! runtime-state/runtime assoc-in [:asset-icons :pending] {})
    (swap! runtime-state/runtime assoc-in [:asset-icons :flush-handle] nil)
    (with-redefs [effect-adapters/schedule-animation-frame! (fn [f]
                                                               (reset! scheduled-callback f)
                                                               :raf-id)]
      (core/queue-asset-icon-status nil store {:market-key "perp:BTC" :icon-status :loaded})
      (core/queue-asset-icon-status nil store {:market-key "perp:BTC" :icon-status :missing})
      (core/queue-asset-icon-status nil store {:market-key "perp:ETH" :icon-status :loaded})
      (is (fn? @scheduled-callback))
      (@scheduled-callback)
      (is (= #{"perp:ETH"} (get-in @store [:asset-selector :loaded-icons])))
      (is (= #{"perp:BTC"} (get-in @store [:asset-selector :missing-icons])))
      (is (= {} (get-in @runtime-state/runtime [:asset-icons :pending])))
      (is (nil? (get-in @runtime-state/runtime [:asset-icons :flush-handle]))))))

(deftest maybe-increase-asset-selector-render-limit-expands-near-bottom-test
  (let [state {:asset-selector {:markets (vec (repeat 400 {:key "perp:T"}))
                                :render-limit 120}}
        effects (core/maybe-increase-asset-selector-render-limit state 2304)]
    (is (= [[:effects/save-many [[[:asset-selector :render-limit] 200]
                                 [[:asset-selector :scroll-top] 2304]]]
            [:effects/sync-asset-selector-active-ctx-subscriptions]]
           effects))))

(deftest maybe-increase-asset-selector-render-limit-noop-when-not-near-bottom-test
  (let [state {:asset-selector {:markets (vec (repeat 400 {:key "perp:T"}))
                                :render-limit 120}}
        effects (core/maybe-increase-asset-selector-render-limit state 0)]
    (is (= [] effects))))

(deftest increase-asset-selector-render-limit-steps-forward-test
  (let [state {:asset-selector {:markets (vec (repeat 400 {:key "perp:T"}))
                                :render-limit 120}}
        effects (core/increase-asset-selector-render-limit state)]
    (is (= [[:effects/save [:asset-selector :render-limit] 200]
            [:effects/sync-asset-selector-active-ctx-subscriptions]]
           effects))))

(deftest show-all-asset-selector-markets-expands-to-total-test
  (let [state {:asset-selector {:markets (vec (repeat 622 {:key "perp:T"}))
                                :render-limit 120}}
        effects (core/show-all-asset-selector-markets state)]
    (is (= [[:effects/save [:asset-selector :render-limit] 622]
            [:effects/sync-asset-selector-active-ctx-subscriptions]]
           effects))))


(deftest select-asset-closes-dropdown-first-and-removes-duplicate-effects-test
  (let [market {:key :perp/BTC
                :coin "BTC"}
        effects (core/select-asset {:active-asset "ETH"
                                    :asset-selector {:visible-dropdown :asset-selector}
                                    :orderbook-ui {:price-aggregation-dropdown-visible? true
                                                   :size-unit-dropdown-visible? true}}
                                   market)]
    (is (= [[:effects/save-many [[[:asset-selector :visible-dropdown] nil]
                                 [[:asset-selector :search-term] ""]
                                 [[:asset-selector :scroll-top] 0]
                                 [[:asset-selector :render-limit] 120]
                                 [[:asset-selector :last-render-limit-increase-ms] nil]
                                 [[:asset-selector :highlighted-market-key] nil]
                                 [[:orderbook-ui :price-aggregation-dropdown-visible?] false]
                                 [[:orderbook-ui :size-unit-dropdown-visible?] false]
                                 [[:active-market] market]
                                 [[:order-form-ui] (trading/default-order-form-ui)]
                                 [[:order-form-runtime] (trading/default-order-form-runtime)]]]
            [:effects/sync-asset-selector-active-ctx-subscriptions]
            [:effects/unsubscribe-active-asset "ETH"]
            [:effects/unsubscribe-orderbook "ETH"]
            [:effects/unsubscribe-trades "ETH"]
            [:effects/subscribe-active-asset "BTC"]
            [:effects/subscribe-orderbook "BTC"]
            [:effects/subscribe-trades "BTC"]
            [:effects/sync-active-asset-funding-predictability "BTC"]]
           effects))
    (is (not-any? #(= (first %) :effects/fetch-candle-snapshot) effects))
    (is (effect-extractors/projection-before-heavy? effects select-asset-heavy-effect-ids))
    (is (effect-extractors/phase-order-valid? effects select-asset-heavy-effect-ids))
    (is (empty? (effect-extractors/duplicate-heavy-effect-ids effects select-asset-heavy-effect-ids)))
    (is (not-any? #(and (= (first %) :effects/save)
                        (= (second %) [:asset-selector :visible-dropdown]))
                  effects))))

(deftest select-asset-on-trade-route-syncs-router-before-heavy-effects-test
  (let [market {:key :perp/BTC
                :coin "BTC"}
        effects (core/select-asset {:active-asset "ETH"
                                    :router {:path "/trade"}
                                    :asset-selector {:visible-dropdown :asset-selector}
                                    :orderbook-ui {:price-aggregation-dropdown-visible? true
                                                   :size-unit-dropdown-visible? true}}
                                   market)]
    (is (= [[:effects/save [:router :path] "/trade/BTC"]
            [:effects/push-state "/trade?market=BTC"]]
           (subvec effects 2 4)))
    (is (= [[:effects/unsubscribe-active-asset "ETH"]
            [:effects/unsubscribe-orderbook "ETH"]
            [:effects/unsubscribe-trades "ETH"]
            [:effects/subscribe-active-asset "BTC"]
            [:effects/subscribe-orderbook "BTC"]
            [:effects/subscribe-trades "BTC"]
            [:effects/sync-active-asset-funding-predictability "BTC"]]
           (subvec effects 4)))
    (is (effect-extractors/projection-before-heavy? effects select-asset-heavy-effect-ids))
    (is (effect-extractors/phase-order-valid? effects select-asset-heavy-effect-ids))
    (is (empty? (effect-extractors/duplicate-heavy-effect-ids effects select-asset-heavy-effect-ids)))))

(deftest select-asset-without-current-asset-still-batches-immediate-ui-close-test
  (let [market {:key :perp/SOL
                :coin "SOL"}
        effects (core/select-asset {:active-asset nil} market)]
    (is (= [[:effects/save-many [[[:asset-selector :visible-dropdown] nil]
                                 [[:asset-selector :search-term] ""]
                                 [[:asset-selector :scroll-top] 0]
                                 [[:asset-selector :render-limit] 120]
                                 [[:asset-selector :last-render-limit-increase-ms] nil]
                                 [[:asset-selector :highlighted-market-key] nil]
                                 [[:orderbook-ui :price-aggregation-dropdown-visible?] false]
                                 [[:orderbook-ui :size-unit-dropdown-visible?] false]
                                 [[:active-market] market]
                                 [[:order-form-ui] (trading/default-order-form-ui)]
                                 [[:order-form-runtime] (trading/default-order-form-runtime)]]]
            [:effects/sync-asset-selector-active-ctx-subscriptions]
            [:effects/subscribe-active-asset "SOL"]
            [:effects/subscribe-orderbook "SOL"]
            [:effects/subscribe-trades "SOL"]
            [:effects/sync-active-asset-funding-predictability "SOL"]]
           effects))))

(deftest select-asset-resolves-legacy-spot-id-to-canonical-coin-test
  (let [resolved-market {:key "spot:@1"
                         :coin "@1"
                         :symbol "HYPE/USDC"
                         :market-type :spot}
        effects (core/select-asset {:active-asset "ETH"
                                    :asset-selector {:visible-dropdown :asset-selector
                                                     :market-by-key {"spot:@1" resolved-market}}
                                    :orderbook-ui {:price-aggregation-dropdown-visible? true
                                                   :size-unit-dropdown-visible? true}}
                                   "1")]
    (is (= [[:effects/save-many [[[:asset-selector :visible-dropdown] nil]
                                 [[:asset-selector :search-term] ""]
                                 [[:asset-selector :scroll-top] 0]
                                 [[:asset-selector :render-limit] 120]
                                 [[:asset-selector :last-render-limit-increase-ms] nil]
                                 [[:asset-selector :highlighted-market-key] nil]
                                 [[:orderbook-ui :price-aggregation-dropdown-visible?] false]
                                 [[:orderbook-ui :size-unit-dropdown-visible?] false]
                                 [[:active-market] resolved-market]
                                 [[:order-form-ui] (trading/default-order-form-ui)]
                                 [[:order-form-runtime] (trading/default-order-form-runtime)]]]
            [:effects/sync-asset-selector-active-ctx-subscriptions]
            [:effects/unsubscribe-active-asset "ETH"]
            [:effects/unsubscribe-orderbook "ETH"]
            [:effects/unsubscribe-trades "ETH"]
            [:effects/subscribe-active-asset "@1"]
            [:effects/subscribe-orderbook "@1"]
            [:effects/subscribe-trades "@1"]
            [:effects/sync-active-asset-funding-predictability "@1"]]
           effects))
    (is (= :effects/save-many (ffirst effects)))
    (is (not-any? #(= (first %) :effects/fetch-candle-snapshot) effects))))

(deftest select-asset-resolves-spot-base-token-to-usdc-pair-test
  (let [resolved-market {:key "spot:MEOW/USDC"
                         :coin "MEOW/USDC"
                         :symbol "MEOW/USDC"
                         :base "MEOW"
                         :quote "USDC"
                         :market-type :spot}
        effects (core/select-asset {:active-asset "ETH"
                                    :asset-selector {:visible-dropdown :asset-selector
                                                     :market-by-key {"spot:MEOW/USDC" resolved-market}}
                                    :orderbook-ui {:price-aggregation-dropdown-visible? true
                                                   :size-unit-dropdown-visible? true}}
                                   "MEOW")
        save-many-path-values (-> effects first second)
        saved-active-market (some (fn [[path value]]
                                    (when (= [:active-market] path)
                                      value))
                                  save-many-path-values)]
    (is (= resolved-market saved-active-market))
    (is (some #{[:effects/unsubscribe-active-asset "ETH"]} effects))
    (is (some #{[:effects/unsubscribe-orderbook "ETH"]} effects))
    (is (some #{[:effects/unsubscribe-trades "ETH"]} effects))
    (is (some #{[:effects/subscribe-active-asset "MEOW/USDC"]} effects))
    (is (some #{[:effects/subscribe-orderbook "MEOW/USDC"]} effects))
    (is (some #{[:effects/subscribe-trades "MEOW/USDC"]} effects))
    (is (some #{[:effects/sync-active-asset-funding-predictability "MEOW/USDC"]} effects))
    (is (not (some #{[:effects/subscribe-active-asset "MEOW"]} effects)))))

(deftest select-asset-resets-price-input-focus-lock-to-dynamic-state-test
  (let [market {:key :perp/BTC
                :coin "BTC"}
        order-form (assoc (trading/default-order-form)
                          :type :limit
                          :price "70155")
        effects (core/select-asset {:active-asset "ETH"
                                    :order-form order-form
                                    :order-form-ui {:price-input-focused? true}
                                    :asset-selector {:visible-dropdown :asset-selector}
                                    :orderbook-ui {:price-aggregation-dropdown-visible? true
                                                   :size-unit-dropdown-visible? true}}
                                   market)
        save-many-path-values (-> effects first second)
        saved-order-form (some (fn [[path value]]
                                 (when (= [:order-form] path)
                                   value))
                               save-many-path-values)
        saved-order-form-ui (some (fn [[path value]]
                                    (when (= [:order-form-ui] path)
                                      value))
                                  save-many-path-values)]
    (is (= :effects/save-many (ffirst effects)))
    (is (some? saved-order-form))
    (is (some? saved-order-form-ui))
    (is (= false (:price-input-focused? saved-order-form-ui)))
    (is (= "" (:price saved-order-form)))))

(ns hyperopen.core-bootstrap-test
  (:require [cljs.test :refer-macros [async deftest is use-fixtures]]
            [hyperopen.api :as api]
            [hyperopen.core :as core]
            [hyperopen.state.trading :as trading]
            [hyperopen.wallet.address-watcher :as address-watcher]
            [hyperopen.websocket.active-asset-ctx :as active-ctx]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.websocket.orderbook :as orderbook]
            [hyperopen.websocket.trades :as trades]
            [hyperopen.websocket.user :as user-ws]
            [hyperopen.websocket.webdata2 :as webdata2]))

(defn- reset-startup-runtime! []
  (reset! @#'hyperopen.core/startup-runtime
          {:deferred-scheduled? false
           :bootstrapped-address nil
           :summary-logged? false}))

(use-fixtures
  :each
  {:before (fn []
             (reset-startup-runtime!)
             (swap! core/store assoc :active-asset nil))
   :after (fn []
            (reset-startup-runtime!))})

(defn- extract-saved-order-form [effects]
  (or (some (fn [effect]
              (when (= :effects/save-many (first effect))
                (some (fn [[path value]]
                        (when (= [:order-form] path) value))
                      (second effect))))
            effects)
      (some (fn [effect]
              (when (and (= :effects/save (first effect))
                         (= [:order-form] (second effect)))
                (nth effect 2)))
            effects)))

(deftest initialize-remote-data-streams-phased-bootstrap-test
  (let [phases (atom [])
        critical-fetches (atom 0)
        deferred-callback (atom nil)]
    (with-redefs [ws-client/init-connection! (fn [_] nil)
                  active-ctx/init! (fn [_] nil)
                  orderbook/init! (fn [_] nil)
                  trades/init! (fn [_] nil)
                  user-ws/init! (fn [_] nil)
                  webdata2/init! (fn [_] nil)
                  address-watcher/init-with-webdata2! (fn [& _] nil)
                  address-watcher/add-handler! (fn [& _] nil)
                  address-watcher/sync-current-address! (fn [& _] nil)
                  api/fetch-asset-contexts! (fn fetch-asset-contexts-mock
                                              ([store]
                                               (fetch-asset-contexts-mock store {}))
                                              ([_ _]
                                               (swap! critical-fetches inc)
                                               (js/Promise.resolve nil)))
                  api/fetch-asset-selector-markets! (fn fetch-asset-selector-markets-mock
                                                      ([store]
                                                       (fetch-asset-selector-markets-mock store {:phase :full}))
                                                      ([_ opts]
                                                       (swap! phases conj (:phase opts))
                                                       (js/Promise.resolve [])))
                  hyperopen.core/schedule-idle-or-timeout! (fn [f]
                                                              (reset! deferred-callback f)
                                                              :scheduled)]
      (core/initialize-remote-data-streams!)
      (is (= 1 @critical-fetches))
      (is (= [:bootstrap] @phases))
      (is (fn? @deferred-callback))
      (@deferred-callback)
      (is (= [:bootstrap :full] @phases)))))

(deftest account-bootstrap-two-stage-and-guarded-test
  (async done
    (let [stage-a-calls (atom [])
          stage-b-calls (atom [])
          original-fetch-open-orders api/fetch-frontend-open-orders!
          original-fetch-user-fills api/fetch-user-fills!
          original-fetch-spot-state api/fetch-spot-clearinghouse-state!
          original-ensure-perp-dexs api/ensure-perp-dexs!
          original-stage-b hyperopen.core/stage-b-account-bootstrap!]
      (swap! core/store assoc-in [:wallet :address] "0xabc")
      (set! api/fetch-frontend-open-orders!
            (fn fetch-frontend-open-orders-mock
              ([store address]
               (fetch-frontend-open-orders-mock store address nil {}))
              ([store address dex-or-opts]
               (fetch-frontend-open-orders-mock store address dex-or-opts {}))
              ([store address dex opts]
               (swap! stage-a-calls conj [:open-orders [store address dex opts]])
               (js/Promise.resolve nil))))
      (set! api/fetch-user-fills!
            (fn fetch-user-fills-mock
              ([store address]
               (fetch-user-fills-mock store address {}))
              ([store address opts]
               (swap! stage-a-calls conj [:fills [store address opts]])
               (js/Promise.resolve nil))))
      (set! api/fetch-spot-clearinghouse-state!
            (fn fetch-spot-clearinghouse-state-mock
              ([store address]
               (fetch-spot-clearinghouse-state-mock store address {}))
              ([store address opts]
               (swap! stage-a-calls conj [:spot [store address opts]])
               (js/Promise.resolve nil))))
      (set! api/ensure-perp-dexs!
            (fn ensure-perp-dexs-mock
              ([store]
               (ensure-perp-dexs-mock store {}))
              ([_ _]
               (js/Promise.resolve ["dex-1" "dex-2"]))))
      (set! hyperopen.core/stage-b-account-bootstrap!
            (fn [address dexs]
              (swap! stage-b-calls conj [address dexs])))
      (letfn [(restore! []
                (set! api/fetch-frontend-open-orders! original-fetch-open-orders)
                (set! api/fetch-user-fills! original-fetch-user-fills)
                (set! api/fetch-spot-clearinghouse-state! original-fetch-spot-state)
                (set! api/ensure-perp-dexs! original-ensure-perp-dexs)
                (set! hyperopen.core/stage-b-account-bootstrap! original-stage-b))]
        (@#'hyperopen.core/bootstrap-account-data! "0xabc")
        (js/setTimeout
         (fn []
           (is (= 3 (count @stage-a-calls)))
           (is (= [["0xabc" ["dex-1" "dex-2"]]] @stage-b-calls))
           ;; Same address should not trigger stage A/B again.
           (@#'hyperopen.core/bootstrap-account-data! "0xabc")
           (js/setTimeout
            (fn []
              (is (= 3 (count @stage-a-calls)))
              (is (= 1 (count @stage-b-calls)))
              (restore!)
              (done))
            0))
         0)))))

(deftest select-asset-closes-dropdown-first-and-removes-duplicate-effects-test
  (let [market {:key :perp/BTC
                :coin "BTC"}
        effects (core/select-asset {:active-asset "ETH"
                                    :asset-selector {:visible-dropdown :asset-selector}
                                    :orderbook-ui {:price-aggregation-dropdown-visible? true
                                                   :size-unit-dropdown-visible? true}}
                                   market)]
    (is (= [[:effects/save-many [[[:asset-selector :visible-dropdown] nil]
                                 [[:orderbook-ui :price-aggregation-dropdown-visible?] false]
                                 [[:orderbook-ui :size-unit-dropdown-visible?] false]
                                 [[:active-market] market]]]
            [:effects/unsubscribe-active-asset "ETH"]
            [:effects/unsubscribe-orderbook "ETH"]
            [:effects/unsubscribe-trades "ETH"]
            [:effects/subscribe-active-asset "BTC"]
            [:effects/subscribe-orderbook "BTC"]
            [:effects/subscribe-trades "BTC"]]
           effects))
    (is (not-any? #(= (first %) :effects/fetch-candle-snapshot) effects))
    (is (not-any? #(and (= (first %) :effects/save)
                        (= (second %) [:asset-selector :visible-dropdown]))
                  effects))))

(deftest select-asset-without-current-asset-still-batches-immediate-ui-close-test
  (let [market {:key :perp/SOL
                :coin "SOL"}
        effects (core/select-asset {:active-asset nil} market)]
    (is (= [[:effects/save-many [[[:asset-selector :visible-dropdown] nil]
                                 [[:orderbook-ui :price-aggregation-dropdown-visible?] false]
                                 [[:orderbook-ui :size-unit-dropdown-visible?] false]
                                 [[:active-market] market]]]
            [:effects/subscribe-active-asset "SOL"]
            [:effects/subscribe-orderbook "SOL"]
            [:effects/subscribe-trades "SOL"]]
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
                                 [[:orderbook-ui :price-aggregation-dropdown-visible?] false]
                                 [[:orderbook-ui :size-unit-dropdown-visible?] false]
                                 [[:active-market] resolved-market]]]
            [:effects/unsubscribe-active-asset "ETH"]
            [:effects/unsubscribe-orderbook "ETH"]
            [:effects/unsubscribe-trades "ETH"]
            [:effects/subscribe-active-asset "@1"]
            [:effects/subscribe-orderbook "@1"]
            [:effects/subscribe-trades "@1"]]
           effects))
    (is (= :effects/save-many (ffirst effects)))
    (is (not-any? #(= (first %) :effects/fetch-candle-snapshot) effects))))

(deftest select-order-entry-mode-market-emits-single-batched-projection-test
  (let [state {:order-form (assoc (trading/default-order-form)
                                  :entry-mode :pro
                                  :type :stop-market
                                  :pro-order-type-dropdown-open? true)}
        effects (core/select-order-entry-mode state :market)
        saved-form (extract-saved-order-form effects)]
    (is (= 1 (count effects)))
    (is (= :effects/save-many (ffirst effects)))
    (is (map? saved-form))
    (is (= :market (:entry-mode saved-form)))
    (is (= :market (:type saved-form)))
    (is (= false (:pro-order-type-dropdown-open? saved-form)))))

(deftest select-order-entry-mode-limit-forces-limit-type-test
  (let [state {:order-form (assoc (trading/default-order-form)
                                  :type :stop-limit
                                  :entry-mode :pro
                                  :pro-order-type-dropdown-open? true)}
        effects (core/select-order-entry-mode state :limit)
        saved-form (extract-saved-order-form effects)]
    (is (= 1 (count effects)))
    (is (= :effects/save-many (ffirst effects)))
    (is (map? saved-form))
    (is (= :limit (:entry-mode saved-form)))
    (is (= :limit (:type saved-form)))
    (is (= false (:pro-order-type-dropdown-open? saved-form)))))

(deftest select-order-entry-mode-pro-sets-pro-entry-and-normalized-pro-type-test
  (let [state {:order-form (assoc (trading/default-order-form) :type :limit)}
        effects (core/select-order-entry-mode state :pro)
        saved-form (extract-saved-order-form effects)]
    (is (= 1 (count effects)))
    (is (= :effects/save-many (ffirst effects)))
    (is (map? saved-form))
    (is (= :pro (:entry-mode saved-form)))
    (is (= :stop-market (:type saved-form)))))

(deftest select-pro-order-type-closes-dropdown-and-persists-pro-selection-test
  (let [state {:order-form (assoc (trading/default-order-form)
                                  :entry-mode :pro
                                  :type :stop-market
                                  :pro-order-type-dropdown-open? true)}
        effects (core/select-pro-order-type state :scale)
        saved-form (extract-saved-order-form effects)]
    (is (= 1 (count effects)))
    (is (= :effects/save-many (ffirst effects)))
    (is (= :pro (:entry-mode saved-form)))
    (is (= :scale (:type saved-form)))
    (is (= false (:pro-order-type-dropdown-open? saved-form)))))

(deftest toggle-pro-order-type-dropdown-flips-open-flag-test
  (let [closed-state {:order-form (assoc (trading/default-order-form) :pro-order-type-dropdown-open? false)}
        open-state {:order-form (assoc (trading/default-order-form) :pro-order-type-dropdown-open? true)}
        closed-effects (core/toggle-pro-order-type-dropdown closed-state)
        open-effects (core/toggle-pro-order-type-dropdown open-state)]
    (is (= [[:effects/save-many [[[:order-form :pro-order-type-dropdown-open?] true]]]]
           closed-effects))
    (is (= [[:effects/save-many [[[:order-form :pro-order-type-dropdown-open?] false]]]]
           open-effects))))

(deftest close-pro-order-type-dropdown-forces-open-flag-false-test
  (let [state {:order-form (assoc (trading/default-order-form) :pro-order-type-dropdown-open? true)}
        effects (core/close-pro-order-type-dropdown state)]
    (is (= [[:effects/save-many [[[:order-form :pro-order-type-dropdown-open?] false]]]]
           effects))))

(deftest handle-pro-order-type-dropdown-keydown-closes-only-on-escape-test
  (let [state {:order-form (assoc (trading/default-order-form) :pro-order-type-dropdown-open? true)}
        escape-effects (core/handle-pro-order-type-dropdown-keydown state "Escape")
        enter-effects (core/handle-pro-order-type-dropdown-keydown state "Enter")]
    (is (= [[:effects/save-many [[[:order-form :pro-order-type-dropdown-open?] false]]]]
           escape-effects))
    (is (= [] enter-effects))))

(deftest set-order-size-percent-emits-single-batched-projection-and-no-network-effects-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC" :mark 100 :maxLeverage 40 :szDecimals 4}
               :orderbooks {"BTC" {:bids [{:px "99"}]
                                   :asks [{:px "101"}]}}
               :webdata2 {:clearinghouseState {:marginSummary {:accountValue "1000"
                                                               :totalMarginUsed "250"}}}
               :order-form (assoc (trading/default-order-form) :type :limit :price "100")}
        effects (core/set-order-size-percent state 25)
        saved-form (-> effects first second first second)]
    (is (= 1 (count effects)))
    (is (= :effects/save-many (ffirst effects)))
    (is (= 25 (:size-percent saved-form)))
    (is (not-any? #(= (first %) :effects/api-submit-order) effects))
    (is (not-any? #(= (first %) :effects/subscribe-orderbook) effects))))

(deftest set-order-size-display-preserves-user-entered-value-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC" :mark 100 :maxLeverage 40 :szDecimals 4}
               :orderbooks {"BTC" {:bids [{:px "99"}]
                                   :asks [{:px "101"}]}}
               :webdata2 {:clearinghouseState {:marginSummary {:accountValue "1000"
                                                               :totalMarginUsed "250"}}}
               :order-form (assoc (trading/default-order-form) :type :limit :price "")}
        effects (core/set-order-size-display state "202")
        saved-form (-> effects first second first second)]
    (is (= 1 (count effects)))
    (is (= :effects/save-many (ffirst effects)))
    (is (= "202" (:size-display saved-form)))
    (is (= "2" (:size saved-form)))
    (is (not-any? #(= (first %) :effects/api-submit-order) effects))))

(deftest set-order-size-display-truncates-canonical-size-to-market-decimals-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC" :mark 70179 :maxLeverage 40 :szDecimals 5}
               :orderbooks {"BTC" {:bids [{:px "70150"}]
                                   :asks [{:px "70160"}]}}
               :webdata2 {:clearinghouseState {:marginSummary {:accountValue "1000"
                                                               :totalMarginUsed "250"}}}
               :order-form (assoc (trading/default-order-form) :type :limit :price "70179")}
        effects (core/set-order-size-display state "2")
        saved-form (-> effects first second first second)
        summary (trading/order-summary state saved-form)]
    (is (= "2" (:size-display saved-form)))
    (is (= "0.00002" (:size saved-form)))
    (is (<= (js/Math.abs (- 1.4 (:order-value summary))) 0.01))))

(deftest set-order-price-to-mid-uses-best-bid-ask-midpoint-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC" :mark 70000 :maxLeverage 40 :szDecimals 4}
               :orderbooks {"BTC" {:bids [{:px "70120"} {:px "70150"} {:px "70090"}]
                                   :asks [{:px "70240"} {:px "70160"} {:px "70210"}]}}
               :order-form (assoc (trading/default-order-form) :type :limit :price "")}
        effects (core/set-order-price-to-mid state)
        saved-form (-> effects first second first second)]
    (is (= 1 (count effects)))
    (is (= :effects/save-many (ffirst effects)))
    (is (= "70155" (:price saved-form)))
    (is (not-any? #(= (first %) :effects/api-submit-order) effects))))

(deftest submit-order-emits-single-api-submit-order-effect-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC" :market-type :perp}
               :asset-contexts {:BTC {:idx 0}}
               :orderbooks {"BTC" {:bids [{:px "99"}]
                                   :asks [{:px "101"}]}}
               :order-form (assoc (trading/default-order-form)
                                  :type :limit
                                  :side :buy
                                  :size "1"
                                  :price "100")}
        effects (core/submit-order state)
        api-submit-effects (filter #(= (first %) :effects/api-submit-order) effects)]
    (is (= 1 (count api-submit-effects)))))

(deftest submit-order-limit-with-blank-price-uses-fallback-and-emits-single-submit-effect-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC" :market-type :perp}
               :asset-contexts {:BTC {:idx 0}}
               :orderbooks {"BTC" {:bids [{:px "99"}]
                                   :asks [{:px "101"}]}}
               :order-form (assoc (trading/default-order-form)
                                  :type :limit
                                  :side :buy
                                  :size "1"
                                  :price "")}
        effects (core/submit-order state)
        api-submit-effects (filter #(= (first %) :effects/api-submit-order) effects)
        saved-form (some (fn [effect]
                           (when (and (= :effects/save (first effect))
                                      (= [:order-form] (second effect)))
                             (nth effect 2)))
                         effects)]
    (is (= 1 (count api-submit-effects)))
    (is (seq (:price saved-form)))))

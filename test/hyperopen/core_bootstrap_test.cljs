(ns hyperopen.core-bootstrap-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [async deftest is use-fixtures]]
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

(defn- with-test-local-storage [f]
  (let [original-local-storage (.-localStorage js/globalThis)
        storage (atom {})]
    (set! (.-localStorage js/globalThis)
          #js {:setItem (fn [key value]
                          (swap! storage assoc (str key) (str value)))
               :getItem (fn [key]
                          (get @storage (str key)))
               :removeItem (fn [key]
                             (swap! storage dissoc (str key)))
               :clear (fn []
                        (reset! storage {}))})
    (try
      (f)
      (finally
        (set! (.-localStorage js/globalThis) original-local-storage)))))

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
          original-fetch-and-merge-funding-history hyperopen.core/fetch-and-merge-funding-history!
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
      (set! hyperopen.core/fetch-and-merge-funding-history!
            (fn [_store address opts]
              (swap! stage-a-calls conj [:fundings [address opts]])
              (js/Promise.resolve nil)))
      (set! hyperopen.core/stage-b-account-bootstrap!
            (fn [address dexs]
              (swap! stage-b-calls conj [address dexs])))
      (letfn [(restore! []
                (set! api/fetch-frontend-open-orders! original-fetch-open-orders)
                (set! api/fetch-user-fills! original-fetch-user-fills)
                (set! api/fetch-spot-clearinghouse-state! original-fetch-spot-state)
                (set! api/ensure-perp-dexs! original-ensure-perp-dexs)
                (set! hyperopen.core/fetch-and-merge-funding-history! original-fetch-and-merge-funding-history)
                (set! hyperopen.core/stage-b-account-bootstrap! original-stage-b))]
        (@#'hyperopen.core/bootstrap-account-data! "0xabc")
        (js/setTimeout
         (fn []
           (is (= 4 (count @stage-a-calls)))
           (is (= [["0xabc" ["dex-1" "dex-2"]]] @stage-b-calls))
           ;; Same address should not trigger stage A/B again.
           (@#'hyperopen.core/bootstrap-account-data! "0xabc")
           (js/setTimeout
            (fn []
              (is (= 4 (count @stage-a-calls)))
              (is (= 1 (count @stage-b-calls)))
              (restore!)
              (done))
            0))
            0)))))

(deftest select-account-info-tab-funding-history-saves-selection-before-fetch-test
  (let [state {:account-info {:selected-tab :balances
                              :funding-history {:filters {:coin-set #{}
                                                          :start-time-ms 0
                                                          :end-time-ms 1000}
                                                :request-id 2}}
               :orders {:fundings-raw []}}
        effects (core/select-account-info-tab state :funding-history)
        immediate (first effects)
        path-values (second immediate)]
    (is (= :effects/save-many (first immediate)))
    (is (= [:account-info :selected-tab]
           (-> path-values first first)))
    (is (= :funding-history
           (-> path-values first second)))
    (is (= [:effects/api-fetch-user-funding-history 3]
           (second effects)))))

(deftest apply-funding-history-filters-resets-pagination-and-refetches-only-on-time-range-change-test
  (let [base-state {:account-info {:funding-history {:filters {:coin-set #{}
                                                          :start-time-ms 1000
                                                          :end-time-ms 2000}
                                                    :draft-filters {:coin-set #{"BTC"}
                                                                    :start-time-ms 1000
                                                                    :end-time-ms 2000}
                                                    :page 4
                                                    :page-input "4"
                                                    :request-id 5}}
                    :orders {:fundings-raw []}}
        no-refetch (core/apply-funding-history-filters base-state)
        with-refetch (core/apply-funding-history-filters
                      (assoc-in base-state
                                [:account-info :funding-history :draft-filters :end-time-ms]
                                3000))]
    (is (some #(= [[:account-info :funding-history :page] 1] %)
              (-> no-refetch first second)))
    (is (some #(= [[:account-info :funding-history :page-input] "1"] %)
              (-> no-refetch first second)))
    (is (not-any? #(= :effects/api-fetch-user-funding-history (first %))
                  no-refetch))
    (is (some #(= :effects/api-fetch-user-funding-history (first %))
              with-refetch))))

(deftest sort-funding-history-toggles-direction-on-same-column-test
  (let [state {:account-info {:funding-history {:sort {:column "Time"
                                                       :direction :desc}
                                                :page 3
                                                :page-input "3"}}}
        effects (core/sort-funding-history state "Time")]
    (is (= [[:effects/save-many [[[:account-info :funding-history :sort]
                                  {:column "Time" :direction :asc}]
                                 [[:account-info :funding-history :page] 1]
                                 [[:account-info :funding-history :page-input] "1"]]]]
           effects))))

(deftest sort-funding-history-uses-mixed-default-direction-for-new-columns-test
  (let [state {:account-info {:funding-history {:sort {:column "Time"
                                                       :direction :desc}
                                                :page 2
                                                :page-input "2"}}}
        coin-effects (core/sort-funding-history state "Coin")
        payment-effects (core/sort-funding-history state "Payment")]
    (is (= [[:effects/save-many [[[:account-info :funding-history :sort]
                                  {:column "Coin" :direction :asc}]
                                 [[:account-info :funding-history :page] 1]
                                 [[:account-info :funding-history :page-input] "1"]]]]
           coin-effects))
    (is (= [[:effects/save-many [[[:account-info :funding-history :sort]
                                  {:column "Payment" :direction :desc}]
                                 [[:account-info :funding-history :page] 1]
                                 [[:account-info :funding-history :page-input] "1"]]]]
           payment-effects))))

(deftest view-all-funding-history-resets-pagination-before-fetch-test
  (let [state {:account-info {:funding-history {:filters {:coin-set #{"BTC"}
                                                          :start-time-ms 1000
                                                          :end-time-ms 2000}
                                                :request-id 3
                                                :page 7
                                                :page-input "7"}}
               :orders {:fundings-raw []}}
        effects (core/view-all-funding-history state)
        path-values (-> effects first second)]
    (is (some #(= [[:account-info :funding-history :page] 1] %) path-values))
    (is (some #(= [[:account-info :funding-history :page-input] "1"] %) path-values))
    (is (= [:effects/api-fetch-user-funding-history 4]
           (second effects)))))

(deftest funding-history-pagination-page-size-normalizes-and-persists-test
  (with-test-local-storage
    (fn []
      (let [state {:account-info {:funding-history {:page-size 25
                                                    :page 8
                                                    :page-input "8"}}}
            effects (core/set-funding-history-page-size state "100")]
        (is (= [[:effects/save-many [[[:account-info :funding-history :page-size] 100]
                                     [[:account-info :funding-history :page] 1]
                                     [[:account-info :funding-history :page-input] "1"]]]]
               effects))
        (is (= "100" (.getItem js/localStorage "funding-history-page-size")))
        (let [invalid-effects (core/set-funding-history-page-size state "13")]
          (is (= [[:effects/save-many [[[:account-info :funding-history :page-size] 50]
                                       [[:account-info :funding-history :page] 1]
                                       [[:account-info :funding-history :page-input] "1"]]]]
                 invalid-effects))
          (is (= "50" (.getItem js/localStorage "funding-history-page-size"))))))))

(deftest funding-history-pagination-set-page-clamps-and-syncs-input-test
  (let [state {:account-info {:funding-history {:page 2
                                                :page-input "2"}}}
        within (core/set-funding-history-page state 3 5)
        too-high (core/set-funding-history-page state 99 5)
        too-low (core/set-funding-history-page state -2 5)]
    (is (= [[:effects/save-many [[[:account-info :funding-history :page] 3]
                                 [[:account-info :funding-history :page-input] "3"]]]]
           within))
    (is (= [[:effects/save-many [[[:account-info :funding-history :page] 5]
                                 [[:account-info :funding-history :page-input] "5"]]]]
           too-high))
    (is (= [[:effects/save-many [[[:account-info :funding-history :page] 1]
                                 [[:account-info :funding-history :page-input] "1"]]]]
           too-low))))

(deftest funding-history-pagination-next-prev-and-input-apply-test
  (let [state {:account-info {:funding-history {:page 2
                                                :page-input "2"}}}
        next-effects (core/next-funding-history-page state 3)
        prev-effects (core/prev-funding-history-page state 3)
        at-end-effects (core/next-funding-history-page
                        {:account-info {:funding-history {:page 3 :page-input "3"}}}
                        3)
        typed-state {:account-info {:funding-history {:page 1 :page-input "12"}}}
        apply-effects (core/apply-funding-history-page-input typed-state 4)
        invalid-apply-effects (core/apply-funding-history-page-input
                               {:account-info {:funding-history {:page 1 :page-input "abc"}}}
                               4)
        keydown-effects (core/handle-funding-history-page-input-keydown typed-state "Enter" 4)
        keydown-nop (core/handle-funding-history-page-input-keydown typed-state "Escape" 4)]
    (is (= [[:effects/save-many [[[:account-info :funding-history :page] 3]
                                 [[:account-info :funding-history :page-input] "3"]]]]
           next-effects))
    (is (= [[:effects/save-many [[[:account-info :funding-history :page] 1]
                                 [[:account-info :funding-history :page-input] "1"]]]]
           prev-effects))
    (is (= [[:effects/save-many [[[:account-info :funding-history :page] 3]
                                 [[:account-info :funding-history :page-input] "3"]]]]
           at-end-effects))
    (is (= [[:effects/save-many [[[:account-info :funding-history :page] 4]
                                 [[:account-info :funding-history :page-input] "4"]]]]
           apply-effects))
    (is (= [[:effects/save-many [[[:account-info :funding-history :page] 1]
                                 [[:account-info :funding-history :page-input] "1"]]]]
           invalid-apply-effects))
    (is (= apply-effects keydown-effects))
    (is (= [] keydown-nop))))

(deftest restore-funding-history-pagination-settings-uses-defaults-and-stored-size-test
  (with-test-local-storage
    (fn []
      (.setItem js/localStorage "funding-history-page-size" "100")
      (let [store (atom {:account-info {:funding-history {:page-size 25
                                                          :page 4
                                                          :page-input "4"}}})]
        (core/restore-funding-history-pagination-settings! store)
        (is (= {:page-size 100
                :page 1
                :page-input "1"}
               (select-keys (get-in @store [:account-info :funding-history])
                            [:page-size :page :page-input]))))
      (.setItem js/localStorage "funding-history-page-size" "13")
      (let [store (atom {:account-info {:funding-history {}}})]
        (core/restore-funding-history-pagination-settings! store)
        (is (= 50
               (get-in @store [:account-info :funding-history :page-size])))))))

(deftest trade-history-pagination-page-size-normalizes-and-persists-test
  (with-test-local-storage
    (fn []
      (let [state {:account-info {:trade-history {:page-size 25
                                                  :page 8
                                                  :page-input "8"}}}
            effects (core/set-trade-history-page-size state "100")]
        (is (= [[:effects/save-many [[[:account-info :trade-history :page-size] 100]
                                     [[:account-info :trade-history :page] 1]
                                     [[:account-info :trade-history :page-input] "1"]]]]
               effects))
        (is (= "100" (.getItem js/localStorage "trade-history-page-size")))
        (let [invalid-effects (core/set-trade-history-page-size state "13")]
          (is (= [[:effects/save-many [[[:account-info :trade-history :page-size] 50]
                                       [[:account-info :trade-history :page] 1]
                                       [[:account-info :trade-history :page-input] "1"]]]]
                 invalid-effects))
          (is (= "50" (.getItem js/localStorage "trade-history-page-size"))))))))

(deftest trade-history-pagination-set-page-clamps-and-syncs-input-test
  (let [state {:account-info {:trade-history {:page 2
                                              :page-input "2"}}}
        within (core/set-trade-history-page state 3 5)
        too-high (core/set-trade-history-page state 99 5)
        too-low (core/set-trade-history-page state -2 5)]
    (is (= [[:effects/save-many [[[:account-info :trade-history :page] 3]
                                 [[:account-info :trade-history :page-input] "3"]]]]
           within))
    (is (= [[:effects/save-many [[[:account-info :trade-history :page] 5]
                                 [[:account-info :trade-history :page-input] "5"]]]]
           too-high))
    (is (= [[:effects/save-many [[[:account-info :trade-history :page] 1]
                                 [[:account-info :trade-history :page-input] "1"]]]]
           too-low))))

(deftest trade-history-pagination-next-prev-and-input-apply-test
  (let [state {:account-info {:trade-history {:page 2
                                              :page-input "2"}}}
        next-effects (core/next-trade-history-page state 3)
        prev-effects (core/prev-trade-history-page state 3)
        at-end-effects (core/next-trade-history-page
                        {:account-info {:trade-history {:page 3 :page-input "3"}}}
                        3)
        typed-state {:account-info {:trade-history {:page 1 :page-input "12"}}}
        apply-effects (core/apply-trade-history-page-input typed-state 4)
        invalid-apply-effects (core/apply-trade-history-page-input
                               {:account-info {:trade-history {:page 1 :page-input "abc"}}}
                               4)
        keydown-effects (core/handle-trade-history-page-input-keydown typed-state "Enter" 4)
        keydown-nop (core/handle-trade-history-page-input-keydown typed-state "Escape" 4)]
    (is (= [[:effects/save-many [[[:account-info :trade-history :page] 3]
                                 [[:account-info :trade-history :page-input] "3"]]]]
           next-effects))
    (is (= [[:effects/save-many [[[:account-info :trade-history :page] 1]
                                 [[:account-info :trade-history :page-input] "1"]]]]
           prev-effects))
    (is (= [[:effects/save-many [[[:account-info :trade-history :page] 3]
                                 [[:account-info :trade-history :page-input] "3"]]]]
           at-end-effects))
    (is (= [[:effects/save-many [[[:account-info :trade-history :page] 4]
                                 [[:account-info :trade-history :page-input] "4"]]]]
           apply-effects))
    (is (= [[:effects/save-many [[[:account-info :trade-history :page] 1]
                                 [[:account-info :trade-history :page-input] "1"]]]]
           invalid-apply-effects))
    (is (= apply-effects keydown-effects))
    (is (= [] keydown-nop))))

(deftest sort-trade-history-toggles-direction-on-same-column-test
  (let [state {:account-info {:trade-history {:sort {:column "Time"
                                                     :direction :desc}
                                              :page 3
                                              :page-input "3"}}}
        effects (core/sort-trade-history state "Time")]
    (is (= [[:effects/save-many [[[:account-info :trade-history :sort]
                                  {:column "Time" :direction :asc}]
                                 [[:account-info :trade-history :page] 1]
                                 [[:account-info :trade-history :page-input] "1"]]]]
           effects))))

(deftest sort-trade-history-uses-mixed-default-direction-for-new-columns-test
  (let [state {:account-info {:trade-history {:sort {:column "Time"
                                                     :direction :desc}
                                              :page 2
                                              :page-input "2"}}}
        coin-effects (core/sort-trade-history state "Coin")
        value-effects (core/sort-trade-history state "Trade Value")]
    (is (= [[:effects/save-many [[[:account-info :trade-history :sort]
                                  {:column "Coin" :direction :asc}]
                                 [[:account-info :trade-history :page] 1]
                                 [[:account-info :trade-history :page-input] "1"]]]]
           coin-effects))
    (is (= [[:effects/save-many [[[:account-info :trade-history :sort]
                                  {:column "Trade Value" :direction :desc}]
                                 [[:account-info :trade-history :page] 1]
                                 [[:account-info :trade-history :page-input] "1"]]]]
           value-effects))))

(deftest restore-trade-history-pagination-settings-uses-defaults-and-stored-size-test
  (with-test-local-storage
    (fn []
      (.setItem js/localStorage "trade-history-page-size" "100")
      (let [store (atom {:account-info {:trade-history {:page-size 25
                                                        :page 4
                                                        :page-input "4"}}})]
        (core/restore-trade-history-pagination-settings! store)
        (is (= {:page-size 100
                :page 1
                :page-input "1"}
               (select-keys (get-in @store [:account-info :trade-history])
                            [:page-size :page :page-input]))))
      (.setItem js/localStorage "trade-history-page-size" "13")
      (let [store (atom {:account-info {:trade-history {}}})]
        (core/restore-trade-history-pagination-settings! store)
        (is (= 50
               (get-in @store [:account-info :trade-history :page-size])))))))

(deftest select-account-info-tab-order-history-saves-selection-before-fetch-test
  (let [state {:account-info {:selected-tab :balances
                              :order-history {:request-id 2}}}
        effects (core/select-account-info-tab state :order-history)
        immediate (first effects)
        path-values (second immediate)]
    (is (= :effects/save-many (first immediate)))
    (is (= [:account-info :selected-tab]
           (-> path-values first first)))
    (is (= :order-history
           (-> path-values first second)))
    (is (= [:effects/api-fetch-historical-orders 3]
           (second effects)))))

(deftest sort-order-history-toggles-direction-on-same-column-test
  (let [state {:account-info {:order-history {:sort {:column "Time"
                                                     :direction :desc}}}}
        effects (core/sort-order-history state "Time")]
    (is (= [[:effects/save-many [[[:account-info :order-history :sort]
                                  {:column "Time" :direction :asc}]
                                 [[:account-info :order-history :page] 1]
                                 [[:account-info :order-history :page-input] "1"]]]]
           effects))))

(deftest sort-order-history-uses-mixed-default-direction-for-new-columns-test
  (let [state {:account-info {:order-history {:sort {:column "Time"
                                                     :direction :desc}}}}
        coin-effects (core/sort-order-history state "Coin")
        oid-effects (core/sort-order-history state "Order ID")]
    (is (= [[:effects/save-many [[[:account-info :order-history :sort]
                                  {:column "Coin" :direction :asc}]
                                 [[:account-info :order-history :page] 1]
                                 [[:account-info :order-history :page-input] "1"]]]]
           coin-effects))
    (is (= [[:effects/save-many [[[:account-info :order-history :sort]
                                  {:column "Order ID" :direction :desc}]
                                 [[:account-info :order-history :page] 1]
                                 [[:account-info :order-history :page-input] "1"]]]]
           oid-effects))))

(deftest order-history-filter-actions-update-paths-and-close-dropdown-test
  (let [state {:account-info {:order-history {:filter-open? false
                                              :status-filter :all}}}
        toggle-effects (core/toggle-order-history-filter-open state)
        set-effects (core/set-order-history-status-filter
                     {:account-info {:order-history {:filter-open? true
                                                     :status-filter :all}}}
                     :filled)
        set-invalid-effects (core/set-order-history-status-filter
                             {:account-info {:order-history {:filter-open? true
                                                             :status-filter :all}}}
                             :unknown)]
    (is (= [[:effects/save [:account-info :order-history :filter-open?] true]]
           toggle-effects))
    (is (= [[:effects/save-many [[[:account-info :order-history :status-filter] :filled]
                                 [[:account-info :order-history :filter-open?] false]
                                 [[:account-info :order-history :page] 1]
                                 [[:account-info :order-history :page-input] "1"]]]]
           set-effects))
    (is (= [[:effects/save-many [[[:account-info :order-history :status-filter] :all]
                                 [[:account-info :order-history :filter-open?] false]
                                 [[:account-info :order-history :page] 1]
                                 [[:account-info :order-history :page-input] "1"]]]]
           set-invalid-effects))))

(deftest order-history-pagination-page-size-normalizes-and-persists-test
  (with-test-local-storage
    (fn []
      (let [state {:account-info {:order-history {:page-size 25
                                                  :page 8
                                                  :page-input "8"}}}
            effects (core/set-order-history-page-size state "100")]
        (is (= [[:effects/save-many [[[:account-info :order-history :page-size] 100]
                                     [[:account-info :order-history :page] 1]
                                     [[:account-info :order-history :page-input] "1"]]]]
               effects))
        (is (= "100" (.getItem js/localStorage "order-history-page-size")))
        (let [invalid-effects (core/set-order-history-page-size state "13")]
          (is (= [[:effects/save-many [[[:account-info :order-history :page-size] 50]
                                       [[:account-info :order-history :page] 1]
                                       [[:account-info :order-history :page-input] "1"]]]]
                 invalid-effects))
          (is (= "50" (.getItem js/localStorage "order-history-page-size"))))))))

(deftest order-history-pagination-set-page-clamps-and-syncs-input-test
  (let [state {:account-info {:order-history {:page 2
                                              :page-input "2"}}}
        within (core/set-order-history-page state 3 5)
        too-high (core/set-order-history-page state 99 5)
        too-low (core/set-order-history-page state -2 5)]
    (is (= [[:effects/save-many [[[:account-info :order-history :page] 3]
                                 [[:account-info :order-history :page-input] "3"]]]]
           within))
    (is (= [[:effects/save-many [[[:account-info :order-history :page] 5]
                                 [[:account-info :order-history :page-input] "5"]]]]
           too-high))
    (is (= [[:effects/save-many [[[:account-info :order-history :page] 1]
                                 [[:account-info :order-history :page-input] "1"]]]]
           too-low))))

(deftest order-history-pagination-next-prev-and-input-apply-test
  (let [state {:account-info {:order-history {:page 2
                                              :page-input "2"}}}
        next-effects (core/next-order-history-page state 3)
        prev-effects (core/prev-order-history-page state 3)
        at-end-effects (core/next-order-history-page
                        {:account-info {:order-history {:page 3 :page-input "3"}}}
                        3)
        typed-state {:account-info {:order-history {:page 1 :page-input "12"}}}
        apply-effects (core/apply-order-history-page-input typed-state 4)
        invalid-apply-effects (core/apply-order-history-page-input
                               {:account-info {:order-history {:page 1 :page-input "abc"}}}
                               4)
        keydown-effects (core/handle-order-history-page-input-keydown typed-state "Enter" 4)
        keydown-nop (core/handle-order-history-page-input-keydown typed-state "Escape" 4)]
    (is (= [[:effects/save-many [[[:account-info :order-history :page] 3]
                                 [[:account-info :order-history :page-input] "3"]]]]
           next-effects))
    (is (= [[:effects/save-many [[[:account-info :order-history :page] 1]
                                 [[:account-info :order-history :page-input] "1"]]]]
           prev-effects))
    (is (= [[:effects/save-many [[[:account-info :order-history :page] 3]
                                 [[:account-info :order-history :page-input] "3"]]]]
           at-end-effects))
    (is (= [[:effects/save-many [[[:account-info :order-history :page] 4]
                                 [[:account-info :order-history :page-input] "4"]]]]
           apply-effects))
    (is (= [[:effects/save-many [[[:account-info :order-history :page] 1]
                                 [[:account-info :order-history :page-input] "1"]]]]
           invalid-apply-effects))
    (is (= apply-effects keydown-effects))
    (is (= [] keydown-nop))))

(deftest restore-order-history-pagination-settings-uses-defaults-and-stored-size-test
  (with-test-local-storage
    (fn []
      (.setItem js/localStorage "order-history-page-size" "100")
      (let [store (atom {:account-info {:order-history {:page-size 25
                                                        :page 4
                                                        :page-input "4"}}})]
        (core/restore-order-history-pagination-settings! store)
        (is (= {:page-size 100
                :page 1
                :page-input "1"}
               (select-keys (get-in @store [:account-info :order-history])
                            [:page-size :page :page-input]))))
      (.setItem js/localStorage "order-history-page-size" "13")
      (let [store (atom {:account-info {:order-history {}}})]
        (core/restore-order-history-pagination-settings! store)
        (is (= 50
               (get-in @store [:account-info :order-history :page-size])))))))

(deftest refresh-order-history-emits-request-then-fetch-with-tab-aware-loading-test
  (let [selected-state {:account-info {:selected-tab :order-history
                                       :order-history {:request-id 5}}}
        background-state {:account-info {:selected-tab :balances
                                         :order-history {:request-id 5}}}
        selected-effects (core/refresh-order-history selected-state)
        background-effects (core/refresh-order-history background-state)]
    (is (= :effects/save-many (ffirst selected-effects)))
    (is (= [:effects/api-fetch-historical-orders 6]
           (second selected-effects)))
    (is (= true
           (-> selected-effects first second (nth 1) second)))
    (is (= false
           (-> background-effects first second (nth 1) second)))
    (is (= [:effects/api-fetch-historical-orders 6]
           (second background-effects)))))

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

(deftest websocket-diagnostics-ui-actions-emit-deterministic-effects-test
  (is (= [[:effects/save-many [[[:websocket-ui :diagnostics-open?] true]
                               [[:websocket-ui :reveal-sensitive?] false]
                               [[:websocket-ui :copy-status] nil]]]
          [:effects/refresh-websocket-health]]
         (core/toggle-ws-diagnostics {:websocket-ui {:diagnostics-open? false}})))
  (is (= [[:effects/save-many [[[:websocket-ui :diagnostics-open?] false]
                               [[:websocket-ui :reveal-sensitive?] false]
                               [[:websocket-ui :copy-status] nil]]]]
         (core/toggle-ws-diagnostics {:websocket-ui {:diagnostics-open? true}})))
  (is (= [[:effects/save-many [[[:websocket-ui :diagnostics-open?] false]
                               [[:websocket-ui :reveal-sensitive?] false]
                               [[:websocket-ui :copy-status] nil]]]]
         (core/close-ws-diagnostics {})))
  (is (= [[:effects/save [:websocket-ui :reveal-sensitive?] false]]
         (core/toggle-ws-diagnostics-sensitive {:websocket-ui {:reveal-sensitive? true}})))
  (is (= [[:effects/confirm-ws-diagnostics-reveal]]
         (core/toggle-ws-diagnostics-sensitive {:websocket-ui {:reveal-sensitive? false}})))
  (is (= [[:effects/save-many [[[:websocket-ui :diagnostics-open?] false]
                               [[:websocket-ui :reveal-sensitive?] false]
                               [[:websocket-ui :copy-status] nil]]]
          [:effects/save [:websocket-ui :reconnect-cooldown-until-ms] 7000]
          [:effects/reconnect-websocket]]
         (core/ws-diagnostics-reconnect-now {:websocket-ui {:diagnostics-open? true}
                                             :websocket {:health {:generated-at-ms 2000
                                                                  :transport {:state :connected}}}})))
  (is (= [[:effects/copy-websocket-diagnostics]]
         (core/ws-diagnostics-copy {}))))

(deftest websocket-diagnostics-reconnect-guard-prevents-duplicate-reconnect-test
  (is (= []
         (core/ws-diagnostics-reconnect-now {:websocket-ui {:reconnect-cooldown-until-ms 9000}
                                             :websocket {:health {:generated-at-ms 5000
                                                                  :transport {:state :connected}}}})))
  (is (= []
         (core/ws-diagnostics-reconnect-now {:websocket-ui {:reconnect-cooldown-until-ms nil}
                                             :websocket {:health {:generated-at-ms 5000
                                                                  :transport {:state :reconnecting}}}}))))

(deftest copy-websocket-diagnostics-redacts-sensitive-fields-test
  (async done
    (let [address "0x1234567890abcdef1234567890abcdef12345678"
          written (atom nil)
          navigator-prop "navigator"
          original-navigator-descriptor (js/Object.getOwnPropertyDescriptor js/globalThis navigator-prop)
          fake-clipboard #js {:writeText (fn [payload]
                                           (reset! written payload)
                                           (js/Promise.resolve true))}
          fake-navigator #js {:clipboard fake-clipboard}
          health {:generated-at-ms 1700000000000
                  :transport {:state :connected
                              :freshness :live
                              :last-close {:code 1000
                                           :reason "ok"}}
                  :groups {:orders_oms {:worst-status :offline}
                           :market_data {:worst-status :idle}
                           :account {:worst-status :n-a}}
                  :streams {["openOrders" nil address nil nil]
                            {:group :orders_oms
                             :topic "openOrders"
                             :status :offline
                             :last-payload-at-ms 1699999999000
                             :stale-threshold-ms nil
                             :descriptor {:type "openOrders"
                                          :user address
                                          :token "secret-token"
                                          :meta {:authorization "Bearer token"
                                                 :entries [{:address address}]}}}}}
          store (atom {:websocket {:health health}
                       :websocket-ui {:copy-status nil}})]
      (js/Object.defineProperty js/globalThis navigator-prop
                                #js {:value fake-navigator
                                     :configurable true})
      (core/copy-websocket-diagnostics nil store)
      (js/setTimeout
        (fn []
          (try
            (is (string? @written))
            (is (not (str/includes? @written address)))
            (is (str/includes? @written "<redacted>"))
            (is (= :success (get-in @store [:websocket-ui :copy-status :kind])))
            (is (= "Copied (redacted)"
                   (get-in @store [:websocket-ui :copy-status :message])))
            (let [decoded (js->clj (js/JSON.parse @written) :keywordize-keys true)]
              (is (= "<redacted>" (get-in decoded [:streams 0 :descriptor :user])))
              (is (= "<redacted>" (get-in decoded [:streams 0 :descriptor :token])))
              (is (= "<redacted>" (get-in decoded [:streams 0 :descriptor :meta :authorization])))
              (is (= "<redacted>" (get-in decoded [:streams 0 :descriptor :meta :entries 0 :address]))))
            (finally
              (if original-navigator-descriptor
                (js/Object.defineProperty js/globalThis navigator-prop original-navigator-descriptor)
                (js/Reflect.deleteProperty js/globalThis navigator-prop))
              (done))))
        0))))

(deftest copy-websocket-diagnostics-fallback-status-when-clipboard-unavailable-test
  (let [navigator-prop "navigator"
        original-navigator-descriptor (js/Object.getOwnPropertyDescriptor js/globalThis navigator-prop)
        store (atom {:websocket {:health {:generated-at-ms 1700000000000
                                          :transport {:state :connected
                                                      :freshness :live}
                                          :groups {:orders_oms {:worst-status :idle}
                                                   :market_data {:worst-status :live}
                                                   :account {:worst-status :n-a}}
                                          :streams {["openOrders" nil "0x1234567890abcdef1234567890abcdef12345678" nil nil]
                                                    {:group :orders_oms
                                                     :topic "openOrders"
                                                     :status :n-a
                                                     :descriptor {:type "openOrders"
                                                                  :user "0x1234567890abcdef1234567890abcdef12345678"}}}}}
                     :websocket-ui {:copy-status nil}})]
    (js/Object.defineProperty js/globalThis navigator-prop
                              #js {:value #js {}
                                   :configurable true})
    (try
      (core/copy-websocket-diagnostics nil store)
      (let [status (get-in @store [:websocket-ui :copy-status])]
        (is (= :error (:kind status)))
        (is (str/includes? (:message status) "Couldn't access clipboard"))
        (is (string? (:fallback-json status)))
        (is (str/includes? (:fallback-json status) "<redacted>")))
      (finally
        (if original-navigator-descriptor
          (js/Object.defineProperty js/globalThis navigator-prop original-navigator-descriptor)
          (js/Reflect.deleteProperty js/globalThis navigator-prop))))))

(deftest sync-websocket-health-second-bucket-updates-only-when-drawer-open-test
  (async done
    (let [original-connection @ws-client/connection-state
          original-runtime @ws-client/stream-runtime
          store (atom {:websocket {:health {}}
                       :websocket-ui {:diagnostics-open? false}})
          projection-state @#'hyperopen.core/websocket-health-projection-state]
      (reset! ws-client/connection-state
              {:status :connected
               :attempt 0
               :next-retry-at-ms nil
               :last-close nil
               :last-activity-at-ms 100
               :now-ms 1000
               :online? true
               :transport/state :connected
               :transport/last-recv-at-ms 900
               :transport/connected-at-ms 900
               :transport/expected-traffic? false
               :transport/freshness :live
               :queue-size 0
               :ws nil})
      (reset! ws-client/stream-runtime
              {:tier-depth {:market 0 :lossless 0}
               :metrics {:market-coalesced 0
                         :market-dispatched 0
                         :lossless-dispatched 0
                         :ingress-parse-errors 0}
               :now-ms 1000
               :streams {}
               :transport {:state :connected
                           :online? true
                           :last-recv-at-ms 900
                           :connected-at-ms 900
                           :expected-traffic? false
                           :freshness :live
                           :attempt 0
                           :last-close nil}
               :market-coalesce {:pending {}
                                 :timer nil}})
      (reset! projection-state {:second-bucket nil :fingerprint nil})
      (@#'hyperopen.core/sync-websocket-health! store :force? true)
      (js/setTimeout
        (fn []
          (is (= 1000 (get-in @store [:websocket :health :generated-at-ms])))
          (swap! ws-client/stream-runtime assoc :now-ms 2000)
          (@#'hyperopen.core/sync-websocket-health! store)
          (js/setTimeout
            (fn []
              (is (= 1000 (get-in @store [:websocket :health :generated-at-ms])))
              (swap! store assoc-in [:websocket-ui :diagnostics-open?] true)
              (swap! ws-client/stream-runtime assoc :now-ms 3000)
              (@#'hyperopen.core/sync-websocket-health! store)
              (js/setTimeout
                (fn []
                  (try
                    (is (= 3000 (get-in @store [:websocket :health :generated-at-ms])))
                    (finally
                      (reset! ws-client/connection-state original-connection)
                      (reset! ws-client/stream-runtime original-runtime)
                      (done))))
                0))
            0))
        0))))

(deftest toggle-timeframes-dropdown-opens-timeframes-and-closes-other-chart-menus-test
  (let [effects (core/toggle-timeframes-dropdown
                 {:chart-options {:timeframes-dropdown-visible false
                                  :chart-type-dropdown-visible true
                                  :indicators-dropdown-visible true}})]
    (is (= [[:effects/save-many [[[:chart-options :timeframes-dropdown-visible] true]
                                 [[:chart-options :chart-type-dropdown-visible] false]
                                 [[:chart-options :indicators-dropdown-visible] false]]]]
           effects))))

(deftest toggle-chart-type-dropdown-opens-chart-type-and-closes-other-chart-menus-test
  (let [effects (core/toggle-chart-type-dropdown
                 {:chart-options {:timeframes-dropdown-visible true
                                  :chart-type-dropdown-visible false
                                  :indicators-dropdown-visible true}})]
    (is (= [[:effects/save-many [[[:chart-options :timeframes-dropdown-visible] false]
                                 [[:chart-options :chart-type-dropdown-visible] true]
                                 [[:chart-options :indicators-dropdown-visible] false]]]]
           effects))))

(deftest toggle-indicators-dropdown-opens-indicators-and-closes-other-chart-menus-test
  (let [effects (core/toggle-indicators-dropdown
                 {:chart-options {:timeframes-dropdown-visible true
                                  :chart-type-dropdown-visible true
                                  :indicators-dropdown-visible false}})]
    (is (= [[:effects/save-many [[[:chart-options :timeframes-dropdown-visible] false]
                                 [[:chart-options :chart-type-dropdown-visible] false]
                                 [[:chart-options :indicators-dropdown-visible] true]]]]
           effects))))

(deftest toggle-open-chart-menu-closes-all-chart-menus-test
  (let [effects (core/toggle-timeframes-dropdown
                 {:chart-options {:timeframes-dropdown-visible true
                                  :chart-type-dropdown-visible false
                                  :indicators-dropdown-visible false}})]
    (is (= [[:effects/save-many [[[:chart-options :timeframes-dropdown-visible] false]
                                 [[:chart-options :chart-type-dropdown-visible] false]
                                 [[:chart-options :indicators-dropdown-visible] false]]]]
           effects))))

(deftest select-chart-timeframe-emits-batched-projection-before-single-fetch-test
  (with-test-local-storage
    (fn []
      (let [effects (core/select-chart-timeframe
                     {:chart-options {:timeframes-dropdown-visible true
                                      :chart-type-dropdown-visible true
                                      :indicators-dropdown-visible true}}
                     :5m)]
        (is (= [[:effects/save-many [[[:chart-options :selected-timeframe] :5m]
                                     [[:chart-options :timeframes-dropdown-visible] false]
                                     [[:chart-options :chart-type-dropdown-visible] false]
                                     [[:chart-options :indicators-dropdown-visible] false]]]
                [:effects/fetch-candle-snapshot :interval :5m]]
               effects))
        (is (= 1 (count (filter #(= :effects/fetch-candle-snapshot (first %)) effects))))
        (is (= :effects/save-many (ffirst effects)))
        (is (= :effects/fetch-candle-snapshot (first (second effects))))))))

(deftest select-chart-type-emits-single-batched-projection-and-no-network-effects-test
  (with-test-local-storage
    (fn []
      (let [effects (core/select-chart-type
                     {:chart-options {:timeframes-dropdown-visible true
                                      :chart-type-dropdown-visible true
                                      :indicators-dropdown-visible true}}
                     :line)]
        (is (= [[:effects/save-many [[[:chart-options :selected-chart-type] :line]
                                     [[:chart-options :timeframes-dropdown-visible] false]
                                     [[:chart-options :chart-type-dropdown-visible] false]
                                     [[:chart-options :indicators-dropdown-visible] false]]]]
               effects))
        (is (= 1 (count effects)))
        (is (not-any? #(= :effects/fetch-candle-snapshot (first %)) effects))
        (is (not-any? #(= :effects/subscribe-active-asset (first %)) effects))))))

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

(deftest toggle-order-tpsl-panel-noops-for-scale-test
  (let [state {:order-form (assoc (trading/default-order-form)
                                  :entry-mode :pro
                                  :type :scale
                                  :tpsl-panel-open? false)}
        effects (core/toggle-order-tpsl-panel state)]
    (is (= [] effects))))

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

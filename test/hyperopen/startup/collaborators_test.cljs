(ns hyperopen.startup.collaborators-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.default :as api]
            [hyperopen.account.history.effects :as account-history-effects]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.startup.collaborators :as collaborators]
            [hyperopen.websocket.active-asset-ctx :as active-ctx]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.websocket.infrastructure.transport :as ws-infra]
            [hyperopen.websocket.orderbook :as orderbook]
            [hyperopen.websocket.trades :as trades]))

(deftest startup-base-deps-includes-default-collaborators-test
  (let [deps (collaborators/startup-base-deps
              {:store :store
               :startup-runtime :startup-runtime})]
    (is (= :store (:store deps)))
    (is (= :startup-runtime (:startup-runtime deps)))
    (is (= runtime-state/websocket-url (:ws-url deps)))
    (is (fn? (:log-fn deps)))
    (is (identical? api/get-request-stats
                    (:get-request-stats deps)))
    (is (identical? account-history-effects/fetch-and-merge-funding-history!
                    (:fetch-and-merge-funding-history! deps)))
    (is (identical? ws-client/init-connection!
                    (:init-connection! deps)))))

(deftest startup-base-deps-allows-overriding-default-collaborators-test
  (let [log-fn* (fn [& _] nil)
        dispatch!* (fn [& _] nil)
        deps (collaborators/startup-base-deps
              {:log-fn log-fn*
               :ws-url "wss://example.test/ws"
               :dispatch! dispatch!*})]
    (is (identical? log-fn* (:log-fn deps)))
    (is (= "wss://example.test/ws" (:ws-url deps)))
    (is (identical? dispatch!* (:dispatch! deps)))))

(deftest startup-base-deps-prefers-injected-api-ops-test
  (let [get-request-stats* (fn [] {:source :injected})
        request-asset-selector-markets* (fn [_store _opts]
                                          (js/Promise.resolve {:phase :full
                                                               :market-state {:markets []}}))
        api-instance {:get-request-stats get-request-stats*
                      :request-frontend-open-orders! (fn [_address _opts] (js/Promise.resolve []))
                      :request-clearinghouse-state! (fn [_address _dex _opts] (js/Promise.resolve nil))
                      :request-user-fills! (fn [_address _opts] (js/Promise.resolve []))
                      :request-spot-clearinghouse-state! (fn [_address _opts] (js/Promise.resolve nil))
                      :request-user-abstraction! (fn [_address _opts] (js/Promise.resolve "default"))
                      :ensure-perp-dexs-data! (fn [_store _opts] (js/Promise.resolve []))
                      :request-asset-contexts! (fn [_opts] (js/Promise.resolve []))
                      :request-asset-selector-markets! request-asset-selector-markets*}
        deps (collaborators/startup-base-deps {:api api-instance})]
    (is (identical? get-request-stats*
                    (:get-request-stats deps)))))

(deftest startup-base-deps-fetch-asset-selector-markets-resyncs-active-outcome-side-streams-test
  (async done
    (let [market {:key "outcome:1"
                  :coin "outcome:1"
                  :market-type :outcome
                  :outcome-sides [{:side-index 0 :coin "#10"}
                                  {:side-index 1 :coin "#11"}]}
          store (atom {:active-asset "#10"
                       :asset-selector {}})
          original-orderbook-state @orderbook/orderbook-state
          original-trades-state @trades/trades-state
          original-active-ctx-state @active-ctx/active-asset-ctx-state
          original-ws-state @ws-client/runtime-state
          restore-state! (fn []
                           (reset! orderbook/orderbook-state original-orderbook-state)
                           (reset! trades/trades-state original-trades-state)
                           (reset! active-ctx/active-asset-ctx-state original-active-ctx-state)
                           (reset! ws-client/runtime-state original-ws-state))
          deps (collaborators/startup-base-deps
                {:api {:request-asset-selector-markets!
                       (fn [_store opts]
                         (js/Promise.resolve
                          {:phase (:phase opts)
                           :market-state {:markets [market]
                                          :market-by-key {"outcome:1" market}
                                          :active-market market
                                          :loaded-at-ms 123}}))}})]
      (reset! orderbook/orderbook-state {:subscriptions {}
                                          :books {}})
      (reset! trades/trades-state {:subscriptions #{}
                                    :trades []
                                    :trades-by-coin {}})
      (reset! active-ctx/active-asset-ctx-state {:subscriptions #{}
                                                 :owners-by-coin {}
                                                 :coins-by-owner {}
                                                 :contexts {}})
      (swap! ws-client/runtime-state
             assoc
             :clock (ws-infra/make-function-clock (fn [] 1700000000000)
                                                  (fn [] 0.5)))
      (-> ((:fetch-asset-selector-markets! deps) store {:phase :bootstrap})
          (.then (fn [_]
                   (is (= #{"#10" "#11"}
                          (set (keys (orderbook/get-subscriptions)))))
                   (is (= #{"#10" "#11"}
                          (:subscriptions @trades/trades-state)))
                   (is (= #{"#10" "#11"}
                          (active-ctx/get-subscribed-coins-by-owner :active-asset)))
                   (restore-state!)
                   (done)))
          (.catch (fn [err]
                    (restore-state!)
                    (is false (str "Unexpected selector fetch error: " err))
                    (done)))))))

(deftest startup-base-deps-fetch-asset-contexts-uses-injected-api-request-test
  (async done
    (let [calls (atom 0)
          deps (collaborators/startup-base-deps
                {:api {:get-request-stats (fn [] {:source :injected})
                       :request-frontend-open-orders! (fn [_address _opts] (js/Promise.resolve []))
                       :request-clearinghouse-state! (fn [_address _dex _opts] (js/Promise.resolve nil))
                       :request-user-fills! (fn [_address _opts] (js/Promise.resolve []))
                       :request-spot-clearinghouse-state! (fn [_address _opts] (js/Promise.resolve nil))
                       :request-user-abstraction! (fn [_address _opts] (js/Promise.resolve "default"))
                       :ensure-perp-dexs-data! (fn [_store _opts] (js/Promise.resolve []))
                       :request-asset-contexts! (fn [_opts]
                                                  (swap! calls inc)
                                                  (js/Promise.resolve [{:coin "BTC"}]))
                       :request-asset-selector-markets! (fn [_store _opts]
                                                          (js/Promise.resolve {:phase :full
                                                                               :market-state {:markets []}}))}})
          store (atom {})]
      (-> ((:fetch-asset-contexts! deps) store {})
          (.then (fn [rows]
                   (is (= [{:coin "BTC"}] rows))
                   (is (= 1 @calls))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest startup-base-deps-fetch-portfolio-loads-ledger-updates-for-summary-time-window-test
  (async done
    (let [request-address "0x1111111111111111111111111111111111111111"
          portfolio-calls (atom [])
          ledger-calls (atom [])
          store (atom {:wallet {:address request-address}
                       :portfolio {}})
          deps (collaborators/startup-base-deps
                {:api {:get-request-stats (fn [] {:source :injected})
                       :request-portfolio! (fn [address opts]
                                             (swap! portfolio-calls conj [address opts])
                                             (js/Promise.resolve {:month {:accountValueHistory [[1000 "10"] [2000 "15"]]
                                                                          :pnlHistory [[1000 "0"] [2000 "1"]]}
                                                                  :all-time {:accountValueHistory [[500 "5"] [2500 "20"]]
                                                                             :pnlHistory [[500 "0"] [2500 "4"]]}}))
                       :request-user-non-funding-ledger-updates! (fn [address start-time-ms end-time-ms opts]
                                                                   (swap! ledger-calls conj [address start-time-ms end-time-ms opts])
                                                                   (js/Promise.resolve [{:time 1500
                                                                                         :hash "0xabc"
                                                                                         :delta {:type "deposit"
                                                                                                 :usdc "3.0"}}]))}})]
      (-> ((:fetch-portfolio! deps) store request-address {:priority :high})
          (.then (fn [summary]
                   (is (= request-address (ffirst @portfolio-calls)))
                   (is (= [request-address 500 2500 {:priority :high}]
                          (first @ledger-calls)))
                   (is (= 2 (count summary)))
                   (is (= [{:time 1500
                            :hash "0xabc"
                            :delta {:type "deposit"
                                    :usdc "3.0"}}]
                          (get-in @store [:portfolio :ledger-updates])))
                   (is (number? (get-in @store [:portfolio :ledger-loaded-at-ms])))
                   (is (nil? (get-in @store [:portfolio :ledger-error])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest startup-base-deps-fetch-user-fills-ignores-stale-address-responses-test
  (async done
    (let [requested-address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          next-address "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
          resolve-request! (atom nil)
          store (atom {:wallet {:address requested-address}
                       :orders {:fills []}})
          deps (collaborators/startup-base-deps
                {:api {:get-request-stats (fn [] {:source :injected})
                       :request-user-fills! (fn [_address _opts]
                                              (js/Promise.
                                               (fn [resolve _reject]
                                                 (reset! resolve-request! resolve))))}})]
      (-> ((:fetch-user-fills! deps) store requested-address {:priority :high})
          (.then (fn [_rows]
                   (is (empty? (get-in @store [:orders :fills])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done))))
      (swap! store assoc-in [:wallet :address] next-address)
      (@resolve-request! [{:tid 101 :coin "BTC"}]))))

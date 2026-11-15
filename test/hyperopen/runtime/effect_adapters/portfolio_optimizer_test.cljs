(ns hyperopen.runtime.effect-adapters.portfolio-optimizer-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.portfolio.optimizer.contracts :as optimizer-contracts]
            [hyperopen.runtime.effect-adapters :as effect-adapters]
            [hyperopen.runtime.effect-adapters.portfolio-optimizer :as portfolio-optimizer-adapters]
            [hyperopen.test-support.async :as async-support]))

(def ^:private optimizer-history-api-browser-override
  @#'hyperopen.runtime.effect-adapters.portfolio-optimizer/optimizer-history-api-browser-override)

(def ^:private normalize-optimizer-history-api-browser-override
  @#'hyperopen.runtime.effect-adapters.portfolio-optimizer/normalize-optimizer-history-api-browser-override)

(def ^:private absent-override ::absent-override)

(defn- with-optimizer-history-api-override
  [value f]
  (let [property-name "__HYPEROPEN_OPTIMIZER_HISTORY_API__"
        original-descriptor (js/Object.getOwnPropertyDescriptor
                             js/globalThis
                             property-name)]
    (if (= absent-override value)
      (js/Reflect.deleteProperty js/globalThis property-name)
      (js/Object.defineProperty js/globalThis
                                property-name
                                #js {:value value
                                     :configurable true
                                     :writable true}))
    (try
      (f)
      (finally
        (if original-descriptor
          (js/Object.defineProperty js/globalThis
                                    property-name
                                    original-descriptor)
          (js/Reflect.deleteProperty js/globalThis property-name))))))

(deftest facade-portfolio-optimizer-adapter-delegates-to-owner-module-test
  (is (identical? portfolio-optimizer-adapters/run-portfolio-optimizer-effect
                  effect-adapters/run-portfolio-optimizer-effect))
  (is (identical? portfolio-optimizer-adapters/run-portfolio-optimizer-pipeline-effect
                  effect-adapters/run-portfolio-optimizer-pipeline-effect))
  (is (identical? portfolio-optimizer-adapters/load-portfolio-optimizer-history-effect
                  effect-adapters/load-portfolio-optimizer-history-effect))
  (is (identical? portfolio-optimizer-adapters/save-portfolio-optimizer-scenario-effect
                  effect-adapters/save-portfolio-optimizer-scenario-effect))
  (is (identical? portfolio-optimizer-adapters/load-portfolio-optimizer-scenario-index-effect
                  effect-adapters/load-portfolio-optimizer-scenario-index-effect))
  (is (identical? portfolio-optimizer-adapters/load-portfolio-optimizer-scenario-effect
                  effect-adapters/load-portfolio-optimizer-scenario-effect))
  (is (identical? portfolio-optimizer-adapters/archive-portfolio-optimizer-scenario-effect
                  effect-adapters/archive-portfolio-optimizer-scenario-effect))
  (is (identical? portfolio-optimizer-adapters/duplicate-portfolio-optimizer-scenario-effect
                  effect-adapters/duplicate-portfolio-optimizer-scenario-effect))
  (is (identical? portfolio-optimizer-adapters/execute-portfolio-optimizer-plan-effect
                  effect-adapters/execute-portfolio-optimizer-plan-effect))
  (is (identical? portfolio-optimizer-adapters/refresh-portfolio-optimizer-tracking-effect
                  effect-adapters/refresh-portfolio-optimizer-tracking-effect))
  (is (identical? portfolio-optimizer-adapters/refresh-portfolio-optimizer-rebalance-slippage-snapshots-effect
                  effect-adapters/refresh-portfolio-optimizer-rebalance-slippage-snapshots-effect))
  (is (identical? portfolio-optimizer-adapters/enable-portfolio-optimizer-manual-tracking-effect
                  effect-adapters/enable-portfolio-optimizer-manual-tracking-effect)))

(deftest run-portfolio-optimizer-effect-calls-run-bridge-with-runtime-store-test
  (let [calls (atom [])
        store (atom {})
        request {:scenario-id "scenario-1"}
        signature {:scenario-id "scenario-1" :revision 1}]
    (with-redefs [portfolio-optimizer-adapters/*request-run!*
                  (fn [payload]
                    (swap! calls conj payload)
                    "run-1")]
      (is (= "run-1"
             (portfolio-optimizer-adapters/run-portfolio-optimizer-effect
              :ctx
              store
              request
              signature
              {:computed-at-ms 123})))
      (is (= [{:request request
               :request-signature signature
               :computed-at-ms 123
               :store store}]
             @calls)))))

(deftest runtime-bound-runner-passes-store-controller-to-run-bridge-test
  (let [calls (atom [])
        runtime (atom {})
        store (atom {})
        request {:scenario-id "scenario-1"}
        signature {:scenario-id "scenario-1" :revision 1}]
    (with-redefs [portfolio-optimizer-adapters/*request-run!*
                  (fn [payload]
                    (swap! calls conj payload)
                    "run-1")]
      (let [handler (portfolio-optimizer-adapters/make-run-portfolio-optimizer runtime)]
        (is (= "run-1"
               (handler :ctx
                        store
                        request
                        signature
                        {:computed-at-ms 123})))
        (is (= "run-1"
               (handler :ctx
                        store
                        request
                        signature
                        {:computed-at-ms 124})))
        (is (= 2 (count @calls)))
        (is (= {:request request
                :request-signature signature
                :computed-at-ms 123
                :store store}
               (dissoc (first @calls) :controller)))
        (is (some? (:controller (first @calls))))
        (is (identical? (:controller (first @calls))
                        (:controller (second @calls))))))))

(deftest refresh-portfolio-optimizer-rebalance-slippage-snapshots-effect-fetches-capped-snapshots-and-updates-preview-test
  (async done
    (let [calls (atom [])
          instruments [{:instrument-id "perp:BTC"
                        :instrument-type :perp
                        :coin "BTC"}
                       {:instrument-id "perp:ETH"
                        :instrument-type :perp
                        :coin "ETH"}]
          last-run {:request-signature
                    {:request {:current-portfolio {:capital {:nav-usdc 300}}
                               :constraints {:rebalance-tolerance 0}
                               :execution-assumptions {:fallback-slippage-bps 25
                                                       :prices-by-id {"perp:BTC" 100
                                                                      "perp:ETH" 100}}
                               :requested-universe instruments
                               :universe instruments}}
                    :result {:status :solved
                             :instrument-ids ["perp:BTC" "perp:ETH"]
                             :current-weights [0 0]
                             :target-weights [1 0.5]
                             :rebalance-preview
                             {:rows [{:instrument-id "perp:BTC"
                                      :instrument-type :perp
                                      :coin "BTC"
                                      :status :ready
                                      :side :buy
                                      :cost {:source :fallback-bps}}
                                     {:instrument-id "perp:ETH"
                                      :instrument-type :perp
                                      :coin "ETH"
                                      :status :ready
                                      :side :buy
                                      :cost {:source :fallback-bps}}]}}}
          store (atom {:portfolio {:optimizer {:last-successful-run last-run}}})]
      (-> (portfolio-optimizer-adapters/refresh-portfolio-optimizer-rebalance-slippage-snapshots-effect
           nil
           store
           {:max-snapshot-coins 1
            :now-ms-fn (fn [] 10000)
            :request-l2-book-snapshot!
            (fn [coin opts]
              (swap! calls conj [coin opts])
              (js/Promise.resolve
               {:time 9000
                :levels [[{:px "99" :sz "10"}]
                         [{:px "101" :sz "1"}
                          {:px "102" :sz "2"}]]}))})
          (.then
           (fn [_]
             (is (= [["BTC" {:priority :low
                              :cache-ttl-ms 30000}]]
                    @calls))
             (is (= :snapshot
                    (get-in @store
                            (into optimizer-contracts/last-successful-run-result-path
                                  [:rebalance-preview :rows 0 :cost :source]))))
             (is (= :fallback-bps
                    (get-in @store
                            (into optimizer-contracts/last-successful-run-result-path
                                  [:rebalance-preview :rows 1 :cost :source]))))
             (is (= 1000
                    (get-in @store
                            (into optimizer-contracts/last-successful-run-result-path
                                  [:rebalance-preview :rows 0 :cost :age-ms]))))
             (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest refresh-portfolio-optimizer-rebalance-slippage-snapshots-effect-restages-the-staged-plan-test
  (async done
    (let [dispatched (atom [])
          instruments [{:instrument-id "perp:BTC" :instrument-type :perp :coin "BTC"}]
          last-run {:request-signature
                    {:request {:current-portfolio {:capital {:nav-usdc 300}}
                               :constraints {:rebalance-tolerance 0}
                               :execution-assumptions {:fallback-slippage-bps 25
                                                       :prices-by-id {"perp:BTC" 100}}
                               :requested-universe instruments
                               :universe instruments}}
                    :result {:status :solved
                             :instrument-ids ["perp:BTC"]
                             :current-weights [0]
                             :target-weights [1]
                             :rebalance-preview
                             {:rows [{:instrument-id "perp:BTC"
                                      :instrument-type :perp
                                      :coin "BTC"
                                      :status :ready
                                      :side :buy
                                      :cost {:source :fallback-bps}}]}}}
          store (atom {:portfolio {:optimizer {:last-successful-run last-run}}})]
      (-> (portfolio-optimizer-adapters/refresh-portfolio-optimizer-rebalance-slippage-snapshots-effect
           nil
           store
           {:now-ms-fn (fn [] 10000)
            :dispatch! (fn [_store _ actions] (swap! dispatched conj actions))
            :request-l2-book-snapshot!
            (fn [_coin _opts]
              (js/Promise.resolve
               {:time 9000
                :levels [[{:px "99" :sz "10"}] [{:px "101" :sz "10"}]]}))})
          (.then
           (fn [_]
             (is (= [[[:actions/restage-portfolio-optimizer-execution-plan]]]
                    @dispatched))
             (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest refresh-portfolio-optimizer-rebalance-slippage-snapshots-effect-does-not-restage-without-fresh-books-test
  (async done
    (let [dispatched (atom [])
          instruments [{:instrument-id "perp:BTC" :instrument-type :perp :coin "BTC"}]
          last-run {:request-signature
                    {:request {:current-portfolio {:capital {:nav-usdc 300}}
                               :constraints {:rebalance-tolerance 0}
                               :execution-assumptions {:fallback-slippage-bps 25
                                                       :prices-by-id {"perp:BTC" 100}}
                               :requested-universe instruments
                               :universe instruments}}
                    :result {:status :solved
                             :instrument-ids ["perp:BTC"]
                             :current-weights [0]
                             :target-weights [1]
                             :rebalance-preview
                             {:rows [{:instrument-id "perp:BTC"
                                      :instrument-type :perp
                                      :coin "BTC"
                                      :status :ready
                                      :side :buy
                                      :cost {:source :fallback-bps}}]}}}
          store (atom {:portfolio {:optimizer {:last-successful-run last-run}}})]
      (-> (portfolio-optimizer-adapters/refresh-portfolio-optimizer-rebalance-slippage-snapshots-effect
           nil
           store
           {:now-ms-fn (fn [] 10000)
            :dispatch! (fn [_store _ actions] (swap! dispatched conj actions))
            :request-l2-book-snapshot!
            (fn [_coin _opts] (js/Promise.reject (js/Error. "rate limited")))})
          (.then
           (fn [_]
             (is (= [] @dispatched))
             (is (= :fallback-bps
                    (get-in @store
                            (into optimizer-contracts/last-successful-run-result-path
                                  [:rebalance-preview :rows 0 :cost :source]))))
             (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest refresh-portfolio-optimizer-rebalance-slippage-snapshots-effect-ignores-late-stale-run-response-test
  (async done
    (let [instruments [{:instrument-id "perp:BTC"
                        :instrument-type :perp
                        :coin "BTC"}]
          last-run {:request-signature
                    {:request {:current-portfolio {:capital {:nav-usdc 300}}
                               :constraints {:rebalance-tolerance 0}
                               :execution-assumptions {:fallback-slippage-bps 25
                                                       :prices-by-id {"perp:BTC" 100}}
                               :requested-universe instruments
                               :universe instruments}}
                    :result {:status :solved
                             :scenario-id "old"
                             :instrument-ids ["perp:BTC"]
                             :current-weights [0]
                             :target-weights [1]
                             :rebalance-preview
                             {:rows [{:instrument-id "perp:BTC"
                                      :instrument-type :perp
                                      :coin "BTC"
                                      :status :ready
                                      :side :buy
                                      :cost {:source :fallback-bps}}]}}}
          newer-run (assoc-in last-run [:result :scenario-id] "new")
          newer-run (assoc-in newer-run [:request-signature :request :seed] "new-run")
          store (atom {:portfolio {:optimizer {:last-successful-run last-run}}})
          promise (portfolio-optimizer-adapters/refresh-portfolio-optimizer-rebalance-slippage-snapshots-effect
                   nil
                   store
                   {:now-ms-fn (fn [] 10000)
                    :request-l2-book-snapshot!
                    (fn [_coin _opts]
                      (js/Promise.resolve
                       {:time 9000
                        :levels [[{:px "99" :sz "10"}]
                                 [{:px "101" :sz "10"}]]}))})]
      (reset! store {:portfolio {:optimizer {:last-successful-run newer-run}}})
      (-> promise
          (.then
           (fn [_]
             (is (= "new"
                    (get-in @store
                            (into optimizer-contracts/last-successful-run-result-path
                                  [:scenario-id]))))
             (is (= :fallback-bps
                    (get-in @store
                            (into optimizer-contracts/last-successful-run-result-path
                                  [:rebalance-preview :rows 0 :cost :source]))))
             (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest optimizer-history-api-browser-override-returns-nil-when-global-is-absent-test
  (with-optimizer-history-api-override
    absent-override
    (fn []
      (is (nil? (optimizer-history-api-browser-override))))))

(deftest normalize-optimizer-history-api-browser-override-accepts-camel-case-aliases-test
  (is (= {:enabled? true
          :base-url "https://history.test"
          :proxy-policy :approved-proxy-allowed
          :include-aligned-returns? false
          :fallback-to-legacy? false}
         (normalize-optimizer-history-api-browser-override
          {:enabled true
           :baseUrl "https://history.test"
           :proxyPolicy "approved_proxy_allowed"
           :includeAlignedReturns false
           :fallbackToLegacy false}))))

(deftest normalize-optimizer-history-api-browser-override-prefers-kebab-aliases-and-defaults-invalid-booleans-test
  (is (= {:enabled? false
          :base-url "https://kebab.test"
          :proxy-policy :native-only
          :include-aligned-returns? true
          :fallback-to-legacy? true}
         (normalize-optimizer-history-api-browser-override
          {:enabled? "not-a-bool"
           :enabled true
           :base-url "https://kebab.test"
           :baseUrl "https://camel.test"
           :proxy-policy :native_only
           :proxyPolicy "approved_proxy_allowed"
           :include-aligned-returns? "yes"
           :includeAlignedReturns false
           :fallback-to-legacy? nil
           :fallbackToLegacy false}))))

(deftest optimizer-history-api-browser-override-normalizes-js-global-object-test
  (with-optimizer-history-api-override
    (js-obj "enabled" true
            "baseUrl" "https://history.test"
            "proxyPolicy" "native only"
            "includeAlignedReturns" true
            "fallbackToLegacy" false)
    (fn []
      (is (= {:enabled? true
              :base-url "https://history.test"
              :proxy-policy :native-only
              :include-aligned-returns? true
              :fallback-to-legacy? false}
             (optimizer-history-api-browser-override))))))

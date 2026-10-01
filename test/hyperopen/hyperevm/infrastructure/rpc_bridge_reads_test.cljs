(ns hyperopen.hyperevm.infrastructure.rpc-bridge-reads-test
  "`read-balances!` carrying the bridge-health reads as `:extra-calls` in the
   same JSON-RPC batch."
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.hyperevm.domain.abi :as abi]
            [hyperopen.hyperevm.domain.bridge :as bridge]
            [hyperopen.hyperevm.domain.tokens :as tokens]
            [hyperopen.hyperevm.infrastructure.rpc :as rpc]
            [hyperopen.hyperevm.test-support.bridge-fixtures :as bridge-fixtures]
            [hyperopen.hyperevm.test-support.rpc-stubs :as stubs]
            [hyperopen.test-support.async :as async-support]))

(def ^:private owner "0x1111111111111111111111111111111111111111")
(def ^:private catalog (tokens/linked-tokens bridge-fixtures/bridge-spot-meta))

(defn- call-data-of
  [entry]
  (get-in entry [:params 0 :data]))

(deftest extra-calls-ride-the-same-batch-test
  (async done
    (let [calls (atom [])
          health-calls (bridge/health-calls catalog [1 6])
          ;; health-calls order: system PURR, SIX, HOPE, UBTC, JOFF, FUNT,
          ;; then decimals PURR, SIX.
          health-answer (stubs/encode-results
                         [[true (stubs/amount-data (get bridge-fixtures/live-system-units 1))]
                          [true (stubs/amount-data "0")]
                          [true (stubs/amount-data (get bridge-fixtures/live-system-units 122))]
                          [true (stubs/amount-data (get bridge-fixtures/live-system-units 197))]
                          [false "0x"]
                          [true (stubs/amount-data "0")]
                          [true (stubs/amount-data 18)]
                          [true (stubs/amount-data 18)]])
          fetch-fn (stubs/batch-fetch
                    calls
                    (fn [{:keys [id method]}]
                      (case method
                        "eth_getBalance" "0x0"
                        "eth_gasPrice" "0x1"
                        "eth_call" (if (= 3 id)
                                     (stubs/encode-results (repeat 7 [true (stubs/amount-data "0")]))
                                     health-answer))))]
      (-> (rpc/read-balances! {:fetch-fn fetch-fn}
                              {:owner owner :tokens catalog :extra-calls health-calls})
          (.then
           (fn [result]
             (let [[{:keys [body]} :as all] @calls]
               (is (= 1 (count all)) "one HTTP request")
               (is (= ["eth_getBalance" "eth_gasPrice" "eth_call" "eth_call"] (mapv :method body)))
               (is (= (abi/encode-aggregate3 (mapv #(assoc % :allow-failure? true) health-calls))
                      (call-data-of (nth body 3)))
                   "the extra calls are one allowFailure aggregate3"))
             (is (= {:native-wei "0" :token-units {} :gas-price-wei "1"}
                    (select-keys result [:native-wei :token-units :gas-price-wei])))
             (is (= [] (:extra-unread result)))
             (is (= {[:system 1] {:success? true
                                  :return-data (stubs/amount-data
                                                (get bridge-fixtures/live-system-units 1))}
                     [:system 296] {:success? false :return-data "0x"}}
                    (select-keys (:extra-results result) [[:system 1] [:system 296]])))
             (let [health (bridge/health-result catalog result)]
               (is (= "0" (get-in health [:evm-system-units 6])) "SIX's empty bridge")
               (is (= {:balance-of-ok? false} (get-in health [:token-health 296])) "JOFF reverts"))
             (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest a-failed-extra-chunk-is-unread-test
  (async done
    (let [health-calls (bridge/health-calls catalog [])
          fetch-fn (stubs/batch-fetch
                    (atom [])
                    (fn [{:keys [id method]}]
                      (case method
                        "eth_getBalance" "0x5"
                        "eth_gasPrice" "0x1"
                        "eth_call" (if (= 3 id)
                                     (stubs/encode-results (repeat 7 [true "0x"]))
                                     {:error {:code -32000 :message "out of gas"}}))))]
      (-> (rpc/read-balances! {:fetch-fn fetch-fn}
                              {:owner owner :tokens catalog :extra-calls health-calls})
          (.then
           (fn [result]
             (is (= "5" (:native-wei result)) "balances survive a failed bridge chunk")
             (is (= {} (:extra-results result)))
             (is (= (mapv :key health-calls) (:extra-unread result)))
             (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest invalid-extra-calls-reject-without-fetching-test
  (async done
    (let [calls (atom [])]
      (-> (rpc/read-balances! {:fetch-fn (stubs/batch-fetch calls (constantly "0x0"))}
                              {:owner owner
                               :tokens []
                               :extra-calls [{:key [:system 1] :target "not-an-address" :call-data "0x"}]})
          (.then (fn [value]
                   (is false (str "Expected a rejection, got " (pr-str value)))
                   (done)))
          (.catch (fn [err]
                    (is (= :invalid-request (:kind (ex-data err))))
                    (is (= [] @calls))
                    (done)))))))

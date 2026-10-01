(ns hyperopen.hyperevm.infrastructure.rpc-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.hyperevm.infrastructure.rpc :as rpc]
            [hyperopen.hyperevm.test-support.fixtures :as fixtures]
            [hyperopen.hyperevm.test-support.rpc-stubs
             :refer [amount-data encode-results expect-rejection json-response
                     request-body single-fetch]]
            [hyperopen.test-support.async :as async-support]))

;; read-balances! is covered in rpc-balances-test; this namespace covers the
;; transport, batching, errors and receipt polling.

(deftest encode-results-stub-reproduces-the-live-response-test
  (is (= fixtures/probe-aggregate3-result
         (encode-results [[true (amount-data fixtures/probe-native-wei)]
                          [true (amount-data fixtures/probe-purr-units)]
                          [false "0x"]]))))

(deftest batch-responses-are-matched-by-id-test
  (async done
    (let [fetch-fn (fn [_url init]
                     (js/Promise.resolve
                      (json-response 200 (->> (request-body init)
                                              (mapv (fn [{:keys [id]}]
                                                      {:jsonrpc "2.0" :id id :result (str "r" id)}))
                                              reverse
                                              vec))))]
      (-> (rpc/rpc-batch! {:fetch-fn fetch-fn} [["eth_chainId" []] ["eth_gasPrice" []]])
          (.then (fn [results]
                   (is (= ["r1" "r2"] results))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest batch-entry-error-rejects-with-its-code-test
  (async done
    (let [fetch-fn (fn [_url init]
                     (js/Promise.resolve
                      (json-response 200 (mapv (fn [{:keys [id method]}]
                                                 (if (= "eth_call" method)
                                                   {:jsonrpc "2.0" :id id
                                                    :error {:code -32000 :message "execution reverted"}}
                                                   {:jsonrpc "2.0" :id id :result "0x1"}))
                                               (request-body init)))))]
      (expect-rejection
       done
       (rpc/rpc-batch! {:fetch-fn fetch-fn}
                       [["eth_gasPrice" []]
                        ["eth_call" [{:to fixtures/probe-owner :data "0x"} "latest"]]])
       (fn [data err]
         (is (= :rpc (:kind data)))
         (is (= -32000 (:code data)))
         (is (= "eth_call" (:method data)))
         (is (re-find #"execution reverted" (.-message err))))))))

(deftest batch-level-error-object-rejects-test
  (async done
    (let [fetch-fn (fn [_url _init]
                     (js/Promise.resolve
                      (json-response 200 {:jsonrpc "2.0" :id nil
                                          :error {:code -32010 :message "Exceeded max limit of 20"}})))]
      (expect-rejection
       done
       (rpc/rpc-batch! {:fetch-fn fetch-fn} [["eth_gasPrice" []] ["eth_chainId" []]])
       (fn [data _err]
         (is (= :rpc (:kind data)))
         (is (= -32010 (:code data))))))))

(deftest http-429-rejects-as-rate-limited-test
  (async done
    (let [fetch-fn (fn [_url _init] (js/Promise.resolve (json-response 429 {})))]
      (expect-rejection
       done
       (rpc/gas-price! {:fetch-fn fetch-fn})
       (fn [data _err]
         (is (= {:kind :rate-limited :http-status 429 :code nil :method "eth_gasPrice"}
                data)))))))

(def ^:private live-rate-limit-reply
  "The public RPC's rate-limit reply, seen live on 2026-09-30: HTTP 200 and
   ONE JSON-RPC error object, even in reply to a batch."
  {:id nil :error {:code -32005 :message "rate limited"}})

(deftest json-rpc-rate-limit-rejects-as-rate-limited-test
  (async done
    (let [fetch-fn (fn [_url _init] (js/Promise.resolve (json-response 200 live-rate-limit-reply)))]
      (-> (js/Promise.all
           #js [(.then (rpc/gas-price! {:fetch-fn fetch-fn}) (constantly :resolved) ex-data)
                (.then (rpc/rpc-batch! {:fetch-fn fetch-fn} [["eth_gasPrice" []] ["eth_chainId" []]])
                       (constantly :resolved)
                       ex-data)])
          (.then (fn [[single batch]]
                   (is (= {:kind :rate-limited :code -32005 :http-status nil :method "eth_gasPrice"}
                          single)
                       "a single request")
                   (is (= :rate-limited (:kind batch))
                       "a batch answered with one non-array error object")
                   (is (= -32005 (:code batch)))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest batch-entry-rate-limit-rejects-as-rate-limited-test
  (async done
    (let [fetch-fn (fn [_url init]
                     (js/Promise.resolve
                      (json-response 200 (mapv (fn [{:keys [id]}]
                                                 {:jsonrpc "2.0" :id id
                                                  :error {:code -32005 :message "rate limited"}})
                                               (request-body init)))))]
      (expect-rejection
       done
       (rpc/rpc-batch! {:fetch-fn fetch-fn} [["eth_gasPrice" []]])
       (fn [data _err]
         (is (= :rate-limited (:kind data))))))))

(deftest http-error-status-rejects-with-status-test
  (async done
    (let [fetch-fn (fn [_url _init] (js/Promise.resolve (json-response 503 nil)))]
      (expect-rejection
       done
       (rpc/gas-price! {:fetch-fn fetch-fn})
       (fn [data _err]
         (is (= :http (:kind data)))
         (is (= 503 (:http-status data))))))))

(deftest network-failure-rejects-as-network-test
  (async done
    (let [fetch-fn (fn [_url _init] (js/Promise.reject (js/TypeError. "Failed to fetch")))]
      (expect-rejection
       done
       (rpc/gas-price! {:fetch-fn fetch-fn})
       (fn [data _err]
         (is (= :network (:kind data)))
         (is (nil? (:http-status data))))))))

(deftest invalid-json-rejects-as-invalid-response-test
  (async done
    (let [fetch-fn (fn [_url _init]
                     (js/Promise.resolve
                      #js {:ok true :status 200
                           :json (fn [] (js/Promise.reject (js/SyntaxError. "Unexpected token")))}))]
      (expect-rejection
       done
       (rpc/gas-price! {:fetch-fn fetch-fn})
       (fn [data _err]
         (is (= :invalid-response (:kind data))))))))

(deftest request-times-out-and-aborts-test
  (async done
    (let [scheduled (atom [])
          seen-signal (atom nil)
          fetch-fn (fn [_url init]
                     (reset! seen-signal (.-signal init))
                     (js/Promise. (fn [_resolve _reject] nil)))
          promise (rpc/gas-price! {:fetch-fn fetch-fn
                                   :timeout-ms 10000
                                   :set-timeout-fn (fn [f ms]
                                                     (swap! scheduled conj [f ms])
                                                     :timer-1)
                                   :clear-timeout-fn (fn [_] nil)})]
      (-> (js/Promise.resolve nil)
          (.then (fn [_]
                   (is (= [10000] (mapv second @scheduled)))
                   ((ffirst @scheduled))))
          (.then (fn [_]
                   (expect-rejection
                    done
                    promise
                    (fn [data _err]
                      (is (= :timeout (:kind data)))
                      (is (true? (.-aborted @seen-signal))
                          "the in-flight fetch is cancelled")))))))))

(deftest settled-request-clears-its-timer-test
  (async done
    (let [cleared (atom [])
          fetch-fn (fn [_url _init]
                     (js/Promise.resolve (json-response 200 {:jsonrpc "2.0" :id 1 :result "0x5914"})))]
      (-> (rpc/estimate-gas! {:fetch-fn fetch-fn
                              :set-timeout-fn (fn [_f _ms] :timer-7)
                              :clear-timeout-fn #(swap! cleared conj %)}
                             {:from fixtures/probe-owner :to fixtures/probe-owner :value "0x1"})
          (.then (fn [gas]
                   (is (= "22804" gas) "hex quantity comes back as a decimal string")
                   (is (= [:timer-7] @cleared))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest invalid-requests-reject-without-fetching-test
  (async done
    (let [calls (atom 0)
          fetch-fn (fn [& _] (swap! calls inc) (js/Promise.resolve nil))]
      (-> (js/Promise.all
           #js [(-> (rpc/read-balances! {:fetch-fn fetch-fn} {:owner "0x123" :tokens []})
                    (.then (constantly :resolved) #(:kind (ex-data %))))
                (-> (rpc/rpc-batch! {:fetch-fn fetch-fn} (repeat 21 ["eth_gasPrice" []]))
                    (.then (constantly :resolved) #(:kind (ex-data %))))])
          (.then (fn [kinds]
                   (is (= [:invalid-request :invalid-request] (vec kinds)))
                   (is (= 0 @calls))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest empty-batch-resolves-without-fetching-test
  (async done
    (-> (rpc/rpc-batch! {:fetch-fn (fn [& _] (throw (js/Error. "unexpected fetch")))} [])
        (.then (fn [results]
                 (is (= [] results))
                 (done)))
        (.catch (async-support/unexpected-error done)))))

(deftest non-quantity-results-reject-test
  (async done
    (let [fetch-fn (fn [_url _init]
                     (js/Promise.resolve (json-response 200 {:jsonrpc "2.0" :id 1 :result "abc"})))]
      (expect-rejection
       done
       (rpc/gas-price! {:fetch-fn fetch-fn})
       (fn [data _err]
         (is (= :invalid-response (:kind data))))))))

;; --- receipts ------------------------------------------------------------------

(defn- receipt-response
  [receipt]
  (fn [] (js/Promise.resolve (json-response 200 {:jsonrpc "2.0" :id 1 :result receipt}))))

(defn- async-timeout
  [delays]
  (fn [f ms]
    (swap! delays conj ms)
    (.then (js/Promise.resolve nil) f)
    :poll-timer))

(deftest wait-for-receipt-polls-until-success-test
  (async done
    (let [calls (atom [])
          delays (atom [])
          fetch-fn (single-fetch calls [(receipt-response nil)
                                        (receipt-response nil)
                                        (receipt-response {:status "0x1" :transactionHash "0xabc"})])]
      (-> (rpc/wait-for-receipt! {:fetch-fn fetch-fn}
                                 "0xabc"
                                 {:poll-ms 1000
                                  :timeout-ms 180000
                                  :schedule-poll-fn (async-timeout delays)
                                  :now-ms-fn (constantly 0)})
          (.then (fn [receipt]
                   (is (= {:status "0x1" :transactionHash "0xabc"} receipt))
                   (is (= [1000 1000] @delays))
                   (is (= {:jsonrpc "2.0" :id 1 :method "eth_getTransactionReceipt" :params ["0xabc"]}
                          (:body (first @calls))))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest wait-for-receipt-rejects-reverted-transactions-test
  (async done
    (let [fetch-fn (single-fetch (atom []) [(receipt-response {:status "0x0"})])]
      (expect-rejection
       done
       (rpc/wait-for-receipt! {:fetch-fn fetch-fn} "0xdead" {:now-ms-fn (constantly 0)})
       (fn [data _err]
         (is (= :reverted (:kind data)))
         (is (= "0xdead" (:tx-hash data))))))))

(deftest wait-for-receipt-times-out-test
  (async done
    (let [now (atom 0)
          fetch-fn (fn [_url _init]
                     (swap! now + 60000)
                     ((receipt-response nil)))]
      (expect-rejection
       done
       (rpc/wait-for-receipt! {:fetch-fn fetch-fn}
                              "0xabc"
                              {:timeout-ms 180000
                               :schedule-poll-fn (async-timeout (atom []))
                               :now-ms-fn #(deref now)})
       (fn [data _err]
         (is (= :receipt-timeout (:kind data)))
         (is (= 180000 @now)))))))

(deftest wait-for-receipt-retries-a-failed-poll-test
  ;; A rate-limited poll must not report a transaction that may have landed
  ;; as failed; the wait keeps polling until its deadline.
  (async done
    (let [fetch-fn (single-fetch (atom [])
                                 [(fn [] (js/Promise.resolve (json-response 429 {})))
                                  (receipt-response {:status "0x1"})])]
      (-> (rpc/wait-for-receipt! {:fetch-fn fetch-fn}
                                 "0xabc"
                                 {:schedule-poll-fn (async-timeout (atom []))
                                  :now-ms-fn (constantly 0)})
          (.then (fn [receipt]
                   (is (= "0x1" (:status receipt)))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest wait-for-receipt-backs-off-while-rate-limited-test
  ;; Another tab on the same IP can hold the public RPC's limit; retrying
  ;; every second would keep it there for the whole three-minute wait.
  (async done
    (let [delays (atom [])
          limited (fn [] (js/Promise.resolve
                          (json-response 200 {:jsonrpc "2.0" :id nil
                                              :error {:code -32005 :message "rate limited"}})))
          fetch-fn (single-fetch (atom [])
                                 [limited
                                  limited
                                  limited
                                  limited
                                  (receipt-response nil)
                                  (receipt-response {:status "0x1"})])]
      (-> (rpc/wait-for-receipt! {:fetch-fn fetch-fn}
                                 "0xabc"
                                 {:poll-ms 1000
                                  :schedule-poll-fn (async-timeout delays)
                                  :now-ms-fn (constantly 0)})
          (.then (fn [receipt]
                   (is (= "0x1" (:status receipt)))
                   (is (= [2000 4000 8000 8000 1000] @delays)
                       "doubling, capped at 8 s, and back to 1 s once the RPC answers")
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest wait-for-receipt-treats-nil-options-as-defaults-test
  ;; A present-but-nil option must not replace its default. A nil
  ;; :timeout-ms used to compare `elapsed >= null` (always true) and report a
  ;; false timeout right after the first pending poll.
  (async done
    (let [delays (atom [])
          fetch-fn (single-fetch (atom []) [(receipt-response nil)
                                            (receipt-response {:status "0x1"})])]
      (-> (rpc/wait-for-receipt! {:fetch-fn fetch-fn}
                                 "0xabc"
                                 {:poll-ms nil
                                  :timeout-ms nil
                                  :schedule-poll-fn (async-timeout delays)
                                  :now-ms-fn (constantly 0)})
          (.then (fn [receipt]
                   (is (= "0x1" (:status receipt)))
                   (is (= [rpc/default-receipt-poll-ms] @delays))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest wait-for-receipt-falls-back-to-platform-timers-test
  ;; nil scheduler and clock fall back to the real platform timers.
  (async done
    (let [fetch-fn (single-fetch (atom []) [(receipt-response nil)
                                            (receipt-response {:status "0x1"})])]
      (-> (rpc/wait-for-receipt! {:fetch-fn fetch-fn}
                                 "0xabc"
                                 {:poll-ms 1
                                  :schedule-poll-fn nil
                                  :now-ms-fn nil})
          (.then (fn [receipt]
                   (is (= "0x1" (:status receipt)))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest wait-for-receipt-poll-scheduler-is-not-the-request-timer-test
  ;; One map passed as both deps and opts: its immediate-firing poll
  ;; scheduler must not be taken for each request's deadline timer, which
  ;; would time out every poll.
  (async done
    (let [delays (atom [])
          shared {:fetch-fn (single-fetch (atom []) [(receipt-response nil)
                                                     (receipt-response {:status "0x1"})])
                  :schedule-poll-fn (async-timeout delays)
                  :now-ms-fn (constantly 0)}]
      (-> (rpc/wait-for-receipt! shared "0xabc" shared)
          (.then (fn [receipt]
                   (is (= "0x1" (:status receipt)))
                   (is (= [rpc/default-receipt-poll-ms] @delays))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

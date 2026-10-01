(ns hyperopen.hyperevm.infrastructure.rpc-balances-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.hyperevm.domain.abi :as abi]
            [hyperopen.hyperevm.domain.chain :as chain]
            [hyperopen.hyperevm.domain.tokens :as tokens]
            [hyperopen.hyperevm.infrastructure.rpc :as rpc]
            [hyperopen.hyperevm.test-support.fixtures :as fixtures]
            [hyperopen.hyperevm.test-support.rpc-stubs :as stubs]
            [hyperopen.test-support.async :as async-support]))

(def ^:private multicall (:multicall3-address chain/mainnet))

(def ^:private owner "0x1111111111111111111111111111111111111111")

(defn- balance-of-calls
  [owner* targets]
  (mapv (fn [target]
          {:target target
           :allow-failure? true
           :call-data (abi/encode-balance-of owner*)})
        targets))

(defn- synthetic-tokens
  [n]
  (mapv (fn [i] {:index i :erc20-address (stubs/token-address i)})
        (range n)))

(defn- ex-data-of
  "Resolve with the rejection's ex-data, or :resolved."
  [promise]
  (.then promise (constantly :resolved) ex-data))

;; Mainnet order of the fixture's ERC-20 reads: USDC, PURR, HOPE, UBTC, FUNT
;; (HYPE is native and read by eth_getBalance).
(def ^:private fixture-erc20-addresses
  ["0xb88339cb7199b77e23db6e890353e22632ba630f"
   "0x9b498c3c8a0b8cd8ba1d9851d40d186f1872b44e"
   "0x869ac826b78bc1d9501014994196e41025b5224b"
   "0x9fdbda0a5e284c32744d2f17ee5c74b284993463"
   "0xd6f92d754818307d0e2853eada247178f1ae605b"])

(deftest read-balances-replays-the-live-probe-values-test
  ;; The live probe's owner, tokens and answers (2026-09-30). Native HYPE now
  ;; comes from eth_getBalance, so the aggregate carries only the probe's two
  ;; cast-verified balanceOf calls.
  (async done
    (let [calls (atom [])
          fetch-fn (stubs/batch-fetch
                    calls
                    (fn [{:keys [method]}]
                      (case method
                        "eth_getBalance" (stubs/quantity fixtures/probe-native-wei)
                        "eth_gasPrice" fixtures/probe-gas-price-hex
                        "eth_call" (stubs/encode-results
                                    [[true (stubs/amount-data fixtures/probe-purr-units)]
                                     [false "0x"]]))))]
      (-> (rpc/read-balances!
           {:fetch-fn fetch-fn}
           {:owner fixtures/probe-owner
            :tokens [{:index 1 :erc20-address "0x9b498c3c8a0b8cd8ba1d9851d40d186f1872b44e"}
                     {:index 0 :erc20-address "0x6b9e773128f453f5c2c60935ee2de2cbc5390a24"}]
            :chain chain/mainnet})
          (.then
           (fn [result]
             (is (= {:native-wei fixtures/probe-native-wei
                     :token-units {1 fixtures/probe-purr-units}
                     :gas-price-wei fixtures/probe-gas-price-wei
                     :failed-token-indexes [0]}
                    result)
                 "the reverted CoreDepositWallet read is reported failed, not zeroed")
             (let [[{:keys [url init body]} :as all] @calls]
               (is (= 1 (count all)) "one HTTP request")
               (is (= "https://rpc.hyperliquid.xyz/evm" url))
               (is (= "POST" (.-method init)))
               (is (= "application/json" (aget (.-headers init) "content-type")))
               (is (some? (.-signal init)) "the request is abortable")
               (is (= [{:jsonrpc "2.0" :id 1 :method "eth_getBalance"
                        :params [fixtures/probe-owner "latest"]}
                       {:jsonrpc "2.0" :id 2 :method "eth_gasPrice" :params []}
                       {:jsonrpc "2.0" :id 3 :method "eth_call"
                        :params [{:to multicall
                                  :data (abi/encode-aggregate3 (rest fixtures/probe-calls))}
                                 "latest"]}]
                      body)))
             (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest read-balances-reads-linked-tokens-from-their-erc20-contracts-test
  (async done
    (let [calls (atom [])
          linked (tokens/linked-tokens fixtures/mainnet-spot-meta)
          fetch-fn (stubs/batch-fetch
                    calls
                    (fn [{:keys [method]}]
                      (case method
                        "eth_getBalance" (stubs/quantity "12500000000000000000")
                        "eth_gasPrice" "0x5f5e100"
                        ;; call order: USDC, PURR, HOPE, UBTC, FUNT
                        "eth_call" (stubs/encode-results
                                    [[true (stubs/amount-data "1000000000")]
                                     [true (stubs/amount-data "0")]
                                     [false "0x"]
                                     [true (stubs/amount-data "100000000")]
                                     [true "0x"]]))))]
      (-> (rpc/read-balances! {:fetch-fn fetch-fn} {:owner owner :tokens linked})
          (.then
           (fn [result]
             (is (= {:native-wei "12500000000000000000"
                     :token-units {0 "1000000000" 197 "100000000"}
                     :gas-price-wei "100000000"
                     :failed-token-indexes [122 478]}
                    result)
                 "zero balances are omitted; a failed call and an empty reply are unknown")
             (is (= (abi/encode-aggregate3 (balance-of-calls owner fixture-erc20-addresses))
                    (get-in (first @calls) [:body 2 :params 0 :data]))
                 "USDC reads native USDC (never the CoreDepositWallet); HYPE is not in the aggregate")
             (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest read-balances-reports-a-zero-native-balance-test
  ;; Gas status must tell "no HYPE" (:none) from "not read yet", so a zero
  ;; native balance is reported, unlike zero token balances.
  (async done
    (let [calls (atom [])
          fetch-fn (stubs/batch-fetch calls
                                      (fn [{:keys [method]}]
                                        (case method
                                          "eth_getBalance" "0x0"
                                          "eth_gasPrice" "0x1")))]
      (-> (rpc/read-balances! {:fetch-fn fetch-fn} {:owner owner :tokens []})
          (.then
           (fn [result]
             (is (= {:native-wei "0" :token-units {} :gas-price-wei "1"} result))
             (is (= ["eth_getBalance" "eth_gasPrice"]
                    (mapv :method (:body (first @calls))))
                 "no tokens, no aggregate3 call")
             (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest read-balances-chunks-tokens-per-aggregate-call-test
  (async done
    (let [calls (atom [])
          token-count (inc rpc/max-tokens-per-aggregate)
          fetch-fn (stubs/batch-fetch
                    calls
                    (fn [{:keys [id method]}]
                      (case method
                        "eth_getBalance" "0x7"
                        "eth_gasPrice" "0x1"
                        "eth_call" (if (= 3 id)
                                     (stubs/encode-results
                                      (map (fn [i] [true (stubs/amount-data (inc i))])
                                           (range rpc/max-tokens-per-aggregate)))
                                     (stubs/encode-results
                                      [[true (stubs/amount-data token-count)]])))))]
      (-> (rpc/read-balances! {:fetch-fn fetch-fn}
                              {:owner owner :tokens (synthetic-tokens token-count)})
          (.then
           (fn [result]
             (let [body (:body (first @calls))]
               (is (= 1 (count @calls)))
               (is (= ["eth_getBalance" "eth_gasPrice" "eth_call" "eth_call"]
                      (mapv :method body)))
               (is (= (abi/encode-aggregate3
                       (balance-of-calls owner
                                         [(stubs/token-address rpc/max-tokens-per-aggregate)]))
                      (get-in body [3 :params 0 :data]))))
             (is (= "7" (:native-wei result)))
             (is (= token-count (count (:token-units result))))
             (is (= (str token-count)
                    (get-in result [:token-units rpc/max-tokens-per-aggregate])))
             (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest read-balances-splits-json-rpc-batches-at-twenty-test
  (async done
    (let [calls (atom [])
          chunk-count rpc/max-batch-size
          token-count (* chunk-count rpc/max-tokens-per-aggregate)
          fetch-fn (stubs/batch-fetch
                    calls
                    (fn [{:keys [method]}]
                      (if (= "eth_call" method)
                        (stubs/encode-results
                         (repeat rpc/max-tokens-per-aggregate [true (stubs/amount-data "1")]))
                        "0x1")))]
      (-> (rpc/read-balances! {:fetch-fn fetch-fn}
                              {:owner owner :tokens (synthetic-tokens token-count)})
          (.then
           (fn [result]
             (is (= [20 2] (mapv (comp count :body) @calls)))
             (is (= ["eth_getBalance" "eth_gasPrice"]
                    (mapv :method (take 2 (:body (first @calls))))))
             (is (= ["eth_call" "eth_call"] (mapv :method (:body (second @calls)))))
             (is (= token-count (count (:token-units result))))
             (is (not (contains? result :unread-token-indexes)))
             (is (not (contains? result :failed-token-indexes)))
             (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest read-balances-survives-a-failing-aggregate-call-test
  ;; A linked token whose balanceOf burns its gas fails the whole aggregate3
  ;; call. Native HYPE and the gas price must still arrive, and the token
  ;; balances must be reported unknown rather than zero.
  (async done
    (let [fetch-fn (stubs/batch-fetch
                    (atom [])
                    (fn [{:keys [method]}]
                      (case method
                        "eth_getBalance" (stubs/quantity "12500000000000000000")
                        "eth_gasPrice" "0x5f5e100"
                        "eth_call" {:error {:code -32000 :message "out of gas"}})))]
      (-> (rpc/read-balances! {:fetch-fn fetch-fn}
                              {:owner owner
                               :tokens (tokens/linked-tokens fixtures/mainnet-spot-meta)})
          (.then
           (fn [result]
             (is (= {:native-wei "12500000000000000000"
                     :token-units {}
                     :gas-price-wei "100000000"
                     :unread-token-indexes [0 1 122 197 478]}
                    result))
             (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest read-balances-never-shifts-balances-on-a-malformed-aggregate-test
  ;; An aggregate3 result with one entry too few or too many cannot be lined
  ;; up with the tokens it was sent for. That chunk is unread; the other
  ;; chunk's balances stay on their own tokens.
  (async done
    (let [token-count (inc rpc/max-tokens-per-aggregate)
          read-with (fn [first-chunk-size]
                      (rpc/read-balances!
                       {:fetch-fn (stubs/batch-fetch
                                   (atom [])
                                   (fn [{:keys [id method]}]
                                     (case method
                                       "eth_getBalance" "0x7"
                                       "eth_gasPrice" "0x1"
                                       "eth_call" (stubs/encode-results
                                                   (if (= 3 id)
                                                     (map (fn [i] [true (stubs/amount-data (inc i))])
                                                          (range first-chunk-size))
                                                     [[true (stubs/amount-data token-count)]])))))}
                       {:owner owner :tokens (synthetic-tokens token-count)}))]
      (-> (js/Promise.all
           #js [(read-with (dec rpc/max-tokens-per-aggregate))
                (read-with (inc rpc/max-tokens-per-aggregate))])
          (.then
           (fn [results]
             (doseq [result results]
               (is (= {:native-wei "7"
                       :token-units {rpc/max-tokens-per-aggregate (str token-count)}
                       :gas-price-wei "1"
                       :unread-token-indexes (vec (range rpc/max-tokens-per-aggregate))}
                      result)))
             (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest read-balances-rejects-when-native-or-gas-price-is-unreadable-test
  (async done
    (let [read-with (fn [answer]
                      (ex-data-of
                       (rpc/read-balances!
                        {:fetch-fn (stubs/batch-fetch (atom []) answer)}
                        {:owner owner
                         :tokens [{:index 1 :erc20-address (stubs/token-address 1)}]})))
          ok-call (stubs/encode-results [[true (stubs/amount-data "5")]])]
      (-> (js/Promise.all
           #js [(read-with (fn [{:keys [method]}]
                             (case method
                               "eth_getBalance" {:error {:code -32000 :message "boom"}}
                               "eth_gasPrice" "0x1"
                               "eth_call" ok-call)))
                (read-with (fn [{:keys [method]}]
                             (case method
                               "eth_getBalance" "0x1"
                               "eth_gasPrice" "not-a-quantity"
                               "eth_call" ok-call)))])
          (.then
           (fn [[native-error gas-error]]
             (is (= [:rpc -32000 "eth_getBalance"]
                    ((juxt :kind :code :method) native-error)))
             (is (= [:invalid-response "eth_gasPrice"]
                    ((juxt :kind :method) gas-error)))
             (done)))
          (.catch (async-support/unexpected-error done))))))

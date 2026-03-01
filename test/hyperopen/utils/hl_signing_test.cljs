(ns hyperopen.utils.hl-signing-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [async deftest is testing]]
            [hyperopen.utils.hl-signing :as signing]))

(defn- bytes->vec [bytes]
  (vec (array-seq bytes)))

(deftest hex-bytes-roundtrip-test
  (testing "hex input supports 0x prefix and odd lengths"
    (is (= [10 188] (bytes->vec (signing/hex->bytes "0xabc")))))
  (testing "bytes->hex and hex->bytes roundtrip"
    (let [hex "00ff10ab"
          bytes (signing/hex->bytes hex)]
      (is (= hex (signing/bytes->hex bytes))))))

(deftest build-typed-data-test
  (let [connection-id "0x1234"
        typed-data (signing/build-typed-data connection-id)]
    (is (= "Exchange" (get-in typed-data [:domain :name])))
    (is (= 1337 (get-in typed-data [:domain :chainId])))
    (is (= connection-id (get-in typed-data [:message :connectionId])))))

(deftest compute-connection-id-parity-vectors-test
  (testing "matches l1 action hash parity vector without vault/expires"
    (let [action {:type "order"
                  :orders [{:a 0
                            :b true
                            :p "100"
                            :s "0.1"
                            :r false
                            :t {:limit {:tif "Gtc"}}}]
                  :grouping "na"}
          nonce 1700000000123]
      (is (= "0xd7567be22b72cdf306bcd6dee16555ed17e5f49ff477f59cb0ed032d283fa064"
             (signing/compute-connection-id action nonce)))))

  (testing "matches l1 action hash parity vector with vault and expiresAfter"
    (let [action {:type "cancel"
                  :cancels [{:a 0 :o 12345678}]}
          nonce 1700001234567
          vault "0x1234567890abcdef1234567890abcdef12345678"
          expires-after 1700002234567]
      (is (= "0x18a9dfb8109ee9401dd124caf447c474138e558b5b6759b5df096f1a8bdee2dd"
             (signing/compute-connection-id action
                                            nonce
                                            :vault-address vault
                                            :expires-after expires-after)))))

  (testing "matches l1 action hash parity vector for cancel with large oid"
    (let [action {:type "cancel"
                  :cancels [{:a 5
                             :o 317776454141}]}
          nonce 1770824619171]
      (is (= "0x4b3f03be6a802e9484c28b9ca31353e7e097059fa40f46f243f531ebf44fd57c"
             (signing/compute-connection-id action nonce))))))

(deftest build-typed-data-selects-source-by-environment-test
  (let [connection-id "0x1234"
        mainnet (signing/build-typed-data connection-id)
        testnet (signing/build-typed-data connection-id :is-mainnet false)]
    (is (= "a" (get-in mainnet [:message :source])))
    (is (= "b" (get-in testnet [:message :source])))))

(deftest build-approve-agent-typed-data-test
  (let [typed-data (signing/build-approve-agent-typed-data
                     {:hyperliquidChain "Mainnet"
                      :signatureChainId "0xa4b1"
                      :agentAddress "0x1234567890abcdef1234567890abcdef12345678"
                      :agentName ""
                      :nonce 1700000000999})]
    (is (= "HyperliquidSignTransaction" (get-in typed-data [:domain :name])))
    (is (= 42161 (get-in typed-data [:domain :chainId])))
    (is (= "HyperliquidTransaction:ApproveAgent" (:primaryType typed-data)))
    (is (= "Mainnet" (get-in typed-data [:message :hyperliquidChain])))
    (is (= "0x1234567890abcdef1234567890abcdef12345678"
           (get-in typed-data [:message :agentAddress])))))

(deftest build-usd-class-transfer-typed-data-test
  (let [typed-data (signing/build-usd-class-transfer-typed-data
                    {:hyperliquidChain "Mainnet"
                     :signatureChainId "0xa4b1"
                     :amount "12.34"
                     :toPerp true
                     :nonce 1700000003000})]
    (is (= "HyperliquidSignTransaction" (get-in typed-data [:domain :name])))
    (is (= 42161 (get-in typed-data [:domain :chainId])))
    (is (= "HyperliquidTransaction:UsdClassTransfer" (:primaryType typed-data)))
    (is (= "Mainnet" (get-in typed-data [:message :hyperliquidChain])))
    (is (= "12.34" (get-in typed-data [:message :amount])))
    (is (= true (get-in typed-data [:message :toPerp])))
    (is (= 1700000003000 (get-in typed-data [:message :nonce])))))

(deftest build-withdraw3-typed-data-test
  (let [typed-data (signing/build-withdraw3-typed-data
                    {:hyperliquidChain "Testnet"
                     :signatureChainId "0x66eee"
                     :destination "0x1234567890abcdef1234567890abcdef12345678"
                     :amount "7.5"
                     :time 1700000004000})]
    (is (= "HyperliquidSignTransaction" (get-in typed-data [:domain :name])))
    (is (= 421614 (get-in typed-data [:domain :chainId])))
    (is (= "HyperliquidTransaction:Withdraw" (:primaryType typed-data)))
    (is (= "Testnet" (get-in typed-data [:message :hyperliquidChain])))
    (is (= "0x1234567890abcdef1234567890abcdef12345678"
           (get-in typed-data [:message :destination])))
    (is (= "7.5" (get-in typed-data [:message :amount])))
    (is (= 1700000004000 (get-in typed-data [:message :time])))))

(deftest split-signature-test
  (let [r (apply str (repeat 64 "a"))
        s (apply str (repeat 64 "b"))
        v "1c"
        signature (str "0x" r s v)
        parts (signing/split-signature signature)]
    (is (= (str "0x" r) (:r parts)))
    (is (= (str "0x" s) (:s parts)))
    (is (= 28 (:v parts)))))

(deftest sign-l1-action-with-private-key-is-deterministic-test
  (async done
    (let [private-key "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          action {:type "order"
                  :orders [{:a 0
                            :b true
                            :p "100"
                            :s "0.1"
                            :r false
                            :t {:limit {:tif "Gtc"}}}]
                  :grouping "na"}
          nonce 1700000007777]
      (-> (js/Promise.all
           #js [(signing/sign-l1-action-with-private-key! private-key action nonce)
                (signing/sign-l1-action-with-private-key! private-key action nonce)])
          (.then (fn [results]
                   (let [[first-sig second-sig] (js->clj results :keywordize-keys true)]
                     (is (= (:connectionId first-sig) (:connectionId second-sig)))
                     (is (= (:r first-sig) (:r second-sig)))
                     (is (= (:s first-sig) (:s second-sig)))
                     (is (= (:v first-sig) (:v second-sig)))
                     (is (re-matches #"0x[0-9a-f]{64}" (:r first-sig)))
                     (is (re-matches #"0x[0-9a-f]{64}" (:s first-sig)))
                     (is (contains? #{27 28} (:v first-sig)))
                     (done))))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest sign-approve-agent-action-falls-back-to-next-typed-data-method-test
  (async done
    (let [original-provider (.-ethereum js/globalThis)
          calls (atom [])
          r (apply str (repeat 64 "a"))
          s (apply str (repeat 64 "b"))
          signature (str "0x" r s "1c")
          address "0x1234567890abcdef1234567890abcdef12345678"
          action {:hyperliquidChain "Mainnet"
                  :signatureChainId "0xa4b1"
                  :agentAddress "0x9999999999999999999999999999999999999999"
                  :agentName ""
                  :nonce 1700000000999}]
      (set! (.-ethereum js/globalThis)
            #js {:request (fn [payload]
                            (let [method (aget payload "method")
                                  params (array-seq (aget payload "params"))]
                              (swap! calls conj {:method method :params params})
                              (if (= 1 (count @calls))
                                (js/Promise.reject (js/Error. "unsupported typed data format"))
                                (js/Promise.resolve signature))))})
      (-> (signing/sign-approve-agent-action! address action)
          (.then (fn [signed]
                   (let [result (js->clj signed :keywordize-keys true)]
                     (is (= 2 (count @calls)))
                     (is (= "eth_signTypedData_v4" (:method (first @calls))))
                     (is (= "eth_signTypedData_v4" (:method (second @calls))))
                     (is (string? (second (:params (first @calls)))))
                     (is (some? (aget (second (:params (second @calls))) "types")))
                     (is (= signature (:sig result)))
                     (is (= (str "0x" r) (:r result)))
                     (is (= (str "0x" s) (:s result)))
                     (is (= 28 (:v result)))
                     (set! (.-ethereum js/globalThis) original-provider)
                     (done))))
          (.catch (fn [err]
                    (set! (.-ethereum js/globalThis) original-provider)
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest sign-approve-agent-action-errors-when-provider-missing-test
  (async done
    (let [original-provider (.-ethereum js/globalThis)
          address "0x1234567890abcdef1234567890abcdef12345678"
          action {:hyperliquidChain "Mainnet"
                  :signatureChainId "0xa4b1"
                  :agentAddress "0x9999999999999999999999999999999999999999"
                  :agentName ""
                  :nonce 1700000000999}]
      (set! (.-ethereum js/globalThis) nil)
      (-> (signing/sign-approve-agent-action! address action)
          (.then (fn [_]
                   (set! (.-ethereum js/globalThis) original-provider)
                   (is false "Expected signing to fail when provider is missing")
                   (done)))
          (.catch (fn [err]
                    (set! (.-ethereum js/globalThis) original-provider)
                    (let [message (or (some-> err .-message str)
                                      (str err))]
                      (is (str/includes? message "No wallet provider found.")))
                    (done)))))))

(ns hyperopen.utils.hl-signing-test
  (:require [cljs.test :refer-macros [deftest is testing]]
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
                                            :expires-after expires-after))))))

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

(deftest split-signature-test
  (let [r (apply str (repeat 64 "a"))
        s (apply str (repeat 64 "b"))
        v "1c"
        signature (str "0x" r s v)
        parts (signing/split-signature signature)]
    (is (= (str "0x" r) (:r parts)))
    (is (= (str "0x" s) (:s parts)))
    (is (= 28 (:v parts)))))

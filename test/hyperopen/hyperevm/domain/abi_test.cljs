(ns hyperopen.hyperevm.domain.abi-test
  "Golden vectors below were produced independently with foundry's `cast sig`
   / `cast calldata` on 2026-09-30, and the aggregate3 round trip replays a
   live HyperEVM mainnet response (see the fixtures namespace)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [hyperopen.hyperevm.domain.abi :as abi]
            [hyperopen.hyperevm.test-support.fixtures :as fixtures]))

(defn- big
  [text]
  (js/BigInt text))

(def ^:private hype-system "0x2222222222222222222222222222222222222222")
(def ^:private purr-system "0x2000000000000000000000000000000000000001")
(def ^:private core-deposit-wallet "0x6b9e773128f453f5c2c60935ee2de2cbc5390a24")
(def ^:private max-uint256
  "115792089237316195423570985008687907853269984665640564039457584007913129639935")

(deftest selectors-match-cast-sig-test
  (is (= {:balance-of "0x70a08231"
          :transfer "0xa9059cbb"
          :approve "0x095ea7b3"
          :allowance "0xdd62ed3e"
          :core-deposit "0x2b2dfd2c"
          :get-eth-balance "0x4d2301cc"
          :aggregate3 "0x82ad56cb"
          :decimals "0x313ce567"}
         abi/selectors))
  (is (= "0x313ce567" (abi/encode-decimals))))

(deftest encode-core-deposit-golden-vectors-test
  (testing "deposit(1000000, 4294967295): 1 USDC to spot"
    (is (= (str "0x2b2dfd2c"
                "00000000000000000000000000000000000000000000000000000000000f4240"
                "00000000000000000000000000000000000000000000000000000000ffffffff")
           (abi/encode-core-deposit (big "1000000") 4294967295))))
  (testing "deposit(1000000000, 4294967295): 1,000 USDC to spot"
    (is (= (str "0x2b2dfd2c"
                "000000000000000000000000000000000000000000000000000000003b9aca00"
                "00000000000000000000000000000000000000000000000000000000ffffffff")
           (abi/encode-core-deposit "1000000000" 4294967295))))
  (testing "deposit(1000000, 0): perps"
    (is (= (str "0x2b2dfd2c"
                "00000000000000000000000000000000000000000000000000000000000f4240"
                "0000000000000000000000000000000000000000000000000000000000000000")
           (abi/encode-core-deposit (big "1000000") 0))))
  (testing "destinationDex must fit a uint32"
    (is (nil? (abi/encode-core-deposit (big "1") 4294967296)))
    (is (nil? (abi/encode-core-deposit (big "1") -1)))))

(deftest encode-erc20-golden-vectors-test
  (is (= "0x70a082310000000000000000000000002222222222222222222222222222222222222222"
         (abi/encode-balance-of hype-system)))
  (is (= (str "0xa9059cbb"
              "0000000000000000000000002000000000000000000000000000000000000001"
              "0000000000000000000000000000000000000000000000000de0b6b3a7640000")
         (abi/encode-transfer purr-system (big "1000000000000000000"))))
  (is (= (str "0xa9059cbb"
              "0000000000000000000000002000000000000000000000000000000000000001"
              "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
         (abi/encode-transfer purr-system (big max-uint256))))
  (is (= (str "0x095ea7b3"
              "0000000000000000000000006b9e773128f453f5c2c60935ee2de2cbc5390a24"
              "000000000000000000000000000000000000000000000000000000003b9aca00")
         (abi/encode-approve core-deposit-wallet (big "1000000000"))))
  (is (= (str "0xdd62ed3e"
              "0000000000000000000000002222222222222222222222222222222222222222"
              "0000000000000000000000006b9e773128f453f5c2c60935ee2de2cbc5390a24")
         (abi/encode-allowance hype-system core-deposit-wallet)))
  (is (= "0x4d2301cc0000000000000000000000002222222222222222222222222222222222222222"
         (abi/encode-get-eth-balance hype-system))))

(deftest checksummed-addresses-are-lowercased-test
  (is (= (abi/encode-balance-of "0xca11bde05977b3631167028862be2a173976ca11")
         (abi/encode-balance-of "0xcA11bde05977b3631167028862bE2a173976CA11"))))

(deftest encoders-return-nil-on-invalid-input-and-never-throw-test
  (let [bad-addresses [nil "" "0x" "0x1234" "2222222222222222222222222222222222222222"
                       "0x222222222222222222222222222222222222222g"
                       "0x22222222222222222222222222222222222222222" 42 {}]
        bad-amounts [nil -1 1.5 "1.5" "-1" "abc" (big "-1")
                     (+ (big max-uint256) (big "1")) {}]]
    (doseq [address bad-addresses]
      (testing (pr-str address)
        (is (nil? (abi/encode-balance-of address)))
        (is (nil? (abi/encode-get-eth-balance address)))
        (is (nil? (abi/encode-transfer address (big "1"))))
        (is (nil? (abi/encode-approve address (big "1"))))
        (is (nil? (abi/encode-allowance address hype-system)))
        (is (nil? (abi/encode-allowance hype-system address)))))
    (doseq [amount bad-amounts]
      (testing (pr-str amount)
        (is (nil? (abi/encode-transfer purr-system amount)))
        (is (nil? (abi/encode-approve core-deposit-wallet amount)))
        (is (nil? (abi/encode-core-deposit amount 4294967295)))))))

(deftest encode-aggregate3-matches-the-live-probe-calldata-test
  (is (= fixtures/probe-aggregate3-calldata
         (abi/encode-aggregate3 fixtures/probe-calls)))
  (testing "the probe calls are exactly what the encoders produce"
    (is (= [(abi/encode-get-eth-balance fixtures/probe-owner)
            (abi/encode-balance-of fixtures/probe-owner)
            (abi/encode-balance-of fixtures/probe-owner)]
           (mapv :call-data fixtures/probe-calls)))))

(deftest encode-aggregate3-edge-cases-test
  (testing "an empty call list (cast calldata of [])"
    (is (= (str "0x82ad56cb"
                "0000000000000000000000000000000000000000000000000000000000000020"
                "0000000000000000000000000000000000000000000000000000000000000000")
           (abi/encode-aggregate3 []))))
  (testing "a call with empty calldata (cast calldata of [(purr,true,0x)])"
    (is (= (str "0x82ad56cb"
                "0000000000000000000000000000000000000000000000000000000000000020"
                "0000000000000000000000000000000000000000000000000000000000000001"
                "0000000000000000000000000000000000000000000000000000000000000020"
                "0000000000000000000000009b498c3c8a0b8cd8ba1d9851d40d186f1872b44e"
                "0000000000000000000000000000000000000000000000000000000000000001"
                "0000000000000000000000000000000000000000000000000000000000000060"
                "0000000000000000000000000000000000000000000000000000000000000000")
           (abi/encode-aggregate3 [{:target "0x9b498c3c8a0b8cd8ba1d9851d40d186f1872b44e"
                                    :allow-failure? true
                                    :call-data "0x"}]))))
  (testing "invalid calls yield nil"
    (is (nil? (abi/encode-aggregate3 nil)))
    (is (nil? (abi/encode-aggregate3 {:target hype-system})))
    (is (nil? (abi/encode-aggregate3 [{:target "0x12" :allow-failure? true :call-data "0x"}])))
    (is (nil? (abi/encode-aggregate3 [{:target hype-system :allow-failure? true :call-data "0x123"}])))
    (is (nil? (abi/encode-aggregate3 [{:target hype-system :allow-failure? true :call-data "abcd"}])))
    (is (nil? (abi/encode-aggregate3 [{:target hype-system :allow-failure? true :call-data nil}])))
    (is (nil? (abi/encode-aggregate3 ["not-a-call"])))))

(deftest decode-aggregate3-decodes-the-live-probe-response-test
  (let [decoded (abi/decode-aggregate3 fixtures/probe-aggregate3-result)]
    (is (= [true true false] (mapv :success? decoded)))
    (is (= "0x" (:return-data (nth decoded 2))) "the CoreDepositWallet has no balanceOf")
    (is (= (big fixtures/probe-native-wei)
           (abi/decode-uint256 (:return-data (nth decoded 0)))))
    (is (= (big fixtures/probe-purr-units)
           (abi/decode-uint256 (:return-data (nth decoded 1)))))
    (is (nil? (abi/decode-uint256 (:return-data (nth decoded 2)))))))

(deftest decode-aggregate3-rejects-malformed-data-without-throwing-test
  (let [live fixtures/probe-aggregate3-result
        word (fn [hex] (str (apply str (repeat (- 64 (count hex)) "0")) hex))
        replace-word (fn [data index hex]
                       (let [start (+ 2 (* 64 index))]
                         (str (subs data 0 start) (word hex) (subs data (+ start 64)))))]
    (doseq [[label value]
            [["nil" nil]
             ["blank" ""]
             ["empty" "0x"]
             ["odd length" "0x1"]
             ["not hex" "0xzz"]
             ["no prefix" (subs live 2)]
             ["number" 42]
             ["truncated tail" (subs live 0 (- (count live) 64))]
             ["truncated head" (subs live 0 66)]
             ["array offset past the end" (replace-word live 0 "ffff")]
             ["huge array offset" (replace-word live 0 (apply str (repeat 64 "f")))]
             ["array length past the end" (replace-word live 1 "ff")]
             ["tuple offset past the end" (replace-word live 2 "ffff")]
             ["bool word that is neither 0 nor 1" (replace-word live 5 "2")]
             ["bytes length past the end" (replace-word live 7 "ffff")]]]
      (testing label
        (is (nil? (abi/decode-aggregate3 value)))))
    (is (= [] (abi/decode-aggregate3
               (str "0x"
                    (word "20")
                    (word "0"))))
        "a well-formed empty result decodes to an empty vector")))

(deftest decode-uint256-test
  (is (= (big "1000000")
         (abi/decode-uint256 "0x00000000000000000000000000000000000000000000000000000000000f4240")))
  (is (= (big max-uint256)
         (abi/decode-uint256 (str "0x" (apply str (repeat 64 "f"))))))
  (is (= (big "1")
         (abi/decode-uint256 (str "0x" (str/join (repeat 63 "0")) "1" (str/join (repeat 64 "0")))))
      "longer return data reads its first word")
  (doseq [value [nil "" "0x" "0x1234" "0xzz" 5]]
    (is (nil? (abi/decode-uint256 value)))))

(deftest json-rpc-quantities-test
  (is (= "0x0" (abi/quantity-hex (big "0"))))
  (is (= "0x8ac7230489e80000" (abi/quantity-hex (big "10000000000000000000")))
      "10 HYPE in wei, the EVM->Core acceptance value")
  (is (= "0x8ac7230489e80000" (abi/quantity-hex "10000000000000000000")))
  (is (nil? (abi/quantity-hex "-1")))
  (is (nil? (abi/quantity-hex nil)))
  (is (= (big "107410883") (abi/parse-quantity fixtures/probe-gas-price-hex)))
  (is (= (big "0") (abi/parse-quantity "0x0")))
  (doseq [value [nil "" "0x" "107410883" "0xg" 5]]
    (is (nil? (abi/parse-quantity value)))))

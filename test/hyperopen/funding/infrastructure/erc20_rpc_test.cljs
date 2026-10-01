(ns hyperopen.funding.infrastructure.erc20-rpc-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.funding.infrastructure.erc20-rpc :as erc20-rpc]
            [hyperopen.test-support.async :as async-support]))

(def ^:private owner-address
  "0xABCDEFabcdefABCDEFabcdefABCDEFabcdefABCD")

(def ^:private spender-address
  "0x2222222222222222222222222222222222222222")

(def ^:private token-address
  "0x3333333333333333333333333333333333333333")

(def ^:private pad-24
  (apply str (repeat 24 "0")))

(def ^:private pad-63
  (apply str (repeat 63 "0")))

(def ^:private hex-64-fs
  (apply str (repeat 64 "f")))

(deftest erc20-call-data-encoders-pad-addresses-and-amounts-test
  (is (= (str "0xa9059cbb"
              pad-24
              "abcdefabcdefabcdefabcdefabcdefabcdefabcd"
              pad-63
              "f")
         (erc20-rpc/encode-erc20-transfer-call-data
          (str "  " owner-address "  ")
          (js/BigInt "15"))))
  (is (= (str "0x095ea7b3"
              pad-24
              "2222222222222222222222222222222222222222"
              hex-64-fs)
         (erc20-rpc/encode-erc20-approve-call-data
          spender-address
          (js/BigInt (str "0x" hex-64-fs)))))
  (is (= (str "0x70a08231"
              pad-24
              "abcdefabcdefabcdefabcdefabcdefabcdefabcd")
         (erc20-rpc/encode-erc20-balance-of-call-data owner-address)))
  (is (= (str "0xdd62ed3e"
              pad-24
              "abcdefabcdefabcdefabcdefabcdefabcdefabcd"
              pad-24
              "2222222222222222222222222222222222222222")
         (erc20-rpc/encode-erc20-allowance-call-data owner-address spender-address))))

(deftest bigint-from-hex-normalizes-invalid-inputs-to-zero-test
  (is (= "16"
         (.toString (erc20-rpc/bigint-from-hex " 0X10 ") 10)))
  (is (= "0"
         (.toString (erc20-rpc/bigint-from-hex "garbage") 10)))
  (is (= "0"
         (.toString (erc20-rpc/bigint-from-hex nil) 10))))

(deftest read-erc20-balance-and-allowance-units-build-eth-call-requests-test
  (async done
    (let [calls (atom [])
          provider-request! (fn [provider method params]
                              (swap! calls conj [provider method params])
                              (if (= 1 (count @calls))
                                (js/Promise.resolve "0x2a")
                                (js/Promise.resolve "not-hex")))]
      (-> (js/Promise.all
           #js [(erc20-rpc/read-erc20-balance-units!
                 provider-request!
                 :provider
                 token-address
                 owner-address)
                (erc20-rpc/read-erc20-allowance-units!
                 provider-request!
                 :provider
                 token-address
                 owner-address
                 spender-address)])
          (.then (fn [results]
                   (is (= ["42" "0"]
                          (mapv #(.toString % 10)
                                (js->clj results))))
                   (is (= [[:provider
                            "eth_call"
                            [{:to token-address
                              :data (erc20-rpc/encode-erc20-balance-of-call-data owner-address)}
                             "latest"]]
                           [:provider
                            "eth_call"
                            [{:to token-address
                              :data (erc20-rpc/encode-erc20-allowance-call-data
                                     owner-address
                                     spender-address)}
                             "latest"]]]
                          @calls))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest erc20-encoders-throw-rather-than-send-without-calldata-test
  ;; The encoders delegate to hyperopen.hyperevm.domain.abi, which returns
  ;; nil on invalid input; a deposit must never send a transaction whose
  ;; calldata is missing.
  (is (thrown? js/Error (erc20-rpc/encode-erc20-transfer-call-data "0xnope" (js/BigInt "1"))))
  (is (thrown? js/Error (erc20-rpc/encode-erc20-approve-call-data token-address (js/BigInt "-1"))))
  (is (thrown? js/Error (erc20-rpc/encode-erc20-balance-of-call-data nil)))
  (is (thrown? js/Error (erc20-rpc/encode-erc20-allowance-call-data owner-address "0x"))))

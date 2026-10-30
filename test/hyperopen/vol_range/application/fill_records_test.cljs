(ns hyperopen.vol-range.application.fill-records-test
  "The canonical fill record shared with Reporting (HO-SPEC-014 v2.1
  sections 7.4, 9.2, 13.5 row 4; VR-14). Synthetic rows only."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.vol-range.application.fill-records :as fill-records]
            [hyperopen.vol-range.application.lifecycle-evidence :as evidence]
            [hyperopen.vol-range.live-fixtures :as fx]))

(defn- record
  ([row] (record row :websocket fx/received-at-ms))
  ([row source received-at-ms]
   (fill-records/normalize-fill {:network fx/network :account-address fx/account :row row
                                 :source source :received-at-ms received-at-ms})))

(def ^:private varying [:source :received-at-ms])

(deftest the-websocket-and-rest-rows-of-one-fill-give-one-record-test
  (let [ws (record (first (get-in fx/fills-increment [:data :fills])))
        rest-r (record fx/rest-partial-fill :rest (+ fx/received-at-ms 9000))]
    (is (= (apply dissoc ws varying) (apply dissoc rest-r varying)))
    (is (= [:websocket fx/received-at-ms] ((juxt :source :received-at-ms) ws)))
    (is (= [:rest (+ fx/received-at-ms 9000)] ((juxt :source :received-at-ms) rest-r)))
    (is (= (evidence/fill-key fx/network fx/account fx/partial-fill) (:fill-key ws) (:fill-key rest-r))
        "the same key the ladders use")
    (is (fill-records/same-fill? ws rest-r))
    (is (= {:fill-key "hyperliquid-mainnet|0xabc|SYN/USDC|1700000005000|4401|9000"
            :network :hyperliquid-mainnet
            :account-address "0xabc"
            :coin "SYN/USDC"
            :time 1700000005000
            :tid "4401"
            :oid "9000"
            :cloid nil
            :side :buy
            :px "76.4" :sz "2"
            :fee "0.0012" :fee-token "SYN" :builder-fee "0.0002"
            :crossed false
            :hash "0x0000000000000000000000000000000000000000000000000000000000004401"
            :dir "Buy" :start-position "150.0"
            :source :websocket :received-at-ms fx/received-at-ms}
           ws))))

(deftest the-account-and-cloid-are-lower-cased-test
  (let [r (fill-records/normalize-fill {:network fx/network :account-address " 0xABC "
                                        :row (assoc fx/partial-fill :cloid "0x484F5652000000000000000000000A01")
                                        :source :websocket :received-at-ms 1})]
    (is (= "0xabc" (:account-address r)))
    (is (= "0x484f5652000000000000000000000a01" (:cloid r)))
    (is (= (:fill-key (record fx/partial-fill)) (:fill-key r)) "the key lower-cases the account too")))

(deftest numeric-and-numeric-string-time-and-tid-agree-test
  (let [numeric (record fx/partial-fill)
        texts (record (assoc fx/partial-fill :time "1700000005000" :tid "4401"))]
    (is (= numeric texts))
    (is (= 1700000005000 (:time texts)) "time is venue ms as an integer")
    (is (= "4401" (:tid texts)) "tid is integer text")))

(deftest sides-map-and-unknown-sides-are-rejected-test
  (is (= :buy (:side (record fx/partial-fill))))
  (is (= :sell (:side (record (assoc fx/partial-fill :side "A")))))
  (doseq [side [nil "" "b" "S" "Buy" :buy]]
    (is (nil? (record (assoc fx/partial-fill :side side))) (pr-str side))))

(deftest rows-that-cannot-identify-a-fill-give-nil-test
  (doseq [k [:coin :time :tid :px :sz]]
    (testing (str "missing " k)
      (is (nil? (record (dissoc fx/partial-fill k))))
      (is (nil? (record (assoc fx/partial-fill k ""))))))
  (testing "non-integer time or tid, non-decimal money"
    (is (nil? (record (assoc fx/partial-fill :time "soon"))))
    (is (nil? (record (assoc fx/partial-fill :tid "4401a"))))
    (is (nil? (record (assoc fx/partial-fill :px "1e3"))))
    (is (nil? (record (assoc fx/partial-fill :sz "two")))))
  (testing "neither a lossless oid nor a CLOID"
    (is (nil? (record (dissoc fx/partial-fill :oid))))
    (is (nil? (record (assoc fx/partial-fill :oid (inc js/Number.MAX_SAFE_INTEGER))))
        "an unsafe numeric oid is never trusted")
    (let [by-cloid (record (-> fx/partial-fill (dissoc :oid) (assoc :cloid fx/cloid-buy-0)))]
      (is (= [nil fx/cloid-buy-0] ((juxt :oid :cloid) by-cloid)))
      (is (= (evidence/fill-key fx/network fx/account (-> fx/partial-fill (dissoc :oid) (assoc :cloid fx/cloid-buy-0)))
             (:fill-key by-cloid)))))
  (testing "no network or account"
    (is (nil? (fill-records/normalize-fill {:account-address fx/account :row fx/partial-fill})))
    (is (nil? (fill-records/normalize-fill {:network fx/network :account-address " " :row fx/partial-fill})))))

(deftest money-keeps-the-venue-text-test
  (let [r (record (assoc fx/partial-fill :px "76.40000" :sz "2.000" :fee "-0.00012345678" :startPosition "150"))]
    (is (= ["76.40000" "2.000" "-0.00012345678" "150"] ((juxt :px :sz :fee :start-position) r))
        "never re-rounded; a negative fee (rebate) keeps its sign"))
  (is (= ["76.4" "2"] ((juxt :px :sz) (record (assoc fx/partial-fill :px 76.4 :sz 2))))
      "a numeric venue value becomes its text"))

(deftest the-fee-already-includes-the-builder-fee-test
  (let [r (record (assoc fx/partial-fill :fee "0.4" :builderFee "0.1" :feeToken "USDC"))]
    (is (= ["0.4" "0.1" "USDC"] ((juxt :fee :builder-fee :fee-token) r))
        "spec 13.5 row 4: the fee is kept as is, never fee plus builder fee"))
  (is (nil? (:builder-fee (record (dissoc fx/partial-fill :builderFee))))))

(deftest execution-attributes-are-preserved-test
  (is (true? (:crossed (record (assoc fx/partial-fill :crossed true)))))
  (is (false? (:crossed (record fx/partial-fill))))
  (is (nil? (:crossed (record (dissoc fx/partial-fill :crossed)))) "unknown stays unknown"))

(deftest same-fill-compares-fill-keys-test
  (let [a (record fx/partial-fill)]
    (is (fill-records/same-fill? a (record fx/partial-fill :rest 1)))
    (is (not (fill-records/same-fill? a (record fx/other-coin-fill)))
        "the same tid on another coin is another fill")
    (is (not (fill-records/same-fill? a (record fx/manual-fill))))
    (is (not (fill-records/same-fill? nil nil)))
    (is (not (fill-records/same-fill? {} {})))))

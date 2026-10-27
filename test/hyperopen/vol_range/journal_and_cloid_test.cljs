(ns hyperopen.vol-range.journal-and-cloid-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.vol-range.application.journal-records :as jr]
            [hyperopen.vol-range.domain.cloid :as cloid]
            [hyperopen.vol-range.infrastructure.entropy :as entropy]))

(deftest cloid-shape-test
  (is (= "0x484f5652000000000000000000000001" (cloid/cloid "000000000000000000000001")))
  (is (cloid/vol-range-cloid? "0x484f5652ABCDEF0123456789abcdef01"))
  (is (not (cloid/vol-range-cloid? "0x11111111abcdef0123456789abcdef01")) "other families are not ours")
  (doseq [bad [nil "" "123" "zzzzzzzzzzzzzzzzzzzzzzzz" "0000000000000000000000001"]]
    (is (nil? (cloid/cloid bad)) (pr-str bad)))
  (is (= 34 (count (cloid/cloid (entropy/random-cloid-suffix!)))) "0x + 32 hex digits = 128 bits")
  (is (cloid/vol-range-cloid? (cloid/cloid (entropy/random-cloid-suffix!)))))

(deftest assign-cloids-test
  (let [legs [{:leg-id "buy-0"} {:leg-id "buy-1"}]]
    (is (= ["0x484f5652000000000000000000000001" "0x484f5652000000000000000000000002"]
           (mapv :cloid (:legs (cloid/assign-cloids legs ["000000000000000000000001" "000000000000000000000002"])))))
    (is (= {:error :cloid/duplicate}
           (cloid/assign-cloids legs ["000000000000000000000001" "000000000000000000000001"])))
    (is (= {:error :cloid/suffix-invalid} (cloid/assign-cloids legs ["000000000000000000000001"])))))

(def scope {:network :hyperliquid-mainnet :account-address "0xAcc" :spot-pair-index 107})

(defn- intent [op usdc base & [state]]
  (cond-> (jr/intent-record {:operation-id op :scope scope :scope-key "k" :plan-id "p" :input-fingerprint "f"
                             :action-digest "d" :legs [{:leg-id "buy-0" :cloid "0x1" :side :buy :price "76.4" :size "7"}]
                             :reservations {:usdc usdc :base base} :now-ms 1})
    state (assoc :reservation-state state)))

(deftest records-are-versioned-and-free-of-signing-material-test
  (let [r (intent "op1" "2889.1552" "0")]
    (is (jr/record-valid? r))
    (is (= "op:op1" (:record-id r)))
    (is (= {:usdc "2889.1552" :base "0"} (:reservations r)) "monetary values are clean strings")
    (is (not (jr/record-valid? (assoc r :signature {:r "0x" :s "0x" :v 27}))))
    (is (not (jr/record-valid? (assoc-in r [:legs 0 :private-key] "0xdead"))) "checked at any depth")
    (is (not (jr/record-valid? (dissoc r :saved-at-ms))))))

(deftest pending-reservations-count-each-intent-once-test
  (let [records [(intent "a" "100" "0") (intent "b" "50.5" "3") (intent "c" "999" "0" :released)
                 (assoc-in (intent "d" "7" "0") [:scope :account-address] "0xother")]]
    (is (= {:usdc "150.5" :base "3" :operation-ids ["a" "b"]}
           (jr/pending-reservations records "0xacc")))))

(deftest unresolved-attempts-test
  (let [base (jr/attempt-record {:operation-id "op" :attempt-id "a1" :scope-key "k" :nonce 5
                                 :expires-after-ms 30 :action-digest "d" :now-ms 1})]
    (is (jr/attempt-unresolved? base) "recorded as sending: a crash here is unresolved")
    (is (jr/attempt-unresolved? (jr/with-outcome base {:delivery :unknown} 2)))
    (is (not (jr/attempt-unresolved? (jr/with-outcome base {:delivery :not-sent} 2))))
    (is (jr/attempt-unresolved? (jr/with-outcome base {:delivery :response :leg-results []} 2))
        "a response with no legs proves nothing")
    (is (jr/attempt-unresolved? (jr/with-outcome base {:delivery :response
                                                        :leg-results [{:placement-state :accepted}
                                                                      {:placement-state :unknown}]} 2)))
    (is (not (jr/attempt-unresolved? (jr/with-outcome base {:delivery :response
                                                             :leg-results [{:placement-state :accepted}
                                                                           {:placement-state :rejected}]} 2))))
    (is (= 1 (count (jr/unresolved-attempts [base (intent "x" "1" "0")] "k"))))
    (is (= 0 (count (jr/unresolved-attempts [base] "other-scope"))))))

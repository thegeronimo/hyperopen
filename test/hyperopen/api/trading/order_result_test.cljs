(ns hyperopen.api.trading.order-result-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.api.trading.order-result :as r]))

(def legs (mapv (fn [i] {:cloid (str "0x484f5652" (apply str (repeat 23 "0")) i)}) (range 7)))

(defn- ok [statuses] {:status "ok" :response {:type "order" :data {:statuses statuses}}})
(defn- states [res] (mapv :placement-state (:leg-results res)))

(deftest mixed-statuses-preserve-each-leg-test
  ;; Spec 13.4: one post-only rejection, six resting.
  (let [res (r/normalize-order-response
             legs
             (ok (into [{:error "Post only order would have immediately matched, bbo was 76.39@76.41. asset=10107"}]
                       (for [i (range 1 7)] {:resting {:oid (+ 1000 i)}}))))]
    (is (= :per-leg (:response-category res)))
    (is (= (into [:rejected] (repeat 6 :accepted)) (states res)))
    (is (= {:accepted 6 :rejected 1 :unknown 0 :category :partial} (:summary res)))
    (is (= "1001" (:oid (second (:leg-results res)))) "oids are kept as lossless strings")
    (is (re-find #"Post only" (:reason (first (:leg-results res)))))))

(deftest filled-entries-carry-fill-evidence-test
  (let [res (r/normalize-order-response (take 1 legs) (ok [{:filled {:oid 9 :totalSz "7" :avgPx "76.4"}}]))]
    (is (= [:accepted] (states res)))
    (is (= {:oid "9" :order-state :filled :filled-base "7" :avg-px "76.4"}
           (select-keys (first (:leg-results res)) [:oid :order-state :filled-base :avg-px])))))

(deftest unfamiliar-responses-are-never-success-test
  (testing "empty vector"
    (is (= (repeat 7 :unknown) (states (r/normalize-order-response legs (ok []))))))
  (testing "wrong length"
    (is (= (repeat 7 :unknown) (states (r/normalize-order-response legs (ok [{:resting {:oid 1}}]))))))
  (testing "unexpected response type"
    (is (= (repeat 7 :unknown)
           (states (r/normalize-order-response legs {:status "ok" :response {:type "cancel"}})))))
  (testing "unknown entry shape is unknown, not success"
    (let [res (r/normalize-order-response (take 2 legs) (ok [{:resting {:oid 1}} {:waitingForTrigger {}}]))]
      (is (= [:accepted :unknown] (states res)))
      (is (= :unresolved (get-in res [:summary :category])))))
  (testing "an entry with no oid is unknown"
    (is (= [:unknown] (states (r/normalize-order-response (take 1 legs) (ok [{:resting {}}]))))))
  (testing "a CLOID that does not match its index is resolved by original ids later"
    (is (= [:unknown] (states (r/normalize-order-response
                               (take 1 legs) (ok [{:resting {:oid 1 :cloid "0x484f5652ffffffffffffffffffffffff"}}]))))))
  (testing "a non-map body (unreadable) is unknown"
    (is (= :unrecognized (:response-category (r/normalize-order-response legs nil)))))
  (testing "status ok with a non-ok-looking payload"
    (is (= (repeat 7 :unknown) (states (r/normalize-order-response legs {:status "weird"}))))))

(deftest whole-action-rejection-rejects-every-leg-test
  (let [res (r/normalize-order-response legs {:status "err" :response "Insufficient spot balance"})]
    (is (= :whole-action-rejection (:response-category res)))
    (is (= (repeat 7 :rejected) (states res)))
    (is (every? #(= "Insufficient spot balance" (:reason %)) (:leg-results res)))
    (is (= :all-rejected (get-in res [:summary :category])))))

(deftest cancel-normalization-test
  (let [targets [{:oid "1"} {:oid "2"} {:oid "3"}]
        res (r/normalize-cancel-response
             targets
             {:status "ok" :response {:type "cancel"
                                      :data {:statuses ["success"
                                                        {:error "Order was never placed, already canceled, or filled. asset=1"}
                                                        {:error "Rate limited"}]}}})]
    (is (= [:cancel-acknowledged :not-resting :rejected] (mapv :cancel-state (:results res))))
    (is (r/missing-order-text? "Order was never placed, already canceled, or filled."))
    (testing "a missing status vector is unknown, never assumed success"
      (is (every? #(= :unknown (:cancel-state %))
                  (:results (r/normalize-cancel-response targets {:status "ok" :response {:type "cancel"}})))))))

(deftest cancel-whole-rejection-cloid-and-oid-precision-test
  (let [targets [{:cloid "0x484f5652000000000000000000000001"}]]
    (is (= [:rejected] (mapv :cancel-state (:results (r/normalize-cancel-response
                                                       targets {:status "err" :response "Rate limited"})))))
    (is (= [:cancel-acknowledged]
           (mapv :cancel-state (:results (r/normalize-cancel-response
                                          targets {:status "ok" :response {:type "cancelByCloid"
                                                                           :data {:statuses ["success"]}}}))))))
  (testing "an oid beyond 2^53 cannot be represented losslessly and is unknown"
    (is (= [:unknown] (states (r/normalize-order-response
                               (take 1 legs) (ok [{:resting {:oid 9007199254740993}}])))))))

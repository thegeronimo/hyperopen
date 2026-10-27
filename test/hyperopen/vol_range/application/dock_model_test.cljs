(ns hyperopen.vol-range.application.dock-model-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.vol-range.application.dock-model :as dm]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.application.lifecycle-evidence :as evidence]))

(def c (fn [n] (str "0x484f56520000000000000000000000" (if (< n 10) (str "0" n) n))))
(def scope {:network :hyperliquid-mainnet :account-address "0xabc" :coin "SYN/USDC" :symbol "SYN"
            :quote-symbol "USDC" :asset-id 10001})

(defn- seven-legs []
  (vec (concat (map-indexed (fn [i [p s]] {:leg-id (str "buy-" i) :side :buy :rung-index i :price p :size s
                                          :cloid (c (inc i))})
                            [["76.4" "7"] ["74.0" "9"] ["71.6" "11"] ["69.2" "13"]])
               (map-indexed (fn [i p] {:leg-id (str "sell-" i) :side :sell :rung-index i :price p :size "10"
                                       :cloid (c (+ 5 i))})
                            ["84" "86" "88"]))))

(defn- placed [rejected-cloids]
  (let [legs (seven-legs)
        g (-> (lifecycle/new-grid {:grid-id "g" :scope scope :scope-key "k" :operation-id "op" :plan-id "p"
                                   :expires-after-ms 31000 :now-ms 1000 :legs legs
                                   :model {:anchor {:mode :custom :price "80"} :volatility {:daily-fraction "0.06"}}})
              (lifecycle/mark-sending "op" "a" 1500))]
    (evidence/apply-placement-delivery
     g "op" {:delivery :response
             :leg-results (map-indexed (fn [i l]
                                         (if (contains? rejected-cloids (:cloid l))
                                           {:cloid (:cloid l) :placement-state :rejected :reason "Post only order would have immediately matched"}
                                           {:cloid (:cloid l) :oid (str (+ 100 i)) :placement-state :accepted :order-state :resting}))
                                       legs)}
     2000)))

(deftest partial-deployment-row-states-the-spec-message-test
  (let [row (dm/grid-row {} (placed #{(c 1)}))]
    (is (= :partially-deployed (:status row)))
    (is (= "Partially deployed" (:status-label row)))
    (is (= "1 order was not placed. 6 other orders remain active." (:partial-message row)))
    (is (= "33" (get-in row [:counts :live-buy-units])))
    (is (:can-cancel? row))
    (is (= "Rejected" (:status-label (first (:legs row)))))
    (is (= "SYN/USDC" (:market row)))))

(deftest unknown-outcomes-are-flagged-and-never-reassuring-test
  (let [g (evidence/apply-placement-delivery
           (-> (lifecycle/new-grid {:grid-id "g" :scope scope :scope-key "k" :operation-id "op" :plan-id "p"
                                    :expires-after-ms 31000 :now-ms 1000 :legs (seven-legs)})
               (lifecycle/mark-sending "op" "a" 1500))
           "op" {:delivery :unknown} 2000)
        row (dm/grid-row {} g)]
    (is (= "Checking status" (:status-label row)))
    (is (:attention? row))
    (is (every? #(= "Checking" (:status-label %)) (:legs row)))))

(deftest review-commit-respects-the-gate-and-the-local-cap-test
  (let [state {:vol-range {:execution {:enabled? true :max-ladder-usdc "150"}
                           :frozen-review {:status :ready :legs [{:price "60" :size "1" :notional "60"}
                                                                 {:price "60" :size "1" :notional "60"}]}}}]
    (is (= {:visible? true :enabled? true :placing? false :label "Place 2 orders"}
           (dissoc (dm/review-commit state) :note)))
    (testing "over the cap"
      (is (false? (:enabled? (dm/review-commit (assoc-in state [:vol-range :execution :max-ladder-usdc] "100"))))))
    (testing "not armed"
      (is (false? (:visible? (dm/review-commit (assoc-in state [:vol-range :execution :enabled?] false))))))
    (testing "placing"
      (let [m (dm/review-commit (assoc-in state [:vol-range :execution :busy [:place]] true))]
        (is (:placing? m))
        (is (false? (:enabled? m)))))))

(deftest status-message-explains-a-refused-send-test
  (is (re-find #"Nothing was sent"
               (dm/status-message {:vol-range {:execution {:last {:op :place :status :not-placed :delivery :not-sent
                                                                   :errors [:guard/book-stale]}}}})))
  (is (re-find #"changed since review"
               (dm/status-message {:vol-range {:execution {:last {:op :place :status :admission-failed}}}}))))

(deftest the-dock-only-shows-the-active-accounts-ladders-test
  (let [owner "0x1234567890abcdef1234567890abcdef12345678"
        g (assoc-in (placed #{}) [:scope :account-address] owner)
        state {:vol-range {:execution {:grids {"g" g}}}}]
    (is (= 1 (count (:rows (dm/dock (assoc state :wallet {:address (.toUpperCase owner)}))))))
    (is (empty? (:rows (dm/dock (assoc state :wallet {:address "0xfedcba0987654321fedcba0987654321fedcba09"}))))
        "after an account switch the previous account's ladders are not shown")))

(deftest cancel-messages-are-about-the-ladder-test
  (is (re-find #"still in flight"
               (dm/status-message {:vol-range {:execution {:last {:op :cancel :status :busy}}}}))))

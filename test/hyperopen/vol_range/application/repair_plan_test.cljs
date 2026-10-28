(ns hyperopen.vol-range.application.repair-plan-test
  "Pure repair proposal and the shared repair checks (HO-SPEC-014 sections
  6.3, 6.6, 8.6, 13.4). 13.2 fixture: r = 80, v = 0.06, size decimals 2."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.vol-range.application.ladder-settings :as ladder-settings]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.application.lifecycle-evidence :as evidence]
            [hyperopen.vol-range.application.planner :as planner]
            [hyperopen.vol-range.application.repair-plan :as repair-plan]
            [hyperopen.vol-range.application.revalidation :as revalidation]
            [hyperopen.vol-range.fixtures :as fixtures]))

(def scope {:network :hyperliquid-mainnet :account-address "0xabc" :coin "SYN/USDC" :asset-id 10001
            :spot-pair-index 1})

(def plan (planner/prepare-plan (fixtures/plan-input)))

(def rejection "Post only order would have immediately matched, bbo was 76.39@76.41. asset=10001")

(defn- cloid [i] (str "0x" (.padStart (str i) 32 "0")))

(defn- partial-grid
  "The 13.4 partial deployment: the seven-order fixture with `rejected`
  (default the 76.4 buy) definitively rejected and every other leg resting."
  ([] (partial-grid {}))
  ([{:keys [settings? rejected] :or {settings? true rejected #{"buy-0"}}}]
   (let [legs (vec (map-indexed (fn [i l] (assoc l :cloid (cloid (inc i)))) (:legs plan)))
         g (-> (lifecycle/new-grid {:grid-id "g" :scope scope :scope-key "k" :operation-id "op"
                                    :plan-id "plan-1" :legs legs
                                    :model (select-keys (:model plan) [:anchor :volatility :tilt :shifted-anchor])
                                    :settings (when settings?
                                                (ladder-settings/settings-from-plan plan fixtures/draft-13-2))
                                    :expires-after-ms 31000 :now-ms 1000})
               (lifecycle/mark-sending "op" "a1" 1500))]
     (evidence/apply-placement-delivery
      g "op"
      {:delivery :response
       :leg-results (map-indexed (fn [i {:keys [leg-id] :as l}]
                                   (if (contains? rejected leg-id)
                                     {:cloid (:cloid l) :placement-state :rejected :reason rejection}
                                     {:cloid (:cloid l) :oid (str (+ 100 i)) :placement-state :accepted
                                      :order-state :resting}))
                                 legs)}
      2000))))

(defn- leg-of [grid logical-id]
  (get-in grid [:legs (lifecycle/current-cloid (get-in grid [:members logical-id]))]))

(defn- survivor-orders
  "Open orders as an account read shows them: every resting current leg."
  [grid]
  (->> (lifecycle/current-legs grid)
       (filter lifecycle/live-leg?)
       (mapv (fn [l] {:oid (:oid l) :side (:side l) :price (:price l) :remaining (:original-base l)}))))

(defn- account [grid overrides]
  (fixtures/account-snapshot (merge {:open-orders (survivor-orders grid)} overrides)))

(defn- market [bid ask] (fixtures/market-snapshot {:bbo {:bid bid :ask ask :observed-at-ms 1000}}))

(defn- propose
  ([grid bid ask] (propose grid bid ask {}))
  ([grid bid ask account-overrides]
   (repair-plan/propose {:grid grid :market (market bid ask) :account (account grid account-overrides)
                         :fees fixtures/synthetic-fees :now-ms 5000})))

(deftest fixture-is-the-13-4-partial-deployment-test
  (let [g (partial-grid)
        counts (lifecycle/counts g)]
    (is (= 6 (:live counts)))
    (is (= 1 (:rejected counts)))
    (is (= "76.4" (:price (leg-of g "buy-0"))))
    (is (= "80" (get-in g [:model :shifted-anchor])))
    (is (= "0.06" (get-in g [:model :volatility :daily-fraction])))))

(deftest a-crossing-rejected-buy-steps-away-from-the-book-test
  ;; Actual distance 0.75 -> first step 0.80 -> 80 x (1 - 0.8 x 0.06) = 76.16.
  (let [proposal (propose (partial-grid) "76.00" "76.35")
        [leg] (:legs proposal)]
    (is (= 1 (count (:legs proposal))))
    (is (empty? (:unrepairable proposal)))
    (is (= {:logical-id "buy-0" :side :buy :rung-index 0 :rejected-price "76.4" :price "76.16" :size "7"
            :notional "533.12" :actual-sigma "-0.8" :repriced? true :reason rejection}
           leg))
    (is (neg? (compare (js/parseFloat (:price leg)) 76.4)) "a buy only ever moves down, away from the book")
    (is (= #{"buy-0"} (:selected proposal)) "every repairable leg is selected by default")
    (is (= {:bid "76.00" :ask "76.35" :observed-at-ms 1000} (:bbo proposal)))
    (is (= {:core "120" :maximum "190"} (:limits proposal)))
    (is (= {:base-total "150" :available-base "150" :available-usdc "3000" :buy-exposure "33" :sell-exposure "30"}
           (:account proposal))
        "the survivors' open orders are the existing exposure")
    (is (= 5000 (:proposed-at-ms proposal)))
    (is (= [{:logical-id "buy-0" :price "76.16" :size "7"}] (repair-plan/selected-legs proposal)))))

(deftest a-rejected-price-that-no-longer-crosses-is-kept-test
  (let [[leg] (:legs (propose (partial-grid) "75.00" "76.50"))]
    (is (= "76.4" (:price leg)))
    (is (= "7" (:size leg)))
    (is (false? (:repriced? leg)))))

(deftest a-step-on-a-surviving-price-is-skipped-test
  ;; A survivor buy already rests at 76.16, so the next step (0.85) is used.
  (let [g (partial-grid)
        g (assoc-in g [:legs (:cloid (leg-of g "buy-1")) :price] "76.16")
        [leg] (:legs (propose g "76.00" "76.35"))]
    (is (= "75.92" (:price leg)))
    (is (= "-0.85" (:actual-sigma leg)))))

(deftest a-leg-with-no-safe-price-is-not-repairable-test
  (testing "a sell that would need more than 4.00 sigma"
    (let [proposal (propose (partial-grid {:rejected #{"sell-2"}}) "99.50" "99.60")]
      (is (empty? (:legs proposal)))
      (is (= [{:logical-id "sell-2" :side :sell :rung-index 2 :rejected-price "90.8" :size "10"
               :code :repair/no-safe-price}]
             (:unrepairable proposal)))
      (is (= #{} (:selected proposal)))
      (is (= [] (repair-plan/selected-legs proposal)))))
  (testing "a sell beyond 50% of the mid"
    (let [proposal (propose (partial-grid {:rejected #{"sell-2"}}) "49.90" "50.10")]
      (is (= [:repair/no-safe-price] (map :code (:unrepairable proposal)))))))

(deftest a-leg-below-the-minimum-notional-at-its-safe-price-test
  (let [g (partial-grid)
        g (assoc-in g [:legs (:cloid (leg-of g "buy-0")) :original-base] "0.13")
        proposal (propose g "76.00" "76.35")]
    (is (empty? (:legs proposal)))
    (is (= [{:logical-id "buy-0" :side :buy :rung-index 0 :rejected-price "76.4" :size "0.13"
             :code :repair/below-min-notional}]
           (:unrepairable proposal)))))

(deftest a-ladder-without-its-model-is-not-repairable-test
  (let [proposal (propose (dissoc (partial-grid) :model) "76.00" "76.35")]
    (is (= [:repair/model-unavailable] (map :code (:unrepairable proposal))))
    (is (empty? (:legs proposal)))))

(deftest nothing-to-propose-while-anything-is-unresolved-test
  (let [g (assoc-in (partial-grid) [:legs (cloid 2) :placement-state] :unknown)]
    (is (= {:legs [] :unrepairable []} (select-keys (propose g "76.00" "76.35") [:legs :unrepairable])))))

(def buy-repair [{:logical-id "buy-0" :side :buy :price "76.16" :size "7"}])

(defn- summary [overrides]
  (merge {:base-total "150" :available-base "150" :available-usdc "3000" :buy-exposure "0" :sell-exposure "0"}
         overrides))

(deftest bound-errors-test
  (testing "q 190 at a maximum of 190: a buy repair breaches the maximum"
    (is (= [:repair/max-inventory-breach]
           (repair-plan/bound-errors {:account (summary {:base-total "190"}) :limits {:core "120" :maximum "190"}
                                      :legs buy-repair :fees fixtures/synthetic-fees}))))
  (testing "a sell repair below the protected core"
    (is (= [:repair/protected-core-breach]
           (repair-plan/bound-errors {:account (summary {:base-total "125"}) :limits {:core "120" :maximum "190"}
                                      :legs [{:logical-id "sell-0" :side :sell :price "86" :size "10"}]
                                      :fees fixtures/synthetic-fees}))))
  (testing "a buy while already above the maximum deepens the breach"
    (is (= [:repair/max-inventory-breach]
           (repair-plan/bound-errors {:account (summary {:base-total "200"}) :limits {:core "120" :maximum "190"}
                                      :legs buy-repair :fees fixtures/synthetic-fees}))))
  (testing "the survivors' open orders count once: 150 + 33 + 7 = 190 fits"
    (is (= [] (repair-plan/bound-errors {:account (summary {:buy-exposure "33" :sell-exposure "30"})
                                         :limits {:core "120" :maximum "190"}
                                         :legs buy-repair :fees fixtures/synthetic-fees})))
    (is (= [:repair/max-inventory-breach]
           (repair-plan/bound-errors {:account (summary {:buy-exposure "34"}) :limits {:core "120" :maximum "190"}
                                      :legs buy-repair :fees fixtures/synthetic-fees}))))
  (testing "no recorded limits: no inventory bound, balances still checked"
    (is (= [] (repair-plan/bound-errors {:account (summary {:base-total "190"}) :limits nil
                                         :legs buy-repair :fees fixtures/synthetic-fees})))
    (is (= [:repair/insufficient-usdc]
           (repair-plan/bound-errors {:account (summary {:available-usdc "500"}) :limits nil
                                      :legs buy-repair :fees fixtures/synthetic-fees}))))
  (testing "unknown account or fees cannot be judged"
    (is (= [:guard/account-unavailable]
           (repair-plan/bound-errors {:account nil :limits nil :legs buy-repair :fees fixtures/synthetic-fees})))
    (is (some #{:guard/fees-unavailable}
              (repair-plan/bound-errors {:account (summary {}) :limits nil :legs buy-repair
                                         :fees {:status :unavailable}})))))

(deftest proposal-status-test
  (let [proposal (assoc (propose (partial-grid) "76.00" "76.35") :status :ready)]
    (is (= {:status :ready :errors [] :warnings []} (repair-plan/proposal-status proposal)))
    (testing "without recorded limits only a warning is added"
      (let [p (assoc (propose (partial-grid {:settings? false}) "76.00" "76.35") :status :ready)]
        (is (nil? (:limits p)))
        (is (= {:status :ready :errors [] :warnings [:repair/limits-unrecorded]} (repair-plan/proposal-status p)))))
    (testing "a breach makes the selection invalid"
      (is (= {:status :invalid :errors [:repair/max-inventory-breach] :warnings []}
             (repair-plan/proposal-status (assoc-in proposal [:account :base-total] "151")))))
    (testing "nothing selected has nothing to check"
      (is (= :ready (:status (repair-plan/proposal-status (assoc proposal :selected #{})))))
      (is (= [] (repair-plan/selected-legs (assoc proposal :selected #{})))))
    (testing "a proposal that is not ready is never ready"
      (is (= :invalid (:status (repair-plan/proposal-status (assoc proposal :status :loading))))))))

(deftest leg-error-test
  (let [g (partial-grid)
        ctx (assoc (repair-plan/repair-context g) :market (market "76.00" "76.35"))
        check #(repair-plan/leg-error ctx (merge {:logical-id "buy-0" :side :buy :price "76.16" :size "7"} %))]
    (is (nil? (check {})))
    (is (= :repair/crosses-book (check {:price "76.4"})))
    (is (= :repair/exceeds-rejected-quantity (check {:size "7.01"})))
    (is (= :repair/not-a-candidate (check {:logical-id "buy-1"})))
    (is (= :repair/not-a-candidate (check {:side :sell})))
    (is (= :repair/duplicate-price (check {:price "74"})) "a surviving buy already rests at 74")
    (is (= :repair/illegal-price (check {:price "76.1555"})))
    (is (= :repair/not-lot-multiple (check {:size "6.999"})))
    (is (= :repair/below-min-notional (check {:size "0.13"})))
    (is (= :repair/too-far-from-mid (check {:price "38"})))
    (testing "a buy at or above a surviving sell self-crosses"
      (is (= :repair/self-cross
             (repair-plan/leg-error (assoc ctx :market (market "86.50" "86.60"))
                                    {:logical-id "buy-0" :side :buy :price "86.1" :size "7"}))))))

(defn- check-input [bid ask]
  {:scope scope :market (market bid ask) :fees (assoc fixtures/synthetic-fees :loaded-at-ms 1000)})

(defn- revalidate [grid account-overrides legs]
  (revalidation/revalidate-repair
   (merge (repair-plan/repair-context grid)
          {:grid-scope scope :input (check-input "76.00" "76.35") :account (account grid account-overrides)
           :pending {:usdc "0" :base "0"} :legs legs :now-ms 1500})))

(deftest revalidate-repair-checks-the-recorded-limits-test
  (let [g (partial-grid)]
    (testing "inside the limits the repair passes"
      (is (= {:ok? true :errors []} (select-keys (revalidate g {} buy-repair) [:ok? :errors]))))
    (testing "an external deposit since placement: 160 + 33 + 7 > 190 is refused at the final guard"
      (let [r (revalidate g {:base {:total "160" :hold "0" :available "160"}} buy-repair)]
        (is (false? (:ok? r)))
        (is (= [:repair/max-inventory-breach] (:errors r)))))
    (testing "a ladder without recorded limits keeps only the balance checks"
      (let [g (partial-grid {:settings? false})]
        (is (nil? (:limits (repair-plan/repair-context g))))
        (is (:ok? (revalidate g {:base {:total "160" :hold "0" :available "160"}} buy-repair)))))
    (testing "the existing leg checks still apply"
      (is (= [:repair/exceeds-rejected-quantity :repair/max-inventory-breach]
             (:errors (revalidate g {} [{:logical-id "buy-0" :side :buy :price "76.16" :size "8"}])))
          "8 units would also take 150 + 33 + 8 above 190")
      (is (= [:repair/crosses-book]
             (:errors (revalidate g {} [{:logical-id "buy-0" :side :buy :price "76.4" :size "7"}])))))
    (testing "repair-reservations still delegates"
      (is (= (repair-plan/repair-reservations buy-repair fixtures/synthetic-fees)
             (revalidation/repair-reservations buy-repair fixtures/synthetic-fees))))))

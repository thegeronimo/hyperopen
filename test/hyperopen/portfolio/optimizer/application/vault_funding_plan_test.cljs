(ns hyperopen.portfolio.optimizer.application.vault-funding-plan-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.application.vault-funding.plan :as plan]))

(def ^:private now 1000000)

(defn- leg
  [address weight]
  {:address address :id (str "vault:" address) :label address :weight weight})

;; The design canvas's worked example (weights sum to 1).
(def ^:private targets
  [(leg "futurai" 0.200) (leg "qmns" 0.198) (leg "pf1" 0.121) (leg "jump" 0.107)
   (leg "growi" 0.091) (leg "cold" 0.073) (leg "akka" 0.068) (leg "pulse" 0.062)
   (leg "equinox" 0.044) (leg "convexity" 0.036)])

(def ^:private holdings
  {"qmns" {:equity 3000}
   "pf1" {:equity 6500}
   "jump" {:equity 1200}
   "growi" {:equity 4100 :locked-until-ms (+ now 2)}
   "convexity" {:equity 800}})

(defn- by-address
  [result]
  (into {} (map (juxt :address identity)) (:rows result)))

(defn- approx=
  [a b]
  (< (js/Math.abs (- a b)) 0.02))

(deftest existing-deposits-count-by-default-test
  (let [result (plan/build-plan {:targets targets :holdings holdings :amount 10000
                                 :available 24180 :now-ms now})
        rows (by-address result)
        total 25600]
    (is (= total (get-in result [:totals :total])) "holdings + new cash")
    (testing "an unlocked vault above target is cut back to target"
      (is (= :withdraw (get-in rows ["pf1" :status])))
      (is (approx= (- 6500 (* 0.121 total)) (get-in rows ["pf1" :withdraw]))))
    (testing "a locked vault above target gets nothing and is reported"
      (is (= :over-locked (get-in rows ["growi" :status])))
      (is (zero? (get-in rows ["growi" :withdraw])))
      (is (zero? (get-in rows ["growi" :deposit])))
      (is (approx= (- 4100 (* 0.091 total)) (get-in rows ["growi" :over]))))
    (testing "the freed cash and the new cash close every shortfall by the same fraction"
      (let [pool (+ 10000 (get-in rows ["pf1" :withdraw]))
            futurai-gap (* 0.2 total)
            jump-gap (- (* 0.107 total) 1200)
            fraction (/ (get-in rows ["futurai" :deposit]) futurai-gap)]
        (is (< 0.85 fraction 0.9))
        (is (approx= (* fraction jump-gap) (get-in rows ["jump" :deposit])))
        (is (<= (get-in result [:totals :deposit]) pool))
        (is (< (- pool (get-in result [:totals :deposit])) 0.2) "nothing meaningful left over")))
    (testing "withdrawals are ordered before deposits"
      (is (= [:withdraw] (distinct (map :kind (take 1 (:steps result))))))
      (is (every? #{:deposit} (map :kind (rest (:steps result))))))
    (is (< (:drift-after result) (:drift-before result)))
    (is (= [] (:errors result)))))

(deftest withdraw-off-keeps-the-excess-test
  (let [rows (by-address (plan/build-plan {:targets targets :holdings holdings :amount 10000
                                           :withdraw-over? false :now-ms now}))]
    (is (= :over-kept (get-in rows ["pf1" :status])))
    (is (zero? (get-in rows ["pf1" :withdraw])))
    (is (zero? (get-in rows ["pf1" :deposit])) "still no new cash for it")))

(deftest new-cash-only-splits-by-weight-test
  (let [result (plan/build-plan {:targets targets :holdings holdings :amount 10000
                                 :include-existing? false :now-ms now})
        rows (by-address result)]
    (is (empty? (filter #(= :withdraw (:kind %)) (:steps result))) "nothing is withdrawn")
    (is (= 1210 (get-in rows ["pf1" :deposit])) "even an over-target vault gets its weight of the new cash")
    (is (= 2000 (get-in rows ["futurai" :deposit])))
    (is (approx= 10000 (get-in result [:totals :deposit])))
    (is (pos? (:drift-after result)) "drift is measured on the real holdings")))

(deftest zero-cash-rebalance-test
  (let [result (plan/build-plan {:targets targets :holdings holdings :amount 0 :now-ms now})]
    (is (pos? (get-in result [:totals :withdraw])))
    (is (< (- (get-in result [:totals :withdraw]) (get-in result [:totals :deposit])) 0.2)
        "the freed cash is redeposited (less cent rounding)")
    (is (= [] (:errors result)))))

(deftest closed-vault-share-test
  (let [base {:targets [(leg "a" 0.5) (leg "b" 0.3) (leg "closed" 0.2)]
              :holdings {} :amount 1000 :closed #{"closed"} :now-ms now}
        spread (by-address (plan/build-plan base))
        cash (plan/build-plan (assoc base :closed-mode :cash))]
    (is (= :closed (get-in spread ["closed" :status])))
    (is (zero? (get-in spread ["closed" :deposit])))
    (is (= 625 (get-in spread ["a" :deposit])) "its 20% is spread over the open vaults by weight")
    (is (= 375 (get-in spread ["b" :deposit])))
    (is (= 200 (get-in cash [:totals :leftover])) "or kept as cash")))

(deftest weights-are-rescaled-to-the-vault-sleeve-test
  (let [rows (by-address (plan/build-plan {:targets [(leg "a" 0.3) (leg "b" 0.1)] :amount 400 :now-ms now}))]
    (is (= 300 (get-in rows ["a" :deposit])))
    (is (= 100 (get-in rows ["b" :deposit])))))

(deftest errors-test
  (is (= [:over-available] (:errors (plan/build-plan {:targets targets :amount 50000 :available 24180 :now-ms now}))))
  (is (= [:no-vaults] (:errors (plan/build-plan {:targets [] :amount 10 :now-ms now}))))
  (is (= [:nothing-to-do] (:errors (plan/build-plan {:targets targets :amount 0 :now-ms now})))))

(deftest dust-is-skipped-test
  (let [rows (by-address (plan/build-plan {:targets [(leg "a" 0.999) (leg "b" 0.001)] :amount 100 :now-ms now}))]
    (is (zero? (get-in rows ["b" :deposit])) "a 10-cent deposit is not worth a request")))

(deftest usd->micros-test
  (is (= 1350690000 (plan/usd->micros 1350.69)))
  (is (= 0 (plan/usd->micros -5))))

(deftest zero-target-vault-is-withdrawn-in-full-test
  (let [rows (by-address (plan/build-plan {:targets [(leg "a" 1) (leg "gone" 0)]
                                           :holdings {"gone" {:equity 500}} :amount 0 :now-ms now}))]
    (is (= 500 (get-in rows ["gone" :withdraw])))
    (is (= 500 (get-in rows ["a" :deposit])) "and the cash moves to the vault the optimizer wants")))

(defn- one-side-per-vault?
  [result]
  (let [by-kind (group-by :kind (:steps result))]
    (empty? (filter (set (map :address (:withdraw by-kind))) (map :address (:deposit by-kind))))))

(deftest closed-vault-spread-never-round-trips-test
  (testing "A holds 100 against a 50% target only because B (50%) is closed: nothing moves"
    (let [result (plan/build-plan {:targets [(leg "a" 0.5) (leg "b" 0.5)] :holdings {"a" {:equity 100}}
                                   :amount 0 :closed #{"b"} :now-ms now})]
      (is (empty? (:steps result)))
      (is (= :on-target (get-in (by-address result) ["a" :status])))))
  (testing "a locked vault that is only over because of the closed share gets no cash and no warning"
    (let [result (plan/build-plan {:targets [(leg "closed" 0.4) (leg "b" 0.3) (leg "c" 0.3)]
                                   :holdings {"b" {:equity 100 :locked-until-ms (+ now 5)}}
                                   :amount 100 :closed #{"closed"} :now-ms now})
          rows (by-address result)]
      (is (zero? (get-in rows ["b" :deposit])))
      (is (not= :over-locked (get-in rows ["b" :status])))
      (is (= 100 (get-in rows ["c" :deposit])))))
  (testing "unlocked, same shape: still one side per vault"
    (let [result (plan/build-plan {:targets [(leg "closed" 0.4) (leg "b" 0.3) (leg "c" 0.3)]
                                   :holdings {"b" {:equity 100}} :amount 100 :closed #{"closed"} :now-ms now})]
      (is (one-side-per-vault? result))
      (is (= [{:kind :deposit :address "c" :label "c" :usd 100}] (:steps result)))))
  (testing "cash mode keeps the closed share as cash and may cut an over-target vault back"
    (let [result (plan/build-plan {:targets [(leg "closed" 0.4) (leg "b" 0.3) (leg "c" 0.3)]
                                   :holdings {"b" {:equity 100}} :amount 100 :closed #{"closed"}
                                   :closed-mode :cash :now-ms now})]
      (is (one-side-per-vault? result))
      (is (= 40 (get-in (by-address result) ["b" :withdraw])))
      (is (= 60 (get-in (by-address result) ["c" :deposit])))
      (is (= 80 (get-in result [:totals :leftover]))))))

(deftest one-side-per-vault-everywhere-test
  (doseq [mode [:spread :cash]
          withdraw? [true false]
          amount [0 500 10000]]
    (let [result (plan/build-plan {:targets targets :holdings holdings :amount amount :closed #{"akka" "pf1"}
                                   :closed-mode mode :withdraw-over? withdraw? :now-ms now})]
      (is (one-side-per-vault? result) (str mode " " withdraw? " " amount))
      (is (<= (get-in result [:totals :deposit]) (+ amount (get-in result [:totals :withdraw]) 0.001))
          "deposits never exceed new cash plus what was withdrawn")
      (is (not-any? #(and (contains? #{:over-locked :over-kept} (:status %)) (pos? (:deposit %))) (:rows result))
          "no over-target vault gets cash"))))

(deftest dust-shortfall-is-labelled-test
  (let [rows (by-address (plan/build-plan {:targets [(leg "a" 0.999) (leg "b" 0.001)] :amount 100 :now-ms now}))]
    (is (= :too-small (get-in rows ["b" :status])))))

(deftest equal-split-ignores-the-optimizer-weights-test
  (let [legs [(leg "a" 0.6) (leg "b" 0.3) (leg "c" 0.1) (leg "zero" 0)]
        result (plan/build-plan {:targets legs :split :equal :amount 900 :available 900
                                 :holdings {"zero" {:equity 50}} :now-ms now})
        rows (by-address result)]
    (is (= [316.66 316.66 316.66] (mapv #(get-in rows [% :deposit]) ["a" "b" "c"]))
        "the same share of 950 (900 new + the 50 freed) for every vault the optimizer picked")
    (is (zero? (get-in rows ["zero" :weight])) "a vault the optimizer left at 0% stays at 0%")
    (is (= 50 (get-in rows ["zero" :withdraw])) "and what is held there comes out")
    (is (= (:rows (plan/build-plan {:targets legs :amount 900 :available 900 :now-ms now}))
           (:rows (plan/build-plan {:targets legs :split :bogus :amount 900 :available 900 :now-ms now})))
        "an unknown split falls back to the optimizer target")))

(deftest each-row-says-when-its-money-unlocks-test
  (let [day (* 24 60 60 1000)
        legs [(assoc (leg "new" 0.4) :lock-days 1)
              (assoc (leg "hlp" 0.3) :lock-days 4)
              (assoc (leg "topup" 0.2) :lock-days 1)
              (assoc (leg "over" 0.05) :lock-days 1)
              (assoc (leg "stuck" 0.05) :lock-days 1)]
        holdings {"topup" {:equity 100}
                  "over" {:equity 400}
                  "stuck" {:equity 400 :locked-until-ms (+ now (* 2 day))}}
        result (plan/build-plan {:targets legs :holdings holdings :amount 1000 :available 1000 :now-ms now})
        rows (by-address result)]
    (is (= (+ now day) (get-in rows ["new" :unlock-ms])) "a new position locks for its vault's lock-up")
    (is (= (+ now (* 4 day)) (get-in rows ["hlp" :unlock-ms])) "HLP locks for 4 days")
    (is (= (+ now day) (get-in rows ["topup" :unlock-ms])) "a top-up restarts the lock on the whole position")
    (is (= :withdraw (get-in rows ["over" :status])))
    (is (nil? (get-in rows ["over" :unlock-ms])) "an unlocked position cut back to target is free now")
    (is (= (+ now (* 2 day)) (get-in rows ["stuck" :unlock-ms])) "an untouched locked position keeps its lock")
    (is (= (+ now (* 4 day)) (:free-by-ms result)) "everything is withdrawable once the last lock ends")))

(deftest leader-share-headroom-caps-a-deposit-test
  (let [legs [(leg "a" 0.5) (assoc (leg "tight" 0.5) :max-deposit 200)]
        result (plan/build-plan {:targets legs :amount 1000 :available 1000 :now-ms now})
        rows (by-address result)]
    (is (= 200 (get-in rows ["tight" :deposit])) "never more than the vault will take")
    (is (true? (get-in rows ["tight" :capped?])))
    (is (= 500 (get-in rows ["a" :deposit])))
    (is (= 300 (get-in result [:totals :leftover])) "what the capped vault cannot take stays as cash")))

(deftest refill-after-a-partial-run-test
  ;; The live case: 16 vaults equal-weighted, 14 already hold 1,250, FuturAI
  ;; got nothing, Cold Process is at its leader's 5% floor, 2,500 to add.
  (let [held (map #(str "v" %) (range 14))
        legs (concat (map #(leg % 1) held) [(leg "futurai" 1) (leg "cold" 1)])
        holdings (zipmap held (repeat {:equity 1250}))
        base {:targets legs :split :equal :holdings holdings :amount 2500 :available 2500
              :closed #{"cold"} :now-ms now}]
    (testing "only fill vaults below their target: the cash goes to FuturAI's gap, the closed share stays cash"
      (let [result (plan/build-plan (assoc base :closed-mode :cash))
            rows (by-address result)]
        (is (= 1250 (get-in rows ["futurai" :deposit])))
        (is (every? #(zero? (get-in rows [% :deposit])) held) "vaults already at target are not topped up")
        (is (= 1250 (get-in result [:totals :leftover])))))
    (testing "the default spreads the closed vault's share over the others"
      (let [rows (by-address (plan/build-plan base))]
        (is (approx= (/ 20000 15) (get-in rows ["futurai" :deposit])))
        (is (pos? (get-in rows ["v0" :deposit])))))
    (testing "a skipped vault is left alone"
      (let [rows (by-address (plan/build-plan (assoc base :closed-mode :cash :skipped #{"futurai"})))]
        (is (= :skipped (get-in rows ["futurai" :status])))
        (is (zero? (get-in rows ["futurai" :deposit])))))))

(deftest skipped-vault-is-never-withdrawn-from-test
  (let [legs [(leg "a" 0.5) (leg "big" 0.5)]
        result (plan/build-plan {:targets legs :holdings {"big" {:equity 5000}} :amount 1000
                                 :available 1000 :skipped #{"big"} :now-ms now})
        rows (by-address result)]
    (is (= :skipped (get-in rows ["big" :status])))
    (is (zero? (get-in rows ["big" :withdraw])) "over target, unlocked, and still untouched")
    (is (= 1000 (get-in rows ["a" :deposit])))))

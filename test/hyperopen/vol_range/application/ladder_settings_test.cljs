(ns hyperopen.vol-range.application.ladder-settings-test
  "Settings recorded on a ladder at placement (HO-SPEC-014 sections 6.6, 7.4,
  10.2 step 1)."
  (:require [cljs.reader :as reader]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.trading.vol-range-draft :as vol-range-draft]
            [hyperopen.vol-range.application.ladder-settings :as ladder-settings]
            [hyperopen.vol-range.application.planner :as planner]
            [hyperopen.vol-range.fixtures :as fx]))

(defn- plan [draft] (planner/prepare-plan (fx/plan-input {:draft draft})))

(defn- round-trip [x] (reader/read-string (binding [*print-length* nil *print-level* nil] (pr-str x))))

(deftest settings-for-the-13-2-fixture-test
  (let [p (plan fx/draft-13-2)
        s (ladder-settings/settings-from-plan p fx/draft-13-2)]
    (is (= :ready (:status p)) (pr-str (:errors p)))
    (is (= #{:plan-id :draft :limits :totals :inventory-outcomes} (set (keys s))))
    (is (= "plan-1" (:plan-id s)))
    (testing "limits are the plan's inventory outcomes in base units"
      (is (= {:core "120" :target (get-in p [:inventory-outcomes :target]) :maximum "190"} (:limits s))))
    (testing "the draft is normalized: every field present, enums as keywords, numbers as text"
      (is (= (vol-range-draft/normalize-vol-range-draft fx/draft-13-2) (:draft s)))
      (is (= :base (get-in s [:draft :inventory :limit-unit])))
      (is (= "40" (get-in s [:draft :buy :quantity])))
      (is (= 4 (get-in s [:draft :buy :count])))
      (is (= :custom (get-in s [:draft :anchor :mode]))))
    (is (= (:totals p) (:totals s)))
    (is (= (:inventory-outcomes p) (:inventory-outcomes s)))
    (testing "plain data: survives the journal's encoding unchanged"
      (is (= s (round-trip s))))))

(deftest limits-entered-in-usdc-are-recorded-in-base-test
  (let [draft (-> fx/draft-13-2
                  (assoc-in [:inventory :limit-unit] :quote)
                  (assoc-in [:inventory :protected-core-quote] "9600")
                  (assoc-in [:inventory :target-quote] "12800")
                  (assoc-in [:inventory :maximum-quote] "15200")
                  (assoc-in [:inventory :protected-core] "")
                  (assoc-in [:inventory :target] "")
                  (assoc-in [:inventory :maximum] ""))
        p (plan draft)
        s (ladder-settings/settings-from-plan p draft)]
    (is (= :ready (:status p)) (pr-str (:errors p)))
    (is (= ["120" "190"] [(get-in s [:limits :core]) (get-in s [:limits :maximum])]))
    (is (= "15200" (get-in s [:draft :inventory :maximum-quote])) "the draft keeps what the user entered")))

(deftest missing-inputs-test
  (testing "no frozen draft records no draft, never the defaults"
    (let [s (ladder-settings/settings-from-plan (plan fx/draft-13-2) nil)]
      (is (nil? (:draft s)))
      (is (= "190" (get-in s [:limits :maximum])))))
  (testing "no plan, no settings"
    (is (nil? (ladder-settings/settings-from-plan nil fx/draft-13-2))))
  (testing "a plan without inventory outcomes records blank limits"
    (is (= {:core nil :target nil :maximum nil}
           (:limits (ladder-settings/settings-from-plan {:plan-id "p"} fx/draft-13-2)))))
  (testing "a raw draft is normalized; unknown keys are dropped"
    (is (= (vol-range-draft/normalize-vol-range-draft {})
           (:draft (ladder-settings/settings-from-plan {:plan-id "p"} {:secret "x"}))))))

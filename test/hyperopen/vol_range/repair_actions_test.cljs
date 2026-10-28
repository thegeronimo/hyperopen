(ns hyperopen.vol-range.repair-actions-test
  "Repair action handlers (HO-SPEC-014 sections 8.6, 11.2, 13.4): exact
  effects, nothing while the gate is off, the ladder is out of scope, being
  canceled or has nothing to repair, and nothing on a second click."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.vol-range.application.ladder-settings :as ladder-settings]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.application.lifecycle-evidence :as evidence]
            [hyperopen.vol-range.application.planner :as planner]
            [hyperopen.vol-range.application.repair-plan :as repair-plan]
            [hyperopen.vol-range.application.snapshots :as snapshots]
            [hyperopen.vol-range.contracts :as contracts]
            [hyperopen.vol-range.fixtures :as fixtures]
            [hyperopen.vol-range.repair-actions :as a]
            [hyperopen.vol-range.state-fixtures :as sf]))

(def plan (planner/prepare-plan (fixtures/plan-input)))

(defn- cloid [i] (str "0x" (.padStart (str i) 32 "0")))

(defn- current-scope-key [] (contracts/scope-key (snapshots/current-scope (sf/base-state))))

(defn- partial-grid
  "The 13.4 partial deployment in the ticket's scope: the 76.4 buy rejected,
  the six other legs resting."
  ([] (partial-grid #{"buy-0"}))
  ([rejected]
   (let [legs (vec (map-indexed (fn [i l] (assoc l :cloid (cloid (inc i)))) (:legs plan)))
         g (-> (lifecycle/new-grid {:grid-id "g1" :scope {:account-address sf/owner} :scope-key (current-scope-key)
                                    :operation-id "op" :plan-id "plan-1" :legs legs
                                    :model (select-keys (:model plan) [:anchor :volatility :tilt :shifted-anchor])
                                    :settings (ladder-settings/settings-from-plan plan fixtures/draft-13-2)
                                    :expires-after-ms 31000 :now-ms 1000})
               (lifecycle/mark-sending "op" "a1" 1500))]
     (evidence/apply-placement-delivery
      g "op"
      {:delivery :response
       :leg-results (map-indexed (fn [i {:keys [leg-id] :as l}]
                                   (if (contains? rejected leg-id)
                                     {:cloid (:cloid l) :placement-state :rejected :reason "Post only"}
                                     {:cloid (:cloid l) :oid (str (+ 100 i)) :placement-state :accepted
                                      :order-state :resting}))
                                 legs)}
      2000))))

(defn- state-with
  ([grid] (state-with grid nil))
  ([grid repair]
   (-> (sf/base-state)
       (assoc-in [:vol-range :execution] {:enabled? true :grids {"g1" grid}})
       (assoc-in [:vol-range :repair] repair))))

(def proposal
  {:grid-id "g1" :status :ready :proposal-id "rp-1" :error nil :errors []
   :legs [{:logical-id "buy-0" :side :buy :rung-index 0 :rejected-price "76.4" :price "76.16" :size "7"
           :notional "533.12" :repriced? true}]
   :unrepairable []
   :selected #{"buy-0"}
   :account {:base-total "150" :available-base "150" :available-usdc "3000" :buy-exposure "33" :sell-exposure "30"}
   :limits {:core "120" :maximum "190"}
   :fees fixtures/synthetic-fees})

(def open-effects
  [[:effects/save-many [[[:vol-range :repair] {:grid-id "g1" :status :loading}]]]
   [:effects/vol-range-execute :repair-preview {:grid-id "g1"}]])

(deftest open-reads-a-fresh-proposal-for-a-repairable-ladder-test
  (let [s (state-with (partial-grid))]
    (is (= ["buy-0"] (map :logical-id (lifecycle/repair-candidates (partial-grid)))))
    (is (= open-effects (a/open-vol-ladder-repair s "g1")))
    (testing "nothing for an unknown ladder"
      (is (= [] (a/open-vol-ladder-repair s "nope"))))
    (testing "nothing while placement is off in this browser"
      (is (= [] (a/open-vol-ladder-repair (assoc-in s [:vol-range :execution :enabled?] false) "g1"))))
    (testing "nothing without definitively rejected members"
      (is (= [] (a/open-vol-ladder-repair (state-with (partial-grid #{})) "g1"))))
    (testing "nothing while the ladder is being canceled"
      (is (= [] (a/open-vol-ladder-repair (state-with (assoc (partial-grid) :cancel-requested-at-ms 3000)) "g1"))))
    (testing "nothing for a recovered ladder"
      (is (= [] (a/open-vol-ladder-repair (state-with (assoc (partial-grid) :recovered? true)) "g1"))))
    (testing "nothing while anything in the ladder is unresolved"
      (let [g (assoc-in (partial-grid) [:legs (cloid 2) :placement-state] :unknown)]
        (is (= [] (a/open-vol-ladder-repair (state-with g) "g1")))))
    (testing "nothing when the ticket is on another market or account"
      (is (= [] (a/open-vol-ladder-repair (state-with (assoc (partial-grid) :scope-key "other")) "g1"))))
    (testing "nothing while an update of this ladder is active"
      (is (= [] (a/open-vol-ladder-repair (assoc-in s [:vol-range :replace :grid-id] "g1") "g1"))))
    (testing "a second click while reading or sending emits nothing"
      (is (= [] (a/open-vol-ladder-repair (assoc-in s [:vol-range :execution :busy [:repair-preview "g1"]] true) "g1")))
      (is (= [] (a/open-vol-ladder-repair (assoc-in s [:vol-range :execution :busy [:repair "g1"]] true) "g1"))))))

(deftest refresh-re-reads-the-open-review-test
  (is (= open-effects (a/refresh-vol-ladder-repair (state-with (partial-grid) proposal))))
  (is (= [] (a/refresh-vol-ladder-repair (state-with (partial-grid)))) "no review open")
  (is (= [] (a/refresh-vol-ladder-repair
             (assoc-in (state-with (partial-grid) proposal) [:vol-range :execution :busy [:repair-preview "g1"]] true)))))

(deftest toggle-changes-only-repairable-legs-test
  (let [s (state-with (partial-grid) proposal)]
    (is (= [[:effects/save-many [[[:vol-range :repair :selected] #{}]]]] (a/toggle-vol-ladder-repair-leg s "buy-0")))
    (is (= [[:effects/save-many [[[:vol-range :repair :selected] #{"buy-0"}]]]]
           (a/toggle-vol-ladder-repair-leg (assoc-in s [:vol-range :repair :selected] #{}) "buy-0")))
    (is (= [] (a/toggle-vol-ladder-repair-leg s "sell-2")) "not a repairable leg")
    (is (= [] (a/toggle-vol-ladder-repair-leg (assoc-in s [:vol-range :repair :status] :loading) "buy-0")))
    (is (= [] (a/toggle-vol-ladder-repair-leg (state-with (partial-grid)) "buy-0")) "no review open")
    (is (= [] (a/toggle-vol-ladder-repair-leg (assoc-in s [:vol-range :execution :busy [:repair "g1"]] true) "buy-0"))
        "the selection is frozen while the repair is sent")))

(deftest close-and-keyboard-test
  (let [s (state-with (partial-grid) proposal)
        close [[:effects/save-many [[[:vol-range :repair] nil]]]]]
    (is (= close (a/close-vol-ladder-repair s)))
    (is (= close (a/handle-vol-ladder-repair-keydown s "Escape")))
    (is (= [] (a/handle-vol-ladder-repair-keydown s "Enter")) "Enter never submits")))

(deftest submit-sends-exactly-the-frozen-selection-once-test
  (let [s (state-with (partial-grid) proposal)
        send [[:effects/vol-range-execute :repair
               {:grid-id "g1" :proposal-id "rp-1" :legs [{:logical-id "buy-0" :price "76.16" :size "7"}]}]]]
    (is (= :ready (:status (repair-plan/proposal-status proposal))))
    (is (= send (a/submit-vol-ladder-repair s)))
    (testing "a second click while sending emits nothing"
      (is (= [] (a/submit-vol-ladder-repair (assoc-in s [:vol-range :execution :busy [:repair "g1"]] true)))))
    (testing "nothing selected"
      (is (= [] (a/submit-vol-ladder-repair (assoc-in s [:vol-range :repair :selected] #{})))))
    (testing "a proposal that is loading, failed or has no id"
      (is (= [] (a/submit-vol-ladder-repair (assoc-in s [:vol-range :repair :status] :loading))))
      (is (= [] (a/submit-vol-ladder-repair (assoc-in s [:vol-range :repair :status] :error))))
      (is (= [] (a/submit-vol-ladder-repair (assoc-in s [:vol-range :repair :proposal-id] nil)))))
    (testing "an invalid selection (inventory above the recorded maximum)"
      (is (= [] (a/submit-vol-ladder-repair (assoc-in s [:vol-range :repair :account :base-total] "160")))))
    (testing "placement off in this browser"
      (is (= [] (a/submit-vol-ladder-repair (assoc-in s [:vol-range :execution :enabled?] false)))))
    (is (= [] (a/submit-vol-ladder-repair (state-with (partial-grid)))) "no review open")))

(defn- close-survivors
  "Every surviving leg filled or was canceled outside the ladder (Open Orders
  tab): the ladder is :closed without a cancel request."
  [g]
  (reduce (fn [g l] (if (lifecycle/live-leg? l)
                      (assoc-in g [:legs (:cloid l)] (assoc l :order-state :terminal :terminal-reason :filled))
                      g))
          g (lifecycle/current-legs g)))

(deftest no-review-for-a-closed-or-never-placed-ladder-test
  (let [all (set (map :leg-id (:legs plan)))
        not-placed (partial-grid all)
        closed (close-survivors (partial-grid))]
    (is (= :not-placed (lifecycle/status not-placed)))
    (is (= :closed (lifecycle/status closed)))
    (doseq [g [not-placed closed]]
      (is (= [] (a/open-vol-ladder-repair (state-with g) "g1")))
      (is (= [] (a/refresh-vol-ladder-repair (state-with g proposal)))
          "a review left open when the ladder closed reads nothing"))))

(def two-leg-proposal
  (-> proposal
      (update :legs conj {:logical-id "sell-2" :side :sell :rung-index 2 :rejected-price "90.8" :price "90.8"
                          :size "10" :notional "908" :repriced? false})
      (assoc :selected #{"buy-0" "sell-2"})))

(deftest refresh-keeps-the-orders-the-user-left-out-test
  (let [s (state-with (partial-grid #{"buy-0" "sell-2"}) (assoc two-leg-proposal :selected #{"buy-0"}))]
    (is (= [[:effects/save-many [[[:vol-range :repair] {:grid-id "g1" :status :loading :excluded #{"sell-2"}}]]]
            [:effects/vol-range-execute :repair-preview {:grid-id "g1"}]]
           (a/refresh-vol-ladder-repair s)))
    (testing "a review that failed to load keeps the exclusions it carried"
      (is (= {:grid-id "g1" :status :loading :excluded #{"sell-2"}}
             (get-in (a/refresh-vol-ladder-repair
                      (state-with (partial-grid #{"buy-0" "sell-2"})
                                  {:grid-id "g1" :status :error :errors [] :excluded #{"sell-2"}}))
                     [0 1 0 1]))))
    (testing "opening the review from the dock starts with every repairable order selected"
      (is (= open-effects (a/open-vol-ladder-repair s "g1"))))))

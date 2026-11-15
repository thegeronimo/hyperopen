(ns hyperopen.portfolio.optimizer.application.progress-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.progress :as progress]))

(def ^:private sample-progress
  (progress/begin-progress
   {:run-id "run-1"
    :scenario-id "scenario-1"
    :request {:universe [{:instrument-id "perp:BTC"}
                         {:instrument-id "perp:ETH"}]
              :risk-model {:kind :diagonal-shrink}
              :return-model {:kind :historical-mean}}
    :started-at-ms 100}))

(deftest worker-progress-applies-string-step-ids-from-the-wire-test
  (let [updated (progress/worker-progress sample-progress
                                          {:step "risk-model"
                                           :status :running
                                           :percent 25
                                           :detail "estimating covariance"})
        step (first (filter #(= :risk-model (:id %)) (:steps updated)))]
    (is (= 25 (:percent step)))
    (is (= :running (:status step)))
    (is (= "estimating covariance" (:detail step)))
    (is (= :risk-model (:active-step updated)))))

(deftest worker-progress-applies-keyword-step-ids-test
  (let [updated (progress/worker-progress sample-progress
                                          {:step :frontier
                                           :status :running
                                           :percent 37.5
                                           :detail "30/80 points"})
        step (first (filter #(= :frontier (:id %)) (:steps updated)))]
    (is (= 37.5 (:percent step)))
    (is (= :running (:status step)))))

(deftest worker-progress-ignores-unknown-steps-test
  (is (= sample-progress
         (progress/worker-progress sample-progress
                                   {:step "not-a-step"
                                    :status :running
                                    :percent 10}))))

(deftest default-steps-list-frontier-sweep-before-diagnostics-test
  (is (= [:fetch-returns :risk-model :return-model :solve :frontier :diagnostics]
         (mapv :id (:steps sample-progress)))))

(deftest default-steps-covariance-only-solve-and-frontier-labels-test
  (let [steps-for (fn [kind]
                    (:steps (progress/begin-progress
                             {:run-id "run-2"
                              :scenario-id "scenario-2"
                              :request {:universe [{:instrument-id "perp:BTC"}]
                                        :risk-model {:kind :sample-covariance}
                                        :return-model {:kind :historical-mean}
                                        :objective {:kind kind}}
                              :started-at-ms 100})))
        step (fn [steps id] (first (filter #(= id (:id %)) steps)))
        inverse-steps (steps-for :inverse-volatility)
        equal-risk-steps (steps-for :equal-risk)]
    (is (= {:label "risk-weighted sizing" :detail "projection QP"}
           (select-keys (step inverse-steps :solve) [:label :detail])))
    (is (= {:label "target selection" :detail "selected point"}
           (select-keys (step inverse-steps :frontier) [:label :detail])))
    (is (= {:label "equal-risk solve" :detail "sequential QP"}
           (select-keys (step equal-risk-steps :solve) [:label :detail])))
    (is (= {:label "target selection" :detail "selected point"}
           (select-keys (step equal-risk-steps :frontier) [:label :detail])))))

(deftest smooth-display-percent-trickles-ahead-but-stays-bounded-test
  (let [step1 (progress/smooth-display-percent nil 30)]
    (is (< 30 step1))
    (is (<= step1 (+ 30 progress/display-trickle-headroom)))
    (let [parked (reduce (fn [d _] (progress/smooth-display-percent d 30))
                         step1
                         (range 200))]
      (is (<= parked (+ 30 progress/display-trickle-headroom)))
      (is (> parked (+ 30 (* 0.9 progress/display-trickle-headroom)))))))

(deftest smooth-display-percent-is-monotonic-and-snaps-up-to-real-test
  (is (<= 80 (progress/smooth-display-percent 50 80) (+ 80 progress/display-trickle-headroom)))
  (is (>= (progress/smooth-display-percent 42 10) 42))
  (is (< (progress/smooth-display-percent 98 90) 100))
  (is (< (progress/smooth-display-percent 0 95) 100)))

(deftest tick-progress-advances-running-and-leaves-settled-runs-untouched-test
  (let [running {:status :running :overall-percent 40 :started-at-ms 0}
        ticked (progress/tick-progress running 1234)]
    (is (= 1234 (:now-ms ticked)))
    (is (< 40 (:display-percent ticked)))
    (let [ticked2 (progress/tick-progress ticked 5678)]
      (is (= 5678 (:now-ms ticked2)))
      (is (>= (:display-percent ticked2) (:display-percent ticked)))))
  (doseq [settled [{:status :succeeded :overall-percent 100}
                   {:status :failed :overall-percent 63}
                   {:status :idle :overall-percent 0}]]
    (is (= settled (progress/tick-progress settled 999))
        "non-running progress is returned unchanged")))

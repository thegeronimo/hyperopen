(ns hyperopen.portfolio.optimizer.application.view-model.execution-commit-block-test
  "The Execution tab separates the *commit* gate (cannot Arm/Confirm/send — spectate,
  trader-portfolio, unavailable subaccount, or stale) from *simulation editability* (the
  strategy tiles and per-order type/param editors, which only re-project estimated costs).
  These tests pin that split at the view-model boundary so the simulation controls can never
  be re-locked off the commit flag again."
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.view-model.execution :as vm]))

(defn- commit-blocked-state
  [{:keys [disabled-reason disabled-message]}]
  {:portfolio
   {:optimizer
    {:execution {:status :idle :history []}
     :execution-modal
     {:open? true
      :phase :staged
      :plan {:status :ready
             :execution-disabled? true
             :disabled-reason disabled-reason
             :disabled-message disabled-message
             :summary {:ready-count 1}
             :rows [{:row-id "perp:BTC" :instrument-id "perp:BTC"
                     :status :ready :side :buy :delta-notional-usd 100}]}}}}})

(deftest read-only-plan-blocks-commit-but-invites-simulation-test
  (let [model (vm/execution-tab-model
               (commit-blocked-state {:disabled-reason :read-only
                                      :disabled-message "Spectate Mode is read-only."}))]
    (is (true? (:commit-blocked? model)))
    (is (= :read-only (:commit-blocked-reason model)))
    (is (true? (:arm-disabled? model)))
    (is (true? (:confirm-disabled? model)))
    (is (str/includes? (:commit-blocked-message model) "Spectate Mode is read-only.")
        "keeps the original read-only message")
    (is (str/includes? (:commit-blocked-message model) "still model execution strategies")
        "appends the simulate-anyway clarification")
    (is (not (contains? model :read-only?))
        "the flag that used to double as a simulation lock is gone")))

(deftest stale-plan-blocks-commit-without-the-simulate-note-test
  (let [stale-message "Inputs changed since this recommendation was computed — re-run the optimizer before executing."
        model (vm/execution-tab-model
               (commit-blocked-state {:disabled-reason :stale-recommendation
                                      :disabled-message stale-message}))]
    (is (true? (:commit-blocked? model)))
    (is (= :stale-recommendation (:commit-blocked-reason model)))
    (is (= stale-message (:commit-blocked-message model)))
    (is (not (str/includes? (:commit-blocked-message model) "still model execution strategies")))))

(ns hyperopen.api.projections.portfolio-vault-attribution-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.api.projections.portfolio-vault-attribution :as projections]
            [hyperopen.portfolio.vault-attribution-state :as attribution-state]))

(def ^:private address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")
(def ^:private other "0x1111111111111111111111111111111111111111")

(def ^:private path attribution-state/state-path)

(defn- base-state
  []
  {:wallet {:address address}
   :portfolio {:vault-attribution (attribution-state/default-state)}})

(def ^:private payload
  {:ledger-rows [{:time 1}]
   :details-by-vault {"0xv" {:name "V"}}
   :errors-by-vault {"0xw" "boom"}
   :loaded-at-ms 42})

(deftest begin-load-marks-loading-for-the-shown-account-test
  (let [slot (get-in (projections/begin-load (base-state) address) path)]
    (is (= :loading (:status slot)))
    (is (= address (:for-address slot)))
    (is (nil? (:error slot)))))

(deftest begin-load-keeps-rows-for-same-address-and-drops-foreign-rows-test
  (let [ready (projections/apply-success (base-state) address payload)
        refresh (get-in (projections/begin-load ready address) path)]
    (is (= :loading (:status refresh)))
    (is (= [{:time 1}] (:ledger-rows refresh)))
    (is (= {"0xv" {:name "V"}} (:details-by-vault refresh)))
    (is (= 42 (:loaded-at-ms refresh))))
  (let [foreign (assoc-in (base-state) path
                          (assoc payload :status :ready :for-address other))
        slot (get-in (projections/begin-load foreign address) path)]
    (is (= :loading (:status slot)))
    (is (= [] (:ledger-rows slot)))
    (is (= {} (:details-by-vault slot)))))

(deftest begin-load-is-dropped-when-account-changed-test
  (let [state (base-state)]
    (is (= state (projections/begin-load state other)))
    (is (= {} (projections/begin-load {} address)))))

(deftest apply-success-stores-ready-slot-test
  (let [slot (-> (base-state)
                 (projections/begin-load address)
                 (projections/apply-success address payload)
                 (get-in path))]
    (is (= {:status :ready
            :for-address address
            :ledger-rows [{:time 1}]
            :ledger-partial? false
            :live-vaults-at-load #{}
            :details-by-vault {"0xv" {:name "V"}}
            :errors-by-vault {"0xw" "boom"}
            :loaded-at-ms 42
            :stale? false
            :error nil}
           slot))))

(deftest apply-success-records-ledger-partial-test
  (is (true? (-> (base-state)
                 (projections/apply-success address (assoc payload :ledger-partial? true))
                 (get-in (conj path :ledger-partial?))))))

(deftest stale-mark-survives-a-load-in-flight-test
  (let [loading (-> (base-state)
                    (projections/apply-success address payload)
                    (attribution-state/mark-stale)
                    (projections/begin-load address))]
    (is (false? (get-in loading (conj path :stale?))) "a new load clears it")
    (is (false? (-> loading
                    (projections/apply-success address payload)
                    (get-in (conj path :stale?)))))
    (is (true? (-> loading
                   (attribution-state/mark-stale)
                   (projections/apply-success address payload)
                   (get-in (conj path :stale?))))
        "a transfer that lands mid-load leaves the result stale"))
  (is (= {} (attribution-state/mark-stale {}))))

(deftest failed-refresh-keeps-last-good-rows-test
  (let [slot (-> (base-state)
                 (projections/apply-success address payload)
                 (projections/begin-load address)
                 (projections/apply-error address (js/Error. "ledger down"))
                 (get-in path))]
    (is (= :error (:status slot)))
    (is (re-find #"ledger down" (:error slot)))
    (is (= [{:time 1}] (:ledger-rows slot)))
    (is (= {"0xv" {:name "V"}} (:details-by-vault slot)))
    (is (= 42 (:loaded-at-ms slot)))))

(deftest apply-error-stores-error-slot-test
  (let [slot (-> (base-state)
                 (projections/begin-load address)
                 (projections/apply-error address (js/Error. "ledger down"))
                 (get-in path))]
    (is (= :error (:status slot)))
    (is (= address (:for-address slot)))
    (is (re-find #"ledger down" (:error slot)))
    (is (= [] (:ledger-rows slot)) "a first-load failure holds no rows")
    (is (nil? (:loaded-at-ms slot)))))

(deftest writes-are-dropped-when-account-changed-test
  (testing "another account's data never lands over the new account's slot"
    (let [switched (-> (base-state)
                       (assoc-in [:wallet :address] other)
                       (projections/begin-load other))
          before (get-in switched path)]
      (is (= before (get-in (projections/apply-success switched address payload) path)))
      (is (= before (get-in (projections/apply-error switched address (js/Error. "x")) path)))))
  (testing "a dropped completion does not leave :loading behind for that address"
    (let [switched (-> (base-state)
                       (projections/begin-load address)
                       (assoc-in [:wallet :address] other))]
      (is (= (attribution-state/default-state)
             (get-in (projections/apply-success switched address payload) path)))
      (is (= :idle
             (:status (get-in (projections/apply-error switched address (js/Error. "x")) path)))))))

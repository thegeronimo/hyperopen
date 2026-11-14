(ns hyperopen.views.portfolio.account-tabs-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.views.account-info.tab-registry :as tab-registry]
            [hyperopen.views.portfolio.account-tabs :as account-tabs]
            [hyperopen.views.portfolio.vaults-tab-fixtures :as tf]))

(defn- options
  [state trader?]
  (account-tabs/account-info-options state {} trader?))

(deftest vaults-tab-registration-test
  (doseq [trader? [false true]]
    (let [{:keys [extra-tabs tab-order tab-click-actions-by-tab]} (options {} trader?)
          tabs (tab-registry/available-tabs-for extra-tabs tab-order {})]
      (is (some #(= :vaults (:id %)) extra-tabs) (str "trader? " trader?))
      (is (= :vaults (second tabs)) "right after Performance Metrics")
      (is (= :performance-metrics (first tabs)))
      (is (= [[:actions/set-portfolio-account-info-tab :vaults]
              [:actions/load-portfolio-vault-attribution]]
             (get tab-click-actions-by-tab :vaults))))))

(deftest vaults-tab-label-count-test
  (let [label (fn [state]
                (some #(when (= :vaults (:id %)) (:label %))
                      (:extra-tabs (options state false))))]
    (is (= "Vaults" (label {})))
    (is (= "Vaults" (label (tf/state-with {:status :loading :for-address tf/address}))))
    (is (= "Vaults (2)" (label (tf/state-with (tf/ready-slot)))))))

(deftest vaults-tab-id-normalizes-test
  (is (= :vaults (portfolio-actions/normalize-portfolio-account-info-tab "vaults")))
  (is (= [[:effects/save [:portfolio-ui :account-info-tab] :vaults]]
         (take 1 (portfolio-actions/set-portfolio-account-info-tab {} :vaults)))))

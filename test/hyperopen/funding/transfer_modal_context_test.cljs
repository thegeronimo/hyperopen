(ns hyperopen.funding.transfer-modal-context-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.funding.actions :as funding-actions]))

(def ^:private owner-address "0x999e9a397b703d68af21113abededd827b309068")
(def ^:private subaccount-address "0xbce774ef2382a4eb9376ea6f20408b318b10b63e")

(defn- base-state
  []
  {:wallet {:address "0x1234567890abcdef1234567890abcdef12345678"}
   :funding-ui {:modal (funding-actions/default-funding-modal-state)}})

(defn- selected-subaccount-state
  []
  {:wallet {:address owner-address}
   :funding-ui {:modal (funding-actions/default-funding-modal-state)}
   :account-context {:subaccounts {:selected-address subaccount-address
                                   :rows [{:sub-account-user subaccount-address
                                           :master owner-address}]}}})

(defn- saved-funding-modal
  [effects]
  (some (fn [effect]
          (when (and (vector? effect)
                     (= :effects/save (first effect))
                     (= [:funding-ui :modal] (second effect)))
            (nth effect 2)))
        effects))

(deftest open-funding-transfer-modal-applies-named-dex-context-test
  (let [saved (saved-funding-modal
               (funding-actions/open-funding-transfer-modal (base-state) nil nil
                                                            {:dex "xyz"
                                                             :to-perp? false}))]
    (is (= :transfer (:mode saved)))
    (is (= "xyz" (:transfer-dex saved)))
    (is (= false (:to-perp? saved)))))

(deftest open-funding-transfer-modal-applies-spot-row-context-test
  (let [saved (saved-funding-modal
               (funding-actions/open-funding-transfer-modal (base-state) nil nil
                                                            {:dex ""
                                                             :to-perp? true}))]
    (is (= "" (:transfer-dex saved)))
    (is (= true (:to-perp? saved)))))

(deftest open-funding-transfer-modal-without-context-keeps-defaults-test
  (let [saved (saved-funding-modal
               (funding-actions/open-funding-transfer-modal (base-state)))]
    (is (= "" (:transfer-dex saved)))
    (is (= true (:to-perp? saved)))))

(deftest open-funding-transfer-modal-master-defaults-transfer-identity-test
  (let [saved (saved-funding-modal
               (funding-actions/open-funding-transfer-modal (base-state) nil nil
                                                            {:dex "xyz"
                                                             :to-perp? false}))]
    (is (= "0x1234567890abcdef1234567890abcdef12345678"
           (:transfer-destination-address saved)))
    (is (= "" (:transfer-from-subaccount saved)))))

(deftest open-funding-transfer-modal-carries-selected-subaccount-identity-test
  (let [saved (saved-funding-modal
               (funding-actions/open-funding-transfer-modal (selected-subaccount-state) nil nil
                                                            {:dex "xyz"
                                                             :to-perp? false}))]
    (is (= subaccount-address (:transfer-from-subaccount saved)))
    (is (= subaccount-address (:transfer-destination-address saved)))
    (is (= "xyz" (:transfer-dex saved)))
    (is (= false (:to-perp? saved)))))

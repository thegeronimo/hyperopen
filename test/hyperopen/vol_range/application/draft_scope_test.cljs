(ns hyperopen.vol-range.application.draft-scope-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.trading.vol-range-draft :as draft]
            [hyperopen.vol-range.actions :as actions]
            [hyperopen.vol-range.application.draft-scope :as scope]
            [hyperopen.vol-range.fixtures :as fx]
            [hyperopen.vol-range.state-fixtures :as sf]))

(defn- switch-market [state]
  (assoc state :active-asset "@108" :active-market (sf/market "@108" 108 "OTH")))

(defn- switch-account [state]
  (assoc-in state [:wallet :address] sf/other-owner))

(defn- write [state path value]
  (sf/apply-effects state (actions/update-vol-range-draft state path value)))

(def scoped-blank (draft/blank-scoped-values (draft/default-vol-range-draft)))

(deftest bound-scope-uses-the-form-draft-test
  (let [state (sf/base-state)]
    (is (= (draft/normalize-vol-range-draft fx/draft-13-2) (scope/effective-draft state)))
    (is (= [] (scope/rebind-path-values state)) "already bound: nothing to rebind")))

(deftest market-switch-never-carries-scoped-values-test
  (let [a (sf/base-state)
        b (switch-market a)
        b-draft (scope/effective-draft b)]
    (testing "unbound scope shows blank quantities and limits but keeps preferences"
      (is (= ["" "" "" "" "" "" "" "" "" "" "" ""]
             (mapv #(get-in b-draft %) draft/scoped-draft-paths)))
      (is (= :custom (get-in b-draft [:anchor :mode])))
      (is (= :manual (get-in b-draft [:volatility :mode])))
      (is (= 4 (get-in b-draft [:buy :count])))
      (is (true? (get-in b-draft [:sell :enabled?]))))
    (testing "rebind stashes the old scope, restores the new one and binds it"
      (let [pvs (scope/rebind-path-values b)
            paths (mapv first pvs)]
        (is (= [[:vol-range :drafts] [:order-form :scale :vol-range] [:vol-range :draft-binding]] paths))
        (is (= (scope/current-scope-key b) (second (last pvs))))))
    (testing "writing in the new market stashes market A and later restores it"
      (let [b* (write b [:buy :quantity] "12")
            b-after (scope/effective-draft b*)]
        (is (= "12" (get-in b-after [:buy :quantity])))
        (is (= "" (get-in b-after [:inventory :maximum])) "A's maximum 190 did not carry over")
        (is (= "" (get-in b-after [:buy :max-usdc-debit])))
        (is (= (scope/current-scope-key b*) (scope/draft-binding b*)))
        (let [b-key (scope/current-scope-key b*)
              a-again (assoc b* :active-asset "@107" :active-market (sf/market "@107" 107 "SYN"))
              restored (scope/effective-draft a-again)]
          (is (= "40" (get-in restored [:buy :quantity])))
          (is (= "190" (get-in restored [:inventory :maximum])))
          (is (= "2900" (get-in restored [:buy :max-usdc-debit])))
          (is (= "120" (get-in restored [:inventory :protected-core])))
          (is (= "80" (get-in restored [:anchor :custom-price])))
          (is (= "30" (get-in restored [:sell :quantity])))
          (testing "B's values are stashed for B once A is written again"
            (let [a* (write a-again [:buy :quantity] "41")]
              (is (= "12" (get (get-in a* [:vol-range :drafts b-key])
                               [:buy :quantity])))
              (is (= "41" (get-in (scope/effective-draft a*) [:buy :quantity]))))))))))

(deftest account-switch-never-carries-scoped-values-test
  (let [a (sf/base-state)
        other (switch-account a)
        other-draft (scope/effective-draft other)]
    (is (not= (scope/current-scope-key a) (scope/current-scope-key other)))
    (is (= ["" "" "" "" "" "" "" "" "" "" "" ""] (mapv #(get-in other-draft %) draft/scoped-draft-paths)))
    (let [other* (write other [:inventory :maximum] "55")
          back (assoc-in other* [:wallet :address] sf/owner)]
      (is (= "55" (get-in (scope/effective-draft other*) [:inventory :maximum])))
      (is (= "190" (get-in (scope/effective-draft back) [:inventory :maximum])))
      (is (= "40" (get-in (scope/effective-draft back) [:buy :quantity]))))))

(deftest subaccount-switch-is-its-own-scope-test
  (let [a (sf/base-state)
        sub (-> a
                (assoc-in [:account-context :subaccounts :selected-address] sf/subaccount)
                (assoc-in [:account-context :subaccounts :rows]
                          [{:sub-account-user sf/subaccount :master sf/owner}]))]
    (is (not= (scope/current-scope-key a) (scope/current-scope-key sub)))
    (is (= "" (get-in (scope/effective-draft sub) [:buy :quantity])))))

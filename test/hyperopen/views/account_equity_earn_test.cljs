(ns hyperopen.views.account-equity-earn-test
  "Classic Account Equity counts HyperCore lending. Supplying moves funds out
  of the spot balance and a borrow lands in it, so the grouped total adds
  supplied value (stablecoins and collateral) minus borrowed value."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.api.projections.borrow-lend :as projections]
            [hyperopen.startup.collaborators.borrow-lend :as bootstrap]
            [hyperopen.views.account-equity-fixtures :as fixtures]
            [hyperopen.views.account-equity-view :as view]
            [hyperopen.views.account-info.derived-cache :as derived-cache]))

(def ^:private account-a "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
(def ^:private account-b "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

(def ^:private reserves
  [{:token 0 :oracle-px 1 :ltv 0}
   {:token 150 :oracle-px 40 :ltv 0.65}])

(defn- base-state []
  (-> (or (some (fn [{:keys [label state]}]
                  (when (= "classic / empty base dex, whole book on a named dex" label) state))
                (fixtures/classic))
          (throw (js/Error. "classic fixture missing")))
      (assoc :wallet {:address account-a})
      (assoc :account-equity/hyperevm-funds {:status :ready :address account-a :usd 0 :unpriced-count 0})
      (assoc :vaults {:user-equities [] :user-equities-for-address account-a
                      :loading {:user-equities? false} :errors {:user-equities nil}})))

(defn- with-lending [state address positions]
  (-> state
      (projections/apply-borrow-lend-reserves-success reserves)
      (projections/apply-borrow-lend-user-state-success address {:positions positions})))

(defn- metrics-for [state]
  (derived-cache/reset-derived-cache!)
  (view/reset-account-equity-metrics-cache!)
  (let [result (view/account-equity-metrics state)]
    (derived-cache/reset-derived-cache!)
    (view/reset-account-equity-metrics-cache!)
    result))

(deftest supplied-usdc-is-counted-in-the-total-test
  (let [without (metrics-for (base-state))
        with (metrics-for (with-lending (base-state) account-a [{:token 0 :supplied 2000 :borrowed 0}]))]
    (is (= 0 (:earn-equity without)))
    (is (= 2000 (:earn-equity with)))
    (is (= (+ (:total-account-value-display without) 2000)
           (:total-account-value-display with)))
    (testing "margin figures do not change"
      (is (= (select-keys without [:cross-margin-ratio :maintenance-margin :cross-account-leverage])
             (select-keys with [:cross-margin-ratio :maintenance-margin :cross-account-leverage]))))))

(deftest collateral-counts-and-borrows-are-netted-test
  ;; 100 HYPE at $40 plus 5,000 USDC supplied, 2,000 USDC borrowed.
  (let [m (metrics-for (with-lending (base-state) account-a
                                     [{:token 0 :supplied 5000 :borrowed 2000}
                                      {:token 150 :supplied 100 :borrowed 0}]))]
    (is (= 7000 (:earn-equity m)))))

(deftest another-accounts-lending-is-not-counted-test
  (let [without (metrics-for (base-state))
        stale (metrics-for (with-lending (base-state) account-b [{:token 0 :supplied 2000 :borrowed 0}]))]
    (is (= 0 (:earn-equity stale)))
    (is (= (:total-account-value-display without) (:total-account-value-display stale)))))

(deftest earn-row-renders-on-classic-panel-test
  (let [state (with-lending (base-state) account-a [{:token 0 :supplied 2000 :borrowed 0}])
        _ (view/reset-account-equity-metrics-cache!)
        strings (set (flatten (filter string? (tree-seq coll? seq (view/account-equity-view state)))))]
    (view/reset-account-equity-metrics-cache!)
    (is (contains? strings "Earn"))
    (is (contains? strings "$2,000.00"))))

(deftest bootstrap-clears-a-previous-accounts-positions-test
  (let [store (atom (with-lending {:wallet {:address account-b}} account-a [{:token 0 :supplied 9 :borrowed 0}]))]
    (bootstrap/fetch-borrow-lend-state!
     {:request-borrow-lend-reserve-states! (fn [_] (js/Promise. (fn [_ _])))
      :request-borrow-lend-user-state! (fn [_ _] (js/Promise. (fn [_ _])))}
     store account-b {})
    (is (nil? (get-in @store [:borrow-lend :user-state])))
    (is (= 0 (get-in @store [:borrow-lend :total-supplied-usd])))))

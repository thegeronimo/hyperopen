(ns hyperopen.views.trade-view.account-equity-slice-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.account-equity-fixtures :as account-equity-fixtures]
            [hyperopen.views.account-equity-view :as account-equity-view]
            [hyperopen.views.account-info.derived-cache :as derived-cache]
            [hyperopen.views.trade-view :as trade-view]))

(def ^:private account-a
  "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

(def ^:private account-b
  "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

(defn- classic-named-dex-state
  []
  (or (some (fn [{:keys [label state]}]
              (when (= "classic / empty base dex, whole book on a named dex" label)
                state))
            (account-equity-fixtures/classic))
      (throw (js/Error. "classic named-dex fixture missing"))))

(defn- account-equity-slice
  [state]
  (hyperopen.views.trade-view/account-equity-view-state state))

(deftest account-equity-render-slice-retains-vault-source-and-effective-account-identity-test
  (let [vault-a [{:vault-address "0xvault-a" :equity 10 :equity-raw "10"}]
        vault-a-refresh [{:vault-address "0xvault-a" :equity 25 :equity-raw "25"}]
        state-a (-> (classic-named-dex-state)
                    (assoc :wallet {:address account-a})
                    (assoc :vaults {:user-equities vault-a
                                    :user-equities-for-address account-a
                                    :loading {:user-equities? false}
                                    :errors {:user-equities nil}}))
        state-a-refreshed (assoc-in state-a [:vaults :user-equities] vault-a-refresh)
        state-b-with-a-snapshot (assoc state-a-refreshed :wallet {:address account-b})
        state-b (assoc state-b-with-a-snapshot
                       :vaults {:user-equities vault-a-refresh
                                :user-equities-for-address account-b
                                :loading {:user-equities? false}
                                :errors {:user-equities nil}})
        metrics (fn [state]
                  (account-equity-view/account-equity-metrics
                   (account-equity-slice state)))]
    (derived-cache/reset-derived-cache!)
    (account-equity-view/reset-account-equity-metrics-cache!)
    ;; The desktop panel receives a selected slice, rather than full app state.
    ;; It must still rerender when its current vault response changes and must
    ;; discard A's response immediately when B becomes effective.
    (is (= 10 (:vault-equity (metrics state-a))))
    (is (= 25 (:vault-equity (metrics state-a-refreshed))))
    (is (nil? (:vault-equity (metrics state-b-with-a-snapshot))))
    (is (= 25 (:vault-equity (metrics state-b))))
    (derived-cache/reset-derived-cache!)
    (account-equity-view/reset-account-equity-metrics-cache!)))

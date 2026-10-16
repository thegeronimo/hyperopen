(ns hyperopen.views.vaults.screener-view-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.vaults.application.list-vm.cache :as list-cache]
            [hyperopen.vaults.application.screener-vm :as screener-vm]
            [hyperopen.views.vaults.list-view :as list-view]
            [hyperopen.views.vaults.screener-view :as screener-view]))

(def day-ms
  (* 24 60 60 1000))

(def now-ms
  (.getTime (js/Date. "2026-10-16T00:00:00.000Z")))

(defn- index-row
  [address tvl age-days]
  {:vault-address address
   :name (str "Vault " address)
   :leader "0x1111111111111111111111111111111111111111"
   :tvl tvl
   :create-time-ms (- now-ms (* age-days day-ms))
   :relationship {:type :normal}
   :is-closed? false})

(def addresses
  ["0xa" "0xb" "0xc" "0xd"])

(defn- base-state
  []
  (list-cache/reset-cache!)
  {:vaults-ui {:view :screener}
   :vaults {:merged-index-rows (mapv #(index-row % 100000 400) addresses)}})

(defn- run-state
  [results]
  (-> (base-state)
      (assoc-in [:vaults :screener :run] {:id 1 :status :done :addresses addresses :total 4 :done 4
                                          :cached 1 :fetched 3
                                          :config {:min-age-days 180 :min-tvl 10000 :window :six-month}})
      (assoc-in [:vaults :screener :results] results)))

(def results
  {"0xa" {:point-count 40 :values {:sharpe 2.0 :max-drawdown -0.1}}
   "0xb" {:point-count 40 :values {:sharpe 0.97 :max-drawdown -0.1}}
   "0xc" {:point-count 40 :values {:sharpe 0.2 :max-drawdown -0.5}}
   "0xd" {:error "boom"}})

(deftest vm-builder-counts-and-estimate-test
  (let [vm (screener-vm/screener-vm (base-state) now-ms)]
    (is (= :builder (:panel vm)))
    (is (= 4 (:universe-count vm)))
    (is (= {:total 4 :cached 0 :to-fetch 4 :seconds 8}
           (select-keys (:estimate vm) [:total :cached :to-fetch :seconds])))
    (is (= "8 s" (get-in vm [:estimate :eta-label])))
    (is (= ["Sharpe ≥ 1.0" "Max drawdown ≤ 25%"] (:criteria-summary vm)))))

(deftest vm-groups-results-test
  (let [vm (screener-vm/screener-vm (run-state results) now-ms)]
    (is (= :run (:panel vm)))
    (is (= {:pass 1 :near 1 :fail 1 :thin 1} (:tab-counts vm)))
    (is (= ["0xa"] (mapv :vault-address (:result-rows vm))))
    (is (= ["2.00" "−10.0%"] (mapv :text (:cells (first (:result-rows vm))))))
    (is (false? (get-in vm [:run :stale?]))))
  (testing "the near-miss reason names the criterion and the gap"
    (let [vm (screener-vm/screener-vm (assoc-in (run-state results) [:vaults-ui :screener :results-tab] :near) now-ms)]
      (is (= "Sharpe 0.03 under the limit" (:reason (first (:result-rows vm)))))))
  (testing "changing the window marks the run stale; changing a threshold does not"
    (is (true? (get-in (screener-vm/screener-vm (assoc-in (run-state results) [:vaults-ui :screener :window] :one-year) now-ms)
                       [:run :stale?])))
    (is (false? (get-in (screener-vm/screener-vm (assoc-in (run-state results) [:vaults-ui :screener :criteria]
                                                           [{:metric :sharpe :value "0.1"}]) now-ms)
                        [:run :stale?])))))

(deftest list-view-shows-switch-and-screener-test
  (let [view (list-view/vaults-view (base-state))]
    (is (some? (hiccup/find-by-data-role view "vaults-view-switch")))
    (is (some? (hiccup/find-by-data-role view "vault-screener-builder")))
    (is (nil? (hiccup/find-by-data-role view "vaults-list-content")))
    (is (some? (hiccup/find-by-data-role view "vault-screener-run")))))

(deftest list-view-keeps-the-list-by-default-test
  (let [view (list-view/vaults-view (assoc-in (base-state) [:vaults-ui :view] :list))]
    (is (some? (hiccup/find-by-data-role view "vaults-list-content")))
    (is (nil? (hiccup/find-by-data-role view "vault-screener")))))

(deftest run-view-renders-direct-row-children-test
  (let [view (screener-view/screener-view (run-state results) {:desktop-layout? true})
        tbody (hiccup/find-first-node view #(= :tbody (first %)))
        rows (hiccup/node-children tbody)]
    (is (= 1 (count rows)))
    (is (every? #(= :tr (first %)) rows) "rows are direct children, not a nested vector")
    (is (some? (hiccup/find-by-data-role view "vault-screener-tab-near")))))

(deftest format-metric-signs-follow-shown-digits-test
  (is (= "0.0%" (screener-vm/format-metric :max-drawdown -0.0000004)))
  (is (= "−10.0%" (screener-vm/format-metric :max-drawdown -0.1)))
  (is (= "+31.6%" (screener-vm/format-metric :cagr 0.316)))
  (is (= "18.4%" (screener-vm/format-metric :volatility-ann 0.184)))
  (is (= "−1.45" (screener-vm/format-metric :sharpe -1.449)))
  (is (= "0.00" (screener-vm/format-metric :sharpe -0.001)))
  (is (= "45 d" (screener-vm/format-metric :longest-dd-days 45)))
  (is (= "—" (screener-vm/format-metric :sharpe nil))))

(deftest saved-view-filters-the-all-vaults-list-test
  (let [state (-> (base-state)
                  (assoc-in [:vaults-ui :view] :list)
                  (assoc-in [:vaults :saved-views]
                            [{:id "v1" :name "Two vaults" :membership :update :scope :pass
                              :config {:min-age-days 180 :min-tvl 10000 :window :six-month
                                       :criteria [{:metric :sharpe :value "1.0"}]}
                              :members ["0xa" "0xc"]
                              :metrics-by-address {"0xa" {:sharpe 2.5 :max-drawdown -0.12}}
                              :created-at-ms now-ms :refreshed-at-ms now-ms}])
                  (assoc-in [:vaults-ui :active-saved-view] "v1"))
        view (hiccup/with-viewport-width 1440 #(list-view/vaults-view state))
        rows (hiccup/find-all-nodes view #(= "vault-row" (get-in % [1 :data-role])))]
    (is (= 2 (count rows)) "only the view's members are listed")
    (is (some? (hiccup/find-by-data-role view "vaults-saved-view-header")))
    (is (some? (hiccup/find-by-data-role view "vaults-saved-views-row")))
    (is (= ["2.50" "—"]
           (mapv #(hiccup/node-text %)
                 (hiccup/find-all-nodes view #(= "vault-row-view-sharpe" (get-in % [1 :data-role]))))))))

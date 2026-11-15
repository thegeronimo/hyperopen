(ns hyperopen.views.portfolio.vm.vaults-scope-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [hyperopen.portfolio.vault-attribution.test-fixtures :as fx]
            [hyperopen.views.account-equity-view :as account-equity-view]
            [hyperopen.views.portfolio.vm :as vm]
            [hyperopen.views.portfolio.vm.chart-tooltip :as chart-tooltip]
            [hyperopen.views.portfolio.vm.summary :as vm-summary]
            [hyperopen.views.portfolio.vm.vaults-scope :as vaults-scope]))

(def ^:private address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")

(def ^:private day fx/day)

(def ^:private t-end fx/now)

(defn- ready-slot
  [& {:as overrides}]
  (merge {:status :ready
          :for-address address
          :ledger-rows [(fx/deposit (- t-end (* 40 day)) fx/vault-a "1000")]
          :details-by-vault {fx/vault-a {:name "HLP"
                                         :follower-state {:vault-equity 1500}}}
          :errors-by-vault {}
          :loaded-at-ms t-end
          :error nil}
         overrides))

(defn- whole-account-rows
  [scale]
  [[(- t-end (* 400 day)) (* scale 100)]
   [(- t-end (* 200 day)) (* scale 200)]
   [t-end (* scale 300)]])

(defn- state-with
  [slot & {:keys [scope range tab] :or {scope :vaults range :all-time tab :pnl}}]
  {:wallet {:address address}
   :account {:mode :classic}
   :portfolio-ui {:summary-scope scope
                  :summary-time-range range
                  :chart-tab tab}
   :portfolio {:vault-attribution slot
               :summary-by-key {:all-time {:pnlHistory (mapv (fn [[t v]] [t (- v 100)])
                                                             (whole-account-rows 1))
                                           :accountValueHistory (whole-account-rows 1)}
                                :perp-all-time {:pnlHistory (mapv (fn [[t v]] [t (- v 50)])
                                                                  (whole-account-rows 2))
                                                :accountValueHistory (whole-account-rows 2)}}}
   :webdata2 {:clearinghouseState {:marginSummary {:accountValue 0}}}
   :borrow-lend {:total-supplied-usd 0}})

(defn- portfolio-vm
  [state]
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 0
                                                               :perps-value 0
                                                               :cross-account-value 0
                                                               :unrealized-pnl 7})]
    (vm/portfolio-vm state)))

(deftest vaults-scope-key-selection-test
  (is (= :vault-day (vm-summary/selected-summary-key :vaults :day)))
  (is (= :vault-month (vm-summary/selected-summary-key :vaults :month)))
  (is (= :vault-all-time (vm-summary/all-time-summary-key :vaults)))
  (is (= :vault-all-time (vm-summary/selected-summary-key :vaults :all-time)))
  (testing "candidates never fall back to a non-vault key"
    (doseq [range [:day :week :month :three-month :one-year :all-time]]
      (let [candidates (vm-summary/summary-key-candidates :vaults range)]
        (is (seq candidates))
        (is (every? #(str/starts-with? (name %) "vault-") candidates)))))
  (testing "vault keys survive normalization"
    (is (= :vault-month (vm-summary/canonical-summary-key "vault-month")))
    (is (= :vault-month (vm-summary/canonical-summary-key :vaultMonth)))
    (is (= :vault-all-time (vm-summary/canonical-summary-key "vaultAllTime")))
    (is (= {:vault-week {:a 1}} (vm-summary/normalize-summary-by-key {"vault-week" {:a 1}})))))

(deftest vaults-scope-never-falls-back-to-other-scopes-test
  (let [by-key {:all-time {:pnlHistory [[1 0] [2 5]] :accountValueHistory [[1 10] [2 15]]}
                :perp-month {:pnlHistory [[1 0] [2 9]] :accountValueHistory [[1 10] [2 19]]}}]
    (testing "no vault entry resolves to no entry, not the first map entry"
      (let [context (vm-summary/selected-summary-context by-key :vaults :month)]
        (is (nil? (:entry context)))
        (is (= :vault-month (:requested-key context)))))
    (testing "the other scopes still fall back as before"
      (is (some? (:entry (vm-summary/selected-summary-context by-key :all :month)))))))

(deftest vaults-scope-derives-windows-from-vault-all-time-test
  (let [rows (fn [f] (mapv (fn [d] [(- t-end (* (- 700 d) day)) (f d)]) (range 0 701 10)))
        by-key {:vault-all-time {:accountValueHistory (rows #(+ 1000 %))
                                 :pnlHistory (rows identity)}}]
    (testing "3M window"
      (let [context (vm-summary/selected-summary-context by-key :vaults :three-month)
            first-ms (:time-ms (first (:accountValueHistory (:entry context))))]
        (is (= :derived (:source context)))
        (is (= :vault-all-time (:source-key context)))
        (is (<= (- t-end (* 91 day)) first-ms))
        (is (zero? (:value (first (:pnlHistory (:entry context))))))))
    (testing "1Y window"
      (let [context (vm-summary/selected-summary-context by-key :vaults :one-year)
            first-ms (:time-ms (first (:accountValueHistory (:entry context))))]
        (is (= :derived (:source context)))
        (is (<= (- t-end (* 366 day)) first-ms))))
    (testing "custom range resolves from the vault all-time series"
      (let [from (- t-end (* 200 day))
            to (- t-end (* 100 day))
            range {:from from :to to}
            context (vm-summary/selected-summary-context by-key :vaults range)]
        (is (= :derived (:source context)))
        (is (seq (:accountValueHistory (:entry context))))
        (is (every? #(<= (:time-ms %) (+ to day)) (:accountValueHistory (:entry context))))))))

(deftest vaults-scope-ready-view-model-test
  (let [view-model (portfolio-vm (state-with (ready-slot)))
        summary (:summary view-model)
        vaults (:vaults-scope summary)]
    (is (= :vaults (get-in view-model [:selectors :summary-scope :value])))
    (is (= "Vaults" (get-in view-model [:selectors :summary-scope :label])))
    (is (= [:all :perps :vaults]
           (mapv :value (get-in view-model [:selectors :summary-scope :options]))))
    (testing "lifetime totals ride along"
      (is (= :ready (:status vaults)))
      (is (= 1500 (get-in vaults [:totals :value])))
      (is (= 1 (get-in vaults [:totals :open-count]))))
    (testing "chart is the vault series, labelled for it"
      (is (= "Vaults" (get-in view-model [:chart :series 0 :label])))
      (is (= "Vault Value"
             (some #(when (= :account-value (:value %)) (:label %))
                   (get-in view-model [:chart :tabs]))))
      (is (seq (get-in view-model [:chart :points]))))
    (is (= "Vaults" (get-in view-model [:performance-metrics :portfolio-label])))
    (is (= 1500 (get-in view-model [:monte-carlo :start-equity])))
    (is (= :vault-all-time (:selected-key summary)))))

(deftest vaults-scope-does-not-use-whole-account-pnl-test
  (let [view-model (portfolio-vm (state-with (ready-slot)))]
    (is (not= 7 (get-in view-model [:summary :vaults-scope :pnl])))))

(deftest vaults-scope-loading-state-shows-no-data-test
  (doseq [slot [{:status :idle}
                {:status :loading :for-address address}
                {:status :ready :for-address "0x2222222222222222222222222222222222222222"}]]
    (let [view-model (portfolio-vm (state-with slot))
          vaults (get-in view-model [:summary :vaults-scope])]
      (is (= :loading (:status vaults)) (pr-str slot))
      (is (nil? (:pnl vaults)))
      (is (nil? (:totals vaults)))
      (is (empty? (get-in view-model [:chart :points]))
          "no other scope's series is drawn")
      (is (false? (get-in view-model [:chart :has-data?]))))))

(deftest vaults-scope-error-empty-and-partial-states-test
  (testing "error"
    (let [vaults (get-in (portfolio-vm (state-with {:status :error :for-address address :error "boom"}))
                         [:summary :vaults-scope])]
      (is (= :error (:status vaults)))
      (is (nil? (:totals vaults)))))
  (testing "ready with no deposits"
    (let [view-model (portfolio-vm (state-with (ready-slot :ledger-rows [] :details-by-vault {})))
          vaults (get-in view-model [:summary :vaults-scope])]
      (is (= :empty (:status vaults)))
      (is (empty? (get-in view-model [:chart :points])))))
  (testing "partial"
    (let [vaults (get-in (portfolio-vm (state-with (ready-slot :errors-by-vault {fx/vault-b "x"})))
                         [:summary :vaults-scope])]
      (is (= :ready (:status vaults)))
      (is (true? (:partial? vaults))))))

(deftest vaults-scope-estimate-note-test
  (let [entry {:accountValueHistory [[1000000 5] [9000000 6]] :pnlHistory [[1000000 0] [9000000 1]]}
        note (fn [daily-from-ms]
               (vaults-scope/estimate-note {:attribution {:daily-from-ms daily-from-ms}} entry))]
    (testing "shown when the estimate boundary is later than the first visible point"
      (let [text (note 5000000)]
        (is (str/starts-with? text "Estimated before "))
        (is (str/ends-with? text " from vault history sampled about every 12 days"))))
    (testing "omitted when nil or not later than the first point"
      (is (nil? (note nil)))
      (is (nil? (note 1000000)))
      (is (nil? (note 500000))))
    (testing "omitted when there is no visible series"
      (is (nil? (vaults-scope/estimate-note {:attribution {:daily-from-ms 5000000}} nil)))))
  (testing "the view model carries it for a ready account whose history is coarse"
    (let [view-model (portfolio-vm (state-with (ready-slot)))]
      (is (= (some? (get-in view-model [:chart :estimate-note]))
             (get-in view-model [:summary :vaults-scope :estimated?]))))))

(deftest vaults-scope-tooltip-names-vault-value-test
  (let [tooltip (fn [scope tab]
                  (chart-tooltip/build-chart-hover-tooltip
                   :month tab {:point {:time-ms 1000000 :value 12} :index 0} [] scope))]
    (is (= "Vault Value" (:metric-label (tooltip :vaults :account-value))))
    (is (= "PNL" (:metric-label (tooltip :vaults :pnl))))
    (is (= "Account Value" (:metric-label (tooltip :all :account-value))))
    (is (= "Account Value"
           (:metric-label (chart-tooltip/build-chart-hover-tooltip
                           :month :account-value {:point {:time-ms 1000000 :value 12} :index 0} []))))))

(deftest vaults-scope-exited-vaults-have-no-window-fallback-test
  (let [slot (ready-slot
              :ledger-rows [(fx/deposit (- t-end (* 100 day)) fx/vault-a "1000")
                            (fx/withdraw (- t-end (* 60 day)) fx/vault-a
                                         {:requested "1100" :commission "0" :basis "1000" :net "1100"})]
              :details-by-vault {fx/vault-a {:name "HLP" :follower-state {:vault-equity 0}}})
        all-time (portfolio-vm (state-with slot :range :all-time))
        month (portfolio-vm (state-with slot :range :month))]
    (is (seq (get-in all-time [:chart :points])))
    (is (empty? (get-in month [:chart :points])))
    (is (nil? (get-in month [:summary :vaults-scope :pnl])))
    (is (nil? (get-in month [:summary :vaults-scope :max-drawdown-pct])))
    (is (not= :fallback (:source (:summary month))))
    (testing "the returns tab does not borrow the all-time series either"
      (is (empty? (get-in (portfolio-vm (state-with slot :range :month :tab :returns))
                          [:chart :points]))))))

(deftest vaults-scope-withholds-stale-performance-metrics-test
  (let [stale {:portfolio-values {:cagr 0.5 :sharpe 2}}
        loading (-> (state-with {:status :loading :for-address address})
                    (assoc-in [:portfolio-ui :metrics-result] stale))
        ready (-> (state-with (ready-slot))
                  (assoc-in [:portfolio-ui :metrics-result] stale))]
    (let [perf (:performance-metrics (portfolio-vm loading))]
      (is (= [] (:groups perf)))
      (is (= {} (:values perf)))
      (is (= "Vaults" (:portfolio-label perf))))
    (is (= "Vaults" (:portfolio-label (:performance-metrics (portfolio-vm ready)))))))

(deftest vaults-scope-chart-follows-the-attribution-data-test
  (let [points (fn [slot]
                 (get-in (portfolio-vm (state-with slot :tab :account-value)) [:chart :points]))
        base (points (ready-slot))
        more-equity (points (ready-slot :details-by-vault
                                        {fx/vault-a {:name "HLP"
                                                     :follower-state {:vault-equity 2500}}}
                                        :loaded-at-ms (inc t-end)))]
    (is (seq base))
    (is (not= base more-equity) "chart memo keys move when the vault entries change")))

(deftest other-scopes-ignore-vault-data-test
  (doseq [scope [:all :perps]]
    (let [with-vaults (portfolio-vm (state-with (ready-slot) :scope scope))
          without-vaults (portfolio-vm (state-with {:status :idle} :scope scope))]
      (is (nil? (get-in with-vaults [:summary :vaults-scope])))
      (is (nil? (get-in with-vaults [:chart :estimate-note])))
      (is (= (:summary without-vaults) (:summary with-vaults)))
      (is (= (:chart without-vaults) (:chart with-vaults)))
      (is (= "Portfolio" (get-in with-vaults [:chart :series 0 :label])))
      (is (nil? (get-in with-vaults [:performance-metrics :portfolio-label])))
      (is (= "Account Value"
             (some #(when (= :account-value (:value %)) (:label %))
                   (get-in with-vaults [:chart :tabs])))))))

(deftest vaults-scope-max-drawdown-is-return-based-test
  (testing "a value renders when the sleeve has data"
    (let [mdd (get-in (portfolio-vm (state-with (ready-slot)))
                      [:summary :vaults-scope :max-drawdown-pct])]
      (is (number? mdd))
      (is (<= 0 mdd 1))))
  (testing "a small early peak then a large top-up never reads as > 100%"
    (let [slot (ready-slot
                :ledger-rows [(fx/deposit (- t-end (* 90 day)) fx/vault-a "100")
                              (fx/withdraw (- t-end (* 60 day)) fx/vault-a
                                           {:requested "60" :commission "0" :basis "50" :net "60"})
                              (fx/withdraw (- t-end (* 55 day)) fx/vault-a
                                           {:requested "10" :commission "0" :basis "25" :net "10"})
                              (fx/deposit (- t-end (* 50 day)) fx/vault-a "10000")]
                :details-by-vault {fx/vault-a {:name "HLP" :follower-state {:vault-equity 9500}}})
          vm-model (portfolio-vm (state-with slot :range :all-time))
          mdd (get-in vm-model [:summary :vaults-scope :max-drawdown-pct])]
      (is (> (vm-summary/max-drawdown-ratio
              (get-in (vaults-scope/summary-by-key (state-with slot) :vaults)
                      [:vault-all-time]))
             1)
          "the P&L-based ratio really does exceed 100% here")
      (is (number? mdd))
      (is (< 0 mdd 1)))))

(deftest return-index-drawdown-ratio-test
  (is (nil? (vm-summary/return-index-drawdown-ratio [])))
  (is (= 0 (vm-summary/return-index-drawdown-ratio [[1 0] [2 10]])))
  (is (fx/near? 0.5 (vm-summary/return-index-drawdown-ratio [[1 0] [2 100] [3 0]])))
  (is (= 1 (vm-summary/return-index-drawdown-ratio [[1 0] [2 -100] [3 -150]]))))

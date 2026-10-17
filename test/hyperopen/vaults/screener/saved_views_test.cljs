(ns hyperopen.vaults.screener.saved-views-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.vaults.screener.model :as model]
            [hyperopen.vaults.application.saved-views-vm :as saved-views-vm]
            [hyperopen.vaults.screener.saved-views :as saved-views]))

(def run
  {:config (model/normalize-config {:min-age-days 180 :min-tvl 100000 :window :all-time})
   :addresses ["0xa" "0xb" "0xc" "0xd"]})

(def results
  {"0xa" {:point-count 40 :values {:sharpe 2.0 :max-drawdown -0.1 :sortino 3}}
   "0xb" {:point-count 40 :values {:sharpe 0.95 :max-drawdown -0.1}}
   "0xc" {:point-count 40 :values {:sharpe 0.2 :max-drawdown -0.5}}
   "0xd" {:error "boom"}})

(def ui-config
  (model/normalize-config {:window :one-year
                           :criteria [{:metric :sharpe :value "1.0"}
                                      {:metric :max-drawdown :value "25"}]}))

(deftest suggested-name-reads-the-criteria-test
  (is (= "Sharpe ≥ 1.0, Max drawdown ≤ 25%" (saved-views/suggested-name ui-config)))
  (is (= "Saved vaults" (saved-views/suggested-name {:criteria []}))))

(deftest view-config-keeps-run-universe-and-live-thresholds-test
  (let [config (saved-views/view-config (:config run) (assoc ui-config :criteria [{:metric :sharpe :value "2"}]))]
    (is (= :all-time (:window config)) "the window the results were computed with")
    (is (= 100000 (:min-tvl config)))
    (is (= [{:metric :sharpe :value "2"}] (:criteria config)) "thresholds edited after the run")))

(deftest build-view-takes-members-by-scope-test
  (let [base {:id "view-1" :name " My view " :run run :results results :ui-config ui-config :now-ms 1000}
        pass-view (saved-views/build-view (assoc base :scope :pass))
        near-view (saved-views/build-view (assoc base :scope "pass-and-near" :membership "fixed"))]
    (is (= "My view" (:name pass-view)))
    (is (= ["0xa"] (:members pass-view)))
    (is (= {"0xa" {:sharpe 2.0 :max-drawdown -0.1 :sortino 3}} (:metrics-by-address pass-view)))
    (is (= :update (:membership pass-view)))
    (is (= ["0xa" "0xb"] (:members near-view)))
    (is (= :fixed (:membership near-view)))
    (is (= "Sharpe ≥ 1.0, Max drawdown ≤ 25%"
           (:name (saved-views/build-view (assoc base :name "  "))))
        "a blank name falls back to the suggestion")))

(deftest refresh-view-updates-or-keeps-members-test
  (let [view (saved-views/build-view {:id "v" :run run :results results :ui-config ui-config
                                      :scope :pass :now-ms 1000})
        next-results (assoc results
                            "0xa" {:point-count 40 :values {:sharpe 0.1 :max-drawdown -0.6}}
                            "0xc" {:point-count 40 :values {:sharpe 1.5 :max-drawdown -0.2}})]
    (testing "update membership replaces the members and reports the diff"
      (let [refreshed (saved-views/refresh-view view run next-results 2000)]
        (is (= ["0xc"] (:members refreshed)))
        (is (= {:joined ["0xc"] :left ["0xa"]} (:last-diff refreshed)))
        (is (= 2000 (:refreshed-at-ms refreshed)))
        (is (= "1 vault joined, 1 left" (saved-views/diff-summary (:last-diff refreshed))))))
    (testing "fixed membership keeps the list and refreshes its numbers"
      (let [refreshed (saved-views/refresh-view (assoc view :membership :fixed)
                                                {:addresses ["0xa"]} next-results 2000)]
        (is (= ["0xa"] (:members refreshed)))
        (is (= 0.1 (get-in refreshed [:metrics-by-address "0xa" :sharpe])))
        (is (= "no change in members" (saved-views/diff-summary (:last-diff refreshed))))))))

(deftest refresh-keeps-a-member-whose-fetch-failed-test
  (let [view (saved-views/build-view {:id "v" :run run :results (dissoc results "0xd")
                                      :ui-config ui-config :scope :pass :now-ms 1000})
        failed (assoc results "0xa" {:error "rate limited"})
        refreshed (saved-views/refresh-view view run failed 2000)]
    (is (= ["0xa"] (:members view)))
    (is (= ["0xa"] (:members refreshed)) "a failed request is not evidence the vault no longer qualifies")
    (is (= 2.0 (get-in refreshed [:metrics-by-address "0xa" :sharpe])) "keeps its last numbers")
    (is (= ["0xa" "0xd"] (:unfetched refreshed)))
    (is (= {:joined [] :left []} (:last-diff refreshed)))
    (is (= "no change in members; 2 vaults couldn't be loaded (members kept)"
           (saved-views/diff-summary (:last-diff refreshed) (:unfetched refreshed))))
    (is (= ["0xa" "0xd"] (:unfetched (first (saved-views/normalize-views [refreshed]))))
        "survives storage")))

(deftest normalize-views-reads-back-stored-records-test
  (testing "IndexedDB returns keywordized address keys and string tokens"
    (let [[view] (saved-views/normalize-views
                  [{:id "view-1" :name "N" :scope "pass-and-near" :membership "fixed"
                    :config {:window "all-time" :criteria [{:metric "sharpe" :value "1"}]}
                    :members ["0xA"]
                    :metrics-by-address {:0xa {:sharpe 2 :bogus 1}}
                    :created-at-ms 5 :refreshed-at-ms 6}
                   {:id "view-1" :name "duplicate"}
                   {:name "no id"}])]
      (is (= :pass-and-near (:scope view)))
      (is (= :fixed (:membership view)))
      (is (= :all-time (get-in view [:config :window])))
      (is (= [{:metric :sharpe :value "1"}] (get-in view [:config :criteria])))
      (is (= ["0xa"] (:members view)))
      (is (= {"0xa" {:sharpe 2}} (:metrics-by-address view)))))
  (is (= 1 (count (saved-views/normalize-views [{:id "a"} {:id "a"}])))))

(deftest upsert-and-remove-test
  (let [views (saved-views/upsert-view [{:id "a" :name "A"}] {:id "b" :name "B"})]
    (is (= ["A" "B"] (mapv :name views)))
    (is (= ["A2" "B"] (mapv :name (saved-views/upsert-view views {:id "a" :name "A2"}))))
    (is (= ["B"] (mapv :name (saved-views/remove-view views "a"))))))

(deftest view-header-reports-unfetched-members-test
  (let [day (* 24 60 60 1000)
        now (* 30 day)
        view (assoc (saved-views/build-view {:id "v" :run run :results (dissoc results "0xd")
                                             :ui-config ui-config :scope :pass :now-ms (- now (* 2 day))})
                    :unfetched ["0xd"])
        state {:vaults {:saved-views [view]}
               :vaults-ui {:active-saved-view "v"}}
        text (get-in (saved-views-vm/saved-views-vm state now) [:active :status :text])]
    (is (re-find #"1 vault couldn't be loaded last time \(members kept\) — Refresh to retry" text))))

(deftest view-header-shows-the-history-rule-test
  (let [view (assoc-in (saved-views/build-view {:id "v" :run run :results results
                                                :ui-config ui-config :scope :pass :now-ms 1000})
                       [:config :min-history-points] 20)
        state {:vaults {:saved-views [view]} :vaults-ui {:active-saved-view "v"}}
        active (:active (saved-views-vm/saved-views-vm state 2000))]
    (is (some #(= "History ≥ 20 points" (:text %)) (:chips active)))
    (is (= 20 (:min-history-points active))))
  (let [view (assoc-in (saved-views/build-view {:id "v" :run run :results results
                                                :ui-config ui-config :scope :pass :now-ms 1000})
                       [:config :min-history-points] 0)
        state {:vaults {:saved-views [view]} :vaults-ui {:active-saved-view "v"}}]
    (is (not-any? #(re-find #"History" (:text %))
                  (:chips (:active (saved-views-vm/saved-views-vm state 2000)))))))

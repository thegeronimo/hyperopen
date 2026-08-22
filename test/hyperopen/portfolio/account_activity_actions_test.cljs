(ns hyperopen.portfolio.account-activity-actions-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.account-activity-actions :as actions]))

(defn- saved-entries
  "The [path value] pairs an action's :effects/save-many carries."
  [effects]
  (into {}
        (mapcat (fn [[effect-id entries]]
                  (when (= :effects/save-many effect-id)
                    entries)))
        effects))

(deftest defaults-start-on-all-newest-first-page-one-test
  (let [defaults (actions/default-account-activity-state)]
    (is (= :all (:sub-tab defaults)))
    (is (= {:column "Time" :direction :desc} (:sort defaults)))
    (is (= 1 (:page defaults)))
    (is (= 50 (:page-size defaults)))))

(deftest selecting-a-sub-tab-normalizes-it-and-returns-to-page-one-test
  (let [entries (saved-entries (actions/set-portfolio-account-activity-sub-tab
                                {:portfolio-ui {:account-activity {:page 3}}}
                                "Deposits and Withdrawals"))]
    (is (= :deposits-withdrawals (get entries actions/sub-tab-path)))
    (testing "the page resets, which the reference client fails to do"
      ;; Without this, moving from a three-page sub-tab to a one-page one while
      ;; on page 3 leaves an empty table under a hidden pagination footer.
      (is (= 1 (get entries actions/page-path)))
      (is (= "1" (get entries actions/page-input-path)))))
  (testing "an unknown sub-tab falls back to All rather than blanking the table"
    (is (= :all (get (saved-entries (actions/set-portfolio-account-activity-sub-tab {} "nope"))
                     actions/sub-tab-path)))))

(deftest sorting-toggles-direction-and-picks-a-sensible-first-direction-test
  (let [state-with (fn [sort] {:portfolio-ui {:account-activity {:sort sort}}})]
    (testing "re-clicking the active column flips direction"
      (is (= {:column "Time" :direction :asc}
             (get (saved-entries (actions/sort-portfolio-account-activity
                                  (state-with {:column "Time" :direction :desc})
                                  "Time"))
                  actions/sort-path))))
    (testing "numeric and time columns start descending"
      (doseq [column ["Time" "Account Change" "USD Value" "Fee"]]
        (is (= :desc
               (:direction (get (saved-entries (actions/sort-portfolio-account-activity
                                                (state-with {:column "Status" :direction :asc})
                                                column))
                                actions/sort-path)))
            (str column " should start descending"))))
    (testing "textual columns start ascending"
      (doseq [column ["Status" "Asset" "Action" "From" "To" "Destination"]]
        (is (= :asc
               (:direction (get (saved-entries (actions/sort-portfolio-account-activity
                                                (state-with {:column "Time" :direction :desc})
                                                column))
                                actions/sort-path)))
            (str column " should start ascending"))))
    (testing "sorting also returns to page 1"
      (is (= 1 (get (saved-entries (actions/sort-portfolio-account-activity
                                    (state-with {:column "Time" :direction :desc})
                                    "Asset"))
                    actions/page-path))))))

(deftest pagination-actions-clamp-to-the-available-pages-test
  (let [state (fn [page] {:portfolio-ui {:account-activity {:page page}}})]
    (testing "next stops at the last page"
      (is (= 3 (get (saved-entries (actions/next-portfolio-account-activity-page (state 3) 3))
                    actions/page-path))))
    (testing "prev stops at the first page"
      (is (= 1 (get (saved-entries (actions/prev-portfolio-account-activity-page (state 1) 3))
                    actions/page-path))))
    (testing "next advances in the middle"
      (is (= 2 (get (saved-entries (actions/next-portfolio-account-activity-page (state 1) 3))
                    actions/page-path))))
    (testing "the page input follows the resolved page"
      (is (= "2" (get (saved-entries (actions/next-portfolio-account-activity-page (state 1) 3))
                      actions/page-input-path))))))

(deftest page-size-changes-reset-to-page-one-test
  (let [entries (saved-entries (actions/set-portfolio-account-activity-page-size
                                {:portfolio-ui {:account-activity {:page 4}}}
                                "25"))]
    (is (= 25 (get entries actions/page-size-path)))
    (is (= 1 (get entries actions/page-path))))
  (testing "an unsupported page size falls back to the default"
    (is (= 50 (get (saved-entries (actions/set-portfolio-account-activity-page-size {} "9999"))
                   actions/page-size-path)))))

(deftest jump-to-page-applies-only-on-enter-test
  (let [state {:portfolio-ui {:account-activity {:page 1 :page-input "3"}}}]
    (is (= 3 (get (saved-entries (actions/apply-portfolio-account-activity-page-input state 5))
                  actions/page-path)))
    (is (= 3 (get (saved-entries (actions/handle-portfolio-account-activity-page-input-keydown
                                  state "Enter" 5))
                  actions/page-path)))
    (is (= [] (actions/handle-portfolio-account-activity-page-input-keydown state "a" 5))
        "a non-Enter keystroke emits nothing")
    (testing "a jump past the end clamps rather than blanking the table"
      (is (= 5 (get (saved-entries (actions/apply-portfolio-account-activity-page-input
                                    {:portfolio-ui {:account-activity {:page-input "99"}}}
                                    5))
                    actions/page-path))))))

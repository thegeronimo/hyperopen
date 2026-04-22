(ns hyperopen.account.history.twap-actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.account.history.twap-actions :as twap-actions]))

(deftest twap-default-state-and-subtab-normalization-cover-supported-inputs-test
  (is (= {:selected-subtab :active}
         (twap-actions/default-twap-state)))
  (is (= :active (twap-actions/normalize-twap-subtab :active)))
  (is (= :history (twap-actions/normalize-twap-subtab "history")))
  (is (= :fill-history (twap-actions/normalize-twap-subtab "fill-history")))
  (is (= :fill-history (twap-actions/normalize-twap-subtab :fill-history))))

(deftest twap-subtab-normalization-falls-back-to-active-test
  (is (= :active (twap-actions/normalize-twap-subtab "fills")))
  (is (= :active (twap-actions/normalize-twap-subtab nil)))
  (is (= :active (twap-actions/normalize-twap-subtab 42))))

(deftest select-account-info-twap-subtab-saves-normalized-subtab-test
  (is (= [[:effects/save [:account-info :twap :selected-subtab] :history]]
         (twap-actions/select-account-info-twap-subtab {} "history")))
  (is (= [[:effects/save [:account-info :twap :selected-subtab] :active]]
         (twap-actions/select-account-info-twap-subtab {} :unknown))))

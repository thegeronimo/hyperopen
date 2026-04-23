(ns hyperopen.account.history.position-reduce-metadata-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.account.history.actions :as history-actions]
            [hyperopen.account.history.position-reduce :as position-reduce]
            [hyperopen.views.account-info.test-support.fixtures :as fixtures]))

(def ^:private full-market-load-effect
  [:effects/fetch-asset-selector-markets {:phase :full}])

(deftest position-reduce-popover-loads-full-markets-for-named-dex-positions-test
  (let [row (fixtures/sample-position-row "xyz:BRENTOIL" 20 "1.31" "xyz")]
    (is (some #{full-market-load-effect}
              (history-actions/open-position-reduce-popover
               {:asset-selector {:phase :bootstrap}}
               row)))
    (is (some #{full-market-load-effect}
              (history-actions/open-position-reduce-popover
               {:asset-selector {:phase :bootstrap}}
               row
               {:left 10 :right 20 :top 30 :bottom 40})))
    (is (not (some #{full-market-load-effect}
                   (history-actions/open-position-reduce-popover
                    {:asset-selector {:phase :full}}
                    row))))))

(deftest submit-position-reduce-close-fetches-full-markets-when-asset-id-missing-test
  (let [row (fixtures/sample-position-row "xyz:BRENTOIL" 20 "1.31" "xyz")
        popover (-> (position-reduce/from-position-row row)
                    (assoc :close-type :limit
                           :limit-price "100"))
        effects (history-actions/submit-position-reduce-close
                 {:asset-selector {:phase :bootstrap
                                   :market-by-key {}}
                  :positions-ui {:reduce-popover popover}})]
    (is (= :effects/save
           (ffirst effects)))
    (is (= "Select an asset and ensure market data is loaded."
           (get-in (first effects) [2 :error])))
    (is (= full-market-load-effect
           (second effects)))))

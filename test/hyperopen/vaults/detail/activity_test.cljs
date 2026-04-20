(ns hyperopen.vaults.detail.activity-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.vaults.detail.activity :as activity]))

(deftest normalize-sort-column-supports-legacy-labels-and-stable-ids-test
  (is (= :size
         (activity/normalize-sort-column :positions "Size")))
  (is (= :size
         (activity/normalize-sort-column :positions :size)))
  (is (nil? (activity/normalize-sort-column :positions "Unknown"))))

(deftest sort-state-normalizes-legacy-string-column-values-test
  (let [state {:vaults-ui {:detail-activity-sort-by-tab {:positions {:column "Coin"
                                                                      :direction :asc}}}}
        sort-state (activity/sort-state state :positions)]
    (is (= :coin (:column sort-state)))
    (is (= :asc (:direction sort-state)))))

(deftest project-rows-applies-direction-filter-and-sort-order-test
  (let [rows [{:coin "ETH" :size -2 :position-value 20}
              {:coin "BTC" :size 1 :position-value 10}
              {:coin "SOL" :size 3 :position-value 30}]
        sort-state {:column :coin
                    :direction :asc}
        projected (activity/project-rows rows :positions :long sort-state)]
    (is (= ["BTC" "SOL"]
           (mapv :coin projected)))))

(deftest project-rows-filters-trade-history-open-close-labels-by-position-direction-test
  (let [rows [{:coin "OPEN-LONG" :side "Open Long" :side-key :long :direction-key :long :time-ms 1}
              {:coin "CLOSE-SHORT" :side "Close Short" :side-key :long :direction-key :short :time-ms 2}
              {:coin "OPEN-SHORT" :side "Open Short" :side-key :short :direction-key :short :time-ms 3}
              {:coin "CLOSE-LONG" :side "Close Long" :side-key :short :direction-key :long :time-ms 4}]
        sort-state {:column :time
                    :direction :asc}
        long-projected (activity/project-rows rows :trade-history :long sort-state)
        short-projected (activity/project-rows rows :trade-history :short sort-state)]
    (is (= ["OPEN-LONG" "CLOSE-LONG"]
           (mapv :coin long-projected)))
    (is (= ["CLOSE-SHORT" "OPEN-SHORT"]
           (mapv :coin short-projected)))))

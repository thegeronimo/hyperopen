(ns hyperopen.views.leaderboard.vm-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [hyperopen.views.leaderboard.vm :as vm]))

(defn- reset-leaderboard-vm-cache-fixture
  [f]
  (vm/reset-leaderboard-vm-cache!)
  (f)
  (vm/reset-leaderboard-vm-cache!))

(use-fixtures :each reset-leaderboard-vm-cache-fixture)

(def sample-rows
  [{:eth-address "0x1111111111111111111111111111111111111111"
    :account-value 1000
    :display-name "Alpha"
    :window-performances {:day {:pnl 10 :roi 0.01 :volume 100}
                          :week {:pnl 20 :roi 0.02 :volume 200}
                          :month {:pnl 30 :roi 0.03 :volume 300}
                          :all-time {:pnl 40 :roi 0.04 :volume 400}}}
   {:eth-address "0x2222222222222222222222222222222222222222"
    :account-value 2000
    :display-name "Bravo"
    :window-performances {:day {:pnl 5 :roi 0.005 :volume 90}
                          :week {:pnl 15 :roi 0.015 :volume 190}
                          :month {:pnl 25 :roi 0.025 :volume 290}
                          :all-time {:pnl 35 :roi 0.035 :volume 390}}}
   {:eth-address "0x3333333333333333333333333333333333333333"
    :account-value 3000
    :display-name "Charlie"
    :window-performances {:day {:pnl 7 :roi 0.007 :volume 80}
                          :week {:pnl 17 :roi 0.017 :volume 180}
                          :month {:pnl 27 :roi 0.027 :volume 280}
                          :all-time {:pnl 37 :roi 0.037 :volume 380}}}])

(defn- with-viewport-width
  [width f]
  (let [original-inner-width (.-innerWidth js/globalThis)]
    (set! (.-innerWidth js/globalThis) width)
    (try
      (f)
      (finally
        (set! (.-innerWidth js/globalThis) original-inner-width)))))

(deftest leaderboard-vm-filters-excluded-addresses-and-pins-current-user-test
  (with-viewport-width
    430
    (fn []
      (let [state {:wallet {:address "0x2222222222222222222222222222222222222222"}
                   :leaderboard-ui {:query ""
                                    :timeframe :month
                                    :sort {:column :pnl
                                           :direction :desc}
                                    :page 1}
                   :leaderboard {:rows sample-rows
                                 :excluded-addresses #{"0x3333333333333333333333333333333333333333"}
                                 :loading? false
                                 :error nil
                                 :loaded-at-ms 1700000000000}}
            result (vm/leaderboard-vm state)]
        (is (false? (:desktop-layout? result)))
        (is (= "Month" (:timeframe-label result)))
        (is (= "0x2222222222222222222222222222222222222222"
               (get-in result [:pinned-row :eth-address])))
        (is (= [2 1] (mapv :rank (cons (:pinned-row result) (:rows result)))))
        (is (= ["0x1111111111111111111111111111111111111111"]
               (mapv :eth-address (:rows result))))
        (is (= 2 (:total-rows result)))))))

(deftest leaderboard-vm-searches-address-and-display-name-and-sorts-deterministically-test
  (let [state {:wallet {:address nil}
               :leaderboard-ui {:query "brav"
                                :timeframe :month
                                :sort {:column :account-value
                                       :direction :asc}
                                :page 1}
               :leaderboard {:rows sample-rows
                             :excluded-addresses #{}
                             :loading? false
                             :error nil
                             :loaded-at-ms 1700000000000}}
        result (vm/leaderboard-vm state)]
    (is (= ["0x2222222222222222222222222222222222222222"]
           (mapv :eth-address (:rows result))))
    (is (= 1 (:total-rows result)))
    (is (nil? (:pinned-row result)))))

(deftest leaderboard-vm-paginates-unpinned-results-with-fixed-page-size-test
  (let [rows (mapv (fn [idx]
                     {:eth-address (str "0x" (.padStart (str idx) 40 "0"))
                      :account-value idx
                      :display-name (str "Trader " idx)
                      :window-performances {:day {:pnl idx :roi (/ idx 1000) :volume idx}
                                            :week {:pnl idx :roi (/ idx 1000) :volume idx}
                                            :month {:pnl idx :roi (/ idx 1000) :volume idx}
                                            :all-time {:pnl idx :roi (/ idx 1000) :volume idx}}})
                   (range 1 16))
        state {:wallet {:address nil}
               :leaderboard-ui {:query ""
                                :timeframe :month
                                :sort {:column :pnl
                                       :direction :desc}
                                :page 2}
               :leaderboard {:rows rows
                             :excluded-addresses #{}
                             :loading? false
                             :error nil
                             :loaded-at-ms 1700000000000}}
        result (vm/leaderboard-vm state)]
    (is (= 2 (:page result)))
    (is (= 2 (:page-count result)))
    (is (= 5 (:visible-rows-count result)))
    (is (= [11 12 13 14 15]
           (mapv :rank (:rows result))))))

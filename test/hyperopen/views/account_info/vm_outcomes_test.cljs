(ns hyperopen.views.account-info.vm-outcomes-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.account-info.vm :as vm]))

(defn- base-orders []
  {:open-orders []
   :open-orders-snapshot []
   :open-orders-snapshot-by-dex {}
   :order-history []})

(deftest account-info-vm-enriches-outcome-mark-from-public-spot-contexts-test
  (let [outcome-market {:key "outcome:0"
                        :coin "#0"
                        :title "BTC above 78213 on May 3 at 2:00 AM?"
                        :symbol "BTC above 78213 on May 3 at 2:00 AM?"
                        :quote "USDH"
                        :market-type :outcome
                        :outcome-id 0
                        :outcome-sides [{:side-index 0
                                         :side-name "Yes"
                                         :coin "#0"}
                                        {:side-index 1
                                         :side-name "No"
                                         :coin "#1"}]}
        state {:account-info {:selected-tab :outcomes}
               :active-market outcome-market
               :asset-selector {:market-by-key {}}
               :webdata2 {:spotAssetCtxs [{:coin "#0" :markPx "0.53210"}]}
               :orders (base-orders)
               :account {:mode :classic}
               :spot {:meta nil
                      :clearinghouse-state {:balances [{:coin "+0"
                                                        :token 100000000
                                                        :hold "0"
                                                        :total "19"
                                                        :entryNtl "11.0271"}]}}
               :perp-dex-clearinghouse {}}
        view-model (vm/account-info-vm state)
        [outcome-row] (:outcomes view-model)]
    (is (= 1 (get-in view-model [:tab-counts :outcomes])))
    (is (= 1 (count (:outcomes view-model))))
    (is (= "BTC above 78213 on May 3 at 2:00 AM?" (:title outcome-row)))
    (is (= "Yes" (:side-name outcome-row)))
    (is (= 0.53210 (:mark-price outcome-row)))
    (is (< (js/Math.abs (- 10.1099 (:position-value outcome-row))) 0.000001))
    (is (< (js/Math.abs (- -0.9172 (:pnl-value outcome-row))) 0.000001))))

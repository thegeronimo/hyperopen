(ns hyperopen.outcomes.receipt-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.outcomes.receipt :as receipt]
            [hyperopen.views.account-info.outcome-receipt :as receipt-view]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]))

(defn- cents [x] (/ (js/Math.round (* 100 x)) 100))

(deftest from-fills-nets-buys-and-sells-test
  (let [fills [{:coin "#5" :side "B" :sz "60" :px "0.5" :time 1}
               {:coin "#5" :side "B" :sz "40" :px "0.54" :time 2}
               {:coin "#5" :side "A" :sz "50" :px "0.6" :time 3}
               {:coin "BTC" :side "B" :sz "9" :px "1" :time 1}]
        {:keys [shares cost avg-price]} (receipt/from-fills fills "+5")]
    (is (= 50 shares))
    (is (= 25.8 (cents cost)))
    (is (= 0.52 (cents avg-price)))))

(deftest settled-win-receipt-matches-board-4e-test
  (let [r (receipt/receipt {:title "Bitcoin above $74,456 at 1:00 AM EST"
                            :side-name "Yes" :side-label "Yes"
                            :shares 50 :cost 26 :payout-per-share 1})]
    (is (= "Settled Yes" (:result r)))
    (is (= "50 Yes · average 52.0¢" (:held r)))
    (is (= 50 (:payout r)))
    (is (= "0.14%" (:fee-rate-text r)))
    (is (= 0.07 (cents (:fee r))))
    (is (= 49.93 (cents (:received r))))
    (is (= 23.93 (cents (:profit r))))))

(deftest split-result-and-missing-result-test
  (is (= "A split result: settled at 62%"
         (:result (receipt/receipt {:shares 10 :cost 5 :payout-per-share 0.62}))))
  (let [r (receipt/receipt {:shares 10 :cost 5})]
    (is (= "Result not available" (:result r)))
    (is (false? (:result-known? r)))
    (is (nil? (:received r)))))

(deftest unconfirmed-venue-receipt-prints-no-fee-test
  (let [r (receipt/receipt {:shares 10 :cost 5 :payout-per-share 1 :venue "txyz"})]
    (is (false? (:fee-known? r)))
    (is (nil? (:fee r)))
    (is (contains? (set (hiccup/collect-strings (receipt-view/receipt-view r)))
                   "unconfirmed on this venue"))))

(deftest receipt-view-links-onward-test
  (let [r (receipt/receipt {:title "t" :shares 50 :cost 26 :payout-per-share 1
                            :next {:title "Tomorrow's Bitcoin daily" :coin "#30"}})
        view (receipt-view/receipt-view r)
        all (set (hiccup/collect-strings view))
        link (hiccup/find-first-node view #(= "outcome-receipt-next" (get-in % [1 :data-role])))]
    (is (contains? all "You received"))
    (is (contains? all "$49.93"))
    (is (contains? all "+$23.93"))
    (is (= [[:actions/open-outcome-market nil "#30"]] (get-in link [1 :on :click])))))

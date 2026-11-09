(ns hyperopen.views.trade.outcome-ticket-vm-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.outcomes.test-support.fixture :as fixture]
            [hyperopen.views.active-asset.outcome-header-vm :as header-vm]
            [hyperopen.views.trade.outcome-ticket-vm :as ticket-vm]))

(def ^:private now-ms (js/Date.UTC 2026 10 9 16 25))

(defn- market-by-key []
  (into {} (map (juxt :key identity))
        (markets/build-outcome-markets fixture/outcome-meta fixture/asset-ctxs)))

(defn- active-market
  [market-key coin]
  (markets/market-with-selected-outcome-coin (get (market-by-key) market-key) coin))

;; #270370 (DET) book, 2026-11-09 16:25 UTC.
(def ^:private det-book
  {:asks [{:px "0.81902" :sz "758"} {:px "0.81903" :sz "97"} {:px "0.8194" :sz "700"}]
   :bids [{:px "0.81886" :sz "69"} {:px "0.81885" :sz "135"} {:px "0.81859" :sz "67"}]})

(defn- state
  [market coin book & [balances]]
  {:active-asset coin
   :active-market market
   :orderbooks {coin book}
   :spot {:clearinghouse-state {:balances (or balances [])}}})

(defn- vm
  [overrides]
  (merge {:side :buy
          :type :market
          :form {:size "122" :price ""}
          :size-input-mode :quote
          :size-display "100"
          :available-usdc 275.67
          :submit {:disabled? false}}
         overrides))

(deftest header-reads-both-teams-and-the-selected-price-test
  (let [game (active-market "outcome:27037" "#270370")
        model (header-vm/header-model game det-book now-ms)]
    (is (= "Detroit Pistons vs Washington Wizards" (:title model)))
    (is (= "Sports · NBA" (:kicker model)))
    (is (= "out" (:venue model)))
    (is (= [{:label "DET" :text "82%"} {:label "WAS" :text "18%"}] (:chances model)))
    (is (= "DET" (:price-label model)))
    (is (= "81.9¢" (:price-text model)))
    (is (= "Starts" (:clock-heading model)))
    (is (= "Starts in 7h 35m" (:clock-label model)))
    (is (= "24h" (:change-label model)))))

(deftest buying-a-team-shows-shares-and-payout-test
  (let [game (active-market "outcome:27037" "#270370")
        model (ticket-vm/ticket-model (state game "#270370" det-book) (vm {}) now-ms)]
    (is (= [["DET" "81.9¢" "82%" true] ["WAS" "18.1¢" "18%" false]]
           (mapv (juxt :label :price-text :chance-text :selected?) (:sides model))))
    (is (= ["Detroit Pistons" "Washington Wizards"] (mapv :sub-label (:sides model))))
    (is (= [:neutral :neutral] (mapv :tone (:sides model))))
    (is (= [["Shares" "122 DET"]
            ["Avg price" "81.902¢"]
            ["Cost" "$99.92"]
            ["Pays if DET wins" "$122.00"]
            ["Profit if right" "+$22.08 (+22.1%)"]]
           (:summary-rows model)))
    (is (= "Buy DET · $99.92" (:submit-label model)))
    (is (false? (:submit-blocked? model)))
    (is (= "0 DET · 0 WAS" (:held-text model)))
    (is (not (:holds-any? model)))
    (is (re-find #"^Contract 27037 · Sides #270370 / #270371 · Venue out" (:details model)))))

(deftest cash-out-sells-held-shares-into-the-bids-test
  (let [game (active-market "outcome:27037" "#270370")
        balances [{:coin "+270370" :total "122.0" :entryNtl "99.92"}]
        model (ticket-vm/ticket-model (state game "#270370" det-book balances)
                                      (vm {:side :sell :size-input-mode :base :size-display "122"})
                                      now-ms)]
    (is (= :cash-out (:mode model)))
    (is (= 122 (:held model)))
    (is (= "122 DET · 0 WAS" (:held-text model)))
    (is (= "Cash out 122 DET · $99.90" (:submit-label model)))
    (is (= ["Result vs cost" "−$0.02"] (last (:summary-rows model))))))

(deftest one-sided-book-turns-market-orders-off-test
  (let [game (active-market "outcome:27037" "#270370")
        book {:asks [{:px "0.988" :sz "100"}] :bids []}
        model (ticket-vm/ticket-model (state game "#270370" book) (vm {}) now-ms)
        limit (ticket-vm/ticket-model (state game "#270370" book)
                                      (vm {:type :limit :form {:size "333" :price "0.03"}})
                                      now-ms)]
    (is (true? (:market-blocked? model)))
    (is (true? (:submit-blocked? model)))
    (is (re-find #"No two-sided quotes · best ask 98\.8¢" (:no-market-note model)))
    (is (= "Place limit · 333 DET at 3.000¢" (:submit-label limit)))
    (is (= "3" (:limit-cents-text limit)))
    (is (= "Rests below the best ask. Fills only if DET trades down to 3.0¢." (:limit-note limit)))))

(deftest pick-one-ticket-lists-every-answer-test
  (let [fed (get (market-by-key) "question:545")
        hike-no (:no-coin (some #(when (= "Hike" (:label %)) %) (:question-options fed)))
        active (markets/market-with-selected-outcome-coin fed hike-no)
        model (ticket-vm/ticket-model (state active hike-no nil) (vm {:form {:size "" :price ""}}) now-ms)
        options (:options model)]
    (is (= #{"Hike" "Hold" "Cut"} (set (map :label (:rows options)))))
    (is (true? (:exclusive? options)))
    (is (= "Other" (get-in options [:other :label])))
    (is (= "Hike" (:option-label model)))
    (is (= ["Yes" "No"] (mapv :label (:sides model))))
    (is (= ["Hike" "Not Hike"] (mapv :sub-label (:sides model))))
    (is (= [false true] (mapv :selected? (:sides model))))
    (is (= "No on Hike wins if any other answer wins." (:no-explainer model)))
    (is (= "Buy No on Hike" (:submit-label model)))))

(deftest ladder-rungs-sit-against-the-live-price-test
  (let [ladder (get (market-by-key) "ladder:price-touch|BTC|20261201-0000")
        rung-90k (some #(when (= "$90,000" (:label %)) %) (:question-options ladder))
        active (markets/market-with-selected-outcome-coin ladder (:yes-coin rung-90k))
        base-state (assoc (state active (:yes-coin rung-90k) nil)
                          :asset-selector {:market-by-key {"perp:BTC" {:mark 74940}}})
        model (ticket-vm/ticket-model base-state (vm {:form {:size "" :price ""}}) now-ms)
        rows (get-in model [:options :rows])]
    (is (true? (get-in model [:options :ladder?])))
    (is (= "Bitcoin now $74,940" (get-in model [:options :spot-text])))
    (is (= [["$90,000" :up "+20.1%"] ["$85,000" :up "+13.4%"] ["$70,000" :down "−6.6%"] ["$65,000" :down "−13.3%"]]
           (mapv (juxt :label :direction :distance-text) rows)))
    (is (= "$90,000" (:option-label model)))))

(deftest ladder-flags-a-rung-priced-above-the-one-nearer-the-money-test
  (let [ladder (get (market-by-key) "ladder:price-touch|BTC|20261201-0000")
        ;; Make $90k (further out) dearer than $85k: a kink.
        kinked (update ladder :question-options
                       (fn [options]
                         (mapv (fn [option]
                                 (case (:label option)
                                   "$90,000" (assoc-in option [:sides 0 :mid] 0.30)
                                   "$85,000" (assoc-in option [:sides 0 :mid] 0.20)
                                   option))
                               options)))
        kinked (update kinked :question-options
                       (fn [options]
                         (mapv (fn [option]
                                 (update-in option [:sides 0] #(assoc % :mark (:mid %))))
                               options)))
        active (markets/market-with-selected-outcome-coin kinked (:yes-coin (first (:question-options kinked))))
        model (ticket-vm/ticket-model (assoc (state active nil nil)
                                             :asset-selector {:market-by-key {"perp:BTC" {:mark 74940}}})
                                      (vm {:form {:size "" :price ""}})
                                      now-ms)
        by-label (into {} (map (juxt :label identity)) (get-in model [:options :rows]))]
    (is (= "Priced above the $85,000 rung" (get-in by-label ["$90,000" :kink-note])))
    (is (nil? (get-in by-label ["$85,000" :kink-note])))))

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
    (is (= [{:label "DET" :text "82%"} {:label "WAS" :text "18%"}]
           (mapv #(select-keys % [:label :text]) (:chances model))))
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
            ["Average price" "81.902¢"]
            ["Cost" "$99.92"]
            ["Fee to buy" "None"]
            ["If DET wins" "$122.00"]
            ["Settlement fee (0.14%)" "−$0.17"]
            ["You keep if right" "$121.83"]
            ["Profit" "+$21.91 (+21.9%)"]]
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
    (is (= "Cash out 122 DET · $99.76" (:submit-label model)))
    (is (= [["Selling" "122 DET"]
            ["Sell at best bid" "81.886¢"]
            ["Sale" "$99.90"]
            ["Fee (0.14%)" "−$0.14"]
            ["You receive" "$99.76"]
            ["Result vs cost" "−$0.16"]]
           (:summary-rows model)))
    (is (= ["Resting at your own price instead pays 0.08%."
            "Or hold to the result: $121.83 after the fee if DET wins, $0 if WAS wins."]
           (:summary-notes model)))))

(deftest one-sided-book-turns-market-orders-off-test
  (let [game (active-market "outcome:27037" "#270370")
        book {:asks [{:px "0.988" :sz "100"}] :bids []}
        model (ticket-vm/ticket-model (state game "#270370" book) (vm {}) now-ms)
        limit (ticket-vm/ticket-model (state game "#270370" book)
                                      (vm {:type :limit :form {:size "333" :price "0.03"}})
                                      now-ms)]
    (is (true? (:market-blocked? model)))
    ;; The submit gate itself is the submit policy's (`outcome-order-message`).
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
    (is (= ["Hike" "Any other answer"] (mapv :sub-label (:sides model))))
    (is (= [false true] (mapv :selected? (:sides model))))
    (is (= "No on Hike wins if any other answer wins." (:no-explainer model)))
    (is (= "Buy No on Hike" (:submit-label model)))))

(deftest ladder-rungs-sit-against-the-live-price-test
  (let [ladder (get (market-by-key) "ladder:price-touch|BTC|out|BTC-USDC perp mark|3|20261201-0000")
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
  (let [ladder (get (market-by-key) "ladder:price-touch|BTC|out|BTC-USDC perp mark|3|20261201-0000")
        ;; Make $90k (further out) dearer than $85k: a kink.
        kinked (update ladder :question-options
                       (fn [options]
                         (mapv (fn [option]
                                 (case (:label option)
                                   ;; Live best bid/ask written by the book feed.
                                   "$90,000" (update-in option [:sides 0] assoc :bid 0.295 :ask 0.305)
                                   "$85,000" (update-in option [:sides 0] assoc :bid 0.195 :ask 0.205)
                                   option))
                               options)))
        active (markets/market-with-selected-outcome-coin kinked (:yes-coin (first (:question-options kinked))))
        model (ticket-vm/ticket-model (assoc (state active nil nil)
                                             :asset-selector {:market-by-key {"perp:BTC" {:mark 74940}}})
                                      (vm {:form {:size "" :price ""}})
                                      now-ms)
        by-label (into {} (map (juxt :label identity)) (get-in model [:options :rows]))]
    (is (= "Priced above the $85,000 rung" (get-in by-label ["$90,000" :kink-note])))
    (is (nil? (get-in by-label ["$85,000" :kink-note])))))

(deftest cash-out-counts-only-shares-not-held-by-open-orders-test
  (let [game (active-market "outcome:27037" "#270370")
        balances [{:coin "+270370" :total "122.0" :hold "22.0" :entryNtl "99.92"}]
        model (ticket-vm/ticket-model (state game "#270370" det-book balances)
                                      (vm {:side :sell :size-input-mode :base :size-display "110"
                                           :form {:size "110" :price ""}})
                                      now-ms)]
    (is (= 100 (:held model)))
    ;; 110 > 100 free is refused by the submit policy, see
    ;; outcome-order-policy-test/outcome-balance-checks-before-submit-test.
    (is (= :cash-out (:mode model)))))

(deftest market-summary-respects-the-ioc-price-cap-test
  (let [game (active-market "outcome:27037" "#270370")
        thin {:asks [{:px "0.50" :sz "10"} {:px "0.60" :sz "1000"}]
              :bids [{:px "0.49" :sz "100"}]}
        model (ticket-vm/ticket-model (state game "#270370" thin)
                                      (vm {:form {:size "100" :price ""}})
                                      now-ms)
        rows (into {} (:summary-rows model))]
    (is (= "10 DET" (get rows "Shares")) "only the levels under the 5% cap fill")
    (is (= "10 of 100 DET" (get rows "Fills at your price cap")))
    (is (= "$5.00" (get rows "Cost")))))

(deftest blank-limit-price-asks-for-one-test
  (let [game (active-market "outcome:27037" "#270370")
        blank (ticket-vm/ticket-model (state game "#270370" det-book)
                                      (vm {:type :limit :form {:size "100" :price ""}})
                                      now-ms)
        priced (ticket-vm/ticket-model (state game "#270370" det-book)
                                       (vm {:type :limit :form {:size "100" :price "0.29"}})
                                       now-ms)]
    (is (= "Enter a limit price" (:submit-label blank)))
    (is (true? (:submit-blocked? blank)))
    (is (= "Place limit · 100 DET at 29.000¢" (:submit-label priced)))
    (is (false? (:submit-blocked? priced)))))

(deftest stale-board-blocks-submit-until-confirmed-test
  (let [fed (get (market-by-key) "question:545")
        stale (update fed :question-options
                      (fn [options]
                        (mapv #(-> % (assoc-in [:sides 0 :bid] 0.59) (assoc-in [:sides 0 :ask] 0.61)) options)))
        coin (:yes-coin (first (:question-options stale)))
        active (markets/market-with-selected-outcome-coin stale coin)
        unconfirmed (ticket-vm/ticket-model (state active coin det-book)
                                            (vm {:type :limit :form {:size "10" :price "0.5"}})
                                            now-ms)
        confirmed (ticket-vm/ticket-model (state active coin det-book)
                                          (vm {:type :limit :form {:size "10" :price "0.5"
                                                                   :outcome-stale-confirmed? true}})
                                          now-ms)]
    (is (true? (get-in unconfirmed [:options :stale?])))
    (is (true? (:submit-blocked? unconfirmed)))
    (is (false? (:submit-blocked? confirmed)))
    (is (true? (:stale-confirmed? confirmed)))))

;; ---- Chance rule states (4b) -------------------------------------------

(defn- det-model
  [book vm-overrides & [balances]]
  (let [game (active-market "outcome:27037" "#270370")]
    (ticket-vm/ticket-model (state game "#270370" book balances) (vm vm-overrides) now-ms)))

(deftest live-book-shows-the-chance-and-allows-market-orders-test
  (let [model (det-model det-book {})]
    (is (= :live (:quote-state model)))
    (is (= ["82% chance" "18% chance"] (mapv :chance-label (:sides model))))
    (is (false? (:market-blocked? model)))
    (is (nil? (:no-market-note model)))))

(deftest never-traded-wide-book-reads-not-traded-and-is-limit-only-test
  (let [book {:asks [{:px "0.987" :sz "100"}] :bids [{:px "0.013" :sz "100"}]}
        game (-> (active-market "outcome:27037" "#270370")
                 (assoc-in [:outcome-sides 0 :prevDayRaw] 0)
                 (assoc-in [:outcome-sides 0 :volume24h] 0)
                 (assoc-in [:outcome-sides 0 :baseVolume24h] 0))
        model (ticket-vm/ticket-model (state game "#270370" book) (vm {}) now-ms)
        limit (ticket-vm/ticket-model (state game "#270370" book)
                                      (vm {:type :limit :form {:size "10" :price "0.05"}}) now-ms)]
    (is (= :not-traded (:quote-state model)))
    (is (= ["Not traded" "Not traded"] (mapv :chance-label (:sides model))))
    (is (= ["ask 98.7¢" "ask 98.7¢"] (mapv :price-text (:sides model))))
    (is (true? (:market-blocked? model)))
    (is (= "Not traded yet · bid 1.3¢ · ask 98.7¢. Market orders are off; set a limit price."
           (:no-market-note model)))
    (is (false? (:submit-blocked? limit)) "a limit order still goes")))

(deftest range-book-is-muted-and-prices-the-button-test
  (let [book {:asks [{:px "0.40" :sz "500"}] :bids [{:px "0.30" :sz "500"}]}
        model (det-model book {})]
    (is (= :range (:quote-state model)))
    (is (= ["30–40% chance" "60–70% chance"] (mapv :chance-label (:sides model))))
    (is (every? :muted? (:sides model)))
    (is (false? (:market-blocked? model)) "market orders are allowed across a range")
    (is (= "Buy DET at 40.0¢" (:submit-label model)))
    (is (re-find #"10\.0¢ wide: a market buy pays the ask, 40\.0¢ a share" (:wide-note model)))))

(deftest wide-or-empty-book-reads-no-market-test
  (let [book {:asks [{:px "0.988" :sz "100"}] :bids [{:px "0.012" :sz "100"}]}
        model (det-model book {})]
    (is (= :no-market (:quote-state model)))
    (is (= "No market" (:chance-text (first (:sides model)))))
    (is (true? (:market-blocked? model)))
    (is (re-find #"^No market · bid 1\.2¢ · ask 98\.8¢" (:no-market-note model)))))

(deftest stale-board-only-counts-midpoint-chances-test
  (let [fed (get (market-by-key) "question:545")
        marked (update fed :question-options
                       (fn [options] (mapv #(-> % (assoc-in [:sides 0 :mid] 0.9) (assoc-in [:sides 0 :mark] 0.9)) options)))
        coin (:yes-coin (first (:question-options marked)))
        active (markets/market-with-selected-outcome-coin marked coin)
        model (ticket-vm/ticket-model (state active coin nil) (vm {:type :limit :form {:size "1" :price "0.5"}}) now-ms)]
    (is (false? (get-in model [:options :stale?])) "marks are not chances, so they cannot add up to a stale board")))

;; ---- Fees (4c) -----------------------------------------------------------

(deftest unconfirmed-venue-prints-no-fee-number-test
  (let [game (assoc (active-market "outcome:27037" "#270370") :outcome-venue "skew")
        balances [{:coin "+270370" :total "122.0" :entryNtl "99.92"}]
        buy (ticket-vm/ticket-model (state game "#270370" det-book) (vm {}) now-ms)
        sell (ticket-vm/ticket-model (state game "#270370" det-book balances)
                                     (vm {:side :sell :size-input-mode :base :size-display "122"}) now-ms)]
    (is (some #{["Fees" "Fee not confirmed for this venue"]} (:summary-rows buy)))
    (is (not-any? #(#{"You keep if right" "Profit" "Fee to buy"} (first %)) (:summary-rows buy)))
    (is (some #{["Fee" "Fee not confirmed for this venue"]} (:summary-rows sell)))
    (is (not-any? #(= "You receive" (first %)) (:summary-rows sell)))
    (is (= "Cash out 122 DET" (:submit-label sell)) "no net number on the button")
    (is (nil? (:summary-notes sell)))))

(deftest resting-limit-sale-pays-the-maker-rate-test
  (let [game (active-market "outcome:27037" "#270370")
        balances [{:coin "+270370" :total "122.0" :entryNtl "99.92"}]
        model (ticket-vm/ticket-model (state game "#270370" det-book balances)
                                      (vm {:side :sell :type :limit :size-input-mode :base :size-display "100"
                                           :form {:size "100" :price "0.90"}})
                                      now-ms)
        rows (into {} (:summary-rows model))]
    (is (= "$90.00" (get rows "Sale")))
    (is (= "−$0.07" (get rows "Fee (0.08%)")))
    (is (= "$89.93" (get rows "You receive")))
    (is (= "Place limit sell · 100 DET at 90.000¢" (:submit-label model)))))

(deftest account-fee-tier-changes-the-fees-test
  (let [game (active-market "outcome:27037" "#270370")
        base (state game "#270370" det-book)
        tiered (assoc-in base [:portfolio :user-fees] {:userSpotCrossRate "0.0003" :userSpotAddRate "0.0001"})
        rows (into {} (:summary-rows (ticket-vm/ticket-model tiered (vm {}) now-ms)))]
    (is (= "−$0.07" (get rows "Settlement fee (0.06%)")))))

;; ---- Merge pairs (4c ticket C) ---------------------------------------------

(def ^:private both-sides
  [{:coin "+270370" :total "122.0" :entryNtl "99.92"}
   {:coin "+270371" :total "40.0" :entryNtl "7.0"}])

(deftest holding-both-sides-offers-merge-test
  (let [game (active-market "outcome:27037" "#270370")
        closed (ticket-vm/ticket-model (state game "#270370" det-book both-sides) (vm {}) now-ms)
        one-side (ticket-vm/ticket-model (state game "#270370" det-book [(first both-sides)]) (vm {}) now-ms)]
    (is (true? (:can-merge? closed)))
    (is (= :buy (:mode closed)) "merge is its own tab, never the default")
    (is (= {:coins ["#270370" "#270371"] :pairs "40"} (:merge-entry closed)))
    (is (not (:can-merge? one-side)))
    (is (nil? (:merge-entry one-side)))))

(deftest merge-ticket-returns-a-dollar-a-pair-less-the-fee-test
  (let [game (active-market "outcome:27037" "#270370")
        st (assoc (state game "#270370" det-book both-sides)
                  :outcome-ticket {:merge {:coins ["#270371" "#270370"] :pairs "40"}})
        model (ticket-vm/ticket-model st (vm {}) now-ms)
        merge-model (:merge model)]
    (is (= :merge (:mode model)))
    (is (= ["122 DET" "40 WAS"] (:held-text merge-model)))
    (is (= "One DET and one WAS together pay $1.00 whoever wins." (:sentence merge-model)))
    (is (= [["Returns" "$40.00"]
            ["Fee (0.14%)" "−$0.06"]
            ["You receive" "$39.94"]
            ["Leaves" "82 DET"]]
           (:rows merge-model)))
    (is (= "Merge 40 pairs · $39.94" (:submit-label merge-model)))
    (is (= {:outcome-id 27037 :pairs 40} (:submit-args merge-model)))
    (is (false? (:submit-blocked? merge-model)))))

(deftest merge-pairs-are-capped-at-what-both-sides-hold-test
  (let [game (active-market "outcome:27037" "#270370")
        st (fn [pairs] (assoc (state game "#270370" det-book both-sides)
                              :outcome-ticket {:merge {:coins ["#270370" "#270371"] :pairs pairs}}))
        over (:merge (ticket-vm/ticket-model (st "99") (vm {}) now-ms))
        none (:merge (ticket-vm/ticket-model (st "") (vm {}) now-ms))]
    (is (= 40 (:pairs over)))
    (is (= "Merge pairs" (:submit-label none)))
    (is (true? (:submit-blocked? none)))))

(deftest merge-state-for-another-contract-is-ignored-test
  (let [game (active-market "outcome:27037" "#270370")
        st (assoc (state game "#270370" det-book both-sides)
                  :outcome-ticket {:merge {:coins ["#1" "#2"] :pairs "40"}})]
    (is (= :buy (:mode (ticket-vm/ticket-model st (vm {}) now-ms))))))

(deftest merge-on-an-unconfirmed-venue-has-no-fee-number-test
  (let [game (assoc (active-market "outcome:27037" "#270370") :outcome-venue "fox")
        st (assoc (state game "#270370" det-book both-sides)
                  :outcome-ticket {:merge {:coins ["#270370" "#270371"] :pairs "40"}})
        merge-model (:merge (ticket-vm/ticket-model st (vm {}) now-ms))]
    (is (= [["Returns" "$40.00"] ["Fee" "Fee not confirmed for this venue"]] (:rows merge-model)))
    (is (= "Merge 40 pairs" (:submit-label merge-model)))))

(deftest ticket-clock-is-read-at-the-vm-boundary-test
  (let [game (active-market "outcome:27037" "#270370")
        injected (ticket-vm/ticket-model (assoc (state game "#270370" det-book) :now-ms now-ms) (vm {}))
        explicit (ticket-vm/ticket-model (state game "#270370" det-book) (vm {}) now-ms)]
    (is (= (:timeline injected) (:timeline explicit)))
    (is (seq (second (first (:timeline injected)))))))

(deftest cash-out-with-nothing-held-falls-back-to-buy-test
  (let [model (det-model det-book {:side :sell :size-input-mode :base :size-display "10"})]
    (is (= :buy (:mode model)) "a Sell left on the form is not a Cash out of nothing")
    (is (false? (boolean (:holds-any? model))))))

(deftest limit-field-keeps-the-raw-typed-cents-test
  (let [game (active-market "outcome:27037" "#270370")
        model (fn [raw price]
                (ticket-vm/ticket-model (assoc-in (state game "#270370" det-book) [:outcome-ticket :limit-cents-text] raw)
                                        (vm {:type :limit :form {:size "10" :price price}})
                                        now-ms))]
    (is (= "68." (:limit-cents-text (model "68." "0.68"))) "the dot survives a backspace from 68.5")
    (is (= "68.5" (:limit-cents-text (model "68.5" "0.685"))))
    (is (= "" (:limit-cents-text (model "" ""))))
    (is (= "41" (:limit-cents-text (model "68." "0.41")))
        "a price set elsewhere (a Bid/Mid/Ask click) shows formatted")))

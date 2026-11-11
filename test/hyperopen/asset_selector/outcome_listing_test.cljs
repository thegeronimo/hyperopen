(ns hyperopen.asset-selector.outcome-listing-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.asset-selector.outcome-listing :as listing]
            [hyperopen.outcomes.test-support.fixture :as fixture]
            [hyperopen.views.active-asset.outcome-header-vm :as header-vm]))

(def ^:private now-ms (js/Date.UTC 2026 10 9 16 25))
(def ^:private utc {:time-zone "UTC"})
(def ^:private by-start (assoc utc :sort :start-time))

(def ^:private touch-key
  "ladder:price-touch|BTC|out|BTC-USDC perp mark|3|20261201-0000")

(defn- built-markets []
  (markets/build-outcome-markets fixture/outcome-meta fixture/asset-ctxs))

(defn- market-by-key [k]
  (some #(when (= k (:key %)) %) (built-markets)))

(defn- row-by-key
  [sections k]
  (some #(when (= k (:key %)) %) (mapcat :rows sections)))

(defn- book
  "An order book with one level a side."
  [bid ask]
  {:bids (if bid [{:px (str bid) :sz "100"}] [])
   :asks (if ask [{:px (str ask) :sz "100"}] [])})

(deftest default-order-is-most-traded-in-shares-test
  (let [sections (listing/sections (built-markets) now-ms utc)
        rows (:rows (first sections))]
    (is (= ["Most traded"] (mapv :title sections)))
    (is (= 15 (count rows)))
    (is (apply >= (map #(or (:volume %) 0) rows)))
    (testing "volume counts each contract once, in shares"
      (let [game (row-by-key sections "outcome:27037")]
        ;; dayBaseVlm 226,989 on both sides of #27037: counted once.
        (is (= 226989 (:volume game)))
        (is (= "227K shares" (:volume-text game)))))))

(deftest sort-options-keep-start-time-and-ending-soon-test
  (is (= [:most-traded :ending-soon :start-time] (mapv :id listing/sort-options)))
  (is (= :most-traded (listing/normalize-sort nil)))
  (is (= :ending-soon (listing/normalize-sort "ending-soon")))
  (testing "by start time keeps the time-then-category sections"
    (is (= ["Tomorrow · NBA"
            "Thu, Nov 12 · UEFA Nations League"
            "Futures"
            "Crypto"
            "Stocks & commodities"
            "Economics"
            "Companies"
            "Other markets"]
           (mapv :title (listing/sections (built-markets) now-ms by-start)))))
  (testing "ending soon puts upcoming events by time left and past ones last"
    (let [later (+ now-ms (* 10 60 1000))
          sections (listing/sections (built-markets) later (assoc utc :sort :ending-soon))
          rows (:rows (first sections))
          at-ms (fn [row] (get-in (market-by-key (:key row)) [:outcome-clock :at-ms]))
          upcoming (remove #(= :awaiting (get-in % [:clock :phase])) rows)]
      (is (= ["Ending soon"] (mapv :title sections)))
      (is (= "outcome:29614" (:key (first rows))) "NVDA settles first once the 16:30 strikes passed")
      (is (apply <= (keep at-ms upcoming)))
      (is (= #{"outcome:30516" "outcome:30520"} (set (map :key (take-last 2 rows))))))))

(deftest live-games-are-not-earlier-test
  (let [game (market-by-key "outcome:27037")
        started (+ (get-in game [:outcome-clock :at-ms]) (* 30 60 1000))
        long-ago (+ (get-in game [:outcome-clock :at-ms]) (* 6 60 60 1000))]
    (is (= "Live · NBA" (:title (first (listing/sections [game] started by-start)))))
    (is (= "Awaiting result · NBA" (:title (first (listing/sections [game] long-ago by-start)))))))

(deftest head-to-head-row-answers-are-both-teams-test
  (let [row (row-by-key (listing/sections (built-markets) now-ms utc) "outcome:27037")]
    (is (= "Detroit Pistons vs Washington Wizards" (:title row)))
    (is (= "NBA" (:meta row)))
    (is (= "out" (:venue row)))
    (is (= "Starts in 7h 35m" (get-in row [:clock :label])))
    (testing "no book yet: a dash, never the context mid or the mark"
      (is (= [["DET" "—" "#270370"] ["WAS" "—" "#270371"]]
             (mapv (juxt :label :text :coin) (:answers row)))))
    (testing "with the live book the row quotes the midpoint, sides mirrored"
      (let [row* (row-by-key (listing/sections (built-markets) now-ms
                                               (assoc utc :books {"#270370" (book 0.81886 0.81902)}))
                             "outcome:27037")]
        (is (= [["DET" "82%"] ["WAS" "18%"]] (mapv (juxt :label :text) (:answers row*))))))
    (is (zero? (:more row)))))

(deftest list-header-and-ticket-agree-on-one-contract-test
  ;; Same inputs on every surface: the market and the book of its selected side.
  (doseq [[bid ask expected] [[0.81886 0.81902 "82%"]
                              [0.30 0.40 "30–40%"]
                              [0.012 0.988 "No market"]]]
    (let [market (market-by-key "outcome:27037")
          orderbook (book bid ask)
          list-row (row-by-key (listing/sections [market] now-ms (assoc utc :books {"#270370" orderbook}))
                               "outcome:27037")
          header (header-vm/header-model market orderbook now-ms)
          ;; The ticket reads `header-vm/side-quotes` with the same book.
          ticket-quotes (header-vm/side-quotes market orderbook)]
      (is (= expected (:text (first (:answers list-row)))))
      (is (= expected (:text (first (:chances header)))))
      (is (= (:quote (first (:answers list-row))) (first ticket-quotes)))
      (is (= (:state (:quote header)) (:state (first ticket-quotes)))))))

(deftest placeholder-quotes-read-not-traded-everywhere-test
  ;; Never traded: mid 0.50, prevDayPx 0, no volume, book 1.3¢ / 98.7¢.
  (let [placeholder {:prevDayPx "0.0" :dayNtlVlm "0.0" :dayBaseVlm "0.0" :markPx "0.5" :midPx "0.5"
                     :circulatingSupply "0.0"}
        ctxs (mapv (fn [ctx] (if (#{"#305200" "#305201"} (:coin ctx)) (merge ctx placeholder) ctx))
                   fixture/asset-ctxs)
        market (some #(when (= "outcome:30520" (:key %)) %)
                     (markets/build-outcome-markets fixture/outcome-meta ctxs))
        orderbook (book 0.013 0.987)
        list-row (row-by-key (listing/sections [market] now-ms (assoc utc :books {"#305200" orderbook}))
                             "outcome:30520")
        bare-row (row-by-key (listing/sections [market] now-ms utc) "outcome:30520")
        header (header-vm/header-model market orderbook now-ms)]
    (is (= "Not traded" (:text (first (:answers list-row)))))
    (is (= "bid 1.3¢ · ask 98.7¢" (:detail (first (:answers list-row)))))
    (is (= "Not traded" (:text (first (:answers bare-row)))) "without a book it is still Not traded, never 50%")
    (is (= "Not traded" (:text (first (:chances header)))))
    (is (false? (:market-order? header)))))

(deftest header-change-uses-the-selected-side-test
  (let [game (markets/market-with-selected-outcome-coin (market-by-key "outcome:27037") "#270371")
        model (header-vm/header-model game nil now-ms)]
    ;; WAS: mark 0.18092 against prevDayPx 0.1827.
    (is (= "−0.2 pts" (:change-text model)))
    (is (= :down (:change-direction model))))
  (let [untraded (-> (market-by-key "outcome:27037")
                     (assoc-in [:outcome-sides 0 :prevDayRaw] "0.0"))]
    (is (= "—" (:change-text (header-vm/header-model untraded nil now-ms)))
        "a seed prevDayPx of 0 is not a reference")))

(deftest header-reads-the-selected-rung-test
  (let [ladder (market-by-key touch-key)
        rung (last (:question-options ladder))
        active (markets/market-with-selected-outcome-coin ladder (:yes-coin rung))
        model (header-vm/header-model active nil now-ms)]
    (is (= "out" (:venue model)))
    (is (re-find #"\$65,000" (:rules model)))
    (is (= "Ends" (:clock-heading model)))))

(deftest pick-one-rows-keep-match-order-and-fields-rank-by-chance-test
  (let [sections (listing/sections (built-markets) now-ms utc)
        match (row-by-key sections "question:614")
        field (row-by-key sections "question:430")
        fed (row-by-key sections "question:545")]
    (is (= ["Italy" "Draw" "France"] (mapv :label (:answers match))))
    (is (= 3 (count (:answers field))))
    (is (= 5 (:more field)))
    (is (apply >= (map #(or (get-in % [:quote :chance]) -1) (:answers field))))
    (is (= #{"Hike" "Hold" "Cut"} (set (map :label (:answers fed)))))))

(deftest exclusive-answers-past-105-percent-are-flagged-in-the-list-test
  (let [fed (market-by-key "question:545")
        books {"#199900" (book 0.79 0.81)
               "#199880" (book 0.26 0.28)
               "#199890" (book 0.01 0.03)}
        row (listing/row fed now-ms (assoc utc :books books))]
    (is (= [{:kind :sum :text "Adds up to 109%"}] (:flags row)))
    (is (empty? (:flags (listing/row fed now-ms utc))) "nothing to add up without midpoints")))

(deftest ladder-rows-prefer-quoted-rungs-test
  (let [ladder (market-by-key touch-key)
        [r90 r85 r70 r65] (:question-options ladder)
        books {(:yes-coin r85) (book 0.17 0.19)
               (:yes-coin r70) (book 0.47 0.49)}
        row (listing/row ladder now-ms (assoc utc :books books))]
    (testing "without a live price: quoted rungs lead, shown in strike order"
      (is (= 3 (count (:answers row))))
      (is (= 1 (:more row)))
      (is (= ["$85,000" "$70,000"] (filterv #{"$85,000" "$70,000"} (mapv :label (:answers row))))))
    (testing "never-traded placeholder rungs trail traded ones"
      (let [untraded (assoc-in ladder [:question-options 0 :sides 0]
                               (merge (get-in ladder [:question-options 0 :sides 0])
                                      {:prevDayRaw "0.0" :volume24h 0 :baseVolume24h 0 :midRaw "0.5"}))
            row* (listing/row untraded now-ms (assoc utc :books books))]
        (is (not-any? #{"$90,000"} (mapv :label (:answers row*))))))
    (is (some? r90))
    (is (some? r65))))

(deftest touch-ladder-rows-show-direction-test
  (let [ladder (market-by-key touch-key)
        catalog {"perp:BTC" {:key "perp:BTC" :mark 74940}}
        row (listing/row ladder now-ms (assoc utc :market-by-key catalog))]
    (is (= ["Rises to $85,000" "Falls to $70,000"] (mapv :label (:answers row))))
    (is (= [:up :down] (mapv :direction (:answers row))))
    (is (= 2 (:more row)))))

(deftest ladder-out-of-order-rungs-are-flagged-in-the-list-test
  (let [ladder (market-by-key touch-key)
        [r90 r85 _ _] (:question-options ladder)
        books {(:yes-coin r90) (book 0.30 0.31)
               (:yes-coin r85) (book 0.17 0.18)}
        catalog {"perp:BTC" {:key "perp:BTC" :mark 74940}}
        row (listing/row ladder now-ms (assoc utc :books books :market-by-key catalog))]
    (is (= [{:kind :order :text "Out of order"}] (:flags row)))))

(deftest answers-without-context-are-unknown-not-untraded-test
  (let [row (row-by-key (listing/sections (built-markets) now-ms utc) "outcome:99999")]
    (is (= ["—" "—"] (mapv :text (:answers row))))))

(deftest chip-counts-and-subscriptions-test
  (let [markets (built-markets)
        sections (listing/sections markets now-ms utc)
        coins (listing/visible-coins sections)]
    (is (= {:outcome 15
            :outcome-sports 3
            :outcome-crypto 5
            :outcome-stocks 1
            :outcome-economics 1
            :outcome-companies 4}
           (listing/chip-counts markets)))
    (testing "side 1 mirrors side 0, so only side-0 coins are subscribed"
      (is (contains? coins "#270370"))
      (is (not (contains? coins "#270371"))))
    (testing "visible keys from the view replace the first-30 fallback"
      (is (= #{"#270370"} (listing/visible-coins sections ["outcome:27037"])))
      (is (= coins (listing/visible-coins sections []))))))

(deftest load-status-test
  (testing "a failed load is an error with a retry, not an empty list"
    (let [status (listing/load-status {:loading? false :phase :bootstrap :error "Network error"} now-ms)]
      (is (= :error (:status status)))
      (is (= [:actions/refresh-asset-markets] (:refresh-action status)))))
  (testing "a loaded list says how old it is and when it is stale"
    (let [fresh (listing/load-status {:phase :full :loaded-at-ms (- now-ms 120000)} now-ms)
          old (listing/load-status {:phase :full :loaded-at-ms (- now-ms (* 7 60000))} now-ms)]
      (is (= :ready (:status fresh)))
      (is (= "Updated 2 min ago" (:updated-text fresh)))
      (is (false? (:stale? fresh)))
      (is (true? (:stale? old)))))
  (is (= :loading (:status (listing/load-status {:loading? true :phase :full} now-ms)))))

(deftest list-actions-test
  (is (= [[:effects/save-many [[[:asset-selector :outcome-sort] :ending-soon]
                               [[:asset-selector :highlighted-market-key] nil]]]
          [:effects/sync-asset-selector-active-ctx-subscriptions]]
         (listing/set-outcome-list-sort {} :ending-soon)))
  (is (= [] (listing/set-outcome-list-sort {:asset-selector {:outcome-sort :most-traded}} "most-traded")))
  (is (= [[:effects/save [:asset-selector :outcome-visible-keys] ["a" "b"]]
          [:effects/sync-asset-selector-active-ctx-subscriptions]]
         (listing/set-outcome-visible-event-keys {} ["a" "b" "a" nil])))
  (is (= [] (listing/set-outcome-visible-event-keys {:asset-selector {:outcome-visible-keys ["a"]}} ["a"]))))

(deftest day-labels-test
  (is (= "Today" (listing/day-label (js/Date.UTC 2026 10 9 23 0) now-ms utc)))
  (is (= "Tomorrow" (listing/day-label (js/Date.UTC 2026 10 10 1 0) now-ms utc)))
  (is (= "Earlier" (listing/day-label (js/Date.UTC 2026 10 8 1 0) now-ms utc)))
  (is (= "Thu, Nov 12" (listing/day-label (js/Date.UTC 2026 10 12 19 45) now-ms utc))))

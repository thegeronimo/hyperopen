(ns hyperopen.websocket.outcome-bbo-write-rate-test
  "Regression guard for the outcome-market lag: a busy `bbo` feed must not
   write the store per message, a quote that changes nothing must not write
   at all, and a quote for a contract the ticket does not show must leave the
   ticket, header and account panels' inputs untouched."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.asset-selector.market-index :as market-index]
            [hyperopen.asset-selector.market-live-projection :as projection]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.views.account-equity.metrics :as metrics]
            [hyperopen.views.account-info.derived-cache :as derived-cache]
            [hyperopen.views.account-info.projections :as account-projections]
            [hyperopen.views.account-info.projections.outcome-contexts :as outcome-contexts]
            [hyperopen.views.account-info.projections.outcomes :as outcome-projections]
            [hyperopen.views.active-asset.vm :as active-asset-vm]
            [hyperopen.views.trade-view]
            [hyperopen.views.trade-view.market-lookup :as market-lookup]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.websocket.market-projection-runtime :as market-projection-runtime]
            [hyperopen.websocket.outcome-bbo :as outcome-bbo]))

(defn- contract
  [id]
  {:key (str "outcome:" id) :coin (str "#" (* 10 id)) :market-type :outcome :title (str "Event " id)
   :outcome-sides [{:coin (str "#" (* 10 id)) :side-index 0 :side-name "Yes"}
                   {:coin (str "#" (inc (* 10 id))) :side-index 1 :side-name "No"}]})

(def ^:private question
  {:key "question:9" :coin "#10" :market-type :outcome :title "Albania vs Finland"
   :outcome-subscription-coins ["#10" "#11" "#20" "#21" "#30" "#31"]
   :outcome-sides (:outcome-sides (contract 1))
   :question-options [{:outcome-id 1 :label "Albania" :sides (:outcome-sides (contract 1))}
                      {:outcome-id 2 :label "Draw" :sides (:outcome-sides (contract 2))}
                      {:outcome-id 3 :label "Finland" :sides (:outcome-sides (contract 3))}]})

(def ^:private spot
  {:key "spot:@1" :coin "@1" :market-type :spot :base "HYPE" :quote "USDC" :mark 30})

(defn- world
  []
  (let [markets [question (contract 5) (contract 6) spot {:key "perp:BTC" :coin "BTC" :market-type :perp}]]
    {:active-asset "#10"
     :active-market question
     :asset-selector {:phase :full
                      :markets markets
                      :market-by-key (into {} (map (juxt :key identity)) markets)
                      :market-index-by-key (into {} (map-indexed (fn [i m] [(:key m) i])) markets)}}))

(defn- bbo
  [coin bid ask]
  {:channel "bbo" :data {:coin coin :time 1 :bbo [{:px bid :sz "1" :n 1} {:px ask :sz "1" :n 1}]}})

(deftest an-unchanged-quote-writes-nothing-test
  (let [state (projection/apply-outcome-top-of-book-updates (world) {"#50" {:bid "0.40" :ask "0.42"}
                                                                     "#20" {:bid "0.30" :ask "0.33"}})]
    (is (identical? state (projection/apply-outcome-top-of-book-updates state {"#50" {:bid "0.40" :ask "0.42"}}))
        "the same top again returns the very same state: no store write, no render")
    (is (identical? state (projection/apply-outcome-top-of-book-updates state {"#50" {:bid 0.4 :ask 0.42}
                                                                               "#20" {:bid "0.300" :ask "0.33"}}))
        "equal prices spelled differently change nothing either")
    (is (identical? state (projection/apply-outcome-top-of-book-updates state {"#999" {:bid "0.5" :ask "0.6"}}))
        "a coin no market holds changes nothing")))

(deftest a-batch-writes-every-market-holding-the-coin-in-one-pass-test
  (let [both (-> (world)
                 (assoc-in [:asset-selector :market-by-key "outcome:1"] (contract 1))
                 (update-in [:asset-selector :markets] conj (contract 1))
                 (assoc-in [:asset-selector :market-index-by-key "outcome:1"] 5))
        state (projection/apply-outcome-top-of-book-updates both {"#10" {:bid "0.6" :ask "0.62"}
                                                                  "#30" {:bid "0.2" :ask "0.22"}})]
    (is (= 0.6 (get-in state [:asset-selector :market-by-key "outcome:1" :outcome-sides 0 :bid])))
    (is (= 0.6 (get-in state [:asset-selector :market-by-key "question:9" :question-options 0 :sides 0 :bid])))
    (is (= 0.2 (get-in state [:asset-selector :market-by-key "question:9" :question-options 2 :sides 0 :bid])))
    (is (= (get-in state [:asset-selector :market-by-key "question:9"])
           (get-in state [:asset-selector :markets 0]))
        "the list vector and the by-key map stay in step")
    (is (= (get-in state [:asset-selector :market-by-key "question:9" :question-options])
           (get-in state [:active-market :question-options]))
        "header and ticket (active market) read the same book as the list")))

(deftest many-messages-in-one-window-make-one-store-write-test
  (outcome-bbo/reset-runtime!)
  (market-projection-runtime/reset-market-projection-runtime!)
  (let [store (atom (world))
        writes (atom 0)
        flushes (atom [])
        frames (atom [])
        handler (outcome-bbo/create-bbo-data-handler store)]
    (add-watch store ::count (fn [_ _ old new] (when-not (identical? old new) (swap! writes inc))))
    (outcome-bbo/register-hooks! {:resync! (fn [] nil)
                                  :now-ms (fn [] 1000)
                                  :schedule-flush! (fn [f delay-ms] (swap! flushes conj [f delay-ms]) :timer)
                                  :schedule-frame! (fn [f] (swap! frames conj f) :frame)})
    (doseq [[coin bid ask] [["#10" "0.50" "0.52"] ["#20" "0.20" "0.22"] ["#30" "0.25" "0.27"]
                            ["#10" "0.51" "0.53"] ["#50" "0.70" "0.72"] ["#10" "0.51" "0.53"]]]
      (handler (bbo coin bid ask)))
    (is (= 1 (count @flushes)) "six messages schedule one flush")
    (is (= 0 (second (first @flushes))) "the first quote after a quiet spell goes out at once")
    (is (zero? @writes) "nothing is written before the flush")
    ((ffirst @flushes))
    (doseq [frame @frames] (frame 0))
    (is (= 1 @writes) "one store write for the whole batch")
    (is (= 0.51 (get-in @store [:active-market :question-options 0 :sides 0 :bid])) "the latest top wins")
    (is (= 0.7 (get-in @store [:asset-selector :market-by-key "outcome:5" :outcome-sides 0 :bid])))
    (testing "the next batch waits out the write interval"
      (handler (bbo "#20" "0.21" "0.23"))
      (is (= outcome-bbo/write-interval-ms (second (second @flushes))))
      (is (<= outcome-bbo/write-interval-ms 250) "at most four top-of-book writes a second"))
    (testing "a repeat of what is already written produces no write"
      ((first (second @flushes)))
      (doseq [frame (drop 1 @frames)] (frame 0))
      (let [before @writes]
        (handler (bbo "#50" "0.70" "0.72"))
        ((first (nth @flushes 2)))
        (doseq [frame (drop 2 @frames)] (frame 0))
        (is (= before @writes))))
    (remove-watch store ::count))
  (outcome-bbo/register-hooks! {:schedule-flush! nil :schedule-frame! nil :now-ms nil :resync! nil})
  (outcome-bbo/reset-runtime!)
  (market-projection-runtime/reset-market-projection-runtime!))

(deftest an-unrelated-quote-leaves-ticket-header-and-account-inputs-alone-test
  (market-lookup/reset-cache!)
  (let [s0 (projection/apply-outcome-top-of-book-updates (world) {"#10" {:bid "0.5" :ask "0.52"}})
        catalog0 (market-lookup/stable-market-by-key (get-in s0 [:asset-selector :market-by-key]))
        s1 (projection/apply-outcome-top-of-book-updates s0 {"#50" {:bid "0.7" :ask "0.72"}})]
    (is (not (identical? (get-in s0 [:asset-selector :market-by-key])
                         (get-in s1 [:asset-selector :market-by-key])))
        "the list's catalog does take the quote")
    (is (identical? (:active-market s0) (:active-market s1))
        "the ticket and header read the active market: same object, nothing to recompute")
    (is (= (active-asset-vm/panel-dependency-state s0) (active-asset-vm/panel-dependency-state s1))
        "the header's memoized panel input is unchanged")
    (is (identical? catalog0 (market-lookup/stable-market-by-key (get-in s1 [:asset-selector :market-by-key])))
        "account panels and the chart keep the same catalog object across a quote batch")
    (testing "a change other than a quote reaches the account panels"
      (let [s2 (assoc-in s1 [:asset-selector :market-by-key "outcome:5" :title] "Renamed")]
        (is (identical? (get-in s2 [:asset-selector :market-by-key])
                        (market-lookup/stable-market-by-key (get-in s2 [:asset-selector :market-by-key])))))))
  (market-lookup/reset-cache!))

(deftest balance-rows-survive-non-spot-catalog-writes-test
  (let [catalog (get-in (world) [:asset-selector :market-by-key])
        ticked (assoc-in catalog ["outcome:5" :outcome-sides 0 :bid] 0.4)]
    (is (true? (derived-cache/same-spot-markets? catalog ticked)))
    (is (false? (derived-cache/same-spot-markets? catalog (assoc-in catalog ["spot:@1" :mark] 31))))
    (is (false? (derived-cache/same-spot-markets? catalog (dissoc catalog "spot:@1"))))))

(deftest indexed-lookups-agree-with-the-scans-test
  (let [catalog (assoc (get-in (world) [:asset-selector :market-by-key])
                       "spot:@2" {:key "spot:@2" :coin "@2" :market-type :spot :base "HYPE" :quote "USDH"}
                       "spot:@3" {:key "spot:@3" :coin "@3" :market-type :spot :base "PURR" :quote "USDC"})
        index (markets/spot-market-base-index catalog)]
    (doseq [coin ["HYPE" "hype" "PURR" "NOPE" "@1" "BTC"]]
      (is (= (markets/resolve-spot-market-by-coin catalog coin)
             (markets/resolve-spot-market-by-coin catalog coin index))
          coin))
    (is (= "@1" (:coin (markets/resolve-spot-market-by-coin catalog "HYPE" index))) "USDC-quoted first")
    (is (= #{"outcome:5"} (set (map :key (get (market-index/build-outcome-token-index catalog) "#51")))))
    (is (= "question:9" (:key (markets/resolve-market-by-coin catalog "#30"))))
    (is (= (markets/resolve-market-by-coin catalog "#31")
           (some #(when (markets/market-matches-coin? % "#31")
                    (markets/market-with-selected-outcome-coin % "#31"))
                 (vals catalog)))
        "the index finds what the scan finds")
    (is (= "outcome:6" (:key (markets/resolve-market-by-coin catalog "#61"))))))

(deftest held-sides-only-are-looked-up-test
  (let [catalog (get-in (world) [:asset-selector :market-by-key])
        lookup (outcome-projections/outcome-side-lookup catalog {:coins #{"#51"}})]
    (is (= #{"#51" "+51"} (set (keys lookup))))
    (is (= "Event 5" (get-in lookup ["#51" :market-title])))
    (is (= [] (outcome-projections/build-outcome-rows {:clearinghouse-state {:balances [{:coin "USDC" :total "5"}]}}
                                                       catalog))
        "no outcome shares held: nothing to build")))

(defn- holder-state
  "The world with 10 No shares of Event 5 (#51) held, a book on #51 and
   some perp contexts; the catalog as /trade hands it to Account Equity."
  []
  (-> (world)
      (assoc :spot {:clearinghouse-state {:balances [{:coin "USDC" :total "5"}
                                                     {:coin "+51" :total "10" :hold "0" :entryNtl "4"}]}}
             :orderbooks {"#51" {:bids [{:px "0.4" :sz "100"}] :asks [{:px "0.45" :sz "100"}]}
                          "BTC" {:bids [{:px "100" :sz "1"}]}}
             :asset-contexts {:BTC {:coin "BTC" :mark 100}}
             :active-assets {:contexts {"BTC" {:coin "BTC" :mark 100}}})))

(defn- as-trade-view-sees
  [state]
  (assoc-in state [:asset-selector :market-by-key]
            (market-lookup/stable-market-by-key (get-in state [:asset-selector :market-by-key]))))

(deftest outcome-shares-cost-pointer-compares-on-unrelated-writes-test
  (metrics/reset-account-equity-metrics-cache!)
  (outcome-contexts/reset-cache!)
  (market-lookup/reset-cache!)
  (let [walks (atom 0)
        builds (atom 0)
        real-index outcome-contexts/outcome-alias-index
        real-build account-projections/build-outcome-rows]
    (with-redefs [outcome-contexts/outcome-alias-index (fn [contexts] (swap! walks inc) (real-index contexts))
                  account-projections/build-outcome-rows (fn [& args] (swap! builds inc) (apply real-build args))]
      (let [s0 (holder-state)
            v0 (metrics/account-equity-outcome-shares (as-trade-view-sees s0))
            walks0 @walks
            ;; a perp book tick, an unrelated key and a quote batch for the open event
            s1 (-> s0
                   (assoc-in [:orderbooks "BTC" :bids 0 :px] "101")
                   (assoc :unrelated 1)
                   (projection/apply-outcome-top-of-book-updates {"#10" {:bid "0.5" :ask "0.52"}}))
            v1 (metrics/account-equity-outcome-shares (as-trade-view-sees s1))]
        (is (= 4 v0) "10 shares at the 0.40 bid")
        (is (identical? v0 v1))
        (is (= 1 @builds) "the rows are not rebuilt")
        (is (= walks0 @walks) "no context is walked again")
        (metrics/account-equity-outcome-shares (as-trade-view-sees s1))
        (is (= [1 walks0] [@builds @walks]))
        (testing "the held side's book moving does re-value"
          (is (= 5 (metrics/account-equity-outcome-shares
                    (as-trade-view-sees (assoc-in s1 [:orderbooks "#51" :bids 0 :px] "0.5")))))
          (is (= 2 @builds))))))
  (metrics/reset-account-equity-metrics-cache!)
  (market-lookup/reset-cache!))

(deftest chart-active-market-is-stable-across-quotes-test
  (market-lookup/reset-cache!)
  (let [chart-state #'hyperopen.views.trade-view/trade-chart-view-state
        s0 (projection/apply-outcome-top-of-book-updates (world) {"#10" {:bid "0.5" :ask "0.52"}})
        s1 (projection/apply-outcome-top-of-book-updates s0 {"#10" {:bid "0.6" :ask "0.62"}})
        m0 (:active-market (chart-state s0))]
    (is (not (identical? (:active-market s0) (:active-market s1))) "header and ticket get the new quote")
    (is (identical? m0 (:active-market (chart-state s1))) "the chart keeps the same object")
    (let [s2 (assoc-in s1 [:active-market :display-name] "Renamed")]
      (is (= "Renamed" (:display-name (:active-market (chart-state s2))))
          "a change other than a quote reaches the chart")))
  (market-lookup/reset-cache!))

(deftest both-sides-in-one-batch-do-not-depend-on-order-test
  (let [yes {:bid "0.40" :ask "0.42"}
        no {:bid "0.55" :ask "0.61"}
        side (fn [state i] (get-in state [:asset-selector :market-by-key "outcome:5" :outcome-sides i]))
        a (projection/apply-outcome-top-of-book-updates (world) (array-map "#50" yes "#51" no))
        b (projection/apply-outcome-top-of-book-updates (world) (array-map "#51" no "#50" yes))]
    (is (= (side a 0) (side b 0)))
    (is (= (side a 1) (side b 1)))
    (is (= [0.4 0.42] ((juxt :bid :ask) (side a 0))) "each side keeps its own quote")
    (is (= [0.55 0.61] ((juxt :bid :ask) (side a 1))))))

(deftest a-catalog-reload-keeps-known-quotes-test
  (outcome-bbo/reset-runtime!)
  (market-projection-runtime/reset-market-projection-runtime!)
  (let [store (atom (world))
        flushes (atom [])
        frames (atom [])
        drain! (fn []
                 (let [fs @flushes] (reset! flushes []) (doseq [[f] fs] (f)))
                 (let [fs @frames] (reset! frames []) (doseq [f fs] (f 0))))
        handler (outcome-bbo/create-bbo-data-handler store)
        quote-of (fn [i] ((juxt :bid :ask) (get-in @store [:asset-selector :market-by-key "outcome:5" :outcome-sides i])))]
    (outcome-bbo/register-hooks! {:store store
                                  :resync! nil
                                  :request-snapshot! nil
                                  :now-ms (fn [] 1000)
                                  :schedule-flush! (fn [f delay-ms] (swap! flushes conj [f delay-ms]) :timer)
                                  :schedule-frame! (fn [f] (swap! frames conj f) :frame)})
    (with-redefs [ws-client/send-message! (fn [_] nil)]
      (outcome-bbo/sync-subscriptions! ["#50"] @store)
      (handler (bbo "#50" "0.40" "0.42"))
      (drain!)
      (is (= [0.4 0.42] (quote-of 0)))
      (testing "the sync after a reload re-applies the known top, no network"
        (swap! store assoc :asset-selector (:asset-selector (world)) :active-market question)
        (is (= [nil nil] (quote-of 0)) "the reloaded catalog carries no quote")
        (outcome-bbo/sync-subscriptions! ["#50"] @store)
        (drain!)
        (is (= [0.4 0.42] (quote-of 0)))
        (is (< (js/Math.abs (- 0.58 (first (quote-of 1)))) 1e-9) "the sibling gets the mirror"))
      (testing "so does a load that runs no sync"
        (swap! store assoc :asset-selector (:asset-selector (world)))
        (outcome-bbo/restore-known-tops! store)
        (drain!)
        (is (= [0.4 0.42] (quote-of 0))))
      (testing "a quoted catalog queues nothing"
        (outcome-bbo/sync-subscriptions! ["#50"] @store)
        (is (empty? @flushes)))))
  (outcome-bbo/register-hooks! {:store nil :schedule-flush! nil :schedule-frame! nil :now-ms nil :resync! nil})
  (outcome-bbo/reset-runtime!)
  (market-projection-runtime/reset-market-projection-runtime!))

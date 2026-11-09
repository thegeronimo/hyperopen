(ns hyperopen.portfolio.optimizer.application.universe-duplicates-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.application.universe-duplicates :as dup]))

(defn- hip3-perp
  [dex base quote]
  {:key (str "perp:" dex ":" base) :market-type :perp :coin (str dex ":" base)
   :dex dex :symbol (str base "-" quote) :base base :quote quote})

(def ^:private nvda-xyz (hip3-perp "xyz" "NVDA" "USDC"))
(def ^:private nvda-flx (hip3-perp "flx" "NVDA" "USDH"))
(def ^:private nvdl-xyz (hip3-perp "xyz" "NVDL" "USDC"))

(defn- held
  ([market] (held market :long))
  ([market side]
   {:instrument-id (:key market) :market-type (:market-type market)
    :coin (:coin market) :dex (:dex market) :symbol (:symbol market)
    :base (:base market) :quote (:quote market) :position-side side}))

(deftest underlying-key-drops-venue-and-quote-test
  (is (= "NVDA" (dup/underlying-key nvda-xyz)))
  (testing "falls back to the symbol, then the coin, stripping prefix and quote"
    (is (= "NVDA" (dup/underlying-key {:market-type :spot :symbol "NVDA/USDC"})))
    (is (= "NVDA" (dup/underlying-key {:market-type :perp :coin "km:NVDA"}))))
  (testing "vaults and bare raw spot ids never match"
    (is (nil? (dup/underlying-key {:market-type :vault :name "NVDA fund"})))
    (is (nil? (dup/underlying-key {:market-type :spot :coin "@142"})))))

(deftest existing-sibling-matches-same-underlying-only-test
  (let [index (dup/universe-index [(held nvda-xyz)])]
    (is (= "perp:xyz:NVDA" (:instrument-id (dup/existing-sibling index nvda-flx))))
    (testing "prefix overlap is not a match"
      (is (nil? (dup/existing-sibling index nvdl-xyz))))
    (testing "the held listing itself is not its own sibling"
      (is (nil? (dup/existing-sibling index nvda-xyz))))))

(deftest needs-confirm-is-asked-once-per-underlying-test
  (let [universe [(held nvda-xyz)]]
    (is (true? (dup/needs-confirm? {} universe nvda-flx)))
    (is (false? (dup/needs-confirm?
                 {:portfolio-ui {:optimizer {:universe-duplicate-acknowledged ["NVDA"]}}}
                 universe nvda-flx)))))

(deftest annotate-candidate-rows-tags-twins-and-opens-the-prompt-test
  (let [state {:portfolio-ui {:optimizer {:universe-duplicate-prompt "perp:flx:NVDA"}}}
        rows (dup/annotate-candidate-rows
              state
              [(held nvda-xyz)]
              [{:market nvda-flx :market-key (:key nvda-flx)}
               {:market nvdl-xyz :market-key (:key nvdl-xyz)}])
        [flx nvdl] rows]
    (is (= "NVDA-USDC · xyz" (:duplicate-of-label flx)))
    (is (= {:market-key "perp:flx:NVDA"
            :existing-id "perp:xyz:NVDA"
            :existing-label "NVDA-USDC · xyz"
            :existing-symbol "NVDA-USDC"
            :adding-label "NVDA-USDH · flx"
            :adding-symbol "NVDA-USDH"
            :existing-venue "xyz"
            :adding-venue "flx"
            :existing-quote "USDC"
            :adding-quote "USDH"}
           (:duplicate-prompt flx)))
    (is (nil? (:duplicate-of-label nvdl)))
    (is (= {:existing [{:existing-id "perp:xyz:NVDA"
                        :existing-label "NVDA-USDC · xyz"
                        :underlying "NVDA"}]
            :existing-ids #{"perp:xyz:NVDA"}
            :duplicate-row-count 1}
           (dup/search-summary rows)))
    (is (nil? (dup/search-summary [{:market nvdl-xyz}])))))

(deftest addable-market-keys-skips-mix-twins-and-in-batch-twins-test
  (let [cash (hip3-perp "cash" "NVDA" "USDT0")
        rows [{:market nvda-flx :market-key (:key nvda-flx) :duplicate-of-id "x"}
              {:market nvdl-xyz :market-key (:key nvdl-xyz)}
              {:market cash :market-key (:key cash)}
              {:market (hip3-perp "km" "NVDL" "USDH") :market-key "perp:km:NVDL"}
              {:market {:market-type :vault} :market-key "vault:0x1"}]]
    (is (= ["perp:xyz:NVDL" "perp:cash:NVDA" "vault:0x1"]
           (dup/addable-market-keys rows))))
  (is (= " · skips 2 duplicates" (dup/skipped-suffix 2)))
  (is (nil? (dup/skipped-suffix 0))))

(deftest group-selected-rows-pulls-twins-together-test
  (let [universe [(held nvda-xyz) {:instrument-id "perp:SOL" :market-type :perp :coin "SOL"}
                  (held nvda-flx)]
        twins (dup/twin-groups universe)
        rows (dup/group-selected-rows
              (mapv (fn [i] {:instrument-id (:instrument-id i)}) universe)
              twins)]
    (is (= {"NVDA" ["perp:xyz:NVDA" "perp:flx:NVDA"]} twins))
    (is (= ["perp:xyz:NVDA" "perp:flx:NVDA" "perp:SOL"] (mapv :instrument-id rows)))
    (is (= [true false nil] (mapv :twin-first? rows)))
    (is (= [false true nil] (mapv :twin-last? rows)))
    (is (= [2 2 nil] (mapv :twin-count rows)))))

(deftest duplicate-warnings-ignore-hedges-test
  (is (= [{:code :duplicate-underlying
           :instrument-id "perp:flx:NVDA"
           :underlying "NVDA"
           :message "NVDA is in the universe 2 times (NVDA-USDC, NVDA-USDH)"}]
         (dup/duplicate-warnings [(held nvda-xyz) (held nvda-flx)])))
  (testing "long one listing, short the other is a deliberate hedge"
    (is (= [] (dup/duplicate-warnings [(held nvda-xyz :long) (held nvda-flx :short)])))))

(ns hyperopen.portfolio.optimizer.application.universe-search-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.application.universe-candidates :as universe-candidates]
            [hyperopen.portfolio.optimizer.application.view-model.universe-search :as universe-search]))

(def ^:private pump-perp
  {:key "perp:PUMP" :market-type :perp :coin "PUMP" :symbol "PUMP-USDC"
   :base "PUMP" :quote "USDC" :volume24h 412600000})

(def ^:private pump-spot-usdc
  {:key "spot:PUMP/USDC" :market-type :spot :coin "PUMP/USDC"
   :symbol "PUMP/USDC" :base "PUMP" :quote "USDC" :volume24h 96400000})

(def ^:private pump-spot-usde
  {:key "spot:PUMP/USDE" :market-type :spot :coin "PUMP/USDE"
   :symbol "PUMP/USDE" :base "PUMP" :quote "USDE" :volume24h 31800000})

(def ^:private pump-spot-usdh
  {:key "spot:PUMP/USDH" :market-type :spot :coin "PUMP/USDH"
   :symbol "PUMP/USDH" :base "PUMP" :quote "USDH" :volume24h 7200000})

(def ^:private pump-vault
  {:key "vault:0xabc" :market-type :vault :coin "vault:0xabc"
   :name "Pump Hunt" :symbol "Pump Hunt" :vault-address "0xabc" :tvl 8400000})

(def ^:private markets
  [pump-perp pump-spot-usdc pump-spot-usde pump-spot-usdh pump-vault])

(deftest market-quote-reads-the-field-and-never-invents-one-for-vaults-test
  (testing "the real :quote field is preferred for perps and spot"
    (is (= "USDC" (universe-candidates/market-quote pump-perp)))
    (is (= "USDE" (universe-candidates/market-quote pump-spot-usde))))
  (testing "the separator differs by market type when falling back to the symbol"
    (is (= "USDC" (universe-candidates/market-quote
                   {:market-type :perp :symbol "BTC-USDC"})))
    (is (= "USDH" (universe-candidates/market-quote
                   {:market-type :spot :symbol "HYPE/USDH"}))))
  (testing "a vault has no quote, and must not be given a fabricated one"
    ;; A vault candidate is seven keys with no :quote and no :base. A "USDC"
    ;; fallback would file every vault under the USDC facet.
    (is (nil? (universe-candidates/market-quote pump-vault)))))

(deftest type-chip-counts-are-taken-before-the-type-filter-test
  ;; The chips must keep saying what is AVAILABLE. If they counted the filtered
  ;; set, selecting PERP would collapse every other chip to zero and there would
  ;; be no way to see that spot rows exist.
  (let [chips (universe-search/type-chips markets :perp)
        by-key (into {} (map (juxt :key identity)) chips)]
    (is (= 5 (:count (:all by-key))))
    (is (= 1 (:count (:perp by-key))))
    (is (= 3 (:count (:spot by-key))))
    (is (= 1 (:count (:vault by-key))))
    (is (true? (:active? (:perp by-key))))
    (is (false? (:active? (:all by-key))))))

(deftest quote-chips-are-derived-from-the-data-not-hard-coded-test
  (testing "every quote present in the matches gets a chip, most populated first"
    (let [chips (universe-search/quote-chips markets :all :all)]
      (is (= ["Any" "USDC" "USDE" "USDH"] (mapv :label chips)))
      (is (= 2 (:count (second chips))))))
  (testing "a HIP-3 collateral token nobody hard-coded still gets a chip"
    (let [chips (universe-search/quote-chips
                 [{:market-type :perp :quote "USDT0" :key "perp:x"}]
                 :all
                 :all)]
      (is (some #(= "USDT0" (:label %)) chips))))
  (testing "counts follow the active type filter"
    (let [chips (universe-search/quote-chips markets :spot :all)
          by-label (into {} (map (juxt :label :count)) chips)]
      (is (= 1 (get by-label "USDC")))
      (is (= 3 (get by-label "Any")))))
  (testing "vaults contribute no quote chip at all"
    (is (empty? (remove #(= :all (:key %))
                        (universe-search/quote-chips [pump-vault] :all :all))))))

(deftest filter-markets-applies-facets-and-groups-in-render-order-test
  (testing "grouping puts perps first, then spot, then vaults"
    (is (= ["perp:PUMP" "spot:PUMP/USDC" "spot:PUMP/USDE" "spot:PUMP/USDH"
            "vault:0xabc"]
           (mapv :key (universe-search/filter-markets markets :all :all true)))))
  (testing "ungrouped preserves the incoming rank order for the shared popover"
    (is (= (mapv :key markets)
           (mapv :key (universe-search/filter-markets markets :all :all false)))))
  (testing "the quote facet narrows to one row"
    (is (= ["spot:PUMP/USDE"]
           (mapv :key (universe-search/filter-markets markets :all "USDE" true)))))
  (testing "the type facet excludes vaults from a quote-filtered set"
    (is (= ["perp:PUMP"]
           (mapv :key (universe-search/filter-markets markets :perp "USDC" true))))))

(deftest highlight-splits-around-the-first-case-insensitive-match-test
  (is (= {:pre "" :match "PUMP" :post "-"}
         (universe-search/highlight "PUMP-" "pump")))
  (is (= {:pre "Hype" :match "rliquid" :post ""}
         (universe-search/highlight "Hyperliquid" "rliquid")))
  (testing "a field that does not contain the query still renders in full"
    ;; The candidate filter searches several fields, so a visible row need not
    ;; contain the query in either RENDERED field. Losing the text would blank
    ;; the row.
    (is (= {:pre "Bitcoin" :match "" :post ""}
           (universe-search/highlight "Bitcoin" "pump"))))
  (testing "a blank query highlights nothing and keeps the text"
    (is (= {:pre "Bitcoin" :match "" :post ""}
           (universe-search/highlight "Bitcoin" "")))))

(deftest row-search-fields-keeps-the-separator-on-the-symbol-lead-test
  ;; The quote token renders as its own pill. The separator must stay on the
  ;; lead so the row's text content still concatenates to the whole symbol.
  (let [perp (universe-search/row-search-fields
              {:market pump-perp :label "PUMP-USDC" :name "PUMP"}
              "pump")
        spot (universe-search/row-search-fields
              {:market pump-spot-usde :label "PUMP/USDE" :name "PUMP"}
              "pump")
        vault (universe-search/row-search-fields
               {:market pump-vault :label "Pump Hunt" :name "Pump Hunt"}
               "pump")]
    (is (= "USDC" (:quote-label perp)))
    (is (= {:pre "" :match "PUMP" :post "-"} (:symbol-segments perp)))
    (is (= "USDE" (:quote-label spot)))
    (is (= {:pre "" :match "PUMP" :post "/"} (:symbol-segments spot)))
    (testing "a vault has no pill and keeps its whole name"
      (is (nil? (:quote-label vault)))
      (is (= {:pre "" :match "Pump" :post " Hunt"} (:symbol-segments vault))))))

(deftest labels-state-the-truncation-instead-of-hiding-it-test
  (is (= "13 hits" (universe-search/match-label 13)))
  (is (= "1 hit" (universe-search/match-label 1)))
  (testing "the footer says how many of how many, and stays quiet when equal"
    (is (= "6 of 13 matches shown" (universe-search/footer-label 6 13)))
    (is (= "13 matches" (universe-search/footer-label 13 13)))
    (is (= "1 match" (universe-search/footer-label 1 1))))
  (is (= "2 markets" (universe-search/group-count-label 2)))
  (is (= "1 market" (universe-search/group-count-label 1))))

(deftest candidate-markets-carries-the-true-pre-limit-match-count-test
  ;; The footer promises "N of M matches shown". M cannot be read off the
  ;; returned vector — that vector is already capped — so it rides along as
  ;; metadata. Without it the panel would report "25 of 25" for a 27-match query
  ;; and re-hide exactly the truncation this work removes.
  (let [state {:asset-selector
               {:markets (mapv (fn [i]
                                 {:key (str "perp:PUMP" i)
                                  :market-type :perp
                                  :coin (str "PUMP" i)
                                  :symbol (str "PUMP" i "-USDC")
                                  :quote "USDC"
                                  :volume24h (- 100 i)})
                               (range 30))}}
        capped (universe-candidates/candidate-markets state [] "pump" {:limit 10})]
    (is (= 10 (count capped)))
    (is (= 30 (:total-match-count (meta capped))))
    (testing "an uncapped call still reports the same total"
      (is (= 30 (:total-match-count
                 (meta (universe-candidates/candidate-markets
                        state [] "pump" {:limit 200}))))))))

(deftest add-all-label-never-promises-an-unbounded-batch-test
  (is (nil? (universe-search/add-all-label 0)))
  (is (= "+ add all 3" (universe-search/add-all-label 3)))
  (is (= (str "+ add all " universe-search/add-all-limit)
         (universe-search/add-all-label universe-search/add-all-limit)))
  (testing "above the cap the label states what will actually happen"
    (is (= (str "+ add first " universe-search/add-all-limit)
           (universe-search/add-all-label 500)))))

(deftest filter-normalizers-tolerate-view-supplied-strings-test
  ;; The chips dispatch (name key), so every value arrives as a string.
  (is (= :perp (universe-search/normalize-type-filter "perp")))
  (is (= :all (universe-search/normalize-type-filter "all")))
  (is (= :all (universe-search/normalize-type-filter "not-a-type")))
  (is (= :all (universe-search/normalize-type-filter nil)))
  (is (= "USDC" (universe-search/normalize-quote-filter "usdc")))
  (is (= :all (universe-search/normalize-quote-filter "all")))
  (is (= :all (universe-search/normalize-quote-filter "   ")))
  (testing "the STORED default is the keyword :all, not a string"
    ;; Regression: the shared non-blank-text helper stringifies :all to ":all",
    ;; which normalized to a quote token of ":ALL" and filtered every row out —
    ;; an empty list under a "197 hits" count.
    (is (= :all (universe-search/normalize-quote-filter :all)))
    (is (= :all (universe-search/normalize-quote-filter nil)))
    (is (= "USDC" (universe-search/normalize-quote-filter :usdc)))))

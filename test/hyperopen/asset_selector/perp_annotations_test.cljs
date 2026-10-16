(ns hyperopen.asset-selector.perp-annotations-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.asset-selector.perp-annotations :as perp-annotations]
            [hyperopen.asset-selector.query :as query]))

(def ^:private payload
  ;; Shape of {"type" "perpConciseAnnotations"} after JSON keywordization.
  [["xyz:HO" {:category "commodities"
              :displayName "DIESEL"
              :keywords ["ho" "ULSD" " " "ho"]}]
   ["io:PRL" {:category "crypto" :displayName "PEARL" :keywords ["prl" "pearl"]}]
   ["flx:GOLD" {:category "commodities"}]
   ["bad-row"]
   [nil {:displayName "NOPE"}]
   ["km:EMPTY" {:displayName "  "}]])

(def ^:private diesel-market
  {:key "perp:xyz:HO" :coin "xyz:HO" :symbol "HO-USDC" :base "HO" :quote "USDC"
   :market-type :perp :dex "xyz" :category :tradfi :hip3? true})

(def ^:private pearl-market
  {:key "perp:io:PRL" :coin "io:PRL" :symbol "PRL-USDC" :base "PRL" :quote "USDC"
   :market-type :perp :dex "io" :category :tradfi :hip3? true})

(deftest normalize-perp-annotations-test
  (let [annotations (perp-annotations/normalize-perp-annotations payload)]
    (is (= {:category "commodities" :display-name "DIESEL" :keywords ["ho" "ulsd"]}
           (get annotations "xyz:HO")))
    (is (= {:category "commodities"} (get annotations "flx:GOLD")))
    (is (not (contains? annotations "km:EMPTY")))
    (is (= #{"xyz:HO" "io:PRL" "flx:GOLD"} (set (keys annotations)))))
  (is (= {} (perp-annotations/normalize-perp-annotations nil)))
  (is (= {} (perp-annotations/normalize-perp-annotations {:error "x"}))))

(deftest apply-perp-annotation-keeps-identity-and-adds-display-fields-test
  (let [annotations (perp-annotations/normalize-perp-annotations payload)
        diesel (perp-annotations/apply-perp-annotation diesel-market (get annotations "xyz:HO"))]
    (testing "coin identity is untouched so wire coins and matching still work"
      (is (= "xyz:HO" (:coin diesel)))
      (is (= "HO-USDC" (:symbol diesel)))
      (is (= "HO" (:base diesel))))
    (is (= "DIESEL" (:display-name diesel)))
    (is (= "DIESEL-USDC" (:display-symbol diesel)))
    (is (= ["ho" "ulsd"] (:search-keywords diesel)))
    (is (= :tradfi (:category diesel)))
    (is (= "DIESEL-USDC" (perp-annotations/display-symbol diesel)))))

(deftest apply-perp-annotation-promotes-crypto-category-test
  (let [annotations (perp-annotations/normalize-perp-annotations payload)]
    (is (= :crypto (:category (perp-annotations/apply-perp-annotation
                               pearl-market (get annotations "io:PRL")))))))

(deftest apply-perp-annotation-ignores-non-perps-and-missing-annotations-test
  (let [spot {:key "spot:@1" :coin "@1" :symbol "HO/USDC" :market-type :spot}]
    (is (= spot (perp-annotations/apply-perp-annotation spot {:display-name "DIESEL"})))
    (is (= diesel-market (perp-annotations/apply-perp-annotation diesel-market nil)))
    (is (= "HO-USDC" (perp-annotations/display-symbol diesel-market)))))

(deftest apply-perp-annotations-updates-market-state-test
  (let [annotations (perp-annotations/normalize-perp-annotations payload)
        state {:markets [diesel-market pearl-market]
               :market-by-key {"perp:xyz:HO" diesel-market "perp:io:PRL" pearl-market}
               :market-index-by-key {"perp:xyz:HO" 0 "perp:io:PRL" 1}
               :active-market pearl-market
               :loaded-at-ms 1}
        enriched (perp-annotations/apply-perp-annotations state annotations)]
    (is (= ["DIESEL-USDC" "PEARL-USDC"] (mapv :display-symbol (:markets enriched))))
    (is (= "DIESEL-USDC" (get-in enriched [:market-by-key "perp:xyz:HO" :display-symbol])))
    (is (= "PEARL-USDC" (get-in enriched [:active-market :display-symbol])))
    (is (= {"perp:xyz:HO" 0 "perp:io:PRL" 1} (:market-index-by-key enriched)))
    (is (= state (perp-annotations/apply-perp-annotations state {})))))

(deftest search-matches-display-name-and-keywords-test
  (let [annotations (perp-annotations/normalize-perp-annotations payload)
        diesel (perp-annotations/apply-perp-annotation diesel-market (get annotations "xyz:HO"))]
    (is (query/matches-search? diesel "diesel" false))
    (is (query/matches-search? diesel "dies" true))
    (is (query/matches-search? diesel "ulsd" true))
    (is (query/matches-search? diesel "ho" true))
    (is (not (query/matches-search? diesel "pearl" false)))))

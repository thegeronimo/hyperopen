(ns hyperopen.portfolio.optimizer.application.universe-candidates-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.universe-candidates :as universe-candidates]))

(defn- state-with-markets
  [markets]
  {:asset-selector {:markets markets}})

(defn- market-keys
  [markets]
  (mapv :key markets))

(def ^:private alpha-vault-address
  "0x1111111111111111111111111111111111111111")

(def ^:private beta-vault-address
  "0x2222222222222222222222222222222222222222")

(def ^:private child-vault-address
  "0x3333333333333333333333333333333333333333")

(deftest selected-instrument-ids-ignores-missing-ids-test
  (is (= #{"perp:BTC" "spot:@107"}
         (universe-candidates/selected-instrument-ids
          [{:instrument-id "perp:BTC"}
           {:instrument-id nil}
           {:coin "ETH"}
           {:instrument-id "spot:@107"}
           {:instrument-id "perp:BTC"}]))))

(deftest candidate-markets-excludes-selected-and-malformed-results-and-limits-to-six-test
  (let [markets [{:key nil
                  :market-type :perp
                  :coin "BROKEN"
                  :symbol "BROKEN-USDC"
                  :volume24h 1100}
                 {:key "perp:BTC"
                  :market-type :perp
                  :coin "BTC"
                  :symbol "BTC-USDC"
                  :volume24h 1090}
                 {:key "perp:NO-COIN"
                  :market-type :perp
                  :coin "   "
                  :symbol "NO-COIN-USDC"
                  :volume24h 1080}
                 {:key "perp:NO-TYPE"
                  :coin "NOTYPE"
                  :symbol "NOTYPE-USDC"
                  :volume24h 1070}
                 {:key "perp:ETH"
                  :market-type :perp
                  :coin "ETH"
                  :symbol "ETH-USDC"
                  :volume24h 1060}
                 {:key "perp:SOL"
                  :market-type :perp
                  :coin "SOL"
                  :symbol "SOL-USDC"
                  :volume24h 1050}
                 {:key "perp:ARB"
                  :market-type :perp
                  :coin "ARB"
                  :symbol "ARB-USDC"
                  :volume24h 1040}
                 {:key "perp:LINK"
                  :market-type :perp
                  :coin "LINK"
                  :symbol "LINK-USDC"
                  :volume24h 1030}
                 {:key "perp:DOGE"
                  :market-type :perp
                  :coin "DOGE"
                  :symbol "DOGE-USDC"
                  :volume24h 1020}
                 {:key "perp:AVAX"
                  :market-type :perp
                  :coin "AVAX"
                  :symbol "AVAX-USDC"
                  :volume24h 1010}
                 {:key "perp:NEAR"
                  :market-type :perp
                  :coin "NEAR"
                  :symbol "NEAR-USDC"
                  :volume24h 1000}]
        universe [{:instrument-id "perp:BTC"
                   :market-type :perp
                   :coin "BTC"}]
        candidates (universe-candidates/candidate-markets
                    (state-with-markets markets)
                    universe
                    nil)]
    (is (= ["perp:ETH"
            "perp:SOL"
            "perp:ARB"
            "perp:LINK"
            "perp:DOGE"
            "perp:AVAX"]
           (market-keys candidates)))
    (is (= 6 (count candidates)))
    (is (not-any? #(= "perp:BTC" (:key %)) candidates))))

(deftest candidate-markets-prioritizes-exact-matches-and-spot-markets-test
  (let [markets [{:key "perp:SUPERHYPE"
                  :market-type :perp
                  :coin "SUPERHYPE"
                  :symbol "SUPERHYPE-USDC"
                  :volume24h 1400}
                 {:key "perp:HYPE"
                  :market-type :perp
                  :coin "HYPE"
                  :symbol "HYPE-USDC"
                  :name "Hyperliquid"
                  :volume24h 1300}
                 {:key "spot:HYPE"
                  :market-type :spot
                  :coin "HYPE"
                  :symbol "HYPE"
                  :base "HYPE"
                  :quote "USDC"
                  :volume24h 1200}
                 {:key "spot:@232"
                  :market-type :spot
                  :coin "@232"
                  :symbol "HYPE/USDH"
                  :base "HYPE"
                  :quote "USDH"
                  :volume24h 1100}]
        candidates (universe-candidates/candidate-markets
                    (state-with-markets markets)
                    []
                    "hype")]
    (is (= ["spot:HYPE" "perp:HYPE" "spot:@232" "perp:SUPERHYPE"]
           (market-keys candidates)))))

(deftest candidate-markets-includes-eligible-vault-rows-by-name-address-and-type-test
  (let [state {:asset-selector {:markets []}
               :vaults {:merged-index-rows [{:name "Alpha Yield"
                                             :vault-address alpha-vault-address
                                             :relationship {:type :normal}
                                             :tvl 500}
                                            {:name "Beta Carry"
                                             :vault-address beta-vault-address
                                             :relationship {:type :normal}
                                             :tvl 700}
                                            {:name "Child Sleeve"
                                             :vault-address child-vault-address
                                             :relationship {:type :child}
                                             :tvl 900}]}}
        universe [{:instrument-id (str "vault:" beta-vault-address)
                   :market-type :vault
                   :coin (str "vault:" beta-vault-address)
                   :vault-address beta-vault-address}]
        vault-candidates (universe-candidates/candidate-markets
                          state
                          universe
                          "vault")
        name-candidates (universe-candidates/candidate-markets
                         state
                         []
                         "alpha")
        address-candidates (universe-candidates/candidate-markets
                            state
                            []
                            (subs alpha-vault-address 0 10))]
    (is (= [(str "vault:" alpha-vault-address)]
           (market-keys vault-candidates)))
    (is (= [(str "vault:" alpha-vault-address)]
           (market-keys name-candidates)))
    (is (= [(str "vault:" alpha-vault-address)]
           (market-keys address-candidates)))
    (is (= {:market-type :vault
            :coin (str "vault:" alpha-vault-address)
            :vault-address alpha-vault-address
            :name "Alpha Yield"
            :symbol "Alpha Yield"
            :tvl 500}
           (select-keys (first vault-candidates)
                        [:market-type :coin :vault-address :name :symbol :tvl])))
    (is (not-any? #(= (str "vault:" beta-vault-address) (:key %))
                  vault-candidates))
    (is (not-any? #(= (str "vault:" child-vault-address) (:key %))
                  vault-candidates))))

(deftest market-display-prefers-friendly-labels-over-raw-asset-ids-test
  (let [raw-spot-display (universe-candidates/market-display
                          {:key "spot:@107"
                           :market-type :spot
                           :coin "@107"
                           :symbol "HYPE/USDC"
                           :base "HYPE"
                           :quote "USDC"})
        named-perp-display (universe-candidates/market-display
                            {:key "perp:ETH"
                             :market-type :perp
                             :coin "ETH"
                             :symbol "ETH-USDC"
                             :base "ETH"
                             :name "Ether"})]
    (is (= {:label "HYPE/USDC"
            :name "Hyperliquid"
            :base-label "HYPE"}
           (select-keys raw-spot-display [:label :name :base-label])))
    (is (= {:label "ETH-USDC"
            :name "Ether"
            :base-label "ETH"}
           (select-keys named-perp-display [:label :name :base-label])))))

(deftest market-display-labels-vault-instruments-by-name-and-address-test
  (let [display (universe-candidates/market-display
                 {:key (str "vault:" alpha-vault-address)
                  :instrument-id (str "vault:" alpha-vault-address)
                  :market-type :vault
                  :coin (str "vault:" alpha-vault-address)
                  :vault-address alpha-vault-address
                  :name "Alpha Yield"
                  :symbol "Alpha Yield"})]
    (is (= {:label "Alpha Yield"
            :name "Alpha Yield"
            :base-label alpha-vault-address}
           (select-keys display [:label :name :base-label])))))

(deftest active-index-clamps-negative-oversized-and-invalid-values-test
  (let [markets [{:key "perp:BTC"}
                 {:key "perp:ETH"}
                 {:key "perp:SOL"}
                 {:key "perp:ARB"}]]
    (is (= 0
           (universe-candidates/active-index
            {:portfolio-ui {:optimizer {:universe-search-active-index -3}}}
            markets)))
    (is (= 2
           (universe-candidates/active-index
            {:portfolio-ui {:optimizer {:universe-search-active-index 2.8}}}
            markets)))
    (is (= 3
           (universe-candidates/active-index
            {:portfolio-ui {:optimizer {:universe-search-active-index 99}}}
            markets)))
    (is (= 0
           (universe-candidates/active-index
            {:portfolio-ui {:optimizer {:universe-search-active-index "abc"}}}
            markets)))
    (is (= 0
           (universe-candidates/active-index
            {:portfolio-ui {:optimizer {:universe-search-active-index 7}}}
            [])))))

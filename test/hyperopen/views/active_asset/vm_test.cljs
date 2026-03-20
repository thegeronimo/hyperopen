(ns hyperopen.views.active-asset.vm-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.active-asset.vm :as active-asset-vm]))

(deftest active-asset-panel-dependency-state-prunes-closed-selector-roots-test
  (let [state {:active-asset "BTC"
               :active-market {:key "perp:BTC"
                               :coin "BTC"
                               :symbol "BTC-USDC"
                               :base "BTC"
                               :market-type :perp}
               :active-assets {:contexts {"BTC" {:coin "BTC"
                                                 :mark 64000.0}
                                          "ETH" {:coin "ETH"
                                                 :mark 3200.0}}
                               :funding-predictability {:by-coin {"BTC" {:mean 0.1}
                                                                  "ETH" {:mean 0.2}}
                                                        :loading-by-coin {"ETH" true}
                                                        :error-by-coin {"ETH" :boom}}}
               :asset-selector {:visible-dropdown nil
                                :search-term "et"
                                :sort-by :name
                                :sort-direction :asc
                                :markets [{:key "perp:ETH"
                                           :coin "ETH"
                                           :symbol "ETH-USDC"
                                           :market-type :perp}]
                                :market-by-key {"perp:BTC" {:key "perp:BTC"
                                                            :coin "BTC"
                                                            :symbol "BTC-USDC"
                                                            :base "BTC"
                                                            :market-type :perp}}
                                :favorites #{"perp:BTC"}
                                :missing-icons #{"perp:BTC"}
                                :loaded-icons #{"perp:ETH"}
                                :scroll-top 144
                                :render-limit 240
                                :strict? true
                                :active-tab :perps}
               :funding-ui {:tooltip {:visible-id nil
                                      :pinned-id nil}
                            :hypothetical-position-by-coin {"BTC" {:size-input "1"}}}
               :trade-ui {:mobile-asset-details-open? false}
               :account {:positions [{:coin "BTC"}]}
               :spot {:meta :spot}
               :perp-dex-clearinghouse {:default {:assetPositions []}}
               :ui {:locale "en-US"}}
        deps (active-asset-vm/panel-dependency-state state)]
    (is (= {:visible-dropdown nil
            :missing-icons #{"perp:BTC"}
            :loaded-icons #{"perp:ETH"}}
           (:asset-selector deps)))
    (is (= {"BTC" {:coin "BTC"
                   :mark 64000.0}}
           (get-in deps [:active-assets :contexts])))
    (is (nil? (get-in deps [:asset-selector :markets])))
    (is (nil? (get-in deps [:asset-selector :search-term])))
    (is (nil? (get-in deps [:asset-selector :market-by-key])))
    (is (nil? (get-in deps [:active-assets :funding-predictability])))
    (is (= {:tooltip {:visible-id nil
                      :pinned-id nil}}
           (:funding-ui deps)))
    (is (nil? (:account deps)))
    (is (nil? (:spot deps)))
    (is (nil? (:perp-dex-clearinghouse deps)))
    (is (nil? (:ui deps)))))

(deftest active-asset-panel-dependency-state-keeps-open-selector-state-and-market-fallback-test
  (let [market {:key "spot:@1"
                :coin "@1"
                :symbol "HYPE/USDC"
                :base "HYPE"
                :quote "USDC"
                :market-type :spot
                :mark 10.0
                :markRaw "10.0"
                :change24h 1.0
                :change24hPct 11.11
                :volume24h 100000.0}
        state {:active-asset "@1"
               :active-market nil
               :active-assets {:contexts {"@1" {:coin "@1"}}}
               :asset-selector {:visible-dropdown :asset-selector
                                :search-term "hype"
                                :sort-by :name
                                :sort-direction :asc
                                :loading? true
                                :phase :full
                                :favorites #{}
                                :missing-icons #{}
                                :loaded-icons #{}
                                :highlighted-market-key "spot:@1"
                                :scroll-top 144
                                :render-limit 180
                                :favorites-only? false
                                :strict? true
                                :active-tab :spot
                                :markets [market]
                                :market-by-key {"spot:@1" market}}
               :funding-ui {:tooltip {}}
               :trade-ui {:mobile-asset-details-open? false}}
        deps (active-asset-vm/panel-dependency-state state)
        panel-vm (active-asset-vm/active-asset-panel-vm deps)]
    (is (= :asset-selector (get-in deps [:asset-selector :visible-dropdown])))
    (is (= "hype" (get-in deps [:asset-selector :search-term])))
    (is (= 144 (get-in deps [:asset-selector :scroll-top])))
    (is (= {"spot:@1" market}
           (get-in deps [:asset-selector :market-by-key])))
    (is (= [market]
           (get-in deps [:asset-selector :markets])))
    (is (true? (get-in panel-vm [:asset-selector-props :visible?])))
    (is (= "spot:@1"
           (get-in panel-vm [:asset-selector-props :selected-market-key])))
    (is (= "HYPE/USDC"
           (get-in panel-vm [:row-vm :icon-market :symbol])))))

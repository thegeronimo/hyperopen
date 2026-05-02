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
               :webdata2 {:clearinghouseState {:assetPositions [{:position {:coin "BTC"
                                                                            :szi "1"
                                                                            :positionValue "64000"}}]}}
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
    (is (nil? (:webdata2 deps)))
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
    (is (= {"spot:@1" market}
           (get-in panel-vm [:asset-selector-props :market-by-key])))
    (is (= "spot:@1"
           (get-in panel-vm [:asset-selector-props :selected-market-key])))
    (is (= "HYPE/USDC"
           (get-in panel-vm [:row-vm :icon-market :symbol])))))

(deftest active-asset-row-vm-finds-live-position-from-resolved-market-test
  (let [market {:key "perp:xyz:GOLD"
                :coin "xyz:GOLD"
                :symbol "GOLD-USDC"
                :base "GOLD"
                :dex "xyz"
                :market-type :perp
                :mark 5000.0}
        state {:active-asset "xyz:GOLD"
               :active-market nil
               :active-assets {:contexts {"xyz:GOLD" {:coin "xyz:GOLD"
                                                      :mark 5000.0
                                                      :fundingRate 0.0056}}
                               :funding-predictability {:by-coin {}
                                                        :loading-by-coin {}
                                                        :error-by-coin {}}}
               :asset-selector {:visible-dropdown nil
                                :missing-icons #{}
                                :loaded-icons #{}
                                :market-by-key {"perp:xyz:GOLD" market}}
               :funding-ui {:tooltip {:visible-id "funding-rate-tooltip-pin-xyz-gold"
                                      :pinned-id nil}
                            :hypothetical-position-by-coin {}}
               :trade-ui {:mobile-asset-details-open? false}
               :perp-dex-clearinghouse {"xyz" {:assetPositions [{:position {:coin "GOLD"
                                                                              :szi "2"
                                                                              :positionValue "10000"}}]}}
               :ui {:locale "en-US"}}
        panel-vm (active-asset-vm/active-asset-panel-vm state)]
    (is (= "Your Position"
           (get-in panel-vm [:row-vm :funding-tooltip-model :position-title])))
    (is (= "Long 2 GOLD"
           (get-in panel-vm [:row-vm :funding-tooltip-model :position-size-label])))
    (is (= 10000
           (get-in panel-vm [:row-vm :funding-tooltip-model :position-value])))))

(deftest active-asset-row-vm-projects-outcome-market-fields-test
  (let [market {:key "outcome:0"
                :coin "#0"
                :symbol "BTC above 78213 on May 3 at 2:00 AM?"
                :title "BTC above 78213 on May 3 at 2:00 AM?"
                :market-type :outcome
                :underlying "BTC"
                :target-price 78213
                :mark 0.57841
                :markRaw "0.57841"
                :change24h 0.0268
                :change24hPct 4.87
                :volume24h 180211.68
                :openInterest 537233
                :expiry-ms 1777788000000
                :outcome-details "If the BTC mark price at time of settlement is above 78213 at May 03, 2026 06:00 UTC, YES tokens pay out $1 each. Otherwise, NO tokens pay out $1 each."}
        panel-vm (active-asset-vm/active-asset-panel-vm
                  {:active-asset "#0"
                   :now-ms 1777751495000
                   :active-market market
                   :active-assets {:contexts {"#0" {:coin "#0"
                                                     :openInterest 276502}}}
                   :asset-selector {:visible-dropdown nil
                                    :missing-icons #{}
                                    :loaded-icons #{}
                                    :market-by-key {"outcome:0" market}}
                   :funding-ui {:tooltip {}}
                   :trade-ui {:mobile-asset-details-open? false}})]
    (is (true? (get-in panel-vm [:row-vm :is-outcome])))
    (is (= "58%" (get-in panel-vm [:row-vm :outcome-chance-label])))
    (is (= "BTC above 78213 on May 3 at 2:00 AM?"
           (get-in panel-vm [:row-vm :outcome-title])))
    (is (= "10h 8m 25s"
           (get-in panel-vm [:row-vm :countdown-text])))
    (is (= 276502
           (get-in panel-vm [:row-vm :open-interest-usd])))
    (is (= "Two sided-open interest: the sum of Yes and No shares on this contract"
           (get-in panel-vm [:row-vm :open-interest-tooltip])))
    (is (= "If the BTC mark price at time of settlement is above 78213 at May 03, 2026 06:00 UTC, YES tokens pay out $1 each. Otherwise, NO tokens pay out $1 each."
           (get-in panel-vm [:row-vm :outcome-details])))
    (is (= {:title "Outcome Details"
            :summary "This market resolves to YES or NO based on the following settlement condition at the specified time."
            :settlement-label "BTC mark price is above 78,213"
            :settlement-time-label "at May 03, 2026 06:00 AM UTC"
            :yes-payout-label "$1.00"
            :no-payout-label "$0.00"
            :footer-label "All payouts are in USDC."}
           (get-in panel-vm [:row-vm :outcome-tooltip])))))

(deftest active-asset-row-vm-falls-back-to-outcome-side-supply-open-interest-test
  (let [market {:key "outcome:0"
                :coin "#0"
                :symbol "BTC above 78213 on May 3 at 2:00 AM?"
                :title "BTC above 78213 on May 3 at 2:00 AM?"
                :market-type :outcome
                :mark 0.57841
                :expiry-ms 1777788000000
                :outcome-sides [{:coin "#0"
                                 :circulatingSupply 276502}
                                {:coin "#1"
                                 :circulatingSupply 260731}]}
        panel-vm (active-asset-vm/active-asset-panel-vm
                  {:active-asset "#0"
                   :now-ms 1777751495000
                   :active-market market
                   :active-assets {:contexts {"#0" {:coin "#0"}}}
                   :asset-selector {:visible-dropdown nil
                                    :missing-icons #{}
                                    :loaded-icons #{}
                                    :market-by-key {"outcome:0" market}}
                   :funding-ui {:tooltip {}}
                   :trade-ui {:mobile-asset-details-open? false}})]
    (is (= 276502
           (get-in panel-vm [:row-vm :open-interest-usd])))))

(deftest active-asset-row-vm-uses-active-outcome-context-open-interest-test
  (let [market {:key "outcome:0"
                :coin "#0"
                :symbol "BTC above 78213 on May 3 at 2:00 AM?"
                :title "BTC above 78213 on May 3 at 2:00 AM?"
                :market-type :outcome
                :mark 0.57841
                :expiry-ms 1777788000000}
        panel-vm (active-asset-vm/active-asset-panel-vm
                  {:active-asset "#0"
                   :now-ms 1777751495000
                   :active-market market
                   :active-assets {:contexts {"#0" {:coin "#0"
                                                     :openInterest 567696}}}
                   :asset-selector {:visible-dropdown nil
                                    :missing-icons #{}
                                    :loaded-icons #{}
                                    :market-by-key {"outcome:0" market}}
                   :funding-ui {:tooltip {}}
                   :trade-ui {:mobile-asset-details-open? false}})]
    (is (= 567696
           (get-in panel-vm [:row-vm :open-interest-usd])))
    (is (= "Two sided-open interest: the sum of Yes and No shares on this contract"
           (get-in panel-vm [:row-vm :open-interest-tooltip])))))

(deftest active-asset-row-vm-inferrs-namespaced-market-during-bootstrap-and-shows-live-position-test
  (let [state {:active-asset "xyz:BRENTOIL"
               :active-market nil
               :active-assets {:contexts {"xyz:BRENTOIL" {:coin "xyz:BRENTOIL"
                                                          :mark 108.17
                                                          :fundingRate -0.005618}}
                               :funding-predictability {:by-coin {}
                                                        :loading-by-coin {}
                                                        :error-by-coin {}}}
               :asset-selector {:visible-dropdown nil
                                :phase :bootstrap
                                :selected-market-key nil
                                :missing-icons #{}
                                :loaded-icons #{}
                                :market-by-key {}}
               :funding-ui {:tooltip {:visible-id "funding-rate-tooltip-pin-xyz-brentoil"
                                      :pinned-id "funding-rate-tooltip-pin-xyz-brentoil"}
                            :hypothetical-position-by-coin {}}
               :trade-ui {:mobile-asset-details-open? false}
               :perp-dex-clearinghouse {"xyz" {:assetPositions [{:position {:coin "xyz:BRENTOIL"
                                                                             :szi "1.31"
                                                                             :positionValue "141.087"}}]}}
               :ui {:locale "en-US"}}
        deps (active-asset-vm/panel-dependency-state state)
        panel-vm (active-asset-vm/active-asset-panel-vm deps)]
    (is (= "perp:xyz:BRENTOIL"
           (get-in deps [:active-market :key])))
    (is (= "xyz"
           (get-in deps [:active-market :dex])))
    (is (= "BRENTOIL-USDC"
           (get-in deps [:active-market :symbol])))
    (is (= "Your Position"
           (get-in panel-vm [:row-vm :funding-tooltip-model :position-title])))
    (is (= "Long 1.31 BRENTOIL"
           (get-in panel-vm [:row-vm :funding-tooltip-model :position-size-label])))
    (is (= 141.087
           (get-in panel-vm [:row-vm :funding-tooltip-model :position-value])))))

(deftest active-asset-row-vm-ignores-stale-different-dex-market-when-active-asset-is-namespaced-test
  (let [state {:active-asset "xyz:GOLD"
               :active-market {:key "perp:hyna:GOLD"
                               :coin "hyna:GOLD"
                               :symbol "GOLD-USDC"
                               :base "GOLD"
                               :dex "hyna"
                               :market-type :perp
                               :mark 5000.0}
               :active-assets {:contexts {"xyz:GOLD" {:coin "xyz:GOLD"
                                                      :mark 5000.0
                                                      :fundingRate 0.0056}}
                               :funding-predictability {:by-coin {}
                                                        :loading-by-coin {}
                                                        :error-by-coin {}}}
               :asset-selector {:visible-dropdown nil
                                :missing-icons #{}
                                :loaded-icons #{}
                                :market-by-key {}}
               :funding-ui {:tooltip {:visible-id "funding-rate-tooltip-pin-xyz-gold"
                                      :pinned-id nil}
                            :hypothetical-position-by-coin {}}
               :trade-ui {:mobile-asset-details-open? false}
               :perp-dex-clearinghouse {"xyz" {:assetPositions [{:position {:coin "xyz:GOLD"
                                                                             :szi "2"
                                                                             :positionValue "10000"}}]}
                                        "hyna" {:assetPositions [{:position {:coin "hyna:GOLD"
                                                                              :szi "9"
                                                                              :positionValue "45000"}}]}}
               :ui {:locale "en-US"}}
        deps (active-asset-vm/panel-dependency-state state)
        panel-vm (active-asset-vm/active-asset-panel-vm deps)]
    (is (= "xyz"
           (get-in deps [:active-market :dex])))
    (is (= "Your Position"
           (get-in panel-vm [:row-vm :funding-tooltip-model :position-title])))
    (is (= "Long 2 GOLD"
           (get-in panel-vm [:row-vm :funding-tooltip-model :position-size-label])))
    (is (= 10000
           (get-in panel-vm [:row-vm :funding-tooltip-model :position-value])))))

(deftest active-asset-row-vm-keeps-webdata2-position-state-when-tooltip-open-test
  (let [market {:key "perp:BTC"
                :coin "BTC"
                :symbol "BTC-USDC"
                :base "BTC"
                :market-type :perp
                :mark 107.7426}
        state {:active-asset "BTC"
               :active-market market
               :active-assets {:contexts {"BTC" {:coin "BTC"
                                                 :mark 107.7426
                                                 :fundingRate 0.00015}}
                               :funding-predictability {:by-coin {}
                                                        :loading-by-coin {}
                                                        :error-by-coin {}}}
               :asset-selector {:visible-dropdown nil
                                :missing-icons #{}
                                :loaded-icons #{}
                                :market-by-key {"perp:BTC" market}}
               :funding-ui {:tooltip {:visible-id "funding-rate-tooltip-pin-btc"
                                      :pinned-id nil}
                            :hypothetical-position-by-coin {}}
               :trade-ui {:mobile-asset-details-open? false}
               :webdata2 {:clearinghouseState {:assetPositions [{:position {:coin "BTC"
                                                                            :szi "9.2807"
                                                                            :positionValue "1000"}}]}}
               :ui {:locale "en-US"}}
        deps (active-asset-vm/panel-dependency-state state)
        panel-vm (active-asset-vm/active-asset-panel-vm deps)]
    (is (= (:webdata2 state) (:webdata2 deps)))
    (is (= "Your Position"
           (get-in panel-vm [:row-vm :funding-tooltip-model :position-title])))
    (is (= "Long 9.2807 BTC"
           (get-in panel-vm [:row-vm :funding-tooltip-model :position-size-label])))
    (is (= 1000
           (get-in panel-vm [:row-vm :funding-tooltip-model :position-value])))))

(deftest active-asset-row-vm-keeps-namespaced-projected-market-when-active-asset-is-base-symbol-test
  (let [market {:key "perp:xyz:BRENTOIL"
                :coin "xyz:BRENTOIL"
                :symbol "BRENTOIL-USDC"
                :base "BRENTOIL"
                :dex "xyz"
                :market-type :perp
                :mark 108.17}
        state {:active-asset "BRENTOIL"
               :active-market market
               :active-assets {:contexts {"BRENTOIL" {:coin "BRENTOIL"
                                                      :mark 108.17
                                                      :fundingRate -0.000234}}}
               :asset-selector {:visible-dropdown nil
                                :missing-icons #{}
                                :loaded-icons #{}}
               :funding-ui {:tooltip {:visible-id "funding-rate-tooltip-pin-xyz-brentoil"
                                      :pinned-id nil}
                            :hypothetical-position-by-coin {}}
               :trade-ui {:mobile-asset-details-open? false}
               :perp-dex-clearinghouse {"xyz" {:assetPositions [{:position {:coin "BRENTOIL"
                                                                             :szi "1.31"
                                                                             :positionValue "141.66"}}]}}
               :ui {:locale "en-US"}}
        deps (active-asset-vm/panel-dependency-state state)
        panel-vm (active-asset-vm/active-asset-panel-vm deps)]
    (is (= market (:active-market deps)))
    (is (nil? (get-in deps [:asset-selector :market-by-key])))
    (is (= (:perp-dex-clearinghouse state)
           (:perp-dex-clearinghouse deps)))
    (is (= "Your Position"
           (get-in panel-vm [:row-vm :funding-tooltip-model :position-title])))
    (is (= "Long 1.31 BRENTOIL"
           (get-in panel-vm [:row-vm :funding-tooltip-model :position-size-label])))
    (is (= 141.66
           (get-in panel-vm [:row-vm :funding-tooltip-model :position-value])))))

(deftest active-asset-row-vm-finds-live-position-through-wrapped-named-dex-clearinghouse-state-test
  (let [market {:key "perp:xyz:BRENTOIL"
                :coin "xyz:BRENTOIL"
                :symbol "BRENTOIL-USDC"
                :base "BRENTOIL"
                :dex "xyz"
                :market-type :perp
                :mark 108.17}
        state {:active-asset "BRENTOIL"
               :active-market market
               :active-assets {:contexts {"BRENTOIL" {:coin "BRENTOIL"
                                                      :mark 108.17
                                                      :fundingRate -0.000234}}}
               :asset-selector {:visible-dropdown nil
                                :missing-icons #{}
                                :loaded-icons #{}}
               :funding-ui {:tooltip {:visible-id "funding-rate-tooltip-pin-xyz-brentoil"
                                      :pinned-id nil}
                            :hypothetical-position-by-coin {}}
               :trade-ui {:mobile-asset-details-open? false}
               :perp-dex-clearinghouse {"XYZ" {:clearinghouseState {:assetPositions [{:position {:coin "BRENTOIL"
                                                                                                   :szi "1.31"
                                                                                                   :positionValue "141.66"}}]}}}
               :ui {:locale "en-US"}}
        deps (active-asset-vm/panel-dependency-state state)
        panel-vm (active-asset-vm/active-asset-panel-vm deps)]
    (is (= "Your Position"
           (get-in panel-vm [:row-vm :funding-tooltip-model :position-title])))
    (is (= "Long 1.31 BRENTOIL"
           (get-in panel-vm [:row-vm :funding-tooltip-model :position-size-label])))
    (is (= 141.66
           (get-in panel-vm [:row-vm :funding-tooltip-model :position-value])))))

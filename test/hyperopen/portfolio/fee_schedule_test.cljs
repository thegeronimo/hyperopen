(ns hyperopen.portfolio.fee-schedule-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.fee-schedule :as fee-schedule]))

(deftest normalize-market-type-accepts-keywords-strings-and-labels-test
  (is (= :perps (fee-schedule/normalize-market-type nil)))
  (is (= :perps (fee-schedule/normalize-market-type :unknown)))
  (is (= :perps (fee-schedule/normalize-market-type "Perps")))
  (is (= :perps (fee-schedule/normalize-market-type "Core Perps")))
  (is (= :spot (fee-schedule/normalize-market-type "spot")))
  (is (= :spot-stable-pair
         (fee-schedule/normalize-market-type "Spot + Stable Pair")))
  (is (= :spot-aligned-quote
         (fee-schedule/normalize-market-type "spotAlignedQuote")))
  (is (= :spot-aligned-stable-pair
         (fee-schedule/normalize-market-type :spot-aligned-stable-pair)))
  (is (= :spot-aligned-stable-pair
         (fee-schedule/normalize-market-type "Spot + Aligned Quote + Stable Pair")))
  (is (= :hip3-perps
         (fee-schedule/normalize-market-type "HIP-3 Perps")))
  (is (= :hip3-perps-growth-mode
         (fee-schedule/normalize-market-type "HIP-3 Perps + Growth mode")))
  (is (= :hip3-perps-aligned-quote
         (fee-schedule/normalize-market-type :hip3-perps-aligned-quote)))
  (is (= :hip3-perps-growth-mode-aligned-quote
         (fee-schedule/normalize-market-type "hip3PerpsGrowthModeAlignedQuote"))))

(deftest market-type-options-include-expanded-volume-tier-scenarios-test
  (is (= ["Spot"
          "Spot + Aligned Quote"
          "Spot + Stable Pair"
          "Spot + Aligned Quote + Stable Pair"
          "Core Perps"
          "HIP-3 Perps"
          "HIP-3 Perps + Growth mode"
          "HIP-3 Perps + Aligned Quote"
          "HIP-3 Perps + Growth mode + Aligned Quote"]
         (mapv :label fee-schedule/market-type-options))))

(deftest fee-schedule-rows-render-protocol-rate-variants-test
  (testing "perps base schedule"
    (let [rows (fee-schedule/fee-schedule-rows :perps)]
      (is (= {:tier "0"
              :volume "<= $5M"
              :taker "0.045%"
              :maker "0.015%"}
             (first rows)))
      (is (= {:tier "6"
              :volume "> $7B"
              :taker "0.024%"
              :maker "0%"}
             (last rows)))))
  (testing "spot base schedule"
    (let [rows (fee-schedule/fee-schedule-rows :spot)]
      (is (= {:tier "0"
              :volume "<= $5M"
              :taker "0.070%"
              :maker "0.040%"}
             (first rows)))))
  (testing "stable pair and aligned quote multipliers"
    (is (= {:tier "0"
            :volume "<= $5M"
            :taker "0.014%"
            :maker "0.008%"}
           (first (fee-schedule/fee-schedule-rows :spot-stable-pair))))
    (is (= {:tier "0"
            :volume "<= $5M"
            :taker "0.056%"
            :maker "0.040%"}
           (first (fee-schedule/fee-schedule-rows :spot-aligned-quote))))
    (is (= {:tier "0"
            :volume "<= $5M"
            :taker "0.0112%"
            :maker "0.008%"}
           (first (fee-schedule/fee-schedule-rows :spot-aligned-stable-pair))))
    (is (= {:tier "6"
            :volume "> $7B"
            :taker "0.004%"
            :maker "0%"}
           (last (fee-schedule/fee-schedule-rows :spot-aligned-stable-pair)))))
  (testing "HIP-3 variants use active deployer fee context"
    (is (= {:tier "0"
            :volume "<= $5M"
            :taker "0.0675%"
            :maker "0.0225%"}
           (first (fee-schedule/fee-schedule-rows
                   :hip3-perps
                   {:active-fee-context {:deployer-fee-scale 0.5}}))))
    (is (= {:tier "0"
            :volume "<= $5M"
            :taker "0.0068%"
            :maker "0.0023%"}
           (first (fee-schedule/fee-schedule-rows
                   :hip3-perps-growth-mode
                   {:active-fee-context {:deployer-fee-scale 0.5}}))))
    (is (= {:tier "0"
            :volume "<= $5M"
            :taker "0.0585%"
            :maker "0.0225%"}
           (first (fee-schedule/fee-schedule-rows
                   :hip3-perps-aligned-quote
                   {:active-fee-context {:deployer-fee-scale 0.5}}))))
    (is (= {:tier "0"
            :volume "<= $5M"
            :taker "0.0059%"
            :maker "0.0023%"}
           (first (fee-schedule/fee-schedule-rows
                   :hip3-perps-growth-mode-aligned-quote
                   {:active-fee-context {:deployer-fee-scale 0.5}}))))))

(deftest fee-schedule-rows-apply-selected-discount-scenarios-test
  (testing "referral and staking reduce positive perps fees while maker rebate overrides maker fee"
    (let [rows (fee-schedule/fee-schedule-rows
                :perps
                {:referral-discount :referral-4
                 :staking-tier :diamond
                 :maker-rebate-tier :tier-2})]
      (is (= {:tier "0"
              :volume "<= $5M"
              :taker "0.0259%"
              :maker "-0.002%"}
             (first rows)))
      (is (= "-0.002%" (:maker (last rows))))))
  (testing "spot stable-pair and aligned-quote scaling applies after selected discounts"
    (let [rows (fee-schedule/fee-schedule-rows
                :spot-aligned-stable-pair
                {:referral-discount :referral-4
                 :staking-tier :wood})]
      (is (= {:tier "0"
              :volume "<= $5M"
              :taker "0.0102%"
              :maker "0.0073%"}
             (first rows)))))
  (testing "aligned quote improves selected negative maker rebate"
    (let [rows (fee-schedule/fee-schedule-rows
                :spot-aligned-stable-pair
                {:maker-rebate-tier :tier-1})]
      (is (= "-0.0003%" (:maker (first rows))))))
  (testing "HIP-3 aligned quote improves selected negative maker rebate using deployer share"
    (let [rows (fee-schedule/fee-schedule-rows
                :hip3-perps-growth-mode-aligned-quote
                {:maker-rebate-tier :tier-1
                 :active-fee-context {:deployer-fee-scale 0.5}})]
      (is (= "-0.0001%" (:maker (first rows)))))))

(deftest fee-schedule-model-describes-disconnected-and-connected-status-test
  (let [disconnected (fee-schedule/fee-schedule-model
                      {:portfolio-ui {:fee-schedule-open? true
                                      :fee-schedule-market-type "spotAlignedStablePair"
                                      :fee-schedule-market-dropdown-open? true}
                       :portfolio {:user-fees nil}})
        connected (fee-schedule/fee-schedule-model
                   {:wallet {:address "0x1234567890abcdef1234567890abcdef12345678"}
                    :portfolio-ui {:fee-schedule-open? true
                                   :fee-schedule-market-type :perps
                                   :fee-schedule-referral-dropdown-open? true
                                   :fee-schedule-staking-dropdown-open? true
                                   :fee-schedule-maker-rebate-dropdown-open? true}
                    :portfolio {:user-fees {:activeReferralDiscount 0.04
                                            :activeStakingDiscount {:discount 0.1
                                                                    :tier "Bronze"}
                                            :userAddRate -0.00002}}})
        overridden (fee-schedule/fee-schedule-model
                    {:wallet {:address "0x1234567890abcdef1234567890abcdef12345678"}
                     :portfolio-ui {:fee-schedule-open? true
                                    :fee-schedule-market-type :perps
                                    :fee-schedule-referral-discount :none
                                    :fee-schedule-staking-tier :diamond
                                    :fee-schedule-maker-rebate-tier :tier-3}
                     :portfolio {:user-fees {:activeReferralDiscount 0.04
                                             :activeStakingDiscount {:discount 0.05}
                                             :userAddRate -0.00001}}})
        hip3-default (fee-schedule/fee-schedule-model
                      {:portfolio-ui {:fee-schedule-open? true
                                      :fee-schedule-market-type
                                      :hip3-perps}
                       :portfolio {:user-fees nil}})
        hip3-default-growth (fee-schedule/fee-schedule-model
                             {:portfolio-ui {:fee-schedule-open? true
                                             :fee-schedule-market-type
                                             :hip3-perps-growth-mode}
                              :portfolio {:user-fees nil}})
        hip3-active (fee-schedule/fee-schedule-model
                     {:active-asset "testdex:WTIOIL"
                      :active-market {:coin "testdex:WTIOIL"
                                      :base "WTIOIL"
                                      :quote "USDC"
                                      :market-type :perp
                                      :dex "testdex"
                                      :growth-mode? true}
                      :perp-dex-fee-config-by-name {"testdex" {:deployer-fee-scale 0.5}}
                      :portfolio-ui {:fee-schedule-open? true
                                     :fee-schedule-market-type
                                     :hip3-perps-growth-mode
                                     :fee-schedule-market-dropdown-open? true}
                      :portfolio {:user-fees nil}})]
    (testing "disconnected wallet"
      (is (true? (:open? disconnected)))
      (is (= :spot-aligned-stable-pair (:selected-market-type disconnected)))
      (is (= "Spot + Aligned Quote + Stable Pair"
             (:selected-market-label disconnected)))
      (is (true? (:market-dropdown-open? disconnected)))
      (is (= "No referral discount" (get-in disconnected [:referral :value])))
      (is (= "Wallet not connected" (get-in disconnected [:referral :helper])))
      (is (= :none (get-in disconnected [:referral :selected-value])))
      (is (= true (->> (get-in disconnected [:referral :options])
                       (some #(when (= :none (:value %))
                                (:current? %))))))
      (is (= "No stake" (get-in disconnected [:staking :value])))
      (is (= "Wallet not connected" (get-in disconnected [:staking :helper])))
      (is (= :none (get-in disconnected [:staking :selected-value])))
      (is (= "No rebate" (get-in disconnected [:maker-rebate :value])))
      (is (= :none (get-in disconnected [:maker-rebate :selected-value])))
      (is (= "* Rates reflect selected scenarios, market type, and HIP-3 deployer context"
             (:rate-note disconnected))))
    (testing "connected wallet discounts"
      (is (= "4%" (get-in connected [:referral :value])))
      (is (= "Active referral discount" (get-in connected [:referral :helper])))
      (is (= :referral-4 (get-in connected [:referral :selected-value])))
      (is (true? (get-in connected [:referral :dropdown-open?])))
      (is (= "Bronze" (get-in connected [:staking :value])))
      (is (= "Active staking discount" (get-in connected [:staking :helper])))
      (is (= :bronze (get-in connected [:staking :selected-value])))
      (is (true? (get-in connected [:staking :dropdown-open?])))
      (is (= "Tier 2" (get-in connected [:maker-rebate :value])))
      (is (= "Current maker rate is a rebate"
             (get-in connected [:maker-rebate :helper])))
      (is (= :tier-2 (get-in connected [:maker-rebate :selected-value])))
      (is (true? (get-in connected [:maker-rebate :dropdown-open?])))
      (is (= "0.0389%" (get-in connected [:rows 0 :taker])))
      (is (= "-0.002%" (get-in connected [:rows 0 :maker]))))
    (testing "local what-if overrides beat current wallet defaults"
      (is (= :none (get-in overridden [:referral :selected-value])))
      (is (= :diamond (get-in overridden [:staking :selected-value])))
      (is (= :tier-3 (get-in overridden [:maker-rebate :selected-value])))
      (is (= "0.027%" (get-in overridden [:rows 0 :taker])))
      (is (= "-0.003%" (get-in overridden [:rows 0 :maker]))))
    (testing "HIP-3 selection without active market context uses default deployer scale"
      (is (= :hip3-perps (:selected-market-type hip3-default)))
      (is (= "HIP-3 Perps" (:selected-market-label hip3-default)))
      (is (= "0.090%" (get-in hip3-default [:rows 0 :taker])))
      (is (= "0.030%" (get-in hip3-default [:rows 0 :maker])))
      (is (= :hip3-perps-growth-mode (:selected-market-type hip3-default-growth)))
      (is (= "HIP-3 Perps + Growth mode" (:selected-market-label hip3-default-growth)))
      (is (= "0.009%" (get-in hip3-default-growth [:rows 0 :taker])))
      (is (= "0.003%" (get-in hip3-default-growth [:rows 0 :maker])))
      (is (not (->> (:market-options hip3-default-growth)
                    (some #(when (= :hip3-perps-growth-mode (:value %))
                             (:disabled? %))))))
      (is (nil? (->> (:market-options hip3-default-growth)
                     (some #(when (= :hip3-perps-growth-mode (:value %))
                              (:helper %)))))))
    (testing "active HIP-3 market enables HIP-3 previews and marks active option"
      (is (= :hip3-perps-growth-mode (:selected-market-type hip3-active)))
      (is (= "HIP-3 Perps + Growth mode" (:selected-market-label hip3-active)))
      (is (= "0.0068%" (get-in hip3-active [:rows 0 :taker])))
      (is (= "0.0023%" (get-in hip3-active [:rows 0 :maker])))
      (is (= "Active market: WTIOIL"
             (->> (:market-options hip3-active)
                  (some #(when (= :hip3-perps-growth-mode (:value %))
                           (:current-label %))))))
      (is (not (->> (:market-options hip3-active)
                    (some #(when (= :hip3-perps-growth-mode (:value %))
                             (:disabled? %)))))))))

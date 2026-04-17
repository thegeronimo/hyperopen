(ns hyperopen.views.portfolio.fee-schedule-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.portfolio.fee-schedule :as fee-schedule-view]))

(def ^:private sample-model
  {:open? true
   :title "Fee Schedule"
   :anchor {:left 24
            :right 190
            :top 220
            :viewport-width 900
            :viewport-height 900}
   :selected-market-type :perps
   :selected-market-label "Perps"
   :market-dropdown-open? true
   :market-options [{:value :perps
                     :label "Perps"}
                    {:value :spot-aligned-stable-pair
                     :label "Spot + Aligned Quote + Stable Pair"}]
   :referral {:label "Referral Status"
              :value "No referral discount"
              :helper "Wallet not connected"
              :selected-value :none
              :dropdown-open? true
              :options [{:value :none
                         :label "No referral discount"
                         :description "No active referral discount"
                         :current? true}
                        {:value :referral-4
                         :label "4%"
                         :description "Referral discount"}]}
   :staking {:label "Staking Tier"
             :value "Diamond"
             :helper "Active staking discount"
             :selected-value :diamond
             :dropdown-open? true
             :options [{:value :none
                        :label "No stake"
                        :description "No active staking discount"}
                       {:value :diamond
                        :label "Diamond"
                        :description ">500k HYPE staked = 40% discount"
                        :current? true}]}
   :maker-rebate {:label "Maker Rebate Tier"
                  :value "Tier 2"
                  :helper "Current maker rate is a rebate"
                  :selected-value :tier-2
                  :dropdown-open? true
                  :options [{:value :none
                             :label "No rebate"
                             :description "No active maker rebate"}
                            {:value :tier-2
                             :label "Tier 2"
                             :description ">1.5% 14d weighted maker volume = -0.002% maker fee"
                             :current? true}]}
   :rows [{:tier "0"
           :volume "<= $5M"
           :taker "0.045%"
           :maker "0.015%"}
          {:tier "6"
           :volume "> $7B"
           :taker "0.024%"
           :maker "0%"}]
   :rate-note "* Base protocol rates before user-specific referral, staking, maker rebate, and HIP-3 deployer adjustments"
   :documentation-url "https://hyperliquid.gitbook.io/hyperliquid-docs/trading/fees"})

(defn- collect-classes
  [node]
  (cond
    (vector? node)
    (let [class-attr (get-in node [1 :class])
          own-classes (cond
                        (vector? class-attr) class-attr
                        (seq? class-attr) (vec class-attr)
                        (string? class-attr) [class-attr]
                        :else [])]
      (concat own-classes
              (mapcat collect-classes (hiccup/node-children node))))

    (seq? node)
    (mapcat collect-classes node)

    :else []))

(defn- class-set
  [node]
  (set (collect-classes node)))

(defn- first-node-containing-text
  [node text]
  (hiccup/find-first-node node #(contains? (set (hiccup/direct-texts %)) text)))

(deftest fee-schedule-popover-renders-anchored-dialog-contract-and-actions-test
  (let [view (fee-schedule-view/fee-schedule-popover sample-model)
        overlay (hiccup/find-by-data-role view "portfolio-fee-schedule-overlay")
        backdrop (hiccup/find-by-data-role view "portfolio-fee-schedule-backdrop")
        dialog (hiccup/find-by-data-role view "portfolio-fee-schedule-dialog")
        overlay-classes (hiccup/root-class-set overlay)
        backdrop-classes (hiccup/root-class-set backdrop)
        dialog-classes (hiccup/root-class-set dialog)
        close-button (hiccup/find-by-data-role view "portfolio-fee-schedule-close")
        referral-trigger (hiccup/find-by-data-role view "portfolio-fee-schedule-referral-trigger")
        referral-option (hiccup/find-by-data-role view "portfolio-fee-schedule-referral-option-referral-4")
        staking-trigger (hiccup/find-by-data-role view "portfolio-fee-schedule-staking-trigger")
        staking-option (hiccup/find-by-data-role view "portfolio-fee-schedule-staking-option-diamond")
        maker-rebate-trigger (hiccup/find-by-data-role view "portfolio-fee-schedule-maker-rebate-trigger")
        maker-rebate-option (hiccup/find-by-data-role view "portfolio-fee-schedule-maker-rebate-option-tier-2")
        market-trigger (hiccup/find-by-data-role view "portfolio-fee-schedule-market-trigger")
        stable-option (hiccup/find-by-data-role
                       view
                       "portfolio-fee-schedule-market-option-spot-aligned-stable-pair")
        docs-link (hiccup/find-by-data-role view "portfolio-fee-schedule-docs-link")
        all-text (set (hiccup/collect-strings view))]
    (is (some? overlay))
    (is (contains? overlay-classes "pointer-events-none"))
    (is (not (contains? overlay-classes "items-center")))
    (is (not (contains? overlay-classes "justify-center")))
    (is (contains? backdrop-classes "bg-transparent"))
    (is (not (contains? backdrop-classes "bg-black/60")))
    (is (not (contains? backdrop-classes "backdrop-blur-[2px]")))
    (is (= "dialog" (get-in dialog [1 :role])))
    (is (false? (get-in dialog [1 :aria-modal])))
    (is (= "portfolio-fee-schedule-title"
           (get-in dialog [1 :aria-labelledby])))
    (is (contains? dialog-classes "absolute"))
    (is (= {:left "200px"
            :top "200px"
            :width "480px"
            :background-color "#0f1a1f"}
           (get-in dialog [1 :style])))
    (is (fn? (get-in dialog [1 :replicant/on-render])))
    (is (= [[:actions/close-portfolio-fee-schedule]]
           (get-in backdrop [1 :on :click])))
    (is (= [[:actions/close-portfolio-fee-schedule]]
           (get-in close-button [1 :on :click])))
    (is (= [[:actions/handle-portfolio-fee-schedule-keydown [:event/key]]]
           (get-in dialog [1 :on :keydown])))
    (is (= [[:actions/toggle-portfolio-fee-schedule-referral-dropdown]]
           (get-in referral-trigger [1 :on :click])))
    (is (= "true" (get-in referral-trigger [1 :aria-expanded])))
    (is (= [[:actions/select-portfolio-fee-schedule-referral-discount :referral-4]]
           (get-in referral-option [1 :on :click])))
    (is (= [[:actions/toggle-portfolio-fee-schedule-staking-dropdown]]
           (get-in staking-trigger [1 :on :click])))
    (is (= [[:actions/select-portfolio-fee-schedule-staking-tier :diamond]]
           (get-in staking-option [1 :on :click])))
    (is (= [[:actions/toggle-portfolio-fee-schedule-maker-rebate-dropdown]]
           (get-in maker-rebate-trigger [1 :on :click])))
    (is (= [[:actions/select-portfolio-fee-schedule-maker-rebate-tier :tier-2]]
           (get-in maker-rebate-option [1 :on :click])))
    (is (= [[:actions/toggle-portfolio-fee-schedule-market-dropdown]]
           (get-in market-trigger [1 :on :click])))
    (is (= "true" (get-in market-trigger [1 :aria-expanded])))
    (is (= [[:actions/select-portfolio-fee-schedule-market-type
              :spot-aligned-stable-pair]]
           (get-in stable-option [1 :on :click])))
    (is (= "https://hyperliquid.gitbook.io/hyperliquid-docs/trading/fees"
           (get-in docs-link [1 :href])))
    (is (= "_blank" (get-in docs-link [1 :target])))
    (is (= "noreferrer" (get-in docs-link [1 :rel])))
    (is (= 0 (hiccup/count-nodes view #(= :select (first %)))))
    (is (contains? all-text "Fee Schedule"))
    (is (contains? all-text "REFERRAL DISCOUNT"))
    (is (contains? all-text "STAKING DISCOUNT"))
    (is (contains? all-text "MAKER REBATE"))
    (is (contains? all-text "VOLUME TIER"))
    (is (contains? all-text "Current wallet status"))
    (is (contains? all-text "Current wallet staking tier"))
    (is (contains? all-text "Current wallet maker rebate"))
    (is (contains? all-text "Taker*"))
    (is (contains? all-text "Maker*"))
    (is (contains? all-text "0.045%"))
    (is (contains? all-text "0.015%"))
    (is (not-any? #(or (str/includes? % "#f4c430")
                       (str/includes? % "#ffe08a")
                       (str/includes? % "text-yellow"))
                  (collect-classes view)))
    (is (not-any? #(or (str/includes? % "bg-base-100/")
                       (str/includes? % "bg-base-200/")
                       (str/includes? % "bg-base-300/"))
                  (collect-classes view)))))

(deftest fee-schedule-dropdown-options-use-compact-hoverable-rows-test
  (let [view (fee-schedule-view/fee-schedule-popover sample-model)
        staking-option (hiccup/find-by-data-role
                        view
                        "portfolio-fee-schedule-staking-option-diamond")
        description-node (first-node-containing-text
                          staking-option
                          ">500k HYPE staked = 40% discount")
        current-node (first-node-containing-text
                      staking-option
                      "Current wallet staking tier")
        option-classes (hiccup/root-class-set staking-option)
        description-classes (hiccup/root-class-set description-node)
        current-classes (hiccup/root-class-set current-node)
        all-option-classes (class-set staking-option)]
    (is (contains? option-classes "flex"))
    (is (contains? option-classes "h-7"))
    (is (contains? option-classes "items-center"))
    (is (contains? option-classes "hover:bg-base-300"))
    (is (contains? option-classes "hover:text-trading-text"))
    (is (contains? option-classes "focus-visible:bg-base-300"))
    (is (contains? option-classes "focus-visible:text-trading-text"))
    (is (contains? description-classes "min-w-0"))
    (is (contains? description-classes "flex-1"))
    (is (contains? description-classes "truncate"))
    (is (contains? current-classes "ml-auto"))
    (is (not (contains? option-classes "block")))
    (is (not (contains? all-option-classes "mt-0.5")))))

(deftest fee-schedule-popover-is-absent-when-closed-test
  (is (nil? (fee-schedule-view/fee-schedule-popover (assoc sample-model :open? false)))))

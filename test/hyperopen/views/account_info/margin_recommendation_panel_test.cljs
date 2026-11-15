(ns hyperopen.views.account-info.margin-recommendation-panel-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [hyperopen.views.account-info.margin-rec-copy :as copy]
            [hyperopen.views.account-info.margin-recommendation-panel :as panel]
            [hyperopen.views.account-info.positions-vm :as positions-vm]
            [hyperopen.views.account-info.tabs.positions.desktop :as positions-desktop]))

(defn- node-children
  [node]
  (if (map? (second node))
    (drop 2 node)
    (rest node)))

(defn- all-nodes
  [root]
  (tree-seq vector? node-children root))

(defn- node-attrs
  [node]
  (when (map? (second node))
    (second node)))

(defn- node-by-role
  [root role]
  (some #(when (and (vector? %)
                    (= role (:data-role (node-attrs %))))
           %)
        (all-nodes root)))

(defn- collect-strings
  [root]
  (set (filter string? (all-nodes root))))

(defn- click-actions
  [node]
  (get-in (node-attrs node) [:on :click]))

(def rec-result
  {:status :ok
   :coin "xyz:TSM"
   :dex "xyz"
   :risk-mode :balanced
   :alpha 0.02
   :horizon {:hours 72 :source :per-coin :samples 22 :bars 72}
   :as-of {:mark 437.51 :equity 12.42 :liquidation-px 424.2
           :notional 157.5 :side :long}
   :sigma {:hourly 0.017 :daily 0.083 :annualized 0.87
           :distance-frac 0.0304 :buffer-sigmas 0.74}
   :p-now 0.146
   :p-after 0.021
   :paths-count 4000
   :curve {:x-max 40
           :points [{:e 0 :p 1.0}
                    {:e 10 :p 0.35}
                    {:e 20 :p 0.02}
                    {:e 30 :p 0.005}
                    {:e 40 :p 0.001}]}
   :risk-level :high
   :recommended {:equity 18.64
                 :additional 6.22
                 :new-liquidation-px 403.1
                 :new-liq-change-frac 0.0497
                 :effective-leverage 8.4}
   :breakdown [{:key :maintenance :label "Maintenance requirement" :amount 5.41}
               {:key :adverse-path :label "Adverse-path protection" :amount 7.87}
               {:key :funding :label "Funding buffer (3d)" :amount 2.08}
               {:key :exit :label "Exit / slippage buffer (1.0% notional)" :amount 1.82}
               {:key :model :label "Model uncertainty buffer" :amount 1.46}]
   :confidence {:tier :high :n-bars 1080}})

(def rec-entry
  {:status :ok :result rec-result :computed-at 1})

(def xyz-position
  {:coin "xyz:TSM"
   :szi "0.36"
   :entryPx "446.441"
   :positionValue "157.5"
   :liquidationPx "424.20"
   :marginUsed "12.42"
   :maxLeverage 10
   :leverage {:type "isolated" :value 10}})

(def position-data
  {:position xyz-position :dex "xyz"})

(def row-vm
  (positions-vm/position-row-vm position-data))

(defn- render-panel
  [& [overrides]]
  (panel/margin-recommendation-panel
   (merge {:position-key "xyz:TSM|xyz"
           :rec rec-entry
           :row-vm row-vm
           :read-only? false
           :risk-mode :balanced
           :anchor {:left 400 :right 460 :top 500
                    :viewport-width 1440 :viewport-height 900}}
          overrides)))

(deftest panel-renders-wide-card
  (let [tree (render-panel)
        strings (collect-strings tree)]
    (is (some? (node-by-role tree "margin-rec-panel")))
    (testing "header: title, coin badge, leverage inline"
      (is (contains? strings "Margin recommendation"))
      (is (some? (node-by-role tree "margin-rec-coin")))
      (is (contains? (collect-strings (node-by-role tree "margin-rec-leverage"))
                     "10x isolated")))
    (testing "recommendation summary: headline, amount-to-add, before/after, new liq"
      (is (some? (node-by-role tree "margin-rec-summary")))
      (is (some? (node-by-role tree "margin-rec-recommended")))
      (is (contains? strings "$18.64 USDC"))
      (is (contains? strings "+50.1% vs current"))
      (testing "amount-to-add references the current margin instead of a duplicate cell"
        (is (some? (node-by-role tree "margin-rec-additional")))
        (is (contains? strings "Add $6.22 USDC to your current $12.42")))
      (testing "one before/after probability line, not separate current/after cells"
        (is (some? (node-by-role tree "margin-rec-risk-delta")))
        (is (contains? strings "Modeled liq. probability"))
        (is (contains? strings "before next intervention"))
        (is (contains? strings "14.6%"))
        (is (contains? strings "2.1%")))
      (testing "resulting liquidation price, with an unambiguous comparison base"
        (is (some? (node-by-role tree "margin-rec-new-liq")))
        (is (contains? strings "$403.10"))
        (is (contains? strings "≈ 5.0% below current liq. price"))))
    (testing "the duplicated stat cells were removed (the chart carries these)"
      (doseq [role ["margin-rec-current-stats" "margin-rec-stat-current"
                    "margin-rec-stat-liq" "margin-rec-stat-distance"
                    "margin-rec-stat-horizon" "margin-rec-stat-p-now"
                    "margin-rec-p-after" "margin-rec-recommendation"]]
        (is (nil? (node-by-role tree role)) role)))
    (testing "probability-vs-collateral chart with both markers"
      (is (some? (node-by-role tree "margin-rec-curve-card")))
      (is (some? (node-by-role tree "margin-rec-curve")))
      (is (some? (node-by-role tree "margin-rec-curve-current")))
      (is (some? (node-by-role tree "margin-rec-curve-recommended")))
      (is (contains? strings "Modeled probability of liquidation vs. collateral"))
      (is (contains? strings "Isolated margin (USDC)")))
    (testing "methodology + buffers are tucked into the advanced disclosure"
      (let [advanced (node-by-role tree "margin-rec-advanced")]
        (is (some? advanced))
        (is (= :details (first advanced)))
        (is (some? (node-by-role advanced "margin-rec-methods")))
        (is (some? (node-by-role advanced "margin-rec-buffers")))
        (is (contains? (collect-strings advanced)
                       "How we estimated this & the margin breakdown"))))
    (testing "how-we-estimated column reflects real model quantities incl. horizon"
      (is (some? (node-by-role tree "margin-rec-methods")))
      (is (contains? strings "365-day crypto volatility convention"))
      (is (contains? strings "Applied"))
      (is (contains? strings "Recent realized volatility (TSM)"))
      (is (contains? strings "87%"))
      (is (contains? strings "Scenario simulation (4,000 paths)"))
      (is (contains? strings "Monte Carlo"))
      (is (contains? strings "Trade-history-derived horizon"))
      (is (contains? strings "3 days")))
    (testing "components column now includes maintenance so amounts sum to total"
      (is (some? (node-by-role tree "margin-rec-buffers")))
      (is (some? (node-by-role tree "margin-rec-buffer-maintenance")))
      (is (contains? strings "Recommended margin components"))
      (is (contains? strings "$5.41 (29%)"))
      (is (contains? strings "$7.87 (42%)"))
      (is (contains? strings "$2.08 (11%)"))
      (is (contains? strings "$1.82 (10%)"))
      (is (contains? strings "$1.46 (8%)")))
    (testing "key elements carry styled help tooltips + an always-visible disclaimer"
      (is (some #(and (vector? %) (= "tooltip" (:role (node-attrs %))))
                (all-nodes tree)))
      (is (not-any? #(and (vector? %) (:title (node-attrs %))) (all-nodes tree)))
      (is (contains? strings (copy/tip :liq-probability)))
      (is (contains? strings (copy/tip :recommended-margin)))
      (is (contains? strings (copy/tip :risk-target)))
      (is (contains? (collect-strings (node-by-role tree "margin-rec-apply"))
                     (copy/tip :apply)))
      (is (some? (node-by-role tree "margin-rec-disclaimer"))))
    (testing "renders as a centred overlay with a computed layout style"
      (let [panel (node-by-role tree "margin-rec-panel")
            style (:style (node-attrs panel))]
        (is (= "dialog" (:role (node-attrs panel))))
        (is (contains? style :left))
        (is (contains? style :top))
        (is (contains? style :width))))
    (testing "apply opens the prefilled margin modal then dismisses the panel"
      (let [apply-node (node-by-role tree "margin-rec-apply")
            [[action-id payload placeholder] close-action] (click-actions apply-node)]
        (is (contains? strings "Apply recommendation"))
        (is (= :actions/open-position-margin-modal action-id))
        (is (= 6.22 (:prefill-margin-amount payload)))
        (is (= :add (:prefill-margin-mode payload)))
        (is (= xyz-position (:position payload)))
        (is (= :event.currentTarget/bounds placeholder))
        (is (= [:actions/close-margin-rec-panel] close-action))))
    (testing "set custom margin opens the plain margin modal, no prefill"
      (let [custom-node (node-by-role tree "margin-rec-custom")
            [[action-id payload placeholder] close-action] (click-actions custom-node)]
        (is (contains? strings "Set custom margin"))
        (is (= :actions/open-position-margin-modal action-id))
        (is (nil? (:prefill-margin-amount payload)))
        (is (= xyz-position (:position payload)))
        (is (= :event.currentTarget/bounds placeholder))
        (is (= [:actions/close-margin-rec-panel] close-action))))
    (testing "close button and Escape close the panel"
      (is (= [[:actions/close-margin-rec-panel]]
             (click-actions (node-by-role tree "margin-rec-panel-close"))))
      (is (= [[:actions/handle-margin-rec-panel-keydown [:event/key]]]
             (get-in (node-attrs (node-by-role tree "margin-rec-panel"))
                     [:on :keydown]))))
    (testing "risk-mode control marks the active mode and cites Settings"
      (is (= "true" (:aria-pressed (node-attrs (node-by-role tree "margin-rec-risk-mode-balanced")))))
      (is (= "false" (:aria-pressed (node-attrs (node-by-role tree "margin-rec-risk-mode-conservative")))))
      (is (= [[:actions/set-margin-rec-risk-mode :conservative]]
             (click-actions (node-by-role tree "margin-rec-risk-mode-conservative"))))
      (is (contains? strings "You can adjust this anytime in Settings.")))))

(deftest new-liquidation-price-always-shows-two-decimals
  (let [noisy-result (assoc-in rec-result
                               [:recommended :new-liquidation-px]
                               367.15550076)
        strings (collect-strings (render-panel {:rec {:status :ok
                                                       :result noisy-result
                                                       :computed-at 1}}))]
    (is (contains? strings "$367.16"))
    (is (not-any? #(and (string? %) (str/includes? % "367.1555")) strings))))

(deftest panel-tolerates-cached-results-without-curve
  (let [tree (render-panel {:rec {:status :ok
                                  :result (dissoc rec-result :curve)
                                  :computed-at 1}})]
    (is (some? (node-by-role tree "margin-rec-summary")))
    (is (nil? (node-by-role tree "margin-rec-curve-card")))
    (is (nil? (node-by-role tree "margin-rec-curve")))))

(def by-mode-rec-result
  (assoc rec-result
         :by-risk-mode
         {:balanced {:risk-mode :balanced :status :ok :p-after 0.021
                     :recommended {:equity 18.64 :additional 6.22
                                   :new-liquidation-px 403.1 :new-liq-change-frac 0.0497}}
          :capital-efficient {:risk-mode :capital-efficient :status :ok :p-after 0.049
                              :recommended {:equity 15.9 :additional 3.48
                                            :new-liquidation-px 408.5 :new-liq-change-frac 0.035}}}))

(deftest panel-shows-the-selected-risk-mode
  (testing "capital-efficient selection reads that mode's precomputed numbers"
    (let [tree (render-panel {:rec {:status :ok :result by-mode-rec-result :computed-at 1}
                              :risk-mode :capital-efficient})
          strings (collect-strings tree)]
      (is (contains? strings "$15.90 USDC"))
      (is (contains? strings "Add $3.48 USDC to your current $12.42"))
      (is (= "true" (:aria-pressed (node-attrs (node-by-role tree "margin-rec-risk-mode-capital-efficient")))))
      (is (not (contains? strings "$18.64 USDC")))))
  (testing "balanced selection reads the balanced numbers from the same result"
    (let [strings (collect-strings
                   (render-panel {:rec {:status :ok :result by-mode-rec-result :computed-at 1}
                                  :risk-mode :balanced}))]
      (is (contains? strings "$18.64 USDC"))
      (is (not (contains? strings "$15.90 USDC"))))))

(deftest panel-read-only-hides-actions
  (let [tree (render-panel {:read-only? true})]
    (is (nil? (node-by-role tree "margin-rec-apply")))
    (is (nil? (node-by-role tree "margin-rec-custom")))
    (is (some? (node-by-role tree "margin-rec-buffers")))))

(deftest panel-within-target-hides-apply
  (let [result (-> rec-result
                   (assoc :status :within-target)
                   (assoc-in [:recommended :additional] 0))
        tree (render-panel {:rec {:status :ok :result result :computed-at 1}})]
    (is (some? (node-by-role tree "margin-rec-within-target")))
    (is (nil? (node-by-role tree "margin-rec-apply")))
    (is (some? (node-by-role tree "margin-rec-custom")))))

(deftest panel-terminal-states
  (testing "insufficient history"
    (let [tree (render-panel {:rec {:status :insufficient-history
                                    :result {:status :insufficient-history
                                             :n-bars 20}}})]
      (is (some? (node-by-role tree "margin-rec-insufficient")))))
  (testing "still computing"
    (is (some? (node-by-role (render-panel {:rec nil}) "margin-rec-computing"))))
  (testing "error"
    (is (some? (node-by-role (render-panel {:rec {:status :error :error "boom"}})
                             "margin-rec-error")))))

(deftest desktop-row-chip-and-suggestion
  (let [positions-state {:margin-rec {:recs {"xyz:TSM|xyz" rec-entry}
                                      :panel nil
                                      :risk-mode :balanced}}
        row (positions-desktop/position-row-from-vm row-vm nil nil nil false positions-state)
        strings (collect-strings row)
        chip (node-by-role row "margin-rec-risk-chip")
        suggestion (node-by-role row "margin-rec-row-suggestion")]
    (is (some? chip))
    (is (contains? strings "Liq. risk high"))
    (testing "chip opens the popover anchored to itself"
      (is (= [[:actions/toggle-margin-rec-panel "xyz:TSM|xyz" :event.currentTarget/bounds]]
             (click-actions chip))))
    (is (some? suggestion))
    (is (contains? strings "Recommended: $18.64"))
    (testing "no chip or suggestion without a recommendation"
      (let [bare (positions-desktop/position-row-from-vm row-vm nil nil nil false {})]
        (is (nil? (node-by-role bare "margin-rec-risk-chip")))
        (is (nil? (node-by-role bare "margin-rec-row-suggestion")))))))

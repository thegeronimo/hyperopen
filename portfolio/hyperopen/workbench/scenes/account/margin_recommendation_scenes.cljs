(ns hyperopen.workbench.scenes.account.margin-recommendation-scenes
  "The isolated-margin recommendation popover rendered over explicit result
  fixtures, matching the designer's card (2026-07-12): current-state stats,
  green recommendation block, probability-vs-collateral curve, methods +
  buffers columns, risk selector, and the apply / custom actions."
  (:require [portfolio.replicant :as portfolio]
            [hyperopen.views.account-info.margin-recommendation-panel :as panel]
            [hyperopen.views.account-info.positions-vm :as positions-vm]
            [hyperopen.workbench.support.layout :as layout]))

(portfolio/configure-scenes
  {:title "Margin recommendation"
   :collection :account})

(def ^:private position-data
  {:position {:coin "xyz:TSM"
              :szi "0.36"
              :entryPx "446.441"
              :positionValue "157.5"
              :liquidationPx "424.20"
              :marginUsed "12.42"
              :maxLeverage 10
              :leverage {:type "isolated" :value 10}}
   :dex "xyz"})

(def ^:private row-vm
  (positions-vm/position-row-vm position-data))

(defn- fixture-curve
  "Smooth declining p_liq(E) fixture shaped like the real engine output."
  []
  (let [x-max 40
        n 50
        step (/ x-max (dec n))]
    {:x-max x-max
     :points (mapv (fn [i]
                     (let [e (* step i)]
                       {:e e
                        :p (min 1 (* 0.98 (js/Math.exp
                                           (- (js/Math.pow (/ e 7.2) 1.35)))))}))
                   (range n))}))

(defn- mode-rec
  [risk-mode equity p-after new-liq change-frac adverse model]
  {:risk-mode risk-mode
   :status :ok
   :p-after p-after
   :recommended {:equity equity
                 :additional (js/Math.round (* 100 (- equity 12.42)))
                 :new-liquidation-px new-liq
                 :new-liq-change-frac change-frac
                 :effective-leverage (/ 157.5 equity)}
   :breakdown [{:key :maintenance :label "Maintenance requirement" :amount 5.41}
               {:key :adverse-path :label "Adverse-path protection" :amount adverse}
               {:key :funding :label "Funding buffer (3d)" :amount 2.08}
               {:key :exit :label "Exit / slippage buffer (1.0% notional)" :amount 1.82}
               {:key :model :label "Model uncertainty buffer" :amount model}]})

(def ^:private by-risk-mode
  {:conservative (assoc-in (mode-rec :conservative 21.4 0.009 398.0 0.0617 9.55 1.72)
                           [:recommended :additional] 8.98)
   :balanced (assoc-in (mode-rec :balanced 18.64 0.021 403.1 0.0497 7.87 1.46)
                       [:recommended :additional] 6.22)
   :capital-efficient (assoc-in (mode-rec :capital-efficient 15.9 0.049 408.5 0.0352 5.62 1.05)
                                [:recommended :additional] 3.48)})

(def ^:private rec-result
  (merge
   {:coin "xyz:TSM"
    :dex "xyz"
    :horizon {:hours 72 :source :per-coin :samples 22 :bars 72}
    :as-of {:mark 437.51 :equity 12.42 :liquidation-px 424.2
            :notional 157.5 :side :long}
    :sigma {:hourly 0.0093 :daily 0.0456 :annualized 0.87
            :distance-frac 0.082 :buffer-sigmas 0.74}
    :p-now 0.146
    :paths-count 4000
    :curve (fixture-curve)
    :risk-level :high
    :confidence {:tier :high :n-bars 1080}
    :by-risk-mode by-risk-mode}
   (:balanced by-risk-mode)))

(def ^:private anchor
  {:left 620 :right 680 :top 900
   :viewport-width 1440 :viewport-height 960})

(defn- scene-shell
  [rec-entry & [overrides]]
  (layout/page-shell
   {:style {:min-height "1120px"}}
   [:div
    (panel/margin-recommendation-panel
     (merge {:position-key "xyz:TSM|xyz"
             :rec rec-entry
             :row-vm row-vm
             :read-only? false
             :risk-mode :balanced
             :anchor anchor}
            overrides))]))

(portfolio/defscene elevated-risk-recommendation
  []
  (scene-shell {:status :ok :result rec-result :computed-at 1}))

(portfolio/defscene capital-efficient-selected
  []
  (scene-shell {:status :ok :result rec-result :computed-at 1}
               {:risk-mode :capital-efficient}))

(portfolio/defscene within-target
  []
  (scene-shell {:status :ok
                :result (-> rec-result
                            (dissoc :by-risk-mode)
                            (assoc :status :within-target :p-now 0.008 :p-after 0.006)
                            (assoc-in [:as-of :equity] 18.64)
                            (assoc :recommended {:equity 18.64
                                                 :additional 0
                                                 :new-liquidation-px 403.1
                                                 :new-liq-change-frac 0.0497
                                                 :effective-leverage 8.4}))
                :computed-at 1}))

(portfolio/defscene cached-result-without-curve
  []
  (scene-shell {:status :ok
                :result (dissoc rec-result :curve)
                :computed-at 1}))

(portfolio/defscene still-computing
  []
  (scene-shell nil))

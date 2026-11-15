(ns hyperopen.workbench.scenes.account.margin-rec-batch-scenes
  "The batch liquidation-risk top-up popover rendered over explicit
  candidate fixtures: several at-risk isolated positions with their
  recommended top-ups, the shared risk-target selector, per-dex pool
  coverage footer, and the one-action apply button."
  (:require [portfolio.replicant :as portfolio]
            [hyperopen.views.account-info.margin-rec-batch-panel :as batch-panel]
            [hyperopen.workbench.support.layout :as layout]))

(portfolio/configure-scenes
  {:title "Margin batch top-up"
   :collection :account})

(def ^:private anchor
  {:left 980 :right 1110 :top 120 :bottom 148
   :viewport-width 1440 :viewport-height 960})

(defn- candidate
  [coin dex additional p-now p-after new-liq risk-level]
  {:position-key (str coin "|" (or dex "default"))
   :coin coin
   :dex dex
   :position-data {:position {:coin coin} :dex dex}
   :equity 12.42
   :additional additional
   :target-equity (+ 12.42 additional)
   :new-liquidation-px new-liq
   :p-now p-now
   :p-after p-after
   :risk-level risk-level})

(def ^:private candidates
  [(candidate "xyz:TSM" "xyz" 11.66 0.182 0.021 403.1 :high)
   (candidate "xyz:EWZ" "xyz" 7.28 0.146 0.019 31.9 :high)
   (candidate "xyz:BABA" "xyz" 4.05 0.071 0.02 84.4 :elevated)])

(defn- slice
  [& [overrides]]
  (merge {:batch {:open? true :anchor anchor :deselected #{}}
          :batch-candidates candidates
          :batch-computing-count 0
          :batch-available-pools {"xyz" 500}
          :risk-mode :balanced}
         overrides))

(defn- scene-shell
  [margin-rec]
  (layout/page-shell
   {:style {:min-height "900px"}}
   [:div (batch-panel/batch-panel margin-rec)]))

(portfolio/defscene three-at-risk-positions
  []
  (scene-shell (slice)))

(portfolio/defscene one-deselected
  []
  (scene-shell (slice {:batch {:open? true :anchor anchor
                               :deselected #{"xyz:BABA|xyz"}}})))

(portfolio/defscene insufficient-collateral
  []
  (scene-shell (slice {:batch-available-pools {"xyz" 13.2}})))

(portfolio/defscene still-modeling-others
  []
  (scene-shell (slice {:batch-computing-count 2})))

(portfolio/defscene trigger-button
  []
  (layout/page-shell
   {:style {:min-height "200px"}}
   [:div {:class ["flex" "justify-end" "p-4"]}
    (batch-panel/batch-trigger (slice {:batch {:open? false :anchor nil
                                               :deselected #{}}})
                               false)]))

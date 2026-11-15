(ns hyperopen.workbench.scenes.optimize.exposure-scenes
  "Workbench scenes for the 2D exposure-map pieces
  (views.portfolio.optimize.setup-exposure-map). Each scene builds the exposure-map view-model
  from a seeded constraint map + current exposure so every state — default (Balanced), a seeded
  gross floor, a long-bias band, an off-policy current dot, and a saved-default profile — can be
  eyeballed in isolation inside the .portfolio-optimizer scope. Since the 2026-07-10 simplified
  default view the ns exports pieces instead of one composition, so the scene shell stacks them
  all (pad + readout, bands, current line, profile, warning) with everything visible."
  (:require [portfolio.replicant :as portfolio]
            [hyperopen.workbench.support.layout :as layout]
            [hyperopen.portfolio.optimizer.application.view-model.exposure :as exposure-vm]
            [hyperopen.views.portfolio.optimize.setup-exposure-map :as exposure-map]))

(portfolio/configure-scenes
  {:title "Exposure Map"
   :collection :optimize})

(defn- shell
  [content]
  (layout/page-shell
   (layout/desktop-shell
    [:div {:class ["portfolio-optimizer" "w-full" "p-6"]}
     [:div {:style {:max-width "720px"}} content]])))

(defn- render
  [opts]
  (let [model (exposure-vm/exposure-map-model opts)]
    (shell
     [:div {:class ["space-y-3"]}
      [:div {:class ["flex" "min-w-0" "items-stretch" "gap-4"]}
       (exposure-map/pad-frame model)
       (exposure-map/readout model)]
      (exposure-map/bands-block model)
      (exposure-map/preview-block (:preview model))
      (exposure-map/profile-row (:profile model))
      (exposure-map/policy-warning (:preview model))])))

(portfolio/defscene balanced-default
  []
  (render {:constraints {:gross-max 2.0 :net-min 1.0 :net-max 1.0 :max-asset-weight 0.5}
           :current-exposure {:gross 1.8 :net 1.0}
           :highlighted-controls #{}}))

(portfolio/defscene seeded-gross-floor
  []
  (render {:constraints {:gross-min 1.91 :gross-max 1.92 :net-min 1.31 :net-max 1.42
                         :max-asset-weight 0.5}
           :current-exposure {:gross 1.88 :net 1.28}
           :highlighted-controls #{}}))

(portfolio/defscene long-bias
  []
  (render {:constraints {:gross-max 2.0 :net-min 1.25 :net-max 1.75 :max-asset-weight 0.5}
           :current-exposure {:gross 1.6 :net 1.5}
           :highlighted-controls #{}}))

(portfolio/defscene high-leverage
  []
  (render {:constraints {:gross-min 5.0 :gross-max 6.0 :net-min 1.5 :net-max 2.5
                         :max-asset-weight 0.5}
           :current-exposure {:gross 5.4 :net 1.9}
           :highlighted-controls #{}}))

(portfolio/defscene zoomed-out
  []
  (render {:constraints {:gross-max 2.0 :net-min 1.0 :net-max 1.0 :max-asset-weight 0.5}
           :current-exposure {:gross 1.8 :net 1.0}
           :highlighted-controls #{}
           :zoom-level 2}))

(portfolio/defscene off-policy-current
  []
  (render {:constraints {:gross-max 1.0 :net-min 0.0 :net-max 0.0 :max-asset-weight 0.25}
           :current-exposure {:gross 2.4 :net 1.6}
           :highlighted-controls #{}}))

(portfolio/defscene infeasible-highlight
  []
  (render {:constraints {:gross-min 2.5 :gross-max 3.0 :net-min 1.8 :net-max 2.0
                         :max-asset-weight 0.5}
           :current-exposure {:gross 1.2 :net 0.8}
           :highlighted-controls #{:gross-max :net-min}}))

(portfolio/defscene saved-default
  []
  (render {:constraints {:gross-max 2.0 :net-min 1.0 :net-max 1.0 :max-asset-weight 0.5}
           :current-exposure {:gross 1.8 :net 1.0}
           :highlighted-controls #{}
           :has-saved-default? true}))

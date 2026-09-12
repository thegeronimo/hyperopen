(ns hyperopen.views.active-asset.funding-tooltip-runtime
  (:require [hyperopen.system :as app-system]
            [nexus.registry :as nxr]))

(defn close-on-resize
  "Replicant lifecycle hook for the viewport-fixed funding tooltip."
  [pin-id]
  (fn [{:keys [replicant/life-cycle replicant/memory replicant/remember]}]
    (case life-cycle
      :replicant.life-cycle/mount
      (let [on-resize #(nxr/dispatch app-system/store
                                     nil
                                     [[:actions/set-funding-tooltip-pinned pin-id false]
                                      [:actions/set-funding-tooltip-visible pin-id false]])]
        (.addEventListener js/window "resize" on-resize)
        (remember {:on-resize on-resize}))

      :replicant.life-cycle/update
      (remember memory)

      :replicant.life-cycle/unmount
      (when-let [on-resize (:on-resize memory)]
        (.removeEventListener js/window "resize" on-resize))

      nil)))

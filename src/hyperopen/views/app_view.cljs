(ns hyperopen.views.app-view
  (:require [clojure.string :as str]
            [hyperopen.views.footer-view :as footer-view]
            [hyperopen.views.header-view :as header-view]
            [hyperopen.views.trade-view :as trade-view]))

(defn app-view [state]
  (let [route (get-in state [:router :path] "/trade")]
    [:div.h-screen.bg-base-100.flex.flex-col
     (header-view/header-view state)
     [:div {:class ["flex-1" "min-h-0" "pb-12" "flex" "flex-col"]}
      (case route
        "/trade" (trade-view/trade-view state)
        (trade-view/trade-view state))]
     (when-let [modal (get-in state [:funding-ui :modal])]
       [:div.fixed.inset-0.z-50.flex.items-center.justify-center
        [:div.absolute.inset-0.bg-black.opacity-50
         {:on {:click [[:actions/set-funding-modal nil]]}}]
        [:div.relative.bg-base-100.rounded-lg.shadow-lg.p-6.w-full.max-w-sm
         [:div.text-lg.font-semibold.mb-2 (str/capitalize (name modal))]
         [:div.text-sm.text-gray-500.mb-4 "Coming soon in Phase 2."]
         [:button.btn.btn-primary.w-full
          {:on {:click [[:actions/set-funding-modal nil]]}}
          "Close"]]])
     (footer-view/footer-view state)]))

(ns hyperopen.core
  (:require [replicant.dom :as d]))

(defonce app-state (atom {:title "Hyperopen"
                          :message "Welcome to Hyperopen - A ClojureScript app with Replicant"
                          :count 0}))

(defn app-view [state]
  [:div.min-h-screen.flex.flex-col.items-center.justify-center.bg-base-100.p-8
   [:div.text-center.space-y-6.max-w-md
    [:h1.text-4xl.font-bold.text-primary (:title state)]
    [:p.text-lg.text-base-content.opacity-80 (:message state)]
    [:div.card.bg-base-200.shadow-xl.p-6
     [:p.text-xl.mb-4 "You clicked " (:count state) " times!!"]
     [:button.btn.btn-primary.btn-lg
      {:on {:click #(swap! app-state update :count inc)}}
      "Click me!"]]]])

(defn render! []
  (d/render (.getElementById js/document "app")
            (app-view @app-state)))

(defn init []
  (println "Initializing Hyperopen...")
  (add-watch app-state :render (fn [_ _ _ _] (render!)))
  (render!)) 
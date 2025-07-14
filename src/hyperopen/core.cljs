(ns hyperopen.core
  (:require [replicant.dom :as d]))

(defonce app-state (atom {:title "Hyperopen"
                          :message "Welcome to Hyperopen - A ClojureScript app with Replicant"
                          :count 0}))

(defn app-view [state]
  [:div
   [:h1 (:title state)]
   [:p (:message state)]
   [:div
    [:p "You clicked " (:count state) " times"]
    [:button {:on {:click #(swap! app-state update :count inc)}}
     "Click me!"]]])

(defn render! []
  (d/render (.getElementById js/document "app")
            (app-view @app-state)))

(defn init []
  (println "Initializing Hyperopen...")
  (add-watch app-state :render (fn [_ _ _ _] (render!)))
  (render!)) 
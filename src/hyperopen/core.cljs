(ns hyperopen.core
  (:require [replicant.dom :as d]))

(def app-state (atom {:title "Hyperopen"
                          :message "Welcome to Hyperopen - A ClojureScript app with Replicant!!!!"
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

;; Called on first load and *after* every hot-reload
(defn ^:dev/after-load start []
  ;; (re)add any watches if needed; here we simply re-render
  (render!)
  (println "Hyperopen re-rendered after hot-reload"))

(defn init []
  (println "Initializing Hyperopen...")
  ;; Watch state so UI updates on state changes
  (add-watch app-state :render (fn [_ _ _ _] (render!)))
  ;; First render
  (start)) 
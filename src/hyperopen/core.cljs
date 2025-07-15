(ns hyperopen.core
  (:require [replicant.dom :as r]
            [nexus.registry :as nxr]))

;; App state
(defonce store (atom {:title "Hyperopen"
                      :message "Welcome to Hyperopen - A ClojureScript app with Replicant"
                      :count 0}))

;; Effects - handle side effects
(defn save [_ store path value]
  (swap! store assoc-in path value))

;; Actions - pure functions that return effects
(defn increment-count [state]
  [[:effects/save [:count] (inc (:count state))]])

;; Pure component - uses actions directly in event handlers
(defn app-view [state]
  [:div.min-h-screen.flex.flex-col.items-center.justify-center.bg-base-100.p-8
   [:div.text-center.space-y-6.max-w-md
    [:h1.text-4xl.font-bold.text-primary (:title state)]
    [:p.text-lg.text-base-content.opacity-80 (:message state)]
    [:div.card.bg-base-200.shadow-xl.p-6
     [:p.text-xl.mb-4 "You clicked " (:count state) " times"]
     [:button.btn.btn-primary.btn-lg
      {:on {:click [[:actions/increment-count]]}}
      "Click me!"]]]])

;; Register effects and actions
(nxr/register-effect! :effects/save save)
(nxr/register-action! :actions/increment-count increment-count)
(nxr/register-system->state! deref)

;; Wire up the render loop
(r/set-dispatch! #(nxr/dispatch store %1 %2))
(add-watch store ::render #(r/render (.getElementById js/document "app") (app-view %4)))

(defn reload []
  (println "Reloading Hyperopen...")
  (r/render (.getElementById js/document "app") (app-view @store)))

(defn init []
  (println "Initializing Hyperopen...")
  ;; Trigger initial render by updating the store
  (swap! store identity)) 
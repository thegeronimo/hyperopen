(ns hyperopen.workbench.support.layout
  (:require [hyperopen.workbench.support.dispatch :as dispatch]))

(defn- merge-class
  [base attrs]
  (update attrs :class (fn [extra] (into base (or extra [])))))

(defn panel-shell
  ([content]
   (panel-shell {} content))
  ([attrs content]
   [:div (merge-class ["min-h-full"
                       "rounded-2xl"
                       "border"
                       "border-base-300"
                       "bg-base-100"
                       "p-4"
                       "shadow-[0_20px_40px_rgba(0,0,0,0.2)]"]
                      attrs)
    content]))

(defn page-shell
  ([content]
   (page-shell {} content))
  ([attrs content]
   [:div (merge-class ["min-h-screen"
                       "bg-[radial-gradient(circle_at_top_left,rgba(54,197,167,0.16),transparent_36%),linear-gradient(180deg,rgba(6,20,26,0.96),rgba(6,14,20,0.98))]"
                       "p-4"
                       "text-trading-text"]
                      attrs)
    content]))

(defn mobile-shell
  ([content]
   (mobile-shell {} content))
  ([attrs content]
   [:div (merge-class ["mx-auto"
                       "w-[390px]"
                       "max-w-full"]
                      attrs)
    content]))

(defn desktop-shell
  ([content]
   (desktop-shell {} content))
  ([attrs content]
   [:div (merge-class ["mx-auto"
                       "w-full"
                       "max-w-[1280px]"]
                      attrs)
    content]))

(defn interactive-shell
  ([store reducers content]
   (interactive-shell store reducers {} content))
  ([store reducers attrs content]
   (let [scene-id* (dispatch/install-dispatch! store reducers)]
     [:div (merge-class ["min-h-full"]
                        (merge {:data-workbench-scene-id (dispatch/scene-attr store)}
                               attrs))
      content])))

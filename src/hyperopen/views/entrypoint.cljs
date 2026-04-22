(ns hyperopen.views.entrypoint
  (:require [hyperopen.app.bootstrap :as app-bootstrap]
            [hyperopen.core :as core]
            [hyperopen.views.app-view :as app-view]))

(defn install-app-view!
  []
  (app-bootstrap/set-app-view! app-view/app-view))

(defn start!
  []
  (install-app-view!)
  (core/start!))

(defn reload
  []
  (install-app-view!)
  (core/reload))

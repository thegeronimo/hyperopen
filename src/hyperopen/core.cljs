(ns hyperopen.core
  (:require [hyperopen.app.bootstrap :as app-bootstrap]
            [hyperopen.app.startup :as app-startup]
            [hyperopen.system :as app-system]))

(def make-system
  app-system/make-system)

(def system
  app-system/system)

(def store
  app-system/store)

(defn initialize-remote-data-streams!
  []
  (app-startup/initialize-remote-data-streams!
   {:runtime app-system/runtime
    :store store}))

(defn init
  []
  (app-bootstrap/ensure-runtime-bootstrapped!
   app-system/runtime
   #(app-bootstrap/bootstrap-runtime!
     {:runtime app-system/runtime
      :store store}))
  (app-startup/init!
   {:runtime app-system/runtime
    :store store}))

(defn reload
  []
  (app-bootstrap/reload!
   {:runtime app-system/runtime
    :store store}))

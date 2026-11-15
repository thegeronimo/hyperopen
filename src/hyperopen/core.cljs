(ns hyperopen.core
  (:require [hyperopen.app.bootstrap :as app-bootstrap]
            [hyperopen.app.startup :as app-startup]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.system :as app-system]
            [hyperopen.telemetry.console-warning :as console-warning]))

(def make-system
  app-system/make-system)

(def store
  app-system/store)

(defn initialize-remote-data-streams!
  []
  (app-startup/initialize-remote-data-streams!
   {:runtime app-system/runtime
    :store store}))

(defn start!
  []
  (when (runtime-state/mark-app-started! app-system/runtime)
    (console-warning/emit-warning!)
    (app-bootstrap/ensure-runtime-bootstrapped!
     app-system/runtime
     #(app-bootstrap/bootstrap-runtime!
       {:runtime app-system/runtime
        :store store}))
    (app-startup/init!
     {:runtime app-system/runtime
      :store store})))

(defn init
  []
  (start!))

(defn reload
  []
  (app-bootstrap/reload!
   {:runtime app-system/runtime
    :store store}))

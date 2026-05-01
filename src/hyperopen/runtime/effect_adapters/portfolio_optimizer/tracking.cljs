(ns hyperopen.runtime.effect-adapters.portfolio-optimizer.tracking
  (:require [hyperopen.portfolio.optimizer.application.current-portfolio :as current-portfolio]
            [hyperopen.portfolio.optimizer.application.tracking :as tracking]))

(defn refresh-portfolio-optimizer-tracking-effect
  [env _ store]
  (let [now-ms-fn (:now-ms env)
        load-tracking! (:load-tracking! env)
        save-tracking! (:save-tracking! env)
        state @store
        scenario-id (or (get-in state [:portfolio :optimizer :active-scenario :loaded-id])
                        (get-in state [:portfolio :optimizer :draft :id]))
        snapshot (tracking/build-tracking-snapshot
                  {:scenario-id scenario-id
                   :as-of-ms (now-ms-fn)
                   :saved-run (get-in state [:portfolio :optimizer :last-successful-run])
                   :current-snapshot (current-portfolio/current-portfolio-snapshot state)})]
    (if-not (= :tracked (:status snapshot))
      (do
        (swap! store assoc-in [:portfolio :optimizer :tracking] snapshot)
        (js/Promise.resolve snapshot))
      (-> (load-tracking! scenario-id)
          (.then (fn [loaded-tracking]
                   (let [tracking-record (tracking/append-tracking-snapshot
                                          loaded-tracking
                                          snapshot)]
                     (-> (save-tracking! scenario-id tracking-record)
                         (.then (fn [_]
                                  (swap! store assoc-in
                                         [:portfolio :optimizer :tracking]
                                         tracking-record)
                                  tracking-record))))))))))

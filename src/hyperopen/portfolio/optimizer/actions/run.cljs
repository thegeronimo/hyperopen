(ns hyperopen.portfolio.optimizer.actions.run
  (:require [hyperopen.portfolio.optimizer.actions.common :as common]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]
            [hyperopen.portfolio.optimizer.query-state :as optimizer-query-state]
            [hyperopen.portfolio.routes :as portfolio-routes]))

(defn set-portfolio-optimizer-results-tab
  [_state tab]
  [[:effects/save
    [:portfolio-ui :optimizer :results-tab]
    (optimizer-query-state/normalize-results-tab tab)]
   [:effects/replace-shareable-route-query]])

(defn load-portfolio-optimizer-history-from-draft
  [state]
  (if (seq (get-in state [:portfolio :optimizer :draft :universe]))
    [[:effects/load-portfolio-optimizer-history]]
    []))

(defn run-portfolio-optimizer-from-draft
  [state]
  (if (seq (get-in state [:portfolio :optimizer :draft :universe]))
    [[:effects/run-portfolio-optimizer-pipeline]]
    []))

(defn run-portfolio-optimizer-from-ready-draft
  [state]
  (let [{:keys [request runnable?]} (setup-readiness/build-readiness state)]
    (if runnable?
      [[:effects/run-portfolio-optimizer request (common/build-request-signature request)]]
      [])))

(defn save-portfolio-optimizer-scenario-from-current
  [state]
  (if (= :solved
         (get-in state [:portfolio :optimizer :last-successful-run :result :status]))
    [[:effects/save-portfolio-optimizer-scenario]]
    []))

(defn load-portfolio-optimizer-route
  [state path]
  (let [route (portfolio-routes/parse-portfolio-route path)]
    (into
     (case (:kind route)
       :optimize-index [[:effects/load-portfolio-optimizer-scenario-index]]
       :optimize-scenario [[:effects/load-portfolio-optimizer-scenario
                            (:scenario-id route)]]
       [])
     (if (contains? #{:optimize-index :optimize-new :optimize-scenario}
                    (:kind route))
       (common/vault-list-metadata-fetch-effects state)
       []))))

(defn archive-portfolio-optimizer-scenario
  [_state scenario-id]
  (common/scenario-id-effect :effects/archive-portfolio-optimizer-scenario scenario-id))

(defn duplicate-portfolio-optimizer-scenario
  [_state scenario-id]
  (common/scenario-id-effect :effects/duplicate-portfolio-optimizer-scenario scenario-id))

(defn run-portfolio-optimizer
  [_state request request-signature]
  [[:effects/run-portfolio-optimizer request request-signature]])

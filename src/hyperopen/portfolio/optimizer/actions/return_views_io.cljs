(ns hyperopen.portfolio.optimizer.actions.return-views-io
  "Actions for the Return views panel's file workflow: export the universe's
  views as JSON for a desktop tool (typically an AI agent) to fill in, and
  import the filled file back as authored user views. The decisions live in the
  pure `application.return-views-io`; these actions only assemble state and
  emit effects. Feedback lands in an inline panel note, not the global toast."
  (:require [hyperopen.platform :as platform]
            [hyperopen.portfolio.optimizer.actions.common :as common]
            [hyperopen.portfolio.optimizer.application.return-inputs :as return-inputs]
            [hyperopen.portfolio.optimizer.application.return-views :as return-views]
            [hyperopen.portfolio.optimizer.application.return-views-io :as return-views-io]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]
            [hyperopen.portfolio.optimizer.contracts :as contracts]))

(defn- draft-views
  [state]
  (vec (or (get-in state contracts/draft-return-model-views-path) [])))

(defn- black-litterman-return-model?
  [state]
  (= :black-litterman
     (get-in state (conj contracts/draft-return-model-path :kind))))

(defn- implied-return-inputs
  [state]
  (return-inputs/readiness-inputs-by-instrument
   (setup-readiness/build-readiness state)))

(defn- note-effect
  [kind message]
  [:effects/save contracts/ui-return-views-io-note-path
   {:kind kind :message message}])

(defn export-portfolio-optimizer-return-views
  [state]
  (let [universe (vec (get-in state contracts/draft-universe-path))
        now-ms (platform/now-ms)
        rows (return-views/rows
              {:universe universe
               :views (draft-views state)
               :library-entries (get-in state contracts/view-library-path)
               :implied-returns-by-instrument (implied-return-inputs state)
               :now-ms now-ms})
        payload (return-views-io/export-payload
                 {:rows rows
                  :universe universe
                  :implied-returns-by-instrument (implied-return-inputs state)
                  :now-ms now-ms})]
    (if (pos? (:count payload))
      [[:effects/download-portfolio-optimizer-return-views-file payload]
       (note-effect :success
                    (return-views-io/export-success-message (:count payload)))]
      [(note-effect :error return-views-io/export-empty-message)])))

(defn import-portfolio-optimizer-return-views
  [_state]
  [[:effects/pick-portfolio-optimizer-return-views-file]])

(defn- cleared-view-drafts
  [state applied-instrument-ids]
  (apply dissoc
         (or (get-in state contracts/ui-objective-menu-view-drafts-path) {})
         (map keyword applied-instrument-ids)))

(defn apply-imported-portfolio-optimizer-return-views
  [state data]
  (if-not (black-litterman-return-model? state)
    [(note-effect :error (return-views-io/import-error-message :views-off))]
    (let [plan (return-views-io/import-plan
                {:views (draft-views state)
                 :universe (get-in state contracts/draft-universe-path)
                 :data data})]
      (cond
        (not= :ok (:status plan))
        [(note-effect :error (return-views-io/import-error-message (:reason plan)))]

        (zero? (:applied plan))
        [(note-effect :error (return-views-io/import-success-message plan))]

        :else
        (conj (common/save-draft-path-values
               [[contracts/draft-return-model-views-path (:views plan)]
                [contracts/ui-objective-menu-view-drafts-path
                 (cleared-view-drafts state (:applied-instrument-ids plan))]])
              [:effects/sync-portfolio-optimizer-view-library
               {:upserts (vec (:upserts plan)) :removes []}]
              (note-effect :success (return-views-io/import-success-message plan)))))))

(defn dismiss-portfolio-optimizer-return-views-io-note
  [_state]
  [[:effects/save contracts/ui-return-views-io-note-path nil]])

(ns hyperopen.vol-range.application.planner
  "The single pure Vol Range planner entry point (HO-SPEC-014 v2.1 section 6.7).

  Release A0 is preview-only: `:ready` means preview-admissible. This namespace
  never builds an exchange action, claims a signing counter, writes an intent or calls
  an endpoint (VR-24)."
  (:require [hyperopen.vol-range.application.plan-core :as core]
            [hyperopen.vol-range.application.remediations :as remediations]))

(defn prepare-plan
  "Prepare a plan from injected snapshots. Input keys: :plan-id :scope :draft
  :market :account :fees :pending-intents :clock :model-version
  :precision-version :capabilities (defaults to the capability matrix plus
  {:preview? true}) :remediations? (default true). Returns a map satisfying
  `contracts/::plan-result` exactly."
  [input]
  (let [result (core/prepare-plan-core input)]
    (if (and (get input :remediations? true) (= :invalid (:status result)))
      (assoc result :remediations (remediations/offers input result))
      result)))

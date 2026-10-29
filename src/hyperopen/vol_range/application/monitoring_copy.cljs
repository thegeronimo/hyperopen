(ns hyperopen.vol-range.application.monitoring-copy
  "View copy for Vol Range live monitoring (HO-SPEC-014 v2.1 sections 9.3,
  11.2, 11.4): the dock's monitoring line, the note shown while the review or
  repair submit is disabled, and the text for a placement or repair that was
  refused because monitoring had not caught up.

  Views read monitoring without `:last-tick-ms` (the order-form slice drops
  it), so the blocker here is `monitoring/view-blocker` (status and account
  rules only). The executor still applies the full
  `monitoring/placement-blocker` with real time, at the click and at the
  final pre-send guard."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.vol-range.application.monitoring :as monitoring]))

(def review-note "Placement waits for live order and fill updates to catch up.")

(def refusal-text
  "Nothing was placed: live order and fill updates were catching up. Try again in a moment.")

(defn- lower [x] (some-> x str str/trim str/lower-case not-empty))

(defn- monitoring-map [state] (get-in state [:vol-range :execution :monitoring]))

(defn- account [state] (lower (account-context/active-trading-account-address state)))

(defn blocker
  "The `:monitoring/*` code that keeps the review and repair submit disabled,
  or nil while monitoring is live for the active trading account."
  [state]
  (monitoring/view-blocker (monitoring-map state) (account state)))

(defn monitoring-code? [x]
  (and (keyword? x) (= "monitoring" (namespace x))))

(defn monitoring-refusal?
  "True when a placement or repair result was refused (at the click, or at
  the final guard, whose codes the executor puts in `:errors` next to
  delivery's text `:error`) for a `:monitoring/*` code."
  [{:keys [errors]}]
  (boolean (some monitoring-code? errors)))

(defn- stale-text [reason]
  (case reason
    :transport-down "Live updates paused while reconnecting. Your orders stay on the exchange."
    :streams-elsewhere "Live updates paused while another account is shown."
    "Connecting live updates…"))

(defn line
  "The dock's monitoring line {:status :reason :text :attention?}, or nil with
  no trading account, or with placement off and no monitoring yet. A map for
  another account (before the watcher catches up) reads as connecting."
  [state]
  (let [m (monitoring-map state)
        a (account state)
        own? (and (some? m) (= a (lower (:account-address m))))
        status (if own? (:status m) :stale)
        reason (if own? (first (:reasons m)) :awaiting-streams)]
    (when (and a (or (some? m) (true? (get-in state [:vol-range :execution :enabled?]))))
      {:status status
       :reason reason
       :attention? (not= :live status)
       :text (case status
               :live "Live updates on"
               :resyncing (if (pos? (or (get-in m [:resync :attempt]) 0))
                            "Couldn't catch up yet. Retrying automatically."
                            "Catching up on fills and orders…")
               (stale-text reason))})))

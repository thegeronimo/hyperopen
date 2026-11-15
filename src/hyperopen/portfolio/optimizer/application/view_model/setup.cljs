(ns hyperopen.portfolio.optimizer.application.view-model.setup
  "Setup-page readiness/data-health projections and the unified run verdict.
  The summary/label projections live in view-model.setup-summary and the
  history-assumption card/rail projections in
  view-model.setup-history-assumption-cards / -rail (size-gate splits)."
  (:require [hyperopen.portfolio.optimizer.application.history-warning-policy :as warning-policy]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]))

(defn- warning-code-label
  [warning]
  (some-> (:code warning) name))

(defn- warning-message
  [readiness warning]
  (or (:message warning)
      (setup-readiness/warning-display-message (:request readiness) warning)
      (warning-code-label warning)))

(defn- readiness-copy
  [readiness]
  (case (:reason readiness)
    :missing-universe "Select a universe before running."
    :holdings-loading "Waiting for your holdings snapshot — the universe fills itself when account data arrives."
    :no-eligible-history "History starts loading as assets are included. Run Optimization retries anything still missing."
    :incomplete-history "History is incomplete for this universe. Run Optimization retries anything still missing."
    :missing-history-assumptions "Some assets need history assumptions before this universe can run."
    :history-loading "History is loading for the selected assets."
    "Optimizer inputs are ready to run."))

(defn- history-load-copy
  [history-load-state readiness]
  (if (and (= :loading (:status history-load-state))
           (:refreshing? readiness)
           (not= :blocked (:status readiness)))
    "Optimizer history is loaded; a background refresh is running."
    (case (:status history-load-state)
      :loading "Loading optimizer history for the selected assets."
      :succeeded "Optimizer history is loaded for the selected assets."
      :failed "History load failed. Existing history, if any, is retained."
      (readiness-copy readiness))))

(defn- warning-group-action
  "Remediation for a warning group, so warnings tell the user what to do next
  instead of only describing the problem. Stale/failed-fetch history is fixable
  in-app: a full history load refetches the bundle at a fresh as-of."
  [code]
  (when (contains? setup-readiness/stale-history-warning-codes code)
    {:label "Refresh history"
     :actions [[:actions/load-portfolio-optimizer-history-from-draft]]}))

(def ^:private info-warning-codes
  #{:proxy-history-used
    :vault-derived-history-used
    :funding-history-missing
    :manual-capital-base
    :missing-market-cap-prior
    :missing-current-portfolio-prior
    :stale-history
    :insufficient-common-history
    :excluded-from-alignment
    :sparse-native-history
    :optimizer-history-api-legacy-fallback})

(defn- warning-severity
  "Rank a warning group so the panel can render blocking issues, cautions, and
  informational notes distinctly instead of one undifferentiated amber wall."
  [blocking? code group]
  (cond
    blocking? :blocking
    (and (contains? warning-policy/stale-history-warning-codes code)
         (some warning-policy/stale-incident? group))
    :caution
    (contains? info-warning-codes code) :info
    :else :caution))

(def ^:private warning-code-details
  {:proxy-history-used "Used when direct history is limited."
   :stale-history "Cached history is used; refreshing rarely changes the result."
   :source-fetch-failed "Refresh retries the history provider."
   :missing-market-cap-prior "The optimizer could not load market-cap baseline data for some assets."
   :duplicate-underlying "Listings of the same asset move together, so the optimizer may split weight between them arbitrarily. Remove one in Universe."})

(defn- provider-limit-code?
  [code]
  (= :insufficient-common-history code))

(defn- warning-group-detail
  "Secondary explanation line for a group. Provider-limit warnings demote their
  raw provider message here so the headline stays human-readable."
  [readiness code group]
  (or (get warning-code-details code)
      (when (provider-limit-code? code)
        (warning-message readiness (first group)))))

(defn- warning-group-message
  [readiness code group cnt]
  (cond
    (provider-limit-code? code)
    "History source is limited — not enough shared history across the selected assets."

    (= 1 cnt)
    (warning-message readiness (first group))

    :else
    (setup-readiness/warning-code-summary code cnt)))

(defn group-readiness-warnings
  "Group the chosen readiness warnings by :code (first-seen order) so the panel shows each KIND
  once with a count and an expandable affected-asset list, instead of repeating one row per asset
  (a 13-stale-history universe used to render 13 near-identical rows). Pure projection — it does
  not touch the raw readiness lists that history-status/assumption-cards depend on."
  [readiness]
  (let [request (:request readiness)
        blocking? (boolean (seq (:blocking-warnings readiness)))
        warnings (vec (or (seq (:blocking-warnings readiness))
                          (:warnings readiness)))
        order (distinct (map :code warnings))
        by-code (group-by :code warnings)]
    (->> order
         (mapv (fn [code]
                 (let [group (get by-code code)
                       cnt (count group)]
                   (cond-> {:code code
                            :code-label (some-> code name)
                            :count cnt
                            :severity (warning-severity blocking? code group)
                            :message (warning-group-message readiness code group cnt)
                            :assets (mapv (fn [warning]
                                            {:instrument-id (:instrument-id warning)
                                             :label (setup-readiness/warning-asset-label request warning)})
                                          group)}
                     (warning-group-detail readiness code group)
                     (assoc :detail (warning-group-detail readiness code group))
                     (warning-group-action code)
                     (assoc :action (warning-group-action code))))))
         (sort-by (fn [{:keys [severity action]}]
                    [(get {:blocking 0 :caution 1 :info 2} severity 1)
                     (if action 0 1)]))
         vec)))

(defn- readiness-status
  "Overall verdict for the Data health header: can the user run, run with
  cautions, or must they fix something first. :issue-count is the number of
  warning groups, so the verdict line can read \"Ready with cautions · 3
  issues\" and stay meaningful even when the cards sit below other panels."
  [readiness history-load-state warnings]
  (let [issue-count (count (remove #(= :info (:severity %)) warnings))
        refreshing? (boolean (:refreshing? readiness))]
    (cond
      (or (contains? #{:history-loading :holdings-loading} (:reason readiness))
          (and (= :loading (:status history-load-state))
               (not refreshing?)))
      {:level :loading :label "Loading…" :issue-count issue-count}

      (= :blocked (:status readiness))
      {:level :blocked :label "Action needed" :issue-count issue-count}

      (some #(= :caution (:severity %)) warnings)
      {:level :caution :label "Ready with cautions" :issue-count issue-count}

      refreshing?
      {:level :refreshing :label "Refreshing…" :issue-count issue-count}

      :else
      {:level :ready :label "Ready to run" :issue-count issue-count})))

(defn readiness-panel-model
  [readiness history-load-state]
  (let [warnings (group-readiness-warnings readiness)]
    {:title "Data health"
     :status (readiness-status readiness history-load-state warnings)
     :copy (history-load-copy history-load-state readiness)
     :error-message (get-in history-load-state [:error :message])
     :warnings warnings}))

(defn run-verdict
  "Global run verdict shared by the footer status pill and the rail Run-summary
  Status row — ONE vocabulary for \"ready\", derived from Data health readiness
  alone. Green means nothing needs review; anything reviewable turns the
  verdict into an amber \"Ready with N warning(s)\" instead of letting a green
  \"Ready to run\" contradict a visible warning elsewhere on the page."
  [readiness history-load-state]
  (let [{:keys [level issue-count]} (:status (readiness-panel-model readiness history-load-state))
        warning-count (or issue-count 0)]
    (cond
      (= :loading level)
      {:level :loading :label "Loading…" :warning-count warning-count}

      (= :blocked level)
      {:level :blocked :label "Action needed" :warning-count warning-count}

      (and (= :refreshing level) (zero? warning-count))
      {:level :refreshing :label "Refreshing…" :warning-count 0}

      (pos? warning-count)
      {:level :caution
       :label (str "Ready with " warning-count
                   (if (= 1 warning-count) " warning" " warnings"))
       :warning-count warning-count}

      :else
      {:level :ready :label "Ready to run" :warning-count 0})))

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
           ;; Only when nothing is actually blocked. Adding an asset also
           ;; refreshes, and there "Optimizer history is loaded" would sit above
           ;; an "Action needed" verdict blaming the user for the very history
           ;; still in flight.
           (not= :blocked (:status readiness)))
    ;; Keeps the Data health panel consistent with the rail's Status row, which
    ;; reads "Refreshing…" for this state — claiming "Loading" beside a usable
    ;; bundle is the confusion this whole change removes.
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
  ;; By-design notes, not problems: the run is unaffected, the user only needs
  ;; the disclosure. Rendered muted and folded behind a collapsed "data notes"
  ;; disclosure, below cautions.
  ;;
  ;; :stale-history is a NOTE, not a caution (owner review 2026-07-04): a
  ;; refresh adds at most the newest day of data, which does not move a
  ;; covariance estimate or the allocation — only an actual fetch error
  ;; (:source-fetch-failed) is worth the user's attention.
  ;; :insufficient-common-history is likewise not user-fixable here (the
  ;; provider window is what it is), so it must not read as an action item.
  ;; :excluded-from-alignment is a disclosure that an asset was left out of the
  ;; shared estimate; the ACTIONABLE follow-up (configure a proxy/conservative
  ;; assumption) already surfaces as its own caution, so this stays a note.
  #{:proxy-history-used
    :vault-derived-history-used
    :funding-history-missing
    :manual-capital-base
    :missing-market-cap-prior
    :missing-current-portfolio-prior
    :stale-history
    :insufficient-common-history
    :excluded-from-alignment})

(defn- warning-severity
  "Rank a warning group so the panel can render blocking issues, cautions, and
  informational notes distinctly instead of one undifferentiated amber wall."
  [blocking? code group]
  (cond
    blocking? :blocking
    ;; Serve-time staleness is normally an immaterial note, but once it escalates
    ;; to an incident (>= 7 days, or the backend tagged it severity error) it
    ;; signals a failing refresh pipeline and earns the user's attention.
    (and (contains? warning-policy/stale-history-warning-codes code)
         (some warning-policy/stale-incident? group))
    :caution
    (contains? info-warning-codes code) :info
    :else :caution))

(def ^:private warning-code-details
  ;; One human sentence of context per code — what the condition means for the
  ;; run — so the headline can stay short. The stale-history line is honest
  ;; about the stakes: a refresh adds at most the newest data and rarely
  ;; changes the allocation.
  {:proxy-history-used "Used when direct history is limited."
   :stale-history "Cached history is used; refreshing rarely changes the result."
   :source-fetch-failed "Refresh retries the history provider."
   :missing-market-cap-prior "The optimizer could not load market-cap baseline data for some assets."})

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
    ;; The provider message ("CoinGecko Demo provider history window is capped
    ;; by provider tier.") is vendor telemetry; lead with the user's problem.
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
         ;; Rank by severity, then actionability: a warning the user can fix
         ;; here (Refresh history) outranks one they cannot (provider limit).
         ;; Stable sort, so first-seen order is otherwise preserved.
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
  ;; Only blocking/caution groups count as issues — informational notes are
  ;; folded away and must not inflate the verdict into looking actionable.
  (let [issue-count (count (remove #(= :info (:severity %)) warnings))
        ;; A load running over a bundle that already landed is a REFRESH, not a
        ;; cold load: the data on screen is usable and the run is allowed. Both
        ;; arms of this check have to agree, because the second one reads the
        ;; store path directly — narrowing only build-readiness would leave this
        ;; arm true and the spinner would never go away.
        refreshing? (boolean (:refreshing? readiness))]
    (cond
      (or (contains? #{:history-loading :holdings-loading} (:reason readiness))
          (and (= :loading (:status history-load-state))
               (not refreshing?)))
      {:level :loading :label "Loading…" :issue-count issue-count}

      (= :blocked (:status readiness))
      {:level :blocked :label "Action needed" :issue-count issue-count}

      ;; Cautions outrank the refresh notice deliberately. A warning is
      ;; actionable and persists; a refresh is transient and needs no response.
      ;; Reporting :refreshing here instead would let the CTA paint its green
      ;; ready tone beside visible warning cards, which is exactly the
      ;; contradiction run-verdict's docstring below forbids.
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

      ;; Runnable, so never blocked. readiness-status only reports :refreshing
      ;; when nothing needs review, so this can never preempt the amber warning
      ;; label below — but the warning-count guard makes that independent of
      ;; that ordering rather than reliant on it.
      (and (= :refreshing level) (zero? warning-count))
      {:level :refreshing :label "Refreshing…" :warning-count 0}

      (pos? warning-count)
      {:level :caution
       :label (str "Ready with " warning-count
                   (if (= 1 warning-count) " warning" " warnings"))
       :warning-count warning-count}

      :else
      {:level :ready :label "Ready to run" :warning-count 0})))

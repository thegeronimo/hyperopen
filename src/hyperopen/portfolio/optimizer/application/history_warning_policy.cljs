(ns hyperopen.portfolio.optimizer.application.history-warning-policy
  "Readiness policy for optimizer history warnings: which codes block a run,
  which map to a per-asset history status, and how several warnings on the
  same asset resolve to one status.")

(def history-blocking-warning-codes
  #{:missing-history-coin
    :missing-candle-history
    :missing-return-history
    :insufficient-candle-history
    :insufficient-return-history
    :missing-vault-address
    :missing-vault-history
    :insufficient-vault-history
    :insufficient-common-history
    :missing-native-risk-history
    :identity-ambiguous
    :instrument-kind-mismatch
    :proxy-mapping-unapproved
    :proxy-validation-failed
    :validation-failed
    :history-assumption-required
    :history-assumption-incomplete})

(def history-assumption-warning-codes
  #{:history-assumption-required
    :history-assumption-incomplete})

(def missing-history-warning-codes
  #{:missing-history-coin
    :missing-candle-history
    :missing-return-history
    :missing-vault-address
    :missing-vault-history
    :missing-native-risk-history
    :identity-ambiguous})

(def insufficient-history-warning-codes
  #{:insufficient-candle-history
    :insufficient-return-history
    :insufficient-vault-history
    :insufficient-common-history})

(def stale-history-warning-codes
  #{:stale-history
    :source-fetch-failed})

(def stale-history-incident-min-age-days
  7)

(def misaligned-history-warning-codes
  #{:excluded-from-alignment})

(defn serve-age-days
  "Serve-time staleness age in days a stale-history warning carries in
  details.serve_age_days, or nil when the backend did not report one. Lets the
  STALE surfaces read the age directly instead of recomputing it from
  served_end_ms and the request date."
  [warning]
  (let [age (get-in warning [:details :serve-age-days])]
    (when (number? age) age)))

(defn stale-incident?
  "True when a stale-history warning has escalated to an incident: the backend
  tagged it severity \"error\", or its served series is at least
  `stale-history-incident-min-age-days` days old."
  [warning]
  (and (contains? stale-history-warning-codes (:code warning))
       (boolean
        (or (contains? #{"error" :error} (:severity warning))
            (when-let [age (serve-age-days warning)]
              (>= age stale-history-incident-min-age-days))))))

(def rejected-history-warning-codes
  #{:instrument-kind-mismatch
    :proxy-mapping-unapproved
    :proxy-validation-failed
    :rejected
    :validation-failed})

(defn warning-history-status
  [warning]
  (cond
    (:forgiven? warning)
    nil

    (contains? missing-history-warning-codes (:code warning))
    :missing

    (contains? insufficient-history-warning-codes (:code warning))
    :insufficient

    (contains? misaligned-history-warning-codes (:code warning))
    :loaded-but-misaligned

    (contains? stale-history-warning-codes (:code warning))
    (if (stale-incident? warning) :stale-critical :stale)

    (contains? rejected-history-warning-codes (:code warning))
    :rejected

    :else
    nil))

(def ^:private warning-status-severity
  {:rejected 5
   :missing 4
   :insufficient 3
   :loaded-but-misaligned 2
   :stale-critical 1
   :stale 0})

(defn strongest-warning-status-by-id
  "Per-asset warning-derived history status, resolving multiple warnings on the
  same asset by severity rather than input order."
  [warnings]
  (reduce (fn [acc warning]
            (let [instrument-id (:instrument-id warning)
                  status (when instrument-id
                           (warning-history-status warning))]
              (if (and status
                       (> (get warning-status-severity status)
                          (get warning-status-severity (get acc instrument-id) -1)))
                (assoc acc instrument-id status)
                acc)))
          {}
          warnings))

(ns hyperopen.api.endpoints.account.staking
  (:require [clojure.string :as str]
            [hyperopen.api.endpoints.account.common :refer [normalize-address optional-number parse-ms]]
            [hyperopen.api.request-policy :as request-policy]))

(defn- normalize-validator-stats-window
  [window]
  (when (map? window)
    {:uptime-fraction (optional-number (or (:uptimeFraction window)
                                           (:uptime-fraction window)))
     :predicted-apr (optional-number (or (:predictedApr window)
                                         (:predicted-apr window)))
     :sample-count (some parse-ms
                         [(:nSamples window)
                          (:sampleCount window)
                          (:sample-count window)])}))

(def ^:private validator-stake-scale
  100000000)

(defn- normalize-validator-stake
  [value]
  (when-let [stake (optional-number value)]
    (if (> stake 1000000000)
      (/ stake validator-stake-scale)
      stake)))

(defn- stats-window-token
  [value]
  (some-> (if (keyword? value)
            (name value)
            value)
          str
          str/lower-case))

(defn- validator-stats-entry-name
  [entry]
  (cond
    (and (vector? entry)
         (= 2 (count entry)))
    (first entry)

    (map? entry)
    (or (:window entry)
        (:name entry)
        (:period entry)
        (:key entry))

    :else
    nil))

(defn- validator-stats-entry-payload
  [entry]
  (cond
    (and (vector? entry)
         (= 2 (count entry)))
    (second entry)

    (map? entry)
    (or (:stats entry)
        (:value entry)
        entry)

    :else
    nil))

(defn- validator-stats-window
  [stats target-window]
  (let [target-token (stats-window-token target-window)]
    (cond
      (map? stats)
      (or (get stats target-window)
          (get stats target-token))

      (sequential? stats)
      (some (fn [entry]
              (when (= target-token
                       (stats-window-token
                        (validator-stats-entry-name entry)))
                (validator-stats-entry-payload entry)))
            stats)

      :else
      nil)))

(defn- normalize-validator-summary-row
  [row]
  (when (map? row)
    (let [validator (normalize-address (:validator row))
          signer (normalize-address (:signer row))
          stats (:stats row)]
      (when (seq validator)
        {:validator validator
         :signer signer
         :name (some-> (:name row) str str/trim not-empty)
         :description (some-> (:description row) str str/trim not-empty)
         :stake (normalize-validator-stake (:stake row))
         :is-active? (true? (:isActive row))
         :is-jailed? (true? (:isJailed row))
         :commission (optional-number (:commission row))
         :stats {:day (normalize-validator-stats-window
                       (validator-stats-window stats :day))
                 :week (normalize-validator-stats-window
                        (validator-stats-window stats :week))
                 :month (normalize-validator-stats-window
                         (validator-stats-window stats :month))}}))))

(defn- normalize-validator-summaries
  [payload]
  (if (sequential? payload)
    (->> payload
         (keep normalize-validator-summary-row)
         vec)
    []))

(defn- normalize-delegator-summary
  [payload]
  (if (map? payload)
    {:delegated (optional-number (:delegated payload))
     :undelegated (optional-number (:undelegated payload))
     :total-pending-withdrawal (optional-number (or (:totalPendingWithdrawal payload)
                                                    (:total-pending-withdrawal payload)))
     :pending-withdrawals (some parse-ms
                                [(:nPendingWithdrawals payload)
                                 (:pendingWithdrawals payload)
                                 (:pending-withdrawals payload)])}
    nil))

(defn- normalize-delegation-row
  [row]
  (when (map? row)
    (let [validator (normalize-address (:validator row))]
      (when (seq validator)
        {:validator validator
         :amount (optional-number (:amount row))
         :locked-until-timestamp (some parse-ms
                                       [(:lockedUntilTimestamp row)
                                        (:lockedUntil row)
                                        (:locked-until-timestamp row)])}))))

(defn- normalize-delegations
  [payload]
  (if (sequential? payload)
    (->> payload
         (keep normalize-delegation-row)
         vec)
    []))

(defn- normalize-delegator-reward-row
  [row]
  (when (map? row)
    {:time-ms (some parse-ms [(:time row) (:time-ms row)])
     :source (keyword (str (or (:source row) "unknown")))
     :total-amount (optional-number (or (:totalAmount row)
                                        (:total-amount row)
                                        (:amount row)))}))

(defn- normalize-delegator-rewards
  [payload]
  (if (sequential? payload)
    (->> payload
         (keep normalize-delegator-reward-row)
         (sort-by :time-ms >)
         vec)
    []))

(def ^:private delegator-history-delta-keys
  [:delegate :cDeposit :cWithdraw :withdrawal])

(defn- delegator-history-delta-entry
  [delta entry-key]
  (let [entry (get delta entry-key)]
    (when (map? entry)
      entry)))

(defn- first-delegator-history-delta-entry
  [delta]
  (some (fn [entry-key]
          (when-let [entry (delegator-history-delta-entry delta entry-key)]
            [entry-key entry]))
        delegator-history-delta-keys))

(defn- normalize-delegate-history-delta
  [delegate]
  (let [undelegate? (true? (:isUndelegate delegate))]
    {:kind (if undelegate?
             :undelegate
             :delegate)
     :validator (normalize-address (:validator delegate))
     :amount (optional-number (:amount delegate))
     :is-undelegate? undelegate?}))

(defn- normalize-c-deposit-history-delta
  [c-deposit]
  {:kind :deposit
   :amount (optional-number (:amount c-deposit))})

(defn- normalize-c-withdraw-history-delta
  [c-withdraw]
  {:kind :withdraw
   :amount (optional-number (:amount c-withdraw))})

(defn- normalize-withdrawal-history-delta
  [withdrawal]
  {:kind :withdrawal
   :amount (optional-number (:amount withdrawal))
   :phase (keyword (str (or (:phase withdrawal) "unknown")))})

(defn- normalize-delegator-history-delta
  [delta]
  (let [[entry-key entry] (first-delegator-history-delta-entry delta)]
    (case entry-key
      :delegate (normalize-delegate-history-delta entry)
      :cDeposit (normalize-c-deposit-history-delta entry)
      :cWithdraw (normalize-c-withdraw-history-delta entry)
      :withdrawal (normalize-withdrawal-history-delta entry)
      {:kind :unknown
       :raw delta})))

(defn- normalize-delegator-history-row
  [row]
  (when (map? row)
    (let [time-ms (some parse-ms [(:time row) (:time-ms row)])]
      (when (number? time-ms)
        {:time-ms time-ms
         :hash (some-> (:hash row) str str/trim not-empty)
         :delta (normalize-delegator-history-delta
                 (if (map? (:delta row))
                   (:delta row)
                   {}))}))))

(defn- normalize-delegator-history
  [payload]
  (if (sequential? payload)
    (->> payload
         (keep normalize-delegator-history-row)
         (sort-by :time-ms >)
         vec)
    []))

(defn request-staking-validator-summaries!
  [post-info! opts]
  (let [opts* (request-policy/apply-info-request-policy
               :validator-summaries
               (merge {:priority :high
                       :dedupe-key :validator-summaries}
                      opts))]
    (-> (post-info! {"type" "validatorSummaries"}
                    opts*)
        (.then normalize-validator-summaries))))

(defn request-staking-delegator-summary!
  [post-info! address opts]
  (if-not address
    (js/Promise.resolve nil)
    (let [requested-address (some-> address str str/lower-case)
          opts* (request-policy/apply-info-request-policy
                 :delegator-summary
                 (merge {:priority :high
                         :dedupe-key [:delegator-summary requested-address]}
                        opts))]
      (-> (post-info! {"type" "delegatorSummary"
                       "user" address}
                      opts*)
          (.then normalize-delegator-summary)))))

(defn request-staking-delegations!
  [post-info! address opts]
  (if-not address
    (js/Promise.resolve [])
    (let [requested-address (some-> address str str/lower-case)
          opts* (request-policy/apply-info-request-policy
                 :delegations
                 (merge {:priority :high
                         :dedupe-key [:delegations requested-address]}
                        opts))]
      (-> (post-info! {"type" "delegations"
                       "user" address}
                      opts*)
          (.then normalize-delegations)))))

(defn request-staking-delegator-rewards!
  [post-info! address opts]
  (if-not address
    (js/Promise.resolve [])
    (let [requested-address (some-> address str str/lower-case)
          opts* (request-policy/apply-info-request-policy
                 :delegator-rewards
                 (merge {:priority :high
                         :dedupe-key [:delegator-rewards requested-address]}
                        opts))]
      (-> (post-info! {"type" "delegatorRewards"
                       "user" address}
                      opts*)
          (.then normalize-delegator-rewards)))))

(defn request-staking-delegator-history!
  [post-info! address opts]
  (if-not address
    (js/Promise.resolve [])
    (let [requested-address (some-> address str str/lower-case)
          opts* (request-policy/apply-info-request-policy
                 :delegator-history
                 (merge {:priority :high
                         :dedupe-key [:delegator-history requested-address]}
                        opts))]
      (-> (post-info! {"type" "delegatorHistory"
                       "user" address}
                      opts*)
          (.then normalize-delegator-history)))))

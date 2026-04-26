(ns hyperopen.api.endpoints.account
  (:require [clojure.string :as str]
            [hyperopen.api.request-policy :as request-policy]
            [hyperopen.wallet.agent-session :as agent-session]
            [hyperopen.platform :as platform]))

(defn- wait-ms
  [delay-ms]
  (js/Promise.
   (fn [resolve _reject]
     (platform/set-timeout! resolve (max 0 (or delay-ms 0))))))

(defn- parse-ms
  [value]
  (let [parsed (cond
                 (integer? value) value
                 (and (number? value)
                      (not (js/isNaN value))) value
                 (string? value) (js/parseInt (str/trim value) 10)
                 :else js/NaN)]
    (when (and (number? parsed)
               (not (js/isNaN parsed)))
      (js/Math.floor parsed))))

(defn- normalize-extra-agent-address
  [row]
  (some-> (or (:address row)
              (:agentAddress row)
              (:walletAddress row))
          str
          str/trim
          str/lower-case
          not-empty))

(defn- normalize-extra-agent-name
  [row]
  (some-> (or (:name row)
              (:agentName row)
              (:walletName row)
              (:label row))
          str
          str/trim
          not-empty))

(defn- normalize-extra-agent-valid-until
  [row]
  (some parse-ms
        [(:validUntil row)
         (:valid-until row)
         (:agentValidUntil row)
         (:validUntilMs row)
         (:valid-until-ms row)]))

(def ^:private extra-agent-collection-keys
  [:extraAgents :extra-agents :agents :wallets])

(defn- first-sequential
  [candidates]
  (some #(when (sequential? %) %) candidates))

(defn- extra-agent-candidates
  [payload]
  (let [data (:data payload)]
    (concat (map #(get payload %) extra-agent-collection-keys)
            (when (map? data)
              (map #(get data %) extra-agent-collection-keys))
            [data])))

(defn- extra-agents-seq
  [payload]
  (cond
    (sequential? payload)
    payload

    (map? payload)
    (or (first-sequential (extra-agent-candidates payload))
        [])

    :else
    []))

(defn- normalize-extra-agent-row
  [row]
  (when (map? row)
    (let [approval-name (normalize-extra-agent-name row)
          {:keys [name valid-until-ms]}
          (agent-session/parse-agent-name-valid-until approval-name)
          explicit-valid-until-ms (normalize-extra-agent-valid-until row)
          address (normalize-extra-agent-address row)]
      (when address
        {:row-kind :named
         :name (or name approval-name)
         :approval-name approval-name
         :address address
         :valid-until-ms (or explicit-valid-until-ms valid-until-ms)}))))

(defn- normalize-extra-agents
  [payload]
  (->> (extra-agents-seq payload)
       (keep normalize-extra-agent-row)
       vec))

(defn- normalize-user-webdata2-payload
  [payload]
  (if (map? payload)
    payload
    {}))

(defn- normalize-address
  [value]
  (let [text (some-> value str str/trim str/lower-case)]
    (when (and (seq text)
               (re-matches #"^0x[0-9a-f]{40}$" text))
      text)))

(defn- optional-number
  [value]
  (let [parsed (cond
                 (number? value) value
                 (string? value) (js/Number (str/trim value))
                 :else js/NaN)]
    (when (and (number? parsed)
               (not (js/isNaN parsed))
               (js/isFinite parsed))
      parsed)))

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

(defn- strip-user-funding-pagination-opts
  [opts]
  (dissoc (or opts {})
          :wait-ms-fn
          :user-funding-page-min-delay-ms
          :user-funding-page-max-delay-ms
          :user-funding-page-size))

(defn- user-funding-page-delay-ms
  [rows opts]
  (let [{:keys [min-delay-ms max-delay-ms page-size]}
        (request-policy/user-funding-pagination-policy opts)
        row-count (max 0 (count (or rows [])))
        load-factor (max 1 (/ row-count page-size))
        scaled-delay-ms (js/Math.ceil (* min-delay-ms load-factor))]
    (-> scaled-delay-ms
        (max min-delay-ms)
        (min max-delay-ms))))

(defn- user-funding-request-body
  [address start-time-ms end-time-ms]
  (cond-> {"type" "userFunding"
           "user" address}
    (number? start-time-ms) (assoc "startTime" (js/Math.floor start-time-ms))
    (number? end-time-ms) (assoc "endTime" (js/Math.floor end-time-ms))))

(defn- fetch-user-funding-page!
  [post-info! address start-time-ms end-time-ms opts]
  (let [requested-address (some-> address str str/lower-case)
        start-time* (when (number? start-time-ms)
                      (js/Math.floor start-time-ms))
        end-time* (when (number? end-time-ms)
                    (js/Math.floor end-time-ms))
        request-opts (strip-user-funding-pagination-opts opts)
        opts* (request-policy/apply-info-request-policy
               :user-funding-history
               (merge {:priority :high
                       :dedupe-key [:user-funding-history requested-address start-time* end-time*]}
                      request-opts))]
    (post-info! (user-funding-request-body address start-time-ms end-time-ms)
                opts*)))

(defn- funding-history-seq
  [payload]
  (cond
    (sequential? payload)
    payload

    (map? payload)
    (let [data (:data payload)
          nested (or (:fundings payload)
                     (:userFunding payload)
                     (:userFundings payload)
                     (when (map? data)
                       (or (:fundings data)
                           (:userFunding data)
                           (:userFundings data)))
                     data)]
      (if (sequential? nested) nested []))

    :else
    []))

(defn- warn-funding-normalization-drop!
  [start-time-ms end-time-ms raw-rows]
  (let [console-object (some-> js/globalThis .-console)
        warn-fn (some-> console-object .-warn)]
    (when (and (fn? warn-fn)
               (seq raw-rows))
      (.warn console-object
             "Funding history normalization dropped all rows on a non-empty page."
             (clj->js {:event "funding-history-normalization-drop"
                       :start-time-ms start-time-ms
                       :end-time-ms end-time-ms
                       :raw-row-count (count raw-rows)})))))

(defn- fetch-user-funding-history-loop!
  [post-info! normalize-info-funding-rows-fn sort-funding-history-rows-fn
   address start-time-ms end-time-ms opts acc wait-ms-fn]
  (-> (fetch-user-funding-page! post-info! address start-time-ms end-time-ms opts)
      (.then
       (fn [payload]
         (let [raw-rows (funding-history-seq payload)
               rows (normalize-info-funding-rows-fn raw-rows)]
           (when (and (seq raw-rows)
                      (empty? rows))
             (warn-funding-normalization-drop! start-time-ms end-time-ms raw-rows))
           (if (seq rows)
             (let [max-time-ms (apply max (map :time-ms rows))
                   next-start-ms (inc max-time-ms)
                   acc* (into acc rows)
                   exhausted? (or (nil? max-time-ms)
                                  (= next-start-ms start-time-ms)
                                  (and (number? end-time-ms)
                                       (> next-start-ms end-time-ms)))]
               (if exhausted?
                 (sort-funding-history-rows-fn acc*)
                 (let [delay-ms (user-funding-page-delay-ms rows opts)]
                   (-> ((or wait-ms-fn wait-ms) delay-ms)
                       (.then
                        (fn []
                          (fetch-user-funding-history-loop! post-info!
                                                            normalize-info-funding-rows-fn
                                                            sort-funding-history-rows-fn
                                                            address
                                                            next-start-ms
                                                            end-time-ms
                                                            opts
                                                            acc*
                                                            wait-ms-fn)))))))
             (sort-funding-history-rows-fn acc)))))))

(defn request-user-funding-history!
  [post-info! normalize-info-funding-rows-fn sort-funding-history-rows-fn
   address start-time-ms end-time-ms opts]
  (if-not address
    (js/Promise.resolve [])
    (let [opts* (merge {:priority :high}
                       (or opts {}))
          wait-ms-fn (or (:wait-ms-fn opts*)
                         wait-ms)]
      (fetch-user-funding-history-loop! post-info!
                                        normalize-info-funding-rows-fn
                                        sort-funding-history-rows-fn
                                        address
                                        start-time-ms
                                        end-time-ms
                                        opts*
                                        []
                                        wait-ms-fn))))

(defn request-spot-clearinghouse-state!
  [post-info! address opts]
  (if-not address
    (js/Promise.resolve nil)
    (let [requested-address (some-> address str str/lower-case)
          opts* (request-policy/apply-info-request-policy
                 :spot-clearinghouse-state
                 (merge {:priority :high
                         :dedupe-key [:spot-clearinghouse-state requested-address]}
                        opts))]
      (post-info! {"type" "spotClearinghouseState"
                   "user" address}
                  opts*))))

(defn request-extra-agents!
  [post-info! address opts]
  (if-not address
    (js/Promise.resolve [])
    (let [requested-address (some-> address str str/lower-case)
          opts* (request-policy/apply-info-request-policy
                 :extra-agents
                 (merge {:priority :high
                         :dedupe-key [:extra-agents requested-address]}
                        opts))]
      (-> (post-info! {"type" "extraAgents"
                       "user" address}
                      opts*)
          (.then normalize-extra-agents)))))

(defn request-user-webdata2!
  [post-info! address opts]
  (if-not address
    (js/Promise.resolve {})
    (let [requested-address (some-> address str str/lower-case)
          opts* (request-policy/apply-info-request-policy
                 :user-webdata2
                 (merge {:priority :high
                         :dedupe-key [:user-webdata2 requested-address]}
                        opts))]
      (-> (post-info! {"type" "webData2"
                       "user" address}
                      opts*)
          (.then normalize-user-webdata2-payload)))))

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

(defn normalize-user-abstraction-mode
  [abstraction]
  (let [abstraction* (some-> abstraction str str/trim)]
    (case abstraction*
      "unifiedAccount" :unified
      "portfolioMargin" :unified
      ;; `dexAbstraction` is a standard (non-unified) account shape.
      "dexAbstraction" :classic
      "default" :classic
      "disabled" :classic
      :classic)))

(defn request-user-abstraction!
  [post-info! address opts]
  (if-not address
    (js/Promise.resolve nil)
    (let [requested-address (some-> address str str/lower-case)
          opts* (request-policy/apply-info-request-policy
                 :user-abstraction
                 (merge {:priority :high
                         :dedupe-key [:user-abstraction requested-address]}
                        opts))]
      (post-info! {"type" "userAbstraction"
                   "user" address}
                  opts*))))

(defn request-clearinghouse-state!
  [post-info! address dex opts]
  (if-not address
    (js/Promise.resolve nil)
    (let [requested-address (some-> address str str/lower-case)
          requested-dex* (some-> dex str str/trim)
          requested-dex (when (seq requested-dex*)
                          requested-dex*)
          dedupe-dex (some-> requested-dex str/lower-case)
          body (cond-> {"type" "clearinghouseState"
                        "user" address}
                 requested-dex (assoc "dex" requested-dex))
          opts* (request-policy/apply-info-request-policy
                 :clearinghouse-state
                 (merge {:priority :high
                         :dedupe-key [:clearinghouse-state requested-address dedupe-dex]}
                        opts))]
      (post-info! body opts*))))

(def ^:private portfolio-summary-base-key-aliases
  {"day" :day
   "week" :week
   "month" :month
   "3m" :three-month
   "3-m" :three-month
   "3month" :three-month
   "3-month" :three-month
   "threemonth" :three-month
   "three-month" :three-month
   "three-months" :three-month
   "quarter" :three-month
   "6m" :six-month
   "6-m" :six-month
   "6month" :six-month
   "6-month" :six-month
   "sixmonth" :six-month
   "six-month" :six-month
   "six-months" :six-month
   "halfyear" :six-month
   "half-year" :six-month
   "1y" :one-year
   "1-y" :one-year
   "1year" :one-year
   "1-year" :one-year
   "oneyear" :one-year
   "one-year" :one-year
   "one-years" :one-year
   "year" :one-year
   "2y" :two-year
   "2-y" :two-year
   "2year" :two-year
   "2-year" :two-year
   "twoyear" :two-year
   "two-year" :two-year
   "two-years" :two-year
   "alltime" :all-time
   "all-time" :all-time})

(defn- portfolio-summary-token
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      (-> text
          (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
          str/lower-case
          (str/replace #"[^a-z0-9]+" "-")
          (str/replace #"(^-+)|(-+$)" "")))))

(defn- split-portfolio-summary-scope
  [token]
  (cond
    (str/starts-with? token "perp-")
    {:perp? true
     :base-token (subs token 5)}

    (str/starts-with? token "perp")
    {:perp? true
     :base-token (subs token 4)}

    :else
    {:perp? false
     :base-token token}))

(defn- perp-portfolio-summary-key
  [summary-key]
  (keyword (str "perp-" (name summary-key))))

(defn- normalize-portfolio-summary-key
  [value]
  (when-let [token (portfolio-summary-token value)]
    (let [{:keys [perp? base-token]} (split-portfolio-summary-scope token)
          base-key (get portfolio-summary-base-key-aliases base-token)]
      (cond
        (and perp? base-key) (perp-portfolio-summary-key base-key)
        base-key base-key
        :else (keyword token)))))

(defn- portfolio-summary-pairs
  [payload]
  (cond
    (sequential? payload)
    (keep (fn [entry]
            (cond
              (and (sequential? entry)
                   (= 2 (count entry)))
              (let [[k v] entry]
                [k v])

              (map? entry)
              (let [account-key (or (:account entry)
                                    (:key entry)
                                    (:range entry))]
                (when account-key
                  [account-key entry]))

              :else
              nil))
          payload)

    (map? payload)
    (let [data (:data payload)]
      (cond
        (map? data)
        (seq data)

        (map? (:portfolio payload))
        (seq (:portfolio payload))

        :else
        (seq payload)))

    :else
    []))

(defn normalize-portfolio-summary
  [payload]
  (reduce (fn [acc [raw-key row]]
            (let [summary-key (normalize-portfolio-summary-key raw-key)]
              (if (and summary-key (map? row))
                (assoc acc summary-key row)
                acc)))
          {}
          (portfolio-summary-pairs payload)))

(defn request-portfolio!
  [post-info! address opts]
  (if-not address
    (js/Promise.resolve {})
    (let [requested-address (some-> address str str/lower-case)
          opts* (request-policy/apply-info-request-policy
                 :portfolio
                 (merge {:priority :high
                         :dedupe-key [:portfolio requested-address]}
                        opts))]
      (-> (post-info! {"type" "portfolio"
                       "user" address}
                      opts*)
          (.then normalize-portfolio-summary)))))

(defn request-user-fees!
  [post-info! address opts]
  (if-not address
    (js/Promise.resolve nil)
    (let [requested-address (some-> address str str/lower-case)
          opts* (request-policy/apply-info-request-policy
                 :user-fees
                 (merge {:priority :high
                         :dedupe-key [:user-fees requested-address]}
                        opts))]
      (post-info! {"type" "userFees"
                   "user" address}
                  opts*))))

(defn- non-funding-ledger-updates-seq
  [payload]
  (cond
    (sequential? payload)
    (vec payload)

    (map? payload)
    (let [data (:data payload)
          nested (or (:nonFundingLedgerUpdates payload)
                     (:userNonFundingLedgerUpdates payload)
                     (when (map? data)
                       (or (:nonFundingLedgerUpdates data)
                           (:userNonFundingLedgerUpdates data)))
                     data)]
      (if (sequential? nested)
        (vec nested)
        []))

    :else
    []))

(defn request-user-non-funding-ledger-updates!
  [post-info! address start-time-ms end-time-ms opts]
  (if-not address
    (js/Promise.resolve [])
    (let [requested-address (some-> address str str/lower-case)
          start-time* (when (number? start-time-ms)
                        (js/Math.floor start-time-ms))
          end-time* (when (number? end-time-ms)
                      (js/Math.floor end-time-ms))
          body (cond-> {"type" "userNonFundingLedgerUpdates"
                        "user" address}
                 (number? start-time*) (assoc "startTime" start-time*)
                 (number? end-time*) (assoc "endTime" end-time*))
          opts* (request-policy/apply-info-request-policy
                 :user-non-funding-ledger
                 (merge {:priority :high
                         :dedupe-key [:user-non-funding-ledger requested-address start-time* end-time*]}
                        opts))]
      (-> (post-info! body opts*)
          (.then non-funding-ledger-updates-seq)))))

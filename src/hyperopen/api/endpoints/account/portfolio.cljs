(ns hyperopen.api.endpoints.account.portfolio
  (:require [clojure.string :as str]
            [hyperopen.api.request-policy :as request-policy]))

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

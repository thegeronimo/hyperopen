(ns hyperopen.api.request-policy)

(def default-info-request-ttl-ms
  {:asset-contexts 4000
   :meta-and-asset-ctxs 4000
   :candle-snapshot 4000
   :perp-dexs 60000
   :spot-meta 60000
   :public-webdata2 30000
   :frontend-open-orders 2500
   :user-fills 5000
   :extra-agents 5000
   :user-webdata2 5000
   :validator-summaries 10000
   :delegator-summary 5000
   :delegations 5000
   :delegator-rewards 10000
   :delegator-history 10000
   :user-funding-history 5000
   :historical-orders 5000
   :clearinghouse-state 5000
   :spot-clearinghouse-state 15000
   :user-abstraction 60000
   :portfolio 8000
   :user-fees 15000
   :user-non-funding-ledger 5000
   :market-funding-history 15000
   :predicted-fundings 5000
   :vault-summaries 15000
   :user-vault-equities 5000
   :vault-details 8000
   :vault-webdata2 8000})

(def ^:private default-user-funding-page-min-delay-ms
  1250)

(def ^:private default-user-funding-page-max-delay-ms
  10000)

(def ^:private default-user-funding-page-size
  500)

(defn- normalize-int-gte
  [value floor]
  (when (number? value)
    (let [value* (js/Math.floor value)]
      (when (>= value* floor)
        value*))))

(defn default-ttl-ms
  [request-kind]
  (get default-info-request-ttl-ms request-kind))

(defn normalize-ttl-ms
  [value]
  (normalize-int-gte value 1))

(defn user-funding-pagination-policy
  [opts]
  (let [opts* (or opts {})
        min-delay-ms (or (normalize-int-gte (:user-funding-page-min-delay-ms opts*) 1)
                         default-user-funding-page-min-delay-ms)
        max-delay-candidate (or (normalize-int-gte (:user-funding-page-max-delay-ms opts*) min-delay-ms)
                                default-user-funding-page-max-delay-ms)
        max-delay-ms (max min-delay-ms max-delay-candidate)
        page-size (or (normalize-int-gte (:user-funding-page-size opts*) 1)
                      default-user-funding-page-size)]
    {:min-delay-ms min-delay-ms
     :max-delay-ms max-delay-ms
     :page-size page-size}))

(defn apply-info-request-policy
  [request-kind opts]
  (let [opts* (or opts {})
        explicit-ttl? (contains? opts* :cache-ttl-ms)
        ttl-ms (normalize-ttl-ms
                (if explicit-ttl?
                  (:cache-ttl-ms opts*)
                  (default-ttl-ms request-kind)))]
    (cond-> opts*
      explicit-ttl? (dissoc :cache-ttl-ms)
      (number? ttl-ms) (assoc :cache-ttl-ms ttl-ms))))

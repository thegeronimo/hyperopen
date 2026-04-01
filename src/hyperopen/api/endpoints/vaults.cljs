(ns hyperopen.api.endpoints.vaults
  (:require [clojure.string :as str]
            [hyperopen.api.endpoints.account :as account-endpoints]
            [hyperopen.api.request-policy :as request-policy]))

(def default-vault-index-url
  "https://stats-data.hyperliquid.xyz/Mainnet/vaults")

(def ^:private snapshot-preview-point-limit
  8)

(def ^:private conditional-vault-index-header-tokens
  #{"if-none-match"
    "if-modified-since"})

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- header-name-token
  [header-name]
  (some-> header-name
          name
          str/lower-case
          non-blank-text))

(defn- conditional-vault-index-header?
  [header-name]
  (contains? conditional-vault-index-header-tokens
             (header-name-token header-name)))

(defn- browser-origin
  []
  (some-> js/globalThis
          .-location
          .-origin
          non-blank-text))

(defn- browser-href
  []
  (some-> js/globalThis
          .-location
          .-href
          non-blank-text))

(defn- cross-origin-browser-request?
  [url]
  (when-let [origin (browser-origin)]
    (try
      (let [target-origin (some-> (js/URL. url (or (browser-href) origin))
                                  .-origin
                                  non-blank-text)]
        (and (seq target-origin)
             (not= origin target-origin)))
      (catch :default _
        false))))

(defn- strip-conditional-vault-index-headers
  [headers]
  (if (map? headers)
    (reduce-kv (fn [acc header-name value]
                 (if (conditional-vault-index-header? header-name)
                   acc
                   (assoc acc header-name value)))
               {}
               headers)
    headers))

(defn- browser-safe-vault-index-opts
  [url opts]
  (let [opts* (or opts {})
        fetch-opts (or (:fetch-opts opts*) {})
        headers (:headers fetch-opts)
        validator-headers? (and (map? headers)
                                (some conditional-vault-index-header?
                                      (keys headers)))]
    (if (and validator-headers?
             (cross-origin-browser-request? url))
      (assoc opts*
             :fetch-opts
             (-> fetch-opts
                 (assoc :headers (strip-conditional-vault-index-headers headers))
                 ;; Cross-origin conditional request headers trigger a CORS preflight
                 ;; against the live vault index. Let the browser cache revalidate
                 ;; instead of attaching custom validators directly.
                 ((fn [fetch-opts*]
                    (if (contains? fetch-opts* :cache)
                      fetch-opts*
                      (assoc fetch-opts* :cache "no-cache"))))))
      opts*)))

(defn- normalize-address
  [value]
  (some-> value non-blank-text str/lower-case))

(defn- parse-optional-num
  [value]
  (let [num (cond
              (number? value) value
              (string? value) (js/Number (str/trim value))
              :else js/NaN)]
    (when (and (number? num)
               (js/isFinite num))
      num)))

(defn- parse-optional-int
  [value]
  (when-let [n (parse-optional-num value)]
    (js/Math.floor n)))

(defn- boolean-value
  [value]
  (cond
    (true? value) true
    (false? value) false

    (string? value)
    (case (some-> value str str/trim str/lower-case)
      "true" true
      "false" false
      nil)

    :else nil))

(def ^:private snapshot-key-alias-groups
  {:day #{"day"}
   :week #{"week"}
   :month #{"month"}
   :three-month #{"3m" "3-m" "3month" "3-month" "threemonth" "three-month" "three-months" "quarter"}
   :six-month #{"6m" "6-m" "6month" "6-month" "sixmonth" "six-month" "six-months" "halfyear" "half-year"}
   :one-year #{"1y" "1-y" "1year" "1-year" "oneyear" "one-year" "one-years" "year"}
   :two-year #{"2y" "2-y" "2year" "2-year" "twoyear" "two-year" "two-years"}
   :all-time #{"alltime" "all-time"}})

(def ^:private snapshot-key-by-token
  (reduce-kv (fn [acc snapshot-key aliases]
               (reduce (fn [next-acc alias]
                         (assoc next-acc alias snapshot-key))
                       acc
                       aliases))
             {}
             snapshot-key-alias-groups))

(defn- normalize-snapshot-token
  [value]
  (some-> value
          non-blank-text
          (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
          str/lower-case
          (str/replace #"[^a-z0-9]+" "-")
          (str/replace #"(^-+)|(-+$)" "")))

(defn- normalize-snapshot-key
  [value]
  (some-> value
          normalize-snapshot-token
          snapshot-key-by-token))

(defn- normalize-pnl-values
  [values]
  (if (sequential? values)
    (->> values
         (keep parse-optional-num)
         vec)
    []))

(defn- normalize-vault-snapshot-return
  [raw tvl]
  (cond
    (not (number? raw))
    nil

    (and (number? tvl)
         (pos? tvl)
         (> (js/Math.abs raw) 1000))
    (* 100 (/ raw tvl))

    (<= (js/Math.abs raw) 1)
    (* 100 raw)

    :else
    raw))

(defn- sample-snapshot-preview-series
  [values]
  (let [values* (vec (or values []))
        value-count (count values*)]
    (cond
      (<= value-count snapshot-preview-point-limit)
      values*

      :else
      (let [last-idx (dec value-count)
            slot-count (dec snapshot-preview-point-limit)
            step (if (pos? slot-count)
                   (/ last-idx slot-count)
                   0)]
        (mapv (fn [idx]
                (let [value-idx (if (= idx slot-count)
                                  last-idx
                                  (js/Math.round (* idx step)))
                      value-idx* (max 0 (min last-idx value-idx))]
                  (nth values* value-idx*)))
              (range snapshot-preview-point-limit))))))

(defn- preview-snapshot-key?
  [snapshot-key]
  (contains? #{:day :week :month :all-time} snapshot-key))

(defn normalize-vault-snapshot-preview
  [payload tvl]
  (reduce (fn [acc entry]
            (if (and (sequential? entry)
                     (= 2 (count entry)))
              (let [[range-key values] entry
                    snapshot-key (normalize-snapshot-key range-key)]
                (if (preview-snapshot-key? snapshot-key)
                  (let [normalized-values (->> values
                                               normalize-pnl-values
                                               (keep #(normalize-vault-snapshot-return % tvl))
                                               vec)]
                    (if (seq normalized-values)
                      (assoc acc snapshot-key
                             {:series (sample-snapshot-preview-series normalized-values)
                              :last-value (peek normalized-values)})
                      acc))
                  acc))
              acc))
          {}
          (if (sequential? payload) payload [])))

(defn normalize-vault-pnls
  [payload]
  (reduce (fn [acc entry]
            (if (and (sequential? entry)
                     (= 2 (count entry)))
              (let [[range-key values] entry
                    key* (normalize-snapshot-key range-key)]
                (if key*
                  (assoc acc key* (normalize-pnl-values values))
                  acc))
              acc))
          {}
          (if (sequential? payload) payload [])))

(defn- normalize-relationship-type
  [value]
  (case (some-> value non-blank-text str/lower-case)
    "parent" :parent
    "child" :child
    :normal))

(defn normalize-vault-relationship
  [relationship]
  (let [relationship* (if (map? relationship) relationship {})
        type* (normalize-relationship-type (:type relationship*))
        data (:data relationship*)]
    (cond-> {:type type*}
      (and (= type* :parent) (map? data))
      (assoc :child-addresses
             (->> (or (:childAddresses data) [])
                  (keep normalize-address)
                  vec))

      (and (= type* :child) (map? data))
      (assoc :parent-address (normalize-address (:parentAddress data))))))

(defn normalize-vault-summary
  [payload]
  (when (map? payload)
    (when-let [vault-address (normalize-address (:vaultAddress payload))]
      {:name (or (non-blank-text (:name payload))
                 vault-address)
       :vault-address vault-address
       :leader (normalize-address (:leader payload))
       :tvl (or (parse-optional-num (:tvl payload)) 0)
       :tvl-raw (:tvl payload)
       :is-closed? (boolean (or (boolean-value (:isClosed payload))
                                false))
       :relationship (normalize-vault-relationship (:relationship payload))
       :create-time-ms (parse-optional-int (:createTimeMillis payload))})))

(defn normalize-vault-index-row
  [row]
  (when (map? row)
    (let [summary-source (if (map? (:summary row))
                           (:summary row)
                           row)
          summary (normalize-vault-summary summary-source)
          apr-source (or (:apr row) (:apr summary-source))
          pnls-source (or (:pnls row) (:pnls summary-source))]
      (when summary
        (assoc summary
               :apr (or (parse-optional-num apr-source) 0)
               :apr-raw apr-source
               :snapshot-preview-by-key (normalize-vault-snapshot-preview pnls-source
                                                                         (:tvl summary)))))))

(defn normalize-vault-index-rows
  [payload]
  (if (sequential? payload)
    (->> payload
         (keep normalize-vault-index-row)
         vec)
    []))

(defn merge-vault-index-with-summaries
  [index-rows summary-rows]
  (let [index-rows* (normalize-vault-index-rows index-rows)
        summary-rows* (normalize-vault-index-rows summary-rows)
        max-create-time-ms (or (some->> index-rows*
                                         (keep :create-time-ms)
                                         seq
                                         (apply max))
                               -1)
        recent-summary-rows (filter (fn [row]
                                      (> (or (:create-time-ms row) -1)
                                         max-create-time-ms))
                                    summary-rows*)
        merged (concat index-rows* recent-summary-rows)]
    (->> merged
         (reduce (fn [{:keys [order row-by-address]} row]
                   (let [vault-address (:vault-address row)
                         existing (get row-by-address vault-address)
                         row-time (or (:create-time-ms row) -1)
                         existing-time (or (:create-time-ms existing) -1)]
                     (cond
                       (nil? vault-address)
                       {:order order
                        :row-by-address row-by-address}

                       (nil? existing)
                       {:order (conj order vault-address)
                        :row-by-address (assoc row-by-address vault-address row)}

                       (> row-time existing-time)
                       {:order order
                        :row-by-address (assoc row-by-address vault-address row)}

                       :else
                       {:order order
                        :row-by-address row-by-address})))
                 {:order []
                  :row-by-address {}})
         ((fn [{:keys [order row-by-address]}]
            (mapv row-by-address order))))))

(defn- response-header
  [response header-name]
  (some-> response
          .-headers
          (.get header-name)
          non-blank-text))

(defn- structured-vault-index-response
  [status rows etag last-modified]
  {:status status
   :rows (normalize-vault-index-rows rows)
   :etag etag
   :last-modified last-modified})

(defn- parse-vault-index-response-with-metadata!
  [response]
  (cond
    (or (map? response)
        (sequential? response))
    (js/Promise.resolve
     (structured-vault-index-response :ok
                                      response
                                      nil
                                      nil))

    (= 304 (some-> response .-status))
    (js/Promise.resolve
     (structured-vault-index-response :not-modified
                                      []
                                      (response-header response "ETag")
                                      (response-header response "Last-Modified")))

    (and (some? response)
         (false? (.-ok response)))
    (let [status (.-status response)
          error (js/Error. (str "Vault index request failed with HTTP " status))]
      (aset error "status" status)
      (js/Promise.reject error))

    (fn? (some-> response .-json))
    (let [etag (response-header response "ETag")
          last-modified (response-header response "Last-Modified")]
      (-> (.json response)
          (.then (fn [payload]
                   (structured-vault-index-response :ok
                                                    (js->clj payload :keywordize-keys true)
                                                    etag
                                                    last-modified)))))

    :else
    (js/Promise.resolve
     (structured-vault-index-response :ok
                                      []
                                      nil
                                      nil))))

(defn request-vault-index-response!
  ([fetch-fn opts]
   (request-vault-index-response! fetch-fn default-vault-index-url opts))
  ([fetch-fn url opts]
   (let [fetch-fn* (or fetch-fn js/fetch)
         opts* (browser-safe-vault-index-opts url opts)
         init (clj->js (merge {:method "GET"}
                              (:fetch-opts opts*)))]
     (-> (fetch-fn* url init)
         (.then parse-vault-index-response-with-metadata!)))))

(defn request-vault-index!
  ([fetch-fn opts]
   (request-vault-index! fetch-fn default-vault-index-url opts))
  ([fetch-fn url opts]
   (-> (request-vault-index-response! fetch-fn url opts)
       (.then (fn [{:keys [rows]}]
                rows)))))

(defn request-vault-summaries!
  [post-info! opts]
  (-> (post-info! {"type" "vaultSummaries"}
                  (request-policy/apply-info-request-policy
                   :vault-summaries
                   (merge {:priority :high
                           :dedupe-key :vault-summaries}
                          opts)))
      (.then normalize-vault-index-rows)))

(defn normalize-user-vault-equity
  [row]
  (when (map? row)
    (when-let [vault-address (normalize-address (:vaultAddress row))]
      {:vault-address vault-address
       :equity (or (parse-optional-num (:equity row)) 0)
       :equity-raw (:equity row)
       :locked-until-ms (parse-optional-int (:lockedUntilTimestamp row))})))

(defn normalize-user-vault-equities
  [payload]
  (if (sequential? payload)
    (->> payload
         (keep normalize-user-vault-equity)
         vec)
    []))

(defn request-user-vault-equities!
  [post-info! address opts]
  (if-let [requested-address (normalize-address address)]
    (-> (post-info! {"type" "userVaultEquities"
                     "user" requested-address}
                    (request-policy/apply-info-request-policy
                     :user-vault-equities
                     (merge {:priority :high
                             :dedupe-key [:user-vault-equities requested-address]}
                            opts)))
        (.then normalize-user-vault-equities))
    (js/Promise.resolve [])))

(defn- normalize-follower-state
  [payload]
  (when (map? payload)
    (let [normalized {:user (normalize-address (:user payload))
                      :vault-equity (parse-optional-num (:vaultEquity payload))
                      :pnl (parse-optional-num (:pnl payload))
                      :all-time-pnl (parse-optional-num (:allTimePnl payload))
                      :days-following (parse-optional-int (:daysFollowing payload))
                      :vault-entry-time-ms (parse-optional-int (:vaultEntryTime payload))
                      :lockup-until-ms (parse-optional-int (:lockupUntil payload))}
          normalized* (reduce-kv (fn [acc k v]
                                   (if (nil? v)
                                     acc
                                     (assoc acc k v)))
                                 {}
                                 normalized)]
      (when (seq normalized*)
        normalized*))))

(defn- normalize-followers
  [followers]
  (if (sequential? followers)
    (->> followers
         (keep normalize-follower-state)
         vec)
    []))

(defn- followers-count
  [followers normalized-followers]
  (if (seq normalized-followers)
    (count normalized-followers)
    (or (parse-optional-int followers) 0)))

(defn normalize-vault-details
  [payload]
  (when (map? payload)
    (when-let [vault-address (normalize-address (:vaultAddress payload))]
      (let [followers (normalize-followers (:followers payload))]
        {:name (or (non-blank-text (:name payload))
                   vault-address)
         :vault-address vault-address
         :leader (normalize-address (:leader payload))
         :description (or (non-blank-text (:description payload)) "")
         :tvl (parse-optional-num (:tvl payload))
         :tvl-raw (:tvl payload)
         :portfolio (account-endpoints/normalize-portfolio-summary (:portfolio payload))
         :apr (or (parse-optional-num (:apr payload)) 0)
         :follower-state (normalize-follower-state (:followerState payload))
         :leader-fraction (parse-optional-num (:leaderFraction payload))
         :leader-commission (parse-optional-num (:leaderCommission payload))
         :followers followers
         :followers-count (followers-count (:followers payload) followers)
         :max-distributable (parse-optional-num (:maxDistributable payload))
         :max-withdrawable (parse-optional-num (:maxWithdrawable payload))
         :is-closed? (boolean (or (boolean-value (:isClosed payload))
                                  false))
         :relationship (normalize-vault-relationship (:relationship payload))
         :allow-deposits? (boolean (or (boolean-value (:allowDeposits payload))
                                       false))
         :always-close-on-withdraw? (boolean (or (boolean-value (:alwaysCloseOnWithdraw payload))
                                                 false))}))))

(defn request-vault-details!
  [post-info! vault-address opts]
  (if-let [vault-address* (normalize-address vault-address)]
    (let [opts* (or opts {})
          user-address (normalize-address (:user opts*))
          request-opts (request-policy/apply-info-request-policy
                        :vault-details
                        (merge {:priority :high
                                :dedupe-key [:vault-details vault-address* user-address]}
                               (dissoc opts* :user)))
          request-body (cond-> {"type" "vaultDetails"
                                "vaultAddress" vault-address*}
                         user-address (assoc "user" user-address))]
      (-> (post-info! request-body request-opts)
          (.then normalize-vault-details)))
    (js/Promise.resolve nil)))

(defn request-vault-webdata2!
  [post-info! vault-address opts]
  (if-let [vault-address* (normalize-address vault-address)]
    (post-info! {"type" "webData2"
                 "user" vault-address*}
                (request-policy/apply-info-request-policy
                 :vault-webdata2
                 (merge {:priority :high
                         :dedupe-key [:vault-webdata2 vault-address*]}
                        opts)))
    (js/Promise.resolve nil)))

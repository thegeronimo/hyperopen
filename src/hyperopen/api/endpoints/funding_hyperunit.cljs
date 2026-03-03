(ns hyperopen.api.endpoints.funding-hyperunit
  (:require [clojure.string :as str]))

(def default-mainnet-base-url
  "https://api.hyperunit.xyz")

(def default-testnet-base-url
  "https://api.hyperunit-testnet.xyz")

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- normalize-token
  [value]
  (some-> value non-blank-text str/lower-case))

(defn- normalize-base-url
  [value]
  (-> (or (non-blank-text value)
          default-mainnet-base-url)
      (str/replace #"/+$" "")))

(defn- key->text
  [value]
  (cond
    (keyword? value) (name value)
    (string? value) (non-blank-text value)
    :else (non-blank-text value)))

(defn- value->text
  [value]
  (cond
    (string? value) (non-blank-text value)
    (number? value) (when (js/isFinite value) (str value))
    :else nil))

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

(defn- parse-optional-bool
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

(defn- parse-json-text
  [text]
  (try
    (-> (js/JSON.parse text)
        (js->clj :keywordize-keys true))
    (catch :default _
      nil)))

(defn- response-text->payload
  [raw-text]
  (let [text (some-> raw-text str str/trim)]
    (cond
      (not (seq text))
      {}

      :else
      (or (parse-json-text text)
          {:message text}))))

(defn- parse-response-payload!
  [response]
  (cond
    (map? response)
    (js/Promise.resolve response)

    (sequential? response)
    (js/Promise.resolve response)

    (fn? (some-> response .-text))
    (-> (.text response)
        (.then response-text->payload)
        (.catch (fn [_]
                  (js/Promise.resolve {}))))

    (fn? (some-> response .-json))
    (-> (.json response)
        (.then (fn [payload]
                 (js->clj payload :keywordize-keys true)))
        (.catch (fn [_]
                  (js/Promise.resolve {}))))

    :else
    (js/Promise.resolve {})))

(defn- payload-error-message
  [payload]
  (or (non-blank-text (:error payload))
      (non-blank-text (:message payload))
      (non-blank-text (:detail payload))))

(defn- http-error-promise
  [response payload request-label]
  (let [status (or (some-> response .-status)
                   0)
        message (or (payload-error-message payload)
                    (str "HyperUnit " request-label " failed with HTTP " status))
        err (js/Error. message)]
    (aset err "status" status)
    (aset err "payload" (clj->js payload))
    (js/Promise.reject err)))

(defn- request-json!
  [fetch-fn url opts request-label]
  (let [fetch-fn* (or fetch-fn js/fetch)
        init (clj->js (merge {:method "GET"
                              :headers {"Content-Type" "application/json"}}
                             (:fetch-opts (or opts {}))))]
    (-> (fetch-fn* url init)
        (.then (fn [response]
                 (-> (parse-response-payload! response)
                     (.then (fn [payload]
                              (if (and (some? response)
                                       (some? (.-ok response))
                                       (false? (.-ok response)))
                                (http-error-promise response payload request-label)
                                payload)))))))))

(defn- encode-segment
  [value]
  (js/encodeURIComponent (or (non-blank-text value) "")))

(defn generate-address-url
  [base-url source-chain destination-chain asset destination-address]
  (str (normalize-base-url base-url)
       "/gen/"
       (encode-segment source-chain)
       "/"
       (encode-segment destination-chain)
       "/"
       (encode-segment asset)
       "/"
       (encode-segment destination-address)))

(defn operations-url
  [base-url address]
  (str (normalize-base-url base-url)
       "/operations/"
       (encode-segment address)))

(defn estimate-fees-url
  [base-url]
  (str (normalize-base-url base-url)
       "/v2/estimate-fees"))

(defn withdrawal-queue-url
  [base-url]
  (str (normalize-base-url base-url)
       "/withdrawal-queue"))

(defn- normalize-signatures
  [value]
  (if (map? value)
    (reduce-kv (fn [acc k v]
                 (if-let [k* (key->text k)]
                   (if-let [sig (non-blank-text v)]
                     (assoc acc k* sig)
                     acc)
                   acc))
               {}
               value)
    {}))

(defn normalize-generate-address-response
  [payload]
  (let [payload* (if (map? payload) payload {})
        address (non-blank-text (:address payload*))
        status (non-blank-text (:status payload*))
        signatures (normalize-signatures (:signatures payload*))
        signature-operation-id (non-blank-text (or (:signatureOperationId payload*)
                                                   (:signature-operation-id payload*)))
        signature-endpoint-error (non-blank-text (or (:signatureEndpointError payload*)
                                                     (:signature-endpoint-error payload*)))
        sufficiently-signed? (parse-optional-bool (or (:isSufficientlySigned payload*)
                                                     (:is-sufficiently-signed payload*)))
        error-message (payload-error-message payload*)]
    {:address address
     :status status
     :signatures signatures
     :signature-operation-id signature-operation-id
     :signature-endpoint-error signature-endpoint-error
     :sufficiently-signed? sufficiently-signed?
     :error error-message}))

(defn- state->key
  [value]
  (let [token (some-> value
                      non-blank-text
                      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
                      str/lower-case
                      (str/replace #"[^a-z0-9]+" "-")
                      (str/replace #"(^-+)|(-+$)" ""))]
    (when (seq token)
      (keyword token))))

(defn normalize-operation-address
  [payload]
  (when (map? payload)
    (when-let [address (non-blank-text (:address payload))]
      {:source-coin-type (normalize-token (or (:sourceCoinType payload)
                                              (:sourceChain payload)))
       :source-chain (normalize-token (:sourceChain payload))
       :destination-chain (normalize-token (:destinationChain payload))
       :address address
       :signatures (normalize-signatures (:signatures payload))})))

(defn normalize-operation
  [payload]
  (when (map? payload)
    (let [state-text (non-blank-text (:state payload))]
      {:operation-id (non-blank-text (:operationId payload))
       :op-created-at (non-blank-text (:opCreatedAt payload))
       :protocol-address (non-blank-text (:protocolAddress payload))
       :source-address (non-blank-text (:sourceAddress payload))
       :destination-address (non-blank-text (:destinationAddress payload))
       :source-chain (normalize-token (:sourceChain payload))
       :destination-chain (normalize-token (:destinationChain payload))
       :source-amount (value->text (:sourceAmount payload))
       :destination-fee-amount (value->text (:destinationFeeAmount payload))
       :sweep-fee-amount (value->text (:sweepFeeAmount payload))
       :state state-text
       :state-key (state->key state-text)
       :source-tx-hash (non-blank-text (:sourceTxHash payload))
       :source-tx-confirmations (parse-optional-int (:sourceTxConfirmations payload))
       :destination-tx-hash (non-blank-text (:destinationTxHash payload))
       :destination-tx-confirmations (parse-optional-int (:destinationTxConfirmations payload))
       :position-in-withdraw-queue (parse-optional-int (:positionInWithdrawQueue payload))
       :asset (normalize-token (:asset payload))
       :state-started-at (non-blank-text (:stateStartedAt payload))
       :state-updated-at (non-blank-text (:stateUpdatedAt payload))
       :state-next-attempt-at (non-blank-text (:stateNextAttemptAt payload))
       :broadcast-at (non-blank-text (:broadcastAt payload))
       :status (non-blank-text (:status payload))
       :error (payload-error-message payload)})))

(defn normalize-operations-response
  [payload]
  (let [payload* (if (map? payload) payload {})
        addresses (->> (or (:addresses payload*) [])
                       (keep normalize-operation-address)
                       vec)
        operations (->> (or (:operations payload*) [])
                        (keep normalize-operation)
                        vec)]
    {:addresses addresses
     :operations operations
     :error (payload-error-message payload*)}))

(defn- normalize-metric-value
  [value]
  (cond
    (number? value)
    value

    (string? value)
    (or (parse-optional-num value)
        (non-blank-text value))

    :else
    value))

(defn- normalize-metrics-map
  [payload]
  (if (map? payload)
    (reduce-kv (fn [acc k v]
                 (if-let [k* (key->text k)]
                   (assoc acc k* (normalize-metric-value v))
                   acc))
               {}
               payload)
    {}))

(defn- canonical-token
  [value]
  (-> (or value "")
      str
      str/lower-case
      (str/replace #"[^a-z0-9]+" "")))

(defn- metric-by-suffix
  [metrics suffix]
  (let [suffix* (canonical-token suffix)]
    (some (fn [[k v]]
            (when (str/ends-with? (canonical-token k) suffix*)
              v))
          metrics)))

(defn normalize-estimate-fees-response
  [payload]
  (let [payload* (if (map? payload) payload {})
        by-chain (reduce-kv
                  (fn [acc chain-key chain-metrics]
                    (if-let [chain (normalize-token (key->text chain-key))]
                      (let [metrics (normalize-metrics-map chain-metrics)
                            normalized {:chain chain
                                        :deposit-eta (some-> (metric-by-suffix metrics "deposit-eta")
                                                             non-blank-text)
                                        :withdrawal-eta (some-> (metric-by-suffix metrics "withdrawal-eta")
                                                                non-blank-text)
                                        :deposit-fee (metric-by-suffix metrics "deposit-fee")
                                        :withdrawal-fee (metric-by-suffix metrics "withdrawal-fee")
                                        :metrics metrics}]
                        (assoc acc chain normalized))
                      acc))
                  {}
                  payload*)]
    {:by-chain by-chain
     :chains (->> (vals by-chain)
                  (sort-by :chain)
                  vec)
     :error (payload-error-message payload*)}))

(defn normalize-withdrawal-queue-entry
  [chain payload]
  (when-let [chain* (normalize-token (key->text chain))]
    (let [payload* (if (map? payload) payload {})]
      {:chain chain*
       :last-withdraw-queue-operation-tx-id (non-blank-text (or (:lastWithdrawQueueOperationTxID payload*)
                                                                (:lastWithdrawQueueOperationTxId payload*)
                                                                (:last-withdraw-queue-operation-tx-id payload*)))
       :withdrawal-queue-length (or (parse-optional-int (or (:withdrawalQueueLength payload*)
                                                            (:withdrawal-queue-length payload*)))
                                    0)})))

(defn normalize-withdrawal-queue-response
  [payload]
  (let [payload* (if (map? payload) payload {})
        by-chain (reduce-kv (fn [acc chain queue-entry]
                              (if-let [normalized (normalize-withdrawal-queue-entry chain queue-entry)]
                                (assoc acc (:chain normalized) normalized)
                                acc))
                            {}
                            payload*)]
    {:by-chain by-chain
     :chains (->> (vals by-chain)
                  (sort-by :chain)
                  vec)
     :error (payload-error-message payload*)}))

(defn- reject-message
  [message]
  (js/Promise.reject (js/Error. message)))

(defn request-generate-address!
  [fetch-fn base-url {:keys [source-chain
                             src-chain
                             destination-chain
                             dst-chain
                             asset
                             destination-address
                             dst-addr]
                      :as opts}]
  (let [source-chain* (normalize-token (or source-chain src-chain))
        destination-chain* (normalize-token (or destination-chain dst-chain))
        asset* (normalize-token asset)
        destination-address* (non-blank-text (or destination-address dst-addr))]
    (cond
      (not (seq source-chain*))
      (reject-message "HyperUnit source chain is required.")

      (not (seq destination-chain*))
      (reject-message "HyperUnit destination chain is required.")

      (not (seq asset*))
      (reject-message "HyperUnit asset is required.")

      (not (seq destination-address*))
      (reject-message "HyperUnit destination address is required.")

      :else
      (-> (request-json! fetch-fn
                         (generate-address-url base-url
                                               source-chain*
                                               destination-chain*
                                               asset*
                                               destination-address*)
                         opts
                         "address request")
          (.then normalize-generate-address-response)
          (.then (fn [normalized]
                   (cond
                     (seq (:address normalized))
                     normalized

                     (seq (:error normalized))
                     (reject-message (:error normalized))

                     :else
                     (reject-message "HyperUnit address response missing address."))))))))

(defn request-operations!
  [fetch-fn base-url {:keys [address] :as opts}]
  (let [address* (non-blank-text address)]
    (if-not address*
      (js/Promise.resolve {:addresses []
                           :operations []
                           :error nil})
      (-> (request-json! fetch-fn
                         (operations-url base-url address*)
                         opts
                         "operations request")
          (.then normalize-operations-response)))))

(defn request-estimate-fees!
  [fetch-fn base-url opts]
  (-> (request-json! fetch-fn
                     (estimate-fees-url base-url)
                     opts
                     "fee estimate request")
      (.then normalize-estimate-fees-response)))

(defn request-withdrawal-queue!
  [fetch-fn base-url opts]
  (-> (request-json! fetch-fn
                     (withdrawal-queue-url base-url)
                     opts
                     "withdrawal queue request")
      (.then normalize-withdrawal-queue-response)))

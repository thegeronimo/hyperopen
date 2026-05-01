(ns hyperopen.api.endpoints.vaults.index
  (:require [clojure.string :as str]
            [hyperopen.api.endpoints.vaults.common :as common]
            [hyperopen.api.endpoints.vaults.snapshots :as snapshots]
            [hyperopen.api.request-policy :as request-policy]))

(def default-vault-index-url
  "https://stats-data.hyperliquid.xyz/Mainnet/vaults")

(def ^:private conditional-vault-index-header-tokens
  #{"if-none-match"
    "if-modified-since"})

(defn- header-name-token
  [header-name]
  (some-> header-name
          name
          str/lower-case
          common/non-blank-text))

(defn- conditional-vault-index-header?
  [header-name]
  (contains? conditional-vault-index-header-tokens
             (header-name-token header-name)))

(defn- browser-origin
  []
  (some-> js/globalThis
          .-location
          .-origin
          common/non-blank-text))

(defn- browser-href
  []
  (some-> js/globalThis
          .-location
          .-href
          common/non-blank-text))

(defn cross-origin-browser-request?
  [url]
  (when-let [origin (browser-origin)]
    (try
      (let [target-origin (some-> (js/URL. url (or (browser-href) origin))
                                  .-origin
                                  common/non-blank-text)]
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
  [cross-origin-browser-request? url opts]
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

(defn- response-header
  [response header-name]
  (some-> response
          .-headers
          (.get header-name)
          common/non-blank-text))

(defn- structured-vault-index-response
  [status rows etag last-modified]
  {:status status
   :rows (snapshots/normalize-vault-index-rows rows)
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
   (request-vault-index-response! fetch-fn url opts cross-origin-browser-request?))
  ([fetch-fn url opts cross-origin-browser-request?]
   (let [fetch-fn* (or fetch-fn js/fetch)
         opts* (browser-safe-vault-index-opts cross-origin-browser-request? url opts)
         init (clj->js (merge {:method "GET"}
                              (:fetch-opts opts*)))]
     (-> (fetch-fn* url init)
         (.then parse-vault-index-response-with-metadata!)))))

(defn request-vault-index!
  ([fetch-fn opts]
   (request-vault-index! fetch-fn default-vault-index-url opts))
  ([fetch-fn url opts]
   (request-vault-index! fetch-fn url opts cross-origin-browser-request?))
  ([fetch-fn url opts cross-origin-browser-request?]
   (-> (request-vault-index-response! fetch-fn url opts cross-origin-browser-request?)
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
      (.then snapshots/normalize-vault-index-rows)))

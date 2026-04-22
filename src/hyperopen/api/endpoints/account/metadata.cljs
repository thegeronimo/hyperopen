(ns hyperopen.api.endpoints.account.metadata
  (:require [clojure.string :as str]
            [hyperopen.api.endpoints.account.common :as common]
            [hyperopen.api.request-policy :as request-policy]
            [hyperopen.wallet.agent-session :as agent-session]))

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
  (some common/parse-ms
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

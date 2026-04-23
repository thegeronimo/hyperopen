(ns hyperopen.portfolio.optimizer.infrastructure.persistence
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.platform.indexed-db :as indexed-db]))

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- address-token
  [address]
  (account-context/normalize-address address))

(defn scenario-index-key
  [address]
  (when-let [address* (address-token address)]
    (str "scenario-index::" address*)))

(defn draft-key
  [address]
  (when-let [address* (address-token address)]
    (str "draft::" address*)))

(defn scenario-key
  [scenario-id]
  (when-let [scenario-id* (non-blank-text scenario-id)]
    (str "scenario::" scenario-id*)))

(defn tracking-key
  [scenario-id]
  (when-let [scenario-id* (non-blank-text scenario-id)]
    (str "tracking::" scenario-id*)))

(defn- get-record!
  [key]
  (if (seq key)
    (indexed-db/get-json! indexed-db/portfolio-optimizer-store key)
    (js/Promise.resolve nil)))

(defn- put-record!
  [key value]
  (if (seq key)
    (indexed-db/put-json! indexed-db/portfolio-optimizer-store key value)
    (js/Promise.resolve false)))

(defn- delete-record!
  [key]
  (if (seq key)
    (indexed-db/delete-key! indexed-db/portfolio-optimizer-store key)
    (js/Promise.resolve false)))

(defn- encode-record
  [record]
  {:encoding :edn-v1
   :payload (pr-str record)})

(defn- decode-record
  [record]
  (if (and (map? record)
           (= "edn-v1" (:encoding record))
           (string? (:payload record)))
    (reader/read-string (:payload record))
    record))

(defn- get-encoded-record!
  [key]
  (-> (get-record! key)
      (.then decode-record)))

(defn- put-encoded-record!
  [key value]
  (put-record! key (encode-record value)))

(defn load-scenario-index!
  [address]
  (get-encoded-record! (scenario-index-key address)))

(defn save-scenario-index!
  [address scenario-index]
  (put-encoded-record! (scenario-index-key address) scenario-index))

(defn load-draft!
  [address]
  (get-encoded-record! (draft-key address)))

(defn save-draft!
  [address draft]
  (put-encoded-record! (draft-key address) draft))

(defn delete-draft!
  [address]
  (delete-record! (draft-key address)))

(defn load-scenario!
  [scenario-id]
  (get-encoded-record! (scenario-key scenario-id)))

(defn save-scenario!
  [scenario-id scenario]
  (put-encoded-record! (scenario-key scenario-id) scenario))

(defn delete-scenario!
  [scenario-id]
  (delete-record! (scenario-key scenario-id)))

(defn load-tracking!
  [scenario-id]
  (get-encoded-record! (tracking-key scenario-id)))

(defn save-tracking!
  [scenario-id tracking]
  (put-encoded-record! (tracking-key scenario-id) tracking))

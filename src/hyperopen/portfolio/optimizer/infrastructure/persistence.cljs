(ns hyperopen.portfolio.optimizer.infrastructure.persistence
  (:require [cljs.reader :as reader]
            [hyperopen.account.context :as account-context]
            [hyperopen.portfolio.optimizer.coercion :as coercion]
            [hyperopen.portfolio.optimizer.application.scenario-records :as scenario-records]
            [hyperopen.portfolio.optimizer.application.scenario-state :as scenario-state]
            [hyperopen.platform.indexed-db :as indexed-db]))

(def ^:private non-blank-text coercion/non-blank-text)

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

(defn constraint-profiles-key
  "One record per wallet holds the whole {universe-hash -> profile} map of remembered
  positioning policies."
  [address]
  (when-let [address* (address-token address)]
    (str "constraint-profiles::" address*)))

(defn view-library-key
  "One record per wallet holds the whole {instrument-id -> remembered return view}
  map — the user's authored views, durable across drafts and preset switches."
  [address]
  (when-let [address* (address-token address)]
    (str "view-library::" address*)))

(defn history-cache-key
  "One record per wallet holds the last successful normalized history bundle —
  the stale-while-revalidate cache the restore funnel hydrates so a repeat
  visit does not wait ~5-10s of backend fetch before the assumption cards and
  readiness can settle."
  [address]
  (when-let [address* (address-token address)]
    (str "history-bundle::" address*)))

(defn assumption-library-key
  "One record per wallet holds the whole {instrument-id -> remembered history
  assumption} map — the user's authored proxy/conservative assumptions, durable
  across drafts and universe edits."
  [address]
  (when-let [address* (address-token address)]
    (str "assumption-library::" address*)))

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

(defn- get-all-encoded-records!
  []
  (-> (indexed-db/get-all-json! indexed-db/portfolio-optimizer-store)
      (.then (fn [records]
               (mapv decode-record records)))))

(defn- put-encoded-record!
  [key value]
  (put-record! key (encode-record value)))

(defn- scenario-record-for-address?
  [address record]
  (and (map? record)
       (= address (address-token (:address record)))
       (seq (non-blank-text (:id record)))))

(defn- recovered-scenario-index
  [address records]
  (let [address* (address-token address)
        scenario-records (->> records
                              (filter #(scenario-record-for-address? address* %))
                              (sort-by #(or (:updated-at-ms %) 0)))]
    (when (seq scenario-records)
      (reduce (fn [scenario-index scenario-record]
                (scenario-records/refresh-scenario-index-summary
                 scenario-index
                 (scenario-records/scenario-summary scenario-record)))
              (scenario-state/default-scenario-index)
              scenario-records))))

(defn- usable-scenario-index?
  [scenario-index]
  (let [{:keys [ordered-ids by-id]} scenario-index]
    (and (seq ordered-ids)
         (every? #(map? (get by-id %)) ordered-ids))))

(defn load-scenario-index!
  [address]
  (-> (get-encoded-record! (scenario-index-key address))
      (.then (fn [scenario-index]
               (if (usable-scenario-index? scenario-index)
                 scenario-index
                 (-> (get-all-encoded-records!)
                     (.then #(or (recovered-scenario-index address %)
                                 scenario-index))))))))

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

(defn load-tracking!
  [scenario-id]
  (get-encoded-record! (tracking-key scenario-id)))

(defn save-tracking!
  [scenario-id tracking]
  (put-encoded-record! (tracking-key scenario-id) tracking))

(defn load-constraint-profiles!
  [address]
  (get-encoded-record! (constraint-profiles-key address)))

(defn save-constraint-profiles!
  [address profiles]
  (put-encoded-record! (constraint-profiles-key address) profiles))

(defn load-view-library!
  [address]
  (get-encoded-record! (view-library-key address)))

(defn save-view-library!
  [address record]
  (put-encoded-record! (view-library-key address) record))

(defn load-history-cache!
  [address]
  (get-encoded-record! (history-cache-key address)))

(defn save-history-cache!
  [address record]
  (put-encoded-record! (history-cache-key address) record))

(defn load-assumption-library!
  [address]
  (get-encoded-record! (assumption-library-key address)))

(defn save-assumption-library!
  [address record]
  (put-encoded-record! (assumption-library-key address) record))

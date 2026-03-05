(ns hyperopen.account.context
  (:require [clojure.string :as str]))

(def ghost-watchlist-storage-key
  "ghost-mode-watchlist:v1")

(def ghost-last-search-storage-key
  "ghost-mode-last-search:v1")

(def ghost-mode-read-only-message
  "Ghost Mode is read-only. Stop Ghost Mode to place trades or move funds.")

(def ^:private max-watchlist-size
  50)

(def ^:private max-watchlist-label-length
  64)

(defn normalize-address
  [address]
  (let [text (some-> address str str/trim str/lower-case)]
    (when (and (seq text)
               (re-matches #"^0x[0-9a-f]{40}$" text))
      text)))

(defn normalize-watchlist-label
  [label]
  (let [text (some-> label str str/trim)]
    (when (seq text)
      (if (> (count text) max-watchlist-label-length)
        (subs text 0 max-watchlist-label-length)
        text))))

(defn normalize-watchlist-entry
  [entry]
  (let [address-source (cond
                         (string? entry) entry
                         (map? entry) (or (:address entry)
                                          (get entry "address"))
                         :else nil)
        label-source (when (map? entry)
                       (or (:label entry)
                           (get entry "label")))
        address* (normalize-address address-source)
        label* (normalize-watchlist-label label-source)]
    (when address*
      {:address address*
       :label label*})))

(defn- watchlist-entry-index
  [watchlist address]
  (first (keep-indexed (fn [idx entry]
                         (when (= address (:address entry))
                           idx))
                       watchlist)))

(defn normalize-watchlist
  [watchlist]
  (->> (or watchlist [])
       (reduce (fn [entries raw-entry]
                 (if-let [entry (normalize-watchlist-entry raw-entry)]
                   (let [address (:address entry)
                         existing-idx (watchlist-entry-index entries address)]
                     (cond
                       (some? existing-idx)
                       (let [existing (nth entries existing-idx)
                             merged-label (or (:label entry)
                                              (:label existing))]
                         (assoc entries
                                existing-idx
                                {:address address
                                 :label merged-label}))

                       (< (count entries) max-watchlist-size)
                       (conj entries entry)

                       :else
                       entries))
                   entries))
               [])
       vec))

(defn watchlist-entry-by-address
  [watchlist address]
  (let [address* (normalize-address address)]
    (some (fn [entry]
            (when (= address* (:address entry))
              entry))
          (normalize-watchlist watchlist))))

(defn upsert-watchlist-entry
  ([watchlist address label]
   (upsert-watchlist-entry watchlist address label false))
  ([watchlist address label preserve-existing-label?]
   (let [watchlist* (normalize-watchlist watchlist)
         address* (normalize-address address)]
     (if (nil? address*)
       watchlist*
       (let [label* (normalize-watchlist-label label)
             existing-idx (watchlist-entry-index watchlist* address*)
             existing (when (some? existing-idx)
                        (nth watchlist* existing-idx))
             next-label (if preserve-existing-label?
                          (or label* (:label existing))
                          label*)]
         (if (some? existing-idx)
           (assoc watchlist*
                  existing-idx
                  {:address address*
                   :label next-label})
           (normalize-watchlist
            (conj watchlist*
                  {:address address*
                   :label next-label}))))))))

(defn remove-watchlist-entry
  [watchlist address]
  (if-let [address* (normalize-address address)]
    (->> (normalize-watchlist watchlist)
         (remove #(= address* (:address %)))
         vec)
    (normalize-watchlist watchlist)))

(defn owner-address
  [state]
  (normalize-address (get-in state [:wallet :address])))

(defn ghost-address
  [state]
  (normalize-address (get-in state [:account-context :ghost-mode :address])))

(defn ghost-mode-active?
  [state]
  (let [active? (true? (get-in state [:account-context :ghost-mode :active?]))]
    (and active?
         (some? (ghost-address state)))))

(defn effective-account-address
  [state]
  (if (ghost-mode-active? state)
    (ghost-address state)
    (owner-address state)))

(defn mutations-allowed?
  [state]
  (not (ghost-mode-active? state)))

(defn mutations-blocked-message
  [state]
  (when-not (mutations-allowed? state)
    ghost-mode-read-only-message))

(defn default-account-context-state
  []
  {:ghost-mode {:active? false
                :address nil
                :started-at-ms nil}
   :ghost-ui {:modal-open? false
              :anchor nil
              :search ""
              :label ""
              :editing-watchlist-address nil
              :last-search ""
              :search-error nil}
   :watchlist []
   :watchlist-loaded? false})

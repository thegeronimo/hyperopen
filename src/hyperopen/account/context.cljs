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

(defn normalize-address
  [address]
  (let [text (some-> address str str/trim str/lower-case)]
    (when (and (seq text)
               (re-matches #"^0x[0-9a-f]{40}$" text))
      text)))

(defn normalize-watchlist
  [watchlist]
  (->> (or watchlist [])
       (map normalize-address)
       (remove nil?)
       distinct
       (take max-watchlist-size)
       vec))

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
              :search ""
              :last-search ""
              :search-error nil}
   :watchlist []
   :watchlist-loaded? false})

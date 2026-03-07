(ns hyperopen.websocket.user-runtime.common
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]))

(defn normalized-address
  [value]
  (account-context/normalize-address value))

(defn active-effective-address
  ([store]
   (active-effective-address store account-context/effective-account-address))
  ([store resolve-current-address]
   (some-> (resolve-current-address @store)
           normalized-address)))

(defn normalized-dex
  [value]
  (let [token (some-> value str str/trim)]
    (when (seq token)
      token)))

(defn message-address
  [msg]
  (or (normalized-address (:user msg))
      (normalized-address (:address msg))
      (normalized-address (:walletAddress msg))
      (normalized-address (get-in msg [:data :user]))
      (normalized-address (get-in msg [:data :address]))
      (normalized-address (get-in msg [:data :walletAddress]))
      (normalized-address (get-in msg [:data :wallet]))))

(defn message-for-active-address?
  ([store msg]
   (message-for-active-address? store msg account-context/effective-account-address))
  ([store msg resolve-current-address]
   (let [msg-address (message-address msg)
         active-address (active-effective-address store resolve-current-address)]
     (if msg-address
       (and active-address
            (= msg-address active-address))
       true))))

(defn requested-address-active?
  ([store requested-address]
   (requested-address-active? store requested-address account-context/effective-account-address))
  ([store requested-address resolve-current-address]
   (let [requested-address* (normalized-address requested-address)
         active-address (active-effective-address store resolve-current-address)]
     (and requested-address*
          active-address
          (= requested-address* active-address)))))

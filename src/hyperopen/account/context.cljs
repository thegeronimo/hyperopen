(ns hyperopen.account.context
  (:require [clojure.string :as str]
            [hyperopen.portfolio.routes :as portfolio-routes]))

(def spectate-watchlist-storage-key
  "spectate-mode-watchlist:v1")

(def spectate-last-search-storage-key
  "spectate-mode-last-search:v1")

(def spectate-mode-read-only-message
  "Spectate Mode is read-only. Stop Spectate Mode to place trades or move funds.")

(def trader-portfolio-read-only-message
  "Trader portfolio routes are read-only. Open your Portfolio to place trades or move funds.")

(def selected-subaccount-unavailable-message
  "Subaccount selection is unavailable. Refresh subaccounts or switch back to the master account.")

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

(defn spectate-address
  [state]
  (normalize-address (get-in state [:account-context :spectate-mode :address])))

(defn spectate-mode-active?
  [state]
  (let [active? (true? (get-in state [:account-context :spectate-mode :active?]))]
    (and active?
         (some? (spectate-address state)))))

(defn spectate-label
  "Saved watchlist label for the address currently being spectated, or nil when
   that address is unlabeled. Surfaces that identify the spectated account in a
   short space prefer this over a truncated address, because a name the user
   chose reads faster than `0x7c93…c8fd`."
  [state]
  (when-let [address (spectate-address state)]
    (:label (watchlist-entry-by-address
             (get-in state [:account-context :watchlist])
             address))))

(defn trader-portfolio-address
  [state]
  (portfolio-routes/trader-portfolio-address
   (get-in state [:router :path])))

(defn trader-portfolio-route-active?
  [state]
  (some? (trader-portfolio-address state)))

(defn selected-subaccount-address
  [state]
  (normalize-address (get-in state [:account-context :subaccounts :selected-address])))

(defn subaccounts-viewed-owner-address
  "Address whose master mode should govern the Sub-Accounts page.
   This mirrors the displayed Sub-Accounts master: spectate address when
   spectating, otherwise the connected wallet owner."
  [state]
  (or (when (spectate-mode-active? state)
        (spectate-address state))
      (owner-address state)))

(defn subaccounts-owner-unified?
  "True when the Sub-Accounts master/owner account is a unified
   (portfolio-margin) account, which must move funds via `sendAsset` rather
   than the classic sub-account transfer primitives.

   Prefers the explicitly tracked owner mode (fetched for the master/owner
   address whenever the Sub-Accounts page loads or refreshes) because the
   active-account mode at `[:account :mode]` reflects whichever account is
   *currently selected for trading* — which can be a classic sub-account even
   while the master is unified. Without this distinction a withdraw back to a
   unified master, made while a classic sub-account is the active trading
   account, would fall through to the legacy transfer primitive and fail. Falls
   back to the active-account mode only for legacy/minimal state where no
   owner-mode record has been loaded at all."
  [state]
  (let [owner-mode (get-in state [:account-context :subaccounts :owner-mode])
        active-unified? (= :unified (get-in state [:account :mode]))]
    (cond
      (map? owner-mode)
      (if (= (subaccounts-viewed-owner-address state)
             (normalize-address (:owner owner-mode)))
        (if-some [mode (:mode owner-mode)]
          (= :unified mode)
          false)
        false)

      (some? owner-mode)
      false

      :else
      active-unified?)))

(defn- subaccount-row-address
  [row]
  (normalize-address (or (:sub-account-user row)
                         (:subAccountUser row)
                         (get row "subAccountUser")
                         (get row "sub-account-user"))))

(defn- subaccount-row-master
  [row]
  (normalize-address (or (:master row)
                         (get row "master"))))

(defn- normalize-subaccount-row
  [row]
  (when-let [address (subaccount-row-address row)]
    (cond-> (assoc row :sub-account-user address)
      (subaccount-row-master row) (assoc :master (subaccount-row-master row)))))

(defn selected-subaccount-row
  [state]
  (let [selected (selected-subaccount-address state)]
    (when selected
      (some (fn [row]
              (let [row* (normalize-subaccount-row row)]
                (when (= selected (:sub-account-user row*))
                  row*)))
            (get-in state [:account-context :subaccounts :rows])))))

(defn selected-subaccount-owned-by-owner?
  [state]
  (let [owner (owner-address state)
        row (selected-subaccount-row state)]
    (and (some? owner)
         (some? row)
         (= owner (:master row)))))

(defn selected-subaccount-unavailable?
  [state]
  (and (some? (selected-subaccount-address state))
       (not (selected-subaccount-owned-by-owner? state))))

(defn active-trading-account-address
  [state]
  (if (selected-subaccount-owned-by-owner? state)
    (:sub-account-user (selected-subaccount-row state))
    (owner-address state)))

(defn exchange-vault-address
  [state]
  (when (selected-subaccount-owned-by-owner? state)
    (:sub-account-user (selected-subaccount-row state))))

(defn live-user-streams-enabled?
  [state]
  (not (trader-portfolio-route-active? state)))

(defn user-stream-subscriptions-enabled?
  [state]
  (live-user-streams-enabled? state))

(defn effective-account-address
  [state]
  (if-let [trader-address (trader-portfolio-address state)]
    trader-address
    (if (spectate-mode-active? state)
      (spectate-address state)
      (active-trading-account-address state))))

(defn native-staking-account-address
  [state]
  (if (or (spectate-mode-active? state)
          (trader-portfolio-route-active? state))
    (effective-account-address state)
    (owner-address state)))

(defn live-user-stream-address
  [state]
  (when (user-stream-subscriptions-enabled? state)
    (effective-account-address state)))

(defn inspected-account-read-only?
  [state]
  (or (spectate-mode-active? state)
      (trader-portfolio-route-active? state)
      (selected-subaccount-unavailable? state)))

(defn mutations-allowed?
  [state]
  (not (inspected-account-read-only? state)))

(defn mutations-blocked-message
  [state]
  (cond
    (spectate-mode-active? state)
    spectate-mode-read-only-message

    (trader-portfolio-route-active? state)
    trader-portfolio-read-only-message

    (selected-subaccount-unavailable? state)
    selected-subaccount-unavailable-message

    :else
    nil))

(defn default-account-context-state
  []
  {:spectate-mode {:active? false
                :address nil
                :started-at-ms nil}
   :subaccounts {:status :idle
                 :loaded-for-owner nil
                 :owner-mode nil
                 :rows []
                 :error nil
                 :refreshing? false
                 :selected-address nil
                 :selection-loaded? false
                 :create-name ""
                 :create-popover-open? false
                 :rename-name ""
                 :transfer-amount ""
                 :transfer-direction :deposit
                 :transfer-account :trading
                 :transfer-account-menu-open? false
                 :transfer-token "USDC"
                 :transfer-token-menu-open? false
                 :creating? false
                 :renaming-address nil
                 :transferring-address nil}
   :spectate-ui {:modal-open? false
                 :anchor nil
                 :search ""
                 :label ""
                 :editing-watchlist-address nil
                 :search-auto-prefilled? false
                 :last-search ""
                 :search-error nil}
   :watchlist []
   :watchlist-loaded? false})

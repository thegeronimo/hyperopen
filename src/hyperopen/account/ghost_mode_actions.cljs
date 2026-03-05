(ns hyperopen.account.ghost-mode-actions
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.platform :as platform]))

(def ^:private invalid-address-error
  "Enter a valid 0x-prefixed EVM address.")

(def ^:private anchor-keys
  [:left :right :top :bottom :width :height :viewport-width :viewport-height])

(defn- parse-num
  [value]
  (cond
    (number? value) value
    (string? value) (let [text (str/trim value)
                          parsed (js/parseFloat text)]
                      (when (and (seq text)
                                 (not (js/isNaN parsed)))
                        parsed))
    :else nil))

(defn- normalize-anchor
  [anchor]
  (when (map? anchor)
    (let [normalized (reduce (fn [acc key]
                               (if-let [num (parse-num (get anchor key))]
                                 (assoc acc key num)
                                 acc))
                             {}
                             anchor-keys)]
      (when (seq normalized)
        normalized))))

(defn- search-value
  [state]
  (or (get-in state [:account-context :ghost-ui :search]) ""))

(defn- label-value
  [state]
  (or (get-in state [:account-context :ghost-ui :label]) ""))

(defn- normalized-search-address
  [state]
  (account-context/normalize-address (search-value state)))

(defn- normalized-search-label
  [state]
  (account-context/normalize-watchlist-label (label-value state)))

(defn- resolved-address
  [state address]
  (or (account-context/normalize-address address)
      (normalized-search-address state)))

(defn- editing-watchlist-address
  [state]
  (account-context/normalize-address
   (get-in state [:account-context :ghost-ui :editing-watchlist-address])))

(defn- watchlist
  [state]
  (account-context/normalize-watchlist
   (get-in state [:account-context :watchlist])))

(defn- persist-watchlist-effects
  [watchlist*]
  [[:effects/local-storage-set-json
    account-context/ghost-watchlist-storage-key
    watchlist*]])

(defn open-ghost-mode-modal
  [state & [trigger-bounds]]
  (let [watchlist* (watchlist state)
        active-address (account-context/ghost-address state)
        active-entry (account-context/watchlist-entry-by-address watchlist* active-address)
        search* (or active-address
                    (search-value state)
                    "")
        label* (or (:label active-entry)
                   (label-value state)
                   "")
        anchor* (normalize-anchor trigger-bounds)]
    [[:effects/save-many [[[:account-context :ghost-ui :modal-open?] true]
                          [[:account-context :ghost-ui :anchor] anchor*]
                          [[:account-context :ghost-ui :search] search*]
                          [[:account-context :ghost-ui :label] label*]
                          [[:account-context :ghost-ui :editing-watchlist-address] nil]
                          [[:account-context :ghost-ui :search-error] nil]]]]))

(defn close-ghost-mode-modal
  [_state]
  [[:effects/save-many [[[:account-context :ghost-ui :modal-open?] false]
                        [[:account-context :ghost-ui :anchor] nil]
                        [[:account-context :ghost-ui :label] ""]
                        [[:account-context :ghost-ui :editing-watchlist-address] nil]
                        [[:account-context :ghost-ui :search-error] nil]]]])

(defn set-ghost-mode-search
  [state value]
  (let [text (if (string? value) value (str (or value "")))
        editing-address* (editing-watchlist-address state)
        next-address* (account-context/normalize-address text)
        editing-active? (and (some? editing-address*)
                             (= editing-address* next-address*))]
    [[:effects/save-many [[[:account-context :ghost-ui :search] text]
                          [[:account-context :ghost-ui :label] (if editing-active?
                                                                  (label-value state)
                                                                  "")]
                          [[:account-context :ghost-ui :editing-watchlist-address] (when editing-active?
                                                                                      editing-address*)]
                          [[:account-context :ghost-ui :search-error] nil]]]]))

(defn set-ghost-mode-label
  [_state value]
  (let [text (if (string? value) value (str (or value "")))]
    [[:effects/save [:account-context :ghost-ui :label] text]]))

(defn start-ghost-mode
  [state & [address]]
  (if-let [address* (resolved-address state address)]
    (let [started-at-ms (platform/now-ms)
          watchlist* (account-context/upsert-watchlist-entry
                      (watchlist state)
                      address*
                      (normalized-search-label state)
                      true)]
      (into [[:effects/save-many [[[:account-context :ghost-mode :active?] true]
                                  [[:account-context :ghost-mode :address] address*]
                                  [[:account-context :ghost-mode :started-at-ms] started-at-ms]
                                  [[:account-context :ghost-ui :modal-open?] false]
                                  [[:account-context :ghost-ui :anchor] nil]
                                  [[:account-context :ghost-ui :search] address*]
                                  [[:account-context :ghost-ui :last-search] address*]
                                  [[:account-context :ghost-ui :label] ""]
                                  [[:account-context :ghost-ui :editing-watchlist-address] nil]
                                  [[:account-context :ghost-ui :search-error] nil]
                                  [[:account-context :watchlist] watchlist*]]]
             [:effects/local-storage-set
              account-context/ghost-last-search-storage-key
              address*]]
            (persist-watchlist-effects watchlist*)))
    [[:effects/save [:account-context :ghost-ui :search-error] invalid-address-error]]))

(defn stop-ghost-mode
  [_state]
  [[:effects/save-many [[[:account-context :ghost-mode :active?] false]
                        [[:account-context :ghost-mode :address] nil]
                        [[:account-context :ghost-mode :started-at-ms] nil]
                        [[:account-context :ghost-ui :modal-open?] false]
                        [[:account-context :ghost-ui :anchor] nil]
                        [[:account-context :ghost-ui :label] ""]
                        [[:account-context :ghost-ui :editing-watchlist-address] nil]
                        [[:account-context :ghost-ui :search-error] nil]]]])

(defn add-ghost-mode-watchlist-address
  [state & [address]]
  (if-let [address* (resolved-address state address)]
    (let [editing-address* (editing-watchlist-address state)
          preserve-existing-label? (not= editing-address* address*)
          watchlist* (account-context/upsert-watchlist-entry
                      (watchlist state)
                      address*
                      (normalized-search-label state)
                      preserve-existing-label?)]
      (into [[:effects/save-many [[[:account-context :watchlist] watchlist*]
                                  [[:account-context :ghost-ui :search] address*]
                                  [[:account-context :ghost-ui :last-search] address*]
                                  [[:account-context :ghost-ui :label] ""]
                                  [[:account-context :ghost-ui :editing-watchlist-address] nil]
                                  [[:account-context :ghost-ui :search-error] nil]]]
             [:effects/local-storage-set
              account-context/ghost-last-search-storage-key
              address*]]
            (persist-watchlist-effects watchlist*)))
    [[:effects/save [:account-context :ghost-ui :search-error] invalid-address-error]]))

(defn remove-ghost-mode-watchlist-address
  [state address]
  (if-let [address* (account-context/normalize-address address)]
    (let [watchlist* (account-context/remove-watchlist-entry
                      (watchlist state)
                      address*)
          editing-address* (editing-watchlist-address state)
          removed-editing? (= address* editing-address*)
          save-effects (cond-> [[[:account-context :watchlist] watchlist*]]
                         removed-editing?
                         (into [[[:account-context :ghost-ui :label] ""]
                                [[:account-context :ghost-ui :editing-watchlist-address] nil]]))]
      (into [[:effects/save-many save-effects]]
            (persist-watchlist-effects watchlist*)))
    []))

(defn edit-ghost-mode-watchlist-address
  [state address]
  (if-let [{:keys [address label]} (account-context/watchlist-entry-by-address
                                    (watchlist state)
                                    address)]
    [[:effects/save-many [[[:account-context :ghost-ui :search] address]
                          [[:account-context :ghost-ui :label] (or label "")]
                          [[:account-context :ghost-ui :editing-watchlist-address] address]
                          [[:account-context :ghost-ui :search-error] nil]]]]
    []))

(defn clear-ghost-mode-watchlist-edit
  [_state]
  [[:effects/save-many [[[:account-context :ghost-ui :label] ""]
                        [[:account-context :ghost-ui :editing-watchlist-address] nil]
                        [[:account-context :ghost-ui :search-error] nil]]]])

(defn copy-ghost-mode-watchlist-address
  [_state address]
  (if-let [address* (account-context/normalize-address address)]
    [[:effects/copy-wallet-address address*]]
    []))

(defn spectate-ghost-mode-watchlist-address
  [state address]
  (if (str/blank? (str (or address "")))
    []
    (let [entry (account-context/watchlist-entry-by-address
                 (watchlist state)
                 address)]
      (start-ghost-mode (-> state
                            (assoc-in [:account-context :ghost-ui :search] address)
                            (assoc-in [:account-context :ghost-ui :label] (or (:label entry) "")))
                        address))))

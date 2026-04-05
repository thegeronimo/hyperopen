(ns hyperopen.account.spectate-mode-actions
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.account.history.shared :as history-shared]
            [hyperopen.account.spectate-mode-links :as spectate-mode-links]
            [hyperopen.router :as router]
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
  (or (get-in state [:account-context :spectate-ui :search]) ""))

(defn- label-value
  [state]
  (or (get-in state [:account-context :spectate-ui :label]) ""))

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
   (get-in state [:account-context :spectate-ui :editing-watchlist-address])))

(defn- watchlist
  [state]
  (account-context/normalize-watchlist
   (get-in state [:account-context :watchlist])))

(defn- current-route-path
  [state]
  (or (get-in state [:router :path])
      "/trade"))

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- spectate-browser-path
  [state address]
  (let [route (current-route-path state)]
    (if (router/trade-route? route)
      (router/trade-browser-path
       {:market (or (non-blank-text (:active-asset state))
                    (router/trade-route-asset route))
        :tab (history-shared/normalize-account-info-route-tab
              (get-in state [:account-info :selected-tab]))
        :spectate address})
      (spectate-mode-links/spectate-url-path route address))))

(defn- persist-watchlist-effects
  [watchlist*]
  [[:effects/local-storage-set-json
    account-context/spectate-watchlist-storage-key
    watchlist*]])

(defn open-spectate-mode-modal
  [state & [trigger-bounds]]
  (let [watchlist* (watchlist state)
        active-address (account-context/spectate-address state)
        active-entry (account-context/watchlist-entry-by-address watchlist* active-address)
        search* (or active-address
                    (search-value state)
                    "")
        label* (or (:label active-entry)
                   (label-value state)
                   "")
        anchor* (normalize-anchor trigger-bounds)]
    [[:effects/save-many [[[:account-context :spectate-ui :modal-open?] true]
                          [[:account-context :spectate-ui :anchor] anchor*]
                          [[:account-context :spectate-ui :search] search*]
                          [[:account-context :spectate-ui :label] label*]
                          [[:account-context :spectate-ui :editing-watchlist-address] nil]
                          [[:account-context :spectate-ui :search-error] nil]]]]))

(defn close-spectate-mode-modal
  [_state]
  [[:effects/save-many [[[:account-context :spectate-ui :modal-open?] false]
                        [[:account-context :spectate-ui :anchor] nil]
                        [[:account-context :spectate-ui :label] ""]
                        [[:account-context :spectate-ui :editing-watchlist-address] nil]
                        [[:account-context :spectate-ui :search-error] nil]]]])

(defn set-spectate-mode-search
  [state value]
  (let [text (if (string? value) value (str (or value "")))
        editing-address* (editing-watchlist-address state)
        next-address* (account-context/normalize-address text)
        editing-active? (and (some? editing-address*)
                             (= editing-address* next-address*))]
    [[:effects/save-many [[[:account-context :spectate-ui :search] text]
                          [[:account-context :spectate-ui :label] (if editing-active?
                                                                  (label-value state)
                                                                  "")]
                          [[:account-context :spectate-ui :editing-watchlist-address] (when editing-active?
                                                                                      editing-address*)]
                          [[:account-context :spectate-ui :search-error] nil]]]]))

(defn set-spectate-mode-label
  [_state value]
  (let [text (if (string? value) value (str (or value "")))]
    [[:effects/save [:account-context :spectate-ui :label] text]]))

(defn start-spectate-mode
  [state & [address]]
  (if-let [address* (resolved-address state address)]
    (let [started-at-ms (platform/now-ms)
          watchlist* (account-context/upsert-watchlist-entry
                      (watchlist state)
                      address*
                      (normalized-search-label state)
                      true)]
      (into [[:effects/save-many [[[:account-context :spectate-mode :active?] true]
                                  [[:account-context :spectate-mode :address] address*]
                                  [[:account-context :spectate-mode :started-at-ms] started-at-ms]
                                  [[:account-context :spectate-ui :modal-open?] false]
                                  [[:account-context :spectate-ui :anchor] nil]
                                  [[:account-context :spectate-ui :search] address*]
                                  [[:account-context :spectate-ui :last-search] address*]
                                  [[:account-context :spectate-ui :label] ""]
                                  [[:account-context :spectate-ui :editing-watchlist-address] nil]
                                  [[:account-context :spectate-ui :search-error] nil]
                                  [[:account-context :watchlist] watchlist*]]]
             [:effects/replace-state
              (spectate-browser-path state address*)]
             [:effects/local-storage-set
              account-context/spectate-last-search-storage-key
              address*]]
            (persist-watchlist-effects watchlist*)))
    [[:effects/save [:account-context :spectate-ui :search-error] invalid-address-error]]))

(defn stop-spectate-mode
  [state]
  (let [spectate-address* (account-context/spectate-address state)
        disconnected-after-stop? (and (some? spectate-address*)
                                      (nil? (account-context/owner-address state))
                                      (nil? (account-context/trader-portfolio-address state)))]
    (cond-> [[:effects/save-many [[[:account-context :spectate-mode :active?] false]
                                  [[:account-context :spectate-mode :address] nil]
                                  [[:account-context :spectate-mode :started-at-ms] nil]
                                  [[:account-context :spectate-ui :modal-open?] false]
                                  [[:account-context :spectate-ui :anchor] nil]
                                  [[:account-context :spectate-ui :label] ""]
                                  [[:account-context :spectate-ui :editing-watchlist-address] nil]
                                  [[:account-context :spectate-ui :search-error] nil]]]
             [:effects/replace-state
              (spectate-browser-path state nil)]]
      disconnected-after-stop?
      (conj [:effects/clear-disconnected-account-lifecycle spectate-address*]))))

(defn add-spectate-mode-watchlist-address
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
                                  [[:account-context :spectate-ui :search] address*]
                                  [[:account-context :spectate-ui :last-search] address*]
                                  [[:account-context :spectate-ui :label] ""]
                                  [[:account-context :spectate-ui :editing-watchlist-address] nil]
                                  [[:account-context :spectate-ui :search-error] nil]]]
             [:effects/local-storage-set
              account-context/spectate-last-search-storage-key
              address*]]
            (persist-watchlist-effects watchlist*)))
    [[:effects/save [:account-context :spectate-ui :search-error] invalid-address-error]]))

(defn remove-spectate-mode-watchlist-address
  [state address]
  (if-let [address* (account-context/normalize-address address)]
    (let [watchlist* (account-context/remove-watchlist-entry
                      (watchlist state)
                      address*)
          editing-address* (editing-watchlist-address state)
          removed-editing? (= address* editing-address*)
          save-effects (cond-> [[[:account-context :watchlist] watchlist*]]
                         removed-editing?
                         (into [[[:account-context :spectate-ui :label] ""]
                                [[:account-context :spectate-ui :editing-watchlist-address] nil]]))]
      (into [[:effects/save-many save-effects]]
            (persist-watchlist-effects watchlist*)))
    []))

(defn edit-spectate-mode-watchlist-address
  [state address]
  (if-let [{:keys [address label]} (account-context/watchlist-entry-by-address
                                    (watchlist state)
                                    address)]
    [[:effects/save-many [[[:account-context :spectate-ui :search] address]
                          [[:account-context :spectate-ui :label] (or label "")]
                          [[:account-context :spectate-ui :editing-watchlist-address] address]
                          [[:account-context :spectate-ui :search-error] nil]]]]
    []))

(defn clear-spectate-mode-watchlist-edit
  [_state]
  [[:effects/save-many [[[:account-context :spectate-ui :label] ""]
                        [[:account-context :spectate-ui :editing-watchlist-address] nil]
                        [[:account-context :spectate-ui :search-error] nil]]]])

(defn copy-spectate-mode-watchlist-address
  [_state address]
  (if-let [address* (account-context/normalize-address address)]
    [[:effects/copy-wallet-address address*]]
    []))

(defn copy-spectate-mode-watchlist-link
  [state address]
  (if-let [address* (account-context/normalize-address address)]
    [[:effects/copy-spectate-link
      (current-route-path state)
      address*]]
    []))

(defn start-spectate-mode-watchlist-address
  [state address]
  (if (str/blank? (str (or address "")))
    []
    (let [entry (account-context/watchlist-entry-by-address
                 (watchlist state)
                 address)]
      (start-spectate-mode (-> state
                            (assoc-in [:account-context :spectate-ui :search] address)
                            (assoc-in [:account-context :spectate-ui :label] (or (:label entry) "")))
                        address))))

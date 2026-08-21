(ns hyperopen.subaccounts.actions
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.subaccounts.management :as management]
            [hyperopen.subaccounts.transfer-amount :as transfer-amount]))

(def canonical-route
  "/subAccounts")

(def selected-subaccount-storage-prefix
  "hyperopen:subaccounts:selected:v1")

(def ^:private subaccounts-route-kinds
  #{"/subaccounts"})

(def missing-owner-message
  "Connect your wallet before selecting a subaccount.")

(def invalid-subaccount-selection-message
  "Select a subaccount owned by the connected master wallet.")

(defn selected-subaccount-storage-key
  [owner-address]
  (str selected-subaccount-storage-prefix ":"
       (or (account-context/normalize-address owner-address) "")))

(defn viewed-master-address
  "Address whose subaccounts are displayed by the subaccount page.
  This stays distinct from effective-account-address so an already selected
  subaccount does not become the parent queried for subaccounts."
  [state]
  (account-context/subaccounts-viewed-owner-address state))

(defn- split-path-from-query-fragment
  [path]
  (let [path* (if (string? path) path (str (or path "")))]
    (or (first (str/split path* #"[?#]" 2))
        "")))

(defn- trim-trailing-slashes
  [path]
  (loop [path* path]
    (if (and (> (count path*) 1)
             (str/ends-with? path* "/"))
      (recur (subs path* 0 (dec (count path*))))
      path*)))

(defn normalize-route-path
  [path]
  (-> path
      split-path-from-query-fragment
      str/trim
      trim-trailing-slashes))

(defn parse-subaccounts-route
  [path]
  (let [path* (normalize-route-path path)
        path-lower (str/lower-case path*)]
    (if (contains? subaccounts-route-kinds path-lower)
      {:kind :page
       :path canonical-route}
      {:kind :other
       :path path*})))

(defn subaccounts-route?
  [path]
  (= :page (:kind (parse-subaccounts-route path))))

(defn- subaccount-row-address
  [row]
  (account-context/normalize-address
   (or (:sub-account-user row)
       (:subAccountUser row)
       (get row "subAccountUser")
       (get row "sub-account-user"))))

(defn- subaccount-row-master
  [row]
  (account-context/normalize-address
   (or (:master row)
       (get row "master"))))

(defn- owned-subaccount-row
  [state owner-address subaccount-address]
  (some (fn [row]
          (when (and (= subaccount-address (subaccount-row-address row))
                     (= owner-address (subaccount-row-master row)))
            row))
        (get-in state [:account-context :subaccounts :rows])))

(def set-subaccount-form-field management/set-subaccount-form-field)
(def toggle-transfer-direction management/toggle-transfer-direction)
(def open-create-popover management/open-create-popover)
(def close-create-popover management/close-create-popover)
(def copy-subaccount-address management/copy-subaccount-address)
(def parse-usdc-amount->micros transfer-amount/parse-usdc-amount->micros)
(def submit-create-subaccount management/submit-create-subaccount)
(def start-rename-subaccount management/start-rename-subaccount)
(def cancel-rename-subaccount management/cancel-rename-subaccount)
(def submit-rename-subaccount management/submit-rename-subaccount)
(def start-transfer-subaccount management/start-transfer-subaccount)
(def cancel-transfer-subaccount management/cancel-transfer-subaccount)
(def submit-transfer-subaccount management/submit-transfer-subaccount)

(defn- owner-mode-record
  [state owner-address]
  (when (seq owner-address)
    (let [current (get-in state [:account-context :subaccounts :owner-mode])]
      (if (and (map? current)
               (= owner-address
                  (account-context/normalize-address (:owner current))))
        {:owner owner-address
         :mode (:mode current)}
        {:owner owner-address
         :mode nil}))))

(defn- owner-snapshot-record
  [state owner-address]
  (when (seq owner-address)
    (let [current (get-in state [:account-context :subaccounts :owner-snapshot])]
      (if (and (map? current)
               (= owner-address
                  (account-context/normalize-address (:owner current))))
        (assoc current
               :owner owner-address
               :loading? true
               :error nil)
        {:owner owner-address
         :clearinghouse-state nil
         :spot-state nil
         :loading? true
         :error nil}))))

(defn- load-route-path-values
  [state master-address]
  (if (seq master-address)
    [[[:account-context :subaccounts :status] :loading]
     [[:account-context :subaccounts :loaded-for-owner] master-address]
     [[:account-context :subaccounts :owner-mode]
      (owner-mode-record state master-address)]
     [[:account-context :subaccounts :owner-snapshot]
      (owner-snapshot-record state master-address)]
     [[:account-context :subaccounts :rows] []]
     [[:account-context :subaccounts :error] nil]
     [[:account-context :subaccounts :refreshing?] false]
     [[:account-context :subaccounts :create-name] ""]
     [[:account-context :subaccounts :create-popover-open?] false]
     [[:account-context :subaccounts :rename-name] ""]
     [[:account-context :subaccounts :transfer-amount] ""]
     [[:account-context :subaccounts :transfer-direction] :deposit]
     [[:account-context :subaccounts :transfer-account] :trading]
     [[:account-context :subaccounts :transfer-account-menu-open?] false]
     [[:account-context :subaccounts :transfer-token] "USDC"]
     [[:account-context :subaccounts :transfer-token-menu-open?] false]
     ;; View preference for the Send Tokens token list, not transfer data:
     ;; defined here so a fresh route entry starts with the short list, but
     ;; deliberately NOT reset by start/cancel-transfer, so a user who wants to
     ;; see dust balances does not have to re-check it for every sub-account.
     [[:account-context :subaccounts :show-zero-balances?] false]
     [[:account-context :subaccounts :selection-loaded?] false]]
    [[[:account-context :subaccounts :status] :idle]
     [[:account-context :subaccounts :loaded-for-owner] nil]
     [[:account-context :subaccounts :owner-mode] nil]
     [[:account-context :subaccounts :owner-snapshot] nil]
     [[:account-context :subaccounts :rows] []]
     [[:account-context :subaccounts :error] nil]
     [[:account-context :subaccounts :refreshing?] false]
     [[:account-context :subaccounts :selected-address] nil]
     [[:account-context :subaccounts :create-name] ""]
     [[:account-context :subaccounts :create-popover-open?] false]
     [[:account-context :subaccounts :rename-name] ""]
     [[:account-context :subaccounts :transfer-amount] ""]
     [[:account-context :subaccounts :transfer-direction] :deposit]
     [[:account-context :subaccounts :transfer-account] :trading]
     [[:account-context :subaccounts :transfer-account-menu-open?] false]
     [[:account-context :subaccounts :transfer-token] "USDC"]
     [[:account-context :subaccounts :transfer-token-menu-open?] false]
     ;; View preference for the Send Tokens token list, not transfer data:
     ;; defined here so a fresh route entry starts with the short list, but
     ;; deliberately NOT reset by start/cancel-transfer, so a user who wants to
     ;; see dust balances does not have to re-check it for every sub-account.
     [[:account-context :subaccounts :show-zero-balances?] false]
     [[:account-context :subaccounts :selection-loaded?] false]]))

(defn load-subaccounts-route
  "Route-entry loader for the Sub-Accounts page. Idempotent: when the page
   already has (or is loading) data for the same master, re-entering the route
   is a no-op so we never blank the visible rows or rejoin a pending load. A
   fresh owner, an error, or an idle state triggers a full load. Manual
   refreshes go through `refresh-subaccounts` (non-destructive force load)."
  [state path]
  (if-not (subaccounts-route? path)
    []
    (let [master-address (viewed-master-address state)
          {:keys [status loaded-for-owner]}
          (get-in state [:account-context :subaccounts])]
      (cond
        (not (seq master-address))
        [[:effects/save-many (load-route-path-values state nil)]]

        (and (= master-address loaded-for-owner)
             (contains? #{:loading :loaded} status))
        []

        :else
        [[:effects/save-many (load-route-path-values state master-address)]
         [:effects/api-load-subaccounts]]))))

(defn refresh-subaccounts
  "User-triggered refresh of the Sub-Accounts page. Keeps the currently
   rendered rows visible (sets a `:refreshing?` flag instead of clearing rows
   and forcing `:loading`) and dispatches a force-refresh load that bypasses
   the single-flight/cache dedupe key, so a stale or stuck in-flight request
   can never block recovery.

   Also re-requests the full market catalog when spot metadata is missing. The
   Send Tokens dialog disables any token it cannot name on the wire and tells the
   user to refresh balances, but `:effects/api-refresh-subaccounts` only reloads
   clearinghouse state — it never touches `[:spot :meta]`, so without this the
   advertised remedy is a no-op and a failed catalog load is unrecoverable
   without a page reload."
  [state]
  (let [master-address (viewed-master-address state)]
    (if-not (seq master-address)
      [[:effects/save [:account-context :subaccounts :error]
        missing-owner-message]]
      (cond-> [[:effects/save-many [[[:account-context :subaccounts :refreshing?] true]
                                    [[:account-context :subaccounts :loaded-for-owner] master-address]
                                    [[:account-context :subaccounts :owner-mode]
                                     (owner-mode-record state master-address)]
                                    [[:account-context :subaccounts :owner-snapshot]
                                     (owner-snapshot-record state master-address)]
                                    [[:account-context :subaccounts :error] nil]]]
               [:effects/api-refresh-subaccounts]]
        (nil? (get-in state [:spot :meta]))
        (conj [:effects/fetch-asset-selector-markets {:phase :full}])))))

(defn- spectate-read-only-effect
  [state]
  (when (account-context/spectate-mode-active? state)
    [[:effects/save [:account-context :subaccounts :error]
      account-context/spectate-mode-read-only-message]]))

(defn select-subaccount
  [state address]
  (if-let [blocked (spectate-read-only-effect state)]
    blocked
    (let [owner-address (account-context/owner-address state)
          address* (account-context/normalize-address address)]
      (cond
        (not (seq owner-address))
        [[:effects/save [:account-context :subaccounts :error]
          missing-owner-message]]

        (not (and (seq address*)
                  (owned-subaccount-row state owner-address address*)))
        [[:effects/save [:account-context :subaccounts :error]
          invalid-subaccount-selection-message]]

        :else
        [[:effects/save-many [[[:account-context :subaccounts :selected-address] address*]
                              [[:account-context :subaccounts :error] nil]]]
         [:effects/local-storage-set
          (selected-subaccount-storage-key owner-address)
          address*]
         [:effects/api-load-user-data address*]]))))

(defn select-master-account
  [state]
  (if-let [blocked (spectate-read-only-effect state)]
    blocked
    (let [owner-address (account-context/owner-address state)]
      (if-not (seq owner-address)
        [[:effects/save [:account-context :subaccounts :error]
          missing-owner-message]]
        [[:effects/save-many [[[:account-context :subaccounts :selected-address] nil]
                              [[:account-context :subaccounts :error] nil]]]
         [:effects/local-storage-set
          (selected-subaccount-storage-key owner-address)
          ""]
         [:effects/api-load-user-data owner-address]]))))

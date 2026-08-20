(ns hyperopen.subaccounts.effects
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.funding.domain.spot-tokens :as spot-tokens]
            [hyperopen.platform :as platform]
            [hyperopen.subaccounts.actions :as actions]))

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

(defn- normalize-subaccount-row
  [row]
  (when-let [address (subaccount-row-address row)]
    (cond-> (assoc row :sub-account-user address)
      (subaccount-row-master row) (assoc :master (subaccount-row-master row)))))

(defn- owned-subaccount-address?
  [owner-address rows address]
  (boolean
   (some (fn [row]
           (and (= address (subaccount-row-address row))
                (= owner-address (subaccount-row-master row))))
         rows)))

(defn- request-opts
  [owner-address force-refresh? now-ms-fn]
  (if force-refresh?
    (let [token (now-ms-fn)]
      {:priority :high
       :cache-ttl-ms 1
       :dedupe-key [:sub-accounts owner-address token]
       :cache-key [:sub-accounts owner-address token]})
    {:priority :high}))

(defn- error-message
  [err]
  (or (some-> err .-message str/trim not-empty)
      (some-> err str str/trim not-empty)
      "Failed to load subaccounts."))

(defn- reset-subaccounts!
  [store]
  (swap! store assoc-in [:account-context :subaccounts]
         {:status :idle
          :loaded-for-owner nil
          :owner-mode nil
          :owner-snapshot nil
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
          :transferring-address nil}))

(defn- stored-selected-address
  [owner-address local-storage-get]
  (account-context/normalize-address
   (local-storage-get (actions/selected-subaccount-storage-key owner-address))))

(defn- next-selected-address
  [state owner-address rows local-storage-get]
  (let [current-selected (account-context/normalize-address
                          (get-in state [:account-context :subaccounts :selected-address]))
        stored-selected (stored-selected-address owner-address local-storage-get)]
    (cond
      (owned-subaccount-address? owner-address rows current-selected)
      current-selected

      (owned-subaccount-address? owner-address rows stored-selected)
      stored-selected

      :else
      nil)))

(defn- owner-mode-record
  [owner-address mode]
  {:owner owner-address
   :mode mode})

(defn- owner-snapshot-record
  [owner-address clearinghouse-state spot-state loading? error]
  {:owner owner-address
   :clearinghouse-state clearinghouse-state
   :spot-state spot-state
   :loading? (boolean loading?)
   :error error})

(defn- prepare-owner-mode!
  [store owner-address]
  (swap! store
         update-in
         [:account-context :subaccounts :owner-mode]
         (fn [current]
           (if (and (map? current)
                    (= owner-address
                       (account-context/normalize-address (:owner current))))
             (owner-mode-record owner-address (:mode current))
             (owner-mode-record owner-address nil)))))

(defn- prepare-owner-snapshot!
  [store owner-address]
  (swap! store
         update-in
         [:account-context :subaccounts :owner-snapshot]
         (fn [current]
           (if (and (map? current)
                    (= owner-address
                       (account-context/normalize-address (:owner current))))
             (assoc current
                    :owner owner-address
                    :loading? true
                    :error nil)
             (owner-snapshot-record owner-address nil nil true nil)))))

(defn- apply-owner-mode!
  [store owner-address mode]
  (when mode
    (swap! store
           (fn [state]
             ;; Guard against the owner changing while the abstraction request
             ;; was in flight so we never stamp a stale master's mode.
             (if (= owner-address (actions/viewed-master-address state))
               (assoc-in state
                         [:account-context :subaccounts :owner-mode]
                         (owner-mode-record owner-address mode))
               state)))))

(defn- apply-owner-snapshot!
  [store owner-address clearinghouse-state spot-state]
  (swap! store
         (fn [state]
           (if (= owner-address (actions/viewed-master-address state))
             (assoc-in state
                       [:account-context :subaccounts :owner-snapshot]
                       (owner-snapshot-record owner-address
                                              clearinghouse-state
                                              spot-state
                                              false
                                              nil))
             state))))

(defn- apply-owner-snapshot-error!
  [store owner-address err]
  (swap! store
         (fn [state]
           (if (= owner-address (actions/viewed-master-address state))
             (update-in state
                        [:account-context :subaccounts :owner-snapshot]
                        (fn [current]
                          (let [current* (if (and (map? current)
                                                  (= owner-address
                                                     (account-context/normalize-address
                                                      (:owner current))))
                                           current
                                           (owner-snapshot-record owner-address nil nil true nil))]
                            (assoc current*
                                   :owner owner-address
                                   :loading? false
                                   :error (error-message err)))))
             state))))

(defn- load-owner-mode!
  "Fetches the master/owner account abstraction mode so the Sub-Accounts page
   can decide whether to use unified (`sendAsset`) transfers based on the
   master — not the active trading account, which may be a classic
   sub-account. Best-effort: failures never reject the subaccounts load."
  [{:keys [store request-owner-mode! force-refresh?]} owner-address]
  (when (fn? request-owner-mode!)
    (-> (js/Promise.resolve (request-owner-mode! owner-address (boolean force-refresh?)))
        (.then (fn [mode]
                 (apply-owner-mode! store owner-address mode)
                 nil))
        (.catch (fn [_] nil)))))

(defn- owner-snapshot-request-opts
  [owner-address request-kind force-refresh? now-ms-fn]
  (if force-refresh?
    (let [token (now-ms-fn)]
      {:priority :high
       :cache-ttl-ms 1
       :force-refresh? true
       :dedupe-key [:subaccounts-owner-snapshot owner-address request-kind token]
       :cache-key [:subaccounts-owner-snapshot owner-address request-kind token]})
    {:priority :high}))

(defn- load-owner-snapshot!
  [{:keys [store
           request-owner-clearinghouse-state!
           request-owner-spot-state!
           force-refresh?
           now-ms-fn]
    :or {now-ms-fn platform/now-ms}}
   owner-address]
  (if (and (fn? request-owner-clearinghouse-state!)
           (fn? request-owner-spot-state!))
    (let [clearinghouse-promise
          (js/Promise.resolve
           (request-owner-clearinghouse-state!
            owner-address
            (owner-snapshot-request-opts owner-address
                                         :clearinghouse
                                         force-refresh?
                                         now-ms-fn)))
          spot-promise
          (js/Promise.resolve
           (request-owner-spot-state!
            owner-address
            (owner-snapshot-request-opts owner-address
                                         :spot
                                         force-refresh?
                                         now-ms-fn)))]
      (-> (js/Promise.all #js [clearinghouse-promise spot-promise])
          (.then (fn [results]
                   (apply-owner-snapshot! store
                                          owner-address
                                          (aget results 0)
                                          (aget results 1))
                   nil))
          (.catch (fn [err]
                    (apply-owner-snapshot-error! store owner-address err)
                    nil))))
    (js/Promise.resolve nil)))

(defn load-subaccounts!
  [{:keys [store
           request-sub-accounts!
           local-storage-get
           now-ms-fn
           force-refresh?]
    :or {local-storage-get platform/local-storage-get
         now-ms-fn platform/now-ms}
    :as deps}]
  (let [owner-address (actions/viewed-master-address @store)]
    (if-not (seq owner-address)
      (do
        (reset-subaccounts! store)
        (js/Promise.resolve nil))
      (do
        (prepare-owner-mode! store owner-address)
        (prepare-owner-snapshot! store owner-address)
        (let [owner-mode-promise (or (load-owner-mode! deps owner-address)
                                     (js/Promise.resolve nil))
              owner-snapshot-promise (load-owner-snapshot! deps owner-address)
              rows-promise (-> (request-sub-accounts! owner-address
                                                       (request-opts owner-address
                                                                     force-refresh?
                                                                     now-ms-fn))
                               (.then (fn [rows]
                                        (let [rows* (->> (or rows [])
                                                         (keep normalize-subaccount-row)
                                                         vec)
                                              selected (next-selected-address @store
                                                                              owner-address
                                                                              rows*
                                                                              local-storage-get)]
                                          (swap! store
                                                 (fn [state]
                                                   (-> state
                                                       (assoc-in [:account-context :subaccounts :status] :loaded)
                                                       (assoc-in [:account-context :subaccounts :loaded-for-owner] owner-address)
                                                       (assoc-in [:account-context :subaccounts :rows] rows*)
                                                       (assoc-in [:account-context :subaccounts :error] nil)
                                                       (assoc-in [:account-context :subaccounts :selected-address] selected)
                                                       (assoc-in [:account-context :subaccounts :selection-loaded?] true))))
                                          nil)))
                               (.catch (fn [err]
                                         (swap! store
                                                (fn [state]
                                                  (-> state
                                                      (assoc-in [:account-context :subaccounts :status] :error)
                                                      (assoc-in [:account-context :subaccounts :error] (error-message err))
                                                      (assoc-in [:account-context :subaccounts :selection-loaded?] true))))
                                         nil)))]
          (-> (js/Promise.all #js [rows-promise owner-mode-promise owner-snapshot-promise])
              (.then (fn [_] nil))))))))

(defn api-load-subaccounts!
  [deps]
  (load-subaccounts! (merge {:force-refresh? false} deps)))

(defn api-refresh-subaccounts!
  "Force-refresh load for the Sub-Accounts page Refresh button. Uses the
   tokenized force path (unique dedupe/cache key) so it bypasses any stale or
   stuck single-flight entry, and always clears the `:refreshing?` flag once
   the load settles."
  [deps]
  (-> (load-subaccounts! (assoc deps :force-refresh? true))
      (.finally
       (fn []
         (when-let [store (:store deps)]
           (swap! store assoc-in
                  [:account-context :subaccounts :refreshing?] false))))))

(def ^:private default-load-subaccounts! load-subaccounts!)

(defn- response-ok?
  [resp]
  (= "ok" (:status resp)))

(defn- response-error-message
  [resp fallback-message]
  (or (some-> (:error resp) str str/trim not-empty)
      (some-> (:response resp) str str/trim not-empty)
      fallback-message))

(defn- management-runtime-error-message
  [runtime-error-message err]
  (or (some-> (runtime-error-message err) str str/trim not-empty)
      (error-message err)))

(defn- refresh-subaccounts!
  [{load-subaccounts-fn :load-subaccounts!
    :as deps}]
  ((or load-subaccounts-fn default-load-subaccounts!)
   (assoc deps :force-refresh? true)))

(defn- owner-address
  [store]
  (account-context/owner-address @store))

(defn- set-management-error!
  [store path-values message]
  (swap! store
         (fn [state]
           (reduce (fn [state* [path value]]
                     (assoc-in state* path value))
                   (assoc-in state [:account-context :subaccounts :error] message)
                   path-values))))

(defn- clear-create-success!
  [store]
  (swap! store
         (fn [state]
           (-> state
               (assoc-in [:account-context :subaccounts :creating?] false)
               (assoc-in [:account-context :subaccounts :create-popover-open?] false)
               (assoc-in [:account-context :subaccounts :create-name] "")
               (assoc-in [:account-context :subaccounts :error] nil)))))

(defn create-subaccount!
  [{:keys [store request create-sub-account! runtime-error-message]
    :as deps
    :or {create-sub-account! trading-api/create-sub-account!
         runtime-error-message error-message}}]
  (let [owner (owner-address store)
        name* (or (:name request)
                  (get-in @store [:account-context :subaccounts :create-name]))]
    (-> (create-sub-account! store owner name*)
        (.then (fn [resp]
                 (if (response-ok? resp)
                   (do
                     (clear-create-success! store)
                     (refresh-subaccounts! deps))
                   (set-management-error!
                    store
                    [[[:account-context :subaccounts :creating?] false]]
                    (response-error-message resp "Subaccount creation failed.")))))
        (.catch (fn [err]
                  (set-management-error!
                   store
                   [[[:account-context :subaccounts :creating?] false]]
                   (management-runtime-error-message runtime-error-message err)))))))

(defn- clear-rename-success!
  [store]
  (swap! store
         (fn [state]
           (-> state
               (assoc-in [:account-context :subaccounts :renaming-address] nil)
               (assoc-in [:account-context :subaccounts :rename-name] "")
               (assoc-in [:account-context :subaccounts :error] nil)))))

(defn rename-subaccount!
  [{:keys [store request modify-sub-account! runtime-error-message]
    :as deps
    :or {modify-sub-account! trading-api/modify-sub-account!
         runtime-error-message error-message}}]
  (let [owner (owner-address store)
        address (account-context/normalize-address (:sub-account-user request))
        name* (:name request)]
    (-> (modify-sub-account! store owner address name*)
        (.then (fn [resp]
                 (if (response-ok? resp)
                   (do
                     (clear-rename-success! store)
                     (refresh-subaccounts! deps))
                   (set-management-error!
                    store
                    [[[:account-context :subaccounts :renaming-address] nil]]
                    (response-error-message resp "Subaccount rename failed.")))))
        (.catch (fn [err]
                  (set-management-error!
                   store
                   [[[:account-context :subaccounts :renaming-address] nil]]
                   (management-runtime-error-message runtime-error-message err)))))))

(defn- clear-transfer-success!
  [store]
  (swap! store
         (fn [state]
           (-> state
               (assoc-in [:account-context :subaccounts :transferring-address] nil)
               (assoc-in [:account-context :subaccounts :transfer-amount] "")
               (assoc-in [:account-context :subaccounts :transfer-direction] :deposit)
               (assoc-in [:account-context :subaccounts :transfer-account] :trading)
               (assoc-in [:account-context :subaccounts :transfer-account-menu-open?] false)
               (assoc-in [:account-context :subaccounts :transfer-token] "USDC")
               (assoc-in [:account-context :subaccounts :transfer-token-menu-open?] false)
               (assoc-in [:account-context :subaccounts :error] nil)))))

(defn- dispatch-active-user-refresh!
  [store dispatch!]
  (when-let [address (account-context/effective-account-address @store)]
    (dispatch! store nil [[:effects/api-load-user-data address]])))

(defn- transfer-token-label
  [token]
  (let [token* (some-> token str str/trim)]
    (cond
      (and (seq token*) (str/includes? token* ":"))
      (first (str/split token* #":" 2))

      (seq token*)
      token*

      :else
      "USDC")))

(defn- subaccount-name-for-address
  [state address]
  (let [address* (account-context/normalize-address address)]
    (or (some (fn [row]
                (when (= address* (subaccount-row-address row))
                  (some-> (:name row) str str/trim not-empty)))
              (get-in state [:account-context :subaccounts :rows]))
        "Sub-Account")))

(defn- transfer-success-toast
  [state address is-deposit? amount token]
  (let [subaccount-name (subaccount-name-for-address state address)
        from-label (if is-deposit? "Master Account" subaccount-name)
        to-label (if is-deposit? subaccount-name "Master Account")]
    {:headline "Transfer submitted"
     :subline (str amount " " (transfer-token-label token)
                   " from " from-label " to " to-label)}))

(defn- show-transfer-success-toast!
  [store show-toast! address is-deposit? amount token]
  (show-toast! store
               :success
               (transfer-success-toast @store
                                       address
                                       is-deposit?
                                       amount
                                       token)))

(def ^:private unified-transfer-dex
  "spot")

(def ^:private unresolved-spot-token-message
  "Spot asset details are unavailable. Refresh balances before transferring.")

(defn- spot-wire-token
  "Wire token id (`NAME:0x<hash>`) for a spot transfer request, or nil.

   Hyperliquid rejects both a bare symbol such as `HYPE` and the bare numeric
   token index that a `spotClearinghouseState` balance row carries, so an
   unresolvable token must fail here rather than be signed unqualified. A blank
   token means the caller never chose one, which only happens on the legacy
   unified USDC default, so it resolves to USDC."
  [state token]
  (if (seq (some-> token str str/trim))
    (spot-tokens/resolve-with (spot-tokens/resolver state)
                              {:coin token :token token})
    (spot-tokens/usdc-wire-token-id state)))

(defn- unified-send-asset-action
  [owner address is-deposit? wire-token amount]
  {:type "sendAsset"
   :destination (if is-deposit? address owner)
   :sourceDex unified-transfer-dex
   :destinationDex unified-transfer-dex
   :token wire-token
   :amount (str amount)
   :fromSubAccount (if is-deposit? "" address)})

(defn transfer-subaccount!
  [{:keys [store
           request
           transfer-sub-account!
           transfer-sub-account-spot!
           submit-send-asset!
           dispatch!
           show-toast!
           runtime-error-message]
    :as deps
    :or {transfer-sub-account! trading-api/transfer-sub-account!
         transfer-sub-account-spot! trading-api/transfer-sub-account-spot!
         submit-send-asset! trading-api/submit-send-asset!
         dispatch! (fn [_store _ctx _effects] nil)
         show-toast! (fn [_store _kind _message] nil)
         runtime-error-message error-message}}]
  (let [owner (owner-address store)
        address (account-context/normalize-address (:sub-account-user request))
        is-deposit? (boolean (:is-deposit request))
        spot? (= :spot (:account-kind request))
        unified? (account-context/subaccounts-owner-unified? @store)
        usd (:usd request)
        token (:token request)
        amount (:amount request)
        amount-display (or (:amount-display request) amount)
        ;; Account mode decides which exchange primitive is legal at all, so it
        ;; is the outer question; the asset kind is the inner one. Hyperliquid
        ;; rejects the classic sub-account primitives for unified accounts
        ;; ("Unified account only supports sending assets through spot"), so
        ;; `unified?` must be tested before `spot?`.
        wire-token (when (or unified? spot?)
                     (spot-wire-token @store token))
        submit! (cond
                  (and (or unified? spot?) (not wire-token))
                  (js/Promise.resolve
                   {:status "err" :error unresolved-spot-token-message})

                  unified?
                  (submit-send-asset! store
                                      owner
                                      (unified-send-asset-action owner
                                                                 address
                                                                 is-deposit?
                                                                 wire-token
                                                                 amount))

                  spot?
                  (transfer-sub-account-spot! store owner address is-deposit? wire-token amount)

                  :else
                  (transfer-sub-account! store owner address is-deposit? usd))]
    (-> submit!
        (.then (fn [resp]
                 (if (response-ok? resp)
                   (do
                     (clear-transfer-success! store)
                     (dispatch-active-user-refresh! store dispatch!)
                     (-> (refresh-subaccounts! deps)
                         (.then (fn [result]
                                  (show-transfer-success-toast! store
                                                                show-toast!
                                                                address
                                                                is-deposit?
                                                                amount-display
                                                                token)
                                  result))))
                   (set-management-error!
                    store
                    [[[:account-context :subaccounts :transferring-address] nil]]
                    (response-error-message resp "Subaccount transfer failed.")))))
        (.catch (fn [err]
                  (set-management-error!
                   store
                   [[[:account-context :subaccounts :transferring-address] nil]]
                   (management-runtime-error-message runtime-error-message err)))))))

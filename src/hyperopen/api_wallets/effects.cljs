(ns hyperopen.api-wallets.effects
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.api.promise-effects :as promise-effects]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.api-wallets.actions :as api-wallets-actions]
            [hyperopen.wallet.agent-session :as agent-session]))

(defn- parse-ms
  [value]
  (let [parsed (cond
                 (integer? value) value
                 (and (number? value)
                      (not (js/isNaN value))) value
                 (string? value) (js/parseInt (str/trim value) 10)
                 :else js/NaN)]
    (when (and (number? parsed)
               (not (js/isNaN parsed)))
      (js/Math.floor parsed))))

(defn- load-route-active?
  [store]
  (api-wallets-actions/api-wallet-route?
   (get-in @store [:router :path] "")))

(defn- same-address?
  [left right]
  (= (agent-session/normalize-wallet-address left)
     (agent-session/normalize-wallet-address right)))

(defn- owner-webdata2-from-store
  [state owner-address]
  (let [effective-address (account-context/effective-account-address state)
        snapshot (:webdata2 state)
        server-time-ms (some parse-ms
                             [(:serverTime snapshot)
                              (:server-time snapshot)])]
    (when (and (seq owner-address)
               (same-address? owner-address effective-address)
               (map? snapshot)
               (number? server-time-ms))
      snapshot)))

(defn- refresh-request-opts
  [owner-address now-ms-fn request-kind]
  (let [token (now-ms-fn)]
    {:priority :high
     :cache-ttl-ms 1
     :dedupe-key [request-kind owner-address token]
     :cache-key [request-kind owner-address token]}))

(defn- set-form-error!
  [store message]
  (swap! store assoc-in [:api-wallets-ui :form-error] message))

(defn- set-modal-error!
  [store message]
  (swap! store
         (fn [state]
           (-> state
               (assoc-in [:api-wallets-ui :modal :submitting?] false)
               (assoc-in [:api-wallets-ui :modal :error] message)))))

(defn- close-modal-and-clear-generated!
  [store]
  (swap! store
         (fn [state]
           (-> state
               (assoc-in [:api-wallets-ui :modal]
                         (api-wallets-actions/default-api-wallet-modal-state))
               (assoc-in [:api-wallets-ui :generated]
                         {:address nil
                          :private-key nil})
               (assoc-in [:api-wallets-ui :form :days-valid] "")
               (assoc-in [:api-wallets-ui :form-error] nil)))))

(defn- reset-form-after-authorize!
  [store]
  (swap! store
         (fn [state]
           (-> state
               (assoc-in [:api-wallets-ui :form]
                         (api-wallets-actions/default-api-wallet-form))
               (assoc-in [:api-wallets-ui :form-error] nil)))))

(defn- clear-default-agent-session!
  [{:keys [store owner-address clear-agent-session-by-mode! default-agent-state]}]
  (when (seq owner-address)
    (clear-agent-session-by-mode! owner-address :local)
    (clear-agent-session-by-mode! owner-address :session)
    (let [storage-mode (get-in @store [:wallet :agent :storage-mode])]
      (swap! store assoc-in [:wallet :agent]
             (assoc (default-agent-state :storage-mode storage-mode)
                    :error nil)))))

(defn load-api-wallets!
  [{:keys [store
           request-extra-agents!
           request-user-webdata2!
           apply-api-wallets-extra-agents-success
           apply-api-wallets-extra-agents-error
           apply-api-wallets-default-agent-success
           apply-api-wallets-default-agent-error
           clear-api-wallets-errors
           reset-api-wallets
           now-ms-fn
           force-refresh?]
    :or {clear-api-wallets-errors api-projections/clear-api-wallets-errors
         reset-api-wallets api-projections/reset-api-wallets}}]
  (if-not (load-route-active? store)
    (js/Promise.resolve nil)
    (let [state @store
          owner-address (account-context/owner-address state)]
      (if-not (seq owner-address)
        (do
          (swap! store reset-api-wallets)
          (js/Promise.resolve nil))
        (let [extra-agent-opts (if force-refresh?
                                 (refresh-request-opts owner-address now-ms-fn :extra-agents)
                                 {:priority :high})
              user-webdata2-opts (if force-refresh?
                                   (refresh-request-opts owner-address now-ms-fn :user-webdata2)
                                   {:priority :high})
              local-webdata2 (owner-webdata2-from-store state owner-address)
              extra-agents-promise
              (-> (request-extra-agents! owner-address extra-agent-opts)
                  (.then (promise-effects/apply-success-and-return
                          store
                          apply-api-wallets-extra-agents-success))
                  (.catch (promise-effects/apply-error-and-reject
                           store
                           apply-api-wallets-extra-agents-error)))
              default-agent-promise
              (if local-webdata2
                (do
                  (swap! store apply-api-wallets-default-agent-success owner-address local-webdata2)
                  (js/Promise.resolve local-webdata2))
                (-> (request-user-webdata2! owner-address user-webdata2-opts)
                    (.then (promise-effects/apply-success-and-return
                            store
                            apply-api-wallets-default-agent-success
                            owner-address))
                    (.catch (promise-effects/apply-error-and-reject
                             store
                             apply-api-wallets-default-agent-error
                             owner-address))))]
          (swap! store clear-api-wallets-errors)
          (-> (js/Promise.allSettled
               #js [extra-agents-promise
                    default-agent-promise])
              (.then (fn [_results]
                       nil))))))))

(defn api-load-api-wallets!
  [deps]
  (load-api-wallets! (merge {:force-refresh? false} deps)))

(defn generate-api-wallet!
  [{:keys [store
           create-agent-credentials!
           runtime-error-message]}]
  (try
    (let [{:keys [private-key agent-address]} (create-agent-credentials!)]
      (swap! store
             (fn [state]
               (-> state
                   (assoc-in [:api-wallets-ui :form :address] agent-address)
                   (assoc-in [:api-wallets-ui :generated]
                             {:address agent-address
                              :private-key private-key})
                   (assoc-in [:api-wallets-ui :form-error] nil))))
      nil)
    (catch :default err
      (set-form-error! store (runtime-error-message err)))))

(defn api-authorize-api-wallet!
  [{:keys [store
           approve-agent-request!
           load-api-wallets!
           runtime-error-message]
    :as deps}]
  (let [state @store
        owner-address (account-context/owner-address state)
        form (get-in state [:api-wallets-ui :form])
        {:keys [name address days-valid]} form
        validation-error (some identity
                               (vals (api-wallets-actions/api-wallet-form-errors form)))
        generated-address (get-in state [:api-wallets-ui :generated :address])
        generated-private-key (when (same-address? address generated-address)
                                (get-in state [:api-wallets-ui :generated :private-key]))
        server-time-ms (get-in state [:api-wallets :server-time-ms])]
    (if (seq validation-error)
      (set-modal-error! store validation-error)
      (-> (approve-agent-request!
           (merge deps
                  {:store store
                   :owner-address owner-address
                   :agent-address address
                   :private-key generated-private-key
                   :agent-name name
                   :days-valid days-valid
                   :server-time-ms server-time-ms
                   :persist-session? false
                   :missing-owner-error "Connect your wallet before authorizing an API wallet."}))
          (.then
           (fn [_result]
             (reset-form-after-authorize! store)
             (close-modal-and-clear-generated! store)
             (load-api-wallets! (assoc deps :force-refresh? true))))
          (.catch
           (fn [err]
             (set-modal-error! store (runtime-error-message err))))))))

(defn api-remove-api-wallet!
  [{:keys [store
           approve-agent-request!
           load-api-wallets!
           clear-agent-session-by-mode!
           default-agent-state
           runtime-error-message]
    :as deps}]
  (let [state @store
        owner-address (account-context/owner-address state)
        row (get-in state [:api-wallets-ui :modal :row])
        named-row? (= :named (:row-kind row))
        default-row? (= :default (:row-kind row))
        approval-name (when named-row?
                        (or (:approval-name row)
                            (:name row)))]
    (if-not (map? row)
      (set-modal-error! store "Select an API wallet row to remove.")
      (-> (approve-agent-request!
           (merge deps
                  {:store store
                   :owner-address owner-address
                   :agent-address agent-session/zero-address
                   :agent-name approval-name
                   :persist-session? false
                   :missing-owner-error "Connect your wallet before removing an API wallet."}))
          (.then
           (fn [_result]
             (when default-row?
               (clear-default-agent-session!
                {:store store
                 :owner-address owner-address
                 :clear-agent-session-by-mode! clear-agent-session-by-mode!
                 :default-agent-state default-agent-state}))
             (close-modal-and-clear-generated! store)
             (load-api-wallets! (assoc deps :force-refresh? true))))
          (.catch
           (fn [err]
             (set-modal-error! store (runtime-error-message err))))))))

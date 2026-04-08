(ns hyperopen.wallet.agent-runtime)

(defn- noop
  [& _]
  nil)

(defn- noop-promise
  [& _]
  (js/Promise.resolve nil))

(defn- fallback-local-protection-mode
  [local-protection-mode]
  (if (or (= :passkey local-protection-mode)
          (= "passkey" local-protection-mode))
    :passkey
    :plain))

(defn exchange-response-error
  [resp]
  (or (:error resp)
      (:response resp)
      (:message resp)
      (pr-str resp)))

(defn runtime-error-message
  [err]
  (or (some-> err .-message str)
      (some-> err (aget "message") str)
      (some-> err (aget "data") (aget "message") str)
      (some-> err (aget "error") (aget "message") str)
      (when (map? err)
        (or (some-> (:message err) str)
            (some-> err :data :message str)
            (some-> err :error :message str)))
      (try
        (let [clj-value (js->clj err :keywordize-keys true)]
          (when (map? clj-value)
            (or (some-> (:message clj-value) str)
                (some-> clj-value :data :message str)
                (some-> clj-value :error :message str)
                (pr-str clj-value))))
        (catch :default _
          nil))
      (str err)))

(defn- runtime-error
  [runtime-error-message err]
  (js/Error. (runtime-error-message err)))

(defn- known-error
  [message]
  (doto (js/Error. message)
    (aset "__hyperopenKnownMessage" true)))

(defn- known-error?
  [err]
  (true? (some-> err (aget "__hyperopenKnownMessage"))))

(defn- set-agent-error!
  [store error]
  (swap! store update-in [:wallet :agent] merge
         {:status :error
          :error error
          :agent-address nil
          :last-approved-at nil
          :nonce-cursor nil}))

(defn- current-local-protection-mode
  [store normalize-local-protection-mode]
  (normalize-local-protection-mode
   (get-in @store [:wallet :agent :local-protection-mode])))

(defn- reset-agent-state!
  [store default-agent-state storage-mode local-protection-mode error]
  (let [passkey-supported? (true? (get-in @store [:wallet :agent :passkey-supported?]))]
    (swap! store assoc-in [:wallet :agent]
           (assoc (default-agent-state :storage-mode storage-mode
                                       :local-protection-mode local-protection-mode
                                       :passkey-supported? passkey-supported?)
                  :error error))))

(defn- passkey-local-mode?
  [storage-mode local-protection-mode]
  (and (= :local storage-mode)
       (= :passkey local-protection-mode)))

(defn- migration-session
  [agent-state storage-mode local-protection-mode session]
  (let [agent-address (or (:agent-address session)
                          (:agent-address agent-state))
        private-key (:private-key session)]
    (when (and (map? session)
               (string? agent-address)
               (seq agent-address)
               (string? private-key)
               (seq private-key))
      {:agent-address agent-address
       :private-key private-key
       :last-approved-at (or (:last-approved-at session)
                             (:last-approved-at agent-state))
       :nonce-cursor (or (:nonce-cursor session)
                         (:nonce-cursor agent-state))
       :storage-mode storage-mode
       :local-protection-mode local-protection-mode})))

(defn- apply-migrated-agent-session!
  [store session]
  (swap! store update-in [:wallet :agent] merge
         {:status :ready
          :agent-address (:agent-address session)
          :storage-mode (:storage-mode session)
          :local-protection-mode (:local-protection-mode session)
          :last-approved-at (:last-approved-at session)
          :nonce-cursor (:nonce-cursor session)
          :error nil
          :recovery-modal-open? false}))

(defn- set-agent-local-protection-error!
  [store error]
  (swap! store update-in [:wallet :agent] merge
         {:error error
          :recovery-modal-open? false}))

(defn- migration-session-for-mode
  [{:keys [agent-state
           wallet-address
           storage-mode
           local-protection-mode
           load-unlocked-session
           load-agent-session-by-mode]}]
  (or (some->> (when (fn? load-unlocked-session)
                 (load-unlocked-session wallet-address))
               (migration-session agent-state storage-mode local-protection-mode))
      (some->> (when (and (fn? load-agent-session-by-mode)
                          (not= :passkey local-protection-mode))
                 (load-agent-session-by-mode wallet-address storage-mode))
               (migration-session agent-state storage-mode local-protection-mode))))

(defn- ignore-delete-failure!
  [delete-promise]
  (if (some? delete-promise)
    (.catch delete-promise (fn [_] (js/Promise.resolve nil)))
    (js/Promise.resolve nil)))

(defn set-agent-storage-mode!
  [{:keys [store
           storage-mode
           normalize-storage-mode
           normalize-local-protection-mode
           clear-persisted-agent-session!
           clear-agent-session-by-mode!
           clear-unlocked-session!
           persist-storage-mode-preference!
           default-agent-state
           agent-storage-mode-reset-message]
    :or {normalize-storage-mode identity
         normalize-local-protection-mode fallback-local-protection-mode
         clear-unlocked-session! noop
         persist-storage-mode-preference! noop}}]
  (let [clear-persisted-agent-session!* (or clear-persisted-agent-session!
                                            (fn [wallet-address mode _local-protection-mode]
                                              (when clear-agent-session-by-mode!
                                                (clear-agent-session-by-mode! wallet-address mode))))
        next-mode (normalize-storage-mode storage-mode)
        current-mode (normalize-storage-mode
                      (get-in @store [:wallet :agent :storage-mode]))
        local-protection-mode (current-local-protection-mode store
                                                             normalize-local-protection-mode)
        wallet-address (get-in @store [:wallet :address])
        switching? (not= current-mode next-mode)]
    (when switching?
      (when (seq wallet-address)
        (clear-persisted-agent-session!* wallet-address current-mode local-protection-mode)
        (clear-persisted-agent-session!* wallet-address next-mode local-protection-mode)
        (clear-unlocked-session! wallet-address))
      (persist-storage-mode-preference! next-mode)
      (reset-agent-state! store
                          default-agent-state
                          next-mode
                          local-protection-mode
                          agent-storage-mode-reset-message))))

(defn set-agent-local-protection-mode!
  [{:keys [store
           local-protection-mode
           normalize-local-protection-mode
           normalize-storage-mode
           clear-persisted-agent-session!
           clear-agent-session-by-mode!
           load-agent-session-by-mode
           load-unlocked-session
           clear-unlocked-session!
           cache-unlocked-session!
           create-locked-session!
           delete-locked-session!
           persist-agent-session-by-mode!
           persist-passkey-session-metadata!
           clear-passkey-session-metadata!
           persist-local-protection-mode-preference!
           default-agent-state
           agent-protection-mode-reset-message
           persist-session-error
           missing-session-error
           unlock-required-error]
    :or {normalize-storage-mode identity
         normalize-local-protection-mode fallback-local-protection-mode
         clear-unlocked-session! noop
         cache-unlocked-session! noop
         create-locked-session! noop-promise
         delete-locked-session! noop-promise
         persist-passkey-session-metadata! (constantly false)
         clear-passkey-session-metadata! noop
         persist-local-protection-mode-preference! noop
         persist-session-error "Unable to persist agent credentials."
         missing-session-error "Trading session data is unavailable. Enable Trading again."
         unlock-required-error "Unlock trading before turning off passkey protection."}}]
  (let [clear-persisted-agent-session!* (or clear-persisted-agent-session!
                                            (fn [wallet-address mode _local-protection-mode]
                                              (when clear-agent-session-by-mode!
                                                (clear-agent-session-by-mode! wallet-address mode))))
        next-mode (normalize-local-protection-mode local-protection-mode)
        agent-state (get-in @store [:wallet :agent] {})
        current-mode (normalize-local-protection-mode
                      (:local-protection-mode agent-state))
        storage-mode (normalize-storage-mode
                      (:storage-mode agent-state))
        wallet-address (get-in @store [:wallet :address])
        switching? (not= current-mode next-mode)]
    (when switching?
      (let [status (:status agent-state)
            live-session (when (seq wallet-address)
                           (migration-session-for-mode
                            {:agent-state agent-state
                             :wallet-address wallet-address
                             :storage-mode storage-mode
                             :local-protection-mode current-mode
                             :load-unlocked-session load-unlocked-session
                             :load-agent-session-by-mode load-agent-session-by-mode}))]
        (cond
          (and (= :passkey current-mode)
               (= :plain next-mode)
               (nil? live-session))
          (set-agent-local-protection-error! store
                                             (if (#{:locked :unlocking} status)
                                               unlock-required-error
                                               missing-session-error))

          (and (= :ready status)
               (= :plain current-mode)
               (= :passkey next-mode))
          (if-not live-session
            (set-agent-local-protection-error! store missing-session-error)
            (-> (create-locked-session! {:wallet-address wallet-address
                                         :session live-session})
                (.then
                 (fn [{:keys [metadata session]}]
                   (let [locked-session (or (migration-session agent-state
                                                               storage-mode
                                                               next-mode
                                                               session)
                                            (assoc live-session
                                                   :storage-mode storage-mode
                                                   :local-protection-mode next-mode))]
                     (cond
                       (not (persist-passkey-session-metadata! wallet-address metadata))
                       (-> (ignore-delete-failure! (delete-locked-session! wallet-address))
                           (.then (fn [_]
                                    (js/Promise.reject
                                     (known-error persist-session-error)))))

                       (not (persist-local-protection-mode-preference! next-mode))
                       (do
                         (clear-passkey-session-metadata! wallet-address)
                         (-> (ignore-delete-failure! (delete-locked-session! wallet-address))
                             (.then (fn [_]
                                      (js/Promise.reject
                                       (known-error persist-session-error))))))

                       (and (fn? clear-agent-session-by-mode!)
                            (false? (clear-agent-session-by-mode! wallet-address storage-mode)))
                       (do
                         (persist-local-protection-mode-preference! current-mode)
                         (clear-passkey-session-metadata! wallet-address)
                         (-> (ignore-delete-failure! (delete-locked-session! wallet-address))
                             (.then (fn [_]
                                      (js/Promise.reject
                                       (known-error persist-session-error))))))

                       :else
                       (do
                         (cache-unlocked-session! wallet-address locked-session)
                         (apply-migrated-agent-session! store locked-session)
                         locked-session)))))
                (.catch
                 (fn [err]
                   (set-agent-local-protection-error! store
                                                      (if (known-error? err)
                                                        (or (some-> err .-message str)
                                                            missing-session-error)
                                                        (runtime-error-message err)))))))

          (and (= :ready status)
               (= :passkey current-mode)
               (= :plain next-mode))
          (if-not live-session
            (set-agent-local-protection-error! store missing-session-error)
            (if-not (persist-agent-session-by-mode!
                     wallet-address
                     storage-mode
                     live-session)
              (set-agent-local-protection-error! store persist-session-error)
              (if-not (persist-local-protection-mode-preference! next-mode)
                (do
                  (when (fn? clear-agent-session-by-mode!)
                    (clear-agent-session-by-mode! wallet-address storage-mode))
                  (set-agent-local-protection-error! store persist-session-error))
                (do
                  (clear-passkey-session-metadata! wallet-address)
                  (-> (ignore-delete-failure! (delete-locked-session! wallet-address))
                      (.then
                       (fn [_]
                         (let [plain-session (assoc live-session
                                                    :storage-mode storage-mode
                                                    :local-protection-mode next-mode)]
                           (cache-unlocked-session! wallet-address plain-session)
                           (apply-migrated-agent-session! store plain-session)))))))))

          :else
          (do
            (persist-local-protection-mode-preference! next-mode)
            (if (and (= :passkey current-mode)
                     (= :plain next-mode))
              (reset-agent-state! store
                                  default-agent-state
                                  storage-mode
                                  current-mode
                                  unlock-required-error)
              (swap! store update-in [:wallet :agent] merge
                     {:storage-mode storage-mode
                      :local-protection-mode next-mode}))))))))

(defn approve-agent-request!
  [{:keys [store
           owner-address
           agent-address
           private-key
           storage-mode
           is-mainnet
           agent-name
           days-valid
           signature-chain-id
           server-time-ms
           persist-session?
           missing-owner-error
           persist-session-error
           now-ms-fn
           normalize-storage-mode
           default-signature-chain-id-for-environment
           build-approve-agent-action
           format-agent-name-with-valid-until
           approve-agent!
           persist-agent-session-by-mode!
           runtime-error-message
           exchange-response-error]
    :or {storage-mode :local
         is-mainnet true
         persist-session? false
         missing-owner-error "Connect your wallet before approving an agent."
         persist-session-error "Unable to persist agent credentials."}}]
  (let [owner-address* (or owner-address
                           (get-in @store [:wallet :address]))]
    (if-not (seq owner-address*)
      (js/Promise.reject (known-error missing-owner-error))
      (try
        (let [nonce (now-ms-fn)
              normalized-storage-mode (normalize-storage-mode storage-mode)
              wallet-chain-id (get-in @store [:wallet :chain-id])
              resolved-signature-chain-id (or signature-chain-id
                                              wallet-chain-id
                                              (default-signature-chain-id-for-environment is-mainnet))
              format-agent-name* (or format-agent-name-with-valid-until
                                     (fn [name _server-time-ms _days-valid]
                                       name))
              encoded-agent-name (format-agent-name* agent-name
                                                     server-time-ms
                                                     days-valid)
              action (build-approve-agent-action
                      agent-address
                      nonce
                      :agent-name encoded-agent-name
                      :is-mainnet is-mainnet
                      :signature-chain-id resolved-signature-chain-id)]
          (-> (approve-agent! store owner-address* action)
              (.then #(.json %))
              (.then
               (fn [resp]
                 (let [data (js->clj resp :keywordize-keys true)]
                   (if (= "ok" (:status data))
                     (if persist-session?
                       (if (and (string? private-key)
                                (seq private-key)
                                (persist-agent-session-by-mode!
                                 owner-address*
                                 normalized-storage-mode
                                 {:agent-address agent-address
                                  :private-key private-key
                                  :last-approved-at nonce
                                  :nonce-cursor nonce}))
                         {:owner-address owner-address*
                          :agent-address agent-address
                          :private-key private-key
                          :storage-mode normalized-storage-mode
                          :last-approved-at nonce
                          :nonce-cursor nonce
                          :response data
                          :action action}
                         (js/Promise.reject
                          (known-error persist-session-error)))
                       {:owner-address owner-address*
                        :agent-address agent-address
                        :private-key private-key
                        :storage-mode normalized-storage-mode
                        :last-approved-at nonce
                        :nonce-cursor nonce
                        :response data
                        :action action})
                     (js/Promise.reject
                      (known-error (exchange-response-error data)))))))
              (.catch (fn [err]
                        (js/Promise.reject
                         (if (known-error? err)
                           err
                           (runtime-error runtime-error-message err)))))))
        (catch :default err
          (js/Promise.reject
           (runtime-error runtime-error-message err)))))))

(defn- persist-enabled-agent-session!
  [{:keys [owner-address
           agent-address
           private-key
           storage-mode
           local-protection-mode
           last-approved-at
           nonce-cursor
           passkey-lock-supported?
           create-locked-session!
           cache-unlocked-session!
           persist-passkey-session-metadata!
           delete-locked-session!
           persist-agent-session-by-mode!
           persist-session-error]
    :or {passkey-lock-supported? (constantly false)
         create-locked-session! noop-promise
         cache-unlocked-session! noop
         persist-passkey-session-metadata! (constantly false)
         delete-locked-session! noop-promise
         persist-session-error "Unable to persist agent credentials."}}]
  (if (passkey-local-mode? storage-mode local-protection-mode)
    (if-not (passkey-lock-supported?)
      (js/Promise.reject
       (known-error "Passkey unlock is unavailable in this browser."))
      (-> (create-locked-session! {:wallet-address owner-address
                                   :session {:agent-address agent-address
                                             :private-key private-key
                                             :last-approved-at last-approved-at
                                             :nonce-cursor nonce-cursor}})
          (.then
           (fn [{:keys [metadata session]}]
             (if (persist-passkey-session-metadata! owner-address metadata)
               (do
                 (cache-unlocked-session! owner-address
                                          (assoc session
                                                 :storage-mode storage-mode
                                                 :local-protection-mode local-protection-mode))
                 {:storage-mode storage-mode
                  :local-protection-mode local-protection-mode
                  :agent-address agent-address
                  :last-approved-at last-approved-at
                  :nonce-cursor nonce-cursor})
               (-> (delete-locked-session! owner-address)
                   (.then (fn [_]
                            (js/Promise.reject
                             (known-error persist-session-error))))))))))
    (if (persist-agent-session-by-mode!
         owner-address
         storage-mode
         {:agent-address agent-address
          :private-key private-key
          :last-approved-at last-approved-at
          :nonce-cursor nonce-cursor})
      (do
        (cache-unlocked-session! owner-address
                                 {:agent-address agent-address
                                  :private-key private-key
                                  :last-approved-at last-approved-at
                                  :nonce-cursor nonce-cursor
                                  :storage-mode storage-mode
                                  :local-protection-mode local-protection-mode})
        (js/Promise.resolve {:storage-mode storage-mode
                             :local-protection-mode local-protection-mode
                             :agent-address agent-address
                             :last-approved-at last-approved-at
                             :nonce-cursor nonce-cursor}))
      (js/Promise.reject
       (known-error persist-session-error)))))

(defn enable-agent-trading!
  [{:keys [store
           options
           create-agent-credentials!
           now-ms-fn
           normalize-storage-mode
           normalize-local-protection-mode
           ensure-device-label!
           passkey-lock-supported?
           create-locked-session!
           cache-unlocked-session!
           persist-passkey-session-metadata!
           delete-locked-session!
           default-signature-chain-id-for-environment
           build-approve-agent-action
           format-agent-name-with-valid-until
           approve-agent!
           persist-agent-session-by-mode!
           runtime-error-message
           exchange-response-error]
    :or {normalize-storage-mode identity
         normalize-local-protection-mode fallback-local-protection-mode
         ensure-device-label! (constantly nil)
         passkey-lock-supported? (constantly false)
         create-locked-session! noop-promise
         cache-unlocked-session! noop
         persist-passkey-session-metadata! (constantly false)
         delete-locked-session! noop-promise}}]
  (let [{:keys [storage-mode local-protection-mode is-mainnet agent-name signature-chain-id]
         :or {storage-mode :local
              local-protection-mode :plain
              is-mainnet true
              agent-name nil
              signature-chain-id nil}} options
        owner-address (get-in @store [:wallet :address])]
    (if-not (seq owner-address)
      (set-agent-error! store "Connect your wallet before enabling trading.")
      (try
        (let [{:keys [private-key agent-address]} (create-agent-credentials!)
              normalized-storage-mode (normalize-storage-mode storage-mode)
              normalized-local-protection-mode
              (normalize-local-protection-mode local-protection-mode)
              device-label (ensure-device-label!)
              resolved-agent-name (or agent-name device-label)]
          (-> (approve-agent-request!
               {:store store
                :owner-address owner-address
                :agent-address agent-address
                :private-key private-key
                :storage-mode normalized-storage-mode
                :is-mainnet is-mainnet
                :agent-name resolved-agent-name
                :signature-chain-id signature-chain-id
                :persist-session? false
                :missing-owner-error "Connect your wallet before enabling trading."
                :persist-session-error "Unable to persist agent credentials."
                :now-ms-fn now-ms-fn
                :normalize-storage-mode normalize-storage-mode
                :default-signature-chain-id-for-environment default-signature-chain-id-for-environment
                :build-approve-agent-action build-approve-agent-action
                :format-agent-name-with-valid-until format-agent-name-with-valid-until
                :approve-agent! approve-agent!
                :persist-agent-session-by-mode! persist-agent-session-by-mode!
                :runtime-error-message runtime-error-message
                :exchange-response-error exchange-response-error})
              (.then
               (fn [{:keys [last-approved-at nonce-cursor]}]
                 (persist-enabled-agent-session!
                  {:owner-address owner-address
                   :agent-address agent-address
                   :private-key private-key
                   :storage-mode normalized-storage-mode
                   :local-protection-mode normalized-local-protection-mode
                   :last-approved-at last-approved-at
                   :nonce-cursor nonce-cursor
                   :passkey-lock-supported? passkey-lock-supported?
                   :create-locked-session! create-locked-session!
                   :cache-unlocked-session! cache-unlocked-session!
                   :persist-passkey-session-metadata! persist-passkey-session-metadata!
                   :delete-locked-session! delete-locked-session!
                   :persist-agent-session-by-mode! persist-agent-session-by-mode!
                   :persist-session-error "Unable to persist agent credentials."})))
              (.then
               (fn [{:keys [last-approved-at nonce-cursor]}]
                 (swap! store update-in [:wallet :agent] merge
                        {:status :ready
                         :agent-address agent-address
                         :storage-mode normalized-storage-mode
                         :local-protection-mode normalized-local-protection-mode
                         :last-approved-at last-approved-at
                         :error nil
                         :recovery-modal-open? false
                         :nonce-cursor nonce-cursor})))
              (.catch
               (fn [err]
                 (set-agent-error! store
                                   (if (known-error? err)
                                     (or (some-> err .-message str)
                                         (runtime-error-message err))
                                     (runtime-error-message err)))))))
        (catch :default err
          (set-agent-error! store (runtime-error-message err)))))))

(defn unlock-agent-trading!
  [{:keys [store
           normalize-storage-mode
           normalize-local-protection-mode
           load-passkey-session-metadata
           unlock-locked-session!
           runtime-error-message]
    :or {normalize-storage-mode identity
         normalize-local-protection-mode fallback-local-protection-mode
         load-passkey-session-metadata (constantly nil)
         unlock-locked-session! noop-promise}}]
  (let [owner-address (get-in @store [:wallet :address])
        agent-state (get-in @store [:wallet :agent] {})
        storage-mode (normalize-storage-mode (:storage-mode agent-state))
        local-protection-mode (normalize-local-protection-mode
                               (:local-protection-mode agent-state))
        metadata (when (seq owner-address)
                   (load-passkey-session-metadata owner-address))]
    (cond
      (not (seq owner-address))
      (swap! store update-in [:wallet :agent] merge
             {:status :locked
              :error "Connect your wallet before unlocking trading."})

      (not (passkey-local-mode? storage-mode local-protection-mode))
      (swap! store update-in [:wallet :agent] merge
             {:status :error
              :error "Trading unlock is available only for remembered passkey sessions."})

      (not (map? metadata))
      (set-agent-error! store "Enable Trading before unlocking.")

      :else
      (-> (unlock-locked-session! {:metadata metadata
                                   :wallet-address owner-address})
          (.then
           (fn [_]
             (swap! store update-in [:wallet :agent] merge
                    {:status :ready
                     :agent-address (:agent-address metadata)
                     :storage-mode storage-mode
                     :local-protection-mode local-protection-mode
                     :last-approved-at (:last-approved-at metadata)
                     :error nil
                     :recovery-modal-open? false
                     :nonce-cursor (:nonce-cursor metadata)})))
          (.catch
           (fn [err]
             (swap! store update-in [:wallet :agent] merge
                    {:status :locked
                     :agent-address (:agent-address metadata)
                     :storage-mode storage-mode
                     :local-protection-mode local-protection-mode
                     :last-approved-at (:last-approved-at metadata)
                     :error (if (known-error? err)
                              (or (some-> err .-message str)
                                  (runtime-error-message err))
                              (runtime-error-message err))
                     :nonce-cursor (:nonce-cursor metadata)})))))))

(defn lock-agent-trading!
  [{:keys [store
           normalize-storage-mode
           normalize-local-protection-mode
           clear-unlocked-session!]
    :or {normalize-storage-mode identity
         normalize-local-protection-mode fallback-local-protection-mode
         clear-unlocked-session! noop}}]
  (let [owner-address (get-in @store [:wallet :address])
        agent-state (get-in @store [:wallet :agent] {})
        storage-mode (normalize-storage-mode (:storage-mode agent-state))
        local-protection-mode (normalize-local-protection-mode
                               (:local-protection-mode agent-state))]
    (when (seq owner-address)
      (clear-unlocked-session! owner-address))
    (when (passkey-local-mode? storage-mode local-protection-mode)
      (swap! store update-in [:wallet :agent] merge
             {:status :locked
              :error nil
              :recovery-modal-open? false}))))

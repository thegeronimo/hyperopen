(ns hyperopen.wallet.agent-runtime)

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

(defn set-agent-storage-mode!
  [{:keys [store
           storage-mode
           normalize-storage-mode
           clear-agent-session-by-mode!
           persist-storage-mode-preference!
           default-agent-state
           agent-storage-mode-reset-message]}]
  (let [next-mode (normalize-storage-mode storage-mode)
        current-mode (normalize-storage-mode
                      (get-in @store [:wallet :agent :storage-mode]))
        wallet-address (get-in @store [:wallet :address])
        switching? (not= current-mode next-mode)]
    (when switching?
      (when (seq wallet-address)
        (clear-agent-session-by-mode! wallet-address current-mode)
        (clear-agent-session-by-mode! wallet-address next-mode))
      (persist-storage-mode-preference! next-mode)
      (swap! store assoc-in [:wallet :agent]
             (assoc (default-agent-state :storage-mode next-mode)
                    :error agent-storage-mode-reset-message)))))

(defn- set-agent-error!
  [store error]
  (swap! store update-in [:wallet :agent] merge
         {:status :error
          :error error
          :agent-address nil
          :last-approved-at nil
          :nonce-cursor nil}))

(defn- runtime-error
  [runtime-error-message err]
  (let [message (runtime-error-message err)]
    (js/Error. message)))

(defn- known-error
  [message]
  (doto (js/Error. message)
    (aset "__hyperopenKnownMessage" true)))

(defn- known-error?
  [err]
  (true? (some-> err (aget "__hyperopenKnownMessage"))))

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

(defn enable-agent-trading!
  [{:keys [store
           options
           create-agent-credentials!
           now-ms-fn
           normalize-storage-mode
           default-signature-chain-id-for-environment
           build-approve-agent-action
           format-agent-name-with-valid-until
           approve-agent!
           persist-agent-session-by-mode!
           runtime-error-message
           exchange-response-error]}]
  (let [{:keys [storage-mode is-mainnet agent-name signature-chain-id]
         :or {storage-mode :local
              is-mainnet true
              agent-name nil
              signature-chain-id nil}} options
        owner-address (get-in @store [:wallet :address])]
    (if-not (seq owner-address)
      (set-agent-error! store "Connect your wallet before enabling trading.")
      (try
        (let [{:keys [private-key agent-address]} (create-agent-credentials!)
              normalized-storage-mode (normalize-storage-mode storage-mode)]
          (-> (approve-agent-request!
               {:store store
                :owner-address owner-address
                :agent-address agent-address
                :private-key private-key
                :storage-mode normalized-storage-mode
                :is-mainnet is-mainnet
                :agent-name agent-name
                :signature-chain-id signature-chain-id
                :persist-session? true
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
              (.then (fn [{:keys [last-approved-at nonce-cursor]}]
                       (swap! store update-in [:wallet :agent] merge
                              {:status :ready
                               :agent-address agent-address
                               :storage-mode normalized-storage-mode
                               :last-approved-at last-approved-at
                               :error nil
                               :recovery-modal-open? false
                               :nonce-cursor nonce-cursor})))
              (.catch (fn [err]
                        (set-agent-error! store
                                          (if (known-error? err)
                                            (or (some-> err .-message str)
                                                (runtime-error-message err))
                                            (runtime-error-message err)))))))
        (catch :default err
          (set-agent-error! store (runtime-error-message err)))))))

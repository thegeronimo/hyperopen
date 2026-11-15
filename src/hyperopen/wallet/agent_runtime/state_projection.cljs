(ns hyperopen.wallet.agent-runtime.state-projection)

(defn set-agent-error!
  [store error]
  (swap! store update-in [:wallet :agent] merge
         {:status :error
          :error error
          :agent-address nil
          :last-approved-at nil
          :nonce-cursor nil}))

(defn reset-agent-state!
  [store default-agent-state storage-mode local-protection-mode error]
  (let [agent-state (get-in @store [:wallet :agent])
        passkey-supported? (true? (:passkey-supported? agent-state))
        runtime-operation-seq (:runtime-operation-seq agent-state)]
    (swap! store assoc-in [:wallet :agent]
           (cond-> (assoc (default-agent-state :storage-mode storage-mode
                                               :local-protection-mode local-protection-mode
                                               :passkey-supported? passkey-supported?)
                          :error error)
             runtime-operation-seq
             (assoc :runtime-operation-seq runtime-operation-seq)))))

(defn apply-migrated-agent-session!
  [store session & [operation-key]]
  (swap! store update-in [:wallet :agent]
         (fn [agent-state]
           (cond-> (merge agent-state
                          {:status :ready
                           :agent-address (:agent-address session)
                           :storage-mode (:storage-mode session)
                           :local-protection-mode (:local-protection-mode session)
                           :last-approved-at (:last-approved-at session)
                           :nonce-cursor (:nonce-cursor session)
                           :error nil
                           :recovery-modal-open? false})
             operation-key (dissoc operation-key)))))

(defn apply-ready-agent-session!
  [store session & [operation-key]]
  (swap! store update-in [:wallet :agent]
         (fn [agent-state]
           (cond-> (merge agent-state
                          {:status :ready
                           :agent-address (:agent-address session)
                           :storage-mode (:storage-mode session)
                           :local-protection-mode (:local-protection-mode session)
                           :last-approved-at (:last-approved-at session)
                           :error nil
                           :recovery-modal-open? false
                           :nonce-cursor (:nonce-cursor session)})
             operation-key (dissoc operation-key)))))

(defn set-agent-local-protection-error!
  [store error]
  (swap! store update-in [:wallet :agent] merge
         {:error error
          :recovery-modal-open? false}))

(defn metadata->ready-session
  [metadata storage-mode local-protection-mode]
  {:agent-address (:agent-address metadata)
   :storage-mode storage-mode
   :local-protection-mode local-protection-mode
   :last-approved-at (:last-approved-at metadata)
   :nonce-cursor (:nonce-cursor metadata)
   :error nil
   :recovery-modal-open? false})

(defn next-operation-token!
  [store operation-key]
  (let [token* (atom nil)]
    (swap! store update-in [:wallet :agent]
           (fn [agent-state]
             (let [next-token (inc (or (:runtime-operation-seq agent-state) 0))]
               (reset! token* next-token)
               (assoc (or agent-state {})
                      :runtime-operation-seq next-token
                      operation-key next-token))))
    @token*))

(defn operation-current?
  [store operation-key token]
  (= token (get-in @store [:wallet :agent operation-key])))

(defn invalidate-operations!
  [store & operation-keys]
  (swap! store update-in [:wallet :agent]
         (fn [agent-state]
           (apply dissoc (or agent-state {}) operation-keys))))

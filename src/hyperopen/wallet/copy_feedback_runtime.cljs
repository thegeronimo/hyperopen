(ns hyperopen.wallet.copy-feedback-runtime)

(defn set-wallet-copy-feedback!
  [store kind message]
  (swap! store assoc-in [:wallet :copy-feedback] {:kind kind
                                                  :message message}))

(defn clear-wallet-copy-feedback!
  [store]
  (swap! store assoc-in [:wallet :copy-feedback] nil))

(defn clear-wallet-copy-feedback-timeout!
  [wallet-copy-feedback-timeout-id clear-timeout-fn]
  (when-let [timeout-id @wallet-copy-feedback-timeout-id]
    (clear-timeout-fn timeout-id)
    (reset! wallet-copy-feedback-timeout-id nil)))

(defn clear-wallet-copy-feedback-timeout-in-runtime!
  [runtime clear-timeout-fn]
  (when-let [timeout-id (get-in @runtime [:timeouts :wallet-copy])]
    (clear-timeout-fn timeout-id)
    (swap! runtime assoc-in [:timeouts :wallet-copy] nil)))

(defn schedule-wallet-copy-feedback-clear!
  [{:keys [store
           runtime
           wallet-copy-feedback-timeout-id
           clear-wallet-copy-feedback!
           clear-wallet-copy-feedback-timeout!
           wallet-copy-feedback-duration-ms
           set-timeout-fn]}]
  (clear-wallet-copy-feedback-timeout!)
  (let [timeout-id (set-timeout-fn
                    (fn []
                      (clear-wallet-copy-feedback! store)
                      (if runtime
                        (swap! runtime assoc-in [:timeouts :wallet-copy] nil)
                        (reset! wallet-copy-feedback-timeout-id nil)))
                    wallet-copy-feedback-duration-ms)]
    (if runtime
      (swap! runtime assoc-in [:timeouts :wallet-copy] timeout-id)
      (reset! wallet-copy-feedback-timeout-id timeout-id))))

(defn- resolve-clipboard
  [clipboard]
  (or clipboard
      (some-> js/globalThis .-navigator .-clipboard)))

(defn copy-wallet-address!
  [{:keys [store
           address
           set-wallet-copy-feedback!
           clear-wallet-copy-feedback!
           clear-wallet-copy-feedback-timeout!
           schedule-wallet-copy-feedback-clear!
           log-fn
           clipboard]}]
  (let [clipboard* (resolve-clipboard clipboard)
        write-text-fn (some-> clipboard* .-writeText)]
    (clear-wallet-copy-feedback! store)
    (clear-wallet-copy-feedback-timeout!)
    (cond
      (not (seq address))
      (do
        (set-wallet-copy-feedback! store :error "No address to copy")
        (schedule-wallet-copy-feedback-clear! store))

      (not (and clipboard* write-text-fn))
      (do
        (set-wallet-copy-feedback! store :error "Clipboard unavailable")
        (schedule-wallet-copy-feedback-clear! store))

      :else
      (try
        (-> (.writeText clipboard* address)
            (.then (fn []
                     (set-wallet-copy-feedback! store :success "Address copied to clipboard")
                     (schedule-wallet-copy-feedback-clear! store)))
            (.catch (fn [err]
                      (log-fn "Copy wallet address failed:" err)
                      (set-wallet-copy-feedback! store :error "Couldn't copy address")
                      (schedule-wallet-copy-feedback-clear! store))))
        (catch :default err
          (log-fn "Copy wallet address failed:" err)
          (set-wallet-copy-feedback! store :error "Couldn't copy address")
          (schedule-wallet-copy-feedback-clear! store))))))

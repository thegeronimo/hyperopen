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

(defn- copy-text!
  [{:keys [store
           text
           missing-message
           success-message
           failure-message
           log-label
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
      (not (seq text))
      (do
        (set-wallet-copy-feedback! store :error missing-message)
        (schedule-wallet-copy-feedback-clear! store))

      (not (and clipboard* write-text-fn))
      (do
        (set-wallet-copy-feedback! store :error "Clipboard unavailable")
        (schedule-wallet-copy-feedback-clear! store))

      :else
      (try
        (-> (.writeText clipboard* text)
            (.then (fn []
                     (set-wallet-copy-feedback! store :success success-message)
                     (schedule-wallet-copy-feedback-clear! store)))
            (.catch (fn [err]
                      (log-fn log-label err)
                      (set-wallet-copy-feedback! store :error failure-message)
                      (schedule-wallet-copy-feedback-clear! store))))
        (catch :default err
          (log-fn log-label err)
          (set-wallet-copy-feedback! store :error failure-message)
          (schedule-wallet-copy-feedback-clear! store))))))

(defn copy-wallet-address!
  [{:keys [address] :as opts}]
  (copy-text! (assoc opts
                     :text address
                     :missing-message "No address to copy"
                     :success-message "Address copied to clipboard"
                     :failure-message "Couldn't copy address"
                     :log-label "Copy wallet address failed:")))

(defn copy-share-link!
  [{:keys [url referral?] :as opts}]
  (copy-text! (assoc opts
                     :text url
                     :missing-message "No link to copy"
                     :success-message (if referral?
                                        "Referral link copied to clipboard"
                                        "Link copied to clipboard")
                     :failure-message "Couldn't copy link"
                     :log-label "Copy share link failed:")))

(defn copy-spectate-link!
  [{:keys [url] :as opts}]
  (copy-text! (assoc opts
                     :text url
                     :missing-message "No spectate link to copy"
                     :success-message "Spectate link copied to clipboard"
                     :failure-message "Couldn't copy spectate link"
                     :log-label "Copy spectate link failed:")))

(ns hyperopen.funding.application.lifecycle-polling)

(defn start-hyperunit-lifecycle-polling!
  [{:keys [store
           direction
           wallet-address
           asset-key
           protocol-address
           destination-address
           base-url
           base-urls
           request-hyperunit-operations!
           request-hyperunit-withdrawal-queue!
           set-timeout-fn
           now-ms-fn
           runtime-error-message
           on-terminal-lifecycle!
           lifecycle-poll-key-fn
           install-lifecycle-poll-token!
           clear-lifecycle-poll-token!
           lifecycle-poll-token-active?
           modal-active-for-lifecycle?
           normalize-hyperunit-lifecycle
           select-operation
           operation->lifecycle
           awaiting-lifecycle
           lifecycle-next-delay-ms
           hyperunit-lifecycle-terminal?
           fetch-hyperunit-withdrawal-queue!
           non-blank-text
           default-poll-delay-ms]}]
  (let [request-ops! (when (fn? request-hyperunit-operations!)
                       request-hyperunit-operations!)
        request-queue! (when (fn? request-hyperunit-withdrawal-queue!)
                         request-hyperunit-withdrawal-queue!)
        terminal-callback! (when (fn? on-terminal-lifecycle!)
                             on-terminal-lifecycle!)
        timeout! (or set-timeout-fn
                     (fn [f delay-ms]
                       (js/setTimeout f delay-ms)))
        now-ms!* (or now-ms-fn
                     (fn [] (js/Date.now)))]
    (when (and request-ops!
               (contains? #{:deposit :withdraw} direction)
               (seq (non-blank-text wallet-address))
               (keyword? asset-key))
      (let [poll-key (lifecycle-poll-key-fn store direction asset-key)
            token (str (random-uuid))
            should-continue? (fn []
                               (and (lifecycle-poll-token-active? poll-key token)
                                    (modal-active-for-lifecycle? store direction asset-key protocol-address)))
            update-lifecycle! (fn [lifecycle]
                                (when (should-continue?)
                                  (swap! store assoc-in
                                         [:funding-ui :modal :hyperunit-lifecycle]
                                         (normalize-hyperunit-lifecycle lifecycle))))
            refresh-withdraw-queue! (fn []
                                      (when (and (= direction :withdraw)
                                                 request-queue!
                                                 (should-continue?))
                                        (fetch-hyperunit-withdrawal-queue!
                                         {:store store
                                          :base-url base-url
                                          :base-urls base-urls
                                          :request-hyperunit-withdrawal-queue! request-queue!
                                          :now-ms-fn now-ms!*
                                          :runtime-error-message runtime-error-message
                                          :expected-asset-key asset-key
                                          :transition-loading? false})))
            schedule-next! (fn [delay-ms poll-fn]
                             (when (should-continue?)
                               (timeout! poll-fn delay-ms)))
            notify-terminal! (fn [lifecycle]
                               (when terminal-callback!
                                 (try
                                   (terminal-callback! lifecycle)
                                   (catch :default _ nil))))]
        (install-lifecycle-poll-token! poll-key token)
        (letfn [(poll! []
                  (if-not (should-continue?)
                    (clear-lifecycle-poll-token! poll-key token)
                    (-> (request-ops! {:base-url base-url
                                       :base-urls base-urls
                                       :address wallet-address})
                        (.then (fn [response]
                                 (when (should-continue?)
                                   (let [operations (:operations (or response {}))
                                         now-ms (now-ms!*)
                                         operation (select-operation operations
                                                                    {:asset-key asset-key
                                                                     :protocol-address protocol-address
                                                                     :source-address (when (= direction :withdraw)
                                                                                       wallet-address)
                                                                     :destination-address (if (= direction :withdraw)
                                                                                            destination-address
                                                                                            wallet-address)})
                                         lifecycle (if operation
                                                     (operation->lifecycle operation direction asset-key now-ms)
                                                     (awaiting-lifecycle direction asset-key now-ms))]
                                     (update-lifecycle! lifecycle)
                                     (refresh-withdraw-queue!)
                                     (if (hyperunit-lifecycle-terminal? lifecycle)
                                       (do
                                         (clear-lifecycle-poll-token! poll-key token)
                                         (notify-terminal! lifecycle))
                                       (schedule-next! (lifecycle-next-delay-ms now-ms lifecycle) poll!))))))
                        (.catch (fn [err]
                                  (when (should-continue?)
                                    (let [now-ms (now-ms!*)
                                          message (or (non-blank-text (some-> err .-message))
                                                      "Unable to refresh lifecycle status right now.")
                                          previous (get-in @store [:funding-ui :modal :hyperunit-lifecycle])]
                                      (update-lifecycle!
                                       (assoc (merge (awaiting-lifecycle direction asset-key now-ms)
                                                     (if (map? previous) previous {}))
                                               :direction direction
                                               :asset-key asset-key
                                               :last-updated-ms now-ms
                                               :error message))
                                      (refresh-withdraw-queue!)
                                      (schedule-next! default-poll-delay-ms poll!))))))))]
          (poll!))))))

(defn start-hyperunit-deposit-lifecycle-polling!
  [opts]
  (start-hyperunit-lifecycle-polling!
   (assoc opts :direction :deposit)))

(defn start-hyperunit-withdraw-lifecycle-polling!
  [opts]
  (start-hyperunit-lifecycle-polling!
   (assoc opts :direction :withdraw)))

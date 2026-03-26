(ns hyperopen.funding.application.hyperunit-query)

(defn- response-items
  [operations-response key]
  (if (sequential? (key operations-response))
    (key operations-response)
    []))

(defn- matching-hyperunit-operation?
  [{:keys [canonical-token
           same-chain-token?]}
   source-token
   asset-token
   destination-chain-token
   destination-address-token
   op]
  (and (= asset-token
          (canonical-token (:asset op)))
       (same-chain-token? source-token
                          (:source-chain op))
       (same-chain-token? destination-chain-token
                          (:destination-chain op))
       (or (not (seq destination-address-token))
           (= destination-address-token
              (canonical-token (:destination-address op))))
       (seq (canonical-token (:protocol-address op)))))

(defn- latest-operation-address
  [{:keys [op-sort-ms-fn
           non-blank-text]} matching-ops]
  (some->> matching-ops
           (sort-by op-sort-ms-fn)
           last
           :protocol-address
           non-blank-text))

(defn- find-address-entry
  [{:keys [canonical-token]} addresses expected-address]
  (let [expected-address-token (canonical-token expected-address)]
    (when (seq expected-address-token)
      (some (fn [entry]
              (when (= expected-address-token
                       (canonical-token (:address entry)))
                entry))
            addresses))))

(defn- hyperliquid-address-entries
  [{:keys [same-chain-token?
           canonical-token]} addresses destination-chain-token]
  (->> addresses
       (filter (fn [entry]
                 (and (same-chain-token? destination-chain-token
                                         (:destination-chain entry))
                      (seq (canonical-token (:address entry))))))
       vec))

(defn- direct-address-entry
  [{:keys [same-chain-token?]} source-token addresses]
  (some (fn [entry]
          (when (same-chain-token? source-token
                                   (or (:source-coin-type entry)
                                       (:source-chain entry)))
            entry))
        addresses))

(defn- source-format-address-entry
  [{:keys [canonical-chain-token
           same-chain-token?
           protocol-address-matches-source-chain?
           known-source-chain-tokens]}
   source-token
   addresses]
  (some (fn [entry]
          (let [entry-source (or (:source-coin-type entry)
                                 (:source-chain entry))
                entry-source-token (canonical-chain-token entry-source)
                entry-source-conflicts? (and (seq entry-source-token)
                                             (contains? known-source-chain-tokens
                                                        entry-source-token)
                                             (not (same-chain-token? source-token
                                                                     entry-source-token)))
                address* (:address entry)]
            (when (and (not entry-source-conflicts?)
                       (protocol-address-matches-source-chain?
                        source-token
                        address*))
              entry)))
        addresses))

(defn select-existing-hyperunit-deposit-address
  [{:keys [canonical-chain-token
           canonical-token
           same-chain-token?
           op-sort-ms-fn
           non-blank-text
           protocol-address-matches-source-chain?
           known-source-chain-tokens]}
   operations-response
   source-chain
   asset
   destination-address]
  (let [source-token (canonical-chain-token source-chain)
        asset-token (canonical-token asset)
        destination-chain-token (canonical-chain-token "hyperliquid")
        destination-address-token (canonical-token destination-address)
        operations (response-items operations-response :operations)
        addresses (response-items operations-response :addresses)
        matching-ops (->> operations
                          (filter (fn [op]
                                    (matching-hyperunit-operation?
                                     {:canonical-token canonical-token
                                      :same-chain-token? same-chain-token?}
                                     source-token
                                     asset-token
                                     destination-chain-token
                                     destination-address-token
                                     op)))
                          vec)
        op-address (latest-operation-address {:op-sort-ms-fn op-sort-ms-fn
                                              :non-blank-text non-blank-text}
                                             matching-ops)
        address-entry-by-op (find-address-entry {:canonical-token canonical-token}
                                                addresses
                                                op-address)
        hyperliquid-addresses (hyperliquid-address-entries {:same-chain-token? same-chain-token?
                                                            :canonical-token canonical-token}
                                                           addresses
                                                           destination-chain-token)
        direct-address-entry (direct-address-entry {:same-chain-token? same-chain-token?}
                                                   source-token
                                                   hyperliquid-addresses)
        source-format-address-entry (source-format-address-entry {:canonical-chain-token canonical-chain-token
                                                                  :same-chain-token? same-chain-token?
                                                                  :protocol-address-matches-source-chain?
                                                                  protocol-address-matches-source-chain?
                                                                  :known-source-chain-tokens known-source-chain-tokens}
                                                                 source-token
                                                                 hyperliquid-addresses)
        chosen-entry (or address-entry-by-op
                         direct-address-entry
                         source-format-address-entry)
        chosen-address (or op-address
                          (some-> (:address chosen-entry) non-blank-text))]
    (when (seq chosen-address)
      {:address chosen-address
       :signatures (:signatures chosen-entry)})))

(defn request-existing-hyperunit-deposit-address!
  [{:keys [request-hyperunit-operations!
           select-existing-hyperunit-deposit-address]}
   base-url
   base-urls
   destination-address
   source-chain
   asset]
  (-> (request-hyperunit-operations! {:base-url base-url
                                      :base-urls base-urls
                                      :address destination-address})
      (.then (fn [operations-response]
               (select-existing-hyperunit-deposit-address operations-response
                                                          source-chain
                                                          asset
                                                          destination-address)))
      (.catch (fn [_err]
                nil))))

(defn prefetch-selected-hyperunit-deposit-address!
  [{:keys [funding-modal-view-model-fn
           normalize-asset-key
           non-blank-text
           canonical-chain-token
           normalize-address
           resolve-hyperunit-base-urls
           request-existing-hyperunit-deposit-address!]}
   store]
  (let [state @store
        modal (get-in state [:funding-ui :modal])
        view-model (funding-modal-view-model-fn state)
        selected-asset (:deposit-selected-asset view-model)
        selected-asset-key (normalize-asset-key (:key selected-asset))
        selected-source-chain (some-> (:hyperunit-source-chain selected-asset)
                                      non-blank-text
                                      canonical-chain-token)
        generated-address (non-blank-text (:deposit-generated-address modal))
        generated-asset-key (normalize-asset-key (:deposit-generated-asset-key modal))
        wallet-address (normalize-address (get-in state [:wallet :address]))
        should-prefetch? (and (= :deposit (:mode modal))
                              (= :amount-entry (:deposit-step modal))
                              (= :hyperunit-address (:flow-kind selected-asset))
                              (keyword? selected-asset-key)
                              (seq selected-source-chain)
                              (seq wallet-address)
                              (not (and (= selected-asset-key generated-asset-key)
                                        (seq generated-address))))]
    (when should-prefetch?
      (let [base-urls (resolve-hyperunit-base-urls store)
            base-url (first base-urls)
            asset-token (name selected-asset-key)]
        (-> (request-existing-hyperunit-deposit-address! base-url
                                                         base-urls
                                                         wallet-address
                                                         selected-source-chain
                                                         asset-token)
            (.then (fn [existing-address]
                     (when (map? existing-address)
                       (swap! store
                              (fn [state*]
                                (let [modal* (get-in state* [:funding-ui :modal])
                                      active-asset-key (normalize-asset-key (:deposit-selected-asset-key modal*))
                                      active-generated-key (normalize-asset-key (:deposit-generated-asset-key modal*))
                                      active-generated-address (non-blank-text (:deposit-generated-address modal*))
                                      still-active? (and (= :deposit (:mode modal*))
                                                         (= :amount-entry (:deposit-step modal*))
                                                         (= selected-asset-key active-asset-key))
                                      already-populated? (and (= selected-asset-key active-generated-key)
                                                              (seq active-generated-address))]
                                  (if (and still-active?
                                           (not already-populated?))
                                    (-> state*
                                        (assoc-in [:funding-ui :modal :error] nil)
                                        (assoc-in [:funding-ui :modal :deposit-generated-address] (:address existing-address))
                                        (assoc-in [:funding-ui :modal :deposit-generated-signatures] (:signatures existing-address))
                                        (assoc-in [:funding-ui :modal :deposit-generated-asset-key] selected-asset-key))
                                    state*)))))
                     existing-address))
            (.catch (fn [_err]
                      nil)))))))

(defn fetch-hyperunit-withdrawal-queue!
  [{:keys [modal-active-for-withdraw-queue?
           normalize-hyperunit-withdrawal-queue
           resolve-hyperunit-base-urls
           non-blank-text
           fallback-runtime-error-message]}
   {:keys [store
           base-url
           base-urls
           request-hyperunit-withdrawal-queue!
           now-ms-fn
           runtime-error-message
           expected-asset-key
           transition-loading?]
    :or {transition-loading? true}}]
  (let [request-queue! (when (fn? request-hyperunit-withdrawal-queue!)
                         request-hyperunit-withdrawal-queue!)
        now-ms!* (or now-ms-fn
                     (fn [] (js/Date.now)))
        runtime-error-message* (or runtime-error-message
                                   fallback-runtime-error-message)
        resolved-base-urls (or (seq base-urls)
                               (resolve-hyperunit-base-urls store))
        base-url* (or (non-blank-text base-url)
                      (first resolved-base-urls))]
    (when (and request-queue!
               (modal-active-for-withdraw-queue? store expected-asset-key))
      (let [requested-at (now-ms!*)]
        (when transition-loading?
          (swap! store update-in
                 [:funding-ui :modal :hyperunit-withdrawal-queue]
                 (fn [current]
                   (-> (normalize-hyperunit-withdrawal-queue current)
                       (assoc :status :loading
                              :requested-at-ms requested-at
                              :error nil)))))
        (-> (request-queue! {:base-url base-url*
                             :base-urls resolved-base-urls})
            (.then (fn [resp]
                     (when (modal-active-for-withdraw-queue? store expected-asset-key)
                       (let [timestamp (now-ms!*)
                             by-chain (if (map? (:by-chain resp))
                                        (:by-chain resp)
                                        {})
                             error-text (non-blank-text (:error resp))]
                         (swap! store update-in
                                [:funding-ui :modal :hyperunit-withdrawal-queue]
                                (fn [current]
                                  (let [prev (normalize-hyperunit-withdrawal-queue current)]
                                    (if (seq error-text)
                                      (assoc prev
                                             :status :error
                                             :by-chain by-chain
                                             :updated-at-ms timestamp
                                             :error error-text)
                                      (assoc prev
                                             :status :ready
                                             :by-chain by-chain
                                             :updated-at-ms timestamp
                                             :error nil)))))))
                     resp))
            (.catch (fn [err]
                      (when (modal-active-for-withdraw-queue? store expected-asset-key)
                        (let [timestamp (now-ms!*)
                              message (or (non-blank-text (runtime-error-message* err))
                                          "Unable to load HyperUnit withdrawal queue.")]
                          (swap! store update-in
                                 [:funding-ui :modal :hyperunit-withdrawal-queue]
                                 (fn [current]
                                   (-> (normalize-hyperunit-withdrawal-queue current)
                                       (assoc :status :error
                                              :updated-at-ms timestamp
                                              :error message)))))))))))))

(defn api-fetch-hyperunit-fee-estimate!
  [{:keys [modal-active-for-fee-estimate?
           normalize-hyperunit-fee-estimate
           resolve-hyperunit-base-urls
           prefetch-selected-hyperunit-deposit-address!
           non-blank-text
           fallback-runtime-error-message]}
   {:keys [store
           request-hyperunit-estimate-fees!
           now-ms-fn
           runtime-error-message]}]
  (let [request-estimate! (when (fn? request-hyperunit-estimate-fees!)
                            request-hyperunit-estimate-fees!)
        now-ms* (or now-ms-fn (fn [] (js/Date.now)))
        runtime-error-message* (or runtime-error-message
                                   fallback-runtime-error-message)]
    (when (and request-estimate!
               (modal-active-for-fee-estimate? store))
      (let [base-urls (resolve-hyperunit-base-urls store)
            base-url (first base-urls)
            now-ms (now-ms*)]
        (swap! store update-in
               [:funding-ui :modal :hyperunit-fee-estimate]
               (fn [current]
                 (-> (normalize-hyperunit-fee-estimate current)
                     (assoc :status :loading
                            :requested-at-ms now-ms
                            :error nil))))
        (prefetch-selected-hyperunit-deposit-address! store)
        (-> (request-estimate! {:base-url base-url
                                :base-urls base-urls})
            (.then (fn [resp]
                     (when (modal-active-for-fee-estimate? store)
                       (let [timestamp (now-ms*)
                             by-chain (if (map? (:by-chain resp))
                                        (:by-chain resp)
                                        {})
                             error-text (non-blank-text (:error resp))]
                         (swap! store update-in
                                [:funding-ui :modal :hyperunit-fee-estimate]
                                (fn [current]
                                  (let [prev (normalize-hyperunit-fee-estimate current)]
                                    (if (seq error-text)
                                      (assoc prev
                                             :status :error
                                             :by-chain by-chain
                                             :updated-at-ms timestamp
                                             :error error-text)
                                      (assoc prev
                                             :status :ready
                                             :by-chain by-chain
                                             :updated-at-ms timestamp
                                             :error nil)))))))
                     resp))
            (.catch (fn [err]
                      (when (modal-active-for-fee-estimate? store)
                        (let [timestamp (now-ms*)
                              message (or (non-blank-text (runtime-error-message* err))
                                          "Unable to load HyperUnit fee estimates.")]
                          (swap! store update-in
                                 [:funding-ui :modal :hyperunit-fee-estimate]
                                 (fn [current]
                                   (-> (normalize-hyperunit-fee-estimate current)
                                       (assoc :status :error
                                              :updated-at-ms timestamp
                                              :error message)))))))))))))

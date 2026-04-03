(ns hyperopen.startup.collaborators
  (:require [clojure.string :as str]
            [nexus.registry :as nxr]
            [hyperopen.account.context :as account-context]
            [hyperopen.api.default :as api-default]
            [hyperopen.api.endpoints.account :as account-endpoints]
            [hyperopen.api.promise-effects :as promise-effects]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.account.history.effects :as account-history-effects]
            [hyperopen.runtime.api-effects :as runtime-api-effects]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.wallet.address-watcher :as address-watcher]
            [hyperopen.websocket.active-asset-ctx :as active-ctx]
            [hyperopen.websocket.candles :as candles]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.websocket.orderbook :as orderbook]
            [hyperopen.websocket.trades :as trades]
            [hyperopen.websocket.user :as user-ws]
            [hyperopen.websocket.webdata2 :as webdata2]))

(defn- resolve-api-op
  [api-instance key fallback]
  (or (get api-instance key)
      fallback))

(defn- normalize-address
  [address]
  (account-context/normalize-address address))

(defn- requested-address-current?
  [store requested-address]
  (let [requested-address* (normalize-address requested-address)
        active-address* (normalize-address (account-context/effective-account-address @store))]
    (and requested-address*
         active-address*
         (= requested-address* active-address*))))

(defn- apply-success-and-return-when-current
  [store requested-address apply-fn & leading-args]
  (fn [payload]
    (when (requested-address-current? store requested-address)
      (apply swap! store apply-fn (concat leading-args [payload])))
    payload))

(defn- apply-error-and-reject-when-current
  [store requested-address apply-error-fn & leading-args]
  (fn [err]
    (when (requested-address-current? store requested-address)
      (apply swap! store apply-error-fn (concat leading-args [err])))
    (promise-effects/reject-error err)))

(defn- resolve-api-ops
  [api-instance]
  {:get-request-stats (resolve-api-op api-instance :get-request-stats api-default/get-request-stats)
   :request-frontend-open-orders! (resolve-api-op api-instance :request-frontend-open-orders! api-default/request-frontend-open-orders!)
   :request-clearinghouse-state! (resolve-api-op api-instance :request-clearinghouse-state! api-default/request-clearinghouse-state!)
   :request-user-fills! (resolve-api-op api-instance :request-user-fills! api-default/request-user-fills!)
   :request-historical-orders! (resolve-api-op api-instance :request-historical-orders! api-default/request-historical-orders!)
   :request-spot-clearinghouse-state! (resolve-api-op api-instance :request-spot-clearinghouse-state! api-default/request-spot-clearinghouse-state!)
   :request-user-abstraction! (resolve-api-op api-instance :request-user-abstraction! api-default/request-user-abstraction!)
   :request-portfolio! (resolve-api-op api-instance :request-portfolio! api-default/request-portfolio!)
   :request-user-fees! (resolve-api-op api-instance :request-user-fees! api-default/request-user-fees!)
   :request-user-non-funding-ledger-updates! (resolve-api-op api-instance :request-user-non-funding-ledger-updates! api-default/request-user-non-funding-ledger-updates!)
   :ensure-perp-dexs-data! (resolve-api-op api-instance :ensure-perp-dexs-data! api-default/ensure-perp-dexs-data!)
   :request-asset-contexts! (resolve-api-op api-instance :request-asset-contexts! api-default/request-asset-contexts!)
   :request-asset-selector-markets! (resolve-api-op api-instance :request-asset-selector-markets! api-default/request-asset-selector-markets!)})

(defn- fetch-frontend-open-orders!
  ([api-ops store address]
   (fetch-frontend-open-orders! api-ops store address {}))
  ([{:keys [request-frontend-open-orders!]} store address opts]
   (let [requested-address (normalize-address address)
         opts* (or opts {})
         dex (:dex opts*)]
     (-> (request-frontend-open-orders! address opts*)
         (.then (apply-success-and-return-when-current
                 store
                 requested-address
                 api-projections/apply-open-orders-success
                 dex))
         (.catch (apply-error-and-reject-when-current
                  store
                  requested-address
                  api-projections/apply-open-orders-error)))))
  ([api-ops store address dex opts]
   (fetch-frontend-open-orders! api-ops store address
                                (cond-> (or opts {})
                                  (and dex (not= dex "")) (assoc :dex dex)))))

(defn- fetch-clearinghouse-state!
  ([api-ops store address dex]
   (fetch-clearinghouse-state! api-ops store address dex {}))
  ([{:keys [request-clearinghouse-state!]} store address dex opts]
   (let [requested-address (normalize-address address)]
     (-> (request-clearinghouse-state! address dex opts)
         (.then (apply-success-and-return-when-current
                 store
                 requested-address
                 api-projections/apply-perp-dex-clearinghouse-success
                 dex))
         (.catch (apply-error-and-reject-when-current
                  store
                  requested-address
                  api-projections/apply-perp-dex-clearinghouse-error))))))

(defn- fetch-user-fills!
  ([api-ops store address]
   (fetch-user-fills! api-ops store address {}))
  ([{:keys [request-user-fills!]} store address opts]
   (let [requested-address (normalize-address address)]
     (-> (request-user-fills! address opts)
         (.then (apply-success-and-return-when-current
                 store
                 requested-address
                 api-projections/apply-user-fills-success))
         (.catch (apply-error-and-reject-when-current
                  store
                  requested-address
                  api-projections/apply-user-fills-error))))))

(defn- fetch-spot-clearinghouse-state!
  ([api-ops store address]
   (fetch-spot-clearinghouse-state! api-ops store address {}))
  ([{:keys [request-spot-clearinghouse-state!]} store address opts]
   (if-not address
     (js/Promise.resolve nil)
     (let [requested-address (normalize-address address)]
       (do
         (swap! store api-projections/begin-spot-balances-load)
         (-> (request-spot-clearinghouse-state! address opts)
             (.then (apply-success-and-return-when-current
                     store
                     requested-address
                     api-projections/apply-spot-balances-success))
             (.catch (apply-error-and-reject-when-current
                      store
                      requested-address
                      api-projections/apply-spot-balances-error))))))))

(defn- fetch-user-abstraction!
  ([api-ops store address]
   (fetch-user-abstraction! api-ops store address {}))
  ([{:keys [request-user-abstraction!]} store address opts]
   (if-not address
     (js/Promise.resolve {:mode :classic
                          :abstraction-raw nil})
     (let [requested-address (some-> address str str/lower-case)]
       (-> (request-user-abstraction! address opts)
           (.then (fn [payload]
                    (let [snapshot {:mode (account-endpoints/normalize-user-abstraction-mode payload)
                                    :abstraction-raw payload}]
                      (swap! store api-projections/apply-user-abstraction-snapshot requested-address snapshot)
                      snapshot)))
           (.catch promise-effects/reject-error))))))

(defn- optional-number
  [value]
  (cond
    (number? value)
    (when (js/isFinite value)
      value)

    (string? value)
    (let [trimmed (str/trim value)]
      (when (seq trimmed)
        (let [parsed (js/Number trimmed)]
          (when (js/isFinite parsed)
            parsed))))

    :else
    nil))

(defn- history-row-time-ms
  [row]
  (cond
    (and (sequential? row)
         (seq row))
    (optional-number (first row))

    (map? row)
    (or (optional-number (:time row))
        (optional-number (:timestamp row))
        (optional-number (:time-ms row))
        (optional-number (:timeMs row))
        (optional-number (:ts row))
        (optional-number (:t row)))

    :else
    nil))

(defn- portfolio-summary-time-window
  [summary-by-key]
  (let [times (->> (vals (or summary-by-key {}))
                   (mapcat (fn [entry]
                             (or (:accountValueHistory entry) [])))
                   (keep history-row-time-ms)
                   vec)]
    (when (seq times)
      {:start-time-ms (apply min times)
       :end-time-ms (apply max times)})))

(defn- normalize-ledger-updates
  [rows]
  (if (sequential? rows)
    (vec rows)
    []))

(defn- error-text
  [err]
  (or (some-> err .-message)
      (some-> err str)))

(defn- fetch-portfolio!
  ([api-ops store address]
   (fetch-portfolio! api-ops store address {}))
  ([{:keys [request-portfolio!
            request-user-non-funding-ledger-updates!]} store address opts]
   (if-not address
     (js/Promise.resolve {})
     (let [requested-address (normalize-address address)]
       (if-not requested-address
         (js/Promise.resolve {})
         (do
           (swap! store api-projections/begin-portfolio-load)
           (-> (request-portfolio! address opts)
               (.then (fn [summary-by-key]
                        (let [summary* (or summary-by-key {})
                              {:keys [start-time-ms end-time-ms]} (portfolio-summary-time-window summary*)]
                          (if-not (requested-address-current? store requested-address)
                            summary*
                            (do
                              (swap! store api-projections/apply-portfolio-success summary*)
                              (if (and (fn? request-user-non-funding-ledger-updates!)
                                       (number? start-time-ms)
                                       (number? end-time-ms)
                                       (<= start-time-ms end-time-ms))
                                (-> (request-user-non-funding-ledger-updates! address
                                                                               start-time-ms
                                                                               end-time-ms
                                                                               (dissoc (or opts {}) :dedupe-key))
                                    (.then (fn [rows]
                                             (when (requested-address-current? store requested-address)
                                               (swap! store assoc-in [:portfolio :ledger-updates] (normalize-ledger-updates rows))
                                               (swap! store assoc-in [:portfolio :ledger-loaded-at-ms] (.now js/Date))
                                               (swap! store assoc-in [:portfolio :ledger-error] nil))
                                             summary*))
                                    (.catch (fn [err]
                                              ;; Portfolio summary should remain available even if ledger fetch fails.
                                              (when (requested-address-current? store requested-address)
                                                (swap! store assoc-in [:portfolio :ledger-error] (error-text err)))
                                              (js/Promise.resolve summary*))))
                                (do
                                  (when (requested-address-current? store requested-address)
                                    (swap! store assoc-in [:portfolio :ledger-updates] [])
                                    (swap! store assoc-in [:portfolio :ledger-loaded-at-ms] nil)
                                    (swap! store assoc-in [:portfolio :ledger-error] nil))
                                  (js/Promise.resolve summary*))))))))
               (.catch (apply-error-and-reject-when-current
                        store
                        requested-address
                        api-projections/apply-portfolio-error)))))))))

(defn- fetch-user-fees!
  ([api-ops store address]
   (fetch-user-fees! api-ops store address {}))
  ([{:keys [request-user-fees!]} store address opts]
   (if-not address
     (js/Promise.resolve nil)
     (let [requested-address (normalize-address address)]
       (if-not requested-address
         (js/Promise.resolve nil)
         (do
           (swap! store api-projections/begin-user-fees-load)
           (-> (request-user-fees! address opts)
               (.then (apply-success-and-return-when-current
                       store
                       requested-address
                       api-projections/apply-user-fees-success))
               (.catch (apply-error-and-reject-when-current
                        store
                        requested-address
                        api-projections/apply-user-fees-error)))))))))

(defn- ensure-perp-dexs!
  ([api-ops store]
   (ensure-perp-dexs! api-ops store {}))
  ([{:keys [ensure-perp-dexs-data!]} store opts]
   (-> (ensure-perp-dexs-data! store opts)
       (.then (promise-effects/apply-success-and-return
               store
               api-projections/apply-perp-dexs-success))
       (.catch (promise-effects/apply-error-and-reject
                store
                api-projections/apply-perp-dexs-error)))))

(defn- fetch-asset-contexts!
  ([api-ops store]
   (fetch-asset-contexts! api-ops store {}))
  ([{:keys [request-asset-contexts!]} store opts]
   (-> (request-asset-contexts! opts)
       (.then (promise-effects/apply-success-and-return
               store
               api-projections/apply-asset-contexts-success))
       (.catch (promise-effects/apply-error-and-reject
                store
                api-projections/apply-asset-contexts-error)))))

(defn- fetch-asset-selector-markets!
  ([api-ops store]
   (fetch-asset-selector-markets! api-ops store {:phase :full}))
  ([{:keys [request-asset-selector-markets!]} store opts]
   (runtime-api-effects/fetch-asset-selector-markets!
   {:store store
     :opts opts
     :request-asset-selector-markets-fn request-asset-selector-markets!
     :begin-asset-selector-load api-projections/begin-asset-selector-load
     :apply-spot-meta-success api-projections/apply-spot-meta-success
     :apply-asset-selector-success api-projections/apply-asset-selector-success
     :apply-asset-selector-error api-projections/apply-asset-selector-error})))

(defn startup-base-deps
  [overrides]
  (let [api-ops (resolve-api-ops (:api overrides))]
    (merge
     {:log-fn telemetry/log!
      :get-request-stats (:get-request-stats api-ops)
      :fetch-frontend-open-orders! (fn
                                     ([store address]
                                      (fetch-frontend-open-orders! api-ops store address))
                                     ([store address opts]
                                      (fetch-frontend-open-orders! api-ops store address opts))
                                     ([store address dex opts]
                                      (fetch-frontend-open-orders! api-ops store address dex opts)))
      :fetch-clearinghouse-state! (fn
                                    ([store address dex]
                                     (fetch-clearinghouse-state! api-ops store address dex))
                                    ([store address dex opts]
                                     (fetch-clearinghouse-state! api-ops store address dex opts)))
      :fetch-user-fills! (fn
                           ([store address]
                            (fetch-user-fills! api-ops store address))
                           ([store address opts]
                            (fetch-user-fills! api-ops store address opts)))
      :fetch-spot-clearinghouse-state! (fn
                                         ([store address]
                                          (fetch-spot-clearinghouse-state! api-ops store address))
                                         ([store address opts]
                                          (fetch-spot-clearinghouse-state! api-ops store address opts)))
      :fetch-user-abstraction! (fn
                                 ([store address]
                                  (fetch-user-abstraction! api-ops store address))
                                 ([store address opts]
                                  (fetch-user-abstraction! api-ops store address opts)))
      :fetch-portfolio! (fn
                          ([store address]
                           (fetch-portfolio! api-ops store address))
                          ([store address opts]
                           (fetch-portfolio! api-ops store address opts)))
      :fetch-user-fees! (fn
                          ([store address]
                           (fetch-user-fees! api-ops store address))
                          ([store address opts]
                           (fetch-user-fees! api-ops store address opts)))
      :fetch-historical-orders! (fn
                                  ([store request-id]
                                   (account-history-effects/fetch-historical-orders!
                                    {:store store
                                     :request-id request-id
                                     :request-historical-orders! (:request-historical-orders! api-ops)}))
                                  ([store request-id opts]
                                   (account-history-effects/fetch-historical-orders!
                                    {:store store
                                     :request-id request-id
                                     :request-historical-orders! (:request-historical-orders! api-ops)
                                     :opts opts})))
      :fetch-and-merge-funding-history! account-history-effects/fetch-and-merge-funding-history!
      :ensure-perp-dexs! (fn
                           ([store]
                            (ensure-perp-dexs! api-ops store))
                           ([store opts]
                            (ensure-perp-dexs! api-ops store opts)))
      :fetch-asset-contexts! (fn
                               ([store]
                                (fetch-asset-contexts! api-ops store))
                               ([store opts]
                                (fetch-asset-contexts! api-ops store opts)))
      :fetch-asset-selector-markets! (fn
                                       ([store]
                                        (fetch-asset-selector-markets! api-ops store))
                                       ([store opts]
                                        (fetch-asset-selector-markets! api-ops store opts)))
      :ws-url runtime-state/websocket-url
      :init-connection! ws-client/init-connection!
      :init-active-ctx! active-ctx/init!
      :init-candles! candles/init!
      :init-orderbook! orderbook/init!
      :init-trades! trades/init!
      :init-user-ws! user-ws/init!
      :init-webdata2! webdata2/init!
      :dispatch! nxr/dispatch
      :init-with-webdata2! address-watcher/init-with-webdata2!
      :add-handler! address-watcher/add-handler!
      :remove-handler! address-watcher/remove-handler!
      :stop-watching! address-watcher/stop-watching!
      :sync-current-address! address-watcher/sync-current-address!
      :create-user-handler user-ws/create-user-handler
      :subscribe-user! user-ws/subscribe-user!
      :unsubscribe-user! user-ws/unsubscribe-user!
      :sync-perp-dex-clearinghouse-subscriptions! user-ws/sync-perp-dex-clearinghouse-subscriptions!
      :subscribe-webdata2! webdata2/subscribe-webdata2!
      :unsubscribe-webdata2! webdata2/unsubscribe-webdata2!}
     (dissoc overrides :api))))

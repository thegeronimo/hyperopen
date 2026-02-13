(ns hyperopen.startup.collaborators
  (:require [clojure.string :as str]
            [nexus.registry :as nxr]
            [hyperopen.api.default :as api-default]
            [hyperopen.api.endpoints.account :as account-endpoints]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.account.history.effects :as account-history-effects]
            [hyperopen.runtime.api-effects :as runtime-api-effects]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.wallet.address-watcher :as address-watcher]
            [hyperopen.websocket.active-asset-ctx :as active-ctx]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.websocket.orderbook :as orderbook]
            [hyperopen.websocket.trades :as trades]
            [hyperopen.websocket.user :as user-ws]
            [hyperopen.websocket.webdata2 :as webdata2]))

(defn- resolve-api-op
  [api-instance key fallback]
  (or (get api-instance key)
      fallback))

(defn- resolve-api-ops
  [api-instance]
  {:get-request-stats (resolve-api-op api-instance :get-request-stats api-default/get-request-stats)
   :request-frontend-open-orders! (resolve-api-op api-instance :request-frontend-open-orders! api-default/request-frontend-open-orders!)
   :request-clearinghouse-state! (resolve-api-op api-instance :request-clearinghouse-state! api-default/request-clearinghouse-state!)
   :request-user-fills! (resolve-api-op api-instance :request-user-fills! api-default/request-user-fills!)
   :request-spot-clearinghouse-state! (resolve-api-op api-instance :request-spot-clearinghouse-state! api-default/request-spot-clearinghouse-state!)
   :request-user-abstraction! (resolve-api-op api-instance :request-user-abstraction! api-default/request-user-abstraction!)
   :ensure-perp-dexs-data! (resolve-api-op api-instance :ensure-perp-dexs-data! api-default/ensure-perp-dexs-data!)
   :request-asset-contexts! (resolve-api-op api-instance :request-asset-contexts! api-default/request-asset-contexts!)
   :request-asset-selector-markets! (resolve-api-op api-instance :request-asset-selector-markets! api-default/request-asset-selector-markets!)})

(defn- fetch-frontend-open-orders!
  ([api-ops store address]
   (fetch-frontend-open-orders! api-ops store address {}))
  ([{:keys [request-frontend-open-orders!]} store address opts]
   (let [opts* (or opts {})
         dex (:dex opts*)]
     (-> (request-frontend-open-orders! address opts*)
       (.then (fn [rows]
                (swap! store api-projections/apply-open-orders-success dex rows)
                rows))
       (.catch (fn [err]
                 (swap! store api-projections/apply-open-orders-error err)
                 (js/Promise.reject err))))))
  ([api-ops store address dex opts]
   (fetch-frontend-open-orders! api-ops store address
                                (cond-> (or opts {})
                                  (and dex (not= dex "")) (assoc :dex dex)))))

(defn- fetch-clearinghouse-state!
  ([api-ops store address dex]
   (fetch-clearinghouse-state! api-ops store address dex {}))
  ([{:keys [request-clearinghouse-state!]} store address dex opts]
   (-> (request-clearinghouse-state! address dex opts)
       (.then (fn [data]
                (swap! store api-projections/apply-perp-dex-clearinghouse-success dex data)
                data))
       (.catch (fn [err]
                 (swap! store api-projections/apply-perp-dex-clearinghouse-error err)
                 (js/Promise.reject err))))))

(defn- fetch-user-fills!
  ([api-ops store address]
   (fetch-user-fills! api-ops store address {}))
  ([{:keys [request-user-fills!]} store address opts]
   (-> (request-user-fills! address opts)
       (.then (fn [rows]
                (swap! store api-projections/apply-user-fills-success rows)
                rows))
       (.catch (fn [err]
                 (swap! store api-projections/apply-user-fills-error err)
                 (js/Promise.reject err))))))

(defn- fetch-spot-clearinghouse-state!
  ([api-ops store address]
   (fetch-spot-clearinghouse-state! api-ops store address {}))
  ([{:keys [request-spot-clearinghouse-state!]} store address opts]
   (if-not address
     (js/Promise.resolve nil)
     (do
       (swap! store api-projections/begin-spot-balances-load)
       (-> (request-spot-clearinghouse-state! address opts)
           (.then (fn [data]
                    (swap! store api-projections/apply-spot-balances-success data)
                    data))
           (.catch (fn [err]
                     (swap! store api-projections/apply-spot-balances-error err)
                     (js/Promise.reject err))))))))

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
           (.catch (fn [err]
                     (js/Promise.reject err))))))))

(defn- ensure-perp-dexs!
  ([api-ops store]
   (ensure-perp-dexs! api-ops store {}))
  ([{:keys [ensure-perp-dexs-data!]} store opts]
   (-> (ensure-perp-dexs-data! store opts)
       (.then (fn [dexs]
                (swap! store api-projections/apply-perp-dexs-success dexs)
                dexs))
       (.catch (fn [err]
                 (swap! store api-projections/apply-perp-dexs-error err)
                 (js/Promise.reject err))))))

(defn- fetch-asset-contexts!
  ([api-ops store]
   (fetch-asset-contexts! api-ops store {}))
  ([{:keys [request-asset-contexts!]} store opts]
   (-> (request-asset-contexts! opts)
       (.then (fn [rows]
                (swap! store api-projections/apply-asset-contexts-success rows)
                rows))
       (.catch (fn [err]
                 (swap! store api-projections/apply-asset-contexts-error err)
                 (js/Promise.reject err))))))

(defn- fetch-asset-selector-markets!
  ([api-ops store]
   (fetch-asset-selector-markets! api-ops store {:phase :full}))
  ([{:keys [request-asset-selector-markets!]} store opts]
   (runtime-api-effects/fetch-asset-selector-markets!
    {:store store
     :opts opts
     :request-asset-selector-markets-fn request-asset-selector-markets!
     :begin-asset-selector-load api-projections/begin-asset-selector-load
     :apply-asset-selector-success api-projections/apply-asset-selector-success
     :apply-asset-selector-error api-projections/apply-asset-selector-error})))

(defn startup-base-deps
  [overrides]
  (let [api-ops (resolve-api-ops (:api overrides))]
    (merge
     {:log-fn println
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
      :init-orderbook! orderbook/init!
      :init-trades! trades/init!
      :init-user-ws! user-ws/init!
      :init-webdata2! webdata2/init!
      :dispatch! nxr/dispatch
      :init-with-webdata2! address-watcher/init-with-webdata2!
      :add-handler! address-watcher/add-handler!
      :sync-current-address! address-watcher/sync-current-address!
      :create-user-handler user-ws/create-user-handler
      :subscribe-user! user-ws/subscribe-user!
      :unsubscribe-user! user-ws/unsubscribe-user!
      :subscribe-webdata2! webdata2/subscribe-webdata2!
      :unsubscribe-webdata2! webdata2/unsubscribe-webdata2!}
     (dissoc overrides :api))))

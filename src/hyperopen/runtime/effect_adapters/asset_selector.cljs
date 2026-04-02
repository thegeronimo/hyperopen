(ns hyperopen.runtime.effect-adapters.asset-selector
  (:require [hyperopen.asset-selector.active-market-cache :as active-market-cache]
            [hyperopen.asset-selector.actions :as asset-actions]
            [hyperopen.asset-selector.icon-status-runtime :as icon-status-runtime]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.asset-selector.markets-cache :as markets-cache]
            [hyperopen.asset-selector.query :as asset-selector-query]
            [hyperopen.websocket.active-asset-ctx :as active-ctx]))

(defn flush-queued-asset-icon-statuses!
  [{:keys [runtime store save-many!]}]
  (icon-status-runtime/flush-queued-asset-icon-statuses!
   {:store store
    :runtime runtime
    :apply-asset-icon-status-updates-fn asset-actions/apply-asset-icon-status-updates
    :save-many! save-many!}))

(defn queue-asset-icon-status!
  [{:keys [runtime store payload schedule-animation-frame! flush-queued-asset-icon-statuses!]}]
  (icon-status-runtime/queue-asset-icon-status!
   {:store store
    :runtime runtime
    :payload payload
    :schedule-animation-frame! schedule-animation-frame!
    :flush-queued-asset-icon-statuses! flush-queued-asset-icon-statuses!}))

(defn persist-asset-selector-markets-cache!
  ([markets]
   (persist-asset-selector-markets-cache! markets {}))
  ([markets state]
   (markets-cache/persist-asset-selector-markets-cache! markets state)))

(defn- load-asset-selector-markets-cache []
  (markets-cache/load-asset-selector-markets-cache))

(defn restore-asset-selector-markets-cache!
  [{:keys [store load-cache-fn resolve-market-by-coin-fn]
    :or {load-cache-fn load-asset-selector-markets-cache
         resolve-market-by-coin-fn markets/resolve-or-infer-market-by-coin}}]
  (markets-cache/restore-asset-selector-markets-cache!
   store
   {:load-cache-fn load-cache-fn
    :resolve-market-by-coin-fn resolve-market-by-coin-fn}))

(defn- active-market-display-normalize-deps []
  {:normalize-display-text markets-cache/normalize-display-text
   :normalize-market-type markets-cache/normalize-market-type
   :parse-max-leverage markets-cache/parse-max-leverage
   :parse-market-index markets-cache/parse-market-index})

(defn persist-active-market-display!
  [market]
  (active-market-cache/persist-active-market-display!
   market
   (active-market-display-normalize-deps)))

(defn load-active-market-display
  [active-asset]
  (active-market-cache/load-active-market-display
   active-asset
   (active-market-display-normalize-deps)))

(def ^:private asset-selector-active-ctx-owner
  :asset-selector)

(defn sync-asset-selector-active-ctx-subscriptions
  [_ store]
  (let [state @store
        desired-coins (if (true? (get-in state [:asset-selector :live-market-subscriptions-paused?]))
                        (active-ctx/get-subscribed-coins-by-owner asset-selector-active-ctx-owner)
                        (asset-selector-query/selector-visible-market-coins state))]
    (active-ctx/sync-owner-subscriptions! asset-selector-active-ctx-owner desired-coins)))

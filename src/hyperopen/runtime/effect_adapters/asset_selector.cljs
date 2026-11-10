(ns hyperopen.runtime.effect-adapters.asset-selector
  (:require [hyperopen.api.default :as api]
            [hyperopen.asset-selector.active-market-cache :as active-market-cache]
            [hyperopen.asset-selector.actions :as asset-actions]
            [hyperopen.asset-selector.icon-status-runtime :as icon-status-runtime]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.asset-selector.markets-cache :as markets-cache]
            [hyperopen.asset-selector.query :as asset-selector-query]
            [hyperopen.websocket.active-asset-ctx :as active-ctx]
            [hyperopen.websocket.outcome-bbo :as outcome-bbo]))

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

(def ^:private fallback-active-asset
  "BTC")

(defn correct-unresolved-active-asset!
  "After a full catalog load, re-select a real market when the active asset is
   not a venue coin: a bare spot-token name becomes its pair (\"KHYPE\" ->
   \"@336\"), and a coin no market matches (a stale ?market= or stored asset)
   falls back to BTC. Such coins reach the store when a spot balance row is
   clicked, or a route or stored asset restored, before the full catalog
   loaded; left alone, their l2Book subscription makes Hyperliquid close the
   websocket on every reconnect. Returns the corrected coin, or nil."
  [store dispatch!]
  (let [state @store
        market-by-key (get-in state [:asset-selector :market-by-key])
        coin (:active-asset state)]
    (when (= :full (get-in state [:asset-selector :phase]))
      (when-let [coin* (or (:coin (markets/bare-spot-token-market market-by-key coin))
                           (when (markets/unknown-market-coin? market-by-key coin)
                             fallback-active-asset))]
        (dispatch! store nil [[:actions/select-asset coin*]])
        coin*))))

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
  "Live market subscriptions of what the selector and the outcome ticket
   show: `activeAssetCtx` per visible selector row, and `bbo` (top of book) per
   outcome contract on screen — the open event's answers and the visible list
   rows, capped (`query/outcome-top-of-book-coins`). Rows that leave the
   screen, or a closed selector, drop their subscriptions here. A new `bbo`
   coin with no top of book yet is seeded once from a REST l2Book snapshot
   (the venue only pushes `bbo` on change), and quotes landing re-run this
   sync (the answers a row shows follow their quotes)."
  [_ store]
  (let [state @store
        paused? (true? (get-in state [:asset-selector :live-market-subscriptions-paused?]))
        desired-coins (if paused?
                        (active-ctx/get-subscribed-coins-by-owner asset-selector-active-ctx-owner)
                        (asset-selector-query/selector-visible-market-coins state))
        desired-bbo-coins (if paused?
                            (outcome-bbo/get-subscribed-coins)
                            (asset-selector-query/outcome-top-of-book-coins state))]
    (active-ctx/sync-owner-subscriptions! asset-selector-active-ctx-owner desired-coins)
    (outcome-bbo/register-hooks! {:request-snapshot! api/request-l2-book-snapshot!
                                  :resync! #(sync-asset-selector-active-ctx-subscriptions nil store)})
    (outcome-bbo/sync-subscriptions! desired-bbo-coins state)))

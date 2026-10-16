(ns hyperopen.runtime.action-adapters.websocket
  (:require [hyperopen.asset-selector.annotations :as annotations]
            [hyperopen.asset-selector.markets :as markets]))

(defn- matching-active-market
  [state coin]
  (let [market (:active-market state)]
    (when (and (map? market)
               (markets/market-matches-coin? market coin))
      market)))

(defn- active-market-side-coins
  [state coin]
  (let [market-by-key (get-in state [:asset-selector :market-by-key] {})
        market (or (matching-active-market state coin)
                   (markets/resolve-or-infer-market-by-coin market-by-key coin))
        market* (markets/market-with-selected-outcome-coin market coin)
        side-coins (when (= :outcome (:market-type market))
                     (markets/outcome-subscription-coins market*))]
    (vec (or (seq side-coins)
             (when (string? coin)
               [coin])))))

(defn init-websockets
  [_state]
  [[:effects/init-websocket]])

(defn subscribe-to-asset
  [state coin]
  (let [side-coins (active-market-side-coins state coin)]
    (into [[:effects/subscribe-active-asset coin]]
          (concat
           (mapcat (fn [side-coin]
                     [[:effects/subscribe-orderbook side-coin]
                      [:effects/subscribe-trades side-coin]])
                   side-coins)
           [[:effects/sync-active-asset-funding-predictability coin]]
           (when (annotations/annotatable-coin? coin)
             [[:effects/sync-perp-annotations coin]])))))

(defn subscribe-to-webdata2
  [_state address]
  [[:effects/subscribe-webdata2 address]])

(defn refresh-asset-markets
  [_state]
  [[:effects/fetch-asset-selector-markets]])

(defn load-user-data
  [_state address]
  [[:effects/api-load-user-data address]])

(defn reconnect-websocket-action
  [_state]
  [[:effects/reconnect-websocket]])

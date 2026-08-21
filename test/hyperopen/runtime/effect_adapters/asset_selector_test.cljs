(ns hyperopen.runtime.effect-adapters.asset-selector-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.projections.market :as market-projections]
            [hyperopen.asset-selector.query :as asset-selector-query]
            [hyperopen.runtime.api-effects :as api-effects]
            [hyperopen.runtime.effect-adapters.asset-selector :as asset-adapters]
            [hyperopen.runtime.effect-adapters :as effect-adapters]
            [hyperopen.websocket.active-asset-ctx :as active-ctx]
            [hyperopen.websocket.client :as ws-client]))

(deftest asset-selector-markets-effect-deps-carry-spot-meta-projection-test
  ;; Regression: this dependency map and the one in
  ;; `hyperopen.startup.collaborators` are hand-written and drifted. This one
  ;; omitted `:apply-spot-meta-success`, and the write in
  ;; `fetch-asset-selector-markets!` is guarded on the key being present, so
  ;; every demand-path catalog load fetched spotMeta and dropped it. `[:spot
  ;; :meta]` stayed nil for whole sessions, which is what made every non-USDC
  ;; token in the Send Tokens dropdown read "unavailable".
  (is (identical? market-projections/apply-spot-meta-success
                  (:apply-spot-meta-success
                   (effect-adapters/asset-selector-markets-effect-deps (atom {}) nil)))))

(deftest asset-selector-markets-effect-deps-write-spot-meta-to-app-state-test
  ;; Drives the REAL adapter dependency map end to end with only the network
  ;; call stubbed, so a missing projection key fails here instead of passing a
  ;; test that injects its own.
  (async done
    (let [store (atom {})
          spot-meta {:tokens [{:name "HYPE" :index 150 :tokenId "0xbaadf00d"}]}
          deps (assoc (effect-adapters/asset-selector-markets-effect-deps store {:phase :full})
                      :request-asset-selector-markets-fn
                      (fn [_store _opts]
                        (js/Promise.resolve
                         {:phase :full
                          :spot-meta spot-meta
                          :market-state {:markets [] :loaded-at-ms 1}}))
                      :after-asset-selector-success! nil)]
      (-> (api-effects/fetch-asset-selector-markets! deps)
          (.then (fn [_]
                   (is (= spot-meta (get-in @store [:spot :meta])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest facade-asset-selector-adapters-delegate-to-asset-selector-module-test
  (is (identical? asset-adapters/persist-asset-selector-markets-cache!
                  effect-adapters/persist-asset-selector-markets-cache!))
  (is (identical? asset-adapters/persist-active-market-display!
                  effect-adapters/persist-active-market-display!))
  (is (identical? asset-adapters/load-active-market-display
                  effect-adapters/load-active-market-display))
  (is (identical? asset-adapters/sync-asset-selector-active-ctx-subscriptions
                  effect-adapters/sync-asset-selector-active-ctx-subscriptions)))

(deftest queue-asset-icon-status-wrapper-injects-facade-schedule-animation-frame-seam-test
  (let [captured (atom nil)
        store (atom {})]
    (with-redefs [effect-adapters/schedule-animation-frame! (fn [_] :raf-id)
                  asset-adapters/queue-asset-icon-status!
                  (fn [opts]
                    (reset! captured opts))]
      (effect-adapters/queue-asset-icon-status nil store {:market-key "perp:BTC"
                                                           :icon-status :loaded})
      (is (= :raf-id ((:schedule-animation-frame! @captured) (fn [] nil))))
      (is (fn? (:flush-queued-asset-icon-statuses! @captured))))))

(deftest sync-asset-selector-active-ctx-subscriptions-diffs-owner-scoped-coins-test
  (let [store (atom {:asset-selector {}})
        sent-messages (atom [])
        original-state @active-ctx/active-asset-ctx-state]
    (reset! active-ctx/active-asset-ctx-state {:subscriptions #{"BTC" "SOL"}
                                                :owners-by-coin {"BTC" #{:asset-selector}
                                                                 "SOL" #{:asset-selector}}
                                                :coins-by-owner {:asset-selector #{"BTC" "SOL"}}
                                                :contexts {}})
    (try
      (with-redefs [asset-selector-query/selector-visible-market-coins (fn [_]
                                                                          #{"BTC" "ETH"})
                    ws-client/send-message! (fn [message]
                                              (swap! sent-messages conj message)
                                              true)]
        (effect-adapters/sync-asset-selector-active-ctx-subscriptions nil store))
      (is (= [{:method "subscribe"
               :subscription {:type "activeAssetCtx"
                              :coin "ETH"}}
              {:method "unsubscribe"
               :subscription {:type "activeAssetCtx"
                              :coin "SOL"}}]
             @sent-messages))
      (is (= #{"BTC" "ETH"}
             (active-ctx/get-subscribed-coins-by-owner :asset-selector)))
      (finally
        (reset! active-ctx/active-asset-ctx-state original-state)))))

(deftest sync-asset-selector-active-ctx-subscriptions-preserves-owned-selector-coins-while-live-updates-are-paused-test
  (let [store (atom {:asset-selector {:live-market-subscriptions-paused? true}})
        sent-messages (atom [])
        original-state @active-ctx/active-asset-ctx-state]
    (reset! active-ctx/active-asset-ctx-state {:subscriptions #{"BTC" "SOL"}
                                                :owners-by-coin {"BTC" #{:asset-selector}
                                                                 "SOL" #{:asset-selector}}
                                                :coins-by-owner {:asset-selector #{"BTC" "SOL"}}
                                                :contexts {}})
    (try
      (with-redefs [asset-selector-query/selector-visible-market-coins (fn [_]
                                                                          #{"BTC" "ETH"})
                    ws-client/send-message! (fn [message]
                                              (swap! sent-messages conj message)
                                              true)]
        (effect-adapters/sync-asset-selector-active-ctx-subscriptions nil store))
      (is (empty? @sent-messages))
      (is (= #{"BTC" "SOL"}
             (active-ctx/get-subscribed-coins-by-owner :asset-selector)))
      (finally
        (reset! active-ctx/active-asset-ctx-state original-state)))))

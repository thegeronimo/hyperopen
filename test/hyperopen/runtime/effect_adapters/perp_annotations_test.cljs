(ns hyperopen.runtime.effect-adapters.perp-annotations-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.asset-selector.actions :as asset-actions]
            [hyperopen.runtime.action-adapters.websocket :as ws-actions]
            [hyperopen.runtime.effect-adapters :as effect-adapters]
            [hyperopen.runtime.effect-adapters.perp-annotations :as perp-annotation-adapters]
            [hyperopen.test-support.async :as async-support]))

(defn- recording-deps
  [calls {:keys [concise detail dexs fail-concise?]}]
  {:request-perp-annotations!
   (fn [opts]
     (swap! calls conj [:concise opts])
     (if fail-concise?
       (js/Promise.reject (js/Error. "rate limited"))
       (js/Promise.resolve concise)))
   :request-perp-annotation!
   (fn [coin opts]
     (swap! calls conj [:detail coin opts])
     (js/Promise.resolve detail))
   :request-perp-dexs!
   (fn [opts]
     (swap! calls conj [:perp-dexs opts])
     (js/Promise.resolve dexs))})

(deftest facade-exposes-sync-perp-annotations-test
  (is (identical? perp-annotation-adapters/sync-perp-annotations
                  effect-adapters/sync-perp-annotations)))

(deftest sync-loads-bulk-names-and-the-coin-detail-once-test
  (async done
    (let [calls (atom [])
          store (atom {})
          deps (recording-deps calls {:concise [["xyz:SPCX" {:category "stocks" :displayName "SPCX"}]]
                                      :detail {:category "stocks"
                                               :description "SPCX references 1 share of Space Exploration Technologies Corp. Class A common stock."}
                                      :dexs [{:name "xyz" :fullName "XYZ" :deployerFeeScale "1.0"}]})]
      (-> (perp-annotation-adapters/sync-perp-annotations-with-deps! deps store "xyz:SPCX")
          (.then (fn [_]
                   (is (= :loaded (get-in @store [:perp-annotations :concise-status])))
                   (is (= :stocks (get-in @store [:perp-annotations :by-coin "xyz:SPCX" :category])))
                   (is (= :loaded (get-in @store [:perp-annotations :detail-by-coin "xyz:SPCX" :status])))
                   (is (= {"xyz" "XYZ"} (:perp-dex-full-name-by-name @store)))
                   (perp-annotation-adapters/sync-perp-annotations-with-deps! deps store "xyz:SPCX")))
          (.then (fn [_]
                   (is (= [:concise :perp-dexs :detail]
                          (mapv first @calls))
                       "a second sync for the same coin requests nothing")
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest sync-for-a-main-dex-coin-loads-only-the-bulk-payload-test
  (async done
    (let [calls (atom [])
          store (atom {})]
      (-> (perp-annotation-adapters/sync-perp-annotations-with-deps!
           (recording-deps calls {:concise []}) store "BTC")
          (.then (fn [_]
                   (is (= [:concise] (mapv first @calls)))
                   (is (= :loaded (get-in @store [:perp-annotations :concise-status])))
                   (is (= {} (get-in @store [:perp-annotations :detail-by-coin])))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest sync-records-bulk-failure-for-retry-test
  (async done
    (let [calls (atom [])
          store (atom {:perp-dex-full-name-by-name {"xyz" "XYZ"}})]
      (-> (perp-annotation-adapters/sync-perp-annotations-with-deps!
           (recording-deps calls {:fail-concise? true :detail nil}) store "cash:HOOD")
          (.then (fn [_]
                   (is (= :error (get-in @store [:perp-annotations :concise-status])))
                   (is (= {:status :loaded :annotation nil}
                          (get-in @store [:perp-annotations :detail-by-coin "cash:HOOD"])))
                   (is (not-any? #(= :perp-dexs (first %)) @calls)
                       "known deployer names are not refetched")
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest subscribe-to-asset-requests-annotations-for-hip3-coins-only-test
  (is (some #{[:effects/sync-perp-annotations "xyz:SPCX"]}
            (ws-actions/subscribe-to-asset {} "xyz:SPCX")))
  (is (not-any? #(= :effects/sync-perp-annotations (first %))
                (ws-actions/subscribe-to-asset {} "BTC"))))

(deftest opening-the-selector-requests-bulk-annotations-until-loaded-test
  (let [sync-effects (fn [state]
                       (filterv #(= :effects/sync-perp-annotations (first %))
                                (asset-actions/toggle-asset-dropdown state :asset-selector)))]
    (is (= [[:effects/sync-perp-annotations "BTC"]]
           (sync-effects {:active-asset "BTC"})))
    (is (= [] (sync-effects {:active-asset "BTC"
                             :perp-annotations {:concise-status :loaded}})))
    (is (= [] (sync-effects {:active-asset "BTC"
                             :asset-selector {:visible-dropdown :asset-selector}}))
        "closing the selector requests nothing")))

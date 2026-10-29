(ns hyperopen.vol-range.infrastructure.live-ingress-test
  "Live ingress: raw WebSocket evidence applied to the store's ladders in
  memory (HO-SPEC-014 v2.1 sections 9.1, 9.2, 9.4; VR-14, VR-18, VR-19), and
  the rate-limited reconciliation hints. Synthetic frames and the simulator
  only; nothing reaches an exchange."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [hyperopen.account.context :as account-context]
            [hyperopen.config :as config]
            [hyperopen.platform :as platform]
            [hyperopen.platform.locks :as locks]
            [hyperopen.vol-range.application.dock-model :as dock-model]
            [hyperopen.vol-range.application.grid-store :as grid-store]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.infrastructure.executor :as ex]
            [hyperopen.vol-range.infrastructure.executor-place :as place]
            [hyperopen.vol-range.infrastructure.live-ingress :as ingress]
            [hyperopen.vol-range.live-fixtures :as fx]
            [hyperopen.vol-range.simulator :as sim]))

(def ^:private dispatched (atom []))

(defn- install-fake-dispatch! []
  (reset! dispatched [])
  (ingress/install! {:dispatch! (fn [_store _event actions] (swap! dispatched conj actions))}))

(use-fixtures :each
  {:before (fn []
             (ingress/reset-for-tests!)
             (reset! dispatched []))
   :after (fn [] (ingress/reset-for-tests!))})

(defn- store-with [grids]
  (atom {:vol-range {:execution {:enabled? true :grids (into {} (map (juxt :grid-id identity)) grids)}}}))

(defn- store-grid [store gid] (get-in @store [:vol-range :execution :grids gid]))

(defn- with-sync-microtasks
  "Run `(f)` with queued microtasks run at once, so hint dispatches are
  observable synchronously."
  [f]
  (let [orig platform/queue-microtask!]
    (set! platform/queue-microtask! (fn [g] (g)))
    (try (f) (finally (set! platform/queue-microtask! orig)))))

(def ^:private reconcile-hint [[:actions/reconcile-open-vol-ladders]])

(defn- fail [done] (fn [e] (is false (str e (when (instance? js/Error e) (.-stack e)))) (done)))

;; ---------------------------------------------------------------------------
;; Taps
;; ---------------------------------------------------------------------------

(deftest taps-exist-only-in-a-build-that-includes-placement-test
  (doseq [[preview? built? expected] [[false false #{}] [true false #{}] [false true #{}]
                                      [true true #{:on-raw-user-fills :on-raw-open-orders}]]]
    (with-redefs [config/config (assoc config/config :vol-range {:preview-enabled? preview?
                                                                 :execution-built? built?})]
      (is (= expected (set (keys (ingress/user-stream-taps)))) (str [preview? built?]))))
  (with-redefs [config/config (assoc config/config :vol-range {:preview-enabled? true :execution-built? true})]
    (let [{:keys [on-raw-user-fills on-raw-open-orders]} (ingress/user-stream-taps)]
      (is (= ingress/observe-user-fills! on-raw-user-fills))
      (is (= ingress/observe-open-orders! on-raw-open-orders)))))

;; ---------------------------------------------------------------------------
;; Fills
;; ---------------------------------------------------------------------------

(deftest a-fill-frame-updates-the-ladder-and-the-dock-test
  (let [store (store-with [(fx/grid)])
        envelope (ingress/observe-user-fills! store fx/fills-increment fx/received-at-ms)
        g (store-grid store "grid-live")]
    (is (= :fills (:kind envelope)))
    (is (= "0:1" (:account-event-revision envelope)) "epoch 0 until monitoring exists, then the sequence")
    (is (= 1 (count (:fills g))))
    (is (= "2" (:filled (lifecycle/leg-quantities g (get-in g [:legs fx/cloid-buy-0])))))
    (is (= "2" (get-in (dock-model/grid-row @store g) [:filled :buy :qty])))
    (is (= "0" (get-in (dock-model/grid-row @store g) [:filled :sell :qty])))))

(deftest other-accounts-networks-and-coins-are-no-ops-test
  (let [store (store-with [(fx/grid)])
        before @store]
    (ingress/observe-user-fills! store (assoc-in fx/fills-increment [:data :user] "0xdef") fx/received-at-ms)
    (ingress/observe-user-fills! store {:channel "userFills"
                                        :data {:user fx/account :fills [fx/other-coin-fill fx/manual-fill]}}
                                 fx/received-at-ms)
    (ingress/observe-open-orders! store (assoc-in fx/open-orders-default [:data :user] "0xdef") fx/received-at-ms)
    (is (identical? before @store) "VR-18: no store write at all")
    (testing "a frame with no address and no live subscription is dropped"
      (is (nil? (ingress/observe-user-fills! store fx/fills-without-address fx/received-at-ms)))
      (is (identical? before @store)))))

(deftest a-repeat-frame-causes-no-store-write-test
  (let [store (store-with [(fx/grid)])
        writes (atom 0)]
    (add-watch store ::writes (fn [_ _ _ _] (swap! writes inc)))
    (ingress/observe-user-fills! store fx/fills-increment fx/received-at-ms)
    (ingress/observe-user-fills! store fx/fills-increment (+ fx/received-at-ms 100))
    (ingress/observe-user-fills! store fx/fills-snapshot (+ fx/received-at-ms 200))
    (is (= 1 @writes) "the snapshot repeating the fill writes nothing")
    (is (= 1 (count (:fills (store-grid store "grid-live")))))
    (testing "the full open-order listing of an untouched ladder writes nothing"
      (ingress/observe-open-orders! store fx/open-orders-default (+ fx/received-at-ms 300))
      (is (= 1 @writes)))))

(deftest the-250th-fill-reaches-the-ladder-test
  (let [store (store-with [(fx/grid)])]
    (ingress/observe-user-fills! store fx/fills-250 fx/received-at-ms)
    (is (= 1 (count (:fills (store-grid store "grid-live"))))
        "VR-19: the display list's 200-row truncation does not apply")))

(deftest the-fallback-address-is-the-live-subscription-test
  (let [store (store-with [(fx/grid)])]
    (with-redefs [account-context/live-user-stream-address (fn [_] "0xABC")]
      (let [envelope (ingress/observe-user-fills! store fx/fills-without-address fx/received-at-ms)]
        (is (= :subscription (:address-source envelope)))
        (is (= 1 (count (:fills (store-grid store "grid-live")))))))))

;; ---------------------------------------------------------------------------
;; Fill before the placement's HTTP response (VR-14, simulator)
;; ---------------------------------------------------------------------------

(defn- top-buy [grid]
  (last (sort-by #(js/parseFloat (:price %)) (filter #(= :buy (:side %)) (vals (:legs grid))))))

(deftest a-fill-before-the-response-is-counted-once-after-reconciliation-test
  (async done
    (let [s (sim/new-sim)
          store (store-with [])]
      (locks/reset-in-tab-state!)
      (-> (place/place! (sim/deps s) {:frozen-plan (sim/frozen-plan s) :frozen-scope sim/scope})
          (.then
           (fn [{:keys [grid]}]
             (let [gid (:grid-id grid)
                   leg (top-buy grid)
                   _ (sim/fill! s (:cloid leg) (:original-base leg))
                   frame (sim/ws-fills-frame s)]
               (testing "the ladder is not in the store yet"
                 (let [before @store]
                   (ingress/observe-user-fills! store frame (sim/now s))
                   (is (identical? before @store))))
               (testing "the leg is still pending (sent, no response)"
                 (swap! store grid-store/merge-grid-into-state
                        (assoc-in grid [:legs (:cloid leg) :placement-state] :pending))
                 (ingress/observe-user-fills! store frame (sim/now s))
                 (is (empty? (:fills (store-grid store gid))))
                 (swap! store update-in [:vol-range :execution :grids] dissoc gid))
               ;; The response lands, then the REST reconciliation reads the fill.
               (swap! store grid-store/merge-grid-into-state grid)
               (-> (ex/reconcile! (sim/deps s) grid)
                   (.then (fn [{reconciled :grid}]
                            (swap! store grid-store/merge-grid-into-state reconciled)
                            (ingress/observe-user-fills! store frame (sim/now s))
                            (ingress/observe-user-fills! store (sim/ws-fills-frame s {:snapshot? true}) (sim/now s))
                            (let [g (store-grid store gid)
                                  l (get-in g [:legs (:cloid leg)])]
                              (is (= 1 (count (:fills g))) "REST and two stream frames: one fill")
                              (is (= (:original-base leg) (:filled (lifecycle/leg-quantities g l)))))))))))
          (.then (fn [_] (done)))
          (.catch (fail done))))))

(deftest a-reconcile-finishing-after-a-stream-fill-keeps-it-test
  (async done
    (let [s (sim/new-sim)]
      (locks/reset-in-tab-state!)
      (-> (place/place! (sim/deps s) {:frozen-plan (sim/frozen-plan s) :frozen-scope sim/scope})
          (.then (fn [{:keys [grid]}] (ex/reconcile! (sim/deps s) grid)))
          (.then
           (fn [{grid :grid}]
             (let [gid (:grid-id grid)
                   leg (top-buy grid)
                   store (store-with [grid])
                   ;; A fill only the stream has delivered; the venue's REST
                   ;; window in this run never reports it.
                   row {:coin sim/coin :px (:price leg) :sz (:original-base leg) :side "B"
                        :time (sim/now s) :oid (js/parseInt (:oid leg)) :cloid (:cloid leg) :tid 777001
                        :fee "0" :feeToken "USDC" :builderFee "0" :crossed false :hash "0x0"}]
               (ingress/observe-user-fills! store {:channel "userFills" :data {:user "0xabc" :fills [row]}}
                                            (sim/now s))
               (is (= 1 (count (:fills (store-grid store gid)))))
               (-> (ex/reconcile! (sim/deps s) grid)
                   (.then (fn [{reconciled :grid}]
                            (is (empty? (:fills reconciled)) "the reconcile's copy never saw the fill")
                            (swap! store grid-store/merge-grid-into-state reconciled)
                            (let [g (store-grid store gid)]
                              (is (= 1 (count (:fills g))))
                              (is (= :terminal (get-in g [:legs (:cloid leg) :order-state]))))))))))
          (.then (fn [_] (done)))
          (.catch (fail done))))))

;; ---------------------------------------------------------------------------
;; Reconciliation hints (spec 9.4)
;; ---------------------------------------------------------------------------

(deftest evidence-on-a-checking-ladder-hints-once-per-interval-test
  (with-sync-microtasks
    (fn []
      (install-fake-dispatch!)
      (let [store (store-with [(fx/grid :unknown)])
            t fx/received-at-ms
            frame (fn [row] (fx/open-orders-frame [row]))]
        (is (= :checking (lifecycle/status (store-grid store "grid-live"))))
        (ingress/observe-open-orders! store (frame fx/buy-0-row) t)
        (is (= [reconcile-hint] @dispatched))
        (testing "more Checking evidence within 5 s asks for nothing"
          (ingress/observe-open-orders! store (frame fx/sell-0-row) (+ t 4999))
          (is (lifecycle/live-leg? (get-in (store-grid store "grid-live") [:legs fx/cloid-sell-0])))
          (is (= [reconcile-hint] @dispatched)))
        (testing "after the interval it asks again"
          (ingress/observe-open-orders! store (frame (assoc fx/buy-1-row :cloid fx/cloid-buy-1)) (+ t 5000))
          (is (= [reconcile-hint reconcile-hint] @dispatched)))
        (testing "a ladder that is no longer Checking asks for nothing"
          (is (not= :checking (lifecycle/status (store-grid store "grid-live"))))
          (ingress/observe-user-fills! store fx/fills-increment (+ t 20000))
          (is (= 1 (count (:fills (store-grid store "grid-live")))))
          (is (= 2 (count @dispatched))))))))

(deftest absence-hints-need-a-coin-this-connection-listed-test
  (with-sync-microtasks
    (fn []
      (install-fake-dispatch!)
      (let [store (store-with [(fx/grid)])
            t (+ fx/placed-at-ms 10000)
            empty-frame (fx/open-orders-frame [])]
        (testing "the first frame lists no Vol Range coin: absence is not acted on"
          (ingress/observe-open-orders! store empty-frame t)
          (is (= [] @dispatched)))
        (testing "a builder-dex frame never arms the guard"
          (ingress/observe-open-orders! store (fx/open-orders-frame "xyz" [fx/buy-0-row]) (+ t 100))
          (ingress/observe-open-orders! store empty-frame (+ t 200))
          (is (= [] @dispatched)))
        (testing "once the coin was listed, a frame missing resting legs hints"
          (ingress/observe-open-orders! store fx/open-orders-default (+ t 300))
          (is (= [] @dispatched) "every leg present: nothing to check")
          (ingress/observe-open-orders! store empty-frame (+ t 400))
          (is (= [reconcile-hint] @dispatched))
          (is (every? lifecycle/live-leg? (vals (:legs (store-grid store "grid-live"))))
              "absence never changes a leg"))
        (testing "a socket close forgets the listed coins"
          (swap! store assoc-in [:websocket :health :transport :last-close :at-ms] (+ t 500))
          (ingress/observe-open-orders! store empty-frame (+ t 20000))
          (is (= 1 (count @dispatched))))))))

(deftest no-hint-without-an-installed-dispatch-test
  (with-sync-microtasks
    (fn []
      (let [store (store-with [(fx/grid :unknown)])]
        (ingress/observe-open-orders! store (fx/open-orders-frame [fx/buy-0-row]) fx/received-at-ms)
        (is (lifecycle/live-leg? (get-in (store-grid store "grid-live") [:legs fx/cloid-buy-0])))
        (is (= [] @dispatched))))))

;; ---------------------------------------------------------------------------
;; Live monitoring marker (spec 9.3)
;; ---------------------------------------------------------------------------

(deftest only-a-snapshot-for-the-trading-account-marks-monitoring-test
  (testing "no trading account, or an increment: monitoring is left alone"
    (let [store (store-with [(fx/grid)])]
      (ingress/observe-user-fills! store fx/fills-snapshot fx/received-at-ms)
      (is (nil? (get-in @store [:vol-range :execution :monitoring])))))
  (with-redefs [account-context/active-trading-account-address (fn [_] "0xABC")]
    (let [store (store-with [(fx/grid)])]
      (ingress/observe-user-fills! store fx/fills-increment fx/received-at-ms)
      (is (nil? (get-in @store [:vol-range :execution :monitoring])) "an increment is not a snapshot")
      (ingress/observe-user-fills! store (assoc-in fx/fills-snapshot [:data :user] "0xdef") fx/received-at-ms)
      (is (nil? (get-in @store [:vol-range :execution :monitoring])) "another account's snapshot")
      (let [envelope (ingress/observe-user-fills! store fx/fills-snapshot (+ fx/received-at-ms 10))
            m (get-in @store [:vol-range :execution :monitoring])]
        (is (= [fx/account (+ fx/received-at-ms 10)] ((juxt :account-address :fills-snapshot-at-ms) m))
            "the trading account's snapshot is the monitoring marker")
        (is (= (str (:epoch m) ":4") (:account-event-revision envelope)))
        (is (= 1 (count (:fills (store-grid store "grid-live")))) "and its rows still reach the ladder")))))

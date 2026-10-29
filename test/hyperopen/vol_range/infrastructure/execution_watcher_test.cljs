(ns hyperopen.vol-range.infrastructure.execution-watcher-test
  "Live monitoring wiring in the execution watcher (HO-SPEC-014 v2.1 sections
  9.3, 9.4): a health change to usable streams plus a `userFills` snapshot
  dispatches one resync, hidden pages dispatch nothing, a tick gap means the
  page was suspended, and a reinstall replaces the visibility listener. The
  platform timers, microtasks and document are fakes; nothing reaches an
  exchange."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [hyperopen.config :as config]
            [hyperopen.platform :as platform]
            [hyperopen.vol-range.application.monitoring :as monitoring]
            [hyperopen.vol-range.infrastructure.execution-watcher :as watcher]
            [hyperopen.vol-range.infrastructure.live-ingress :as ingress]))

(def account "0x1234567890abcdef1234567890abcdef12345678")
(def t0 1761660000000)

(def ^:private clock (atom t0))
(def ^:private microtasks (atom []))
(def ^:private interval-fn (atom nil))
(def ^:private dispatched (atom []))

(def ^:private originals (atom nil))

(use-fixtures :each
  {:before (fn []
             (reset! originals {:queue platform/queue-microtask! :set-interval platform/set-interval!
                                :clear-interval platform/clear-interval! :config config/config})
             (reset! clock t0)
             (reset! microtasks [])
             (reset! interval-fn nil)
             (reset! dispatched [])
             (ingress/reset-for-tests!)
             (set! platform/queue-microtask! (fn [f] (swap! microtasks conj f)))
             (set! platform/set-interval! (fn [f _] (reset! interval-fn f) :interval))
             (set! platform/clear-interval! (fn [_] nil))
             (set! config/config (assoc config/config :vol-range {:preview-enabled? true :execution-built? true})))
   :after (fn []
            (let [{:keys [queue set-interval clear-interval] :as o} @originals]
              (set! platform/queue-microtask! queue)
              (set! platform/set-interval! set-interval)
              (set! platform/clear-interval! clear-interval)
              (set! config/config (:config o)))
            (ingress/reset-for-tests!))})

(defn- flush! []
  (loop [n 0]
    (when-let [fs (seq @microtasks)]
      (reset! microtasks [])
      (doseq [f fs] (f))
      (when (< n 20) (recur (inc n))))))

(defn- fake-document [hidden?]
  (let [listeners (atom [])
        doc #js {:hidden hidden?}]
    (set! (.-addEventListener doc) (fn [t f] (swap! listeners conj [t f])))
    (set! (.-removeEventListener doc) (fn [t f] (swap! listeners #(vec (remove #{[t f]} %)))))
    {:doc doc :listeners listeners}))

(defn- fire-visibility! [{:keys [listeners]}]
  (doseq [[t f] @listeners :when (= "visibilitychange" t)] (f)))

(defn- stream [topic]
  {:topic topic :status :n-a :subscribed? true :first-payload-at-ms t0 :descriptor {:type topic :user account}})

(def usable-health
  {:transport {:state :connected :freshness :live}
   :streams (into {} (map (fn [topic] [[topic nil account nil nil] (stream topic)])) ["userFills" "openOrders"])})

(def down-health
  {:transport {:state :reconnecting :freshness :offline
               :last-close {:code 1006 :reason "" :was-clean? false :at-ms (+ t0 100)}}
   :streams {}})

(defn- new-store [] (atom {:wallet {:address account} :websocket {:health {}}}))

(defn- install! [store doc]
  (watcher/install-vol-range-execution-watcher!
   {:store store :document doc :now-ms-fn #(deref clock)
    :dispatch! (fn [_ _ actions] (swap! dispatched into actions))}))

(defn- mon [store] (get-in @store [:vol-range :execution :monitoring]))

(def ^:private resync [:actions/resync-vol-ladders])
(def ^:private reconcile [:actions/reconcile-open-vol-ladders])

(defn- snapshot-frame [] {:channel "userFills" :data {:isSnapshot true :user account :fills []}})

(defn- catch-up!
  "Usable streams, then the account's `userFills` snapshot (through the
  ingress tap, as the account runtime delivers it)."
  [store]
  (swap! store assoc-in [:websocket :health] usable-health)
  (flush!)
  (ingress/observe-user-fills! store (snapshot-frame) @clock)
  (flush!))

(defn- go-live!
  "Finish the due resync as the adapter would."
  [store]
  (let [epoch (:epoch (mon store))]
    (swap! store update-in [:vol-range :execution :monitoring]
           #(-> %
                (monitoring/on-resync-started {:epoch epoch :now-ms @clock})
                (monitoring/on-resync-finished {:epoch epoch :account account :ok? true :now-ms @clock})))
    (flush!)))

(deftest install-starts-stale-and-keeps-epochs-increasing-test
  (let [store (new-store)
        {:keys [doc]} (fake-document false)]
    (install! store doc)
    (flush!)
    (is (= [account :stale 1 t0] ((juxt :account-address :status :epoch :last-tick-ms) (mon store))))
    (is (= [] @dispatched) "nothing is due before the streams and the snapshot")
    (install! store doc)
    (is (= 2 (:epoch (mon store))) "a reinstall never reuses an epoch")))

(deftest usable-streams-plus-a-snapshot-dispatch-one-resync-test
  (let [store (new-store)
        {:keys [doc]} (fake-document false)]
    (install! store doc)
    (flush!)
    (swap! store assoc-in [:websocket :health] usable-health)
    (flush!)
    (testing "usable streams alone wait for the snapshot"
      (is (= [:stale [:awaiting-streams]] ((juxt :status :reasons) (mon store))))
      (is (= [] @dispatched)))
    (let [envelope (ingress/observe-user-fills! store (snapshot-frame) (+ t0 50))]
      (is (= (+ t0 50) (:fills-snapshot-at-ms (mon store))))
      (is (= "1:1" (:account-event-revision envelope)) "the envelope revision carries the monitoring epoch"))
    (is (= [:resyncing [:catching-up]] ((juxt :status :reasons) (mon store))))
    (flush!)
    (is (= [resync] @dispatched) "exactly one resync")
    (testing "live after the resync; another health change dispatches nothing"
      (go-live! store)
      (swap! store assoc-in [:websocket :health :transport :freshness] :live)
      (swap! store update-in [:websocket :health] assoc :seen 1)
      (flush!)
      (is (= :live (:status (mon store))))
      (is (= [resync] @dispatched)))
    (testing "a disconnect goes stale; the reconnect needs a new resync"
      (swap! store assoc-in [:websocket :health] down-health)
      (flush!)
      (is (= [:stale [:transport-down] 2] ((juxt :status :reasons :epoch) (mon store))))
      (reset! clock (+ t0 1000))
      (swap! store assoc-in [:websocket :health] (assoc-in usable-health [:transport :last-close]
                                                           (get-in down-health [:transport :last-close])))
      (ingress/observe-user-fills! store (snapshot-frame) @clock)
      (flush!)
      (is (= [:resyncing 2] ((juxt :status :epoch) (mon store))))
      (is (= [resync resync] @dispatched)))))

(deftest hidden-pages-do-not-dispatch-until-visible-test
  (let [store (new-store)
        {:keys [doc] :as d} (fake-document true)]
    (install! store doc)
    (flush!)
    (catch-up! store)
    (is (monitoring/resync-due? (mon store)))
    (is (= [] @dispatched) "no resync while hidden")
    (reset! clock (+ t0 15000))
    (@interval-fn)
    (is (= (+ t0 15000) (:last-tick-ms (mon store))) "the tick still runs while hidden")
    (is (= [] @dispatched) "and neither a resync nor a reconcile is dispatched")
    (set! (.-hidden doc) false)
    (fire-visibility! d)
    (is (= [resync] @dispatched) "returning to the page ticks at once")))

(deftest a-tick-gap-triggers-a-resync-test
  (let [store (new-store)
        {:keys [doc]} (fake-document false)]
    (install! store doc)
    (catch-up! store)
    (reset! dispatched [])
    (go-live! store)
    (testing "a normal tick reconciles in the background"
      (reset! clock (+ t0 15000))
      (@interval-fn)
      (is (= [reconcile] @dispatched))
      (is (= :live (:status (mon store)))))
    (testing "a gap over 45 s means the page slept: resync, no reconcile on top"
      (reset! dispatched [])
      (let [epoch (:epoch (mon store))]
        (reset! clock (+ t0 15000 60000))
        (@interval-fn)
        (is (= [:resyncing [:suspended] (inc epoch)] ((juxt :status :reasons :epoch) (mon store))))
        (is (= [resync] @dispatched))))
    (testing "no background reconcile while the resync runs"
      (reset! dispatched [])
      (swap! store assoc-in [:vol-range :execution :busy [:resync]] true)
      (swap! store update-in [:vol-range :execution :monitoring]
             monitoring/on-resync-started {:epoch (:epoch (mon store)) :now-ms @clock})
      (flush!)
      (reset! clock (+ t0 90000))
      (@interval-fn)
      (is (= [] @dispatched)))))

(deftest reinstall-replaces-the-visibility-listener-test
  (let [store (new-store)
        a (fake-document false)
        b (fake-document false)]
    (install! store (:doc a))
    (install! store (:doc a))
    (is (= 1 (count @(:listeners a))) "one listener per document")
    (install! store (:doc b))
    (is (= 0 (count @(:listeners a))) "the previous document's listener is removed")
    (is (= 1 (count @(:listeners b))))))

(deftest an-account-change-starts-over-test
  (let [store (new-store)
        {:keys [doc]} (fake-document false)
        other "0x9999999999999999999999999999999999999999"]
    (install! store doc)
    (catch-up! store)
    (go-live! store)
    (let [epoch (:epoch (mon store))]
      (swap! store assoc-in [:wallet :address] other)
      (flush!)
      (is (= [other :stale] ((juxt :account-address :status) (mon store))))
      (is (< epoch (:epoch (mon store)))))))

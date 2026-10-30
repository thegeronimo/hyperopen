(ns hyperopen.vol-range.infrastructure.report-watcher-test
  "Report wiring (HO-SPEC-014 v2.1 sections 9.3, 9.4, 11.3): opening the tab
  loads once (coalesced) and rendering loads nothing; periodic backfill
  rides the execution watcher's existing tick (no timer of its own); stream
  fills are offered to the ledger ingest. Platform timers, microtasks and the
  document are fakes; nothing reaches an exchange."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [hyperopen.config :as config]
            [hyperopen.platform :as platform]
            [hyperopen.vol-range.contracts :as contracts]
            [hyperopen.vol-range.infrastructure.execution-watcher :as execution-watcher]
            [hyperopen.vol-range.infrastructure.ledger-ingest :as ledger-ingest]
            [hyperopen.vol-range.infrastructure.live-ingress :as ingress]
            [hyperopen.vol-range.infrastructure.report-watcher :as watcher]))

(def account "0x1234567890abcdef1234567890abcdef12345678")
(def t0 1761660000000)
(def interval (:backfill-interval-ms contracts/report-policy))

(def ^:private microtasks (atom []))
(def ^:private interval-fn (atom nil))
(def ^:private dispatched (atom []))
(def ^:private originals (atom nil))

(use-fixtures :each
  {:before (fn []
             (reset! originals {:queue platform/queue-microtask! :set-interval platform/set-interval!
                                :clear-interval platform/clear-interval! :config config/config})
             (reset! microtasks [])
             (reset! interval-fn nil)
             (reset! dispatched [])
             (ingress/reset-for-tests!)
             (set! platform/queue-microtask! (fn [f] (swap! microtasks conj f)))
             (set! platform/set-interval! (fn [f _] (reset! interval-fn f) :interval))
             (set! platform/clear-interval! (fn [_] nil))
             (set! config/config (assoc config/config :vol-range {:preview-enabled? true :execution-built? true
                                                                  :reporting-enabled? true})))
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

(def ^:private load [:actions/load-vol-range-report])
(def ^:private refresh [:actions/refresh-vol-range-report])

(def ^:private campaign
  {:campaign-id "cmp-1" :campaign-key "k" :t0-ms t0 :scope {:account-address account}})

(defn- new-store []
  (atom {:wallet {:address account} :router {:path "/trade"} :websocket {:health {}}
         :vol-range {:campaigns {"cmp-1" campaign} :campaign-closures {}}}))

(defn- dispatch! [_ _ actions] (swap! dispatched into actions))

(defn- install! [store]
  (watcher/install-vol-range-report-watcher! {:store store :dispatch! dispatch!}))

(defn- open-tab! [store]
  (swap! store assoc-in [:router :path] "/portfolio")
  (swap! store assoc-in [:portfolio-ui :account-info-tab] :vol-range))

(deftest opening-the-tab-loads-once-and-rendering-loads-nothing-test
  (let [store (new-store)]
    (install! store)
    (flush!)
    (is (= [] @dispatched) "not visible on the trade route")
    (open-tab! store)
    (swap! store assoc :unrelated 1)
    (flush!)
    (is (= [load] @dispatched) "one coalesced load")
    (testing "store changes while the loaded report is current dispatch nothing"
      (swap! store assoc-in [:vol-range :report :account-address] account)
      (swap! store assoc :unrelated 2)
      (swap! store assoc-in [:vol-range :report :ledger] {:fills {}})
      (flush!)
      (is (= [load] @dispatched)))
    (testing "another account while visible loads again"
      (swap! store assoc-in [:wallet :address] "0x9999999999999999999999999999999999999999")
      (flush!)
      (is (= [load load] @dispatched)))
    (testing "a load in flight is not repeated"
      (reset! dispatched [])
      (swap! store assoc-in [:vol-range :report :busy [:load]] true)
      (swap! store assoc :unrelated 3)
      (flush!)
      (is (= [] @dispatched)))
    (testing "leaving the tab loads nothing"
      (swap! store assoc-in [:portfolio-ui :account-info-tab] :balances)
      (swap! store update-in [:vol-range :report :busy] dissoc [:load])
      (flush!)
      (is (= [] @dispatched)))))

(deftest a-deep-link-loads-at-install-test
  (let [store (new-store)]
    (open-tab! store)
    (install! store)
    (flush!)
    (is (= [load] @dispatched))))

(deftest backfill-due-test
  (let [state @(new-store)]
    (is (true? (watcher/backfill-due? state t0)) "never backfilled")
    (is (false? (watcher/backfill-due? (assoc-in state [:vol-range :report :backfill-at-ms account] t0)
                                       (+ t0 interval -1))))
    (is (true? (watcher/backfill-due? (assoc-in state [:vol-range :report :backfill-at-ms account] t0)
                                      (+ t0 interval))))
    (is (false? (watcher/backfill-due? (assoc-in state [:vol-range :campaign-closures "cmp-1"] {}) t0))
        "no open campaign")
    (is (false? (watcher/backfill-due? (assoc-in state [:vol-range :report :busy [:backfill]] true) t0)))
    (is (false? (watcher/backfill-due? (assoc state :account-context {:spectate-mode {:active? true
                                                                                      :address account}})
                                       t0))
        "read-only")))

(defn- fake-document [hidden?]
  (let [doc #js {:hidden hidden?}]
    (set! (.-addEventListener doc) (fn [_ _] nil))
    (set! (.-removeEventListener doc) (fn [_ _] nil))
    doc))

(deftest periodic-backfill-rides-the-execution-watcher-tick-test
  (let [store (new-store)
        clock (atom t0)
        doc (fake-document false)]
    (execution-watcher/install-vol-range-execution-watcher!
     {:store store :document doc :now-ms-fn #(deref clock) :dispatch! dispatch!})
    (install! store)
    (flush!)
    (reset! dispatched [])
    (@interval-fn)
    (is (some #{refresh} @dispatched) "due on the first visible tick")
    (reset! dispatched [])
    (swap! store assoc-in [:vol-range :report :backfill-at-ms account] @clock)
    (swap! clock + 15000)
    (@interval-fn)
    (is (not-any? #{refresh} @dispatched) "not again within the backfill interval")
    (swap! clock + interval)
    (set! (.-hidden doc) true)
    (@interval-fn)
    (is (not-any? #{refresh} @dispatched) "never while hidden")
    (testing "a failing listener never breaks the tick"
      (execution-watcher/register-tick-listener! ::boom (fn [_] (throw (js/Error. "boom"))))
      (set! (.-hidden doc) false)
      (@interval-fn)
      (is (some #{refresh} @dispatched))
      (execution-watcher/unregister-tick-listener! ::boom))
    (testing "reporting off: the listener is removed"
      (set! config/config (assoc-in config/config [:vol-range :reporting-enabled?] false))
      (install! store)
      (reset! dispatched [])
      (swap! store assoc-in [:vol-range :report :backfill-at-ms account] nil)
      (@interval-fn)
      (is (not-any? #{refresh} @dispatched)))))

(def ^:private hour 3600000)
(def ^:private marks-refresh [:actions/refresh-vol-range-report-marks])

(defn- report-store
  "The report tab open with campaign cmp-1 (coin @1) loaded, backfilled at
  `now` and no hourly closes read yet."
  [now]
  (doto (new-store)
    (open-tab!)
    (swap! assoc-in [:vol-range :campaigns "cmp-1" :scope :coin] "@1")
    (swap! update-in [:vol-range :report] merge
           {:account-address account :campaigns {"cmp-1" (assoc-in campaign [:scope :coin] "@1")} :closures {}
            :backfill-at-ms {account now}})))

(deftest marks-due-test
  (let [now (+ t0 (* 3 hour))
        state @(report-store now)]
    (is (true? (watcher/marks-due? state now)) "the window needs closes no read has proved")
    (is (false? (watcher/marks-due? (assoc-in state [:vol-range :report :marks-at-ms] (- now 1000)) now))
        "at most one background read a minute")
    (is (true? (watcher/marks-due? (assoc-in state [:vol-range :report :marks-at-ms] (- now watcher/marks-retry-ms))
                                   now)))
    (is (false? (watcher/marks-due? (assoc-in state [:vol-range :report :marks "@1"]
                                              {:closes {} :covered [[(- t0 hour) now]]})
                                    now))
        "nothing missing")
    (is (false? (watcher/marks-due? (assoc-in state [:vol-range :report :busy [:marks]] true) now)))
    (is (false? (watcher/marks-due? (assoc-in state [:portfolio-ui :account-info-tab] :balances) now))
        "only while the report is shown")))

(deftest a-new-completed-hour-reads-marks-on-the-tick-test
  (let [now (+ t0 (* 3 hour))
        clock (atom now)
        store (report-store now)
        doc (fake-document false)]
    (execution-watcher/install-vol-range-execution-watcher!
     {:store store :document doc :now-ms-fn #(deref clock) :dispatch! dispatch!})
    (install! store)
    (flush!)
    (reset! dispatched [])
    (@interval-fn)
    (is (some #{marks-refresh} @dispatched))
    (is (not-any? #{refresh} @dispatched) "the backfill is not due")))

(deftest stream-fills-are-offered-to-the-ledger-ingest-test
  (let [store (new-store)
        seen (atom [])]
    (with-redefs [ledger-ingest/on-fills-envelope! (fn ([s env] (swap! seen conj [(identical? s store) (:kind env)]))
                                                     ([_ _ _] nil))]
      (install! store)
      (ingress/observe-user-fills! store {:channel "userFills" :data {:isSnapshot false :user account :fills []}} t0)
      (is (= [[true :fills]] @seen)))))

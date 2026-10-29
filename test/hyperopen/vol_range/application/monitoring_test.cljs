(ns hyperopen.vol-range.application.monitoring-test
  "Live monitoring transitions (HO-SPEC-014 v2.1 sections 9.3, 9.4): epochs,
  stale / resyncing / live, suspension, backoff with jitter and the
  placement blockers. Health maps are shaped like `[:websocket :health]`."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [hyperopen.platform :as platform]
            [hyperopen.vol-range.application.monitoring :as monitoring]
            [hyperopen.vol-range.contracts :as contracts]))

(def account "0x1234567890abcdef1234567890abcdef12345678")
(def other "0x9999999999999999999999999999999999999999")
(def t0 1761660000000)

(defn- stream [topic user status]
  {:topic topic :status status :subscribed? true :descriptor {:type topic :user user}})

(defn- health
  "Transport plus the two account streams. `:user` picks whose streams are
  subscribed (nil: none); `:status` is their stream status."
  [& {:keys [state freshness close-at user status topics]
      :or {state :connected freshness :live user account status :n-a
           topics ["userFills" "openOrders"]}}]
  {:transport (cond-> {:state state :freshness freshness}
                close-at (assoc :last-close {:code 1006 :reason "" :was-clean? false :at-ms close-at}))
   :streams (if user
              (into {} (map (fn [topic] [[topic nil user nil nil] (stream topic user status)])) topics)
              {})})

(def usable (health))
(def down (health :state :reconnecting :freshness :offline))

(defn- observe [m h now] (monitoring/observe m {:account account :health h :now-ms now}))
(defn- snapshot [m now] (monitoring/on-fills-snapshot m {:account account :now-ms now}))
(defn- start [m now] (monitoring/on-resync-started m {:epoch (:epoch m) :now-ms now}))
(defn- finish [m now & {:as opts}]
  (monitoring/on-resync-finished m (merge {:epoch (:epoch m) :account account :ok? true :now-ms now} opts)))

(defn- live-at
  "Monitoring that went live through the normal first-load path."
  [now]
  (-> (monitoring/initial account t0)
      (observe usable t0)
      (snapshot (+ t0 100))
      (observe usable (+ t0 100))
      (start (+ t0 200))
      (finish now)
      (monitoring/on-tick {:now-ms now})))

(deftest policy-test
  (is (= {:version 1 :suspend-gap-ms 45000 :hint-min-interval-ms 5000
          :resync-backoff-ms [15000 30000 60000 120000] :resync-jitter 0.2}
         contracts/live-monitoring-policy)))

(deftest initial-test
  (is (= {:account-address account :status :stale :reasons [:awaiting-streams] :epoch 1
          :stale-since-ms t0 :live-since-ms nil :fills-snapshot-at-ms nil :last-close-at-ms nil
          :last-tick-ms t0 :resync {:state :idle :attempt 0}}
         (monitoring/initial (str/upper-case (str " " account " ")) t0)))
  (testing "the account is lower-cased; the 0x prefix is kept"
    (is (= account (:account-address (monitoring/initial "0x1234567890ABCDEF1234567890ABCDEF12345678" t0))))))

(deftest first-load-test
  (let [m0 (monitoring/initial account t0)
        m1 (observe m0 down (+ t0 10))
        m2 (observe m1 usable (+ t0 20))
        m3 (snapshot m2 (+ t0 30))
        m4 (observe m3 usable (+ t0 30))
        m5 (start m4 (+ t0 40))
        m6 (finish m5 (+ t0 900))]
    (testing "transport down keeps it stale in the same epoch"
      (is (= [:stale [:transport-down] 1] ((juxt :status :reasons :epoch) m1))))
    (testing "usable streams without a snapshot still wait"
      (is (= [:stale [:awaiting-streams] 1] ((juxt :status :reasons :epoch) m2)))
      (is (false? (monitoring/resync-due? m2))))
    (testing "the snapshot marker plus usable streams require a resync"
      (is (= (+ t0 30) (:fills-snapshot-at-ms m3)))
      (is (= :stale (:status m3)))
      (is (= [:resyncing [:catching-up] 1 {:state :required :attempt 0}]
             ((juxt :status :reasons :epoch :resync) m4)))
      (is (true? (monitoring/resync-due? m4))))
    (testing "a started resync is no longer due"
      (is (= {:state :running :attempt 0 :started-at-ms (+ t0 40)} (:resync m5)))
      (is (false? (monitoring/resync-due? m5))))
    (testing "an ok finish in the same epoch goes live"
      (is (= {:status :live :reasons [] :epoch 1 :live-since-ms (+ t0 900) :stale-since-ms nil
              :resync {:state :idle :attempt 0}}
             (select-keys m6 [:status :reasons :epoch :live-since-ms :stale-since-ms :resync]))))
    (testing "the snapshot alone (no usable streams yet) does not resync"
      (is (= :stale (:status (observe (snapshot m1 (+ t0 15)) down (+ t0 15))))))))

(deftest observe-returns-the-same-map-when-nothing-changed-test
  (let [live (live-at (+ t0 1000))]
    (is (identical? live (observe live usable (+ t0 2000))))
    (let [stale (observe (monitoring/initial account t0) down t0)]
      (is (identical? stale (observe stale down (+ t0 5000)))))))

(deftest disconnect-test
  (let [live (live-at (+ t0 1000))
        m (observe live down (+ t0 2000))]
    (is (= [:stale [:transport-down] (inc (:epoch live))] ((juxt :status :reasons :epoch) m)))
    (is (= (+ t0 2000) (:stale-since-ms m)))
    (is (nil? (:live-since-ms m)))
    (is (= {:state :idle :attempt 0} (:resync m)))
    (testing "a delayed transport counts as down"
      (is (= [:transport-down] (:reasons (observe live (health :freshness :delayed) (+ t0 2000))))))
    (testing "going stale from resyncing also starts a new epoch"
      (let [r (snapshot live (+ t0 1500))]
        (is (= (inc (:epoch r)) (:epoch (observe r down (+ t0 2000)))))))))

(deftest reconnect-with-snapshot-after-health-change-test
  (let [live (live-at (+ t0 1000))
        close-at (+ t0 2000)
        m1 (observe live (health :state :reconnecting :freshness :offline :close-at close-at) (+ t0 2050))
        m2 (observe m1 (health :close-at close-at :status :idle) (+ t0 2500))
        m3 (observe m2 (health :close-at close-at) (+ t0 2600))
        m4 (snapshot m3 (+ t0 2700))
        m5 (observe m4 (health :close-at close-at) (+ t0 2700))]
    (is (= [:stale (inc (:epoch live)) close-at] ((juxt :status :epoch :last-close-at-ms) m1)))
    (testing "streams without a first payload after the reconnect are not ready"
      (is (= [:streams-not-ready] (:reasons m2))))
    (testing "the snapshot from before the close is not a valid marker"
      (is (= [:stale [:awaiting-streams]] ((juxt :status :reasons) m3))))
    (testing "the new snapshot makes the marker valid"
      (is (true? (monitoring/resync-due? m5)))
      (is (= (:epoch m1) (:epoch m5))))
    (is (= :live (:status (finish (start m5 (+ t0 2800)) (+ t0 3000)))))))

(deftest reconnect-with-snapshot-before-health-change-test
  (let [live (live-at (+ t0 1000))
        close-at (+ t0 2000)
        m1 (snapshot live (+ t0 2300))
        m2 (observe m1 (health :close-at close-at) (+ t0 2400))]
    (testing "the early snapshot is a re-subscription"
      (is (= [:resyncing [:resubscribed] (inc (:epoch live))] ((juxt :status :reasons :epoch) m1)))
      (is (true? (monitoring/resync-due? m1))))
    (testing "the later close starts another epoch, and the marker is still valid"
      (is (= [:resyncing [:reconnected] (inc (:epoch m1))] ((juxt :status :reasons :epoch) m2)))
      (is (= close-at (:last-close-at-ms m2)))
      (is (true? (monitoring/resync-due? m2))))
    (testing "a result of the earlier epoch is ignored; the current one goes live"
      (let [running-early (start m1 (+ t0 2350))
            m3 (observe running-early (health :close-at close-at) (+ t0 2400))]
        (is (identical? m3 (monitoring/on-resync-finished
                            m3 {:epoch (:epoch m1) :account account :ok? true :now-ms (+ t0 2500)})))
        (is (= :live (:status (finish (start m3 (+ t0 2600)) (+ t0 2700)))))))))

(deftest resubscription-snapshot-test
  (let [live (live-at (+ t0 1000))]
    (testing "while live"
      (let [m (snapshot live (+ t0 5000))]
        (is (= [:resyncing [:resubscribed] (inc (:epoch live))] ((juxt :status :reasons :epoch) m)))
        (is (= (+ t0 5000) (:fills-snapshot-at-ms m)))
        (is (= {:state :required :attempt 0} (:resync m)))))
    (testing "while a resync is running, it restarts in a new epoch"
      (let [running (start (snapshot live (+ t0 5000)) (+ t0 5100))
            m (snapshot running (+ t0 5200))]
        (is (= (inc (:epoch running)) (:epoch m)))
        (is (true? (monitoring/resync-due? m)))
        (is (identical? m (monitoring/on-resync-finished
                           m {:epoch (:epoch running) :account account :ok? true :now-ms (+ t0 5300)})))))
    (testing "another account's snapshot is ignored"
      (is (identical? live (monitoring/on-fills-snapshot live {:account other :now-ms (+ t0 5000)}))))
    (testing "account case does not matter"
      (is (= :resyncing (:status (monitoring/on-fills-snapshot
                                  live {:account (str/upper-case account) :now-ms (+ t0 5000)})))))))

(deftest account-switch-mid-resync-test
  (let [running (-> (monitoring/initial account t0)
                    (observe usable t0)
                    (snapshot (+ t0 10))
                    (observe usable (+ t0 10))
                    (start (+ t0 20)))
        switched (monitoring/observe running {:account other :health (health :user other) :now-ms (+ t0 30)})]
    (is (= :running (get-in running [:resync :state])))
    (testing "the new account starts over with a higher epoch"
      (is (= [other :stale [:awaiting-streams] (inc (:epoch running)) nil]
             ((juxt :account-address :status :reasons :epoch :fills-snapshot-at-ms) switched))))
    (testing "the old account's result is ignored"
      (is (identical? switched (monitoring/on-resync-finished
                                switched {:epoch (:epoch running) :account account :ok? true :now-ms (+ t0 40)})))
      (is (identical? switched (monitoring/on-resync-finished
                                switched {:epoch (:epoch switched) :account account :ok? true :now-ms (+ t0 40)}))))
    (testing "the placement blocker refuses the old account"
      (is (= :monitoring/stale (monitoring/placement-blocker switched {:account account :now-ms (+ t0 40)}))))))

(deftest stream-reasons-test
  (let [m (monitoring/initial account t0)
        reasons (fn [h] (:reasons (observe m h t0)))]
    (testing "spectate or trader route: the streams follow another account"
      (is (= [:streams-elsewhere] (reasons (health :user other)))))
    (testing "no account streams at all yet"
      (is (= [:streams-not-ready] (reasons (health :user nil)))))
    (testing "only one of the two streams"
      (is (= [:streams-not-ready] (reasons (health :topics ["userFills"]))))
      (is (= [:streams-not-ready] (reasons (health :topics ["openOrders"])))))
    (testing "subscribed but no payload since subscribing"
      (is (= [:streams-not-ready] (reasons (health :status :idle)))))
    (testing "transport problems win"
      (is (= [:transport-down] (reasons (health :user other :state :connecting :freshness :offline)))))
    (testing "addresses compare without case"
      (is (= [:awaiting-streams] (reasons (health :user (str "0x" (str/upper-case (subs account 2))))))))
    (testing "a live account losing its streams to a spectated account goes stale"
      (let [live (live-at (+ t0 1000))
            m* (observe live (health :user other) (+ t0 2000))]
        (is (= [:stale [:streams-elsewhere] (inc (:epoch live))] ((juxt :status :reasons :epoch) m*)))
        (testing "and resyncs on return once a new snapshot arrived"
          (let [back (observe (snapshot m* (+ t0 2500)) usable (+ t0 3000))]
            (is (= [:resyncing [:catching-up]] ((juxt :status :reasons) back)))
            (is (true? (monitoring/resync-due? back)))))))
    (testing "no account never counts as usable, whatever streams exist"
      (let [n (monitoring/observe nil {:account nil :health (health :user other) :now-ms t0})]
        (is (= [nil :stale [:streams-not-ready]] ((juxt :account-address :status :reasons) n)))
        (is (= :stale (:status (monitoring/on-fills-snapshot n {:account nil :now-ms t0}))))))))

(deftest suspension-test
  (let [live (live-at (+ t0 1000))
        gap (:suspend-gap-ms contracts/live-monitoring-policy)]
    (testing "a gap at the limit is not a suspension"
      (let [m (monitoring/on-tick live {:now-ms (+ t0 1000 gap)})]
        (is (= :live (:status m)))
        (is (= (+ t0 1000 gap) (:last-tick-ms m)))))
    (testing "a longer gap while live starts a new epoch that needs a resync"
      (let [m (monitoring/on-tick live {:now-ms (+ t0 1001 gap)})]
        (is (= [:resyncing [:suspended] (inc (:epoch live))] ((juxt :status :reasons :epoch) m)))
        (is (= (+ t0 1001 gap) (:stale-since-ms m)))
        (is (true? (monitoring/resync-due? m)))))
    (testing "a gap while a resync runs restarts it"
      (let [running (start (snapshot live (+ t0 2000)) (+ t0 2000))
            m (monitoring/on-tick running {:now-ms (+ t0 2000 gap 1)})]
        (is (= (inc (:epoch running)) (:epoch m)))
        (is (= {:state :required :attempt 0} (:resync m)))))
    (testing "a gap while stale changes nothing but the tick"
      (let [stale (monitoring/initial account t0)
            m (monitoring/on-tick stale {:now-ms (+ t0 gap 1)})]
        (is (= (assoc stale :last-tick-ms (+ t0 gap 1)) m))))
    (testing "a clock that moved back is not a suspension"
      (let [m (monitoring/on-tick live {:now-ms (- t0 100000)})]
        (is (= :live (:status m)))
        (is (= (:epoch live) (:epoch m)))
        (is (= (- t0 100000) (:last-tick-ms m)))))
    (testing "nil monitoring stays nil"
      (is (nil? (monitoring/on-tick nil {:now-ms t0}))))))

(defn- failed-once [now jitter]
  (let [due (snapshot (live-at (+ t0 1000)) (+ t0 2000))]
    (finish (start due (+ t0 2000)) now :ok? false :error "Fill page cap reached" :jitter jitter)))

(defn- ticks
  "The watcher's 15 s ticks from `from` up to and including `to`."
  [m from to]
  (reduce #(monitoring/on-tick %1 {:now-ms %2}) m (concat (range from to 15000) [to])))

(deftest backoff-and-jitter-test
  (let [now (+ t0 3000)]
    (testing "attempt 1 waits 15 s scaled by 1 + jitter, jitter bounded to 20 %"
      (doseq [[jitter expected] [[0 15000] [0.2 18000] [-0.2 12000] [0.1 16500]
                                 [0.9 18000] [-5 12000] [nil 15000]]]
        (let [m (failed-once now jitter)]
          (is (= {:state :failed :attempt 1 :error "Fill page cap reached" :next-at-ms (+ now expected)}
                 (:resync m))
              (str "jitter " jitter))
          (is (= :resyncing (:status m)))
          (is (false? (monitoring/resync-due? m))))))
    (testing "retries become due at their time and back off 15, 30, 60, 120, 120 s"
      (loop [m (failed-once now 0) failed-at now n 1 expected [15000 30000 60000 120000 120000]]
        (when (seq expected)
          (let [at (get-in m [:resync :next-at-ms])
                before (ticks m failed-at (dec at))
                due (monitoring/on-tick before {:now-ms at})
                fail-at (+ at 10)]
            (is (= n (get-in m [:resync :attempt])))
            (is (= (first expected) (- at failed-at)) (str "attempt " n))
            (is (= :failed (get-in before [:resync :state])))
            (is (= :required (get-in due [:resync :state])))
            (is (= n (get-in due [:resync :attempt])) "a retry keeps its attempt count")
            (recur (finish (start due fail-at) fail-at :ok? false :error "again" :jitter 0)
                   fail-at (inc n) (rest expected))))))
    (testing "a later ok clears the attempts"
      (let [m (failed-once now 0)
            due (monitoring/on-tick m {:now-ms (+ now 15000)})]
        (is (= {:state :idle :attempt 0} (:resync (finish (start due (+ now 15000)) (+ now 16000)))))))))

(deftest resync-start-and-finish-guards-test
  (let [due (snapshot (live-at (+ t0 1000)) (+ t0 2000))
        running (start due (+ t0 2000))]
    (testing "start needs the current epoch and a required resync"
      (is (identical? due (monitoring/on-resync-started due {:epoch (dec (:epoch due)) :now-ms t0})))
      (is (identical? running (monitoring/on-resync-started running {:epoch (:epoch running) :now-ms t0})))
      (is (nil? (monitoring/on-resync-started nil {:epoch 1 :now-ms t0}))))
    (testing "finish needs a running resync of that epoch and account"
      (is (identical? due (finish due (+ t0 2100))) "never started")
      (is (identical? running (finish running (+ t0 2100) :account other)))
      (is (identical? running (finish running (+ t0 2100) :epoch (inc (:epoch running)))))
      (let [live (finish running (+ t0 2100))]
        (is (= :live (:status live)))
        (is (identical? live (finish live (+ t0 2200))) "a duplicate finish is ignored")))))

(deftest placement-blocker-test
  (let [live (live-at (+ t0 1000))
        gap (:suspend-gap-ms contracts/live-monitoring-policy)
        blocker (fn [m & {:keys [acct now] :or {acct account now (+ t0 1000)}}]
                  (monitoring/placement-blocker m {:account acct :now-ms now}))]
    (is (nil? (blocker live)))
    (is (nil? (blocker live :acct (str/upper-case account))))
    (is (nil? (blocker live :now (+ t0 1000 gap))) "a tick exactly at the limit")
    (is (= :monitoring/suspended (blocker live :now (+ t0 1001 gap))) "a stale tick at call time")
    (is (= :monitoring/stale (blocker nil)))
    (is (= :monitoring/stale (blocker live :acct other)))
    (is (= :monitoring/stale (blocker live :acct nil)))
    (is (= :monitoring/stale (blocker (monitoring/initial account (+ t0 1000)))))
    (is (= :monitoring/stale (blocker (observe live down (+ t0 1000)))))
    (is (= :monitoring/resync-required (blocker (snapshot live (+ t0 1000)))))
    (is (= :monitoring/resync-required (blocker (start (snapshot live (+ t0 1000)) (+ t0 1000)))))
    (is (= :monitoring/resync-required (blocker (failed-once (+ t0 1000) 0))))
    (is (= :monitoring/suspended (blocker (dissoc live :last-tick-ms))) "no tick fails closed")
    (testing "view-blocker applies the status and account rules but not the tick gap"
      (is (nil? (monitoring/view-blocker live account)))
      (is (nil? (monitoring/view-blocker (dissoc live :last-tick-ms) account)))
      (is (= :monitoring/stale (monitoring/view-blocker live other)))
      (is (= :monitoring/stale (monitoring/view-blocker live nil)))
      (is (= :monitoring/stale (monitoring/view-blocker nil account)))
      (is (= :monitoring/resync-required (monitoring/view-blocker (snapshot live (+ t0 1000)) account)))
      (is (= :monitoring/stale (monitoring/view-blocker (observe live down (+ t0 1000)) account))))))

(deftest revision-test
  (is (= "4:41" (monitoring/revision {:epoch 4} 41)))
  (is (= "0:1" (monitoring/revision nil 1))))

(def ^:private fs (js/require "fs"))

(deftest no-function-reads-a-clock-test
  (testing "the source names no clock or random source"
    (let [text (.readFileSync fs "src/hyperopen/vol_range/application/monitoring.cljs" "utf8")]
      (doseq [needle ["js/Date" "platform" "Math.random" "now-ms!" "performance"]]
        (is (not (str/includes? text needle)) needle))))
  (testing "a whole scenario runs with the platform clock and random source broken"
    (with-redefs [platform/now-ms (fn [] (throw (js/Error. "clock read")))
                  platform/random-value (fn [] (throw (js/Error. "random read")))]
      (let [m (-> (live-at (+ t0 1000))
                  (observe down (+ t0 2000))
                  (observe (health :close-at (+ t0 2000)) (+ t0 3000))
                  (snapshot (+ t0 3100))
                  (observe (health :close-at (+ t0 2000)) (+ t0 3100))
                  (start (+ t0 3200))
                  (finish (+ t0 3300) :ok? false :jitter 0.1)
                  (monitoring/on-tick {:now-ms (+ t0 3300 16500)})
                  (start (+ t0 3300 16500))
                  (finish (+ t0 3300 17000)))]
        (is (= :live (:status m)))
        (is (nil? (monitoring/placement-blocker m {:account account :now-ms (+ t0 3300 17000)})))))))

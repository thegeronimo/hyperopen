(ns hyperopen.vol-range.infrastructure.report-watcher
  "Keeps the Vol Range report current when reporting is on (HO-SPEC-014 v2.1
  sections 9.3, 9.4, 11.3, 12.2).

  - A store watch: when the Portfolio Vol Range tab becomes visible, or the
    shown account changes while it is (or the loaded report is for another
    account), one coalesced microtask dispatches
    `:actions/load-vol-range-report`. Rendering dispatches nothing.
  - No timer of its own: a listener on the execution watcher's existing tick
    dispatches `:actions/refresh-vol-range-report` while the page is visible,
    the trading account has an open tracking campaign, and at least
    `:backfill-interval-ms` passed since that account's last backfill.
  - WebSocket fills reach the ledger through `ledger-ingest`, registered with
    `live-ingress/register-fills-consumer!`.

  It only dispatches actions; the work runs in the vol-range-report effect."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.platform :as platform]
            [hyperopen.vol-range.contracts :as contracts]
            [hyperopen.vol-range.infrastructure.execution-watcher :as execution-watcher]
            [hyperopen.vol-range.infrastructure.ledger-ingest :as ledger-ingest]
            [hyperopen.vol-range.infrastructure.live-ingress :as live-ingress]
            [hyperopen.vol-range.report-actions :as report-actions]))

(def ^:private watch-key ::vol-range-report)
(def ^:private listener-key ::vol-range-report-backfill)
(def ^:private consumer-key ::vol-range-ledger-ingest)
(defonce ^:private load-scheduled? (atom false))

(defn- lower [x] (some-> x str str/trim str/lower-case not-empty))
(defn- busy? [state k] (true? (get-in state [:vol-range :report :busy k])))

(defn- report-key [state]
  [(report-actions/report-visible? state) (report-actions/report-account state)])

(defn- inputs
  "Everything `load-wanted?` reads, compared first so most store changes
  cost no route parsing."
  [state]
  [(get-in state [:router :path]) (get-in state [:portfolio-ui :account-info-tab])
   (account-context/effective-account-address state)
   (get-in state [:vol-range :report :account-address]) (busy? state [:load])])

(defn load-wanted?
  "True when a store change from `old-state` to `new-state` should load the
  report: it is visible with an account, and it just became visible, the
  shown account changed, or the loaded report is for another account (and
  no load is in flight)."
  [old-state new-state]
  (let [[visible? account] (when (not= (inputs old-state) (inputs new-state)) (report-key new-state))]
    (boolean
     (and visible? account
          (or (not= (report-key old-state) [visible? account])
              (and (not= account (get-in new-state [:vol-range :report :account-address]))
                   (not (busy? new-state [:load]))))))))

(defn- has-open-campaign? [state account]
  (let [closures (get-in state [:vol-range :campaign-closures])]
    (some #(and (= account (lower (get-in % [:scope :account-address])))
                (not (contains? closures (:campaign-id %))))
          (vals (get-in state [:vol-range :campaigns])))))

(defn backfill-due?
  "True when a background backfill should run now for the trading account."
  [state now-ms]
  (let [account (lower (account-context/active-trading-account-address state))
        last-at (get-in state [:vol-range :report :backfill-at-ms account])]
    (boolean
     (and account
          (not (report-actions/read-only? state))
          (= account (report-actions/report-account state))
          (has-open-campaign? state account)
          (not (busy? state [:load]))
          (not (busy? state [:backfill]))
          (or (not (number? last-at))
              (>= (- now-ms last-at) (:backfill-interval-ms contracts/report-policy)))))))

(defn- on-tick [dispatch!]
  (fn [{:keys [store now-ms visible?]}]
    (when (and visible? (contracts/reporting-enabled?) (backfill-due? @store now-ms))
      (dispatch! store nil [[:actions/refresh-vol-range-report]]))))

(defn- schedule-load! [store dispatch!]
  (when (compare-and-set! load-scheduled? false true)
    (platform/queue-microtask!
     (fn []
       (reset! load-scheduled? false)
       (dispatch! store nil [[:actions/load-vol-range-report]])))))

(defn install-vol-range-report-watcher!
  "Install (or reinstall) the watch, the tick listener and the fills
  consumer; with reporting off, remove them."
  [{:keys [store dispatch!]}]
  (remove-watch store watch-key)
  (execution-watcher/unregister-tick-listener! listener-key)
  (live-ingress/unregister-fills-consumer! consumer-key)
  (reset! load-scheduled? false)
  (when (contracts/reporting-enabled?)
    (add-watch store watch-key
               (fn [_ _ old-state new-state]
                 (when (load-wanted? old-state new-state)
                   (schedule-load! store dispatch!))))
    (execution-watcher/register-tick-listener! listener-key (on-tick dispatch!))
    (live-ingress/register-fills-consumer! consumer-key #(ledger-ingest/on-fills-envelope! store %))
    ;; Already on the tab at startup (a deep link): load once.
    (when (load-wanted? {} @store)
      (schedule-load! store dispatch!)))
  store)

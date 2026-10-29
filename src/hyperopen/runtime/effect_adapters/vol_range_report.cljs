(ns hyperopen.runtime.effect-adapters.vol-range-report
  "Runtime adapter for Vol Range reporting (HO-SPEC-014 v2.1 sections 9.2,
  9.4, 12, 15.2): `[:effects/vol-range-report op params]`.

  Builds the reporting collaborators from the app store: the shared info
  request function (reads only, low priority for backfill), the committed
  journal, the `vol-range-ledger` store, spot metadata, the account admission
  section (Start tracking only), the clock and crypto entropy. There is no
  send path here: no op builds, signs or sends an action. Results land under
  `[:vol-range :report]`:

    {:account-address :journal-ok? :storage-ok? :campaigns :closures
     :attribution :ledger :unreadable :loaded-at-ms
     :backfill-at-ms {account ms} :marks {coin entry} :marks-at-ms
     :busy {op-key true} :last {...} :ui {...}}

  Hourly closes (`report-marks`) are public candle reads at low priority:
  `:marks` fetches what the selected window still needs, and a load or
  backfill fetches them after it reloads.

  The loaded account's campaigns also refresh `[:vol-range :campaigns]` and
  `[:vol-range :campaign-closures]` when it is the trading account, so the
  Vol ladders tracking line follows a restart. A result for an account that
  is no longer shown is dropped."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.api.default.state :as api-state]
            [hyperopen.api.trading.action-coordinator :as action-coordinator]
            [hyperopen.platform :as platform]
            [hyperopen.vol-range.contracts :as contracts]
            [hyperopen.vol-range.infrastructure.campaign-executor :as campaign-executor]
            [hyperopen.vol-range.infrastructure.entropy :as entropy]
            [hyperopen.vol-range.infrastructure.journal :as journal]
            [hyperopen.vol-range.infrastructure.ledger-store :as ledger-store]
            [hyperopen.vol-range.domain.report-marks :as report-marks]
            [hyperopen.vol-range.infrastructure.report-executor :as report-executor]
            [hyperopen.vol-range.infrastructure.report-reads :as report-reads]
            [hyperopen.vol-range.report-actions :as report-actions]))

(def ^:private report-path [:vol-range :report])

(defn- lower [x] (some-> x str str/trim str/lower-case not-empty))
(defn- error-text [e]
  (cond (instance? js/Error e) (.-message e) (some? e) (str e) :else "unknown error"))

(defn report-deps [store]
  {:post-info! api-state/post-info!
   :journal {:load-all! journal/load-all! :commit! journal/commit!}
   :ledger {:put-records! ledger-store/put-records!
            :load-account! ledger-store/load-account!
            :merge-coverage! ledger-store/merge-coverage!
            :put-balance-check! ledger-store/put-balance-check!}
   :spot-meta! #(get-in @store [:spot :meta])
   :admission! action-coordinator/with-account-admission!
   :now-ms! platform/now-ms
   :random-id! entropy/random-id!
   :network (contracts/current-network)})

(defn- shown-account [state] (lower (account-context/effective-account-address state)))

(defn write-loaded
  "`state` with a `report-executor/load!` result written, unless that
  account is no longer shown."
  [state {:keys [account-address journal-ok?] :as loaded} now-ms]
  (if-not (= account-address (shown-account state))
    state
    (cond-> (update-in state report-path merge
                       (-> (select-keys loaded [:account-address :journal-ok? :storage-ok? :campaigns :closures
                                                :attribution :ledger :unreadable])
                           (assoc :loaded-at-ms now-ms)))
      (and journal-ok?
           (= account-address (lower (account-context/active-trading-account-address state))))
      (-> (assoc-in [:vol-range :campaigns] (:campaigns loaded))
          (assoc-in [:vol-range :campaign-closures] (:closures loaded))))))

(defn- reload! [store deps account]
  (.then (report-executor/load! deps account)
         (fn [loaded]
           (swap! store write-loaded loaded (platform/now-ms))
           loaded)))

(defn- loaded-status [{:keys [journal-ok? storage-ok? error]}]
  (cond (not journal-ok?) {:status :error :error error}
        (not storage-ok?) {:status :storage/unavailable :error error}
        :else {:status :ok}))

(defn- fetch-marks!
  "Read each request's hourly closes and merge them into the report's marks.
  Resolves to {:status :ok} or {:status :error :error} (some read was
  incomplete); never rejects."
  [store deps requests]
  (swap! store assoc-in (conj report-path :marks-at-ms) (platform/now-ms))
  (-> (js/Promise.all
       (into-array
        (for [{:keys [coin from-ms to-ms]} requests]
          (.then (report-reads/read-hourly-candles! (:post-info! deps)
                                                    {:coin coin :from-ms from-ms :to-ms to-ms
                                                     :now-ms! (:now-ms! deps)})
                 (fn [read]
                   (swap! store update-in (into report-path [:marks coin])
                          report-marks/merge-read read [from-ms to-ms])
                   read)))))
      (.then (fn [reads]
               (if-let [bad (first (remove :complete? (array-seq reads)))]
                 {:status :error :error (or (:error bad) "Couldn't read hourly prices")}
                 {:status :ok})))))

(defn- with-marks!
  "After a load or backfill, fetch the hourly closes the selected window
  still needs; the operation's own result is kept."
  [store deps result]
  (let [requests (report-actions/marks-requests @store ((:now-ms! deps)))]
    (if (seq requests)
      (.then (fetch-marks! store deps requests) (fn [_] result))
      result)))

(defn- backfill! [store deps account]
  (swap! store assoc-in (into report-path [:backfill-at-ms account]) (platform/now-ms))
  (-> (report-executor/backfill! deps {:account-address account})
      (.then (fn [result] (.then (reload! store deps account) (fn [_] result))))
      (.then (fn [result] (with-marks! store deps result)))))

(defn- load! [store deps {:keys [backfill?]} account]
  (.then (reload! store deps account)
         (fn [loaded]
           (if (and backfill? (:journal-ok? loaded) (:storage-ok? loaded) (seq (:campaigns loaded)))
             (backfill! store deps account)
             (with-marks! store deps (loaded-status loaded))))))

(defn- start-tracking! [store deps {:keys [campaign-key]} account]
  (.then (campaign-executor/start-tracking! deps {:campaign-key campaign-key :account-address account})
         (fn [result]
           (if (= :ok (:status result))
             (.then (reload! store deps account) (fn [_] result))
             result))))

(def op-timeout-ms
  "Loads and backfills give up waiting after this long, so a stuck read or
  lock never leaves the report loading (their writes are idempotent and
  monotonic, so a late finish is harmless). Start tracking is bounded by the
  admission section's own timeout instead: its commit may still land."
  60000)

(defn- bounded [op p]
  (if (= :start-tracking op)
    p
    (js/Promise.race
     #js [p (js/Promise. (fn [resolve _]
                           (platform/set-timeout! #(resolve {:status :error :error "Timed out; try again."})
                                                  op-timeout-ms)))])))

(defn- finish! [store busy-key op account result]
  (swap! store
         (fn [state]
           (cond-> (-> state
                       (update-in (conj report-path :busy) dissoc busy-key)
                       ;; A successful marks read is not announced; the
                       ;; headline shows its result.
                       (cond-> (not (and (= :marks op) (= :ok (:status result))))
                         (assoc-in (conj report-path :last)
                                   (-> (select-keys result [:status :errors :error])
                                       (assoc :op op :at-ms (platform/now-ms))))))
             ;; A load that failed before writing (a timeout) still marks the
             ;; account as loaded, with nothing known, so the watcher does not
             ;; retry it on every change and no other account's data shows.
             (and (not= :marks op)
                  (= account (shown-account state))
                  (not= account (get-in state (conj report-path :account-address))))
             (update-in report-path merge {:account-address account :journal-ok? false :storage-ok? false
                                           :campaigns {} :closures {} :attribution nil :ledger nil
                                           :unreadable [] :loaded-at-ms (platform/now-ms)})))))

(defn vol-range-report
  "Effect `[:effects/vol-range-report op params]`. Ops: :load
  {:account-address :backfill?}, :backfill {:account-address},
  :start-tracking {:campaign-key :account-address}, :marks {:requests
  [{:coin :from-ms :to-ms}]} (candle opens). (:export arrives in a later
  stage.)"
  [_ctx store op params]
  (let [account (lower (:account-address params))
        busy-key [op]
        deps (report-deps store)]
    (swap! store assoc-in (into report-path [:busy busy-key]) true)
    (-> (bounded
         op
         (-> (js/Promise.resolve nil)
             (.then (fn [_]
                      (cond
                        (not (contracts/reporting-enabled?)) {:status :error :error "Reporting is off."}
                        (and (nil? account) (not= :marks op)) {:status :error :error "No account."}
                        :else
                        (case op
                          :load (load! store deps params account)
                          :backfill (backfill! store deps account)
                          :start-tracking (start-tracking! store deps params account)
                          :marks (fetch-marks! store deps (vec (:requests params)))
                          {:status :error :error (str "Unknown operation " op)}))))))
        (.then (fn [result] (finish! store busy-key op account result)))
        (.catch (fn [e] (finish! store busy-key op account {:status :error :error (error-text e)}))))))

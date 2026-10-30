(ns hyperopen.vol-range.infrastructure.ledger-ingest
  "WebSocket fills into the Vol Range reporting ledger (HO-SPEC-014 v2.1
  sections 9.1, 9.2, 12.2).

  Registered with `live-ingress/register-fills-consumer!`, it receives every
  raw `userFills` envelope (all rows, all coins, before any display
  truncation). For the trading account's open tracking campaigns it
  normalizes the rows with `fill-records/normalize-fill`, keeps the ones that
  can change a tracked base token, and queues ONE idempotent put keyed by
  fill key, so a WebSocket row and the REST row of the same fill are stored
  once. Activity appears quickly; coverage is never touched: only complete
  REST reads prove it (spec 9.1 has no venue sequence numbers).

  It runs on the tap thread, so it never awaits: the put is chained on a
  promise and a failure is only logged (REST backfill catches up)."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.platform :as platform]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.vol-range.application.fill-records :as fill-records]
            [hyperopen.vol-range.application.report-attribution :as attribution]
            [hyperopen.vol-range.contracts :as contracts]
            [hyperopen.vol-range.infrastructure.ledger-store :as ledger-store]))

(defn- lower [x] (some-> x str str/trim str/lower-case not-empty))

(def ^:private report-fills-path [:vol-range :report :ledger :fills])

(defn- open-campaigns [state account]
  (let [closures (get-in state [:vol-range :campaign-closures])]
    (->> (vals (get-in state [:vol-range :campaigns]))
         (filter #(= account (lower (get-in % [:scope :account-address]))))
         (remove #(contains? closures (:campaign-id %))))))

(defn- campaign-start [c]
  (- (:t0-ms c) (or (get-in c [:baseline :boundary :skew-ms]) (:boundary-skew-ms contracts/report-policy))))

(defn relevant-fills
  "Canonical fills of `envelope` worth storing for `state`'s trading account:
  rows that can change a base token of an open campaign, from shortly before
  the earliest campaign start on, and not already in the loaded report
  ledger. [] when the envelope is for another account, no campaign is open or
  spot metadata is not loaded (the backfill then classifies them)."
  [state envelope]
  (let [account (lower (account-context/active-trading-account-address state))
        campaigns (open-campaigns state account)
        tokens (into #{} (keep #(some-> (get-in % [:scope :base-token-id]) str)) campaigns)
        markets (attribution/spot-markets (get-in state [:spot :meta]))
        from (when (seq campaigns)
               (- (apply min (map campaign-start campaigns)) (:fills-overlap-ms contracts/report-policy)))
        known (get-in state report-fills-path)]
    (if-not (and account
                 (= :fills (:kind envelope))
                 (= account (get-in envelope [:scope :account-address]))
                 (seq tokens)
                 (:ready? markets))
      []
      (->> (:rows envelope)
           (keep #(fill-records/normalize-fill {:network (get-in envelope [:scope :network])
                                                :account-address account :row % :source :websocket
                                                :received-at-ms (:received-at-ms envelope)}))
           (filter #(>= (:time %) from))
           (filter #(attribution/relevant-fill? (attribution/classify-fill markets {} %) tokens))
           (remove #(contains? known (:fill-key %)))
           (reduce (fn [acc f] (assoc acc (:fill-key f) f)) {})
           vals
           vec))))

(defn- merge-into-report [state account fills]
  (if (= account (get-in state [:vol-range :report :account-address]))
    (update-in state report-fills-path merge (into {} (map (juxt :fill-key identity)) fills))
    state))

(defn on-fills-envelope!
  "The fills consumer: queue an idempotent put of `envelope`'s relevant
  fills and, once committed, show them in the loaded report. Returns the
  queued promise (nil when nothing was relevant). `opts` (tests) may give
  `:put-records!` and `:now-ms!`."
  ([store envelope] (on-fills-envelope! store envelope {}))
  ([store envelope {:keys [put-records! now-ms!]}]
   (when (contracts/reporting-enabled?)
     (let [fills (relevant-fills @store envelope)
           account (get-in envelope [:scope :account-address])]
       (when (seq fills)
         (let [at ((or now-ms! platform/now-ms))
               put! (or put-records! ledger-store/put-records!)
               records (mapv #(ledger-store/fill-record % at) fills)]
           (-> (js/Promise.resolve nil)
               (.then (fn [_] (put! records)))
               (.then (fn [_] (swap! store merge-into-report account fills) (count records)))
               (.catch (fn [e]
                         (telemetry/log! "Vol Range ledger ingest failed" (str e))
                         nil)))))))))

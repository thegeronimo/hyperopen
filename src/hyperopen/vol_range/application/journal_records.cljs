(ns hyperopen.vol-range.application.journal-records
  "Versioned Vol Range journal records (HO-SPEC-014 v2.1 sections 7.4-7.6, 8.1).

  Pure constructors and queries. Records carry `:schema-version`, `:scope`,
  `:record-id` and `:saved-at-ms`; monetary values are decimal strings; no
  record ever holds keys, signatures or session material. Time and ids are
  injected."
  (:require [clojure.string :as str]
            [hyperopen.domain.trading.precision :as precision]))

(def schema-version 1)

(def ^:private forbidden-keys
  "Signing material. Not :r/:s/:v: those are also Hyperliquid wire-order keys
  (reduce-only, size) that a journaled action legitimately carries."
  #{:private-key :signature :session :agent-key :secret :unlocked-session})

(defn- money [x]
  (let [d (cond (string? x) (precision/parse-decimal x)
                (map? x) x
                :else nil)]
    (when d (precision/decimal->string d))))

(defn intent-record
  "The immutable placement intent, committed BEFORE any nonce is reserved or
  anything is signed (spec 8.1 step 4)."
  [{:keys [operation-id scope scope-key plan-id input-fingerprint action-digest legs
           reservations now-ms]}]
  {:schema-version schema-version
   :record-id (str "op:" operation-id)
   :kind :placement-intent
   :operation-id operation-id
   :scope scope
   :scope-key scope-key
   :plan-id plan-id
   :input-fingerprint input-fingerprint
   :action-digest action-digest
   :legs (mapv (fn [{:keys [leg-id cloid side price size]}]
                 {:leg-id leg-id :cloid cloid :side side :price price :size size})
               legs)
   :reservations {:usdc (or (money (:usdc reservations)) "0")
                  :base (or (money (:base reservations)) "0")}
   :reservation-state :pending
   :saved-at-ms now-ms})

(defn attempt-record
  "Send-attempt metadata committed immediately before the HTTP call
  (spec 8.1 step 7, 8.3). `:delivery :sending` until an outcome is recorded."
  [{:keys [operation-id attempt-id scope-key nonce expires-after-ms action-digest now-ms]}]
  {:schema-version schema-version
   :record-id (str "attempt:" attempt-id)
   :kind :send-attempt
   :operation-id operation-id
   :attempt-id attempt-id
   :scope-key scope-key
   :nonce nonce
   :expires-after-ms expires-after-ms
   :action-digest action-digest
   :attempted-at-ms now-ms
   :delivery :sending
   :response-category nil
   :leg-results []
   :saved-at-ms now-ms})

(defn with-outcome
  "The attempt record after its delivery result and normalized leg outcomes."
  [attempt {:keys [delivery response-category leg-results]} now-ms]
  (assoc attempt
         :delivery delivery
         :response-category response-category
         :leg-results (mapv #(select-keys % [:cloid :oid :placement-state :order-state :reason
                                             :filled-base :avg-px])
                            leg-results)
         :saved-at-ms now-ms))

(defn- leg-unresolved? [leg] (= :unknown (:placement-state leg)))

(defn attempt-unresolved?
  "Spec 8.6: a possible send is unresolved until every requested leg has
  definitive evidence. A crash after recording the attempt is unresolved even
  if the HTTP call may never have started."
  [attempt]
  (or (contains? #{:sending :unknown} (:delivery attempt))
      (and (= :response (:delivery attempt))
           (or (empty? (:leg-results attempt)) (some leg-unresolved? (:leg-results attempt))))))

(defn unresolved-attempts
  "Attempt records that block new placement for `scope-key` (nil = any scope)."
  [records scope-key]
  (->> records
       (filter #(= :send-attempt (:kind %)))
       (filter #(or (nil? scope-key) (= scope-key (:scope-key %))))
       (filter attempt-unresolved?)
       vec))

(defn grid-record
  "The current lifecycle state of one logical grid (membership, legs,
  operations, fills). Overwritten in place as evidence arrives."
  [grid now-ms]
  {:schema-version schema-version
   :record-id (str "grid:" (:grid-id grid))
   :kind :grid
   :grid-id (:grid-id grid)
   :scope (:scope grid)
   :scope-key (:scope-key grid)
   :grid grid
   :saved-at-ms now-ms})

(defn cancel-attempt-record
  "A cancel send attempt (spec 10.1). Best effort: cancellation never waits on
  a successful journal commit (spec 7.4)."
  [{:keys [operation-id attempt-id grid-id scope-key nonce expires-after-ms action-digest targets now-ms]}]
  {:schema-version schema-version
   :record-id (str "cancel:" attempt-id)
   :kind :cancel-attempt
   :operation-id operation-id
   :attempt-id attempt-id
   :grid-id grid-id
   :scope-key scope-key
   :nonce nonce
   :expires-after-ms expires-after-ms
   :action-digest action-digest
   :targets (mapv #(select-keys % [:cloid :oid]) targets)
   :attempted-at-ms now-ms
   :delivery :sending
   :results []
   :saved-at-ms now-ms})

(defn with-cancel-outcome [attempt {:keys [delivery results]} now-ms]
  (assoc attempt
         :delivery delivery
         :results (mapv #(select-keys % [:cloid :oid :cancel-state :reason]) results)
         :saved-at-ms now-ms))

(defn reservation-release-record
  "The pending reservation of `operation-id` is released: coherent account
  evidence now includes its hold or settlement (spec 8.1 step 10)."
  [{:keys [operation-id scope now-ms]}]
  {:schema-version schema-version
   :record-id (str "release:" operation-id)
   :kind :reservation-release
   :operation-id operation-id
   :scope scope
   :saved-at-ms now-ms})

(defn grids
  "Grid states from journal records, optionally for one scope key."
  [records scope-key]
  (->> records
       (filter #(= :grid (:kind %)))
       (filter #(or (nil? scope-key) (= scope-key (:scope-key %))))
       (mapv :grid)))

(defn pending-reservations
  "Sum of reservations still pending for intents on `account-address` (spec
  3.3: counted until coherent account evidence includes the hold). An intent
  with a release record no longer counts. `exclude-operation-id` is the
  caller's own operation, which is the same commitment, not a competing one
  (spec 8.1)."
  ([records account-address] (pending-reservations records account-address nil))
  ([records account-address exclude-operation-id]
  (let [account (some-> account-address str/lower-case)
        released (->> records (filter #(= :reservation-release (:kind %))) (map :operation-id) set)
        pending (->> records
                     (filter #(= :placement-intent (:kind %)))
                     (filter #(= :pending (:reservation-state %)))
                     (remove #(contains? released (:operation-id %)))
                     (remove #(= exclude-operation-id (:operation-id %)))
                     (filter #(= account (some-> (get-in % [:scope :account-address]) str/lower-case))))
        sum (fn [k] (reduce precision/add (precision/parse-decimal "0")
                            (map #(precision/parse-decimal (get-in % [:reservations k])) pending)))]
    {:usdc (precision/decimal->string (sum :usdc))
     :base (precision/decimal->string (sum :base))
     :operation-ids (mapv :operation-id pending)})))

(defn record-valid?
  "Shape and safety check before any write: versioned, keyed, timestamped and
  free of signing material at any depth."
  [record]
  (letfn [(has-forbidden? [x]
            (cond (map? x) (or (some forbidden-keys (keys x)) (some has-forbidden? (vals x)))
                  (sequential? x) (some has-forbidden? x)
                  :else false))]
    (boolean (and (map? record)
                  (= schema-version (:schema-version record))
                  (string? (:record-id record))
                  (keyword? (:kind record))
                  (number? (:saved-at-ms record))
                  (not (has-forbidden? record))))))

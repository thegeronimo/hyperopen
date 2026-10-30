(ns hyperopen.api.trading.nonce-coordinator
  "Shared signer nonce reservation (HO-SPEC-014 v2.1 sections 2.2 item 3, 8.2).

  Hyperliquid nonces belong to the SIGNER: every action signed by the same
  agent key, for any subaccount or vault, draws from one sequence. A nonce is
  reserved BEFORE signing and is strictly greater than every nonce already
  reserved for that `[network signer]` in this origin.

  Cross-tab uniqueness does not rely on localStorage coherence (browsers
  propagate writes between renderer processes asynchronously). When the
  reserved nonce is within `max-sync-lead-ms` of the clock, the holder spins
  synchronously (immune to background-tab timer throttling) until the clock
  has passed it, so the next holder's `now` alone already exceeds it. When the
  nonce is further ahead (a cursor or retry floor ahead of the clock, or a clock
  stepped backwards) that argument does not hold: the lock is released at once
  and the reservation is `:degraded`. Manual trading proceeds; Vol Range sends
  refuse until the clock catches up.

  Storage failures never block trading: the in-memory cursor is used and the
  result reports `:coordination :degraded`, which callers that need cross-tab
  guarantees (Vol Range sends) refuse. The cursor never lives in IndexedDB, so a
  failed journal store cannot block cancellation (spec 7.4)."
  (:require [clojure.string :as str]
            [hyperopen.platform :as platform]
            [hyperopen.platform.locks :as locks]))

(def cursor-key-prefix "hyperopen:signer-nonce:")

(def max-cursor-lead-ms
  "A stored cursor further ahead of the clock than the venue's accepted window
  (T + 1 day) is ignored: honoring it would make every action fail."
  86400000)

(def max-sync-lead-ms
  "Largest nonce lead over the clock the holder waits out synchronously."
  2)

(def ^:private max-spin-real-ms
  "Real-time bound on the synchronous wait, whatever the injected clock does."
  10)

(defn network-for-options
  "The signing network implied by `:is-mainnet` (default mainnet)."
  [{:keys [is-mainnet]}]
  (if (false? is-mainnet) :hyperliquid-testnet :hyperliquid-mainnet))

(defn- normalize-signer [signer]
  (some-> signer str str/trim str/lower-case not-empty))

(defn cursor-key [network signer]
  (str cursor-key-prefix (name network) ":" (normalize-signer signer)))

(defn lock-name [network signer]
  (str "hyperopen:signer-nonce-lock:" (name network) ":" (normalize-signer signer)))

(defn- finite-int [x]
  (when (and (number? x) (js/isFinite x)) (js/Math.floor x)))

(defn usable-cursor
  "The cursor if it is plausible for `now-ms`, else nil (too far in the future)."
  [now-ms cursor]
  (let [c (finite-int cursor)]
    (when (and c (<= c (+ now-ms max-cursor-lead-ms))) c)))

(defn next-nonce
  "Pure: the smallest valid next nonce, `max(now, cursor + 1, floor + 1)`.
  `cursor` and `floor` may be nil."
  [now-ms cursor floor]
  (let [c (finite-int cursor)
        f (finite-int floor)]
    (cond-> (js/Math.floor now-ms)
      c (max (inc c))
      f (max (inc f)))))

;; In-memory mirror: still monotonic within this JS realm when storage fails.
(defonce ^:private memory-cursors (atom {}))

(defn- read-stored-cursor
  "{:value n-or-nil :ok? bool}; never throws (storage may be blocked)."
  [k]
  (try
    (let [raw (platform/local-storage-get k)
          n (when (string? raw) (js/parseInt raw 10))]
      {:value (when (and (number? n) (not (js/isNaN n))) n) :ok? true})
    (catch :default _ {:value nil :ok? false})))

(defn- write-stored-cursor!
  "True when persisted; never throws (quota, private mode)."
  [k nonce]
  (try (platform/local-storage-set! k (str nonce)) true
       (catch :default _ false)))

(defn- spin-until-clock-past!
  "Synchronously wait (bounded by real time) until the clock is past `nonce`.
  Returns true when it is."
  [nonce]
  (let [deadline (+ (.now js/Date) max-spin-real-ms)]
    (loop []
      (cond (> (platform/now-ms) nonce) true
            (>= (.now js/Date) deadline) false
            :else (recur)))))

(defn reserve-nonce!
  "Promise of {:nonce n :network kw :signer s :coordination kw} where
  `:coordination` is :web-locks, :in-tab-only (no Web Locks) or :degraded
  (storage failed). `floor` is an extra lower bound (the session cursor, or a
  rejected nonce on retry). Rejects only when the signer is unknown."
  [{:keys [network signer floor]}]
  (let [network (or network :hyperliquid-mainnet)
        signer* (normalize-signer signer)]
    (if-not signer*
      (js/Promise.reject (js/Error. "Cannot reserve a nonce without a signer address."))
      (let [k (cursor-key network signer*)]
        (locks/with-exclusive-lock-kind!
         (lock-name network signer*)
         (fn [lock-kind]
           (let [now (platform/now-ms)
                 {stored :value read-ok? :ok?} (read-stored-cursor k)
                 cursor (some->> [(usable-cursor now stored) (usable-cursor now (get @memory-cursors k))]
                                 (remove nil?) seq (apply max))
                 nonce (next-nonce now cursor (usable-cursor now floor))
                 _ (swap! memory-cursors update k #(if (and % (> % nonce)) % nonce))
                 write-ok? (write-stored-cursor! k nonce)
                 ;; Hold the lock until the clock passes the nonce, so the next
                 ;; holder in any tab gets a larger one from the clock alone.
                 clock-past? (and (<= (- nonce now) max-sync-lead-ms) (spin-until-clock-past! nonce))
                 coordination (if (and read-ok? write-ok? clock-past?) lock-kind :degraded)]
             {:nonce nonce :network network :signer signer* :coordination coordination})))))))

(defn reset-memory-cursors!
  "Test helper."
  []
  (reset! memory-cursors {}))

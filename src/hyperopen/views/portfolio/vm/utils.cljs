(ns hyperopen.views.portfolio.vm.utils
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.portfolio.metrics.parsing :as parsing]))

(defn optional-number [value]
  (parsing/optional-number value))

(defn number-or-zero [value]
  (if (number? value) value 0))

(defn finite-number? [value]
  (parsing/finite-number? value))

(defn canonical-summary-key
  [value]
  (when value
    (let [v (name value)]
      (cond
        (or (= v "perps") (= v "perp")) :perps
        (or (= v "spot")) :spot
        (or (= v "vaults") (= v "vault")) :vaults
        :else :all))))

(defn max-drawdown-ratio
  [summary]
  (when summary
    (let [max-drawdown (or (:maxDrawdown summary)
                           (get-in summary [:metrics :maxDrawdown]))]
      (when (finite-number? max-drawdown)
        (min 0 max-drawdown)))))

(defn normalize-metric-token-map
  [state]
  (let [account-info (get-in state [:market-data :account-info])
        effective-address (account-context/effective-account-address state)]
    (str (hash account-info) "-" (hash effective-address))))

(defn metric-token
  [state request-data]
  (str (normalize-metric-token-map state) "-" (hash request-data)))

(defn benchmark-candle-slots
  "The candle store slots the benchmark pipeline actually reads for one render.

  `benchmark-computation-context` used to cache-key on the identity of the WHOLE
  `[:candles]` map. The trades websocket handler writes
  `[:candles active-asset timeframe]` on a buffered interval regardless of which
  route is on screen, so on /portfolio that identity check failed roughly twice a
  second and the entire benchmark pipeline re-derived for candle data this page
  never draws. Keying on just these slots makes the cache immune to writes it
  does not care about.

  Vault- and trader-backed benchmarks resolve through separate store buckets and
  simply contribute a stable nil here."
  [state coins interval]
  (mapv (fn [coin]
          (get-in state [:candles coin interval]))
        (or coins [])))

(defn identical-slots?
  "Element-wise identity over two slot vectors.

  Value equality would defeat the purpose: the slots hold candle row vectors
  with hundreds of entries, and walking them is the work the cache exists to
  avoid. Every writer replaces the slot object, so identity is the correct and
  constant-time test."
  [a b]
  (let [a (or a [])
        b (or b [])
        n (count a)]
    (and (== n (count b))
         (loop [idx 0]
           (cond
             (>= idx n) true
             (identical? (nth a idx) (nth b idx)) (recur (inc idx))
             :else false)))))

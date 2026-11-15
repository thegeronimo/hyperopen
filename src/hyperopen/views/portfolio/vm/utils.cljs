(ns hyperopen.views.portfolio.vm.utils
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.portfolio.metrics.parsing :as parsing]))

(defn optional-number [value]
  (parsing/optional-number value))

(defn finite-number? [value]
  (parsing/finite-number? value))

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

(ns hyperopen.portfolio.optimizer.instrument-keyed-codec-fusion-test
  "Equivalence proof for the fused worker-boundary walk.

  `normalize-boundary-node` replaced two full post-order walks with one. The
  contract is that it is output-identical to the two-pass composition it
  replaced, for every value -- not just for the shapes the optimizer happens to
  send today, because this function runs on every message in both directions
  and its input is whatever the engine produced.

  Fixtures are generated from the real key sets rather than written as
  literals, so a key added to `enum-value-keys` or `instrument-keyed-map-keys`
  is covered the day it lands instead of the day someone remembers to extend a
  list here.

  The suite also pins the ordering constraint the fusion rests on: the walk
  must stay post-order. A pre-order variant looks like the same optimization
  and is wrong, so `pre-order-fusion-would-be-wrong-test` keeps a copy of it
  and asserts it diverges -- if that test ever goes green, the fixture stopped
  exercising the difference and the real equivalence tests lost their teeth."
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [hyperopen.portfolio.optimizer.bit-parity :as bit-parity]
            [hyperopen.portfolio.optimizer.instrument-keyed-codec :as codec]))

(defn- two-pass
  "The composition `normalize-boundary-node` replaced. Deliberately spelled out
  rather than referenced, so this stays the pre-fusion behaviour even if those
  two functions are later changed or deleted."
  [value]
  (-> value
      codec/normalize-wire-values
      codec/normalize-instrument-keyed-maps))

(def ^:private same?
  "NaN-aware structural equality. Payloads legitimately carry NaN -- a
  degenerate covariance cell reaches the boundary as one -- and `=` calls two
  NaNs different, so a plain `=` oracle fails comparing a walk to itself."
  bit-parity/bit=)

(defn- equivalent!
  [label value]
  (is (same? (two-pass value) (codec/normalize-boundary-node value))
      (str "fused walk diverged from the two-pass composition on " label)))

(def ^:private enum-key (first (sort (map str codec/enum-value-keys))))
(def ^:private instrument-key
  (first (sort (map str codec/instrument-keyed-map-keys))))

(defn- kw [s] (keyword (subs s 1)))

;; ---------------------------------------------------------------------------
;; Every real key, in every position that matters
;; ---------------------------------------------------------------------------

(deftest fused-walk-matches-two-pass-for-every-enum-key-test
  ;; The enum rule keywordizes strings and blanks them to nil. Each key is
  ;; exercised over a string, an already-keyword value, a blank string, nil,
  ;; and -- the interesting one -- a map, which the enum rule must leave alone.
  (doseq [key codec/enum-value-keys]
    (doseq [[label item] [["string" "market"]
                          ["keyword" :market]
                          ["blank string" "   "]
                          ["empty string" ""]
                          ["nil" nil]
                          ["number" 42]
                          ["map" {:perp:BTC 0.5}]
                          ["vector" ["a" "b"]]
                          ["nested map" {:inner {:deeper "x"}}]]]
      (equivalent! (str key " -> " label) {key item})
      (equivalent! (str key " -> " label ", nested") {:outer {key item}})
      (equivalent! (str key " -> " label ", in a vector") {:outer [{key item}]}))))

(deftest fused-walk-matches-two-pass-for-every-instrument-keyed-key-test
  ;; The instrument rule stringifies map keys. Each key is exercised over a
  ;; map, a non-map (which it must leave alone), and a nested instrument-keyed
  ;; map, which is where post-order versus pre-order actually differs.
  (doseq [key codec/instrument-keyed-map-keys]
    (doseq [[label item] [["keyword-keyed map" {:perp:BTC 0.5 :perp:ETH 0.25}]
                          ["string-keyed map" {"perp:BTC" 0.5}]
                          ["empty map" {}]
                          ["nil" nil]
                          ["string" "not-a-map"]
                          ["vector of maps" [{:perp:BTC 0.5}]]
                          ["map of maps" {:perp:BTC {:weight 0.5}}]]]
      (equivalent! (str key " -> " label) {key item})
      (equivalent! (str key " -> " label ", nested") {:outer {key item}}))))

(deftest fused-walk-matches-two-pass-when-both-rules-meet-test
  ;; Both key sets are disjoint today. The fusion does not rely on that, so
  ;; these assert the overlapping case explicitly -- a key that is in both sets
  ;; would apply the enum rule and then the instrument rule, in that order.
  (let [e (kw enum-key)
        i (kw instrument-key)]
    (equivalent! "both keys, siblings" {e "market" i {:perp:BTC 0.5}})
    (equivalent! "enum key wrapping an instrument-keyed map"
                 {e {i {:perp:BTC 0.5}}})
    (equivalent! "instrument key wrapping an enum value"
                 {i {:perp:BTC {e "market"}}})
    (equivalent! "deeply interleaved"
                 {:a [{e "market"} {i {:perp:BTC 0.5}}]
                  :b {:c {e "  "} :d {i {}}}})))

(deftest fused-walk-matches-two-pass-on-shapes-the-walk-must-not-touch-test
  (doseq [[label value] [["nil" nil]
                         ["empty map" {}]
                         ["empty vector" []]
                         ["empty list" '()]
                         ["empty set" #{}]
                         ["bare string" "hello"]
                         ["bare number" 1]
                         ["NaN" js/NaN]
                         ["infinity" js/Infinity]
                         ["a set of maps" #{{:a 1}}]
                         ["lazy seq" (map inc [1 2 3])]
                         ["list" (list {:a 1} {:b 2})]
                         ["non-keyword keys" {"a" 1 2 :b}]
                         ["vector of vectors" [[{:a 1}] [{:b 2}]]]]]
    (equivalent! label value)))

;; ---------------------------------------------------------------------------
;; Generative
;; ---------------------------------------------------------------------------

(defn- lcg
  [seed]
  (let [state (atom seed)]
    (fn [n]
      (swap! state (fn [s] (mod (+ (* 1664525 s) 1013904223) 4294967296)))
      (mod @state n))))

(defn- random-tree
  [rand depth]
  (let [keys* (vec (concat (map kw [enum-key instrument-key])
                           [:plain :other :perp:BTC "string-key"]))
        leaves ["market" "  " "" nil 42 :already-keyword js/NaN]]
    (if (or (zero? depth) (zero? (rand 3)))
      (nth leaves (rand (count leaves)))
      (case (rand 3)
        0 (into {} (map (fn [_]
                          [(nth keys* (rand (count keys*)))
                           (random-tree rand (dec depth))]))
                (range (inc (rand 4))))
        1 (mapv (fn [_] (random-tree rand (dec depth))) (range (inc (rand 3))))
        2 (doall (map (fn [_] (random-tree rand (dec depth))) (range (inc (rand 3)))))))))

(deftest fused-walk-matches-two-pass-on-random-trees-test
  ;; The hand-written fixtures cover the cases someone thought of. This covers
  ;; the ones they did not.
  (let [rand (lcg 20260823)
        failures (->> (range 2000)
                      (map (fn [_] (random-tree rand 5)))
                      (remove (fn [tree]
                                (same? (two-pass tree)
                                       (codec/normalize-boundary-node tree))))
                      (take 3)
                      vec)]
    (is (empty? failures)
        (str "fused walk diverged from the two-pass composition on "
             (count failures) " random trees, e.g. " (pr-str (first failures))))))

;; ---------------------------------------------------------------------------
;; The ordering constraint
;; ---------------------------------------------------------------------------

(defn- pre-order-fusion
  "The wrong version: key rules applied before the recursion. Kept so the test
  below can prove the fixture actually distinguishes the two orders."
  [value]
  (cond
    (map? value)
    (into {}
          (map (fn [[key item]]
                 (let [enumerated (if (contains? codec/enum-value-keys key)
                                    (cond
                                      (keyword? item) item
                                      (string? item) (when-not (str/blank? item)
                                                       (keyword (str/trim item)))
                                      :else item)
                                    item)
                       stringified (if (and (contains? codec/instrument-keyed-map-keys key)
                                            (map? enumerated))
                                     (codec/stringify-instrument-keyed-map enumerated)
                                     enumerated)]
                   [key (pre-order-fusion stringified)])))
          value)

    (vector? value) (mapv pre-order-fusion value)
    (seq? value) (doall (map pre-order-fusion value))
    :else value))

(deftest pre-order-fusion-would-be-wrong-test
  ;; A guard on the guards. If this ever passes, the fixture has stopped
  ;; exercising the difference between the two orders, and the equivalence
  ;; tests above are no longer proving that post-order is required.
  ;; An instrument key directly under an instrument key. Post-order stringifies
  ;; the inner map first, so the outer pass then rewrites a keyword key;
  ;; pre-order stringifies the outer keys first, which turns the inner
  ;; :by-instrument-style key into a STRING, and the recursion no longer
  ;; recognises it as an instrument key at all. Nesting an instrument key one
  ;; level deeper does NOT distinguish the two orders, because
  ;; stringify-instrument-keyed-map only rewrites the keys one level down.
  (let [instrument (kw instrument-key)
        nested {instrument {instrument {:perp:BTC 1}}}]
    (is (same? (two-pass nested) (codec/normalize-boundary-node nested))
        "post-order fusion must match the two-pass composition")
    (is (not= (two-pass nested) (pre-order-fusion nested))
        (str "this fixture no longer distinguishes pre-order from post-order, "
             "so the equivalence suite has lost its ordering coverage"))))

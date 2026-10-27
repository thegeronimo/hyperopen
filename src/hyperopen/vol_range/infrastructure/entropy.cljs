(ns hyperopen.vol-range.infrastructure.entropy
  "Effect-boundary entropy for Vol Range identities (spec 7.5). Uses the
  browser's cryptographic RNG only; there is no Math.random fallback."
  (:require [hyperopen.vol-range.domain.cloid :as cloid]))

(defn- crypto-obj []
  (let [c (.-crypto js/globalThis)]
    (when (and c (fn? (.-getRandomValues c))) c)))

(defn random-cloid-suffix!
  "24 lowercase hex digits (96 bits) from crypto.getRandomValues. Throws when
  cryptographic entropy is unavailable, so no CLOID is ever minted without it."
  []
  (if-let [c (crypto-obj)]
    (let [buf (js/Uint8Array. 12)]
      (.getRandomValues c buf)
      (cloid/bytes->hex (array-seq buf)))
    (throw (js/Error. "Cryptographic randomness is unavailable; cannot create order ids."))))

(defn random-cloid-suffixes!
  [n]
  (vec (repeatedly n random-cloid-suffix!)))

(defn random-id!
  "128-bit random hex id for operations and attempts."
  [prefix]
  (str prefix "-" (random-cloid-suffix!) (subs (random-cloid-suffix!) 0 8)))

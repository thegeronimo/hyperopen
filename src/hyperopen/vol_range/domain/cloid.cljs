(ns hyperopen.vol-range.domain.cloid
  "Vol Range client order ids (HO-SPEC-014 v2.1 section 7.5).

  A CLOID is the feature-family prefix 0x484f5652 (\"HOVR\") followed by a
  96-bit random suffix: exactly 128 bits, `0x` plus 32 lowercase hex digits.
  This namespace is pure: the suffix is generated with browser cryptographic
  entropy at the effect boundary and injected here. The prefix identifies a
  feature family only; it never proves ownership or a safe cancellation scope."
  (:require [clojure.string :as str]))

(def prefix "0x484f5652")
(def suffix-hex-length 24)

(defn valid-suffix? [s]
  (boolean (and (string? s) (re-matches #"[0-9a-f]{24}" s))))

(defn cloid
  "The CLOID for an injected 24-hex-digit suffix; nil when the suffix is invalid."
  [suffix]
  (let [s (some-> suffix str str/lower-case)]
    (when (valid-suffix? s) (str prefix s))))

(defn valid-cloid? [x]
  (boolean (and (string? x) (re-matches #"0x[0-9a-f]{32}" x))))

(defn vol-range-cloid?
  "True for a well-formed CLOID carrying the Vol Range family prefix."
  [x]
  (let [s (some-> x str str/lower-case)]
    (boolean (and (valid-cloid? s) (str/starts-with? s prefix)))))

(defn bytes->hex
  "Lowercase hex for a sequence of byte values 0-255."
  [bytes]
  (apply str (map (fn [b] (let [h (.toString (bit-and b 255) 16)]
                            (if (= 1 (count h)) (str "0" h) h)))
                  bytes)))

(defn assign-cloids
  "Assign one CLOID per leg from injected suffixes, in leg order. Returns
  {:legs legs-with-cloids} or {:error :cloid/suffix-invalid|:cloid/duplicate}."
  [legs suffixes]
  (let [cloids (mapv cloid suffixes)]
    (cond
      (or (not= (count legs) (count suffixes)) (some nil? cloids)) {:error :cloid/suffix-invalid}
      (not= (count cloids) (count (distinct cloids))) {:error :cloid/duplicate}
      :else {:legs (mapv #(assoc %1 :cloid %2) legs cloids)})))

(ns hyperopen.domain.market.instrument
  (:require [clojure.string :as str]))

(defn- non-blank-string [value]
  (let [s (when (some? value) (str value))
        trimmed (some-> s str/trim)]
    (when (seq trimmed) trimmed)))

(defn base-symbol-from-value [value]
  (let [text (non-blank-string value)]
    (cond
      (and text (str/includes? text "/"))
      (non-blank-string (first (str/split text #"/" 2)))

      (and text (str/includes? text ":"))
      (non-blank-string (second (str/split text #":" 2)))

      (and text (str/includes? text "-"))
      (non-blank-string (first (str/split text #"-" 2)))

      :else
      text)))

(defn quote-symbol-from-value [value]
  (let [text (non-blank-string value)]
    (cond
      (and text (str/includes? text "/"))
      (non-blank-string (second (str/split text #"/" 2)))

      (and text (str/includes? text "-"))
      (non-blank-string (second (str/split text #"-" 2)))

      :else
      nil)))

(defn resolve-base-symbol
  ([instrument market]
   (resolve-base-symbol instrument market "Asset"))
  ([instrument market fallback]
   (let [market* (or market {})]
     (or (non-blank-string (:base market*))
         (base-symbol-from-value instrument)
         (base-symbol-from-value (:coin market*))
         (base-symbol-from-value (:symbol market*))
         fallback))))

(defn resolve-quote-symbol
  ([instrument market]
   (resolve-quote-symbol instrument market "USDC"))
  ([instrument market fallback]
   (let [market* (or market {})]
     (or (non-blank-string (:quote market*))
         (quote-symbol-from-value (:symbol market*))
         (quote-symbol-from-value instrument)
         fallback))))

(defn spot-instrument?
  [instrument market]
  (or (= :spot (:market-type market))
      (and (string? instrument)
           (str/includes? instrument "/"))))

(defn hip3-instrument?
  [instrument market]
  (let [spot? (spot-instrument? instrument market)]
    (or (some? (:dex market))
        (and (string? instrument)
             (str/includes? instrument ":")
             (not spot?)))))

(defn infer-market-type
  [instrument market]
  (or (:market-type market)
      (if (spot-instrument? instrument market) :spot :perp)))

(defn market-identity
  [instrument market]
  (let [spot? (spot-instrument? instrument market)
        hip3? (hip3-instrument? instrument market)]
    {:base-symbol (resolve-base-symbol instrument market)
     :quote-symbol (resolve-quote-symbol instrument market)
     :spot? (boolean spot?)
     :hip3? (boolean hip3?)
     :read-only? (boolean (or spot? hip3?))}))

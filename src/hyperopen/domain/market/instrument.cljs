(ns hyperopen.domain.market.instrument
  (:require [clojure.string :as str]))

(defn- non-blank-string [value]
  (let [s (when (some? value) (str value))
        trimmed (some-> s str/trim)]
    (when (seq trimmed) trimmed)))

(defn- canonical-market-type [market]
  (let [market* (or market {})
        market-type (:market-type market*)
        category (:category market*)
        normalized-market-type (cond
                                 (keyword? market-type) market-type
                                 (string? market-type) (keyword (str/lower-case market-type))
                                 :else nil)
        normalized-category (cond
                              (keyword? category) category
                              (string? category) (keyword (str/lower-case category))
                              :else nil)]
    (or normalized-market-type
        (case normalized-category
          :spot :spot
          :perp :perp
          nil))))

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
  (let [market-type (canonical-market-type market)]
    (cond
      (= :spot market-type) true
      (= :perp market-type) false
      :else
      (and (string? instrument)
           (str/includes? instrument "/")))))

(defn hip3-instrument?
  [instrument market]
  (let [spot? (spot-instrument? instrument market)
        market-type (canonical-market-type market)]
    (or (some? (:dex market))
        (= :hip3 market-type)
        (and (string? instrument)
             (str/includes? instrument ":")
             (not spot?)))))

(defn infer-market-type
  [instrument market]
  (or (canonical-market-type market)
      (if (spot-instrument? instrument market) :spot :perp)))

(defn market-identity
  [instrument market]
  (let [spot? (spot-instrument? instrument market)
        hip3? (hip3-instrument? instrument market)]
    {:base-symbol (resolve-base-symbol instrument market)
     :quote-symbol (resolve-quote-symbol instrument market)
     :spot? (boolean spot?)
     :hip3? (boolean hip3?)
     :read-only? (boolean spot?)}))

(ns hyperopen.portfolio.optimizer.application.history-loader.instruments
  (:require [clojure.string :as str]))

(def ^:private vault-instrument-prefix
  "vault:")

(defn non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(declare market-type)

(defn- display-pair-coin?
  [coin base quote symbol]
  (let [dash-pair (when (and base quote) (str base "-" quote))
        slash-pair (when (and base quote) (str base "/" quote))]
    (boolean
     (and base
          (or (and symbol
                   (= coin symbol)
                   (or (str/includes? symbol "-")
                       (str/includes? symbol "/")))
              (= coin dash-pair)
              (= coin slash-pair))))))

(defn normalize-coin
  [instrument]
  (let [coin (non-blank-text (or (:coin instrument)
                                 (:asset instrument)))
        base (non-blank-text (:base instrument))
        quote (non-blank-text (:quote instrument))
        symbol (non-blank-text (:symbol instrument))]
    (if (and (= :perp (market-type instrument))
             (display-pair-coin? coin base quote symbol))
      base
      coin)))

(defn normalize-instrument-id
  [instrument]
  (or (non-blank-text (:instrument-id instrument))
      (normalize-coin instrument)))

(defn normalize-vault-address
  [value]
  (some-> value str str/trim str/lower-case not-empty))

(defn vault-address-from-value
  [value]
  (let [text (some-> value str str/trim)
        lower (some-> text str/lower-case)]
    (when (and (seq lower)
               (str/starts-with? lower vault-instrument-prefix))
      (normalize-vault-address (subs text (count vault-instrument-prefix))))))

(defn market-type
  [instrument]
  (let [value (:market-type instrument)]
    (cond
      (keyword? value) value
      (string? value) (keyword (str/lower-case (str/trim value)))
      :else nil)))

(defn perp-instrument?
  [instrument]
  (= :perp (market-type instrument)))

(defn vault-address
  [instrument]
  (or (normalize-vault-address (:vault-address instrument))
      (vault-address-from-value (:instrument-id instrument))
      (vault-address-from-value (:coin instrument))))

(defn vault-instrument?
  [instrument]
  (boolean
   (or (= :vault (market-type instrument))
       (vault-address-from-value (:instrument-id instrument))
       (vault-address-from-value (:coin instrument)))))

(defn instrument-warning-context
  [instrument]
  (cond-> {:instrument-id (normalize-instrument-id instrument)}
    (market-type instrument) (assoc :market-type (market-type instrument))))

(defn- group-instruments-by-coin
  [universe]
  (reduce (fn [acc instrument]
            (if-let [coin (normalize-coin instrument)]
              (update-in acc [:by-coin coin] (fnil conj []) instrument)
              (update acc :warnings conj
                      (assoc (instrument-warning-context instrument)
                             :code :missing-history-coin))))
          {:by-coin {}
           :warnings []}
          (or universe [])))

(defn sorted-coin-groups
  [universe predicate]
  (let [{:keys [by-coin warnings]} (group-instruments-by-coin universe)
        ordered-coins (->> universe
                           (filter predicate)
                           (keep normalize-coin)
                           distinct
                           vec)]
    {:groups (mapv (fn [coin]
                     [coin (vec (get by-coin coin))])
                   ordered-coins)
     :warnings warnings}))

(defn- group-vault-instruments-by-address
  [universe]
  (reduce (fn [{:keys [by-address ordered-addresses] :as acc} instrument]
            (if-not (vault-instrument? instrument)
              acc
              (if-let [address (vault-address instrument)]
                (cond-> (update-in acc [:by-address address] (fnil conj []) instrument)
                  (not (contains? by-address address))
                  (update :ordered-addresses conj address))
                (update acc :warnings conj
                        (assoc (instrument-warning-context instrument)
                               :code :missing-vault-address)))))
          {:by-address {}
           :ordered-addresses []
           :warnings []}
          (or universe [])))

(defn sorted-vault-groups
  [universe]
  (let [{:keys [by-address ordered-addresses warnings]}
        (group-vault-instruments-by-address universe)]
    {:groups (mapv (fn [address]
                     [address (vec (get by-address address))])
                   ordered-addresses)
     :warnings warnings}))

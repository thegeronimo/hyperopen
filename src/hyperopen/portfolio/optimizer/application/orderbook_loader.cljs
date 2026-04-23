(ns hyperopen.portfolio.optimizer.application.orderbook-loader
  (:require [clojure.string :as str]))

(def default-stale-after-ms
  15000)

(def default-fallback-bps
  25)

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- needed-row?
  [row]
  (true? (:needs-cost-estimate? row)))

(defn- required-coins
  [rows]
  (->> rows
       (filter needed-row?)
       (keep (comp non-blank-text :coin))
       distinct
       vec))

(defn- missing-coin-warnings
  [rows]
  (->> rows
       (filter needed-row?)
       (filter #(nil? (non-blank-text (:coin %))))
       (mapv (fn [_] {:code :missing-orderbook-coin}))))

(defn- best-bid
  [book]
  (or (get-in book [:render :best-bid])
      (first (:bids book))))

(defn- best-ask
  [book]
  (or (get-in book [:render :best-ask])
      (first (:asks book))))

(defn- book-age-ms
  [book now-ms]
  (when (and (number? now-ms)
             (number? (:timestamp book)))
    (- now-ms (:timestamp book))))

(defn- stale-book?
  [book now-ms stale-after-ms]
  (if-let [age-ms (book-age-ms book now-ms)]
    (> age-ms stale-after-ms)
    false))

(defn orderbook-cost-context
  [state coin opts]
  (let [coin* (non-blank-text coin)
        opts* (or opts {})
        book (get-in state [:orderbooks coin*])
        now-ms (:now-ms opts*)
        stale-after-ms (or (:stale-after-ms opts*) default-stale-after-ms)
        fallback-bps (or (:fallback-bps opts*) default-fallback-bps)]
    (if (and coin* book)
      (let [stale? (stale-book? book now-ms stale-after-ms)
            age-ms (book-age-ms book now-ms)]
        (cond-> {:coin coin*
                 :source (if stale?
                           :stale-orderbook
                           :live-orderbook)
                 :best-bid (best-bid book)
                 :best-ask (best-ask book)
                 :stale? stale?}
          (and stale? (number? age-ms)) (assoc :age-ms age-ms)))
      {:coin coin*
       :source :fallback-cost-assumption
       :best-bid nil
       :best-ask nil
       :stale? true
       :fallback-bps fallback-bps})))

(defn build-orderbook-subscription-plan
  [state rows opts]
  (let [opts* (or opts {})
        coins (required-coins rows)
        contexts (into {}
                       (map (fn [coin]
                              [coin (dissoc (orderbook-cost-context state coin opts*) :coin)]))
                       coins)
        coins-to-subscribe (->> coins
                                (filter (fn [coin]
                                          (contains? #{:stale-orderbook
                                                       :fallback-cost-assumption}
                                                     (get-in contexts [coin :source]))))
                                vec)
        missing-orderbook-warnings (->> coins-to-subscribe
                                        (filter #(= :fallback-cost-assumption
                                                    (get-in contexts [% :source])))
                                        (mapv (fn [coin]
                                                {:code :missing-orderbook
                                                 :coin coin})))]
    {:coins-to-subscribe coins-to-subscribe
     :effects (mapv (fn [coin]
                      [:effects/subscribe-orderbook coin])
                    coins-to-subscribe)
     :available-by-coin contexts
     :warnings (vec (concat (missing-coin-warnings rows)
                            missing-orderbook-warnings))}))

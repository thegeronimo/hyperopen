(ns hyperopen.outcomes.receipt
  "The receipt a settled outcome market leaves behind: what you held, what it
   cost, what it paid, the settlement fee, what you received and your profit.

   Pure. Cost and shares come from the account's own fills (`from-fills`); the
   settlement result (1.00 for a winning side, 0 for a losing one, a fraction
   for a split) is an input: once a contract settles the feed drops it, so the
   caller must supply the result from wherever it is recorded. Without a
   result the receipt says so instead of guessing."
  (:require [clojure.string :as str]
            [hyperopen.outcomes.domain.fees :as fees]
            [hyperopen.outcomes.domain.quotes :as quotes]
            [hyperopen.outcomes.names :as outcome-names]))

(defn- num*
  [value]
  (let [n (cond (number? value) value
                (string? value) (js/parseFloat value)
                :else js/NaN)]
    (when (and (number? n) (js/isFinite n)) n)))

(defn from-fills
  "Net shares held and what they cost, from fills on `coin`: buys add shares and
   cost, sells remove shares at the running average price.
   -> {:shares :cost :avg-price}"
  [fills coin]
  (let [coin* (outcome-names/canonical-coin coin)
        rows (->> fills
                  (filter #(= coin* (outcome-names/canonical-coin (some-> (:coin %) str))))
                  (sort-by #(or (num* (:time %)) 0)))
        {:keys [shares cost]}
        (reduce (fn [{:keys [shares cost]} fill]
                  (let [sz (or (num* (:sz fill)) 0)
                        px (or (num* (:px fill)) 0)
                        buy? (contains? #{"B" "BUY"} (some-> (:side fill) str str/upper-case))]
                    (if buy?
                      {:shares (+ shares sz) :cost (+ cost (* sz px))}
                      (let [avg (if (pos? shares) (/ cost shares) 0)]
                        {:shares (max 0 (- shares sz))
                         :cost (max 0 (- cost (* sz avg)))}))))
                {:shares 0 :cost 0}
                rows)]
    {:shares shares
     :cost cost
     :avg-price (when (pos? shares) (/ cost shares))}))

(defn result-text
  "\"Settled Yes\", \"Settled No\", or \"A split result: settled at 62%\"."
  [{:keys [payout-per-share side-name]}]
  (cond
    (nil? payout-per-share) "Result not available"
    (== 1 payout-per-share) (str "Settled " (or side-name "Yes"))
    (zero? payout-per-share) (str "Settled " (if (= "No" side-name) "Yes" "No"))
    :else (str "A split result: settled at " (js/Math.round (* 100 payout-per-share)) "%")))

(defn receipt
  "Receipt model for a settled position.
   Input: {:title :side-name :side-label :shares :cost :payout-per-share
           :venue :rates :next {:title :coin}}
   -> {:title :result :held :cost :payout :fee-rate-text :fee :received :profit
       :fee-known? :result-known? :next}"
  [{:keys [title side-name side-label shares cost payout-per-share venue rates] :as input}]
  (let [shares* (or (num* shares) 0)
        cost* (or (num* cost) 0)
        known? (some? (num* payout-per-share))
        settle (fees/settlement {:shares shares* :payout-per-share payout-per-share
                                 :venue venue :rates rates :cost cost*})
        avg (when (pos? shares*) (/ cost* shares*))]
    (cond-> {:title title
             :result (result-text {:payout-per-share (num* payout-per-share) :side-name side-name})
             :result-known? known?
             :held (str (js/Math.floor shares*) " " (or side-label side-name "shares")
                        (when avg (str " · average " (quotes/cents-text avg))))
             :cost cost*
             :fee-known? (:fee-known? settle)
             :next (:next input)}
      known? (assoc :payout (:gross settle))
      (and known? (:fee-known? settle))
      (assoc :fee (:fee settle)
             :fee-rate-text (fees/rate-text (:rate settle))
             :received (:net settle)
             :profit (:profit settle)))))

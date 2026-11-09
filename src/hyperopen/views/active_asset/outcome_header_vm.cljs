(ns hyperopen.views.active-asset.outcome-header-vm
  "What the trade header says about an outcome market: the event, each side's
   chance, the selected side's price in cents, the change in points, when it
   happens, and the rules."
  (:require [clojure.string :as str]
            [hyperopen.asset-selector.outcome-listing :as listing]
            [hyperopen.outcomes.domain.book :as book]
            [hyperopen.outcomes.domain.clock :as clock]
            [hyperopen.outcomes.domain.quotes :as quotes]))

(def ^:private clock-headings
  {:starts "Starts"
   :settles "Settles"
   :ends "Ends"
   :decision "Decision"
   :resolves "Resolves"
   :listing "Listing"})

(def ^:private category-titles
  {:sports "Sports"
   :crypto "Crypto"
   :stocks "Stocks & commodities"
   :economics "Economics"
   :companies "Companies"})

(defn market-sides
  "The contract's sides; a partial market (a cold-start cache entry) gets a
   Yes side built from its own price fields so the header never goes blank."
  [market]
  (let [sides (vec (:outcome-sides market))]
    (if (seq sides)
      sides
      [{:side-index 0 :side-name "Yes" :coin (:coin market) :mark (:mark market) :mid (:mid market)
        :prevDayRaw (:prevDayRaw market) :volume24h (:volume24h market)}
       {:side-index 1 :side-name "No"}])))

(defn market-clock
  [market]
  (or (:outcome-clock market)
      (when (number? (:expiry-ms market))
        {:kind :settles :at-ms (:expiry-ms market)})))

(defn selected-side-index
  [market]
  (or (:outcome-side-index market)
      (some (fn [side]
              (when (= (:coin market) (:coin side))
                (:side-index side)))
            (:outcome-sides market))
      0))

(defn venue-label
  [market]
  (or (:outcome-venue market)
      (when (seq (:outcome-venues market))
        (str/join " · " (:outcome-venues market)))
      "Hyperliquid"))

(defn kicker
  [market]
  (str/join " · " (keep identity [(get category-titles (:outcome-category market))
                                  (:outcome-competition market)])))

(defn side-quotes
  "[side-0 quote, side-1 quote] for the active contract. The live book of the
   selected side decides when it has arrived; the other side is its mirror, so
   the two chances always add up to 100%."
  [market orderbook]
  (let [sides (market-sides market)
        index (selected-side-index market)
        active-side (get sides index)
        active-quote (or (book/book-quote orderbook (:mark active-side))
                         (quotes/side-quote active-side))
        other-quote (if (contains? #{:live :last} (:state active-quote))
                      (quotes/mirror-quote active-quote)
                      (quotes/side-quote (get sides (- 1 index))))]
    (if (= 1 index)
      [other-quote active-quote]
      [active-quote other-quote])))

(defn- side-labels
  [market]
  (let [[a b] (map :side-name (market-sides market))]
    [(or a "Yes") (or b "No")]))

(defn- change-direction
  [delta]
  (cond
    (not (number? delta)) :flat
    (pos? delta) :up
    (neg? delta) :down
    :else :flat))

(defn header-model
  [market orderbook now-ms]
  (let [index (selected-side-index market)
        [quote-a quote-b :as quotes*] (side-quotes market orderbook)
        [label-a label-b] (side-labels market)
        option-label (when (seq (:question-options market))
                       (:selected-outcome-option-label market))
        active-label (get [label-a label-b] index)
        active-quote (get quotes* index)
        delta (quotes/change-24h (first (market-sides market)))
        clock (market-clock market)
        clock-kind (:kind clock)
        clock-view (clock/clock-view clock now-ms)]
    {:title (or (:title market) (:symbol market))
     :kicker (kicker market)
     :venue (venue-label market)
     :chances (cond
                option-label [{:label option-label :text (quotes/quote-text quote-a)}]
                (= :head-to-head (:outcome-shape market)) [{:label label-a :text (quotes/quote-text quote-a)}
                                                          {:label label-b :text (quotes/quote-text quote-b)}]
                :else [{:label label-a :text (quotes/quote-text quote-a)}])
     :price-label (if option-label (str active-label " · " option-label) active-label)
     :price-text (if (contains? #{:live :last} (:state active-quote))
                   (str (quotes/cents-text (:price active-quote))
                        (when (= :last (:state active-quote)) " last"))
                   (quotes/quote-text active-quote))
     :change-label (if (= :daily (:outcome-subcategory market)) "Since open" "24h")
     :change-text (or (quotes/points-text delta) "—")
     :change-direction (change-direction delta)
     :clock-heading (get clock-headings clock-kind "Ends")
     :clock-label (or (:label clock-view) "—")
     :clock-absolute (:absolute clock-view)
     :clock-phase (:phase clock-view)
     :volume-text (or (quotes/compact-usd-text (listing/event-volume market)) "—")
     :open-interest-text (or (quotes/compact-usd-text (:openInterest market)) "—")}))

(defn tooltip-model
  "Rules panel content for the market name hover."
  [market]
  (when (= :outcome (:market-type market))
    (let [rules (or (:outcome-rules market) (:outcome-details market))
          [label-a label-b] (side-labels market)]
      (when (seq rules)
        {:title (or (:title market) "Rules")
         :summary rules
         :settlement-label rules
         :yes-label label-a
         :no-label label-b
         :yes-payout-label "$1.00"
         :no-payout-label "$0.00"
         :footer-label (str "Payouts are in " (or (:quote market) "USDC")
                            ". Venue: " (venue-label market) ".")}))))

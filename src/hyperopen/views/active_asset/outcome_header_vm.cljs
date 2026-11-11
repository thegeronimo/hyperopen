(ns hyperopen.views.active-asset.outcome-header-vm
  "What the trade header says about an outcome market: the event, each side's
   chance, the selected side's price in cents, the change in points, when it
   happens, and the rules."
  (:require [clojure.string :as str]
            [hyperopen.asset-selector.outcome-listing :as listing]
            [hyperopen.outcomes.domain.book :as book]
            [hyperopen.outcomes.domain.clock :as clock]
            [hyperopen.outcomes.domain.events :as events]
            [hyperopen.outcomes.domain.labels :as labels]
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

(def market-sides book/market-sides)

(defn market-clock
  "The selected rung's clock for a ladder, else the market's own."
  [market]
  (or (:clock (events/selected-rung-facts market))
      (when (number? (:expiry-ms market))
        {:kind :settles :at-ms (:expiry-ms market)})))

(def selected-side-index book/selected-side-index)

(defn venue-label
  "Venue name (never the raw deployer code) of the selected rung, else the
   market's venue."
  [market]
  (or (labels/venue-label (:venue (events/selected-rung-facts market)))
      (when (seq (:outcome-venues market))
        (str/join " · " (keep labels/venue-label (:outcome-venues market))))
      "Hyperliquid"))

(defn market-rules
  "Rules text of the selected rung (ladders), else the market's."
  [market]
  (:rules (events/selected-rung-facts market)))

(defn kicker
  [market]
  (str/join " · " (keep identity [(get category-titles (:outcome-category market))
                                  (:outcome-competition market)])))

(defn side-quotes
  "[side-0 quote, side-1 quote] for the active contract under the one chance
   rule (`book/market-side-quotes`)."
  [market orderbook]
  (book/market-side-quotes market orderbook))

(defn- chance-entry
  [label quote]
  {:label label
   :text (quotes/quote-text quote)
   :state (:state quote)
   :muted? (boolean (:muted? quote))
   :detail (when (contains? #{:not-traded :no-market} (:state quote))
             (quotes/standing-quotes-text quote))})

(defn- price-text
  "The selected side's price: the midpoint in cents, the range in cents, or
   why there is none."
  [quote]
  (case (:state quote)
    :live (quotes/cents-text (:price quote))
    :range (str (quotes/cents-text (:low quote)) "–" (quotes/cents-text (:high quote)))
    (quotes/quote-text quote)))

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
        ;; The selected side's own change; a 0 previous-day price is a
        ;; never-traded placeholder, not a reference (change-24h returns nil).
        delta (quotes/change-24h (get (market-sides market) index))
        clock (market-clock market)
        clock-kind (:kind clock)
        clock-view (clock/clock-view clock now-ms)]
    {:title (or (:title market) (:symbol market))
     :kicker (kicker market)
     :venue (venue-label market)
     :chances (cond
                option-label [(chance-entry option-label quote-a)]
                (= :head-to-head (:outcome-shape market)) [(chance-entry label-a quote-a)
                                                          (chance-entry label-b quote-b)]
                :else [(chance-entry label-a quote-a)])
     ;; The selected answer's one chance number (a two-team game shows both
     ;; in `:chances`).
     :chance-text (quotes/quote-text active-quote)
     :quote active-quote
     :quote-state (:state active-quote)
     :market-order? (boolean (:market-order? active-quote))
     :price-label (if option-label (str active-label " · " option-label) active-label)
     :price-text (price-text active-quote)
     :price-muted? (boolean (:muted? active-quote))
     :change-label (if (= :daily (:outcome-subcategory market)) "Since open" "24h")
     :change-text (or (quotes/points-text delta) "—")
     :change-direction (change-direction delta)
     :clock-heading (get clock-headings clock-kind "Ends")
     :clock-label (or (:label clock-view) "—")
     :clock-absolute (:absolute clock-view)
     :clock-phase (:phase clock-view)
     :rules (market-rules market)
     :volume-text (or (quotes/compact-shares-text (listing/event-volume market)) "—")
     :open-interest-text (or (quotes/compact-usd-text (listing/event-open-interest market)) "—")}))

(defn bar-model
  "What the phone's pinned Buy bar says: both sides' chances, each through the
   one chance rule. {:title :entries [{:label :text :muted?} {...}]}."
  [market orderbook]
  (let [[quote-a quote-b] (side-quotes market orderbook)
        [label-a label-b] (side-labels market)
        option-label (when (seq (:question-options market))
                       (:selected-outcome-option-label market))
        entry (fn [label quote]
                {:label label
                 :text (quotes/quote-text quote)
                 :muted? (boolean (:muted? quote))})]
    {:title (or (:title market) (:symbol market))
     :entries [(entry (if option-label (str option-label " " label-a) label-a) quote-a)
               (entry label-b quote-b)]}))

(defn tooltip-model
  "Rules panel content for the market name hover."
  [market]
  (when (= :outcome (:market-type market))
    (let [rules (market-rules market)
          [label-a label-b] (side-labels market)]
      (when (seq rules)
        {:title (or (:title market) "Rules")
         :summary rules
         :settlement-label rules
         :yes-label label-a
         :no-label label-b
         :yes-payout-label "$1.00"
         :no-payout-label "$0.00"
         :footer-label (str "Payouts are in USDC"
                            ". Venue: " (venue-label market) ".")}))))

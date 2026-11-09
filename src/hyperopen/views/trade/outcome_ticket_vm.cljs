(ns hyperopen.views.trade.outcome-ticket-vm
  "The outcome ticket answers three questions in order: which side, how much,
   what it pays. Selling only appears as Cash out on shares already held."
  (:require [clojure.string :as str]
            [hyperopen.outcomes.domain.book :as book]
            [hyperopen.outcomes.domain.clock :as clock]
            [hyperopen.outcomes.domain.labels :as labels]
            [hyperopen.outcomes.domain.quotes :as quotes]
            [hyperopen.state.trading :as trading]
            [hyperopen.views.active-asset.outcome-header-vm :as header-vm]))

(def ^:private outcome-asset-id-base 100000000)

(def stale-sum-threshold
  "Listed answers of one event adding up past this read as stale prices."
  1.05)

(defn- parse
  [value]
  (let [number (cond
                 (number? value) value
                 (string? value) (js/parseFloat value)
                 :else js/NaN)]
    (when-not (js/isNaN number)
      number)))

(defn- coin-encoding
  [coin]
  (when-let [[_ digits] (and (string? coin) (re-matches #"^[#+](\d+)$" (str/trim coin)))]
    (js/parseInt digits 10)))

(defn- balance-encoding
  [{:keys [coin token]}]
  (or (coin-encoding coin)
      (let [token-id (parse token)]
        (when (and (number? token-id) (>= token-id outcome-asset-id-base))
          (- token-id outcome-asset-id-base)))))

(defn side-holdings
  "{side-coin {:shares :entry-notional}} for the given sides, from spot balances."
  [state sides]
  (let [by-encoding (into {}
                          (keep (fn [balance]
                                  (when-let [encoding (balance-encoding balance)]
                                    [encoding balance])))
                          (get-in state [:spot :clearinghouse-state :balances]))]
    (into {}
          (map (fn [{:keys [coin]}]
                 (let [balance (get by-encoding (coin-encoding coin))]
                   [coin {:shares (or (parse (:total balance)) 0)
                          :entry-notional (or (some #(parse (get balance %)) [:entryNtl :entryNotional]) 0)}])))
          sides)))

(defn- side-labels
  [market]
  (mapv (fn [side]
          (or (:side-name side) (if (zero? (:side-index side)) "Yes" "No")))
        (:outcome-sides market)))

(defn- multi-answer?
  [market]
  (boolean (seq (:question-options market))))

(defn- side-sub-labels
  [market option-label]
  (cond
    (multi-answer? market) [option-label (str "Not " option-label)]
    (seq (:outcome-side-long-labels market)) (:outcome-side-long-labels market)
    :else [nil nil]))

(defn- option-row
  [selected-id {:keys [label outcome-id yes-coin no-coin sides venue threshold]}]
  (let [quote (quotes/side-quote (first sides))
        tradeable? (contains? #{:live :last} (:state quote))]
    {:label label
     :outcome-id outcome-id
     :venue venue
     :threshold threshold
     :selected? (= selected-id outcome-id)
     :chance (:chance quote)
     :chance-text (quotes/quote-text quote)
     :change-text (quotes/points-text (quotes/change-24h (first sides)))
     :yes-text (if tradeable? (quotes/cents-text (:price quote)) "—")
     :no-text (if tradeable? (quotes/cents-text (- 1 (:price quote))) "—")
     :yes-coin (or yes-coin (:coin (first sides)))
     :no-coin (or no-coin (:coin (second sides)))}))

(defn selected-option
  "The answer the ticket trades: the form's choice, else the market's."
  [market vm]
  (let [options (:question-options market)]
    (some (fn [id]
            (some #(when (= id (:outcome-id %)) %) options))
          [(:outcome-option-id vm) (:outcome-option-id market) (:selected-outcome-id market)])))

(defn underlying-price
  "Live price of a ladder's underlying (BTC, xyz:SP500) from the perp catalog."
  [state market]
  (when-let [subject (:outcome-subject market)]
    (let [perp (get-in state [:asset-selector :market-by-key (str "perp:" subject)])
          price (or (:outcome-underlying-price state)
                    (get-in state [:active-assets :contexts subject :mark])
                    (:mark perp))]
      (when (and (number? price) (pos? price)) price))))

(def ^:private kink-tolerance 0.005)

(defn- rung-kinks
  "{outcome-id note} for rungs priced above the rung nearer the money. Each
   venue's rungs are their own chain (deadlines differ); price-touch ladders
   run one chain upward and one downward from the live price."
  [rows kind spot]
  (into {}
        (mapcat (fn [[_ venue-rows]]
                  (let [rows* (filter (comp number? :threshold) venue-rows)
                        chains (cond
                                 (not= :touch kind) [(sort-by :threshold < rows*)]
                                 ;; Without the live price a touch ladder's up and down
                                 ;; rungs cannot be told apart, so nothing is flagged.
                                 (not (number? spot)) []
                                 :else [(sort-by :threshold < (filter #(> (:threshold %) spot) rows*))
                                        (sort-by :threshold > (filter #(<= (:threshold %) spot) rows*))])]
                    (mapcat (fn [chain]
                              (keep (fn [[nearer further]]
                                      (when (and (number? (:chance nearer))
                                                 (number? (:chance further))
                                                 (> (:chance further) (+ (:chance nearer) kink-tolerance)))
                                        [(:outcome-id further) (str "Priced above the " (:label nearer) " rung")]))
                                    (partition 2 1 chain)))
                            chains))))
        (group-by :venue rows)))

(defn- ladder-row
  [kind spot kinks row]
  (let [threshold (:threshold row)
        distance (when (and (number? spot) (number? threshold))
                   (/ (- threshold spot) spot))]
    (cond-> (assoc row :kink-note (get kinks (:outcome-id row)))
      (and (= :touch kind) (number? distance))
      (assoc :direction (if (pos? distance) :up :down))

      (number? distance)
      (assoc :distance-text (str (if (neg? distance) "−" "+")
                                 (.toFixed (js/Math.abs (* 100 distance)) 1) "%")))))

(defn- options-model
  [market selected-id spot]
  (when (multi-answer? market)
    (let [ladder? (= :ladder (:outcome-shape market))
          kind (:outcome-ladder-kind market)
          base-rows (mapv (partial option-row selected-id) (:question-options market))
          multi-venue? (> (count (:outcome-venues market)) 1)
          base-rows (if multi-venue? base-rows (mapv #(dissoc % :venue) base-rows))
          rows (cond
                 ladder? (mapv (partial ladder-row kind spot (rung-kinks base-rows kind spot)) base-rows)
                 ;; Exclusive answers read best likeliest first.
                 :else (vec (sort-by (fn [row] (- (or (:chance row) -1))) base-rows)))
          exclusive? (contains? #{:pick-one :field} (:outcome-shape market))
          listed-sum (reduce + 0 (keep :chance rows))
          fallback (:fallback-option market)]
      {:rows rows
       :ladder? ladder?
       :spot-text (when (and ladder? (number? spot))
                    (str (labels/asset-label (:outcome-subject market)) " now "
                         (labels/level-text (:outcome-subject market)
                                            (str (if (>= spot 1000) (js/Math.round spot) (.toFixed spot 2))))))
       :exclusive? exclusive?
       :listed-sum listed-sum
       :listed-sum-text (str (js/Math.round (* 100 listed-sum)) "%")
       :stale? (and exclusive? (> listed-sum stale-sum-threshold))
       :other (when fallback
                (let [quote (quotes/side-quote (first (:sides fallback)))]
                  {:label (:label fallback)
                   :chance-text (quotes/quote-text quote)}))})))

(defn- shares-of
  [form]
  (let [size (parse (:size form))]
    (if (and (number? size) (pos? size)) (js/Math.floor size) 0)))

(defn- buy-summary
  [{:keys [market-order? shares limit-price asks side-label]}]
  (let [fill (when market-order? (quotes/fill-shares asks shares))
        cost (if market-order?
               (:proceeds fill)
               (when (number? limit-price) (* shares limit-price)))
        avg (if market-order? (:avg-price fill) limit-price)
        {:keys [pays profit profit-pct]} (quotes/payout shares cost)]
    {:shares shares
     :rows (when (pos? shares)
             [["Shares" (str shares " " side-label)]
              ["Avg price" (quotes/cents-text avg {:precise? true})]
              ["Cost" (quotes/usd-text cost)]
              [(str "Pays if " side-label " wins") (quotes/usd-text pays)]
              ["Profit if right" (str (quotes/usd-text profit {:signed? true})
                                      (when (number? profit-pct)
                                        (str " (" (if (neg? profit-pct) "−" "+")
                                             (.toFixed (js/Math.abs profit-pct) 1) "%)")))]])
     :cost cost
     :avg avg}))

(defn- cash-out-summary
  [{:keys [market-order? shares limit-price bids held entry-notional side-label]}]
  (let [fill (when market-order? (quotes/fill-shares bids shares))
        proceeds (if market-order?
                   (:proceeds fill)
                   (when (number? limit-price) (* shares limit-price)))
        cost-basis (when (pos? held) (* entry-notional (/ shares held)))
        result (when (and (number? proceeds) (number? cost-basis) (pos? cost-basis))
                 (- proceeds cost-basis))]
    {:shares shares
     :rows (when (pos? shares)
             (cond-> [["Selling" (str shares " " side-label)]
                      [(if market-order? "Sell at best bid" "Sell at") (quotes/cents-text (if market-order?
                                                                                        (:avg-price fill)
                                                                                        limit-price)
                                                                                      {:precise? true})]
                      ["You receive" (quotes/usd-text proceeds)]]
               (number? result) (conj ["Result vs cost" (quotes/usd-text result {:signed? true})])))
     :proceeds proceeds}))

(defn- limit-note
  [{:keys [mode limit-price best-bid best-ask side-label]}]
  (when (number? limit-price)
    (let [price-text (quotes/cents-text limit-price)]
      (if (= :cash-out mode)
        (if (and (number? best-bid) (<= limit-price best-bid))
          (str "Crosses the book: sells now at " price-text " or better.")
          (str "Rests above the best bid. Fills only if " side-label " trades up to " price-text "."))
        (if (and (number? best-ask) (>= limit-price best-ask))
          (str "Crosses the book: buys now at " price-text " or better.")
          (str "Rests below the best ask. Fills only if " side-label " trades down to " price-text "."))))))

(defn- submit-label
  [{:keys [mode market-order? shares side-label option-label limit-price cost proceeds]}]
  (let [subject (str side-label (when option-label (str " on " option-label)))]
    (cond
      (zero? shares) (if (= :cash-out mode) (str "Cash out " subject) (str "Buy " subject))
      (and (= :cash-out mode) market-order?) (str "Cash out " shares " " subject " · " (quotes/usd-text proceeds))
      (= :cash-out mode) (str "Place limit sell · " shares " " subject " at " (quotes/cents-text limit-price {:precise? true}))
      market-order? (str "Buy " subject " · " (quotes/usd-text cost))
      :else (str "Place limit · " shares " " subject " at " (quotes/cents-text limit-price {:precise? true})))))

(defn- timeline
  [market now-ms]
  (let [{:keys [at-ms deadline-ms kind]} (:outcome-clock market)
        view (clock/clock-view (:outcome-clock market) now-ms)]
    (cond-> []
      (number? at-ms) (conj [(get {:starts "Starts" :settles "Settles" :ends "Ends" :decision "Decision"
                                   :resolves "Resolves by" :listing "Listing deadline"} kind "Ends")
                             (:absolute view)])
      (number? deadline-ms) (conj ["Result due by" (clock/absolute-text deadline-ms)]))))

(defn- details-line
  [market]
  (let [coins (keep :coin (:outcome-sides market))]
    (str/join " · " (keep identity [(when-let [id (or (:outcome-option-id market) (:outcome-id market))]
                                      (str "Contract " id))
                                    (when (seq coins) (str "Sides " (str/join " / " coins)))
                                    (str "Venue " (header-vm/venue-label market))
                                    (str "Quote " (or (:quote market) "USDC"))
                                    "Whole shares"]))))

(defn ticket-model
  [state vm now-ms]
  (let [market (:active-market state)
        orderbook (get-in state [:orderbooks (:active-asset state)])
        sides (vec (:outcome-sides market))
        index (header-vm/selected-side-index market)
        side-quotes (header-vm/side-quotes market orderbook)
        best-bid (book/best-bid orderbook)
        best-ask (book/best-ask orderbook)
        two-sided? (and (number? best-bid) (number? best-ask))
        ;; No book yet means it is still loading, not that the market is empty.
        book-loading? (nil? orderbook)
        labels (side-labels market)
        option (selected-option market vm)
        option-label (when (multi-answer? market)
                       (or (:label option) (:selected-outcome-option-label market)))
        sub-labels (side-sub-labels market option-label)
        holdings (side-holdings state sides)
        side-label (get labels index "Yes")
        active-coin (:coin (get sides index))
        held (get-in holdings [active-coin :shares] 0)
        mode (if (= :sell (:side vm)) :cash-out :buy)
        market-order? (= :market (:type vm))
        form (:form vm)
        limit-price (let [price (parse (:price form))] (when (and price (pos? price)) price))
        shares (shares-of form)
        ctx {:mode mode
             :market-order? market-order?
             :shares shares
             :limit-price limit-price
             :asks (book/asks orderbook)
             :bids (book/bids orderbook)
             :held held
             :entry-notional (get-in holdings [active-coin :entry-notional] 0)
             :best-bid best-bid
             :best-ask best-ask
             :side-label side-label
             :option-label option-label}
        summary (if (= :cash-out mode) (cash-out-summary ctx) (buy-summary ctx))
        ask-of (fn [i] (if (= i index) best-ask (when (number? best-bid) (- 1 best-bid))))
        options (options-model market (:outcome-id option) (underlying-price state market))
        spread (when two-sided? (- best-ask best-bid))]
    {:mode mode
     :market-order? market-order?
     :shape (:outcome-shape market)
     :option-label option-label
     :sides (mapv (fn [i side]
                    (let [quote (get side-quotes i)
                          ask (ask-of i)]
                      {:index i
                       :coin (:coin side)
                       :label (get labels i)
                       :sub-label (get sub-labels i)
                       :price-text (if (number? ask) (quotes/cents-text ask) (quotes/quote-text quote))
                       :chance-text (quotes/quote-text quote)
                       :selected? (= i index)
                       :tone (if (or (= :head-to-head (:outcome-shape market))
                                     (not (contains? #{"Yes" "No"} (get labels i))))
                               :neutral
                               (if (zero? i) :yes :no))}))
                  (range)
                  sides)
     :no-explainer (when (and option-label (= 1 index))
                     (if (= :ladder (:outcome-shape market))
                       (str "No on " option-label " wins if it does not happen.")
                       (str "No on " option-label " wins if any other answer wins.")))
     :options options
     :two-sided? two-sided?
     :market-blocked? (and market-order? (not two-sided?) (not book-loading?))
     :wide-note (when (and market-order? two-sided? (> spread quotes/wide-spread) (= :buy mode))
                  (str "The spread is " (.toFixed (* 100 spread) 1) "¢ wide: a market buy pays the ask, "
                       (quotes/cents-text best-ask) " a share."))
     :no-market-note (when-not (or two-sided? book-loading?)
                       (str "No two-sided quotes"
                            (when (number? best-bid) (str " · best bid " (quotes/cents-text best-bid)))
                            (when (number? best-ask) (str " · best ask " (quotes/cents-text best-ask)))
                            ". Market orders are off; set a limit price."))
     :quote-fills (cond-> []
                    (number? best-bid) (conj ["Bid" best-bid])
                    two-sided? (conj ["Mid" (/ (+ best-bid best-ask) 2)])
                    (number? best-ask) (conj ["Ask" best-ask]))
     :limit-cents-text (when limit-price (str (js/parseFloat (.toFixed (* 100 limit-price) 3))))
     :limit-chance-text (when limit-price (str "= " (.toFixed (* 100 limit-price) 1) "% implied chance"))
     :limit-note (when-not market-order? (limit-note (assoc ctx :limit-price limit-price)))
     :amount-text (when (= :quote (:size-input-mode vm)) (:size-display vm))
     :shares-text (when (= :base (:size-input-mode vm)) (:size-display vm))
     :available-usdc (or (:available-usdc vm) (trading/available-to-trade state))
     :held held
     :held-text (str/join " · " (map (fn [label side]
                                       (str (js/Math.floor (get-in holdings [(:coin side) :shares] 0)) " " label))
                                     labels sides))
     :holds-any? (some #(pos? (:shares %)) (vals holdings))
     :summary-rows (:rows summary)
     :submit-label (submit-label (merge ctx summary))
     :submit-blocked? (or (and market-order? (not two-sided?) (not book-loading?))
                          (and (= :cash-out mode) (> shares held)))
     :fees (get-in vm [:display :fees])
     :rules (or (:rules option)
                (:outcome-rules market)
                (:outcome-details market))
     :timeline (timeline market now-ms)
     :details (details-line market)}))

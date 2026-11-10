(ns hyperopen.views.trade.outcome-ticket-vm
  "The outcome ticket answers three questions in order: which side, how much,
   what it pays. Selling only appears as Cash out on shares already held.

   Every number comes from the outcomes domain: quotes from
   `book/contract-quotes` (the one chance rule), fees from `fees`, order flags
   from `events`. This namespace only arranges them for the view."
  (:require [clojure.string :as str]
            [hyperopen.outcomes.domain.book :as book]
            [hyperopen.outcomes.domain.clock :as clock]
            [hyperopen.outcomes.domain.events :as events]
            [hyperopen.outcomes.domain.fees :as fees]
            [hyperopen.outcomes.domain.labels :as labels]
            [hyperopen.outcomes.domain.quotes :as quotes]
            [hyperopen.state.trading :as trading]
            [hyperopen.trading.order-form-transitions :as transitions]
            [hyperopen.views.active-asset.outcome-header-vm :as header-vm]
            [hyperopen.views.trade.outcome-ticket-summary :as summaries]))

(def stale-sum-threshold
  "Listed answers of one event adding up past this read as stale prices."
  trading/outcome-stale-sum-threshold)

(defn- parse
  [value]
  (let [number (cond
                 (number? value) value
                 (string? value) (js/parseFloat value)
                 :else js/NaN)]
    (when-not (js/isNaN number)
      number)))

(defn side-holdings
  "{side-coin {:shares :entry-notional}} for the given sides, from the spot
   balances of the account orders go to (the selected subaccount when one is
   selected). Shares are what can be sold now: open sell orders hold the rest."
  [state sides]
  (let [by-encoding (into {}
                          (keep (fn [balance]
                                  (when-let [encoding (trading/outcome-coin-encoding balance)]
                                    [encoding balance])))
                          (:balances (trading/spot-clearinghouse-state state)))]
    (into {}
          (map (fn [{:keys [coin]}]
                 (let [balance (get by-encoding (trading/outcome-coin-encoding coin))
                       total (or (parse (:total balance)) 0)
                       hold (or (parse (:hold balance)) 0)]
                   [coin {:shares (max 0 (- total hold))
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
  "Plain words under each side button: the answer for Yes, and what No means
   (a ladder rung that does not happen, any other answer in a pick-one)."
  [market option-label]
  (cond
    (multi-answer? market) [option-label
                            (if (= :ladder (:outcome-shape market))
                              "Does not happen"
                              "Any other answer")]
    (seq (:outcome-side-long-labels market)) (:outcome-side-long-labels market)
    :else [nil nil]))

(defn- option-quote
  "Side-0 quote of one listed answer under the one chance rule."
  [sides]
  (book/quote-for-side (first sides) (second sides) nil))

(defn- option-row
  [selected-id {:keys [label outcome-id yes-coin no-coin sides venue threshold]}]
  (let [quote (option-quote sides)
        live? (= :live (:state quote))
        range? (= :range (:state quote))]
    {:label label
     :outcome-id outcome-id
     :venue venue
     :threshold threshold
     :selected? (= selected-id outcome-id)
     :quote quote
     :state (:state quote)
     :muted? (boolean (:muted? quote))
     :chance (when (:rankable? quote) (:chance quote))
     :chance-text (quotes/quote-text quote)
     :detail-text (when (contains? #{:not-traded :no-market} (:state quote))
                    (quotes/standing-quotes-text quote))
     :change-text (quotes/points-text (quotes/change-24h (first sides)))
     ;; What a market buy pays: the midpoint when tight, the ask across a range.
     :yes-text (cond live? (quotes/cents-text (:price quote))
                     (and range? (number? (:ask quote))) (quotes/cents-text (:ask quote))
                     :else "—")
     :no-text (cond live? (quotes/cents-text (- 1 (:price quote)))
                    (and range? (number? (:bid quote))) (quotes/cents-text (- 1 (:bid quote)))
                    :else "—")
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

(defn- rung-kinks
  "{outcome-id note} for rungs the domain rule flags out of order
   (`events/ladder-order-flags`): priced above an easier rung of the same
   direction, venue and rule set."
  [market spot]
  (into {}
        (map (fn [[outcome-id {:keys [than-label]}]]
               [outcome-id (str "Priced above the " than-label " rung")]))
        (events/ladder-order-flags market {:spot spot
                                           :quote-fn (comp option-quote :sides)})))

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
          kinks (when ladder? (rung-kinks market spot))
          rows (cond
                 ladder? (mapv (partial ladder-row kind spot kinks) base-rows)
                 ;; Exclusive answers read best likeliest first; only midpoint
                 ;; chances rank.
                 :else (vec (sort-by (fn [row] (- (or (:chance row) -1))) base-rows)))
          exclusive? (contains? #{:pick-one :field} (:outcome-shape market))
          listed-sum (:sum (quotes/exclusive-sum (map :quote rows)))
          fallback (:fallback-option market)]
      {:rows (mapv #(dissoc % :quote) rows)
       :ladder? ladder?
       :spot-text (when (and ladder? (number? spot))
                    (str (labels/asset-label (:outcome-subject market)) " now "
                         (labels/level-text (:outcome-subject market)
                                            (str (if (>= spot 1000) (js/Math.round spot) (.toFixed spot 2))))))
       :exclusive? exclusive?
       :listed-sum listed-sum
       :listed-sum-text (str (js/Math.round (* 100 listed-sum)) "%")
       ;; The same rule gates submit, so the box and the gate always agree.
       :stale? (trading/outcome-stale-listed-prices? market)
       :other (when fallback
                (let [quote (option-quote (:sides fallback))]
                  {:label (:label fallback)
                   :chance-text (quotes/quote-text quote)}))})))

(defn- shares-of
  [form]
  (let [size (parse (:size form))]
    (if (and (number? size) (pos? size)) (js/Math.floor size) 0)))

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

(defn- chance-label
  "What a side button says under its price: the chance, the muted range, or
   why there is none."
  [quote]
  (case (:state quote)
    (:live :range) (str (quotes/quote-text quote) " chance")
    (quotes/quote-text quote)))

(defn- side-price-text
  [{:keys [state ask]}]
  (case state
    (:live :range) (if (number? ask) (quotes/cents-text ask) "—")
    (:not-traded :no-market) (if (number? ask) (str "ask " (quotes/cents-text ask)) "—")
    "—"))

(defn- market-off-note
  "Why market orders are off: the book's state, in the same words as the
   header and list."
  [{:keys [state] :as quote} best-bid best-ask two-sided?]
  (let [standing (quotes/standing-quotes-text quote)]
    (cond
      (= :not-traded state)
      (str "Not traded yet" (when standing (str " · " standing)) ". Market orders are off; set a limit price.")

      (not two-sided?)
      (str "No two-sided quotes"
           (when (number? best-bid) (str " · best bid " (quotes/cents-text best-bid)))
           (when (number? best-ask) (str " · best ask " (quotes/cents-text best-ask)))
           ". Market orders are off; set a limit price.")

      :else
      (str "No market" (when standing (str " · " standing)) ". Market orders are off; set a limit price."))))

(defn limit-cents-text
  "What the limit field shows: the trader's raw typed cents while they still
   read as the form's price (so \"68.\" keeps its dot), else the price
   formatted in cents (a quote-fill click or a market switch replaced it)."
  [raw form limit-price]
  (let [typed-price (parse (transitions/outcome-limit-cents->price raw))
        form-price (parse (:price form))]
    (if (and (string? raw)
             (or (and (str/blank? raw) (nil? form-price))
                 (and (number? typed-price) (= typed-price form-price))))
      raw
      (when limit-price (str (js/parseFloat (.toFixed (* 100 limit-price) 3)))))))

(defn- contract-outcome-id
  [market]
  (parse (or (:outcome-option-id market) (:outcome-id market))))

(defn ticket-model
  ;; The clock is read here, at the vm boundary (state may carry one for tests
  ;; and replays), never in the view.
  ([state vm] (ticket-model state vm (or (:now-ms state) (.now js/Date))))
  ([state vm now-ms]
   (let [market (:active-market state)
         orderbook (get-in state [:orderbooks (:active-asset state)])
         sides (vec (:outcome-sides market))
         index (book/selected-side-index market)
         ;; The one helper the header and the submit policy also read.
         side-quotes (book/market-side-quotes market orderbook)
         active-quote (get side-quotes index)
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
         other-label (get labels (- 1 (if (= 1 index) 1 0)) "the other side")
         active-coin (:coin (get sides index))
         held (get-in holdings [active-coin :shares] 0)
         both-held? (and (= 2 (count sides))
                         (every? #(>= (get-in holdings [(:coin %) :shares] 0) 1) sides))
         merge-spec (get-in state [:outcome-ticket :merge])
         merge? (and both-held?
                     (some? merge-spec)
                     (= (set (:coins merge-spec)) (set (map :coin sides))))
         ;; Cash out needs shares of this side; with none held the ticket is a
         ;; Buy ticket whatever side the form last carried.
         mode (cond merge? :merge
                    (and (= :sell (:side vm)) (>= held 1)) :cash-out
                    :else :buy)
         trade-mode (if (= :cash-out mode) :cash-out :buy)
         market-order? (= :market (:type vm))
         form (:form vm)
         limit-price (let [price (parse (:price form))] (when (and price (pos? price)) price))
         shares (shares-of form)
         stale-confirmed? (true? (:outcome-stale-confirmed? form))
         venue (:venue (events/selected-rung-facts market))
         rates (fees/rates-from-state state)
         market-off? (and market-order? (book/market-orders-off? market orderbook))
         cap-price (when market-order?
                     ;; The IOC price cap the order will be sent with.
                     (some-> (trading/apply-market-price state (assoc (trading/order-form-draft state)
                                                                      :type :market
                                                                      :side (if (= :cash-out trade-mode) :sell :buy)))
                             :price
                             parse))
         ctx {:mode trade-mode
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
              :other-label other-label
              :option-label option-label
              :cap-price cap-price
              :venue venue
              :rates rates
              :state (:state active-quote)}
         summary (if (= :cash-out trade-mode) (summaries/cash-out-summary ctx) (summaries/buy-summary ctx))
         options (options-model market (:outcome-id option) (underlying-price state market))
         spread (:spread active-quote)]
     {:mode mode
      :market-order? market-order?
      :shape (:outcome-shape market)
      :option-label option-label
      :quote-state (:state active-quote)
      :sides (mapv (fn [i side]
                     (let [quote (get side-quotes i)]
                       {:index i
                        :coin (:coin side)
                        :label (get labels i)
                        :sub-label (get sub-labels i)
                        :price-text (side-price-text quote)
                        :chance-text (quotes/quote-text quote)
                        :chance-label (chance-label quote)
                        :muted? (boolean (:muted? quote))
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
      :market-blocked? market-off?
      :wide-note (when (and market-order? (= :range (:state active-quote)) (= :buy trade-mode))
                   (str "The spread is " (.toFixed (* 100 spread) 1) "¢ wide: a market buy pays the ask, "
                        (quotes/cents-text best-ask) " a share."))
      :no-market-note (when (and (not book-loading?)
                                 (not (and two-sided? (:market-order? active-quote))))
                        (market-off-note active-quote best-bid best-ask two-sided?))
      :quote-fills (cond-> []
                     (number? best-bid) (conj ["Bid" best-bid])
                     two-sided? (conj ["Mid" (/ (+ best-bid best-ask) 2)])
                     (number? best-ask) (conj ["Ask" best-ask]))
      :limit-cents-text (limit-cents-text (get-in state [:outcome-ticket :limit-cents-text]) form limit-price)
      :limit-chance-text (when limit-price (str "= " (.toFixed (* 100 limit-price) 1) "% implied chance"))
      :limit-note (when-not market-order? (summaries/limit-note (assoc ctx :limit-price limit-price)))
      :amount-text (when (= :quote (:size-input-mode vm)) (:size-display vm))
      :shares-text (when (= :base (:size-input-mode vm)) (:size-display vm))
      :available-usdc (or (:available-usdc vm) (trading/available-to-trade state))
      :held held
      :held-text (str/join " · " (map (fn [label side]
                                        (str (js/Math.floor (get-in holdings [(:coin side) :shares] 0)) " " label))
                                      labels sides))
      :holds-any? (some #(pos? (:shares %)) (vals holdings))
      :can-merge? both-held?
      :merge (when merge?
               (summaries/merge-model {:merge-spec merge-spec :labels labels :sides sides :holdings holdings
                             :venue venue :rates rates :outcome-id (contract-outcome-id market)}))
      :merge-entry (when both-held?
                     {:coins (mapv :coin sides)
                      :pairs (str (js/Math.floor (apply min (map #(get-in holdings [(:coin %) :shares] 0) sides))))})
      :summary-rows (:rows summary)
      :summary-notes (:notes summary)
      :submit-label (summaries/submit-label (merge ctx summary))
      :stale-confirmed? stale-confirmed?
      ;; Market orders off and cash-out above held are refused by the submit
      ;; policy (`submit-policy/outcome-order-message`), read through `vm`.
      :submit-blocked? (boolean
                        (or (and (not market-order?) (not (and (number? limit-price) (< limit-price 1))))
                            (and (:stale? options) (not stale-confirmed?))))
      :rules (or (:rules option)
                 (:outcome-rules market)
                 (:outcome-details market))
      :timeline (timeline market now-ms)
      :details (details-line market)})))

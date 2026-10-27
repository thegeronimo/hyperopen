(ns hyperopen.vol-range.application.ticket-model
  "Display-ready Vol Range ticket model (HO-SPEC-014 v2.1 section 4). Pure:
  built from `plan-state/context-for-state`; every value is a string, boolean,
  keyword or number the view can render as is. Exact key sets are documented
  and validated in `hyperopen.schema.vol-range-contracts`.

  Decimals are formatted from exact strings (`display` namespace), never via
  floats. Inventory-track positions are layout percentages only."
  (:require [clojure.string :as str]
            [hyperopen.domain.trading.precision :as precision]
            [hyperopen.trading.vol-range-draft :as vol-range-draft]
            [hyperopen.vol-range.application.display :as display]
            [hyperopen.vol-range.application.draft-scope :as draft-scope]
            [hyperopen.vol-range.application.plan-state :as plan-state]
            [hyperopen.vol-range.application.snapshots :as snapshots]))

(def eligibility-message "Volatility range is available on USDC spot markets.")
(def footer-note "Orders fill independently. Prices stay fixed until you update them.")
(def sell-proceeds-note "Sale proceeds don't fund the staged buys.")
(def vwap-gap-explanation
  "Assumes matched quantities. It compares the two quoted VWAPs and is not expected profit.")

(defn- keyword-of [x]
  (cond (keyword? x) x
        (string? x) (keyword x)
        :else nil))

(defn visible?
  "The Vol Range ticket shows for a Scale order type (Pro entry) in Volatility
  range mode."
  [state]
  (let [order-type (keyword-of (get-in state [:order-form :type]))
        entry-mode (keyword-of (or (get-in state [:order-form :entry-mode])
                                   (get-in state [:order-form-ui :entry-mode])))]
    (and (= :scale order-type)
         (not (contains? #{:market :limit} entry-mode))
         (= :volatility-range (vol-range-draft/scale-mode (:order-form state))))))

(defn ui-flags [state]
  {:show-bands? (if (contains? (or (get-in state [:vol-range :ui]) {}) :show-bands?)
                  (boolean (get-in state [:vol-range :ui :show-bands?]))
                  true)
   :advanced-open? (boolean (get-in state [:vol-range :ui :advanced-open?]))
   :review-open? (and (boolean (get-in state [:vol-range :ui :review-open?]))
                      (draft-scope/frozen-review-current? state))})

(defn- d [x] (display/parse x))
(defn- offer-view [offer] (when offer {:id (:id offer) :label (:label offer)}))
(defn- find-offer [plan id] (some #(when (= id (:id %)) %) (:remediations plan)))

(defn account-label [address]
  (or (display/short-address address) "No account"))

;; ---------------------------------------------------------------------------
;; Model section
;; ---------------------------------------------------------------------------

(defn- volatility-section [{:keys [draft market plan]}]
  (let [mode (get-in draft [:volatility :mode])
        vol (or (get-in plan [:model :volatility])
                (let [av (:auto-volatility market)]
                  (when (and (= :auto mode) (= :ok (:status av)))
                    {:daily-fraction (:daily-fraction av) :window (:window av) :source :auto})))
        window (:window vol)
        unavailable (some #(when (contains? #{:data/volatility-unavailable :data/volatility-stale} (:code %)) (:message %))
                      (:errors plan))]
    {:mode mode
     :display (some-> (:daily-fraction vol) display/percent)
     :source-label (case (:source vol) :auto "Auto" :manual "Manual" nil)
     :manual-input (get-in draft [:volatility :manual-daily-pct])
     :caption "Parkinson · 14-day lookback"
     :window-label (when (and (= :auto mode) window)
                     (str (display/utc-date (:first-day-ms window)) " – "
                          (display/utc-date (:last-day-ms window)) " (UTC)"))
     :unavailable-message (when (and (= :auto mode) (nil? vol))
                            (or unavailable
                                (get-in market [:auto-volatility :message])
                                "14 completed daily candles required"))}))

(def ^:private anchor-labels
  {:vwap-24h "24h VWAP" :utc-open "UTC open" :market-price "Market price" :custom "Custom anchor"})

(defn- anchor-section [{:keys [draft market plan]}]
  (let [mode (get-in draft [:anchor :mode])
        anchors (:anchors market)
        custom (d (get-in draft [:anchor :custom-price]))
        price-of (fn [id]
                   (if (= id :custom)
                     (when (and custom (precision/pos-dec? custom)) (precision/decimal->string custom))
                     (get-in anchors [id :price])))
        option (fn [id]
                 (let [p (price-of id)]
                   {:id id :label (get anchor-labels id) :available? (some? p)
                    :price (display/exact p) :selected? (= id mode)}))
        anchor (get-in plan [:model :anchor])
        label (get anchor-labels mode)
        observed (:observed-at-ms anchor)
        excursion (get-in plan [:model :excursion-sigma])]
    {:mode mode
     :options (mapv option [:vwap-24h :utc-open :market-price :custom])
     :price (display/exact (or (:price anchor) (price-of mode)))
     :caption (cond
                (nil? (price-of mode)) (str label " is unavailable. Select another anchor.")
                observed (str label " · " (display/utc-clock observed))
                :else label)
     :custom-input (get-in draft [:anchor :custom-price])
     :excursion-text (when excursion (str (display/sigma excursion) " from anchor"))}))

(defn- tilt-section [{:keys [draft plan]}]
  (let [enabled? (boolean (get-in draft [:inventory :tilt-enabled?]))
        shifted (get-in plan [:model :shifted-anchor])
        shift (get-in plan [:model :tilt :shift-sigma])]
    {:enabled? enabled?
     :strength-input (get-in draft [:inventory :tilt-strength])
     :caption (cond
                (not enabled?) "Anchor is not shifted"
                shifted (str "Effective anchor " (display/exact shifted)
                             (when shift (str " · shift " (display/sigma shift))))
                :else "Effective anchor is unavailable")}))

;; ---------------------------------------------------------------------------
;; Inventory section
;; ---------------------------------------------------------------------------

(def ^:private track-labels
  {:all-sells-first "All sells first" :core "Core" :now "Now" :target "Target"
   :max "Max" :all-buys-first "All buys first"})

(defn- track-position [value low span]
  (if (and span (not (precision/zero-dec? span)))
    (let [r (precision/divide (precision/sub value low) span 4 :half-up)]
      (/ (js/Math.round (* 10000 (js/Number (precision/decimal->string r)))) 100))
    50))

(defn- inventory-track [outcomes sz]
  (let [values (keep (fn [[id k]] (when-let [v (d (get outcomes k))] [id v]))
                     [[:all-sells-first :all-sells-first] [:core :core] [:now :current]
                      [:target :target] [:max :maximum] [:all-buys-first :all-buys-first]])]
    (when (seq values)
      (let [low (reduce precision/dec-min (map second values))
            high (reduce precision/dec-max (map second values))
            span (precision/sub high low)]
        {:domain-low (display/amount (precision/decimal->string low) sz)
         :domain-high (display/amount (precision/decimal->string high) sz)
         :markers (mapv (fn [[id v]]
                          {:id id :label (get track-labels id)
                           :value (display/amount (precision/decimal->string v) sz)
                           :position (track-position v low span)})
                        values)}))))

(defn starting-breach-text
  "Spec 6.6: a corrective plan shows the starting breach explicitly."
  [outcomes sz base-symbol]
  (let [amt #(str (display/amount (get outcomes %) sz) (when base-symbol (str " " base-symbol)))]
    (case (:starting-breach outcomes)
      :above-maximum (str "Inventory " (amt :current) " is above the maximum " (amt :maximum)
                          ". Only sells that bring it down can be planned.")
      :below-core (str "Inventory " (amt :current) " is below the protected core " (amt :core)
                       ". Only buys that bring it up can be planned.")
      nil)))

(defn- inventory-section [{:keys [draft market account plan]}]
  (let [sz (:sz-decimals market)
        outcomes (:inventory-outcomes plan)]
    {:base-symbol (:base-symbol market)
     :current (display/amount (get-in account [:base :total]) sz)
     :available-to-sell (display/amount (get-in account [:base :available]) sz)
     :core-input (get-in draft [:inventory :protected-core])
     :target-input (get-in draft [:inventory :target])
     :maximum-input (get-in draft [:inventory :maximum])
     :core-placeholder "current inventory"
     :target-placeholder "current inventory"
     :maximum-placeholder "required"
     :track (inventory-track outcomes sz)
     :starting-breach (starting-breach-text outcomes sz (:base-symbol market))
     :set-maximum-offer (offer-view (or (find-offer plan :set-maximum-all-buys)
                                        (find-offer plan :fill-required-limits)))}))

;; ---------------------------------------------------------------------------
;; Side cards
;; ---------------------------------------------------------------------------

(defn- rows [plan side sz]
  (->> (:legs plan)
       (filter #(= side (:side %)))
       (mapv (fn [leg]
               {:leg-id (:leg-id leg)
                :price (display/exact (:price leg))
                :size (display/amount (:size leg) sz)
                :notional (display/fixed (:notional leg) 2)
                :sigma (display/sigma (:actual-sigma leg))}))))

(defn- token-label [token market]
  (case token :quote (:quote-symbol market) :base (:base-symbol market) nil))

(defn- amount-note
  "In USDC mode, what the amount means and the base quantity it buys or sells."
  [side t sz market]
  (when (= :quote (:amount-unit t))
    (str (if (= side :buy)
           "Spend up to this amount, fee reserve included"
           "Sell up to this much order value")
         (when (pos? (:rung-count t 0))
           (str " · " (display/amount (:quantity t) sz) " " (:base-symbol market))))))

(defn- side-inputs [draft side]
  (let [sd (get draft side)]
    {:enabled? (boolean (:enabled? sd))
     :amount-unit (if (= :quote (:amount-unit sd)) :quote :base)
     :quote-amount-input (:quote-amount sd)
     :quantity-input (:quantity sd)
     :count-input (str (:count sd))
     :start-sigma-input (:start-sigma sd)
     :end-sigma-input (:end-sigma sd)
     :skew-input (:skew sd)}))

(defn- buy-card [{:keys [draft market plan] :as ctx}]
  (let [sz (:sz-decimals market)
        t (get-in plan [:totals :buy])
        legs? (pos? (:rung-count t 0))
        reserve (:fee-reserve t)]
    (merge (side-inputs draft :buy)
           {:max-debit-input (get-in draft [:buy :max-usdc-debit])
            :amount-note (amount-note :buy t sz market)
            :rows (rows plan :buy sz)
            :totals {:quantity (when legs? (display/amount (:quantity t) sz))
                     :gross (when legs? (display/fixed (:gross t) 2))
                     :fee-reserve (when legs? (display/exact (:amount reserve)))
                     :fee-reserve-token (when legs? (token-label (:token reserve) market))
                     :max-debit (when legs? (display/exact (:max-debit t)))
                     :cap (display/exact (:cap t))
                     :cap-headroom (when legs? (display/exact (:cap-headroom t)))
                     :vwap (when legs? (display/fixed (:vwap t) 2))}
            :use-plan-total-offer (offer-view (or (find-offer plan :use-plan-total)
                                                  (find-offer plan :fill-required-limits)))})))

(def ^:private excess-percents [25 50 100])

(defn- percent-options [excess sz]
  (let [e (d excess)]
    (mapv (fn [pct]
            (let [q (when e
                      (precision/quantize (precision/divide (precision/mul e (d (str pct))) (d "100") 20 :floor)
                                          (or sz 0) :floor))]
              {:pct pct
               :label (str pct "%")
               :quantity (some-> q precision/decimal->string)
               :enabled? (boolean (and q (precision/pos-dec? q)))}))
          excess-percents)))

(defn- sell-card [{:keys [draft market plan]}]
  (let [sz (:sz-decimals market)
        t (get-in plan [:totals :sell])
        legs? (pos? (:rung-count t 0))
        reserve (:fee-reserve t)
        excess (get-in plan [:totals :sellable-excess])
        hint (when (get-in draft [:sell :enabled?])
               (some #(when (= :hint/nothing-to-sell (:code %)) (:message %)) (:warnings plan)))]
    (merge (side-inputs draft :sell)
           {:percent-options (percent-options excess sz)
            :amount-note (amount-note :sell t sz market)
            :sellable-excess (display/amount excess sz)
            :nothing-to-sell-hint hint
            :rows (rows plan :sell sz)
            :totals {:quantity (when legs? (display/amount (:quantity t) sz))
                     :base-committed (when legs? (display/amount (:base-committed t) sz))
                     :fee-reserve (when legs? (display/exact (:amount reserve)))
                     :fee-reserve-token (when legs? (token-label (:token reserve) market))
                     :gross (when legs? (display/fixed (:gross t) 2))
                     :est-net-proceeds (when legs? (display/fixed (:net-proceeds t) 2))
                     :vwap (when legs? (display/fixed (:vwap t) 2))}
            :note sell-proceeds-note})))

(defn- outcomes-section [{:keys [draft market plan]}]
  (let [sz (:sz-decimals market)
        o (:inventory-outcomes plan)
        both? (boolean (and (get-in draft [:buy :enabled?]) (get-in draft [:sell :enabled?])))
        gap (get-in plan [:totals :quoted-vwap-gap])]
    {:both-sides? both?
     :base-symbol (:base-symbol market)
     :all-buys-first (display/amount (:all-buys-first o) sz)
     :all-sells-first (display/amount (:all-sells-first o) sz)
     :everything-fills (display/amount (:all-fill o) sz)
     :vwap-gap (when (and both? gap)
                 {:per-unit (display/fixed (:per-unit gap) 2)
                  :percent (str (display/exact (:pct-of-buy-vwap gap)) "%")
                  :explanation vwap-gap-explanation
                  :collapsed? true})}))

;; ---------------------------------------------------------------------------
;; Errors, warnings, footer
;; ---------------------------------------------------------------------------

(def ^:private offer-error-codes
  {:anchor-to-market #{:plan/post-only-cross}
   :start-buys-at #{:plan/post-only-cross}
   :start-sells-at #{:plan/post-only-cross}
   :use-fewer-rungs #{:plan/min-notional}
   :reduce-rung-count #{:plan/duplicate-price}
   :widen-range #{:plan/duplicate-price}
   :set-maximum-all-buys #{:plan/max-inventory-breach :input/maximum-required}
   :use-plan-total #{:plan/usdc-cap-exceeded :input/max-debit-required}
   :fill-required-limits #{:input/maximum-required :input/max-debit-required
                           :plan/max-inventory-breach :plan/usdc-cap-exceeded}})

(def ^:private offer-error-side
  {:start-buys-at :buy :start-sells-at :sell})

(defn- error-rows
  "Error rows with their remediation offers. An offer attaches to the first error
  whose code it addresses (side-specific offers, Start buys/sells at, to the
  first crossing error of that side), or to the first error when none matches."
  [plan]
  (let [errors (vec (:errors plan))
        owner (fn [offer]
                (let [codes (get offer-error-codes (:id offer) #{})
                      side (get offer-error-side (:id offer))
                      match? (fn [e] (and (contains? codes (:code e))
                                          (or (nil? side) (= side (get-in e [:data :side])))))]
                  (or (first (keep-indexed #(when (match? %2) %1) errors))
                      (first (keep-indexed #(when (contains? codes (:code %2)) %1) errors))
                      0)))
        by-idx (group-by owner (:remediations plan))]
    (vec (map-indexed
          (fn [i e]
            {:code (:code e)
             :message (:message e)
             :path (get-in e [:data :path])
             :remediations (mapv offer-view (get by-idx i []))})
          errors))))

(defn- warning-rows [plan]
  (->> (:warnings plan)
       (remove #(= :hint/nothing-to-sell (:code %)))
       (mapv (fn [w] {:code (:code w) :message (:message w)}))))

(defn- identity-section [{:keys [market account]}]
  {:market-label (if (and (:base-symbol market) (:quote-symbol market))
                   (str (:base-symbol market) "/" (:quote-symbol market))
                   "—")
   :market-type-label (case (:market-type market)
                        :spot "Spot"
                        :perp "Perp"
                        nil "—"
                        (str/capitalize (name (:market-type market))))
   :account-label (account-label (:account-address account))
   :execution-label "Post-only"})

(defn- eligibility [market]
  (let [ok? (= :ready (:status market))]
    {:eligible? ok?
     :eligibility-reason (when-not ok? eligibility-message)}))

(defn eligibility-for-state
  "{:eligible? :eligibility-reason} for the active market, whether or not the
  ticket is visible; the mode control uses it to disable Volatility range."
  [state now-ms]
  (eligibility (snapshots/market-snapshot state now-ms)))

(defn ticket-model
  "Ticket model for the state at `now-ms`. When the ticket is not visible only
  :visible? and :ui carry data."
  [state now-ms]
  (let [vis? (visible? state)]
    (if-not vis?
      {:visible? false :eligible? false :eligibility-reason nil :read-only? false :status nil
       :identity nil :model nil :inventory nil :buy nil :sell nil :outcomes nil
       :errors [] :warnings [] :footer nil :ui (ui-flags state)}
      (let [{:keys [market account plan] :as ctx} (plan-state/context-for-state state now-ms)
            n (count (:legs plan))]
        (merge (eligibility market)
               {:visible? true
                :read-only? (boolean (:read-only? account))
                :status (:status plan)
                :identity (identity-section ctx)
                :model {:volatility (volatility-section ctx)
                        :anchor (anchor-section ctx)
                        :tilt (tilt-section ctx)}
                :inventory (inventory-section ctx)
                :buy (buy-card ctx)
                :sell (sell-card ctx)
                :outcomes (outcomes-section ctx)
                :errors (error-rows plan)
                :warnings (warning-rows plan)
                :footer {:note footer-note
                         :review-label (str "Review " n (if (= 1 n) " order" " orders"))
                         :review-enabled? (= :ready (:status plan))}
                :ui (ui-flags state)})))))

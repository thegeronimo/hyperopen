(ns hyperopen.vol-range.application.review-model
  "Read-only Vol Range review model (HO-SPEC-014 v2.1 section 4.4, Release A0).

  Built from the FROZEN plan at `[:vol-range :frozen-review]` and the context
  captured with it at `[:vol-range :frozen-context]` ({:bbo :frozen-at-ms
  :provenance}); source ages come from the provenance captured at freeze time;
  nothing re-anchors to a newer quote. The footer says placement is not
  available and carries no commit control (VR-24)."
  (:require [hyperopen.vol-range.application.display :as display]
            [hyperopen.vol-range.application.draft-scope :as draft-scope]
            [hyperopen.vol-range.application.ticket-model :as ticket-model]
            [hyperopen.vol-range.contracts :as contracts]))

(def placement-text "Placement isn't available yet")
(def enter-hint "Pressing Enter in a field never places orders.")
(def fixed-order-note
  "Prices and sizes are fixed. Orders fill independently, and a fill on one side never changes the others.")

(defn- row [label value] {:label label :value value})

(defn- with-symbol [text symbol] (when text (str text (when symbol (str " " symbol)))))

(def ^:private anchor-labels
  {:vwap-24h "24h VWAP" :utc-open "UTC open" :market-price "Market price" :custom "Custom anchor"})

(defn- scope-sz [scope] (:sz-decimals scope))

(defn- tiles [plan context]
  (let [model (:model plan)
        bbo (:bbo context)
        tilt (:tilt model)
        shifted (:shifted-anchor model)]
    [{:id :anchor :label "Anchor"
      :value (display/exact (get-in model [:anchor :price]))
      :caption (str (get anchor-labels (get-in model [:anchor :mode]))
                    (when-let [t (display/utc-clock (get-in model [:anchor :observed-at-ms]))]
                      (str " · " t)))}
     {:id :volatility :label "Daily volatility"
      :value (display/percent (get-in model [:volatility :daily-fraction]))
      :caption (if (= :manual (get-in model [:volatility :source]))
                 "Manual"
                 "Parkinson · 14-day lookback")}
     {:id :tilt :label "Inventory tilt"
      :value (if (:enabled? tilt) (display/exact shifted) "Off")
      :caption (if (:enabled? tilt)
                 (str "Effective anchor · shift " (display/sigma (:shift-sigma tilt)))
                 "Anchor is not shifted")}
     {:id :book :label "Book at plan"
      :value (when bbo (str (display/exact (:bid bbo)) " / " (display/exact (:ask bbo))))
      :caption "Bid / Ask"}]))

(defn- leg-rows [plan sz]
  (mapv (fn [leg]
          {:leg-id (:leg-id leg)
           :side (:side leg)
           :side-label (if (= :buy (:side leg)) "Buy" "Sell")
           :price (display/exact (:price leg))
           :size (display/amount (:size leg) sz)
           :notional (display/fixed (:notional leg) 2)
           :distance (display/sigma-magnitude (:actual-sigma leg))})
        (:legs plan)))

(defn- cash-rows [plan scope]
  (let [sz (scope-sz scope)
        base (:symbol scope)
        quote (:quote-symbol scope)
        buy (get-in plan [:totals :buy])
        sell (get-in plan [:totals :sell])
        reserve (fn [r] (when (and r (:token r))
                          (str (display/exact (:amount r)) " " (if (= :quote (:token r)) quote base))))]
    (cond-> [(row "Total buys" (str (display/amount (:quantity buy) sz) " " base " · "
                                    (display/fixed (:gross buy) 2) " " quote))
             (row "Total sells" (str (display/amount (:quantity sell) sz) " " base " · "
                                     (display/fixed (:gross sell) 2) " " quote))
             (row "USDC debit cap" (some-> (with-symbol (display/exact (:cap buy)) quote)
                                           (str (when (= :quote (:amount-unit buy)) " · buy amount entered"))))
             (row "Maximum buy debit" (with-symbol (display/exact (:max-debit buy)) quote))
             (row "Buy fee reserve" (reserve (:fee-reserve buy)))
             (row "Sell fee reserve" (reserve (:fee-reserve sell)))
             (row "Headroom to cap" (with-symbol (display/exact (:cap-headroom buy)) quote))
             (row "Headroom to available balance" (with-symbol (display/exact (:available-headroom buy)) quote))]
      (= :quote (:amount-unit sell))
      (conj (row "Sell amount entered" (with-symbol (display/exact (:quote-amount sell)) quote)))
      true vec)))

(defn- inventory-rows [plan scope]
  (let [sz (scope-sz scope)
        base (:symbol scope)
        o (:inventory-outcomes plan)
        amt (fn [k] (with-symbol (display/amount (get o k) sz) base))]
    (cond-> []
      (:starting-breach o)
      (conj (row "Starting breach" (ticket-model/starting-breach-text o sz base)))
      true
      (into
    (remove nil?)
    [(row "Current inventory" (amt :current))
     (when (= :quote (:limit-unit o))
       (row "Limits entered in USDC" (str "converted at anchor " (display/exact (:limit-price o)))))
     (row "Keep at least" (amt :core))
     (when (get-in plan [:model :tilt :enabled?]) (row "Lean toward" (amt :target)))
     (row "Hold at most" (if (:maximum o) (amt :maximum) "No limit"))
     (row "If all buys fill first" (amt :all-buys-first))
     (row "If all sells fill first" (amt :all-sells-first))
     (row "If everything fills" (amt :all-fill))
     (row "Existing buy orders included" (amt :existing-buy-exposure))
     (row "Existing sell orders included" (amt :existing-sell-exposure))]))))

(defn source-age-text
  "\"14:02:10 UTC · 0.4 s before plan\", with \" · stale\" when the age at the
  frozen plan time exceeds `max-age-ms` (spec 7.3), and \"unknown\" when the
  source carried no observation time. Ages are measured against the frozen
  plan time, never the render clock."
  [observed-ms frozen-ms max-age-ms]
  (cond
    (not (number? observed-ms)) "unknown · treated as stale"
    (not (number? frozen-ms)) (display/utc-clock observed-ms)
    :else
    (let [age (max 0 (- frozen-ms observed-ms))
          secs (/ (js/Math.round (/ age 100)) 10)]
      (str (display/utc-clock observed-ms) " · " secs " s before plan"
           (when (and max-age-ms (> age max-age-ms)) " · stale")))))

(defn- detail-rows [plan context]
  (let [v (:source-versions plan)
        window (:volatility-window v)
        frozen (:frozen-at-ms context)
        policy contracts/freshness-policy
        ;; Live provenance captured at freeze; plan source-versions as fallback.
        prov (merge {:bbo-observed-at-ms (:bbo-observed-at-ms v)
                     :vwap-observed-at-ms (:anchor-observed-at-ms v)
                     :fee-loaded-at-ms (:fee-loaded-at-ms v)}
                    (:provenance context))]
    [(row "Plan" (:plan-id plan))
     (row "Model version" (str (:model-version v)))
     (row "Estimator" (:estimator-version v))
     (row "Volatility window" (when window
                                (str (display/utc-date (:first-day-ms window)) " – "
                                     (display/utc-date (:last-day-ms window)) " (UTC)")))
     ;; The store only keeps visually changed books, so this is the time of the
     ;; last book CHANGE, not proof of a current observation (spec 7.3).
     (row "Book last changed" (source-age-text (:bbo-observed-at-ms prov) frozen (:bbo-ms policy)))
     (row "Anchor observed" (case (get-in plan [:model :anchor :mode])
                              :custom "Custom anchor"
                              :market-price (source-age-text (:bbo-observed-at-ms prov) frozen (:bbo-ms policy))
                              :vwap-24h (source-age-text (:vwap-observed-at-ms prov) frozen (:vwap-ms policy))
                              (source-age-text (:anchor-observed-at-ms v) frozen nil)))
     (row "Fee quote loaded" (source-age-text (:fee-loaded-at-ms prov) frozen (:fee-ms policy)))
     (row "Frozen at" (display/utc-clock (:frozen-at-ms context)))]))

(def ^:private empty-review
  {:open? false :available? false :title nil :identity-line nil :plan-as-of nil
   :base-symbol nil :tiles [] :legs [] :cash [] :inventory [] :details [] :note nil
   :footer {:placement-available? false :text placement-text :hint enter-hint
            :back-label "Back to edit"}})

(defn review-model
  "Review model from the frozen plan. `now-ms` is accepted for API symmetry; the
  displayed time is the frozen plan time, never the current time."
  [state _now-ms]
  (let [current? (draft-scope/frozen-review-current? state)
        plan (when current? (get-in state [:vol-range :frozen-review]))
        context (get-in state [:vol-range :frozen-context])
        open? (and current? (boolean (get-in state [:vol-range :ui :review-open?])))]
    (if-not (map? plan)
      (assoc empty-review :open? false)
      (let [scope (:scope plan)
            sz (scope-sz scope)
            n (count (:legs plan))]
        {:open? open?
         :available? true
         :title (str "Review " n (if (= 1 n) " post-only order" " post-only orders"))
         :base-symbol (:symbol scope)
         :identity-line (str (:symbol scope) "/" (:quote-symbol scope) " · Spot · "
                             (ticket-model/account-label (:account-address scope)) " · Post-only")
         :plan-as-of (str "Plan as of " (display/utc-clock (or (:frozen-at-ms context)
                                                               (get-in plan [:source-versions :bbo-observed-at-ms]))))
         :tiles (tiles plan context)
         :legs (leg-rows plan sz)
         :cash (cash-rows plan scope)
         :inventory (inventory-rows plan scope)
         :details (detail-rows plan context)
         :note fixed-order-note
         :footer (:footer empty-review)}))))

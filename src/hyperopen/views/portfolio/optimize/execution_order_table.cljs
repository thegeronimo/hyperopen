(ns hyperopen.views.portfolio.optimize.execution-order-table
  "The dense per-order table for the Execution tab, with its inline per-order type editor
  (Market/Limit/TWAP/Passive) and per-row execution-cost breakdown (spread crossing + book
  impact = price cost, + fees = all-in; a resting Limit/Passive row pays neither spread nor
  impact). Extracted verbatim from hyperopen.views.portfolio.optimize.execution-tab to keep
  each namespace within the size budget; shared pure helpers live in
  hyperopen.views.portfolio.optimize.execution-shared. Only `order-table` is public."
  (:require [clojure.string :as str]
            [hyperopen.views.portfolio.optimize.execution-shared :as shared]
            [hyperopen.views.portfolio.optimize.format :as opt-format]))

;; ── row helpers ─────────────────────────────────────────────────────────

(defn- data-role-token
  "Selector-safe data-role suffix (instrument ids carry colons/slashes)."
  [value]
  (-> (str value)
      (str/replace #"[^A-Za-z0-9_-]+" "-")
      (str/replace #"(^-+|-+$)" "")))

(defn- rec-reason
  "Plain-language rationale for the algorithm-recommended route of this row — cost-aware
  first (the policy in execution-order-type), then clip size."
  [row order-type]
  (case order-type
    :twap "large clip — slice over time to limit market impact"
    :limit "liquid spot sell — rest at mid and capture the spread"
    :market "small clip with a cheap crossing — immediacy outweighs impact"
    (if (shared/high-cost-crossing-row? row)
      (str "crossing costs ~" (shared/format-bps (shared/crossing-cost-bps row))
           " as a market order — post passively instead")
      "medium position — post passively, avoid crossing the spread")))

(defn- route-hint
  "By-exception rationale under the Type chip: only for a ready row the RECOMMENDED
  policy moved off the market default (no override, default strategy :recommended).
  Market routes and user choices stay quiet — the hint marks exactly the rows where
  'the algo picked something for a reason' needs to be inspectable in the table."
  [model row order-type]
  (when (and (= :recommended (or (:default-order-type model) :recommended))
             (not (contains? (:overrides model) (:row-id row)))
             (= :ready (:status row))
             (not= :market order-type))
    (case order-type
      :passive (if (shared/high-cost-crossing-row? row)
                 (str (shared/format-bps (shared/crossing-cost-bps row)) " as market — rests instead")
                 "avoids crossing the spread")
      :twap "large clip — sliced over time"
      :limit "spot sell — rests at a price"
      nil)))

(defn- editable?
  ;; Pre-commit (:staged/:armed) the per-order type/param editor is open — including in read-only
  ;; views (spectate). Toggling a row's type only re-projects its estimated cost; it dispatches
  ;; nothing but modal-state writes. The commit gate is on Arm/Confirm, and live-order amend has
  ;; its own `amendable?` guard, so nothing here can send an order.
  [{:keys [phase]}]
  (contains? #{:staged :armed} phase))

(def ^:private state-glyph
  {:filled "✓"
   :resting "○"
   :failed "✕"
   :blocked "–"
   :skipped "–"
   :working "◐"
   :queued "○"
   :staged "○"})

(defn- row-display-state
  [{:keys [phase]} row]
  (case (:status row)
    :submitted :filled
    ;; Accepted and live on the book, but NOT filled — an open order.
    :resting :resting
    :failed :failed
    :blocked :blocked
    :skipped :skipped
    :working :working
    :ready (if (contains? #{:armed :running} phase) :queued :staged)
    :staged))

(defn- side-tone
  [side]
  (case side :buy :long :sell :short :muted))

(defn- cost-source-label
  [row]
  (let [cost (:cost row)
        coverage (:depth-coverage cost)]
    (->> [(when-let [source (:source cost)] (opt-format/keyword-label source))
          (case (:depth-status cost)
            ;; Disclose HOW limited: the share of the order the visible book covers is
            ;; the difference between "slightly beyond the book" and "409× the book".
            :insufficient-visible-depth
            (if (and (number? coverage) (pos? coverage))
              (str "book covers "
                   (opt-format/format-decimal (* 100 coverage) {:maximum-fraction-digits 1})
                   "% of order — estimate is a floor")
              "depth limited")
            nil)]
         (remove nil?)
         (str/join " · ")
         not-empty)))

(defn- skip-reason-label
  "Plain-language reason a leg was skipped. A bare 'skipped' next to a
  five-figure notional reads as arbitrarily dropped money at the exact moment
  the user is deciding whether to send live orders."
  [{:keys [reason tolerance]}]
  (case reason
    :within-tolerance
    (str "within "
         (if (and (number? tolerance)
                  (js/isFinite tolerance)
                  (pos? tolerance))
           (str (opt-format/format-decimal (* 100 tolerance)) " pp band")
           "tolerance band")
         " — no trade needed")
    :already-filled "already filled on a previous attempt"
    :already-resting "already resting on the book"
    :excluded-from-optimization "excluded from the optimization — held, not sold"
    (some-> reason opt-format/keyword-label)))

(defn- truncate-text
  [text limit]
  (let [s (str text)]
    (if (> (count s) limit)
      (str (subs s 0 (max 0 (dec limit))) "…")
      s)))

(defn- state-cell
  [display-state row]
  (case display-state
    :filled [:span {:class ["text-trading-green"]} "filled"]
    :resting [:span {:class ["text-info"]} "open"]
    ;; A rejection without its reason reads as an arbitrary failure at the exact
    ;; moment the trader must decide whether to resume — surface the exchange's
    ;; own message on the row (truncated; full text on hover).
    :failed (let [message (get-in row [:error :message])]
              [:span {:class ["text-trading-red"]
                      :title (when message (str message))}
               (str "rejected"
                    (when (:reason row) (str " · " (opt-format/keyword-label (:reason row)))))
               (when message
                 [:span {:class ["block" "font-mono" "text-[0.62rem]"
                                 "text-trading-red/80"]
                         :data-role "portfolio-optimizer-execution-row-error"}
                  (truncate-text message 72)])])
    :blocked [:span {:class ["text-trading-muted"]} (opt-format/keyword-label (:reason row))]
    :skipped [:span {:class ["text-trading-muted"]}
              (if-let [label (skip-reason-label row)]
                (str "skipped · " label)
                "skipped")]
    :working [:span {:class ["text-warning"]} "sending…"]
    :queued [:span {:class ["text-warning"]} "queued"]
    [:span {:class ["text-trading-muted"]} "staged"]))

(defn- cost-stat
  "One term in the execution-cost equation: a small uppercase label, the bp value (large for
  glanceability), and the $ underneath. `emphasis` tunes the hierarchy — :input for the
  spread/impact/fee inputs (muted), :total for the price-cost subtotal (bright), :allin for
  the boxed accent total."
  [label bps usd emphasis]
  [:div {:class ["optimizer-exec-cost-stat"] :data-emphasis (name emphasis)}
   [:span {:class ["optimizer-exec-cost-stat-label"]} label]
   [:span {:class ["optimizer-exec-cost-stat-bp"]}
    (if (shared/finite bps) (shared/format-bps bps) "—")]
   [:span {:class ["optimizer-exec-cost-stat-usd"]}
    (if (shared/finite usd) (opt-format/format-usdc usd) "—")]])

(defn- cost-op
  [glyph]
  [:span {:class ["optimizer-exec-cost-op"]} glyph])

(defn- cost-breakdown
  "Per-row execution-cost components for the row's effective type, via the type-aware
  effective-crossing-cost: Market crosses at the one-shot walk; TWAP pays the sliced
  model (impact spread across venue suborders + permanent-impact residue); resting
  Limit/Passive pay neither, + maker fee. A crossing row whose book can't be split
  (untrusted snapshot / flat fallback / prebaked) is `splittable?`=false: spread/impact
  are unknown (nil, not a deceptive 0), and the strip collapses them into a single
  honest note. `floor?` marks depth-overrun estimates (capped lower bounds)."
  [model row]
  (let [{:keys [crossing? slippage-bps estimated-slippage-usd spread-bps spread-usd
                impact-bps impact-usd estimate-floor? twap-adjusted? suborders
                slice-exceeds-visible-depth?]} (shared/effective-crossing-cost model row)
        cost (:cost row)
        splittable? (and crossing? (some? spread-usd))
        price-cost-usd (if crossing? (or estimated-slippage-usd 0) 0)
        price-cost-bps (if crossing? (or slippage-bps 0) 0)
        ;; An absent fee is UNKNOWN, not zero. A resting row pays no spread or impact, so
        ;; coercing its missing maker fee to 0 would render "$0.00 all-in" for a real
        ;; order; nil flows through to "—" and suppresses the all-in total instead.
        fee-usd (if crossing? (:estimated-fee-usd cost) (:maker-fee-usd cost))
        fee-bps (if crossing? (:fee-bps cost) (:maker-fee-bps cost))
        fee-known? (shared/finite fee-usd)]
    {:crossing? crossing?
     :splittable? splittable?
     :floor? (boolean (and crossing? estimate-floor?))
     :fee-known? fee-known?
     :twap-adjusted? (boolean twap-adjusted?)
     :suborders suborders
     :slice-exceeds-visible-depth? (boolean slice-exceeds-visible-depth?)
     :spread-bps (when splittable? spread-bps)
     :spread-usd (when splittable? spread-usd)
     :impact-bps (when splittable? impact-bps)
     :impact-usd (when splittable? impact-usd)
     :price-cost-bps price-cost-bps :price-cost-usd price-cost-usd
     :fee-bps fee-bps :fee-usd fee-usd
     :all-in-bps (when fee-known? (+ price-cost-bps (or fee-bps 0)))
     :all-in-usd (when fee-known? (+ price-cost-usd fee-usd))}))

(defn- cost-breakdown-strip
  "The right-hand column of the expanded editor: the execution-cost equation laid out across
  the full width — spread crossing + book impact = price cost, + fees = all-in (each in bp and
  $). A resting Limit/Passive row pays neither spread nor impact, so those two terms collapse
  into a single \"rests\" note and the price cost reads ~0. A TWAP row's impact term is the
  sliced estimate and the strip says how it is worked; a depth-overrun (floor) estimate is
  labeled as a lower bound."
  [model row]
  (let [{:keys [crossing? splittable? floor? fee-known? twap-adjusted? suborders
                slice-exceeds-visible-depth? spread-bps spread-usd impact-bps impact-usd
                price-cost-bps price-cost-usd fee-bps fee-usd all-in-bps all-in-usd]}
        (cost-breakdown model row)
        twap-row? (= :twap (shared/effective-type model row))
        twap-note (cond
                    twap-adjusted?
                    (str "worked as " suborders " clips over "
                         (:twap-min (shared/row-params model row))
                         "m — book refills between clips"
                         (when slice-exceeds-visible-depth?
                           " · each clip still exceeds the visible book"))

                    ;; Slicing a flat assumption would fabricate precision, so twap-cost
                    ;; passes it through — which makes this tile read identically to
                    ;; Market. Say why instead of leaving two equal numbers unexplained.
                    twap-row?
                    "no live book to slice — this is the same flat estimate a Market order pays")
        floor-note (when floor?
                     "order exceeds visible book depth — price cost is a floor (≥), not a point estimate")
        fee-note (when-not fee-known?
                   "fee unknown for this order type — not included in the all-in total")]
    [:div {:class ["optimizer-exec-cost-panel"]
           :data-role "portfolio-optimizer-execution-cost-breakdown"}
     [:p {:class ["optimizer-exec-cost-head"]}
      [:span "Execution cost breakdown (est.)"]
      [:span {:class ["optimizer-exec-cost-info"]
              :title (str "Price cost = crossing the spread + walking the book (impact). All-in "
                          "adds exchange fees. Resting Limit/Passive orders pay neither spread "
                          "nor impact and earn the lower maker fee. TWAP works the order as venue "
                          "suborders — every 30s at most, wider when a clip would fall under $10 "
                          "— so its impact estimate is the sliced cost, not one full-size walk.")}
       "ⓘ"]]
     [:div {:class ["optimizer-exec-cost-eq"]}
      (cond
        ;; A crossing row with a real book: show the spread vs impact split.
        splittable?
        (list (cost-stat "Spread crossing" spread-bps spread-usd :input)
              (cost-op "+")
              (cost-stat (if twap-adjusted? "Book impact (worked)" "Book impact")
                         impact-bps impact-usd :input))

        ;; A crossing row whose book can't be split (untrusted snapshot / flat fallback):
        ;; the spread is unknown — say so honestly instead of rendering a deceptive 0 bp.
        crossing?
        [:div {:class ["optimizer-exec-cost-rests"]
               :data-role "portfolio-optimizer-execution-cost-unsplit"}
         [:span {:class ["optimizer-exec-cost-stat-label"]} "Spread + impact"]
         [:span {:class ["optimizer-exec-cost-rests-note"]}
          "Not separable — flat estimate (no live book)"]]

        ;; A resting Limit/Passive row pays neither.
        :else
        [:div {:class ["optimizer-exec-cost-rests"]}
         [:span {:class ["optimizer-exec-cost-stat-label"]} "Resting order"]
         [:span {:class ["optimizer-exec-cost-rests-note"]} "No spread or market impact"]])
      (cost-op "=")
      (cost-stat "Price cost" price-cost-bps price-cost-usd :total)
      (cost-op "+")
      (cost-stat "Fees" fee-bps fee-usd :input)
      (cost-op "=")
      (cost-stat "All-in" all-in-bps all-in-usd :allin)]
     (when (or twap-note floor-note fee-note)
       [:p {:class ["mt-1" "font-mono" "text-[0.62rem]" "text-trading-muted/80"]
            :data-role "portfolio-optimizer-execution-cost-note"}
        (str/join " · " (remove nil? [twap-note floor-note fee-note]))])]))

(defn- order-editor-row
  [model row colspan]
  (let [t (shared/effective-type model row)
        rec (shared/recommend-exec-type row)
        params (shared/row-params model row)
        buy? (= :buy (:side row))
        row-id (:row-id row)
        source (cost-source-label row)
        rec-reason-text (rec-reason row rec)]
    [:tr {:data-role (str "portfolio-optimizer-execution-order-editor-" (data-role-token (:instrument-id row)))}
     [:td {:colspan colspan :class ["optimizer-exec-order-editor"]}
      [:div {:class ["optimizer-exec-editor-grid"]}
       ;; LEFT — order-type controls + plain-English consequence
       [:div {:class ["optimizer-exec-editor-controls"]}
        [:div {:class ["flex" "flex-wrap" "items-center" "gap-3"]}
         [:span {:class ["font-mono" "text-[0.6rem]" "uppercase" "tracking-[0.08em]" "text-trading-muted"]}
          (str "Order type · " (:instrument-label row))]
         [:div {:class ["optimizer-exec-toggle" "inline-flex"]}
          (for [ot shared/order-types]
            [:button {:type "button"
                      :class (cond-> ["px-2.5" "py-1" "text-[0.65rem]" "font-medium"]
                               (= t ot) (conj "is-on"))
                      :data-active (str (= t ot))
                      :on {:click [[:actions/set-portfolio-optimizer-execution-row-order-type row-id ot]]}}
             (shared/order-type-labels ot)])]
         (if (not= t rec)
           [:button {:type "button"
                     :class ["font-mono" "text-[0.65rem]" "text-warning"]
                     :on {:click [[:actions/set-portfolio-optimizer-execution-row-order-type row-id :recommended]]}}
            (str "↺ use recommended (" (shared/order-type-labels rec) ")")]
           (shared/chip "recommended" :accent))]
        [:div {:class ["flex" "flex-wrap" "items-center" "gap-2" "text-xs" "text-trading-muted"]}
         (case t
           :market
           [:span "Crosses the spread immediately — full size as one marketable order."]
           :limit
           [:span {:class ["flex" "flex-wrap" "items-center" "gap-2"]}
            [:span {:class ["font-mono" "text-[0.6rem]" "uppercase" "tracking-[0.06em]" "text-trading-muted/70"]}
             "Limit price"]
            (for [[label bp] [["At mid" 0]
                              [(str (if buy? "−" "+") "2 bp") (if buy? -2 2)]
                              [(str (if buy? "−" "+") "5 bp") (if buy? -5 5)]]]
              [:button {:type "button"
                        :class (cond-> ["border" "border-base-300" "px-2" "py-0.5" "text-[0.65rem]"]
                                 (= (:limit-bps params) bp) (conj "optimizer-primary-action" "font-semibold"))
                        :on {:click [[:actions/set-portfolio-optimizer-execution-row-param row-id :limit-bps bp]]}}
               label])
            [:span {:class ["font-mono" "text-trading-muted/70"]}
             (str "rests " (if buy? "below" "above") " mark · GTC")]]
           :twap
           [:span {:class ["flex" "flex-wrap" "items-center" "gap-2"]}
            [:span {:class ["font-mono" "text-[0.6rem]" "uppercase" "tracking-[0.06em]" "text-trading-muted/70"]}
             "Duration"]
            (for [m [5 10 20]]
              [:button {:type "button"
                        :class (cond-> ["border" "border-base-300" "px-2" "py-0.5" "text-[0.65rem]"]
                                 (= (:twap-min params) m) (conj "optimizer-primary-action" "font-semibold"))
                        :on {:click [[:actions/set-portfolio-optimizer-execution-row-param row-id :twap-min m]]}}
               (str m " min")])
            ;; Clip count AND gap from the venue's real slice model: 30s is a spacing
            ;; FLOOR, so a small order over a long runtime is not "one every 30s" — the
            ;; label prices the notional-aware count the cost model also charges.
            [:span {:class ["font-mono" "text-trading-muted/70"]}
             (shared/twap-clip-schedule-label row (:twap-min params))]]
           [:span "Post-only at the best price — never crosses the spread, re-pegs as the book moves."])]
        [:p {:class ["font-mono" "text-[0.65rem]" "text-trading-muted/70"]}
         (str "Recommended: " (shared/order-type-labels rec) " — " rec-reason-text)]
        (when (:exit? row)
          [:div {:class ["flex" "flex-wrap" "items-center" "gap-2"]}
           [:span {:class ["font-mono" "text-[0.65rem]" "text-warning"]}
            "Exit — closes this held position to zero (not an optimizer target)."]
           [:button {:type "button"
                     :class ["font-mono" "text-[0.65rem]" "text-warning" "underline"]
                     :data-role "portfolio-optimizer-execution-exit-revert"
                     :on {:click [[:actions/set-portfolio-optimizer-execution-exit
                                   [(:instrument-id row)] false]]}}
            "↺ keep holding — remove this close"]])
        (when source
          ;; Cost-basis provenance lifted off the lowest 50% opacity / 0.6rem floor so the
          ;; trust signal (snapshot / orderbook / proxy) is actually readable.
          [:p {:class ["font-mono" "text-[0.65rem]" "text-trading-muted/70"]}
           (str "Cost basis · " source)])]
       ;; RIGHT — execution-cost equation across the remaining width
       (cost-breakdown-strip model row)]]]))

(def ^:private amend-order-type-labels
  ;; TWAP is not offered — slicing one live order over time is not an in-place amend.
  [[:limit "Limit"] [:passive "Passive maker"] [:market "Market"]])

(defn- amend-bps-presets
  "Offset presets for repricing a working limit order, signed for the order's own side
  (a buy rests below the mark). 0 = at the live mark — the usual reason to amend."
  [buy?]
  (let [sign (if buy? -1 1)
        arrow (if buy? "−" "+")]
    (into [["At mark" 0]]
          (map (fn [n] [(str arrow n " bp") (* sign n)]) [2 5 10]))))

(defn- format-price
  [value]
  (when (shared/finite value)
    (opt-format/format-decimal value {:maximum-fraction-digits 6})))

(defn- amend-editor-row
  "Inline editor for a WORKING (resting/open) order: reprice it at a new offset from
  the LIVE mark (or post-only at the touch), or convert it to a market order for the
  remaining size. Committing cancels the resting order first and only then submits the
  replacement (halt-on-cancel-failure), via the amend action."
  [model row colspan]
  (let [amend (:amend row)
        t (:order-type amend)
        bps (:limit-bps amend)
        buy? (= :buy (:side row))
        row-id (:row-id row)
        remaining (:remaining-size amend)
        facts (->> [(when-let [px (format-price (:limit-px amend))]
                      (str "resting at " px))
                    (when (shared/finite remaining)
                      (str (opt-format/format-decimal remaining
                                                      {:maximum-fraction-digits 4})
                           " remaining"))
                    (when-let [mark (format-price (:live-mark amend))]
                      (str "live mark " mark))]
                   (remove nil?)
                   (str/join " · "))]
    [:tr {:data-role (str "portfolio-optimizer-execution-order-amend-"
                          (data-role-token (:instrument-id row)))}
     [:td {:colspan colspan :class ["optimizer-exec-order-editor"]}
      [:div {:class ["optimizer-exec-editor-controls"]}
       [:div {:class ["flex" "flex-wrap" "items-center" "gap-3"]}
        [:span {:class ["font-mono" "text-[0.6rem]" "uppercase" "tracking-[0.08em]"
                        "text-trading-muted"]}
         (str "Amend working order · " (:instrument-label row))]
        [:div {:class ["optimizer-exec-toggle" "inline-flex"]}
         (for [[ot label] amend-order-type-labels]
           [:button {:type "button"
                     :class (cond-> ["px-2.5" "py-1" "text-[0.65rem]" "font-medium"]
                              (= t ot) (conj "is-on"))
                     :data-active (str (= t ot))
                     :on {:click [[:actions/set-portfolio-optimizer-execution-row-order-type
                                   row-id ot]]}}
            label])]
        (when (seq facts)
          [:span {:class ["font-mono" "text-[0.65rem]" "text-trading-muted/80"]
                  :data-role "portfolio-optimizer-execution-amend-facts"}
           facts])]
       [:div {:class ["flex" "flex-wrap" "items-center" "gap-2" "text-xs"
                      "text-trading-muted"]}
        (case t
          :limit
          [:span {:class ["flex" "flex-wrap" "items-center" "gap-2"]}
           [:span {:class ["font-mono" "text-[0.6rem]" "uppercase" "tracking-[0.06em]"
                           "text-trading-muted/70"]}
            "New price"]
           (for [[label bp] (amend-bps-presets buy?)]
             [:button {:type "button"
                       :class (cond-> ["border" "border-base-300" "px-2" "py-0.5"
                                       "text-[0.65rem]"]
                                (= bps bp) (conj "optimizer-primary-action"
                                                 "font-semibold"))
                       :on {:click [[:actions/set-portfolio-optimizer-execution-row-param
                                     row-id :limit-bps bp]]}}
              label])
           [:span {:class ["font-mono" "text-trading-muted/70"]}
            (str "re-places " (if (zero? (or bps 0))
                                "at the live mark"
                                (str "off the live mark")) " · GTC")]]
          :market
          [:span "Cancels the resting order and crosses immediately for the remaining size."]
          [:span "Cancels and re-posts post-only at the best price on your side — never crosses."])]
       [:div {:class ["flex" "flex-wrap" "items-center" "gap-3"]}
        [:button {:type "button"
                  :class ["optimizer-primary-action" "px-3" "py-1" "text-[0.7rem]"
                          "font-semibold"]
                  :disabled (boolean (:submitting? model))
                  :data-role "portfolio-optimizer-execution-amend-commit"
                  :on {:click [[:actions/amend-portfolio-optimizer-execution-order row-id]]}}
         "Update order"]
        [:span {:class ["font-mono" "text-[0.62rem]" "text-trading-muted/70"]}
         "Cancels the resting order first — if the cancel fails, nothing new is sent."]]]]]))

(defn- slip-cell
  "Type-aware slippage display. After a fill the realized slippage (vs the same mark the
  estimate used) takes over. Pre-fill: resting orders (limit/passive) read \"rests\" rather
  than the book-crossing market-impact estimate — which would badly overstate their cost;
  crossing orders show their EFFECTIVE-type estimate (a TWAP row shows the sliced TWAP
  figure, not the one-shot walk it will never pay), prefixed \"≥\" when the estimate is a
  depth-overrun floor; non-ready rows show \"—\"."
  [model row order-type status realized-slip]
  (cond
    (shared/finite realized-slip)
    [:span {:title "Realized fill vs the mark the estimate used."} (shared/format-bps realized-slip)]

    (and (= :ready status) (shared/resting-type? order-type))
    [:span {:title "Resting order — pays the spread/offset, not market impact; may not fully fill."}
     "rests"]

    :else
    (let [{:keys [crossing? slippage-bps estimate-floor?]}
          (shared/effective-crossing-cost model row)
          bps (if crossing? slippage-bps (get-in row [:cost :slippage-bps]))]
      (if (and crossing? estimate-floor?)
        [:span {:title "Order exceeds visible book depth — capped lower bound, not a point estimate."}
         (shared/floor-prefixed true (shared/format-bps bps))]
        (shared/format-bps bps)))))

(defn- order-row
  [{:keys [open-row] :as model} index row]
  (let [editable (editable? model)
        amendable (boolean (get-in row [:amend :amendable?]))
        clickable (or editable amendable)
        open? (and clickable (= open-row (:row-id row)))
        display-state (row-display-state model row)
        t (shared/effective-type model row)
        overridden? (contains? (:overrides model) (:row-id row))
        side (:side row)
        buy? (= :buy side)
        notional (:delta-notional-usd row)
        row-tr
        [:tr {:class (cond-> ["optimizer-exec-order-row"]
                       (= :failed display-state) (conj "is-failed")
                       (contains? #{:blocked :skipped} display-state) (conj "is-muted")
                       open? (conj "is-open"))
              :data-role (str "portfolio-optimizer-execution-order-row-" (data-role-token (:instrument-id row)))
              :data-exec-state (name display-state)
              :on (when clickable
                    {:click [[:actions/toggle-portfolio-optimizer-execution-row (:row-id row)]]})}
         [:td {:class ["optimizer-exec-glyph"] :data-state (name display-state)}
          (state-glyph display-state)]
         [:td {:class ["num" "text-trading-muted/70"]} (opt-format/format-decimal (or (:order-no row) (inc index)) {:maximum-fraction-digits 0})]
         ;; The venue is stated once above the table (every order routes to Hyperliquid);
         ;; only the per-row perp/spot kind stays, folded into the asset cell.
         [:td
          [:span {:class ["font-mono" "font-semibold" "text-trading-text"]} (:instrument-label row)]
          (when (:instrument-type row)
            [:span {:class ["optimizer-table-kind-badge" "ml-1.5"]
                    :data-kind (name (:instrument-type row))}
             (name (:instrument-type row))])
          ;; A closing order staged for a held asset the optimizer excluded (by the
          ;; trader or the auto-exit preference) — marked so it never reads as an
          ;; allocator decision. Reverted from the row editor.
          (when (:exit? row)
            [:span {:class ["ml-1.5" "inline-block"]
                    :title "Exit — the optimizer expressed no target for this held position; this order closes it to zero. Revert from the row editor."}
             (shared/chip "exit" :warn)])]
         [:td (shared/chip (name (or side :flat)) (side-tone side))]
         [:td
          [:span {:class ["inline-flex" "items-center" "gap-1.5"]}
           (when overridden? [:span {:class ["optimizer-exec-override-dot"]}])
           [:span {:class (cond-> ["optimizer-exec-type-chip"] overridden? (conj "is-overridden"))}
            (shared/order-type-labels t)]
           (when clickable [:span {:class ["text-[0.6rem]" "text-trading-muted/70"]} (if open? "▾" "▸")])]
          (when-let [hint (route-hint model row t)]
            [:span {:class ["block" "mt-0.5" "font-mono" "text-[0.62rem]" "text-trading-muted/80"]
                    :data-role "portfolio-optimizer-execution-route-hint"}
             hint])]
         [:td {:class ["num" "right"]} (opt-format/format-decimal (:quantity row) {:maximum-fraction-digits 4})]
         ;; Exact dollar notional (not $Nk-rounded) on the row the trader vets before arming,
         ;; matching the rebalance preview's precision so the same order reads the same in both.
         [:td {:class ["num" "right" (if buy? "text-trading-green" "text-trading-red")]
               :title "Order notional"}
          (str (if buy? "+" "−") (opt-format/format-usdc (shared/abs-num notional)))]
         [:td {:class ["num" "right" "text-trading-muted"]}
          (slip-cell model row t (:status row) (get-in row [:realized :slippage-bps]))]
         [:td {:class ["text-[0.7rem]"]} (state-cell display-state row)]]]
    (if open?
      [row-tr (if amendable
                (amend-editor-row model row 9)
                (order-editor-row model row 9))]
      [row-tr])))

(defn- row-visible?
  [order-filter display-state]
  (case order-filter
    ;; A resting order is a live, working order on the book — surface it under "Working".
    :working (contains? #{:queued :working :resting} display-state)
    :filled (= :filled display-state)
    true))

(defn- order-filter-toggle
  [active]
  (into [:div {:class ["optimizer-exec-toggle" "inline-flex"]
               :data-role "portfolio-optimizer-execution-order-filter"}]
        (for [[id label] [[:all "All"] [:working "Working"] [:filled "Filled"]]]
          [:button {:type "button"
                    :class (cond-> ["px-2.5" "py-1" "text-[0.65rem]" "font-medium"]
                             (= active id) (conj "is-on"))
                    :data-active (str (= active id))
                    :on {:click [[:actions/set-portfolio-optimizer-execution-order-filter id]]}}
           label])))

(defn- venue-note
  "The venue stated ONCE for the whole list (every optimizer order routes to
  Hyperliquid), with the perp/spot mix of the sendable rows — replacing a Venue column
  that repeated \"Hyperliquid\" on every row."
  [model rows]
  (let [kinds (->> rows
                   (remove #(= :skipped (row-display-state model %)))
                   (keep :instrument-type)
                   frequencies)
        parts (cond-> []
                (pos? (get kinds :perp 0)) (conj (str (get kinds :perp) " perp"))
                (pos? (get kinds :spot 0)) (conj (str (get kinds :spot) " spot")))]
    (when (seq parts)
      (str "All orders route to Hyperliquid — " (str/join " · " parts) "."))))

(defn- exit-toggle-action
  [rows exit?]
  [[:actions/set-portfolio-optimizer-execution-exit (mapv :instrument-id rows) exit?]])

(defn- auto-exit-setting-strip
  "The persisted auto-exit preference (browser-local, survives sessions): stage
  closing orders for held perp positions removed from the allocation. Rendered
  whenever the surface is pre-run and the decision applies (any exit-staged or
  still-holdable row exists) — including when auto-exit already staged every
  candidate, so the preference can always be found and turned off right where its
  effect shows."
  [model rows]
  (let [relevant? (some #(and (= :perp (:instrument-type %))
                              (or (:exit? %) (:exitable? %)))
                        rows)
        auto? (boolean (:auto-exit-excluded? model))]
    (when (and (editable? model) relevant?)
      [:div {:class ["border-t" "border-base-300" "px-4" "py-2"]}
       [:label {:class ["flex" "w-fit" "cursor-pointer" "items-center" "gap-1.5"
                        "font-mono" "text-[0.7rem]" "text-trading-muted"]
                :title (str "When on, opening this tab pre-stages closing orders (sell "
                            "longs, buy back shorts) for perp positions you removed from "
                            "the allocation. Spot holdings and assets dropped by the "
                            "engine for data reasons are never auto-closed. Saved in this "
                            "browser as your preference.")
                :data-role "portfolio-optimizer-execution-auto-exit"}
        [:input {:type "checkbox"
                 :class ["optimizer-checkbox"]
                 :checked auto?
                 :on {:change [[:actions/set-portfolio-optimizer-execution-auto-exit
                                (not auto?)]]}}]
        "Close perp positions removed from the allocation"]])))

(defn- skipped-section
  "Within-tolerance / already-live rows collapsed OUT of the order list: they are not
  orders and must not read as 20 orders when 10 will be sent. Each keeps its ledger #,
  its data-role, and a plain-language reason. An :exitable? row (a held asset the
  trader removed from the optimization) additionally offers \"Sell instead\" — staging
  an explicit sell-to-zero into the plan — plus a bulk control when several qualify."
  [model rows]
  (let [skipped (filterv #(= :skipped (row-display-state model %)) rows)
        exitable (filterv :exitable? skipped)]
    (when (seq skipped)
      [:details {:class ["optimizer-exec-skipped" "border-t" "border-base-300"]
                 :replicant/key "portfolio-optimizer-execution-skipped"
                 :data-role "portfolio-optimizer-execution-skipped"}
       [:summary {:class ["px-4" "py-2.5" "text-xs" "font-medium" "text-trading-muted"
                          "select-none" "cursor-pointer"]}
        ;; Held-out assets the trader can exit are a decision, not noise — the summary
        ;; says so while the section stays collapsed (forcing :open every render would
        ;; fight the user's collapse).
        (str "Skipped — " (count skipped) " asset" (when (not= 1 (count skipped)) "s")
             " · no orders will be sent"
             (when (seq exitable)
               (str " · " (count exitable) " can be closed instead")))]
       (when (seq exitable)
         [:div {:class ["flex" "flex-wrap" "items-center" "gap-2" "px-4" "pb-2"
                        "text-xs" "text-trading-muted"]
                :data-role "portfolio-optimizer-execution-exit-note"}
          [:span
           (str (count exitable) " held asset" (when (not= 1 (count exitable)) "s")
                " " (if (= 1 (count exitable)) "isn't" "aren't")
                " part of this allocation and will be held. Stage a closing order"
                " (sell a long, buy back a short) to exit "
                (if (= 1 (count exitable)) "it" "them")
                " with this rebalance.")]
          (when (> (count exitable) 1)
            [:button {:type "button"
                      :class ["border" "border-base-300" "px-2" "py-0.5"
                              "text-[0.65rem]" "font-semibold" "text-trading-text"]
                      :data-role "portfolio-optimizer-execution-exit-all"
                      :on {:click (exit-toggle-action exitable true)}}
             (str "Close all " (count exitable))])])
       [:div {:class ["overflow-x-auto"]}
        (into
         [:table {:class ["optimizer-table" "optimizer-exec-table"]}
          [:thead
           [:tr
            [:th {:class ["w-10"]} "#"]
            [:th "Asset"]
            [:th "Side"]
            [:th {:class ["right"]} "Notional"]
            [:th "Why skipped"]
            [:th {:class ["w-24"]}]]]]
         (for [row skipped]
           [:tbody
            [:tr {:class ["optimizer-exec-order-row" "is-muted"]
                  :data-role (str "portfolio-optimizer-execution-order-row-"
                                  (data-role-token (:instrument-id row)))
                  :data-exec-state "skipped"}
             [:td {:class ["num" "text-trading-muted/70"]}
              (opt-format/format-decimal (:order-no row) {:maximum-fraction-digits 0})]
             [:td
              [:span {:class ["font-mono" "font-semibold" "text-trading-text"]} (:instrument-label row)]
              (when (:instrument-type row)
                [:span {:class ["optimizer-table-kind-badge" "ml-1.5"]
                        :data-kind (name (:instrument-type row))}
                 (name (:instrument-type row))])]
             [:td (let [side (:side row)]
                    ;; :none = an excluded/held row — there is no trade direction, so
                    ;; "hold" reads truthfully where "none" would read as broken data.
                    (if (contains? #{:buy :sell} side)
                      (shared/chip (name side) (side-tone side))
                      (shared/chip "hold" :muted)))]
             [:td {:class ["num" "right" "text-trading-muted"]}
              (if (contains? #{:buy :sell} (:side row))
                (str (if (= :buy (:side row)) "+" "−")
                     (opt-format/format-usdc (shared/abs-num (:delta-notional-usd row))))
                "—")]
             [:td {:class ["text-[0.75rem]"]} (state-cell :skipped row)]
             [:td {:class ["right"]}
              (when (:exitable? row)
                [:button {:type "button"
                          :class ["border" "border-base-300" "px-2" "py-0.5"
                                  "text-[0.65rem]" "font-semibold" "text-trading-text"]
                          :title "Stage an order that closes this held position to zero (sells a long, buys back a short) as part of the rebalance."
                          :data-role (str "portfolio-optimizer-execution-exit-"
                                          (data-role-token (:instrument-id row)))
                          :on {:click (exit-toggle-action [row] true)}}
                 "Close instead"])]]]))]])))

(defn order-table
  [{:keys [order-filter] :as model} rows]
  (let [active-filter (or order-filter :all)
        order-rows (remove #(= :skipped (row-display-state model %)) rows)
        any-visible? (some #(row-visible? active-filter (row-display-state model %)) order-rows)
        venue (venue-note model rows)]
    [:section {:class ["flex" "flex-col" "min-h-0" "xl:border-r" "border-base-300"]
               :data-role "portfolio-optimizer-execution-order-list"}
     [:div {:class ["border-b" "border-base-300" "px-4" "py-3"]}
      [:div {:class ["flex" "flex-wrap" "items-center" "justify-between" "gap-2"]}
       ;; Pre-run the headline counts what will actually send (ready rows); post-run the
       ;; phase bands own the outcome counts, so the list header stays neutral.
       (shared/eyebrow (if (contains? #{:staged :armed} (:phase model))
                         (let [n (count (filter #(= :ready (:status %)) order-rows))]
                           (str "Order list — " n " to send"))
                         "Order list"))
       (order-filter-toggle active-filter)]
      [:p {:class ["mt-1" "text-xs" "text-trading-muted"]}
       (str (when venue (str venue " "))
            (cond
              (editable? model)
              "Click any order to change its type. Blocked rows stay visible with their reason."

              (some #(get-in % [:amend :amendable?]) rows)
              "Click an open order to amend it — reprice it or convert it to market."

              :else
              "Order types are locked once execution is armed."))]]
     ;; Stable slots below: the body alternates between a <table> and an empty-state <p>,
     ;; and the auto-exit strip is nil post-run. A bare tag swap desyncs Replicant's child
     ;; walk, the keyed skipped <details> then lands on the nil, and reconciliation throws
     ;; mid-render — freezing the whole surface, toasts included. One tag, always present.
     [:div {:class (cond-> [] any-visible? (conj "overflow-x-auto"))
            :data-role "portfolio-optimizer-execution-order-list-body"}
      (cond
        (not (seq rows))
        [:p {:class ["px-4" "py-4" "text-sm" "text-trading-muted"]}
         "No orders are staged for this run."]

        (not any-visible?)
        [:p {:class ["px-4" "py-4" "text-sm" "text-trading-muted"]}
         (str "No " (name active-filter) " orders to show.")]

        :else
        (into
         [:table {:class ["optimizer-table" "optimizer-exec-table"]}
          [:thead
           [:tr
            [:th {:class ["w-8"]}]
            [:th {:class ["w-10"]} "#"]
            [:th "Asset"]
            [:th "Side"]
            [:th "Type"]
            [:th {:class ["right"]} "Qty"]
            [:th {:class ["right"]} "Notional"]
            [:th {:class ["right"]} "Cost"]
            [:th "State"]]]]
         ;; index over the full row set so the # column keeps stable order numbers
         ;; even when a filter hides rows.
         (mapcat (fn [index row]
                   (if (and (not= :skipped (row-display-state model row))
                            (row-visible? active-filter (row-display-state model row)))
                     (order-row model index row)
                     []))
                 (range)
                 rows)))]
     [:div {:data-role "portfolio-optimizer-execution-auto-exit-slot"}
      (auto-exit-setting-strip model rows)]
     (skipped-section model rows)]))

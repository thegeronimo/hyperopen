(ns hyperopen.portfolio.optimizer.application.execution
  (:require [clojure.string :as str]
            [hyperopen.api.gateway.orders.commands :as order-commands]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.domain.trading :as trading-domain]
            [hyperopen.portfolio.optimizer.application.execution-order-type :as execution-order-type]
            [hyperopen.portfolio.optimizer.application.orderbook-loader :as orderbook-loader]
            [hyperopen.portfolio.optimizer.coercion :as coercion]
            [hyperopen.portfolio.optimizer.domain.rebalance :as rebalance]))

(def ^:private finite-positive? coercion/positive-number?)

(defn- parse-int-value
  [value]
  (let [num (cond
              (number? value) value
              (string? value) (js/parseInt value 10)
              :else js/NaN)]
    (when (and (number? num)
               (not (js/isNaN num)))
      (js/Math.floor num))))

(def ^:private non-blank-text coercion/non-blank-text)

(defn- ready-row?
  "A row is executable when it is :ready, has a tradeable instrument type, a positive
  lot quantity, a directional side, and a non-zero delta. Both perp and spot legs
  execute on Hyperliquid; the gateway routes by the market metadata (spot markets carry
  the 10000+idx wire asset-id and force reduce-only false)."
  [row]
  (and (= :ready (:status row))
       (contains? #{:perp :spot} (:instrument-type row))
       (finite-positive? (:quantity row))
       (not= :none (:side row))
       (not (zero? (or (:delta-notional-usd row) 0)))))

(defn- intent-for-row
  [execution-assumptions row]
  {:kind (if (= :spot (:instrument-type row)) :spot-order :perp-order)
   :instrument-id (:instrument-id row)
   :side (:side row)
   :quantity (:quantity row)
   :order-type (or (:default-order-type execution-assumptions) :market)
   :reduce-only? false})

(defn- execution-row
  [execution-assumptions row]
  (let [instrument-id (:instrument-id row)
        base-row (cond-> {:row-id instrument-id
                          :instrument-id instrument-id
                          :instrument-type (:instrument-type row)
                          :side (:side row)
                          :quantity (:quantity row)
                          :order-type (or (:default-order-type execution-assumptions) :market)
                          :delta-notional-usd (:delta-notional-usd row)
                          :cost (:cost row)}
                   (some? (:coin row)) (assoc :coin (:coin row))
                   (some? (:price row)) (assoc :price (:price row)))]
    (cond
      (ready-row? row)
      (assoc base-row
             :status :ready
             :intent (intent-for-row execution-assumptions row))

      (and (= :ready (:status row))
           (not (finite-positive? (:quantity row))))
      (assoc base-row
             :status :blocked
             :reason :quantity-below-lot)

      (and (= :ready (:status row))
           (or (= :none (:side row))
               (zero? (or (:delta-notional-usd row) 0))))
      (assoc base-row
             :status :blocked
             :reason :zero-delta-notional)

      (= :within-tolerance (:status row))
      (cond-> (assoc base-row
                     :status :skipped
                     :reason :within-tolerance)
        (some? (:tolerance row)) (assoc :tolerance (:tolerance row)))

      (= :excluded (:status row))
      (assoc base-row
             :status :skipped
             :reason (or (:reason row) :excluded-from-optimization))

      (= :blocked (:status row))
      (assoc base-row
             :status :blocked
             :reason (:reason row))

      (= :ready (:status row))
      (assoc base-row
             :status :blocked
             :reason :unsupported-market-type)

      :else
      (assoc base-row
             :status :blocked
             :reason :unsupported-row-status))))

(defn- plan-status
  [ready-count blocked-count]
  (cond
    (and (pos? ready-count)
         (pos? blocked-count)) :partially-blocked
    (pos? ready-count) :ready
    (pos? blocked-count) :blocked
    :else :no-op))

(defn build-execution-plan
  [{:keys [scenario-id
           rebalance-preview
           execution-assumptions
           mutations-blocked-message]}]
  (let [rows (mapv #(execution-row execution-assumptions %)
                   (:rows rebalance-preview))
        ready-rows (filter #(= :ready (:status %)) rows)
        blocked-rows (filter #(= :blocked (:status %)) rows)
        skipped-rows (filter #(= :skipped (:status %)) rows)
        disabled-message (non-blank-text mutations-blocked-message)]
    {:scenario-id scenario-id
     :status (plan-status (count ready-rows) (count blocked-rows))
     :execution-disabled? (boolean disabled-message)
     :disabled-reason (when disabled-message :read-only)
     :disabled-message disabled-message
     :summary {:ready-count (count ready-rows)
               :blocked-count (count blocked-rows)
               :skipped-count (count skipped-rows)
               :gross-ready-notional-usd
               (reduce + 0 (map #(js/Math.abs (:delta-notional-usd %))
                                ready-rows))
               :estimated-fees-usd (get-in rebalance-preview
                                            [:summary :estimated-fees-usd])
               :estimated-slippage-usd (get-in rebalance-preview
                                                [:summary :estimated-slippage-usd])
               :margin (get-in rebalance-preview [:summary :margin])}
     :rows rows}))

(defn apply-order-type-selections
  "Re-resolves each ready row's order type + params from the live staging selections
  ({:default-order-type :overrides :params}) and stamps them onto the row's :intent
  (and top-level :order-type), so the submitted order matches what the user selected on
  the Execution tab. Non-ready rows are left untouched. Called at confirm time because
  the staged plan is built before the user picks types, and the order form is only
  materialized later (in the effect adapter) from the row's :intent."
  [plan selections]
  (if-not (map? selections)
    plan
    (update plan :rows
            (fn [rows]
              (mapv (fn [row]
                      (if (= :ready (:status row))
                        (let [order-type (execution-order-type/effective-type selections row)
                              params (execution-order-type/row-params selections row)]
                          (-> row
                              (assoc :order-type order-type)
                              (update :intent assoc
                                      :order-type order-type
                                      :limit-bps (:limit-bps params)
                                      :twap-min (:twap-min params))))
                        row))
                    (or rows []))))))

(defn coin-for-row
  "Bare coin token for a plan/ledger row (strips the perp:/spot: prefix), used to match a row
  against the live order/fill feeds, which key on the coin token."
  [row]
  (or (non-blank-text (:coin row))
      (when-let [instrument-id (non-blank-text (:instrument-id row))]
        (cond
          (str/starts-with? instrument-id "perp:")
          (subs instrument-id 5)

          (str/starts-with? instrument-id "spot:")
          (subs instrument-id 5)

          :else instrument-id))))

(defn row-market
  "Catalog market entry for a row (instrument-id key, then coin fallback). Public for
  the amend flow, which re-resolves szDecimals + the live native mark at amend time."
  [market-by-key row]
  (let [instrument-id (:instrument-id row)
        coin (coin-for-row row)]
    (or (get market-by-key instrument-id)
        (markets/resolve-or-infer-market-by-coin market-by-key coin))))

(defn- market-asset-idx
  [market]
  (some parse-int-value [(:asset-id market)
                         (:assetId market)
                         (:idx market)]))

(defn- offset-price
  "Mark price adjusted by a signed bps offset. The limit-bps default rests away from the
  market (buy -2 below mark, sell +2 above), so the resting order does not cross."
  [mark bps]
  (when (finite-positive? mark)
    (* mark (+ 1 (/ (or bps 0) 10000.0)))))

(defn- passive-price
  "Post-only (ALO) limit price that is guaranteed to REST rather than cross. Hyperliquid
  rejects a post-only order that would immediately match (\"Post only order would have
  immediately matched\"), and the mark-relative offset is unreliable for thin HIP-3 perps
  whose native mark sits outside the live BBO -- a 2bp nudge below a mark that is already above
  the best ask still crosses. When the live touch is known (and the book is not crossed), rest
  at the near touch of the order's OWN maker side: a buy joins the best bid, a sell joins the
  best ask -- neither can immediately match. Falls back to the mark-relative offset only when
  the book/touch is unavailable (no worse than the prior behavior)."
  [side mark best-bid best-ask limit-bps]
  (let [touch (case side
                :buy best-bid
                :sell best-ask
                nil)]
    (if (and (finite-positive? best-bid)
             (finite-positive? best-ask)
             (< best-bid best-ask)
             (finite-positive? touch))
      touch
      (offset-price mark limit-bps))))

(defn- marketable-price
  "Aggressive IOC price that actually CROSSES the book: start from the OPPOSITE-side
  touch when the live book is known (buy -> best ask, sell -> best bid — the manual
  ticket's apply-market-price), else the mark, and pad by the default market slippage
  tolerance. A bare-mark IOC on a thin named-dex book whose touch sits outside the
  mark matches nothing and Hyperliquid rejects it ('Order could not immediately match
  against any resting orders') — cancelling the row's original order for nothing when
  the market order came from an amend."
  [side mark best-bid best-ask]
  (let [touch (case side
                :buy best-ask
                :sell best-bid
                nil)
        reference (if (finite-positive? touch) touch mark)
        pad (/ trading-domain/default-market-slippage-pct 100)
        adj (if (= :buy side) (+ 1 pad) (- 1 pad))]
    (when (finite-positive? reference)
      (* reference adj))))

(defn- order-form-for-row
  "Translates a ready row's resolved :intent into the order-gateway form. The four UI
  types map onto the gateway as: :market -> marketable IOC priced to cross (opposite-side
  touch, or mark, padded by the market slippage tolerance); :limit -> resting
  GTC at mark +/- limit-bps; :passive -> post-only (ALO) limit priced to rest at the live
  book's own-side touch (so it never crosses); :twap -> twapOrder over twap-min minutes. Any
  unmapped type falls back to :market so it can never leak to build-order-request and become a
  stray resting GTC limit. `bbo` carries the live {:best-bid :best-ask} for the passive and
  marketable prices."
  [row bbo]
  (let [intent (:intent row)
        order-type (or (:order-type intent) :market)
        mark (:price row)
        base {:side (:side intent)
              :size (:quantity intent)
              :reduce-only (boolean (:reduce-only? intent))
              :margin-mode :cross}]
    (case order-type
      :limit (assoc base
                    :type :limit
                    :tif :gtc
                    :price (offset-price mark (:limit-bps intent)))
      :passive (assoc base
                      :type :limit
                      :post-only true
                      :price (passive-price (:side intent) mark
                                            (:best-bid bbo) (:best-ask bbo)
                                            (:limit-bps intent)))
      :twap (assoc base
                   :type :twap
                   :twap {:minutes (max 5 (or (:twap-min intent) 10))
                          :randomize true})
      (assoc base :type :market
             :price (marketable-price (:side intent) mark
                                      (:best-bid bbo) (:best-ask bbo))))))

(defn- wire-size-string
  "Floors the order quantity to the catalog market's szDecimals and formats it as a clean
  wire decimal string (mirrors the manual order form's base-size-string). The optimizer
  derives quantity = |notional| / price as a raw float, so without this the wire :s carries
  14-17 sig figs (e.g. SILVER szDecimals 2 -> \"1.089665624397716\"); Hyperliquid deserializes
  :s into a precision-bounded decimal and rejects an over-precise size with \"Failed to
  deserialize the JSON body into the target type\". Returns nil when the size floors below one
  lot (non-positive)."
  [market size]
  (trading-domain/base-size-string {:market market} (coercion/parse-float-number size)))

(defn- order-request-for-row
  [{:keys [market-by-key orderbooks cloid-fn]} row]
  (let [market (row-market market-by-key row)
        coin (coin-for-row row)
        asset-idx (market-asset-idx market)]
    (cond
      (nil? market)
      {:blocked-reason :market-metadata-missing}

      (nil? asset-idx)
      {:blocked-reason :market-metadata-missing}

      (not (finite-positive? (:price row)))
      {:blocked-reason :missing-price}

      :else
      (let [book (get orderbooks coin)
            bbo {:best-bid (orderbook-loader/best-bid-price book)
                 :best-ask (orderbook-loader/best-ask-price book)}
            command-context {:active-asset coin
                             :asset-idx asset-idx
                             :market market
                             :orderbook book}
            form (order-form-for-row row bbo)
            size-text (wire-size-string market (:size form))
            cloid (when cloid-fn (cloid-fn))]
        (if-not (non-blank-text size-text)
          {:blocked-reason :quantity-below-lot}
          (let [request (order-commands/build-order-request
                         command-context
                         (cond-> (assoc form :size size-text)
                           (non-blank-text cloid) (assoc :cloid cloid)))]
            (if (map? request)
              {:request request}
              {:blocked-reason :request-unavailable})))))))

(defn- attempt-row
  [opts row]
  (if (= :ready (:status row))
    (let [{:keys [request blocked-reason]} (order-request-for-row opts row)]
      (if (map? request)
        (assoc row :request request)
        (-> row
            (assoc :status :blocked
                   :reason blocked-reason)
            (dissoc :intent))))
    row))

(defn build-execution-attempt
  [{:keys [plan market-by-key orderbooks cloid-fn]}]
  (let [rows (mapv #(attempt-row {:market-by-key (or market-by-key {})
                                  :orderbooks (or orderbooks {})
                                  :cloid-fn cloid-fn}
                                 %)
                   (:rows plan))
        ready-count (count (filter #(= :ready (:status %)) rows))
        blocked-count (count (filter #(= :blocked (:status %)) rows))
        skipped-count (count (filter #(= :skipped (:status %)) rows))]
    (assoc plan
           :status (plan-status ready-count blocked-count)
           :summary (assoc (:summary plan)
                           :ready-count ready-count
                           :blocked-count blocked-count
                           :skipped-count skipped-count)
           :rows rows)))

(defn response-statuses
  "Per-order status entries from an order response. Each optimizer row submits exactly one
   order, so the singular [:response :data :status] is the legacy fallback for the vector."
  [resp]
  (let [statuses (get-in resp [:response :data :statuses])
        status (get-in resp [:response :data :status])]
    (cond
      (sequential? statuses) statuses
      (some? status) [status]
      :else [])))

(defn response-ok?
  [resp]
  (and (= "ok" (:status resp))
       (not-any? #(and (map? %)
                       (contains? % :error))
                 (response-statuses resp))))

(defn- status-entry-has?
  [resp status-key]
  (some #(and (map? %) (contains? % status-key))
        (response-statuses resp)))

(def ^:private post-only-cross-bbo-re
  #"would have immediately matched.*?bbo was\s+([0-9]+(?:\.[0-9]+)?)@([0-9]+(?:\.[0-9]+)?)")

(defn post-only-cross-bbo
  "When `resp` is a Hyperliquid post-only (ALO) rejection for crossing, returns the live touch
   {:bid <string> :ask <string>} parsed from its 'bbo was <bid>@<ask>' message; nil otherwise.
   The strings are canonical exchange prices, usable verbatim as a wire `:p`."
  [resp]
  (let [error (some-> (first (response-statuses resp)) :error str)
        match (when (non-blank-text error)
                (re-find post-only-cross-bbo-re error))]
    (when match
      {:bid (nth match 1)
       :ask (nth match 2)})))

(defn reprice-post-only-action
  "Reprices a rejected single-order post-only action to REST at the live touch from the
   rejection bbo -- a buy joins the bid, a sell joins the ask -- so a resubmit no longer crosses.
   The book can move between price computation and submission, so the freshest source of the
   touch is the rejection itself. Returns the repriced action, or nil when it is not a single
   repriceable post-only order. The bbo strings are canonical exchange prices, used verbatim."
  [action {:keys [bid ask]}]
  (let [orders (:orders action)
        order (first orders)
        post-only? (= "Alo" (get-in order [:t :limit :tif]))
        rest-price (if (:b order) bid ask)]
    (when (and (= 1 (count orders))
               post-only?
               (non-blank-text rest-price))
      (assoc-in action [:orders 0 :p] rest-price))))

(defn settled-row-status
  "Terminal row status for an OK order response (callers gate on `response-ok?` first).

   Hyperliquid reports per-order outcomes in [:response :data :statuses]: a `:filled` entry
   means the order crossed and (fully or partially) executed; a `:resting` entry means it was
   accepted and is sitting on the book as an open order, NOT filled. A passive/post-only limit
   order that does not cross returns `:resting` only — so it must read as resting, never as a
   fill. An OK response with neither entry (legacy/empty status shape, e.g. the string
   \"success\") falls back to :submitted so immediate-cross orders keep their prior behavior."
  [resp]
  (cond
    (status-entry-has? resp :filled) :submitted
    (status-entry-has? resp :resting) :resting
    :else :submitted))

(defn realized-from-avg
  "Realized fill map from a known average fill price + filled quantity, measured against
   (:price row). Shared by the settled-response path (`realized-fill`) and the live-fill
   reconciliation overlay (which derives avg-px/qty from [:orders :fills]) so both produce an
   identical :realized shape. Returns nil when no average fill price is available."
  [row avg-px filled-qty]
  (when (coercion/positive-number? avg-px)
    (let [bps (rebalance/realized-slippage-bps (:side row) (:price row) avg-px)
          ref-px (:price row)
          filled-notional (if (and (coercion/positive-number? filled-qty)
                                   (coercion/positive-number? ref-px))
                            (* filled-qty ref-px)
                            (js/Math.abs (or (:delta-notional-usd row) 0)))]
      (cond-> {:avg-px avg-px}
        (some? bps) (assoc :slippage-bps bps
                           :slippage-usd (* filled-notional (/ bps 10000)))))))

(defn realized-fill
  "Realized average fill + slippage from a settled order response, measured against
   (:price row) -- the same reference the slippage ESTIMATE used. One order is submitted
   per row, so the single status entry is at index 0. A post-only order that only rests has
   no :filled entry, so realized stays pending (nil) rather than reading as 0. Returns nil
   when no average fill price is available."
  [row resp]
  (let [status-entry (first (response-statuses resp))
        avg-px (some-> (get-in status-entry [:filled :avgPx])
                       coercion/parse-float-number)
        filled-qty (some-> (get-in status-entry [:filled :totalSz])
                           coercion/parse-float-number)]
    (realized-from-avg row avg-px filled-qty)))

(defn resting-oid
  "The exchange order id of a row that rested on the book (status :resting), read from the
   settled response at [:response :data :statuses 0 :resting :oid]. nil for any non-resting row
   or a row whose response carries no resting entry. The id is the key the live order/fill feeds
   ([:orders :open-orders] / [:orders :fills]) use, so it links a frozen ledger row to its live
   fate."
  [row]
  (when (= :resting (:status row))
    (get-in (first (response-statuses (:response row))) [:resting :oid])))

(defn final-ledger-status
  [rows]
  (let [filled-count (count (filter #(= :submitted (:status %)) rows))
        resting-count (count (filter #(= :resting (:status %)) rows))
        failed-count (count (filter #(= :failed (:status %)) rows))
        blocked-count (count (filter #(= :blocked (:status %)) rows))
        accepted-count (+ filled-count resting-count)]
    (cond
      (and (pos? failed-count) (pos? accepted-count)) :partially-executed
      (pos? failed-count) :failed
      (and (pos? filled-count) (zero? resting-count)) :executed
      (pos? accepted-count) :resting
      (pos? blocked-count) :blocked
      :else :no-op)))

(defn- recoverable-row
  "Maps one ledger-attempt row into a fresh row for a Resume attempt. Failed rows are
  reset to :ready (dropping the stale request/response so a fresh request is built),
  already-submitted rows become :skipped :already-filled and already-resting rows become
  :skipped :already-resting — both already live on the exchange, so they can NEVER be
  re-submitted — and everything else passes through unchanged."
  [row]
  (case (:status row)
    :failed (-> row
                (assoc :status :ready)
                (dissoc :request :response :error :pre-action-responses))
    :submitted (-> row
                   (assoc :status :skipped :reason :already-filled)
                   (dissoc :intent :request :response :error :pre-action-responses))
    :resting (-> row
                 (assoc :status :skipped :reason :already-resting)
                 (dissoc :intent :request :response :error :pre-action-responses))
    row))

(defn build-resume-plan
  "Builds a retry plan from the original plan + the latest execution ledger. Only
  still-recoverable rows (previously :failed) are armed :ready; already-:submitted rows
  are demoted to :skipped :already-filled so a Resume can never double-submit a filled
  order. Summary counts and gross-ready notional are recomputed from the recovered set."
  [plan ledger]
  (let [rows (mapv recoverable-row (:rows ledger))
        ready-rows (filter #(= :ready (:status %)) rows)
        blocked-rows (filter #(= :blocked (:status %)) rows)
        skipped-rows (filter #(= :skipped (:status %)) rows)]
    (assoc plan
           :status (plan-status (count ready-rows) (count blocked-rows))
           :summary (assoc (:summary plan)
                           :ready-count (count ready-rows)
                           :blocked-count (count blocked-rows)
                           :skipped-count (count skipped-rows)
                           :gross-ready-notional-usd
                           (reduce + 0 (map #(js/Math.abs (or (:delta-notional-usd %) 0))
                                            ready-rows)))
           :rows rows)))

(defn- opposite-side
  [side]
  (case side :buy :sell :sell :buy side))

(defn- reversing-row
  "Turns one filled (:submitted) ledger row into a fresh :ready row that unwinds it:
  opposite side, same size, reduce-only for perps (spot has no position to reduce so the
  gateway forces reduce-only false anyway). Reverts always cross immediately (:market)."
  [row]
  (let [side (opposite-side (:side row))
        perp? (= :perp (:instrument-type row))]
    (-> (select-keys row [:row-id :instrument-id :instrument-type :coin :price :quantity])
        (assoc :side side
               :delta-notional-usd (- (or (:delta-notional-usd row) 0))
               :status :ready
               :order-type :market
               :intent {:kind (if perp? :perp-order :spot-order)
                        :instrument-id (:instrument-id row)
                        :side side
                        :quantity (:quantity row)
                        :order-type :market
                        :reduce-only? perp?}))))

(defn build-revert-plan
  "Builds a plan that unwinds every filled order from the latest ledger (reversing,
  reduce-only market orders), reusing the same execute path. The ledger is tagged
  :kind :revert so the persisted scenario history is distinguishable from a normal run."
  [plan ledger]
  (let [rows (->> (:rows ledger)
                  (filter #(= :submitted (:status %)))
                  (mapv reversing-row))]
    (assoc plan
           :kind :revert
           :status (plan-status (count rows) 0)
           :summary (assoc (:summary plan)
                           :ready-count (count rows)
                           :blocked-count 0
                           :skipped-count 0
                           :gross-ready-notional-usd
                           (reduce + 0 (map #(js/Math.abs (or (:delta-notional-usd %) 0))
                                            rows)))
           :rows rows)))

(defn- scale-row
  [factor row]
  (if (= :ready (:status row))
    (-> row
        (update :quantity #(when (number? %) (* % factor)))
        (update :delta-notional-usd #(when (number? %) (* % factor)))
        (update :intent (fn [intent]
                          (some-> intent
                                  (update :quantity #(when (number? %) (* % factor)))))))
    row))

(defn build-restaged-plan
  "Rebuilds the staged plan at a smaller clip: rows already live in the latest ledger —
  filled OR resting on the book — are dropped (re-staging one would duplicate a working
  order), and each remaining ready row's quantity + notional are scaled by `factor`
  (e.g. 0.5). Summary counts are recomputed; the result replaces the modal plan and the
  surface returns to :staged for re-review."
  [plan ledger factor]
  (let [live-ids (->> (:rows ledger)
                      (filter #(contains? #{:submitted :resting} (:status %)))
                      (map :row-id)
                      set)
        rows (->> (:rows plan)
                  (remove #(contains? live-ids (:row-id %)))
                  (mapv #(scale-row factor %)))
        ready-rows (filter #(= :ready (:status %)) rows)
        blocked-rows (filter #(= :blocked (:status %)) rows)
        skipped-rows (filter #(= :skipped (:status %)) rows)]
    (assoc plan
           :status (plan-status (count ready-rows) (count blocked-rows))
           :summary (assoc (:summary plan)
                           :ready-count (count ready-rows)
                           :blocked-count (count blocked-rows)
                           :skipped-count (count skipped-rows)
                           :gross-ready-notional-usd
                           (reduce + 0 (map #(js/Math.abs (or (:delta-notional-usd %) 0))
                                            ready-rows)))
           :rows rows)))

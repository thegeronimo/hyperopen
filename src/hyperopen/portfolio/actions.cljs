(ns hyperopen.portfolio.actions
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.portfolio.candle-coverage :as candle-coverage]
            [hyperopen.portfolio.custom-range :as custom-range]
            [hyperopen.portfolio.fee-schedule :as fee-schedule]
            [hyperopen.platform :as platform]))

(def ^:private portfolio-summary-time-range-storage-key
  "portfolio-summary-time-range")

(def ^:private replace-shareable-route-query-effect
  [:effects/replace-shareable-route-query])

(def default-summary-scope
  :all)

(def default-summary-time-range
  :one-year)

(def default-chart-tab
  :returns)

(def ^:private summary-scope-options
  #{:all :perps})

(def ^:private summary-time-range-options
  #{:day :week :month :three-month :six-month :one-year :two-year :all-time})

(def ^:private summary-time-range-aliases
  {:alltime :all-time
   :3m :three-month
   :3-m :three-month
   :3month :three-month
   :3-month :three-month
   :threemonth :three-month
   :three-month :three-month
   :quarter :three-month
   :6m :six-month
   :6-m :six-month
   :6month :six-month
   :6-month :six-month
   :sixmonth :six-month
   :six-month :six-month
   :halfyear :six-month
   :half-year :six-month
   :1y :one-year
   :1-y :one-year
   :1year :one-year
   :1-year :one-year
   :oneyear :one-year
   :one-year :one-year
   :year :one-year
   :2y :two-year
   :2-y :two-year
   :2year :two-year
   :2-year :two-year
   :twoyear :two-year
   :two-year :two-year})

(def ^:private returns-benchmark-candle-request-by-summary-time-range
  {:day {:interval :5m
         :bars 400}
   :week {:interval :15m
          :bars 800}
   :month {:interval :1h
           :bars 800}
   :three-month {:interval :4h
                 :bars 720}
   :six-month {:interval :8h
               :bars 720}
   :one-year {:interval :12h
              :bars 900}
   :two-year {:interval :1d
              :bars 900}
   :all-time {:interval :1d
              :bars 5000}})

(def ^:private chart-tab-options
  #{:account-value :pnl :returns})

(def default-account-info-tab
  :performance-metrics)

(def ^:private account-info-tab-options
  #{:performance-metrics
    :deposits-withdrawals
    :balances
    :positions
    :open-orders
    :twap
    :trade-history
    :funding-history
    :order-history
    :outcomes
    :monte-carlo})

(def ^:private account-info-tab-aliases
  {:performancemetrics :performance-metrics
   :performancemetric :performance-metrics
   :performance :performance-metrics
   :depositswithdrawals :deposits-withdrawals
   :openorders :open-orders
   :tradehistory :trade-history
   :fundinghistory :funding-history
   :orderhistory :order-history
   :montecarlo :monte-carlo
   :monte :monte-carlo})

(defn- normalize-keyword-like
  [value]
  (let [text (cond
               (keyword? value) (name value)
               (string? value) (str/trim value)
               :else nil)]
    (when (seq text)
      (-> text
          (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
          str/lower-case
          (str/replace #"[_\s]+" "-")
          keyword))))

(defn normalize-summary-scope
  [value]
  (let [token (normalize-keyword-like value)
        normalized (case token
                     :perp :perps
                     token)]
    (if (contains? summary-scope-options normalized)
      normalized
      default-summary-scope)))

(defn normalize-summary-time-range
  "Coerce a range value to a supported preset.

  A custom `{:from :to}` range passes straight through: downstream the range is
  polymorphic (preset keyword OR custom range) so that the windowing helpers can
  read the custom bounds instead of preset arithmetic. Collapsing it to a preset
  here would silently show the wrong window."
  ([value]
   (normalize-summary-time-range value default-summary-time-range))
  ([value fallback]
   (or (custom-range/normalize value)
       (let [token (normalize-keyword-like value)
             normalized (get summary-time-range-aliases token token)]
         (if (contains? summary-time-range-options normalized)
           normalized
           fallback)))))

(defn normalize-portfolio-chart-tab
  [value]
  (let [token (normalize-keyword-like value)
        normalized (case token
                     :accountvalue :account-value
                     :account :account-value
                     :return :returns
                     token)]
    (if (contains? chart-tab-options normalized)
      normalized
      default-chart-tab)))

(defn- returns-chart-tab?
  [state]
  (= :returns (normalize-portfolio-chart-tab
               (get-in state [:portfolio-ui :chart-tab] default-chart-tab))))

(defn normalize-portfolio-account-info-tab
  [value]
  (let [token (normalize-keyword-like value)
        normalized (get account-info-tab-aliases token token)]
    (if (contains? account-info-tab-options normalized)
      normalized
      default-account-info-tab)))

(def ^:private vault-benchmark-prefix
  "vault:")

(def ^:private trader-benchmark-prefix
  "trader:")

(defn trader-benchmark-address
  [value]
  (let [benchmark (some-> value str str/trim)
        benchmark-lower (some-> benchmark str/lower-case)]
    (when (and (seq benchmark-lower)
               (str/starts-with? benchmark-lower trader-benchmark-prefix))
      (account-context/normalize-address
       (subs benchmark (count trader-benchmark-prefix))))))

(defn trader-benchmark-value
  [address]
  (when-let [address* (account-context/normalize-address address)]
    (str trader-benchmark-prefix address*)))

(defn normalize-portfolio-returns-benchmark-coin
  [value]
  (let [coin (cond
               (map? value) (:coin value)
               (keyword? value) (name value)
               (string? value) value
               :else nil)
        coin* (some-> coin str str/trim)]
    (when (seq coin*)
      (or (some-> coin* trader-benchmark-address trader-benchmark-value)
          coin*))))

(defn normalize-portfolio-returns-benchmark-coins
  [value]
  (let [source (cond
                 (sequential? value) value
                 (set? value) (seq value)
                 :else (when-let [coin (normalize-portfolio-returns-benchmark-coin value)]
                         [coin]))]
    (->> source
         (keep normalize-portfolio-returns-benchmark-coin)
         distinct
         vec)))

(defn- selected-returns-benchmark-coins
  [state]
  (let [coins (normalize-portfolio-returns-benchmark-coins
               (get-in state [:portfolio-ui :returns-benchmark-coins]))]
    (if (seq coins)
      coins
      (if-let [legacy-coin (normalize-portfolio-returns-benchmark-coin
                            (get-in state [:portfolio-ui :returns-benchmark-coin]))]
        [legacy-coin]
        []))))

(defn- normalize-returns-benchmark-search
  [value]
  (if (string? value)
    value
    (str (or value ""))))

(defn vault-benchmark-address
  [value]
  (let [coin (normalize-portfolio-returns-benchmark-coin value)
        coin-lower (some-> coin str/lower-case)]
    (when (and (seq coin-lower)
               (str/starts-with? coin-lower vault-benchmark-prefix))
      (some-> (subs coin (count vault-benchmark-prefix))
              str
              str/trim
              str/lower-case
              not-empty))))

(defn selected-portfolio-vault-benchmark-addresses
  [state]
  (->> (selected-returns-benchmark-coins state)
       (keep vault-benchmark-address)
       distinct
       vec))

(defn selected-portfolio-trader-benchmark-addresses
  [state]
  (->> (selected-returns-benchmark-coins state)
       (keep trader-benchmark-address)
       distinct
       vec))

(defn- vault-list-metadata-fetch-effects
  [state]
  (if (seq (get-in state [:vaults :merged-index-rows]))
    []
    [[:effects/api-fetch-vault-index]
     [:effects/api-fetch-vault-summaries]]))

(defn- vault-benchmark-details-fetch-effects
  [state addresses]
  (->> addresses
       (remove (fn [vault-address]
                 (or (get-in state [:vaults :benchmark-details-by-address vault-address])
                     (get-in state [:vaults :details-by-address vault-address])
                     (true? (get-in state [:vaults :loading :benchmark-details-by-address vault-address])))))
       (mapv (fn [vault-address]
               [:effects/api-fetch-vault-benchmark-details vault-address]))))

(defn ensure-portfolio-vault-benchmark-effects
  [state]
  (let [addresses (selected-portfolio-vault-benchmark-addresses state)
        metadata-needed? (or (true? (get-in state [:portfolio-ui :returns-benchmark-suggestions-open?]))
                             (seq addresses))]
    (into []
          (concat (when metadata-needed?
                    (vault-list-metadata-fetch-effects state))
                  (vault-benchmark-details-fetch-effects state addresses)))))

(defn- trader-benchmark-portfolio-fetch-effects
  [state addresses]
  (let [current-address (account-context/effective-account-address state)]
    (->> addresses
         (remove (fn [address]
                   (or (= address current-address)
                       (get-in state [:portfolio :trader-benchmarks-by-address address])
                       (true? (get-in state [:portfolio :loading :trader-benchmarks-by-address address])))))
         (mapv (fn [address]
                 [:effects/api-fetch-trader-portfolio-benchmark address])))))

(defn ensure-portfolio-trader-benchmark-effects
  [state]
  (trader-benchmark-portfolio-fetch-effects
   state
   (selected-portfolio-trader-benchmark-addresses state)))

(defn- fetchable-benchmark-coin
  [value]
  (let [coin (normalize-portfolio-returns-benchmark-coin value)
        coin-lower (some-> coin str/lower-case)]
    (when (and (seq coin)
               (not (str/starts-with? coin-lower vault-benchmark-prefix))
               (not (str/starts-with? coin-lower trader-benchmark-prefix)))
      coin)))

(defn returns-benchmark-candle-request
  "Candle interval + bar count to fetch for a range value.

  A custom range always borrows the ALL-TIME request. Sizing the request to the
  custom SPAN looks tempting but is wrong: candles are fetched as the newest N
  bars ending NOW, with no end bound, so a span-sized request only reaches back
  ~1.3x the span. A custom window sitting further in the past than that would
  come back with no overlapping candles at all — and the benchmark would either
  vanish or, worse, silently rebase to the oldest candle actually returned and
  report plausible-but-wrong alpha. The all-time request (1d x 5000 = ~13.7y)
  covers any window a real account can have."
  [summary-time-range]
  (let [range* (normalize-summary-time-range summary-time-range)]
    (get returns-benchmark-candle-request-by-summary-time-range
         (if (custom-range/active? range*)
           :all-time
           range*)
         {:interval :1h
          :bars 800})))

(defn- returns-benchmark-fetch-effects
  "Candle fetches a range/benchmark change needs, skipping slots the store
  already covers (see `hyperopen.portfolio.candle-coverage`)."
  [state summary-time-range benchmark-coins]
  (let [{:keys [interval bars]} (returns-benchmark-candle-request summary-time-range)]
    (->> (normalize-portfolio-returns-benchmark-coins benchmark-coins)
         (keep fetchable-benchmark-coin)
         (remove (fn [coin]
                   (candle-coverage/covers-window? state coin interval bars)))
         (mapv (fn [coin]
                 [:effects/fetch-candle-snapshot
                  :coin coin
                  :interval interval
                  :bars bars])))))

(defn- selector-visibility-path-values
  [open-dropdown]
  [[[:portfolio-ui :summary-scope-dropdown-open?] (= open-dropdown :scope)]
   [[:portfolio-ui :summary-time-range-dropdown-open?] (= open-dropdown :time-range)]
   [[:portfolio-ui :performance-metrics-time-range-dropdown-open?]
    (= open-dropdown :performance-metrics-time-range)]])

(defn- selector-projection-effect
  ([open-dropdown]
   (selector-projection-effect open-dropdown []))
  ([open-dropdown extra-path-values]
   [:effects/save-many (into (vec extra-path-values)
                             (selector-visibility-path-values open-dropdown))]))

(def ^:private anchor-keys
  [:left :right :top :bottom :width :height :viewport-width :viewport-height])

(def ^:private fee-schedule-anchor-candidate-keys-by-key
  {:left [:left "left"]
   :right [:right "right"]
   :top [:top "top"]
   :bottom [:bottom "bottom"]
   :width [:width "width"]
   :height [:height "height"]
   :viewport-width [:viewport-width :viewportWidth "viewport-width" "viewportWidth"]
   :viewport-height [:viewport-height :viewportHeight "viewport-height" "viewportHeight"]})

(defn- parse-anchor-number
  [value]
  (cond
    (number? value)
    (when-not (js/isNaN value)
      value)

    (string? value)
    (let [text (str/trim value)]
      (when (seq text)
        (let [parsed (js/Number text)]
          (when-not (js/isNaN parsed)
            parsed))))

    :else
    nil))

(defn- normalize-anchor
  [anchor]
  (let [anchor* (cond
                  (map? anchor) anchor
                  (some? anchor) (js->clj anchor :keywordize-keys true)
                  :else nil)]
    (when (map? anchor*)
      (let [normalized (reduce (fn [acc k]
                                 (if-let [num (parse-anchor-number (get anchor* k))]
                                   (assoc acc k num)
                                   acc))
                               {}
                               anchor-keys)]
        (when (seq normalized)
          normalized)))))

(defn- normalize-fee-schedule-anchor
  [anchor]
  (let [anchor* (cond
                  (map? anchor) anchor
                  (some? anchor) (js->clj anchor :keywordize-keys true)
                  :else nil)]
    (when (map? anchor*)
      (let [normalized (reduce (fn [acc [normalized-key candidate-keys]]
                                 (if-let [num (some #(parse-anchor-number (get anchor* %))
                                                    candidate-keys)]
                                   (assoc acc normalized-key num)
                                   acc))
                               {}
                               fee-schedule-anchor-candidate-keys-by-key)]
        (when (seq normalized)
          normalized)))))

(defn- fee-schedule-selector-path-values
  [open-dropdown]
  [[[:portfolio-ui :fee-schedule-referral-dropdown-open?] (= open-dropdown :referral)]
   [[:portfolio-ui :fee-schedule-staking-dropdown-open?] (= open-dropdown :staking)]
   [[:portfolio-ui :fee-schedule-maker-rebate-dropdown-open?] (= open-dropdown :maker-rebate)]
   [[:portfolio-ui :fee-schedule-market-dropdown-open?] (= open-dropdown :market)]])

(defn- fee-schedule-selector-projection-effect
  ([open-dropdown]
   (fee-schedule-selector-projection-effect open-dropdown []))
  ([open-dropdown extra-path-values]
   [:effects/save-many (into (vec extra-path-values)
                             (fee-schedule-selector-path-values open-dropdown))]))

(defn toggle-portfolio-summary-scope-dropdown
  [state]
  (let [current-visible? (boolean (get-in state [:portfolio-ui :summary-scope-dropdown-open?]))
        open-dropdown (when-not current-visible? :scope)]
    [(selector-projection-effect open-dropdown)]))

(defn toggle-portfolio-summary-time-range-dropdown
  [state]
  (let [current-visible? (boolean (get-in state [:portfolio-ui :summary-time-range-dropdown-open?]))
        open-dropdown (when-not current-visible? :time-range)]
    [(selector-projection-effect open-dropdown)]))

(defn toggle-portfolio-performance-metrics-time-range-dropdown
  [state]
  (let [current-visible? (boolean (get-in state [:portfolio-ui :performance-metrics-time-range-dropdown-open?]))
        open-dropdown (when-not current-visible? :performance-metrics-time-range)]
    [(selector-projection-effect open-dropdown)]))

(defn open-portfolio-volume-history
  ([state]
   (open-portfolio-volume-history state nil))
  ([_state trigger-bounds]
   [(selector-projection-effect nil [[[:portfolio-ui :volume-history-open?] true]
                                     [[:portfolio-ui :volume-history-anchor]
                                      (normalize-anchor trigger-bounds)]])]))

(defn close-portfolio-volume-history
  [_state]
  [[:effects/save-many [[[:portfolio-ui :volume-history-open?] false]
                        [[:portfolio-ui :volume-history-anchor] nil]]]])

(defn handle-portfolio-volume-history-keydown
  [state key]
  (if (= key "Escape")
    (close-portfolio-volume-history state)
    []))

(defn open-portfolio-fee-schedule
  ([state]
   (open-portfolio-fee-schedule state nil))
  ([_state anchor]
   [[:effects/save-many
     [[[:portfolio-ui :fee-schedule-open?] true]
      [[:portfolio-ui :fee-schedule-anchor] (normalize-fee-schedule-anchor anchor)]
      [[:portfolio-ui :fee-schedule-referral-discount] nil]
      [[:portfolio-ui :fee-schedule-staking-tier] nil]
      [[:portfolio-ui :fee-schedule-maker-rebate-tier] nil]
      [[:portfolio-ui :fee-schedule-referral-dropdown-open?] false]
      [[:portfolio-ui :fee-schedule-staking-dropdown-open?] false]
      [[:portfolio-ui :fee-schedule-maker-rebate-dropdown-open?] false]
      [[:portfolio-ui :fee-schedule-market-dropdown-open?] false]
      [[:portfolio-ui :summary-scope-dropdown-open?] false]
      [[:portfolio-ui :summary-time-range-dropdown-open?] false]
      [[:portfolio-ui :performance-metrics-time-range-dropdown-open?] false]]]]))

(defn close-portfolio-fee-schedule
  [_state]
  [(fee-schedule-selector-projection-effect
    nil
    [[[:portfolio-ui :fee-schedule-open?] false]
     [[:portfolio-ui :fee-schedule-anchor] nil]])
   [:effects/restore-dialog-focus]])

(defn toggle-portfolio-fee-schedule-referral-dropdown
  [state]
  (let [current-visible? (boolean (get-in state [:portfolio-ui :fee-schedule-referral-dropdown-open?]))
        open-dropdown (when-not current-visible? :referral)]
    [(fee-schedule-selector-projection-effect open-dropdown [[[:portfolio-ui :fee-schedule-open?] true]])]))

(defn toggle-portfolio-fee-schedule-staking-dropdown
  [state]
  (let [current-visible? (boolean (get-in state [:portfolio-ui :fee-schedule-staking-dropdown-open?]))
        open-dropdown (when-not current-visible? :staking)]
    [(fee-schedule-selector-projection-effect open-dropdown [[[:portfolio-ui :fee-schedule-open?] true]])]))

(defn toggle-portfolio-fee-schedule-maker-rebate-dropdown
  [state]
  (let [current-visible? (boolean (get-in state [:portfolio-ui :fee-schedule-maker-rebate-dropdown-open?]))
        open-dropdown (when-not current-visible? :maker-rebate)]
    [(fee-schedule-selector-projection-effect open-dropdown [[[:portfolio-ui :fee-schedule-open?] true]])]))

(defn toggle-portfolio-fee-schedule-market-dropdown
  [state]
  (let [current-visible? (boolean (get-in state [:portfolio-ui :fee-schedule-market-dropdown-open?]))
        open-dropdown (when-not current-visible? :market)]
    [(fee-schedule-selector-projection-effect open-dropdown [[[:portfolio-ui :fee-schedule-open?] true]])]))

(defn select-portfolio-fee-schedule-referral-discount
  [_state referral-discount]
  [(fee-schedule-selector-projection-effect
    nil
    [[[:portfolio-ui :fee-schedule-referral-discount]
      (fee-schedule/normalize-referral-discount referral-discount)]])])

(defn select-portfolio-fee-schedule-staking-tier
  [_state staking-tier]
  [(fee-schedule-selector-projection-effect
    nil
    [[[:portfolio-ui :fee-schedule-staking-tier]
      (fee-schedule/normalize-staking-tier staking-tier)]])])

(defn select-portfolio-fee-schedule-maker-rebate-tier
  [_state maker-rebate-tier]
  [(fee-schedule-selector-projection-effect
    nil
    [[[:portfolio-ui :fee-schedule-maker-rebate-tier]
      (fee-schedule/normalize-maker-rebate-tier maker-rebate-tier)]])])

(defn select-portfolio-fee-schedule-market-type
  [_state market-type]
  [(fee-schedule-selector-projection-effect
    nil
    [[[:portfolio-ui :fee-schedule-market-type]
      (fee-schedule/normalize-market-type market-type)]])])

(defn handle-portfolio-fee-schedule-keydown
  [state key]
  (if (= "Escape" key)
    (close-portfolio-fee-schedule state)
    []))

(defn select-portfolio-summary-scope
  [_state scope]
  [(selector-projection-effect nil [[[:portfolio-ui :summary-scope]
                                     (normalize-summary-scope scope)]])
   replace-shareable-route-query-effect])

;; --- custom chart range (design 1c: drag the context strip) --------------------------------
;;
;; The custom window is stored BESIDE the preset, never on top of it. Two things
;; fall out of that: the preset keyword stays a keyword everywhere it is
;; persisted or rendered, and clearing the custom range restores the previous
;; preset with nothing to remember.

(def summary-custom-range-path
  [:portfolio-ui :summary-custom-range])

(def summary-range-strip-path
  "Which panel currently shows the range strip: `:chart`, `:metrics`, or nil.

  A target rather than a boolean because the chart card and the tearsheet share
  ONE custom range but sit in different places on the page. Opening `Custom...`
  from the tearsheet has to reveal the strip THERE — a single boolean would put
  it under the chart, possibly scrolled off screen, and look like nothing
  happened."
  [:portfolio-ui :summary-range-strip])

(def ^:private summary-range-strip-targets
  #{:chart :metrics})

(defn normalize-summary-range-strip-target
  [value]
  (when (contains? summary-range-strip-targets value)
    value))

(def summary-range-drag-path
  [:portfolio-ui :summary-range-drag])

(defn summary-custom-range
  "Normalized custom range applied to the portfolio chart, or nil."
  [state]
  (custom-range/normalize (get-in state summary-custom-range-path)))

(defn summary-range-drag-mode
  [state]
  (get-in state summary-range-drag-path))

(defn summary-range-strip-target
  "Panel currently showing the strip, or nil."
  [state]
  (normalize-summary-range-strip-target (get-in state summary-range-strip-path)))

(defn effective-summary-time-range
  "Range value the data pipeline should use: the custom window when one is
  applied, otherwise the selected preset. Handing the custom map itself
  downstream is deliberate — the windowing helpers read its bounds, and the view
  caches compare it by value, so a drag invalidates them on its own."
  [state]
  (or (summary-custom-range state)
      (normalize-summary-time-range (get-in state [:portfolio-ui :summary-time-range]
                                            default-summary-time-range))))

(defn- custom-range-projection
  [path-values]
  (selector-projection-effect nil path-values))

(defn open-portfolio-summary-custom-range
  "Custom... — reveal the strip seeded with the window already on screen, so the
  first drag adjusts what the trader is looking at rather than jumping.

  This DOES change the applied range even though the seed reproduces what is on
  screen: the seed is day-snapped, and a custom range reads its benchmark candles
  at the all-time interval rather than the preset's. So it has to publish the
  window to the URL and refetch, exactly like a drag does — otherwise the chip
  and chart would show a custom window that the URL never mentions and whose
  benchmark candles were never fetched."
  [state target seed-from seed-to]
  (let [seed (custom-range/normalize {:from seed-from :to seed-to})
        target* (or (normalize-summary-range-strip-target target) :chart)
        projection (custom-range-projection
                    (cond-> [[summary-range-strip-path target*]
                             [summary-range-drag-path nil]]
                      seed (conj [summary-custom-range-path seed])))]
    (if seed
      (into [projection replace-shareable-route-query-effect]
            (concat (returns-benchmark-fetch-effects state
                                                     seed
                                                     (selected-returns-benchmark-coins state))
                    (ensure-portfolio-trader-benchmark-effects state)))
      [projection])))

(defn close-portfolio-summary-custom-range
  "Done — collapse the strip. The range itself was committed on pointer-up."
  [_state]
  [(custom-range-projection [[summary-range-strip-path nil]
                             [summary-range-drag-path nil]])])

(defn start-portfolio-summary-custom-range-drag
  [state client-x bounds domain-from domain-to]
  (if-let [{:keys [mode range]}
           (custom-range/drag-begin {:client-x client-x
                                     :bounds bounds
                                     :domain (custom-range/normalize {:from domain-from
                                                                      :to domain-to})
                                     :range (summary-custom-range state)})]
    [(custom-range-projection [[summary-range-strip-path
                                (or (summary-range-strip-target state) :chart)]
                               [summary-range-drag-path mode]
                               [summary-custom-range-path range]])]
    []))

(defn update-portfolio-summary-custom-range-drag
  "One pointer sample. Projection-only on purpose: this runs at pointer rate, so
  a URL rewrite or a candle fetch here would fire dozens of times per gesture."
  [state client-x bounds buttons domain-from domain-to]
  (if-let [{:keys [mode range]}
           (custom-range/drag-move {:mode (summary-range-drag-mode state)
                                    :client-x client-x
                                    :bounds bounds
                                    :buttons buttons
                                    :domain (custom-range/normalize {:from domain-from
                                                                     :to domain-to})
                                    :range (summary-custom-range state)})]
    [(custom-range-projection [[summary-range-drag-path mode]
                               [summary-custom-range-path range]])]
    []))

(defn end-portfolio-summary-custom-range-drag
  "Pointer-up ends the gesture and is the only point that pays for it: the URL
  gains the shareable bounds and benchmarks refetch at a resolution that covers
  the new span. A pointer-up with no drag in flight is a plain click and costs
  nothing."
  [state]
  (if (summary-range-drag-mode state)
    (let [range (summary-custom-range state)
          benchmark-coins (selected-returns-benchmark-coins state)
          fetch-effects (if range
                          (concat (returns-benchmark-fetch-effects state range benchmark-coins)
                                  (ensure-portfolio-trader-benchmark-effects state))
                          [])]
      (into [(custom-range-projection [[summary-range-drag-path nil]])
             replace-shareable-route-query-effect]
            fetch-effects))
    []))

(defn select-portfolio-summary-time-range
  "Pick a preset. This also clears any custom range and collapses the strip,
  which is what makes the chip's clear affordance work: the preset was never
  overwritten, so dropping the custom window restores it."
  [state time-range]
  (let [normalized (normalize-summary-time-range time-range)
        ;; Presets only. A custom range would survive `normalize-summary-time-range`
        ;; as a map and then throw in `(name ...)` below.
        time-range* (if (keyword? normalized) normalized default-summary-time-range)
        benchmark-coins (selected-returns-benchmark-coins state)
        ;; Benchmarks draw on the Returns tab only; `select-portfolio-chart-tab`
        ;; guards the identical call and re-emits it on the way back.
        fetch-effects (if (returns-chart-tab? state)
                        (concat (returns-benchmark-fetch-effects state time-range* benchmark-coins)
                                (ensure-portfolio-trader-benchmark-effects state))
                        [])]
    (into [(selector-projection-effect nil [[[:portfolio-ui :summary-time-range]
                                             time-range*]
                                            [summary-custom-range-path nil]
                                            [summary-range-strip-path nil]
                                            [summary-range-drag-path nil]])
           [:effects/local-storage-set
            portfolio-summary-time-range-storage-key
            (name time-range*)]
           replace-shareable-route-query-effect]
          fetch-effects)))

(defn restore-portfolio-summary-time-range!
  [store]
  (let [summary-time-range (normalize-summary-time-range
                            (platform/local-storage-get portfolio-summary-time-range-storage-key))]
    (swap! store assoc-in [:portfolio-ui :summary-time-range] summary-time-range)))

(defn select-portfolio-chart-tab
  [state chart-tab]
  (let [chart-tab* (normalize-portfolio-chart-tab chart-tab)
        ;; Effective, not preset: the view model reads candles at the interval
        ;; derived from the window actually on screen, so fetching at the
        ;; preset's interval while a custom window is applied stores bars nobody
        ;; ever reads and the benchmark never appears.
        summary-time-range (effective-summary-time-range state)
        benchmark-coins (selected-returns-benchmark-coins state)
        fetch-effects (if (= chart-tab* :returns)
                        (concat (returns-benchmark-fetch-effects state summary-time-range benchmark-coins)
                                (ensure-portfolio-trader-benchmark-effects state))
                        [])]
    (into [[:effects/save-many
            [[[:portfolio-ui :chart-tab] chart-tab*]]]
           replace-shareable-route-query-effect]
          fetch-effects)))

(defn set-portfolio-account-info-tab
  [_state tab]
  [[:effects/save
    [:portfolio-ui :account-info-tab]
    (normalize-portfolio-account-info-tab tab)]
   replace-shareable-route-query-effect])

(defn set-portfolio-returns-benchmark-search
  [_state search]
  [[:effects/save
    [:portfolio-ui :returns-benchmark-search]
    (normalize-returns-benchmark-search search)]])

(defn set-portfolio-returns-benchmark-suggestions-open
  [state open?]
  (let [open?* (boolean open?)
        projection-effect [:effects/save
                           [:portfolio-ui :returns-benchmark-suggestions-open?]
                           open?*]
        fetch-effects (if open?*
                        (vault-list-metadata-fetch-effects state)
                        [])]
    (into [projection-effect] fetch-effects)))

(declare clear-portfolio-returns-benchmark)

(defn select-portfolio-returns-benchmark
  [state benchmark]
  (if-let [coin (normalize-portfolio-returns-benchmark-coin benchmark)]
    ;; Effective, not preset — a benchmark added while a custom window is applied
    ;; must be fetched at the interval the view model will read it at.
    (let [summary-time-range (effective-summary-time-range state)
          selected-coins (selected-returns-benchmark-coins state)
          already-selected? (contains? (set selected-coins) coin)
          next-coins (if already-selected?
                       selected-coins
                       (conj selected-coins coin))
          projection-effect [:effects/save-many
                             [[[:portfolio-ui :returns-benchmark-coins] next-coins]
                              [[:portfolio-ui :returns-benchmark-coin] (first next-coins)]
                              [[:portfolio-ui :returns-benchmark-search] ""]
                              [[:portfolio-ui :returns-benchmark-suggestions-open?] false]]]
          candle-effects (if already-selected?
                           []
                           (returns-benchmark-fetch-effects state summary-time-range [coin]))
          benchmark-detail-effects (if already-selected?
                                     []
                                     (if-let [vault-address (vault-benchmark-address coin)]
                                       (vault-benchmark-details-fetch-effects state [vault-address])
                                       []))
          trader-effects (if already-selected?
                           []
                           (if-let [trader-address (trader-benchmark-address coin)]
                             (trader-benchmark-portfolio-fetch-effects state [trader-address])
                             []))]
      (into [projection-effect]
            (concat [replace-shareable-route-query-effect]
                    candle-effects
                    benchmark-detail-effects
                    trader-effects)))
    (clear-portfolio-returns-benchmark state)))

(defn remove-portfolio-returns-benchmark
  [state benchmark]
  (if-let [coin (normalize-portfolio-returns-benchmark-coin benchmark)]
    (let [next-coins (->> (selected-returns-benchmark-coins state)
                          (remove #(= % coin))
                          vec)]
      [[:effects/save-many
        [[[:portfolio-ui :returns-benchmark-coins] next-coins]
         [[:portfolio-ui :returns-benchmark-coin] (first next-coins)]]]
       replace-shareable-route-query-effect])
    []))

(defn handle-portfolio-returns-benchmark-search-keydown
  [state key top-coin]
  (cond
    (= key "Enter")
    (if-let [coin (normalize-portfolio-returns-benchmark-coin top-coin)]
      (select-portfolio-returns-benchmark state coin)
      [])

    (= key "Escape")
    [[:effects/save [:portfolio-ui :returns-benchmark-suggestions-open?] false]]

    :else
    []))

(defn clear-portfolio-returns-benchmark
  [_state]
  [[:effects/save-many
    [[[:portfolio-ui :returns-benchmark-coins] []]
     [[:portfolio-ui :returns-benchmark-coin] nil]
     [[:portfolio-ui :returns-benchmark-search] ""]
     [[:portfolio-ui :returns-benchmark-suggestions-open?] false]]]
   replace-shareable-route-query-effect])

(defn set-portfolio-metrics-result
  [_state payload]
  [[:effects/save [:portfolio-ui :metrics-result] payload]])

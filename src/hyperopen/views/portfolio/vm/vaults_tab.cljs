(ns hyperopen.views.portfolio.vm.vaults-tab
  "View model for the Portfolio Vaults tab: headline cards, the per-vault table
  rows and the state the panel is in. Every figure is a display string plus a
  tone, so the view only lays them out. Unknown is `--`, never zero."
  (:require [hyperopen.portfolio.metrics :as portfolio-metrics]
            [hyperopen.portfolio.vault-attribution-selector :as selector]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.portfolio.format :as portfolio-format]
            [hyperopen.views.portfolio.vm.vaults-scope :as vaults-scope]
            [hyperopen.views.portfolio.vm.vaults-tab-chart :as chart]))

(def ^:private day-ms 86400000)

(def ^:private unknown "--")

(defn- finite?
  [x]
  (and (number? x) (js/isFinite x)))

(defn- sign-of
  [x]
  (cond (pos? x) "+" (neg? x) "-" :else ""))

(defn- tone-of
  [x]
  (cond (not (finite? x)) :none
        (pos? x) :positive
        (neg? x) :negative
        :else :flat))

(defn- currency
  [x]
  (if (finite? x) (portfolio-format/format-currency x) unknown))

(defn- signed-currency
  [x]
  (if (finite? x)
    (str (sign-of x) (portfolio-format/format-currency (js/Math.abs x)))
    unknown))

(defn- signed-percent
  "`pct` is already a percent (12.9 -> +12.9%)."
  [pct]
  (if (finite? pct)
    (str (sign-of pct) (.toFixed (js/Math.abs pct) 1) "%")
    unknown))

(defn- annualised
  [fraction]
  (if (finite? fraction)
    (str (signed-percent (* 100 fraction)) " / yr")
    unknown))

(defn- cell
  [text value]
  {:text text :tone (tone-of value)})

(defn held-label
  "Compact duration: `45 d` up to two months, then months."
  [held-ms]
  (if (finite? held-ms)
    (let [days (/ held-ms day-ms)]
      (if (< days 60)
        (str (max 0 (js/Math.floor days)) " d")
        (str (js/Math.round (/ days 30.4375)) " mo")))
    unknown))

(defn short-address
  [address]
  (if (and (string? address) (> (count address) 12))
    (str (subs address 0 6) "…" (subs address (- (count address) 4)))
    (or address unknown)))

(defn time-weighted-return-pct
  "Last cumulative percent of the sleeve's all-time entry, computed by the
  page's real returns code; nil when it cannot be computed."
  [attribution]
  (let [entry (get-in attribution [:summary-entries :vault-all-time])
        rows (when entry (portfolio-metrics/returns-history-rows-from-summary entry))
        pct (some-> (peek (vec rows)) second)]
    (when (finite? pct) pct)))

(defn- incomplete-note
  [{:keys [flows-complete? equity-known?]}]
  (cond
    (false? equity-known?) "Current value unavailable"
    (false? flows-complete?) "Deposit history incomplete"
    :else nil))

(defn excluded-note
  "`N vaults excluded: incomplete history` when totals leave vaults out."
  [{:keys [incomplete-count]}]
  (when (and (finite? incomplete-count) (pos? incomplete-count))
    (str incomplete-count (if (= 1 incomplete-count) " vault" " vaults")
         " excluded: incomplete history")))

(defn partial-note
  "Why figures may be incomplete, or nil."
  [{:keys [partial? ledger-partial?]}]
  (cond
    ledger-partial? "Vault history may be incomplete: the ledger is too long to load in full."
    partial? "Some vault histories failed to load; figures may be incomplete."
    :else nil))

(def refresh-error-note
  "Couldn't refresh vault history. Showing the last loaded figures.")

(defn- vault-row
  [{:keys [vault-address name status value deposited withdrawn
           pnl money-weighted-return vault-return-while-held-pct held-ms] :as row}]
  (let [open? (= :open status)]
    {:key vault-address
     :vault-address vault-address
     :name (or (when (and (string? name) (seq name)) name)
               (short-address vault-address))
     :route (str "/vaults/" vault-address)
     :open? open?
     :status-label (if open? "Open" "Closed")
     :incomplete-note (incomplete-note row)
     :deposited (currency deposited)
     :withdrawn (currency withdrawn)
     :value (if (and open? (finite? value)) (currency value) unknown)
     :pnl (cell (signed-currency pnl) pnl)
     :money-weighted (cell (annualised money-weighted-return) money-weighted-return)
     :while-held (assoc (cell (signed-percent vault-return-while-held-pct)
                              vault-return-while-held-pct)
                        :estimated? true)
     :held (held-label held-ms)}))

(defn- total-row
  [{:keys [deposited withdrawn value pnl money-weighted-return]} twr-pct]
  {:deposited (currency deposited)
   :withdrawn (currency withdrawn)
   :value (currency value)
   :pnl (cell (signed-currency pnl) pnl)
   :money-weighted (cell (annualised money-weighted-return) money-weighted-return)
   :while-held (assoc (cell (signed-percent twr-pct) twr-pct) :estimated? true)})

(defn- cards
  [{:keys [pnl realized unrealized money-weighted-return value net-deposited]} twr-pct]
  {:pnl {:value (cell (signed-currency pnl) pnl)
         :detail (str "Realized " (signed-currency realized)
                      " · Unrealized " (signed-currency unrealized))}
   :money-weighted {:value (cell (annualised money-weighted-return) money-weighted-return)
                    :detail "What your deposits earned, timing included"}
   :time-weighted {:value (assoc (cell (signed-percent twr-pct) twr-pct) :estimated? true)
                   :detail "Vault picks alone, ignoring deposit timing"}
   :equity {:value {:text (currency value) :tone :none}
            :detail (str "Net deposited " (currency net-deposited))}})

(defn footnote
  "Disclosure for the estimated head of the series, or nil."
  [{:keys [daily-from-ms]}]
  (when (finite? daily-from-ms)
    (str "Estimated before "
         (or (fmt/format-intl-date-time daily-from-ms vaults-scope/estimate-date-format-options)
             unknown)
         " from vault history sampled about every 12 days. "
         "Dollar PNL and money-weighted return are exact.")))

(defn- status-of
  [{:keys [load-status attribution]}]
  (case load-status
    (:idle :loading) :loading
    :error :error
    :ready (if (= :empty (:status attribution)) :empty :ready)
    :none))

(defn open-count
  "Open vault count for the tab label, or nil when not known yet."
  [model]
  (let [n (get-in model [:attribution :totals :open-count])]
    (when (and (= :ready (:load-status model)) (finite? n))
      n)))

(defn- build
  [model]
  (let [status (status-of model)
        attribution (:attribution model)
        ready? (= :ready status)
        twr (when ready? (time-weighted-return-pct attribution))]
    {:status status
     :refreshing? (boolean (:refreshing? model))
     :partial-note (when ready? (partial-note model))
     :excluded-note (when ready? (excluded-note (:totals attribution)))
     :refresh-error (when (and ready? (:refresh-error model)) refresh-error-note)
     :error (:error model)
     :cards (when ready? (cards (:totals attribution) twr))
     :chart (when ready? (chart/chart-model attribution))
     :footnote (when ready? (footnote attribution))
     :rows (when ready? (mapv vault-row (:vaults attribution)))
     :total (when ready? (total-row (:totals attribution) twr))}))

(defonce ^:private cache (atom nil))

(defn vaults-tab-model
  "Memoized on the identity of the selector's (itself memoized) model."
  [state]
  (let [model (selector/model state)
        cached @cache]
    (if (and cached (identical? model (:model cached)))
      (:result cached)
      (let [result (build model)]
        (reset! cache {:model model :result result})
        result))))

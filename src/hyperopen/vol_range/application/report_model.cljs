(ns hyperopen.vol-range.application.report-model
  "The Vol Range Portfolio report view model (HO-SPEC-014 v2.1 sections
  11.3, 12.1, 12.6; exact keys in `hyperopen.schema.vol-range-report-
  contracts`).

  From the loaded report (`[:vol-range :report]`: campaigns, closures, the
  attribution index and the account's ledger) it derives, per tracking
  campaign: the baseline line, Cost basis unavailable, coverage (state chip,
  how far fills were checked, intervals with typed reasons) and execution
  activity of the tracked fills (fills, volumes, VWAPs, maker/taker, fees by
  token). Activity is always shown; coverage only explains it. The headline
  shows the selected window's three dollar figures (one market, or the All
  tracked markets total) from `report-windows` and `report-aggregate`, each
  campaign's coverage evaluated to that window's end, or says why they are
  hidden (`report-headline`), the cumulative P&L chart of the same window
  (`report-chart-model`) and the Gross spread on matched fills disclosure
  (`report-matched`) for the markets the headline describes. `:export`
  says whether the JSON and CSV export buttons work.

  Pure; memoized on the report state, spot metadata, account, read-only
  flag and the minute."
  (:require [clojure.string :as str]
            [hyperopen.domain.trading.precision :as precision]
            [hyperopen.vol-range.application.display :as display]
            [hyperopen.vol-range.application.report-aggregate :as aggregate]
            [hyperopen.vol-range.application.report-attribution :as attribution]
            [hyperopen.vol-range.application.report-chart-model :as chart]
            [hyperopen.vol-range.application.report-copy :as copy]
            [hyperopen.vol-range.application.report-coverage :as coverage]
            [hyperopen.vol-range.application.report-headline :as headline]
            [hyperopen.vol-range.application.report-matched :as matched]
            [hyperopen.vol-range.application.report-windows :as windows]
            [hyperopen.vol-range.contracts :as contracts]
            [hyperopen.vol-range.domain.report-ledger :as ledger]
            [hyperopen.vol-range.report-actions :as report-actions]))

(def ^:private minute-ms 60000)

;; ---------------------------------------------------------------------------
;; Formatting
;; ---------------------------------------------------------------------------

(defn- scale [x] (let [s (str x) dot (.indexOf s ".")] (if (neg? dot) 0 (- (count s) dot 1))))

(defn price-text
  "A price with at least two decimals, exact beyond that."
  [x]
  (when-let [d (display/parse x)]
    (let [s (precision/decimal->string d)]
      (if (<= (scale s) 2) (display/fixed s 2) (display/exact s)))))

(defn- qty-text [x symbol] (str (or (display/exact x) "0") " " symbol))

(defn fees-text
  "Signed fees by their actual token, sorted by token: \"0.0004 VRT · 0 USDC\"."
  [fees]
  (if (seq fees)
    (str/join " · " (for [[token amount] (sort-by key fees)] (str (display/exact amount) " " token)))
    "—"))

(defn- market-label [c]
  (let [{:keys [symbol quote-symbol coin]} (:scope c)]
    (str (or symbol coin) (when quote-symbol (str "/" quote-symbol)))))

;; ---------------------------------------------------------------------------
;; Coverage
;; ---------------------------------------------------------------------------

(defn checked-through
  "Where both streams' complete coverage ends (the earlier of the two last
  complete ends), or nil when either stream was never read completely."
  [{:keys [fills] ledger-cov :ledger}]
  (let [end (fn [r] (some-> (:complete r) last second))
        a (end fills) b (end ledger-cov)]
    (when (and (number? a) (number? b)) (min a b))))

(defn- break-context
  "Names for the break text: the base symbol, and the pair, ledger type or
  fee token of the event behind the break."
  [c {:keys [fills updates]} markets brk]
  (let [fill (when (= :fill (:source brk)) (get fills (:event-id brk)))
        update (when (= :ledger (:source brk)) (get updates (:event-id brk)))
        m (when fill (get-in markets [:by-name (:coin fill)]))]
    {:symbol (get-in c [:scope :symbol])
     :pair (when m (str (or (:base-symbol m) (:coin m)) "/" (or (:quote-symbol m) "?")))
     :delta-type (:delta-type update)
     :fee-token (some-> (:fee-token fill) ledger/token-name)}))

(defn- span-text
  "\"Oct 21, 13:41 UTC → Oct 21, 18:42 UTC\", with seconds when both ends
  fall in the same minute."
  [a b]
  (if (= (quot a minute-ms) (quot b minute-ms))
    (str (display/utc-date a) " " (display/utc-clock a) " → " (display/utc-clock b))
    (str (display/utc-date-minute a) " → " (display/utc-date-minute b))))

(defn- coverage-view [c result through ctx]
  (let [state (:state result)]
    {:state state
     :label (get copy/state-labels state)
     :icon (get copy/state-icons state)
     :detail (copy/coverage-detail result (some-> through display/utc-date-minute)
                                   (some-> (first (:breaks result)) :at-ms display/utc-date-minute) ctx)
     :intervals (mapv (fn [{:keys [from-ms to-ms] :as iv}]
                        {:from-ms from-ms :to-ms to-ms :state (:state iv)
                         :text (str (span-text from-ms to-ms) ": " (copy/interval-text iv ctx))})
                      (:intervals result))}))

;; ---------------------------------------------------------------------------
;; Per campaign
;; ---------------------------------------------------------------------------

(defn- activity-view [c events ended]
  (let [a (ledger/activity events)
        sym (or (get-in c [:scope :symbol]) "")
        quote-sym (or (get-in c [:scope :quote-symbol]) "USDC")]
    {:market (cond-> (market-label c)
               ended (str (copy/until-text (display/utc-date-minute ended))))
     :fills (str (:fills a))
     :bought (str (qty-text (:bought-base a) sym) " · " (display/fixed (:bought-quote a) 2) " " quote-sym)
     :buy-vwap (or (price-text (:buy-vwap a)) "—")
     :sold (str (qty-text (:sold-base a) sym) " · " (display/fixed (:sold-quote a) 2) " " quote-sym)
     :sell-vwap (or (price-text (:sell-vwap a)) "—")
     :maker-taker (str (:maker a) " / " (:taker a))
     :fees (fees-text (:fees-by-token a))}))

(defn- baseline-view [c]
  (let [{:keys [q0 p0]} (:baseline c)]
    (copy/baseline-text {:began (display/utc-date-minute (:t0-ms c))
                         :q0 (or (display/exact q0) "0")
                         :symbol (get-in c [:scope :symbol])
                         :p0 (price-text p0)
                         :quote-symbol (or (get-in c [:scope :quote-symbol]) "USDC")})))

(defn- campaign-data
  "What the campaign view and its window figures share: the closure time,
  the findings (tracked events, breaks, unreadable rows) and the base
  token's coverage records."
  [{:keys [report markets]} c]
  (let [ended (get-in report [:closures (:campaign-id c) :ended-at-ms])
        led (:ledger report)
        token (some-> (get-in c [:scope :base-token-id]) str)]
    {:campaign c
     :ended ended
     :found (attribution/campaign-findings {:campaign c :ended-at-ms ended
                                            :fills (vals (:fills led)) :updates (vals (:updates led))
                                            :markets markets :index (:attribution report)
                                            :unreadable (:unreadable report)})
     :records {:fills (get-in led [:coverage :fills token]) :ledger (get-in led [:coverage :ledger token])}}))

(defn- coverage-until [report {:keys [campaign found records]} until]
  (coverage/campaign-coverage {:campaign campaign :coverage records :breaks (:breaks found)
                               :balance-checks (get-in report [:ledger :balance-checks]) :until-ms until
                               :unreadable (:unreadable found)
                               :storage-ok? (not (false? (:storage-ok? report)))}))

(defn- campaign-view
  [{:keys [report markets now-ms read-only? ui busy-restart?]} {:keys [campaign ended found records] :as data}]
  (let [c campaign
        led (:ledger report)
        through (checked-through records)
        checks (:balance-checks led)
        later-break? (or (some #(>= (:at-ms %) (or through 0))
                               (concat (:breaks found) (coverage/balance-check-breaks c checks)))
                         (some #(>= (:time %) (or through 0)) (:unreadable found)))
        until (cond-> (if (and through (not later-break?)) through now-ms)
                (number? ended) (min ended))
        result (coverage-until report data until)
        ctx (break-context c led markets (first (:breaks result)))
        open? (nil? ended)]
    {:campaign-id (:campaign-id c)
     :campaign-key (:campaign-key c)
     :market (market-label c)
     :open? open?
     :baseline-text (baseline-view c)
     :cost-basis-text copy/cost-basis
     :ended-text (when ended (copy/ended-text (display/utc-date-minute ended)))
     :coverage (coverage-view c result through ctx)
     :activity (activity-view c (:events found) ended)
     :restart {:visible? (boolean (and open? (not read-only?)))
               :confirming? (boolean (and open? (= (:campaign-key c) (:confirm-restart-key ui))))
               :busy? (boolean busy-restart?)}}))

;; ---------------------------------------------------------------------------
;; Headline (open campaigns, selected window)
;; ---------------------------------------------------------------------------

(defn- window-item
  "One open campaign's figures for `window`: coverage to the window end,
  then `report-windows/campaign-window-figures`."
  [{:keys [report markets now-ms]} window {:keys [campaign found] :as data}]
  (let [rng (windows/resolve-window {:window window :campaign campaign :now-ms now-ms})
        cov (when (= :ok (:status rng)) (coverage-until report data (:b-ms rng)))
        result (windows/campaign-window-figures
                {:campaign campaign :range rng :events (:events found) :coverage cov
                 :coin-marks (get-in report [:marks (get-in campaign [:scope :coin])])})]
    {:symbol (or (get-in campaign [:scope :symbol]) (get-in campaign [:scope :coin]))
     :network (get-in campaign [:scope :network])
     :market (market-label campaign)
     :campaign campaign
     :result result
     :events (:events found)
     :coverage cov
     :coin-marks (get-in report [:marks (get-in campaign [:scope :coin])])
     :break-ctx (break-context campaign (:ledger report) markets (first (:breaks cov)))}))

(defn- shown-items
  "The items the chart and the matched disclosure describe: the one market
  shown, or the markets an All tracked markets total includes."
  [{:keys [scope status included]} items]
  (case scope
    :single items
    :all (if (= :ok status) (filterv #(some #{(:symbol %)} included) items) [])
    []))

(defn- window-section
  "The headline, chart and matched disclosure of the selected window."
  [ctx window open-data {:keys [covered-subset? matched-open?]}]
  (let [items (mapv #(window-item ctx window %) (sort-by #(get-in % [:campaign :campaign-key]) open-data))
        agg (aggregate/aggregate {:window window :items items :covered-subset? covered-subset?})
        shown (shown-items agg items)]
    {:headline (headline/headline-view {:agg agg :items items :window window})
     :chart (chart/chart-view {:items shown})
     :matched (matched/matched-view {:items shown :open? matched-open?})}))

(defn- window-view [window]
  {:selected window
   :options (mapv (fn [id] {:id id :label (get copy/window-labels id) :selected? (= id window)})
                  windows/window-ids)})

;; ---------------------------------------------------------------------------
;; View model
;; ---------------------------------------------------------------------------

(defn- busy? [report k] (true? (get-in report [:busy k])))

(defn- sorted-campaigns [campaigns closures]
  (sort-by (juxt market-label #(if (contains? closures (:campaign-id %)) 1 0) #(- (:t0-ms %)))
           (vals campaigns)))

(defn- markets-view [campaigns selected]
  (let [options (->> campaigns
                     (reduce (fn [acc c] (if (some #(= (:campaign-key c) (:id %)) acc)
                                           acc
                                           (conj acc {:id (:campaign-key c) :label (market-label c)})))
                             [])
                     (sort-by :label))]
    {:selected selected
     :options (into [{:id report-actions/all-markets-id :label copy/all-markets :selected? (nil? selected)}]
                    (map #(assoc % :selected? (= selected (:id %))) options))}))

(defn- empty-message [{:keys [account loaded? report campaigns]}]
  (cond (nil? account) copy/empty-no-account
        (not loaded?) copy/empty-loading
        (false? (:journal-ok? report)) copy/empty-journal
        (empty? campaigns) copy/empty-no-tracking
        :else nil))

(defn- build [state now-ms]
  (let [enabled? (contracts/reporting-enabled?)
        account (report-actions/report-account state)
        read-only? (report-actions/read-only? state)
        report (get-in state [:vol-range :report])
        loaded? (and account (= account (:account-address report)))
        campaigns (if loaded? (:campaigns report) {})
        closures (if loaded? (:closures report) {})
        ui (:ui report)
        known-keys (set (map :campaign-key (vals campaigns)))
        selected (let [k (:selected-campaign-key ui)] (when (contains? known-keys k) k))
        shown (cond->> (sorted-campaigns campaigns closures)
                selected (filter #(= selected (:campaign-key %))))
        ctx {:report report :markets (attribution/spot-markets (get-in state [:spot :meta])) :now-ms now-ms
             :read-only? read-only? :ui ui :busy-restart? (busy? report [:start-tracking])}
        data (if enabled? (mapv #(campaign-data ctx %) shown) [])
        views (mapv #(campaign-view ctx %) data)
        window (windows/selected-window ui)
        section (window-section ctx window (remove #(some? (:ended %)) data)
                                {:covered-subset? (true? (:covered-subset? ui)) :matched-open? (true? (:matched-open? ui))})
        worst (coverage/worst (map #(get-in % [:coverage :state]) views))]
    {:enabled? enabled?
     :read-only? read-only?
     :loading? (boolean (or (busy? report [:load]) (busy? report [:backfill])))
     :account-address account
     :window (window-view window)
     :markets (markets-view (vals campaigns) selected)
     :coverage (when (seq views) {:state worst :label (get copy/state-labels worst)
                                  :icon (get copy/state-icons worst)})
     :headline (:headline section)
     :chart (:chart section)
     :matched (:matched section)
     :export {:enabled? (report-actions/export-available? state)}
     :campaigns views
     :status-message (copy/status-message (:last report))
     :empty-message (when enabled?
                      (empty-message {:account account :loaded? loaded? :report report :campaigns campaigns}))}))

(defonce ^:private memo (atom nil))

(defn report-view-model
  "The report view model for `state` at `now-ms`."
  [state now-ms]
  (let [k [(contracts/reporting-enabled?) (report-actions/report-account state)
           (report-actions/read-only? state) (quot now-ms minute-ms)]
        report (get-in state [:vol-range :report])
        meta (get-in state [:spot :meta])
        m @memo]
    (if (and m (= k (:k m)) (identical? report (:report m)) (identical? meta (:meta m)))
      (:vm m)
      (let [vm (build state now-ms)]
        (reset! memo {:k k :report report :meta meta :vm vm})
        vm))))

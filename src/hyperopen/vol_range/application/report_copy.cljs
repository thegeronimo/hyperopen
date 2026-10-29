(ns hyperopen.vol-range.application.report-copy
  "Every user-visible string of the Vol Range Portfolio report (HO-SPEC-014
  v2.1 sections 11.3, 12.6), in one place for the copy review. The report
  says \"tracking\" and \"ladder\"; it never says grid or equity and never
  shows a percentage return or a cost basis. Pure."
  (:require [clojure.string :as str]))

(def tab-label "Vol Range")
(def all-markets "All tracked markets")
(def market-label "Market")
(def refresh "Refresh")
(def cost-basis "Cost basis unavailable")
(def start-tracking "Start new tracking from now")
(def confirm-question
  "Start new tracking from now? Current tracking is kept as history and isn't combined with the new one. No orders are changed.")
(def confirm-yes "Start tracking")
(def confirm-no "Keep current tracking")
(def activity-title "Execution activity")
(def activity-note "Orders, fills and fees of your Vol Range ladders. Execution detail, not P&L.")
(def tracking-title "Tracking")
(def coverage-title "Coverage")
(def activity-columns ["Market" "Fills" "Bought" "Buy VWAP" "Sold" "Sell VWAP" "Maker/Taker" "Fees (by token)"])

(def empty-no-tracking
  "No Vol Range tracking for this account in this browser yet. Tracking starts when you place your first ladder.")
(def empty-no-account "Connect a wallet to see Vol Range tracking.")
(def empty-loading "Loading Vol Range tracking…")
(def empty-journal "Couldn't read Vol Range tracking from this browser's storage.")
(def read-only-note "Viewing only. Tracking can be started or restarted only for your own account.")

(defn first-close-message
  "Before the first completed hourly close after tracking began."
  [hh-mm]
  (str "Figures appear after the first completed hourly close (" hh-mm " UTC)."))

;; ---------------------------------------------------------------------------
;; Windows and headline
;; ---------------------------------------------------------------------------

(def window-group-label "Report window")
(def window-labels {:since-start "Since tracking began" :7d "7D" :30d "30D"})
(def figure-labels
  {:net-pnl "Net tracked asset P&L" :hold-pnl "Hold P&L" :contribution "Ladder contribution versus hold"})
(def benchmark-label "Hold inventory at the selected window start")
(def benchmark-note
  "Benchmark: Hold inventory at the selected window start. A descriptive comparison, not a claim that every wallet action would still have been possible.")
(def headline-title-all "All tracked markets")
(def marks-loading "Loading hourly prices…")
(def partial-message "Checking fills… Figures appear once every fill in this window is confirmed.")
(def no-total-different-windows "Totals need the same window for every market. Choose 7D or 30D.")

(defn range-text
  "\"Oct 21, 14:00 UTC → Oct 28, 14:00 UTC · valued at hourly closes\"."
  [from to since-start?]
  (str from " → " to " · " (if since-start? "start valued at the book midpoint" "valued at hourly closes")))

(defn break-message [at reason-text]
  (str "Dollar figures are hidden from " at ": " reason-text ". Orders, fills and fees are still shown below."))

(defn gap-message [from to]
  (str "Fills between " from " and " to
       " couldn't be confirmed, so dollar figures are hidden for windows that include this period."))

(defn before-tracking-message [window-label needed-by began]
  (str window-label " needs tracking to have started by " needed-by ". Tracking began " began "."))

(defn missing-mark-message [at]
  (str "No completed hourly price for " at ", so figures for this window are unavailable."))

(defn mark-error-message [at]
  (str "Couldn't load the hourly price for " at ". Refresh to try again."))

(defn- names-text [names] (str/join ", " names))

(defn no-total-incomplete [names]
  (str "Totals are hidden because " (names-text names) (if (= 1 (count names)) " isn't" " aren't")
       " fully covered for this window."))

(defn subset-offer-label [names] (str "Show total for covered markets (" (names-text names) ")"))
(defn covered-title [names] (str "Covered markets: " (names-text names)))

;; ---------------------------------------------------------------------------
;; P&L chart and Gross spread on matched fills
;; ---------------------------------------------------------------------------

(def export-json "Export report (JSON)")
(def export-csv "Export fills (CSV)")
(def export-group-label "Export")

(def chart-title "Cumulative P&L (USDC)")
(def chart-label "Cumulative Net tracked asset P&L and Hold P&L in USDC")
(def chart-series-labels {:net "Net tracked asset P&L" :hold "Hold P&L"})

(defn chart-caption
  "The chart's caption. It never names the axis account value."
  [cadence]
  (str "Dollar P&L of the tracked inventory, not your account value. "
       (if (= :daily cadence) "Daily points at the 00:00 UTC hourly close" "Hourly closes")
       "; gaps mark missing prices or unconfirmed fills."))

(defn chart-ends-text [at]
  (str "The lines end at " at ": later fills or prices aren't confirmed."))

(def matched-title "Gross spread on matched fills")
(def matched-note
  "Pairs the oldest opposite fills within this window. It isn't a completed cycle or a cost basis and isn't added to the headline.")
(def matched-gross "Gross matched spread")
(def matched-unmatched-sells "Sold inventory not reaccumulated")
(def matched-fee-impact "Fee and rebate impact at the report mark")
(def matched-total "= Ladder contribution versus hold")

(defn matched-qty-text [qty symbol pairs]
  (str "Matched quantity " qty " " symbol " in " pairs " matched pair" (when-not (= 1 pairs) "s")))

(defn unmatched-buys-text [p-end] (str "Unmatched buys marked at " p-end))
(defn fees-paid-text [fees] (str "Fees paid: " fees))

;; ---------------------------------------------------------------------------
;; Coverage
;; ---------------------------------------------------------------------------

(def state-labels
  {:complete "Complete" :partial "Checking" :gap "Gap" :attribution-break "Outside activity"
   :unavailable "Unavailable"})

(def state-icons
  "Non-color cue for each coverage state (spec 11.4)."
  {:complete "✓" :partial "…" :gap "⚠" :attribution-break "⚠" :unavailable "—"})

(def ^:private reason-texts
  {:coverage/retention "the venue keeps only its most recent 10,000 fills"
   :coverage/no-progress "fill pages stopped advancing"
   :coverage/page-cap "too many fills to read at once"
   :coverage/read-error "a read failed"
   :coverage/unparsed-row "a fill or account update couldn't be read"
   :storage/unreadable-rows "some saved fill records in this browser can't be read"
   :coverage/backfill-pending "not checked yet"
   :baseline/missing "no tracking baseline"
   :baseline/price-unavailable "no starting price was recorded"
   :storage/unavailable "this browser's fill ledger is unavailable"
   :window/before-tracking "tracking has just begun"})

(defn unavailable-message
  "A headline that cannot be computed for `reason` (a coverage reason)."
  [reason]
  (str "Dollar figures are unavailable: " (get reason-texts reason "not available") "."))

(defn break-text
  "The external activity behind an attribution break. `ctx` names the base
  symbol and, when known, the pair, ledger type and fee token."
  [reason {:keys [symbol pair delta-type fee-token]}]
  (let [sym (or symbol "the base token")]
    (case reason
      :break/untracked-same-base-trade (str "a " sym " trade that wasn't a Vol Range order")
      :break/other-pair-trade (str "a " sym " trade in " (or pair "another market"))
      :break/base-transfer (str "a " sym " transfer" (when delta-type (str " (" delta-type ")")))
      :break/unsupported-fee-token (str "a fee paid in " (or fee-token "another token"))
      :break/unknown-market-fill "a fill in a market HyperOpen couldn't identify"
      :break/unexplained-balance-change (str "an unexplained " sym " balance change")
      :break/ambiguous-boundary "activity at the moment tracking began"
      :break/unrecognized-ledger-entry (str "an account update involving " sym)
      "activity outside Vol Range")))

(defn interval-text
  "The state text of one coverage interval."
  [{:keys [state reason]} ctx]
  (case state
    :complete "Complete"
    :partial "Checking fills…"
    :gap (str "Fills couldn't be confirmed (" (get reason-texts reason "a read failed") ")")
    :attribution-break (str "Outside activity: " (break-text reason ctx))
    :unavailable (str "Unavailable: " (get reason-texts reason "not available"))
    "Unknown"))

(defn coverage-detail
  "One line under the chip: how far fills were checked, or why not.
  `break-at` is the time text of the first break."
  [{:keys [state reason]} checked-through break-at ctx]
  (case state
    :unavailable (str "Unavailable: " (get reason-texts reason "not available") ".")
    :attribution-break (str "Outside activity from " break-at ": " (break-text reason ctx) ".")
    (if checked-through
      (str "Fills checked through " checked-through ".")
      "Checking fills…")))

;; ---------------------------------------------------------------------------
;; Tracking
;; ---------------------------------------------------------------------------

(defn baseline-text
  [{:keys [began q0 symbol p0 quote-symbol]}]
  (if p0
    (str "Tracking began " began " with " q0 " " symbol " at " p0 " " quote-symbol " (book midpoint).")
    (str "Tracking began " began " with " q0 " " symbol ". No starting price was recorded.")))

(defn ended-text [ended] (str "Ended " ended " when new tracking started."))
(defn until-text [ended] (str ", until " ended))

;; ---------------------------------------------------------------------------
;; Status (polite live region)
;; ---------------------------------------------------------------------------

(def ^:private refusal-texts
  {:campaign/unresolved-attempt "An order placement in this market is still being checked. Try again once it settles."
   :journal/unreadable-records "Some saved Vol Range records can't be read, so tracking can't restart."
   :campaign/not-found "This market has no current tracking to restart."
   :campaign/other-account "This tracking belongs to another account."
   :campaign/scope-changed "This market changed. Reload and try again."
   :guard/account-unavailable "Couldn't read your balances. Try again."
   :guard/book-unavailable "Couldn't read a fresh order book price. Try again."
   :guard/clock "This browser's clock looks wrong; tracking didn't restart."
   :admission/timeout "Timed out waiting for this account. Reload before trying again."})

(defn status-message
  "Text for the latest report operation, or nil."
  [{:keys [op status errors error]}]
  (when op
    (case status
      :ok (case op :start-tracking "New tracking started." :export "Export downloaded." "Report updated.")
      :skipped nil
      :busy "Another placement or tracking change for this account is in progress. Try again in a moment."
      :coordination-unavailable "Another tab could be trading this account; try again from one tab."
      :refused (or (some refusal-texts errors) "Tracking didn't restart.")
      :journal-unavailable "Browser storage is unavailable, so tracking didn't restart."
      :storage/unavailable "Couldn't read the fill ledger in this browser."
      (case op
        :start-tracking (str "Tracking didn't restart" (when error (str ": " error)) ".")
        :marks (str "Couldn't load hourly prices: " (or error "unknown error") ".")
        :export (str "Couldn't export: " (or error "unknown error") ".")
        (str "Couldn't load fills: " (or error "unknown error") ".")))))

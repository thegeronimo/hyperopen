(ns hyperopen.vol-range.application.report-copy
  "Every user-visible string of the Vol Range Portfolio report (HO-SPEC-014
  v2.1 sections 11.3, 12.6), in one place for the copy review. The report
  says \"tracking\" and \"ladder\"; it never says grid or equity and never
  shows a percentage return or a cost basis. Pure.")

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
   :coverage/backfill-pending "not checked yet"
   :baseline/missing "no tracking baseline"
   :baseline/price-unavailable "no starting price was recorded"
   :storage/unavailable "this browser's fill ledger is unavailable"
   :window/before-tracking "tracking has just begun"})

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
      :ok (if (= :start-tracking op) "New tracking started." "Report updated.")
      :skipped nil
      :busy "Another placement or tracking change for this account is in progress. Try again in a moment."
      :coordination-unavailable "Another tab could be trading this account; try again from one tab."
      :refused (or (some refusal-texts errors) "Tracking didn't restart.")
      :journal-unavailable "Browser storage is unavailable, so tracking didn't restart."
      :storage/unavailable "Couldn't read the fill ledger in this browser."
      (if (= :start-tracking op)
        (str "Tracking didn't restart" (when error (str ": " error)) ".")
        (str "Couldn't load fills: " (or error "unknown error") ".")))))

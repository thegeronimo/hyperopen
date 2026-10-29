(ns hyperopen.vol-range.report-actions
  "Vol Range report action handlers (HO-SPEC-014 v2.1 sections 11.3, 12.6,
  15.2): load the Portfolio report, refresh it with a REST backfill, choose
  the market, and start new tracking from now behind an inline confirmation.

  Pure: each handler returns effects, at most one
  `[:effects/vol-range-report op params]` after its projection writes.
  Nothing here signs, sends or writes the journal; the
  report reads the account the Portfolio shows, and a read-only account
  (Spectate, another trader's portfolio) never starts tracking or backfills.
  A second request while the same operation is in flight emits nothing."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.portfolio.routes :as portfolio-routes]
            [hyperopen.vol-range.contracts :as contracts]))

(def tab-id :vol-range)

(def report-path [:vol-range :report])
(def ui-path (conj report-path :ui))

(defn- lower [x] (some-> x str str/trim str/lower-case not-empty))

(defn report-account
  "The account the Portfolio shows (lower-cased), or nil."
  [state]
  (lower (account-context/effective-account-address state)))

(defn read-only?
  "Spectate, a trader's portfolio or an unavailable subaccount."
  [state]
  (boolean (account-context/inspected-account-read-only? state)))

(defn report-visible?
  "True while the Portfolio page shows the Vol Range tab (never on a trader
  route, never with reporting off)."
  [state]
  (boolean
   (and (contracts/reporting-enabled?)
        (= :page (:kind (portfolio-routes/parse-portfolio-route (get-in state [:router :path]))))
        (= tab-id (portfolio-actions/normalize-portfolio-account-info-tab
                   (get-in state [:portfolio-ui :account-info-tab]))))))

(defn- busy? [state k] (true? (get-in state (into report-path [:busy k]))))
(defn- loading? [state] (or (busy? state [:load]) (busy? state [:backfill])))

(defn load-vol-range-report
  "Load the report's journal and ledger data for the shown account, then (for
  an account this browser trades) backfill it."
  [state]
  (let [account (report-account state)]
    (if (and (contracts/reporting-enabled?) account (not (busy? state [:load])))
      [[:effects/vol-range-report :load {:account-address account :backfill? (not (read-only? state))}]]
      [])))

(defn refresh-vol-range-report
  "A REST backfill for the shown account, then a reload. Read-only accounts
  only load."
  [state]
  (let [account (report-account state)]
    (if (and (contracts/reporting-enabled?) account (not (read-only? state)) (not (loading? state)))
      [[:effects/vol-range-report :backfill {:account-address account}]]
      [])))

(def all-markets-id
  "The market select's value for All tracked markets."
  "all")

(defn select-vol-range-report-campaign
  "Show one market (`campaign-key`) or All tracked markets (nil or \"all\")."
  [_state campaign-key]
  [[:effects/save-many [[(conj ui-path :selected-campaign-key)
                         (when-not (= all-markets-id campaign-key) campaign-key)]]]])

(defn request-start-vol-range-tracking
  "Open the inline confirmation for `campaign-key`. Nothing in read-only
  mode."
  [state campaign-key]
  (if (or (read-only? state) (not (contracts/reporting-enabled?)))
    []
    [[:effects/save-many [[(conj ui-path :confirm-restart-key) campaign-key]]]]))

(defn cancel-start-vol-range-tracking
  [_state]
  [[:effects/save-many [[(conj ui-path :confirm-restart-key) nil]]]])

(defn confirm-start-vol-range-tracking
  "Start new tracking for the confirmed market, once. The effect runs inside
  the account admission section and never sends anything."
  [state]
  (let [k (get-in state (conj ui-path :confirm-restart-key))
        account (report-account state)]
    (if (and k account
             (contracts/reporting-enabled?)
             (not (read-only? state))
             (not (busy? state [:start-tracking])))
      [[:effects/save-many [[(conj ui-path :confirm-restart-key) nil]]]
       [:effects/vol-range-report :start-tracking {:campaign-key k :account-address account}]]
      [])))

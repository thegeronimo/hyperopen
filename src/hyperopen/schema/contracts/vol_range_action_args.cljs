(ns hyperopen.schema.contracts.vol-range-action-args
  (:require [cljs.spec.alpha :as s]
            [hyperopen.schema.contracts.common :as common]))

;; Vol Range action payload contracts (HO-SPEC-014 v2.1, Release A0). Merged into
;; hyperopen.schema.contracts.action-args/action-args-spec-by-id.

(s/def ::scale-mode #{:price-range :volatility-range "price-range" "volatility-range"})
(s/def ::select-scale-mode-args (s/tuple ::scale-mode))

(s/def ::draft-path (s/and vector? #(seq %) #(every? keyword? %)))
(s/def ::update-vol-range-draft-args (s/tuple ::draft-path any?))

(s/def ::sell-percent #{25 50 100 "25" "50" "100"})
(s/def ::set-vol-range-sell-quantity-percent-args (s/tuple ::sell-percent))

(s/def ::remediation-id (s/or :keyword keyword? :string ::common/non-empty-string))
(s/def ::apply-vol-range-remediation-args (s/tuple ::remediation-id))

(s/def ::ui-flag #{:show-bands? :advanced-open? :limits-open?})
(s/def ::set-vol-range-ui-flag-args (s/tuple ::ui-flag boolean?))

(s/def ::grid-id ::common/non-empty-string)
(s/def ::grid-id-args (s/tuple ::grid-id))

(s/def ::logical-id ::common/non-empty-string)
(s/def ::logical-id-args (s/tuple ::logical-id))

;; Release B reporting: a market's campaign key, or nil / "all" for All
;; tracked markets.
(s/def ::campaign-key ::common/non-empty-string)
(s/def ::campaign-key-args (s/tuple ::campaign-key))
(s/def ::report-campaign-args (s/tuple (s/nilable ::campaign-key)))
(s/def ::report-window-args (s/tuple #{:since-start :7d :30d "since-start" "7d" "30d"}))
(s/def ::report-covered-subset-args (s/tuple boolean?))

(def vol-range-action-args-spec-by-id
  {:actions/select-scale-mode ::select-scale-mode-args
   :actions/update-vol-range-draft ::update-vol-range-draft-args
   :actions/set-vol-range-sell-quantity-percent ::set-vol-range-sell-quantity-percent-args
   :actions/apply-vol-range-remediation ::apply-vol-range-remediation-args
   :actions/open-vol-range-review ::common/no-args
   :actions/close-vol-range-review ::common/no-args
   :actions/set-vol-range-ui-flag ::set-vol-range-ui-flag-args
   :actions/handle-vol-range-review-keydown ::common/key-args
   :actions/refresh-vol-range-daily-candles ::common/no-args
   ;; Release A (placement, cancel and the Vol ladders dock).
   :actions/place-vol-range-orders ::common/no-args
   :actions/cancel-vol-ladder ::grid-id-args
   :actions/refresh-vol-ladder ::grid-id-args
   :actions/select-vol-ladder ::grid-id-args
   :actions/sync-vol-ladders ::common/no-args
   :actions/reconcile-open-vol-ladders ::common/no-args
   :actions/resync-vol-ladders ::common/no-args
   ;; Release A repair of definitively rejected orders.
   :actions/open-vol-ladder-repair ::grid-id-args
   :actions/refresh-vol-ladder-repair ::common/no-args
   :actions/toggle-vol-ladder-repair-leg ::logical-id-args
   :actions/close-vol-ladder-repair ::common/no-args
   :actions/handle-vol-ladder-repair-keydown ::common/key-args
   :actions/submit-vol-ladder-repair ::common/no-args
   ;; Release A ladder update (cancel, then review a replacement).
   :actions/preview-vol-ladder-update ::grid-id-args
   :actions/open-vol-ladder-update ::common/no-args
   :actions/close-vol-ladder-update ::common/no-args
   :actions/handle-vol-ladder-update-keydown ::common/key-args
   :actions/cancel-vol-ladder-for-update ::common/no-args
   :actions/check-vol-ladder-update ::common/no-args
   :actions/toggle-vol-ladder-update-rejected ::logical-id-args
   :actions/review-vol-ladder-replacement ::common/no-args
   :actions/stop-vol-ladder-update ::common/no-args
   ;; Release B reporting (the Portfolio Vol Range tab).
   :actions/load-vol-range-report ::common/no-args
   :actions/refresh-vol-range-report ::common/no-args
   :actions/select-vol-range-report-campaign ::report-campaign-args
   :actions/request-start-vol-range-tracking ::campaign-key-args
   :actions/cancel-start-vol-range-tracking ::common/no-args
   :actions/confirm-start-vol-range-tracking ::common/no-args
   :actions/select-vol-range-report-window ::report-window-args
   :actions/set-vol-range-report-covered-subset ::report-covered-subset-args
   :actions/refresh-vol-range-report-marks ::common/no-args})

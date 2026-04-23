import Hyperopen.Formal.Common

namespace Hyperopen.Formal.EffectOrderContract

open Hyperopen.Formal

def surface : Surface := .effectOrderContract

inductive Phase where
  | projection
  | persistence
  | heavyIo
  | other
  deriving Repr, DecidableEq, Inhabited

inductive AssertionRule where
  | heavyBeforeProjectionPhase
  | duplicateHeavyEffect
  | phaseOrderRegression
  deriving Repr, DecidableEq, Inhabited

structure Policy where
  requiredPhaseOrder : List Phase := [.projection, .persistence, .heavyIo]
  requireProjectionBeforeHeavy : Bool := true
  allowDuplicateHeavyEffects : Bool := false
  heavyEffectIds : List String := []
  deriving Repr, DecidableEq, Inhabited

structure AssertionFailure where
  rule : AssertionRule
  effectIndex : Nat
  effectId : String
  deriving Repr, DecidableEq, Inhabited

inductive AssertionResult where
  | ok
  | error (failure : AssertionFailure)
  deriving Repr, DecidableEq, Inhabited

structure Summary where
  actionId : String
  covered : Bool
  effectIds : List String
  phases : List Phase
  projectionEffectCount : Nat
  heavyEffectCount : Nat
  projectionBeforeHeavy : Bool
  duplicateHeavyEffectIds : List String
  phaseOrderValid : Bool
  deriving Repr, DecidableEq, Inhabited

def projectionEffectIds : List String :=
  ["effects/save", "effects/save-many"]

def persistenceEffectIds : List String :=
  ["effects/local-storage-set"
  ,"effects/local-storage-set-json"
  ,"effects/persist-leaderboard-preferences"]

def defaultPolicy (allowDuplicateHeavyEffects : Bool) (heavyEffectIds : List String) : Policy :=
  { requiredPhaseOrder := [.projection, .persistence, .heavyIo]
    requireProjectionBeforeHeavy := true
    allowDuplicateHeavyEffects := allowDuplicateHeavyEffects
    heavyEffectIds := heavyEffectIds }

def policyCorpus : List (String × Policy) :=
  [("actions/apply-funding-history-filters", defaultPolicy false ["effects/api-fetch-user-funding-history"])
  ,("actions/cancel-order", defaultPolicy false ["effects/api-cancel-order"])
  ,("actions/cancel-twap", defaultPolicy false ["effects/api-cancel-order"])
  ,("actions/cancel-visible-open-orders", defaultPolicy false ["effects/api-cancel-order"])
  ,("actions/confirm-api-wallet-modal", defaultPolicy false ["effects/api-authorize-api-wallet", "effects/api-remove-api-wallet"])
  ,("actions/confirm-order-submission", defaultPolicy false ["effects/api-submit-order"])
  ,("actions/enable-agent-trading", defaultPolicy false ["effects/enable-agent-trading"])
  ,("actions/unlock-agent-trading", defaultPolicy false ["effects/unlock-agent-trading"])
  ,("actions/load-api-wallet-route", defaultPolicy false ["effects/api-load-api-wallets"])
  ,("actions/load-funding-comparison", defaultPolicy false ["effects/api-fetch-predicted-fundings"])
  ,("actions/load-funding-comparison-route", defaultPolicy false ["effects/api-fetch-predicted-fundings", "effects/fetch-asset-selector-markets"])
  ,("actions/load-leaderboard", defaultPolicy false ["effects/api-fetch-leaderboard"])
  ,("actions/load-leaderboard-route", defaultPolicy false ["effects/api-fetch-leaderboard"])
  ,("actions/load-staking", defaultPolicy false ["effects/api-fetch-staking-delegations", "effects/api-fetch-staking-delegator-summary", "effects/api-fetch-staking-history", "effects/api-fetch-staking-rewards", "effects/api-fetch-staking-spot-state", "effects/api-fetch-staking-validator-summaries"])
  ,("actions/load-staking-route", defaultPolicy false ["effects/api-fetch-staking-delegations", "effects/api-fetch-staking-delegator-summary", "effects/api-fetch-staking-history", "effects/api-fetch-staking-rewards", "effects/api-fetch-staking-spot-state", "effects/api-fetch-staking-validator-summaries"])
  ,("actions/load-vault-detail", defaultPolicy true ["effects/api-fetch-vault-details", "effects/api-fetch-vault-fills", "effects/api-fetch-vault-funding-history", "effects/api-fetch-vault-ledger-updates", "effects/api-fetch-vault-order-history", "effects/api-fetch-vault-webdata2"])
  ,("actions/load-vault-route", defaultPolicy true ["effects/api-fetch-user-vault-equities", "effects/api-fetch-vault-benchmark-details", "effects/api-fetch-vault-details", "effects/api-fetch-vault-fills", "effects/api-fetch-vault-funding-history", "effects/api-fetch-vault-index", "effects/api-fetch-vault-index-with-cache", "effects/api-fetch-vault-ledger-updates", "effects/api-fetch-vault-order-history", "effects/api-fetch-vault-summaries", "effects/api-fetch-vault-webdata2"])
  ,("actions/load-vaults", defaultPolicy false ["effects/api-fetch-user-vault-equities", "effects/api-fetch-vault-index", "effects/api-fetch-vault-index-with-cache", "effects/api-fetch-vault-summaries"])
  ,("actions/navigate", defaultPolicy false ["effects/api-fetch-leaderboard", "effects/api-fetch-predicted-fundings", "effects/api-fetch-staking-delegations", "effects/api-fetch-staking-delegator-summary", "effects/api-fetch-staking-history", "effects/api-fetch-staking-rewards", "effects/api-fetch-staking-spot-state", "effects/api-fetch-staking-validator-summaries", "effects/api-fetch-user-vault-equities", "effects/api-fetch-vault-details", "effects/api-fetch-vault-fills", "effects/api-fetch-vault-funding-history", "effects/api-fetch-vault-index", "effects/api-fetch-vault-index-with-cache", "effects/api-fetch-vault-ledger-updates", "effects/api-fetch-vault-order-history", "effects/api-fetch-vault-summaries", "effects/api-fetch-vault-webdata2", "effects/api-load-api-wallets", "effects/fetch-asset-selector-markets", "effects/load-route-module", "effects/load-trade-chart-module", "effects/load-trading-indicators-module"])
  ,("actions/navigate-mobile-header-menu", defaultPolicy false ["effects/api-fetch-leaderboard", "effects/api-fetch-predicted-fundings", "effects/api-fetch-staking-delegations", "effects/api-fetch-staking-delegator-summary", "effects/api-fetch-staking-history", "effects/api-fetch-staking-rewards", "effects/api-fetch-staking-spot-state", "effects/api-fetch-staking-validator-summaries", "effects/api-fetch-user-vault-equities", "effects/api-fetch-vault-details", "effects/api-fetch-vault-fills", "effects/api-fetch-vault-funding-history", "effects/api-fetch-vault-index", "effects/api-fetch-vault-index-with-cache", "effects/api-fetch-vault-ledger-updates", "effects/api-fetch-vault-order-history", "effects/api-fetch-vault-summaries", "effects/api-fetch-vault-webdata2", "effects/fetch-asset-selector-markets", "effects/load-route-module", "effects/load-trade-chart-module", "effects/load-trading-indicators-module"])
  ,("actions/select-account-info-tab", defaultPolicy false ["effects/api-fetch-historical-orders", "effects/api-fetch-user-funding-history"])
  ,("actions/open-position-reduce-popover", defaultPolicy false ["effects/fetch-asset-selector-markets"])
  ,("actions/select-asset", defaultPolicy false ["effects/subscribe-active-asset", "effects/subscribe-orderbook", "effects/subscribe-trades", "effects/sync-active-asset-funding-predictability", "effects/unsubscribe-active-asset", "effects/unsubscribe-orderbook", "effects/unsubscribe-trades"])
  ,("actions/select-chart-timeframe", defaultPolicy false ["effects/fetch-candle-snapshot", "effects/sync-active-candle-subscription"])
  ,("actions/select-orderbook-price-aggregation", defaultPolicy false ["effects/subscribe-orderbook"])
  ,("actions/select-portfolio-chart-tab", defaultPolicy true ["effects/fetch-candle-snapshot"])
  ,("actions/select-portfolio-returns-benchmark", defaultPolicy false ["effects/api-fetch-vault-benchmark-details", "effects/fetch-candle-snapshot"])
  ,("actions/select-portfolio-summary-time-range", defaultPolicy true ["effects/fetch-candle-snapshot"])
  ,("actions/select-vault-detail-returns-benchmark", defaultPolicy false ["effects/api-fetch-vault-benchmark-details", "effects/fetch-candle-snapshot"])
  ,("actions/set-portfolio-returns-benchmark-suggestions-open", defaultPolicy false ["effects/api-fetch-vault-index", "effects/api-fetch-vault-summaries"])
  ,("actions/set-vault-detail-chart-series", defaultPolicy true ["effects/fetch-candle-snapshot"])
  ,("actions/set-vault-detail-returns-benchmark-suggestions-open", defaultPolicy false ["effects/api-fetch-vault-index", "effects/api-fetch-vault-index-with-cache", "effects/api-fetch-vault-summaries"])
  ,("actions/set-vaults-snapshot-range", defaultPolicy true ["effects/fetch-candle-snapshot"])
  ,("actions/submit-cancel-visible-open-orders-confirmation", defaultPolicy false ["effects/api-cancel-order"])
  ,("actions/submit-funding-deposit", defaultPolicy false ["effects/api-submit-funding-deposit"])
  ,("actions/submit-funding-send", defaultPolicy false ["effects/api-submit-funding-send"])
  ,("actions/submit-funding-transfer", defaultPolicy false ["effects/api-submit-funding-transfer"])
  ,("actions/submit-funding-withdraw", defaultPolicy false ["effects/api-submit-funding-withdraw"])
  ,("actions/submit-order", defaultPolicy false ["effects/api-submit-order"])
  ,("actions/submit-position-reduce-close", defaultPolicy false ["effects/api-submit-order", "effects/fetch-asset-selector-markets"])
  ,("actions/submit-position-margin-update", defaultPolicy false ["effects/api-submit-position-margin"])
  ,("actions/submit-staking-delegate", defaultPolicy false ["effects/api-submit-staking-delegate"])
  ,("actions/submit-staking-deposit", defaultPolicy false ["effects/api-submit-staking-deposit"])
  ,("actions/submit-staking-undelegate", defaultPolicy false ["effects/api-submit-staking-undelegate"])
  ,("actions/submit-staking-withdraw", defaultPolicy false ["effects/api-submit-staking-withdraw"])
  ,("actions/submit-vault-transfer", defaultPolicy false ["effects/api-submit-vault-transfer"])
  ,("actions/view-all-funding-history", defaultPolicy false ["effects/api-fetch-user-funding-history"])
  ,("actions/ws-diagnostics-reconnect-now", defaultPolicy false ["effects/reconnect-websocket"])]

def lookupPolicy : List (String × Policy) → String → Option Policy
  | [], _ => none
  | (key, policy) :: rest, actionId =>
      if key = actionId then
        some policy
      else
        lookupPolicy rest actionId

def actionPolicy (actionId : String) : Option Policy :=
  lookupPolicy policyCorpus actionId

def coveredActionIds : List String :=
  policyCorpus.map Prod.fst

def coveredAction (actionId : String) : Bool :=
  match actionPolicy actionId with
  | some _ => true
  | none => false

def phaseId : Phase → String
  | .projection => "projection"
  | .persistence => "persistence"
  | .heavyIo => "heavy-io"
  | .other => "other"

def ruleId : AssertionRule → String
  | .heavyBeforeProjectionPhase => "heavy-before-projection-phase"
  | .duplicateHeavyEffect => "duplicate-heavy-effect"
  | .phaseOrderRegression => "phase-order-regression"

def effectPhase (policy : Policy) (effectId : String) : Phase :=
  if effectId ∈ projectionEffectIds then
    .projection
  else if effectId ∈ persistenceEffectIds then
    .persistence
  else if effectId ∈ policy.heavyEffectIds then
    .heavyIo
  else
    .other

def phaseRank : List Phase → Phase → Option Nat
  | [], _ => none
  | candidate :: rest, phase =>
      if candidate = phase then
        some 0
      else
        match phaseRank rest phase with
        | some rank => some (rank + 1)
        | none => none

def firstPhaseIndex : List Phase → Phase → Option Nat
  | [], _ => none
  | current :: rest, target =>
      if current = target then
        some 0
      else
        match firstPhaseIndex rest target with
        | some index => some (index + 1)
        | none => none

def countPhase : List Phase → Phase → Nat
  | [], _ => 0
  | current :: rest, target =>
      (if current = target then 1 else 0) + countPhase rest target

def duplicateHeavyEffectIdsAux :
    List String → List String → List String → List String → List String
  | [], _, _, duplicates => duplicates
  | effectId :: rest, heavyEffectIds, seen, duplicates =>
      if effectId ∈ heavyEffectIds then
        if effectId ∈ seen then
          duplicateHeavyEffectIdsAux rest heavyEffectIds seen (duplicates ++ [effectId])
        else
          duplicateHeavyEffectIdsAux rest heavyEffectIds (seen ++ [effectId]) duplicates
      else
        duplicateHeavyEffectIdsAux rest heavyEffectIds seen duplicates

def duplicateHeavyEffectIds (policy : Policy) (effectIds : List String) : List String :=
  duplicateHeavyEffectIdsAux effectIds policy.heavyEffectIds [] []

def projectionBeforeHeavy (phases : List Phase) : Bool :=
  match firstPhaseIndex phases .heavyIo with
  | none => true
  | some heavyIndex =>
      match firstPhaseIndex phases .projection with
      | some projectionIndex => projectionIndex < heavyIndex
      | none => false

def assertActionEffectOrder (actionId : String) (effectIds : List String) : AssertionResult :=
  match actionPolicy actionId with
  | none => .ok
  | some policy =>
      let rec go
          (remaining : List String)
          (effectIndex : Nat)
          (projectionSeen : Bool)
          (previousRank : Option Nat)
          (seenHeavy : List String) : AssertionResult :=
        match remaining with
        | [] => .ok
        | effectId :: rest =>
            let phase := effectPhase policy effectId
            let isProjection := decide (phase = .projection)
            let isHeavy := decide (phase = .heavyIo)
            let projectionSeen' := projectionSeen || isProjection
            if isHeavy && policy.requireProjectionBeforeHeavy && !projectionSeen then
              .error {rule := .heavyBeforeProjectionPhase, effectIndex := effectIndex, effectId := effectId}
            else if isHeavy && !policy.allowDuplicateHeavyEffects && effectId ∈ seenHeavy then
              .error {rule := .duplicateHeavyEffect, effectIndex := effectIndex, effectId := effectId}
            else
              let seenHeavy' :=
                if isHeavy then
                  seenHeavy ++ [effectId]
                else
                  seenHeavy
              match phaseRank policy.requiredPhaseOrder phase with
              | some currentRank =>
                  match previousRank with
                  | some priorRank =>
                      if currentRank < priorRank then
                        .error {rule := .phaseOrderRegression, effectIndex := effectIndex, effectId := effectId}
                      else
                        go rest (effectIndex + 1) projectionSeen' (some currentRank) seenHeavy'
                  | none => go rest (effectIndex + 1) projectionSeen' (some currentRank) seenHeavy'
              | none => go rest (effectIndex + 1) projectionSeen' previousRank seenHeavy'
      go effectIds 0 false none []

def effectOrderSummary (actionId : String) (effectIds : List String) : Summary :=
  let policy := (actionPolicy actionId).getD (defaultPolicy false [])
  let phases := effectIds.map (effectPhase policy)
  { actionId := actionId
    covered := coveredAction actionId
    effectIds := effectIds
    phases := phases
    projectionEffectCount := countPhase phases .projection
    heavyEffectCount := countPhase phases .heavyIo
    projectionBeforeHeavy := projectionBeforeHeavy phases
    duplicateHeavyEffectIds := duplicateHeavyEffectIds policy effectIds
    phaseOrderValid := assertActionEffectOrder actionId effectIds = .ok }

theorem surface_id :
    surfaceId surface = "effect-order-contract" := by
  rfl

theorem manifest_spec :
    surfaceManifest surface =
      "{:surface \"effect-order-contract\" :module \"Hyperopen.Formal.EffectOrderContract\" :status \"modeled\"}\n" := by
  rfl

def duplicateHeavyRejected : Bool :=
  match assertActionEffectOrder
      "actions/select-orderbook-price-aggregation"
      ["effects/save", "effects/subscribe-orderbook", "effects/subscribe-orderbook"] with
  | .error {rule := .duplicateHeavyEffect, effectIndex := 2, effectId := "effects/subscribe-orderbook"} => true
  | _ => false

def heavyBeforeProjectionRejected : Bool :=
  match assertActionEffectOrder
      "actions/select-chart-timeframe"
      ["effects/sync-active-candle-subscription", "effects/save"] with
  | .error {rule := .heavyBeforeProjectionPhase, effectIndex := 0, effectId := "effects/sync-active-candle-subscription"} => true
  | _ => false

def phaseRegressionRejected : Bool :=
  match assertActionEffectOrder
      "actions/select-portfolio-returns-benchmark"
      ["effects/save", "effects/fetch-candle-snapshot", "effects/local-storage-set"] with
  | .error {rule := .phaseOrderRegression, effectIndex := 2, effectId := "effects/local-storage-set"} => true
  | _ => false

theorem save_is_projection :
    effectPhase (defaultPolicy false ["effects/api-submit-order"]) "effects/save" = .projection := by
  native_decide

theorem duplicate_heavy_rejected_when_forbidden :
    duplicateHeavyRejected = true := by
  native_decide

theorem heavy_before_projection_rejected :
    heavyBeforeProjectionRejected = true := by
  native_decide

theorem phase_regression_rejected :
    phaseRegressionRejected = true := by
  native_decide

theorem duplicate_heavy_allowed_when_permitted :
    assertActionEffectOrder
      "actions/select-portfolio-summary-time-range"
      ["effects/save", "effects/fetch-candle-snapshot", "effects/fetch-candle-snapshot"] = .ok := by
  native_decide

theorem uncovered_actions_pass_through :
    assertActionEffectOrder
      "actions/not-covered"
      ["effects/subscribe-orderbook", "effects/local-storage-set"] = .ok := by
  native_decide

theorem summary_marks_phase_order_invalid_for_regression :
    (effectOrderSummary
      "actions/select-portfolio-returns-benchmark"
      ["effects/save", "effects/fetch-candle-snapshot", "effects/local-storage-set"]).phaseOrderValid = false := by
  native_decide

def kv (key : String) (value : Clj) : Clj × Clj :=
  (.keyword key, value)

def mapClj (entries : List (Clj × Clj)) : Clj :=
  .arrayMap entries

def keywordVec (values : List String) : Clj :=
  .vector (values.map Clj.keyword)

def phaseVec (values : List Phase) : Clj :=
  keywordVec (values.map phaseId)

def effectVectors (effectIds : List String) : Clj :=
  .vector (effectIds.map fun effectId => .vector [.keyword effectId])

def policyToClj (policy : Policy) : Clj :=
  mapClj
    [kv "required-phase-order" (phaseVec policy.requiredPhaseOrder)
    ,kv "require-projection-before-heavy?" (.bool policy.requireProjectionBeforeHeavy)
    ,kv "allow-duplicate-heavy-effects?" (.bool policy.allowDuplicateHeavyEffects)
    ,kv "heavy-effect-ids" (keywordVec policy.heavyEffectIds)]

def assertionResultToClj : AssertionResult → Clj
  | .ok => mapClj [kv "ok?" (.bool true)]
  | .error failure =>
      mapClj
        [kv "ok?" (.bool false)
        ,kv "rule" (.keyword (ruleId failure.rule))
        ,kv "effect-index" (.nat failure.effectIndex)
        ,kv "effect-id" (.keyword failure.effectId)]

def summaryToClj (summary : Summary) : Clj :=
  mapClj
    [kv "action-id" (.keyword summary.actionId)
    ,kv "covered?" (.bool summary.covered)
    ,kv "effect-ids" (keywordVec summary.effectIds)
    ,kv "phases" (phaseVec summary.phases)
    ,kv "projection-effect-count" (.nat summary.projectionEffectCount)
    ,kv "heavy-effect-count" (.nat summary.heavyEffectCount)
    ,kv "projection-before-heavy" (.bool summary.projectionBeforeHeavy)
    ,kv "duplicate-heavy-effect-ids" (keywordVec summary.duplicateHeavyEffectIds)
    ,kv "phase-order-valid" (.bool summary.phaseOrderValid)]

def policyVectorToClj (entry : String × Policy) : Clj :=
  mapClj
    [kv "action-id" (.keyword entry.fst)
    ,kv "expected" (policyToClj entry.snd)]

structure Example where
  id : String
  actionId : String
  effectIds : List String
  deriving Repr, DecidableEq, Inhabited

structure WrapperExample where
  id : String
  actionId : String
  validationEnabled : Bool
  effectIds : List String
  deriving Repr, DecidableEq, Inhabited

def assertionExamples : List Example :=
  [{ id := "uncovered-pass-through"
     actionId := "actions/not-covered"
     effectIds := ["effects/subscribe-orderbook", "effects/local-storage-set"] }
  ,{ id := "covered-valid-basic"
     actionId := "actions/select-chart-timeframe"
     effectIds := ["effects/save", "effects/sync-active-candle-subscription", "effects/fetch-candle-snapshot"] }
  ,{ id := "covered-heavy-before-projection"
     actionId := "actions/select-chart-timeframe"
     effectIds := ["effects/sync-active-candle-subscription", "effects/save"] }
  ,{ id := "covered-phase-order-regression-json-persistence"
     actionId := "actions/select-chart-timeframe"
     effectIds := ["effects/save", "effects/fetch-candle-snapshot", "effects/local-storage-set-json"] }
  ,{ id := "covered-phase-order-regression-leaderboard-persistence"
     actionId := "actions/select-chart-timeframe"
     effectIds := ["effects/save", "effects/fetch-candle-snapshot", "effects/persist-leaderboard-preferences"] }
  ,{ id := "covered-phase-order-regression"
     actionId := "actions/select-portfolio-returns-benchmark"
     effectIds := ["effects/save", "effects/fetch-candle-snapshot", "effects/local-storage-set"] }
  ,{ id := "covered-duplicate-heavy-rejected"
     actionId := "actions/select-orderbook-price-aggregation"
     effectIds := ["effects/save", "effects/subscribe-orderbook", "effects/subscribe-orderbook"] }
  ,{ id := "covered-duplicate-heavy-allowed"
     actionId := "actions/select-portfolio-summary-time-range"
     effectIds := ["effects/save", "effects/fetch-candle-snapshot", "effects/fetch-candle-snapshot"] }]

def wrapperExamples : List WrapperExample :=
  [{ id := "wrapper-uncovered-pass-through-enabled"
     actionId := "actions/not-covered"
     validationEnabled := true
     effectIds := ["effects/subscribe-orderbook", "effects/local-storage-set"] }
  ,{ id := "wrapper-valid-enabled"
     actionId := "actions/select-chart-timeframe"
     validationEnabled := true
     effectIds := ["effects/save", "effects/sync-active-candle-subscription", "effects/fetch-candle-snapshot"] }
  ,{ id := "wrapper-heavy-before-projection-enabled"
     actionId := "actions/select-chart-timeframe"
     validationEnabled := true
     effectIds := ["effects/sync-active-candle-subscription", "effects/save"] }
  ,{ id := "wrapper-phase-regression-enabled"
     actionId := "actions/select-portfolio-returns-benchmark"
     validationEnabled := true
     effectIds := ["effects/save", "effects/fetch-candle-snapshot", "effects/local-storage-set"] }
  ,{ id := "wrapper-duplicate-heavy-rejected-enabled"
     actionId := "actions/select-orderbook-price-aggregation"
     validationEnabled := true
     effectIds := ["effects/save", "effects/subscribe-orderbook", "effects/subscribe-orderbook"] }
  ,{ id := "wrapper-duplicate-heavy-allowed-enabled"
     actionId := "actions/select-portfolio-summary-time-range"
     validationEnabled := true
     effectIds := ["effects/save", "effects/fetch-candle-snapshot", "effects/fetch-candle-snapshot"] }
  ,{ id := "wrapper-phase-regression-disabled"
     actionId := "actions/select-portfolio-returns-benchmark"
     validationEnabled := false
     effectIds := ["effects/save", "effects/fetch-candle-snapshot", "effects/local-storage-set"] }]

def assertionVectorToClj (sample : Example) : Clj :=
  mapClj
    [kv "id" (.keyword sample.id)
    ,kv "action-id" (.keyword sample.actionId)
    ,kv "effects" (effectVectors sample.effectIds)
    ,kv "expected" (assertionResultToClj (assertActionEffectOrder sample.actionId sample.effectIds))]

def summaryVectorToClj (sample : Example) : Clj :=
  mapClj
    [kv "id" (.keyword sample.id)
    ,kv "action-id" (.keyword sample.actionId)
    ,kv "effects" (effectVectors sample.effectIds)
    ,kv "expected" (summaryToClj (effectOrderSummary sample.actionId sample.effectIds))]

def wrapperExpectedToClj (sample : WrapperExample) : Clj :=
  if sample.validationEnabled then
    assertionResultToClj (assertActionEffectOrder sample.actionId sample.effectIds)
  else
    mapClj [kv "ok?" (.bool true)]

def wrapperVectorToClj (sample : WrapperExample) : Clj :=
  let summary := effectOrderSummary sample.actionId sample.effectIds
  let recordsDebugSummary := !sample.validationEnabled || assertActionEffectOrder sample.actionId sample.effectIds = .ok
  mapClj
    [kv "id" (.keyword sample.id)
    ,kv "action-id" (.keyword sample.actionId)
    ,kv "validation-enabled?" (.bool sample.validationEnabled)
    ,kv "effects" (effectVectors sample.effectIds)
    ,kv "expected" (wrapperExpectedToClj sample)
    ,kv "records-debug-summary?" (.bool recordsDebugSummary)
    ,kv "expected-summary" (summaryToClj summary)]

def generatedSource : String :=
  renderNamespace "hyperopen.formal.effect-order-contract-vectors"
    [("effect-order-policy-vectors", .vector (policyCorpus.map policyVectorToClj))
    ,("effect-order-assertion-vectors", .vector (assertionExamples.map assertionVectorToClj))
    ,("effect-order-summary-vectors", .vector (assertionExamples.map summaryVectorToClj))
    ,("effect-order-wrapper-vectors", .vector (wrapperExamples.map wrapperVectorToClj))]

def verify : IO Unit := do
  writeGeneratedSource surface generatedSource

def sync : IO Unit := do
  writeGeneratedSource surface generatedSource

end Hyperopen.Formal.EffectOrderContract

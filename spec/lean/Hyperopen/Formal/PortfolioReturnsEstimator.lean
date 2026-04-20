import Hyperopen.Formal.Common

namespace Hyperopen.Formal.PortfolioReturnsEstimator

open Hyperopen.Formal

def surface : Surface := .portfolioReturnsEstimator

def dayMs : Nat := 24 * 60 * 60 * 1000

structure Ratio where
  numerator : Int
  denominator : Nat
  deriving Repr, DecidableEq, Inhabited

structure ObservedPoint where
  timeMs : Nat
  accountValue : Int
  pnlValue : Int
  deriving Repr, DecidableEq, Inhabited

structure Summary where
  accountValueHistory : List (Nat × Int) := []
  pnlHistory : List (Nat × Int) := []
  deriving Repr, DecidableEq, Inhabited

structure CumulativeRow where
  timeMs : Nat
  percent : Ratio
  deriving Repr, DecidableEq, Inhabited

structure IntervalReturnRow where
  timeMs : Nat
  returnValue : Ratio
  deriving Repr, DecidableEq, Inhabited

structure DailyReturnRow where
  day : String
  timeMs : Nat
  returnValue : Ratio
  deriving Repr, DecidableEq, Inhabited

structure ReturnsHistoryState where
  previous : ObservedPoint
  cumulativeFactor : Ratio
  rows : List CumulativeRow
  deriving Repr, DecidableEq, Inhabited

structure LatentStep where
  dtMs : Nat
  pnlDelta : Int
  cashFlow : Int
  deriving Repr, DecidableEq, Inhabited

structure LatentSnapshot where
  timeMs : Nat
  accountValue : Int
  cumulativePnl : Int
  cumulativeFactor : Ratio
  deriving Repr, DecidableEq, Inhabited

inductive PnlWindowMode where
  | cumulative
  | rebasedAtFirstSample
  deriving Repr, DecidableEq, Inhabited

structure ObservationPolicy where
  sampleIndexes : List Nat
  pnlWindowMode : PnlWindowMode := .cumulative
  deriving Repr, DecidableEq, Inhabited

structure SimulatorCase where
  id : String
  initialAccountValue : Int
  steps : List LatentStep
  observation : ObservationPolicy
  exact : Bool
  avoidFalseWipeout : Bool
  maxFinalErrorBps : Nat
  deriving Repr, DecidableEq, Inhabited

def mkRatio (numerator : Int) (denominator : Nat) : Ratio :=
  let denominator' := if denominator = 0 then 1 else denominator
  let divisor := Nat.gcd numerator.natAbs denominator'
  let divisor' := if divisor = 0 then 1 else divisor
  let normalizedDenominator := denominator' / divisor'
  let normalizedAbsNumerator := numerator.natAbs / divisor'
  let normalizedNumerator : Int :=
    if numerator < 0 then
      -(Int.ofNat normalizedAbsNumerator)
    else
      Int.ofNat normalizedAbsNumerator
  { numerator := normalizedNumerator
    denominator := normalizedDenominator }

def ratioZero : Ratio := mkRatio 0 1

def ratioOne : Ratio := mkRatio 1 1

def ratioFromInt (value : Int) : Ratio := mkRatio value 1

def ratioAdd (left right : Ratio) : Ratio :=
  mkRatio
    (left.numerator * Int.ofNat right.denominator +
      right.numerator * Int.ofNat left.denominator)
    (left.denominator * right.denominator)

def ratioSub (left right : Ratio) : Ratio :=
  mkRatio
    (left.numerator * Int.ofNat right.denominator -
      right.numerator * Int.ofNat left.denominator)
    (left.denominator * right.denominator)

def ratioMul (left right : Ratio) : Ratio :=
  mkRatio
    (left.numerator * right.numerator)
    (left.denominator * right.denominator)

def ratioDiv? (left right : Ratio) : Option Ratio :=
  if right.numerator = 0 then
    none
  else
    let sign : Int := if right.numerator < 0 then -1 else 1
    some <|
      mkRatio
        (left.numerator * Int.ofNat right.denominator * sign)
        (left.denominator * right.numerator.natAbs)

def ratioLe (left right : Ratio) : Bool :=
  left.numerator * Int.ofNat right.denominator ≤
    right.numerator * Int.ofNat left.denominator

def ratioLt (left right : Ratio) : Bool :=
  left.numerator * Int.ofNat right.denominator <
    right.numerator * Int.ofNat left.denominator

def ratioPositive (value : Ratio) : Bool :=
  value.numerator > 0

def cumulativePercentFromFactor (factor : Ratio) : Ratio :=
  mkRatio
    (100 * (factor.numerator - Int.ofNat factor.denominator))
    factor.denominator

def factorFromPercent (percent : Ratio) : Ratio :=
  mkRatio
    (Int.ofNat (100 * percent.denominator) + percent.numerator)
    (100 * percent.denominator)

def clampLowerReturn : Ratio := mkRatio (-999999) 1000000

def dedupeHistoryRowsByTime : List (Nat × Int) → List (Nat × Int)
  | [] => []
  | row :: rest =>
      match dedupeHistoryRowsByTime rest with
      | [] => [row]
      | next :: tail =>
          if row.fst = next.fst then
            next :: tail
          else
            row :: next :: tail

def lookupHistoryValue : Nat → List (Nat × Int) → Option Int
  | _, [] => none
  | timeMs, (candidateTimeMs, value) :: rest =>
      if candidateTimeMs = timeMs then
        some value
      else
        lookupHistoryValue timeMs rest

def alignedAccountPnlPoints (summary : Summary) : List ObservedPoint :=
  let accountRows := dedupeHistoryRowsByTime summary.accountValueHistory
  let pnlRows := dedupeHistoryRowsByTime summary.pnlHistory
  accountRows.filterMap fun (timeMs, accountValue) =>
    match lookupHistoryValue timeMs pnlRows with
    | some pnlValue =>
        some { timeMs := timeMs
               accountValue := accountValue
               pnlValue := pnlValue }
    | none => none

def anchoredAccountPnlPoints : Summary → List ObservedPoint
  | summary =>
      let rec dropLeading : List ObservedPoint → List ObservedPoint
        | [] => []
        | point :: rest =>
            if point.accountValue > 0 then
              point :: rest
            else
              dropLeading rest
      dropLeading (alignedAccountPnlPoints summary)

def deltaAccount (previous current : ObservedPoint) : Int :=
  current.accountValue - previous.accountValue

def deltaPnl (previous current : ObservedPoint) : Int :=
  current.pnlValue - previous.pnlValue

def impliedCashFlow (previous current : ObservedPoint) : Int :=
  deltaAccount previous current - deltaPnl previous current

def cashFlowRatioDenominator (previousAccountValue : Int) : Nat :=
  if previousAccountValue > 1 then
    Int.toNat previousAccountValue
  else
    1

def flowRatioBelowHalf (previousAccountValue impliedCashFlowValue : Int) : Bool :=
  2 * impliedCashFlowValue.natAbs < cashFlowRatioDenominator previousAccountValue

def flowRatioAtLeastHalf (previousAccountValue impliedCashFlowValue : Int) : Bool :=
  cashFlowRatioDenominator previousAccountValue ≤ 2 * impliedCashFlowValue.natAbs

def modifiedDietzReturn
    (deltaPnlValue previousAccountValue impliedCashFlowValue : Int) :
    Option Ratio :=
  let denominatorTwice := 2 * previousAccountValue + impliedCashFlowValue
  if denominatorTwice > 0 && flowRatioBelowHalf previousAccountValue impliedCashFlowValue then
    some (mkRatio (2 * deltaPnlValue) (Int.toNat denominatorTwice))
  else
    none

def fallbackPeriodReturn (deltaPnlValue previousAccountValue : Int) : Option Ratio :=
  if previousAccountValue > 0 then
    some (mkRatio deltaPnlValue (Int.toNat previousAccountValue))
  else
    none

def indeterminateCashFlow
    (previous current : ObservedPoint)
    (impliedCashFlowValue : Int) :
    Bool :=
  current.accountValue > 0 &&
    flowRatioAtLeastHalf previous.accountValue impliedCashFlowValue

def clampPeriodReturn (value : Ratio) : Ratio :=
  if ratioLt value clampLowerReturn then
    clampLowerReturn
  else
    value

def boundedPeriodReturn (previous current : ObservedPoint) : Ratio :=
  let deltaPnlValue := deltaPnl previous current
  let impliedCashFlowValue := impliedCashFlow previous current
  let rawReturn :=
    if indeterminateCashFlow previous current impliedCashFlowValue then
      ratioZero
    else
      match modifiedDietzReturn deltaPnlValue previous.accountValue impliedCashFlowValue with
      | some value => value
      | none =>
          match fallbackPeriodReturn deltaPnlValue previous.accountValue with
          | some value => value
          | none => ratioZero
  clampPeriodReturn rawReturn

def initialReturnsHistoryState (point : ObservedPoint) : ReturnsHistoryState :=
  { previous := point
    cumulativeFactor := ratioOne
    rows := [{ timeMs := point.timeMs, percent := ratioZero }] }

def appendReturnsHistoryRow
    (state : ReturnsHistoryState)
    (current : ObservedPoint) :
    ReturnsHistoryState :=
  let periodReturn := boundedPeriodReturn state.previous current
  let nextFactor := ratioMul state.cumulativeFactor (ratioAdd ratioOne periodReturn)
  let nextPercent := cumulativePercentFromFactor nextFactor
  { previous := current
    cumulativeFactor := nextFactor
    rows := state.rows ++ [{ timeMs := current.timeMs, percent := nextPercent }] }

def returnsHistoryRowsFromSummary (summary : Summary) : List CumulativeRow :=
  match anchoredAccountPnlPoints summary with
  | [] => []
  | first :: rest =>
      (rest.foldl appendReturnsHistoryRow (initialReturnsHistoryState first)).rows

def dedupeCumulativeRowsByTime : List CumulativeRow → List CumulativeRow
  | [] => []
  | row :: rest =>
      match dedupeCumulativeRowsByTime rest with
      | [] => [row]
      | next :: tail =>
          if row.timeMs = next.timeMs then
            next :: tail
          else
            row :: next :: tail

def intervalReturnForPair (previous current : CumulativeRow) : Ratio :=
  let previousFactor := factorFromPercent previous.percent
  let currentFactor := factorFromPercent current.percent
  if ratioPositive previousFactor then
    match ratioDiv? currentFactor previousFactor with
    | some ratio => ratioSub ratio ratioOne
    | none => ratioZero
  else
    ratioZero

def cumulativePercentRowsToIntervalReturns
    (rows : List CumulativeRow) :
    List IntervalReturnRow :=
  let rows' := dedupeCumulativeRowsByTime rows
  let rec go : List CumulativeRow → List IntervalReturnRow
    | [] => []
    | [_] => []
    | previous :: current :: rest =>
        { timeMs := current.timeMs
          returnValue := intervalReturnForPair previous current } :: go (current :: rest)
  go rows'

def pad2 (value : Nat) : String :=
  if value < 10 then
    "0" ++ toString value
  else
    toString value

def dayStringFromMs (timeMs : Nat) : String :=
  let dayIndex := timeMs / dayMs
  "1970-01-" ++ pad2 (dayIndex + 1)

def dailyCompoundedReturns (rows : List CumulativeRow) : List DailyReturnRow :=
  let intervalRows := cumulativePercentRowsToIntervalReturns rows
  let rec flush
      (currentDay : Option String)
      (currentFactor : Ratio)
      (currentTimeMs : Nat)
      (output : List DailyReturnRow) :
      List DailyReturnRow :=
    match currentDay with
    | some day =>
        output ++ [{ day := day
                     timeMs := currentTimeMs
                     returnValue := ratioSub currentFactor ratioOne }]
    | none => output
  let rec go
      (remaining : List IntervalReturnRow)
      (currentDay : Option String)
      (currentFactor : Ratio)
      (currentTimeMs : Nat)
      (output : List DailyReturnRow) :
      List DailyReturnRow :=
    match remaining with
    | [] => flush currentDay currentFactor currentTimeMs output
    | row :: rest =>
        let day := dayStringFromMs row.timeMs
        let factor := ratioAdd ratioOne row.returnValue
        match currentDay with
        | some existingDay =>
            if existingDay = day then
              go rest (some existingDay) (ratioMul currentFactor factor) row.timeMs output
            else
              let flushed := flush currentDay currentFactor currentTimeMs output
              go rest (some day) factor row.timeMs flushed
        | none =>
            go rest (some day) factor row.timeMs output
  go intervalRows none ratioOne 0 []

def latentStepReturn (previousAccountValue pnlDelta : Int) : Ratio :=
  if previousAccountValue > 0 then
    mkRatio pnlDelta (Int.toNat previousAccountValue)
  else
    ratioZero

def simulateSnapshots
    (initialAccountValue : Int)
    (steps : List LatentStep) :
    List LatentSnapshot :=
  let initial :=
    { timeMs := 0
      accountValue := initialAccountValue
      cumulativePnl := 0
      cumulativeFactor := ratioOne }
  let rec go
      (previous : LatentSnapshot)
      (remaining : List LatentStep)
      (acc : List LatentSnapshot) :
      List LatentSnapshot :=
    match remaining with
    | [] => acc
    | step :: rest =>
        let stepReturn := latentStepReturn previous.accountValue step.pnlDelta
        let next :=
          { timeMs := previous.timeMs + step.dtMs
            accountValue := previous.accountValue + step.pnlDelta + step.cashFlow
            cumulativePnl := previous.cumulativePnl + step.pnlDelta
            cumulativeFactor := ratioMul previous.cumulativeFactor (ratioAdd ratioOne stepReturn) }
        go next rest (acc ++ [next])
  go initial steps [initial]

def listGet? : List α → Nat → Option α
  | [], _ => none
  | value :: _, 0 => some value
  | _ :: rest, index + 1 => listGet? rest index

def sampledSnapshots
    (snapshots : List LatentSnapshot)
    (sampleIndexes : List Nat) :
    List LatentSnapshot :=
  sampleIndexes.filterMap fun idx => listGet? snapshots idx

def observedSummaryFromSnapshots
    (snapshots : List LatentSnapshot)
    (observation : ObservationPolicy) :
    Summary :=
  let sampled := sampledSnapshots snapshots observation.sampleIndexes
  match sampled with
  | [] => {}
  | first :: _ =>
      let pnlBase :=
        match observation.pnlWindowMode with
        | .cumulative => 0
        | .rebasedAtFirstSample => first.cumulativePnl
      { accountValueHistory := sampled.map fun snapshot => (snapshot.timeMs, snapshot.accountValue)
        pnlHistory := sampled.map fun snapshot => (snapshot.timeMs, snapshot.cumulativePnl - pnlBase) }

def observedSummaryFromCase (simCase : SimulatorCase) : Summary :=
  observedSummaryFromSnapshots (simulateSnapshots simCase.initialAccountValue simCase.steps) simCase.observation

def latentWindowFinalPercent (simCase : SimulatorCase) : Ratio :=
  let snapshots := simulateSnapshots simCase.initialAccountValue simCase.steps
  let sampled := sampledSnapshots snapshots simCase.observation.sampleIndexes
  match sampled with
  | [] => ratioZero
  | [single] =>
      let _ := single
      ratioZero
  | first :: rest =>
      let last := rest.getLastD first
      match ratioDiv? last.cumulativeFactor first.cumulativeFactor with
      | some windowFactor => cumulativePercentFromFactor windowFactor
      | none => ratioZero

def estimatorRowsFromCase (simCase : SimulatorCase) : List CumulativeRow :=
  returnsHistoryRowsFromSummary (observedSummaryFromCase simCase)

def finalPercentFromRows (rows : List CumulativeRow) : Ratio :=
  match rows.reverse with
  | [] => ratioZero
  | row :: _ => row.percent

def estimatorFinalPercent (simCase : SimulatorCase) : Ratio :=
  finalPercentFromRows (estimatorRowsFromCase simCase)

def maxFinalErrorBps (simCase : SimulatorCase) : Ratio :=
  ratioFromInt simCase.maxFinalErrorBps

def finalErrorBps (simCase : SimulatorCase) : Ratio :=
  let delta := ratioSub (estimatorFinalPercent simCase) (latentWindowFinalPercent simCase)
  let deltaAbs :=
    if delta.numerator < 0 then
      mkRatio (-delta.numerator) delta.denominator
    else
      delta
  ratioMul deltaAbs (ratioFromInt 100)

def falseWipeout (simCase : SimulatorCase) : Bool :=
  let estimatorFinal := estimatorFinalPercent simCase
  let latentFinal := latentWindowFinalPercent simCase
  estimatorFinal = mkRatio (-999999) 10000 && ratioPositive latentFinal

def sharedTimestampsSummary : Summary :=
  { accountValueHistory := [(1, 100), (2, 120), (4, 140)]
    pnlHistory := [(1, 0), (3, 10), (4, 20)] }

def invalidDietzSummary : Summary :=
  { accountValueHistory := [(1, 10), (2, 1), (3, 2)]
    pnlHistory := [(1, 0), (2, 20), (3, 21)] }

def skipLeadingAnchorSummary : Summary :=
  { accountValueHistory := [(1, 0), (2, -10), (3, 100), (4, 110)]
    pnlHistory := [(1, 0), (2, 0), (3, 0), (4, 10)] }

def noPositiveAnchorSummary : Summary :=
  { accountValueHistory := [(1, 0), (2, -10)]
    pnlHistory := [(1, 0), (2, 5)] }

def catastrophicLossSummary : Summary :=
  { accountValueHistory := [(1, 100), (2, 0)]
    pnlHistory := [(1, 0), (2, -200)] }

def positiveRebaseSummary : Summary :=
  { accountValueHistory := [(1, 1000), (2, 60001000), (3, 63001000)]
    pnlHistory := [(1, 0), (2, -100), (3, 2999900)] }

def duplicateTimestampSummary : Summary :=
  { accountValueHistory := [(1, 100), (2, 120), (2, 130), (3, 143)]
    pnlHistory := [(1, 0), (2, 10), (2, 15), (3, 28)] }

def repeatedRebaseSummary : Summary :=
  { accountValueHistory := [(1, 100), (2, 1100), (3, 2100), (4, 2310)]
    pnlHistory := [(1, 0), (2, 0), (3, 0), (4, 210)] }

def simpleCompoundingRows : List CumulativeRow :=
  [{ timeMs := 1000, percent := ratioZero }
  ,{ timeMs := 2000, percent := mkRatio 10 1 }
  ,{ timeMs := 3000, percent := mkRatio 21 1 }]

def negativeIntervalRows : List CumulativeRow :=
  [{ timeMs := 1000, percent := ratioZero }
  ,{ timeMs := 2000, percent := mkRatio (-50) 1 }
  ,{ timeMs := 3000, percent := mkRatio (-25) 1 }]

def nonPositiveFactorRows : List CumulativeRow :=
  [{ timeMs := 1000, percent := ratioZero }
  ,{ timeMs := 2000, percent := mkRatio (-100) 1 }
  ,{ timeMs := 3000, percent := mkRatio (-50) 1 }]

def multiDayRows : List CumulativeRow :=
  [{ timeMs := 0, percent := ratioZero }
  ,{ timeMs := 1000, percent := mkRatio 10 1 }
  ,{ timeMs := dayMs + 1000, percent := mkRatio 21 1 }
  ,{ timeMs := dayMs + 2000, percent := mkRatio 331 10 }]

def noFlowCadenceSteps : List LatentStep :=
  [{ dtMs := 60000, pnlDelta := 10, cashFlow := 0 }
  ,{ dtMs := 60000, pnlDelta := -5, cashFlow := 0 }
  ,{ dtMs := 60000, pnlDelta := 5, cashFlow := 0 }
  ,{ dtMs := 60000, pnlDelta := 6, cashFlow := 0 }]

def noFlowFullCadenceCase : SimulatorCase :=
  { id := "no-flow-full-cadence-exact"
    initialAccountValue := 100
    steps := noFlowCadenceSteps
    observation := { sampleIndexes := [0, 1, 2, 3, 4], pnlWindowMode := .cumulative }
    exact := true
    avoidFalseWipeout := true
    maxFinalErrorBps := 0 }

def noFlowEverySecondCase : SimulatorCase :=
  { id := "no-flow-every-second-step-exact"
    initialAccountValue := 100
    steps := noFlowCadenceSteps
    observation := { sampleIndexes := [0, 2, 4], pnlWindowMode := .cumulative }
    exact := true
    avoidFalseWipeout := true
    maxFinalErrorBps := 0 }

def noFlowShiftedWindowCase : SimulatorCase :=
  { id := "no-flow-shifted-window-rebased-exact"
    initialAccountValue := 100
    steps := noFlowCadenceSteps
    observation := { sampleIndexes := [1, 4], pnlWindowMode := .rebasedAtFirstSample }
    exact := true
    avoidFalseWipeout := true
    maxFinalErrorBps := 0 }

def visibleRebaseSteps : List LatentStep :=
  [{ dtMs := 60000, pnlDelta := 0, cashFlow := 60000000 }
  ,{ dtMs := 60000, pnlDelta := 3000000, cashFlow := 0 }]

def visibleRebaseCase : SimulatorCase :=
  { id := "positive-rebase-full-cadence-exact"
    initialAccountValue := 1000
    steps := visibleRebaseSteps
    observation := { sampleIndexes := [0, 1, 2], pnlWindowMode := .cumulative }
    exact := true
    avoidFalseWipeout := true
    maxFinalErrorBps := 0 }

def mergedSmallTradeSteps : List LatentStep :=
  [{ dtMs := 60000, pnlDelta := 0, cashFlow := 100000 }
  ,{ dtMs := 60000, pnlDelta := 1010, cashFlow := 0 }]

def mergedSmallTradeCase : SimulatorCase :=
  { id := "positive-rebase-merged-small-trade"
    initialAccountValue := 1000
    steps := mergedSmallTradeSteps
    observation := { sampleIndexes := [0, 2], pnlWindowMode := .cumulative }
    exact := false
    avoidFalseWipeout := true
    maxFinalErrorBps := 100 }

def rebaseThenLossSteps : List LatentStep :=
  [{ dtMs := 60000, pnlDelta := 0, cashFlow := 1000 }
  ,{ dtMs := 60000, pnlDelta := -200, cashFlow := 0 }]

def rebaseThenLossCase : SimulatorCase :=
  { id := "positive-rebase-followed-by-real-loss"
    initialAccountValue := 1000
    steps := rebaseThenLossSteps
    observation := { sampleIndexes := [0, 1, 2], pnlWindowMode := .cumulative }
    exact := true
    avoidFalseWipeout := true
    maxFinalErrorBps := 0 }

def simulatorCases : List SimulatorCase :=
  [noFlowFullCadenceCase
  ,noFlowEverySecondCase
  ,noFlowShiftedWindowCase
  ,visibleRebaseCase
  ,mergedSmallTradeCase
  ,rebaseThenLossCase]

theorem surface_id :
    surfaceId surface = "portfolio-returns-estimator" := by
  rfl

theorem manifest_spec :
    surfaceManifest surface =
      "{:surface \"portfolio-returns-estimator\" :module \"Hyperopen.Formal.PortfolioReturnsEstimator\" :status \"modeled\"}\n" := by
  rfl

theorem catastrophic_loss_is_clamped :
    returnsHistoryRowsFromSummary catastrophicLossSummary =
      [{ timeMs := 1, percent := ratioZero }
      ,{ timeMs := 2, percent := mkRatio (-999999) 10000 }] := by
  native_decide

theorem positive_rebase_is_neutralized :
    returnsHistoryRowsFromSummary positiveRebaseSummary =
      [{ timeMs := 1, percent := ratioZero }
      ,{ timeMs := 2, percent := ratioZero }
      ,{ timeMs := 3, percent := mkRatio 300000000 60001000 }] := by
  native_decide

theorem repeated_positive_rebases_stay_neutral_until_real_trade :
    returnsHistoryRowsFromSummary repeatedRebaseSummary =
      [{ timeMs := 1, percent := ratioZero }
      ,{ timeMs := 2, percent := ratioZero }
      ,{ timeMs := 3, percent := ratioZero }
      ,{ timeMs := 4, percent := mkRatio 10 1 }] := by
  native_decide

theorem no_flow_every_second_sampling_is_exact :
    estimatorFinalPercent noFlowEverySecondCase = latentWindowFinalPercent noFlowEverySecondCase := by
  native_decide

theorem shifted_window_no_flow_sampling_is_exact :
    estimatorFinalPercent noFlowShiftedWindowCase = latentWindowFinalPercent noFlowShiftedWindowCase := by
  native_decide

theorem merged_positive_rebase_does_not_false_wipeout :
    falseWipeout mergedSmallTradeCase = false := by
  native_decide

theorem visible_rebase_followed_by_loss_remains_negative :
    estimatorFinalPercent rebaseThenLossCase = mkRatio (-10) 1 := by
  native_decide

def kv (key : String) (value : Clj) : Clj × Clj :=
  (.keyword key, value)

def mapClj (entries : List (Clj × Clj)) : Clj :=
  .arrayMap entries

def vecClj (values : List Clj) : Clj :=
  .vector values

def ratioToClj (value : Ratio) : Clj :=
  mapClj
    [kv "num" (.int value.numerator)
    ,kv "den" (.nat value.denominator)]

def historyRowToClj (row : Nat × Int) : Clj :=
  vecClj [.nat row.fst, .int row.snd]

def summaryToClj (summary : Summary) : Clj :=
  mapClj
    [kv "accountValueHistory" (vecClj (summary.accountValueHistory.map historyRowToClj))
    ,kv "pnlHistory" (vecClj (summary.pnlHistory.map historyRowToClj))]

def cumulativeRowToClj (row : CumulativeRow) : Clj :=
  mapClj
    [kv "time-ms" (.nat row.timeMs)
    ,kv "percent" (ratioToClj row.percent)]

def intervalReturnRowToClj (row : IntervalReturnRow) : Clj :=
  mapClj
    [kv "time-ms" (.nat row.timeMs)
    ,kv "return" (ratioToClj row.returnValue)]

def dailyReturnRowToClj (row : DailyReturnRow) : Clj :=
  mapClj
    [kv "day" (.str row.day)
    ,kv "time-ms" (.nat row.timeMs)
    ,kv "return" (ratioToClj row.returnValue)]

def seriesVectorToClj (id : String) (summary : Summary) : Clj :=
  mapClj
    [kv "id" (.keyword id)
    ,kv "summary" (summaryToClj summary)
    ,kv "expected" (vecClj ((returnsHistoryRowsFromSummary summary).map cumulativeRowToClj))]

def cumulativeRowsInputToClj (rows : List CumulativeRow) : Clj :=
  vecClj (rows.map cumulativeRowToClj)

def intervalVectorToClj (id : String) (rows : List CumulativeRow) : Clj :=
  mapClj
    [kv "id" (.keyword id)
    ,kv "rows" (cumulativeRowsInputToClj rows)
    ,kv "expected" (vecClj ((cumulativePercentRowsToIntervalReturns rows).map intervalReturnRowToClj))]

def dailyVectorToClj (id : String) (rows : List CumulativeRow) : Clj :=
  mapClj
    [kv "id" (.keyword id)
    ,kv "rows" (cumulativeRowsInputToClj rows)
    ,kv "expected" (vecClj ((dailyCompoundedReturns rows).map dailyReturnRowToClj))]

def latentStepToClj (step : LatentStep) : Clj :=
  mapClj
    [kv "dt-ms" (.nat step.dtMs)
    ,kv "pnl-delta" (.int step.pnlDelta)
    ,kv "cash-flow" (.int step.cashFlow)]

def observationToClj (observation : ObservationPolicy) : Clj :=
  mapClj
    [kv "sample-indexes" (vecClj (observation.sampleIndexes.map Clj.nat))
    ,kv "pnl-window-mode"
      (.keyword (match observation.pnlWindowMode with
                 | .cumulative => "cumulative"
                 | .rebasedAtFirstSample => "rebased-at-first-sample"))]

def simulatorExpectedToClj (simCase : SimulatorCase) : Clj :=
  let estimatorRows := estimatorRowsFromCase simCase
  mapClj
    [kv "latent-window-final-percent" (ratioToClj (latentWindowFinalPercent simCase))
    ,kv "estimator-rows" (vecClj (estimatorRows.map cumulativeRowToClj))
    ,kv "estimator-final-percent" (ratioToClj (finalPercentFromRows estimatorRows))
    ,kv "exact?" (.bool simCase.exact)
    ,kv "first-row-zero?"
      (.bool (match estimatorRows with
              | [] => true
              | row :: _ => row.percent = ratioZero))
    ,kv "avoid-false-wipeout?" (.bool simCase.avoidFalseWipeout)
    ,kv "max-final-error-bps" (.nat simCase.maxFinalErrorBps)]

def simulatorCaseToClj (simCase : SimulatorCase) : Clj :=
  mapClj
    [kv "id" (.keyword simCase.id)
    ,kv "latent"
      (mapClj
        [kv "initial-account-value" (.int simCase.initialAccountValue)
        ,kv "steps" (vecClj (simCase.steps.map latentStepToClj))])
    ,kv "observation" (observationToClj simCase.observation)
    ,kv "observed-summary" (summaryToClj (observedSummaryFromCase simCase))
    ,kv "expected" (simulatorExpectedToClj simCase)]

def returnsSeriesVectors : Clj :=
  vecClj
    [seriesVectorToClj "shared-timestamps" sharedTimestampsSummary
    ,seriesVectorToClj "invalid-dietz-denominator" invalidDietzSummary
    ,seriesVectorToClj "skip-leading-nonpositive-anchor" skipLeadingAnchorSummary
    ,seriesVectorToClj "no-positive-anchor" noPositiveAnchorSummary
    ,seriesVectorToClj "catastrophic-loss-clamp" catastrophicLossSummary
    ,seriesVectorToClj "positive-rebase-neutralized" positiveRebaseSummary
    ,seriesVectorToClj "duplicate-timestamp-last-write-wins" duplicateTimestampSummary
    ,seriesVectorToClj "repeated-positive-rebases" repeatedRebaseSummary]

def intervalReturnVectors : Clj :=
  vecClj
    [intervalVectorToClj "simple-compounding" simpleCompoundingRows
    ,intervalVectorToClj "negative-recovery" negativeIntervalRows
    ,intervalVectorToClj "nonpositive-previous-factor-guards" nonPositiveFactorRows]

def dailyCompoundedVectors : Clj :=
  vecClj
    [dailyVectorToClj "single-day-compounding" simpleCompoundingRows
    ,dailyVectorToClj "multi-day-compounding" multiDayRows]

def returnsSimulatorVectors : Clj :=
  vecClj (simulatorCases.map simulatorCaseToClj)

def generatedSource : String :=
  renderNamespace "hyperopen.formal.portfolio-returns-estimator-vectors"
    [("returns-series-vectors", returnsSeriesVectors)
    ,("interval-return-vectors", intervalReturnVectors)
    ,("daily-compounded-vectors", dailyCompoundedVectors)
    ,("returns-simulator-vectors", returnsSimulatorVectors)]

def verify : IO Unit := do
  writeGeneratedSource surface generatedSource

def sync : IO Unit := do
  writeGeneratedSource surface generatedSource

end Hyperopen.Formal.PortfolioReturnsEstimator

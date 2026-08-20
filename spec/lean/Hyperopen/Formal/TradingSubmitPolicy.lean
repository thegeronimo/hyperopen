import Hyperopen.Formal.Common

namespace Hyperopen.Formal.TradingSubmitPolicy

open Hyperopen.Formal

def surface : Surface := .tradingSubmitPolicy

inductive MarginMode where
  | cross
  | isolated
  deriving Repr, DecidableEq, Inhabited

inductive MarketMarginMode where
  | normal
  | noCross
  | strictIsolated
  deriving Repr, DecidableEq, Inhabited

inductive SubmitMode where
  | view
  | submit
  deriving Repr, DecidableEq, Inhabited

inductive Reason where
  | submitting
  | spectateModeReadOnly
  | spotReadOnly
  | marketPriceMissing
  | validationErrors
  | requestUnavailable
  | agentNotReady
  deriving Repr, DecidableEq, Inhabited

structure MarketFlags where
  marginMode : Option MarketMarginMode := none
  onlyIsolated : Bool := false
  deriving Repr, DecidableEq, Inhabited

def crossMarginAllowed (market : MarketFlags) : Bool :=
  !(market.onlyIsolated ||
    match market.marginMode with
    | some .noCross => true
    | some .strictIsolated => true
    | _ => false)

def effectiveMarginMode (market : MarketFlags) (mode : MarginMode) : MarginMode :=
  if mode = .cross && !crossMarginAllowed market then .isolated else mode

-- `_spot` is intentionally ignored: spot order submission is supported, so the
-- former `spot -> spotReadOnly` gate has been removed from both arms. The
-- parameter is retained so the policy input shape (and the CLJS mirror in
-- hyperopen.trading.submit-policy) stays unchanged. `spotReadOnly` remains in
-- `Reason` for back-compat but is no longer produced.
def submitReason
    (mode : SubmitMode)
    (submitting spectate _spot marketPriceMissing hasErrors requestAvailable agentReady : Bool) :
    Option Reason :=
  match mode with
  | .submit =>
      if spectate then some .spectateModeReadOnly
      else if marketPriceMissing then some .marketPriceMissing
      else if hasErrors then some .validationErrors
      else if !requestAvailable then some .requestUnavailable
      else if !agentReady then some .agentNotReady
      else none
  | .view =>
      if submitting then some .submitting
      else if spectate then some .spectateModeReadOnly
      else if marketPriceMissing then some .marketPriceMissing
      else if hasErrors then some .validationErrors
      else none

theorem surface_id :
    surfaceId surface = "trading-submit-policy" := by
  rfl

theorem manifest_spec :
    surfaceManifest surface =
      "{:surface \"trading-submit-policy\" :module \"Hyperopen.Formal.TradingSubmitPolicy\" :status \"modeled\"}\n" := by
  rfl

theorem cross_disallowed_forces_isolated :
    effectiveMarginMode { marginMode := some .noCross, onlyIsolated := false } .cross = .isolated := by
  native_decide

theorem only_isolated_forces_isolated :
    effectiveMarginMode { marginMode := some .normal, onlyIsolated := true } .cross = .isolated := by
  native_decide

-- Spot no longer gates submission, so `reason = none` no longer implies
-- `spot = false`. The theorem still proves the remaining read-only reasons are
-- sound: a ready submit implies no spectate / price-missing / validation block.
-- `spot` is quantified and threaded through `submitReason` to prove the result
-- holds for either spot value.
theorem submit_ready_reason_none_sound :
    ∀ spectate spot marketPriceMissing hasErrors,
      submitReason .submit false spectate spot marketPriceMissing hasErrors true true = none →
        spectate = false ∧
          marketPriceMissing = false ∧
          hasErrors = false := by
  native_decide

theorem view_submitting_has_precedence
    (spectate spot marketPriceMissing hasErrors : Bool) :
    submitReason .view true spectate spot marketPriceMissing hasErrors false false =
      some .submitting := by
  simp [submitReason]

def kv (key : String) (value : Clj) : Clj × Clj :=
  (.keyword key, value)

def mapClj (entries : List (Clj × Clj)) : Clj :=
  .arrayMap entries

def keywordVec (values : List String) : Clj :=
  .vector (values.map Clj.keyword)

def stringVec (values : List String) : Clj :=
  .vector (values.map Clj.str)

def level (px : String) : Clj :=
  mapClj [kv "px" (.str px)]

def orderbook (bids asks : List String) : Clj :=
  mapClj
    [kv "bids" (.vector (bids.map level))
    ,kv "asks" (.vector (asks.map level))]

def market
    (coin : Option String)
    (marketType : Option String)
    (mark : Option String)
    (maxLeverage : Option Nat)
    (szDecimals : Nat)
    (assetId : Option Nat)
    (dex : Option String)
    (marginModeText : Option String)
    (onlyIsolated : Bool) :
    Clj :=
  mapClj <|
    ([] : List (Clj × Clj)) ++
      (match coin with
       | some value => [kv "coin" (.str value)]
       | none => []) ++
      (match marketType with
       | some value => [kv "market-type" (.keyword value)]
       | none => []) ++
      (match mark with
       | some value => [kv "mark" (.str value)]
       | none => []) ++
      (match maxLeverage with
       | some value => [kv "maxLeverage" (.nat value)]
       | none => []) ++
      [kv "szDecimals" (.nat szDecimals)] ++
      (match assetId with
       | some value => [kv "asset-id" (.nat value)]
       | none => []) ++
      (match dex with
       | some value => [kv "dex" (.str value)]
       | none => []) ++
      (match marginModeText with
       | some value => [kv "marginMode" (.str value)]
       | none => []) ++
      (if onlyIsolated then [kv "onlyIsolated" (.bool true)] else [])

def context
    (activeAsset : String)
    (assetIdx : Option Nat)
    (marketClj : Clj)
    (orderbookClj : Clj) :
    Clj :=
  mapClj <|
    [kv "active-asset" (.str activeAsset)] ++
      (match assetIdx with
       | some value => [kv "asset-idx" (.nat value)]
       | none => []) ++
      [kv "market" marketClj
      ,kv "orderbook" orderbookClj]

def identity (spot : Bool) : Clj :=
  mapClj [kv "spot?" (.bool spot)]

def form
    (orderType : String)
    (side : String)
    (size : String)
    (price : String)
    (triggerPx : Option String)
    (slippage : Option String)
    (tp : Option Clj)
    (sl : Option Clj)
    (twap : Option Clj)
    (scale : Option Clj) :
    Clj :=
  mapClj <|
    [kv "type" (.keyword orderType)
    ,kv "side" (.keyword side)
    ,kv "size" (.str size)
    ,kv "price" (.str price)] ++
      (match triggerPx with
       | some value => [kv "trigger-px" (.str value)]
       | none => []) ++
      (match slippage with
       | some value => [kv "slippage" (.str value)]
       | none => []) ++
      (match tp with
       | some value => [kv "tp" value]
       | none => []) ++
      (match sl with
       | some value => [kv "sl" value]
       | none => []) ++
      (match twap with
       | some value => [kv "twap" value]
       | none => []) ++
      (match scale with
       | some value => [kv "scale" value]
       | none => [])

def tpSlLeg (enabled : Bool) (trigger : Option String) : Clj :=
  mapClj <|
    (if enabled then [kv "enabled?" (.bool true)] else []) ++
      (match trigger with
       | some value => [kv "trigger" (.str value)]
       | none => [])

def twapClj (hours minutes : Nat) (randomize : Bool) : Clj :=
  mapClj
    [kv "hours" (.nat hours)
    ,kv "minutes" (.nat minutes)
    ,kv "randomize" (.bool randomize)]

-- A TWAP form carrying the ticket's optional Advanced Settings: a trigger price that arms
-- the order, and the max/min price at which the venue terminates it.
def twapAdvancedClj (hours minutes : Nat) (randomize : Bool) (triggerPx stopPx : String) : Clj :=
  mapClj
    [kv "hours" (.nat hours)
    ,kv "minutes" (.nat minutes)
    ,kv "randomize" (.bool randomize)
    ,kv "trigger-px" (.str triggerPx)
    ,kv "stop-px" (.str stopPx)]

def scaleClj (start finish : String) (count : Nat) (skew : String) : Clj :=
  mapClj
    [kv "start" (.str start)
    ,kv "end" (.str finish)
    ,kv "count" (.nat count)
    ,kv "skew" (.str skew)]

def options
    (mode : String)
    (submitting : Option Bool)
    (agentReady : Option Bool) :
    Clj :=
  mapClj <|
    [kv "mode" (.keyword mode)] ++
      (match submitting with
       | some value => [kv "submitting?" (.bool value)]
       | none => []) ++
      (match agentReady with
       | some value => [kv "agent-ready?" (.bool value)]
       | none => [])

def prepareExpected
    (orderType : String)
    (side : String)
    (size : String)
    (price : String)
    (marketPriceMissing : Bool) :
    Clj :=
  mapClj
    [kv "type" (.keyword orderType)
    ,kv "side" (.keyword side)
    ,kv "size" (.str size)
    ,kv "price" (.str price)
    ,kv "market-price-missing?" (.bool marketPriceMissing)]

def validationExpected (codes : List String) (requiredFields : List String) : Clj :=
  mapClj
    [kv "error-codes" (keywordVec codes)
    ,kv "required-fields" (stringVec requiredFields)]

def submitExpected
    (orderType : String)
    (side : String)
    (size : String)
    (price : String)
    (marketPriceMissing : Bool)
    (codes : List String)
    (requiredFields : List String)
    (reason : Option String)
    (disabled : Bool)
    (errorMessage : Option String)
    (requestPresent : Bool) :
    Clj :=
  mapClj <|
    [kv "type" (.keyword orderType)
    ,kv "side" (.keyword side)
    ,kv "size" (.str size)
    ,kv "price" (.str price)
    ,kv "market-price-missing?" (.bool marketPriceMissing)
    ,kv "error-codes" (keywordVec codes)
    ,kv "required-fields" (stringVec requiredFields)] ++
      (match reason with
       | some value => [kv "reason" (.keyword value)]
       | none => [kv "reason" .nil]) ++
      [kv "disabled?" (.bool disabled)] ++
      (match errorMessage with
       | some value => [kv "error-message" (.str value)]
       | none => [kv "error-message" .nil]) ++
      [kv "request-present?" (.bool requestPresent)]

def basePerpMarket : Clj :=
  market (some "BTC") (some "perp") (some "100") (some 40) 4 (some 0) none none false

def basePerpContext : Clj :=
  context "BTC" (some 0) basePerpMarket (orderbook ["99"] ["101"])

def missingAssetIdxContext : Clj :=
  context "BTC" none basePerpMarket (orderbook ["99"] ["101"])

def spotContext : Clj :=
  context "ETH/USDC"
    (some 12)
    (market (some "ETH/USDC") (some "spot") (some "100") none 4 none none none false)
    (orderbook ["99"] ["101"])

def monContext : Clj :=
  context "MON"
    (some 215)
    (market (some "MON") (some "perp") (some "0.020397") (some 20) 0 (some 215) none none false)
    (orderbook ["0.020397"] ["0.02045"])

def missingDataContext : Clj :=
  context "BTC" (some 0) (mapClj []) (orderbook [] [])

def effectiveMarginModeVectors : Clj :=
  .vector
    [mapClj
       [kv "id" (.keyword "cross-stays-cross")
       ,kv "market" (market (some "BTC") (some "perp") none none 4 none none (some "normal") false)
       ,kv "input" (.keyword "cross")
       ,kv "expected" (.keyword "cross")]
    ,mapClj
       [kv "id" (.keyword "no-cross-forces-isolated")
       ,kv "market" (market (some "xyz:NATGAS") (some "perp") none none 4 none none (some "noCross") false)
       ,kv "input" (.keyword "cross")
       ,kv "expected" (.keyword "isolated")]
    ,mapClj
       [kv "id" (.keyword "strict-isolated-forces-isolated")
       ,kv "market" (market (some "xyz:GOLD") (some "perp") none none 4 none none (some "strictIsolated") false)
       ,kv "input" (.keyword "cross")
       ,kv "expected" (.keyword "isolated")]
    ,mapClj
       [kv "id" (.keyword "only-isolated-forces-isolated")
       ,kv "market" (market (some "xyz:GOLD") (some "perp") none none 4 none none none true)
       ,kv "input" (.keyword "cross")
       ,kv "expected" (.keyword "isolated")]]

def prepareOrderFormVectors : Clj :=
  .vector
    [mapClj
       [kv "id" (.keyword "market-price-from-orderbook")
       ,kv "context" monContext
       ,kv "form" (form "market" "sell" "98" "" none (some "0.5") none none none none)
       ,kv "expected" (prepareExpected "market" "sell" "98" "0.020295" false)]
    ,mapClj
       [kv "id" (.keyword "market-price-missing")
       ,kv "context" missingDataContext
       ,kv "form" (form "market" "buy" "1" "" none none none none none none)
       ,kv "expected" (prepareExpected "market" "buy" "1" "" true)]
    ,mapClj
       [kv "id" (.keyword "limit-like-fallback-price")
       ,kv "context" basePerpContext
       ,kv "form" (form "limit" "buy" "1" "" none none none none none none)
       ,kv "expected" (prepareExpected "limit" "buy" "1" "100" false)]
    ,mapClj
       [kv "id" (.keyword "limit-like-fallback-missing")
       ,kv "context" missingDataContext
       ,kv "form" (form "limit" "buy" "1" "" none none none none none none)
       ,kv "expected" (prepareExpected "limit" "buy" "1" "" false)]]

def validationVectors : Clj :=
  .vector
    [mapClj
       [kv "id" (.keyword "market-requires-size")
       ,kv "context" basePerpContext
       ,kv "form" (form "market" "buy" "" "" none none none none none none)
       ,kv "expected" (validationExpected ["order/size-invalid"] ["Size"])]
    ,mapClj
       [kv "id" (.keyword "limit-requires-price")
       ,kv "context" basePerpContext
       ,kv "form" (form "limit" "buy" "1" "" none none none none none none)
       ,kv "expected" (validationExpected ["order/price-required"] ["Price"])]
    ,mapClj
       [kv "id" (.keyword "stop-market-requires-trigger")
       ,kv "context" basePerpContext
       ,kv "form" (form "stop-market" "buy" "1" "" none none none none none none)
       ,kv "expected" (validationExpected ["order/trigger-required"] ["Trigger Price"])]
    ,mapClj
       [kv "id" (.keyword "take-limit-requires-price-and-trigger")
       ,kv "context" basePerpContext
       ,kv "form" (form "take-limit" "sell" "1" "" none none none none none none)
       ,kv "expected" (validationExpected ["order/price-required", "order/trigger-required"]
                                       ["Price", "Trigger Price"])]
    ,mapClj
       [kv "id" (.keyword "scale-validates-inputs-skew-and-endpoints")
       ,kv "context" basePerpContext
       ,kv "form" (form "scale" "buy" "1" "" none none none none none
                        (some (scaleClj "8" "7" 1 "0")))
       ,kv "expected" (validationExpected ["scale/inputs-invalid",
                                           "scale/skew-invalid",
                                           "scale/endpoint-notional-too-small"]
                                       ["Start Price", "End Price", "Total Orders"])]
    ,mapClj
       [kv "id" (.keyword "twap-runtime-invalid")
       ,kv "context" basePerpContext
       ,kv "form" (form "twap" "buy" "1" "" none none none none (some (twapClj 0 4 false)) none)
       ,kv "expected" (validationExpected ["twap/runtime-invalid"] [])]
    ,mapClj
       -- Since the 2026-08-01 venue upgrade the binding size constraint on a TWAP is its
       -- TOTAL notional ($100), not its per-clip notional -- the venue keeps clips above
       -- their own $10 floor by spacing them further apart. 0.5 at a mark of 100 is $50.
       [kv "id" (.keyword "twap-order-notional-too-small")
       ,kv "context" basePerpContext
       ,kv "form" (form "twap" "buy" "0.5" "" none none none none (some (twapClj 0 30 false)) none)
       ,kv "expected" (validationExpected ["twap/order-notional-too-small"] ["Size"])]
    ,mapClj
       -- The same ticket at $100 total clears the floor: 10 clips of $10.
       [kv "id" (.keyword "twap-order-at-notional-floor-is-valid")
       ,kv "context" basePerpContext
       ,kv "form" (form "twap" "buy" "1" "" none none none none (some (twapClj 0 30 false)) none)
       ,kv "expected" (validationExpected [] [])]
    ,mapClj
       -- Advanced settings are optional, but a typed-and-unusable price must explain
       -- itself rather than silently disabling submit.
       [kv "id" (.keyword "twap-advanced-prices-invalid")
       ,kv "context" basePerpContext
       ,kv "form" (form "twap" "buy" "1" "" none none none none
                        (some (twapAdvancedClj 0 30 false "0" "-1")) none)
       ,kv "expected" (validationExpected ["twap/trigger-price-invalid",
                                           "twap/stop-price-invalid"] [])]
    ,mapClj
       [kv "id" (.keyword "enabled-tpsl-requires-triggers")
       ,kv "context" basePerpContext
       ,kv "form" (form "limit" "buy" "1" "100" none none
                        (some (tpSlLeg true none))
                        (some (tpSlLeg true none))
                        none
                        none)
       ,kv "expected" (validationExpected ["tpsl/tp-trigger-required",
                                           "tpsl/sl-trigger-required"]
                                       ["TP Trigger", "SL Trigger"])]]

def submitPolicyVectors : Clj :=
  .vector
    [mapClj
       [kv "id" (.keyword "view-submitting")
       ,kv "context" basePerpContext
       ,kv "identity" (identity false)
       ,kv "spectate-mode-message" .nil
       ,kv "form" (form "limit" "buy" "1" "100" none none none none none none)
       ,kv "options" (options "view" (some true) none)
       ,kv "expected" (submitExpected "limit" "buy" "1" "100" false [] []
                                      (some "submitting") true none false)]
    ,mapClj
       [kv "id" (.keyword "submit-spectate-read-only")
       ,kv "context" basePerpContext
       ,kv "identity" (identity false)
       ,kv "spectate-mode-message" (.str "Spectate Mode is read-only. Stop Spectate Mode to place trades or move funds.")
       ,kv "form" (form "limit" "buy" "1" "100" none none none none none none)
       ,kv "options" (options "submit" none (some true))
       ,kv "expected" (submitExpected "limit" "buy" "1" "100" false [] []
                                      (some "spectate-mode-read-only") true
                                      (some "Spectate Mode is read-only. Stop Spectate Mode to place trades or move funds.")
                                      true)]
    ,mapClj
       [kv "id" (.keyword "submit-spot-submittable")
       ,kv "context" spotContext
       ,kv "identity" (identity true)
       ,kv "spectate-mode-message" .nil
       ,kv "form" (form "limit" "buy" "1" "100" none none none none none none)
       ,kv "options" (options "submit" none (some true))
       -- Spot is now submittable: a spot identity with a ready form yields no
       -- reason and is not disabled (the former :spot-read-only gate is gone).
       ,kv "expected" (submitExpected "limit" "buy" "1" "100" false [] []
                                      none false none true)]
    ,mapClj
       [kv "id" (.keyword "submit-market-price-missing")
       ,kv "context" missingDataContext
       ,kv "identity" (identity false)
       ,kv "spectate-mode-message" .nil
       ,kv "form" (form "market" "buy" "1" "" none none none none none none)
       ,kv "options" (options "submit" none (some true))
       ,kv "expected" (submitExpected "market" "buy" "1" "" true [] []
                                      (some "market-price-missing") true
                                      (some "Market price unavailable. Load order book first.")
                                      false)]
    ,mapClj
       [kv "id" (.keyword "submit-validation-errors")
       ,kv "context" basePerpContext
       ,kv "identity" (identity false)
       ,kv "spectate-mode-message" .nil
       ,kv "form" (form "limit" "buy" "" "" none none none none none none)
       ,kv "options" (options "submit" none (some true))
       ,kv "expected" (submitExpected "limit" "buy" "" "100" false
                                      ["order/size-invalid"] ["Size"]
                                      (some "validation-errors") true
                                      (some "Size must be greater than 0.")
                                      false)]
    ,mapClj
       [kv "id" (.keyword "submit-request-unavailable")
       ,kv "context" missingAssetIdxContext
       ,kv "identity" (identity false)
       ,kv "spectate-mode-message" .nil
       ,kv "form" (form "limit" "buy" "1" "100" none none none none none none)
       ,kv "options" (options "submit" none (some true))
       ,kv "expected" (submitExpected "limit" "buy" "1" "100" false [] []
                                      (some "request-unavailable") true
                                      (some "Select an asset and ensure market data is loaded.")
                                      false)]
    ,mapClj
       [kv "id" (.keyword "submit-agent-not-ready")
       ,kv "context" basePerpContext
       ,kv "identity" (identity false)
       ,kv "spectate-mode-message" .nil
       ,kv "form" (form "limit" "buy" "1" "100" none none none none none none)
       ,kv "options" (options "submit" none (some false))
       ,kv "expected" (submitExpected "limit" "buy" "1" "100" false [] []
                                      (some "agent-not-ready") true
                                      (some "Enable trading before submitting orders.")
                                      true)]
    ,mapClj
       [kv "id" (.keyword "submit-ready")
       ,kv "context" basePerpContext
       ,kv "identity" (identity false)
       ,kv "spectate-mode-message" .nil
       ,kv "form" (form "limit" "buy" "1" "100" none none none none none none)
       ,kv "options" (options "submit" none (some true))
       ,kv "expected" (submitExpected "limit" "buy" "1" "100" false [] []
                                      none false none true)]]

def generatedSource : String :=
  renderNamespace "hyperopen.formal.trading-submit-policy-vectors"
    [("effective-margin-mode-vectors", effectiveMarginModeVectors)
    ,("prepare-order-form-vectors", prepareOrderFormVectors)
    ,("validation-vectors", validationVectors)
    ,("submit-policy-vectors", submitPolicyVectors)]

def verify : IO Unit := do
  writeGeneratedSource surface generatedSource

def sync : IO Unit := do
  writeGeneratedSource surface generatedSource

end Hyperopen.Formal.TradingSubmitPolicy

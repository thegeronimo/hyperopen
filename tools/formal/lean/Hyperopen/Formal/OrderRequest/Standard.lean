import Hyperopen.Formal.OrderRequest.Common

namespace Hyperopen.Formal.OrderRequest.Standard

open Hyperopen.Formal

def surface : Surface := .orderRequestStandard

def maxMarketPriceSignificantFigures : Nat := 5
def maxPerpPriceDecimals : Nat := 6
def maxSpotPriceDecimals : Nat := 8

inductive OrderType where
  | limit
  | market
  | stopMarket
  | stopLimit
  | takeMarket
  | takeLimit
  deriving Repr, DecidableEq, Inhabited

inductive Side where
  | buy
  | sell
  deriving Repr, DecidableEq, Inhabited

inductive Tif where
  | gtc
  | ioc
  | alo
  deriving Repr, DecidableEq, Inhabited

inductive MarginMode where
  | cross
  | isolated
  deriving Repr, DecidableEq, Inhabited

inductive MarketType where
  | perp
  | spot
  deriving Repr, DecidableEq, Inhabited

inductive MarketMarginMode where
  | normal
  | noCross
  | strictIsolated
  deriving Repr, DecidableEq, Inhabited

inductive Contract where
  | submitReady
  | rawBuilder
  deriving Repr, DecidableEq, Inhabited

inductive TriggerFlavor where
  | tp
  | sl
  deriving Repr, DecidableEq, Inhabited

inductive NumericInput where
  | nat (value : Nat)
  | text (value : String)
  deriving Repr, DecidableEq, Inhabited

structure Market where
  marketType : Option MarketType := none
  coin : Option String := none
  szDecimals : Nat := 4
  dex : Option String := none
  assetId : Option Nat := none
  idx : Option Nat := none
  marginMode : Option MarketMarginMode := none
  onlyIsolated : Bool := false
  deriving Repr, DecidableEq, Inhabited

structure Context where
  activeAsset : String
  assetIdx : Option Nat := none
  market : Market := default
  deriving Repr, DecidableEq, Inhabited

structure TpslForm where
  enabled : Bool := false
  trigger : String := ""
  limitPrice : Option String := none
  isMarket : Bool := true
  deriving Repr, DecidableEq, Inhabited

structure Form where
  orderType : OrderType := .limit
  side : Side := .buy
  size : String := ""
  price : String := ""
  triggerPx : String := ""
  reduceOnly : Bool := false
  postOnly : Bool := false
  tif : Tif := .gtc
  tp : TpslForm := default
  sl : TpslForm := default
  uiLeverage : Option NumericInput := none
  marginMode : MarginMode := .cross
  deriving Repr, DecidableEq, Inhabited

inductive OrderTerms where
  | limit (tif : String)
  | trigger (isMarket : Bool) (triggerPx : String) (tpsl : String)
  deriving Repr, DecidableEq, Inhabited

structure WireOrder where
  asset : Nat
  isBuy : Bool
  price : String
  size : String
  reduceOnly : Option Bool := none
  terms : OrderTerms
  deriving Repr, DecidableEq, Inhabited

structure OrderAction where
  orders : List WireOrder
  grouping : String
  deriving Repr, DecidableEq, Inhabited

structure UpdateLeverageAction where
  asset : Nat
  isCross : Bool
  leverage : Nat
  deriving Repr, DecidableEq, Inhabited

structure OrderRequest where
  action : OrderAction
  assetIdx : Nat
  orders : List WireOrder
  preActions : List UpdateLeverageAction := []
  deriving Repr, DecidableEq, Inhabited

structure StandardVector where
  id : String
  contract : Contract
  context : Context
  form : Form
  expected : Option OrderRequest
  deriving Repr, DecidableEq, Inhabited

structure Decimal where
  wholeDigits : List Char
  fractionDigits : List Char
  deriving Repr, DecidableEq, Inhabited

def btcPerpContext : Context :=
  { activeAsset := "BTC"
    assetIdx := some 5
    market := { marketType := some .perp
                szDecimals := 4 } }

def monPerpContext : Context :=
  { activeAsset := "MON"
    assetIdx := some 215
    market := { marketType := some .perp
                szDecimals := 0 } }

def namedDexContext : Context :=
  { activeAsset := "hyna:GOLD"
    assetIdx := some 110005
    market := { marketType := some .perp
                szDecimals := 2
                dex := some "hyna"
                assetId := some 110005 } }

def namedDexMissingCanonicalAssetIdContext : Context :=
  { activeAsset := "hyna:GOLD"
    assetIdx := none
    market := { marketType := some .perp
                szDecimals := 2
                dex := some "hyna"
                idx := some 5 } }

def spotLikeContext : Context :=
  { activeAsset := "ETH/USDC"
    assetIdx := some 12
    market := { coin := some "ETH/USDC"
                szDecimals := 4 } }

def isolatedOnlyContext : Context :=
  { activeAsset := "BTC"
    assetIdx := some 5
    market := { marketType := some .perp
                szDecimals := 4
                marginMode := some .noCross
                onlyIsolated := true } }

def isWhitespace (c : Char) : Bool :=
  c = ' ' || c = '\n' || c = '\r' || c = '\t'

def trimChars (chars : List Char) : List Char :=
  ((chars.dropWhile isWhitespace).reverse.dropWhile isWhitespace).reverse

def trim (value : String) : String :=
  String.ofList (trimChars value.toList)

def lower (value : String) : String :=
  String.ofList (value.toList.map Char.toLower)

def containsSlash (value : String) : Bool :=
  value.toList.any (fun c => c = '/')

def isDigit (c : Char) : Bool :=
  '0' ≤ c && c ≤ '9'

def isZeroDigit (c : Char) : Bool :=
  c = '0'

def allChars (predicate : Char → Bool) : List Char → Bool
  | [] => true
  | c :: rest => predicate c && allChars predicate rest

def digitValue? (c : Char) : Option Nat :=
  if isDigit c then
    some (c.toNat - '0'.toNat)
  else
    none

def parseNatDigitsAux : Nat → List Char → Option Nat
  | acc, [] => some acc
  | acc, c :: rest =>
      match digitValue? c with
      | some digit => parseNatDigitsAux (acc * 10 + digit) rest
      | none => none

def parseNatDigits? (chars : List Char) : Option Nat :=
  parseNatDigitsAux 0 chars

def takeChars : Nat → List Char → List Char
  | 0, _ => []
  | _, [] => []
  | n + 1, c :: rest => c :: takeChars n rest

def trimLeadingZeroDigits (digits : List Char) : List Char :=
  match digits.dropWhile isZeroDigit with
  | [] => ['0']
  | normalized => normalized

def trimTrailingZeroDigits (digits : List Char) : List Char :=
  (digits.reverse.dropWhile isZeroDigit).reverse

def digitsPositive (digits : List Char) : Bool :=
  digits.any (fun c => c ≠ '0')

def decimalPositive (value : Decimal) : Bool :=
  digitsPositive value.wholeDigits || digitsPositive value.fractionDigits

def decimalIntegerValue (value : Decimal) : Bool :=
  value.fractionDigits.all isZeroDigit

def parseDecimal? (value : String) : Option Decimal :=
  let chars := (trim value).toList
  match chars with
  | [] => none
  | '.' :: rest =>
      if rest.isEmpty then
        none
      else if allChars isDigit rest then
        some { wholeDigits := ['0'], fractionDigits := rest }
      else
        none
  | _ =>
      let wholeDigits := chars.takeWhile isDigit
      if wholeDigits.isEmpty then
        none
      else
        let remaining := chars.drop wholeDigits.length
        match remaining with
        | [] => some { wholeDigits := wholeDigits, fractionDigits := [] }
        | '.' :: fractionDigits =>
            if allChars isDigit fractionDigits then
              some { wholeDigits := wholeDigits, fractionDigits := fractionDigits }
            else
              none
        | _ => none

def parsePositiveDecimal? (value : String) : Option Decimal := do
  let parsed ← parseDecimal? value
  if decimalPositive parsed then
    some parsed
  else
    none

def cleanDecimalString (value : Decimal) : String :=
  let wholeDigits := trimLeadingZeroDigits value.wholeDigits
  let fractionDigits := trimTrailingZeroDigits value.fractionDigits
  if fractionDigits.isEmpty then
    String.ofList wholeDigits
  else
    String.ofList (wholeDigits ++ ['.'] ++ fractionDigits)

def truncateToDecimals (value : Decimal) (decimals : Nat) : Decimal :=
  { value with fractionDigits := takeChars decimals value.fractionDigits }

structure SigState where
  started : Bool := false
  count : Nat := 0
  deriving Repr, DecidableEq, Inhabited

def truncateSignificantDigitsLoop (limit : Nat) :
    SigState → List Char → (List Char × SigState)
  | state, [] => ([], state)
  | state, digit :: rest =>
      let keepDigit :=
        if state.started then
          if state.count < limit then
            digit
          else
            '0'
        else if digit = '0' then
          '0'
        else if state.count < limit then
          digit
        else
          '0'
      let nextState :=
        if state.started then
          if state.count < limit then
            { started := true, count := state.count + 1 }
          else
            { started := true, count := state.count }
        else if digit = '0' then
          state
        else if state.count < limit then
          { started := true, count := 1 }
        else
          { started := true, count := state.count }
      let (restDigits, finalState) := truncateSignificantDigitsLoop limit nextState rest
      (keepDigit :: restDigits, finalState)

def truncateToSignificantFigures (value : Decimal) (limit : Nat) : Decimal :=
  let (wholeDigits, stateAfterWhole) :=
    truncateSignificantDigitsLoop limit default value.wholeDigits
  let (fractionDigits, _) :=
    truncateSignificantDigitsLoop limit stateAfterWhole value.fractionDigits
  { wholeDigits := wholeDigits, fractionDigits := fractionDigits }

def normalizedSizeText? (value : String) : Option String :=
  (parsePositiveDecimal? value).map cleanDecimalString

def spotMarket (context : Context) : Bool :=
  match context.market.marketType with
  | some .spot => true
  | some .perp => false
  | none =>
      match context.market.coin with
      | some coin => containsSlash coin
      | none => containsSlash context.activeAsset

def maxPriceDecimals (context : Context) : Nat :=
  let maxDecimals := if spotMarket context then maxSpotPriceDecimals else maxPerpPriceDecimals
  maxDecimals - context.market.szDecimals

def canonicalPriceTextFromDecimal? (context : Context) (value : Decimal) : Option String :=
  let decimalsTruncated := truncateToDecimals value (maxPriceDecimals context)
  let formatted :=
    if decimalIntegerValue value then
      cleanDecimalString decimalsTruncated
    else
      cleanDecimalString <|
        truncateToSignificantFigures decimalsTruncated maxMarketPriceSignificantFigures
  match parsePositiveDecimal? formatted with
  | some _ => some formatted
  | none => none

def canonicalPriceText? (context : Context) (value : String) : Option String := do
  let parsed ← parsePositiveDecimal? value
  canonicalPriceTextFromDecimal? context parsed

def sideIsBuy : Side → Bool
  | .buy => true
  | .sell => false

def oppositeSide : Side → Side
  | .buy => .sell
  | .sell => .buy

def tifToWire : Tif → String
  | .ioc => "Ioc"
  | .alo => "Alo"
  | .gtc => "Gtc"

def reduceOnlyFlag (value : Bool) : Option Bool :=
  if value then some true else none

def buildTpslLeg
    (context : Context)
    (assetIdx : Nat)
    (closeSide : Side)
    (baseSize : String)
    (flavor : TriggerFlavor)
    (cfg : TpslForm) : Option WireOrder := do
  let triggerDecimal ← parsePositiveDecimal? cfg.trigger
  let triggerText ← canonicalPriceTextFromDecimal? context triggerDecimal
  let chosenPriceDecimal :=
    match cfg.limitPrice with
    | some limitPrice =>
        match parsePositiveDecimal? limitPrice with
        | some limitDecimal => limitDecimal
        | none => triggerDecimal
    | none => triggerDecimal
  let orderPriceText ← canonicalPriceTextFromDecimal? context chosenPriceDecimal
  some
    { asset := assetIdx
      isBuy := sideIsBuy closeSide
      price := orderPriceText
      size := baseSize
      reduceOnly := some true
      terms := .trigger cfg.isMarket triggerText
                 (match flavor with | .tp => "tp" | .sl => "sl") }

def buildOptionalTpslLeg
    (context : Context)
    (assetIdx : Nat)
    (closeSide : Side)
    (baseSize : String)
    (flavor : TriggerFlavor)
    (cfg : TpslForm) : Option (Option WireOrder) :=
  if cfg.enabled then
    match buildTpslLeg context assetIdx closeSide baseSize flavor cfg with
    | some order => some (some order)
    | none => none
  else
    some none

def buildTpslOrders
    (context : Context)
    (assetIdx : Nat)
    (side : Side)
    (baseSize : String)
    (tp : TpslForm)
    (sl : TpslForm) : Option (List WireOrder) := do
  let closeSide := oppositeSide side
  let tpOrder? ← buildOptionalTpslLeg context assetIdx closeSide baseSize .tp tp
  let slOrder? ← buildOptionalTpslLeg context assetIdx closeSide baseSize .sl sl
  let orders :=
    ([] : List WireOrder) ++
      (match tpOrder? with | some order => [order] | none => []) ++
      (match slOrder? with | some order => [order] | none => [])
  some orders

def groupedWithTpsl (form : Form) : Bool :=
  form.tp.enabled || form.sl.enabled

def mainOrderShape?
    (orderType : OrderType)
    (assetIdx : Nat)
    (side : Side)
    (sizeText : String)
    (reduceOnly : Option Bool)
    (priceText : Option String)
    (triggerText : Option String)
    (postOnly : Bool)
    (tif : Tif) : Option WireOrder := do
  match orderType with
  | .limit =>
      let price ← priceText
      some { asset := assetIdx
             isBuy := sideIsBuy side
             price := price
             size := sizeText
             reduceOnly := reduceOnly
             terms := .limit (if postOnly then "Alo" else tifToWire tif) }
  | .market =>
      let price ← priceText
      some { asset := assetIdx
             isBuy := sideIsBuy side
             price := price
             size := sizeText
             reduceOnly := reduceOnly
             terms := .limit "Ioc" }
  | .stopMarket =>
      let trigger ← triggerText
      let price := (priceText.getD trigger)
      some { asset := assetIdx
             isBuy := sideIsBuy side
             price := price
             size := sizeText
             reduceOnly := reduceOnly
             terms := .trigger true trigger "sl" }
  | .stopLimit =>
      let price ← priceText
      let trigger ← triggerText
      some { asset := assetIdx
             isBuy := sideIsBuy side
             price := price
             size := sizeText
             reduceOnly := reduceOnly
             terms := .trigger false trigger "sl" }
  | .takeMarket =>
      let trigger ← triggerText
      let price := (priceText.getD trigger)
      some { asset := assetIdx
             isBuy := sideIsBuy side
             price := price
             size := sizeText
             reduceOnly := reduceOnly
             terms := .trigger true trigger "tp" }
  | .takeLimit =>
      let price ← priceText
      let trigger ← triggerText
      some { asset := assetIdx
             isBuy := sideIsBuy side
             price := price
             size := sizeText
             reduceOnly := reduceOnly
             terms := .trigger false trigger "tp" }

def buildStandardOrderRequest (context : Context) (form : Form) : Option OrderRequest := do
  let assetIdx ← context.assetIdx
  let sizeText ← normalizedSizeText? form.size
  let priceText := canonicalPriceText? context form.price
  let triggerText := canonicalPriceText? context form.triggerPx
  let mainOrder ←
    mainOrderShape? form.orderType assetIdx form.side sizeText (reduceOnlyFlag form.reduceOnly)
      priceText triggerText form.postOnly form.tif
  let tpslOrders ← buildTpslOrders context assetIdx form.side sizeText form.tp form.sl
  let orders := mainOrder :: tpslOrders
  let grouping := if groupedWithTpsl form then "normalTpsl" else "na"
  some { action := { orders := orders, grouping := grouping }
         assetIdx := assetIdx
         orders := orders }

def normalizeLeverage? : Option NumericInput → Option Nat
  | none => none
  | some (.nat value) =>
      if value = 0 then none else some value
  | some (.text value) =>
      match parsePositiveDecimal? value with
      | none => none
      | some decimal =>
          let wholeDigits := trimLeadingZeroDigits decimal.wholeDigits
          let wholeValue := (parseNatDigits? wholeDigits).getD 0
          let rounded :=
            match decimal.fractionDigits with
            | digit :: _ =>
                match digitValue? digit with
                | some n =>
                    if n ≥ 5 then wholeValue + 1 else wholeValue
                | none => wholeValue
            | [] => wholeValue
          some (max 1 rounded)

def crossMarginAllowed (context : Context) : Bool :=
  !(context.market.onlyIsolated ||
    match context.market.marginMode with
    | some .noCross => true
    | some .strictIsolated => true
    | _ => false)

def perpMarket (context : Context) : Bool :=
  match context.market.marketType with
  | some .spot => false
  | some .perp => true
  | none =>
      match context.market.coin with
      | some coin => !(containsSlash coin)
      | none => !(containsSlash context.activeAsset)

def buildUpdateLeverageAction (context : Context) (form : Form) : Option UpdateLeverageAction := do
  let assetIdx ← context.assetIdx
  let leverage ← normalizeLeverage? form.uiLeverage
  if perpMarket context then
    let effectiveMarginMode :=
      if crossMarginAllowed context then
        form.marginMode
      else
        .isolated
    some { asset := assetIdx
           isCross := effectiveMarginMode ≠ .isolated
           leverage := leverage }
  else
    none

def buildOrderRequest (context : Context) (form : Form) : Option OrderRequest :=
  match buildStandardOrderRequest context form with
  | none => none
  | some request =>
      match buildUpdateLeverageAction context form with
      | some preAction => some { request with preActions := [preAction] }
      | none => some request

def limitPostOnlyForm : Form :=
  { orderType := .limit
    side := .buy
    size := "1"
    price := "100"
    postOnly := true
    tif := .ioc }

def limitWithTpslForm : Form :=
  { orderType := .limit
    side := .buy
    size := "2"
    price := "100"
    tp := { enabled := true
            trigger := "110"
            isMarket := true }
    sl := { enabled := true
            trigger := "90"
            isMarket := true } }

def marketOrderForm : Form :=
  { orderType := .market
    side := .buy
    size := "1"
    price := "100" }

def stopMarketCanonicalizedForm : Form :=
  { orderType := .stopMarket
    side := .buy
    size := "1"
    price := ""
    triggerPx := "0.01969873" }

def stopLimitForm : Form :=
  { orderType := .stopLimit
    side := .sell
    size := "1.5"
    price := "97"
    triggerPx := "98" }

def takeMarketForm : Form :=
  { orderType := .takeMarket
    side := .sell
    size := "2"
    price := ""
    triggerPx := "105" }

def takeLimitCanonicalizedForm : Form :=
  { orderType := .takeLimit
    side := .buy
    size := "1"
    price := "0.01969873"
    triggerPx := "0.01969873" }

def crossLeveragePreActionForm : Form :=
  { orderType := .limit
    side := .buy
    size := "1"
    price := "100"
    uiLeverage := some (.nat 17)
    marginMode := .cross }

def crossDisallowedForm : Form :=
  { orderType := .limit
    side := .buy
    size := "1"
    price := "100"
    uiLeverage := some (.nat 11)
    marginMode := .cross }

def isolatedLeverageStringForm : Form :=
  { orderType := .limit
    side := .buy
    size := "1"
    price := "100"
    uiLeverage := some (.text "21")
    marginMode := .isolated }

def namedDexLimitForm : Form :=
  { orderType := .limit
    side := .buy
    size := "1"
    price := "100" }

def invalidEnabledTpslLegForm : Form :=
  { orderType := .limit
    side := .buy
    size := "1"
    price := "100"
    tp := { enabled := true
            trigger := "" } }

def limitMissingPriceForm : Form :=
  { orderType := .limit
    side := .buy
    size := "1"
    price := "" }

def stopMarketMissingTriggerForm : Form :=
  { orderType := .stopMarket
    side := .buy
    size := "1"
    price := ""
    triggerPx := "" }

def standardVectors : List StandardVector :=
  [ { id := "limit-post-only"
      contract := .submitReady
      context := btcPerpContext
      form := limitPostOnlyForm
      expected := buildOrderRequest btcPerpContext limitPostOnlyForm }
  , { id := "limit-with-tpsl"
      contract := .submitReady
      context := btcPerpContext
      form := limitWithTpslForm
      expected := buildOrderRequest btcPerpContext limitWithTpslForm }
  , { id := "market-order"
      contract := .submitReady
      context := btcPerpContext
      form := marketOrderForm
      expected := buildOrderRequest btcPerpContext marketOrderForm }
  , { id := "stop-market-canonicalized"
      contract := .submitReady
      context := monPerpContext
      form := stopMarketCanonicalizedForm
      expected := buildOrderRequest monPerpContext stopMarketCanonicalizedForm }
  , { id := "stop-limit"
      contract := .submitReady
      context := btcPerpContext
      form := stopLimitForm
      expected := buildOrderRequest btcPerpContext stopLimitForm }
  , { id := "take-market"
      contract := .submitReady
      context := btcPerpContext
      form := takeMarketForm
      expected := buildOrderRequest btcPerpContext takeMarketForm }
  , { id := "take-limit-canonicalized"
      contract := .submitReady
      context := monPerpContext
      form := takeLimitCanonicalizedForm
      expected := buildOrderRequest monPerpContext takeLimitCanonicalizedForm }
  , { id := "cross-leverage-pre-action"
      contract := .submitReady
      context := btcPerpContext
      form := crossLeveragePreActionForm
      expected := buildOrderRequest btcPerpContext crossLeveragePreActionForm }
  , { id := "cross-disallowed-forces-isolated"
      contract := .submitReady
      context := isolatedOnlyContext
      form := crossDisallowedForm
      expected := buildOrderRequest isolatedOnlyContext crossDisallowedForm }
  , { id := "isolated-leverage-string"
      contract := .submitReady
      context := btcPerpContext
      form := isolatedLeverageStringForm
      expected := buildOrderRequest btcPerpContext isolatedLeverageStringForm }
  , { id := "spot-like-omits-leverage-pre-action"
      contract := .submitReady
      context := spotLikeContext
      form := crossLeveragePreActionForm
      expected := buildOrderRequest spotLikeContext crossLeveragePreActionForm }
  , { id := "named-dex-uses-canonical-asset-id"
      contract := .submitReady
      context := namedDexContext
      form := namedDexLimitForm
      expected := buildOrderRequest namedDexContext namedDexLimitForm }
  , { id := "named-dex-missing-canonical-asset-id-fails-closed"
      contract := .rawBuilder
      context := namedDexMissingCanonicalAssetIdContext
      form := namedDexLimitForm
      expected := buildOrderRequest namedDexMissingCanonicalAssetIdContext namedDexLimitForm }
  , { id := "invalid-enabled-tpsl-leg-fails-closed"
      contract := .rawBuilder
      context := btcPerpContext
      form := invalidEnabledTpslLegForm
      expected := buildOrderRequest btcPerpContext invalidEnabledTpslLegForm }
  , { id := "limit-missing-price-fails-closed"
      contract := .rawBuilder
      context := btcPerpContext
      form := limitMissingPriceForm
      expected := buildOrderRequest btcPerpContext limitMissingPriceForm }
  , { id := "stop-market-missing-trigger-fails-closed"
      contract := .rawBuilder
      context := btcPerpContext
      form := stopMarketMissingTriggerForm
      expected := buildOrderRequest btcPerpContext stopMarketMissingTriggerForm } ]

def orderTypeKeyword : OrderType → String
  | .limit => "limit"
  | .market => "market"
  | .stopMarket => "stop-market"
  | .stopLimit => "stop-limit"
  | .takeMarket => "take-market"
  | .takeLimit => "take-limit"

def sideKeyword : Side → String
  | .buy => "buy"
  | .sell => "sell"

def tifKeyword : Tif → String
  | .gtc => "gtc"
  | .ioc => "ioc"
  | .alo => "alo"

def marginModeKeyword : MarginMode → String
  | .cross => "cross"
  | .isolated => "isolated"

def marketMarginModeText : MarketMarginMode → String
  | .normal => "normal"
  | .noCross => "noCross"
  | .strictIsolated => "strictIsolated"

def contractKeyword : Contract → String
  | .submitReady => "submit-ready"
  | .rawBuilder => "raw-builder"

def numericInputToClj : NumericInput → Clj
  | .nat value => .nat value
  | .text value => .str value

def optionalEntry (key : String) : Option Clj → List (Clj × Clj)
  | some value => [(.keyword key, value)]
  | none => []

def marketToClj (market : Market) : Clj :=
  .arrayMap <|
    ([] : List (Clj × Clj)) ++
      (match market.marketType with
       | some marketType => [(.keyword "market-type",
                              .keyword (match marketType with | .perp => "perp" | .spot => "spot"))]
       | none => []) ++
      optionalEntry "coin" (market.coin.map Clj.str) ++
      [(.keyword "szDecimals", .nat market.szDecimals)] ++
      optionalEntry "dex" (market.dex.map Clj.str) ++
      (match market.assetId with
       | some assetId => [(.keyword "asset-id", .nat assetId)]
       | none => []) ++
      (match market.idx with
       | some idx => [(.keyword "idx", .nat idx)]
       | none => []) ++
      (match market.marginMode with
       | some mode => [(.keyword "marginMode", .str (marketMarginModeText mode))]
       | none => []) ++
      (if market.onlyIsolated then [(.keyword "onlyIsolated", .bool true)] else [])

def contextToClj (context : Context) : Clj :=
  .arrayMap <|
    [(.keyword "active-asset", .str context.activeAsset)] ++
      (match context.assetIdx with
       | some assetIdx => [(.keyword "asset-idx", .nat assetIdx)]
       | none => []) ++
      [(.keyword "market", marketToClj context.market)]

def tpslFormToClj (form : TpslForm) : Clj :=
  .arrayMap <|
    (if form.enabled then [(.keyword "enabled?", .bool true)] else []) ++
      (if form.trigger.isEmpty then [] else [(.keyword "trigger", .str form.trigger)]) ++
      (match form.limitPrice with
       | some limitPrice => [(.keyword "limit", .str limitPrice)]
       | none => []) ++
      (if form.enabled then [(.keyword "is-market", .bool form.isMarket)] else [])

def formToClj (form : Form) : Clj :=
  .arrayMap <|
    [(.keyword "type", .keyword (orderTypeKeyword form.orderType))
    ,(.keyword "side", .keyword (sideKeyword form.side))
    ,(.keyword "size", .str form.size)] ++
      (if form.price.isEmpty then [] else [(.keyword "price", .str form.price)]) ++
      (if form.triggerPx.isEmpty then [] else [(.keyword "trigger-px", .str form.triggerPx)]) ++
      (if form.reduceOnly then [(.keyword "reduce-only", .bool true)] else []) ++
      (if form.postOnly then [(.keyword "post-only", .bool true)] else []) ++
      (if form.postOnly || form.tif ≠ .gtc then [(.keyword "tif", .keyword (tifKeyword form.tif))] else []) ++
      (if form.tp.enabled then [(.keyword "tp", tpslFormToClj form.tp)] else []) ++
      (if form.sl.enabled then [(.keyword "sl", tpslFormToClj form.sl)] else []) ++
      (match form.uiLeverage with
       | some leverage => [(.keyword "ui-leverage", numericInputToClj leverage)]
       | none => []) ++
      (if form.uiLeverage.isSome || form.marginMode ≠ .cross then
         [(.keyword "margin-mode", .keyword (marginModeKeyword form.marginMode))]
       else
         [])

def orderTermsToClj : OrderTerms → Clj
  | .limit tif =>
      .arrayMap [(.keyword "limit", .arrayMap [(.keyword "tif", .str tif)])]
  | .trigger isMarket triggerPx tpsl =>
      .arrayMap
        [(.keyword "trigger",
          .arrayMap
            [(.keyword "isMarket", .bool isMarket)
            ,(.keyword "triggerPx", .str triggerPx)
            ,(.keyword "tpsl", .str tpsl)])]

def wireOrderToClj (order : WireOrder) : Clj :=
  .arrayMap
    [(.keyword "a", .nat order.asset)
    ,(.keyword "b", .bool order.isBuy)
    ,(.keyword "p", .str order.price)
    ,(.keyword "s", .str order.size)
    ,(.keyword "r",
      match order.reduceOnly with
      | some flag => .bool flag
      | none => .nil)
    ,(.keyword "t", orderTermsToClj order.terms)]

def updateLeverageActionToClj (action : UpdateLeverageAction) : Clj :=
  .arrayMap
    [(.keyword "type", .str "updateLeverage")
    ,(.keyword "asset", .nat action.asset)
    ,(.keyword "isCross", .bool action.isCross)
    ,(.keyword "leverage", .nat action.leverage)]

def orderActionToClj (action : OrderAction) : Clj :=
  .arrayMap
    [(.keyword "type", .str "order")
    ,(.keyword "orders", .vector (action.orders.map wireOrderToClj))
    ,(.keyword "grouping", .str action.grouping)]

def requestToClj (request : OrderRequest) : Clj :=
  .arrayMap <|
    [(.keyword "action", orderActionToClj request.action)
    ,(.keyword "asset-idx", .nat request.assetIdx)
    ,(.keyword "orders", .vector (request.orders.map wireOrderToClj))] ++
      (if request.preActions.isEmpty then
         []
       else
         [(.keyword "pre-actions",
           .vector (request.preActions.map updateLeverageActionToClj))])

def expectedToClj : Option OrderRequest → Clj
  | some request => requestToClj request
  | none => .nil

def vectorEntryToClj (vector : StandardVector) : Clj :=
  .arrayMap
    [(.keyword "id", .keyword vector.id)
    ,(.keyword "contract", .keyword (contractKeyword vector.contract))
    ,(.keyword "context", contextToClj vector.context)
    ,(.keyword "form", formToClj vector.form)
    ,(.keyword "expected", expectedToClj vector.expected)]

theorem surface_id :
    surfaceId surface = "order-request-standard" := by
  rfl

theorem manifest_spec :
    surfaceManifest surface =
      "{:surface \"order-request-standard\" :module \"Hyperopen.Formal.OrderRequest.Standard\" :status \"modeled\"}\n" := by
  rfl

theorem named_dex_missing_canonical_asset_id_fails_closed :
    buildOrderRequest namedDexMissingCanonicalAssetIdContext namedDexLimitForm = none := by
  native_decide

theorem invalid_enabled_tpsl_leg_fails_closed :
    buildOrderRequest btcPerpContext invalidEnabledTpslLegForm = none := by
  native_decide

theorem post_only_limit_uses_alo_tif :
    buildOrderRequest btcPerpContext limitPostOnlyForm =
      some { action := { orders :=
                           [{ asset := 5
                              isBuy := true
                              price := "100"
                              size := "1"
                              reduceOnly := none
                              terms := .limit "Alo" }]
                         grouping := "na" }
             assetIdx := 5
             orders :=
               [{ asset := 5
                  isBuy := true
                  price := "100"
                  size := "1"
                  reduceOnly := none
                  terms := .limit "Alo" }]
             preActions := [] } := by
  native_decide

theorem cross_disallowed_forces_isolated_pre_action :
    buildUpdateLeverageAction isolatedOnlyContext crossDisallowedForm =
      some { asset := 5, isCross := false, leverage := 11 } := by
  native_decide

theorem stop_market_price_truncates_without_rounding :
    buildOrderRequest monPerpContext stopMarketCanonicalizedForm =
      some { action := { orders :=
                           [{ asset := 215
                              isBuy := true
                              price := "0.019698"
                              size := "1"
                              reduceOnly := none
                              terms := .trigger true "0.019698" "sl" }]
                         grouping := "na" }
             assetIdx := 215
             orders :=
               [{ asset := 215
                  isBuy := true
                  price := "0.019698"
                  size := "1"
                  reduceOnly := none
                  terms := .trigger true "0.019698" "sl" }]
             preActions := [] } := by
  native_decide

theorem tpsl_orders_attach_on_opposite_side_with_reduce_only :
    buildOrderRequest btcPerpContext limitWithTpslForm =
      some { action := { orders :=
                           [{ asset := 5
                              isBuy := true
                              price := "100"
                              size := "2"
                              reduceOnly := none
                              terms := .limit "Gtc" }
                           ,{ asset := 5
                              isBuy := false
                              price := "110"
                              size := "2"
                              reduceOnly := some true
                              terms := .trigger true "110" "tp" }
                           ,{ asset := 5
                              isBuy := false
                              price := "90"
                              size := "2"
                              reduceOnly := some true
                              terms := .trigger true "90" "sl" }]
                         grouping := "normalTpsl" }
             assetIdx := 5
             orders :=
               [{ asset := 5
                  isBuy := true
                  price := "100"
                  size := "2"
                  reduceOnly := none
                  terms := .limit "Gtc" }
               ,{ asset := 5
                  isBuy := false
                  price := "110"
                  size := "2"
                  reduceOnly := some true
                  terms := .trigger true "110" "tp" }
               ,{ asset := 5
                  isBuy := false
                  price := "90"
                  size := "2"
                  reduceOnly := some true
                  terms := .trigger true "90" "sl" }]
             preActions := [] } := by
  native_decide

def generatedSource : String :=
  renderNamespace "hyperopen.formal.order-request-standard-vectors"
    [("btc-perp-context", contextToClj btcPerpContext)
    ,("mon-perp-context", contextToClj monPerpContext)
    ,("named-dex-context", contextToClj namedDexContext)
    ,("named-dex-missing-asset-id-context", contextToClj namedDexMissingCanonicalAssetIdContext)
    ,("spot-like-context", contextToClj spotLikeContext)
    ,("isolated-only-context", contextToClj isolatedOnlyContext)
    ,("standard-order-request-vectors", .vector (standardVectors.map vectorEntryToClj))]

def verify : IO Unit := do
  writeGeneratedSource surface generatedSource

def sync : IO Unit := do
  writeGeneratedSource surface generatedSource

end Hyperopen.Formal.OrderRequest.Standard

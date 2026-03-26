import Hyperopen.Formal.Common

namespace Hyperopen.Formal.VaultTransfer

open Hyperopen.Formal

def surface : Surface := .vaultTransfer

def maxSafeMicros : Nat := 9007199254740991

def microsScale : Nat := 1000000

def vaultAddressText : String := "0x1234567890abcdef1234567890abcdef12345678"

def leaderAddressText : String := "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"

def otherAddressText : String := "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

inductive TransferMode where
  | deposit
  | withdraw
  deriving Repr, DecidableEq, Inhabited

inductive PreviewFailure where
  | invalidVaultAddress
  | depositsDisabled
  | invalidAmount
  deriving Repr, DecidableEq, Inhabited

structure VaultDetails where
  name : Option String := none
  leader : Option String := none
  allowDeposits : Bool := false
  deriving Repr, Inhabited

structure MergedRow where
  vaultAddress : String
  name : Option String := none
  leader : Option String := none
  deriving Repr, Inhabited

structure VaultState where
  walletAddress : Option String := none
  detailsByAddress : List (String × VaultDetails) := []
  mergedRows : List MergedRow := []
  deriving Repr, Inhabited

structure Modal where
  mode : TransferMode := .deposit
  vaultAddress : Option String := none
  amountInput : String := ""
  withdrawAll : Bool := false
  deriving Repr, Inhabited

structure TransferAction where
  vaultAddress : String
  isDeposit : Bool
  usd : Nat
  deriving Repr, DecidableEq, Inhabited

structure TransferRequest where
  vaultAddress : String
  action : TransferAction
  deriving Repr, DecidableEq, Inhabited

structure PreviewSuccess where
  mode : TransferMode
  vaultAddress : String
  request : TransferRequest
  deriving Repr, DecidableEq, Inhabited

abbrev PreviewResult := Except PreviewFailure PreviewSuccess

inductive DecimalInput where
  | whole (wholeDigits : List Char) (fractionDigits : List Char)
  | fractionalOnly (fractionDigits : List Char)
  deriving Repr, Inhabited

def failureMessage : PreviewFailure → String
  | .invalidVaultAddress => "Invalid vault address."
  | .depositsDisabled => "Deposits are disabled for this vault."
  | .invalidAmount => "Enter an amount greater than 0."

def modeKeyword : TransferMode → String
  | .deposit => "deposit"
  | .withdraw => "withdraw"

def isWhitespace (c : Char) : Bool :=
  c = ' ' || c = '\n' || c = '\r' || c = '\t'

def trimChars (chars : List Char) : List Char :=
  ((chars.dropWhile isWhitespace).reverse.dropWhile isWhitespace).reverse

def trim (value : String) : String :=
  String.ofList (trimChars value.toList)

def nonBlankText? (value : String) : Option String :=
  let text := trim value
  if text.isEmpty then none else some text

def lower (value : String) : String :=
  String.ofList (value.toList.map Char.toLower)

def allChars (predicate : Char → Bool) : List Char → Bool
  | [] => true
  | c :: rest => predicate c && allChars predicate rest

def isDigit (c : Char) : Bool :=
  '0' ≤ c && c ≤ '9'

def isLowerHexDigit (c : Char) : Bool :=
  isDigit c || ('a' ≤ c && c ≤ 'f')

def digitValue? (c : Char) : Option Nat :=
  if _h : isDigit c then
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

def parseCanonicalDecimal? (value : String) : Option DecimalInput :=
  match value.toList with
  | [] => none
  | '.' :: rest =>
      if rest.isEmpty then
        none
      else if allChars isDigit rest then
        some (.fractionalOnly rest)
      else
        none
  | chars =>
      let wholeDigits := chars.takeWhile isDigit
      if wholeDigits.isEmpty then
        none
      else
        let remaining := chars.drop wholeDigits.length
        match remaining with
        | [] => some (.whole wholeDigits [])
        | '.' :: fractionDigits =>
            if allChars isDigit fractionDigits then
              some (.whole wholeDigits fractionDigits)
            else
              none
        | _ => none

def fractionDigitsToMicros? (fractionDigits : List Char) : Option Nat :=
  let truncated := takeChars 6 fractionDigits
  let padded := truncated ++ List.replicate (6 - truncated.length) '0'
  parseNatDigits? padded

def ensureSafeMicros (micros : Nat) : Option Nat :=
  if micros ≤ maxSafeMicros then some micros else none

def parseUsdcMicros (value : String) : Option Nat := do
  match parseCanonicalDecimal? value with
  | none => none
  | some (.whole wholeDigits fractionDigits) =>
      let whole ← parseNatDigits? wholeDigits
      let fraction ← fractionDigitsToMicros? fractionDigits
      ensureSafeMicros (whole * microsScale + fraction)
  | some (.fractionalOnly fractionDigits) =>
      let fraction ← fractionDigitsToMicros? fractionDigits
      ensureSafeMicros fraction

def normalizeVaultAddress (value : String) : Option String :=
  let text := lower (trim value)
  match text.toList with
  | '0' :: 'x' :: rest =>
      if rest.length = 40 && allChars isLowerHexDigit rest then
        some text
      else
        none
  | _ => none

def lookupDetails : List (String × VaultDetails) → String → Option VaultDetails
  | [], _ => none
  | (key, details) :: rest, vaultAddress =>
      if key = vaultAddress then some details else lookupDetails rest vaultAddress

def mergedVaultRow : List MergedRow → String → Option MergedRow
  | [], _ => none
  | row :: rest, vaultAddress =>
      if normalizeVaultAddress row.vaultAddress = some vaultAddress then
        some row
      else
        mergedVaultRow rest vaultAddress

def vaultDetailsRecord (state : VaultState) (vaultAddress : String) : Option VaultDetails :=
  lookupDetails state.detailsByAddress vaultAddress

def vaultEntityName (state : VaultState) (vaultAddress : String) : Option String :=
  match vaultDetailsRecord state vaultAddress with
  | some details =>
      match details.name.bind nonBlankText? with
      | some name => some name
      | none => (mergedVaultRow state.mergedRows vaultAddress).bind (fun row => row.name.bind nonBlankText?)
  | none => (mergedVaultRow state.mergedRows vaultAddress).bind (fun row => row.name.bind nonBlankText?)

def vaultLeaderAddress (state : VaultState) (vaultAddress : String) : Option String :=
  match vaultDetailsRecord state vaultAddress with
  | some details =>
      match details.leader.bind normalizeVaultAddress with
      | some leader => some leader
      | none => (mergedVaultRow state.mergedRows vaultAddress).bind (fun row => row.leader.bind normalizeVaultAddress)
  | none => (mergedVaultRow state.mergedRows vaultAddress).bind (fun row => row.leader.bind normalizeVaultAddress)

def walletAddress? (state : VaultState) : Option String :=
  state.walletAddress.bind normalizeVaultAddress

def vaultTransferDepositAllowed (state : VaultState) (vaultAddress : String) : Bool :=
  match normalizeVaultAddress vaultAddress with
  | none => false
  | some vaultAddress' =>
      let allowDeposits :=
        match vaultDetailsRecord state vaultAddress' with
        | some details => details.allowDeposits
        | none => false
      let leader? :=
        match walletAddress? state, vaultLeaderAddress state vaultAddress' with
        | some walletAddress, some leaderAddress => walletAddress = leaderAddress
        | _, _ => false
      let liquidatorVault :=
        match vaultEntityName state vaultAddress' with
        | some name => lower (trim name) = "liquidator"
        | none => false
      !liquidatorVault && (leader? || allowDeposits)

def withdrawAllEnabled (modal : Modal) : Bool :=
  modal.mode = .withdraw && modal.withdrawAll

def selectedVaultAddress (modal : Modal) (routeVaultAddress : Option String) : Option String :=
  match modal.vaultAddress.bind normalizeVaultAddress with
  | some vaultAddress => some vaultAddress
  | none => routeVaultAddress.bind normalizeVaultAddress

def mkPreviewSuccess (mode : TransferMode) (vaultAddress : String) (amountMicros : Nat) : PreviewSuccess :=
  let action : TransferAction :=
    { vaultAddress := vaultAddress
      isDeposit := mode = .deposit
      usd := amountMicros }
  let request : TransferRequest :=
    { vaultAddress := vaultAddress
      action := action }
  { mode := mode
    vaultAddress := vaultAddress
    request := request }

def preview (state : VaultState) (routeVaultAddress : Option String) (modal : Modal) : PreviewResult :=
  match selectedVaultAddress modal routeVaultAddress with
  | none => .error .invalidVaultAddress
  | some vaultAddress =>
      if _hDeposit : modal.mode = .deposit then
        if vaultTransferDepositAllowed state vaultAddress then
          let withdrawAll := withdrawAllEnabled modal
          let amountMicros := if withdrawAll then some 0 else parseUsdcMicros modal.amountInput
          match amountMicros with
          | some amount =>
              if !withdrawAll && amount = 0 then
                .error .invalidAmount
              else
                .ok (mkPreviewSuccess modal.mode vaultAddress amount)
          | none => .error .invalidAmount
        else
          .error .depositsDisabled
      else
        let withdrawAll := withdrawAllEnabled modal
        let amountMicros := if withdrawAll then some 0 else parseUsdcMicros modal.amountInput
        match amountMicros with
        | some amount =>
            if !withdrawAll && amount = 0 then
              .error .invalidAmount
            else
              .ok (mkPreviewSuccess modal.mode vaultAddress amount)
        | none => .error .invalidAmount

theorem surface_id :
    surfaceId surface = "vault-transfer" := by
  rfl

theorem manifest_spec :
    surfaceManifest surface =
      "{:surface \"vault-transfer\" :module \"Hyperopen.Formal.VaultTransfer\" :status \"modeled\"}\n" := by
  rfl

theorem preview_invalid_address_precedence
    (state : VaultState)
    (routeVaultAddress : Option String)
    (modal : Modal)
    (hSelected : selectedVaultAddress modal routeVaultAddress = none) :
    preview state routeVaultAddress modal = .error .invalidVaultAddress := by
  unfold preview
  simp [hSelected]

theorem preview_deposit_disabled_precedence
    (state : VaultState)
    (routeVaultAddress : Option String)
    (modal : Modal)
    (vaultAddress : String)
    (hSelected : selectedVaultAddress modal routeVaultAddress = some vaultAddress)
    (hMode : modal.mode = .deposit)
    (hAllowed : vaultTransferDepositAllowed state vaultAddress = false) :
    preview state routeVaultAddress modal = .error .depositsDisabled := by
  unfold preview
  simp [hSelected, hMode, hAllowed]

theorem preview_success_request_consistent
    (mode : TransferMode)
    (vaultAddress : String)
    (amountMicros : Nat) :
    let success := mkPreviewSuccess mode vaultAddress amountMicros
    success.vaultAddress = success.request.vaultAddress ∧
      success.vaultAddress = success.request.action.vaultAddress ∧
      success.request.action.isDeposit = (mode = .deposit) := by
  simp [mkPreviewSuccess]

theorem preview_withdraw_all_bypasses_amount_parsing
    (state : VaultState)
    (routeVaultAddress : Option String)
    (vaultAddress : String)
    (modal : Modal)
    (hSelected : selectedVaultAddress { modal with mode := .withdraw, withdrawAll := true } routeVaultAddress = some vaultAddress) :
    preview state routeVaultAddress { modal with mode := .withdraw, withdrawAll := true } =
      .ok (mkPreviewSuccess .withdraw vaultAddress 0) := by
  unfold preview
  simp [hSelected, withdrawAllEnabled, mkPreviewSuccess]

def sharedTextClj (value : String) : Clj :=
  if value = vaultAddressText then .symbol "vault-address"
  else if value = leaderAddressText then .symbol "leader-address"
  else if value = otherAddressText then .symbol "other-address"
  else .str value

def optionStringClj (value : Option String) : Clj :=
  match value with
  | some text => sharedTextClj text
  | none => .nil

def detailsToClj (details : VaultDetails) : Clj :=
  .arrayMap
    ([]
      ++ (match details.name with
          | some name => [(.keyword "name", .str name)]
          | none => [])
      ++ (match details.leader with
          | some leader => [(.keyword "leader", sharedTextClj leader)]
          | none => [])
      ++ [(.keyword "allow-deposits?", .bool details.allowDeposits)])

def mergedRowToClj (row : MergedRow) : Clj :=
  .arrayMap
    ([ (.keyword "vault-address", sharedTextClj row.vaultAddress) ]
      ++ (match row.name with
          | some name => [(.keyword "name", .str name)]
          | none => [])
      ++ (match row.leader with
          | some leader => [(.keyword "leader", sharedTextClj leader)]
          | none => []))

def detailsByAddressToClj : List (String × VaultDetails) → Clj
  | [] => .arrayMap []
  | entries =>
      .arrayMap <| entries.map fun (vaultAddress, details) => (sharedTextClj vaultAddress, detailsToClj details)

def mergedRowsToClj (rows : List MergedRow) : Clj :=
  .vector (rows.map mergedRowToClj)

def vaultStateToClj (state : VaultState) : Clj :=
  .arrayMap
    [(.keyword "wallet",
      .arrayMap
        [(.keyword "address",
          optionStringClj state.walletAddress)])
    ,(.keyword "vaults",
      .arrayMap
        [(.keyword "details-by-address", detailsByAddressToClj state.detailsByAddress)
        ,(.keyword "merged-index-rows", mergedRowsToClj state.mergedRows)])]

def previewStateToClj (locale : String) (state : VaultState) : Clj :=
  .arrayMap
    [(.keyword "ui",
      .arrayMap [(.keyword "locale", .str locale)])
    ,(.keyword "wallet",
      .arrayMap [(.keyword "address", optionStringClj state.walletAddress)])
    ,(.keyword "vaults",
      .arrayMap
        [(.keyword "details-by-address", detailsByAddressToClj state.detailsByAddress)
        ,(.keyword "merged-index-rows", mergedRowsToClj state.mergedRows)])]

def modalToClj (modal : Modal) : Clj :=
  .arrayMap
    [(.keyword "open?", .bool true)
    ,(.keyword "mode", .keyword (modeKeyword modal.mode))
    ,(.keyword "vault-address", optionStringClj modal.vaultAddress)
    ,(.keyword "amount-input", .str modal.amountInput)
    ,(.keyword "withdraw-all?", .bool modal.withdrawAll)]

def previewFailureToClj (failure : PreviewFailure) : Clj :=
  .arrayMap
    [(.keyword "ok?", .bool false)
    ,(.keyword "display-message", .str (failureMessage failure))]

def transferActionToClj (action : TransferAction) : Clj :=
  .arrayMap
    [(.keyword "type", .str "vaultTransfer")
    ,(.keyword "vaultAddress", sharedTextClj action.vaultAddress)
    ,(.keyword "isDeposit", .bool action.isDeposit)
    ,(.keyword "usd", .nat action.usd)]

def transferRequestToClj (request : TransferRequest) : Clj :=
  .arrayMap
    [(.keyword "vault-address", sharedTextClj request.vaultAddress)
    ,(.keyword "action", transferActionToClj request.action)]

def previewSuccessToClj (success : PreviewSuccess) : Clj :=
  .arrayMap
    [(.keyword "ok?", .bool true)
    ,(.keyword "mode", .keyword (modeKeyword success.mode))
    ,(.keyword "vault-address", sharedTextClj success.vaultAddress)
    ,(.keyword "display-message", .nil)
    ,(.keyword "request", transferRequestToClj success.request)]

def previewResultToClj : PreviewResult → Clj
  | .ok success => previewSuccessToClj success
  | .error failure => previewFailureToClj failure

def parseVector (id : String) (input : String) : Clj :=
  .arrayMap
    [(.keyword "id", .keyword id)
    ,(.keyword "input", .str input)
    ,(.keyword "expected",
      match parseUsdcMicros input with
      | some micros => .nat micros
      | none => .nil)]

def depositVector (id : String) (state : VaultState) (vaultAddress : String) : Clj :=
  .arrayMap
    [(.keyword "id", .keyword id)
    ,(.keyword "state", vaultStateToClj state)
    ,(.keyword "vault-address", sharedTextClj vaultAddress)
    ,(.keyword "expected", .bool (vaultTransferDepositAllowed state vaultAddress))]

def previewVector (id : String) (routeVaultAddress : Option String) (state : VaultState) (modal : Modal) : Clj :=
  .arrayMap
    [(.keyword "id", .keyword id)
    ,(.keyword "route-vault-address", optionStringClj routeVaultAddress)
    ,(.keyword "state", previewStateToClj "en-US" state)
    ,(.keyword "modal", modalToClj modal)
    ,(.keyword "expected", previewResultToClj (preview state routeVaultAddress modal))]

def leaderVaultState (allowDeposits : Bool) : VaultState :=
  { walletAddress := some leaderAddressText
    detailsByAddress :=
      [(vaultAddressText,
        { name := some "Vault Detail"
          leader := some leaderAddressText
          allowDeposits := allowDeposits })]
    mergedRows :=
      [{ vaultAddress := vaultAddressText
         name := some "Vault Detail"
         leader := some leaderAddressText }] }

def otherWalletVaultState (allowDeposits : Bool) : VaultState :=
  { walletAddress := some otherAddressText
    detailsByAddress :=
      [(vaultAddressText,
        { name := some "Vault Detail"
          leader := some leaderAddressText
          allowDeposits := allowDeposits })]
    mergedRows :=
      [{ vaultAddress := vaultAddressText
         name := some "Vault Detail"
         leader := some leaderAddressText }] }

def mergedLeaderFallbackState : VaultState :=
  { walletAddress := some leaderAddressText
    detailsByAddress :=
      [(vaultAddressText,
        { name := some "Vault Detail"
          allowDeposits := false })]
    mergedRows :=
      [{ vaultAddress := vaultAddressText
         name := some "Vault Detail"
         leader := some leaderAddressText }] }

def liquidatorState : VaultState :=
  { walletAddress := some leaderAddressText
    detailsByAddress :=
      [(vaultAddressText,
        { name := some "Liquidator"
          leader := some leaderAddressText
          allowDeposits := true })]
    mergedRows :=
      [{ vaultAddress := vaultAddressText
         name := some "Liquidator"
         leader := some leaderAddressText }] }

def emptyVaultState : VaultState :=
  { walletAddress := some leaderAddressText
    detailsByAddress := []
    mergedRows := [] }

def parseVectors : Clj :=
  .vector
    [parseVector "integer" "12"
    ,parseVector "trailing-decimal-point" "12."
    ,parseVector "leading-decimal-point" ".5"
    ,parseVector "truncates-extra-fractional-digits" "1.2345679"
    ,parseVector "smallest-positive-unit" "0.000001"
    ,parseVector "zero" "0"
    ,parseVector "max-safe-boundary" "9007199254.740991"
    ,parseVector "overflow" "9007199254.740992"
    ,parseVector "rejects-negative-input" "-1"
    ,parseVector "rejects-garbage" "nope"]

def depositVectors : Clj :=
  .vector
    [depositVector "details-allow-deposits" (otherWalletVaultState true) vaultAddressText
    ,depositVector "leader-override-when-allow-deposits-false" (leaderVaultState false) vaultAddressText
    ,depositVector "merged-row-leader-fallback" mergedLeaderFallbackState vaultAddressText
    ,depositVector "liquidator-blocked" liquidatorState vaultAddressText
    ,depositVector "invalid-address-blocked" emptyVaultState "not-an-address"]

def previewVectors : Clj :=
  .vector
    [previewVector "route-fallback-normalizes-before-preview"
       (some " 0X1234567890ABCDEF1234567890ABCDEF12345678 ")
       (leaderVaultState true)
       { mode := .withdraw
         amountInput := "1"
         withdrawAll := false }
    ,previewVector "invalid-route-fallback-is-rejected"
       (some "not-a-vault")
       emptyVaultState
       { mode := .withdraw
         amountInput := "1"
         withdrawAll := false }
    ,previewVector "invalid-address-wins-before-other-checks"
       none
       { walletAddress := some otherAddressText
         detailsByAddress :=
           [(vaultAddressText,
             { name := some "Liquidator"
               leader := some leaderAddressText
               allowDeposits := false })]
         mergedRows :=
           [{ vaultAddress := vaultAddressText
              name := some "Liquidator"
              leader := some leaderAddressText }] }
       { mode := .deposit
         vaultAddress := some "garbage"
         amountInput := "0"
         withdrawAll := false }
    ,previewVector "deposit-disabled-wins-before-invalid-amount"
       (some vaultAddressText)
       (otherWalletVaultState false)
       { mode := .deposit
         amountInput := "nope"
         withdrawAll := false }
    ,previewVector "leader-override-success"
       none
       (leaderVaultState false)
       { mode := .deposit
         vaultAddress := some vaultAddressText
         amountInput := "1.25"
         withdrawAll := false }
    ,previewVector "merged-row-leader-fallback-success"
       none
       mergedLeaderFallbackState
       { mode := .deposit
         vaultAddress := some vaultAddressText
         amountInput := "3"
         withdrawAll := false }
    ,previewVector "liquidator-blocked"
       none
       liquidatorState
       { mode := .deposit
         vaultAddress := some vaultAddressText
         amountInput := "1"
         withdrawAll := false }
    ,previewVector "withdraw-all-bypasses-amount"
       none
       (leaderVaultState true)
       { mode := .withdraw
         vaultAddress := some vaultAddressText
         amountInput := ""
         withdrawAll := true }
    ,previewVector "deposit-withdraw-all-flag-does-not-bypass-amount"
       none
       (leaderVaultState true)
       { mode := .deposit
         vaultAddress := some vaultAddressText
         amountInput := ""
         withdrawAll := true }]

def generatedSource : String :=
  renderNamespace "hyperopen.formal.vault-transfer-vectors"
    [("vault-address", .str vaultAddressText)
    ,("leader-address", .str leaderAddressText)
    ,("other-address", .str otherAddressText)
    ,("parse-usdc-micros-vectors", parseVectors)
    ,("deposit-eligibility-vectors", depositVectors)
    ,("vault-transfer-preview-vectors", previewVectors)]

def verify : IO Unit := do
  writeGeneratedSource surface generatedSource

def sync : IO Unit := do
  writeGeneratedSource surface generatedSource

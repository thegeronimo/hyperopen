namespace Hyperopen.Formal

inductive Surface where
  | vaultTransfer
  | orderRequestStandard
  | orderRequestAdvanced
  deriving Repr, DecidableEq, Inhabited

inductive Command where
  | verify
  | sync
  deriving Repr, DecidableEq, Inhabited

structure Invocation where
  command : Command
  surface : Surface
  deriving Repr

def surfaceId : Surface → String
  | .vaultTransfer => "vault-transfer"
  | .orderRequestStandard => "order-request-standard"
  | .orderRequestAdvanced => "order-request-advanced"

def surfaceModuleName : Surface → String
  | .vaultTransfer => "Hyperopen.Formal.VaultTransfer"
  | .orderRequestStandard => "Hyperopen.Formal.OrderRequest.Standard"
  | .orderRequestAdvanced => "Hyperopen.Formal.OrderRequest.Advanced"

def surfaceManifestPath : Surface → String :=
  fun surface => "../generated/" ++ surfaceId surface ++ ".edn"

def surfaceManifest : Surface → String :=
  fun surface =>
    "{:surface \"" ++ surfaceId surface ++ "\" :module \"" ++ surfaceModuleName surface ++ "\" :status \"bootstrap\"}\n"

def parseSurface? : String → Option Surface
  | "vault-transfer" => some .vaultTransfer
  | "order-request-standard" => some .orderRequestStandard
  | "order-request-advanced" => some .orderRequestAdvanced
  | _ => none

def commandId : Command → String
  | .verify => "verify"
  | .sync => "sync"

def parseCommand? : String → Option Command
  | "verify" => some .verify
  | "sync" => some .sync
  | _ => none

def usage : String :=
  "Usage: formal <verify|sync> --surface <vault-transfer|order-request-standard|order-request-advanced>"

def parseInvocation : List String → Except String Invocation
  | [] => Except.error usage
  | "help" :: _ => Except.error usage
  | "--help" :: _ => Except.error usage
  | command :: tail =>
      match parseCommand? command with
      | none => Except.error (command ++ " is not a supported command.\n" ++ usage)
      | some command' =>
          match tail with
          | ["--surface", surface] =>
              match parseSurface? surface with
              | none => Except.error (surface ++ " is not a supported surface.\n" ++ usage)
              | some surface' => Except.ok {command := command', surface := surface'}
          | [] => Except.error ("Missing --surface.\n" ++ usage)
          | _ => Except.error ("Expected exactly one --surface argument.\n" ++ usage)

def writeManifest (surface : Surface) : IO Unit := do
  let path := surfaceManifestPath surface
  IO.FS.createDirAll "../generated"
  IO.FS.writeFile path (surfaceManifest surface)

def verifyManifest (surface : Surface) : IO Unit := do
  let path := surfaceManifestPath surface
  try
    let actual ← IO.FS.readFile path
    let expected := surfaceManifest surface
    if actual = expected then
      pure ()
    else
      throw <| IO.userError s!"Stale generated manifest at {path}"
  catch _ =>
    throw <| IO.userError s!"Missing generated manifest at {path}"

theorem parseSurface?_vaultTransfer :
    parseSurface? "vault-transfer" = some Surface.vaultTransfer := by
  rfl

theorem parseSurface?_standard :
    parseSurface? "order-request-standard" = some Surface.orderRequestStandard := by
  rfl

theorem parseCommand?_verify :
    parseCommand? "verify" = some Command.verify := by
  rfl

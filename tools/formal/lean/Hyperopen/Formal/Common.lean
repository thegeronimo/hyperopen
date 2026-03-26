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

inductive Clj where
  | nil
  | bool (value : Bool)
  | nat (value : Nat)
  | str (value : String)
  | keyword (value : String)
  | symbol (value : String)
  | vector (values : List Clj)
  | arrayMap (entries : List (Clj × Clj))
  deriving Repr, Inhabited

def surfaceId : Surface → String
  | .vaultTransfer => "vault-transfer"
  | .orderRequestStandard => "order-request-standard"
  | .orderRequestAdvanced => "order-request-advanced"

def surfaceModuleName : Surface → String
  | .vaultTransfer => "Hyperopen.Formal.VaultTransfer"
  | .orderRequestStandard => "Hyperopen.Formal.OrderRequest.Standard"
  | .orderRequestAdvanced => "Hyperopen.Formal.OrderRequest.Advanced"

def surfaceStatus : Surface → String
  | .vaultTransfer => "modeled"
  | .orderRequestStandard => "modeled"
  | .orderRequestAdvanced => "bootstrap"

def surfaceManifestPath : Surface → String :=
  fun surface => "../generated/" ++ surfaceId surface ++ ".edn"

def surfaceGeneratedSourcePath? : Surface → Option String
  | .vaultTransfer => some "../../../target/formal/vault-transfer-vectors.cljs"
  | .orderRequestStandard => some "../../../target/formal/order-request-standard-vectors.cljs"
  | .orderRequestAdvanced => none

def surfaceManifest : Surface → String :=
  fun surface =>
    "{:surface \"" ++ surfaceId surface ++ "\" :module \"" ++ surfaceModuleName surface ++
      "\" :status \"" ++ surfaceStatus surface ++ "\"}\n"

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

def joinWithSpaces : List String → String
  | [] => ""
  | first :: rest => rest.foldl (fun acc part => acc ++ " " ++ part) first

def escapeString (value : String) : String :=
  String.join <|
    value.toList.map fun c =>
      if c = '\\' then "\\\\"
      else if c = '"' then "\\\""
      else if c = '\n' then "\\n"
      else if c = '\t' then "\\t"
      else if c = '\r' then "\\r"
      else String.ofList [c]

partial def renderClj : Clj → String
  | .nil => "nil"
  | .bool true => "true"
  | .bool false => "false"
  | .nat value => toString value
  | .str value => "\"" ++ escapeString value ++ "\""
  | .keyword value => ":" ++ value
  | .symbol value => value
  | .vector values => "[" ++ joinWithSpaces (values.map renderClj) ++ "]"
  | .arrayMap entries =>
      let renderedEntries :=
        entries.foldr
          (fun (key, value) acc => renderClj key :: renderClj value :: acc)
          []
      "(array-map" ++
        (if renderedEntries.isEmpty then
           ""
         else
           " " ++ joinWithSpaces renderedEntries) ++
        ")"

def renderDef (name : String) (value : Clj) : String :=
  "(def " ++ name ++ "\n  " ++ renderClj value ++ ")\n"

def renderNamespace (namespaceName : String) (defs : List (String × Clj)) : String :=
  let defBlocks := defs.map fun (name, value) => renderDef name value
  String.intercalate "\n" <|
    [";; GENERATED by `npm run formal:sync`. Do not edit by hand."
    , "(ns " ++ namespaceName ++ ")"
    , ""] ++ defBlocks

def writeGeneratedSource (surface : Surface) (content : String) : IO Unit := do
  match surfaceGeneratedSourcePath? surface with
  | none => pure ()
  | some path =>
      IO.FS.createDirAll "../../../target/formal"
      IO.FS.writeFile path content

theorem parseSurface?_vaultTransfer :
    parseSurface? "vault-transfer" = some Surface.vaultTransfer := by
  rfl

theorem parseSurface?_standard :
    parseSurface? "order-request-standard" = some Surface.orderRequestStandard := by
  rfl

theorem parseCommand?_verify :
    parseCommand? "verify" = some Command.verify := by
  rfl

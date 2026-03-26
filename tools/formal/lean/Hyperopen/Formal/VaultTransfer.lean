import Hyperopen.Formal.Common

namespace Hyperopen.Formal.VaultTransfer

open Hyperopen.Formal

def surface : Surface := .vaultTransfer

theorem surface_id :
    surfaceId surface = "vault-transfer" := by
  rfl

theorem manifest_spec :
    surfaceManifest surface =
      "{:surface \"vault-transfer\" :module \"Hyperopen.Formal.VaultTransfer\" :status \"bootstrap\"}\n" := by
  rfl

def verify : IO Unit := do
  verifyManifest surface

def sync : IO Unit := do
  writeManifest surface

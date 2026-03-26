import Hyperopen.Formal.OrderRequest.Common

namespace Hyperopen.Formal.OrderRequest.Advanced

open Hyperopen.Formal

def surface : Surface := .orderRequestAdvanced

theorem surface_id :
    surfaceId surface = "order-request-advanced" := by
  rfl

theorem manifest_spec :
    surfaceManifest surface =
      "{:surface \"order-request-advanced\" :module \"Hyperopen.Formal.OrderRequest.Advanced\" :status \"bootstrap\"}\n" := by
  rfl

def verify : IO Unit := do
  verifyManifest surface

def sync : IO Unit := do
  writeManifest surface

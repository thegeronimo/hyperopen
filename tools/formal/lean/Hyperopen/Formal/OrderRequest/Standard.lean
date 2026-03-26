import Hyperopen.Formal.OrderRequest.Common

namespace Hyperopen.Formal.OrderRequest.Standard

open Hyperopen.Formal

def surface : Surface := .orderRequestStandard

theorem surface_id :
    surfaceId surface = "order-request-standard" := by
  rfl

theorem manifest_spec :
    surfaceManifest surface =
      "{:surface \"order-request-standard\" :module \"Hyperopen.Formal.OrderRequest.Standard\" :status \"bootstrap\"}\n" := by
  rfl

def verify : IO Unit := do
  verifyManifest surface

def sync : IO Unit := do
  writeManifest surface

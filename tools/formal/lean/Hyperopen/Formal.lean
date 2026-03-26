import Hyperopen.Formal.Common
import Hyperopen.Formal.VaultTransfer
import Hyperopen.Formal.OrderRequest.Standard
import Hyperopen.Formal.OrderRequest.Advanced

namespace Hyperopen.Formal

def runVerify : Surface → IO Unit
  | .vaultTransfer => VaultTransfer.verify
  | .orderRequestStandard => OrderRequest.Standard.verify
  | .orderRequestAdvanced => OrderRequest.Advanced.verify

def runSync : Surface → IO Unit
  | .vaultTransfer => VaultTransfer.sync
  | .orderRequestStandard => OrderRequest.Standard.sync
  | .orderRequestAdvanced => OrderRequest.Advanced.sync

def runInvocation : Invocation → IO Unit
  | {command := .verify, surface} => runVerify surface
  | {command := .sync, surface} => runSync surface

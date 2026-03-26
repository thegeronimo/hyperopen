import Hyperopen.Formal

open Hyperopen.Formal

def main (args : List String) : IO UInt32 := do
  match parseInvocation args with
  | .ok invocation =>
      try
        runInvocation invocation
        return 0
      catch err =>
        IO.eprintln s!"{err}"
        return 1
  | .error message =>
      IO.eprintln message
      return 1

(ns hyperopen.hyperevm.actions
  "Pure HyperEVM actions: the reads, and the Balances tab's location filter.
   Each returns effects and never reads the clock: callers pass `:now-ms`, so
   the same state and args always yield the same effects."
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.hyperevm.domain.balances :as balances]
            [hyperopen.hyperevm.domain.bridge :as bridge]
            [hyperopen.hyperevm.domain.tokens :as tokens]
            [hyperopen.hyperevm.domain.transfer-state :as transfer-state]))

(defn refresh-hyperevm-balances
  "Refresh HyperEVM balances for the shown and owner addresses when due.

   `opts` is `{:now-ms n :force? bool :fast-poll-ms n}`. `:force?` is for an
   explicit refresh after a transfer: it skips the freshness, surface,
   loading and receipt-wait checks (never the rate-limit backoff). The
   poller never forces. `:fast-poll-ms` starts, or extends, a fast-poll
   window during which ticks refresh every few seconds."
  [state {:keys [now-ms force? fast-poll-ms]}]
  (let [current-until (get-in state [:hyperevm :fast-poll-until-ms])
        fast-until (when (and (number? now-ms)
                              (number? fast-poll-ms)
                              (pos? fast-poll-ms))
                     (max (+ now-ms fast-poll-ms)
                          (if (number? current-until) current-until 0)))
        state* (cond-> state
                 fast-until (assoc-in [:hyperevm :fast-poll-until-ms] fast-until))
        plan (when (number? now-ms)
               (balances/refresh-plan state* now-ms {:force? (true? force?)}))]
    (cond-> []
      fast-until (conj [:effects/save [:hyperevm :fast-poll-until-ms] fast-until])
      plan (conj [:effects/fetch-hyperevm-balances plan]))))

(defn refresh-hyperevm-bridge-capacity
  "Fetch what a HyperEVM -> Core move of token `index` needs from HyperCore:
   the Core balance of the token's system address (HYPE's is 0x2222…; USDC
   needs none), and whether the owner's HyperCore account exists.

   Only `:active` is final. An unknown or `:missing` status is read again on
   every call, because the owner may have activated the account since (a
   Core deposit, or a USDC HyperEVM -> Core move); the effect then bypasses
   the info cache. Dispatch this on every EVM -> Core route or asset
   selection and after any completed Core-bound transfer."
  [state index]
  (let [token (tokens/token-by-index (get-in state [:spot :meta]) index)
        owner (account-context/owner-address state)]
    (if-not token
      []
      (cond-> []
        (bridge/core-capacity-fetch-needed? token)
        (conj [:effects/fetch-hyperevm-core-bridge-balance index (:system-address token)])

        (and owner (not= :active (account-context/core-account-activation-status state)))
        (conj [:effects/fetch-hyperevm-core-account-status owner])))))

(defn check-hyperevm-in-flight
  "Read the receipt of every HyperEVM -> Core move left confirming in the
   background (`transfer-state/pending-in-flight`); the effect settles each
   one whose receipt has landed. The balance poller dispatches this while
   any such move exists."
  [state]
  (mapv (fn [[owner entry]]
          [:effects/fetch-hyperevm-in-flight-receipt
           owner
           (transfer-state/in-flight-tx-hash entry)])
        (transfer-state/pending-in-flight state)))

(def balances-location-filters
  "Where the Balances tab can narrow its rows to."
  #{:all :hypercore :hyperevm})

(defn set-balances-location-filter
  "Show the Balances rows of every place (`:all`), HyperCore only
   (`:hypercore`) or HyperEVM only (`:hyperevm`). Session UI state, like Hide
   Small Balances; anything else is ignored."
  [_state location-filter]
  (if (contains? balances-location-filters location-filter)
    [[:effects/save [:account-info :balances-location-filter] location-filter]]
    []))

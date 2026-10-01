(ns hyperopen.hyperevm.domain.bridge
  "Bridge solvency and token health, as hard gates on HyperCore <-> HyperEVM
   moves.

   Hyperliquid runs no supply or ERC-20 checks when a token crosses. A move
   credits the destination from the token's *system address* on the other
   ledger, so a system address that cannot pay out swallows the funds:

   - Core -> EVM debits HyperCore, then the token's ERC-20 transfers from its
     system address on HyperEVM. It is capped by `balanceOf(systemAddress)`.
     On mainnet SIX (idx 6) and NAV (idx 1022) hold 0 there while Core supply
     is outstanding, and FUNT (idx 478) is 0 on both sides.
   - EVM -> Core sends the ERC-20 (or native HYPE) to the system address, then
     HyperCore credits the sender from the system address's Core spot
     balance. It is capped by that balance, fetched on demand.
   - A token whose `decimals()` disagrees with spotMeta (HOPE returns 0 where
     spotMeta implies 5) or whose `balanceOf` reverts (JOFF) would be
     credited at the wrong scale or not at all, so it is unmovable.

   Native HYPE and USDC are exempt. HYPE is the chain's own coin, and USDC is
   bridged by Circle's CoreDepositWallet rather than a pre-minted balance, so
   both have unlimited capacity and are always healthy.

   State lives at `[:hyperevm :bridge]`:

       {:evm-system-units {idx \"base units\"}
        :evm-system-read-at-ms {idx n}
        :core->evm-sent-at-ms {idx n}
        :core-system-balances {idx {:amount \"…\" :loaded-at-ms n :error \"…\"
                                    :requested-at-ms n}}
        :token-health {idx {:decimals-ok? bool :balance-of-ok? bool}}
        :health-requested-at-ms n}

   `:evm-system-read-at-ms` is when the poll that read each token's HyperEVM
   system balance was requested (a chunk that went unread stamps nothing),
   and `:core->evm-sent-at-ms` when the user last sent that token Core ->
   EVM. Amounts are decimal strings; a missing entry means unknown, never
   zero."
  (:require [hyperopen.hyperevm.domain.abi :as abi]
            [hyperopen.hyperevm.domain.units :as units]))

(def core-capacity-max-age-ms
  "Oldest HyperCore system balance `evm->core-capacity` still trusts when
   given the clock. Each EVM -> Core move draws it down, so an old reading
   can overstate the cap."
  60000)

(def evm-capacity-max-age-ms
  "Oldest HyperEVM system balance `core->evm-capacity` still trusts when
   given the clock. The balance poll reads it every 30 s while a surface is
   up, so a reading this old means polls are failing, backing off or
   paused, and a Core -> EVM move must not be checked against it."
  60000)

(def core->evm-settle-ms
  "How long after a Core -> EVM send a HyperEVM system-balance read must
   have been requested to count: the credit lands in the next HyperEVM
   block, so a read sent sooner may not show the send's draw on the
   bridge."
  5000)

(def core-capacity-refresh-ms
  "Age at which a HyperCore system balance is read again while a HyperEVM ->
   Core draft is open: early enough that an idle form never reaches
   `core-capacity-max-age-ms`."
  45000)

(def core-capacity-retry-ms
  "Least time between two reads of the same HyperCore system balance, so a
   read still in flight, or one that just failed, is not sent again on every
   keystroke or poller tick."
  10000)

(def ^:private core-amount-decimals
  "Decimals used to subtract HyperCore balance strings exactly. Core reports
   more fractional digits than weiDecimals (the PURR system address held
   \"91403084.7764399946\" live on 2026-09-30, weiDecimals 5), so parsing at
   weiDecimals would lose them before the final floor."
  18)

(defn- bridge-state
  [state]
  (get-in state [:hyperevm :bridge]))

(defn- exempt?
  "HYPE and USDC never need a bridge-health read."
  [token]
  (contains? #{:native :usdc-cdw} (:kind token)))

(defn- erc20?
  [token]
  (= :erc20 (:kind token)))

;; --- reads ---------------------------------------------------------------

(defn health-calls
  "Multicall3 reads for the bridge-health check, as `read-balances!`
   `:extra-calls`: `balanceOf(systemAddress)` for every `:erc20` token, and
   `decimals()` for the tokens whose index is in `decimals-indexes` (each
   token's first sighting in the session). Keys are `[:system idx]` and
   `[:decimals idx]`."
  [tokens decimals-indexes]
  (let [pending (set decimals-indexes)
        erc20-tokens (filter #(and (erc20? %) (:erc20-address %) (:system-address %))
                             tokens)]
    (-> []
        (into (keep (fn [{:keys [index erc20-address system-address]}]
                      (when-let [data (abi/encode-balance-of system-address)]
                        {:key [:system index] :target erc20-address :call-data data})))
              erc20-tokens)
        (into (keep (fn [{:keys [index erc20-address]}]
                      (when (contains? pending index)
                        {:key [:decimals index]
                         :target erc20-address
                         :call-data (abi/encode-decimals)})))
              erc20-tokens))))

(defn pending-decimals-indexes
  "Indexes of `:erc20` tokens with no definite `decimals()` answer yet: never
   checked, or checked only by calls that failed."
  [state tokens]
  (let [health (:token-health (bridge-state state))]
    (into []
          (comp (filter erc20?)
                (map :index)
                (remove #(contains? (get health %) :decimals-ok?)))
          tokens)))

(defn- system-read
  [{:keys [success? return-data]}]
  (when success?
    (abi/decode-uint256 return-data)))

(defn- decimals-read
  [{:keys [success? return-data]}]
  (when success?
    (some-> (abi/decode-uint256 return-data) js/Number)))

(defn health-result
  "Project `read-balances!` `:extra-results`/`:extra-unread` for
   `health-calls` into `{:evm-system-units {idx units} :token-health {idx
   {...}}}`. A call whose chunk went unread updates nothing, so its previous
   value stands. A system `balanceOf` that ran and failed marks the token
   unhealthy until a later poll reads it. A `decimals()` call that failed
   records nothing, so it is re-checked on the next poll; only a definite
   answer (matching or not) is cached, since one gas-starved chunk must not
   make its tokens unmovable for the session."
  [tokens {:keys [extra-results extra-unread]}]
  (let [unread (set extra-unread)
        by-index (into {} (map (juxt :index identity)) tokens)]
    (reduce-kv
     (fn [acc [kind index] result]
       (let [token (get by-index index)]
         (cond
           (or (nil? token) (contains? unread [kind index]))
           acc

           (= :system kind)
           (if-let [amount (system-read result)]
             (-> acc
                 (assoc-in [:evm-system-units index] (units/units-text amount))
                 (assoc-in [:token-health index :balance-of-ok?] true))
             (assoc-in acc [:token-health index :balance-of-ok?] false))

           (= :decimals kind)
           (if-let [decimals (decimals-read result)]
             (assoc-in acc [:token-health index :decimals-ok?]
                       (= (:evm-decimals token) decimals))
             acc)

           :else acc)))
     {:evm-system-units {} :token-health {}}
     (or extra-results {}))))

(defn apply-health
  "Merge a `health-result` into state. Tokens it did not cover keep their
   previous values, and a token whose system `balanceOf` failed loses its
   cached units, so its capacity reads as unknown.

   Given `requested-at-ms`, the result is applied only when its request is
   newer than the last one applied (`:health-requested-at-ms`), so a slow
   reply can never overwrite fresher capacity."
  ([state {:keys [evm-system-units token-health]}]
   (let [failed (keep (fn [[index health]]
                        (when (false? (:balance-of-ok? health)) index))
                      token-health)]
     (update-in state [:hyperevm :bridge]
                (fn [bridge]
                  (-> bridge
                      (update :evm-system-units #(apply dissoc (merge % evm-system-units) failed))
                      (update :token-health #(merge-with merge % token-health)))))))
  ([state result requested-at-ms]
   (let [last-applied (get-in state [:hyperevm :bridge :health-requested-at-ms])
         read-indexes (keys (:evm-system-units result))]
     (if (and (number? requested-at-ms)
              (or (not (number? last-applied)) (> requested-at-ms last-applied)))
       (-> (apply-health state result)
           (assoc-in [:hyperevm :bridge :health-requested-at-ms] requested-at-ms)
           (update-in [:hyperevm :bridge :evm-system-read-at-ms]
                      #(merge % (zipmap read-indexes (repeat requested-at-ms)))))
       state))))

;; --- health and capacity ---------------------------------------------------

(defn token-health-status
  "`:ok`, `:bad` or `:unknown` for moving `token` across the bridge.

   HYPE and USDC are always `:ok`. An `:erc20` token is `:bad` once either
   check failed and `:ok` only when both passed. A nil token is `:unknown`."
  [state token]
  (cond
    (nil? token) :unknown
    (exempt? token) :ok
    (not (erc20? token)) :unknown
    :else
    (let [{:keys [decimals-ok? balance-of-ok?]}
          (get-in (bridge-state state) [:token-health (:index token)])]
      (cond
        (or (false? decimals-ok?) (false? balance-of-ok?)) :bad
        (and (true? decimals-ok?) (true? balance-of-ok?)) :ok
        :else :unknown))))

(defn movable?
  "Whether `token` may cross the bridge at all: false when its health is bad
   (JOFF, HOPE), not yet known, or the token itself is unknown."
  [state token]
  (= :ok (token-health-status state token)))

(defn- floor-core
  [amount-text token]
  (units/floor-to-decimals amount-text (:core-precision token)))

(defn- evm-system-reading-current?
  "Whether `token`'s HyperEVM system-balance reading may gate a move at
   `now-ms`: requested less than `evm-capacity-max-age-ms` ago, and at least
   `core->evm-settle-ms` after the user's last Core -> EVM send of it (a
   reading from before that send overstates what the bridge still holds)."
  [state token now-ms]
  (let [index (:index token)
        read-at (get-in (bridge-state state) [:evm-system-read-at-ms index])
        sent-at (get-in (bridge-state state) [:core->evm-sent-at-ms index])]
    (and (number? read-at)
         (< (- now-ms read-at) evm-capacity-max-age-ms)
         (or (not (number? sent-at))
             (>= (- read-at sent-at) core->evm-settle-ms)))))

(defn core->evm-capacity
  "Most of `token` a Core -> EVM move can deliver: the HyperEVM system
   address's balance scaled to Core units and floored to the token's Core
   precision, `:unlimited` for HYPE and USDC, or nil when unknown.

   Given `now-ms` it is also nil unless the reading is current
   (`evm-system-reading-current?`): a poll that failed, backed off, paused
   for a receipt wait or lost the token's chunk leaves the last amount in
   state, and a Core -> EVM send draws it down before the next read shows
   it. The Transfer modal passes the clock, so a stale or pre-send amount
   never passes as current; it reads \"Checking the bridge balance…\"."
  ([state token]
   (core->evm-capacity state token nil))
  ([state token now-ms]
   (cond
     (nil? token) nil
     (exempt? token) :unlimited
     (and (some? now-ms) (not (evm-system-reading-current? state token now-ms))) nil
     :else
     (some-> (get-in (bridge-state state) [:evm-system-units (:index token)])
             (units/format-units (:evm-decimals token))
             (floor-core token)))))

(defn mark-core->evm-sent
  "Record that the user sent `token` Core -> EVM at `now-ms`, so its
   HyperEVM capacity reads as unknown until a read requested after the send
   lands. HYPE and USDC have no cap to protect."
  [state token now-ms]
  (if (and (erc20? token) (number? now-ms))
    (assoc-in state [:hyperevm :bridge :core->evm-sent-at-ms (:index token)] now-ms)
    state))

(defn evm->core-capacity
  "Most of `token` an EVM -> Core move can deliver: the Core system
   address's spot balance floored to the token's Core precision, or
   `:unlimited` for USDC. HYPE's comes from 0x2222…2222.

   It is nil (unknown) until fetched, and whenever the latest fetch failed
   (`:error`), even though the last amount is kept in state. Given `now-ms`,
   it is also nil once the amount is `core-capacity-max-age-ms` old; the
   Transfer modal passes the clock, so a stale cap never passes as current."
  ([state token]
   (evm->core-capacity state token nil))
  ([state token now-ms]
   (cond
     (nil? token) nil
     (= :usdc-cdw (:kind token)) :unlimited
     :else
     (let [{:keys [amount loaded-at-ms error]}
           (get-in (bridge-state state) [:core-system-balances (:index token)])]
       (when (and (string? amount)
                  (nil? error)
                  (or (nil? now-ms)
                      (and (number? loaded-at-ms)
                           (< (- now-ms loaded-at-ms) core-capacity-max-age-ms))))
         (floor-core amount token))))))

(defn core-capacity-fetch-needed?
  "Whether an EVM -> Core move of `token` is capped by a Core system
   balance that must be fetched: HYPE and every `:erc20`, never USDC."
  [token]
  (contains? #{:native :erc20} (:kind token)))

(defn- core-system-entry
  [state token]
  (get-in (bridge-state state) [:core-system-balances (:index token)]))

(defn core-capacity-error
  "The error of the latest failed HyperCore system-balance read for
   `token`, or nil."
  [state token]
  (when (core-capacity-fetch-needed? token)
    (some-> (:error (core-system-entry state token)) str not-empty)))

(defn core-capacity-refresh-due?
  "Whether `token`'s HyperCore system balance should be read again at
   `now-ms`: never read, failed, or `core-capacity-refresh-ms` old, and no
   read sent in the last `core-capacity-retry-ms`. Always false for USDC,
   whose capacity needs no read."
  [state token now-ms]
  (boolean
   (when (and (core-capacity-fetch-needed? token) (number? now-ms))
     (let [{:keys [amount loaded-at-ms error requested-at-ms]} (core-system-entry state token)]
       (and (not (and (number? requested-at-ms)
                      (< (- now-ms requested-at-ms) core-capacity-retry-ms)))
            (or (not (string? amount))
                (some? error)
                (not (number? loaded-at-ms))
                (>= (- now-ms loaded-at-ms) core-capacity-refresh-ms)))))))

(defn mark-core-system-balance-requested
  "Record that token `index`'s HyperCore system balance was requested at
   `now-ms`, keeping the last amount. A successful read replaces the entry."
  [state index now-ms]
  (assoc-in state [:hyperevm :bridge :core-system-balances index :requested-at-ms] now-ms))

;; --- Core system balances ----------------------------------------------------

(defn- core-available-text
  "total - hold of a spot balance row as a decimal string, never negative."
  [{:keys [total hold]}]
  (let [total* (units/parse-units (str total) core-amount-decimals)
        hold* (or (units/parse-units (str (or hold "0")) core-amount-decimals) units/zero)]
    (when total*
      (units/format-units (if (> total* hold*) (- total* hold*) units/zero)
                          core-amount-decimals))))

(defn apply-core-system-balance
  "Store the Core spot balance of token `index` held by its system address,
   from a `spotClearinghouseState` payload. A payload with no row for the
   token means the system address holds none of it."
  [state index payload now-ms]
  (let [rows (when (map? payload) (:balances payload))
        row (some #(when (= index (:token %)) %) (when (sequential? rows) rows))
        amount (if row (core-available-text row) "0")]
    (if (and (sequential? rows) amount)
      (assoc-in state [:hyperevm :bridge :core-system-balances index]
                {:amount amount :loaded-at-ms now-ms})
      (assoc-in state [:hyperevm :bridge :core-system-balances index :error]
                "HyperCore returned an unreadable balance."))))

(defn apply-core-system-balance-error
  "Record a failed Core system-balance fetch, keeping any previous amount."
  [state index message]
  (assoc-in state [:hyperevm :bridge :core-system-balances index :error]
            (or message "Could not read the HyperCore bridge balance.")))

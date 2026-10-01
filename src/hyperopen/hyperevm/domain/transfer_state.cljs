(ns hyperopen.hyperevm.domain.transfer-state
  "HyperEVM transfer state that outlives the Transfer modal: in-flight
   transactions and cached wallet capabilities. Pure readers and paths; the
   submit effect writes both.

   - `[:hyperevm :in-flight <owner>]` holds the owner's unresolved HyperEVM ->
     Core move, `{:flow-id :status :hashes [..] :waiting-receipt? bool
     :started-at-ms n :submitted-at-ms n :step kw :kind str :asset
     :token-index :amount :arrival {..}}`, where `:kind` is the
     `hyperEvmToCore` action's own kind string (`native`, `erc20` or
     `usdcCoreDeposit`). It is written when the move starts, before the
     wallet is asked for anything, because a hash can come back at any
     moment after that. `:status` is `:running` while the submit flow owns
     it, `:pending` once the flow gave up waiting for a receipt (the balance
     poller then resolves it in the background, `pending-in-flight`), and
     `:unconfirmed` when the wallet answered a send without a usable hash:
     that transaction may exist, but nothing can track it, so the entry
     stays until a reload. It is cleared once no transaction is left
     unresolved. While it exists, no new HyperEVM -> Core move may start: a
     second send would move the funds twice.

     A pending transaction that is replaced (speed up or cancel in the
     wallet) or dropped never gets a receipt, so its entry also stays until
     a reload; `in-flight-check-interval-ms` slows its receipt reads down
     as it ages.
   - `[:hyperevm :wallet-capabilities <provider-key>]` holds what a wallet
     provider has proven it cannot do, today only `{:chain-switch
     :unsupported}` after it refused to switch to HyperEVM (EIP-1193 has no
     way to ask up front). It is keyed per provider, so connecting another
     wallet starts clean."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.hyperevm.domain.chain :as chain]))

(def ^:private default-provider-key
  "Capability key used when the wallet reports no provider id (a single
   injected `window.ethereum`)."
  "default")

(def in-flight-message
  "Why a HyperEVM -> Core move cannot start while an earlier one's
   transaction is sent and its receipt is still awaited (at most the
   foreground wait, three minutes)."
  "A HyperEVM transfer is still confirming. Wait for it to finish before starting another.")

(def wallet-unanswered-message
  "Why a HyperEVM -> Core move cannot start while an earlier one waits on
   the wallet (no transaction hash yet). A prompt that is never answered
   keeps its entry until a reload, so the copy says how to get out."
  (str "Your last HyperEVM transfer is waiting for your wallet. Finish or reject it there, "
       "or reload the page to start over."))

(def pending-message
  "Why a HyperEVM -> Core move cannot start after the foreground receipt
   wait gave up. A transaction replaced (speed up or cancel) or dropped in
   the wallet never gets a receipt, so the copy never promises it will
   finish, and says how to get out."
  (str "Your last HyperEVM transfer hasn't confirmed yet. If your wallet shows it finished, "
       "was replaced or was cancelled, reload the page to start another."))

(def unconfirmed-message
  "Why a HyperEVM -> Core move cannot start after the wallet answered a send
   without a transaction hash."
  (str "Your wallet didn't report whether your last HyperEVM transfer was sent. Check your "
       "wallet activity, then reload the page to start another."))

(defn in-flight-entry
  "The owner's unresolved HyperEVM transaction entry, or nil."
  [state owner]
  (when-let [owner* (account-context/normalize-address owner)]
    (let [entry (get-in state [:hyperevm :in-flight owner*])]
      (when (map? entry) entry))))

(defn in-flight-tx-hash
  "The latest transaction hash of an in-flight entry, or nil."
  [entry]
  (let [hashes (:hashes entry)]
    (when (sequential? hashes)
      (some-> (last hashes) str not-empty))))

(defn in-flight-tx-url
  "Explorer URL of the latest in-flight transaction, or nil."
  [entry]
  (some-> (in-flight-tx-hash entry) chain/explorer-tx-url))

(defn in-flight-blocked-message
  "Why `entry` (an in-flight entry, or nil) blocks another HyperEVM -> Core
   move, or nil when there is no entry. The copy follows the entry's state:
   still waiting on the wallet, confirming in the foreground, given up on
   (`:pending`), or answered without a hash (`:unconfirmed`)."
  [entry]
  (when (map? entry)
    (case (:status entry)
      :unconfirmed unconfirmed-message
      :pending pending-message
      (if (some? (in-flight-tx-hash entry))
        in-flight-message
        wallet-unanswered-message))))

(defn- in-flight-path
  "Where `owner`'s entry lives. Its last key is an address string, so it is
   for store swaps, never `:effects/save`."
  [owner]
  (when-let [owner* (account-context/normalize-address owner)]
    [:hyperevm :in-flight owner*]))

(defn start-in-flight
  "Record the start of `owner`'s HyperEVM -> Core move. An existing entry is
   never replaced: its transaction may still be confirming."
  [state owner entry]
  (let [path (in-flight-path owner)]
    (if (or (nil? path) (map? (get-in state path)))
      state
      (assoc-in state path (merge {:status :running
                                   :hashes []
                                   :waiting-receipt? false}
                                  entry)))))

(defn- flow-entry?
  [state path flow-id]
  (let [entry (when path (get-in state path))]
    (and (map? entry) (some? flow-id) (= flow-id (:flow-id entry)))))

(defn merge-in-flight
  "Merge `changes` into `owner`'s entry, only while it belongs to `flow-id`."
  [state owner flow-id changes]
  (let [path (in-flight-path owner)]
    (if (flow-entry? state path flow-id)
      (update-in state path merge changes)
      state)))

(defn record-in-flight-hash
  "A transaction of `flow-id` was sent: add its hash and pause balance
   polling while its receipt is awaited."
  [state owner flow-id {:keys [hash step submitted-at-ms]}]
  (let [path (in-flight-path owner)]
    (if (and (flow-entry? state path flow-id) (string? hash))
      (update-in state path
                 (fn [entry]
                   (assoc entry
                          :hashes (conj (vec (:hashes entry)) hash)
                          :step step
                          :waiting-receipt? true
                          :submitted-at-ms submitted-at-ms)))
      state)))

(defn clear-in-flight
  "Drop `owner`'s entry, only while it belongs to `flow-id`."
  [state owner flow-id]
  (let [path (in-flight-path owner)]
    (if (flow-entry? state path flow-id)
      (update-in state (pop path) dissoc (peek path))
      state)))

(defn pending-in-flight
  "`[[owner entry] ..]` for every move left confirming in the background:
   `:pending`, with a hash and no foreground receipt wait."
  [state]
  (let [entries (get-in state [:hyperevm :in-flight])]
    (->> (when (map? entries) entries)
         (filter (fn [[_ entry]]
                   (and (map? entry)
                        (= :pending (:status entry))
                        (not (true? (:waiting-receipt? entry)))
                        (some? (in-flight-tx-hash entry)))))
         (sort-by key)
         vec)))

(def in-flight-check-schedule
  "How often a move left pending is re-checked in the background, by the
   age of its latest transaction: `[[up-to-age-ms interval-ms] ..]`, then
   `in-flight-check-slowest-ms`. A HyperEVM transaction confirms within
   seconds, so one still without a receipt after minutes was most likely
   replaced or dropped; reading it every few seconds for the rest of the
   session would only spend the public RPC's rate limit."
  [[300000 nil]
   [1800000 30000]])

(def in-flight-check-slowest-ms
  120000)

(defn in-flight-check-interval-ms
  "The gap between background receipt reads at `now-ms`: `base-ms` (the
   poller's own) while every pending move is young, slower as the youngest
   one ages (`in-flight-check-schedule`). nil when nothing is pending."
  [state now-ms base-ms]
  (let [pending (pending-in-flight state)]
    (when (seq pending)
      (let [ages (keep (fn [[_ entry]]
                         (let [sent (:submitted-at-ms entry)]
                           (when (and (number? sent) (number? now-ms))
                             (max 0 (- now-ms sent)))))
                       pending)
            age (if (seq ages) (apply min ages) 0)]
        (or (some (fn [[up-to interval]]
                    (when (<= age up-to) (or interval base-ms)))
                  in-flight-check-schedule)
            in-flight-check-slowest-ms)))))

(defn provider-key
  "The capability cache key for the connected wallet provider."
  [state]
  (or (some-> (get-in state [:wallet :selected-provider-id]) str str/trim not-empty)
      default-provider-key))

(def wallet-capabilities-path
  "Where every provider's capabilities live. Writers that go through
   `:effects/save` must save this whole map: the provider keys are strings,
   and effect paths are keywords only."
  [:hyperevm :wallet-capabilities])

(defn capabilities-path
  "Read path of the connected provider's capabilities (its last key is a
   string, so it is for `get-in` and store swaps, never `:effects/save`)."
  [state]
  (conj wallet-capabilities-path (provider-key state)))

(defn capabilities-without-chain-switch
  "The whole capability map with the connected provider's refused-switch
   flag forgotten; a provider left with nothing cached is dropped."
  [state]
  (let [capabilities (get-in state wallet-capabilities-path)
        capabilities* (if (map? capabilities) capabilities {})
        key (provider-key state)
        entry (get capabilities* key)
        entry* (when (map? entry) (dissoc entry :chain-switch))]
    (if (seq entry*)
      (assoc capabilities* key entry*)
      (dissoc capabilities* key))))

(defn chain-switch-unsupported?
  "Whether the connected provider already refused to switch to HyperEVM."
  [state]
  (= :unsupported (get-in state (conj (capabilities-path state) :chain-switch))))

(defn wallet-chain-id
  "The wallet's active chain id as lowercase hex without leading zeros
   (\"0x3e7\"), or nil. Wallets report hex; a decimal id is converted."
  [state]
  (when-let [raw (some-> (get-in state [:wallet :chain-id]) str str/trim str/lower-case not-empty)]
    (let [hex? (str/starts-with? raw "0x")
          digits (if hex? (subs raw 2) raw)]
      (when (re-matches (if hex? #"^[0-9a-f]+$" #"^\d+$") digits)
        (str "0x" (.toString (js/parseInt digits (if hex? 16 10)) 16))))))

(defn wallet-on-hyperevm?
  [state]
  (= (:chain-id chain/mainnet) (wallet-chain-id state)))

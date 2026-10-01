(ns hyperopen.hyperevm.domain.balances
  "HyperEVM balance state: reads, the refresh plan, and the projections the
   fetch effect applies. Pure; the effect and the poller own the IO.

   Balances are keyed by lowercase address at
   `[:hyperevm :balances :by-address]`, because the display wants the
   effective account (spectated, trader route, subaccount) while the
   Transfer modal's gas check wants the connected owner. An entry is

       {:status :loading|:ready|:error :stale? bool
        :requested-at-ms n :loaded-at-ms n
        :native-wei \"…\" :token-units {idx \"…\"} :token-indexes #{idx}
        :unread-token-indexes [idx] :never-read-token-indexes #{idx}
        :gas-price-wei \"…\" :error \"…\" :error-kind kw}

   Every amount is a decimal string. Loading and errors are
   stale-while-revalidate: they keep the last good values, and nil from a
   reader always means unknown, never zero. `:unread-token-indexes` lists
   the tokens the latest read could not answer (a failed chunk or a failed
   call); they are unknown too. `:never-read-token-indexes` are those of
   them whose whole chunk went unread and that no read of the entry ever
   answered, so a sum over the entry is partial (`partial-read?`).

   A retry keeps a failed read's `:error` while it runs, so
   `first-read-failed?` stays true until a read lands."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.hyperevm.domain.fees :as fees]
            [hyperopen.hyperevm.domain.tokens :as tokens]
            [hyperopen.hyperevm.domain.units :as units]))

(def normal-freshness-ms
  "An address read this recently is skipped unless the refresh is forced or
   a fast poll is running."
  10000)

(def read-timeout-ms
  "How long a read may stay `:loading` before a new unforced refresh may
   supersede it. It matches the RPC client's request deadline
   (`hyperopen.hyperevm.infrastructure.rpc/default-timeout-ms`), so a slow
   reply is never dropped as overtaken by a fast-poll tick."
  10000)

(def receipt-wait-pause-ms
  "Longest a receipt wait pauses polling, counted from the in-flight entry's
   `:submitted-at-ms`. It matches the foreground receipt wait
   (`hyperopen.hyperevm.infrastructure.rpc/default-receipt-timeout-ms`), so a
   transaction that is dropped and never mined cannot stop polling for good."
  180000)

(def rate-limit-backoff-ms
  "Pause after the 1st, 2nd and 3rd+ consecutive rate limit. The public RPC
   allows 100 requests per minute per IP, shared by every tab on that IP."
  [30000 60000 120000])

(def ^:private hype-evm-decimals 18)

(def ^:private hype-core-precision
  "HYPE has 8 weiDecimals on HyperCore; used when spotMeta has not loaded."
  8)

(defn default-state
  []
  {:balances {:by-address {}}
   :bridge {:evm-system-units {} :evm-system-read-at-ms {} :core->evm-sent-at-ms {}
            :core-system-balances {} :token-health {} :health-requested-at-ms nil}
   :in-flight {}
   :fast-poll-until-ms nil
   :wallet-capabilities {}
   :core-account {}
   :backoff {:strikes 0 :until-ms nil}})

;; --- addresses ----------------------------------------------------------------

(defn display-address
  "The account whose HyperEVM balances the UI shows: the effective account,
   so spectate mode and trader routes show theirs, read-only."
  [state]
  (account-context/effective-account-address state))

(defn owner-address
  "The connected wallet, the only address that can sign a HyperEVM move."
  [state]
  (account-context/owner-address state))

(defn balance-addresses
  "Distinct addresses whose balances are kept: display first, then owner."
  [state]
  (->> [(display-address state) (owner-address state)]
       (keep account-context/normalize-address)
       distinct
       vec))

;; --- reads --------------------------------------------------------------------

(defn entry
  [state address]
  (when-let [address* (account-context/normalize-address address)]
    (get-in state [:hyperevm :balances :by-address address*])))

(defn- read?
  "Whether `entry` holds at least one successful read."
  [entry*]
  (string? (:native-wei entry*)))

(defn first-read-failed?
  "Whether `entry*` has never been read and its last read failed, including
   while a retry of it is in flight: `apply-loading` flips `:status` to
   `:loading` but keeps the failure's `:error`, so readers that show
   \"unavailable\" do not flip to \"loading\" on every retry."
  [entry*]
  (and (map? entry*)
       (not (read? entry*))
       (or (= :error (:status entry*))
           (some? (:error entry*)))))

(defn partial-read?
  "Whether `address`'s balances are read but some linked token was never
   answered: its whole aggregate3 chunk went unread on every read so far.
   A token whose own call failed does not count (JOFF's `balanceOf` reverts
   on every read, so it would leave the read partial for good)."
  [state address]
  (let [entry* (entry state address)]
    (boolean (and (read? entry*)
                  (seq (:never-read-token-indexes entry*))))))

(defn- hype-token
  [state]
  (tokens/token-by-name (get-in state [:spot :meta]) "HYPE"))

(defn native-hype-text
  "Native HYPE on HyperEVM at `address`, floored to HYPE's Core precision,
   or nil when unknown."
  [state address]
  (let [entry* (entry state address)]
    (when (read? entry*)
      (some-> (units/format-units (:native-wei entry*) hype-evm-decimals)
              (units/floor-to-decimals (or (:core-precision (hype-token state))
                                           hype-core-precision))))))

(defn token-amount-text
  "Amount of linked `token` at `address` on HyperEVM, floored to the token's
   Core precision, or nil when unknown: nothing read yet, the token's chunk
   or its own call failed with no earlier value, or the token was not part
   of the read (spotMeta changed since)."
  [state address token]
  (let [entry* (entry state address)
        index (:index token)]
    (when (and (read? entry*) token)
      (if (= :native (:kind token))
        (native-hype-text state address)
        (let [units* (get (:token-units entry*) index)]
          (cond
            (some? units*)
            (some-> (units/format-units units* (:evm-decimals token))
                    (units/floor-to-decimals (:core-precision token)))

            (some #{index} (:unread-token-indexes entry*)) nil
            (not (contains? (:token-indexes entry*) index)) nil
            :else "0"))))))

(defn evm-gas-status
  "Whether `address`'s native HYPE covers the worst-case gas of sending the
   HyperEVM transaction `kinds` (default one native transfer): `:ok`, `:low`,
   `:none`, or nil when the balance is unknown."
  ([state address]
   (evm-gas-status state address [:native]))
  ([state address kinds]
   (let [entry* (entry state address)]
     (when (read? entry*)
       (fees/evm-gas-status (units/format-units (:native-wei entry*) hype-evm-decimals)
                            (fees/evm-tx-cost-hype (:gas-price-wei entry*) kinds))))))

;; --- refresh policy -------------------------------------------------------------

(defn surface-active?
  "Whether a surface that shows HyperEVM balances is up: /trade, /portfolio
   (except the optimizer), or the funding modal."
  [state]
  (let [path (str (get-in state [:router :path]))]
    (or (true? (get-in state [:funding-ui :modal :open?]))
        (str/starts-with? path "/trade")
        (and (str/starts-with? path "/portfolio")
             (not (str/starts-with? path "/portfolio/optimize"))))))

(defn watch-fingerprint
  "Cheap inputs whose change should trigger an immediate refresh: the shown
   and owner addresses, whether each already has an entry (so an account
   reset that clears the entries triggers a re-read), spotMeta's linked-token
   count (the catalog is memoized on identity), and whether a surface is up."
  [state]
  (let [addresses (balance-addresses state)]
    [addresses
     (mapv #(some? (entry state %)) addresses)
     (count (tokens/linked-tokens (get-in state [:spot :meta])))
     (surface-active? state)]))

(defn fast-polling?
  [state now-ms]
  (let [until (get-in state [:hyperevm :fast-poll-until-ms])]
    (and (number? until) (< now-ms until))))

(defn waiting-receipt?
  "Whether an in-flight HyperEVM transaction submitted less than
   `receipt-wait-pause-ms` ago is still waiting for its receipt. Polling of
   addresses already read pauses meanwhile to leave RPC budget for the wait.
   An entry without a numeric `:submitted-at-ms`, or older than the bound,
   pauses nothing."
  [state now-ms]
  (boolean
   (some (fn [{:keys [waiting-receipt? submitted-at-ms]}]
           (and (true? waiting-receipt?)
                (number? submitted-at-ms)
                (< (- now-ms submitted-at-ms) receipt-wait-pause-ms)))
         (vals (get-in state [:hyperevm :in-flight])))))

(defn backing-off?
  [state now-ms]
  (let [until (get-in state [:hyperevm :backoff :until-ms])]
    (and (number? until) (< now-ms until))))

(defn- due?
  "Whether `entry*` needs a read: never requested, or requested at least
   `freshness-ms` ago and not still loading a request younger than
   `read-timeout-ms` (which only `supersede?` overrides)."
  [entry* now-ms freshness-ms supersede?]
  (let [requested-at (:requested-at-ms entry*)]
    (or (not (number? requested-at))
        (and (>= (- now-ms requested-at) freshness-ms)
             (or supersede?
                 (not= :loading (:status entry*))
                 (>= (- now-ms requested-at) read-timeout-ms))))))

(defn refresh-plan
  "`{:addresses [..] :requested-at-ms now}` for the addresses due a read, or
   nil when nothing should be fetched.

   - Nothing is fetched without spotMeta or an address, or while rate-limit
     backoff runs, forced or not.
   - An unforced refresh needs an active surface or a running fast poll.
   - An address requested within 10 s is skipped, except while a fast poll
     runs or when forced. A read still loading is left alone for
     `read-timeout-ms` unless forced, so ticks never overtake a slow reply.
   - While `waiting-receipt?`, an unforced refresh skips the addresses that
     already hold a read. An address never read is always planned, so an
     account switch during a receipt wait still gets its first read.

   `:force?` is for an explicit refresh after a transfer; it bypasses
   freshness, the surface check, the loading guard and the receipt-wait
   pause. The poller never forces, fast-poll ticks included."
  [state now-ms {:keys [force?]}]
  (let [addresses (balance-addresses state)
        fast? (fast-polling? state now-ms)
        freshness (if (or force? fast?) 0 normal-freshness-ms)
        paused? (and (not force?) (waiting-receipt? state now-ms))]
    (when (and (seq addresses)
               (seq (tokens/linked-tokens (get-in state [:spot :meta])))
               (not (backing-off? state now-ms))
               (or force? fast? (surface-active? state)))
      (let [due (filterv (fn [address]
                           (let [entry* (entry state address)]
                             (and (due? entry* now-ms freshness (true? force?))
                                  (not (and paused? (read? entry*))))))
                         addresses)]
        (when (seq due)
          {:addresses due :requested-at-ms now-ms})))))

;; --- projections ------------------------------------------------------------------

(defn- update-entries
  [state f]
  (update-in state [:hyperevm :balances :by-address] #(f (or % {}))))

(defn apply-loading
  "Mark the plan's addresses loading, keeping their last good values, and
   drop entries for addresses no longer shown."
  [state {:keys [addresses requested-at-ms]}]
  (let [kept (set (balance-addresses state))]
    (update-entries
     state
     (fn [by-address]
       (reduce (fn [acc address]
                 (update acc address #(assoc (or % {})
                                             :status :loading
                                             :requested-at-ms requested-at-ms)))
               (select-keys by-address kept)
               (filter kept addresses))))))

(defn- current-request?
  "Whether a reply for `address` answers the latest request for it. A reply
   for an address no longer shown, or overtaken by a newer request, is
   dropped, so a slow reply can never overwrite fresher data."
  [state address requested-at-ms]
  (and (some #{address} (balance-addresses state))
       (= requested-at-ms (:requested-at-ms (entry state address)))))

(defn- reset-backoff
  [state]
  (assoc-in state [:hyperevm :backoff] {:strikes 0 :until-ms nil}))

(defn apply-rate-limit
  "Back off after a rate limit: 30 s, then 60 s, then 120 s."
  [state now-ms]
  (let [strikes (inc (or (get-in state [:hyperevm :backoff :strikes]) 0))
        pause (nth rate-limit-backoff-ms
                   (min (dec strikes) (dec (count rate-limit-backoff-ms))))]
    (assoc-in state [:hyperevm :backoff] {:strikes strikes :until-ms (+ now-ms pause)})))

(defn apply-poll-backoff
  "Settle rate-limit backoff once per poll, because the limit is per IP, not
   per address. `outcomes` holds one of `:ok`, `:rate-limited` or `:error`
   per address read: any rate limit adds one strike, otherwise any success
   resets the backoff, otherwise nothing changes."
  [state now-ms outcomes]
  (cond
    (some #{:rate-limited} outcomes) (apply-rate-limit state now-ms)
    (some #{:ok} outcomes) (reset-backoff state)
    :else state))

(defn- never-read-indexes
  "The tokens of `chunk-unread` (a read's `:unread-token-indexes`, whole
   chunks that went unread) that no read of `entry*` ever answered: all of
   them on a first read; later, those still never answered and those the
   previous read did not ask about."
  [entry* chunk-unread]
  (let [unread (set chunk-unread)]
    (if-not (read? entry*)
      unread
      (let [asked-before (set (:token-indexes entry*))
            never-before (set (:never-read-token-indexes entry*))]
        (into #{}
              (filter #(or (contains? never-before %)
                           (not (contains? asked-before %))))
              unread)))))

(defn apply-success
  "Store a `read-balances!` result for `address`. `queried-indexes` are the
   tokens the read covered. A token whose chunk went unread
   (`:unread-token-indexes`) or whose own call failed
   (`:failed-token-indexes`) is unknown: it keeps its previous value, is
   never zeroed, and is listed in the entry's `:unread-token-indexes`. The
   chunk-unread ones no read ever answered are also kept apart, in
   `:never-read-token-indexes` (see `partial-read?`).
   Backoff is left to `apply-poll-backoff`."
  [state address requested-at-ms result queried-indexes now-ms]
  (if-not (current-request? state address requested-at-ms)
    state
    (update-entries
     state
     (fn [by-address]
       (update by-address address
               (fn [entry*]
                 (let [unread (into [] (distinct) (concat (:unread-token-indexes result)
                                                          (:failed-token-indexes result)))]
                   (assoc entry*
                          :status :ready
                          :stale? false
                          :loaded-at-ms now-ms
                          :native-wei (:native-wei result)
                          :token-units (merge (or (:token-units result) {})
                                              (select-keys (:token-units entry*) unread))
                          :token-indexes (set queried-indexes)
                          :unread-token-indexes unread
                          :never-read-token-indexes (never-read-indexes
                                                     entry*
                                                     (:unread-token-indexes result))
                          :gas-price-wei (:gas-price-wei result)
                          :error nil
                          :error-kind nil))))))))

(defn apply-error
  "Record a failed read for `address`, keeping its last good values (then
   marked `:stale?`). `error` is `{:message :kind}`."
  [state address requested-at-ms {:keys [message kind]}]
  (if-not (current-request? state address requested-at-ms)
    state
    (update-entries
     state
     (fn [by-address]
       (update by-address address
               (fn [entry*]
                 (assoc entry*
                        :status :error
                        :stale? (read? entry*)
                        :error (or message "HyperEVM balances are unavailable right now.")
                        :error-kind kind)))))))

(defn apply-core-account-role
  "Record whether `owner` has a HyperCore account, from a `userRole`
   response: `{:role \"missing\"}` is `:missing`, any other role `:active`."
  [state owner response]
  (let [owner* (account-context/normalize-address owner)
        role (some-> (when (map? response) (:role response)) str str/lower-case)]
    (cond
      (or (nil? owner*) (str/blank? role)) state
      (= "missing" role) (assoc-in state [:hyperevm :core-account owner*] :missing)
      :else (assoc-in state [:hyperevm :core-account owner*] :active))))

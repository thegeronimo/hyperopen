(ns hyperopen.hyperevm.panel-slice
  "The part of `[:hyperevm]` a memoized account panel reads, projected so a
   balance poll that changes nothing the panel shows compares equal.

   `/trade` renders its account panel from a `select-keys` slice of app state
   compared with `=` (see `hyperopen.views.trade-view`). Putting the raw
   `:hyperevm` subtree in that slice would repaint the panel on every poll:
   each read rewrites `:requested-at-ms`, flips `:status` to `:loading` and
   back, and carries a new gas price and new bridge balances. None of that
   changes a Balances row.

   The projection keeps the real state shape, but it is lossy: only the
   readers the Balances tab uses give the same answers on the slice as on
   the full state. Those are, for the shown account, `balances/entry`,
   `balances/token-amount-text` and `balances/native-hype-text`,
   `fees/native-max-reserve-hype` of the entry's gas price,
   `bridge/token-health-status`, and whether `bridge/core->evm-capacity` is
   \"0\" (plus the Balances-tab projections built on them).

   NOT valid on the slice: `balances/evm-gas-status` (the gas price is
   dropped at normal prices, so a low balance reads `:ok`), a positive
   `bridge/core->evm-capacity` (nil here), `bridge/evm->core-capacity`
   (always nil: `:core-system-balances` is dropped), and anything reading
   `:in-flight`, `:core-account`, the owner's own entry while another
   account is shown, or a loading entry's timestamps. Anything that needs
   those (the Trade panel's HyperEVM line, the Portfolio strip) derives from
   the full state or its own projection, never from this slice.

   What the slice keeps:

   - The shown account's entry keeps its amounts and the read's coverage
     (`:token-indexes`, `:unread-token-indexes`, `:never-read-token-indexes`,
     as sets). Its `:status`
     collapses to `:ready` once any read landed, or `:error` when the first
     read failed (also while a retry of it runs); an entry still loading
     its first read is left out.
     Its gas price is kept only when it lifts the native-HYPE gas reserve
     above the reserve's floor, the one thing the table derives from it.
   - Bridge health is kept as is (it settles after the first read).
   - Only the HyperEVM system balances whose Core -> EVM capacity is zero
     are kept: the table asks whether a token's bridge side is empty, never
     how much it holds, and busy tokens' pools move on every poll."
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.hyperevm.domain.balances :as balances]
            [hyperopen.hyperevm.domain.bridge :as bridge]
            [hyperopen.hyperevm.domain.fees :as fees]
            [hyperopen.hyperevm.domain.tokens :as tokens]))

(defn- read?
  [entry]
  (string? (:native-wei entry)))

(defn- projected-status
  [entry]
  (cond
    (read? entry) :ready
    ;; A retry of a failed first read keeps reading as failed.
    (balances/first-read-failed? entry) :error
    :else :loading))

(defn- reserve-gas-price
  "`gas-price-wei` when it changes the native-HYPE reserve, else nil (the
   reserve of an unknown gas price is the floor)."
  [gas-price-wei]
  (when (not= (fees/native-max-reserve-hype nil)
              (fees/native-max-reserve-hype gas-price-wei))
    gas-price-wei))

(defn- projected-entry
  [entry]
  (cond-> {:status (projected-status entry)}
    (read? entry)
    (assoc :native-wei (:native-wei entry)
           :token-units (or (:token-units entry) {})
           :token-indexes (set (:token-indexes entry))
           :unread-token-indexes (set (:unread-token-indexes entry))
           ;; Read partial (`balances/partial-read?`) only while non-empty.
           :never-read-token-indexes (set (:never-read-token-indexes entry))
           :gas-price-wei (reserve-gas-price (:gas-price-wei entry)))))

(defonce ^:private empty-units-cache
  (atom nil))

(defn- empty-bridge-units
  "The `:evm-system-units` entries whose Core -> EVM capacity is zero,
   memoized on the identity of the units map and spotMeta."
  [state]
  (let [units (get-in state [:hyperevm :bridge :evm-system-units])
        spot-meta (get-in state [:spot :meta])
        cached @empty-units-cache]
    (if (and cached
             (identical? units (:units cached))
             (identical? spot-meta (:spot-meta cached)))
      (:result cached)
      (let [result (into {}
                         (filter (fn [[index _]]
                                   (= "0" (bridge/core->evm-capacity
                                           state
                                           (tokens/token-by-index spot-meta index)))))
                         units)]
        (reset! empty-units-cache {:units units :spot-meta spot-meta :result result})
        result))))

(defn balances-panel-slice
  "`{:hyperevm …}` holding what the Balances tab reads for the shown account,
   to merge into a memoized panel's state slice."
  [state]
  (let [address (account-context/normalize-address (balances/display-address state))
        entry (balances/entry state address)]
    {:hyperevm {:balances {:by-address (if (and address
                                                entry
                                                ;; An entry still loading its
                                                ;; first read reads exactly
                                                ;; like no entry at all.
                                                (not= :loading (projected-status entry)))
                                         {address (projected-entry entry)}
                                         {})}
                :bridge {:token-health (or (get-in state [:hyperevm :bridge :token-health]) {})
                         :evm-system-units (empty-bridge-units state)}}}))

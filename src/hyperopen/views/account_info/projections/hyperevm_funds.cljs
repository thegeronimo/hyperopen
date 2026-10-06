(ns hyperopen.views.account-info.projections.hyperevm-funds
  "What the shown account holds on HyperEVM, summed into one figure for the
   Portfolio \"Where your funds are\" strip and the /trade Account Equity
   HyperEVM line.

   It is built from the Balances tab's own HyperEVM rows
   (`balances-hyperevm/hyperevm-rows`, priced against the same HyperCore
   rows), so the table, the strip and the line always agree on what a token
   is worth. It reads the full app state, never the /trade panel slice
   (`hyperopen.hyperevm.panel-slice`), which drops the gas price the gas
   status needs.

   HyperEVM funds cannot margin anything. They feed classic Account Equity's
   presentation-only Total Account Value, never trading equity, the shared
   balance-row memo, or the Portfolio summary's Total Equity.

   Unknown is never zero. Until the shown account's first HyperEVM read
   lands, when it failed, or while some token has never been answered (a
   whole chunk of the read went unread), `:usd` is nil and `:status` says
   why; a token with no price counts as held but adds nothing to `:usd`,
   the way an unpriced HyperCore spot token adds nothing to spot equity."
  (:require [hyperopen.domain.token-pricing :as token-pricing]
            [hyperopen.hyperevm.domain.balances :as hyperevm-balances]
            [hyperopen.views.account-info.derived-cache :as derived-cache]
            [hyperopen.views.account-info.projections.balances-hyperevm :as balances-hyperevm]))

(defn core-balance-rows
  "The shown account's HyperCore Balances rows, from the shared memo with
   exactly the arguments the Balances tab passes, so a render that shows
   both builds them once."
  [state]
  (derived-cache/memoized-balance-rows (:webdata2 state)
                                       (:spot state)
                                       (:account state)
                                       (get-in state [:asset-selector :market-by-key] {})
                                       (:perp-dex-clearinghouse state)))

(defn- by-value-desc
  "Rows by USD value, largest first; unpriced rows last, then by coin."
  [rows]
  (sort-by (fn [row]
             [(if (number? (:usdc-value row)) 0 1)
              (- (or (:usdc-value row) 0))
              (str (:coin row))])
           rows))

(defn- rows-summary
  [rows]
  (let [priced (keep :usdc-value rows)]
    {:usd (reduce + 0 priced)
     :token-count (count rows)
     :unpriced-count (- (count rows) (count priced))
     :symbols (mapv :coin (by-value-desc rows))}))

(def ^:private unknown-summary
  {:usd nil
   :token-count nil
   :unpriced-count nil
   :symbols []
   :native-hype-text nil
   :gas-status nil})

(defn- funds-status
  "`hyperevm-status`, with a read that left some token never answered as
   `:partial`: its sum would be a partial figure."
  [state address]
  (let [status (balances-hyperevm/hyperevm-status state)]
    (if (and (= :ready status) (hyperevm-balances/partial-read? state address))
      :partial
      status)))

(defn- build-funds
  "`held-names` are `balances-hyperevm/held-token-names`: with none, the
   HyperCore rows (only used to price held tokens) are never built."
  [state address held-names]
  (if (nil? address)
    (assoc unknown-summary :status :none :address nil)
    (let [status (funds-status state address)]
      (if (= :ready status)
        (merge {:status :ready
                :address address
                :native-hype-text (hyperevm-balances/native-hype-text state address)
                :gas-status (hyperevm-balances/evm-gas-status state address)}
               (rows-summary (if (seq held-names)
                               (balances-hyperevm/hyperevm-rows state (core-balance-rows state))
                               [])))
        (assoc unknown-summary :status status :address address)))))

;; --- memo --------------------------------------------------------------------
;;
;; /trade asks on every render, and there the market catalogue changes on
;; every active-asset tick while the equity metrics price the HyperCore rows
;; against no catalogue at all. Rebuilding those rows on each tick (and
;; evicting the metrics' entry from the shared single-slot memo) would cost
;; a full `build-balance-rows` per tick for nothing shown. So the result is
;; reused while the shown read is the same and, when something is held,
;; while the HyperCore inputs are the same objects and the catalogue's
;; price inputs compare equal.

(defonce ^:private funds-cache
  (atom nil))

(defn reset-hyperevm-funds-cache!
  []
  (reset! funds-cache nil))

(defn- spot-markets
  "The catalogue's spot markets, reused while the catalogue is the same
   object."
  [cached market-by-key]
  (if (and (map? cached) (identical? market-by-key (:market-by-key cached)))
    (:spot-markets cached)
    (into [] (filter #(= :spot (:market-type %))) (vals market-by-key))))

(defn- price-key
  "What the held tokens' USD values can read from the catalogue, compared
   by value: every spot market (the HyperCore Spot rows price from spot
   markets only, and so does a held token's own USDC market) and the perp
   market a held token's bare name resolves to before its spot one
   (`resolve-market-by-coin` tries `perp:NAME` first). A tick of any other
   market, such as the active perp's mark, leaves it equal."
  [spot-markets* market-by-key held-names]
  [spot-markets*
   (mapv #(get market-by-key (str "perp:" (token-pricing/normalized-token-name %)))
         held-names)])

(defn- core-inputs
  [state]
  [(:webdata2 state) (:spot state) (:account state) (:perp-dex-clearinghouse state)])

(defn- same-objects?
  [xs ys]
  (and (= (count xs) (count ys))
       (every? true? (map identical? xs ys))))

(defn hyperevm-funds
  "`{:status :address :usd :token-count :unpriced-count :symbols
     :native-hype-text :gas-status}` for the account the UI shows.

   - `:status` is `:ready` once any HyperEVM read of the account landed
     (later reads refresh it in place), `:loading` before that,
     `:unavailable` when the first read failed (and while it is retried),
     `:partial` when a read landed but some token's whole chunk was never
     answered, and `:none` when no account is shown.
   - `:usd` is the priced rows' USD sum, nil unless `:ready`.
   - `:symbols` lists the held tokens, largest USD value first.
   - `:gas-status` is `:ok`, `:low`, `:none` or nil (unknown) for one native
     HYPE transfer at the read's gas price.

   Memoized (see above), so the /trade view can ask on every render."
  [state]
  (let [address (hyperevm-balances/display-address state)
        entry (hyperevm-balances/entry state address)
        spot-meta (get-in state [:spot :meta])
        cached @funds-cache
        same-read? (and (map? cached)
                        (= address (:address cached))
                        (identical? entry (:entry cached))
                        (identical? spot-meta (:spot-meta cached)))
        held-names (if same-read?
                     (:held-names cached)
                     (balances-hyperevm/held-token-names state))
        market-by-key (get-in state [:asset-selector :market-by-key] {})
        priced? (boolean (seq held-names))
        spot-markets* (when priced? (spot-markets cached market-by-key))
        price-key* (when priced? (price-key spot-markets* market-by-key held-names))
        core-inputs* (when priced? (core-inputs state))
        reuse? (and same-read?
                    (or (not priced?)
                        (and (same-objects? core-inputs* (:core-inputs cached))
                             (= price-key* (:price-key cached)))))
        result (if reuse?
                 (:result cached)
                 (build-funds state address held-names))]
    (reset! funds-cache {:address address
                         :entry entry
                         :spot-meta spot-meta
                         :held-names held-names
                         ;; Kept as a pair: `spot-markets` reuses the list
                         ;; only for the catalogue it was taken from.
                         :market-by-key (if priced? market-by-key (:market-by-key cached))
                         :spot-markets (if priced? spot-markets* (:spot-markets cached))
                         :price-key price-key*
                         :core-inputs core-inputs*
                         :result result})
    result))

(ns hyperopen.views.account-info.projections.balances-hyperevm
  "HyperEVM balance rows for the Balances tab.

   The shown account's HyperEVM balances (`hyperopen.hyperevm.domain.balances`)
   become rows shaped like the HyperCore ones, with `:location :hyperevm`.
   They are appended in the Balances-tab view-model only, never in
   `projections.balances/build-balance-rows` or its memo: those rows feed the
   trading-equity metrics, and HyperEVM funds cannot margin anything.

   Amounts come from the domain readers, so a token the latest read could not
   answer and never answered before is unknown and has no row, while one read
   earlier keeps its last value (stale-while-revalidate, as everywhere else).
   Before the first read lands, or when the first read failed, there are no
   rows at all and `hyperevm-status` says why."
  (:require [clojure.string :as str]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.domain.token-pricing :as token-pricing]
            [hyperopen.funding.domain.transfer-balances :as transfer-balances]
            [hyperopen.hyperevm.domain.balances :as hyperevm-balances]
            [hyperopen.hyperevm.domain.chain :as chain]
            [hyperopen.hyperevm.domain.fees :as fees]
            [hyperopen.hyperevm.domain.tokens :as tokens]))

(def location-filters
  "`[:account-info :balances-location-filter]` values."
  [:all :hypercore :hyperevm])

(defn normalize-location-filter
  [value]
  (if (some #{value} location-filters) value :all))

(defn hyperevm-row?
  [row]
  (= :hyperevm (:location row)))

(defn unpriced-row?
  "A HyperEVM row whose USD value is unknown (no price for the token)."
  [row]
  (and (hyperevm-row? row) (nil? (:usdc-value row))))

(defn row-location
  "`:hyperevm`, `:perps` or `:spot` for a Balances row. HyperCore rows carry
   no `:location`; perps rows are recognised by their `perps-usdc` key."
  [row]
  (or (:location row)
      (if (str/starts-with? (str (:key row)) "perps-usdc")
        :perps
        :spot)))

(defn filter-rows-by-location
  "Rows shown under a location filter (`:all`, `:hypercore` or `:hyperevm`)."
  [rows location-filter]
  (case (normalize-location-filter location-filter)
    :hypercore (filterv (complement hyperevm-row?) rows)
    :hyperevm (filterv hyperevm-row? rows)
    (vec rows)))

;; --- held tokens -------------------------------------------------------------

(defn- display-address
  [state]
  (hyperevm-balances/display-address state))

(defn- held-tokens
  "`[[token amount-text] …]` for every linked token `address` holds a known,
   positive amount of on HyperEVM: native HYPE first, then by token index.
   Only the indexes the read returned a balance for are looked at, so this
   does not walk the whole linked-token catalog on every render."
  [state address]
  (let [spot-meta (get-in state [:spot :meta])
        entry (hyperevm-balances/entry state address)
        hype (tokens/token-by-name spot-meta "HYPE")
        candidates (cond->> (keep #(tokens/token-by-index spot-meta %)
                                  (sort (keys (:token-units entry))))
                     hype (cons hype))]
    (->> candidates
         (reduce (fn [[seen acc] token]
                   (if (contains? seen (:index token))
                     [seen acc]
                     (let [amount (hyperevm-balances/token-amount-text state address token)]
                       [(conj seen (:index token))
                        (if (transfer-balances/positive-text? amount)
                          (conj acc [token amount])
                          acc)])))
                 [#{} []])
         second)))

(defn hyperevm-status
  "`:ready` once the shown account's HyperEVM balances were read at least
   once, `:partial` while that read left some token never answered (its
   rows are unknown, not absent), `:unavailable` when the first read failed
   (and while a retry of it runs, so the copy does not flip on every
   retry), else `:loading`. With no account shown there is nothing to wait
   for, so it is `:ready` (and empty)."
  [state]
  (if-let [address (display-address state)]
    (let [entry (hyperevm-balances/entry state address)]
      (cond
        (string? (:native-wei entry)) (if (hyperevm-balances/partial-read? state address)
                                        :partial
                                        :ready)
        (hyperevm-balances/first-read-failed? entry) :unavailable
        :else :loading))
    :ready))

(defn hyperevm-row-count
  "How many HyperEVM rows `hyperevm-rows` gives, without pricing them."
  [state]
  (if-let [address (display-address state)]
    (count (held-tokens state address))
    0))

(defn held-token-names
  "The token names of the rows `hyperevm-rows` gives, in the same order,
   without pricing them."
  [state]
  (if-let [address (display-address state)]
    (mapv (comp :name first) (held-tokens state address))
    []))

;; --- rows ------------------------------------------------------------------------

(defn- native-gas-reserve
  "HYPE kept on HyperEVM for gas by a move to Core: the same reserve the
   Transfer modal's MAX holds back."
  [state address]
  (fees/native-max-reserve-hype
   (:gas-price-wei (hyperevm-balances/entry state address))))

(defn- amount-number
  [text]
  (let [value (js/parseFloat text)]
    (if (js/isNaN value) 0 value)))

(defn- evm-row
  [state address price-rows market-by-key [token amount]]
  (let [name* (:name token)
        native? (= :native (:kind token))
        reserve (when native? (native-gas-reserve state address))
        kept (when native? (transfer-balances/min-text amount reserve))
        available (if native?
                    (or (transfer-balances/sub-text amount reserve) "0")
                    amount)
        total (amount-number amount)
        price (token-pricing/token-price-usd price-rows market-by-key name*)
        market-coin (when-not (= :usdc-cdw (:kind token))
                      (:coin (markets/resolve-spot-market-by-coin market-by-key name*)))
        contract (:erc20-address token)]
    (cond-> {:key (str "hyperevm-" (:index token))
             :location :hyperevm
             :selection-coin name*
             :coin name*
             :token (:index token)
             :total-balance total
             :available-balance (amount-number available)
             :total-text amount
             :available-text available
             :usdc-value (when (number? price) (* total price))
             :pnl-value nil
             :pnl-pct nil
             ;; Shown like the token's HyperCore Spot row (weiDecimals),
             ;; so a HyperEVM USDC row lines up with the Spot one instead
             ;; of showing its 6 on-chain decimals. The Transfer modal keeps
             ;; its own route precision.
             :amount-decimals (:wei-decimals token)
             :contract-id nil
             :evm-native? native?
             :evm-contract contract
             :evm-contract-url (some-> contract chain/explorer-token-url)}
      (seq market-coin) (assoc :market-coin market-coin)
      (transfer-balances/positive-text? kept) (assoc :gas-reserve-text kept))))

(defn hyperevm-rows
  "Balances rows for what the shown account holds on HyperEVM, native HYPE
   first. `core-rows` are the HyperCore rows, used only to price a token the
   wallet also holds on HyperCore the same way (USD values fall back to the
   token's USDC spot market). Empty until the account's first read lands."
  [state core-rows]
  (if-let [address (display-address state)]
    (let [market-by-key (get-in state [:asset-selector :market-by-key] {})
          price-rows (token-pricing/balance-rows-by-token core-rows)]
      (mapv #(evm-row state address price-rows market-by-key %)
            (held-tokens state address)))
    []))

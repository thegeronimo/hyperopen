(ns hyperopen.views.portfolio.funds-locations.model
  "The model behind the Portfolio \"Where your funds are\" strip
   (`hyperopen.views.portfolio.funds-locations`).

   Total value is the Portfolio summary's Total Equity plus what the shown
   account holds on HyperEVM. It is the only figure that adds HyperEVM:
   HyperEVM funds cannot margin a position, so Total Equity, the equity
   metrics and the Monte Carlo start equity stay HyperCore-only. The total
   card names the HyperCore part by the summary card's own label, \"Total
   Equity\", and says how much of it sits in vaults and Earn, which have no
   card here, so the cards and the total reconcile.

   Unknown is never zero, on either ledger. The summary reports 0 for every
   HyperCore figure until the shown account's snapshot loads (and the
   account lifecycle clears it on every identity change), so until the
   Perps clearinghouse state and the Spot balances are both there the
   HyperCore cards read \"Loading…\" and the total \"—\". The same goes for
   HyperEVM until its first complete read. A partial figure is never shown
   as the total value.

   The model reads the full app state; the HyperEVM figures come from
   `projections.hyperevm-funds`, the same rows the Balances table shows."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.funding.domain.transfer-balances :as transfer-balances]
            [hyperopen.hyperevm.domain.units :as units]
            [hyperopen.views.account-info.derived-cache :as derived-cache]
            [hyperopen.views.account-info.projections.hyperevm-funds :as hyperevm-funds]
            [hyperopen.views.portfolio.format :as portfolio-format]
            [hyperopen.views.portfolio.vm.equity :as vm-equity]))

(def perps-spot-data-role
  "portfolio-funds-connector-perps-spot")

(def spot-evm-data-role
  "portfolio-funds-connector-spot-evm")

(def ^:private unknown-text
  "—")

(def ^:private loading-text
  "Loading…")

(def ^:private listed-symbols
  "How many token names a card lists before \"+N\"."
  3)

(def ^:private min-shown-usd
  "The smallest USD amount that rounds to $0.01."
  0.005)

;; --- readiness ---------------------------------------------------------------

(defn hypercore-status
  "`:ready` once the shown account's HyperCore snapshot is loaded (its
   Perps clearinghouse state and its Spot balances), `:unavailable` when
   the Spot read failed with nothing loaded, else `:loading`."
  [state]
  (let [perps? (map? (get-in state [:webdata2 :clearinghouseState]))
        spot? (map? (get-in state [:spot :clearinghouse-state]))]
    (cond
      (and perps? spot?) :ready
      (and (not spot?) (some? (get-in state [:spot :error]))) :unavailable
      :else :loading)))

;; --- cards -------------------------------------------------------------------

(defn- token-count-text
  [n]
  (cond
    (not (and (number? n) (pos? n))) "No tokens"
    (= 1 n) "1 token"
    :else (str n " tokens")))

(defn- tokens-detail
  "\"3 tokens · USDC, HYPE, PURR\", \"1 token · HYPE\", or \"No tokens\"."
  [symbols]
  (let [n (count symbols)]
    (if (pos? n)
      (str (token-count-text n)
           " · "
           (str/join ", " (take listed-symbols symbols))
           (when (> n listed-symbols)
             (str " +" (- n listed-symbols))))
      (token-count-text 0))))

(defn- positions-text
  [n]
  (cond
    (not (number? n)) nil
    (zero? n) "no open positions"
    (= 1 n) "1 position"
    :else (str n " positions")))

(defn- spot-rows
  "The shown account's HyperCore Spot rows holding a positive amount,
   largest USD value first."
  [core-rows]
  (->> core-rows
       (filter (fn [row]
                 (and (str/starts-with? (str (:key row)) "spot-")
                      (number? (:total-balance row))
                      (pos? (:total-balance row)))))
       (sort-by (fn [row] (- (or (:usdc-value row) 0))))))

(defn- core-card
  "A HyperCore card: `value-text` and `detail` once the snapshot is
   loaded, else the reason it is not."
  [core-status value-text detail]
  (case core-status
    :ready {:value-text value-text :detail detail}
    :unavailable {:value-text unknown-text
                  :detail "HyperCore balances are unavailable right now."}
    {:value-text loading-text
     :detail "Checking HyperCore balances…"}))

(defn- hype-text
  "A HYPE amount for the gas line: two decimals from 1 HYPE up, else up to
   six, floored so it never reads more than the wallet holds."
  [text]
  (when (string? text)
    (let [decimals (if (= -1 (units/compare-amounts text "1" 18)) 6 2)]
      (transfer-balances/display-amount (units/floor-to-decimals text decimals)))))

(defn- gas-model
  [{:keys [gas-status native-hype-text]}]
  (let [hype (hype-text native-hype-text)]
    (case gas-status
      :ok {:tone :ok :text (str "gas: " hype " HYPE")}
      :low {:tone :warn :text (str "Low gas: " hype " HYPE")}
      :none {:tone :warn :text "No HYPE for gas"}
      nil)))

(defn- evm-card
  [{:keys [status usd token-count unpriced-count] :as funds}]
  (case status
    :ready (let [unpriced? (and (number? unpriced-count) (pos? unpriced-count))]
             ;; Only unpriced tokens (and dust): their value is unknown, not
             ;; $0.00.
             {:value-text (if (and unpriced? (< usd min-shown-usd))
                            "Unpriced"
                            (portfolio-format/format-currency usd))
              ;; A token with no price adds nothing to the value; say so.
              :detail (str (token-count-text token-count)
                           (when unpriced?
                             (str ", " unpriced-count " unpriced")))
              ;; An empty wallet needs no gas warning.
              :gas (when (or (pos? token-count)
                             (not= :none (:gas-status funds)))
                     (gas-model funds))})
    :unavailable {:value-text unknown-text
                  :detail "HyperEVM balances are unavailable right now."
                  :gas nil}
    :partial {:value-text unknown-text
              :detail "Some HyperEVM balances are unavailable right now."
              :gas nil}
    {:value-text loading-text
     :detail "Checking HyperEVM balances…"
     :gas nil}))

;; --- total -------------------------------------------------------------------

(defn- unknown-total
  "`[status-text tone]` saying why the total is unknown, or nil when both
   ledgers are known. A failure reads in the warning tone; loading, which
   happens on every page load, does not."
  [core-status evm-status]
  (cond
    (= :unavailable evm-status) ["HyperEVM balances unavailable" :warn]
    (= :partial evm-status) ["Some HyperEVM balances unavailable" :warn]
    (= :unavailable core-status) ["HyperCore balances unavailable" :warn]
    (and (not= :ready core-status) (not= :ready evm-status)) ["Balances loading" :muted]
    (not= :ready core-status) ["HyperCore balances loading" :muted]
    (not= :ready evm-status) ["HyperEVM balances loading" :muted]
    :else nil))

(defn- elsewhere-text
  "\"Includes $Y in vaults and Earn\": what Total Equity holds beyond the
   Perps and Spot cards (a unified account's Total Equity leaves Earn out),
   or nil when that is under a cent."
  [summary unified?]
  (let [amount (fn [value] (if (number? value) value 0))
        vault (amount (:vault-equity summary))
        earn (if unified? 0 (amount (:earn-balance summary)))
        parts (cond-> []
                (>= vault min-shown-usd) (conj "vaults")
                (>= earn min-shown-usd) (conj "Earn"))]
    (when (seq parts)
      (str "Includes "
           (portfolio-format/format-currency (+ vault earn))
           " in "
           (str/join " and " parts)))))

(defn- total-card
  [summary unified? core-status {:keys [status usd]}]
  (if-let [[status-text tone] (unknown-total core-status status)]
    {:value-text unknown-text
     :known? false
     :status-text status-text
     :tone tone
     :includes-text nil}
    (let [total-equity (or (:total-equity summary) 0)]
      {:value-text (portfolio-format/format-currency (+ total-equity usd))
       :known? true
       ;; The summary card's own label for the same figure.
       :status-text (str "Total Equity " (portfolio-format/format-currency total-equity))
       :tone :muted
       :includes-text (elsewhere-text summary unified?)})))

;; --- connectors --------------------------------------------------------------

(defn- connector
  [id data-role aria-label context reason]
  {:id id
   :data-role data-role
   :aria-label aria-label
   :action [:actions/open-funding-transfer-modal
            :event.currentTarget/bounds
            data-role
            context]
   :disabled? (some? reason)
   :reason reason})

(defn- with-reason-ids
  "Give each distinct reason one id; connectors sharing a reason point at
   the same visible line. Returns `[connectors reasons]`."
  [connectors]
  (let [reasons (->> connectors (keep :reason) distinct vec)
        id-of (into {} (map-indexed (fn [i reason] [reason (str "portfolio-funds-reason-" i)]) reasons))]
    [(mapv (fn [c] (assoc c :reason-id (get id-of (:reason c)))) connectors)
     (mapv (fn [reason] {:id (get id-of reason) :text reason}) reasons)]))

(defn funds-locations-model
  "The strip for the account the Portfolio page shows. `summary` is the
   portfolio view-model's `:summary` (`:total-equity`,
   `:perps-account-equity`, `:spot-account-equity`, `:vault-equity`,
   `:earn-balance`)."
  [state summary]
  (let [address (account-context/effective-account-address state)
        unified? (vm-equity/top-up-abstraction-enabled? state)
        core-status (hypercore-status state)
        funds (hyperevm-funds/hyperevm-funds state)
        core-rows (when (and address (= :ready core-status))
                    (hyperevm-funds/core-balance-rows state))
        positions (when (and address (= :ready core-status))
                    (count (derived-cache/memoized-positions (:webdata2 state)
                                                             (:perp-dex-clearinghouse state))))
        spot-equity (:spot-account-equity summary)
        mutations-reason (account-context/mutations-blocked-message state)
        evm-reason (account-context/hyperevm-moves-blocked-message state)
        [connectors reasons]
        (with-reason-ids
          (cond-> []
            (not unified?)
            (conj (connector :perps-spot perps-spot-data-role
                             "Transfer between Perps and Spot"
                             {:from :perps :to :spot}
                             mutations-reason))
            true
            (conj (connector :spot-evm spot-evm-data-role
                             "Transfer between Spot and HyperEVM"
                             {:from :spot :to :hyperevm}
                             evm-reason))))]
    {:visible? (some? address)
     :unified? unified?
     :total (total-card summary unified? core-status funds)
     :perps (core-card core-status
                       (portfolio-format/format-currency (:perps-account-equity summary))
                       (str/join " · " (remove nil? ["USDC collateral" (positions-text positions)])))
     :spot (core-card core-status
                      (portfolio-format/format-currency spot-equity)
                      (tokens-detail (mapv #(or (:selection-coin %) (:coin %))
                                           (spot-rows core-rows))))
     :trading (core-card core-status
                         (portfolio-format/format-currency spot-equity)
                         (str/join " · " (remove nil? ["Perps and Spot share one balance"
                                                        (positions-text positions)])))
     :hyperevm (evm-card funds)
     :connectors connectors
     :reasons reasons
     :focus-request {:data-role (get-in state [:funding-ui :modal :focus-return-data-role])
                     :token (get-in state [:funding-ui :modal :focus-return-token] 0)}}))

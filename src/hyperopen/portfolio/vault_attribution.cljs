(ns hyperopen.portfolio.vault-attribution
  "Vault attribution: the returns of the account's Hyperliquid vault deposits
   considered alone (the \"vault sleeve\"), across every vault ever held,
   closed positions as well as open ones.

   Money is exact ledger dollars: a vault's P&L is current equity plus net
   withdrawals minus deposits. The vault share-price index only shapes the
   path between transfers. See `vault-attribution.series` for the series
   model and `vault-attribution.model` for the building blocks."
  (:require [hyperopen.portfolio.vault-attribution.model :as model]
            [hyperopen.portfolio.vault-attribution.series :as series]))

(defn- sum-by
  [f coll]
  (reduce + 0 (keep f coll)))

(defn- vault-model
  "Everything known about one vault before the sleeve is assembled.

   Equity is KNOWN (a number, zero when the account holds nothing) unless the
   address is in `equity-unknown`. An unknown equity still reads as a known
   zero when the ledger itself shows the position fully withdrawn; otherwise
   the vault's value is unknown and it is left out of every total."
  [ledger-rows equity-by-vault equity-unknown vaults end-ms address]
  (let [unknown? (contains? equity-unknown address)
        equity (when-not unknown? (or (model/number-in (get equity-by-vault address)) 0))
        raw-transfers (model/vault-transfers ledger-rows address)
        {:keys [transfers periods open-at-end? leading-withdrawal?]}
        (model/holding-periods raw-transfers
                               (when (number? equity) (if (> equity model/epsilon) equity 0))
                               end-ms)
        equity-known? (or (not unknown?) (not open-at-end?))
        value (cond
                (not equity-known?) nil
                (and (number? equity) (> equity model/epsilon)) equity
                :else 0)
        open? (if equity-known? (pos? value) true)
        deposits (filter #(= :deposit (:kind %)) raw-transfers)
        info (get vaults address)]
    {:address address
     :name (when (map? info) (:name info))
     :equity value
     :equity-known? equity-known?
     :open? open?
     :raw-transfers raw-transfers
     :transfers transfers
     :periods periods
     :deposited (sum-by :amount deposits)
     :withdrawn (sum-by :amount (filter #(= :withdraw (:kind %)) raw-transfers))
     :realized (sum-by :realized (filter #(= :withdraw (:kind %)) transfers))
     :flows-complete? (boolean (and (seq deposits)
                                    equity-known?
                                    (not leading-withdrawal?)
                                    (or (not open?) open-at-end?)))
     :index (model/merged-index (when (map? info) (:returns-candidates info)))}))

(defn- held-ms
  [periods]
  (sum-by (fn [{:keys [start-ms end-ms]}] (max 0 (- end-ms start-ms))) periods))

(defn- dated-flows
  "Dated flows for IRR: deposits out of pocket, withdrawals and the current
   value back."
  [transfers terminal now-ms]
  (cond-> (mapv (fn [{:keys [kind time-ms amount]}]
                  [time-ms (if (= :deposit kind) (- amount) amount)])
                transfers)
    (and (number? terminal) (pos? terminal)) (conj [now-ms terminal])))

(defn- vault-row
  "`end-ms` is the sleeve's end, so a one-vault account's row and total read
   the same time-weighted return."
  [{:keys [address name open? equity equity-known? raw-transfers periods deposited
           withdrawn realized flows-complete?] :as vault}
   now-ms end-ms]
  (let [pnl (when flows-complete? (model/finite (- (+ equity withdrawn) deposited)))
        realized (when flows-complete? (model/finite (if open? realized pnl)))
        held (held-ms periods)
        first-deposit (some #(when (= :deposit (:kind %)) (:time-ms %)) raw-transfers)]
    {:vault-address address
     :name name
     :status (if open? :open :closed)
     :deposited deposited
     :withdrawn withdrawn
     :net-deposited (- deposited withdrawn)
     :value equity
     :equity-known? equity-known?
     :pnl pnl
     :realized realized
     :unrealized (when (and (number? pnl) (number? realized))
                   (- ^number pnl ^number realized))
     :money-weighted-return (when flows-complete?
                              (model/money-weighted-return
                               (dated-flows raw-transfers equity now-ms) held))
     :vault-return-while-held-pct (when flows-complete?
                                    (series/time-weighted-return-pct
                                     [(series/prepare-vault vault)] end-ms))
     :first-deposit-ms first-deposit
     :last-activity-ms (:time-ms (peek raw-transfers))
     :held-ms held
     :flows-complete? flows-complete?}))

(defn- sort-rows
  [rows]
  (let [{open-rows true closed-rows false} (group-by #(= :open (:status %)) rows)]
    (vec (concat (sort-by (comp - #(or % 0) :value) open-rows)
                 (sort-by (comp - #(or % 0) :last-activity-ms) closed-rows)))))

(defn- union-ms
  "Total length of the union of `[{:start-ms :end-ms}]` intervals."
  [periods]
  (:total
   (reduce (fn [{:keys [total end] :as acc} {:keys [start-ms end-ms]}]
             (cond
               (or (nil? end) (> start-ms end)) {:total (+ total (- end-ms start-ms)) :end end-ms}
               (> end-ms end) {:total (+ total (- end-ms end)) :end end-ms}
               :else acc))
           {:total 0 :end nil}
           (sort-by :start-ms periods))))

(defn- totals
  "Money totals are over ONE population: the vaults whose flows are complete
   (`:incomplete-count` says how many were left out). P&L figures are nil when
   no vault is complete, never zero. Counts are over every vault."
  [vault-models rows now-ms]
  (let [complete (filter :flows-complete? vault-models)
        complete-rows (filter :flows-complete? rows)
        any? (boolean (seq complete-rows))
        held (union-ms (mapcat :periods complete))
        deposited (sum-by :deposited complete-rows)
        withdrawn (sum-by :withdrawn complete-rows)
        pnl (when any? (sum-by :pnl complete-rows))
        realized (when any? (sum-by :realized complete-rows))
        terminal (sum-by :equity complete)
        first-deposits (keep :first-deposit-ms rows)]
    {:deposited deposited
     :withdrawn withdrawn
     :net-deposited (- deposited withdrawn)
     :value (sum-by :value complete-rows)
     :pnl pnl
     :realized realized
     :unrealized (when any? (- pnl realized))
     :money-weighted-return
     (when any?
       (model/money-weighted-return
        (dated-flows (mapcat :raw-transfers complete) terminal now-ms)
        held))
     :open-count (count (filter #(= :open (:status %)) rows))
     :closed-count (count (filter #(= :closed (:status %)) rows))
     :incomplete-count (count (remove :flows-complete? rows))
     :first-deposit-ms (when (seq first-deposits) (apply min first-deposits))}))

(def ^:private empty-totals
  {:deposited 0 :withdrawn 0 :net-deposited 0 :value 0 :pnl nil :realized nil
   :unrealized nil :money-weighted-return nil :open-count 0 :closed-count 0
   :incomplete-count 0 :first-deposit-ms nil})

(defn- markers
  "Transfers of the vaults drawn in the series (complete flows only)."
  [vault-models]
  (->> vault-models
       (filter :flows-complete?)
       (mapcat (fn [{:keys [address name raw-transfers]}]
                 (map (fn [{:keys [kind time-ms amount source]}]
                        (cond-> {:time-ms time-ms
                                 :kind kind
                                 :amount amount
                                 :vault-address address
                                 :name name}
                          source (assoc :source source)))
                      raw-transfers)))
       (sort-by :time-ms)
       vec))

(defn attribution
  "Vault sleeve read model. Inputs:
   - `:ledger-rows`     raw `userNonFundingLedgerUpdates` rows, any order
   - `:account-address` optional; the account the ledger belongs to, so rows
                        that are not its own vault transfers are dropped
   - `:equity-by-vault` USD equity per vault address, from the same snapshot
                        as the ledger; a vault absent from it holds nothing
   - `:equity-unknown`  optional set of vault addresses whose equity could not
                        be read (value unknown, never zero)
   - `:vaults`          `{address {:name s :returns-candidates [rows ...]}}`,
                        candidates `[[time-ms cumulative-pct] ...]` finest first
   - `:now-ms`"
  [{:keys [ledger-rows account-address equity-by-vault equity-unknown vaults now-ms]}]
  (let [now-ms (or (model/finite now-ms) (.now js/Date))
        ledger-rows (model/account-rows ledger-rows account-address)
        equity-by-vault (model/normalize-keys equity-by-vault)
        equity-unknown (into #{} (keep model/normalize-address) equity-unknown)
        vaults (model/normalize-keys vaults)
        addresses (into (model/ledger-vault-addresses ledger-rows)
                        (keep (fn [[address equity]]
                                (when (> (or (model/number-in equity) 0) model/epsilon)
                                  address)))
                        equity-by-vault)
        transfer-times (model/vault-transfer-times ledger-rows)
        end-ms (if (seq transfer-times)
                 (max now-ms (+ 2 (apply max transfer-times)))
                 now-ms)
        vault-models (->> addresses
                          (map #(vault-model ledger-rows equity-by-vault equity-unknown
                                             vaults end-ms %))
                          (filter #(or (seq (:raw-transfers %)) (:open? %)))
                          vec)]
    (if (empty? vault-models)
      {:status :empty
       :vaults []
       :totals empty-totals
       :series []
       :markers []
       :daily-from-ms nil
       :summary-entries {}}
      (let [rows (sort-rows (mapv #(vault-row % now-ms end-ms) vault-models))
            sleeve (mapv series/prepare-vault (filter :flows-complete? vault-models))
            points (if (seq sleeve)
                     (series/sleeve-series sleeve (series/build-slots sleeve end-ms))
                     [])]
        {:status :ready
         :vaults rows
         :totals (totals vault-models rows now-ms)
         :series points
         :markers (markers vault-models)
         :daily-from-ms (series/daily-from-ms sleeve now-ms)
         :summary-entries (series/summary-entries points now-ms)}))))

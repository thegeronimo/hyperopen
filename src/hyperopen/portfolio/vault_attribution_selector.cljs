(ns hyperopen.portfolio.vault-attribution-selector
  "Memoized view model for Portfolio vault attribution. Reads the data loaded
   by `:effects/api-fetch-portfolio-vault-attribution` and runs the pure
   `vault-attribution` model over it."
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.portfolio.vault-attribution :as attribution]
            [hyperopen.portfolio.vault-attribution-state :as attribution-state]
            [hyperopen.portfolio.vault-attribution.equity :as equity]
            [hyperopen.portfolio.vault-attribution.model :as model]
            [hyperopen.vaults.detail.performance :as performance]
            [hyperopen.vaults.detail.position :as position]
            [hyperopen.vaults.domain.identity :as vault-identity]))

(def ^:private summary-ranges
  [:day :week :month :all-time])

(defn- vault-input
  [details]
  {:name (:name details)
   :returns-candidates (mapv #(position/returns-rows-from-summary
                               (performance/portfolio-summary-by-range details %))
                             summary-ranges)})

(defn- orphans
  "`{vault equity}` for vaults the account holds per its trusted live rows but
   that its ledger never mentions (no details were loaded for them). They are
   shown with their value and counted as incomplete."
  [slot live address]
  (when (map? live)
    (let [ledger-vaults (model/ledger-vault-addresses
                         (model/account-rows (:ledger-rows slot) address))]
      (into {}
            (filter (fn [[v equity]]
                      (and (> equity model/epsilon)
                           (not (contains? ledger-vaults v))
                           (not= v address))))
            live))))

(defn- compute
  [{:keys [slot live-equity address state]}]
  (let [details-by-vault (:details-by-vault slot)
        {:keys [equity-by-vault equity-unknown]} (equity/equity-inputs slot live-equity)
        orphan-equity (orphans slot live-equity address)]
    (attribution/attribution
     {:ledger-rows (:ledger-rows slot)
      :account-address address
      :equity-by-vault (merge orphan-equity equity-by-vault)
      :equity-unknown equity-unknown
      :vaults (merge (into {}
                           (map (fn [v] [v {:name (vault-identity/vault-entity-name state v)}]))
                           (keys orphan-equity))
                     (into {} (map (fn [[vault details]] [vault (vault-input details)]))
                           details-by-vault))
      :now-ms (:loaded-at-ms slot)})))

(defn- build-model
  [{:keys [slot address] :as inputs}]
  (let [raw-status (cond
                     (nil? address) :none
                     (not= address (:for-address slot)) :idle
                     :else (or (:status slot) :idle))
        held? (number? (:loaded-at-ms slot))
        ;; Stale-while-revalidate: a refresh keeps the rows of the SAME address
        ;; (see `begin-load`), so the last good figures stay on screen. A
        ;; failed refresh keeps them too, with the error alongside.
        refreshing? (and (= :loading raw-status) held?)
        refresh-error (when (and (= :error raw-status) held?) (:error slot))
        load-status (if (or refreshing? refresh-error) :ready raw-status)
        ready? (= :ready load-status)
        ledger-partial? (boolean (and ready? (:ledger-partial? slot)))]
    {:load-status load-status
     :refreshing? (boolean refreshing?)
     :error (when (= :error load-status) (:error slot))
     :refresh-error refresh-error
     :ledger-partial? ledger-partial?
     :partial? (boolean (and ready? (or ledger-partial? (seq (:errors-by-vault slot)))))
     :attribution (when ready? (compute inputs))}))

(defonce ^:private model-cache (atom nil))

(defn model
  "`{:load-status :none|:idle|:loading|:ready|:error :error msg
     :refresh-error msg :partial? bool :ledger-partial? bool :refreshing? bool
     :attribution result-or-nil}`.
   `:refreshing?` is true while a reload runs over data already held for the
   shown account (`:load-status` stays `:ready`); `:refresh-error` is a failed
   reload over held data (also `:ready`). `:partial?` covers both failed vault
   details and a ledger cut at the page cap (`:ledger-partial?`). The whole
   map is memoized on the identity of its inputs, so unrelated state changes
   return the same object."
  [state]
  (let [slot (get-in state attribution-state/state-path)
        address (account-context/effective-account-address state)
        equity-rows (get-in state [:vaults :user-equities])
        equity-rows-for-address (get-in state [:vaults :user-equities-for-address])
        ;; Orphan vault names come from these.
        vault-rows (get-in state [:vaults :merged-index-rows])
        vault-details (get-in state [:vaults :details-by-address])
        cached @model-cache]
    (if (and cached
             (identical? slot (:slot cached))
             (identical? equity-rows (:equity-rows cached))
             (= equity-rows-for-address (:equity-rows-for-address cached))
             (identical? vault-rows (:vault-rows cached))
             (identical? vault-details (:vault-details cached))
             (= address (:address cached)))
      (:result cached)
      (let [inputs {:slot slot
                    :address address
                    :state state
                    :live-equity (equity/live-equity-by-vault state address)}
            result (build-model inputs)]
        (reset! model-cache {:slot slot
                             :equity-rows equity-rows
                             :equity-rows-for-address equity-rows-for-address
                             :vault-rows vault-rows
                             :vault-details vault-details
                             :address address
                             :result result})
        result))))

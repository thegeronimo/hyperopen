(ns hyperopen.vaults.application.route-loading
  (:require [clojure.string :as str]
            [hyperopen.vaults.application.detail-commands :as detail-commands]
            [hyperopen.vaults.domain.identity :as identity]))

(def ^:private projection-effect-ids
  #{:effects/save :effects/save-many})

(defn- projection-effect?
  [effect]
  (contains? projection-effect-ids (first effect)))

(defn- projection-first-effects
  [effects]
  (let [effects* (vec (or effects []))]
    (into []
          (concat (filter projection-effect? effects*)
                  (remove projection-effect? effects*)))))

(defn- load-vault-list-effects
  [state]
  (let [wallet-address (identity/vault-wallet-address state)]
    (cond-> [[:effects/save [:vaults-ui :list-loading?] true]
             [:effects/api-fetch-vault-index]
             [:effects/api-fetch-vault-summaries]]
      wallet-address
      (conj [:effects/api-fetch-user-vault-equities wallet-address]))))

(defn- component-vault-history-effects
  [state vault-address]
  (let [component-addresses (identity/component-vault-addresses state vault-address)]
    (->> component-addresses
         (mapcat (fn [address]
                   [[:effects/api-fetch-vault-fills address]
                    [:effects/api-fetch-vault-funding-history address]
                    [:effects/api-fetch-vault-order-history address]]))
         vec)))

(defn load-vaults
  [state]
  (load-vault-list-effects state))

(defn load-vault-detail
  [state vault-address]
  (if-let [vault-address* (identity/normalize-vault-address vault-address)]
    (let [snapshot-range (get-in state [:vaults-ui :snapshot-range])
          benchmark-fetch-effects (detail-commands/vault-detail-returns-benchmark-fetch-effects
                                   snapshot-range
                                   (detail-commands/selected-vault-detail-returns-benchmark-coins state))]
      (into [[:effects/save [:vaults-ui :detail-loading?] true]
             [:effects/save [:vaults-ui :detail-chart-hover-index] nil]
             [:effects/api-fetch-vault-details vault-address* (identity/vault-wallet-address state)]
             [:effects/api-fetch-vault-webdata2 vault-address*]
             [:effects/api-fetch-vault-fills vault-address*]
             [:effects/api-fetch-vault-funding-history vault-address*]
             [:effects/api-fetch-vault-order-history vault-address*]
             [:effects/api-fetch-vault-ledger-updates vault-address*]]
            (concat (component-vault-history-effects state vault-address*)
                    benchmark-fetch-effects)))
    []))

(defn load-vault-route
  [state route]
  (let [{:keys [kind vault-address path]} route]
    (case kind
      :list
      (load-vault-list-effects state)

      :detail
      (projection-first-effects
       (into (load-vault-list-effects state)
             (load-vault-detail state vault-address)))

      :other
      (if (str/starts-with? (or path "") "/portfolio")
        (load-vault-list-effects state)
        [])

      [])))

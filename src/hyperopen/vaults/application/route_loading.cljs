(ns hyperopen.vaults.application.route-loading
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.portfolio.actions :as portfolio-actions]
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
  (let [viewer-address (account-context/effective-account-address state)]
    (cond-> [[:effects/save [:vaults-ui :list-loading?] true]
             [:effects/api-fetch-vault-index]
             [:effects/api-fetch-vault-summaries]]
      viewer-address
      (conj [:effects/api-fetch-user-vault-equities viewer-address]))))

(def ^:private vault-list-effect-ids
  #{:effects/api-fetch-vault-index
    :effects/api-fetch-vault-summaries})

(defn- list-fetch-effect?
  [effect]
  (contains? vault-list-effect-ids (first effect)))

(defn- maybe-prepend-list-loading-effect
  [effects]
  (let [effects* (vec (or effects []))]
    (if (some list-fetch-effect? effects*)
      (into [[:effects/save [:vaults-ui :list-loading?] true]]
            effects*)
      effects*)))

(defn- user-vault-equity-effects
  [state]
  (if-let [viewer-address (account-context/effective-account-address state)]
    [[:effects/api-fetch-user-vault-equities viewer-address]]
    []))

(defn- detail-route-support-effects
  [state]
  (into []
        (concat (user-vault-equity-effects state)
                (detail-commands/ensure-vault-detail-vault-benchmark-effects state))))

(defn- portfolio-route-support-effects
  [state]
  (portfolio-actions/ensure-portfolio-vault-benchmark-effects state))

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
          viewer-address (account-context/effective-account-address state)
          benchmark-fetch-effects (detail-commands/vault-detail-returns-benchmark-fetch-effects
                                   snapshot-range
                                   (detail-commands/selected-vault-detail-returns-benchmark-coins state))]
      (into [[:effects/save [:vaults-ui :detail-loading?] true]
             [:effects/save [:vaults-ui :detail-chart-hover-index] nil]
             [:effects/api-fetch-vault-details vault-address* viewer-address]
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
       (into (maybe-prepend-list-loading-effect
              (detail-route-support-effects state))
             (load-vault-detail state vault-address)))

      :other
      (if (str/starts-with? (or path "") "/portfolio")
        (maybe-prepend-list-loading-effect
         (portfolio-route-support-effects state))
        [])

      [])))

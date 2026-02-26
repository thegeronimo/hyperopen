(ns hyperopen.api.projections
  (:require [clojure.string :as str]
            [hyperopen.api.errors :as api-errors]
            [hyperopen.api.market-metadata.perp-dexs :as perp-dexs]))

(defn- normalized-error
  [err]
  (api-errors/normalize-error err))

(defn begin-spot-meta-load
  [state]
  (assoc-in state [:spot :loading-meta?] true))

(defn apply-spot-meta-success
  [state data]
  (-> state
      (assoc-in [:spot :meta] data)
      (assoc-in [:spot :loading-meta?] false)
      (assoc-in [:spot :error] nil)
      (assoc-in [:spot :error-category] nil)))

(defn apply-spot-meta-error
  [state err]
  (let [{:keys [message category]} (normalized-error err)]
    (-> state
        (assoc-in [:spot :loading-meta?] false)
        (assoc-in [:spot :error] message)
        (assoc-in [:spot :error-category] category))))

(defn apply-asset-contexts-success
  [state rows]
  (assoc-in state [:asset-contexts] rows))

(defn apply-asset-contexts-error
  [state err]
  (let [{:keys [message category]} (normalized-error err)]
    (-> state
        (assoc-in [:asset-contexts :error] message)
        (assoc-in [:asset-contexts :error-category] category))))

(defn apply-perp-dexs-success
  [state payload]
  (let [{:keys [dex-names fee-config-by-name]} (perp-dexs/normalize-perp-dex-payload payload)]
    (-> state
        (assoc-in [:perp-dexs] dex-names)
        (assoc-in [:perp-dex-fee-config-by-name] fee-config-by-name))))

(defn apply-perp-dexs-error
  [state err]
  (let [{:keys [message category]} (normalized-error err)]
    (-> state
        (assoc-in [:perp-dexs-error] message)
        (assoc-in [:perp-dexs-error-category] category))))

(defn apply-candle-snapshot-success
  [state coin interval rows]
  (assoc-in state [:candles coin interval] rows))

(defn apply-candle-snapshot-error
  [state coin interval err]
  (let [{:keys [message category]} (normalized-error err)]
    (-> state
        (assoc-in [:candles coin interval :error] message)
        (assoc-in [:candles coin interval :error-category] category))))

(defn apply-open-orders-success
  [state dex rows]
  (if (and dex (not= dex ""))
    (assoc-in state [:orders :open-orders-snapshot-by-dex dex] rows)
    (assoc-in state [:orders :open-orders-snapshot] rows)))

(defn apply-open-orders-error
  [state err]
  (let [{:keys [message category]} (normalized-error err)]
    (-> state
        (assoc-in [:orders :open-error] message)
        (assoc-in [:orders :open-error-category] category))))

(defn apply-user-fills-success
  [state rows]
  (assoc-in state [:orders :fills] rows))

(defn apply-user-fills-error
  [state err]
  (let [{:keys [message category]} (normalized-error err)]
    (-> state
        (assoc-in [:orders :fills-error] message)
        (assoc-in [:orders :fills-error-category] category))))

(defn begin-portfolio-load
  [state]
  (-> state
      (assoc-in [:portfolio :loading?] true)
      (assoc-in [:portfolio :error] nil)))

(defn apply-portfolio-success
  [state summary-by-key]
  (-> state
      (assoc-in [:portfolio :summary-by-key] (or summary-by-key {}))
      (assoc-in [:portfolio :loading?] false)
      (assoc-in [:portfolio :error] nil)
      (assoc-in [:portfolio :loaded-at-ms] (.now js/Date))))

(defn apply-portfolio-error
  [state err]
  (let [{:keys [message]} (normalized-error err)]
    (-> state
        (assoc-in [:portfolio :loading?] false)
        (assoc-in [:portfolio :error] message))))

(defn begin-user-fees-load
  [state]
  (-> state
      (assoc-in [:portfolio :user-fees-loading?] true)
      (assoc-in [:portfolio :user-fees-error] nil)))

(defn apply-user-fees-success
  [state payload]
  (-> state
      (assoc-in [:portfolio :user-fees] payload)
      (assoc-in [:portfolio :user-fees-loading?] false)
      (assoc-in [:portfolio :user-fees-error] nil)
      (assoc-in [:portfolio :user-fees-loaded-at-ms] (.now js/Date))))

(defn apply-user-fees-error
  [state err]
  (let [{:keys [message]} (normalized-error err)]
    (-> state
        (assoc-in [:portfolio :user-fees-loading?] false)
        (assoc-in [:portfolio :user-fees-error] message))))

(defn begin-asset-selector-load
  [state phase]
  (-> state
      (assoc-in [:asset-selector :loading?] true)
      (assoc-in [:asset-selector :phase] phase)))

(defn apply-asset-selector-success
  [state phase {:keys [markets market-by-key active-market loaded-at-ms]}]
  (let [current-phase (get-in state [:asset-selector :phase])
        cache-hydrated? (boolean (get-in state [:asset-selector :cache-hydrated?]))
        prefer-current? (and (= phase :bootstrap)
                             (= current-phase :full)
                             (not cache-hydrated?))
        state* (if prefer-current?
                 (assoc-in state [:asset-selector :loaded-at-ms] loaded-at-ms)
                 (-> state
                     (assoc-in [:asset-selector :markets] markets)
                     (assoc-in [:asset-selector :market-by-key] market-by-key)
                     (assoc :active-market (or active-market (:active-market state)))
                     (assoc-in [:asset-selector :loaded-at-ms] loaded-at-ms)
                     (assoc-in [:asset-selector :phase] phase)
                     (assoc-in [:asset-selector :cache-hydrated?] false)
                     (assoc-in [:asset-selector :error] nil)
                     (assoc-in [:asset-selector :error-category] nil)))]
    (assoc-in state* [:asset-selector :loading?] false)))

(defn apply-asset-selector-error
  [state err]
  (let [{:keys [message category]} (normalized-error err)]
    (-> state
        (assoc-in [:asset-selector :loading?] false)
        (assoc-in [:asset-selector :error] message)
        (assoc-in [:asset-selector :error-category] category))))

(defn begin-spot-balances-load
  [state]
  (assoc-in state [:spot :loading-balances?] true))

(defn apply-spot-balances-success
  [state data]
  (-> state
      (assoc-in [:spot :clearinghouse-state] data)
      (assoc-in [:spot :loading-balances?] false)
      (assoc-in [:spot :error] nil)
      (assoc-in [:spot :error-category] nil)))

(defn apply-spot-balances-error
  [state err]
  (let [{:keys [message category]} (normalized-error err)]
    (-> state
        (assoc-in [:spot :loading-balances?] false)
        (assoc-in [:spot :error] message)
        (assoc-in [:spot :error-category] category))))

(defn apply-user-abstraction-snapshot
  [state requested-address snapshot]
  (let [active-address (some-> (get-in state [:wallet :address]) str str/lower-case)]
    (if (= requested-address active-address)
      (assoc state :account snapshot)
      state)))

(defn apply-perp-dex-clearinghouse-success
  [state dex data]
  (assoc-in state [:perp-dex-clearinghouse dex] data))

(defn apply-perp-dex-clearinghouse-error
  [state err]
  (let [{:keys [message category]} (normalized-error err)]
    (-> state
        (assoc-in [:perp-dex-clearinghouse-error] message)
        (assoc-in [:perp-dex-clearinghouse-error-category] category))))

(defn- normalize-vault-address
  [value]
  (some-> value str str/trim str/lower-case))

(defn- dedupe-vault-rows
  [rows]
  (reduce (fn [{:keys [order by-address]} row]
            (let [address (normalize-vault-address (:vault-address row))]
              (if (seq address)
                (if (contains? by-address address)
                  {:order order
                   :by-address (assoc by-address address row)}
                  {:order (conj order address)
                   :by-address (assoc by-address address row)})
                {:order order
                 :by-address by-address})))
          {:order []
           :by-address {}}
          (if (sequential? rows) rows [])))

(defn- merge-vault-rows
  [index-rows summary-rows]
  (let [{:keys [order by-address]} (dedupe-vault-rows (concat (or index-rows [])
                                                              (or summary-rows [])))]
    (mapv by-address order)))

(defn begin-vault-index-load
  [state]
  (-> state
      (assoc-in [:vaults :loading :index?] true)
      (assoc-in [:vaults :errors :index] nil)))

(defn apply-vault-index-success
  [state rows]
  (let [rows* (if (sequential? rows) (vec rows) [])
        summaries (get-in state [:vaults :recent-summaries] [])]
    (-> state
        (assoc-in [:vaults :index-rows] rows*)
        (assoc-in [:vaults :merged-index-rows] (merge-vault-rows rows* summaries))
        (assoc-in [:vaults :loading :index?] false)
        (assoc-in [:vaults :errors :index] nil)
        (assoc-in [:vaults :loaded-at-ms :index] (.now js/Date)))))

(defn apply-vault-index-error
  [state err]
  (let [{:keys [message]} (normalized-error err)]
    (-> state
        (assoc-in [:vaults :loading :index?] false)
        (assoc-in [:vaults :errors :index] message))))

(defn begin-vault-summaries-load
  [state]
  (-> state
      (assoc-in [:vaults :loading :summaries?] true)
      (assoc-in [:vaults :errors :summaries] nil)))

(defn apply-vault-summaries-success
  [state rows]
  (let [rows* (if (sequential? rows) (vec rows) [])
        index-rows (get-in state [:vaults :index-rows] [])]
    (-> state
        (assoc-in [:vaults :recent-summaries] rows*)
        (assoc-in [:vaults :merged-index-rows] (merge-vault-rows index-rows rows*))
        (assoc-in [:vaults :loading :summaries?] false)
        (assoc-in [:vaults :errors :summaries] nil)
        (assoc-in [:vaults :loaded-at-ms :summaries] (.now js/Date)))))

(defn apply-vault-summaries-error
  [state err]
  (let [{:keys [message]} (normalized-error err)]
    (-> state
        (assoc-in [:vaults :loading :summaries?] false)
        (assoc-in [:vaults :errors :summaries] message))))

(defn begin-user-vault-equities-load
  [state]
  (-> state
      (assoc-in [:vaults :loading :user-equities?] true)
      (assoc-in [:vaults :errors :user-equities] nil)))

(defn apply-user-vault-equities-success
  [state rows]
  (let [rows* (if (sequential? rows) (vec rows) [])
        by-address (reduce (fn [acc row]
                             (if-let [address (normalize-vault-address (:vault-address row))]
                               (assoc acc address row)
                               acc))
                           {}
                           rows*)]
    (-> state
        (assoc-in [:vaults :user-equities] rows*)
        (assoc-in [:vaults :user-equity-by-address] by-address)
        (assoc-in [:vaults :loading :user-equities?] false)
        (assoc-in [:vaults :errors :user-equities] nil)
        (assoc-in [:vaults :loaded-at-ms :user-equities] (.now js/Date)))))

(defn apply-user-vault-equities-error
  [state err]
  (let [{:keys [message]} (normalized-error err)]
    (-> state
        (assoc-in [:vaults :loading :user-equities?] false)
        (assoc-in [:vaults :errors :user-equities] message))))

(defn begin-vault-details-load
  [state vault-address]
  (if-let [vault-address* (normalize-vault-address vault-address)]
    (-> state
        (assoc-in [:vaults :loading :details-by-address vault-address*] true)
        (assoc-in [:vaults :errors :details-by-address vault-address*] nil))
    state))

(defn apply-vault-details-success
  [state vault-address payload]
  (if-let [vault-address* (normalize-vault-address vault-address)]
    (-> state
        (assoc-in [:vaults :details-by-address vault-address*] payload)
        (assoc-in [:vaults :loading :details-by-address vault-address*] false)
        (assoc-in [:vaults :errors :details-by-address vault-address*] nil)
        (assoc-in [:vaults :loaded-at-ms :details-by-address vault-address*] (.now js/Date)))
    state))

(defn apply-vault-details-error
  [state vault-address err]
  (if-let [vault-address* (normalize-vault-address vault-address)]
    (let [{:keys [message]} (normalized-error err)]
      (-> state
          (assoc-in [:vaults :loading :details-by-address vault-address*] false)
          (assoc-in [:vaults :errors :details-by-address vault-address*] message)))
    state))

(defn begin-vault-webdata2-load
  [state vault-address]
  (if-let [vault-address* (normalize-vault-address vault-address)]
    (-> state
        (assoc-in [:vaults :loading :webdata-by-vault vault-address*] true)
        (assoc-in [:vaults :errors :webdata-by-vault vault-address*] nil))
    state))

(defn apply-vault-webdata2-success
  [state vault-address payload]
  (if-let [vault-address* (normalize-vault-address vault-address)]
    (-> state
        (assoc-in [:vaults :webdata-by-vault vault-address*] payload)
        (assoc-in [:vaults :loading :webdata-by-vault vault-address*] false)
        (assoc-in [:vaults :errors :webdata-by-vault vault-address*] nil)
        (assoc-in [:vaults :loaded-at-ms :webdata-by-vault vault-address*] (.now js/Date)))
    state))

(defn apply-vault-webdata2-error
  [state vault-address err]
  (if-let [vault-address* (normalize-vault-address vault-address)]
    (let [{:keys [message]} (normalized-error err)]
      (-> state
          (assoc-in [:vaults :loading :webdata-by-vault vault-address*] false)
          (assoc-in [:vaults :errors :webdata-by-vault vault-address*] message)))
    state))

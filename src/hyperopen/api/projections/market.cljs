(ns hyperopen.api.projections.market
  (:require [hyperopen.api.errors :as api-errors]
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

(defn- candle-rows
  [payload]
  (cond
    (vector? payload)
    payload

    (sequential? payload)
    (vec payload)

    (map? payload)
    (let [nested (or (:data payload)
                     (:rows payload)
                     (:candles payload))]
      (if (sequential? nested)
        (vec nested)
        []))

    :else
    []))

(defn- candle-row-timestamp
  [row]
  (when (map? row)
    (or (:t row)
        (:time row)
        (get row "t")
        (get row "time"))))

(defn- merge-candle-rows
  [existing incoming]
  (->> (concat (candle-rows existing)
               (candle-rows incoming))
       (reduce (fn [rows-by-time row]
                 (if-let [timestamp (candle-row-timestamp row)]
                   (assoc rows-by-time timestamp row)
                   rows-by-time))
               {})
       vals
       (sort-by candle-row-timestamp)
       vec))

(defn apply-candle-snapshot-success
  [state coin interval rows]
  (let [path [:candles coin interval]
        merged-rows (merge-candle-rows (get-in state path) rows)]
    (assoc-in state path merged-rows)))

(defn apply-candle-snapshot-error
  "Record a failed candle fetch WITHOUT destroying the rows already stored.

  `apply-candle-snapshot-success` stores a plain vector at `[:candles coin
  interval]`. Appending `:error` to that path and calling `assoc-in` therefore
  resolves to `(assoc <vector> :error ...)`, which throws — and it throws inside
  the `swap!` that runs in the fetch's `.catch`, so a failure against an
  already-warm slot recorded nothing, cleared no pending flag, and left the
  caller's loading affordance up until some later fetch happened to succeed.

  The store already supports an entry being either a bare row vector or a map
  carrying a rows key alongside `:error`/`:error-category` — every reader
  (`candle-rows` here, `candle-rows-present?` in websocket/migration_flags,
  `benchmark-candle-rows` in the portfolio view model) handles both, and the
  websocket writer clears the error keys on success. So normalize to the map
  shape rather than assuming the slot is empty."
  [state coin interval err]
  (let [{:keys [message category]} (normalized-error err)
        path [:candles coin interval]
        existing (get-in state path)
        entry (if (map? existing)
                existing
                {:rows (candle-rows existing)})]
    (assoc-in state
              path
              (assoc entry
                     :error message
                     :error-category category))))

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

(defn apply-perp-dex-clearinghouse-success
  [state dex data]
  (assoc-in state [:perp-dex-clearinghouse dex] data))

(defn apply-default-clearinghouse-success
  "Store the base-dex clearinghouse state. [:webdata2 :clearinghouseState] is
  the canonical bucket every base-book consumer reads (positions, balances,
  equity, optimizer); it is fed by the dex \"\" clearinghouseState stream and
  REST refreshes now that the provider removed the webData2 topic."
  [state data]
  (assoc-in state [:webdata2 :clearinghouseState] data))

(defn apply-perp-dex-clearinghouse-error
  [state err]
  (let [{:keys [message category]} (normalized-error err)]
    (-> state
        (assoc-in [:perp-dex-clearinghouse-error] message)
        (assoc-in [:perp-dex-clearinghouse-error-category] category))))

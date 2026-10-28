(ns hyperopen.websocket.user-runtime.handlers
  (:require [hyperopen.domain.funding-history :as funding-history]
            [hyperopen.platform :as platform]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.order.cancel-guard :as cancel-guard]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.trading-settings :as trading-settings]
            [hyperopen.websocket.user-runtime.common :as common]
            [hyperopen.websocket.user-runtime.fills :as fill-runtime]
            [hyperopen.websocket.user-runtime.refresh :as refresh-runtime]))

(defn- upsert-seq
  [current incoming]
  (let [combined (concat incoming current)]
    (vec (take 200 combined))))

(defn- unique-by
  [id-fn rows]
  (reduce (fn [acc row]
            (let [id (id-fn row)]
              (if (or (nil? id)
                      (contains? (:seen acc) id))
                acc
                {:seen (conj (:seen acc) id)
                 :rows (conj (:rows acc) row)})))
          {:seen #{} :rows []}
          (or rows [])))

(defn- twap-history-row-id
  [row]
  (some-> [(get-in row [:status :status])
           (:time row)
           (get-in row [:state :timestamp])
           (get-in row [:state :coin])]
          pr-str))

(defn- merge-twap-history-rows
  [current incoming]
  (->> (concat incoming current)
       (unique-by twap-history-row-id)
       :rows
       (take 200)
       vec))

(defn- twap-slice-fill-rows
  [rows]
  (->> (or rows [])
       (keep (fn [row]
               (cond
                 (map? (:fill row)) (:fill row)
                 (map? row) row
                 :else nil)))
       vec))

(defn- extract-channel-rows
  [msg collection-key]
  (let [payload (:data msg)]
    (cond
      (and (map? payload)
           (sequential? (get payload collection-key)))
      {:rows (vec (get payload collection-key))
       :snapshot? (boolean (:isSnapshot payload))}

      (sequential? payload)
      {:rows (vec payload)
       :snapshot? (boolean (:isSnapshot msg))}

      :else
      {:rows []
       :snapshot? false})))

(defn- tap-raw-message!
  "Offers the raw message to an optional evidence tap before any display
  filtering. A failing tap is logged and never affects the display path."
  [store on-raw-message channel msg]
  (when on-raw-message
    (try
      (on-raw-message store msg (platform/now-ms))
      (catch :default e
        (telemetry/log! "Raw user-stream tap failed" channel (str e))))))

(defn open-orders-handler
  "Display handler for `openOrders`. With `{:on-raw-message f}`, `f` receives
  `(f store msg received-at-ms)` for every message on the channel, before the
  live-address filter and before cancel-guard pruning."
  ([store]
   (open-orders-handler store nil))
  ([store {:keys [on-raw-message]}]
   (fn [msg]
     (when (= "openOrders" (:channel msg))
       (tap-raw-message! store on-raw-message "openOrders" msg)
       (when (common/message-for-live-user-address? store msg)
         (swap! store
                (fn [state]
                  (-> state
                      (assoc-in [:orders :open-orders]
                                (cancel-guard/prune-open-order-payload
                                 (:data msg)
                                 (cancel-guard/state-guard-entries state)))
                      (assoc-in [:orders :open-orders-hydrated?] true)))))))))

(defn twap-states-handler
  [store]
  (fn [msg]
    (when (and (= "twapStates" (:channel msg))
               (common/message-for-live-user-address? store msg))
      (let [{:keys [rows]} (extract-channel-rows msg :states)]
        (swap! store assoc-in [:orders :twap-states] rows)))))

(defn user-fills-handler
  "Display handler for `userFills`. With `{:on-raw-message f}`, `f` receives
  `(f store msg received-at-ms)` for every message on the channel, before the
  live-address filter, before empty snapshots are skipped and before the
  display de-duplication and 200-row truncation."
  ([store]
   (user-fills-handler store nil))
  ([store {:keys [on-raw-message]}]
   (fn [msg]
     (when (= "userFills" (:channel msg))
       (tap-raw-message! store on-raw-message "userFills" msg)
       (when (common/message-for-live-user-address? store msg)
         (let [{:keys [rows snapshot?]} (extract-channel-rows msg :fills)]
           (when (seq rows)
             (if snapshot?
               (swap! store assoc-in [:orders :fills] rows)
               (let [existing (get-in @store [:orders :fills] [])
                     new-rows (vec (fill-runtime/novel-fills existing rows))]
                 (when (seq new-rows)
                   (swap! store update-in [:orders :fills] #(upsert-seq (or % []) new-rows))
                   (when (trading-settings/fill-alerts-enabled? @store)
                     (fill-runtime/show-user-fill-toast! store new-rows))
                   (refresh-runtime/schedule-account-surface-refresh-after-fill! store)))))))))))

(defn user-fundings-handler
  [store]
  (fn [msg]
    (when (and (= "userFundings" (:channel msg))
               (common/message-for-live-user-address? store msg))
      (let [{:keys [rows]} (extract-channel-rows msg :fundings)
            normalized (funding-history/normalize-ws-funding-rows rows)]
        (when (seq normalized)
          (swap! store
                 (fn [state]
                   (let [existing (get-in state [:orders :fundings-raw] [])
                         filters (get-in state [:account-info :funding-history :filters])
                         filters* (funding-history/normalize-funding-history-filters
                                   filters
                                   (platform/now-ms))
                         merged (funding-history/merge-funding-history-rows existing normalized)
                         filtered (funding-history/filter-funding-history-rows merged filters*)]
                     (-> state
                         (assoc-in [:orders :fundings-raw] merged)
                         (assoc-in [:orders :fundings] filtered))))))))))

(defn user-ledger-handler
  [store]
  (fn [msg]
    (when (and (= "userNonFundingLedgerUpdates" (:channel msg))
               (common/message-for-live-user-address? store msg))
      (let [{:keys [rows snapshot?]} (extract-channel-rows msg :nonFundingLedgerUpdates)]
        (when (seq rows)
          (if snapshot?
            (swap! store assoc-in [:orders :ledger] rows)
            (do
              (swap! store update-in [:orders :ledger] #(upsert-seq (or % []) rows))
              (refresh-runtime/schedule-account-surface-refresh-after-ledger! store))))))))

(defn user-twap-history-handler
  [store]
  (fn [msg]
    (when (and (= "userTwapHistory" (:channel msg))
               (common/message-for-live-user-address? store msg))
      (let [{:keys [rows snapshot?]} (extract-channel-rows msg :history)]
        (swap! store update :orders
               (fn [orders]
                 (assoc (or orders {})
                        :twap-history
                        (if snapshot?
                          rows
                          (merge-twap-history-rows (:twap-history orders) rows)))))))))

(defn user-twap-slice-fills-handler
  [store]
  (fn [msg]
    (when (and (= "userTwapSliceFills" (:channel msg))
               (common/message-for-live-user-address? store msg))
      (let [{:keys [rows snapshot?]} (extract-channel-rows msg :twapSliceFills)
            fills (twap-slice-fill-rows rows)]
        (if snapshot?
          (swap! store assoc-in [:orders :twap-slice-fills] fills)
          (let [existing (get-in @store [:orders :twap-slice-fills] [])
                new-rows (vec (fill-runtime/novel-fills existing fills))]
            (when (seq new-rows)
              (swap! store update-in [:orders :twap-slice-fills] #(upsert-seq (or % []) new-rows)))))))))

(defn- clear-dex-from-clearinghouse-message
  [msg]
  (or (common/normalized-dex (:dex msg))
      (common/normalized-dex (get-in msg [:data :dex]))))

(defn- base-dex-clearinghouse-message?
  "True for a clearinghouseState payload addressed to the base perp dex: the
  server echoes dex \"\" (blank) and nests the state under :clearinghouseState.
  Frames without an explicit dex field stay unrouted."
  [msg]
  (let [payload (:data msg)]
    (and (map? payload)
         (contains? payload :dex)
         (nil? (common/normalized-dex (:dex payload)))
         (map? (:clearinghouseState payload)))))

(defn- clearinghouse-message-data
  [msg]
  (let [payload (:data msg)]
    (if (and (map? payload)
             (contains? payload :clearinghouseState))
      (:clearinghouseState payload)
      payload)))

(defn clearinghouse-state-handler
  [store]
  (fn [msg]
    (when (and (= "clearinghouseState" (:channel msg))
               (common/message-for-live-user-address? store msg))
      (if-let [dex (clear-dex-from-clearinghouse-message msg)]
        (swap! store
               api-projections/apply-perp-dex-clearinghouse-success
               dex
               (clearinghouse-message-data msg))
        (when (base-dex-clearinghouse-message? msg)
          (swap! store
                 api-projections/apply-default-clearinghouse-success
                 (clearinghouse-message-data msg)))))))

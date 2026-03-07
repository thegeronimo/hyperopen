(ns hyperopen.websocket.user
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.account.surface-policy :as account-surface-policy]
            [hyperopen.account.surface-service :as account-surface-service]
            [hyperopen.api.default :as api]
            [hyperopen.api.market-metadata.facade :as market-metadata]
            [hyperopen.api.promise-effects :as promise-effects]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.domain.funding-history :as funding-history]
            [hyperopen.order.feedback-runtime :as order-feedback-runtime]
            [hyperopen.platform :as platform]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.wallet.address-watcher :as address-watcher]))

(defonce user-state (atom {:subscriptions #{}
                           :clearinghouse-subscriptions #{}}))
(defonce ^:private account-surface-refresh-timeout-id (atom nil))

(def ^:private fill-account-surface-refresh-debounce-ms 250)

(defn- normalized-address
  [value]
  (account-context/normalize-address value))

(defn- active-effective-address
  [store]
  (some-> (account-context/effective-account-address @store)
          normalized-address))

(defn- normalized-dex
  [value]
  (let [token (some-> value str str/trim)]
    (when (seq token)
      token)))

(defn- message-address
  [msg]
  (or (normalized-address (:user msg))
      (normalized-address (:address msg))
      (normalized-address (:walletAddress msg))
      (normalized-address (get-in msg [:data :user]))
      (normalized-address (get-in msg [:data :address]))
      (normalized-address (get-in msg [:data :walletAddress]))
      (normalized-address (get-in msg [:data :wallet]))))

(defn- message-for-active-address?
  [store msg]
  (let [msg-address (message-address msg)
        active-address (active-effective-address store)]
    (if msg-address
      (and active-address
           (= msg-address active-address))
      true)))

(defn- requested-address-active?
  [store requested-address]
  (let [requested-address* (normalized-address requested-address)
        active-address (active-effective-address store)]
    (and requested-address*
         active-address
         (= requested-address* active-address))))

(defn- apply-success-and-return-when-address-active
  [store requested-address apply-fn & leading-args]
  (fn [payload]
    (when (requested-address-active? store requested-address)
      (apply swap! store apply-fn (concat leading-args [payload])))
    payload))

(defn- apply-error-and-reject-when-address-active
  [store requested-address apply-error-fn & leading-args]
  (fn [err]
    (when (requested-address-active? store requested-address)
      (apply swap! store apply-error-fn (concat leading-args [err])))
    (promise-effects/reject-error err)))

(defn- subscribe! [sub]
  (ws-client/send-message! {:method "subscribe"
                            :subscription sub}))

(defn- unsubscribe! [sub]
  (ws-client/send-message! {:method "unsubscribe"
                            :subscription sub}))

(defn- clearinghouse-sub-key
  [address dex]
  (let [address* (normalized-address address)
        dex* (normalized-dex dex)]
    (when (and address* dex*)
      [address* dex*])))

(defn- subscribed-clearinghouse-keys-for-address
  [address]
  (let [address* (normalized-address address)]
    (if-not address*
      #{}
      (->> (get @user-state :clearinghouse-subscriptions #{})
           (filter (fn [[sub-address _]]
                     (= sub-address address*)))
           set))))

(defn- subscribe-clearinghouse-state!
  [address dex]
  (when-let [[_ dex* :as sub-key] (clearinghouse-sub-key address dex)]
    (when-not (contains? (get @user-state :clearinghouse-subscriptions #{}) sub-key)
      (subscribe! {:type "clearinghouseState"
                   :user address
                   :dex dex*})
      (swap! user-state update :clearinghouse-subscriptions
             (fnil conj #{})
             sub-key))))

(defn- unsubscribe-clearinghouse-state!
  [address dex]
  (when-let [[_ dex* :as sub-key] (clearinghouse-sub-key address dex)]
    (when (contains? (get @user-state :clearinghouse-subscriptions #{}) sub-key)
      (unsubscribe! {:type "clearinghouseState"
                     :user address
                     :dex dex*})
      (swap! user-state update :clearinghouse-subscriptions disj sub-key))))

(defn sync-perp-dex-clearinghouse-subscriptions!
  [address dex-names]
  (let [address* (normalized-address address)]
    (when address*
        (let [desired (into #{}
                          (keep (fn [dex]
                                  (clearinghouse-sub-key address* dex)))
                          (account-surface-policy/normalize-dex-names dex-names))
            existing (subscribed-clearinghouse-keys-for-address address*)]
        (doseq [[sub-address dex] desired
                :when (not (contains? existing [sub-address dex]))]
          (subscribe-clearinghouse-state! address* dex))
        (doseq [[sub-address dex] existing
                :when (not (contains? desired [sub-address dex]))]
          (unsubscribe-clearinghouse-state! sub-address dex))))))

(defn subscribe-user! [address]
  (when address
    (let [subs [{:type "openOrders" :user address}
                {:type "userFills" :user address}
                {:type "userFundings" :user address}
                {:type "userNonFundingLedgerUpdates" :user address}]]
      (doseq [s subs] (subscribe! s))
      (swap! user-state update :subscriptions conj address)
      (telemetry/log! "Subscribed to user streams for:" address))))

(defn unsubscribe-user! [address]
  (when address
    (let [subs [{:type "openOrders" :user address}
                {:type "userFills" :user address}
                {:type "userFundings" :user address}
                {:type "userNonFundingLedgerUpdates" :user address}]
          clearinghouse-subs (subscribed-clearinghouse-keys-for-address address)]
      (doseq [s subs] (unsubscribe! s))
      (doseq [[sub-address dex] clearinghouse-subs]
        (unsubscribe-clearinghouse-state! sub-address dex))
      (swap! user-state update :subscriptions disj address)
      (telemetry/log! "Unsubscribed from user streams for:" address))))

(defn- upsert-seq [current incoming]
  (let [combined (concat incoming current)]
    (vec (take 200 combined))))

(defn- refresh-open-orders-snapshot!
  [store address dex opts]
  (-> (api/request-frontend-open-orders! address
                                         (cond-> (or opts {})
                                           (and dex (not= dex "")) (assoc :dex dex)))
      (.then (apply-success-and-return-when-address-active
              store
              address
              api-projections/apply-open-orders-success
              dex))
      (.catch (apply-error-and-reject-when-address-active
               store
               address
               api-projections/apply-open-orders-error))))

(defn- refresh-default-clearinghouse-snapshot!
  [store address opts]
  (-> (api/request-clearinghouse-state! address nil opts)
      (.then (fn [data]
               (swap! store
                      (fn [state]
                        (if (= address (account-context/effective-account-address state))
                          (assoc-in state [:webdata2 :clearinghouseState] data)
                          state)))))
      (.catch (fn [err]
                (telemetry/log! "Error refreshing default clearinghouse state after user fill:" err)))))

(defn- refresh-spot-clearinghouse-snapshot!
  [store address opts]
  (-> (api/request-spot-clearinghouse-state! address opts)
      (.then (apply-success-and-return-when-address-active
              store
              address
              api-projections/apply-spot-balances-success))
      (.catch (apply-error-and-reject-when-address-active
               store
               address
               api-projections/apply-spot-balances-error))))

(defn- refresh-perp-dex-clearinghouse-snapshot!
  [store address dex opts]
  (-> (api/request-clearinghouse-state! address dex opts)
      (.then (apply-success-and-return-when-address-active
              store
              address
              api-projections/apply-perp-dex-clearinghouse-success
              dex))
      (.catch (apply-error-and-reject-when-address-active
               store
               address
               api-projections/apply-perp-dex-clearinghouse-error))))

(defn- ensure-perp-dex-metadata!
  [store opts]
  (market-metadata/ensure-and-apply-perp-dex-metadata!
   {:store store
    :ensure-perp-dexs-data! api/ensure-perp-dexs-data!
    :apply-perp-dexs-success api-projections/apply-perp-dexs-success
    :apply-perp-dexs-error api-projections/apply-perp-dexs-error}
   opts))

(defn- refresh-account-surfaces-after-user-fill!
  [store address]
  (account-surface-service/refresh-after-user-fill!
   {:store store
    :address address
    :ensure-perp-dexs! ensure-perp-dex-metadata!
    :sync-perp-dex-clearinghouse-subscriptions! sync-perp-dex-clearinghouse-subscriptions!
    :refresh-open-orders! refresh-open-orders-snapshot!
    :refresh-default-clearinghouse! refresh-default-clearinghouse-snapshot!
    :refresh-spot-clearinghouse! refresh-spot-clearinghouse-snapshot!
    :refresh-perp-dex-clearinghouse! refresh-perp-dex-clearinghouse-snapshot!
    :resolve-current-address account-context/effective-account-address
    :log-fn telemetry/log!}))

(defn- clear-account-surface-refresh-timeout!
  []
  (when-let [timeout-id @account-surface-refresh-timeout-id]
    (platform/clear-timeout! timeout-id)
    (reset! account-surface-refresh-timeout-id nil)))

(defn- schedule-account-surface-refresh-after-fill!
  [store]
  (let [address (account-context/effective-account-address @store)]
    (when address
      (clear-account-surface-refresh-timeout!)
      (reset! account-surface-refresh-timeout-id
              (platform/set-timeout!
               (fn []
                 (reset! account-surface-refresh-timeout-id nil)
                 (when (= address (account-context/effective-account-address @store))
                   (refresh-account-surfaces-after-user-fill! store address)))
               fill-account-surface-refresh-debounce-ms)))))

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

(defn- clear-order-feedback-toast-timeout!
  ([]
   (clear-order-feedback-toast-timeout! nil))
  ([toast-id]
   (order-feedback-runtime/clear-order-feedback-toast-timeout-in-runtime!
    runtime-state/runtime
    platform/clear-timeout!
    toast-id)))

(defn- schedule-order-feedback-toast-clear!
  [store toast-id]
  (order-feedback-runtime/schedule-order-feedback-toast-clear!
   {:store store
    :runtime runtime-state/runtime
    :toast-id toast-id
    :clear-order-feedback-toast! order-feedback-runtime/clear-order-feedback-toast!
    :clear-order-feedback-toast-timeout! clear-order-feedback-toast-timeout!
    :order-feedback-toast-duration-ms runtime-state/order-feedback-toast-duration-ms
    :set-timeout-fn platform/set-timeout!}))

(defn- fill-identity
  [fill]
  (when (map? fill)
    (or (some-> (or (:tid fill)
                    (:fill-id fill)
                    (:fillId fill)
                    (:id fill))
                (vector :id))
        (let [time-token (or (:time fill)
                             (:timestamp fill)
                             (:ts fill)
                             (:t fill))
              coin-token (or (:coin fill)
                             (:symbol fill)
                             (:asset fill))
              side-token (or (:side fill)
                             (:dir fill))
              size-token (or (:sz fill)
                             (:size fill))
              price-token (or (:px fill)
                              (:price fill)
                              (:fillPx fill)
                              (:avgPx fill))]
          (when (or (some? time-token)
                    (some? coin-token)
                    (some? side-token)
                    (some? size-token)
                    (some? price-token))
            [:fallback time-token coin-token side-token size-token price-token])))))

(defn- novel-fills
  [existing incoming]
  (let [known (->> (or existing [])
                   (keep fill-identity)
                   set)]
    (second
     (reduce (fn [[seen acc] fill]
               (if-let [identity (fill-identity fill)]
                 (if (contains? seen identity)
                   [seen acc]
                   [(conj seen identity) (conj acc fill)])
                 [seen (conj acc fill)]))
             [known []]
             (or incoming [])))))

(defn- fill-toast-message
  [rows]
  (let [coin* (some-> (or (:coin (first rows))
                          (:symbol (first rows))
                          (:asset (first rows)))
                      str
                      str/trim)]
    (if (seq coin*)
      (str "Order filled: " coin* ".")
      "Order filled.")))

(defn- parse-finite-number
  [value]
  (let [num (cond
              (number? value) value
              (string? value) (js/parseFloat (str/trim value))
              :else js/NaN)]
    (when (and (number? num)
               (js/isFinite num))
      num)))

(defn- normalize-fill-side
  [row]
  (let [side* (some-> (:side row) str str/trim str/upper-case)
        direction* (some-> (or (:dir row) (:direction row))
                           str
                           str/lower-case)]
    (cond
      (#{"B" "BUY" "LONG"} side*) :buy
      (#{"A" "S" "SELL" "SHORT"} side*) :sell
      (and (seq direction*)
           (or (str/includes? direction* "sell")
               (str/includes? direction* "open short")
               (str/includes? direction* "close long")))
      :sell
      (and (seq direction*)
           (or (str/includes? direction* "buy")
               (str/includes? direction* "open long")
               (str/includes? direction* "close short")))
      :buy
      :else nil)))

(def ^:private fill-size-format-options
  {:minimumFractionDigits 0
   :maximumFractionDigits 8})

(defn- format-fill-size
  [size]
  (or (fmt/format-intl-number size fill-size-format-options)
      (fmt/safe-to-fixed size 4)))

(defn- format-fill-price
  [price]
  (or (fmt/format-currency-with-digits price 0 5)
      (str "$" (fmt/safe-to-fixed price 2))))

(defn- normalized-fill-row
  [row]
  (let [coin* (some-> (or (:coin row)
                          (:symbol row)
                          (:asset row))
                      str
                      str/trim
                      str/upper-case)
        side* (normalize-fill-side row)
        size* (some-> (or (:sz row)
                          (:size row)
                          (:filledSz row)
                          (:filled row))
                      parse-finite-number
                      js/Math.abs)
        price* (some-> (or (:px row)
                           (:price row)
                           (:fillPx row)
                           (:avgPx row))
                       parse-finite-number)]
    (when (and (seq coin*)
               (some? side*)
               (number? size*)
               (pos? size*))
      {:coin coin*
       :side side*
       :size size*
       :price price*})))

(defn- add-fill-group
  [acc fill]
  (let [group-key [(:coin fill) (:side fill)]]
    (if (contains? (:groups acc) group-key)
      (update-in acc [:groups group-key] conj fill)
      (-> acc
          (update :group-order conj group-key)
          (assoc-in [:groups group-key] [fill])))))

(defn- summarize-fill-group
  [fills]
  (let [total-size (reduce + 0 (map :size fills))
        {:keys [weighted-notional weighted-size]}
        (reduce (fn [acc {:keys [size price]}]
                  (if (number? price)
                    (-> acc
                        (update :weighted-notional + (* size price))
                        (update :weighted-size + size))
                    acc))
                {:weighted-notional 0
                 :weighted-size 0}
                fills)
        average-price (when (pos? weighted-size)
                        (/ weighted-notional weighted-size))
        {:keys [coin side]} (first fills)
        action-label (if (= :buy side) "Bought" "Sold")
        headline (str action-label " " (format-fill-size total-size) " " coin)]
    {:headline headline
     :subline (when (number? average-price)
                (str "At average price of " (format-fill-price average-price)))}))

(defn- fill-toast-payloads
  [rows]
  (let [parsed-rows (->> (or rows [])
                         (keep normalized-fill-row)
                         vec)
        {:keys [group-order groups]}
        (reduce add-fill-group
                {:group-order []
                 :groups {}}
                parsed-rows)]
    (if (seq group-order)
      (mapv (fn [group-key]
              (let [{:keys [headline subline]} (summarize-fill-group (get groups group-key))]
                (cond-> {:headline headline
                         :message headline}
                  (seq subline) (assoc :subline subline))))
            group-order)
      [{:message (fill-toast-message rows)}])))

(defn- show-user-fill-toast!
  [store rows]
  (doseq [payload (fill-toast-payloads rows)]
    (order-feedback-runtime/show-order-feedback-toast!
     store
     :success
     payload
     schedule-order-feedback-toast-clear!)))

(defn create-user-handler [subscribe-fn unsubscribe-fn]
  (reify address-watcher/IAddressChangeHandler
    (on-address-changed [_ old-address new-address]
      (when old-address (unsubscribe-fn old-address))
      (when new-address (subscribe-fn new-address)))
    (get-handler-name [_] "user-ws-subscription-handler")))

(defn- open-orders-handler [store]
  (fn [msg]
    (when (and (= "openOrders" (:channel msg))
               (message-for-active-address? store msg))
      (swap! store assoc-in [:orders :open-orders] (:data msg)))))

(defn- user-fills-handler [store]
  (fn [msg]
    (when (and (= "userFills" (:channel msg))
               (message-for-active-address? store msg))
      (let [{:keys [rows snapshot?]} (extract-channel-rows msg :fills)]
        (when (seq rows)
          (if snapshot?
            (swap! store assoc-in [:orders :fills] rows)
            (let [existing (get-in @store [:orders :fills] [])
                  new-rows (vec (novel-fills existing rows))]
              (when (seq new-rows)
                (swap! store update-in [:orders :fills] #(upsert-seq (or % []) new-rows))
                (show-user-fill-toast! store new-rows)
                (schedule-account-surface-refresh-after-fill! store)))))))))

(defn- user-fundings-handler [store]
  (fn [msg]
    (when (and (= "userFundings" (:channel msg))
               (message-for-active-address? store msg))
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

(defn- user-ledger-handler [store]
  (fn [msg]
    (when (and (= "userNonFundingLedgerUpdates" (:channel msg))
               (message-for-active-address? store msg))
      (let [{:keys [rows snapshot?]} (extract-channel-rows msg :nonFundingLedgerUpdates)]
        (when (seq rows)
          (if snapshot?
            (swap! store assoc-in [:orders :ledger] rows)
            (do
              (swap! store update-in [:orders :ledger] #(upsert-seq (or % []) rows))
              (schedule-account-surface-refresh-after-fill! store))))))))

(defn- clear-dex-from-clearinghouse-message
  [msg]
  (or (normalized-dex (:dex msg))
      (normalized-dex (get-in msg [:data :dex]))))

(defn- clearinghouse-message-data
  [msg]
  (let [payload (:data msg)]
    (if (and (map? payload)
             (contains? payload :clearinghouseState))
      (:clearinghouseState payload)
      payload)))

(defn- clearinghouse-state-handler [store]
  (fn [msg]
    (when (and (= "clearinghouseState" (:channel msg))
               (message-for-active-address? store msg))
      (when-let [dex (clear-dex-from-clearinghouse-message msg)]
        (swap! store
               api-projections/apply-perp-dex-clearinghouse-success
               dex
               (clearinghouse-message-data msg))))))

(defn init! [store]
  (ws-client/register-handler! "openOrders" (open-orders-handler store))
  (ws-client/register-handler! "userFills" (user-fills-handler store))
  (ws-client/register-handler! "userFundings" (user-fundings-handler store))
  (ws-client/register-handler! "userNonFundingLedgerUpdates" (user-ledger-handler store))
  (ws-client/register-handler! "clearinghouseState" (clearinghouse-state-handler store))
  (telemetry/log! "User websocket handlers initialized"))

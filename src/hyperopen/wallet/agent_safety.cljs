(ns hyperopen.wallet.agent-safety
  (:require [hyperopen.api.trading :as trading]
            [hyperopen.platform :as platform]))

(def ^:private agent-safety-watch-key
  ::agent-safety)

(def ^:private timer-path
  [:timeouts :agent-schedule-cancel-refresh])

(defn- clear-refresh-timeout!
  [runtime clear-timeout-fn]
  (when-let [timer-id (get-in @runtime timer-path)]
    (clear-timeout-fn timer-id)
    (swap! runtime assoc-in timer-path nil)))

(defn stop-agent-safety!
  [{:keys [runtime]
    :or {runtime (atom {})}}]
  (clear-refresh-timeout! runtime platform/clear-timeout!)
  true)

(defn- ready-owner-address
  [state]
  (let [wallet-state (:wallet state)
        agent-state (:agent wallet-state)
        owner-address (:address wallet-state)]
    (when (and (:connected? wallet-state)
               (seq owner-address)
               (= :ready (:status agent-state)))
      owner-address)))

(defn- safety-fingerprint
  [state]
  [(boolean (get-in state [:wallet :connected?]))
   (get-in state [:wallet :address])
   (get-in state [:wallet :agent :status])
   (get-in state [:wallet :agent :agent-address])])

(defn- schedule-next-refresh!
  [{:keys [ahead-ms now-ms-fn refresh-ms runtime set-timeout-fn store]}]
  (letfn [(refresh! []
            (swap! runtime assoc-in timer-path nil)
            (if-let [owner-address (ready-owner-address @store)]
              (-> (trading/schedule-cancel! store
                                            owner-address
                                            (+ (now-ms-fn) ahead-ms))
                  (.catch (fn [_] nil))
                  (.finally
                   (fn []
                     (when (ready-owner-address @store)
                       (schedule-next-refresh! {:ahead-ms ahead-ms
                                                :now-ms-fn now-ms-fn
                                                :refresh-ms refresh-ms
                                                :runtime runtime
                                                :set-timeout-fn set-timeout-fn
                                                :store store})))))
              (swap! runtime assoc-in timer-path nil)))]
    (let [timer-id (set-timeout-fn refresh! refresh-ms)]
      (swap! runtime assoc-in timer-path timer-id)
      timer-id)))

(defn- ensure-agent-safety!
  [{:keys [ahead-ms now-ms-fn refresh-ms runtime set-timeout-fn store]}]
  (clear-refresh-timeout! runtime platform/clear-timeout!)
  (when-let [owner-address (ready-owner-address @store)]
    (-> (trading/schedule-cancel! store
                                  owner-address
                                  (+ (now-ms-fn) ahead-ms))
        (.catch (fn [_] nil))
        (.finally
         (fn []
           (when (ready-owner-address @store)
             (schedule-next-refresh! {:ahead-ms ahead-ms
                                      :now-ms-fn now-ms-fn
                                      :refresh-ms refresh-ms
                                      :runtime runtime
                                      :set-timeout-fn set-timeout-fn
                                      :store store})))))))

(defn install-agent-safety-watch!
  [{:keys [ahead-ms
           now-ms-fn
           refresh-ms
           runtime
           set-timeout-fn
           store]
    :or {ahead-ms 60000
         now-ms-fn platform/now-ms
         refresh-ms 30000
         runtime (atom {})
         set-timeout-fn platform/set-timeout!}}]
  (ensure-agent-safety! {:ahead-ms ahead-ms
                         :now-ms-fn now-ms-fn
                         :refresh-ms refresh-ms
                         :runtime runtime
                         :set-timeout-fn set-timeout-fn
                         :store store})
  (remove-watch store agent-safety-watch-key)
  (add-watch store agent-safety-watch-key
             (fn [_ _ old-state new-state]
               (when (not= (safety-fingerprint old-state)
                           (safety-fingerprint new-state))
                 (ensure-agent-safety! {:ahead-ms ahead-ms
                                        :now-ms-fn now-ms-fn
                                        :refresh-ms refresh-ms
                                        :runtime runtime
                                        :set-timeout-fn set-timeout-fn
                                        :store store})))))

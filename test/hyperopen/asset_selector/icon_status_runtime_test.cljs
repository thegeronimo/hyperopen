(ns hyperopen.asset-selector.icon-status-runtime-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.asset-selector.actions :as asset-actions]
            [hyperopen.asset-selector.icon-status-runtime :as icon-status-runtime]))

(defn- flush!
  [store pending-statuses flush-handle]
  (icon-status-runtime/flush-queued-asset-icon-statuses!
   {:store store
    :pending-statuses pending-statuses
    :flush-handle flush-handle
    :apply-asset-icon-status-updates-fn asset-actions/apply-asset-icon-status-updates
    :save-many! (fn [runtime-store path-values]
                  (swap! runtime-store
                         (fn [state]
                           (reduce (fn [acc [path value]]
                                     (assoc-in acc path value))
                                   state
                                   path-values))))}))

(deftest queue-asset-icon-status-batches-and-flushes-latest-statuses-test
  (let [store (atom {:asset-selector {:loaded-icons #{}
                                      :missing-icons #{}}})
        pending-statuses (atom {})
        flush-handle (atom nil)
        scheduled-callback (atom nil)
        queue! (fn [payload]
                 (icon-status-runtime/queue-asset-icon-status!
                  {:store store
                   :payload payload
                   :pending-statuses pending-statuses
                   :flush-handle flush-handle
                   :schedule-animation-frame! (fn [f]
                                                (reset! scheduled-callback f)
                                                :raf-id)
                   :flush-queued-asset-icon-statuses! (fn [runtime-store]
                                                        (flush! runtime-store
                                                                pending-statuses
                                                                flush-handle))}))]
    (queue! {:market-key "perp:BTC" :status :loaded})
    (queue! {:market-key "perp:BTC" :status :missing})
    (queue! {:market-key "perp:ETH" :status :loaded})
    (is (fn? @scheduled-callback))
    (@scheduled-callback)
    (is (= #{"perp:ETH"} (get-in @store [:asset-selector :loaded-icons])))
    (is (= #{"perp:BTC"} (get-in @store [:asset-selector :missing-icons])))
    (is (= {} @pending-statuses))
    (is (nil? @flush-handle))))

(deftest queue-asset-icon-status-ignores-invalid-payloads-test
  (let [store (atom {:asset-selector {:loaded-icons #{}
                                      :missing-icons #{}}})
        pending-statuses (atom {})
        flush-handle (atom nil)
        scheduled? (atom false)]
    (icon-status-runtime/queue-asset-icon-status!
     {:store store
      :payload {:market-key nil :status :loaded}
      :pending-statuses pending-statuses
      :flush-handle flush-handle
      :schedule-animation-frame! (fn [_]
                                   (reset! scheduled? true)
                                   :raf-id)
      :flush-queued-asset-icon-statuses! (fn [_] nil)})
    (is (false? @scheduled?))
    (is (= {} @pending-statuses))
    (is (nil? @flush-handle))))

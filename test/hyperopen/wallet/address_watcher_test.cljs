(ns hyperopen.wallet.address-watcher-test
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [hyperopen.wallet.address-watcher :as watcher]))

(defn- reset-watcher-state! []
  (reset! @#'hyperopen.wallet.address-watcher/address-watcher-state
          {:handlers []
           :current-address nil
           :watching? false
           :pending-subscription nil
           :ws-connected? false}))

(use-fixtures
  :each
  {:before (fn []
             (reset-watcher-state!))
   :after (fn []
            (reset-watcher-state!))})

(deftest sync-current-address-notifies-handlers-test
  (let [store (atom {:wallet {:address "0xabc"}})
        calls (atom [])]
    (with-redefs [hyperopen.wallet.address-watcher/notify-handlers!
                  (fn [old-address new-address]
                    (swap! calls conj [old-address new-address]))]
      (watcher/sync-current-address! store)
      (is (= [[nil "0xabc"]] @calls)))))

(deftest pending-subscription-still-processes-on-connect-test
  (let [store (atom {:wallet {:address "0xabc"}})
        calls (atom [])
        handler-name "address-watcher-pending-test"
        handler (reify watcher/IAddressChangeHandler
                  (on-address-changed [_ old-address new-address]
                    (swap! calls conj [old-address new-address]))
                  (get-handler-name [_] handler-name))]
    (watcher/add-handler! handler)
    (watcher/on-websocket-disconnected!)
    (watcher/sync-current-address! store)
    (is (empty? @calls))
    (watcher/on-websocket-connected!)
    (is (= [[nil "0xabc"]] @calls))
    (watcher/remove-handler! handler-name)))

(deftest webdata2-handler-record-and-protocol-dispatch-test
  (let [calls (atom [])
        handler (watcher/create-webdata2-handler
                 (fn [address]
                   (swap! calls conj [:subscribe address]))
                 (fn [address]
                   (swap! calls conj [:unsubscribe address])))]
    (is (satisfies? watcher/IAddressChangeHandler handler))
    (is (= "webdata2-subscription-handler"
           (watcher/get-handler-name handler)))
    (watcher/on-address-changed handler "0xold" "0xnew")
    (is (= [[:unsubscribe "0xold"]
            [:subscribe "0xnew"]]
           @calls))
    (is (satisfies? watcher/IAddressChangeHandler
                    (watcher/map->WebData2Handler {:unsubscribe-fn (fn [_] nil)
                                                   :subscribe-fn (fn [_] nil)})))))

(deftest start-watching-processes-address-change-through-listener-test
  (let [store (atom {:wallet {:address nil}})
        calls (atom [])
        handler-name "address-watcher-listener-test"
        handler (reify watcher/IAddressChangeHandler
                  (on-address-changed [_ old-address new-address]
                    (swap! calls conj [old-address new-address]))
                  (get-handler-name [_] handler-name))]
    (watcher/add-handler! handler)
    (watcher/on-websocket-connected!)
    (watcher/start-watching! store)
    (swap! store assoc-in [:wallet :address] "0xdef")
    (is (= [[nil "0xdef"]] @calls))
    (watcher/stop-watching! store)
    (watcher/remove-handler! handler-name)))

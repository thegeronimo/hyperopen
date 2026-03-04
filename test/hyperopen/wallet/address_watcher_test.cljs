(ns hyperopen.wallet.address-watcher-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [hyperopen.wallet.address-watcher :as watcher]))

(defn- reset-watcher-state! []
  (reset! @#'hyperopen.wallet.address-watcher/address-watcher-state
          {:handlers []
           :current-address nil
           :watching? false
           :pending-subscription nil
           :ws-connected? false}))

(defn- watcher-state []
  (deref @#'hyperopen.wallet.address-watcher/address-watcher-state))

(use-fixtures
  :each
  {:before (fn []
             (reset-watcher-state!))
   :after (fn []
            (reset-watcher-state!))})

(deftest sync-current-address-notifies-handlers-test
  (let [store (atom {:wallet {:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}})
        calls (atom [])]
    (with-redefs [hyperopen.wallet.address-watcher/notify-handlers!
                  (fn [old-address new-address]
                    (swap! calls conj [old-address new-address]))]
      (watcher/sync-current-address! store)
      (is (= [[nil "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]] @calls)))))

(deftest add-handler-precondition-rejects-invalid-handlers-test
  (is (thrown? js/Error
               (watcher/add-handler! {}))))

(deftest sync-current-address-skips-notify-when-address-is-nil-test
  (let [store (atom {:wallet {:address nil}})
        calls (atom 0)]
    (with-redefs [hyperopen.wallet.address-watcher/notify-handlers!
                  (fn [_old-address _new-address]
                    (swap! calls inc))]
      (watcher/sync-current-address! store)
      (is (= 0 @calls))
      (is (nil? (:current-address (watcher-state)))))))

(deftest pending-subscription-still-processes-on-connect-test
  (let [store (atom {:wallet {:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}})
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
    (is (= [[nil "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]] @calls))
    (watcher/remove-handler! handler-name)))

(deftest notify-handlers-catches-errors-and-continues-other-handlers-test
  (let [calls (atom [])
        logs (atom [])
        throwing-handler (reify watcher/IAddressChangeHandler
                           (on-address-changed [_ _ _]
                             (throw (js/Error. "handler boom")))
                           (get-handler-name [_]
                             "throwing-handler"))
        healthy-handler (reify watcher/IAddressChangeHandler
                          (on-address-changed [_ old-address new-address]
                            (swap! calls conj [old-address new-address]))
                          (get-handler-name [_]
                            "healthy-handler"))]
    (watcher/add-handler! throwing-handler)
    (watcher/add-handler! healthy-handler)
    (watcher/on-websocket-connected!)
    (with-redefs [hyperopen.telemetry/log! (fn [& args]
                                             (swap! logs conj args))]
      (@#'hyperopen.wallet.address-watcher/notify-handlers! "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" "0xcccccccccccccccccccccccccccccccccccccccc"))
    (is (= [["0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" "0xcccccccccccccccccccccccccccccccccccccccc"]] @calls))
    (is (some #(str/includes? (str (first %)) "Error in address change handler")
              @logs))))

(deftest private-address-change-listener-gates-on-watching-and-actual-change-test
  (let [notify-calls (atom [])]
    (with-redefs [hyperopen.wallet.address-watcher/notify-handlers!
                  (fn [old-address new-address]
                    (swap! notify-calls conj [old-address new-address]))]
      (@#'hyperopen.wallet.address-watcher/address-change-listener
       nil nil
       {:wallet {:address "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}}
       {:wallet {:address "0xcccccccccccccccccccccccccccccccccccccccc"}})
      (is (empty? @notify-calls))
      (swap! @#'hyperopen.wallet.address-watcher/address-watcher-state
             assoc :watching? true)
      (@#'hyperopen.wallet.address-watcher/address-change-listener
       nil nil
       {:wallet {:address "0xdddddddddddddddddddddddddddddddddddddddd"}}
       {:wallet {:address "0xdddddddddddddddddddddddddddddddddddddddd"}})
      (is (empty? @notify-calls))
      (@#'hyperopen.wallet.address-watcher/address-change-listener
       nil nil
       {:wallet {:address "0xdddddddddddddddddddddddddddddddddddddddd"}}
       {:wallet {:address "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"}})
      (is (= [["0xdddddddddddddddddddddddddddddddddddddddd" "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"]] @notify-calls)))))

(deftest private-address-change-listener-tracks-effective-address-when-ghost-mode-toggles-test
  (let [notify-calls (atom [])]
    (with-redefs [hyperopen.wallet.address-watcher/notify-handlers!
                  (fn [old-address new-address]
                    (swap! notify-calls conj [old-address new-address]))]
      (swap! @#'hyperopen.wallet.address-watcher/address-watcher-state
             assoc :watching? true)
      (@#'hyperopen.wallet.address-watcher/address-change-listener
       nil nil
       {:wallet {:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
        :account-context {:ghost-mode {:active? false
                                       :address "0xdddddddddddddddddddddddddddddddddddddddd"}}}
       {:wallet {:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
        :account-context {:ghost-mode {:active? true
                                       :address "0xdddddddddddddddddddddddddddddddddddddddd"}}})
      (is (= [["0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
               "0xdddddddddddddddddddddddddddddddddddddddd"]]
             @notify-calls)))))

(deftest process-pending-subscription-noops-when-empty-and-clears-when-processed-test
  (let [calls (atom [])
        handler (reify watcher/IAddressChangeHandler
                  (on-address-changed [_ old-address new-address]
                    (swap! calls conj [old-address new-address]))
                  (get-handler-name [_]
                    "pending-handler"))]
    (watcher/add-handler! handler)
    (@#'hyperopen.wallet.address-watcher/process-pending-subscription!)
    (is (empty? @calls))
    (swap! @#'hyperopen.wallet.address-watcher/address-watcher-state
           assoc :pending-subscription {:old-address "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                                        :new-address "0xcccccccccccccccccccccccccccccccccccccccc"})
    (@#'hyperopen.wallet.address-watcher/process-pending-subscription!)
    (is (= [["0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" "0xcccccccccccccccccccccccccccccccccccccccc"]] @calls))
    (is (nil? (:pending-subscription (watcher-state))))))

(deftest process-pending-subscription-catches-handler-errors-test
  (let [logs (atom [])
        handler (reify watcher/IAddressChangeHandler
                  (on-address-changed [_ _ _]
                    (throw (js/Error. "pending boom")))
                  (get-handler-name [_]
                    "pending-thrower"))]
    (watcher/add-handler! handler)
    (swap! @#'hyperopen.wallet.address-watcher/address-watcher-state
           assoc :pending-subscription {:old-address "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                                        :new-address "0xcccccccccccccccccccccccccccccccccccccccc"})
    (with-redefs [hyperopen.telemetry/log! (fn [& args]
                                             (swap! logs conj args))]
      (@#'hyperopen.wallet.address-watcher/process-pending-subscription!))
    (is (some #(str/includes? (str (first %)) "Error in pending subscription handler")
              @logs))
    (is (nil? (:pending-subscription (watcher-state))))))

(deftest disconnected-notify-handlers-stores-pending-even-for-nil-new-address-test
  (watcher/on-websocket-disconnected!)
  (@#'hyperopen.wallet.address-watcher/notify-handlers! "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" nil)
  (is (= {:old-address "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" :new-address nil}
         (:pending-subscription (watcher-state)))))

(deftest empty-handler-and-no-match-removal-branches-test
  (watcher/on-websocket-connected!)
  (@#'hyperopen.wallet.address-watcher/notify-handlers! nil "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
  (is (nil? (:pending-subscription (watcher-state))))
  (watcher/on-websocket-disconnected!)
  (swap! @#'hyperopen.wallet.address-watcher/address-watcher-state
         assoc :handlers []
         :pending-subscription {:old-address "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                                :new-address "0xcccccccccccccccccccccccccccccccccccccccc"})
  (@#'hyperopen.wallet.address-watcher/process-pending-subscription!)
  (is (nil? (:pending-subscription (watcher-state))))
  (watcher/remove-handler! "missing-handler")
  (is (empty? (:handlers (watcher-state)))))

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
    (watcher/on-address-changed handler "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" "0xcccccccccccccccccccccccccccccccccccccccc")
    (is (= [[:unsubscribe "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]
            [:subscribe "0xcccccccccccccccccccccccccccccccccccccccc"]]
           @calls))
    (is (satisfies? watcher/IAddressChangeHandler
                    (watcher/map->WebData2Handler {:unsubscribe-fn (fn [_] nil)
                                                   :subscribe-fn (fn [_] nil)})))))

(deftest start-and-stop-watching-are-idempotent-test
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
    (watcher/start-watching! store)
    (swap! store assoc-in [:wallet :address] "0xffffffffffffffffffffffffffffffffffffffff")
    (is (= [[nil "0xffffffffffffffffffffffffffffffffffffffff"]] @calls))
    (watcher/stop-watching! store)
    (watcher/stop-watching! store)
    (swap! store assoc-in [:wallet :address] "0x1111111111111111111111111111111111111111")
    (is (= [[nil "0xffffffffffffffffffffffffffffffffffffffff"]] @calls))
    (watcher/remove-handler! handler-name)))

(deftest websocket-disconnect-updates-state-flag-test
  (watcher/on-websocket-connected!)
  (is (true? (:ws-connected? (watcher-state))))
  (watcher/on-websocket-disconnected!)
  (is (false? (:ws-connected? (watcher-state)))))

(ns hyperopen.websocket.user-runtime.refresh-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.platform :as platform]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.websocket.user-runtime.refresh :as refresh-runtime]))

(defn- make-store
  [address]
  (atom {:wallet {:address address}}))

(deftest schedule-account-surface-refresh-after-fill-uses-runtime-timeout-storage-test
  (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        store (make-store address)
        runtime (atom (runtime-state/default-runtime-state))
        scheduled (atom nil)
        cleared (atom [])
        refresh-calls (atom [])]
    (swap! runtime assoc-in [:timeouts :user-account-surface-refresh] :old-timeout)
    (with-redefs [runtime-state/runtime runtime
                  platform/set-timeout! (fn [callback ms]
                                          (reset! scheduled [callback ms])
                                          :new-timeout)
                  platform/clear-timeout! (fn [timeout-id]
                                            (swap! cleared conj timeout-id))
                  refresh-runtime/refresh-account-surfaces-after-user-fill!
                  (fn [store* address*]
                    (swap! refresh-calls conj [store* address*]))]
      (refresh-runtime/schedule-account-surface-refresh-after-fill! store)
      (is (= [:old-timeout] @cleared))
      (is (= :new-timeout
             (get-in @runtime [:timeouts :user-account-surface-refresh])))
      (is (= 250 (second @scheduled)))
      ((first @scheduled))
      (is (nil? (get-in @runtime [:timeouts :user-account-surface-refresh])))
      (is (= [[store address]] @refresh-calls)))))

(deftest scheduled-account-surface-refresh-skips-stale-address-test
  (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        other-address "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        store (make-store address)
        runtime (atom (runtime-state/default-runtime-state))
        scheduled-callback (atom nil)
        refresh-calls (atom [])]
    (with-redefs [runtime-state/runtime runtime
                  platform/set-timeout! (fn [callback _ms]
                                          (reset! scheduled-callback callback)
                                          :timeout-id)
                  platform/clear-timeout! (fn [_] nil)
                  refresh-runtime/refresh-account-surfaces-after-user-fill!
                  (fn [store* address*]
                    (swap! refresh-calls conj [store* address*]))]
      (refresh-runtime/schedule-account-surface-refresh-after-fill! store)
      (swap! store assoc-in [:wallet :address] other-address)
      (@scheduled-callback)
      (is (empty? @refresh-calls)))))

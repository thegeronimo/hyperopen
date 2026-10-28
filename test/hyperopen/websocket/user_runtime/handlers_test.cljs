(ns hyperopen.websocket.user-runtime.handlers-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.platform :as platform]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.websocket.user-runtime.fills :as fill-runtime]
            [hyperopen.websocket.user-runtime.refresh :as refresh-runtime]
            [hyperopen.websocket.user-runtime.handlers :as handlers]))

(def ^:private address
  "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

(deftest open-orders-handler-stores-payload-and-marks-surface-hydrated-test
  (let [store (atom {:wallet {:address address}
                     :orders {:open-orders []
                              :open-orders-hydrated? false}})
        handle-open-orders! (handlers/open-orders-handler store)
        payload {:user address
                 :openOrders [{:coin "SOL"
                               :oid 11}]}]
    (handle-open-orders! {:channel "openOrders"
                          :data payload})
    (is (= payload
           (get-in @store [:orders :open-orders])))
    (is (= true
           (get-in @store [:orders :open-orders-hydrated?])))))

(deftest open-orders-handler-ignores-payloads-for-stale-addresses-test
  (let [store (atom {:wallet {:address address}
                     :orders {:open-orders []
                              :open-orders-hydrated? false}})
        handle-open-orders! (handlers/open-orders-handler store)]
    (handle-open-orders! {:channel "openOrders"
                          :data {:user "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                                 :openOrders [{:coin "BTC"
                                               :oid 22}]}})
    (is (= []
           (get-in @store [:orders :open-orders])))
    (is (= false
           (get-in @store [:orders :open-orders-hydrated?])))))

(deftest open-orders-handler-filters-recently-canceled-oids-and-keeps-active-rows-test
  (let [store (atom {:wallet {:address address}
                     :orders {:open-orders []
                              :open-orders-hydrated? false
                              :recently-canceled-oids #{22}}})
        handle-open-orders! (handlers/open-orders-handler store)]
    (handle-open-orders! {:channel "openOrders"
                          :data {:user address
                                 :openOrders [{:coin "BTC"
                                               :oid 22}
                                              {:coin "ETH"
                                               :oid 23}]}})
    (is (= {:user address
            :openOrders [{:coin "ETH"
                          :oid 23}]}
           (get-in @store [:orders :open-orders])))
    (is (= true
           (get-in @store [:orders :open-orders-hydrated?])))))

(deftest twap-handlers-store-active-history-and-slice-fill-payloads-test
  (let [store (atom {:wallet {:address address}
                     :orders {:twap-states []
                              :twap-history []
                              :twap-slice-fills []}})
        handle-twap-states! (handlers/twap-states-handler store)
        handle-twap-history! (handlers/user-twap-history-handler store)
        handle-twap-fills! (handlers/user-twap-slice-fills-handler store)]
    (handle-twap-states! {:channel "twapStates"
                          :data {:user address
                                 :states [[17 {:coin "BTC"
                                               :sz "1.0"
                                               :timestamp 1700000000000}]]}})
    (is (= [[17 {:coin "BTC"
                 :sz "1.0"
                 :timestamp 1700000000000}]]
           (get-in @store [:orders :twap-states])))

    (handle-twap-history! {:channel "userTwapHistory"
                           :data {:user address
                                  :isSnapshot true
                                  :history [{:time 1700000000
                                             :status {:status "finished"}
                                             :state {:coin "BTC"
                                                     :sz "1.0"
                                                     :executedSz "1.0"
                                                     :executedNtl "100.0"
                                                     :minutes 30
                                                     :timestamp 1700000000000}}]}})
    (is (= 1
           (count (get-in @store [:orders :twap-history]))))

    (handle-twap-fills! {:channel "userTwapSliceFills"
                         :data {:user address
                                :isSnapshot true
                                :twapSliceFills [{:fill {:tid 99
                                                         :coin "BTC"
                                                         :time 1700000000000
                                                         :px "100"
                                                         :sz "0.1"}}]}})
    (is (= [{:tid 99
             :coin "BTC"
             :time 1700000000000
             :px "100"
             :sz "0.1"}]
           (get-in @store [:orders :twap-slice-fills])))))

(deftest twap-handlers-ignore-payloads-for-stale-addresses-test
  (let [store (atom {:wallet {:address address}
                     :orders {:twap-states []
                              :twap-history []
                              :twap-slice-fills []}})
        handle-twap-states! (handlers/twap-states-handler store)
        handle-twap-history! (handlers/user-twap-history-handler store)
        handle-twap-fills! (handlers/user-twap-slice-fills-handler store)
        stale-address "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]
    (handle-twap-states! {:channel "twapStates"
                          :data {:user stale-address
                                 :states [[1 {:coin "ETH"}]]}})
    (handle-twap-history! {:channel "userTwapHistory"
                           :data {:user stale-address
                                  :history [{:state {:coin "ETH"}}]}})
    (handle-twap-fills! {:channel "userTwapSliceFills"
                         :data {:user stale-address
                                :twapSliceFills [{:fill {:tid 1 :coin "ETH"}}]}})
    (is (= [] (get-in @store [:orders :twap-states])))
    (is (= [] (get-in @store [:orders :twap-history])))
    (is (= [] (get-in @store [:orders :twap-slice-fills])))))

(deftest user-fills-handler-merges-novel-fills-schedules-refresh-and-skips-toasts-when-disabled-test
  (let [store (atom {:wallet {:address address}
                     :orders {:fills []}
                     :trading-settings {:fill-alerts-enabled? false}})
        handle-user-fills! (handlers/user-fills-handler store)
        toast-calls (atom [])
        refresh-calls (atom [])]
    (with-redefs [fill-runtime/show-user-fill-toast!
                  (fn [& args]
                    (swap! toast-calls conj args))
                  refresh-runtime/schedule-account-surface-refresh-after-fill!
                  (fn [store-arg]
                    (swap! refresh-calls conj store-arg))]
      (handle-user-fills! {:channel "userFills"
                           :data {:user address
                                  :fills [{:fillId "fill-1"
                                           :coin "HYPE"
                                           :side "B"
                                           :sz "1.0"
                                           :px "30.0"}]}})
      (is (= 1 (count (get-in @store [:orders :fills]))))
      (is (= "fill-1" (get-in @store [:orders :fills 0 :fillId])))
      (is (= 1 (count @refresh-calls)))
      (is (empty? @toast-calls)))))

(deftest clearinghouse-state-handler-routes-named-dex-payloads-to-perp-dex-bucket-test
  (let [store (atom {:wallet {:address address}
                     :perp-dex-clearinghouse {}})
        handle! (handlers/clearinghouse-state-handler store)
        state {:marginSummary {:accountValue "100"}
               :assetPositions [{:position {:coin "xyz:AAPL" :szi "0.5"}}]}]
    (handle! {:channel "clearinghouseState"
              :data {:dex "xyz"
                     :user address
                     :clearinghouseState state}})
    (is (= state
           (get-in @store [:perp-dex-clearinghouse "xyz"])))
    (is (nil? (get-in @store [:webdata2 :clearinghouseState])))))

(deftest clearinghouse-state-handler-routes-blank-dex-payloads-to-base-bucket-test
  (let [store (atom {:wallet {:address address}
                     :webdata2 {:clearinghouseState nil
                                :other-webdata2-key :untouched}})
        handle! (handlers/clearinghouse-state-handler store)
        state {:marginSummary {:accountValue "74"}
               :assetPositions [{:position {:coin "WLD" :szi "-58.9"}}
                                {:position {:coin "ENS" :szi "0.06"}}]}]
    (handle! {:channel "clearinghouseState"
              :data {:dex ""
                     :user address
                     :clearinghouseState state}})
    (is (= state
           (get-in @store [:webdata2 :clearinghouseState])))
    (is (= :untouched
           (get-in @store [:webdata2 :other-webdata2-key])))
    (is (empty? (get @store :perp-dex-clearinghouse)))))

(deftest clearinghouse-state-handler-drops-frames-without-dex-or-state-test
  (let [store (atom {:wallet {:address address}})
        handle! (handlers/clearinghouse-state-handler store)]
    ;; No dex field at all: unroutable, dropped.
    (handle! {:channel "clearinghouseState"
              :data {:user address
                     :clearinghouseState {:marginSummary {}}}})
    ;; Blank dex without a nested clearinghouseState map: malformed, dropped.
    (handle! {:channel "clearinghouseState"
              :data {:dex ""
                     :user address}})
    (is (nil? (get-in @store [:webdata2 :clearinghouseState])))
    (is (nil? (get @store :perp-dex-clearinghouse)))))

(deftest clearinghouse-state-handler-ignores-blank-dex-payloads-for-stale-addresses-test
  (let [store (atom {:wallet {:address address}})
        handle! (handlers/clearinghouse-state-handler store)]
    (handle! {:channel "clearinghouseState"
              :data {:dex ""
                     :user "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                     :clearinghouseState {:marginSummary {:accountValue "1"}}}})
    (is (nil? (get-in @store [:webdata2 :clearinghouseState])))))

;; ---------------------------------------------------------------------------
;; Raw evidence taps
;; ---------------------------------------------------------------------------

(def ^:private other-address
  "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

(defn- recording-tap
  [calls]
  (fn [store msg received-at-ms]
    (swap! calls conj {:store store :msg msg :received-at-ms received-at-ms})))

(defn- fill-row
  [tid]
  {:tid tid :coin "HYPE" :side "B" :sz "1.0" :px "30.0" :time (+ 1700000000000 tid)})

(deftest user-fills-tap-receives-raw-messages-before-display-filtering-test
  (with-redefs [platform/now-ms (fn [] 4242)
                fill-runtime/show-user-fill-toast! (fn [& _] nil)
                refresh-runtime/schedule-account-surface-refresh-after-fill! (fn [_] nil)]
    (testing "an empty snapshot reaches the tap although the display path skips it"
      (let [store (atom {:wallet {:address address} :orders {:fills [(fill-row 1)]}})
            calls (atom [])
            handle! (handlers/user-fills-handler store {:on-raw-message (recording-tap calls)})
            msg {:channel "userFills" :data {:user address :isSnapshot true :fills []}}]
        (handle! msg)
        (is (= 1 (count @calls)))
        (is (identical? msg (:msg (first @calls))))
        (is (identical? store (:store (first @calls))))
        (is (= 4242 (:received-at-ms (first @calls))))
        (is (= [(fill-row 1)] (get-in @store [:orders :fills])))))
    (testing "a 250-row increment reaches the tap whole; the display keeps 200"
      (let [store (atom {:wallet {:address address} :orders {:fills []}})
            calls (atom [])
            handle! (handlers/user-fills-handler store {:on-raw-message (recording-tap calls)})
            msg {:channel "userFills" :data {:user address :fills (mapv fill-row (range 250))}}]
        (handle! msg)
        (is (identical? msg (:msg (first @calls))))
        (is (= 250 (count (get-in (:msg (first @calls)) [:data :fills]))))
        (is (= 200 (count (get-in @store [:orders :fills]))))))
    (testing "a message for another user reaches the tap; the display ignores it"
      (let [store (atom {:wallet {:address address} :orders {:fills []}})
            calls (atom [])
            handle! (handlers/user-fills-handler store {:on-raw-message (recording-tap calls)})
            msg {:channel "userFills" :data {:user other-address :fills [(fill-row 7)]}}]
        (handle! msg)
        (is (identical? msg (:msg (first @calls))))
        (is (= [] (get-in @store [:orders :fills])))))
    (testing "messages on other channels never reach the tap"
      (let [store (atom {:wallet {:address address} :orders {:fills []}})
            calls (atom [])
            handle! (handlers/user-fills-handler store {:on-raw-message (recording-tap calls)})]
        (handle! {:channel "openOrders" :data {:user address :orders []}})
        (is (empty? @calls))))))

(deftest open-orders-tap-receives-raw-messages-before-cancel-guard-pruning-test
  (with-redefs [platform/now-ms (fn [] 4343)]
    (testing "a recently canceled oid reaches the tap; the display prunes it"
      (let [store (atom {:wallet {:address address}
                         :orders {:open-orders []
                                  :open-orders-hydrated? false
                                  :recently-canceled-oids #{22}}})
            calls (atom [])
            handle! (handlers/open-orders-handler store {:on-raw-message (recording-tap calls)})
            msg {:channel "openOrders"
                 :data {:user address
                        :openOrders [{:coin "BTC" :oid 22} {:coin "ETH" :oid 23}]}}]
        (handle! msg)
        (is (= 1 (count @calls)))
        (is (identical? msg (:msg (first @calls))))
        (is (= 4343 (:received-at-ms (first @calls))))
        (is (= [22 23] (mapv :oid (get-in (:msg (first @calls)) [:data :openOrders]))))
        (is (= [23] (mapv :oid (get-in @store [:orders :open-orders :openOrders]))))))
    (testing "a message for another user reaches the tap; the display ignores it"
      (let [store (atom {:wallet {:address address}
                         :orders {:open-orders [] :open-orders-hydrated? false}})
            calls (atom [])
            handle! (handlers/open-orders-handler store {:on-raw-message (recording-tap calls)})
            msg {:channel "openOrders" :data {:user other-address :openOrders [{:coin "BTC" :oid 5}]}}]
        (handle! msg)
        (is (identical? msg (:msg (first @calls))))
        (is (= [] (get-in @store [:orders :open-orders])))
        (is (= false (get-in @store [:orders :open-orders-hydrated?])))))))

(defn- run-fills-handler
  "Runs two fill increments through the handler built with `opts` and
  returns the resulting store, toast calls, refresh calls and log calls."
  [opts]
  (let [store (atom {:wallet {:address address}
                     :orders {:fills []}
                     :trading-settings {:fill-alerts-enabled? true}})
        toasts (atom [])
        refreshes (atom 0)
        logs (atom [])]
    (with-redefs [fill-runtime/show-user-fill-toast! (fn [_ rows] (swap! toasts conj (mapv :tid rows)))
                  refresh-runtime/schedule-account-surface-refresh-after-fill! (fn [_] (swap! refreshes inc))
                  telemetry/log! (fn [& args] (swap! logs conj (vec args)))]
      (let [handle! (if opts (handlers/user-fills-handler store opts) (handlers/user-fills-handler store))]
        (handle! {:channel "userFills" :data {:user address :fills [(fill-row 1)]}})
        (handle! {:channel "userFills" :data {:user address :fills [(fill-row 1) (fill-row 2)]}})))
    {:state @store :toasts @toasts :refreshes @refreshes :logs @logs}))

(defn- run-open-orders-handler
  [opts]
  (let [store (atom {:wallet {:address address}
                     :orders {:open-orders [] :open-orders-hydrated? false
                              :recently-canceled-oids #{22}}})
        logs (atom [])]
    (with-redefs [telemetry/log! (fn [& args] (swap! logs conj (vec args)))]
      (let [handle! (if opts (handlers/open-orders-handler store opts) (handlers/open-orders-handler store))]
        (handle! {:channel "openOrders"
                  :data {:user address :openOrders [{:coin "BTC" :oid 22} {:coin "ETH" :oid 23}]}})))
    {:state @store :logs @logs}))

(deftest throwing-tap-leaves-the-display-path-unchanged-test
  (let [throwing {:on-raw-message (fn [_ _ _] (throw (js/Error. "tap boom")))}]
    (testing "userFills: store, toasts and refresh scheduling match the untapped handler"
      (let [plain (run-fills-handler nil)
            tapped (run-fills-handler throwing)]
        (is (= (:state plain) (:state tapped)))
        (is (= [[1] [2]] (:toasts plain) (:toasts tapped)))
        (is (= 2 (:refreshes plain) (:refreshes tapped)))
        (is (empty? (:logs plain)))
        (is (= 2 (count (:logs tapped))) "each failure is logged once")
        (is (every? #(= "userFills" (second %)) (:logs tapped)))))
    (testing "openOrders: the pruned display payload matches the untapped handler"
      (let [plain (run-open-orders-handler nil)
            tapped (run-open-orders-handler throwing)]
        (is (= (:state plain) (:state tapped)))
        (is (= [23] (mapv :oid (get-in (:state tapped) [:orders :open-orders :openOrders]))))
        (is (= 1 (count (:logs tapped))))))
    (testing "a nil tap behaves like the one-arity handler"
      (is (= (:state (run-fills-handler nil))
             (:state (run-fills-handler {:on-raw-message nil})))))))

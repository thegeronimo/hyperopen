(ns hyperopen.websocket.account-surface-service-coverage-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [async deftest is]]
            [hyperopen.account.context :as account-context]
            [hyperopen.account.surface-service :as surface-service]
            [hyperopen.platform :as platform]))

(defn- indexed-address
  [n]
  (str "0x" (.padStart (str n) 40 "0")))

(defn- resolved-thenable
  [value]
  #js {:then (fn [on-resolve]
               (on-resolve value)
               #js {:catch (fn [_] nil)})})

(defn- rejected-thenable
  [err]
  #js {:then (fn [_]
               #js {:catch (fn [on-reject]
                             (on-reject err)
                             nil)})})

(deftest ws-account-context-watchlist-helper-coverage-test
  (let [primary (indexed-address 1)
        secondary (indexed-address 2)
        long-label (apply str (repeat 80 "L"))
        normalized (account-context/normalize-watchlist
                    [{:address primary :label "Primary"}
                     {:address primary}
                     {"address" (str/upper-case secondary)
                      "label" " Treasury "}
                     "bad"])
        oversized (account-context/normalize-watchlist
                   (mapv (fn [n]
                           {:address (indexed-address n)
                            :label (str "Label-" n)})
                         (range 1 55)))]
    (is (= 64 (count (account-context/normalize-watchlist-label long-label))))
    (is (nil? (account-context/normalize-watchlist-label "  ")))
    (is (= {:address secondary
            :label "Treasury"}
           (account-context/normalize-watchlist-entry
            {"address" (str/upper-case secondary)
             "label" " Treasury "})))
    (is (nil? (account-context/normalize-watchlist-entry {:address "bad"})))
    (is (= [{:address primary :label "Primary"}
            {:address secondary :label "Treasury"}]
           normalized))
    (is (= {:address primary :label "Primary"}
           (account-context/watchlist-entry-by-address normalized
                                                      (str/upper-case primary))))
    (is (nil? (account-context/watchlist-entry-by-address normalized "bad")))
    (is (= [{:address primary :label "Primary"}
            {:address secondary :label "Secondary"}]
           (account-context/upsert-watchlist-entry
            [{:address primary :label "Primary"}]
            secondary
            "Secondary")))
    (is (= normalized
           (account-context/upsert-watchlist-entry normalized "bad" "Ignored")))
    (is (= normalized
           (account-context/upsert-watchlist-entry normalized secondary "" true)))
    (is (= normalized
           (account-context/remove-watchlist-entry normalized "bad")))
    (is (= [{:address primary :label "Primary"}]
           (account-context/remove-watchlist-entry normalized secondary)))
    (is (= 50 (count oversized)))
    (is (= (indexed-address 50)
           (:address (last oversized))))))

(deftest ws-account-context-mode-helper-coverage-test
  (let [owner (indexed-address 9)
        spectate (indexed-address 10)
        active-state {:wallet {:address (str/upper-case owner)}
                      :account-context {:spectate-mode {:active? true
                                                        :address (str/upper-case spectate)}}}
        fallback-state {:wallet {:address owner}
                        :account-context {:spectate-mode {:active? true
                                                          :address "not-an-address"}}}
        default-state (account-context/default-account-context-state)]
    (is (= owner (account-context/owner-address active-state)))
    (is (= spectate (account-context/spectate-address active-state)))
    (is (true? (account-context/spectate-mode-active? active-state)))
    (is (= spectate (account-context/effective-account-address active-state)))
    (is (false? (account-context/mutations-allowed? active-state)))
    (is (= account-context/spectate-mode-read-only-message
           (account-context/mutations-blocked-message active-state)))
    (is (false? (account-context/spectate-mode-active? fallback-state)))
    (is (= owner (account-context/effective-account-address fallback-state)))
    (is (true? (account-context/mutations-allowed? fallback-state)))
    (is (nil? (account-context/mutations-blocked-message fallback-state)))
    (is (= {:active? false
            :address nil
            :started-at-ms nil}
           (:spectate-mode default-state)))
    (is (= "" (get-in default-state [:spectate-ui :search])))
    (is (= [] (:watchlist default-state)))
    (is (false? (:watchlist-loaded? default-state)))))

(deftest ws-account-surface-service-schedule-fallback-coverage-test
  (let [address (indexed-address 21)
        scheduled-callback (atom nil)
        scheduled-delays (atom [])
        fetch-calls (atom [])
        store (atom {:wallet {:address address}})
        set-timeout-fn (fn [callback delay-ms]
                         (reset! scheduled-callback callback)
                         (swap! scheduled-delays conj delay-ms)
                         :timeout-id)]
    (surface-service/schedule-stream-backed-fallback!
     {:store store
      :address address
      :topic "openOrders"
      :fetch-fn (fn [_store fetch-address opts]
                  (swap! fetch-calls conj [fetch-address opts]))
      :startup-stream-backfill-delay-ms -5
      :set-timeout-fn set-timeout-fn})
    (is (= [0] @scheduled-delays))
    (@scheduled-callback)
    (is (= [[address {}]] @fetch-calls))
    (reset! scheduled-callback nil)
    (reset! fetch-calls [])
    (surface-service/schedule-stream-backed-fallback!
     {:store store
      :address address
      :topic "openOrders"
      :fetch-fn (fn [_store fetch-address opts]
                  (swap! fetch-calls conj [fetch-address opts]))
      :opts {:priority :high}
      :set-timeout-fn set-timeout-fn})
    (swap! store assoc
           :websocket {:health {:transport {:state :connected
                                            :freshness :live}
                                :streams {["openOrders" nil address nil nil]
                                          {:topic "openOrders"
                                           :status :live
                                           :subscribed? true
                                           :descriptor {:type "openOrders"
                                                        :user address}}}}})
    (@scheduled-callback)
    (is (empty? @fetch-calls))
    (reset! scheduled-callback nil)
    (surface-service/schedule-stream-backed-fallback!
     {:store store
      :address ""
      :topic "openOrders"
      :fetch-fn (fn [& _] nil)
      :set-timeout-fn set-timeout-fn})
    (surface-service/schedule-stream-backed-fallback!
     {:store store
      :address address
      :topic nil
      :fetch-fn (fn [& _] nil)
      :set-timeout-fn set-timeout-fn})
    (surface-service/schedule-stream-backed-fallback!
     {:store store
      :address address
      :topic "openOrders"
      :fetch-fn :not-a-function
      :set-timeout-fn set-timeout-fn})
    (is (nil? @scheduled-callback))))

(deftest ws-account-surface-service-bootstrap-default-stage-b-coverage-test
  (let [address (indexed-address 31)
        timeout-delays (atom [])
        open-orders-calls (atom [])
        fills-calls (atom [])
        spot-calls (atom [])
        abstraction-calls (atom [])
        portfolio-calls (atom [])
        fees-calls (atom [])
        funding-calls (atom [])
        sync-calls (atom [])
        clearinghouse-calls (atom [])
        store (atom {:wallet {:address address}
                     :websocket {:migration-flags {:startup-bootstrap-ws-first? false}}})]
    (with-redefs [platform/set-timeout! (fn [f delay-ms]
                                          (swap! timeout-delays conj delay-ms)
                                          (f)
                                          :timeout-id)]
      (surface-service/bootstrap-account-surfaces!
       {:store store
        :address address
        :per-dex-stagger-ms 5
        :startup-funding-request-opts {:priority :seed}
        :fetch-frontend-open-orders! (fn [_store fetch-address opts]
                                       (swap! open-orders-calls conj [fetch-address opts]))
        :fetch-user-fills! (fn [_store fetch-address opts]
                             (swap! fills-calls conj [fetch-address opts]))
        :fetch-spot-clearinghouse-state! (fn [_store fetch-address opts]
                                           (swap! spot-calls conj [fetch-address opts]))
        :fetch-user-abstraction! (fn [_store fetch-address opts]
                                   (swap! abstraction-calls conj [fetch-address opts]))
        :fetch-portfolio! (fn [_store fetch-address opts]
                            (swap! portfolio-calls conj [fetch-address opts]))
        :fetch-user-fees! (fn [_store fetch-address opts]
                            (swap! fees-calls conj [fetch-address opts]))
        :fetch-and-merge-funding-history! (fn [_store fetch-address opts]
                                            (swap! funding-calls conj [fetch-address opts]))
        :ensure-perp-dexs! (fn [_store _opts]
                             (resolved-thenable ["dex-a" "" nil "dex-b"]))
        :sync-perp-dex-clearinghouse-subscriptions! (fn [sync-address dex-names]
                                                      (swap! sync-calls conj [sync-address dex-names]))
        :fetch-clearinghouse-state! (fn [_store fetch-address dex opts]
                                      (swap! clearinghouse-calls conj [fetch-address dex opts]))}))
    (is (= [[address {:priority :high}]
            [address {:dex "dex-a" :priority :low}]
            [address {:dex "dex-b" :priority :low}]]
           @open-orders-calls))
    (is (= [[address {:priority :high}]] @fills-calls))
    (is (= [[address {:priority :high}]] @spot-calls))
    (is (= [[address {:priority :high}]] @abstraction-calls))
    (is (= [[address {:priority :high}]] @portfolio-calls))
    (is (= [[address {:priority :high}]] @fees-calls))
    (is (= [[address {:priority :seed}]] @funding-calls))
    (is (= [[address ["dex-a" "dex-b"]]] @sync-calls))
    (is (= [[address "dex-a" {:priority :low}]
            [address "dex-b" {:priority :low}]]
           @clearinghouse-calls))
    (is (= [5 10] @timeout-delays))))

(deftest ws-account-surface-service-order-mutation-refresh-coverage-test
  (async done
    (let [address (indexed-address 41)
          open-orders-calls (atom [])
          default-clearinghouse-calls (atom [])
          perp-dex-calls (atom [])
          store (atom {:wallet {:address address}})]
      (surface-service/refresh-after-order-mutation!
       {:store store
        :address address
        :ensure-perp-dexs! (fn [_store _opts]
                             (resolved-thenable ["dex-a" "" nil "dex-b"]))
        :refresh-open-orders! (fn [_store refresh-address refresh-dex opts]
                                (swap! open-orders-calls conj [refresh-address refresh-dex opts]))
        :refresh-default-clearinghouse! (fn [_store refresh-address opts]
                                          (swap! default-clearinghouse-calls conj [refresh-address opts]))
        :refresh-perp-dex-clearinghouse! (fn [_store refresh-address refresh-dex opts]
                                           (swap! perp-dex-calls conj [refresh-address refresh-dex opts]))})
      (js/setTimeout
       (fn []
         (is (= [[address nil {:priority :high}]
                 [address "dex-a" {:priority :low}]
                 [address "dex-b" {:priority :low}]]
                @open-orders-calls))
         (is (= [[address {:priority :high}]]
                @default-clearinghouse-calls))
         (is (= [[address "dex-a" {:priority :low}]
                 [address "dex-b" {:priority :low}]]
                @perp-dex-calls))
         (done))
       0))))

(deftest ws-account-surface-service-order-mutation-refreshes-dex-open-orders-when-generic-stream-live-test
  (async done
    (let [address (indexed-address 43)
          open-orders-calls (atom [])
          default-clearinghouse-calls (atom [])
          perp-dex-calls (atom [])
          store (atom
                 {:wallet {:address address}
                  :websocket {:health {:transport {:state :connected
                                                   :freshness :live}
                                       :streams {["openOrders" nil address nil nil]
                                                 {:topic "openOrders"
                                                  :status :live
                                                  :subscribed? true
                                                  :descriptor {:type "openOrders"
                                                               :user address}}}}}})]
      (surface-service/refresh-after-order-mutation!
       {:store store
        :address address
        :ensure-perp-dexs! (fn [_store _opts]
                             (resolved-thenable ["dex-a" "" nil "dex-b"]))
        :refresh-open-orders! (fn [_store refresh-address refresh-dex opts]
                                (swap! open-orders-calls conj [refresh-address refresh-dex opts]))
        :refresh-default-clearinghouse! (fn [_store refresh-address opts]
                                          (swap! default-clearinghouse-calls conj [refresh-address opts]))
        :refresh-perp-dex-clearinghouse! (fn [_store refresh-address refresh-dex opts]
                                           (swap! perp-dex-calls conj [refresh-address refresh-dex opts]))})
      (js/setTimeout
       (fn []
         (is (= [[address "dex-a" {:priority :low}]
                 [address "dex-b" {:priority :low}]]
                @open-orders-calls))
         (is (= [[address {:priority :high}]]
                @default-clearinghouse-calls))
         (is (= [[address "dex-a" {:priority :low}]
                 [address "dex-b" {:priority :low}]]
                @perp-dex-calls))
         (done))
       0))))

(deftest ws-account-surface-service-user-fill-refresh-coverage-test
  (let [address (indexed-address 45)
        dex "vault"
        open-orders-calls (atom [])
        default-clearinghouse-calls (atom [])
        spot-calls (atom [])
        perp-dex-calls (atom [])
        sync-calls (atom [])
        store (atom {:wallet {:address address}
                     :router {:path "/trade"}
                     :account-info {:selected-tab :balances}
                     :perp-dex-clearinghouse {dex {:account-value "1"}}
                     :websocket {:health {:transport {:state :connected
                                                      :freshness :live}
                                          :streams {["clearinghouseState" nil address dex nil]
                                                    {:topic "clearinghouseState"
                                                     :status :idle
                                                     :subscribed? true
                                                     :descriptor {:type "clearinghouseState"
                                                                  :user address
                                                                  :dex dex}}}}}})]
    (surface-service/refresh-after-user-fill!
     {:store store
      :address address
      :ensure-perp-dexs! (fn [_store _opts]
                           (resolved-thenable [dex]))
      :sync-perp-dex-clearinghouse-subscriptions! (fn [sync-address dex-names]
                                                    (swap! sync-calls conj [sync-address dex-names]))
      :refresh-open-orders! (fn [_store refresh-address refresh-dex opts]
                              (swap! open-orders-calls conj [refresh-address refresh-dex opts]))
      :refresh-default-clearinghouse! (fn [_store refresh-address opts]
                                        (swap! default-clearinghouse-calls conj [refresh-address opts]))
      :refresh-spot-clearinghouse! (fn [_store refresh-address opts]
                                     (swap! spot-calls conj [refresh-address opts]))
      :refresh-perp-dex-clearinghouse! (fn [_store refresh-address refresh-dex opts]
                                         (swap! perp-dex-calls conj [refresh-address refresh-dex opts]))})
    (is (= [[address nil {:priority :high}]
            [address dex {:priority :low}]]
           @open-orders-calls))
    (is (= [[address {:priority :high}]]
           @default-clearinghouse-calls))
    (is (= [[address {:priority :high
                      :force-refresh? true}]]
           @spot-calls))
    (is (= [] @perp-dex-calls))
    (is (= [[address [dex]]]
           @sync-calls))))

(deftest ws-account-surface-service-order-mutation-error-coverage-test
  (async done
    (let [address (indexed-address 51)
          log-calls (atom [])
          store (atom {:wallet {:address address}})]
      (surface-service/refresh-after-order-mutation!
       {:store store
        :address address
        :ensure-perp-dexs! (fn [_store _opts]
                             (rejected-thenable (js/Error. "ensure failed")))
        :log-fn (fn [& args]
                  (swap! log-calls conj args))})
      (js/setTimeout
       (fn []
         (js/setTimeout
          (fn []
            (is (= 1 (count @log-calls)))
            (is (= "Error refreshing per-dex account surfaces after order mutation:"
                   (ffirst @log-calls)))
            (is (= "ensure failed"
                   (.-message (second (first @log-calls)))))
            (done))
          0))
       0))))

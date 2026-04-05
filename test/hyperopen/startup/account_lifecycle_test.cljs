(ns hyperopen.startup.account-lifecycle-test
  (:require [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop :include-macros true]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.account.lifecycle-invariants :as lifecycle-invariants]
            [hyperopen.startup.runtime :as startup-runtime]))

(def ^:private address-a
  "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

(def ^:private address-b
  "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

(defn- stale-account-surface-state
  [address]
  {:webdata2 {:clearinghouseState {:assetPositions [{:position {:coin "BTC"
                                                                 :user address}}]}}
   :orders {:open-orders [{:coin "BTC" :oid 1}]
            :open-orders-hydrated? true
            :open-orders-snapshot [{:coin "BTC" :oid 2}]
            :open-orders-snapshot-by-dex {"dex-a" [{}]}
            :open-error "stale-open-error"
            :open-error-category :stale
            :fills [{:tid 3}]
            :fills-error "stale-fills-error"
            :fills-error-category :stale
            :fundings-raw [{:time 4}]
            :fundings [{:time 5}]
            :order-history [{:oid 6}]
            :ledger [{:delta 7}]
            :twap-states [{:coin "BTC"}]
            :twap-history [{:time 8}]
            :twap-slice-fills [{:tid 9}]
            :pending-cancel-oids #{10}}
   :account-info {:funding-history {:loading? true
                                    :error "stale-funding-history-error"}
                  :order-history {:loading? true
                                  :error "stale-order-history-error"
                                  :loaded-at-ms 1700000000000
                                  :loaded-for-address address}}
   :spot {:clearinghouse-state {:balances [{:coin "USDC"}]}
          :loading-balances? true
          :error "stale-spot-error"
          :error-category :stale}
   :perp-dex-clearinghouse {"dex-a" {:assetPositions []}}
   :perp-dex-clearinghouse-error "stale-perp-error"
   :perp-dex-clearinghouse-error-category :stale
   :portfolio {:summary-by-key {:day {:pnl 10}}
               :user-fees {:dailyUserVlm [[0 1]]}
               :ledger-updates [{:delta 1}]
               :loading? true
               :user-fees-loading? true
               :error "stale-portfolio-error"
               :user-fees-error "stale-user-fees-error"
               :ledger-error "stale-ledger-error"
               :loaded-at-ms 1700000000001
               :user-fees-loaded-at-ms 1700000000002
               :ledger-loaded-at-ms 1700000000003}
   :account {:mode :unified
             :abstraction-raw "raw"}})

(defn- seed-stale-account-surfaces!
  [store address]
  (swap! store merge (stale-account-surface-state address)))

(defn- sequence-preserves-disconnected-clear-invariant?
  [addresses]
  (let [store (atom {:router {:path "/trade"}})
        startup-runtime-atom (atom {:bootstrapped-address nil})
        handlers (atom [])
        bootstrap-calls (atom [])]
    (startup-runtime/install-address-handlers!
     {:store store
      :startup-runtime startup-runtime-atom
      :bootstrap-account-data! (fn [new-address]
                                 (swap! bootstrap-calls conj new-address)
                                 (swap! startup-runtime-atom assoc :bootstrapped-address new-address)
                                 (seed-stale-account-surfaces! store new-address))
      :init-with-webdata2! (fn [& _] nil)
      :add-handler! (fn [handler]
                      (swap! handlers conj handler))
      :sync-current-address! (fn [_store] nil)
      :create-user-handler (fn [_subscribe-fn _unsubscribe-fn]
                             {:kind :user-handler})
      :subscribe-user! (fn [& _] nil)
      :unsubscribe-user! (fn [& _] nil)
      :subscribe-webdata2! (fn [& _] nil)
      :unsubscribe-webdata2! (fn [& _] nil)
      :address-handler-reify (fn [on-change handler-name]
                               {:kind :address-handler
                                :name handler-name
                                :on-change on-change})
      :address-handler-name "startup-account-bootstrap-handler"
      :sync-current-address-on-install? false})
    (let [address-handler (last @handlers)]
      (every?
       true?
       (map (fn [address]
              (when (nil? address)
                (seed-stale-account-surfaces! store "stale"))
              ((:on-change address-handler) address)
              (if (nil? address)
                (and (nil? (:bootstrapped-address @startup-runtime-atom))
                     (true? (lifecycle-invariants/no-effective-account-surfaces-cleared?
                             @store)))
                (= address (last @bootstrap-calls))))
            addresses)))))

(deftest install-address-handlers-preserves-disconnected-clear-invariant-across-generated-address-sequences-test
  (let [property
        (prop/for-all [addresses (gen/vector (gen/elements [nil address-a address-b]) 1 20)]
          (sequence-preserves-disconnected-clear-invariant? addresses))
        result (tc/quick-check 80 property)]
    (is (:pass? result) (pr-str result))))

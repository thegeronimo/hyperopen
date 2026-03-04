(ns hyperopen.api.compat-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.compat :as api-compat]
            [hyperopen.api.gateway.account :as account-gateway]
            [hyperopen.api.gateway.market :as market-gateway]
            [hyperopen.api.gateway.orders :as order-gateway]
            [hyperopen.api.projections :as api-projections]))

(deftest fetch-user-fills-projects-rows-to-store-test
  (async done
    (let [store (atom {:orders {}})
          deps {:log-fn (fn [& _] nil)
                :request-user-fills! (fn [_address _opts]
                                       (js/Promise.resolve [{:tid 1 :coin "BTC"}]))}]
      (-> (api-compat/fetch-user-fills! deps store "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" {:priority :high})
          (.then (fn [rows]
                   (is (= [{:tid 1 :coin "BTC"}] rows))
                   (is (= rows (get-in @store [:orders :fills])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest fetch-frontend-open-orders-projects-by-dex-test
  (async done
    (let [store (atom {:orders {}})
          calls (atom [])
          deps {:log-fn (fn [& _] nil)
                :request-frontend-open-orders! (fn [_address opts]
                                                 (swap! calls conj opts)
                                                 (js/Promise.resolve [{:oid 7 :coin "ETH"}]))}]
      (-> (api-compat/fetch-frontend-open-orders! deps store "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" "dex-a" {:priority :high})
          (.then (fn [rows]
                   (is (= [{:oid 7 :coin "ETH"}] rows))
                   (is (= [{:dex "dex-a"
                            :priority :high}]
                          @calls))
                   (is (= rows
                          (get-in @store [:orders :open-orders-snapshot-by-dex "dex-a"])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest fetch-frontend-open-orders-defaults-to-empty-opts-test
  (async done
    (let [store (atom {:orders {}})
          calls (atom [])
          deps {:log-fn (fn [& _] nil)
                :request-frontend-open-orders! (fn [_address opts]
                                                 (swap! calls conj opts)
                                                 (js/Promise.resolve [{:oid 8 :coin "BTC"}]))}]
      (-> (api-compat/fetch-frontend-open-orders! deps store "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
          (.then (fn [rows]
                   (is (= [{:oid 8 :coin "BTC"}] rows))
                   (is (= [{}] @calls))
                   (is (= rows (get-in @store [:orders :open-orders-snapshot])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest fetch-frontend-open-orders-ignores-empty-dex-override-test
  (async done
    (let [store (atom {:orders {}})
          calls (atom [])
          deps {:log-fn (fn [& _] nil)
                :request-frontend-open-orders! (fn [_address opts]
                                                 (swap! calls conj opts)
                                                 (js/Promise.resolve [{:oid 9 :coin "SOL"}]))}]
      (-> (api-compat/fetch-frontend-open-orders! deps store "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" "" {:priority :high})
          (.then (fn [rows]
                   (is (= [{:oid 9 :coin "SOL"}] rows))
                   (is (= [{:priority :high}] @calls))
                   (is (= rows (get-in @store [:orders :open-orders-snapshot])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest fetch-user-abstraction-normalizes-and-projects-account-test
  (async done
    (let [store (atom {:wallet {:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                       :account {:mode :classic :abstraction-raw nil}})
          deps {:log-fn (fn [& _] nil)
                :request-user-abstraction! (fn [_address _opts]
                                             (js/Promise.resolve "portfolioMargin"))}]
      (-> (api-compat/fetch-user-abstraction! deps store "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" {:priority :high})
          (.then (fn [snapshot]
                   (is (= {:mode :unified
                           :abstraction-raw "portfolioMargin"}
                          snapshot))
                   (is (= snapshot (:account @store)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest compat-wrapper-delegation-coverage-test
  (let [calls (atom [])
        store (atom {:wallet {} :orders {}})
        record! (fn [label args]
                  (swap! calls conj [label args])
                  {:ok label})
        req-id (fn [& _] nil)]
    (with-redefs [market-gateway/fetch-asset-contexts! (fn [deps* store* opts*]
                                                         (record! :asset-contexts [deps* store* opts*]))
                  market-gateway/fetch-perp-dexs! (fn [deps* store* opts*]
                                                    (record! :perp-dexs [deps* store* opts*]))
                  market-gateway/fetch-candle-snapshot! (fn [deps* store* opts*]
                                                          (record! :candle-snapshot [deps* store* opts*]))
                  order-gateway/fetch-user-fills! (fn [deps* store* address* opts*]
                                                    (record! :user-fills [deps* store* address* opts*]))
                  order-gateway/request-historical-orders-data! (fn [deps* address* opts*]
                                                                  (record! :historical-orders [deps* address* opts*]))
                  market-gateway/fetch-spot-meta! (fn [deps* store* opts*]
                                                    (record! :spot-meta [deps* store* opts*]))
                  market-gateway/ensure-perp-dexs! (fn [deps* store* opts*]
                                                     (record! :ensure-perp-dexs [deps* store* opts*]))
                  market-gateway/ensure-spot-meta! (fn [deps* store* opts*]
                                                     (record! :ensure-spot-meta [deps* store* opts*]))
                  market-gateway/fetch-asset-selector-markets! (fn [deps* store* opts*]
                                                                 (record! :asset-selector [deps* store* opts*]))
                  account-gateway/fetch-spot-clearinghouse-state! (fn [deps* store* address* opts*]
                                                                    (record! :spot-clearinghouse [deps* store* address* opts*]))
                  account-gateway/fetch-user-abstraction! (fn [deps* store* address* opts*]
                                                           (record! :user-abstraction [deps* store* address* opts*]))
                  account-gateway/fetch-clearinghouse-state! (fn [deps* store* address* dex* opts*]
                                                               (record! :clearinghouse [deps* store* address* dex* opts*]))
                  account-gateway/fetch-perp-dex-clearinghouse-states! (fn [deps* store* address* dex-names* opts*]
                                                                         (record! :batch-clearinghouse [deps* store* address* dex-names* opts*]))]
      (is (= {:ok :asset-contexts}
             (api-compat/fetch-asset-contexts!
              {:log-fn req-id
               :request-asset-contexts! req-id}
              store
              {:priority :high})))
      (is (= {:ok :perp-dexs}
             (api-compat/fetch-perp-dexs!
              {:log-fn req-id
               :request-perp-dexs! req-id}
              store
              {:priority :low})))
      (is (= {:ok :candle-snapshot}
             (api-compat/fetch-candle-snapshot!
              {:log-fn req-id
               :request-candle-snapshot! req-id}
              store
              {:interval :1m :bars 10 :priority :high})))
      (is (= {:ok :user-fills}
             (api-compat/fetch-user-fills!
              {:log-fn req-id
               :request-user-fills! req-id}
              store
              "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
              {:priority :high})))
      (is (= {:ok :historical-orders}
             (api-compat/fetch-historical-orders!
              {:log-fn req-id
               :post-info! req-id}
              "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
              {:priority :high})))
      (is (= {:ok :spot-meta}
             (api-compat/fetch-spot-meta!
              {:log-fn req-id
               :request-spot-meta! req-id}
              store
              {:priority :high})))
      (is (= {:ok :ensure-perp-dexs}
             (api-compat/ensure-perp-dexs!
              {:ensure-perp-dexs-data! req-id}
              store
              {:priority :high})))
      (is (= {:ok :ensure-spot-meta}
             (api-compat/ensure-spot-meta!
              {:ensure-spot-meta-data! req-id}
              store
              {:priority :high})))
      (is (= {:ok :asset-selector}
             (api-compat/fetch-asset-selector-markets!
              {:log-fn req-id
               :request-asset-selector-markets! req-id}
              store
              {:phase :bootstrap})))
      (is (= {:ok :spot-clearinghouse}
             (api-compat/fetch-spot-clearinghouse-state!
              {:log-fn req-id
               :request-spot-clearinghouse-state! req-id}
              store
              "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
              {:priority :high})))
      (is (= {:ok :user-abstraction}
             (api-compat/fetch-user-abstraction!
              {:log-fn req-id
               :request-user-abstraction! req-id}
              store
              "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
              {:priority :high})))
      (is (= {:ok :clearinghouse}
             (api-compat/fetch-clearinghouse-state!
              {:log-fn req-id
               :request-clearinghouse-state! req-id}
              store
              "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
              "dex-a"
              {:priority :high})))
      (is (= {:ok :batch-clearinghouse}
             (api-compat/fetch-perp-dex-clearinghouse-states!
              {:fetch-clearinghouse-state! req-id}
              store
              "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
              ["dex-a" "dex-b"]
              {:priority :high})))
      (let [asset-context-call (some #(when (= :asset-contexts (first %)) %) @calls)
            clearinghouse-call (some #(when (= :clearinghouse (first %)) %) @calls)]
        (is asset-context-call)
        (is clearinghouse-call)
        (let [[asset-deps _ _] (second asset-context-call)
              [clearinghouse-deps _ _ _ _] (second clearinghouse-call)]
          (is (= api-projections/apply-asset-contexts-success
                 (:apply-asset-contexts-success asset-deps)))
          (is (= api-projections/apply-asset-contexts-error
                 (:apply-asset-contexts-error asset-deps)))
          (is (= api-projections/apply-perp-dex-clearinghouse-success
                 (:apply-perp-dex-clearinghouse-success clearinghouse-deps)))
          (is (= api-projections/apply-perp-dex-clearinghouse-error
                 (:apply-perp-dex-clearinghouse-error clearinghouse-deps))))))))

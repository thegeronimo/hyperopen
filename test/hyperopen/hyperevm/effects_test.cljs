(ns hyperopen.hyperevm.effects-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.hyperevm.domain.balances :as balances]
            [hyperopen.hyperevm.effects :as effects]
            [hyperopen.hyperevm.test-support.bridge-fixtures :as bridge-fixtures]
            [hyperopen.hyperevm.test-support.rpc-stubs :as stubs]
            [hyperopen.test-support.async :as async-support]))

(def ^:private owner "0x1111111111111111111111111111111111111111")
(def ^:private spectated "0x3333333333333333333333333333333333333333")

(defn- base-state
  []
  {:router {:path "/trade"}
   :wallet {:address owner}
   :spot {:meta bridge-fixtures/bridge-spot-meta}
   :hyperevm (balances/default-state)})

(defn- spectating-state
  []
  (assoc (base-state) :account-context {:spectate-mode {:active? true :address spectated}}))

(def ^:private plan {:addresses [owner] :requested-at-ms 100})

(defn- ok-result [units] {:success? true :return-data (stubs/amount-data units)})

(deftest fetch-balances-stores-balances-and-bridge-health-test
  (async done
    (let [store (atom (base-state))
          calls (atom [])
          read-balances! (fn [rpc-deps opts]
                           (swap! calls conj [rpc-deps opts])
                           (is (= :loading (:status (balances/entry @store owner)))
                               "the entry is marked loading before the read")
                           (js/Promise.resolve
                            {:native-wei "5000000000000000000"
                             :token-units {1 "100000000000000000000"}
                             :gas-price-wei "100000000"
                             :extra-results {[:system 1] (ok-result "7")
                                             [:system 6] (ok-result "0")
                                             [:decimals 1] (ok-result 18)
                                             [:decimals 6] (ok-result 18)}
                             :extra-unread [[:system 122]]}))]
      (-> (effects/fetch-hyperevm-balances! {:store store
                                             :plan plan
                                             :read-balances! read-balances!
                                             :rpc-deps {:fetch-fn :stub}
                                             :now-ms-fn (constantly 200)})
          (.then (fn [_]
                   (let [[[rpc-deps opts]] @calls
                         entry (balances/entry @store owner)]
                     (is (= {:fetch-fn :stub} rpc-deps))
                     (is (= owner (:owner opts)))
                     (is (= [0 1 6 122 150 197 296 478] (mapv :index (:tokens opts)))
                         "every linked token is read")
                     (is (= 12 (count (:extra-calls opts)))
                         "6 system balances + 6 first-sighting decimals")
                     (is (= {:status :ready :stale? false :loaded-at-ms 200
                             :native-wei "5000000000000000000"
                             :token-units {1 "100000000000000000000"}
                             :gas-price-wei "100000000"}
                            (select-keys entry [:status :stale? :loaded-at-ms :native-wei
                                                :token-units :gas-price-wei])))
                     (is (not (contains? entry :extra-results)) "bridge data stays out of the entry")
                     (is (= {1 "7" 6 "0"} (get-in @store [:hyperevm :bridge :evm-system-units])))
                     (is (= {1 {:balance-of-ok? true :decimals-ok? true}
                             6 {:balance-of-ok? true :decimals-ok? true}}
                            (get-in @store [:hyperevm :bridge :token-health]))))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest fetch-balances-reads-bridge-health-once-per-poll-test
  (async done
    (let [store (atom (spectating-state))
          calls (atom [])
          read-balances! (fn [_ opts]
                           (swap! calls conj opts)
                           (js/Promise.resolve {:native-wei "0" :token-units {} :gas-price-wei "1"}))]
      (-> (effects/fetch-hyperevm-balances! {:store store
                                             :plan {:addresses [spectated owner] :requested-at-ms 100}
                                             :read-balances! read-balances!
                                             :now-ms-fn (constantly 200)})
          (.then (fn [_]
                   (is (= [spectated owner] (mapv :owner @calls)))
                   (is (= [true false] (mapv #(contains? % :extra-calls) @calls))
                       "only the first address carries the bridge reads")
                   (is (= [:ready :ready]
                          (mapv #(:status (balances/entry @store %)) [spectated owner])))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest fetch-balances-records-errors-and-rate-limit-backoff-test
  (async done
    (let [store (atom (base-state))
          read-balances! (fn [_ _]
                           (js/Promise.reject
                            (ex-info "HyperEVM RPC rate limit reached." {:kind :rate-limited})))]
      (-> (effects/fetch-hyperevm-balances! {:store store
                                             :plan plan
                                             :read-balances! read-balances!
                                             :now-ms-fn (constantly 1000)})
          (.then (fn [_]
                   (is (= {:status :error :error "HyperEVM RPC rate limit reached."
                           :error-kind :rate-limited :stale? false}
                          (select-keys (balances/entry @store owner)
                                       [:status :error :error-kind :stale?])))
                   (is (= {:strikes 1 :until-ms 31000} (get-in @store [:hyperevm :backoff])))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest fetch-balances-backs-off-once-per-poll-test
  ;; The RPC limit is per IP: two addresses rate-limited in one poll are one
  ;; strike, and a success beside a rate limit does not reset it.
  (async done
    (let [store (atom (spectating-state))
          poll! (fn [answers requested-at-ms now-ms]
                  (effects/fetch-hyperevm-balances!
                   {:store store
                    :plan {:addresses [spectated owner] :requested-at-ms requested-at-ms}
                    :read-balances! (fn [_ {:keys [owner]}]
                                      (if (= :rate-limited (get answers owner))
                                        (js/Promise.reject
                                         (ex-info "HyperEVM RPC rate limit reached." {:kind :rate-limited}))
                                        (js/Promise.resolve {:native-wei "1" :token-units {} :gas-price-wei "1"})))
                    :now-ms-fn (constantly now-ms)}))]
      (-> (poll! {spectated :rate-limited owner :rate-limited} 100 1000)
          (.then (fn [_]
                   (is (= {:strikes 1 :until-ms 31000} (get-in @store [:hyperevm :backoff]))
                       "30 s, not 60 s")
                   (poll! {spectated :rate-limited} 200 40000)))
          (.then (fn [_]
                   (is (= {:strikes 2 :until-ms 100000} (get-in @store [:hyperevm :backoff]))
                       "the owner's success does not cancel the spectated address's rate limit")
                   (is (= :ready (:status (balances/entry @store owner))))
                   (poll! {} 300 200000)))
          (.then (fn [_]
                   (is (= {:strikes 0 :until-ms nil} (get-in @store [:hyperevm :backoff])))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest fetch-balances-applies-bridge-health-by-request-time-test
  ;; A slow reply to an older poll lands after a newer one. The older balance
  ;; reply is dropped as overtaken, and so is its bridge health.
  (async done
    (let [store (atom (base-state))
          resolvers (atom {})
          read-balances! (fn [_ _]
                           (js/Promise. (fn [resolve _]
                                          (swap! resolvers assoc (count @resolvers) resolve))))
          reply (fn [units]
                  {:native-wei "1" :token-units {} :gas-price-wei "1"
                   :extra-results {[:system 1] (ok-result units)}})
          old-poll (effects/fetch-hyperevm-balances!
                    {:store store :plan {:addresses [owner] :requested-at-ms 100}
                     :read-balances! read-balances! :now-ms-fn (constantly 1)})
          new-poll (effects/fetch-hyperevm-balances!
                    {:store store :plan {:addresses [owner] :requested-at-ms 200}
                     :read-balances! read-balances! :now-ms-fn (constantly 2)})]
      ((get @resolvers 1) (reply "9"))
      (-> new-poll
          (.then (fn [_]
                   ((get @resolvers 0) (reply "5"))
                   old-poll))
          (.then (fn [_]
                   (is (= "9" (get-in @store [:hyperevm :bridge :evm-system-units 1])))
                   (is (= 200 (get-in @store [:hyperevm :bridge :health-requested-at-ms])))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest fetch-balances-drops-replies-after-an-account-switch-test
  (async done
    (let [store (atom (base-state))
          read-balances! (fn [_ _]
                           ;; the user disconnects while the read is in flight
                           (swap! store (fn [state]
                                          (-> state
                                              (dissoc :wallet)
                                              (assoc-in [:hyperevm :balances :by-address] {}))))
                           (js/Promise.resolve {:native-wei "1" :token-units {} :gas-price-wei "1"}))]
      (-> (effects/fetch-hyperevm-balances! {:store store
                                             :plan plan
                                             :read-balances! read-balances!
                                             :now-ms-fn (constantly 200)})
          (.then (fn [_]
                   (is (= {} (get-in @store [:hyperevm :balances :by-address])))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest fetch-core-bridge-balance-stores-the-system-balance-test
  (async done
    (let [store (atom (base-state))
          calls (atom [])
          request! (fn [address opts]
                     (swap! calls conj [address opts])
                     (js/Promise.resolve bridge-fixtures/six-system-core-state))]
      (-> (effects/fetch-core-bridge-balance! {:store store
                                               :index 6
                                               :address "0x2000000000000000000000000000000000000006"
                                               :request-spot-clearinghouse-state! request!
                                               :now-ms-fn (constantly 50)})
          (.then (fn [_]
                   (is (= [["0x2000000000000000000000000000000000000006" {:priority :low :force-refresh? true}]]
                          @calls)
                       "past the info cache, so the cap's age starts when HyperCore answers")
                   (is (= {:amount "293.0535384" :loaded-at-ms 50}
                          (get-in @store [:hyperevm :bridge :core-system-balances 6])))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest fetch-core-bridge-balance-refuses-a-mismatched-address-test
  (async done
    (let [store (atom (base-state))
          calls (atom [])
          request! (fn [address _]
                     (swap! calls conj address)
                     (js/Promise.resolve bridge-fixtures/purr-system-core-state))]
      (-> (js/Promise.all
           #js [(effects/fetch-core-bridge-balance! {:store store :index 6
                                                     :address "0x2000000000000000000000000000000000000001"
                                                     :request-spot-clearinghouse-state! request!
                                                     :now-ms-fn (constantly 1)})
                (effects/fetch-core-bridge-balance! {:store store :index 0
                                                     :address "0x2000000000000000000000000000000000000000"
                                                     :request-spot-clearinghouse-state! request!
                                                     :now-ms-fn (constantly 1)})])
          (.then (fn [_]
                   (is (= [] @calls) "PURR's address for SIX, and USDC (uncapped), are refused")
                   (is (= {} (get-in @store [:hyperevm :bridge :core-system-balances])))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest fetch-core-bridge-balance-records-errors-test
  (async done
    (let [store (atom (base-state))]
      (-> (effects/fetch-core-bridge-balance! {:store store
                                               :index 150
                                               :address "0x2222222222222222222222222222222222222222"
                                               :request-spot-clearinghouse-state!
                                               (fn [_ _] (js/Promise.reject (js/Error. "429")))
                                               :now-ms-fn (constantly 1)})
          (.then (fn [_]
                   (is (= {:error "429" :requested-at-ms 1}
                          (get-in @store [:hyperevm :bridge :core-system-balances 150]))
                       "the request time stays, so the retry gap applies to the failure")
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest fetch-core-account-status-test
  (async done
    (let [store (atom (base-state))
          calls (atom [])]
      (-> (effects/fetch-core-account-status!
           {:store store
            :owner owner
            :request-user-role! (fn [address opts]
                                  (swap! calls conj [address opts])
                                  (js/Promise.resolve bridge-fixtures/missing-user-role))})
          (.then (fn [_]
                   (is (= [[owner {:priority :low}]] @calls))
                   (is (= :missing (get-in @store [:hyperevm :core-account owner])))
                   (effects/fetch-core-account-status!
                    {:store store
                     :owner owner
                     :request-user-role! (fn [_ _] (js/Promise.reject (js/Error. "down")))})))
          (.then (fn [_]
                   (is (= :missing (get-in @store [:hyperevm :core-account owner]))
                       "a failed read changes nothing")
                   (reset! calls [])
                   (effects/fetch-core-account-status!
                    {:store store
                     :owner owner
                     :request-user-role! (fn [address opts]
                                           (swap! calls conj [address opts])
                                           (js/Promise.resolve {:role "user"}))})))
          (.then (fn [_]
                   (is (= [[owner {:priority :low :force-refresh? true}]] @calls)
                       "a :missing re-read bypasses the info cache")
                   (is (= :active (get-in @store [:hyperevm :core-account owner]))
                       "an account activated since is seen")
                   (done)))
          (.catch (async-support/unexpected-error done))))))

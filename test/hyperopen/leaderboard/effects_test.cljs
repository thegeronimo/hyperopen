(ns hyperopen.leaderboard.effects-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.leaderboard.effects :as effects]))

(defn- apply-cache-hydration
  [state record]
  (-> state
      (assoc-in [:leaderboard :rows] (:rows record))
      (assoc-in [:leaderboard :excluded-addresses] (set (:excluded-addresses record)))
      (assoc-in [:leaderboard :loading?] false)
      (assoc-in [:leaderboard :error] nil)
      (assoc-in [:leaderboard :error-category] nil)
      (assoc-in [:leaderboard :loaded-at-ms] (:saved-at-ms record))))

(deftest api-fetch-leaderboard-reuses-fresh-memory-snapshot-test
  (async done
    (let [leaderboard-calls (atom 0)
          vault-calls (atom 0)
          store (atom {:router {:path "/leaderboard"}
                       :leaderboard {:rows [{:eth-address "0x1"}]
                                     :excluded-addresses #{"0x2"}
                                     :loading? false
                                     :error "old-error"
                                     :error-category :transport
                                     :loaded-at-ms 1700000000000}})]
      (-> (effects/api-fetch-leaderboard!
           {:store store
            :request-leaderboard! (fn [_opts]
                                    (swap! leaderboard-calls inc)
                                    (js/Promise.resolve []))
            :request-vault-index! (fn [_opts]
                                    (swap! vault-calls inc)
                                    (js/Promise.resolve []))
            :begin-leaderboard-load (fn [state]
                                      (assoc state :begin? true))
            :apply-leaderboard-cache-hydration apply-cache-hydration
            :apply-leaderboard-success (fn [state payload]
                                         (assoc state :success payload))
            :apply-leaderboard-error (fn [state err]
                                       (assoc state :error-result err))
            :now-ms-fn (fn [] (+ 1700000000000 1000))
            :opts {:skip-route-gate? true}})
          (.then (fn [result]
                   (is (= {:source :memory} result))
                   (is (= 0 @leaderboard-calls))
                   (is (= 0 @vault-calls))
                   (is (nil? (get-in @store [:leaderboard :error])))
                   (is (= [{:eth-address "0x1"}]
                          (get-in @store [:leaderboard :rows])))
                   (done)))
          (.catch (fn [error]
                    (js/console.error error)
                    (is false "Unexpected fresh-memory rejection")
                    (done)))))))

(deftest api-fetch-leaderboard-hydrates-fresh-indexeddb-cache-when-memory-is-empty-test
  (async done
    (let [leaderboard-calls (atom 0)
          vault-calls (atom 0)
          store (atom {:router {:path "/leaderboard"}
                       :leaderboard {:rows []
                                     :excluded-addresses #{}
                                     :loading? false
                                     :error nil
                                     :error-category nil
                                     :loaded-at-ms nil}})
          cache-record {:saved-at-ms 1700000000000
                        :rows [{:eth-address "0x1"}]
                        :excluded-addresses ["0x2"]}]
      (-> (effects/api-fetch-leaderboard!
           {:store store
            :request-leaderboard! (fn [_opts]
                                    (swap! leaderboard-calls inc)
                                    (js/Promise.resolve []))
            :request-vault-index! (fn [_opts]
                                    (swap! vault-calls inc)
                                    (js/Promise.resolve []))
            :load-leaderboard-cache-record! (fn []
                                             cache-record)
            :begin-leaderboard-load (fn [state]
                                      (assoc state :begin? true))
            :apply-leaderboard-cache-hydration apply-cache-hydration
            :apply-leaderboard-success (fn [state payload]
                                         (assoc state :success payload))
            :apply-leaderboard-error (fn [state err]
                                       (assoc state :error-result err))
            :now-ms-fn (fn [] (+ 1700000000000 1000))
            :opts {:skip-route-gate? true}})
          (.then (fn [result]
                   (is (= {:source :cache} result))
                   (is (= 0 @leaderboard-calls))
                   (is (= 0 @vault-calls))
                   (is (= [{:eth-address "0x1"}]
                          (get-in @store [:leaderboard :rows])))
                   (is (= #{"0x2"}
                          (get-in @store [:leaderboard :excluded-addresses])))
                   (is (= 1700000000000
                          (get-in @store [:leaderboard :loaded-at-ms])))
                   (done)))
          (.catch (fn [error]
                    (js/console.error error)
                    (is false "Unexpected fresh-cache rejection")
                    (done)))))))

(deftest api-fetch-leaderboard-reuses-in-flight-load-test
  (async done
    (let [leaderboard-calls (atom 0)
          vault-calls (atom 0)
          store (atom {:router {:path "/leaderboard"}
                       :leaderboard {:rows []
                                     :excluded-addresses #{}
                                     :loading? true
                                     :error nil
                                     :error-category nil
                                     :loaded-at-ms nil}})]
      (-> (effects/api-fetch-leaderboard!
           {:store store
            :request-leaderboard! (fn [_opts]
                                    (swap! leaderboard-calls inc)
                                    (js/Promise.resolve []))
            :request-vault-index! (fn [_opts]
                                    (swap! vault-calls inc)
                                    (js/Promise.resolve []))
            :begin-leaderboard-load (fn [state]
                                      (assoc-in state [:leaderboard :loading?] true))
            :apply-leaderboard-cache-hydration apply-cache-hydration
            :apply-leaderboard-success (fn [state payload]
                                         (assoc state :success payload))
            :apply-leaderboard-error (fn [state err]
                                       (assoc state :error-result err))
            :now-ms-fn (fn [] 1700000000000)
            :opts {:skip-route-gate? true}})
          (.then (fn [result]
                   (is (= {:source :in-flight} result))
                   (is (= 0 @leaderboard-calls))
                   (is (= 0 @vault-calls))
                   (is (true? (get-in @store [:leaderboard :loading?])))
                   (done)))
          (.catch (fn [error]
                    (js/console.error error)
                    (is false "Unexpected in-flight rejection")
                    (done)))))))

(deftest api-fetch-leaderboard-falls-through-to-network-for-stale-cache-and-persists-result-test
  (async done
    (let [leaderboard-calls (atom [])
          vault-calls (atom [])
          persist-calls (atom [])
          store (atom {:router {:path "/leaderboard"}
                       :leaderboard {:rows []
                                     :excluded-addresses #{}
                                     :loading? false
                                     :error nil
                                     :error-category nil
                                     :loaded-at-ms nil}})]
      (-> (effects/api-fetch-leaderboard!
           {:store store
            :request-leaderboard! (fn [opts]
                                    (swap! leaderboard-calls conj opts)
                                    (js/Promise.resolve [{:eth-address "0x111"
                                                          :display-name "Alpha"}]))
            :request-vault-index! (fn [opts]
                                    (swap! vault-calls conj opts)
                                    (js/Promise.resolve [{:vault-address "0x222"
                                                          :relationship {:type :child}}]))
            :load-leaderboard-cache-record! (fn []
                                             {:saved-at-ms 1700000000000
                                              :rows [{:eth-address "0xold"}]
                                              :excluded-addresses ["0xold-excluded"]})
            :persist-leaderboard-cache-record! (fn [payload]
                                                (swap! persist-calls conj payload)
                                                (js/Promise.resolve true))
            :begin-leaderboard-load (fn [state]
                                      (assoc-in state [:leaderboard :loading?] true))
            :apply-leaderboard-cache-hydration apply-cache-hydration
            :apply-leaderboard-success (fn [state payload]
                                         (-> state
                                             (assoc-in [:leaderboard :rows] (:rows payload))
                                             (assoc-in [:leaderboard :excluded-addresses] (:excluded-addresses payload))
                                             (assoc-in [:leaderboard :loading?] false)
                                             (assoc-in [:leaderboard :loaded-at-ms] 1700007200000)))
            :apply-leaderboard-error (fn [state err]
                                       (assoc state :error-result err))
            :known-excluded-addresses #{"0x333"}
            :now-ms-fn (fn [] (+ 1700000000000 (* 2 60 60 1000)))
            :opts {:skip-route-gate? true}})
          (.then (fn [_result]
                   (is (= [{}] @leaderboard-calls))
                   (is (= [{}] @vault-calls))
                   (is (= [{:rows [{:eth-address "0x111"
                                    :display-name "Alpha"}]
                            :excluded-addresses #{"0x222"
                                                  "0x333"
                                                  "0xffffffffffffffffffffffffffffffffffffffff"}}]
                          @persist-calls))
                   (is (= [{:eth-address "0x111"
                            :display-name "Alpha"}]
                          (get-in @store [:leaderboard :rows])))
                   (is (= #{"0x222"
                            "0x333"
                            "0xffffffffffffffffffffffffffffffffffffffff"}
                          (get-in @store [:leaderboard :excluded-addresses])))
                   (done)))
          (.catch (fn [error]
                    (js/console.error error)
                    (is false "Unexpected stale-cache rejection")
                    (done)))))))

(deftest api-fetch-leaderboard-falls-through-to-network-for-stale-memory-without-reading-indexeddb-test
  (async done
    (let [leaderboard-calls (atom [])
          vault-calls (atom [])
          cache-load-calls (atom 0)
          store (atom {:router {:path "/leaderboard"}
                       :leaderboard {:rows [{:eth-address "0x1"}]
                                     :excluded-addresses #{"0x2"}
                                     :loading? false
                                     :error nil
                                     :error-category nil
                                     :loaded-at-ms 1700000000000}})]
      (-> (effects/api-fetch-leaderboard!
           {:store store
            :request-leaderboard! (fn [opts]
                                    (swap! leaderboard-calls conj opts)
                                    (js/Promise.resolve []))
            :request-vault-index! (fn [opts]
                                    (swap! vault-calls conj opts)
                                    (js/Promise.resolve []))
            :load-leaderboard-cache-record! (fn []
                                             (swap! cache-load-calls inc)
                                             nil)
            :begin-leaderboard-load (fn [state]
                                      (assoc-in state [:leaderboard :loading?] true))
            :apply-leaderboard-cache-hydration apply-cache-hydration
            :apply-leaderboard-success (fn [state payload]
                                         (assoc state :success payload))
            :apply-leaderboard-error (fn [state err]
                                       (assoc state :error-result err))
            :now-ms-fn (fn [] (+ 1700000000000 (* 2 60 60 1000)))
            :opts {:skip-route-gate? true}})
          (.then (fn [_result]
                   (is (= 1 (count @leaderboard-calls)))
                   (is (= 1 (count @vault-calls)))
                   (is (= 0 @cache-load-calls))
                   (done)))
          (.catch (fn [error]
                    (js/console.error error)
                    (is false "Unexpected stale-memory rejection")
                    (done)))))))

(deftest api-fetch-leaderboard-force-refresh-bypasses-fresh-memory-cache-test
  (async done
    (let [leaderboard-calls (atom 0)
          vault-calls (atom 0)
          store (atom {:router {:path "/leaderboard"}
                       :leaderboard {:rows [{:eth-address "0x1"}]
                                     :excluded-addresses #{}
                                     :loading? false
                                     :error nil
                                     :error-category nil
                                     :loaded-at-ms 1700000000000}})]
      (-> (effects/api-fetch-leaderboard!
           {:store store
            :request-leaderboard! (fn [_opts]
                                    (swap! leaderboard-calls inc)
                                    (js/Promise.resolve []))
            :request-vault-index! (fn [_opts]
                                    (swap! vault-calls inc)
                                    (js/Promise.resolve []))
            :begin-leaderboard-load (fn [state]
                                      (assoc-in state [:leaderboard :loading?] true))
            :apply-leaderboard-cache-hydration apply-cache-hydration
            :apply-leaderboard-success (fn [state payload]
                                         (assoc state :success payload))
            :apply-leaderboard-error (fn [state err]
                                       (assoc state :error-result err))
            :now-ms-fn (fn [] (+ 1700000000000 1000))
            :opts {:skip-route-gate? true
                   :force-refresh? true}})
          (.then (fn [_result]
                   (is (= 1 @leaderboard-calls))
                   (is (= 1 @vault-calls))
                   (done)))
          (.catch (fn [error]
                    (js/console.error error)
                    (is false "Unexpected force-refresh rejection")
                    (done)))))))

(ns hyperopen.api.fetch-compat-account-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.fetch-compat :as fetch-compat]))

(defn- reject-promise
  [message]
  (js/Promise.reject (js/Error. message)))

(deftest fetch-spot-clearinghouse-state-skips-when-address-missing-test
  (async done
    (let [store (atom {})
          calls (atom 0)
          deps {:log-fn (fn [& _] nil)
                :request-spot-clearinghouse-state! (fn [_address _opts]
                                                     (swap! calls inc)
                                                     (js/Promise.resolve {}))
                :begin-spot-balances-load (fn [state]
                                            (assoc state :spot-balances-loading? true))
                :apply-spot-balances-success (fn [state data]
                                               (assoc state :spot-balances data))
                :apply-spot-balances-error (fn [state err]
                                             (assoc state :spot-balances-error (str err)))}]
      (-> (fetch-compat/fetch-spot-clearinghouse-state! deps store nil {:priority :high})
          (.then (fn [result]
                   (is (nil? result))
                   (is (= 0 @calls))
                   (is (nil? (:spot-balances-loading? @store)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest fetch-spot-clearinghouse-state-applies-success-projection-test
  (async done
    (let [store (atom {})
          deps {:log-fn (fn [& _] nil)
                :request-spot-clearinghouse-state! (fn [_address _opts]
                                                     (js/Promise.resolve {:balances [{:coin "USDC"}]}))
                :begin-spot-balances-load (fn [state]
                                            (assoc state :spot-balances-loading? true))
                :apply-spot-balances-success (fn [state data]
                                               (assoc state :spot-balances data))
                :apply-spot-balances-error (fn [state err]
                                             (assoc state :spot-balances-error (str err)))}]
      (-> (fetch-compat/fetch-spot-clearinghouse-state! deps store "0xabc" {})
          (.then (fn [data]
                   (is (= {:balances [{:coin "USDC"}]} data))
                   (is (= true (:spot-balances-loading? @store)))
                   (is (= data (:spot-balances @store)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest fetch-spot-clearinghouse-state-applies-error-projection-test
  (async done
    (let [store (atom {})
          deps {:log-fn (fn [& _] nil)
                :request-spot-clearinghouse-state! (fn [_address _opts]
                                                     (reject-promise "spot balances failed"))
                :begin-spot-balances-load (fn [state]
                                            (assoc state :spot-balances-loading? true))
                :apply-spot-balances-success (fn [state data]
                                               (assoc state :spot-balances data))
                :apply-spot-balances-error (fn [state err]
                                             (assoc state :spot-balances-error (.-message err)))}]
      (-> (fetch-compat/fetch-spot-clearinghouse-state! deps store "0xabc" {})
          (.then (fn [_]
                   (is false "Expected fetch-spot-clearinghouse-state! to reject")
                   (done)))
          (.catch (fn [err]
                    (is (re-find #"spot balances failed" (str err)))
                    (is (= "spot balances failed" (:spot-balances-error @store)))
                    (done)))))))

(deftest fetch-user-abstraction-projects-normalized-mode-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"}})
          deps {:log-fn (fn [& _] nil)
                :request-user-abstraction! (fn [_address _opts]
                                             (js/Promise.resolve "portfolioMargin"))
                :normalize-user-abstraction-mode (fn [_] :unified)
                :apply-user-abstraction-snapshot
                (fn [state requested-address snapshot]
                  (assoc state :projection [requested-address snapshot]))}]
      (-> (fetch-compat/fetch-user-abstraction! deps store "0xAbC" {})
          (.then (fn [snapshot]
                   (is (= {:mode :unified
                           :abstraction-raw "portfolioMargin"}
                          snapshot))
                   (is (= ["0xabc" snapshot] (:projection @store)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest fetch-user-abstraction-returns-classic-when-address-missing-test
  (async done
    (let [store (atom {})
          called (atom 0)
          deps {:log-fn (fn [& _] nil)
                :request-user-abstraction! (fn [_address _opts]
                                             (swap! called inc)
                                             (js/Promise.resolve "portfolioMargin"))
                :normalize-user-abstraction-mode (fn [_] :unified)
                :apply-user-abstraction-snapshot (fn [state requested-address snapshot]
                                                   (assoc state :projection [requested-address snapshot]))}]
      (-> (fetch-compat/fetch-user-abstraction! deps store nil {})
          (.then (fn [snapshot]
                   (is (= {:mode :classic
                           :abstraction-raw nil}
                          snapshot))
                   (is (= 0 @called))
                   (is (nil? (:projection @store)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest fetch-user-abstraction-rejects-on-request-error-test
  (async done
    (let [store (atom {})
          deps {:log-fn (fn [& _] nil)
                :request-user-abstraction! (fn [_address _opts]
                                             (reject-promise "abstraction failed"))
                :normalize-user-abstraction-mode (fn [_] :unified)
                :apply-user-abstraction-snapshot (fn [state requested-address snapshot]
                                                   (assoc state :projection [requested-address snapshot]))}]
      (-> (fetch-compat/fetch-user-abstraction! deps store "0xAbC" {})
          (.then (fn [_]
                   (is false "Expected fetch-user-abstraction! to reject")
                   (done)))
          (.catch (fn [err]
                    (is (re-find #"abstraction failed" (str err)))
                    (is (nil? (:projection @store)))
                    (done)))))))

(deftest fetch-clearinghouse-state-applies-success-projection-test
  (async done
    (let [store (atom {})
          deps {:log-fn (fn [& _] nil)
                :request-clearinghouse-state! (fn [_address _dex _opts]
                                                (js/Promise.resolve {:positions []}))
                :apply-perp-dex-clearinghouse-success (fn [state dex data]
                                                        (assoc-in state [:clearinghouse dex] data))
                :apply-perp-dex-clearinghouse-error (fn [state err]
                                                      (assoc state :clearinghouse-error (str err)))}]
      (-> (fetch-compat/fetch-clearinghouse-state! deps store "0xabc" "dex-a" {:priority :high})
          (.then (fn [data]
                   (is (= {:positions []} data))
                   (is (= data (get-in @store [:clearinghouse "dex-a"])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest fetch-clearinghouse-state-applies-error-projection-test
  (async done
    (let [store (atom {})
          deps {:log-fn (fn [& _] nil)
                :request-clearinghouse-state! (fn [_address _dex _opts]
                                                (reject-promise "clearinghouse failed"))
                :apply-perp-dex-clearinghouse-success (fn [state dex data]
                                                        (assoc-in state [:clearinghouse dex] data))
                :apply-perp-dex-clearinghouse-error (fn [state err]
                                                      (assoc state :clearinghouse-error (.-message err)))}]
      (-> (fetch-compat/fetch-clearinghouse-state! deps store "0xabc" "dex-a" {})
          (.then (fn [_]
                   (is false "Expected fetch-clearinghouse-state! to reject")
                   (done)))
          (.catch (fn [err]
                    (is (re-find #"clearinghouse failed" (str err)))
                    (is (= "clearinghouse failed" (:clearinghouse-error @store)))
                    (done)))))))

(deftest fetch-perp-dex-clearinghouse-states-batches-per-dex-test
  (async done
    (let [calls (atom [])
          deps {:fetch-clearinghouse-state! (fn [_store address dex opts]
                                              (swap! calls conj [address dex opts])
                                              (js/Promise.resolve {:dex dex}))}]
      (-> (fetch-compat/fetch-perp-dex-clearinghouse-states! deps
                                                              (atom {})
                                                              "0xabc"
                                                              ["dex-a" "dex-b"]
                                                              {:priority :high})
          (.then (fn [results]
                   (is (= [{:dex "dex-a"} {:dex "dex-b"}]
                          (js->clj results :keywordize-keys true)))
                   (is (= [["0xabc" "dex-a" {:priority :high}]
                           ["0xabc" "dex-b" {:priority :high}]]
                          @calls))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest fetch-perp-dex-clearinghouse-states-skips-when-input-missing-test
  (async done
    (let [calls (atom 0)
          deps {:fetch-clearinghouse-state! (fn [& _]
                                              (swap! calls inc)
                                              (js/Promise.resolve :ok))}]
      (-> (js/Promise.all
           #js [(fetch-compat/fetch-perp-dex-clearinghouse-states! deps (atom {}) nil ["dex-a"] {})
                (fetch-compat/fetch-perp-dex-clearinghouse-states! deps (atom {}) "0xabc" [] {})])
          (.then (fn [results]
                   (is (= [nil nil] (js->clj results)))
                   (is (= 0 @calls))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

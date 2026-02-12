(ns hyperopen.wallet.copy-feedback-runtime-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.wallet.copy-feedback-runtime :as copy-runtime]))

(defn- set-feedback!
  [store kind message]
  (swap! store assoc-in [:wallet :copy-feedback] {:kind kind
                                                  :message message}))

(defn- clear-feedback!
  [store]
  (swap! store assoc-in [:wallet :copy-feedback] nil))

(deftest clear-wallet-copy-feedback-timeout-clears-id-and-resets-atom-test
  (let [cleared (atom [])
        timeout-id-atom (atom :timeout-1)]
    (copy-runtime/clear-wallet-copy-feedback-timeout!
     timeout-id-atom
     (fn [timeout-id]
       (swap! cleared conj timeout-id)))
    (is (= [:timeout-1] @cleared))
    (is (nil? @timeout-id-atom))))

(deftest schedule-wallet-copy-feedback-clear-sets-timeout-and-clears-feedback-test
  (let [captured-callback (atom nil)
        cleared-timeouts (atom [])
        store (atom {:wallet {:copy-feedback {:kind :success
                                              :message "Address copied to clipboard"}}})
        timeout-id-atom (atom :old-timeout)]
    (copy-runtime/schedule-wallet-copy-feedback-clear!
     {:store store
      :wallet-copy-feedback-timeout-id timeout-id-atom
      :clear-wallet-copy-feedback! clear-feedback!
      :clear-wallet-copy-feedback-timeout!
      (fn []
        (copy-runtime/clear-wallet-copy-feedback-timeout!
         timeout-id-atom
         (fn [timeout-id]
           (swap! cleared-timeouts conj timeout-id))))
      :wallet-copy-feedback-duration-ms 1500
      :set-timeout-fn (fn [callback _delay-ms]
                        (reset! captured-callback callback)
                        :new-timeout)})
    (is (= [:old-timeout] @cleared-timeouts))
    (is (= :new-timeout @timeout-id-atom))
    (@captured-callback)
    (is (nil? (get-in @store [:wallet :copy-feedback])))
    (is (nil? @timeout-id-atom))))

(deftest schedule-wallet-copy-feedback-clear-supports-runtime-timeout-storage-test
  (let [captured-callback (atom nil)
        cleared-timeouts (atom [])
        store (atom {:wallet {:copy-feedback {:kind :success
                                              :message "Address copied to clipboard"}}})
        runtime (atom {:timeouts {:wallet-copy :old-timeout}})]
    (copy-runtime/schedule-wallet-copy-feedback-clear!
     {:store store
      :runtime runtime
      :clear-wallet-copy-feedback! clear-feedback!
      :clear-wallet-copy-feedback-timeout!
      (fn []
        (copy-runtime/clear-wallet-copy-feedback-timeout-in-runtime!
         runtime
         (fn [timeout-id]
           (swap! cleared-timeouts conj timeout-id))))
      :wallet-copy-feedback-duration-ms 1500
      :set-timeout-fn (fn [callback _delay-ms]
                        (reset! captured-callback callback)
                        :new-timeout)})
    (is (= [:old-timeout] @cleared-timeouts))
    (is (= :new-timeout (get-in @runtime [:timeouts :wallet-copy])))
    (@captured-callback)
    (is (nil? (get-in @store [:wallet :copy-feedback])))
    (is (nil? (get-in @runtime [:timeouts :wallet-copy])))))

(deftest copy-wallet-address-sets-success-feedback-when-clipboard-write-succeeds-test
  (async done
    (let [written (atom nil)
          schedule-calls (atom 0)
          clear-timeout-calls (atom 0)
          store (atom {:wallet {:copy-feedback nil}})
          clipboard #js {:writeText (fn [payload]
                                      (reset! written payload)
                                      (js/Promise.resolve true))}]
      (copy-runtime/copy-wallet-address!
       {:store store
        :address "0xabc"
        :set-wallet-copy-feedback! set-feedback!
        :clear-wallet-copy-feedback! clear-feedback!
        :clear-wallet-copy-feedback-timeout! (fn []
                                               (swap! clear-timeout-calls inc))
        :schedule-wallet-copy-feedback-clear! (fn [_]
                                                (swap! schedule-calls inc))
        :log-fn (fn [& _] nil)
        :clipboard clipboard})
      (js/setTimeout
       (fn []
         (try
           (is (= "0xabc" @written))
           (is (= :success (get-in @store [:wallet :copy-feedback :kind])))
           (is (= "Address copied to clipboard"
                  (get-in @store [:wallet :copy-feedback :message])))
           (is (= 1 @clear-timeout-calls))
           (is (= 1 @schedule-calls))
           (finally
             (done))))
       0))))

(deftest copy-wallet-address-sets-error-feedback-when-clipboard-unavailable-test
  (let [schedule-calls (atom 0)
        store (atom {:wallet {:copy-feedback nil}})]
    (copy-runtime/copy-wallet-address!
     {:store store
      :address "0xabc"
      :set-wallet-copy-feedback! set-feedback!
      :clear-wallet-copy-feedback! clear-feedback!
      :clear-wallet-copy-feedback-timeout! (fn [] nil)
      :schedule-wallet-copy-feedback-clear! (fn [_]
                                              (swap! schedule-calls inc))
      :log-fn (fn [& _] nil)
      :clipboard #js {}})
    (is (= :error (get-in @store [:wallet :copy-feedback :kind])))
    (is (= "Clipboard unavailable"
           (get-in @store [:wallet :copy-feedback :message])))
    (is (= 1 @schedule-calls))))

(deftest copy-wallet-address-sets-error-feedback-when-address-missing-test
  (let [schedule-calls (atom 0)
        store (atom {:wallet {:copy-feedback nil}})
        clipboard #js {:writeText (fn [_]
                                    (js/Promise.resolve true))}]
    (copy-runtime/copy-wallet-address!
     {:store store
      :address nil
      :set-wallet-copy-feedback! set-feedback!
      :clear-wallet-copy-feedback! clear-feedback!
      :clear-wallet-copy-feedback-timeout! (fn [] nil)
      :schedule-wallet-copy-feedback-clear! (fn [_]
                                              (swap! schedule-calls inc))
      :log-fn (fn [& _] nil)
      :clipboard clipboard})
    (is (= :error (get-in @store [:wallet :copy-feedback :kind])))
    (is (= "No address to copy"
           (get-in @store [:wallet :copy-feedback :message])))
    (is (= 1 @schedule-calls))))

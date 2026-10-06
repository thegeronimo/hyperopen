(ns hyperopen.vaults.infrastructure.user-equity-poller-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [hyperopen.vaults.infrastructure.user-equity-poller :as poller]))

(def ^:private account-a
  "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

(def ^:private account-b
  "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

(defn- active-trade-state
  [address]
  {:wallet {:address address}
   :router {:path "/trade"}
   :vaults {:loading {:user-equities? false
                      :user-equities-for-address nil}}})

(use-fixtures :each
  (fn [f]
    (poller/stop-user-equity-poller!)
    (f)
    (poller/stop-user-equity-poller!)))

(deftest install-user-equity-poller-replaces-and-cleans-up-the-prior-timer-test
  (let [ticks (atom [])
        cleared (atom [])
        store (atom (active-trade-state account-a))
        set-interval! (fn [tick interval-ms]
                        (swap! ticks conj [tick interval-ms])
                        (keyword (str "timer-" (count @ticks))))
        clear-interval! #(swap! cleared conj %)
        deps {:store store
              :address account-a
              :fetch-user-vault-equities! (fn [& _] (js/Promise.resolve nil))
              :visible?-fn (constantly true)
              :set-interval-fn set-interval!
              :clear-interval-fn clear-interval!}]
    (poller/install-user-equity-poller! deps)
    (poller/install-user-equity-poller! deps)
    (is (= [[:timer-1]] (mapv vector @cleared)))
    (is (= [60000 60000] (mapv second @ticks)))
    (poller/stop-user-equity-poller!)
    (is (= [:timer-1 :timer-2] @cleared))))

(deftest user-equity-poller-skips-hidden-inactive-unaddressed-and-inflight-ticks-test
  (doseq [[label state visible?]
          [["hidden document" (active-trade-state account-a) false]
           ["non-trade route" (assoc-in (active-trade-state account-a) [:router :path] "/vaults") true]
           ["no effective account" (assoc (active-trade-state account-a) :wallet {:address nil}) true]
           ["same account already loading"
            (assoc-in (active-trade-state account-a)
                      [:vaults :loading]
                      {:user-equities? true
                       :user-equities-for-address account-a})
            true]]]
    (let [ticks (atom [])
          calls (atom [])
          store (atom state)]
      (poller/install-user-equity-poller!
       {:store store
        :address account-a
        :fetch-user-vault-equities! (fn [& args]
                                      (swap! calls conj args)
                                      (js/Promise.resolve nil))
        :visible?-fn (constantly visible?)
        :set-interval-fn (fn [tick _interval-ms]
                           (swap! ticks conj tick)
                           :timer)
        :clear-interval-fn (fn [_] nil)})
      (is (= 1 (count @ticks)) label)
      ((first @ticks))
      (is (empty? @calls) label)
      (poller/stop-user-equity-poller!))))

(deftest user-equity-poller-uses-low-priority-for-the-current-visible-trade-account-test
  (let [ticks (atom [])
        calls (atom [])
        store (atom (active-trade-state account-a))]
    (poller/install-user-equity-poller!
     {:store store
      :address account-a
      :fetch-user-vault-equities! (fn [& args]
                                    (swap! calls conj args)
                                    (js/Promise.resolve nil))
      :visible?-fn (constantly true)
      :set-interval-fn (fn [tick _interval-ms]
                         (swap! ticks conj tick)
                         :timer)
      :clear-interval-fn (fn [_] nil)})
    ((first @ticks))
    (is (= [[store account-a {:priority :low}]] @calls))))

(deftest user-equity-poller-captured-account-does-not-fetch-after-an-account-switch-test
  (let [ticks (atom [])
        calls (atom [])
        store (atom (active-trade-state account-a))]
    (poller/install-user-equity-poller!
     {:store store
      :address account-a
      :fetch-user-vault-equities! (fn [& args]
                                    (swap! calls conj args)
                                    (js/Promise.resolve nil))
      :visible?-fn (constantly true)
      :set-interval-fn (fn [tick _interval-ms]
                         (swap! ticks conj tick)
                         :timer)
      :clear-interval-fn (fn [_] nil)})
    (swap! store assoc :wallet {:address account-b})
    ((first @ticks))
    (is (empty? @calls))))

(deftest user-equity-poller-does-not-install-for-unified-accounts-test
  (let [ticks (atom [])
        store (atom (assoc-in (active-trade-state account-a) [:account :mode] :unified))]
    (poller/install-user-equity-poller!
     {:store store
      :address account-a
      :fetch-user-vault-equities! (fn [& _] (js/Promise.resolve nil))
      :set-interval-fn (fn [tick _interval-ms] (swap! ticks conj tick) :timer)
      :clear-interval-fn (fn [_] nil)})
    (is (empty? @ticks))))

(deftest user-equity-poller-stops-fetching-when-the-current-account-becomes-unified-test
  (let [ticks (atom [])
        calls (atom [])
        store (atom (active-trade-state account-a))]
    (poller/install-user-equity-poller!
     {:store store
      :address account-a
      :fetch-user-vault-equities! (fn [& args]
                                    (swap! calls conj args)
                                    (js/Promise.resolve nil))
      :visible?-fn (constantly true)
      :set-interval-fn (fn [tick _interval-ms] (swap! ticks conj tick) :timer)
      :clear-interval-fn (fn [_] nil)})
    (swap! store assoc-in [:account :mode] :unified)
    ((first @ticks))
    (is (empty? @calls))))

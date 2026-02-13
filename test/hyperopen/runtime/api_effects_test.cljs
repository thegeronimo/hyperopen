(ns hyperopen.runtime.api-effects-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.runtime.api-effects :as api-effects]))

(deftest fetch-asset-selector-markets-uses-default-and-explicit-options-test
  (let [calls (atom [])
        request-fn (fn [store opts]
                     (swap! calls conj [store opts])
                     (js/Promise.resolve
                      {:phase (if (= :bootstrap (:phase opts)) :bootstrap :full)
                       :market-state {:markets [] :loaded-at-ms 1}}))
        store (atom {})]
    (api-effects/fetch-asset-selector-markets!
     {:store store
      :request-asset-selector-markets-fn request-fn
      :begin-asset-selector-load (fn [state phase]
                                   (assoc-in state [:asset-selector :phase] phase))
      :apply-asset-selector-success (fn [state phase _]
                                      (assoc-in state [:asset-selector :phase] phase))
      :apply-asset-selector-error (fn [state _] state)})
    (api-effects/fetch-asset-selector-markets!
     {:store store
      :opts {:phase :bootstrap}
      :request-asset-selector-markets-fn request-fn
      :begin-asset-selector-load (fn [state phase]
                                   (assoc-in state [:asset-selector :phase] phase))
      :apply-asset-selector-success (fn [state phase _]
                                      (assoc-in state [:asset-selector :phase] phase))
      :apply-asset-selector-error (fn [state _] state)})
    (is (= [[store {:phase :full}]
            [store {:phase :bootstrap}]]
           @calls))))

(deftest load-user-data-issues-fetches-only-when-address-present-test
  (let [open-orders-calls (atom [])
        fills-calls (atom [])
        funding-calls (atom [])
        store (atom {})]
    (api-effects/load-user-data!
     {:store store
      :address nil
      :request-frontend-open-orders! (fn [address _opts]
                                       (swap! open-orders-calls conj [address])
                                       (js/Promise.resolve []))
      :request-user-fills! (fn [address _opts]
                             (swap! fills-calls conj [address])
                             (js/Promise.resolve []))
      :apply-open-orders-success (fn [state _dex rows]
                                   (assoc-in state [:orders :open-orders-snapshot] rows))
      :apply-open-orders-error (fn [state err]
                                 (assoc-in state [:orders :open-error] (str err)))
      :apply-user-fills-success (fn [state rows]
                                  (assoc-in state [:orders :fills] rows))
      :apply-user-fills-error (fn [state err]
                                (assoc-in state [:orders :fills-error] (str err)))
      :fetch-and-merge-funding-history! (fn [runtime-store address opts]
                                          (swap! funding-calls conj [runtime-store address opts]))})
    (is (empty? @open-orders-calls))
    (is (empty? @fills-calls))
    (is (empty? @funding-calls))
    (api-effects/load-user-data!
     {:store store
      :address "0xabc"
      :request-frontend-open-orders! (fn [address _opts]
                                       (swap! open-orders-calls conj [address])
                                       (js/Promise.resolve []))
      :request-user-fills! (fn [address _opts]
                             (swap! fills-calls conj [address])
                             (js/Promise.resolve []))
      :apply-open-orders-success (fn [state _dex rows]
                                   (assoc-in state [:orders :open-orders-snapshot] rows))
      :apply-open-orders-error (fn [state err]
                                 (assoc-in state [:orders :open-error] (str err)))
      :apply-user-fills-success (fn [state rows]
                                  (assoc-in state [:orders :fills] rows))
      :apply-user-fills-error (fn [state err]
                                (assoc-in state [:orders :fills-error] (str err)))
      :fetch-and-merge-funding-history! (fn [runtime-store address opts]
                                          (swap! funding-calls conj [runtime-store address opts]))})
    (is (= [["0xabc"]] @open-orders-calls))
    (is (= [["0xabc"]] @fills-calls))
    (is (= [[store "0xabc" {:priority :high}]] @funding-calls))))

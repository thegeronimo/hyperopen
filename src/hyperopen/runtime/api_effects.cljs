(ns hyperopen.runtime.api-effects
  (:require [hyperopen.api.promise-effects :as promise-effects]))

(defn fetch-asset-selector-markets!
  [{:keys [store
           opts
           request-asset-selector-markets-fn
           begin-asset-selector-load
           apply-spot-meta-success
           apply-asset-selector-success
           apply-asset-selector-error
           after-asset-selector-success!]}]
  (let [opts* (or opts {:phase :full})
        phase (if (= :bootstrap (:phase opts*)) :bootstrap :full)]
    (swap! store begin-asset-selector-load phase)
    (-> (request-asset-selector-markets-fn store opts*)
        (.then (fn [{:keys [phase spot-meta market-state]}]
                 (when apply-spot-meta-success
                   (swap! store apply-spot-meta-success spot-meta))
                 (swap! store apply-asset-selector-success phase market-state)
                 (when (fn? after-asset-selector-success!)
                   (after-asset-selector-success! store phase market-state))
                 (:markets market-state)))
        (.catch (promise-effects/apply-error-and-reject
                 store
                 apply-asset-selector-error)))))

(defn load-user-data!
  [{:keys [store
           address
           request-frontend-open-orders!
           request-user-fills!
           apply-open-orders-success
           apply-open-orders-error
           apply-user-fills-success
           apply-user-fills-error
           fetch-and-merge-funding-history!]}]
  (when address
    (-> (request-frontend-open-orders! address {:priority :high})
        (.then (promise-effects/apply-success-and-return
                store
                apply-open-orders-success
                nil))
        (.catch (promise-effects/apply-error-and-reject
                 store
                 apply-open-orders-error)))
    (-> (request-user-fills! address {:priority :high})
        (.then (promise-effects/apply-success-and-return
                store
                apply-user-fills-success))
        (.catch (promise-effects/apply-error-and-reject
                 store
                 apply-user-fills-error)))
    (fetch-and-merge-funding-history! store address {:priority :high})))

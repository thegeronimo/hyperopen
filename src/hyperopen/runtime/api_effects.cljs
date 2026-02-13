(ns hyperopen.runtime.api-effects)

(defn fetch-asset-selector-markets!
  [{:keys [store
           opts
           request-asset-selector-markets-fn
           begin-asset-selector-load
           apply-asset-selector-success
           apply-asset-selector-error]}]
  (let [opts* (or opts {:phase :full})
        phase (if (= :bootstrap (:phase opts*)) :bootstrap :full)]
    (swap! store begin-asset-selector-load phase)
    (-> (request-asset-selector-markets-fn store opts*)
        (.then (fn [{:keys [phase market-state]}]
                 (swap! store apply-asset-selector-success phase market-state)
                 (:markets market-state)))
        (.catch (fn [err]
                  (swap! store apply-asset-selector-error err)
                  (js/Promise.reject err))))))

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
        (.then (fn [rows]
                 (swap! store apply-open-orders-success nil rows)
                 rows))
        (.catch (fn [err]
                  (swap! store apply-open-orders-error err)
                  (js/Promise.reject err))))
    (-> (request-user-fills! address {:priority :high})
        (.then (fn [rows]
                 (swap! store apply-user-fills-success rows)
                 rows))
        (.catch (fn [err]
                  (swap! store apply-user-fills-error err)
                  (js/Promise.reject err))))
    (fetch-and-merge-funding-history! store address {:priority :high})))

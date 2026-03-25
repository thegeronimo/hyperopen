(ns hyperopen.route-modules-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [shadow.loader :as loader]
            [hyperopen.route-modules :as route-modules]))

(deftest route-module-id-maps-non-trade-routes-test
  (is (nil? (route-modules/route-module-id "/trade")))
  (is (nil? (route-modules/route-module-id "/trade/HYPE")))
  (is (= :portfolio (route-modules/route-module-id "/portfolio")))
  (is (= :portfolio
         (route-modules/route-module-id "/portfolio/trader/0x1234567890abcdef1234567890abcdef12345678")))
  (is (= :leaderboard (route-modules/route-module-id "/leaderboard")))
  (is (= :funding-comparison (route-modules/route-module-id "/funding-comparison")))
  (is (= :staking (route-modules/route-module-id "/staking")))
  (is (= :api-wallets (route-modules/route-module-id "/api")))
  (is (= :vaults (route-modules/route-module-id "/vaults")))
  (is (nil? (route-modules/route-module-id "/portfoliox")))
  (is (= :vaults
         (route-modules/route-module-id "/vaults/0x1234567890abcdef1234567890abcdef12345678"))))

(deftest route-module-state-helpers-track-loading-loaded-and-failure-test
  (let [state {:route-modules (route-modules/default-state)}
        loading-state (route-modules/mark-route-module-loading state "/portfolio")
        loaded-state (route-modules/mark-route-module-loaded loading-state :portfolio)
        failed-state (route-modules/mark-route-module-failed state :staking (js/Error. "boom"))]
    (is (= :portfolio (get-in loading-state [:route-modules :loading])))
    (is (true? (route-modules/route-loading? loading-state "/portfolio")))
    (is (= #{:portfolio} (get-in loaded-state [:route-modules :loaded])))
    (is (= "boom" (route-modules/route-error failed-state "/staking")))))

(deftest route-ready-requires-a-resolved-exported-view-test
  (with-redefs [route-modules/resolved-route-view (fn [_module-id] nil)
                hyperopen.route-modules/resolve-module-view
                (fn [module-id]
                  (when (= module-id :vaults)
                    {:list nil
                     :detail nil}))]
    (is (false? (route-modules/route-ready? {:route-modules {:loaded #{:vaults}}}
                                            "/vaults")))))

(deftest load-route-module-fails-when-a-deferred-view-export-is-missing-test
  (async done
    (let [store (atom {:route-modules (route-modules/default-state)})]
      (with-redefs [loader/loaded? (constantly true)
                    route-modules/resolved-route-view (fn [_module-id] nil)
                    hyperopen.route-modules/resolve-module-view
                    (fn [module-id]
                      (when (= module-id :vaults)
                        {:list nil
                         :detail nil}))]
        (let [load-promise (route-modules/load-route-module! store "/vaults")]
          (is (false? (route-modules/route-ready? @store "/vaults")))
          (-> load-promise
            (.then (fn [_result]
                     (is false "expected vault module load to reject when route views are unresolved")
                     (done)))
            (.catch (fn [err]
                      (is (= "Loaded route module without exported view: :vaults"
                             (.-message err)))
                      (is (= "Loaded route module without exported view: :vaults"
                             (route-modules/route-error @store "/vaults")))
                      (done)))))))))

(deftest load-route-module-restores-vault-preview-only-for-list-route-test
  (async done
    (let [store (atom {:route-modules (route-modules/default-state)})
          restore-calls (atom [])]
      (with-redefs [loader/loaded? (constantly true)
                    route-modules/resolved-route-view (fn [_module-id] nil)
                    hyperopen.route-modules/resolve-module-view
                    (fn [module-id]
                      (when (= module-id :vaults)
                        {:list (fn [_state] [:div "list"])
                         :detail (fn [_state] [:div "detail"])}))
                    hyperopen.route-modules/maybe-restore-vaults-list-preview!
                    (fn [store-arg path]
                      (swap! restore-calls conj [store-arg path]))]
        (-> (route-modules/load-route-module! store "/vaults")
            (.then (fn [_]
                     (is (= [[store "/vaults"]] @restore-calls))
                     (reset! restore-calls [])
                     (-> (route-modules/load-route-module! store "/vaults/0x1234567890abcdef1234567890abcdef12345678")
                         (.then (fn [_]
                                  (is (= [] @restore-calls))
                                  (done)))
                         (.catch (fn [err]
                                   (is false (str "unexpected detail-route module load failure: " err))
                                   (done))))))
            (.catch (fn [err]
                      (is false (str "unexpected list-route module load failure: " err))
                      (done))))))))

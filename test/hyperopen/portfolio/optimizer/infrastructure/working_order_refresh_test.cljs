(ns hyperopen.portfolio.optimizer.infrastructure.working-order-refresh-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.infrastructure.working-order-refresh
             :as working-order-refresh]))

(def ^:private resting-ledger-row
  {:row-id "perp:xyz:TSM"
   :instrument-id "perp:xyz:TSM"
   :instrument-type :perp
   :coin "xyz:TSM"
   :side :sell
   :quantity 0.0238
   :price 447.0
   :status :resting
   :response {:status "ok"
              :response {:data {:statuses [{:resting {:oid 777}}]}}}})

(defn- base-state
  [& {:keys [path address rows open-orders fills]
      :or {path "/portfolio/optimize/draft"
           address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
           rows [resting-ledger-row]
           open-orders [{:oid 777 :coin "xyz:TSM" :sz "0.0238"}]
           fills []}}]
  {:router {:path path}
   :wallet {:address address}
   :orders {:open-orders-hydrated? true
            :open-orders open-orders
            :fills fills}
   :portfolio {:optimizer
               {:execution {:status :resting
                            :history [{:attempt-id "exec_1"
                                       :status :resting
                                       :rows rows}]}}}})

(defn- harness
  "Installs the watcher over `initial` with fake timers. Returns handles: the store,
  the recorded refresh count, a `tick!` that fires the captured interval fn (throws
  if none armed), and `armed?`."
  [initial]
  (let [store (atom initial)
        refreshes (atom 0)
        interval-fn (atom nil)
        cleared (atom 0)]
    (working-order-refresh/install-working-order-refresh!
     {:store store
      :refresh-open-orders! (fn [_store] (swap! refreshes inc))
      :set-interval-fn (fn [f _ms] (reset! interval-fn f) ::timer)
      :clear-interval-fn (fn [_id]
                           (swap! cleared inc)
                           (reset! interval-fn nil))})
    {:store store
     :refreshes refreshes
     :cleared cleared
     :armed? (fn [] (some? @interval-fn))
     :tick! (fn [] (@interval-fn))}))

(deftest arms-only-when-a-run-is-resting-on-the-optimizer-route-test
  (testing "resting ledger + optimizer route + own wallet => interval armed at install"
    (is (true? ((:armed? (harness (base-state)))))))
  (testing "no resting rows => not armed"
    (is (false? ((:armed? (harness (base-state
                                    :rows [(assoc resting-ledger-row
                                                  :status :submitted)])))))))
  (testing "away from the optimizer route => not armed"
    (is (false? ((:armed? (harness (base-state :path "/trade")))))))
  (testing "no own wallet (spectate) => not armed"
    (is (false? ((:armed? (harness (base-state :address nil))))))))

(deftest starts-and-stops-on-gate-transitions-test
  (let [{:keys [store armed?]} (harness (base-state :path "/trade"))]
    (is (false? (armed?)))
    (swap! store assoc-in [:router :path] "/portfolio/optimize/draft")
    (is (true? (armed?)) "entering the optimizer route with a resting run starts polling")
    (swap! store assoc-in [:router :path] "/trade")
    (is (false? (armed?)) "leaving the route stops polling")))

(deftest tick-refreshes-while-working-and-self-stops-when-done-test
  (let [{:keys [store refreshes armed? tick!]} (harness (base-state))]
    (tick!)
    (is (= 1 @refreshes) "the order still reconciles :resting -> refresh the book")
    (swap! store (fn [state]
                   (-> state
                       (assoc-in [:orders :open-orders] [])
                       (assoc-in [:orders :fills]
                                 [{:oid 777 :coin "xyz:TSM"
                                   :px "448.08" :sz "0.0238"}]))))
    (is (true? (armed?)) "cheap gate unchanged — the frozen ledger still reads :resting")
    (tick!)
    (is (= 1 @refreshes) "nothing is working anymore — no further refresh")
    (is (false? (armed?)) "the interval terminates itself")))

(ns hyperopen.vaults.screener.runner-test
  (:require [cljs.test :refer-macros [async deftest is use-fixtures]]
            [hyperopen.test-support.async :as async-support]
            [hyperopen.vaults.screener.model :as model]
            [hyperopen.vaults.screener.runner :as runner]))

(use-fixtures :each {:before runner/reset-runner-state!
                     :after runner/reset-runner-state!})

(def portfolio
  {:all-time {:accountValueHistory [[1 "1"]] :pnlHistory [[1 "0"]]}})

(defn- base-store
  [addresses]
  (atom {:vaults-ui {:screener (model/normalize-config {:criteria [{:metric :sharpe :value "1"}]
                                                         :min-history-points 0})}
         :vaults {:screener {:run {:id 1
                                   :status :running
                                   :config (model/normalize-config {})
                                   :addresses addresses
                                   :total (count addresses)
                                   :done 0 :cached 0 :fetched 0 :failed 0
                                   :interval-ms model/request-interval-ms}
                             :results {}}}}))

(defn- deps
  [store {:keys [clock requests sleeps toasts records on-request rate-limit]}]
  {:store store
   :now-ms (fn [] @clock)
   :sleep-ms! (fn [ms]
                (swap! sleeps conj ms)
                (swap! clock + ms)
                (when (and rate-limit (pos? ms))
                  (swap! store assoc-in [:websocket-ui :info-rate-limit] {:count 0 :until-ms 0}))
                (js/Promise.resolve nil))
   :request-vault-details! (fn [address opts]
                             (swap! requests conj [address opts @clock])
                             (when on-request (on-request address))
                             (if (or (= address "0xbad")
                                     (and (= address "0xflaky")
                                          (= 1 (count (filter #(= "0xflaky" (first %)) @requests)))))
                               (js/Promise.reject (js/Error. "boom"))
                               (js/Promise.resolve {:portfolio portfolio
                                                    :allow-deposits? (not= address "0xshut")})))
   :load-record! (fn [address]
                   (js/Promise.resolve (get @records address)))
   :persist-record! (fn [address entry]
                      (swap! records assoc address entry))
   :persist-index! (fn [_index _now] nil)
   :compute-metrics (fn [_details window]
                      {:point-count 30 :values {:sharpe 2} :window window})
   :show-toast! (fn [_store kind message] (swap! toasts conj [kind message]))})

(defn- harness
  []
  {:clock (atom 1000000)
   :requests (atom [])
   :sleeps (atom [])
   :toasts (atom [])
   :records (atom {})})

(deftest run-paces-requests-and-skips-fresh-cache-test
  (async done
    (let [store (base-store ["0xa" "0xb" "0xcached" "0xbad"])
          {:keys [requests toasts records] :as h} (harness)]
      (swap! records assoc "0xcached" {:saved-at-ms 999000 :portfolio portfolio :deposits-closed? false})
      (-> (runner/start-run! (deps store h) 1)
          (.then (fn [_]
                   (let [state @store
                         run (get-in state [:vaults :screener :run])
                         results (get-in state [:vaults :screener :results])
                         times (mapv #(nth % 2) @requests)]
                     (is (= ["0xa" "0xb" "0xbad" "0xbad" "0xbad"] (mapv first @requests))
                         "the cached vault is never requested; a failing one is tried 3 times")
                     (is (every? #(= :low (:priority (second %))) @requests))
                     (is (every? #(>= % model/request-interval-ms) (map - (rest times) times))
                         "consecutive requests are spaced by the interval")
                     (is (= :done (:status run)))
                     (is (= {:done 4 :cached 1 :fetched 3 :failed 1}
                            (select-keys run [:done :cached :fetched :failed])))
                     (is (= :six-month (get-in results ["0xa" :window])) "metrics use the run's window")
                     (is (= "boom" (get-in results ["0xbad" :error])))
                     (is (contains? @records "0xa") "fetched histories are persisted")
                     (is (number? (get-in state [:vaults :screener :cache-index "0xb"])))
                     (is (= 2 (:retries run)))
                     (is (= [[:success (str "Vault screen ready: 3 of 4 vaults pass. "
                                            "1 vault couldn't be loaded — Run again to retry them.")]]
                            @toasts))
                     (done))))
          (.catch (async-support/unexpected-error done))))))

(deftest vault-closed-to-deposits-is-left-out-test
  (async done
    (let [store (base-store ["0xa" "0xshut" "0xold"])
          {:keys [requests records] :as h} (harness)]
      ;; Written before deposits were tracked: fresh, but must be re-read.
      (swap! records assoc "0xold" {:saved-at-ms 999000 :portfolio portfolio})
      (-> (runner/start-run! (deps store h) 1)
          (.then (fn [_]
                   (let [state @store
                         results (get-in state [:vaults :screener :results])
                         config (model/normalize-config (get-in state [:vaults-ui :screener]))]
                     (is (= ["0xa" "0xshut" "0xold"] (mapv first @requests)))
                     (is (true? (get-in results ["0xshut" :deposits-closed?])))
                     (is (= :closed (:status (model/evaluate config (get results "0xshut")))))
                     (is (= :pass (:status (model/evaluate config (get results "0xa")))))
                     (is (true? (get-in @records ["0xshut" :deposits-closed?])) "the flag is cached")
                     (is (false? (get-in @records ["0xold" :deposits-closed?])))
                     (done))))
          (.catch (async-support/unexpected-error done))))))

(deftest cached-closed-vault-stays-out-test
  (async done
    (let [store (base-store ["0xshut"])
          {:keys [requests records] :as h} (harness)]
      (swap! records assoc "0xshut" {:saved-at-ms 999000 :portfolio portfolio :deposits-closed? true})
      (-> (runner/start-run! (deps store h) 1)
          (.then (fn [_]
                   (is (empty? @requests))
                   (is (true? (get-in @store [:vaults :screener :results "0xshut" :deposits-closed?])))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest transient-failure-is-retried-test
  (async done
    (let [store (base-store ["0xflaky"])
          {:keys [requests sleeps] :as h} (harness)]
      (-> (runner/start-run! (deps store h) 1)
          (.then (fn [_]
                   (let [run (get-in @store [:vaults :screener :run])]
                     (is (= ["0xflaky" "0xflaky"] (mapv first @requests)))
                     (is (some #{runner/retry-delay-ms} @sleeps) "waits before retrying")
                     (is (nil? (get-in @store [:vaults :screener :results "0xflaky" :error])))
                     (is (= 0 (:failed run)))
                     (is (= 1 (:retries run)))
                     (done))))
          (.catch (async-support/unexpected-error done))))))

(deftest pause-stops-the-loop-and-resume-continues-test
  (async done
    (let [store (base-store ["0xa" "0xb" "0xc"])
          h (assoc (harness) :on-request (fn [address]
                                           (when (= address "0xa")
                                             (swap! store assoc-in [:vaults :screener :run :status] :paused))))]
      (-> (runner/start-run! (deps store h) 1)
          (.then (fn [_]
                   (is (= ["0xa"] (mapv first @(:requests h))))
                   (is (= :paused (get-in @store [:vaults :screener :run :status])))
                   (is (contains? (get-in @store [:vaults :screener :results]) "0xa")
                       "a request already in flight still lands")
                   (swap! store assoc-in [:vaults :screener :run :status] :running)
                   (runner/start-run! (deps store (dissoc h :on-request)) 1)))
          (.then (fn [_]
                   (is (= ["0xa" "0xb" "0xc"] (mapv first @(:requests h))))
                   (is (= :done (get-in @store [:vaults :screener :run :status])))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest replaced-run-is-ignored-test
  (async done
    (let [store (base-store ["0xa" "0xb"])
          h (assoc (harness) :on-request (fn [_]
                                           (swap! store assoc-in [:vaults :screener :run :id] 2)))]
      (-> (runner/start-run! (deps store h) 1)
          (.then (fn [_]
                   (is (= ["0xa"] (mapv first @(:requests h))))
                   (is (empty? (get-in @store [:vaults :screener :results]))
                       "results from a replaced run are dropped")
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest rate-limit-cooldown-waits-and-slows-down-test
  (async done
    (let [store (base-store ["0xa" "0xb"])
          {:keys [clock sleeps] :as h} (assoc (harness) :rate-limit true)]
      (swap! store assoc-in [:websocket-ui :info-rate-limit] {:count 3 :until-ms (+ @clock 5000)})
      (-> (runner/start-run! (deps store h) 1)
          (.then (fn [_]
                   (is (some #(>= % 5000) @sleeps) "waits out the cooldown before the first request")
                   (is (= :done (get-in @store [:vaults :screener :run :status])))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest new-rate-limit-event-doubles-the-interval-test
  (async done
    (let [store (base-store ["0xa" "0xb"])
          h (assoc (harness) :on-request (fn [address]
                                           (when (= address "0xa")
                                             (swap! store assoc-in [:websocket-ui :info-rate-limit]
                                                    {:count 1 :until-ms 0}))))]
      (-> (runner/start-run! (deps store h) 1)
          (.then (fn [_]
                   (is (= (* 2 model/request-interval-ms)
                          (get-in @store [:vaults :screener :run :interval-ms])))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest load-cache-index-merges-and-marks-loaded-test
  (async done
    (let [store (atom {:vaults {:screener {:cache-index {"0xnew" 5}}}})]
      (-> (runner/load-cache-index! {:store store
                                     :load-index! (fn [] (js/Promise.resolve {"0xold" 1 "0xnew" 2}))})
          (.then (fn [_]
                   (is (= {"0xold" 1 "0xnew" 5} (get-in @store [:vaults :screener :cache-index]))
                       "entries written this session win over the stored index")
                   (is (true? (get-in @store [:vaults :screener :cache-index-loaded?])))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest refresh-run-updates-the-saved-view-test
  (async done
    (let [store (base-store ["0xa" "0xb"])
          persisted (atom nil)
          {:keys [toasts] :as h} (harness)]
      (swap! store update-in [:vaults :screener :run] assoc :saved-view-id "v1")
      (swap! store assoc-in [:vaults :saved-views]
             [{:id "v1" :name "Mine" :membership :update :scope :pass
               :config (model/normalize-config {:criteria [{:metric :sharpe :value "1"}]
                                                :min-history-points 0})
               :members ["0xold"] :metrics-by-address {}}])
      (-> (runner/start-run! (assoc (deps store h)
                                    :persist-saved-views! (fn [views _] (reset! persisted views)))
                             1)
          (.then (fn [_]
                   (let [view (first (get-in @store [:vaults :saved-views]))]
                     (is (= ["0xa" "0xb"] (:members view)))
                     (is (= {:joined ["0xa" "0xb"] :left ["0xold"]} (:last-diff view)))
                     (is (= view (first @persisted)) "the refreshed view is persisted")
                     (is (= [[:success "Refreshed “Mine”: 2 vaults joined, 1 left."]] @toasts)))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest load-saved-views-normalizes-and-marks-loaded-test
  (async done
    (let [store (atom {})]
      (-> (runner/load-saved-views! {:store store
                                     :load-saved-views! (fn [] (js/Promise.resolve
                                                                [{:id "v1" :name "A" :members ["0xA"]}]))})
          (.then (fn [_]
                   (is (= ["0xa"] (get-in @store [:vaults :saved-views 0 :members])))
                   (is (true? (get-in @store [:vaults :saved-views-loaded?])))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

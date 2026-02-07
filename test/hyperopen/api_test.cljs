(ns hyperopen.api-test
  (:require [cljs.test :refer-macros [async deftest is use-fixtures]]
            [hyperopen.api :as api]))

(defn- fake-response
  [status]
  (doto (js-obj)
    (aset "status" status)
    (aset "ok" (= status 200))
    (aset "json" (fn [] (js/Promise.resolve #js {})))))

(use-fixtures
  :each
  {:before (fn []
             (api/reset-request-runtime!))
   :after (fn []
            (api/reset-request-runtime!))})

(deftest ensure-perp-dexs-single-flight-test
  (async done
    (let [store (atom {:perp-dexs []})
          calls (atom 0)
          original-post-info hyperopen.api/post-info!]
      (set! hyperopen.api/post-info!
            (fn post-info-mock
              ([body]
               (post-info-mock body {}))
              ([body _opts]
               (swap! calls inc)
               (js/Promise.resolve
                #js {:status 200
                     :ok true
                     :json (fn []
                             (js/Promise.resolve #js [#js {:name "dex-a"}]))}))
              ([body opts _attempt]
               (post-info-mock body opts))))
      (let [p1 (api/ensure-perp-dexs! store)
            p2 (api/ensure-perp-dexs! store)]
        (is (identical? p1 p2))
        (-> (js/Promise.all #js [p1 p2])
            (.then (fn [results]
                     (is (= [["dex-a"] ["dex-a"]]
                            (js->clj results)))
                     (is (= 1 @calls))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Unexpected error: " err))
                      (done)))
            (.finally
              (fn []
                (set! hyperopen.api/post-info! original-post-info))))))))

(deftest scheduler-prioritizes-high-after-saturation-test
  (async done
    (let [enqueue-request! @#'hyperopen.api/enqueue-request!
          started (atom [])
          releases (atom {})
          make-task (fn [label]
                      (fn []
                        (swap! started conj label)
                        (js/Promise.
                         (fn [resolve _]
                           (swap! releases assoc label resolve)))))]
      (doseq [label [:low-1 :low-2 :low-3 :low-4]]
        (enqueue-request! :low (make-task label)))
      (enqueue-request! :high (make-task :high-1))
      (enqueue-request! :low (make-task :low-5))
      (is (= [:low-1 :low-2 :low-3 :low-4] @started))
      ((get @releases :low-1) :ok)
      (js/setTimeout
       (fn []
         (is (= :high-1 (nth @started 4)))
         (doseq [label [:low-2 :low-3 :low-4 :high-1]]
           (when-let [resolve! (get @releases label)]
             (resolve! :ok)))
         (js/setTimeout
          (fn []
            (is (= :low-5 (last @started)))
            (when-let [resolve! (get @releases :low-5)]
              (resolve! :ok))
            (done))
          0))
       0))))

(deftest retry-path-reenters-scheduler-test
  (async done
    (let [enqueue-count (atom 0)
          step (atom 0)
          original-enqueue hyperopen.api/enqueue-info-request!
          original-wait hyperopen.api/wait-ms]
      (set! hyperopen.api/enqueue-info-request!
            (fn [_ _]
              (swap! enqueue-count inc)
              (let [status (if (zero? @step) 500 200)]
                (swap! step inc)
                (js/Promise.resolve (fake-response status)))))
      (set! hyperopen.api/wait-ms
            (fn [_]
              (js/Promise.resolve nil)))
      (-> (@#'hyperopen.api/post-info! {"type" "perpDexs"} {:priority :high})
          (.then (fn [resp]
                   (is (= 2 @enqueue-count))
                   (is (= 200 (.-status resp)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))
          (.finally
            (fn []
              (set! hyperopen.api/enqueue-info-request! original-enqueue)
              (set! hyperopen.api/wait-ms original-wait)))))))

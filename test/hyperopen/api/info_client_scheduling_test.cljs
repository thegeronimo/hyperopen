(ns hyperopen.api.info-client-scheduling-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.info-client :as info-client]
            [hyperopen.test-support.info-client :as info-support]))

(deftest scheduler-prioritizes-high-after-saturation-test
  (async done
    (let [client (info-client/make-info-client {:log-fn (fn [& _])})
          enqueue-request! (:enqueue-request! client)
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

(deftest info-client-retries-and-parses-data-test
  (async done
    (let [attempts (atom 0)
          sleeps (atom [])
          client (info-client/make-info-client
                  {:fetch-fn (fn [_ _]
                               (let [status (if (zero? @attempts) 500 200)]
                                 (swap! attempts inc)
                                 (if (= status 200)
                                   (doto (info-support/fake-http-response 200)
                                     (aset "json" (fn []
                                                    (js/Promise.resolve #js [#js {:name "dex-a"}]))))
                                   (js/Promise.resolve (info-support/fake-http-response status)))))
                   :sleep-ms-fn (fn [ms]
                                  (swap! sleeps conj ms)
                                  (js/Promise.resolve nil))
                   :log-fn (fn [& _])})]
      (-> ((:request-info! client) {"type" "perpDexs"} {:priority :high})
          (.then (fn [data]
                   (is (= 2 @attempts))
                   (is (= 1 (count @sleeps)))
                   (is (= [{:name "dex-a"}] data))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

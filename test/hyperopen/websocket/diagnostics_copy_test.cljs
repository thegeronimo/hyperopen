(ns hyperopen.websocket.diagnostics-copy-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [clojure.string :as str]
            [hyperopen.websocket.diagnostics-copy :as diagnostics-copy]))

(defn- record-copy-status!
  [statuses]
  (fn [store status]
    (swap! statuses conj status)
    (swap! store assoc-in [:websocket-ui :copy-status] status)))

(deftest copy-websocket-diagnostics-success-updates-status-and-writes-redacted-payload-test
  (async done
    (let [written (atom nil)
          statuses (atom [])
          store (atom {:websocket {:health {:generated-at-ms 1700000000000}}
                       :websocket-ui {:copy-status {:kind :stale}}})
          clipboard #js {:writeText (fn [payload]
                                      (reset! written payload)
                                      (js/Promise.resolve true))}]
      (diagnostics-copy/copy-websocket-diagnostics!
       {:store store
        :diagnostics-copy-payload (fn [_ _]
                                    {:secret "token"
                                     :kind "payload"})
        :sanitize-value (fn [_ payload]
                          (assoc payload :secret "<redacted>"))
        :set-copy-status! (record-copy-status! statuses)
        :copy-success-status (fn [health]
                               {:kind :success
                                :at-ms (:generated-at-ms health)})
        :copy-error-status (fn [health diagnostics-json]
                             {:kind :error
                              :at-ms (:generated-at-ms health)
                              :fallback-json diagnostics-json})
        :log-fn (fn [& _] nil)
        :clipboard clipboard})
      (js/setTimeout
       (fn []
         (try
           (is (= [nil {:kind :success :at-ms 1700000000000}]
                  @statuses))
           (is (= :success
                  (get-in @store [:websocket-ui :copy-status :kind])))
           (is (str/includes? @written "<redacted>"))
           (is (not (str/includes? @written "\"token\"")))
           (finally
             (done))))
       0))))

(deftest copy-websocket-diagnostics-uses-error-status-when-clipboard-unavailable-test
  (let [statuses (atom [])
        logs (atom [])
        store (atom {:websocket {:health {:generated-at-ms 1700000000000}}
                     :websocket-ui {:copy-status nil}})]
    (diagnostics-copy/copy-websocket-diagnostics!
     {:store store
      :diagnostics-copy-payload (fn [_ _] {:kind "payload"})
      :sanitize-value (fn [_ payload] payload)
      :set-copy-status! (record-copy-status! statuses)
      :copy-success-status (fn [_] {:kind :success})
      :copy-error-status (fn [health diagnostics-json]
                           {:kind :error
                            :at-ms (:generated-at-ms health)
                            :fallback-json diagnostics-json})
      :log-fn (fn [& args]
                (swap! logs conj (first args)))
      :clipboard #js {}
      :stringify-fn (fn [_] "{\"kind\":\"payload\"}")})
    (is (= [nil {:kind :error
                 :at-ms 1700000000000
                 :fallback-json "{\"kind\":\"payload\"}"}]
           @statuses))
    (is (= :error
           (get-in @store [:websocket-ui :copy-status :kind])))
    (is (= "Clipboard API unavailable for websocket diagnostics copy"
           (first @logs)))))

(deftest copy-websocket-diagnostics-uses-error-status-when-clipboard-write-fails-test
  (async done
    (let [statuses (atom [])
          logs (atom [])
          store (atom {:websocket {:health {:generated-at-ms 1700000000000}}
                       :websocket-ui {:copy-status nil}})
          clipboard #js {:writeText (fn [_]
                                      (js/Promise.reject (js/Error. "denied")))}]
      (diagnostics-copy/copy-websocket-diagnostics!
       {:store store
        :diagnostics-copy-payload (fn [_ _] {:kind "payload"})
        :sanitize-value (fn [_ payload] payload)
        :set-copy-status! (record-copy-status! statuses)
        :copy-success-status (fn [_] {:kind :success})
        :copy-error-status (fn [health diagnostics-json]
                             {:kind :error
                              :at-ms (:generated-at-ms health)
                              :fallback-json diagnostics-json})
        :log-fn (fn [& args]
                  (swap! logs conj (first args)))
        :clipboard clipboard
        :stringify-fn (fn [_] "{\"kind\":\"payload\"}")})
      (js/setTimeout
       (fn []
         (try
           (is (= [nil {:kind :error
                        :at-ms 1700000000000
                        :fallback-json "{\"kind\":\"payload\"}"}]
                  @statuses))
           (is (= :error
                  (get-in @store [:websocket-ui :copy-status :kind])))
           (is (= "Copy diagnostics failed:"
                  (first @logs)))
           (finally
             (done))))
       0))))

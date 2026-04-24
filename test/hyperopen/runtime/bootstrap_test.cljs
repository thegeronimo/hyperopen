(ns hyperopen.runtime.bootstrap-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.runtime.bootstrap :as runtime-bootstrap]))

(deftest register-runtime-invokes-all-registrars-with-handler-maps-test
  (let [effect-calls (atom [])
        action-calls (atom [])
        system-calls (atom 0)
        placeholder-calls (atom 0)
        effect-handlers {:save (fn [& _] nil)}
        action-handlers {:navigate (fn [& _] nil)}]
    (runtime-bootstrap/register-runtime!
     {:register-effects! (fn [handlers]
                           (swap! effect-calls conj handlers))
      :effect-handlers effect-handlers
      :register-actions! (fn [handlers]
                           (swap! action-calls conj handlers))
      :action-handlers action-handlers
      :register-system-state! (fn []
                                (swap! system-calls inc))
      :register-placeholders! (fn []
                                (swap! placeholder-calls inc))})
    (is (= [effect-handlers] @effect-calls))
    (is (= [action-handlers] @action-calls))
    (is (= 1 @system-calls))
    (is (= 1 @placeholder-calls))))

(deftest install-render-loop-wires-dispatch-and-batches-renders-per-frame-test
  (let [store (atom {:count 0})
        dispatch-calls (atom [])
        render-calls (atom [])
        scheduled-frame-callbacks (atom [])
        captured-dispatch-fn (atom nil)]
    (runtime-bootstrap/install-render-loop!
     {:store store
      :render-watch-key ::render
      :set-dispatch! (fn [dispatch-fn]
                       (reset! captured-dispatch-fn dispatch-fn))
      :dispatch! (fn [runtime-store event effects]
                   (swap! dispatch-calls conj [runtime-store event effects]))
      :render! (fn [state]
                 (swap! render-calls conj state))
      :document? true
      :request-animation-frame! (fn [cb]
                                  (swap! scheduled-frame-callbacks conj cb)
                                  :frame-id)})
    (@captured-dispatch-fn :event [[:actions/navigate "/trade"]])
    (swap! store assoc :count 1)
    (swap! store assoc :count 2)
    (is (empty? @render-calls))
    (is (= 1 (count @scheduled-frame-callbacks)))
    ((first @scheduled-frame-callbacks) 0)
    (swap! store assoc :count 3)
    (is (= 2 (count @scheduled-frame-callbacks)))
    ((second @scheduled-frame-callbacks) 0)
    (is (= [[store :event [[:actions/navigate "/trade"]]]] @dispatch-calls))
    (is (= [{:count 2} {:count 3}] @render-calls))))

(deftest install-render-loop-defers-updates-raised-during-render-test
  (let [store (atom {:count 0})
        render-calls (atom [])
        scheduled-frame-callbacks (atom [])
        scheduled-count-during-render (atom nil)]
    (runtime-bootstrap/install-render-loop!
     {:store store
      :render-watch-key ::render-reentrant
      :set-dispatch! (fn [_] nil)
      :dispatch! (fn [& _] nil)
      :render! (fn [state]
                 (swap! render-calls conj state)
                 (when (= 1 (:count state))
                   (swap! store assoc :count 2)
                   (reset! scheduled-count-during-render
                           (count @scheduled-frame-callbacks))))
      :document? true
      :request-animation-frame! (fn [cb]
                                  (swap! scheduled-frame-callbacks conj cb)
                                  :frame-id)})
    (swap! store assoc :count 1)
    (is (= 1 (count @scheduled-frame-callbacks)))
    ((first @scheduled-frame-callbacks) 0)
    (is (= 1 @scheduled-count-during-render))
    (is (= 2 (count @scheduled-frame-callbacks)))
    ((second @scheduled-frame-callbacks) 0)
    (is (= [{:count 1} {:count 2}] @render-calls))))

(deftest install-render-loop-skips-watch-registration-when-document-missing-test
  (let [store (atom {:count 0})
        render-calls (atom [])
        captured-dispatch-fn (atom nil)]
    (runtime-bootstrap/install-render-loop!
     {:store store
      :render-watch-key ::render-no-doc
      :set-dispatch! (fn [dispatch-fn]
                       (reset! captured-dispatch-fn dispatch-fn))
      :dispatch! (fn [& _] nil)
      :render! (fn [state]
                 (swap! render-calls conj state))
      :document? false})
    (@captured-dispatch-fn :event [])
    (swap! store assoc :count 1)
    (is (empty? @render-calls))))

(deftest install-render-loop-emits-render-telemetry-with-root-diff-and-duration-test
  (let [store (atom {:count 0
                     :router {:path "/trade"}
                     :orderbooks {}})
        scheduled-frame-callbacks (atom [])
        emitted-events (atom [])
        now-values (atom [100 112])
        now-ms-fn (fn []
                    (let [value (first @now-values)]
                      (swap! now-values subvec 1)
                      value))]
    (runtime-bootstrap/install-render-loop!
     {:store store
      :render-watch-key ::render-telemetry
      :set-dispatch! (fn [_] nil)
      :dispatch! (fn [& _] nil)
      :render! (fn [_] nil)
      :document? true
      :request-animation-frame! (fn [cb]
                                  (swap! scheduled-frame-callbacks conj cb)
                                  :frame-id)
      :emit-fn (fn [event payload]
                 (swap! emitted-events conj [event payload]))
      :now-ms-fn now-ms-fn})
    (swap! store assoc :count 1)
    (swap! store assoc-in [:router :path] "/portfolio")
    (is (= 1 (count @scheduled-frame-callbacks)))
    ((first @scheduled-frame-callbacks) 0)
    (is (= [[:ui/app-render-flush
             {:changed-root-keys [:count :router]
              :changed-root-key-count 2
              :render-duration-ms 12}]]
           @emitted-events))))

(deftest install-runtime-watchers-delegates-store-and-websocket-watcher-deps-test
  (let [store (atom {})
        store-watcher-calls (atom [])
        websocket-watcher-calls (atom [])
        store-cache-watchers-deps {:persist-active-market-display! (fn [& _] nil)}
        websocket-watchers-deps {:store store
                                 :sync-websocket-health! (fn [& _] nil)}]
    (runtime-bootstrap/install-runtime-watchers!
     {:store store
      :install-store-cache-watchers! (fn [runtime-store deps]
                                       (swap! store-watcher-calls conj [runtime-store deps]))
      :store-cache-watchers-deps store-cache-watchers-deps
      :install-websocket-watchers! (fn [deps]
                                     (swap! websocket-watcher-calls conj deps))
      :websocket-watchers-deps websocket-watchers-deps})
    (is (= [[store store-cache-watchers-deps]] @store-watcher-calls))
    (is (= [websocket-watchers-deps] @websocket-watcher-calls))))

(deftest bootstrap-runtime-installs-state-validation-when-provided-test
  (let [validation-calls (atom [])]
    (runtime-bootstrap/bootstrap-runtime!
     {:register-runtime-deps {:register-effects! (fn [& _] nil)
                              :effect-handlers {}
                              :register-actions! (fn [& _] nil)
                              :action-handlers {}
                              :register-system-state! (fn [] nil)
                              :register-placeholders! (fn [] nil)}
      :render-loop-deps {:store (atom {})
                         :render-watch-key ::validation-test-render
                         :set-dispatch! (fn [& _] nil)
                         :dispatch! (fn [& _] nil)
                         :render! (fn [& _] nil)
                         :document? false}
      :watchers-deps {:store (atom {})
                      :install-store-cache-watchers! (fn [& _] nil)
                      :store-cache-watchers-deps {}
                      :install-websocket-watchers! (fn [& _] nil)
                      :websocket-watchers-deps {}}
      :validation-deps {:store :store-id
                        :install-store-state-validation! (fn [store]
                                                           (swap! validation-calls conj store))}})
    (is (= [:store-id] @validation-calls))))

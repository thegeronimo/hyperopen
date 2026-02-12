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

(deftest install-render-loop-wires-dispatch-and-state-watch-render-test
  (let [store (atom {:count 0})
        dispatch-calls (atom [])
        render-calls (atom [])
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
      :document? true})
    (@captured-dispatch-fn :event [[:actions/navigate "/trade"]])
    (swap! store assoc :count 1)
    (is (= [[store :event [[:actions/navigate "/trade"]]]] @dispatch-calls))
    (is (= [{:count 1}] @render-calls))))

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

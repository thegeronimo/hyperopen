(ns hyperopen.telemetry.console-warning-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.telemetry.console-warning :as warning]))

(defn- has-own?
  [obj key]
  (.call (.-hasOwnProperty js/Object.prototype) obj key))

(deftest emit-warning-logs-banner-and-warning-in-browser-context-test
  (let [orig-document (.-document js/globalThis)
        had-document? (has-own? js/globalThis "document")
        orig-log (.-log js/console)
        calls (atom [])]
    (try
      (set! (.-document js/globalThis) (js-obj))
      (set! (.-log js/console) (fn [& args]
                                 (swap! calls conj (vec args))))
      (warning/emit-warning!)
      (is (>= (count @calls) 3))
      (is (some (fn [args]
                  (some #(and (string? %)
                              (>= (.indexOf % "Warning!") 0))
                        args))
                @calls))
      (finally
        (set! (.-log js/console) orig-log)
        (if had-document?
          (set! (.-document js/globalThis) orig-document)
          (js-delete js/globalThis "document"))))))

(deftest emit-warning-noops-without-browser-document-test
  (let [orig-document (.-document js/globalThis)
        had-document? (has-own? js/globalThis "document")
        orig-log (.-log js/console)
        calls (atom [])]
    (try
      (js-delete js/globalThis "document")
      (set! (.-log js/console) (fn [& args]
                                 (swap! calls conj args)))
      (warning/emit-warning!)
      (is (empty? @calls))
      (finally
        (set! (.-log js/console) orig-log)
        (if had-document?
          (set! (.-document js/globalThis) orig-document)
          (js-delete js/globalThis "document"))))))

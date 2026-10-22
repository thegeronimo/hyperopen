(ns hyperopen.ui.preferences-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.ui.preferences :as preferences]))

(defn- has-own?
  [obj key]
  (.call (.-hasOwnProperty js/Object.prototype) obj key))

(deftest restore-ui-font-preference-uses-stored-valid-value-test
  (let [orig-document (.-document js/globalThis)
        had-document? (has-own? js/globalThis "document")
        html-el (js-obj "dataset" (js-obj))]
    (try
      (set! (.-document js/globalThis) (js-obj "documentElement" html-el))
      (with-redefs [hyperopen.platform/local-storage-get (fn [_] " Inter ")]
        (preferences/restore-ui-font-preference!)
        (is (= "inter" (.. js/document -documentElement -dataset -uiFont))))
      (finally
        (if had-document?
          (set! (.-document js/globalThis) orig-document)
          (js-delete js/globalThis "document"))))))

(deftest restore-ui-font-preference-falls-back-to-inter-test
  (let [orig-document (.-document js/globalThis)
        had-document? (has-own? js/globalThis "document")
        html-el (js-obj "dataset" (js-obj))]
    (try
      (set! (.-document js/globalThis) (js-obj "documentElement" html-el))
      (with-redefs [hyperopen.platform/local-storage-get (fn [_] "not-supported")]
        (preferences/restore-ui-font-preference!)
        (is (= "inter" (.. js/document -documentElement -dataset -uiFont))))
      (with-redefs [hyperopen.platform/local-storage-get (fn [_]
                                                            (throw (js/Error. "boom")))]
        (preferences/restore-ui-font-preference!)
        (is (= "inter" (.. js/document -documentElement -dataset -uiFont))))
      (finally
        (if had-document?
          (set! (.-document js/globalThis) orig-document)
          (js-delete js/globalThis "document"))))))

(deftest restore-ui-font-preference-keeps-a-stored-system-choice-test
  (let [orig-document (.-document js/globalThis)
        had-document? (has-own? js/globalThis "document")
        html-el (js-obj "dataset" (js-obj))]
    (try
      (set! (.-document js/globalThis) (js-obj "documentElement" html-el))
      (with-redefs [hyperopen.platform/local-storage-get (fn [_] "system")]
        (preferences/restore-ui-font-preference!)
        (is (= "system" (.. js/document -documentElement -dataset -uiFont))))
      (finally
        (if had-document?
          (set! (.-document js/globalThis) orig-document)
          (js-delete js/globalThis "document"))))))

(deftest restore-ui-theme-preference-uses-stored-valid-value-test
  (let [orig-document (.-document js/globalThis)
        had-document? (has-own? js/globalThis "document")
        html-el (js-obj "dataset" (js-obj))
        store (atom {})]
    (try
      (set! (.-document js/globalThis) (js-obj "documentElement" html-el))
      (with-redefs [hyperopen.platform/local-storage-get (fn [_] " HyperDegen ")]
        (is (= "hyperdegen" (preferences/restore-ui-theme-preference! store)))
        (is (= "hyperdegen" (.. js/document -documentElement -dataset -theme)))
        (is (= "hyperdegen" (get-in @store [:ui :theme]))))
      (finally
        (if had-document?
          (set! (.-document js/globalThis) orig-document)
          (js-delete js/globalThis "document"))))))

(deftest restore-ui-theme-preference-falls-back-to-default-test
  (let [orig-document (.-document js/globalThis)
        had-document? (has-own? js/globalThis "document")
        html-el (js-obj "dataset" (js-obj))
        store (atom {})]
    (try
      (set! (.-document js/globalThis) (js-obj "documentElement" html-el))
      (with-redefs [hyperopen.platform/local-storage-get (fn [_] "not-a-theme")]
        (preferences/restore-ui-theme-preference! store)
        (is (= "dark" (.. js/document -documentElement -dataset -theme)))
        (is (= "dark" (get-in @store [:ui :theme]))))
      (with-redefs [hyperopen.platform/local-storage-get (fn [_]
                                                           (throw (js/Error. "boom")))]
        (preferences/restore-ui-theme-preference! store)
        (is (= "dark" (.. js/document -documentElement -dataset -theme))))
      (finally
        (if had-document?
          (set! (.-document js/globalThis) orig-document)
          (js-delete js/globalThis "document"))))))

(deftest restore-ui-theme-preference-no-document-noop-test
  (let [orig-document (.-document js/globalThis)
        had-document? (has-own? js/globalThis "document")
        calls (atom 0)]
    (try
      (js-delete js/globalThis "document")
      (with-redefs [hyperopen.platform/local-storage-get (fn [_]
                                                           (swap! calls inc)
                                                           "hyperdegen")]
        (preferences/restore-ui-theme-preference! (atom {}))
        (is (= 0 @calls)))
      (finally
        (if had-document?
          (set! (.-document js/globalThis) orig-document)
          (js-delete js/globalThis "document"))))))

(deftest restore-ui-font-preference-no-document-noop-test
  (let [orig-document (.-document js/globalThis)
        had-document? (has-own? js/globalThis "document")
        calls (atom 0)]
    (try
      (js-delete js/globalThis "document")
      (with-redefs [hyperopen.platform/local-storage-get (fn [_]
                                                            (swap! calls inc)
                                                            "inter")]
        (preferences/restore-ui-font-preference!)
        (is (= 0 @calls)))
      (finally
        (if had-document?
          (set! (.-document js/globalThis) orig-document)
          (js-delete js/globalThis "document"))))))

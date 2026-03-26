(ns hyperopen.ui.fonts-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.ui.fonts :as fonts]))

(defn- has-own?
  [obj key]
  (.call (.-hasOwnProperty js/Object.prototype) obj key))

(defn- restore-global!
  [key had-own? original]
  (if had-own?
    (aset js/globalThis key original)
    (js-delete js/globalThis key)))

(defn- computed-style
  [values]
  (js-obj "getPropertyValue" (fn [prop-name]
                               (get values prop-name ""))))

(defn- non-blank-text*
  [value]
  (js* "hyperopen.ui.fonts.non_blank_text(~{})" value))

(defn- expand-ui-system-var*
  [font-family system-font-family]
  (js* "hyperopen.ui.fonts.expand_ui_system_var(~{}, ~{})" font-family system-font-family))

(deftest private-helper-contracts-test
  (is (= "IBM Plex Sans"
         (non-blank-text* "  IBM Plex Sans  ")))
  (is (nil? (non-blank-text* "   ")))
  (is (nil? (expand-ui-system-var* nil "Inter, sans-serif")))
  (is (= "Inter, sans-serif, \"IBM Plex Sans\""
         (expand-ui-system-var* " var(--font-ui-system), \"IBM Plex Sans\" "
                                "Inter, sans-serif"))))

(deftest resolve-ui-font-family-without-window-returns-default-test
  (let [orig-window (aget js/globalThis "window")
        had-window? (has-own? js/globalThis "window")
        orig-document (aget js/globalThis "document")
        had-document? (has-own? js/globalThis "document")]
    (try
      (js-delete js/globalThis "window")
      (aset js/globalThis "document" #js {:documentElement #js {}})
      (is (= fonts/default-ui-system-font-family
             (fonts/resolve-ui-font-family)))
      (finally
        (restore-global! "window" had-window? orig-window)
        (restore-global! "document" had-document? orig-document)))))

(deftest resolve-ui-font-family-without-document-returns-default-test
  (let [orig-window (aget js/globalThis "window")
        had-window? (has-own? js/globalThis "window")
        orig-document (aget js/globalThis "document")
        had-document? (has-own? js/globalThis "document")
        get-computed-style-calls (atom 0)]
    (try
      (aset js/globalThis
            "window"
            #js {:getComputedStyle (fn [_]
                                     (swap! get-computed-style-calls inc)
                                     (computed-style {}))})
      (js-delete js/globalThis "document")
      (is (= fonts/default-ui-system-font-family
             (fonts/resolve-ui-font-family)))
      (is (= 0 @get-computed-style-calls))
      (finally
        (restore-global! "window" had-window? orig-window)
        (restore-global! "document" had-document? orig-document)))))

(deftest resolve-ui-font-family-expands-system-var-and-canvas-font-arities-test
  (let [orig-window (aget js/globalThis "window")
        had-window? (has-own? js/globalThis "window")
        orig-document (aget js/globalThis "document")
        had-document? (has-own? js/globalThis "document")
        root #js {}
        expected-font-family "Inter, sans-serif, \"IBM Plex Sans\""]
    (try
      (aset js/globalThis
            "window"
            #js {:getComputedStyle (fn [node]
                                     (is (identical? root node))
                                     (computed-style {"--font-ui-system" " Inter, sans-serif "
                                                      "--font-ui" " var(--font-ui-system), \"IBM Plex Sans\" "}))})
      (aset js/globalThis "document" #js {:documentElement root})
      (is (= expected-font-family
             (fonts/resolve-ui-font-family)))
      (is (= (str "400 12px " expected-font-family)
             (fonts/canvas-font 12)))
      (is (= (str "500 14px " expected-font-family)
             (fonts/canvas-font 14 500)))
      (finally
        (restore-global! "window" had-window? orig-window)
        (restore-global! "document" had-document? orig-document)))))

(deftest resolve-ui-font-family-falls-back-when-css-vars-are-blank-test
  (let [orig-window (aget js/globalThis "window")
        had-window? (has-own? js/globalThis "window")
        orig-document (aget js/globalThis "document")
        had-document? (has-own? js/globalThis "document")
        root #js {}]
    (try
      (aset js/globalThis
            "window"
            #js {:getComputedStyle (fn [_]
                                     (computed-style {"--font-ui-system" "   "
                                                      "--font-ui" "   "}))})
      (aset js/globalThis "document" #js {:documentElement root})
      (is (= fonts/default-ui-system-font-family
             (fonts/resolve-ui-font-family)))
      (finally
        (restore-global! "window" had-window? orig-window)
        (restore-global! "document" had-document? orig-document)))))

(deftest resolve-ui-font-family-preserves-custom-configured-font-test
  (let [orig-window (aget js/globalThis "window")
        had-window? (has-own? js/globalThis "window")
        orig-document (aget js/globalThis "document")
        had-document? (has-own? js/globalThis "document")
        root #js {}]
    (try
      (aset js/globalThis
            "window"
            #js {:getComputedStyle (fn [_]
                                     (computed-style {"--font-ui-system" "Inter, sans-serif"
                                                      "--font-ui" " \"IBM Plex Sans\", sans-serif "}))})
      (aset js/globalThis "document" #js {:documentElement root})
      (is (= "\"IBM Plex Sans\", sans-serif"
             (fonts/resolve-ui-font-family)))
      (finally
        (restore-global! "window" had-window? orig-window)
        (restore-global! "document" had-document? orig-document)))))

(deftest resolve-ui-font-family-catches-browser-errors-test
  (let [orig-window (aget js/globalThis "window")
        had-window? (has-own? js/globalThis "window")
        orig-document (aget js/globalThis "document")
        had-document? (has-own? js/globalThis "document")
        root #js {}]
    (try
      (aset js/globalThis
            "window"
            #js {:getComputedStyle (fn [_]
                                     (throw (js/Error. "boom")))})
      (aset js/globalThis "document" #js {:documentElement root})
      (is (= fonts/default-ui-system-font-family
             (fonts/resolve-ui-font-family)))
      (finally
        (restore-global! "window" had-window? orig-window)
        (restore-global! "document" had-document? orig-document)))))

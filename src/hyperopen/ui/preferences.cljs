(ns hyperopen.ui.preferences
  (:require [clojure.string :as str]
            [hyperopen.platform :as platform]))

(def ^:private ui-font-local-storage-key
  "hyperopen-ui-font")

(def ^:private supported-ui-fonts
  #{"system" "inter"})

(defn- normalize-ui-font
  [value]
  (let [candidate (-> (or value "system")
                      str
                      str/trim
                      str/lower-case)]
    (if (contains? supported-ui-fonts candidate)
      candidate
      "system")))

(defn restore-ui-font-preference!
  []
  (when (exists? js/document)
    (let [html-el (.-documentElement js/document)
          stored (try
                   (platform/local-storage-get ui-font-local-storage-key)
                   (catch :default _
                     nil))
          normalized (normalize-ui-font stored)]
      (set! (.-uiFont (.-dataset html-el)) normalized))))

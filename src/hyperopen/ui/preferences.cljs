(ns hyperopen.ui.preferences
  (:require [clojure.string :as str]
            [hyperopen.platform :as platform]
            [hyperopen.ui.theme :as theme]))

(def ^:private ui-font-local-storage-key
  "hyperopen-ui-font")

(def ^:private supported-ui-fonts
  #{"system" "inter"})

;; Inter is the default, matching Hyperliquid's own UI (Inter Regular), and is
;; already preloaded by every release page. "system" stays available as a
;; stored preference.
(def ^:private default-ui-font "inter")

(defn- normalize-ui-font
  [value]
  (let [candidate (-> (or value default-ui-font)
                      str
                      str/trim
                      str/lower-case)]
    (if (contains? supported-ui-fonts candidate)
      candidate
      default-ui-font)))

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

(defn restore-ui-theme-preference!
  "Restores the persisted UI theme onto <html data-theme> and mirrors it into
   app state at [:ui :theme] so the settings picker reflects the active theme.
   The pre-paint script in index.html applies the same key before first
   render; this restore is the state-owning pass."
  ([]
   (restore-ui-theme-preference! nil))
  ([store]
   (when (exists? js/document)
     (let [stored (try
                    (platform/local-storage-get theme/local-storage-key)
                    (catch :default _
                      nil))
           normalized (theme/apply-theme-attribute! stored)]
       (when store
         (swap! store assoc-in [:ui :theme] normalized))
       normalized))))

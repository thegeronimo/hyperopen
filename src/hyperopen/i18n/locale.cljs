(ns hyperopen.i18n.locale
  (:require [clojure.string :as str]
            [hyperopen.platform :as platform]))

(def ^:private ui-locale-storage-key
  "ui-locale")

(def ^:private fallback-locale
  "en-US")

(defn locale-storage-key
  []
  ui-locale-storage-key)

(defn- supported-locale?
  [candidate]
  (try
    (let [supported (.supportedLocalesOf js/Intl.NumberFormat #js [candidate])]
      (pos? (.-length supported)))
    (catch :default _
      false)))

(defn normalize-locale
  [value]
  (let [candidate (some-> value
                          str
                          str/trim
                          (str/replace "_" "-"))]
    (when (and (seq candidate)
               (supported-locale? candidate))
      candidate)))

(defn browser-locales
  []
  (let [navigator-object (when (exists? js/globalThis)
                           (.-navigator js/globalThis))
        languages-array (or (some-> navigator-object .-languages)
                            #js [])
        language (some-> navigator-object .-language)]
    (->> (concat (js->clj languages-array)
                 [language])
         (keep #(some-> % str str/trim))
         (remove str/blank?)
         vec)))

(defn load-stored-locale
  []
  (normalize-locale
   (platform/local-storage-get ui-locale-storage-key)))

(defn resolve-browser-locale
  []
  (some normalize-locale (browser-locales)))

(defn resolve-preferred-locale
  []
  (or (load-stored-locale)
      fallback-locale))

(defn coalesce-locale
  [value]
  (or (normalize-locale value)
      (resolve-preferred-locale)))

(defn persist-locale!
  [value]
  (when-let [locale (normalize-locale value)]
    (platform/local-storage-set! ui-locale-storage-key locale)
    locale))

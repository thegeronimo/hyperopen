(ns hyperopen.vol-range.infrastructure.execution-gate
  "Whether Vol Range may place orders in THIS browser (HO-SPEC-014 v2.1
  section 15.2).

  Placement is on whenever the build includes it (`config/VOL_RANGE_EXECUTION`,
  true only in the dev build; every release build leaves it false). A browser
  can opt out with:
       localStorage.setItem('hyperopen.volRange.execution', 'off')
  Removing the key, or any other value, leaves placement on.

  There is no per-ladder cap by default. A browser can set one (USDC of
  total order notional) with:
       localStorage.setItem('hyperopen.volRange.maxLadderUsdc', '250')
  The cap only limits placement; cancellation never depends on this gate."
  (:require [clojure.string :as str]
            [hyperopen.config :as config]
            [hyperopen.domain.trading.precision :as precision]
            [hyperopen.platform :as platform]))

(def arm-key "hyperopen.volRange.execution")
(def cap-key "hyperopen.volRange.maxLadderUsdc")

(defn- read-key [k]
  (try (some-> (platform/local-storage-get k) str str/trim not-empty)
       (catch :default _ nil)))

(defn armed?
  "True unless this browser opted out."
  []
  (not= "off" (some-> (read-key arm-key) str/lower-case)))

(defn enabled?
  "True when the build includes placement and this browser has not opted out."
  []
  (boolean (and (config/vol-range-preview-enabled?) (config/vol-range-execution-built?) (armed?))))

(defn max-ladder-usdc
  "The per-ladder notional cap set in this browser as a canonical decimal
  string, or nil (no cap) when unset, unreadable or not positive."
  []
  (let [v (some-> (read-key cap-key) precision/parse-decimal)]
    (when (and v (precision/pos-dec? v)) (precision/decimal->string v))))

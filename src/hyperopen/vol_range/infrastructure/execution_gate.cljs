(ns hyperopen.vol-range.infrastructure.execution-gate
  "Whether Vol Range may place orders in THIS browser (HO-SPEC-014 v2.1
  section 15.2).

  Two locks, both required:
  1. The build includes placement (`config/VOL_RANGE_EXECUTION`, true only
     in the dev build; every release build leaves it false).
  2. The user armed it in this browser:
       localStorage.setItem('hyperopen.volRange.execution', 'armed')
     Removing the key, or any other value, disarms it.

  While armed, every ladder's total order notional is capped (USDC), default
  `default-max-ladder-usdc`, overridable with:
       localStorage.setItem('hyperopen.volRange.maxLadderUsdc', '250')
  The cap only limits placement; cancellation never depends on this gate."
  (:require [clojure.string :as str]
            [hyperopen.config :as config]
            [hyperopen.domain.trading.precision :as precision]
            [hyperopen.platform :as platform]))

(def arm-key "hyperopen.volRange.execution")
(def cap-key "hyperopen.volRange.maxLadderUsdc")
(def default-max-ladder-usdc "150")

(defn- read-key [k]
  (try (some-> (platform/local-storage-get k) str str/trim not-empty)
       (catch :default _ nil)))

(defn armed? [] (= "armed" (read-key arm-key)))

(defn enabled?
  "True only when the build includes placement AND this browser armed it."
  []
  (boolean (and (config/vol-range-preview-enabled?) (config/vol-range-execution-built?) (armed?))))

(defn max-ladder-usdc
  "The per-ladder notional cap as a canonical decimal string. An unreadable
  or non-positive override falls back to the default."
  []
  (let [v (some-> (read-key cap-key) precision/parse-decimal)]
    (if (and v (precision/pos-dec? v)) (precision/decimal->string v) default-max-ladder-usdc)))

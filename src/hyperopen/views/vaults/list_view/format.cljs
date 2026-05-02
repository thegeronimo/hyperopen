(ns hyperopen.views.vaults.list-view.format
  (:require [hyperopen.utils.formatting :as fmt]))

(def focus-ring-classes
  ["focus:outline-none"
   "focus:ring-2"
   "focus:ring-[#66e3c5]/45"
   "focus:ring-offset-1"
   "focus:ring-offset-base-100"
   "focus-visible:outline-none"
   "focus-visible:ring-2"
   "focus-visible:ring-[#66e3c5]/45"
   "focus-visible:ring-offset-1"
   "focus-visible:ring-offset-base-100"])

(def focus-visible-only-ring-classes
  ["focus-visible:outline-none"
   "focus-visible:ring-2"
   "focus-visible:ring-[#66e3c5]/45"
   "focus-visible:ring-offset-1"
   "focus-visible:ring-offset-base-100"])

(defn format-total-currency
  [value]
  (or (fmt/format-large-currency (if (number? value) value 0))
      "$0"))

(defn format-currency
  [value]
  (or (fmt/format-currency (if (number? value) value 0))
      "$0.00"))

(defn format-percent
  [value]
  (let [n (if (number? value) value 0)]
    (str (.toFixed n 2) "%")))

(defn percent-text-class
  [value]
  (if (neg? (if (number? value) value 0))
    ["text-[#ff6b8a]"]
    ["text-[#36e1d3]"]))

(defn format-age
  [days]
  (let [n (if (number? days) (max 0 (js/Math.floor days)) 0)]
    (str n)))

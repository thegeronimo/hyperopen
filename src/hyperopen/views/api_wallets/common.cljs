(ns hyperopen.views.api-wallets.common
  (:require [hyperopen.utils.formatting :as fmt]))

(defn input-label
  [label]
  [:label {:class ["text-xs"
                   "font-semibold"
                   "uppercase"
                   "tracking-[0.08em]"
                   "text-trading-text-secondary"]}
   label])

(defn text-input
  [{:keys [id value placeholder on-input disabled?]}]
  [:input {:id id
           :type "text"
           :value (or value "")
           :placeholder placeholder
           :disabled disabled?
           :class ["h-11"
                   "w-full"
                   "rounded-xl"
                   "border"
                   "border-base-300"
                   "bg-base-100"
                   "px-3"
                   "text-sm"
                   "text-trading-text"
                   "focus:outline-none"
                   "focus:ring-0"
                   "focus:ring-offset-0"
                   "disabled:cursor-not-allowed"
                   "disabled:opacity-60"]
           :on {:input [on-input]}}])

(defn inline-error
  [message]
  (when (seq message)
    [:p {:class ["text-xs" "text-[#f2b8c5]"]}
     message]))
(defn format-valid-until
  [value]
  (if (number? value)
    (or (fmt/format-local-date-time value) "Never")
    "Never"))

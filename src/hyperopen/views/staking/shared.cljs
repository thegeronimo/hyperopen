(ns hyperopen.views.staking.shared
  (:require [hyperopen.utils.formatting :as fmt]))

(defn format-summary-hype
  [value]
  (if (number? value)
    (or (fmt/format-integer value) "0")
    "--"))

(defn format-balance-hype
  [value]
  (if (number? value)
    (str (fmt/format-fixed-number value 8) " HYPE")
    "--"))

(defn format-table-hype
  [value]
  (if (number? value)
    (or (fmt/format-integer value) "0")
    "--"))

(defn format-percent
  [value]
  (if (number? value)
    (str (fmt/format-fixed-number (* value 100) 2) "%")
    "--"))

(def ^:private inactive-jailed-tooltip-copy
  "The validator does not have enough stake to participate in the active validator set.")

(def neutral-input-focus-classes
  ["outline-none"
   "transition-[border-color,box-shadow]"
   "duration-150"
   "hover:border-ho-text-dim"
   "hover:ring-1"
   "hover:ring-ho-text-dim/30"
   "hover:ring-offset-0"
   "focus:outline-none"
   "focus:ring-1"
   "focus:ring-ho-text-muted/40"
   "focus:ring-offset-0"
   "focus:shadow-none"
   "focus:border-ho-text-muted"])

(defn status-pill
  [status]
  (case status
    :active
    [:span {:class ["text-xs" "font-normal" "leading-6" "text-ho-accent-bright"]}
     "Active"]

    :jailed
    [:span {:class ["group" "relative" "inline-flex" "items-center" "leading-6"]}
     [:span {:class ["text-xs" "font-normal" "text-ho-text-secondary" "cursor-help"]
             :tab-index 0
             :data-role "staking-validator-status-inactive"}
      "Inactive"]
     [:span {:class ["pointer-events-none"
                     "absolute"
                     "left-1/2"
                     "-translate-x-1/2"
                     "bottom-full"
                     "mb-2"
                     "z-20"
                     "opacity-0"
                     "transition-opacity"
                     "duration-200"
                     "group-hover:opacity-100"
                     "group-focus-within:opacity-100"]
             :data-role "staking-validator-status-tooltip"}
      [:span {:class ["block"
                      "w-[270px]"
                      "max-w-[calc(100vw-2rem)]"
                      "rounded-md"
                      "bg-[#223038]"
                      "px-2.5"
                      "py-1.5"
                      "text-left"
                      "text-xs"
                      "leading-tight"
                      "text-[#f2f6f8]"
                      "shadow-[0_4px_16px_rgba(0,0,0,0.35)]"]}
       inactive-jailed-tooltip-copy]]]

    [:span {:class ["text-xs" "font-normal" "leading-6" "text-ho-text-secondary"]}
     "Inactive"]))

(defn summary-card
  [label value data-role]
  [:div {:class ["rounded-[10px]"
                 "border"
                 "border-ho-surface"
                 "bg-ho-bg"
                 "px-4"
                 "py-3"
                 "space-y-2"]
         :data-role data-role}
   [:div {:class ["text-sm" "leading-[15px]" "font-normal" "text-ho-text-secondary"]}
    label]
   [:div {:class ["text-[30px]" "sm:text-[34px]" "leading-none" "font-normal" "text-ho-text" "num"]}
    value]])

(defn key-value-row
  ([label value]
   (key-value-row label value nil))
  ([label value data-role]
   [:div (cond-> {:class ["flex" "items-start" "justify-between" "gap-3" "text-xs"]}
           (seq data-role)
           (assoc :data-role data-role))
    [:span {:class ["text-ho-text-secondary" "leading-[15px]"]}
     label]
    [:span {:class ["num" "text-ho-text" "font-normal" "leading-[15px]"]}
     value]]))

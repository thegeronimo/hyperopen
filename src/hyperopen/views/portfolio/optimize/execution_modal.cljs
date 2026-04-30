(ns hyperopen.views.portfolio.optimize.execution-modal
  (:require [clojure.string :as str]
            [hyperopen.views.portfolio.optimize.format :as opt-format]))

(defn- summary-card
  [label value]
  [:div {:class ["rounded-lg" "border" "border-base-300" "bg-base-200/50" "p-3"]}
   [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.18em]"
                "text-trading-muted"]}
    label]
   [:p {:class ["mt-2" "text-lg" "font-semibold" "tabular-nums"]}
    value]])

(defn- labels-by-instrument
  [state]
  (or (get-in state [:portfolio :optimizer :last-successful-run :result :labels-by-instrument])
      {}))

(defn- instrument-label
  [labels-by-instrument instrument-id]
  (let [value (str instrument-id)]
    (if (str/starts-with? value "vault:")
      (or (get labels-by-instrument instrument-id)
          value)
      value)))

(defn- row
  [labels-by-instrument execution-row]
  [:div {:class ["grid"
                 "grid-cols-[minmax(8rem,1.1fr)_repeat(9,minmax(5rem,0.75fr))]"
                 "gap-3"
                 "rounded-lg"
                 "border"
                 "border-base-300"
                 "bg-base-200/40"
                 "p-3"
                 "text-xs"
                 "tabular-nums"]}
   [:span {:class ["font-semibold" "text-trading-text"]}
    (instrument-label labels-by-instrument (:instrument-id execution-row))]
   [:span (opt-format/keyword-label (:status execution-row))]
   [:span (opt-format/keyword-label (:side execution-row))]
   [:span (opt-format/format-usdc (:delta-notional-usd execution-row))]
   [:span (str (or (:quantity execution-row) "N/A"))]
   [:span (opt-format/format-usdc (:price execution-row))]
   [:span (opt-format/keyword-label (:order-type execution-row))]
   [:span (opt-format/keyword-label (get-in execution-row [:cost :source]))]
   [:span (opt-format/format-usdc (get-in execution-row [:cost :estimated-slippage-usd]))]
   [:span (opt-format/keyword-label (:reason execution-row))]])

(defn- execution-row-header
  []
  [:div {:class ["grid"
                 "grid-cols-[minmax(8rem,1.1fr)_repeat(9,minmax(5rem,0.75fr))]"
                 "gap-3"
                 "rounded-lg"
                 "border"
                 "border-base-300"
                 "bg-base-200/60"
                 "p-3"
                 "text-xs"
                 "font-semibold"
                 "text-trading-muted"]}
   [:span "Instrument"]
   [:span "Status"]
   [:span "Side"]
   [:span "Delta"]
   [:span "Size"]
   [:span "Price"]
   [:span "Order Type"]
   [:span "Cost Source"]
   [:span "Slippage"]
   [:span "Reason"]])

(defn- row-error-message
  [execution-row]
  (or (get-in execution-row [:error :message])
      (:reason execution-row)))

(defn- latest-attempt-row
  [labels-by-instrument execution-row]
  [:div {:class ["grid"
                 "grid-cols-[minmax(8rem,1.1fr)_repeat(4,minmax(5rem,0.8fr))]"
                 "gap-3"
                 "rounded-lg"
                 "border"
                 "border-base-300"
                 "bg-base-200/40"
                 "p-3"
                 "text-xs"
                 "tabular-nums"]}
   [:span {:class ["font-semibold" "text-trading-text"]}
    (instrument-label labels-by-instrument (:instrument-id execution-row))]
   [:span (opt-format/keyword-label (:status execution-row))]
   [:span (opt-format/keyword-label (:side execution-row))]
   [:span (opt-format/format-usdc (:delta-notional-usd execution-row))]
   [:span (opt-format/keyword-label (row-error-message execution-row))]])

(defn- latest-attempt-panel
  [labels-by-instrument latest-attempt]
  (when (seq (:rows latest-attempt))
    [:section {:class ["mt-4"
                       "rounded-xl"
                       "border"
                       "border-base-300"
                       "bg-base-200/30"
                       "p-4"]
               :data-role "portfolio-optimizer-execution-latest-attempt"}
     [:div {:class ["flex" "items-start" "justify-between" "gap-3"]}
      [:div
       [:p {:class ["text-[0.65rem]"
                    "font-semibold"
                    "uppercase"
                    "tracking-[0.24em]"
                    "text-trading-muted"]}
        "Latest Attempt"]
       [:p {:class ["mt-2" "text-sm" "text-trading-muted"]}
        "Review the last execution result before retrying the ready rows."]]
      [:p {:class ["rounded-full"
                   "border"
                   "border-base-300"
                   "bg-base-100"
                   "px-3"
                   "py-1"
                   "text-xs"
                   "font-semibold"
                   "text-trading-muted"]}
       (opt-format/keyword-label (:status latest-attempt))]]
     (into
      [:div {:class ["mt-3" "space-y-2"]}]
      (cons
       [:div {:class ["grid"
                      "grid-cols-[minmax(8rem,1.1fr)_repeat(4,minmax(5rem,0.8fr))]"
                      "gap-3"
                      "rounded-lg"
                      "border"
                      "border-base-300"
                      "bg-base-200/60"
                      "p-3"
                      "text-xs"
                      "font-semibold"
                      "text-trading-muted"]}
        [:span "Instrument"]
        [:span "Status"]
        [:span "Side"]
        [:span "Delta"]
        [:span "Recovery Detail"]]
       (map (partial latest-attempt-row labels-by-instrument)
            (:rows latest-attempt))))]))

(defn execution-modal
  [state]
  (let [modal (get-in state [:portfolio :optimizer :execution-modal])
        plan (:plan modal)
        summary (:summary plan)
        labels-by-instrument* (labels-by-instrument state)
        latest-attempt (last (vec (get-in state [:portfolio :optimizer :execution :history])))
        submitting? (boolean (:submitting? modal))
        ready? (pos? (or (:ready-count summary) 0))
        confirm-disabled? (or submitting?
                              (:execution-disabled? plan)
                              (not ready?))
        disabled-message (or (:disabled-message plan)
                             "Order submission wiring is not enabled in this slice.")]
    (when (:open? modal)
      [:div {:class ["fixed" "inset-0" "z-50" "flex" "items-center" "justify-center"
                     "bg-black/60" "p-6"]
             :data-role "portfolio-optimizer-execution-modal"}
       [:section {:class ["w-full" "max-w-5xl" "rounded-2xl" "border" "border-base-300"
                          "bg-base-100" "p-5" "shadow-2xl"]}
        [:div {:class ["flex" "items-start" "justify-between" "gap-4"]}
         [:div
          [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]"
                       "text-trading-muted"]}
           "Execution Preview"]
          [:h2 {:class ["mt-2" "text-xl" "font-semibold" "tracking-tight"]}
           "Confirm & Execute"]
          [:p {:class ["mt-2" "max-w-2xl" "text-sm" "text-trading-muted"]}
           "Supported rows are separated from blocked rows before order submission is enabled."]]
         [:button {:type "button"
                   :class ["rounded-lg" "border" "border-base-300" "px-3" "py-2" "text-xs"
                           "font-semibold" "uppercase" "tracking-[0.16em]" "text-trading-muted"]
                   :data-role "portfolio-optimizer-execution-modal-close"
                   :on {:click [[:actions/close-portfolio-optimizer-execution-modal]]}}
          "Close"]]
        [:div {:class ["mt-4" "grid" "grid-cols-2" "gap-2" "lg:grid-cols-4"]}
         (summary-card "Status" (opt-format/keyword-label (:status plan)))
         (summary-card "Ready" (str (or (:ready-count summary) 0)))
         (summary-card "Blocked" (str (or (:blocked-count summary) 0)))
         (summary-card "Ready Notional" (opt-format/format-usdc (:gross-ready-notional-usd summary)))]
        [:div {:class ["mt-2" "grid" "grid-cols-2" "gap-2" "lg:grid-cols-4"]}
         (summary-card "Fees" (opt-format/format-usdc (:estimated-fees-usd summary)))
         (summary-card "Slippage" (opt-format/format-usdc (:estimated-slippage-usd summary)))
         (summary-card "Margin After"
                       (opt-format/format-usdc (get-in summary [:margin :after-used-usd])))
         (summary-card "Margin Warning"
                       (opt-format/keyword-label (get-in summary [:margin :warning])))]
        (when (:execution-disabled? plan)
          [:p {:class ["mt-4" "rounded-lg" "border" "border-warning/40" "bg-warning/10"
                       "px-3" "py-2" "text-sm" "font-semibold" "text-warning"]}
           disabled-message])
        (when (:error modal)
          [:p {:class ["mt-4" "rounded-lg" "border" "border-error/40" "bg-error/10"
                       "px-3" "py-2" "text-sm" "font-semibold" "text-error"]}
           (:error modal)])
        (into
         [:div {:class ["mt-4" "space-y-2"]}]
         (cons (execution-row-header)
               (map (partial row labels-by-instrument*) (:rows plan))))
        (latest-attempt-panel labels-by-instrument* latest-attempt)
        [:div {:class ["mt-5" "flex" "items-center" "justify-end" "gap-3"]}
         [:button {:type "button"
                   :class ["rounded-lg" "border" "border-base-300" "px-4" "py-2" "text-sm"
                           "font-semibold" "text-trading-muted"]
                   :on {:click [[:actions/close-portfolio-optimizer-execution-modal]]}}
          "Cancel"]
         [:button {:type "button"
                   :class ["rounded-lg" "border" "border-primary/50" "bg-primary/10" "px-4" "py-2"
                           "text-sm" "font-semibold" "text-primary" "disabled:cursor-not-allowed"
                           "disabled:border-base-300" "disabled:bg-base-200/40"
                           "disabled:text-trading-muted"]
                   :data-role "portfolio-optimizer-execution-modal-confirm"
                   :disabled confirm-disabled?
                   :on {:click [[:actions/confirm-portfolio-optimizer-execution]]}}
          (if submitting?
            "Submitting..."
            "Confirm & Execute")]]]])))

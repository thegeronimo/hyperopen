(ns hyperopen.views.portfolio.optimize.execution-modal)

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- format-usdc
  [value]
  (if (finite-number? value)
    (str "$" (.toLocaleString value
                              "en-US"
                              #js {:maximumFractionDigits 2}))
    "N/A"))

(defn- keyword-label
  [value]
  (cond
    (keyword? value) (name value)
    (some? value) (str value)
    :else "N/A"))

(defn- summary-card
  [label value]
  [:div {:class ["rounded-lg" "border" "border-base-300" "bg-base-200/50" "p-3"]}
   [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.18em]"
                "text-trading-muted"]}
    label]
   [:p {:class ["mt-2" "text-lg" "font-semibold" "tabular-nums"]}
    value]])

(defn- row
  [execution-row]
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
    (:instrument-id execution-row)]
   [:span (keyword-label (:status execution-row))]
   [:span (keyword-label (:side execution-row))]
   [:span (format-usdc (:delta-notional-usd execution-row))]
   [:span (keyword-label (:reason execution-row))]])

(defn execution-modal
  [state]
  (let [modal (get-in state [:portfolio :optimizer :execution-modal])
        plan (:plan modal)
        summary (:summary plan)
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
         (summary-card "Status" (keyword-label (:status plan)))
         (summary-card "Ready" (str (or (:ready-count summary) 0)))
         (summary-card "Blocked" (str (or (:blocked-count summary) 0)))
         (summary-card "Ready Notional" (format-usdc (:gross-ready-notional-usd summary)))]
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
           [:span "Reason"]]
          (map row (:rows plan))))
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

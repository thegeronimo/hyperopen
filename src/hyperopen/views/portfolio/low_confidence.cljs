(ns hyperopen.views.portfolio.low-confidence
  (:require [hyperopen.views.ui.performance-metrics-tooltip :as metrics-tooltip]))

(defn low-confidence-metric-title
  [reason]
  (case reason
    :daily-coverage-gate-failed "Estimated from incomplete daily coverage."
    :psr-gate-failed "Estimated from limited daily history."
    :drawdown-reliability-gate-failed "Estimated from sparse drawdown observations."
    :drawdown-unavailable "Estimated from sparse drawdown observations."
    :rolling-window-span-insufficient "Estimated from limited history in this window."
    :benchmark-coverage-gate-failed "Estimated from limited benchmark overlap."
    :benchmark-sparse-intervals "Estimated from sparse portfolio intervals."
    "Low-confidence estimate."))

(def ^:private low-confidence-reason-order
  [:daily-coverage-gate-failed
   :psr-gate-failed
   :drawdown-reliability-gate-failed
   :drawdown-unavailable
   :rolling-window-span-insufficient
   :benchmark-coverage-gate-failed
   :benchmark-sparse-intervals])

(defn ordered-low-confidence-reasons
  [reasons]
  (let [reason-set (disj (set reasons) nil)
        known-reasons (filter reason-set low-confidence-reason-order)
        unknown-reasons (sort (remove (set low-confidence-reason-order) reason-set))]
    (vec (concat known-reasons unknown-reasons))))

(defn- low-confidence-banner-summary
  [reasons]
  (let [reason-set (set reasons)]
    (cond
      (empty? reason-set)
      nil

      (every? #{:daily-coverage-gate-failed :psr-gate-failed} reason-set)
      "Some metrics are estimated from incomplete daily data."

      (every? #{:drawdown-reliability-gate-failed :drawdown-unavailable} reason-set)
      "Some metrics are estimated from sparse drawdown data."

      (every? #{:benchmark-coverage-gate-failed :benchmark-sparse-intervals} reason-set)
      "Some metrics are estimated from limited benchmark overlap."

      :else
      "Some metrics are estimated from incomplete or limited data.")))

(defn- low-confidence-info-icon
  [classes]
  [:svg {:viewBox "0 0 20 20"
         :fill "none"
         :stroke "currentColor"
         :class classes
         :aria-hidden true}
   [:circle {:cx "10"
             :cy "10"
             :r "7.25"
             :stroke-width "1.6"}]
   [:path {:d "M10 8.2v4.1"
           :stroke-linecap "round"
           :stroke-width "1.6"}]
   [:circle {:cx "10"
             :cy "5.8"
             :r "0.95"
             :fill "currentColor"
             :stroke "none"}]])

(defn estimated-metrics-banner
  [reasons]
  (when-let [summary (low-confidence-banner-summary reasons)]
    [:div {:class ["group"
                   "relative"
                   "rounded-lg"
                   "border"
                   "px-3"
                   "py-2.5"]
           :style {:border-color "rgba(78, 109, 150, 0.48)"
                   :background "linear-gradient(135deg, rgba(30, 58, 106, 0.52) 0%, rgba(21, 46, 88, 0.46) 100%)"}
           :data-role "portfolio-performance-metrics-estimated-banner"
           :tabindex 0}
     [:div {:class ["flex" "min-w-0" "items-start" "gap-2.5"]}
      (low-confidence-info-icon ["mt-0.5" "h-4" "w-4" "shrink-0" "text-[#7fb5ff]"])
      [:div {:class ["min-w-0" "text-sm" "leading-5" "text-[#d5e4ff]"]}
       summary]]
     (metrics-tooltip/estimated-banner-tooltip reasons
                                               "portfolio-performance-metrics-estimated-banner"
                                               low-confidence-metric-title)]))

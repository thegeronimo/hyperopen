(ns hyperopen.views.notifications-view
  (:require [clojure.string :as str]))

(defn- toast-tone
  [kind]
  (case kind
    :success {:card ["border-[#3e5368]"
                     "bg-[#283648]/95"]
              :icon ["text-[#52f2c8]"
                     "border-[#52f2c8]/70"]
              :headline ["text-[#f6fefd]"]
              :subline ["text-[#9aa9bb]"]}
    :error {:card ["border-error/40"
                   "bg-[#3a2126]/95"]
            :icon ["text-error"
                   "border-error/70"]
            :headline ["text-[#ffe3e8]"]
            :subline ["text-[#f2b8c3]"]}
    {:card ["border-[#4b5a6d]"
            "bg-[#273244]/95"]
     :icon ["text-info"
            "border-info/70"]
     :headline ["text-[#f6fefd]"]
     :subline ["text-[#9aa9bb]"]}))

(defn- normalize-toast-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- toast-display-lines
  [toast]
  (let [headline (normalize-toast-text (or (:headline toast)
                                           (:message toast)))
        subline (normalize-toast-text (:subline toast))
        headline* (or headline subline)]
    (when (seq headline*)
      {:headline headline*
       :subline (when (and (seq subline)
                           (not= subline headline*))
                  subline)})))

(defn- toast-entries
  [state]
  (let [toasts (->> (or (get-in state [:ui :toasts]) [])
                    (filter map?)
                    vec)
        legacy-toast (get-in state [:ui :toast])]
    (if (seq toasts)
      toasts
      (if (map? legacy-toast)
        [legacy-toast]
        []))))

(defn- toast-icon
  [kind]
  (if (= :error kind)
    [:svg {:viewBox "0 0 20 20"
           :fill "none"
           :stroke "currentColor"
           :stroke-width "1.8"
           :class ["h-3.5" "w-3.5"]
           :data-role "global-toast-error-icon"}
     [:path {:stroke-linecap "round"
             :d "M6 6l8 8M14 6l-8 8"}]]
    [:svg {:viewBox "0 0 20 20"
           :fill "none"
           :stroke "currentColor"
           :stroke-width "1.8"
           :class ["h-3.5" "w-3.5"]
           :data-role "global-toast-success-icon"}
     [:path {:stroke-linecap "round"
             :stroke-linejoin "round"
             :d "M5.75 10.5l2.75 2.75 5.75-5.75"}]]))

(defn- dismiss-icon
  []
  [:svg {:viewBox "0 0 20 20"
         :fill "none"
         :stroke "currentColor"
         :stroke-width "1.8"
         :class ["h-4" "w-4"]}
   [:path {:stroke-linecap "round"
           :d "M6 6l8 8M14 6l-8 8"}]])

(defn- toast-card
  [toast]
  (let [kind (or (:kind toast) :info)
        tone (toast-tone kind)
        {:keys [headline subline]} (toast-display-lines toast)
        toast-id (:id toast)]
    (when (seq headline)
      [:div {:class (into ["pointer-events-auto"
                           "flex"
                           "items-center"
                           "gap-3"
                           "rounded-[14px]"
                           "border"
                           "px-4"
                           "py-3"
                           "shadow-[0_14px_32px_rgba(7,12,22,0.42)]"
                           "backdrop-blur-sm"]
                          (:card tone))
             :role "status"
             :aria-live (if (= :error kind) "assertive" "polite")
             :data-role "global-toast"}
       [:span {:class (into ["inline-flex"
                             "h-5"
                             "w-5"
                             "shrink-0"
                             "items-center"
                             "justify-center"
                             "rounded-full"
                             "border"]
                            (:icon tone))}
        (toast-icon kind)]
       [:div {:class ["min-w-0" "flex-1"]}
        [:p {:class (into ["truncate"
                           "text-sm"
                           "font-medium"
                           "leading-5"]
                          (:headline tone))}
         headline]
        (when (seq subline)
          [:p {:class (into ["truncate"
                             "text-sm"
                             "leading-5"]
                            (:subline tone))}
           subline])]
       [:button {:type "button"
                 :class ["inline-flex"
                         "h-7"
                         "w-7"
                         "shrink-0"
                         "items-center"
                         "justify-center"
                         "rounded-md"
                         "text-[#b7c4d3]"
                         "transition-colors"
                         "hover:text-[#e8eef6]"
                         "focus:outline-none"
                         "focus:ring-0"
                         "focus:ring-offset-0"]
                 :aria-label "Dismiss notification"
                 :on {:click [[:actions/dismiss-order-feedback-toast toast-id]]}
                 :data-role "global-toast-dismiss"}
        (dismiss-icon)]])))

(defn notifications-view
  [state]
  (let [toasts (->> (toast-entries state)
                    (keep toast-card)
                    vec)]
    (when (seq toasts)
      (into [:div {:class ["pointer-events-none"
                           "fixed"
                           "right-3"
                           "bottom-16"
                           "z-[280]"
                           "flex"
                           "w-[min(22rem,calc(100vw-1.5rem))]"
                           "flex-col"
                           "gap-2.5"]
                   :data-role "global-toast-region"}]
            toasts))))

(ns hyperopen.views.notifications-view
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.views.trade-confirmation-toasts :as trade-toasts]))

(def ^:private blotter-history-portfolio-path
  "/portfolio")

(def ^:private blotter-history-portfolio-tab
  "order-history")

(defn- toast-kind-name
  [kind]
  (case kind
    :success "success"
    :error "error"
    "info"))

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

(defn- generic-toast-card
  [toast]
  (let [kind (or (:kind toast) :info)
        {:keys [headline subline]} (toast-display-lines toast)
        toast-id (:id toast)
        toast-kind (toast-kind-name kind)]
    (when (seq headline)
      [:div {:class ["global-toast-surface"
                     "pointer-events-auto"
                     "flex"
                     "items-center"
                     "gap-3"
                     "overflow-hidden"
                     "rounded-[16px]"
                     "px-3.5"
                     "py-3"]
             :role "status"
             :aria-live (if (= :error kind) "assertive" "polite")
             :data-toast-kind toast-kind
             :data-role "global-toast"}
       [:span {:class ["global-toast-accent"]
               :aria-hidden true}]
       [:span {:class ["global-toast-icon-shell"
                       "inline-flex"
                       "h-8"
                       "w-8"
                       "shrink-0"
                       "items-center"
                       "justify-center"
                       "rounded-full"]}
        (toast-icon kind)]
       [:div {:class ["min-w-0" "flex-1"]}
        [:p {:class ["truncate"
                     "text-sm"
                     "font-semibold"
                     "leading-5"
                     "tracking-[0.01em]"
                     "text-[#f4fbff]"]}
         headline]
        (when (seq subline)
          [:p {:class ["truncate"
                       "pt-0.5"
                       "text-xs"
                       "leading-4"
                       "text-[#a9bac6]"]}
           subline])]
       [:div {:class ["ml-1" "flex" "shrink-0" "items-center" "self-stretch"]}
        [:button {:type "button"
                  :class ["global-toast-dismiss"
                          "inline-flex"
                          "h-8"
                          "w-8"
                          "items-center"
                          "justify-center"
                          "rounded-full"
                          "focus:outline-none"
                          "focus:ring-0"
                          "focus:ring-offset-0"]
                  :aria-label "Dismiss notification"
                  :on {:click [[:actions/dismiss-order-feedback-toast toast-id]]}
                  :data-role "global-toast-dismiss"}
         (dismiss-icon)]]])))

(defn- query-string
  [pairs]
  (let [params (js/URLSearchParams.)]
    (doseq [[key value] pairs
            :let [value* (some-> value str str/trim)]
            :when (seq value*)]
      (.append params key value*))
    (.toString params)))

(defn- blotter-history-href
  [state]
  (let [spectate-address (when (account-context/spectate-mode-active? state)
                           (account-context/spectate-address state))
        query (query-string (cond-> []
                              spectate-address
                              (conj ["spectate" spectate-address])

                              true
                              (conj ["tab" blotter-history-portfolio-tab])))]
    (if (seq query)
      (str blotter-history-portfolio-path "?" query)
      blotter-history-portfolio-path)))

(defn- trade-toast-options
  [state toast]
  (let [toast-id (:id toast)]
    {:on-dismiss [[:actions/dismiss-order-feedback-toast toast-id]]
     :on-expand [[:actions/expand-order-feedback-toast toast-id]]
     :on-collapse [[:actions/collapse-order-feedback-toast toast-id]]
     :history-href (blotter-history-href state)}))

(defn- trade-confirmation-toast-card
  [state toast]
  (let [fills (vec (or (:fills toast) []))
        options (trade-toast-options state toast)
        single-fill (first fills)]
    (when (seq fills)
      (if (:expanded? toast)
        (trade-toasts/BlotterCard fills options)
        (case (:variant toast)
          :detailed (trade-toasts/DetailedToast single-fill options)
          :stack (trade-toasts/ToastStack fills options)
          :consolidated (trade-toasts/ConsolidatedToast fills options)
          :pill (trade-toasts/PillToast single-fill options)
          (generic-toast-card toast))))))

(defn- toast-card
  [state toast]
  (if (= :trade-confirmation (:toast-surface toast))
    (trade-confirmation-toast-card state toast)
    (generic-toast-card toast)))

(defn notifications-view
  [state]
  (let [toasts (->> (toast-entries state)
                    (keep #(toast-card state %))
                    vec)]
    (when (seq toasts)
      (into [:div {:class ["pointer-events-none"
                           "fixed"
                           "right-3"
                           "bottom-16"
                           "z-[280]"
                           "flex"
                           "w-[min(24rem,calc(100vw-1.5rem))]"
                           "flex-col"
                           "gap-2.5"]
                   :role "status"
                   :aria-live "polite"
                   :data-role "global-toast-region"}]
            toasts))))

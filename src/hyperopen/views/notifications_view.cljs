(ns hyperopen.views.notifications-view)

(defn- toast-tone-classes
  [kind]
  (case kind
    :success ["border-success/40" "bg-success/10" "text-success"]
    :error ["border-error/40" "bg-error/10" "text-error"]
    ["border-info/40" "bg-info/10" "text-info"]))

(defn- toast-icon
  [kind]
  (if (= :success kind)
    [:svg {:viewBox "0 0 20 20"
           :fill "currentColor"
           :class ["h-4" "w-4" "shrink-0"]
           :data-role "global-toast-success-icon"}
     [:path {:fill-rule "evenodd"
             :clip-rule "evenodd"
             :d "M16.707 5.293a1 1 0 010 1.414l-7.75 7.75a1 1 0 01-1.414 0l-3.25-3.25a1 1 0 011.414-1.414l2.543 2.543 7.043-7.043a1 1 0 011.414 0z"}]]
    [:svg {:viewBox "0 0 20 20"
           :fill "currentColor"
           :class ["h-4" "w-4" "shrink-0"]
           :data-role "global-toast-error-icon"}
     [:path {:fill-rule "evenodd"
             :clip-rule "evenodd"
             :d "M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z"}]]))

(defn notifications-view
  [state]
  (let [toast (get-in state [:ui :toast])
        kind (or (:kind toast) :info)
        message (some-> (:message toast) str)]
    (when (seq message)
      [:div {:class ["pointer-events-none"
                     "fixed"
                     "right-3"
                     "bottom-16"
                     "z-[280]"
                     "w-[min(22rem,calc(100vw-1.5rem))]"]
             :data-role "global-toast-region"}
       [:div {:class (into ["pointer-events-auto"
                            "flex"
                            "items-start"
                            "gap-2"
                            "rounded-lg"
                            "border"
                            "px-3"
                            "py-2.5"
                            "text-sm"
                            "font-medium"
                            "shadow-xl"
                            "backdrop-blur-sm"]
                           (toast-tone-classes kind))
              :role "status"
              :aria-live (if (= :error kind) "assertive" "polite")
              :data-role "global-toast"}
        (toast-icon kind)
        [:span {:class ["leading-5"]}
         message]]])))

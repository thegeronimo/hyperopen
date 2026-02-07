(ns hyperopen.views.footer-view)

(defn- websocket-status-ui [status]
  (case status
    :connected {:dot-border "border-success"
                :dot-fill "bg-success"
                :text-class "text-success"}
    :connecting {:dot-border "border-warning"
                 :dot-fill "bg-warning"
                 :text-class "text-warning"}
    :reconnecting {:dot-border "border-warning"
                   :dot-fill "bg-warning"
                   :text-class "text-warning"}
    {:dot-border "border-error"
     :dot-fill "bg-error"
     :text-class "text-error"}))

(defn footer-view [state]
  (let [status (get-in state [:websocket :status] :disconnected)
        attempt (get-in state [:websocket :attempt] 0)
        {:keys [dot-border dot-fill text-class]} (websocket-status-ui status)
        status-label (if (and (= status :reconnecting) (pos? attempt))
                       (str "reconnecting (attempt " attempt ")")
                       (name status))]
    [:footer {:class ["sticky" "bottom-0" "z-40" "isolate" "w-full" "shrink-0" "bg-base-200" "border-t" "border-base-300"]}
     [:div {:class ["w-full" "app-shell-gutter" "py-2" "relative"]}
      [:div.flex.justify-between.items-center
       ;; Connection Status
       [:div.flex.items-center.space-x-3
        [:div.flex.items-center.space-x-2
         [:div {:class ["w-3" "h-3" "rounded-full" "border" dot-border]}
          [:div {:class ["w-2" "h-2" "rounded-full" "mx-0.5" "my-0.5" dot-fill]}]]
         [:span {:class [text-class "text-sm" "font-medium"]}
          (str "WebSocket: " status-label)]]
        (when (not= status :connected)
          [:button.btn.btn-xs.btn-outline
           {:on {:click [[:actions/reconnect-websocket]]}}
           "Retry"])]

       ;; Footer Links
       [:div.flex.space-x-6
        [:a.text-base-content.opacity-70.hover:opacity-100.hover:text-primary.transition-colors
         {:href "#"} "Docs"]
        [:a.text-base-content.opacity-70.hover:opacity-100.hover:text-primary.transition-colors
         {:href "#"} "Support"]
        [:a.text-base-content.opacity-70.hover:opacity-100.hover:text-primary.transition-colors
         {:href "#"} "Terms"]
        [:a.text-base-content.opacity-70.hover:opacity-100.hover:text-primary.transition-colors
         {:href "#"} "Privacy Policy"]]]]]))

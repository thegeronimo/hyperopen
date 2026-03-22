(ns hyperopen.views.header.icons)

(defn chevron-down-icon
  [attrs]
  [:svg (merge {:viewBox "0 0 20 20"
                :fill "currentColor"}
               attrs)
   [:path {:fill-rule "evenodd"
           :clip-rule "evenodd"
           :d "M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z"}]])

(defn wallet-copy-icon
  []
  [:svg {:viewBox "0 0 20 20"
         :fill "currentColor"
         :class ["h-4" "w-4" "text-gray-300"]
         :data-role "wallet-copy-icon-idle"}
   [:path {:d "M4 4a2 2 0 012-2h6a2 2 0 012 2v1h-2V4H6v8h1v2H6a2 2 0 01-2-2V4z"}]
   [:path {:d "M8 7a2 2 0 012-2h4a2 2 0 012 2v7a2 2 0 01-2 2h-4a2 2 0 01-2-2V7zm2 0h4v7h-4V7z"}]])

(defn feedback-icon
  [kind attrs]
  (case kind
    :success
    [:svg (merge {:viewBox "0 0 20 20"
                  :fill "currentColor"}
                 attrs)
     [:path {:fill-rule "evenodd"
             :clip-rule "evenodd"
             :d "M16.707 5.293a1 1 0 010 1.414l-7.75 7.75a1 1 0 01-1.414 0l-3.25-3.25a1 1 0 011.414-1.414l2.543 2.543 7.043-7.043a1 1 0 011.414 0z"}]]

    :error
    [:svg (merge {:viewBox "0 0 20 20"
                  :fill "currentColor"}
                 attrs)
     [:path {:fill-rule "evenodd"
             :clip-rule "evenodd"
             :d "M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z"}]]

    nil))

(defn spectate-mode-icon
  []
  [:svg {:viewBox "0 0 256 256"
         :fill "currentColor"
         :class ["h-5" "w-5" "shrink-0"]
         :data-role "spectate-mode-trigger-icon"}
   [:path {:d "M237.22,151.9l0-.1a1.42,1.42,0,0,0-.07-.22,48.46,48.46,0,0,0-2.31-5.3L193.27,51.8a8,8,0,0,0-1.67-2.44,32,32,0,0,0-45.26,0A8,8,0,0,0,144,55V80H112V55a8,8,0,0,0-2.34-5.66,32,32,0,0,0-45.26,0,8,8,0,0,0-1.67,2.44L21.2,146.28a48.46,48.46,0,0,0-2.31,5.3,1.72,1.72,0,0,0-.07.21s0,.08,0,.11a48,48,0,0,0,90.32,32.51,47.49,47.49,0,0,0,2.9-16.59V96h32v71.83a47.49,47.49,0,0,0,2.9,16.59,48,48,0,0,0,90.32-32.51Zm-143.15,27a32,32,0,0,1-60.2-21.71l1.81-4.13A32,32,0,0,1,96,167.88V168h0A32,32,0,0,1,94.07,178.94ZM203,198.07A32,32,0,0,1,160,168h0v-.11a32,32,0,0,1,60.32-14.78l1.81,4.13A32,32,0,0,1,203,198.07Z"}]])

(defn mobile-menu-icon
  []
  [:svg {:viewBox "0 0 20 20"
         :fill "currentColor"
         :class ["h-5" "w-5" "text-white"]}
   [:path {:fill-rule "evenodd"
           :clip-rule "evenodd"
           :d "M3 5a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zM3 10a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zM3 15a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1z"}]])

(defn settings-icon
  []
  [:svg {:viewBox "0 0 20 20"
         :fill "currentColor"
         :class ["h-[18px]" "w-[18px]" "text-white"]}
   [:path {:fill-rule "evenodd"
           :clip-rule "evenodd"
           :d "M11.49 3.17c-.38-1.56-2.6-1.56-2.98 0a1.532 1.532 0 01-2.286.948c-1.372-.836-2.942.734-2.106 2.106.54.886.061 2.042-.947 2.287-1.561.379-1.561 2.6 0 2.978a1.532 1.532 0 01.947 2.287c-.836 1.372.734 2.942 2.106 2.106a1.532 1.532 0 012.287.947c.379 1.561 2.6 1.561 2.978 0a1.533 1.533 0 012.287-.947c1.372.836 2.942-.734 2.106-2.106a1.533 1.533 0 01.947-2.287c1.561-.379 1.561-2.6 0-2.978a1.532 1.532 0 01-.947-2.287c.836-1.372-.734-2.942-2.106-2.106a1.532 1.532 0 01-2.287-.947zM10 13a3 3 0 100-6 3 3 0 000 6z"}]])

(defn close-icon
  [attrs]
  [:svg (merge {:viewBox "0 0 20 20"
                :fill "none"
                :stroke "currentColor"}
               attrs)
   [:path {:stroke-linecap "round"
           :stroke-linejoin "round"
           :stroke-width 1.8
           :d "M5 5l10 10M15 5L5 15"}]])

(defn trading-settings-row-icon
  [kind active?]
  (let [icon-classes (into ["h-5" "w-5" "shrink-0"]
                           (if active?
                             ["text-[#9ba4ac]"]
                             ["text-[#7f8991]"]))]
    (case kind
      :session
      [:svg {:viewBox "0 0 20 20"
             :fill "none"
             :stroke "currentColor"
             :class icon-classes}
       [:circle {:cx "10" :cy "10" :r "6.5" :stroke-width "1.7"}]
       [:path {:d "M10 6.7v3.5l2.4 1.4"
               :stroke-width "1.7"
               :stroke-linecap "round"
               :stroke-linejoin "round"}]]

      :alerts
      [:svg {:viewBox "0 0 20 20"
             :fill "none"
             :stroke "currentColor"
             :class icon-classes}
       [:path {:d "M10 4.4a3 3 0 0 0-3 3v1.1c0 .9-.24 1.8-.7 2.56L5.15 13h9.7l-1.15-1.94A4.94 4.94 0 0 1 13 8.5V7.4a3 3 0 0 0-3-3Z"
               :stroke-width "1.6"
               :stroke-linecap "round"
               :stroke-linejoin "round"}]
       [:path {:d "M8.5 14.55a1.7 1.7 0 0 0 3 0"
               :stroke-width "1.6"
               :stroke-linecap "round"}]]

      :confirm-open-orders
      [:svg {:viewBox "0 0 20 20"
             :fill "none"
             :stroke "currentColor"
             :class icon-classes}
       [:circle {:cx "10" :cy "10" :r "6.7" :stroke-width "1.65"}]
       [:path {:d "M7.3 10.1 9 11.8l3.7-3.8"
               :stroke-width "1.7"
               :stroke-linecap "round"
               :stroke-linejoin "round"}]]

      :confirm-close-position
      [:svg {:viewBox "0 0 20 20"
             :fill "none"
             :stroke "currentColor"
             :class icon-classes}
       [:circle {:cx "10" :cy "10" :r "6.7" :stroke-width "1.65"}]
       [:path {:d "M7.6 7.6 12.4 12.4"
               :stroke-width "1.7"
               :stroke-linecap "round"}]
       [:path {:d "M12.4 7.6 7.6 12.4"
               :stroke-width "1.7"
               :stroke-linecap "round"}]]

      :animate-orderbook
      [:svg {:viewBox "0 0 20 20"
             :fill "none"
             :stroke "currentColor"
             :class icon-classes}
       [:path {:d "M3.75 13.5h3.15l2.05-5.2 2.8 7 2.1-4.05h2.4"
               :stroke-width "1.75"
               :stroke-linecap "round"
               :stroke-linejoin "round"}]
       [:path {:d "M14.9 5.2h1.9v1.9"
               :stroke-width "1.75"
               :stroke-linecap "round"
               :stroke-linejoin "round"}]]

      :fill-markers
      [:svg {:viewBox "0 0 20 20"
             :fill "none"
             :stroke "currentColor"
             :class icon-classes}
       [:path {:d "M10 16.1s4.2-4.15 4.2-7.2A4.2 4.2 0 0 0 5.8 8.9c0 3.05 4.2 7.2 4.2 7.2Z"
               :stroke-width "1.65"
               :stroke-linecap "round"
               :stroke-linejoin "round"}]
       [:circle {:cx "10" :cy "8.8" :r "1.35" :stroke-width "1.6"}]]

      nil)))

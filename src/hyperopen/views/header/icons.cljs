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

(defn gear-line-icon
  [attrs]
  [:svg (merge {:viewBox "0 0 14 14"
                :fill "none"
                :aria-hidden "true"}
               attrs)
   [:circle {:cx "7" :cy "7" :r "2.3"
             :stroke "currentColor" :stroke-width "1.2"}]
   [:path {:d "M7 1 V3 M7 11 V13 M1 7 H3 M11 7 H13 M2.8 2.8 L4.2 4.2 M9.8 9.8 L11.2 11.2 M2.8 11.2 L4.2 9.8 M9.8 4.2 L11.2 2.8"
           :stroke "currentColor"
           :stroke-width "1.2"
           :stroke-linecap "round"}]])

(defn device-icon
  [attrs]
  [:svg (merge {:viewBox "0 0 14 14"
                :fill "none"
                :aria-hidden "true"}
               attrs)
   [:rect {:x "2" :y "2.5" :width "10" :height "7" :rx "1"
           :stroke "currentColor" :stroke-width "1.1"}]
   [:path {:d "M5 12 H9 M4.5 9.5 V12 M9.5 9.5 V12"
           :stroke "currentColor"
           :stroke-width "1.1"
           :stroke-linecap "round"}]])

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
  (let [icon-classes (into ["h-3.5" "w-3.5" "shrink-0"]
                           (if active?
                             ["text-[#2dd4bf]"]
                             ["text-[#2dd4bf]"]))]
    (case kind
      :session
      [:svg {:viewBox "0 0 14 14"
             :fill "none"
             :stroke "currentColor"
             :class icon-classes}
       [:circle {:cx "7" :cy "7" :r "5.3" :stroke-width "1.1"}]
       [:path {:d "M7 4 V7 L9 8.5"
               :stroke-width "1.3"
               :stroke-linecap "round"
               :stroke-linejoin "round"}]]

      :alerts
      [:svg {:viewBox "0 0 14 14"
             :fill "none"
             :stroke "currentColor"
             :class icon-classes}
       [:path {:d "M3.5 10 V6.5 A3.5 3.5 0 0 1 10.5 6.5 V10 L11.5 11 H2.5 Z"
               :stroke-width "1.1"
               :stroke-linejoin "round"}]
       [:path {:d "M5.5 12 A1.5 1.5 0 0 0 8.5 12"
               :stroke-width "1.1"}]]

      :confirm
      [:svg {:viewBox "0 0 14 14"
             :fill "none"
             :stroke "currentColor"
             :class icon-classes}
       [:path {:d "M2 7 L5.5 10.5 L12 3.5"
               :stroke-width "1.4"
               :stroke-linecap "round"
               :stroke-linejoin "round"}]]

      :sound
      [:svg {:viewBox "0 0 14 14"
             :fill "none"
             :stroke "currentColor"
             :class icon-classes}
       [:path {:d "M2.5 5.5 V8.5 H5 L8.5 11 V3 L5 5.5 Z"
               :stroke-width "1.1"
               :stroke-linejoin "round"}]
       [:path {:d "M10.5 5 A3 3 0 0 1 10.5 9"
               :stroke-width "1.1"
               :stroke-linecap "round"}]]

      :book
      [:svg {:viewBox "0 0 14 14"
             :fill "none"
             :stroke "currentColor"
             :class icon-classes}
       [:rect {:x "2" :y "2.5" :width "10" :height "9" :rx "1"
               :stroke-width "1.1"}]
       [:path {:d "M2 5.5 H12 M2 8.5 H12"
               :stroke-width "1.1"}]]

      :marker
      [:svg {:viewBox "0 0 14 14"
             :fill "none"
             :stroke "currentColor"
             :class icon-classes}
       [:path {:d "M7 2 L11.5 7 L7 12 L2.5 7 Z"
               :stroke-width "1.1"
               :stroke-linejoin "round"}]]

      :key
      [:svg {:viewBox "0 0 14 14"
             :fill "none"
             :stroke "currentColor"
             :class icon-classes}
       [:circle {:cx "4" :cy "10" :r "2.3" :stroke-width "1.1"}]
       [:path {:d "M5.6 8.4 L12 2 M9.5 4.5 L11 6 M10.5 3.5 L12 5"
               :stroke-width "1.1"
               :stroke-linecap "round"}]]

      :appearance
      [:svg {:viewBox "0 0 14 14"
             :fill "none"
             :stroke "currentColor"
             :class icon-classes}
       [:path {:d "M7 1.8a5.2 5.2 0 1 0 0 10.4c0.9 0 1.3-0.6 1.3-1.2 0-0.5-0.3-0.8-0.6-1.1-0.3-0.3-0.6-0.6-0.6-1.1 0-0.7 0.6-1.2 1.3-1.2H10a2.2 2.2 0 0 0 2.2-2.2C12.2 3.3 9.9 1.8 7 1.8Z"
               :stroke-width "1.1"
               :stroke-linejoin "round"}]
       [:circle {:cx "4.4" :cy "5.2" :r "0.8" :fill "currentColor" :stroke "none"}]
       [:circle {:cx "7.6" :cy "4.2" :r "0.8" :fill "currentColor" :stroke "none"}]]

      nil)))

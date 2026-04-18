(ns hyperopen.views.footer.links
  (:require [hyperopen.views.footer.build-badge :as build-badge]))

(def footer-link-classes
  ["text-sm" "text-trading-text" "hover:text-primary" "transition-colors"])

(def ^:private footer-utility-link-classes
  ["flex" "items-center" "gap-4"])

(def ^:private footer-text-link-classes
  ["flex" "items-center" "space-x-6"])

(def ^:private social-link-group-classes
  ["flex" "items-center" "gap-2"])

(def ^:private social-link-shell-classes
  ["inline-flex"
   "h-6"
   "w-6"
   "items-center"
   "justify-center"
   "text-trading-text"])

(def ^:private social-link-anchor-classes
  ["inline-flex"
   "h-6"
   "w-6"
   "items-center"
   "justify-center"
   "text-trading-text"
   "transition-colors"
   "hover:text-primary"])

(def ^:private social-link-icon-classes
  ["h-4"
   "w-4"
   "shrink-0"])

(def ^:private github-icon
  {:view-box "0 0 98 96"
   :paths [{:d "M41.4395 69.3848C28.8066 67.8535 19.9062 58.7617 19.9062 46.9902C19.9062 42.2051 21.6289 37.0371 24.5 33.5918C23.2559 30.4336 23.4473 23.7344 24.8828 20.959C28.7109 20.4805 33.8789 22.4902 36.9414 25.2656C40.5781 24.1172 44.4062 23.543 49.0957 23.543C53.7852 23.543 57.6133 24.1172 61.0586 25.1699C64.0254 22.4902 69.2891 20.4805 73.1172 20.959C74.457 23.543 74.6484 30.2422 73.4043 33.4961C76.4668 37.1328 78.0937 42.0137 78.0937 46.9902C78.0937 58.7617 69.1934 67.6621 56.3691 69.2891C59.623 71.3945 61.8242 75.9883 61.8242 81.252L61.8242 91.2051C61.8242 94.0762 64.2168 95.7031 67.0879 94.5547C84.4102 87.9512 98 70.6289 98 49.1914C98 22.1074 75.9883 6.69539e-07 48.9043 4.309e-07C21.8203 1.92261e-07 -1.9479e-07 22.1074 -4.3343e-07 49.1914C-6.20631e-07 70.4375 13.4941 88.0469 31.6777 94.6504C34.2617 95.6074 36.75 93.8848 36.75 91.3008L36.75 83.6445C35.4102 84.2188 33.6875 84.6016 32.1562 84.6016C25.8398 84.6016 22.1074 81.1563 19.4277 74.7441C18.375 72.1602 17.2266 70.6289 15.0254 70.3418C13.877 70.2461 13.4941 69.7676 13.4941 69.1934C13.4941 68.0449 15.4082 67.1836 17.3223 67.1836C20.0977 67.1836 22.4902 68.9063 24.9785 72.4473C26.8926 75.2227 28.9023 76.4668 31.2949 76.4668C33.6875 76.4668 35.2187 75.6055 37.4199 73.4043C39.0469 71.7773 40.291 70.3418 41.4395 69.3848Z"}]})

(def ^:private telegram-icon
  {:view-box "186 300 546 454"
   :paths [{:d "M226.328419,494.722069 C372.088573,431.216685 469.284839,389.350049 517.917216,369.122161 C656.772535,311.36743 685.625481,301.334815 704.431427,301.003532 C708.567621,300.93067 717.815839,301.955743 723.806446,306.816707 C728.864797,310.92121 730.256552,316.46581 730.922551,320.357329 C731.588551,324.248848 732.417879,333.113828 731.758626,340.040666 C724.234007,419.102486 691.675104,610.964674 675.110982,699.515267 C668.10208,736.984342 654.301336,749.547532 640.940618,750.777006 C611.904684,753.448938 589.856115,731.588035 561.733393,713.153237 C517.726886,684.306416 492.866009,666.349181 450.150074,638.200013 C400.78442,605.66878 432.786119,587.789048 460.919462,558.568563 C468.282091,550.921423 596.21508,434.556479 598.691227,424.000355 C599.00091,422.680135 599.288312,417.758981 596.36474,415.160431 C593.441168,412.561881 589.126229,413.450484 586.012448,414.157198 C581.598758,415.158943 511.297793,461.625274 375.109553,553.556189 C355.154858,567.258623 337.080515,573.934908 320.886524,573.585046 C303.033948,573.199351 268.692754,563.490928 243.163606,555.192408 C211.851067,545.013936 186.964484,539.632504 189.131547,522.346309 C190.260287,513.342589 202.659244,504.134509 226.328419,494.722069 Z"}]})

(defn- social-icon
  [data-role label {:keys [view-box paths]}]
  [:span {:class ["inline-flex" "items-center" "justify-center"]
          :data-role data-role
          :role "img"
          :aria-label label}
   (into [:svg {:class social-link-icon-classes
                :viewBox view-box
                :fill "currentColor"
                :stroke "none"
                :aria-hidden true}]
         (map (fn [{:keys [d fill-rule clip-rule]}]
                [:path (cond-> {:d d}
                         fill-rule (assoc :fill-rule fill-rule)
                         clip-rule (assoc :clip-rule clip-rule))])
              paths))])

(defn- render-social-icons
  []
  [:div {:class social-link-group-classes
         :data-role "footer-social-links"}
   [:a {:class social-link-anchor-classes
        :href "https://t.me/hyperopen"
        :target "_blank"
        :rel "noreferrer"
        :aria-label "Telegram"}
    (social-icon "footer-social-telegram" "Telegram" telegram-icon)]
   [:a {:class social-link-anchor-classes
        :href "https://github.com/thegeronimo/hyperopen"
        :target "_blank"
        :rel "noreferrer"
        :aria-label "GitHub"}
    (social-icon "footer-social-github" "GitHub" github-icon)]])

(defn render
  [{:keys [links build now-ms]}]
  [:div {:class footer-utility-link-classes
         :data-role "footer-utility-links"}
   (when (seq links)
     [:<>
      [:div {:class footer-text-link-classes
             :data-role "footer-text-links"}
       (for [{:keys [label href]} links]
         ^{:key label}
         [:a {:class footer-link-classes
              :href href}
          label])]
      [:span {:class ["h-3" "w-px" "bg-base-content/15"]
              :data-role "footer-links-divider"
              :aria-hidden true}]])
   (build-badge/render {:build build
                        :now-ms now-ms})
   (render-social-icons)])

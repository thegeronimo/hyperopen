(ns hyperopen.views.footer.links
  (:require ["lucide/dist/esm/icons/github.js" :default lucide-github-node]))

(def footer-link-classes
  ["text-sm" "text-trading-text" "hover:text-primary" "transition-colors"])

(def ^:private social-placeholder-icon-classes
  ["h-4"
   "w-4"
   "shrink-0"
   "text-trading-text"])

(def ^:private social-placeholder-image-classes
  ["block"
   "h-4"
   "w-4"
   "shrink-0"])

(def ^:private social-placeholder-src
  "/telegram_logo.svg")

(defn- lucide-node->hiccup
  [node]
  (let [tag-name (aget node 0)
        attrs (js->clj (aget node 1) :keywordize-keys true)]
    [(keyword tag-name) attrs]))

(defn- social-icon
  [label lucide-node]
  [:span {:class ["inline-flex" "items-center" "justify-center"]
          :role "img"
          :aria-label (str label " placeholder")}
   (into [:svg {:class social-placeholder-icon-classes
                :viewBox "0 0 24 24"
                :fill "none"
                :stroke "currentColor"
                :stroke-width 2
                :stroke-linecap "round"
                :stroke-linejoin "round"
                :aria-hidden true}]
         (map lucide-node->hiccup
              (array-seq lucide-node)))])

(defn- social-image-icon
  [label src]
  [:span {:class ["inline-flex" "items-center" "justify-center"]
          :role "img"
          :aria-label label}
   [:img {:class social-placeholder-image-classes
          :src src
          :alt ""
          :aria-hidden true}]])

(defn render-social-placeholders
  []
  [:div {:class ["flex" "items-center" "gap-3"]}
   ^{:key :footer-telegram-placeholder}
   (social-image-icon "Telegram" social-placeholder-src)
   ^{:key :footer-github-placeholder}
   (social-icon "GitHub" lucide-github-node)])

(defn render
  [links]
  [:div {:class ["flex" "items-center" "space-x-6"]}
   (for [{:keys [label href]} links]
     ^{:key label}
     [:a {:class footer-link-classes
          :href href}
      label])])

(ns hyperopen.views.asset-selector.icons
  (:require ["lucide/dist/esm/icons/star.js" :default lucide-star-node]
            [hyperopen.system :as app-system]
            [nexus.registry :as nxr]))

(defn lucide-node->hiccup [node]
  (let [tag-name (aget node 0)
        attrs (js->clj (aget node 1) :keywordize-keys true)]
    [(keyword tag-name) attrs]))

(defn favorite-star-icon [favorite?]
  (let [classes (if favorite?
                  ["h-3.5" "w-3.5" "shrink-0" "text-amber-300" "drop-shadow-[0_0_6px_rgba(245,158,11,0.18)]"]
                  ["h-3.5" "w-3.5" "shrink-0" "text-slate-500" "group-hover:text-amber-200"])
        fill-color (if favorite? "currentColor" "none")]
    (into [:svg {:class classes
                 :viewBox "0 0 24 24"
                 :fill fill-color
                 :stroke "currentColor"
                 :stroke-width 1.9
                 :stroke-linecap "round"
                 :stroke-linejoin "round"
                 :aria-hidden true}]
          (map lucide-node->hiccup
               (array-seq lucide-star-node)))))

(defn favorite-toggle-click-handler [market-key]
  (fn [event]
    (.stopPropagation event)
    (nxr/dispatch app-system/store nil [[:actions/toggle-asset-favorite market-key]])))

(defn favorite-button [favorite? market-key]
  [:button {:class ["group"
                    "-ml-0.5"
                    "inline-flex"
                    "h-4"
                    "w-4"
                    "items-center"
                    "justify-center"
                    "rounded-[4px]"
                    "bg-transparent"
                    "p-0"
                    "transition-all"
                    "duration-150"
                    "hover:bg-amber-400/10"
                    "focus:outline-none"
                    "focus:ring-0"
                    "focus:ring-offset-0"
                    "focus-visible:outline-none"
                    "focus-visible:ring-0"
                    "focus-visible:ring-offset-0"]
            :type "button"
            :aria-label (if favorite? "Remove favorite" "Add favorite")
            :aria-pressed (if favorite? "true" "false")
            :data-role "asset-selector-favorite-button"
            :on {:click (favorite-toggle-click-handler market-key)}}
   (favorite-star-icon favorite?)])

(defn mobile-favorite-button [favorite? market-key]
  [:button {:class ["group"
                    "-ml-0.5"
                    "mt-0.5"
                    "inline-flex"
                    "h-5"
                    "w-5"
                    "shrink-0"
                    "items-center"
                    "justify-center"
                    "rounded-[4px]"
                    "bg-transparent"
                    "p-0"
                    "transition-all"
                    "duration-150"
                    "hover:bg-amber-400/10"
                    "focus:outline-none"
                    "focus:ring-0"
                    "focus:ring-offset-0"
                    "focus-visible:outline-none"
                    "focus-visible:ring-0"
                    "focus-visible:ring-offset-0"]
            :type "button"
            :aria-label (if favorite? "Remove favorite" "Add favorite")
            :aria-pressed (if favorite? "true" "false")
            :data-role "asset-selector-favorite-button"
            :on {:click (favorite-toggle-click-handler market-key)}}
   (favorite-star-icon favorite?)])

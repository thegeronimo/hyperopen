(ns hyperopen.views.account-info.tabs.positions.shared
  (:require ["lucide/dist/esm/icons/pencil.js" :default lucide-pencil-node]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.account-info.positions-vm :as positions-vm]
            [hyperopen.views.account-info.shared :as shared]
            [hyperopen.views.account-info.tab-filters :as tab-filters]))

(def positions-direction-filter-options
  tab-filters/positions-direction-filter-options)

(def positions-direction-filter-labels
  tab-filters/positions-direction-filter-labels)

(def positions-direction-filter-key
  tab-filters/positions-direction-filter-key)

(defn display-coin
  [position-data]
  (positions-vm/display-coin position-data))

(defn dex-chip-label
  [position-data]
  (if (and (map? position-data)
           (map? (:position position-data)))
    (positions-vm/dex-chip-label position-data)
    (positions-vm/dex-chip-label {:position {:coin (:coin position-data)}
                                  :dex (:dex position-data)})))

(defn explainable-value-node
  ([value-node explanation]
   (explainable-value-node value-node explanation {}))
  ([value-node explanation {:keys [underlined?]
                            :or {underlined? true}}]
   (if explanation
     [:span {:class (into ["group" "relative" "inline-flex" "items-center"]
                          (when underlined?
                            ["underline" "decoration-dashed" "underline-offset-2"]))}
      value-node
      [:span {:class ["pointer-events-none"
                      "absolute"
                      "left-1/2"
                      "-translate-x-1/2"
                      "top-full"
                      "z-[120]"
                      "mt-2"
                      "w-56"
                      "rounded-md"
                      "bg-gray-800"
                      "px-2.5"
                      "py-1.5"
                      "text-left"
                      "text-xs"
                      "leading-tight"
                      "text-gray-100"
                      "whitespace-normal"
                      "spectate-lg"
                      "opacity-0"
                      "transition-opacity"
                      "duration-200"
                      "group-hover:opacity-100"
                      "group-focus-within:opacity-100"]}
       explanation]]
     value-node)))

(defn format-pnl-inline
  [pnl-num pnl-percent]
  (if (and (number? pnl-num) (number? pnl-percent))
    (let [value-prefix (cond
                         (pos? pnl-num) "+$"
                         (neg? pnl-num) "-$"
                         :else "$")
          pct-prefix (cond
                       (pos? pnl-percent) "+"
                       (neg? pnl-percent) "-"
                       :else "")
          value-text (str value-prefix (shared/format-currency (js/Math.abs pnl-num)))
          pct-text (str "(" pct-prefix (.toFixed (js/Math.abs pnl-percent) 1) "%)")]
      (str value-text " " pct-text))
    "--"))

(defn pnl-share-cell
  "The PNL (ROE %) cell. For your own account the whole cell is the button that
   opens the share card, with a small glyph after the figure -- the same
   affordance Hyperliquid puts on this exact cell. In spectate (read-only) mode
   it stays plain text: offering to post a stranger's position as your own
   would be a misrepresentation."
  [row-vm position-data read-only?]
  (let [figure (format-pnl-inline (:pnl-num row-vm) (:pnl-percent row-vm))
        tone (:pnl-color-class row-vm)]
    (if read-only?
      [:div {:class ["text-left" "font-semibold" "num" tone]} figure]
      [:div {:class ["text-left" "min-w-0" "font-semibold" "num" tone]}
       [:button {:type "button"
                 :data-role "pnl-share-trigger"
                 :title "Share this position"
                 :aria-label (str "Share this position, " figure)
                 :class ["inline-flex" "max-w-full" "items-center" "gap-1"
                         "overflow-hidden" "bg-transparent" "p-0"
                         "font-semibold" "num" tone "whitespace-nowrap"
                         "transition-opacity" "hover:opacity-80"
                         "focus:outline-none" "focus-visible:underline"]
                 :on {:click [[:actions/open-pnl-share-card position-data]]}}
        [:span figure]
        (shared/external-link-icon ["h-3.5" "w-3.5" "shrink-0" "text-ho-accent"]
                                   {:stroke-width 2})]])))

(def ^:private max-liquidation-display-chars 6)

(defn- count-integer-digits
  [num]
  (let [abs-value (js/Math.abs num)]
    (if (< abs-value 1)
      1
      (inc (js/Math.floor (/ (js/Math.log abs-value)
                             (js/Math.log 10)))))))

(defn format-liquidation-price
  [value]
  (if-let [num (shared/parse-optional-num value)]
    (let [integer-digits (count-integer-digits num)
          decimal-digits (if (>= integer-digits max-liquidation-display-chars)
                           0
                           (max 0 (- max-liquidation-display-chars integer-digits 1)))]
      (or (fmt/format-currency-with-digits num 0 decimal-digits)
          "N/A"))
    "N/A"))

(defn- lucide-node->hiccup
  [node]
  (let [tag-name (aget node 0)
        attrs (js->clj (aget node 1) :keywordize-keys true)]
    [(keyword tag-name) attrs]))

(defn- edit-icon
  []
  (into [:svg {:class ["h-4" "w-4" "shrink-0"]
               :viewBox "0 0 24 24"
               :fill "none"
               :stroke "currentColor"
               :stroke-width "1.6"
               :stroke-linecap "round"
               :stroke-linejoin "round"
               :aria-hidden true}]
        (map lucide-node->hiccup
             (array-seq lucide-pencil-node))))

(def ^:private position-detail-edit-button-classes
  ["inline-flex"
   "-ml-0.5"
   "h-6"
   "w-6"
   "items-center"
   "justify-center"
   "shrink-0"
   "bg-transparent"
   "p-0"
   "text-trading-green"
   "transition-colors"
   "hover:text-ho-accent-bright"
   "focus:outline-none"
   "focus:ring-0"
   "focus:ring-offset-0"
   "focus-visible:outline-none"
   "focus-visible:ring-0"
   "focus-visible:ring-offset-0"
   "focus-visible:text-ho-accent-bright"])

(defn position-detail-edit-button
  [aria-label action data-attr]
  [:button {:class position-detail-edit-button-classes
            :type "button"
            :aria-label aria-label
            data-attr "true"
            :on {:click action}}
   (edit-icon)])

(defn mobile-position-detail-edit-button
  [aria-label action data-attr]
  [:button {:type "button"
            :class ["inline-flex"
                    "-ml-0.5"
                    "h-6"
                    "w-6"
                    "items-center"
                    "justify-center"
                    "shrink-0"
                    "bg-transparent"
                    "p-0"
                    "text-trading-green"
                    "transition-colors"
                    "hover:text-ho-accent-bright"
                    "focus:outline-none"
                    "focus:ring-0"
                    "focus:ring-offset-0"
                    "focus-visible:text-ho-accent-bright"]
            :aria-label aria-label
            :on {:click action}
            data-attr "true"}
   (edit-icon)])

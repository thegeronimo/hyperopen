(ns hyperopen.views.active-asset.icon-button
  (:require [clojure.string :as str]
            [nexus.registry :as nxr]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.system :as app-system]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.utils.parse :as parse-utils]
            [hyperopen.views.asset-icon :as asset-icon-view]))

(def ^:private active-asset-chip-classes
  ["px-1.5"
   "py-0.5"
   "text-xs"
   "font-medium"
   "rounded"
   "border"
   "bg-emerald-500/20"
   "text-emerald-300"
   "border-emerald-500/30"])

(defn- non-blank-text [value]
  (let [text (some-> value str str/trim)]
    (when (seq text) text)))

(defn- parse-optional-number [value]
  (let [num (cond
              (number? value) value
              (string? value) (parse-utils/parse-localized-currency-decimal value)
              :else js/NaN)]
    (when (and (number? num) (not (js/isNaN num)))
      num)))

(defn- coin-prefix [coin]
  (let [coin* (non-blank-text coin)]
    (when (and coin* (str/includes? coin* ":"))
      (let [[prefix _suffix] (str/split coin* #":" 2)]
        (non-blank-text prefix)))))

(defn- market-dex-label [market]
  (or (non-blank-text (:dex market))
      (coin-prefix (:coin market))))

(defn- leverage-chip-label [market]
  (when-let [max-leverage (parse-optional-number (:maxLeverage market))]
    (when (pos? max-leverage)
      (let [whole-number? (= max-leverage (js/Math.floor max-leverage))
            leverage-text (if whole-number?
                            (str (js/Math.floor max-leverage))
                            (fmt/safe-to-fixed max-leverage 1))]
        (str leverage-text "x")))))

(defn- base-symbol-segment [value]
  (let [text (some-> value non-blank-text (str/replace #"^.*:" ""))]
    (some-> text
            (str/split #"/|-" 2)
            first
            non-blank-text)))

(defn- symbol-monogram [market symbol coin]
  (let [base-symbol (or (non-blank-text (:base market))
                        (base-symbol-segment symbol)
                        (base-symbol-segment coin)
                        "ASSET")
        upper-symbol (str/upper-case base-symbol)]
    (subs upper-symbol 0 (min 5 (count upper-symbol)))))

(defn- mounted-image-status [node]
  (let [natural-width (some-> node .-naturalWidth)
        complete? (boolean (some-> node .-complete))]
    (cond
      (and (number? natural-width)
           (pos? natural-width))
      :loaded

      (and complete?
           (number? natural-width)
           (zero? natural-width))
      :missing

      :else
      nil)))

(defn- dispatch-icon-status! [market-key status status*]
  (when (and (seq market-key)
             (contains? #{:loaded :missing} status)
             (not= status @status*)
             app-system/store)
    (let [action (case status
                   :loaded [:actions/mark-loaded-asset-icon market-key]
                   :missing [:actions/mark-missing-asset-icon market-key])]
      (reset! status* status)
      (nxr/dispatch app-system/store nil [action]))))

(defn- icon-status-handlers [market-key]
  (when (seq market-key)
    {:load [[:actions/mark-loaded-asset-icon market-key]]
     :error [[:actions/mark-missing-asset-icon market-key]]}))

(defn attach-asset-icon-probe [market-key _icon-src]
  (fn [{:keys [:replicant/life-cycle :replicant/node :replicant/memory :replicant/remember]}]
    (when (contains? #{:replicant.life-cycle/mount
                       :replicant.life-cycle/update}
                     life-cycle)
      (let [status* (or (:status* memory) (atom nil))]
        (when (ifn? remember)
          (remember {:status* status*}))
        (when-let [status (mounted-image-status node)]
          (dispatch-icon-status! market-key status status*))))))

(defn asset-button
  [market dropdown-visible? missing-icons loaded-icons & [{:keys [outcome-hover-glow?]}]]
  (let [coin (:coin market)
        symbol (or (:symbol market) coin)
        dex-label (market-dex-label market)
        leverage-label (leverage-chip-label market)
        market-type (:market-type market)
        market-key (or (:key market) (markets/coin->market-key coin))
        loaded-icon? (contains? loaded-icons market-key)
        missing-icon? (contains? missing-icons market-key)
        icon-src (when-not missing-icon?
                   (asset-icon-view/market-icon-url market))
        show-loaded-icon? (and loaded-icon? (seq icon-src))
        probe-icon? (and (seq icon-src) (not loaded-icon?))
        show-monogram? missing-icon?
        monogram (symbol-monogram market symbol coin)]
    [:button {:type "button"
              :class ["flex"
                      "min-w-0"
                      "items-center"
                      "gap-2"
                      "rounded"
                      "bg-transparent"
                      "pr-2"
                      "py-1"
                      "text-left"
                      "focus:outline-none"
                      "focus:ring-0"
                      "focus:ring-offset-0"
                      (if outcome-hover-glow?
                        "border"
                        "transition-colors")
                      (when outcome-hover-glow?
                        "border-transparent")
                      (when outcome-hover-glow?
                        "transition-[border-color,box-shadow,color]")
                      (when outcome-hover-glow?
                        "duration-150")
                      (when outcome-hover-glow?
                        "group-hover/outcome-name:border-[#2dd4bf]/55")
                      (when outcome-hover-glow?
                        "group-hover/outcome-name:shadow-[0_0_20px_rgba(45,212,191,0.22)]")
                      (when outcome-hover-glow?
                        "group-focus-within/outcome-name:border-[#2dd4bf]/55")
                      (when outcome-hover-glow?
                        "group-focus-within/outcome-name:shadow-[0_0_20px_rgba(45,212,191,0.22)]")]
              :aria-haspopup "dialog"
              :aria-expanded dropdown-visible?
              :on {:click [[:actions/toggle-asset-dropdown :asset-selector]]}}
     [:div {:class ["relative" "h-5" "w-5" "shrink-0" "overflow-hidden" "rounded-full"]}
      (if show-loaded-icon?
        [:img {:class ["block" "h-5" "w-5" "object-contain" "pointer-events-none"]
               :on (icon-status-handlers market-key)
               :src icon-src
               :alt ""
               :aria-hidden true}]
        [:div {:class ["flex"
                       "h-5"
                       "w-5"
                       "items-center"
                       "justify-center"
                       "rounded-full"
                       "border"
                       "border-slate-500/40"
                       "bg-slate-800/80"
                       "px-0.5"
                       "text-[7px]"
                       "font-semibold"
                       "leading-none"
                       "tracking-tight"
                       "text-slate-300/70"
                       "uppercase"]
               :aria-hidden true}
         (when show-monogram?
           monogram)])
      (when probe-icon?
        [:div {:class ["absolute"
                       "inset-0"
                       "rounded-full"
                       "bg-center"
                       "bg-contain"
                       "bg-no-repeat"
                       "pointer-events-none"]
               :aria-hidden true
               :style {:background-image (str "url('" icon-src "')")}}])
      (when probe-icon?
        [:img {:class ["absolute"
                       "inset-0"
                       "block"
                       "h-5"
                       "w-5"
                       "object-contain"
                       "opacity-0"
                       "pointer-events-none"]
               :on (icon-status-handlers market-key)
               :src icon-src
               :alt ""
               :aria-hidden true
               :replicant/on-render (attach-asset-icon-probe market-key icon-src)}])]
     [:div {:class ["flex" "min-w-0" "items-center" "space-x-2"]}
      [:span {:class ["truncate" "font-medium"]} symbol]
      (when (= market-type :spot)
        [:span {:class active-asset-chip-classes}
         "SPOT"])
      (when dex-label
        [:span {:class active-asset-chip-classes}
         dex-label])
      (when leverage-label
        [:span {:class active-asset-chip-classes}
         leverage-label])]
     [:svg {:fill "none"
            :stroke "currentColor"
            :viewBox "0 0 24 24"
            :class (into ["h-4" "w-4" "shrink-0" "text-gray-400" "transition-transform"]
                         (when dropdown-visible? ["rotate-180"]))}
      [:path {:stroke-linecap "round"
              :stroke-linejoin "round"
              :stroke-width 2
              :d "M19 9l-7 7-7-7"}]]]))

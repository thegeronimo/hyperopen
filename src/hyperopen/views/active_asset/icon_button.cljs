(ns hyperopen.views.active-asset.icon-button
  (:require [clojure.string :as str]
            [nexus.registry :as nxr]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.system :as app-system]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.utils.parse :as parse-utils]
            [hyperopen.views.asset-icon :as asset-icon-view]))

(def ^:private active-asset-chip-classes
  ["asset-chip"
   "px-1.5"
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

(defn attach-asset-icon-probe [market-key icon-src]
  (fn [{:keys [:replicant/life-cycle :replicant/node :replicant/memory :replicant/remember]}]
    (when (contains? #{:replicant.life-cycle/mount
                       :replicant.life-cycle/update}
                     life-cycle)
      ;; The node is reused when the Hyperliquid icon gives way to the fallback,
      ;; so the last reported status is remembered per source, not per node.
      (let [status* (if (= icon-src (:icon-src memory))
                      (or (:status* memory) (atom nil))
                      (atom nil))]
        (when (ifn? remember)
          (remember {:status* status* :icon-src icon-src}))
        (when-let [status (mounted-image-status node)]
          (dispatch-icon-status! market-key status status*))))))

(defn- subtitle-text
  [{:keys [subtitle category-label fallback-label]}]
  (cond
    (and subtitle category-label) (str subtitle " · " category-label)
    subtitle subtitle
    category-label category-label
    :else fallback-label))

(defn- symbol-subtitle
  [symbol-meta]
  ;; The slot renders even while loading so the header never jumps.
  [:span {:class ["block" "max-w-[18rem]" "truncate" "text-xs" "leading-4" "font-normal"
                  "text-trading-text-secondary"]
          :data-role "active-asset-symbol-subtitle"}
   (or (subtitle-text symbol-meta) "\u00a0")])

(defn- description-tooltip-id
  [coin]
  (str "active-asset-description-" (str/replace (str coin) #"[^A-Za-z0-9_-]" "-")))

(defn- position-description-tooltip!
  [event]
  (let [trigger (.-currentTarget event)
        tooltip (some-> trigger
                        (.querySelector "[data-role=\"active-asset-symbol-description\"]"))]
    (when tooltip
      (let [rect (.getBoundingClientRect trigger)
            style (.-style tooltip)]
        (set! (.-left style) (str (max 8 (.-left rect)) "px"))
        (set! (.-top style) (str (+ (.-bottom rect) 8) "px"))))))

(defn- symbol-description-tooltip
  [{:keys [coin description deployer subtitle display-name]}]
  ;; Deployer-written text: rendered as a plain text node, never markup.
  [:div {:id (description-tooltip-id coin)
         :role "tooltip"
         ;; Viewport-fixed: the market strip scrolls horizontally, which clips
         ;; absolutely positioned children. Coordinates come from the trigger.
         :class ["pointer-events-none" "fixed" "left-0" "top-0" "z-[170]" "hidden"
                 "w-[22rem]" "max-w-[calc(100vw-2rem)]" "space-y-2" "rounded-lg" "border"
                 "border-base-300" "bg-base-100" "p-3" "text-left" "shadow-xl"
                 "group-hover/symbol-meta:block" "group-focus-visible/symbol-meta:block"]
         :data-role "active-asset-symbol-description"}
   [:div {:class ["text-sm" "font-semibold" "text-trading-text"]}
    (or subtitle display-name coin)]
   [:p {:class ["text-sm" "font-normal" "leading-5" "text-trading-text-secondary" "whitespace-normal"]}
    description]
   [:div {:class ["border-t" "border-base-300" "pt-2" "text-xs" "font-normal" "text-trading-text-secondary"]}
    (str "Written by the market deployer, " deployer)]])

(defn icon-source
  "Which icon to try: Hyperliquid's, then Entropy's once Hyperliquid's is
  known missing. Returns {:src :status-key}, or {:exhausted? true} when both
  are missing (the monogram shows), or nil when there is nothing to try."
  [market market-key missing-icons]
  (let [fallback-key (asset-icon-view/fallback-icon-status-key market-key)
        fallback-src (asset-icon-view/market-icon-fallback-url market)]
    (cond
      (not (contains? missing-icons market-key))
      (when-let [src (asset-icon-view/market-icon-url market)]
        {:src src :status-key market-key})

      (and (seq fallback-src) (not (contains? missing-icons fallback-key)))
      {:src fallback-src :status-key fallback-key}

      :else
      {:exhausted? true})))

(defn asset-button
  [market dropdown-visible? missing-icons loaded-icons & [{:keys [outcome-hover-glow?]}]]
  (let [coin (:coin market)
        symbol (or (:symbol market) coin)
        dex-label (market-dex-label market)
        leverage-label (leverage-chip-label market)
        market-type (:market-type market)
        market-key (or (:key market) (markets/coin->market-key coin))
        {icon-src :src status-key :status-key exhausted? :exhausted?}
        (icon-source market market-key missing-icons)
        loaded-icon? (contains? loaded-icons status-key)
        show-loaded-icon? (and loaded-icon? (seq icon-src))
        probe-icon? (and (seq icon-src) (not loaded-icon?))
        show-monogram? (true? exhausted?)
        monogram (symbol-monogram market symbol coin)
        symbol-meta (:symbol-meta market)
        description-tooltip? (and (seq (:description symbol-meta))
                                  (not dropdown-visible?))]
    [:button {:type "button"
              :class ["flex"
                      "min-w-0"
                      "items-center"
                      "gap-2"
                      "rounded"
                      "bg-transparent"
                      (if outcome-hover-glow? "px-2" "pr-2")
                      (if outcome-hover-glow? "py-1.5" "py-1")
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
                        "group-hover/outcome-name:border-[#2dd4bf]/35")
                      (when outcome-hover-glow?
                        "group-hover/outcome-name:shadow-[0_0_14px_rgba(45,212,191,0.14)]")
                      (when outcome-hover-glow?
                        "group-focus-within/outcome-name:border-[#2dd4bf]/35")
                      (when outcome-hover-glow?
                        "group-focus-within/outcome-name:shadow-[0_0_14px_rgba(45,212,191,0.14)]")
                      (when description-tooltip? "relative")
                      (when description-tooltip? "group/symbol-meta")]
              :aria-describedby (when description-tooltip?
                                  (description-tooltip-id coin))
              :aria-haspopup "dialog"
              :aria-expanded dropdown-visible?
              :on (cond-> {:click [[:actions/toggle-asset-dropdown :asset-selector]]}
                    description-tooltip? (assoc :mouseenter position-description-tooltip!
                                                :focus position-description-tooltip!))}
     [:div {:class ["relative" "h-5" "w-5" "shrink-0" "overflow-hidden" "rounded-full"]}
      (if show-loaded-icon?
        [:img {:class ["block" "h-5" "w-5" "object-contain" "pointer-events-none"]
               :on (icon-status-handlers status-key)
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
               :on (icon-status-handlers status-key)
               :src icon-src
               :alt ""
               :aria-hidden true
               :replicant/on-render (attach-asset-icon-probe status-key icon-src)}])]
     [:div {:class ["flex" "min-w-0" "flex-col"]}
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
      (when symbol-meta
        (symbol-subtitle symbol-meta))]
     [:svg {:fill "none"
            :stroke "currentColor"
            :viewBox "0 0 24 24"
            :class (into ["h-4" "w-4" "shrink-0" "text-gray-400" "transition-transform"]
                         (when dropdown-visible? ["rotate-180"]))}
      [:path {:stroke-linecap "round"
              :stroke-linejoin "round"
              :stroke-width 2
              :d "M19 9l-7 7-7-7"}]]
     (when description-tooltip?
       (symbol-description-tooltip symbol-meta))]))

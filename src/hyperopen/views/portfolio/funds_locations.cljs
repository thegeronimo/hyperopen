(ns hyperopen.views.portfolio.funds-locations
  "The Portfolio page's \"Where your funds are\" strip: total value, then a
   card per place funds sit (Perps, Spot, HyperEVM), with a round Transfer
   button between each pair. The model, and what each figure means, is in
   `hyperopen.views.portfolio.funds-locations.model`.

   A unified account pools Perps and Spot into one balance, so it gets one
   \"Trading account\" card and a single Spot <-> HyperEVM button.

   The buttons open Transfer preset to their pair. A button whose move is
   unavailable (read-only views, and for HyperEVM a subaccount or no wallet)
   stays focusable with `aria-disabled` and points `aria-describedby` at its
   reason, a line of visible text under the strip at every width (a tap on
   a touch screen neither hovers nor, in Safari, focuses the button). From
   `lg` up a tooltip anchored to the button repeats it for pointer and
   keyboard users; it stays open while the pointer moves onto it and Escape
   dismisses it (`hyperopen.views.ui.dismissible-tooltip`)."
  (:require [hyperopen.views.portfolio.funds-locations.model :as model]
            [hyperopen.views.ui.dismissible-tooltip :as dismissible-tooltip]
            [hyperopen.views.ui.focus-return :as focus-return]
            [hyperopen.views.ui.location-chip :as location-chip]))

(def perps-spot-data-role
  model/perps-spot-data-role)

(def spot-evm-data-role
  model/spot-evm-data-role)

(def funds-locations-model
  "See `model/funds-locations-model`."
  model/funds-locations-model)

;; --- view --------------------------------------------------------------------

(def ^:private card-classes
  ["flex" "min-w-0" "flex-col" "gap-1.5" "rounded-xl" "border" "px-4" "py-3"])

(def ^:private place-card-classes
  (into card-classes ["border-ho-border" "bg-ho-surface"]))

(def ^:private card-label-classes
  ["text-xs" "text-trading-text-secondary"])

(def ^:private card-value-classes
  ["num" "truncate" "text-xl" "font-semibold" "leading-tight" "text-trading-text"])

(def ^:private card-detail-classes
  ["num" "text-xs" "leading-4" "text-trading-text-secondary"])

(defn- swap-icon
  []
  [:svg {:viewBox "0 0 24 24"
         :fill "none"
         :stroke "currentColor"
         :stroke-width "2.2"
         :stroke-linecap "round"
         :stroke-linejoin "round"
         :aria-hidden "true"
         ;; Vertical while the cards stack, horizontal beside them.
         :class ["h-3.5" "w-3.5" "rotate-90" "md:rotate-0"]}
   [:path {:d "M7 4 3 8l4 4"}]
   [:path {:d "M3 8h14"}]
   [:path {:d "m17 20 4-4-4-4"}]
   [:path {:d "M21 16H7"}]])

(def ^:private connector-button-classes
  ["flex" "h-8" "w-8" "shrink-0" "items-center" "justify-center" "rounded-full" "border" "p-0"
   "transition-colors" "focus:outline-none" "focus-visible:ring-2" "focus-visible:ring-ho-accent/60"])

(defn- connector-tone-classes
  [{:keys [id disabled?]}]
  (cond
    disabled? ["border-ho-border" "bg-ho-bg" "text-trading-text-secondary" "cursor-not-allowed"]
    (= :spot-evm id) ["border-ho-accent" "bg-ho-accent-soft" "text-ho-accent-bright" "hover:bg-ho-accent-soft-hi"]
    :else ["border-ho-border" "bg-ho-bg" "text-ho-accent" "hover:border-ho-accent/60" "hover:text-ho-accent-bright"]))

(def ^:private tooltip-classes
  ;; Anchored to the button (its `relative` group), not the grid slot,
  ;; which stretches to the card row from `md` up. The top padding is part
  ;; of the tooltip, so the pointer can cross from the button onto it
  ;; without ending the hover; `invisible` (not just transparent) keeps a
  ;; closed tooltip from catching the pointer over the content below.
  ["absolute" "left-1/2" "top-full" "z-[120]" "w-56" "-translate-x-1/2" "pt-1.5"
   "invisible" "opacity-0" "transition-opacity" "duration-150"
   "group-hover/connector:visible" "group-hover/connector:opacity-100"
   "group-focus-within/connector:visible" "group-focus-within/connector:opacity-100"
   ;; Escape (`dismissible-tooltip`); spelled out for Tailwind.
   "group-data-[tooltip-dismissed=true]/connector:!invisible"])

(def ^:private tooltip-bubble-classes
  ["block" "whitespace-normal" "rounded-md" "border" "border-ho-border" "bg-ho-surface-raised" "px-2.5"
   "py-1.5" "text-left" "text-xs" "leading-4" "text-ho-text"])

(defn- connector-node
  [{:keys [id data-role aria-label action disabled? reason reason-id] :as connector*} focus-request]
  ^{:key (str "connector-" (name id))}
  ;; Below `md` the cards stack and the slot has no height: the button sits
  ;; on the seam between the two cards it joins (painted above them) instead
  ;; of taking a row of its own.
  [:div {:class ["relative" "z-10" "flex" "h-0" "items-center" "justify-center" "md:h-auto"]
         :data-role (str data-role "-slot")}
   [:span (merge {:class ["group/connector" "relative" "inline-flex"]}
                 (when disabled? (dismissible-tooltip/group-attrs)))
    [:button (merge {:type "button"
                     :class (into connector-button-classes (connector-tone-classes connector*))
                     :data-role data-role
                     :aria-label aria-label}
                    (if disabled?
                      {:aria-disabled "true"
                       :aria-describedby reason-id}
                      (merge {:on {:click [action]}}
                             (focus-return/data-role-return-focus-props data-role
                                                                        (:data-role focus-request)
                                                                        (:token focus-request)))))
     (swap-icon)]
    ;; The tooltip repeats the reason line under the strip (the description
    ;; the button points at), so assistive tech skips it.
    [:span {:class (if disabled?
                     (into ["hidden" "lg:block"] tooltip-classes)
                     ["hidden"])
            :role "tooltip"
            :aria-hidden "true"
            :data-role (str data-role "-tooltip")}
     [:span {:class tooltip-bubble-classes}
      (if disabled? reason "")]]]])

(defn- total-node
  [{:keys [value-text status-text tone includes-text]}]
  ^{:key "total"}
  [:div {:class (into card-classes ["border-ho-border-accent" "bg-ho-bg-deep" "md:col-span-full" "lg:col-span-1" "lg:mr-4"])
         :data-role "portfolio-funds-total"}
   [:div {:class ["text-xs" "uppercase" "tracking-wider" "text-ho-text-muted"]} "Total value"]
   [:div {:class ["num" "text-2xl" "font-semibold" "leading-tight" "text-trading-text"]
          :data-role "portfolio-funds-total-value"}
    value-text]
   [:div {:class card-detail-classes}
    ;; "Total Equity $X" once known, else why the total is unknown; only a
    ;; failure takes the warning tone.
    [:div {:class (cond-> [] (= :warn tone) (conj "text-ho-warn"))
           :data-role "portfolio-funds-total-status"
           :data-tone (some-> tone name)}
     status-text]
    [:div {:class (cond-> [] (nil? includes-text) (conj "hidden"))
           :data-role "portfolio-funds-total-includes"}
     (or includes-text "")]
    [:div "HyperEVM funds are not margin"]]])

(defn- place-card
  [key* data-role chips label {:keys [value-text detail]}]
  ^{:key key*}
  [:div {:class place-card-classes
         :data-role data-role}
   (into [:div {:class ["flex" "min-w-0" "items-center" "gap-2"]}]
         (conj (vec chips) [:span {:class card-label-classes} label]))
   [:div {:class card-value-classes
          :data-role (str data-role "-value")}
    value-text]
   [:div {:class card-detail-classes
          :data-role (str data-role "-detail")}
    detail]])

(defn- gas-node
  [gas]
  (let [tone (:tone gas)]
    [:span {:class (cond-> ["ml-1" "inline-flex" "items-center" "gap-1"]
                     (nil? gas) (conj "hidden")
                     (= :warn tone) (conj "text-ho-warn"))
            :data-role "portfolio-funds-evm-gas-status"
            :data-gas-tone (some-> tone name)}
     (if gas (str "· " (:text gas)) "")
     [:span {:class (cond-> ["h-1.5" "w-1.5" "rounded-full"]
                      (= :ok tone) (conj "bg-ho-buy")
                      (not= :ok tone) (conj "hidden"))
             :aria-hidden "true"}]]))

(defn- evm-card-node
  [evm-model]
  ^{:key "hyperevm"}
  [:div {:class place-card-classes
         :data-role "portfolio-funds-card-evm"}
   [:div {:class ["flex" "min-w-0" "items-center" "justify-between" "gap-2"]}
    [:div {:class ["flex" "min-w-0" "items-center" "gap-2"]}
     (location-chip/location-chip :hyperevm)
     [:span {:class card-label-classes} "HyperEVM wallet"]]
    ;; Dropped where three cards share a tablet row and it would wrap.
    [:span {:class ["shrink-0" "text-xs" "text-ho-text-muted" "md:hidden" "lg:inline"]} "chain 999"]]
   [:div {:class card-value-classes
          :data-role "portfolio-funds-card-evm-value"}
    (:value-text evm-model)]
   [:div {:class card-detail-classes
          :data-role "portfolio-funds-card-evm-detail"}
    (:detail evm-model)
    (gas-node (:gas evm-model))]])

(def ^:private classic-grid-classes
  ["md:grid-cols-[minmax(0,1fr)_44px_minmax(0,1fr)_44px_minmax(0,1fr)]"
   "lg:grid-cols-[240px_minmax(0,1fr)_44px_minmax(0,1fr)_44px_minmax(0,1fr)]"
   "xl:grid-cols-[300px_minmax(0,1fr)_44px_minmax(0,1fr)_44px_minmax(0,1fr)]"])

(def ^:private unified-grid-classes
  ["md:grid-cols-[minmax(0,1fr)_44px_minmax(0,1fr)]"
   "lg:grid-cols-[240px_minmax(0,1fr)_44px_minmax(0,1fr)]"
   "xl:grid-cols-[300px_minmax(0,1fr)_44px_minmax(0,1fr)]"])

(defn- reasons-node
  "Each disabled button's reason, as the visible line it points at, at
   every width: a touch screen at desktop width never shows the tooltip."
  [reasons]
  (into [:div {:class (cond-> ["mt-2" "space-y-1" "text-xs" "leading-4" "text-ho-text-muted"]
                        (empty? reasons) (conj "hidden"))
               :data-role "portfolio-funds-reasons"}]
        (for [{:keys [id text]} reasons]
          ^{:key id}
          [:p {:id id :data-role id} text])))

(defn funds-locations-strip
  "The strip for `model` (`funds-locations-model`). The section is always
   rendered; with no account shown it is an empty section hidden by class."
  [{:keys [visible? unified? total perps spot trading hyperevm connectors reasons focus-request]}]
  (let [connector-by-id (into {} (map (juxt :id identity)) connectors)
        connector* #(connector-node (get connector-by-id %) focus-request)]
    (if-not visible?
      [:section {:class ["hidden"]
                 :aria-label "Where your funds are"
                 :data-role "portfolio-funds-strip"}]
      [:section {:aria-label "Where your funds are"
                 :data-role "portfolio-funds-strip"}
       (if unified?
         [:div {:class (into ["grid" "grid-cols-1" "gap-2" "md:gap-y-3" "lg:gap-0"] unified-grid-classes)
                :data-role "portfolio-funds-grid"}
          (total-node total)
          (place-card "trading" "portfolio-funds-card-trading"
                      [(location-chip/location-chip :perps) (location-chip/location-chip :spot)]
                      "Trading account" trading)
          (connector* :spot-evm)
          (evm-card-node hyperevm)]
         [:div {:class (into ["grid" "grid-cols-1" "gap-2" "md:gap-y-3" "lg:gap-0"] classic-grid-classes)
                :data-role "portfolio-funds-grid"}
          (total-node total)
          (place-card "perps" "portfolio-funds-card-perps"
                      [(location-chip/location-chip :perps)] "Trading margin" perps)
          (connector* :perps-spot)
          (place-card "spot" "portfolio-funds-card-spot"
                      [(location-chip/location-chip :spot)] "HyperCore Spot" spot)
          (connector* :spot-evm)
          (evm-card-node hyperevm)])
       (reasons-node reasons)])))

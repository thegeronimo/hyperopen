(ns hyperopen.views.funding-modal.transfer-parts
  "Pieces shared by the Transfer form and its HyperEVM run views: icons,
   buttons, the balance cards, the summary list and the static route
   header. Every color is an ho-* token."
  (:require [clojure.string :as str]
            [hyperopen.platform :as platform]
            [hyperopen.views.ui.location-chip :as location-chip]))

;; --- icons ------------------------------------------------------------------

(defn- icon
  [classes & paths]
  (into [:svg {:viewBox "0 0 24 24"
               :class classes
               :fill "none"
               :stroke "currentColor"
               :stroke-width "2"
               :stroke-linecap "round"
               :stroke-linejoin "round"
               :aria-hidden "true"}]
        paths))

(defn swap-icon
  "Vertical while From and To stack (below sm), horizontal beside them, as
   the Portfolio strip's connectors turn with their cards."
  []
  (icon ["h-3.5" "w-3.5" "rotate-90" "sm:rotate-0"]
        [:path {:d "M7 4 3 8l4 4"}]
        [:path {:d "M3 8h14"}]
        [:path {:d "m17 20 4-4-4-4"}]
        [:path {:d "M21 16H7"}]))

(defn lock-icon
  []
  (icon ["h-3" "w-3" "shrink-0" "text-ho-text-secondary"]
        [:rect {:x "5" :y "11" :width "14" :height "10" :rx "2"}]
        [:path {:d "M8 11V7a4 4 0 0 1 8 0v4"}]))

(defn info-icon
  []
  (icon ["mt-0.5" "h-4" "w-4" "shrink-0" "text-ho-info"]
        [:circle {:cx "12" :cy "12" :r "9"}]
        [:path {:d "M12 11v5M12 8h.01"}]))

(defn warn-icon
  []
  (icon ["mt-0.5" "h-[18px]" "w-[18px]" "shrink-0" "text-ho-warn"]
        [:path {:d "M12 3 2 21h20L12 3z"}]
        [:path {:d "M12 10v5M12 18h.01"}]))

(defn check-icon
  [classes]
  (icon classes [:path {:d "m5 12 5 5 9-10"}]))

;; --- text and buttons ---------------------------------------------------------

(def label-classes
  ["text-xs" "uppercase" "tracking-[0.08em]" "text-ho-text-muted"])

(defn primary-button-classes
  [disabled?]
  (if disabled?
    ["h-[46px]" "w-full" "rounded-lg" "border" "border-ho-border-accent"
     "bg-ho-accent-soft/40" "px-4" "text-sm" "font-semibold" "text-ho-text-dim"
     "cursor-not-allowed"]
    ["h-[46px]" "w-full" "rounded-lg" "border" "border-ho-accent" "bg-ho-accent"
     "px-4" "text-sm" "font-bold" "text-ho-bg-deep" "transition-colors"
     "hover:bg-ho-accent-hi" "focus:outline-none" "focus-visible:ring-2"
     "focus-visible:ring-ho-accent/50"]))

(def secondary-button-classes
  ["h-[46px]" "w-full" "rounded-lg" "border" "border-ho-border" "bg-ho-surface"
   "px-4" "text-sm" "font-semibold" "text-ho-text" "transition-colors"
   "hover:border-ho-accent/60" "focus:outline-none" "focus-visible:ring-2"
   "focus-visible:ring-ho-accent/50"])

(def link-classes
  ["text-ho-accent" "underline-offset-2" "hover:text-ho-accent-bright" "hover:underline"])

(defn explorer-link
  "A link to the explorer, in a new tab. The trailing arrow is decorative
   (a screen reader would read it as \"north east arrow\"), and the new
   tab is said in words for screen readers."
  [url label data-role]
  [:a {:href url
       :target "_blank"
       :rel "noreferrer noopener"
       :class link-classes
       :data-role data-role}
   label
   [:span {:aria-hidden "true"} " ↗"]
   [:span {:class ["sr-only"]} " (opens in a new tab)"]])

(def panel-bottom-padding-var
  "The CSS variable a funding panel sets to its bottom padding. The mobile
   sheet pads by the device's safe-area inset (at least 1rem); the desktop
   panels pad 1rem, the fallback."
  "--funding-panel-pad-bottom")

(def ^:private panel-bottom-padding
  (str "var(" panel-bottom-padding-var ", 1rem)"))

(defn sticky-footer
  "The action area, kept in view at the bottom of a panel that scrolls. It
   reaches over the panel's bottom padding (negative offset and margin, the
   same padding back), whatever that padding is, so nothing scrolled beneath
   it shows around it."
  [& children]
  (into [:div {:class ["sticky" "-mx-4" "space-y-2" "bg-ho-bg-deep" "px-4" "pt-2"]
               :style {:bottom (str "calc(-1 * " panel-bottom-padding ")")
                       :margin-bottom (str "calc(-1 * " panel-bottom-padding ")")
                       :padding-bottom panel-bottom-padding}
               :data-role "funding-transfer-actions"}]
        children))

;; --- focus --------------------------------------------------------------------

(def amount-input-id
  "funding-transfer-amount-input-field")

(def ^:private run-view-ids
  #{:progress :pending :failed :success})

(defn view-root-hook
  "The on-render hook of every Transfer view's root. Replicant reuses the
   root node when one view replaces another, so it remembers the view it
   last showed. When the form replaces a run view (\"Back to edit\", or the
   network \"Try again\"), focus moves to the amount input, so keyboard and
   screen-reader users land in the form they are editing rather than on
   the dialog's close button. A freshly opened modal is left to the dialog
   (nothing is remembered on mount)."
  [view-id]
  (fn [{:replicant/keys [life-cycle node memory remember]}]
    (when-not (= :replicant.life-cycle/unmount life-cycle)
      (when (and (= :form view-id) (contains? run-view-ids memory))
        (platform/set-timeout!
         (fn []
           (when-let [input (and node
                                 (.-isConnected node)
                                 (.querySelector node (str "#" amount-input-id)))]
             (.focus input)))
         0))
      (when (fn? remember)
        (remember view-id)))))

(defn- document-active-element
  []
  (some-> js/globalThis .-document .-activeElement))

(defn- focus-form-target!
  "Focus the form's submit when it is enabled, else its amount input."
  [node]
  (when-let [form (and node (.-isConnected node)
                       (.closest node "[data-role='funding-transfer-form']"))]
    (when-let [target (or (.querySelector form "[data-role='funding-transfer-submit']:not([disabled])")
                          (.querySelector form (str "#" amount-input-id)))]
      (.focus target))))

(defn fix-focus-hook
  "The on-render hook of the blocked card's region. When the card's fix
   (the gas top-up, or the network \"Try again\") goes away while it held
   focus, its button leaves the DOM and focus would fall to the dialog's
   first control, the Close button, where a repeated Enter closes the modal
   and loses the draft. Instead focus moves on purpose to the submit, or the
   amount when the submit is disabled. It waits a tick, after the dialog's
   own microtask focus."
  [fix?]
  (fn [{:replicant/keys [life-cycle node memory remember]}]
    (when (and (= :replicant.life-cycle/update life-cycle)
               (true? memory)
               (not fix?))
      (let [active (document-active-element)]
        (when (or (nil? active) (= active (some-> js/globalThis .-document .-body)))
          (platform/set-timeout! #(focus-form-target! node) 0))))
    (when (and (fn? remember) (not= :replicant.life-cycle/unmount life-cycle))
      (remember (boolean fix?)))))

(defn focus-when-shown
  "An on-render hook that focuses its node when it first shows `view-id`,
   so a screen reader lands on the new state (progress, success, …) instead
   of the dialog's first button. The focus waits a tick, after the dialog's
   own focus handling."
  [view-id]
  (fn [{:replicant/keys [life-cycle node memory remember]}]
    (when (and (not= :replicant.life-cycle/unmount life-cycle)
               (not= view-id memory))
      (platform/set-timeout! (fn []
                               (when (and node (.-isConnected node))
                                 (.focus node)))
                             0))
    (when (and (fn? remember) (not= :replicant.life-cycle/unmount life-cycle))
      (remember view-id))))

;; --- balances -------------------------------------------------------------------

(defn- balance-card
  [{:keys [label before after delta]} tone side]
  (let [shown (or after before)]
    [:div {:class ["flex" "min-w-0" "flex-col" "gap-0.5" "rounded-lg" "bg-ho-surface" "px-3" "py-2.5"]
           :data-role (str "funding-transfer-balance-" (name side))}
     [:span {:class ["text-xs" "text-ho-text-muted"]}
      (if (and after (seq label)) (str label " after") label)]
     [:span {:class ["truncate" "text-base" "font-semibold" "tabular-nums" "text-ho-text"]}
      (or shown "--")]
     [:span {:class (into ["text-xs" "tabular-nums"]
                          (cond
                            (not (seq delta)) ["invisible"]
                            (= :out tone) ["text-ho-sell"]
                            :else ["text-ho-buy"]))
             :aria-hidden (when-not (seq delta) "true")}
      (or delta "0")]]))

(defn balance-cards
  "Before/after cards for both sides of the move. The delta line always
   takes its space, so typing an amount never shifts the form."
  [{:keys [from to]}]
  [:div {:class ["grid" "grid-cols-2" "gap-2"]
         :data-role "funding-transfer-balances"}
   (balance-card from :out :from)
   (balance-card to :in :to)])

;; --- summary --------------------------------------------------------------------

(defn- summary-item
  [label value tone lock?]
  [:div {:class ["flex" "items-start" "justify-between" "gap-4"]}
   [:dt {:class ["shrink-0" "text-ho-text-secondary"]} label]
   [:dd {:class (into ["m-0" "flex" "min-w-0" "items-center" "justify-end" "gap-1.5" "text-right"
                       "tabular-nums"]
                      (if (= :warn tone) ["text-ho-warn"] ["text-ho-text"]))}
    (if lock?
      [:span {:class ["flex" "items-center" "gap-1.5"]} (lock-icon) [:span value]]
      value)]])

(defn summary-list
  "The destination (always the user's own wallet, with a lock) and the
   route's fee, arrival and network rows."
  [destination rows]
  (into [:dl {:class ["m-0" "space-y-2" "text-xs"]
              :data-role "funding-transfer-summary"}
         (summary-item "Destination" (:display destination) :neutral true)]
        (map (fn [{:keys [label value tone]}]
               (summary-item label value tone false)))
        rows))

;; --- route header (run views) ---------------------------------------------------

(def ^:private place-names
  ;; "HyperCore" alone names the whole ledger (the Balances filter's Perps
  ;; and Spot); a card that means Spot only says so.
  {:perps "HyperCore Perps"
   :spot "HyperCore Spot"
   :hyperevm "HyperEVM"})

(defn- route-card
  [location balance label]
  [:div {:class ["flex" "min-w-0" "flex-1" "flex-col" "gap-1" "rounded-lg" "border" "border-ho-border"
                 "bg-ho-surface" "px-3" "py-2.5"]}
   [:span {:class ["sr-only"]} label]
   [:span {:class ["flex" "items-center" "gap-1.5"]}
    (or (location-chip/location-chip location) [:span])
    [:span {:class ["truncate" "text-sm" "font-semibold" "text-ho-text"]}
     (get place-names location "")]]
   [:span {:class ["truncate" "text-xs" "tabular-nums" "text-ho-text-secondary"]}
    (or balance "--")]])

(defn route-header
  "The places of a move in progress, fixed (no choices)."
  [{:keys [route balances]}]
  [:div {:class ["flex" "items-stretch" "gap-2"]
         :data-role "funding-transfer-route"}
   (route-card (:from route) (get-in balances [:from :before]) "From")
   [:span {:class ["grid" "w-6" "shrink-0" "place-items-center" "text-ho-text-dim"]
           :aria-hidden "true"}
    "→"]
   (route-card (:to route) (get-in balances [:to :before]) "To")])

(defn location-label
  [location]
  (get {:perps "Perps" :spot "Spot" :hyperevm "HyperEVM"} location ""))

(defn lower-first
  [text]
  (if (seq text)
    (str (str/lower-case (subs text 0 1)) (subs text 1))
    ""))

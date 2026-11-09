(ns hyperopen.views.asset-selector.outcome-list
  "The market selector's Outcome tab: category chips and one row per event,
   whose answers are buttons that open the ticket on that side."
  (:require [hyperopen.asset-selector.outcome-listing :as listing]
            [hyperopen.asset-selector.settings :as settings]
            [hyperopen.outcomes.domain.quotes :as quotes]
            [hyperopen.system :as app-system]
            [hyperopen.views.asset-selector.icons :as icons]
            [nexus.registry :as nxr]))

(defn outcome-tab?
  [active-tab]
  (contains? settings/outcome-tab-set active-tab))

(defn- chip-tab
  "Legacy period tabs light up the Crypto chip."
  [active-tab]
  (if (contains? settings/legacy-outcome-tabs active-tab) :outcome-crypto active-tab))

(defn- category-filter
  [active-tab]
  (some (fn [{:keys [tab category]}]
          (when (= tab (chip-tab active-tab)) category))
        listing/category-chips))

(defn list-model
  "Sections, chip counts and keyboard order for `assets` (outcome markets
   already narrowed by search and favorites)."
  [assets active-tab now-ms]
  (let [category (category-filter active-tab)
        shown (if category
                (filterv #(= category (listing/category %)) assets)
                (vec assets))
        sections (listing/sections shown now-ms)]
    {:sections sections
     :counts (listing/chip-counts assets)
     :ordered-keys (listing/ordered-keys sections)}))

(defn category-chip-row
  [active-tab counts]
  (into [:div {:class ["mb-3" "flex" "flex-wrap" "items-center" "gap-1.5"]
               :role "tablist"
               :aria-label "Outcome categories"
               :data-role "outcome-category-chips"}]
        (map (fn [{:keys [tab label]}]
               (let [active? (= tab (chip-tab active-tab))]
                 [:button {:type "button"
                           :role "tab"
                           :aria-selected (boolean active?)
                           :class (into ["inline-flex" "items-center" "gap-1.5" "rounded-full" "border" "px-2.5"
                                         "py-1" "text-xs" "font-medium" "transition-colors"
                                         "focus:outline-none" "focus:ring-0" "focus:ring-offset-0"]
                                        (if active?
                                          ["border-ho-border-accent" "bg-ho-accent-soft" "text-ho-text-hi"]
                                          ["border-base-300" "text-ho-text-secondary" "hover:text-ho-text"]))
                           :data-role "outcome-category-chip"
                           :on {:click [[:actions/set-asset-selector-tab tab]]}}
                  label
                  [:span {:class ["num" "text-ho-text-muted"]} (str (get counts tab 0))]])))
        listing/category-chips))

(defn- dispatch!
  [actions]
  (nxr/dispatch app-system/store nil actions))

(defn- select-event-handler
  [market-key]
  (fn [_event]
    (dispatch! [[:actions/select-asset-by-market-key market-key]])))

(defn- select-answer-handler
  [coin]
  (fn [event]
    (.stopPropagation event)
    (dispatch! [[:actions/select-asset coin]])))

(defn- answer-tone
  [shape {:keys [label side-index]}]
  (cond
    (and (= :yes-no shape) (= 0 side-index) (= "Yes" label)) ["text-ho-buy"]
    (and (= :yes-no shape) (= 1 side-index) (= "No" label)) ["text-ho-sell-hi"]
    :else ["text-ho-text-hi"]))

(defn- answer-button
  [shape {:keys [label coin text quote] :as answer} mobile?]
  [:button {:type "button"
            :class (into ["inline-flex" "min-w-0" "max-w-full" "items-center" "gap-1.5" "rounded-md" "border"
                          "border-base-300" "bg-base-200/60" "transition-colors" "hover:border-ho-border-accent"
                          "hover:bg-ho-accent-soft" "focus:outline-none" "focus:ring-0" "focus:ring-offset-0"]
                         (if mobile? ["h-10" "px-3" "text-sm"] ["h-7" "px-2" "text-xs"]))
            :title (str label " · " text)
            :aria-label (str label ", " text ". Open the ticket on this answer")
            :data-role "outcome-answer"
            :on {:click (select-answer-handler coin)}}
   [:span {:class (into ["truncate" "font-medium"] (answer-tone shape answer))} label]
   [:span {:class (into ["num" "shrink-0"]
                        (if (contains? #{:live :last} (:state quote))
                          ["text-ho-text-hi"]
                          ["text-ho-text-muted"]))}
    text]
   (when (= :last (:state quote))
     [:span {:class ["text-ho-text-muted"]} "last"])])

(defn- clock-classes
  [phase]
  (case phase
    :soon ["text-ho-warn"]
    :live ["text-ho-buy"]
    :awaiting ["text-ho-text-muted"]
    ["text-ho-text-secondary"]))

(defn- highlight-scroll!
  "Scroll a row into view the moment the keyboard highlight reaches it, not on
   every live-price re-render."
  [{:replicant/keys [node]}]
  (when node
    (let [highlighted? (= "highlighted" (.getAttribute node "data-row-state"))
          scrolled? (= "1" (.getAttribute node "data-highlight-scrolled"))]
      (cond
        (and highlighted? (not scrolled?))
        (do (.setAttribute node "data-highlight-scrolled" "1")
            (when (fn? (.-scrollIntoView ^js node))
              (.scrollIntoView ^js node #js {:block "nearest"})))

        (and (not highlighted?) scrolled?)
        (.setAttribute node "data-highlight-scrolled" "0")))))

(defn- row-state
  [{:keys [key]} selected-market-key highlighted-market-key]
  (cond
    (= key selected-market-key) "selected"
    (= key highlighted-market-key) "highlighted"
    :else "idle"))

(defn- answers-cell
  [{:keys [shape answers more]} mobile?]
  (into [:div {:class ["flex" "min-w-0" "flex-wrap" "items-center" "gap-1.5"]
               :data-role "outcome-answers"}]
        (concat (map #(answer-button shape % mobile?) answers)
                (when (pos? more)
                  [[:span {:class ["text-xs" "text-ho-text-muted"]
                           :data-role "outcome-more-answers"}
                    (str "+" more " more")]]))))

(defn- meta-line
  [{:keys [meta venue]}]
  [:div {:class ["flex" "min-w-0" "items-center" "gap-1.5" "text-xs" "text-ho-text-secondary"]}
   [:span {:class ["truncate"]} meta]
   [:span {:class ["shrink-0" "rounded" "border" "border-base-300" "px-1" "text-ho-text-muted"]} venue]])

(defn- desktop-row
  [row selected-market-key highlighted-market-key favorites]
  (let [{:keys [key title clock volume]} row]
    [:div {:class ["grid" "cursor-pointer" "items-center" "gap-3" "border-b" "border-base-300/60" "px-2" "py-2"
                   "asset-selector-row-surface"]
           :style {:grid-template-columns "minmax(0,1fr) minmax(0,19rem) 4.5rem 8.5rem"}
           :replicant/key key
           :replicant/on-render highlight-scroll!
           :data-role "outcome-event-row"
           :data-market-key key
           :data-row-state (row-state row selected-market-key highlighted-market-key)
           :on {:click (select-event-handler key)}}
     [:div {:class ["flex" "min-w-0" "items-start" "gap-2"]}
      (icons/favorite-button (contains? favorites key) key)
      [:div {:class ["min-w-0" "space-y-0.5"]}
       (meta-line row)
       [:div {:class ["truncate" "text-sm" "font-medium" "text-ho-text-hi"]} title]]]
     (answers-cell row false)
     [:div {:class ["num" "text-right" "text-xs" "text-ho-text-secondary"]}
      (or (quotes/compact-usd-text volume) "—")]
     [:div {:class (into ["truncate" "text-right" "text-xs"] (clock-classes (:phase clock)))
            :title (:absolute clock)}
      (or (:label clock) "—")]]))

(defn- mobile-row
  [row selected-market-key highlighted-market-key favorites]
  (let [{:keys [key title clock volume]} row]
    [:div {:class ["flex" "cursor-pointer" "flex-col" "gap-2" "border-b" "border-base-300/70" "px-4" "py-3"
                   "asset-selector-row-surface"]
           :replicant/key key
           :data-role "outcome-event-row"
           :data-market-key key
           :data-row-state (row-state row selected-market-key highlighted-market-key)
           :on {:click (select-event-handler key)}}
     [:div {:class ["flex" "items-start" "gap-2.5"]}
      (icons/mobile-favorite-button (contains? favorites key) key)
      [:div {:class ["min-w-0" "flex-1" "space-y-1"]}
       (meta-line row)
       [:div {:class ["text-base" "font-medium" "leading-snug" "text-ho-text-hi"]} title]
       [:div {:class ["flex" "items-center" "gap-2" "text-xs"]}
        [:span {:class (clock-classes (:phase clock))} (or (:label clock) "")]
        [:span {:class ["num" "text-ho-text-muted"]} (str (or (quotes/compact-usd-text volume) "—") " vol")]]]]
     (answers-cell row true)]))

(defn- section-header
  [{:keys [id title]} mobile?]
  [:div {:class (into ["sticky" "top-0" "z-10" "bg-base-100" "py-1.5" "text-xs" "font-semibold" "uppercase"
                       "tracking-wide" "text-ho-text-muted"]
                      (if mobile? ["px-4"] ["px-2"]))
         :replicant/key (str "section:" id)
         :data-role "outcome-section-header"}
   title])

(defn- empty-state
  [suppress-empty-state?]
  (if suppress-empty-state?
    [:div {:class ["py-8"]}]
    [:div {:class ["py-8" "text-center" "text-ho-text-secondary"]}
     [:div "No events found"]
     [:div {:class ["text-xs"]} "Try another category or search"]]))

(def ^:private min-list-height-px 200)

(defn- fit-to-panel!
  "The trade header's dropdown lives inside the chart panel, which clips at its
   bottom edge; size the list to the room that panel leaves."
  [{:replicant/keys [node]}]
  (when node
    (when-let [panel (.closest ^js node "[data-parity-id='trade-chart-panel']")]
      (let [room (- (.-bottom (.getBoundingClientRect ^js panel))
                    (.-top (.getBoundingClientRect ^js node))
                    12)]
        (set! (.. ^js node -style -maxHeight)
              (str (max min-list-height-px (js/Math.floor room)) "px"))))))

(defn event-list
  [{:keys [sections selected-market-key highlighted-market-key favorites suppress-empty-state? mobile?]}]
  (if (empty? sections)
    (empty-state suppress-empty-state?)
    (into [:div (if mobile?
                  {:class ["flex-1" "overflow-y-auto" "scrollbar-hide"]
                   :data-role "outcome-event-list"}
                  {:class ["overflow-y-auto" "scrollbar-hide"]
                   :style {:max-height "min(560px, 62vh)"}
                   :replicant/on-render fit-to-panel!
                   :data-role "outcome-event-list"})]
          (mapcat (fn [section]
                    (into [(section-header section mobile?)]
                          (map (fn [row]
                                 ((if mobile? mobile-row desktop-row)
                                  row selected-market-key highlighted-market-key favorites)))
                          (:rows section))))
          sections)))

(ns hyperopen.views.asset-selector.outcome-list
  "The market selector's Outcome tab: category chips and one row per event,
   whose answers are buttons that open the ticket on that side."
  (:require [hyperopen.asset-selector.outcome-listing :as listing]
            [hyperopen.asset-selector.settings :as settings]
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
   already narrowed by search and favorites). `opts` are the listing options
   (`listing/listing-opts`: sort, visible keys, catalog, live books) plus
   `:selector` ([:asset-selector] state) for the load status."
  ([assets active-tab now-ms] (list-model assets active-tab now-ms nil))
  ([assets active-tab now-ms opts]
   (let [category (category-filter active-tab)
         shown (if category
                 (filterv #(= category (listing/category %)) assets)
                 (vec assets))
         sections (listing/sections shown now-ms opts)]
     {:sections sections
      :counts (listing/chip-counts assets)
      :ordered-keys (listing/ordered-keys sections)
      :sort (listing/normalize-sort (:sort opts))
      :sort-options listing/sort-options
      :load (listing/load-status (:selector opts) now-ms)})))

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

(defn- answer-title
  [{:keys [label text detail out-of-order?]}]
  (str label " · " text
       (when detail (str " · " detail))
       (when out-of-order? " · Out of order")))

(defn- answer-button
  [shape {:keys [label coin text state muted? out-of-order?] :as answer} mobile?]
  [:button {:type "button"
            :class (into ["inline-flex" "min-w-0" "max-w-full" "items-center" "gap-1.5" "rounded-md" "border"
                          "border-base-300" "bg-base-200/60" "transition-colors" "hover:border-ho-border-accent"
                          "hover:bg-ho-accent-soft" "focus:outline-none" "focus:ring-0" "focus:ring-offset-0"]
                         (if mobile? ["h-10" "px-3" "text-sm"] ["h-6" "px-2" "text-xs"]))
            :title (answer-title answer)
            :aria-label (str (answer-title answer) ". Open the ticket on this answer")
            :data-role "outcome-answer"
            :data-quote-state (some-> state name)
            :on {:click (select-answer-handler coin)}}
   [:span {:class (into ["truncate" "font-medium"] (answer-tone shape answer))} label]
   [:span {:class (into ["num" "shrink-0"]
                        (if muted? ["text-ho-text-muted"] ["text-ho-text-hi"]))}
    text]
   (when out-of-order?
     [:span {:class ["shrink-0" "text-ho-warn"] :aria-hidden true} "!"])])

(defn- row-flags
  [{:keys [flags]}]
  (when (seq flags)
    (into [:div {:class ["flex" "flex-wrap" "items-center" "gap-1.5" "text-xs" "text-ho-warn"]
                 :data-role "outcome-row-flags"}]
          (map (fn [{:keys [kind text]}]
                 [:span {:data-role "outcome-row-flag" :data-flag (name kind)} text]))
          flags)))

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
  [{:keys [shape answers more] :as row} mobile?]
  (into [:div {:class ["flex" "min-w-0" "flex-wrap" "items-center" "gap-1.5"]
               :data-role "outcome-answers"}]
        (concat (map #(answer-button shape % mobile?) answers)
                (when (pos? more)
                  [[:span {:class ["text-xs" "text-ho-text-muted"]
                           :data-role "outcome-more-answers"}
                    (str "+" more " more")]])
                (when-let [flags (row-flags row)]
                  [flags]))))

(defn- meta-line
  [{:keys [meta venue]}]
  [:div {:class ["flex" "min-w-0" "items-center" "gap-1.5" "text-xs" "text-ho-text-secondary"]}
   [:span {:class ["truncate"]} meta]
   [:span {:class ["shrink-0" "rounded" "border" "border-base-300" "px-1" "text-ho-text-muted"]} venue]])

(defn- desktop-answers
  "The answers on one line: they truncate rather than wrap, so every row is two
   lines tall and about eleven rows fit a full-height panel."
  [{:keys [shape answers more] :as row}]
  (into [:div {:class ["flex" "min-w-0" "flex-1" "flex-nowrap" "items-center" "gap-1.5" "overflow-hidden"]
               :data-role "outcome-answers"}]
        (concat (map #(answer-button shape % false) answers)
                (when (pos? more)
                  [[:span {:class ["shrink-0" "text-xs" "text-ho-text-muted"]
                           :data-role "outcome-more-answers"}
                    (str "+" more " more")]])
                (when-let [flags (row-flags row)]
                  [flags]))))

(defn- desktop-row
  [row selected-market-key highlighted-market-key favorites]
  (let [{:keys [key title clock volume-text meta venue]} row]
    [:div {:class ["flex" "cursor-pointer" "flex-col" "gap-1" "border-b" "border-base-300/60" "px-2" "py-1"
                   "asset-selector-row-surface"]
           :replicant/key key
           :replicant/on-render highlight-scroll!
           :title (str meta " · " venue (when-let [label (:label clock)] (str " · " label)))
           :data-role "outcome-event-row"
           :data-market-key key
           :data-row-state (row-state row selected-market-key highlighted-market-key)
           :on {:click (select-event-handler key)}}
     [:div {:class ["flex" "min-w-0" "items-center" "gap-2"]}
      (icons/favorite-button (contains? favorites key) key)
      [:div {:class ["min-w-0" "flex-1" "truncate" "text-sm" "font-medium" "text-ho-text-hi"]} title]
      [:div {:class ["num" "shrink-0" "text-xs" "text-ho-text-secondary"]}
       (or volume-text "—")]]
     [:div {:class ["flex" "min-w-0" "items-center" "gap-2" "pl-6"]}
      (desktop-answers row)
      ;; Many-answer rows need the room for their answers: the clock moves to
      ;; the row's tooltip.
      (when (<= (count (:answers row)) 2)
        [:div {:class (into ["max-w-[9rem]" "shrink-0" "truncate" "text-right" "text-xs"] (clock-classes (:phase clock)))
               :title (:absolute clock)}
         (or (:label clock) "—")])]]))

(defn- mobile-row
  [row selected-market-key highlighted-market-key favorites]
  (let [{:keys [key title clock volume-text]} row]
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
        [:span {:class ["num" "text-ho-text-muted"]} (or volume-text "—")]]]]
     (answers-cell row true)]))

(defn- section-header
  [{:keys [id title]} mobile?]
  [:div {:class (into ["sticky" "top-0" "z-10" "bg-base-100" "py-1.5" "text-xs" "font-semibold" "uppercase"
                       "tracking-wide" "text-ho-text-muted"]
                      (if mobile? ["px-4"] ["px-2"]))
         :replicant/key (str "section:" id)
         :data-role "outcome-section-header"}
   title])

(defn- error-state
  [{:keys [refresh-action]}]
  [:div {:class ["py-8" "text-center" "text-ho-text-secondary"]
         :role "alert"
         :data-role "outcome-load-error"}
   [:div "Couldn’t load outcome markets"]
   [:div {:class ["text-xs"]} "Check the connection and try again."]
   [:button {:type "button"
             :class ["mt-2" "rounded-md" "border" "border-base-300" "px-2.5" "py-1" "text-xs" "text-ho-text-hi"
                     "hover:border-ho-border-accent" "focus:outline-none" "focus:ring-0" "focus:ring-offset-0"]
             :data-role "outcome-load-retry"
             :on {:click [refresh-action]}}
    "Retry"]])

(defn- empty-state
  [suppress-empty-state? load]
  (cond
    (= :error (:status load)) (error-state load)
    suppress-empty-state? [:div {:class ["py-8"]}]
    :else [:div {:class ["py-8" "text-center" "text-ho-text-secondary"]}
           [:div "No events found"]
           [:div {:class ["text-xs"]} "Try another category or search"]]))

(def ^:private panel-width-px 520)

(defn fit-panel-under-strip!
  "Size the Outcome tab's panel: it sits under the trade top bar (the bottom of
   the market strip) and runs to the bottom of the window, about 520 px wide
   (the whole width on a narrow screen), so the chart stays visible beside it."
  [{:replicant/keys [node]}]
  (when node
    (when-let [strip (.closest ^js node "[data-parity-id='market-strip']")]
      (let [rect (.getBoundingClientRect ^js strip)
            top (js/Math.ceil (.-bottom rect))
            viewport-h (.-innerHeight js/window)
            viewport-w (.-innerWidth js/window)
            style (.-style ^js node)]
        (set! (.-top style) (str top "px"))
        (set! (.-left style) (str (max 0 (js/Math.floor (.-left rect))) "px"))
        (set! (.-width style) (str (min panel-width-px viewport-w) "px"))
        (set! (.-height style) (str (max 240 (- viewport-h top)) "px"))))))

;; ---------------------------------------------------------------------------
;; Rows on screen -> live price subscriptions

(def ^:private visible-report-delay-ms 150)

(defn visible-row-keys
  "Event keys of the rows whose box touches the list's visible box, with
   `overscan-px` of margin so prices are live just before a row scrolls in."
  [^js list-node overscan-px]
  (let [box (.getBoundingClientRect list-node)
        top (- (.-top box) overscan-px)
        bottom (+ (.-bottom box) overscan-px)]
    (if (<= (- (.-bottom box) (.-top box)) 0)
      []
      (into []
            (keep (fn [^js row]
                    (let [r (.getBoundingClientRect row)]
                      (when (and (> (.-bottom r) top) (< (.-top r) bottom))
                        (.getAttribute row "data-market-key")))))
            (array-seq (.querySelectorAll list-node "[data-role='outcome-event-row']"))))))

(defn- report-visible!
  [^js node]
  (aset node "__visibleTimer" nil)
  (when (.-isConnected node)
    (let [keys* (visible-row-keys node 80)]
      (when (and (seq keys*) (not= keys* (aget node "__reportedKeys")))
        (aset node "__reportedKeys" keys*)
        (dispatch! [[:actions/set-outcome-visible-event-keys keys*]])))))

(defn- schedule-visible-report!
  "Trailing throttle: at most one measurement per `visible-report-delay-ms`, and
   a dispatch only when the visible set changed."
  [^js node]
  (when (and node (nil? (aget node "__visibleTimer")))
    (aset node "__visibleTimer"
          (js/setTimeout #(report-visible! node) visible-report-delay-ms))))

(defn- list-render-hook
  [{:replicant/keys [node life-cycle]}]
  (cond
    (= :replicant.life-cycle/unmount life-cycle)
    (when node
      (when-let [t (aget node "__visibleTimer")] (js/clearTimeout t))
      (aset node "__visibleTimer" nil))

    node
    (schedule-visible-report! node)))

(defn- scroll-handler
  [^js event]
  (schedule-visible-report! (.-currentTarget event)))

;; ---------------------------------------------------------------------------
;; Sort + freshness

(defn sort-and-freshness-row
  "Sort select, how old the list is, and Refresh."
  [{:keys [sort sort-options load]}]
  [:div {:class ["mb-2" "flex" "flex-wrap" "items-center" "gap-x-3" "gap-y-1" "text-xs"]
         :data-role "outcome-sort-row"}
   [:label {:class ["flex" "items-center" "gap-1.5" "text-ho-text-secondary"]}
    [:span "Sort"]
    (into [:select {:class ["h-7" "rounded-md" "border" "border-base-300" "bg-base-200" "px-2" "py-0" "text-xs"
                            "text-ho-text-hi" "focus:outline-none" "focus:ring-0"]
                    :aria-label "Sort outcome markets"
                    :data-role "outcome-sort-select"
                    :on {:change [[:actions/set-outcome-list-sort [:event.target/value]]]}}]
          (map (fn [{:keys [id label]}]
                 [:option {:value (name id) :selected (= id sort)} label]))
          sort-options)]
   [:span {:class ["flex-1"]}]
   (when-let [text (:updated-text load)]
     [:span {:class ["text-ho-text-muted"] :data-role "outcome-updated-text"} text])
   [:button {:type "button"
             :class ["h-7" "rounded-md" "border" "border-base-300" "px-2" "text-xs" "text-ho-text-hi"
                     "hover:border-ho-border-accent" "focus:outline-none" "focus:ring-0"
                     "disabled:opacity-50"]
             :disabled (= :loading (:status load))
             :data-role "outcome-refresh"
             :on {:click [(:refresh-action load)]}}
    (if (= :loading (:status load)) "Refreshing…" "Refresh")]])

(defn- stale-error-banner
  [{:keys [refresh-action]}]
  [:div {:class ["mb-1" "flex" "items-center" "gap-2" "rounded-md" "border" "border-base-300" "px-2" "py-1"
                 "text-xs" "text-ho-warn"]
         :role "alert"
         :data-role "outcome-refresh-failed"}
   [:span "Couldn’t refresh — showing the last list."]
   [:button {:type "button"
             :class ["text-ho-text-hi" "underline" "focus:outline-none"]
             :on {:click [refresh-action]}}
    "Retry"]])

(defn event-list
  [{:keys [sections selected-market-key highlighted-market-key favorites suppress-empty-state? mobile? panel? load]}]
  ;; Flat sorts return one section even with no rows: no rows is empty.
  ;; The empty or failed state takes the list's place (same role), so it sits
  ;; where the rows would.
  (if (not-any? (comp seq :rows) sections)
    [:div {:class (if (or mobile? panel?) ["min-h-0" "flex-1"] [])
           :data-role "outcome-event-list"}
     (empty-state suppress-empty-state? load)]
    (into [:div (cond
                  mobile?
                  {:class ["flex-1" "overflow-y-auto" "scrollbar-hide"]
                   :replicant/on-render list-render-hook
                   :on {:scroll scroll-handler}
                   :data-role "outcome-event-list"}

                  panel?
                  {:class ["min-h-0" "flex-1" "overflow-y-auto" "scrollbar-hide"]
                   :replicant/on-render list-render-hook
                   :on {:scroll scroll-handler}
                   :data-role "outcome-event-list"}

                  :else
                  {:class ["overflow-y-auto" "scrollbar-hide"]
                   :style {:max-height "min(560px, 62vh)"}
                   :replicant/on-render list-render-hook
                   :on {:scroll scroll-handler}
                   :data-role "outcome-event-list"})]
          (concat
           (when (= :error (:status load))
             [(stale-error-banner load)])
           (mapcat (fn [section]
                     (into [(section-header section mobile?)]
                           (map (fn [row]
                                  ((if mobile? mobile-row desktop-row)
                                   row selected-market-key highlighted-market-key favorites)))
                           (:rows section)))
                   sections)))))

;; ---------------------------------------------------------------------------
;; The All tab's outcome events

(def ^:private all-tab-event-limit 5)

(defn- all-tab-rows
  [{:keys [sections]}]
  (into [] (comp (mapcat :rows) (take all-tab-event-limit)) sections))

(defn all-tab-event-keys
  "Keyboard order of the event rows the All tab shows."
  [model]
  (mapv :key (all-tab-rows model)))

(defn all-tab-events-block
  "Outcome events in the All tab: the first few matching events as the same
   rows the Outcome tab renders, and a link to the whole list."
  [{:keys [sections] :as model} selected-market-key highlighted-market-key favorites mobile?]
  (let [rows (all-tab-rows model)
        total (reduce + 0 (map (comp count :rows) sections))]
    (into [:div {:class ["mb-2" "border-b" "border-base-300"]
                 :data-role "all-tab-outcome-events"}
           [:div {:class (into ["flex" "items-center" "justify-between" "py-1.5" "text-xs" "font-semibold"
                                "uppercase" "tracking-wide" "text-ho-text-muted"]
                               (if mobile? ["px-4"] ["px-2"]))}
            [:span "Outcome markets"]
            (when (> total (count rows))
              [:button {:type "button"
                        :class ["font-medium" "normal-case" "tracking-normal" "text-ho-text-hi" "hover:underline"
                                "focus:outline-none"]
                        :data-role "all-tab-show-outcomes"
                        :on {:click [[:actions/set-asset-selector-tab :outcome]]}}
               (str "Show all " total)])]]
          (map (fn [row]
                 ((if mobile? mobile-row desktop-row)
                  row selected-market-key highlighted-market-key favorites)))
          rows)))

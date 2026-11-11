(ns hyperopen.views.portfolio.optimize.setup-universe-search
  "The Universe panel's search field and its result list.

  Split out of setup_universe so the panel namespace stays under its size cap,
  and because the search is now a component in its own right: facets, a grouped
  scrolling list, match highlighting and honest counts, rather than six
  truncated rows."
  (:require [hyperopen.platform :as platform]))

(def ^:private input-class
  ["w-full" "border" "border-base-300" "bg-base-100/80" "px-2" "py-1.5"
   "font-mono" "text-[0.8125rem]" "font-medium" "outline-none"
   "transition-shadow" "focus:border-warning/70"
   "focus:shadow-[0_0_0_1px_rgba(212,181,88,0.75)]"])

(defn- chip-button
  [{:keys [key label count active?]} role-prefix action]
  [:button {:type "button"
            :class (cond-> ["optimizer-universe-facet-chip"
                            "border" "font-mono" "text-[0.625rem]" "font-semibold"
                            "uppercase" "tracking-[0.04em]" "transition-colors"]
                     active? (conj "optimizer-universe-facet-chip-active"))
            :aria-pressed (if active? "true" "false")
            :data-role (str role-prefix (name key))
            :data-active (when active? "true")
            :on {:click [[action (name key)]]}}
   label
   [:span {:class ["optimizer-universe-facet-chip-count"]} (str count)]])

(defn- facet-rows
  "Type facet, then quote facet. The quote row is suppressed when the matches
  carry only one quote token (or none, as with an all-vault result): a facet with
  a single option filters nothing and only costs a line in a narrow rail."
  [type-chips quote-chips]
  [:div {:class ["mt-2" "flex" "flex-col" "gap-1.5"]}
   (into [:div {:class ["flex" "flex-wrap" "gap-1"]
                :data-role "portfolio-optimizer-universe-search-type-chips"}]
         (map #(chip-button %
                            "portfolio-optimizer-universe-search-type-chip-"
                            :actions/set-portfolio-optimizer-universe-search-type-filter)
              type-chips))
   (when (< 2 (count quote-chips))
     (into [:div {:class ["flex" "flex-wrap" "items-center" "gap-1"]
                  :data-role "portfolio-optimizer-universe-search-quote-chips"}
            [:span {:class ["optimizer-universe-facet-legend" "font-mono"
                            "text-[0.5625rem]" "font-semibold" "uppercase"
                            "tracking-[0.12em]"]}
             "Quote"]]
           (map #(chip-button %
                              "portfolio-optimizer-universe-search-quote-chip-"
                              :actions/set-portfolio-optimizer-universe-search-quote-filter)
                quote-chips)))])

(defn- highlighted
  "Render a highlight triple. All three parts render unconditionally so the row
  still shows its full text when the query matched a field that is not on
  screen.

  Wrapped in a real span rather than a `:<>` fragment: this Replicant version has
  no fragment tag and calls createElement(\"<>\"), which throws at render time —
  invisible to hiccup-data tests, fatal in the browser."
  [{:keys [pre match post]} match-class]
  [:span
   pre
   (when (seq match)
     [:span {:class match-class} match])
   post])

(def ^:private link-icon
  ;; Two chain links: "this is linked to something you already have".
  [:svg {:width "10" :height "10" :viewBox "0 0 24 24" :fill "none"
         :stroke "currentColor" :stroke-width "2.5" :stroke-linecap "round"
         :stroke-linejoin "round" :aria-hidden "true"}
   [:path {:d "M9 17H7A5 5 0 0 1 7 7h2"}]
   [:path {:d "M15 7h2a5 5 0 1 1 0 10h-2"}]
   [:line {:x1 "8" :y1 "12" :x2 "16" :y2 "12"}]])

(defn- same-as-tag
  [market-key duplicate-of-label]
  [:span {:class ["optimizer-universe-same-as-tag" "mt-0.5" "inline-flex"
                  "items-center" "gap-1" "border" "border-info/50" "px-1" "py-px"
                  "font-mono" "text-[0.5625rem]" "font-semibold" "uppercase"
                  "tracking-[0.06em]" "text-info"]
          :data-role (str "portfolio-optimizer-universe-same-as-" market-key)}
   link-icon
   (str "Same as " duplicate-of-label)])

(defn- candidate-row
  [{:keys [market-key market-type active? symbol-segments name-segments
           quote-label adv-label duplicate-name? duplicate-of-label
           duplicate-prompt]}
   idx]
  [:div {:class ["optimizer-universe-candidate-row"
                 "grid" "items-center" "gap-2" "border-b" "border-base-300"
                 "cursor-pointer" "px-2" "py-1.5" "last:border-b-0"
                 "hover:bg-base-200/30"]
         ;; Keyed so Replicant moves rows instead of rebuilding all of them on
         ;; every keystroke and every vault-index tick.
         :replicant/key market-key
         :data-role (str "portfolio-optimizer-universe-candidate-row-" market-key)
         :id (str "portfolio-optimizer-universe-candidate-" idx)
         :role "option"
         :aria-selected (if active? "true" "false")
         :data-active (when active? "true")
         :data-duplicate-of (when duplicate-of-label duplicate-of-label)
         :data-confirm-open (when duplicate-prompt "true")
         :on {:click [[:actions/add-portfolio-optimizer-universe-instrument market-key]]}}
   [:span {:class ["min-w-0"]}
    ;; The symbol wraps rather than truncating, and the quote token is its own
    ;; tinted pill. The separator stays on the lead span, so the row's text
    ;; content still reads as the whole symbol for anything matching on text.
    [:span {:class ["optimizer-universe-candidate-symbol" "block" "font-mono"
                    "text-[0.8125rem]" "font-semibold"]}
     (highlighted symbol-segments ["optimizer-universe-match"])
     (when quote-label
       [:span {:class ["optimizer-universe-quote-pill" "font-mono"
                       "text-[0.5625rem]" "font-semibold" "tracking-[0.06em]"]
               :data-quote quote-label}
        quote-label])]
    ;; A name line that just repeats the symbol is a wasted row in a narrow rail —
    ;; every vault renders its own name as both, which read as a stutter.
    (when-not duplicate-name?
      [:span {:class ["optimizer-universe-candidate-name" "block" "truncate"
                      "text-[0.6875rem]" "text-trading-muted"]}
       (highlighted name-segments ["optimizer-universe-match"])])
    (when duplicate-of-label
      (same-as-tag market-key duplicate-of-label))]
   [:span {:class ["optimizer-universe-candidate-meta" "text-right" "font-mono"
                   "text-[0.625rem]" "text-trading-muted/70"]}
    adv-label]
   [:button {:type "button"
             :class ["optimizer-universe-add-button"
                     "text-right" "font-mono" "text-[0.75rem]" "font-semibold"
                     "text-warning" "hover:text-warning"]
             :data-role (str "portfolio-optimizer-universe-add-" market-key)
             :aria-label (str "Add "
                              (:pre symbol-segments)
                              (:match symbol-segments)
                              (:post symbol-segments)
                              (or quote-label "")
                              (when market-type
                                (str " " (name market-type)))
                              (when duplicate-of-label
                                (str ", same asset as " duplicate-of-label
                                     " in your mix")))
             :on {:click [[:actions/add-portfolio-optimizer-universe-instrument
                           market-key]]}}
    "+ add"]])

(defn- compare-cell
  [text & extra-class]
  [:span {:class (into ["border-t" "border-base-300" "px-2" "py-1"] extra-class)}
   (or text "—")])

(defn- reveal-on-mount
  "The result list is a short scroll box, so a confirm opened on a lower row
  would otherwise sit below the fold with its buttons hidden. Scroll it into
  view once, when it first appears; focus stays in the search field so Enter /
  Escape keep answering it."
  [{:replicant/keys [life-cycle node]}]
  (when (= :replicant.life-cycle/mount life-cycle)
    (platform/set-timeout!
     (fn []
       (when (and node (.-isConnected node))
         (.scrollIntoView node #js {:block "nearest"})))
     0)))

(defn- duplicate-confirm
  "The inline same-stock confirm. Rendered as the row's SIBLING, never inside it:
  the row is itself a click-to-add target, and a button nested in it would
  bubble its click into a plain add."
  [{:keys [market-key existing-label adding-label existing-symbol adding-symbol
           existing-venue adding-venue
           existing-quote adding-quote]}]
  (let [question-id (str "portfolio-optimizer-universe-duplicate-question-" market-key)
        choice (fn [c] {:click [[:actions/add-portfolio-optimizer-universe-instrument
                                 market-key c]]})]
    ;; scroll-mt clears the sticky group header when the confirm scrolls itself
    ;; into view, so its headline is never tucked under "PERPS".
    [:div {:class ["optimizer-universe-duplicate-confirm" "flex" "flex-col" "gap-2"
                   "scroll-mt-7" "border" "border-warning/60" "bg-base-100" "p-2.5"]
           :replicant/key (str market-key "::confirm")
           :role "group"
           :aria-labelledby question-id
           :replicant/on-render reveal-on-mount
           :data-role (str "portfolio-optimizer-universe-duplicate-confirm-" market-key)}
     [:div {:class ["flex" "flex-col" "gap-1"]}
      [:p {:class ["text-[0.8125rem]" "font-semibold" "leading-[1.35]" "text-trading-text"]
           :id question-id}
       (str adding-label " is the same asset as " existing-label
            ", already in your mix.")]
      [:p {:class ["text-[0.75rem]" "leading-[1.45]" "text-trading-muted"]}
       "Both move together, so optimizing both splits one bet and makes the weights unstable."]]
     [:div {:class ["grid" "grid-cols-[4rem_minmax(0,1fr)_minmax(0,1fr)]" "border"
                    "border-base-300" "font-mono" "text-[0.6875rem]"]
            :data-role (str "portfolio-optimizer-universe-duplicate-compare-" market-key)}
      [:span {:class ["px-2" "py-1"]} ""]
      [:span {:class ["border-l" "border-base-300" "px-2" "py-1" "text-[0.625rem]"
                      "uppercase" "tracking-[0.1em]" "text-trading-muted"]}
       "In mix"]
      [:span {:class ["border-l" "border-base-300" "px-2" "py-1" "text-[0.625rem]"
                      "uppercase" "tracking-[0.1em]" "text-warning"]}
       "Adding"]
      (compare-cell "Venue" "text-trading-muted")
      (compare-cell existing-venue "border-l")
      (compare-cell adding-venue "border-l")
      (compare-cell "Quote" "text-trading-muted")
      (compare-cell existing-quote "border-l")
      (compare-cell adding-quote "border-l")]
     [:div {:class ["flex" "flex-wrap" "gap-2"]}
      [:button {:type "button"
                :class ["min-h-[2.25rem]" "flex-[1_1_9rem]" "bg-warning" "px-3"
                        "text-[0.8125rem]" "font-semibold" "text-base-100"
                        "hover:brightness-110"]
                :data-role (str "portfolio-optimizer-universe-duplicate-swap-" market-key)
                :on (choice "swap")}
       (str "Swap for " adding-symbol)]
      [:button {:type "button"
                :class ["min-h-[2.25rem]" "flex-[1_1_6rem]" "border" "border-base-300"
                        "px-3" "text-[0.8125rem]" "font-medium" "text-trading-text"
                        "hover:border-warning/60"]
                :data-role (str "portfolio-optimizer-universe-duplicate-both-" market-key)
                :on (choice "both")}
       "Add both"]
      [:button {:type "button"
                :class ["min-h-[2.25rem]" "flex-[1_1_6rem]" "px-3" "text-[0.8125rem]"
                        "font-medium" "text-trading-muted" "hover:text-warning"]
                :data-role (str "portfolio-optimizer-universe-duplicate-keep-" market-key)
                :on (choice "keep")}
       "Keep current"]]
     [:div {:class ["flex" "flex-wrap" "gap-3" "font-mono" "text-[0.625rem]"
                    "text-trading-muted"]}
      [:span {:aria-hidden "true"} "↵ swap"]
      [:span {:aria-hidden "true"} "esc keep"]
      [:span {:class ["ml-auto"]} "Swap keeps your side and place in the list"]]]))

(defn- duplicate-banner
  "One plain-words line at the top of the results when they include listings of
  something already in the mix. The held row itself is outlined in the
  universe list below."
  [{:keys [existing]}]
  (let [[{:keys [underlying existing-label]}] existing
        many? (< 1 (count existing))]
    [:div {:class ["optimizer-universe-duplicate-banner" "flex" "items-start" "gap-2"
                   "border-b" "border-info/35" "bg-info/10" "px-3" "py-2"]
           :role "status"
           :data-role "portfolio-optimizer-universe-duplicate-banner"}
     [:svg {:width "14" :height "14" :viewBox "0 0 24 24" :fill "none"
            :stroke "currentColor" :stroke-width "2" :stroke-linecap "round"
            :stroke-linejoin "round" :aria-hidden "true"
            :class ["mt-0.5" "shrink-0" "text-info"]}
      [:path {:d "M12 2 2 7l10 5 10-5-10-5Z"}]
      [:path {:d "m2 17 10 5 10-5"}]
      [:path {:d "m2 12 10 5 10-5"}]]
     [:div {:class ["flex" "min-w-0" "flex-col" "gap-0.5"]}
      [:span {:class ["text-[0.8125rem]" "font-semibold" "text-trading-text"]}
       (if many?
         (str (count existing) " of these assets are already in your mix")
         (str "You already have " underlying))]
      [:span {:class ["text-[0.75rem]" "leading-[1.45]" "text-trading-muted"]}
       (if many?
         "Rows tagged Same as… are other listings of something you hold — adding one would count it twice."
         (str existing-label " is in your universe. Rows tagged Same as "
              existing-label " track the same asset — adding one would count it twice."))]]]))

(defn- group-block
  [{:keys [key label count rows]} count-label]
  (into [:div {:replicant/key (name key)
               :data-role (str "portfolio-optimizer-universe-candidate-group-" (name key))}
         ;; Sticky so the type stays legible while the list scrolls. Deliberately
         ;; a DIFFERENT data-role stem from the rows: two tests select rows with
         ;; [data-role^="portfolio-optimizer-universe-candidate-row-"].
         [:div {:class ["optimizer-universe-candidate-group-header"
                        "flex" "items-center" "justify-between" "px-2" "py-1"
                        "font-mono" "text-[0.5625rem]" "font-semibold" "uppercase"
                        "tracking-[0.14em]"]
                :data-role (str "portfolio-optimizer-universe-candidate-group-header-"
                                (name key))
                :data-kind (name key)}
          [:span label]
          [:span {:class ["optimizer-universe-candidate-group-count"]}
           (count-label count)]]]
        (mapcat (fn [row]
                  (cond-> [(candidate-row row (:index row))]
                    (:duplicate-prompt row)
                    (conj (duplicate-confirm (:duplicate-prompt row)))))
                rows)))

(defn- results-footer
  [{:keys [footer-label add-all-label market-keys]}]
  [:div {:class ["optimizer-universe-results-footer"
                 "flex" "flex-wrap" "items-center" "justify-between" "gap-x-2"
                 "gap-y-0.5" "px-2" "py-1.5"]
         :data-role "portfolio-optimizer-universe-search-footer"}
   ;; Both halves stay on one line each; a long add-all label (with its
   ;; "skips N duplicates" note) wraps whole onto its own line.
   [:span {:class ["whitespace-nowrap" "font-mono" "text-[0.625rem]" "text-trading-muted/80"]
           :data-role "portfolio-optimizer-universe-search-footer-count"}
    footer-label]
   (when add-all-label
     [:button {:type "button"
               :class ["ml-auto" "whitespace-nowrap" "font-mono" "text-[0.625rem]"
                       "font-semibold" "text-warning" "hover:text-warning"]
               :data-role "portfolio-optimizer-universe-search-add-all"
               :on {:click [[:actions/add-portfolio-optimizer-universe-matches
                             market-keys]]}}
      add-all-label])])

(defn- keyboard-hints
  []
  [:div {:class ["optimizer-universe-results-hints" "flex" "gap-3" "px-2" "py-1"]
         :data-role "portfolio-optimizer-universe-search-hints"
         :aria-hidden "true"}
   [:span "↑↓ move"]
   [:span "↵ add"]
   [:span "esc clear"]])

(defn- results-panel
  [{:keys [groups footer-label add-all-label market-keys count-label
           duplicate-summary]}]
  [:div {:class ["mt-1" "border" "border-base-300" "bg-base-200/80"
                 "shadow-[0_12px_32px_rgba(0,0,0,0.45)]"]
         :data-role "portfolio-optimizer-universe-search-results-panel"}
   (when duplicate-summary
     (duplicate-banner duplicate-summary))
   (into [:div {:class ["optimizer-universe-results-scroll"
                        "overflow-y-auto" "overflow-x-hidden" "min-h-0"]
                :id "portfolio-optimizer-universe-search-results"
                :role "listbox"
                :data-role "portfolio-optimizer-universe-search-results"}]
         (map #(group-block % count-label) groups))
   (results-footer {:footer-label footer-label
                    :add-all-label add-all-label
                    :market-keys market-keys})
   (keyboard-hints)])

(defn search-block
  "The search field, its facets and the result list.

  `model` is the universe section view-model; `count-label` and `add-all-label`
  are passed in so the label vocabulary stays in the view-model namespace."
  [{:keys [search-query searching? market-keys active-index markets
           search-type-chips search-quote-chips search-groups
           search-match-label search-footer-label search-filtered?
           search-duplicate-summary search-addable-market-keys
           search-add-all-label]}
   {:keys [count-label]}]
  [:div {:class ["mt-3" "relative"]}
   [:div {:class ["optimizer-universe-search-shell"
                  "flex" "items-center" "gap-1.5" "border" "px-2"
                  "portfolio-optimizer-universe-search-shell"
                  (if searching?
                    "border-warning/70"
                    "border-base-300")
                  "bg-transparent"]
          :data-role "portfolio-optimizer-universe-search-shell"
          :data-searching (when searching? "true")}
    [:span {:class ["optimizer-universe-search-affordance"
                    "portfolio-optimizer-universe-search-affordance"
                    "font-mono" "text-[0.75rem]" "text-trading-muted"]
            :data-role "portfolio-optimizer-universe-search-icon"}
     "⌕"]
    [:input {:type "search"
             :class (into input-class ["optimizer-universe-search-field"
                                       "portfolio-optimizer-universe-search-field"
                                       "border-0" "bg-transparent" "px-0" "focus:border-0"])
             :placeholder "Search ticker, name, or vault (e.g. TIA, AVAX, Solana, HLP...)"
             :data-role "portfolio-optimizer-universe-search-input"
             :aria-controls "portfolio-optimizer-universe-search-results"
             :aria-activedescendant (when (and searching? (seq markets))
                                      (str "portfolio-optimizer-universe-candidate-" active-index))
             :value search-query
             :on {:input [[:actions/set-portfolio-optimizer-universe-search-query
                           [:event.target/value]]]
                  :keydown [[:actions/handle-portfolio-optimizer-universe-search-keydown
                             [:event/key]
                             market-keys]]}}]
    (when searching?
      [:button {:type "button"
                :class ["optimizer-universe-search-affordance"
                        "optimizer-universe-search-clear"
                        "portfolio-optimizer-universe-search-affordance"
                        "font-mono" "text-xs" "text-trading-muted" "hover:text-warning"]
                :aria-label "Clear universe search"
                :data-role "portfolio-optimizer-universe-search-clear"
                :on {:click [[:actions/set-portfolio-optimizer-universe-search-query ""]]}}
       "x"])
    [:span {:class ["optimizer-universe-search-add-hint"
                    "portfolio-optimizer-universe-search-add-hint"
                    "border" "border-base-300"
                    "font-mono" "text-[0.625rem]" "text-trading-muted"]
            :data-role "portfolio-optimizer-universe-search-add-hint"}
     "↵ add"]]
   ;; The hit count sits BELOW the shell rather than inside it: the shell's
   ;; geometry (icon / field / clear / "↵ add") is pinned by a browser spec at
   ;; four viewports, and a fifth child there displaces the add hint.
   (when searching?
     [:div {:class ["mt-1" "flex" "items-center" "justify-between" "gap-2"]}
      [:span {:class ["font-mono" "text-[0.625rem]" "uppercase" "tracking-[0.1em]"
                      "text-trading-muted/80"]
              :data-role "portfolio-optimizer-universe-search-match-count"}
       search-match-label]
      (when search-filtered?
        [:button {:type "button"
                  :class ["font-mono" "text-[0.625rem]" "text-trading-muted"
                          "underline" "hover:text-warning"]
                  :data-role "portfolio-optimizer-universe-search-reset-filters"
                  :on {:click [[:actions/set-portfolio-optimizer-universe-search-type-filter "all"]
                               [:actions/set-portfolio-optimizer-universe-search-quote-filter "all"]]}}
         "reset filters"])])
   (when searching?
     (facet-rows search-type-chips search-quote-chips))
   (when searching?
     (if (seq search-groups)
       (results-panel {:groups search-groups
                       :footer-label search-footer-label
                       ;; Add-all skips same-stock rows, so it is fed the
                       ;; de-duplicated batch and says how many it skipped.
                       :add-all-label search-add-all-label
                       :market-keys search-addable-market-keys
                       :count-label count-label
                       :duplicate-summary search-duplicate-summary})
       [:p {:class ["mt-1" "border" "border-base-300" "bg-base-200/70" "p-2"
                    "text-xs" "text-trading-muted"]
            :data-role "portfolio-optimizer-universe-search-results-empty"}
        (if search-filtered?
          "No market matches these filters — try clearing the quote filter."
          "No matching unused instruments found.")]))])

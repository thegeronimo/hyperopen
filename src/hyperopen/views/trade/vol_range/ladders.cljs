(ns hyperopen.views.trade.vol-range.ladders
  "The pieces of the Vol ladders tab (spec 8.5, 11.2): an active ladder's
  card (header with actions, attention callout, the four-cell summary, then
  its orders on demand) and the closed ladders' aligned table. Pure renders
  of `dock-model/grid-row` rows that dispatch only registered ladder actions."
  (:require [clojure.string :as str]
            [hyperopen.domain.trading.precision :as precision]
            [hyperopen.views.trade.vol-range.commands :as commands]))

(def ^:private button-classes
  ["min-h-[28px]" "rounded" "border" "border-ho-border" "px-3" "py-1" "text-xs" "text-ho-text"
   "hover:bg-ho-surface-raised"
   "focus:outline-none" "focus-visible:ring-2" "focus-visible:ring-ho-text"
   "disabled:cursor-not-allowed" "disabled:opacity-50"])

(def ^:private danger-button-classes
  (-> (into [] (remove #{"border-ho-border" "text-ho-text"}) button-classes)
      (into ["border-ho-border-sell" "text-ho-sell"])))

(def ^:private link-button-classes
  ["min-h-[28px]" "px-1" "text-xs" "font-medium" "text-ho-accent" "hover:text-ho-accent-bright"
   "focus:outline-none" "focus-visible:ring-2" "focus-visible:ring-ho-text"])

(def ^:private label-classes ["text-[11px]" "text-ho-text-muted"])

(defn short-address
  "0x1234…abcd for a long address; shorter values pass through."
  [address]
  (let [a (some-> address str)]
    (if (and a (> (count a) 12))
      (str (subs a 0 6) "…" (subs a (- (count a) 4)))
      a)))

(defn- side-class [side] (if (= :buy side) "text-ho-buy" "text-ho-sell"))
(defn- side-text [side] (if (= :buy side) "Buy" "Sell"))
(defn- dash [x] (if (some? x) x "—"))
(defn- zero-str? [x] (or (nil? x) (re-matches #"0(\.0*)?" (str x))))
(defn- count-label [n word] (str n " " word))

(defn- fees-text [fees]
  (if (seq fees)
    (str/join " · " (map (fn [[token amount]] (str amount " " token)) fees))
    "—"))

(defn- fill-text
  "\"2 @ 76.4\" (quantity at VWAP), or — before any fill."
  [{:keys [qty vwap]}]
  (if (zero-str? qty) "—" (str qty " @ " (dash vwap))))

;; ---------------------------------------------------------------------------
;; Status
;; ---------------------------------------------------------------------------

(defn- status-badge
  [{:keys [status status-label attention? needs-attention? closed? recovered?]}]
  [:span {:class (into ["inline-flex" "items-center" "gap-1.5" "rounded-full" "px-2" "py-0.5"
                        "text-[11px]" "font-medium" "whitespace-nowrap"]
                       (cond (or attention? needs-attention?) ["bg-ho-warn/15" "text-ho-warn"]
                             closed? ["bg-ho-surface-raised" "text-ho-text-secondary"]
                             :else ["bg-ho-accent-soft" "text-ho-accent-bright"]))
          :data-role "vol-ladder-status"
          :data-status (some-> status name)
          :data-recovered (when recovered? "true")}
   (cond attention? [:span {:aria-hidden true} "⚠"]
         needs-attention? nil
         (not closed?) [:span {:aria-hidden true :class ["h-1.5" "w-1.5" "rounded-full" "bg-ho-accent"]}])
   [:span status-label]])

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(defn- market-hint-id [grid-id] (str "vol-ladder-market-hint-" grid-id))

(defn- repair-button
  "\"Repair N rejected orders\": opens the rejected-order review. Disabled,
  and described by the market hint, while the ticket is on another market."
  [grid-id {:keys [enabled? busy? label aria-label hint]}]
  [:button {:type "button"
            :class ["min-h-[28px]" "rounded" "bg-ho-warn" "px-3" "py-1" "text-xs" "font-semibold" "text-ho-bg"
                    "focus:outline-none" "focus-visible:ring-2" "focus-visible:ring-ho-text"
                    "disabled:cursor-not-allowed" "disabled:opacity-50"]
            :disabled (not enabled?)
            :aria-busy (boolean busy?)
            :aria-label aria-label
            :aria-describedby (when hint (market-hint-id grid-id))
            :data-role "vol-ladder-repair"
            :data-grid-id grid-id
            :on {:click (commands/open-repair-actions grid-id)}}
   label])

(defn- update-button
  "\"Preview update\": loads the ladder into the ticket and opens the update
  dialog. Disabled, and described by the market hint, while the ticket is on
  another market."
  [grid-id {:keys [enabled? label aria-label hint]}]
  [:button {:type "button" :class (conj button-classes "bg-ho-surface-raised") :disabled (not enabled?)
            :aria-label aria-label
            :aria-describedby (when hint (market-hint-id grid-id))
            :data-role "vol-ladder-preview-update"
            :data-grid-id grid-id
            :on {:click (commands/preview-update-actions grid-id)}}
   label])

(defn- refresh-button [{:keys [grid-id market refreshing?]}]
  [:button {:type "button" :class button-classes :disabled (boolean refreshing?)
            :aria-busy (boolean refreshing?)
            :aria-label (str "Refresh " market " ladder status")
            :data-role "vol-ladder-refresh"
            :on {:click (commands/refresh-ladder-actions grid-id)}}
   "Refresh"])

(defn- cancel-button [{:keys [grid-id market canceling? counts]}]
  [:button {:type "button" :class danger-button-classes :disabled (boolean canceling?)
            :aria-busy (boolean canceling?)
            :aria-label (str "Cancel " market " ladder (" (count-label (:live counts) "live order")
                             (when (not= 1 (:live counts)) "s") ")")
            :data-role "vol-ladder-cancel"
            :on {:click (commands/cancel-ladder-actions grid-id)}}
   "Cancel ladder"])

(defn- market-hint
  "One hint per ladder, shared by Repair and Preview update."
  [{:keys [grid-id repair] update-control :update}]
  (when-let [hint (or (when (:visible? repair) (:hint repair))
                      (when (:visible? update-control) (:hint update-control)))]
    [:span {:id (market-hint-id grid-id) :class ["text-xs" "text-ho-text-secondary"]
            :data-role "vol-ladder-market-hint"}
     hint]))

(defn- header-actions
  [{:keys [grid-id can-cancel?] update-control :update :as row}]
  [:div {:class ["flex" "flex-wrap" "items-center" "gap-2"]}
   (when (:visible? update-control) (update-button grid-id update-control))
   (refresh-button row)
   (when can-cancel? (cancel-button row))])

(defn- view-orders-button
  [{:keys [grid-id selected? legs]} compact?]
  [:button {:type "button"
            :class (if compact?
                     (conj button-classes "whitespace-nowrap")
                     ["inline-flex" "min-h-[28px]" "items-center" "gap-1.5" "px-1" "text-xs" "font-medium"
                      "text-ho-text" "focus:outline-none" "focus-visible:ring-2" "focus-visible:ring-ho-text"])
            :aria-expanded (boolean selected?)
            :data-role "vol-ladder-view-orders"
            :on {:click (commands/select-ladder-actions grid-id)}}
   (when-not compact?
     [:svg {:width 12 :height 12 :viewBox "0 0 12 12" :aria-hidden true
            :class (when-not selected? ["-rotate-90"])}
      [:path {:d "M3 4.5 6 7.5 9 4.5" :fill "none" :stroke "currentColor" :stroke-width 1.5
              :stroke-linecap "round" :stroke-linejoin "round"}]])
   (if compact?
     (if selected? "Hide orders" "Orders")
     (str (if selected? "Hide orders" "Show orders") " (" (count (remove :historical? legs)) ")"))])

(defn- tracking-block [{:keys [tracking market]}]
  (when-let [{:keys [text view-report? campaign-key]} tracking]
    [:span {:class ["inline-flex" "flex-wrap" "items-center" "gap-2"]}
     [:span {:class ["text-xs" "text-ho-text-muted"] :data-role "vol-ladder-tracking"} text]
     (when view-report?
       [:button {:type "button" :class link-button-classes
                 :aria-label (str "View the " market " Vol Range report")
                 :data-role "vol-ladder-view-report"
                 :on {:click (commands/view-report-actions campaign-key)}}
        "View report"])]))

;; ---------------------------------------------------------------------------
;; Active ladder card
;; ---------------------------------------------------------------------------

(defn- callout
  [{:keys [grid-id partial-message notice repair]}]
  (let [warn? (or partial-message (= :checking (:kind notice)))]
    (when (or partial-message notice)
      [:div {:class (into ["mx-4" "mb-3" "flex" "flex-wrap" "items-center" "justify-between" "gap-x-4" "gap-y-2"
                           "rounded" "border" "px-3" "py-2"]
                          (if warn? ["border-ho-warn/40" "bg-ho-warn/10"] ["border-ho-border" "bg-ho-surface-raised"]))
             :data-role "vol-ladder-notice"}
       (if partial-message
         [:p {:class ["text-xs" "text-ho-text"] :data-role "vol-ladder-partial"} partial-message]
         [:p {:class ["text-xs" "text-ho-text"]} (:text notice)])
       (when (:visible? repair) (repair-button grid-id repair))])))

(defn- stat-cell [label role & body]
  (into [:div {:class ["flex" "flex-col" "gap-1.5" "border-ho-border" "px-4" "py-3"
                       "border-b" "lg:border-b-0" "lg:border-r" "lg:last:border-r-0"]
               :data-role role}
         [:p {:class label-classes} label]]
        body))

(defn- kv [label value & [{:keys [label-class value-class role]}]]
  [:p {:class ["flex" "items-baseline" "justify-between" "gap-3" "text-xs"] :data-role role}
   [:span {:class [(or label-class "text-ho-text-secondary")]} label]
   " "
   [:span {:class ["num" "text-right" (or value-class "text-ho-text")]} value]])

(defn- segment [n total cls]
  (when (pos? n)
    [:span {:class [cls "h-full"] :style {:width (str (/ (* 100 n) total) "%")}}]))

(defn- orders-cell [{:keys [counts]}]
  (let [{:keys [members live filled rejected checking canceled]} counts
        others (keep (fn [[n word]] (when (pos? n) (count-label n word)))
                     [[filled "filled"] [rejected "rejected"] [checking "checking"] [canceled "canceled"]])
        summary (str/join " · " (cons (count-label live "live") others))]
    (stat-cell "Orders" "vol-ladder-counts"
               [:p {:class ["num" "text-base" "font-medium" "text-ho-text"]}
                (str live) [:span {:class ["ml-1" "text-xs" "font-normal" "text-ho-text-muted"]} (str "of " members " live")]]
               (when (pos? members)
                 [:span {:role "img" :aria-label summary
                         :class ["flex" "h-1.5" "overflow-hidden" "rounded-full" "bg-ho-surface-raised"]}
                  (segment live members "bg-ho-accent")
                  (segment filled members "bg-ho-text")
                  (segment rejected members "bg-ho-warn")
                  (segment checking members "bg-ho-info")
                  (segment canceled members "bg-ho-text-dim")])
               [:p {:class ["text-[11px]" "text-ho-text-muted" "num"]}
                (if (seq others) (str/join " · " others) "0 filled")])))

(defn- units-text [units base]
  (if (zero-str? units) "—" (str units " " base)))

(defn- resting-cell [{:keys [counts base-symbol range]}]
  (stat-cell "Resting on the book" "vol-ladder-units"
             (kv "To buy" (units-text (:live-buy-units counts) base-symbol) {:label-class "text-ho-buy"})
             (kv "To sell" (units-text (:live-sell-units counts) base-symbol) {:label-class "text-ho-sell"})
             (when range
               [:p {:class ["text-[11px]" "text-ho-text-muted"]}
                "Range " [:span {:class ["num"]} (if (= (:low range) (:high range))
                                                   (:low range)
                                                   (str (:low range) " – " (:high range)))]])))

(defn- filled-cell [{:keys [filled]}]
  (stat-cell "Filled so far (execution, not P&L)" "vol-ladder-filled"
             (kv "Bought" (fill-text (:buy filled)) {:label-class "text-ho-buy" :role "vol-ladder-filled-buy"})
             (kv "Sold" (fill-text (:sell filled)) {:label-class "text-ho-sell" :role "vol-ladder-filled-sell"})
             [:p {:class ["text-[11px]" "text-ho-text-muted"]}
              "Fees " [:span {:class ["num"]}
                       (let [buy (get-in filled [:buy :fees]) sell (get-in filled [:sell :fees])]
                         (if (or (seq buy) (seq sell))
                           (str/join " · " (remove #{"—"} [(fees-text buy) (fees-text sell)]))
                           "—"))]]))

(defn- settings-cell [{:keys [settings]}]
  (stat-cell "Set at placement" "vol-ladder-model"
             (if-let [{:keys [anchor anchor-mode volatility tilt]} settings]
               [:div {:class ["flex" "flex-col" "gap-1.5"]}
                (kv "Anchor" [:span (dash anchor)
                              (when anchor-mode [:span {:class ["ml-1" "text-ho-text-muted"]} anchor-mode])])
                (kv "Daily volatility" (dash volatility))
                (kv "Tilt" tilt)]
               [:p {:class ["text-xs" "text-ho-text-secondary"]} "Original settings unavailable"])))

(defn- legs-table
  "Orders by price, highest first, with the anchor between sells and buys."
  [legs anchor]
  (let [price (fn [l] (or (some-> (:price l) str precision/parse-decimal) (precision/parse-decimal "0")))
        by-price (sort (fn [a b] (precision/cmp (price b) (price a))) legs)
        sells (filter #(= :sell (:side %)) by-price)
        buys (filter #(= :buy (:side %)) by-price)
        reasons? (some :reason legs)
        cols (cond-> [["Side"] ["Price" true] ["From anchor" true] ["Size" true] ["Filled" true]
                      ["Remaining" true] ["Status"]]
               reasons? (conj ["Reason"]))
        leg-row (fn [{:keys [cloid side price from-anchor size filled remaining status-label reason historical?]}]
                  ^{:key cloid}
                  [:tr {:class (into ["border-t" "border-ho-border/60"] (when historical? ["opacity-60"]))
                        :data-role "vol-ladder-leg"
                        :data-historical (when historical? "true")}
                   [:td {:class ["py-1.5" "pr-2" (side-class side)]} (side-text side)]
                   [:td {:class ["px-2" "py-1.5" "text-right" "text-ho-text"]} (dash price)]
                   [:td {:class ["px-2" "py-1.5" "text-right" "text-ho-text-muted"]} (dash from-anchor)]
                   [:td {:class ["px-2" "py-1.5" "text-right" "text-ho-text"]} (dash size)]
                   [:td {:class ["px-2" "py-1.5" "text-right" "text-ho-text"]} (dash filled)]
                   [:td {:class ["px-2" "py-1.5" "text-right" "text-ho-text"]} (dash remaining)]
                   [:td {:class ["py-1.5" "pl-4" "font-sans" "text-ho-text"]}
                    status-label
                    (when historical? [:span {:class ["ml-1" "italic" "text-ho-text-muted"]} "earlier attempt"])]
                   (when reasons? [:td {:class ["py-1.5" "pl-4" "font-sans" "text-ho-text-secondary"]} (or reason "")])])]
    [:div {:class ["overflow-x-auto"] :data-role "vol-ladder-detail"}
     [:table {:class ["w-full" "max-w-5xl" "text-xs" "num"]}
      [:thead
       [:tr {:class ["font-sans" "text-ho-text-muted"]}
        (for [[idx [label right?]] (map-indexed vector cols)]
          ^{:key idx}
          [:th {:scope "col" :class (into ["py-1.5" "font-medium"] (cond right? ["px-2" "text-right"] (zero? idx) ["pr-2" "text-left"] :else ["pl-4" "text-left"]))}
           label])]]
      [:tbody
       (map leg-row sells)
       (when anchor
         [:tr {:class ["border-t" "border-dashed" "border-ho-border"]}
          [:td {:col-span (count cols) :class ["py-1.5" "font-sans" "text-[11px]" "text-ho-text-muted"]}
           "Anchor " [:span {:class ["num" "text-ho-text"]} anchor]]])
       (map leg-row buys)]]]))

(defn ladder-card
  [{:keys [grid-id market account placed-text selected? legs settings lineage status] :as row}]
  [:article {:class ["rounded-md" "border" "border-ho-border" "bg-ho-surface"]
             :data-role "vol-ladder-row"
             :data-grid-id grid-id
             :data-status (some-> status name)}
   [:header {:class ["flex" "flex-wrap" "items-center" "justify-between" "gap-3" "px-4" "py-3"]}
    [:div {:class ["flex" "flex-wrap" "items-center" "gap-x-3" "gap-y-1"]}
     [:h3 {:class ["text-sm" "font-semibold" "text-ho-text"] :data-role "vol-ladder-market"} market]
     (status-badge row)
     (when placed-text [:span {:class ["text-xs" "text-ho-text-muted"]} (str "Placed " placed-text)])
     [:span {:class ["text-[11px]" "text-ho-text-muted" "num"] :title account :data-role "vol-ladder-account"}
      (short-address account)]]
    [:div {:class ["flex" "flex-wrap" "items-center" "gap-2"]}
     (header-actions row)
     (market-hint row)]]
   (callout row)
   [:div {:class ["grid" "grid-cols-1" "border-y" "border-ho-border" "sm:grid-cols-2" "lg:grid-cols-4"]}
    (orders-cell row)
    (resting-cell row)
    (filled-cell row)
    (settings-cell row)]
   [:div {:class ["flex" "flex-wrap" "items-center" "justify-between" "gap-x-4" "gap-y-1" "px-3" "py-1.5"]}
    (view-orders-button row false)
    [:div {:class ["flex" "flex-wrap" "items-center" "gap-x-4" "gap-y-1"]}
     (when lineage [:span {:class ["text-xs" "text-ho-text-secondary"] :data-role "vol-ladder-lineage"} lineage])
     (tracking-block row)]]
   (when selected?
     [:div {:class ["px-4" "pb-3"]} (legs-table legs (:anchor settings))])])

;; ---------------------------------------------------------------------------
;; Closed ladders
;; ---------------------------------------------------------------------------

(def ^:private closed-columns
  [["Market"] ["Outcome"] ["Placed"] ["Filled" true] ["Bought @ VWAP" true] ["Sold @ VWAP" true]
   ["Fees" true] ["Tracking"] [nil]])

(defn- closed-rows
  [{:keys [grid-id market placed-text outcome lineage counts filled selected? legs settings status] :as row}]
  (let [td ["px-3" "py-2" "align-top"]]
    (cond-> [^{:key grid-id}
             [:tr {:class ["border-t" "border-ho-border"]
                   :data-role "vol-ladder-row" :data-grid-id grid-id :data-status (some-> status name)}
              [:th {:scope "row" :class ["px-3" "py-2" "text-left" "align-top" "font-medium"]}
               [:span {:class ["flex" "flex-wrap" "items-center" "gap-2"]}
                [:span {:class ["text-ho-text"] :data-role "vol-ladder-market"} market]
                (status-badge row)]]
              [:td {:class (conj td "text-ho-text-secondary")}
               [:span {:class ["block"]} outcome]
               (when lineage [:span {:class ["block" "text-[11px]" "text-ho-text-muted"] :data-role "vol-ladder-lineage"}
                              lineage])]
              [:td {:class (into td ["num" "whitespace-nowrap" "text-ho-text-secondary"])} (dash placed-text)]
              [:td {:class (into td ["num" "whitespace-nowrap" "text-right"]) :data-role "vol-ladder-counts"}
               (str (:filled counts) " of " (:members counts))]
              [:td {:class (into td ["num" "text-right" "whitespace-nowrap"]) :data-role "vol-ladder-filled-buy"}
               (fill-text (:buy filled))]
              [:td {:class (into td ["num" "text-right" "whitespace-nowrap"]) :data-role "vol-ladder-filled-sell"}
               (fill-text (:sell filled))]
              [:td {:class (into td ["num" "text-right" "text-ho-text-secondary"])}
               (let [buy (get-in filled [:buy :fees]) sell (get-in filled [:sell :fees])]
                 (if (or (seq buy) (seq sell))
                   (str/join " · " (remove #{"—"} [(fees-text buy) (fees-text sell)]))
                   "—"))]
              [:td {:class td} (tracking-block row)]
              [:td {:class (into td ["text-right"])} (view-orders-button row true)]]]
      selected? (conj ^{:key (str grid-id "-detail")}
                      [:tr
                       [:td {:col-span (count closed-columns) :class ["bg-ho-bg" "px-3" "pb-3"]}
                        (legs-table legs (:anchor settings))]]))))

(defn closed-table [rows]
  [:div {:class ["overflow-x-auto" "rounded-md" "border" "border-ho-border"]}
   [:table {:class ["w-full" "min-w-[880px]" "text-xs"] :data-role "vol-ladders-closed"}
    [:thead
     [:tr {:class ["bg-ho-surface" "text-ho-text-muted"]}
      (for [[idx [label right?]] (map-indexed vector closed-columns)]
        ^{:key idx}
        [:th {:scope "col" :class ["whitespace-nowrap" "px-3" "py-2" "font-medium" (if right? "text-right" "text-left")]}
         (or label [:span {:class ["sr-only"]} "Orders"])])]]
    [:tbody (mapcat closed-rows rows)]]])

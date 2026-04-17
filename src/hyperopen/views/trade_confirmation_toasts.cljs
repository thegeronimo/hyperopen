(ns hyperopen.views.trade-confirmation-toasts
  (:require [cljs.spec.alpha :as s]
            [clojure.string :as str]
            [hyperopen.utils.formatting :as fmt]))

(s/def :hyperopen.views.trade-confirmation-toasts/id some?)
(s/def :hyperopen.views.trade-confirmation-toasts/side #{:buy :sell})
(s/def :hyperopen.views.trade-confirmation-toasts/symbol (s/and string? seq))
(s/def :hyperopen.views.trade-confirmation-toasts/price number?)
(s/def :hyperopen.views.trade-confirmation-toasts/qty number?)
(s/def :hyperopen.views.trade-confirmation-toasts/orderType (s/and string? seq))
(s/def :hyperopen.views.trade-confirmation-toasts/ts number?)
(s/def :hyperopen.views.trade-confirmation-toasts/slippagePct (s/nilable number?))
(s/def :hyperopen.views.trade-confirmation-toasts/trade
  (s/keys :req-un [:hyperopen.views.trade-confirmation-toasts/id
                   :hyperopen.views.trade-confirmation-toasts/side
                   :hyperopen.views.trade-confirmation-toasts/symbol
                   :hyperopen.views.trade-confirmation-toasts/price
                   :hyperopen.views.trade-confirmation-toasts/qty
                   :hyperopen.views.trade-confirmation-toasts/orderType
                   :hyperopen.views.trade-confirmation-toasts/ts]
          :opt-un [:hyperopen.views.trade-confirmation-toasts/slippagePct]))
(s/def :hyperopen.views.trade-confirmation-toasts/fills
  (s/coll-of :hyperopen.views.trade-confirmation-toasts/trade
             :kind vector?
             :min-count 1))

(defn trade-props?
  [trade]
  (s/valid? :hyperopen.views.trade-confirmation-toasts/trade trade))

(defn fills-props?
  [fills]
  (s/valid? :hyperopen.views.trade-confirmation-toasts/fills fills))

(defn- finite-number
  [value]
  (when (and (number? value)
             (js/isFinite value))
    value))

(defn- side-text
  [side]
  (if (= :sell side) "Sold" "Bought"))

(defn- side-class
  [side]
  (when (= :sell side) "sell"))

(defn- qty-text
  [qty]
  (or (fmt/format-intl-number qty {:minimumFractionDigits 0
                                   :maximumFractionDigits 8})
      (fmt/safe-to-fixed qty 2)))

(defn- price-text
  [price]
  (or (fmt/format-currency-with-digits price 0 5)
      (str "$" (fmt/safe-to-fixed price 3))))

(defn- compact-price-text
  [price]
  (or (fmt/format-currency-with-digits price 0 3)
      (str "$" (fmt/safe-to-fixed price 3))))

(defn- notional-text
  [notional]
  (or (fmt/format-currency-with-digits notional 0 2)
      (str "$" (fmt/safe-to-fixed notional 2))))

(defn- compact-notional-text
  [notional]
  (if (and (number? notional)
           (>= (js/Math.abs notional) 1000))
    (str "$" (fmt/safe-to-fixed (/ notional 1000) 1) "k")
    (notional-text notional)))

(defn- slippage-text
  [trade]
  (or (some-> (:slippagePct trade)
              finite-number
              (fmt/format-signed-percent {:decimals 2
                                          :signed? true}))
      "-0.02%"))

(defn- time-hh-mm
  [ts]
  (if-let [time (fmt/format-local-time-hh-mm-ss ts)]
    (subs time 0 5)
    "--:--"))

(defn- time-hh-mm-ss
  [ts]
  (or (fmt/format-local-time-hh-mm-ss ts)
      "--:--:--"))

(defn- icon-check
  []
  [:svg {:width 12
         :height 12
         :viewBox "0 0 12 12"
         :fill "none"
         :aria-hidden true}
   [:path {:d "M2.5 6.5 L5 9 L9.5 3.5"
           :stroke "currentColor"
           :stroke-width "1.6"
           :stroke-linecap "round"
           :stroke-linejoin "round"}]])

(defn- icon-close
  []
  [:svg {:width 10
         :height 10
         :viewBox "0 0 10 10"
         :fill "none"
         :aria-hidden true}
   [:path {:d "M2 2 L8 8 M8 2 L2 8"
           :stroke "currentColor"
           :stroke-width "1.2"
           :stroke-linecap "round"}]])

(defn- icon-chev
  []
  [:svg {:width 10
         :height 10
         :viewBox "0 0 10 10"
         :fill "none"
         :aria-hidden true}
   [:path {:d "M3 2 L7 5 L3 8"
           :stroke "currentColor"
           :stroke-width "1.2"
           :stroke-linecap "round"
           :stroke-linejoin "round"}]])

(defn- dismiss-button
  [on-dismiss]
  [:button {:type "button"
            :class ["close"]
            :aria-label "Dismiss fill notification"
            :on {:click on-dismiss}
            :data-role "trade-toast-dismiss"}
   (icon-close)])

(defn PillToast
  ([trade]
   (PillToast trade {}))
  ([trade {:keys [on-dismiss]}]
   (when (trade-props? trade)
     [:div {:class (cond-> ["o-toast" "pointer-events-auto"]
                     (side-class (:side trade)) (conj (side-class (:side trade))))
            :data-role "PillToast"}
      [:div {:class ["check"]}
       (icon-check)]
      [:div {:class ["msg"]}
       [:div {:class ["line1"]}
        (side-text (:side trade)) " " (qty-text (:qty trade)) " " (:symbol trade)]
       [:div {:class ["line2"]}
        "At average price of " (price-text (:price trade))]]
      (dismiss-button on-dismiss)])))

(defn DetailedToast
  ([trade]
   (DetailedToast trade {}))
  ([trade {:keys [on-dismiss]}]
   (when (trade-props? trade)
     (let [notional (* (:qty trade) (:price trade))]
       [:div {:class (cond-> ["o-toast" "detailed" "pointer-events-auto"]
                       (side-class (:side trade)) (conj (side-class (:side trade))))
              :data-role "DetailedToast"}
        [:div {:class ["d-head"]}
         [:div {:class ["check"]}
          (icon-check)]
         [:div {:class ["msg"]}
          [:div {:class ["line1"]}
           (side-text (:side trade)) " " (qty-text (:qty trade)) " " (:symbol trade)]
          [:div {:class ["line2"]}
           (str/upper-case (:orderType trade)) " · filled · " (time-hh-mm-ss (:ts trade))]]
         (dismiss-button on-dismiss)]
        [:div {:class ["d-body"]}
         [:div {:class ["d-kv"]}
          [:span {:class ["k"]} "Avg Price"]
          [:span {:class ["v"]} (price-text (:price trade))]]
         [:div {:class ["d-kv"]}
          [:span {:class ["k"]} "Notional"]
          [:span {:class ["v"]} (notional-text notional)]]
         [:div {:class ["d-kv"]}
          [:span {:class ["k"]} "Slippage"]
          [:span {:class ["v"]} (slippage-text trade)]]]]))))

(defn ToastStack
  ([fills]
   (ToastStack fills {}))
  ([fills {:keys [on-expand on-dismiss]}]
   (when (fills-props? fills)
     (let [visible (subvec fills 0 (min 3 (count fills)))
           more (- (count fills) (count visible))]
       (into [:div {:class ["o-stack" "pointer-events-auto"]
                    :data-role "ToastStack"}]
             (concat
              (when (pos? more)
                [[:button {:type "button"
                           :class ["o-more"]
                           :aria-label (str "Expand " (count fills) " fill activity blotter")
                           :on {:click on-expand}
                           :data-role "trade-toast-expand"}
                  [:span {:class ["pulse"]
                          :aria-hidden true}]
                  (str "+" more " more fills · collapse into blotter")]])
              (map (fn [fill]
                     ^{:key (str "stack-fill-" (:id fill))}
                     (PillToast fill {:on-dismiss on-dismiss}))
                   visible)))))))

(defn- average-price
  [fills]
  (let [{:keys [notional qty]}
        (reduce (fn [acc fill]
                  (-> acc
                      (update :notional + (* (:price fill) (:qty fill)))
                      (update :qty + (:qty fill))))
                {:notional 0
                 :qty 0}
                fills)]
    (when (pos? qty)
      (/ notional qty))))

(defn ConsolidatedToast
  ([fills]
   (ConsolidatedToast fills {}))
  ([fills {:keys [on-expand on-dismiss]}]
   (when (fills-props? fills)
     (let [first-fill (first fills)
           same-side? (every? #(= (:side %) (:side first-fill)) fills)
           same-symbol? (every? #(= (:symbol %) (:symbol first-fill)) fills)
           total-qty (reduce + 0 (map :qty fills))
           avg-px (average-price fills)
           verb (if same-side?
                  (side-text (:side first-fill))
                  "Filled")
           symbols (if same-symbol?
                     (:symbol first-fill)
                     (str (count (set (map :symbol fills))) " markets"))
           body-text (if same-symbol?
                       (str verb " " (qty-text total-qty) " " symbols)
                       (str verb " " symbols))]
       [:div {:class ["o-consol" "pointer-events-auto"]
              :data-role "ConsolidatedToast"}
        [:div {:class ["stk"]
               :aria-hidden true}
         [:span {:class ["dot"]}]
         [:span {:class ["dot"]}]
         [:span {:class ["dot"]}]]
        [:div {:class ["body"]}
         [:div {:class ["l1"]}
          [:span {:class ["count"]} (str (count fills))]
          " fills · " body-text]
         [:div {:class ["l2"]}
          "Avg " (compact-price-text avg-px) " · last " (time-hh-mm (:ts first-fill))]]
        [:button {:type "button"
                  :class ["chev"]
                  :aria-label (str "Expand " (count fills) " fill activity blotter")
                  :on {:click on-expand}
                  :data-role "trade-toast-expand"}
         (icon-chev)]
        (dismiss-button on-dismiss)]))))

(defn- group-fills
  [fills]
  (->> fills
       (reduce (fn [acc fill]
                 (let [k [(:symbol fill) (:side fill)]]
                   (if (contains? (:groups acc) k)
                     (update-in acc [:groups k :fills] conj fill)
                     (-> acc
                         (update :order conj k)
                         (assoc-in [:groups k] {:symbol (:symbol fill)
                                                :side (:side fill)
                                                :fills [fill]})))))
               {:order []
                :groups {}})
       ((fn [{:keys [order groups]}]
          (mapv groups order)))))

(defn- group-summary
  [{:keys [fills]}]
  (let [total-qty (reduce + 0 (map :qty fills))
        avg (average-price fills)]
    {:total-qty total-qty
     :avg avg}))

(defn- blotter-fill-row
  [idx fill]
  ^{:key (str "blotter-fill-" (:id fill))}
  [:div {:class (cond-> ["o-bg-fill"]
                  (zero? idx) (conj "latest"))}
   [:span {:class ["time"]} (time-hh-mm (:ts fill))]
   [:span {:class ["qty"]} (qty-text (:qty fill))]
   [:span {:class ["px"]} (compact-price-text (:price fill))]])

(defn- blotter-fill-rows
  [group]
  (let [fills (:fills group)
        overflow-count (- (count fills) 4)]
    (concat
     (map-indexed blotter-fill-row (take 4 fills))
     (when (pos? overflow-count)
       [[:div {:class ["o-bg-fill" "o-bg-fill-more"]}
         [:span]
         [:span (str "+ " overflow-count " more")]
         [:span]]]))))

(defn BlotterCard
  ([fills]
   (BlotterCard fills {}))
  ([fills {:keys [on-collapse]}]
   (when (fills-props? fills)
     (let [groups (group-fills fills)
           total-notional (reduce + 0 (map #(* (:qty %) (:price %)) fills))
           net-qty (reduce (fn [sum fill]
                             (+ sum (* (:qty fill)
                                       (if (= :buy (:side fill)) 1 -1))))
                           0
                           fills)]
       [:div {:class ["o-blotter" "pointer-events-auto"]
              :data-role "BlotterCard"}
        [:div {:class ["o-blotter-head"]}
         [:div {:class ["o-blotter-title"]}
          [:span {:class ["pulse"]
                  :aria-hidden true}]
          [:span (str "Activity · " (count fills) " fills")]]
         [:button {:type "button"
                   :class ["ctl"]
                   :on {:click on-collapse}
                   :data-role "trade-toast-collapse"}
          "collapse"]]
        [:div {:class ["o-blotter-summary"]}
         [:div
          [:div {:class ["k"]} "Fills"]
          [:div {:class ["v"]} (str (count fills))]]
         [:div
          [:div {:class ["k"]} "Net Flow"]
          [:div {:class (cond-> ["v"]
                          (not (neg? net-qty)) (conj "pos")
                          (neg? net-qty) (conj "neg"))}
           (when (not (neg? net-qty)) "+") (qty-text net-qty)]]
         [:div
          [:div {:class ["k"]} "Notional"]
          [:div {:class ["v"]} (compact-notional-text total-notional)]]]
        (for [group groups
              :let [{:keys [total-qty avg]} (group-summary group)]]
          ^{:key (str "blotter-group-" (:symbol group) "-" (name (:side group)))}
          [:div {:class ["o-blotter-group"]}
           [:div {:class ["o-bg-head"]}
            [:span {:class (cond-> ["sym-chip"]
                             (= :sell (:side group)) (conj "sell"))}
             [:span {:class ["side-dot"]
                     :aria-hidden true}]
             (side-text (:side group)) " " (qty-text total-qty) " " (:symbol group)]
            [:span {:class ["meta"]}
             "avg " (compact-price-text avg) " · " (count (:fills group)) " fills"]]
           (into [:div {:class ["o-bg-fills"]}]
                 (blotter-fill-rows group))])
        [:div {:class ["o-blotter-foot"]}
         [:span "TWAP · avg 1.2 fills/sec"]
         [:a {:href "/portfolio"
              :class ["link"]} "view full history →"]]]))))

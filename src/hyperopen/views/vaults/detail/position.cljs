(ns hyperopen.views.vaults.detail.position
  "The viewer's own position in the vault: value, cost basis, P&L since the
   current deposit began, lockup status and their transfers into this vault."
  (:require [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.vaults.detail.format :as vf]
            [hyperopen.views.vaults.detail.position-chart :as position-chart]
            [hyperopen.views.vaults.detail.transfer-modal :as transfer-modal]
            [hyperopen.wallet.core :as wallet]))

(def ^:private ms-per-hour
  3600000)

(def ^:private band-surface
  ;; Same family as the vault hero and panels: deep teal ground with a faint
  ;; accent wash at the top, teal hairlines.
  ["rounded-2xl" "border" "border-ho-border-accent"
   "bg-[linear-gradient(180deg,rgb(var(--ho-accent-soft)/0.55)_0%,rgb(var(--ho-bg-deep))_42%)]"])

(defn- format-date
  [time-ms]
  (or (when (number? time-ms)
        (fmt/format-intl-date-time time-ms {:month "short"
                                            :day "numeric"
                                            :year "numeric"}))
      "—"))

(defn format-signed-currency
  [value]
  (cond
    (not (vf/finite-number? value)) "—"
    (neg? value) (str "−" (vf/format-currency (js/Math.abs value)))
    :else (str "+" (vf/format-currency value))))

(defn- tone-class
  "Colour by sign, but only for values that survive two-decimal display: a
   rounded 0.00 must not read as a gain or a loss."
  [value]
  (cond
    (not (number? value)) "text-trading-text"
    (>= value 0.005) "text-ho-buy"
    (<= value -0.005) "text-ho-sell"
    :else "text-trading-text"))

(defn- lockup-text
  [remaining-ms]
  (let [hours (js/Math.ceil (/ remaining-ms ms-per-hour))
        days (quot hours 24)
        rem-hours (rem hours 24)]
    (cond
      (pos? days) (str "Locked · " days "d " rem-hours "h left")
      :else (str "Locked · " (max 1 rem-hours) "h left"))))

(defn- status-chip
  [{:keys [locked? lockup-remaining-ms]}]
  (if locked?
    [:span {:class ["inline-flex" "items-center" "gap-1.5" "rounded-full" "border"
                    "border-ho-warn/50" "px-2.5" "py-1" "text-xs" "font-medium" "text-ho-warn"]
            :data-role "vault-position-lockup"}
     (lockup-text lockup-remaining-ms)]
    [:span {:class ["inline-flex" "items-center" "gap-1.5" "rounded-full" "border"
                    "border-ho-border-accent" "px-2.5" "py-1" "text-xs" "font-medium" "text-ho-accent-hi"]
            :data-role "vault-position-lockup"}
     [:span {:class ["h-1.5" "w-1.5" "rounded-full" "bg-ho-buy"]}]
     "Withdrawable now"]))

(defn- stat
  [{:keys [label value tone detail role first?]}]
  [:div {:class (into ["min-w-0" "py-3.5" "pr-4"]
                      (when-not first? ["sm:border-l" "sm:border-ho-border-accent" "sm:pl-4"]))
         :data-role role}
   [:div {:class ["text-xs" "uppercase" "tracking-[0.08em]" "text-ho-text-muted"]} label]
   [:div {:class ["num" "mt-1.5" "text-[22px]" "font-semibold" "leading-tight" (or tone "text-trading-text")]}
    value]
   [:div {:class ["mt-0.5" "min-h-4" "text-xs" "text-ho-text-muted"]}
    (or detail "")]])

(defn- composition-bar
  [{:keys [direction basis-share pnl-share]}]
  (let [pct (fn [share] (str (* 100 (max 0 (min 1 share))) "%"))]
    [:div {:class ["flex" "h-2.5" "overflow-hidden" "rounded-full" "bg-ho-accent-soft"]
           :data-role "vault-position-composition"
           :aria-hidden true}
     [:div {:class ["bg-ho-accent-soft-hi"] :style {:width (pct basis-share)}}]
     [:div {:class [(if (= :loss direction) "bg-ho-sell" "bg-ho-buy")]
            :style {:width (pct pnl-share)}}]]))

(defn- legend-item
  [swatch-class label value]
  [:span {:class ["inline-flex" "items-center" "gap-1.5"]}
   [:span {:class ["h-2" "w-2" "rounded-sm" swatch-class]}]
   label
   [:span {:class ["num" "text-trading-text"]} value]])

(defn- transfer-row
  [{:keys [kind time-ms amount realized commission vault-return-since-pct current-position?]}]
  (let [deposit? (= :deposit kind)]
    [:div {:class (into ["flex" "items-start" "justify-between" "gap-3" "border-t" "border-ho-border-accent-muted" "py-2.5"]
                        (when-not current-position? ["opacity-60"]))
           :data-role "vault-position-transfer"}
     [:div {:class ["min-w-0"]}
      [:div {:class ["text-sm" (if deposit? "text-ho-info" "text-ho-warn")]}
       (if deposit? "Deposit" "Withdraw")]
      [:div {:class ["text-xs" "text-ho-text-muted"]}
       (str (format-date time-ms)
            (when-not current-position? " · earlier position"))]]
     [:div {:class ["text-right"]}
      [:div {:class ["num" "text-sm" "text-trading-text"]}
       (format-signed-currency (if deposit? amount (- amount)))]
      [:div {:class ["num" "text-xs" "text-ho-text-muted"]}
       (cond
         (and deposit? (number? vault-return-since-pct))
         [:span {:class [(tone-class vault-return-since-pct)]}
          (str "≈ vault " (vf/format-percent vault-return-since-pct) " since")]

         (and (not deposit?) (number? realized))
         (str "realized " (format-signed-currency realized)
              (when (and (number? commission) (pos? commission))
                (str " · fee " (vf/format-currency commission))))

         :else "")]]]))

(def ^:private visible-transfer-count
  6)

(defn- transfers-section
  [{:keys [ledger-status transfers]}]
  [:div {:class ["min-w-0"] :data-role "vault-position-transfers"}
   [:div {:class ["mb-1" "flex" "items-baseline" "justify-between" "gap-3"]}
    [:div {:class ["text-xs" "uppercase" "tracking-[0.08em]" "text-ho-text-muted"]}
     "Your deposits & withdrawals"]
    [:div {:class ["hidden" "text-xs" "text-ho-text-muted" "sm:block"]}
     (when (seq transfers) "Vault return since each deposit is approximate")]]
   (case ledger-status
     :loading
     [:div {:class ["space-y-2" "py-2"] :data-role "vault-position-transfers-loading"}
      (vf/loading-skeleton-block ["h-3" "w-48"])
      (vf/loading-skeleton-block ["h-3" "w-32"])]

     :error
     [:div {:class ["py-2" "text-sm" "text-ho-text-muted"]}
      "Couldn't load your transfer history. Totals above come straight from Hyperliquid."]

     (if (seq transfers)
       (let [row-key (fn [transfer]
                       (str "vault-position-transfer-" (name (:kind transfer)) "-" (:time-ms transfer) "-" (:hash transfer)))
             [shown hidden] (split-at visible-transfer-count transfers)]
         [:div
          (for [transfer shown]
            ^{:key (row-key transfer)}
            (transfer-row transfer))
          (when (seq hidden)
            ;; Native disclosure: no UI state to own, and a stable key keeps it
            ;; open across re-renders.
            [:details {:class ["group"]
                       :replicant/key "vault-position-older-transfers"
                       :data-role "vault-position-older-transfers"}
             [:summary {:class ["cursor-pointer" "list-none" "border-t" "border-ho-border-accent-muted"
                                "py-2.5" "text-sm" "text-ho-accent-hi" "hover:underline"]}
              [:span {:class ["group-open:hidden"]} (str "Show " (count hidden) " older transfers")]
              [:span {:class ["hidden" "group-open:inline"]} "Hide older transfers"]]
             (for [transfer hidden]
               ^{:key (row-key transfer)}
               (transfer-row transfer))])])
       [:div {:class ["py-2" "text-sm" "text-ho-text-muted"]}
        "No transfers found."]))])

(defn- band-title
  [{:keys [spectating? viewer-address]} own-label]
  (if spectating?
    [:span own-label " of "
     [:span {:class ["num" "text-ho-info"]} (wallet/short-addr viewer-address)]]
    own-label))

(defn- open-band
  [{:keys [value cost-basis unrealized unrealized-pct realized all-time-earned
           position-start-ms position-start-exact? first-deposit-ms days-held
           vault-return-since-start-pct composition transfer-count]
    :as position}]
  [:section {:id "your-position"
             :class (into band-surface ["px-4" "py-4" "lg:px-6" "space-y-4"])
             :data-role "vault-position-band"}
   [:div {:class ["flex" "flex-wrap" "items-center" "justify-between" "gap-3"]}
    [:div {:class ["flex" "flex-wrap" "items-center" "gap-x-3" "gap-y-1"]}
     [:h2 {:class ["text-lg" "font-semibold" "text-trading-text"]}
      (band-title position "Your position")]
     [:span {:class ["text-sm" "text-ho-text-muted"]}
      (str (if position-start-exact? "Since " "In this vault since ")
           (format-date position-start-ms)
           (cond
             (= 0 days-held) " · opened today"
             (= 1 days-held) " · 1 day"
             (number? days-held) (str " · " days-held " days"))
           (when (pos? transfer-count) (str " · " transfer-count (if (= 1 transfer-count) " transfer" " transfers"))))]]
    (status-chip position)]
   [:div {:class ["grid" "grid-cols-2" "border-y" "border-ho-border-accent" "sm:grid-cols-3" "xl:grid-cols-5"]}
    (stat {:label "Current value"
           :value (vf/format-currency value)
           :role "vault-position-value"
           :first? true})
    (stat {:label "Cost basis"
           :value (vf/format-currency cost-basis)
           :detail "net deposited, current position"
           :role "vault-position-cost-basis"})
    (stat {:label "P&L since deposit"
           :value (format-signed-currency unrealized)
           :tone (tone-class unrealized)
           :detail (when (number? unrealized-pct)
                     (str (vf/format-percent unrealized-pct) " on cost basis"))
           :role "vault-position-unrealized"})
    (stat {:label "All-time earned"
           :value (format-signed-currency all-time-earned)
           :tone (tone-class all-time-earned)
           :detail (when (and (number? realized) (not (zero? (js/Math.round (* 100 realized)))))
                     (str (format-signed-currency realized) " realized via withdrawals"))
           :role "vault-position-all-time"})
    (stat {:label "Vault, same period"
           :value (vf/format-percent vault-return-since-start-pct)
           :tone (tone-class vault-return-since-start-pct)
           :detail (if (number? vault-return-since-start-pct)
                     (str "≈ vault return since " (format-date position-start-ms))
                     "shown after the first day")
           :role "vault-position-vault-return"})]
   (when composition
     [:div {:class ["space-y-2"]}
      (composition-bar composition)
      [:div {:class ["flex" "flex-wrap" "gap-x-6" "gap-y-1" "text-sm" "text-ho-text-secondary"]}
       (legend-item "bg-ho-accent-soft-hi" "Cost basis" (vf/format-currency cost-basis))
       (legend-item (if (= :loss (:direction composition)) "bg-ho-sell" "bg-ho-buy")
                    (if (= :loss (:direction composition)) "Unrealized loss" "Unrealized gain")
                    (format-signed-currency unrealized))
       (when (and (number? first-deposit-ms)
                  (number? position-start-ms)
                  (< first-deposit-ms position-start-ms))
         [:span {:class ["text-ho-text-muted"]}
          (str "First ever deposit " (format-date first-deposit-ms))])]])
   (if (:series position)
     [:div {:class ["grid" "gap-x-8" "gap-y-5" "xl:grid-cols-[minmax(0,3fr)_minmax(320px,2fr)]"]}
      (position-chart/position-chart (:series position))
      (transfers-section position)]
     (transfers-section position))])

(defn- closed-band
  [{:keys [all-time-earned] :as position}]
  [:section {:class (into band-surface ["px-4" "py-4" "lg:px-6" "space-y-3"])
             :data-role "vault-position-band"}
   [:div {:class ["flex" "flex-wrap" "items-center" "justify-between" "gap-3"]}
    [:h2 {:class ["text-lg" "font-semibold" "text-trading-text"]}
     (band-title position "Past position")]
    [:span {:class ["rounded-full" "border" "border-ho-border-accent" "px-2.5" "py-1" "text-xs" "text-ho-text-muted"]}
     "Fully withdrawn"]]
   [:div {:class ["num" "text-[22px]" "font-semibold" (tone-class all-time-earned)]}
    (str (format-signed-currency all-time-earned) " earned")]
   (transfers-section position)])

(defn- empty-band
  [{:keys [spectating?]} {:keys [can-open-deposit?]} vault-address]
  [:section {:class (into band-surface ["flex" "flex-wrap" "items-center" "justify-between" "gap-3"
                                        "px-4" "py-3.5" "lg:px-6"])
             :data-role "vault-position-band"}
   [:div
    [:h2 {:class ["text-base" "font-semibold" "text-trading-text"]}
     (if spectating?
       "This account has no deposit in this vault"
       "You haven't deposited into this vault")]
    [:p {:class ["mt-0.5" "text-sm" "text-ho-text-muted"]}
     "Once there is a deposit, this tracks its value, cost basis and return from the day it went in."]]
   (when (and (not spectating?) (true? can-open-deposit?))
     (transfer-modal/hero-transfer-button {:label "Deposit"
                                           :enabled? true
                                           :action [:actions/open-vault-transfer-modal
                                                    vault-address
                                                    :deposit]}))])

(defn position-band
  "Nil when there is no viewer address to report on."
  [position vault-transfer vault-address]
  (when (:viewer-address position)
    (case (:status position)
      :open (open-band position)
      :closed (closed-band position)
      (empty-band position vault-transfer vault-address))))

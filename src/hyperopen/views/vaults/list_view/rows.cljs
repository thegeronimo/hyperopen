(ns hyperopen.views.vaults.list-view.rows
  (:require [clojure.string :as str]
            [hyperopen.account.spectate-mode-links :as spectate-mode-links]
            [hyperopen.wallet.core :as wallet]
            [hyperopen.views.vaults.list-view.format :as format]))

(defn- vault-detail-route
  [vault-address]
  (str "/vaults/" vault-address))

(defn- normalize-series [series]
  (let [values (if (sequential? series)
                 (->> series
                      (keep #(when (number? %) %))
                      vec)
                 [])]
    (if (seq values)
      values
      [0 0])))

(def ^:private memoized-sparkline-model
  (memoize
   (fn [series width height]
     (let [values (normalize-series series)
           start-value (first values)
           end-value (last values)
           positive? (>= end-value start-value)
           stroke (if positive?
                    "#36e1d3"
                    "#ff6b8a")
           min-value (apply min values)
           max-value (apply max values)
           value-span (max 0.000001 (- max-value min-value))
           step-x (/ width (max 1 (dec (count values))))
           path (->> values
                     (map-indexed (fn [idx value]
                                    (let [x (* idx step-x)
                                          normalized (/ (- value min-value) value-span)
                                          y (* (- 1 normalized) height)]
                                      (str (if (zero? idx) "M" "L")
                                           (.toFixed x 2)
                                           ","
                                           (.toFixed y 2)))))
                     (str/join " "))]
       {:path path
        :stroke stroke}))))

(defn- snapshot-sparkline [series]
  (let [{:keys [path stroke]} (memoized-sparkline-model series 80 28)]
    [:svg {:class ["h-7" "w-20"]
           :viewBox "0 0 80 28"
           :preserveAspectRatio "none"
           :aria-label "Vault snapshot trend"}
     [:path {:d path
             :fill "none"
             :stroke stroke
             :stroke-width 2
             :stroke-linecap "round"
             :stroke-linejoin "round"
             :vector-effect "non-scaling-stroke"}]]))

(defn vault-row
  [state
   {:keys [name vault-address leader apr tvl your-deposit age-days snapshot-series is-closed?]}]
  [:tr {:class ["border-b"
                "border-base-300/50"
                "text-sm"
                "text-trading-text"
                "hover:bg-base-200/50"]
        :data-role "vault-row"}
   [:td {:class ["px-3" "py-2.5"]}
    [:a {:href (spectate-mode-links/internal-route-href state (vault-detail-route vault-address))
         :class (into ["block"
                       "w-full"
                       "text-left"]
                      format/focus-visible-only-ring-classes)
         :data-role "vault-row-link"}
     [:div {:class ["flex" "items-center" "gap-2"]}
      [:span {:class ["truncate" "font-semibold" "text-trading-text"]} name]
      (when is-closed?
        [:span {:class ["rounded"
                        "border"
                        "border-amber-600/40"
                        "px-1.5"
                        "py-0.5"
                        "text-xs"
                        "uppercase"
                        "tracking-wide"
                        "text-amber-300"]}
         "Closed"])]]]
   [:td {:class ["px-3" "py-2.5" "num" "text-trading-text"]}
    (wallet/short-addr leader)]
   [:td {:class (into ["px-3" "py-2.5" "num"]
                      (format/percent-text-class apr))}
    (format/format-percent apr)]
   [:td {:class ["px-3" "py-2.5" "num" "text-trading-text"]}
    (format/format-currency tvl)]
   [:td {:class ["px-3" "py-2.5" "num" "text-trading-text"]}
    (format/format-currency your-deposit)]
   [:td {:class ["px-3" "py-2.5" "num" "text-trading-text"]}
    (format/format-age age-days)]
   [:td {:class ["px-3" "py-2.5" "text-right"]}
    (snapshot-sparkline snapshot-series)]])

(defn mobile-vault-card
  [state
   {:keys [name vault-address leader apr tvl your-deposit age-days snapshot-series]}]
  [:a {:href (spectate-mode-links/internal-route-href state (vault-detail-route vault-address))
       :data-role "vault-mobile-card"
       :class (into ["block"
                     "w-full"
                     "rounded-lg"
                     "border"
                     "border-base-300/70"
                     "bg-base-100/68"
                     "px-3"
                     "py-2.5"
                     "text-left"
                     "transition-colors"
                     "hover:bg-base-200"]
                    format/focus-visible-only-ring-classes)}
   [:div {:class ["min-w-0" "space-y-0.5"]}
    [:div {:class ["flex" "items-start" "justify-between" "gap-3"]}
     [:div {:class ["min-w-0" "flex-1"]}
      [:div {:class ["truncate" "font-semibold" "text-trading-text"]} name]
      [:div {:class ["num" "text-xs" "text-trading-text-secondary"]}
       (wallet/short-addr vault-address)]]
     [:div {:class ["text-right" "text-xs" "uppercase" "tracking-[0.08em]" "text-trading-text-secondary"]}
      "Vault"]]]
   [:div {:class ["mt-2.5" "space-y-2" "text-xs"]}
    [:div {:class ["grid" "grid-cols-[auto_1fr]" "items-center" "gap-2"]}
     [:span {:class ["text-trading-text-secondary"]} "Leader"]
     [:span {:class ["justify-self-end" "num" "text-trading-text"]} (wallet/short-addr leader)]]
    [:div {:class ["grid" "grid-cols-[auto_1fr]" "items-center" "gap-2"]}
     [:span {:class ["text-trading-text-secondary"]} "APR"]
     [:span {:class (into ["justify-self-end" "num"] (format/percent-text-class apr))} (format/format-percent apr)]]
    [:div {:class ["grid" "grid-cols-[auto_1fr]" "items-center" "gap-2"]}
     [:span {:class ["text-trading-text-secondary"]} "TVL"]
     [:span {:class ["justify-self-end" "num" "text-trading-text"]} (format/format-currency tvl)]]
    [:div {:class ["grid" "grid-cols-[auto_1fr]" "items-center" "gap-2"]}
     [:span {:class ["text-trading-text-secondary"]} "Your Deposit"]
     [:span {:class ["justify-self-end" "num" "text-trading-text"]} (format/format-currency your-deposit)]]
    [:div {:class ["grid" "grid-cols-[auto_1fr]" "items-center" "gap-2"]}
     [:span {:class ["text-trading-text-secondary"]} "Age (days)"]
     [:span {:class ["justify-self-end" "num" "text-trading-text"]} (format/format-age age-days)]]
    [:div {:class ["grid" "grid-cols-[auto_1fr]" "items-center" "gap-2"]}
     [:span {:class ["text-trading-text-secondary"]} "Snapshot"]
     [:div {:class ["justify-self-end"]}
      (snapshot-sparkline snapshot-series)]]]])

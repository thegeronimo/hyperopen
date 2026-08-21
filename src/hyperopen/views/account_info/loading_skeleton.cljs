(ns hyperopen.views.account-info.loading-skeleton
  "Pending and failed states for an account tab whose code chunk is still on the
   wire.

   Deliberately requires nothing from views/account-info/tabs/**: every tab body
   except Balances lives in a lazily-loaded chunk, and requiring one from here
   would promote it into :account_surfaces and collapse the code split. That
   failure is silent -- it shows up as a larger account_surfaces.js, not as a
   compile error -- so keep this namespace dependency-free.

   The consequence is that these states are tab-agnostic: they name the tab and
   reserve its height, but do not reproduce its column grid.")

(defn- skeleton-block
  [extra-classes]
  [:span {:class (into ["block"
                        "h-3.5"
                        "rounded"
                        "ui-loading-shimmer"]
                       extra-classes)}])

(def ^:private skeleton-row-widths
  [["w-24" "w-16" "w-28" "w-20" "w-16" "w-24"]
   ["w-20" "w-16" "w-32" "w-16" "w-20" "w-20"]
   ["w-28" "w-20" "w-24" "w-24" "w-16" "w-16"]
   ["w-16" "w-16" "w-28" "w-20" "w-24" "w-20"]])

(defn- skeleton-row
  [idx widths]
  [:div {:class ["grid"
                 "grid-cols-2"
                 "gap-3"
                 "border-b"
                 "border-base-300/40"
                 "px-3"
                 "py-3"
                 "sm:grid-cols-4"
                 "lg:grid-cols-6"]
         :data-index idx}
   (for [[col width] (map-indexed vector widths)]
     ^{:key (str "account-tab-loading-cell-" idx "-" col)}
     (skeleton-block [width]))])

(defn lazy-tab-loading-state
  "Pending state shown while an account tab's code chunk downloads.

   tab-label is the display label already resolved through any route-specific
   overrides, so the headline reads \"Loading Interest...\" on the portfolio
   route where Funding History is renamed. The region is announced politely
   rather than assertively: the wait is normally a few tens of milliseconds and
   should not interrupt whatever the reader is doing."
  [{:keys [tab-label]}]
  (let [label (or tab-label "account data")]
    [:div {:class ["space-y-3" "p-4"]
           :data-role "account-tab-loading"
           :role "status"
           :aria-live "polite"
           :aria-busy "true"}
     [:div {:class ["flex" "items-center" "gap-2" "text-xs" "text-trading-text-secondary"]}
      [:span {:class ["h-2" "w-2" "rounded-full" "bg-emerald-300" "animate-pulse"]
              :aria-hidden true}]
      [:span (str "Loading " label "...")]]
     [:div {:class ["overflow-hidden" "rounded-lg" "border" "border-base-300/60"]}
      (for [[idx widths] (map-indexed vector skeleton-row-widths)]
        ^{:key (str "account-tab-loading-row-" idx)}
        (skeleton-row idx widths))]]))

(defn lazy-tab-error-state
  "Failed-chunk state. retry-actions is an action vector re-dispatched when the
   reader presses Retry; the caller supplies it so this namespace stays free of
   action wiring."
  [{:keys [tab-label message retry-actions]}]
  (let [label (or tab-label "this tab")]
    [:div {:class ["space-y-3" "px-4" "py-6"]
           :data-role "account-tab-error"
           :role "alert"}
     [:div {:class ["text-sm" "font-medium" "text-trading-text"]}
      (str "Couldn't load " label ".")]
     [:p {:class ["text-sm" "text-trading-text-secondary"]}
      (or message "The connection may have dropped, or a new version was deployed while this page was open.")]
     (when (seq retry-actions)
       [:button {:type "button"
                 :class ["rounded-lg"
                         "border"
                         "border-base-300"
                         "bg-base-200"
                         "px-3"
                         "py-2"
                         "text-sm"
                         "font-medium"
                         "text-trading-text"
                         "transition-colors"
                         "hover:bg-base-300"]
                 :data-role "account-tab-error-retry"
                 :on {:click retry-actions}}
        "Retry"])]))

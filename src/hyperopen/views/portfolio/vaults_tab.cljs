(ns hyperopen.views.portfolio.vaults-tab
  "Portfolio account-table panel for the Vaults tab: headline cards, the vault
  value chart and the per-vault table. Figures arrive as display strings from
  `hyperopen.views.portfolio.vm.vaults-tab`."
  (:require [hyperopen.account.spectate-mode-links :as spectate-mode-links]
            [hyperopen.views.portfolio.low-confidence :as low-confidence]
            [hyperopen.views.portfolio.vaults-tab-chart :as chart-view]
            [hyperopen.views.portfolio.vm.vaults-tab :as vm]))

(def ^:private load-action
  [:actions/load-portfolio-vault-attribution])

(defn- tone-classes
  [tone]
  (case tone
    :positive ["text-success"]
    :negative ["text-error"]
    :none ["text-trading-text-secondary"]
    ["text-trading-text"]))

(defn- figure
  "Value text in its tone, with the `~` mark when estimated."
  [{:keys [text tone estimated?]} data-role]
  (into [:span {:class (into ["inline-flex" "items-baseline" "gap-1"] (tone-classes tone))}
         text]
        (when (and estimated? (not= "--" text))
          [(low-confidence/estimated-mark (str data-role "-estimated-mark"))])))

(defn- card
  [accent? data-role label {:keys [value detail]}]
  [:div {:class (into ["flex" "min-w-[12rem]" "flex-1" "flex-col" "gap-1.5" "rounded-xl" "border" "px-4" "py-3"]
                      (if accent?
                        ["border-ho-border-accent" "bg-ho-bg-deep"]
                        ["border-ho-border" "bg-ho-surface"]))
         :data-role data-role}
   [:div {:class ["text-xs" "text-trading-text-secondary"]} label]
   [:div {:class ["num" "truncate" "text-xl" "font-semibold" "leading-tight"]
          :data-role (str data-role "-value")}
    (figure value data-role)]
   [:div {:class ["num" "text-xs" "leading-4" "text-trading-text-secondary"]} detail]])

(defn- cards-row
  [{:keys [pnl money-weighted time-weighted equity]}]
  [:div {:class ["flex" "flex-wrap" "gap-3"]
         :data-role "portfolio-vaults-cards"}
   (card true "portfolio-vaults-card-pnl" "Vault PNL" pnl)
   (card false "portfolio-vaults-card-mwr" "Money-weighted return" money-weighted)
   (card false "portfolio-vaults-card-twr" "Time-weighted return" time-weighted)
   (card false "portfolio-vaults-card-equity" "Vault equity" equity)])

(def ^:private header-classes
  ["px-3" "py-2.5" "text-xs" "font-medium" "uppercase" "tracking-wide" "text-trading-text-secondary"])

(def ^:private columns
  "`[label right-aligned? help]`; `help` is the hover explanation, when the
   heading needs one."
  [["Vault" false] ["Status" false] ["Deposited" true] ["Withdrawn" true] ["Value" true]
   ["PNL" true]
   ["Money-weighted" true
    "Your yearly rate of return on the dollars you put in. It counts how much you had in the vault and when, so a deposit made just before a drop weighs more than one made after it. Shown as -- for positions held under 30 days."]
   ["Vault return while held" true
    "How the vault itself performed over the time you were in it, as if you had put in one deposit and left it alone. It ignores the size and timing of your deposits and is not annualised. Marked ~ because it is estimated from the vault's sampled history."]
   ["Held" true]])

(defn- help-id
  [label]
  (str "portfolio-vaults-help-" (.toLowerCase (.replace label (js/RegExp. "\\s+" "g") "-"))))

(defn- header-cell
  "A column heading. With `help`, the label takes focus and shows the
   explanation on hover or focus; the panel hangs from the heading's right edge
   so it stays inside the table."
  [[label right? help]]
  [:th {:replicant/key label
        :scope "col"
        :class (into header-classes
                     [(if right? "text-right" "text-left")
                      "bg-base-200" "first:rounded-tl-xl" "last:rounded-tr-xl"])}
   (if-not help
     label
     [:span {:class ["group" "relative" "inline-block" "cursor-help" "underline" "decoration-dotted"
                     "underline-offset-4" "outline-none" "focus-visible:text-trading-text"]
             :tabindex 0
             :aria-describedby (help-id label)
             :data-role (str (help-id label) "-trigger")}
      label
      [:span {:id (help-id label)
              :role "tooltip"
              :data-role (help-id label)
              :class ["pointer-events-none" "absolute" "right-0" "top-full" "z-30" "mt-2" "block" "w-72"
                      "rounded-lg" "border" "border-base-300" "bg-gray-800" "px-3" "py-2.5" "text-left"
                      "text-xs" "font-normal" "normal-case" "leading-5" "tracking-normal" "text-gray-100"
                      "whitespace-normal" "opacity-0" "transition-opacity" "duration-100"
                      "group-hover:opacity-100" "group-focus:opacity-100"]}
       help]])])

(defn- num-cell
  ([content]
   (num-cell content nil))
  ([content data-role]
   [:td (cond-> {:class ["num" "px-3" "py-2.5" "text-right" "text-sm" "text-trading-text"]}
          data-role (assoc :data-role data-role))
    content]))

(defn- vault-cell
  [state {:keys [name route incomplete-note]}]
  [:td {:class ["px-3" "py-2.5" "text-sm"]}
   (into [:div {:class ["flex" "flex-col"]}
          [:a {:href (spectate-mode-links/internal-route-href state route)
               :class ["font-medium" "text-trading-text" "hover:underline"]
               :data-role "portfolio-vaults-vault-link"}
           name]]
         (when incomplete-note
           [[:span {:class ["text-xs" "text-trading-text-secondary"]
                    :data-role "portfolio-vaults-incomplete-note"}
             incomplete-note]]))])

(defn- vault-row
  [state {:keys [key open? status-label deposited withdrawn value pnl money-weighted while-held held]
          :as row}]
  [:tr {:replicant/key key
        :class ["border-b" "border-base-300"]
        :data-role "portfolio-vaults-row"}
   (vault-cell state row)
   [:td {:class ["px-3" "py-2.5" "text-sm" (if open? "text-success" "text-trading-text-secondary")]
         :data-role "portfolio-vaults-status"}
    status-label]
   (num-cell deposited)
   (num-cell withdrawn)
   (num-cell value)
   (num-cell (figure pnl "portfolio-vaults-row-pnl") "portfolio-vaults-row-pnl")
   (num-cell (figure money-weighted "portfolio-vaults-row-mwr"))
   (num-cell (figure while-held "portfolio-vaults-row-held-return"))
   (num-cell held)])

(defn- total-row
  "The sum over all vaults: the first body row, set apart from the vault rows
   by a label in header style, a tinted background and a heavier rule."
  [{:keys [deposited withdrawn value pnl money-weighted while-held]}]
  [:tr {:replicant/key "total"
        :class ["border-b-2" "border-base-300" "bg-base-200" "font-semibold"]
        :data-role "portfolio-vaults-total-row"}
   [:th {:scope "row"
         :class ["px-3" "py-3" "text-left" "text-xs" "font-semibold" "uppercase" "tracking-wide"
                 "text-trading-text-secondary"]}
    "Total · All vaults"]
   [:td {:class ["px-3" "py-2.5"]} ""]
   (num-cell deposited)
   (num-cell withdrawn)
   (num-cell value)
   (num-cell (figure pnl "portfolio-vaults-total-pnl") "portfolio-vaults-total-pnl")
   (num-cell (figure money-weighted "portfolio-vaults-total-mwr"))
   (num-cell (figure while-held "portfolio-vaults-total-held-return"))
   (num-cell "")])

(defn- table
  [state {:keys [rows total]}]
  [:div {:class ["min-h-[13rem]" "overflow-x-auto"]
         :data-role "portfolio-vaults-table-wrap"}
   [:div {:class ["min-w-[56rem]" "rounded-xl" "border" "border-base-300"]}
    [:table {:class ["w-full" "border-collapse"]
             :data-role "portfolio-vaults-table"}
     [:thead
      (into [:tr] (map header-cell) columns)]
     (into [:tbody]
           (cons (total-row total)
                 (map #(vault-row state %) rows)))]]])

(defn- muted-line
  [data-role text]
  [:div {:class ["text-xs" "text-trading-text-secondary"]
         :data-role data-role}
   text])

(defn- centered
  [data-role children]
  (into [:div {:class ["flex" "min-h-[12rem]" "flex-col" "items-center" "justify-center" "gap-3" "text-center"]
               :data-role data-role}]
        children))

(defn- refresh-error-line
  [text]
  [:div {:class ["flex" "items-center" "gap-2" "text-xs" "text-trading-text-secondary"]
         :data-role "portfolio-vaults-refresh-error"
         :role "status"}
   [:span text]
   [:button {:type "button"
             :class ["btn" "btn-xs" "btn-ghost"]
             :data-role "portfolio-vaults-refresh-retry"
             :on {:click [load-action]}}
    "Retry"]])

(defn- ready-body
  [state {:keys [cards chart footnote partial-note excluded-note refresh-error refreshing?]
          :as model}]
  (into [:div {:class ["flex" "flex-col" "gap-4"]
               :data-role "portfolio-vaults-ready"
               :aria-busy (boolean refreshing?)}]
        (concat
         (when refresh-error [(refresh-error-line refresh-error)])
         [(cards-row cards)]
         (when chart [(chart-view/chart chart)])
         (when footnote
           [[:div {:class ["flex" "items-baseline" "gap-1" "text-xs" "text-trading-text-secondary"]
                   :data-role "portfolio-vaults-footnote"}
             (low-confidence/estimated-mark "portfolio-vaults-footnote-mark")
             [:span footnote]]])
         (when partial-note
           [(muted-line "portfolio-vaults-partial" partial-note)])
         (when excluded-note
           [(muted-line "portfolio-vaults-excluded" excluded-note)])
         [(table state model)])))

(defn vaults-tab
  [state]
  (let [{:keys [status] :as model} (vm/vaults-tab-model state)]
    [:div {:class ["h-full" "min-h-0" "overflow-y-auto" "scrollbar-hide" "px-4" "py-3"]
           :data-role "portfolio-vaults-tab"}
     (case status
       :none (centered "portfolio-vaults-none"
                       [[:span {:class ["text-sm" "text-trading-text-secondary"]}
                         "Connect a wallet or open a trader to see vault returns."]])
       :loading (centered "portfolio-vaults-loading"
                          [[:span {:class ["ho-spinner" "ho-spinner-lg"] :aria-hidden true}]
                           [:span {:class ["text-sm" "text-trading-text-secondary"]
                                   :role "status"}
                            "Loading vault history…"]])
       :error (centered "portfolio-vaults-error"
                        [[:span {:class ["text-sm" "text-trading-text-secondary"]}
                          "Vault history could not be loaded."]
                         [:button {:type "button"
                                   :class ["btn" "btn-sm" "btn-outline"]
                                   :data-role "portfolio-vaults-retry"
                                   :on {:click [load-action]}}
                          "Retry"]])
       :empty (centered "portfolio-vaults-empty"
                        [[:span {:class ["text-sm" "text-trading-text-secondary"]}
                          "No vault deposits found for this account."]])
       (ready-body state model))]))

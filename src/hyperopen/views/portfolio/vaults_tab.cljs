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
  [["Vault" false] ["Status" false] ["Deposited" true] ["Withdrawn" true] ["Value" true]
   ["PNL" true] ["Money-weighted" true] ["Vault return while held" true] ["Held" true]])

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
  [{:keys [deposited withdrawn value pnl money-weighted while-held]}]
  [:tr {:replicant/key "total"
        :class ["bg-base-200" "font-medium"]
        :data-role "portfolio-vaults-total-row"}
   [:td {:class ["px-3" "py-2.5" "text-sm" "text-trading-text"]} "All vaults"]
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
  [:div {:class ["overflow-x-auto" "rounded-xl" "border" "border-base-300"]
         :data-role "portfolio-vaults-table-wrap"}
   [:table {:class ["w-full" "min-w-[56rem]" "border-collapse"]
            :data-role "portfolio-vaults-table"}
    [:thead {:class ["bg-base-200"]}
     (into [:tr]
           (map (fn [[label right?]]
                  [:th {:replicant/key label
                        :class (conj header-classes (if right? "text-right" "text-left"))}
                   label]))
           columns)]
    (into [:tbody]
          (concat (map #(vault-row state %) rows)
                  [(total-row total)]))]])

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

(ns hyperopen.workbench.scenes.shell.shell-scenes
  (:require [portfolio.replicant :as portfolio]
            [hyperopen.workbench.support.fixtures :as fixtures]
            [hyperopen.workbench.support.layout :as layout]
            [hyperopen.workbench.support.state :as ws]
            [hyperopen.views.footer.build-badge :as build-badge]
            [hyperopen.views.footer-view :as footer-view]
            [hyperopen.views.header-view :as header-view]
            [hyperopen.views.notifications-view :as notifications-view]
            [hyperopen.views.trade-confirmation-toasts :as trade-toasts]))

(portfolio/configure-scenes
  {:title "Shell"
   :collection :shell})

(defn- shell-state
  [overrides]
  (ws/build-state
   {:router {:path "/trade"}
    :wallet {:connected? true
             :address "0x4b20993bc481177ec7e8f571cecae8a9e22c02db"
             :connecting? false
             :copy-feedback nil
             :agent {:status :ready
                     :enabled? true
                     :storage-mode :local}}
    :websocket {:health (fixtures/footer-health)}
    :trade-ui {:mobile-surface :chart}}
   overrides))

(defn- shell-reducers
  []
  {:actions/open-mobile-header-menu
   (fn [state _dispatch-data]
     (assoc-in state [:header-ui :mobile-menu-open?] true))

   :actions/close-mobile-header-menu
   (fn [state _dispatch-data]
     (assoc-in state [:header-ui :mobile-menu-open?] false))

   :actions/navigate
   (fn [state _dispatch-data route]
     (assoc-in state [:router :path] route))

   :actions/navigate-mobile-header-menu
   (fn [state _dispatch-data route]
     (-> state
         (assoc-in [:router :path] route)
         (assoc-in [:header-ui :mobile-menu-open?] false)))

   :actions/toggle-ws-diagnostics
   (fn [state _dispatch-data]
     (update-in state [:websocket-ui :diagnostics-open?] not))

   :actions/select-trade-mobile-surface
   (fn [state _dispatch-data surface]
     (assoc-in state [:trade-ui :mobile-surface] surface))

   :actions/dismiss-order-feedback-toast
   (fn [state _dispatch-data toast-id]
     (update-in state [:ui :toasts]
                (fn [toasts]
                  (vec (remove #(= toast-id (:id %)) toasts)))))

   :actions/expand-order-feedback-toast
   (fn [state _dispatch-data toast-id]
     (update-in state [:ui :toasts]
                (fn [toasts]
                  (mapv #(cond-> %
                           (= toast-id (:id %)) (assoc :expanded? true))
                        toasts))))

   :actions/collapse-order-feedback-toast
   (fn [state _dispatch-data toast-id]
     (update-in state [:ui :toasts]
                (fn [toasts]
                  (mapv #(cond-> %
                           (= toast-id (:id %)) (assoc :expanded? false))
                        toasts))))})

(defonce trade-header-store
  (ws/create-store ::trade-header
                   (shell-state {})))

(defonce vaults-header-store
  (ws/create-store ::vaults-header
                   (shell-state {:router {:path "/vaults"}})))

(defonce mobile-header-store
  (ws/create-store ::mobile-header
                   (shell-state {:router {:path "/funding-comparison"}
                                 :header-ui {:mobile-menu-open? true}})))

(defonce footer-store
  (ws/create-store ::footer
                   (shell-state {})))

(defonce diagnostics-footer-store
  (ws/create-store ::diagnostics-footer
                   (shell-state {:websocket-ui {:diagnostics-open? true
                                                :show-surface-freshness-cues? true
                                                :diagnostics-timeline [{:event :connected
                                                                        :at-ms 1762790400000}]}})))

(defonce notifications-store
  (ws/create-store ::notifications
                   (shell-state {:ui {:toasts [{:id "order-success"
                                                :kind :success
                                                :headline "Order submitted"
                                                :subline "Limit buy 0.05 BTC at 101,950.00"}
                                               {:id "withdrawal-error"
                                                :kind :error
                                                :headline "Withdrawal failed"
                                                :subline "Address checksum does not match network."}]}})))

(defn- fill
  [id side symbol qty price offset-ms]
  {:id id
   :side side
   :symbol symbol
   :qty qty
   :price price
   :orderType "limit"
   :ts (- 1800000000000 offset-ms)})

(def sample-pill-fill
  (fill "pill-1" :sell "HYPE" 0.26 44.598 0))

(def sample-detailed-fill
  (assoc (fill "detail-1" :buy "HYPE" 4.23 44.265 0)
         :orderType "market"
         :slippagePct -0.02))

(def sample-stack-fills
  [(fill "stack-1" :buy "HYPE" 0.25 44.27 0)
   (fill "stack-2" :buy "HYPE" 0.31 44.29 3500)
   (fill "stack-3" :sell "SOL" 4.9 198.18 7000)
   (fill "stack-4" :buy "BTC" 0.018 65124.50 9000)])

(def sample-consolidated-fills
  [(fill "consol-1" :buy "HYPE" 0.25 44.27 0)
   (fill "consol-2" :buy "HYPE" 0.34 44.29 2500)
   (fill "consol-3" :buy "HYPE" 0.29 44.24 5000)
   (fill "consol-4" :buy "HYPE" 0.37 44.31 7500)
   (fill "consol-5" :buy "HYPE" 0.28 44.26 9000)])

(def sample-blotter-fills
  (into sample-consolidated-fills
        [(fill "blotter-1" :sell "SOL" 4.1 198.10 2000)
         (fill "blotter-2" :sell "SOL" 5.2 198.27 5000)
         (fill "blotter-3" :buy "BTC" 0.018 65124.80 8300)]))

(defn- toast-reference-shell
  [title desc content]
  (layout/page-shell
   (layout/desktop-shell
    [:div {:class ["max-w-[520px]"]}
     [:div {:class ["rounded-[14px]"
                    "border"
                    "border-[rgba(70,150,150,0.15)]"
                    "bg-[rgba(10,32,34,0.55)]"
                    "p-5"
                    "backdrop-blur-[14px]"]}
      [:div {:class ["mb-4"]}
       [:div {:class ["font-mono"
                      "text-[10px]"
                      "uppercase"
                      "tracking-[0.1em]"
                      "text-[#2dd4bf]"]}
        desc]
       [:h2 {:class ["m-0" "text-sm" "font-medium" "text-white"]}
        title]]
      [:div {:class ["flex"
                     "min-h-[200px]"
                     "flex-col"
                     "items-end"
                     "justify-end"
                     "gap-2"
                     "rounded-[10px]"
                     "border"
                     "border-dashed"
                     "border-[rgba(70,150,150,0.2)]"
                     "bg-[linear-gradient(180deg,rgba(5,20,22,0.5),rgba(5,20,22,0.8))]"
                     "p-6"]}
       content]]])))

(defn- iso-ago
  [now-ms offset-ms]
  (.toISOString (js/Date. (- now-ms offset-ms))))

(defn- build-fixtures
  [now-ms]
  [{:title "Fresh prod build"
    :note "Deployed within 6h; shows the live pulse."
    :build {:sha "f18fbc2a3b00e4e324b39796ffdc1a9cd9cff7e619c"
            :short "f18fbc2"
            :branch "main"
            :message "perf(portfolio): memoize returns chart series"
            :deployed-at (iso-ago now-ms (+ (* 1 60 60 1000) (* 48 60 1000)))
            :env "prod"
            :region "global"}}
   {:title "Staging build"
    :note "Amber environment pill signals non-prod."
    :build {:sha "9b2ef51a8cc0d4e19bf832a04eebfc71c2a09a81d6"
            :short "9b2ef51"
            :branch "feat/twap-preview"
            :message "feat(trade): add TWAP slicing preview"
            :deployed-at (iso-ago now-ms (* 3 60 1000))
            :env "staging"
            :region "global"}}
   {:title "Aged build"
    :note "Live pulse is replaced with the stale marker."
    :build {:sha "a77c109dd23bf5e4419ac0f71e00a9bbc2d1e442a3"
            :short "a77c109"
            :branch "main"
            :message "fix(orderbook): prevent stale rows on ws reconnect"
            :deployed-at (iso-ago now-ms (* 25 60 60 1000))
            :env "prod"
            :region "global"}}])

(defn- build-card
  [now-ms {:keys [title note build]}]
  [:div {:class ["flex"
                 "min-h-[200px]"
                 "flex-col"
                 "gap-4"
                 "rounded-[8px]"
                 "border"
                 "border-base-300/70"
                 "bg-base-200/80"
                 "p-5"]}
   [:div {:class ["text-sm" "font-semibold" "text-trading-text"]} title]
   [:div {:class ["flex"
                  "min-h-[84px]"
                  "items-center"
                  "rounded-[8px]"
                  "border"
                  "border-dashed"
                  "border-base-300/80"
                  "bg-black/20"
                  "px-3"]}
    (build-badge/render {:build build
                         :now-ms now-ms})]
   [:p {:class ["m-0" "text-xs" "leading-5" "text-trading-text-secondary"]} note]])

(portfolio/defscene trade-header
  :params trade-header-store
  [store]
  (layout/page-shell
   (layout/interactive-shell
    store
    (shell-reducers)
    (layout/desktop-shell
     (layout/panel-shell
      (header-view/header-view @store))))))

(portfolio/defscene vaults-header
  :params vaults-header-store
  [store]
  (layout/page-shell
   (layout/interactive-shell
    store
    (shell-reducers)
    (layout/desktop-shell
     (layout/panel-shell
      (header-view/header-view @store))))))

(portfolio/defscene mobile-menu-open
  :params mobile-header-store
  [store]
  (layout/page-shell
   (layout/interactive-shell
    store
    (shell-reducers)
    (layout/mobile-shell
     (header-view/header-view @store)))))

(portfolio/defscene footer-connected
  :params footer-store
  [store]
  (layout/page-shell
   (layout/interactive-shell
    store
    (shell-reducers)
    [:div {:class ["min-h-[280px]" "pb-24"]}
     (footer-view/footer-view @store)])))

(portfolio/defscene footer-diagnostics-open
  :params diagnostics-footer-store
  [store]
  (layout/page-shell
   (layout/interactive-shell
    store
    (shell-reducers)
    [:div {:class ["min-h-[540px]" "pb-24"]}
     (footer-view/footer-view @store)])))

(portfolio/defscene condensed-build-badge-states
  []
  (let [now-ms (.now js/Date)]
    (layout/page-shell
     [:div {:class ["space-y-5" "p-6"]}
      [:div {:class ["space-y-1"]}
       [:h2 {:class ["m-0" "text-base" "font-semibold" "text-trading-text"]}
        "Condensed build badge"]
       [:p {:class ["m-0" "text-sm" "text-trading-text-secondary"]}
        "Fresh prod, staging, and aged states from the footer handoff."]]
      [:div {:class ["grid" "grid-cols-1" "gap-4" "lg:grid-cols-3"]}
       (for [fixture (build-fixtures now-ms)]
         ^{:key (:title fixture)}
         (build-card now-ms fixture))]])))

(portfolio/defscene notifications-stacked
  :params notifications-store
  [store]
  (layout/page-shell
   (layout/interactive-shell
    store
    (shell-reducers)
    [:div {:class ["min-h-[320px]"]}
     (notifications-view/notifications-view @store)])))

(portfolio/defscene toast-pill
  []
  (toast-reference-shell
   "PillToast"
   "01 · single fill"
   (trade-toasts/PillToast sample-pill-fill
                            {:on-dismiss [[:actions/dismiss-order-feedback-toast "pill"]]})))

(portfolio/defscene toast-detailed
  []
  (toast-reference-shell
   "DetailedToast"
   "02 · single fill + context"
   (trade-toasts/DetailedToast sample-detailed-fill
                                {:on-dismiss [[:actions/dismiss-order-feedback-toast "detailed"]]})))

(portfolio/defscene toast-stack
  []
  (toast-reference-shell
   "ToastStack"
   "03 · rapid fills · 2-3 pills"
   (trade-toasts/ToastStack sample-stack-fills
                             {:on-expand [[:actions/expand-order-feedback-toast "stack"]]
                              :on-dismiss [[:actions/dismiss-order-feedback-toast "stack"]]})))

(portfolio/defscene toast-consolidated
  []
  (toast-reference-shell
   "ConsolidatedToast"
   "04 · 4+ fills · auto-merge"
   (trade-toasts/ConsolidatedToast
    sample-consolidated-fills
    {:on-expand [[:actions/expand-order-feedback-toast "consolidated"]]
     :on-dismiss [[:actions/dismiss-order-feedback-toast "consolidated"]]})))

(portfolio/defscene toast-blotter
  []
  (toast-reference-shell
   "BlotterCard"
   "05 · expanded grouped view"
   (trade-toasts/BlotterCard
    sample-blotter-fills
    {:on-collapse [[:actions/collapse-order-feedback-toast "blotter"]]})))

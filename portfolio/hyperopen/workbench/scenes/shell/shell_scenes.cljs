(ns hyperopen.workbench.scenes.shell.shell-scenes
  (:require [portfolio.replicant :as portfolio]
            [hyperopen.workbench.support.fixtures :as fixtures]
            [hyperopen.workbench.support.layout :as layout]
            [hyperopen.workbench.support.state :as ws]
            [hyperopen.views.footer-view :as footer-view]
            [hyperopen.views.header-view :as header-view]
            [hyperopen.views.notifications-view :as notifications-view]))

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
                  (vec (remove #(= toast-id (:id %)) toasts)))))})

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

(portfolio/defscene notifications-stacked
  :params notifications-store
  [store]
  (layout/page-shell
   (layout/interactive-shell
    store
    (shell-reducers)
    [:div {:class ["min-h-[320px]"]}
     (notifications-view/notifications-view @store)])))

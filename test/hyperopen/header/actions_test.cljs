(ns hyperopen.header.actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.trading-settings :as trading-settings]
            [hyperopen.header.actions :as actions]))

(def ^:private action-vars
  {'open-header-settings (resolve 'hyperopen.header.actions/open-header-settings)
   'close-header-settings (resolve 'hyperopen.header.actions/close-header-settings)
   'handle-header-settings-keydown (resolve 'hyperopen.header.actions/handle-header-settings-keydown)
  'request-agent-storage-mode-change (resolve 'hyperopen.header.actions/request-agent-storage-mode-change)
   'confirm-agent-storage-mode-change (resolve 'hyperopen.header.actions/confirm-agent-storage-mode-change)
   'request-agent-local-protection-mode-change
   (resolve 'hyperopen.header.actions/request-agent-local-protection-mode-change)
   'set-fill-alerts-enabled (resolve 'hyperopen.header.actions/set-fill-alerts-enabled)
   'set-animate-orderbook-enabled (resolve 'hyperopen.header.actions/set-animate-orderbook-enabled)
   'set-fill-markers-enabled (resolve 'hyperopen.header.actions/set-fill-markers-enabled)
   'set-confirm-open-orders-enabled (resolve 'hyperopen.header.actions/set-confirm-open-orders-enabled)
   'set-confirm-close-position-enabled (resolve 'hyperopen.header.actions/set-confirm-close-position-enabled)})

(defn- resolve-action
  [sym]
  (get action-vars sym))

(deftest mobile-header-open-and-close-actions-save-deterministic-state-test
  (is (= [[:effects/save [:header-ui :mobile-menu-open?] true]]
         (actions/open-mobile-header-menu {})))
  (is (= [[:effects/save [:header-ui :mobile-menu-open?] false]]
         (actions/close-mobile-header-menu {}))))

(deftest navigate-mobile-header-menu-closes-before-route-transition-test
  (is (= [[:effects/save [:header-ui :mobile-menu-open?] false]
          [:effects/save [:router :path] "/trade"]
          [:effects/push-state "/trade"]
          [:effects/load-trade-chart-module]]
         (actions/navigate-mobile-header-menu {} "/trade"))))

(deftest open-spectate-mode-mobile-header-menu-closes-before-opening-modal-test
  (let [bounds {:left 10 :top 20 :right 30 :bottom 40 :width 20 :height 20}
        effects (actions/open-spectate-mode-mobile-header-menu
                 {:account-context {:spectate-ui {}
                                    :watchlist []}}
                 bounds)
        first-effect (first effects)
        second-effect (second effects)
        saved-path-values (second second-effect)]
    (is (= [:effects/save [:header-ui :mobile-menu-open?] false]
           first-effect))
    (is (= :effects/save-many (first second-effect)))
    (is (= true
           (some (fn [[path value]]
                   (and (= path [:account-context :spectate-ui :modal-open?])
                        (= true value)))
                 saved-path-values)))
    (is (= bounds
           (some (fn [[path value]]
                   (when (= path [:account-context :spectate-ui :anchor])
                     value))
                 saved-path-values)))))

(deftest header-settings-open-and-close-actions-save-deterministic-state-test
  (let [open-action (resolve-action 'open-header-settings)
        close-action (resolve-action 'close-header-settings)]
    (is (some? open-action))
    (is (some? close-action))
    (is (= [[:effects/save [:header-ui :settings-return-focus?] false]
            [:effects/save [:header-ui :settings-open?] true]]
           (when open-action
             (open-action {}))))
    (is (= [[:effects/save [:header-ui :settings-confirmation] nil]
            [:effects/save [:header-ui :settings-open?] false]
            [:effects/save [:header-ui :settings-return-focus?] true]]
           (when close-action
             (close-action {}))))))

(deftest header-settings-escape-key-closes-only-on-escape-test
  (let [keydown-action (resolve-action 'handle-header-settings-keydown)]
    (is (some? keydown-action))
    (is (= [[:effects/save [:header-ui :settings-confirmation] nil]]
           (when keydown-action
             (keydown-action {:header-ui {:settings-open? true
                                          :settings-confirmation {:kind :agent-storage-mode
                                                                  :next-mode :local}}}
                             "Escape"))))
    (is (= [[:effects/save [:header-ui :settings-confirmation] nil]
            [:effects/save [:header-ui :settings-open?] false]
            [:effects/save [:header-ui :settings-return-focus?] true]]
           (when keydown-action
             (keydown-action {:header-ui {:settings-open? true}}
                             "Escape"))))
    (is (= []
           (when keydown-action
             (keydown-action {:header-ui {:settings-open? true}}
                             "Enter"))))))

(deftest header-settings-storage-mode-change-uses-confirmation-step-test
  (let [request-action (resolve-action 'request-agent-storage-mode-change)
        confirm-action (resolve-action 'confirm-agent-storage-mode-change)
        state {:header-ui {:settings-open? true
                           :settings-confirmation nil}
               :wallet {:agent {:storage-mode :session}}}]
    (is (some? request-action))
    (is (some? confirm-action))
    (is (= [[:effects/save [:header-ui :settings-confirmation]
             {:kind :agent-storage-mode
              :next-mode :local}]]
           (when request-action
             (request-action state :local))))
      (is (= [[:effects/save [:header-ui :settings-confirmation] nil]
              [:effects/save [:header-ui :settings-open?] false]
              [:effects/save [:header-ui :settings-return-focus?] true]
              [:effects/set-agent-storage-mode :local]]
           (when confirm-action
             (confirm-action (assoc-in state [:header-ui :settings-confirmation]
                                       {:kind :agent-storage-mode
                                        :next-mode :local})))))))

(deftest header-settings-passkey-toggle-changes-immediately-test
  (let [request-action (resolve-action 'request-agent-local-protection-mode-change)
        state {:header-ui {:settings-open? true
                           :settings-confirmation nil}
               :wallet {:agent {:storage-mode :local
                                :local-protection-mode :plain}}}]
    (is (some? request-action))
    (is (= [[:effects/set-agent-local-protection-mode :passkey]]
           (when request-action
             (request-action state :passkey))))
    (is (= []
           (when request-action
             (request-action (assoc-in state [:wallet :agent :storage-mode] :session)
                             :passkey))))))

(deftest header-settings-passkey-toggle-blocks-locked-downgrade-test
  (let [request-action (resolve-action 'request-agent-local-protection-mode-change)
        state {:header-ui {:settings-open? true
                           :settings-confirmation nil}
               :wallet {:agent {:status :locked
                                :storage-mode :local
                                :local-protection-mode :passkey}}}]
    (is (some? request-action))
    (is (= []
           (when request-action
             (request-action state :plain))))))

(deftest header-settings-toggle-actions-persist-bound-local-preferences-test
  (let [fill-alerts-action (resolve-action 'set-fill-alerts-enabled)
        animate-action (resolve-action 'set-animate-orderbook-enabled)
        fill-markers-action (resolve-action 'set-fill-markers-enabled)
        confirm-open-action (resolve-action 'set-confirm-open-orders-enabled)
        confirm-close-action (resolve-action 'set-confirm-close-position-enabled)
        base-state {:trading-settings {:fill-alerts-enabled? true
                                       :animate-orderbook? true
                                       :show-fill-markers? false
                                       :confirm-open-orders? true
                                       :confirm-close-position? true}}]
    (is (some? fill-alerts-action))
    (is (some? animate-action))
    (is (some? fill-markers-action))
    (is (some? confirm-open-action))
    (is (some? confirm-close-action))
    (when fill-alerts-action
      (is (= [[:effects/save [:trading-settings]
               {:fill-alerts-enabled? false
                :animate-orderbook? true
                :show-fill-markers? false
                :confirm-open-orders? true
                :confirm-close-position? true}]
              [:effects/local-storage-set-json trading-settings/storage-key
               {:fill-alerts-enabled? false
                :animate-orderbook? true
                :show-fill-markers? false
                :confirm-open-orders? true
                :confirm-close-position? true}]]
             (fill-alerts-action base-state false))))
    (when animate-action
      (is (= [[:effects/save [:trading-settings]
               {:fill-alerts-enabled? true
                :animate-orderbook? false
                :show-fill-markers? false
                :confirm-open-orders? true
                :confirm-close-position? true}]
              [:effects/local-storage-set-json trading-settings/storage-key
               {:fill-alerts-enabled? true
                :animate-orderbook? false
                :show-fill-markers? false
                :confirm-open-orders? true
                :confirm-close-position? true}]]
             (animate-action base-state false))))
    (when fill-markers-action
      (is (= [[:effects/save [:trading-settings]
               {:fill-alerts-enabled? true
                :animate-orderbook? true
                :show-fill-markers? true
                :confirm-open-orders? true
                :confirm-close-position? true}]
              [:effects/local-storage-set-json trading-settings/storage-key
               {:fill-alerts-enabled? true
                :animate-orderbook? true
                :show-fill-markers? true
                :confirm-open-orders? true
                :confirm-close-position? true}]]
             (fill-markers-action base-state true))))
    (when confirm-open-action
      (is (= [[:effects/save [:trading-settings]
               {:fill-alerts-enabled? true
                :animate-orderbook? true
                :show-fill-markers? false
                :confirm-open-orders? false
                :confirm-close-position? true}]
              [:effects/local-storage-set-json trading-settings/storage-key
               {:fill-alerts-enabled? true
                :animate-orderbook? true
                :show-fill-markers? false
                :confirm-open-orders? false
                :confirm-close-position? true}]]
             (confirm-open-action base-state false))))
    (when confirm-close-action
      (is (= [[:effects/save [:trading-settings]
               {:fill-alerts-enabled? true
                :animate-orderbook? true
                :show-fill-markers? false
                :confirm-open-orders? true
                :confirm-close-position? false}]
              [:effects/local-storage-set-json trading-settings/storage-key
               {:fill-alerts-enabled? true
                :animate-orderbook? true
                :show-fill-markers? false
                :confirm-open-orders? true
                :confirm-close-position? false}]]
             (confirm-close-action base-state false))))))

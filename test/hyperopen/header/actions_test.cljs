(ns hyperopen.header.actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.trading-settings :as trading-settings]
            [hyperopen.header.actions :as actions]))

(def ^:private action-vars
  {'open-header-settings (resolve 'hyperopen.header.actions/open-header-settings)
   'close-header-settings (resolve 'hyperopen.header.actions/close-header-settings)
   'request-agent-storage-mode-change (resolve 'hyperopen.header.actions/request-agent-storage-mode-change)
   'confirm-agent-storage-mode-change (resolve 'hyperopen.header.actions/confirm-agent-storage-mode-change)
   'request-agent-local-protection-mode-change
   (resolve 'hyperopen.header.actions/request-agent-local-protection-mode-change)
   'set-fill-alerts-enabled (resolve 'hyperopen.header.actions/set-fill-alerts-enabled)
   'set-animate-orderbook-enabled (resolve 'hyperopen.header.actions/set-animate-orderbook-enabled)
   'set-fill-markers-enabled (resolve 'hyperopen.header.actions/set-fill-markers-enabled)
   'set-confirm-open-orders-enabled (resolve 'hyperopen.header.actions/set-confirm-open-orders-enabled)
   'set-confirm-close-position-enabled (resolve 'hyperopen.header.actions/set-confirm-close-position-enabled)
   'set-confirm-market-orders-enabled (resolve 'hyperopen.header.actions/set-confirm-market-orders-enabled)
   'set-open-order-safety-mode (resolve 'hyperopen.header.actions/set-open-order-safety-mode)
   'set-sound-on-fill-enabled (resolve 'hyperopen.header.actions/set-sound-on-fill-enabled)})

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
          [:effects/load-trade-chart-module]
          [:effects/load-surface-module :account-surfaces]]
         (actions/navigate-mobile-header-menu {} "/trade"))))

(deftest open-spectate-mode-mobile-header-menu-closes-before-opening-modal-test
  (let [bounds {:left 10 :top 20 :right 30 :bottom 40 :width 20 :height 20}
        effects (actions/open-spectate-mode-mobile-header-menu
                 {:account-context {:spectate-ui {}
                                    :watchlist []}}
                 bounds)]
    (is (= [[:effects/save [:header-ui :mobile-menu-open?] false]
            [:effects/load-surface-module :spectate-mode-modal]
            [:effects/save-many [[[:account-context :spectate-ui :modal-open?] true]
                                 [[:account-context :spectate-ui :anchor] bounds]
                                 [[:account-context :spectate-ui :search] ""]
                                 [[:account-context :spectate-ui :label] ""]
                                 [[:account-context :spectate-ui :editing-watchlist-address] nil]
                                 [[:account-context :spectate-ui :search-auto-prefilled?] false]
                                 [[:account-context :spectate-ui :search-error] nil]]]]
           effects))))

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
  (let [base-settings {:fill-alerts-enabled? true
                       :animate-orderbook? true
                       :show-fill-markers? false
                       :confirm-open-orders? true
                       :confirm-close-position? true
                       :confirm-market-orders? true
                       :sound-on-fill? false
                       :open-order-safety-mode :strict}
        base-state {:trading-settings base-settings}
        expected-effects (fn [settings]
                           [[:effects/save [:trading-settings] settings]
                            [:effects/local-storage-set-json
                             trading-settings/storage-key
                             settings]])
        cases [{:sym 'set-fill-alerts-enabled
                :args [false]
                :updates {:fill-alerts-enabled? false}}
               {:sym 'set-animate-orderbook-enabled
                :args [false]
                :updates {:animate-orderbook? false}}
               {:sym 'set-fill-markers-enabled
                :args [true]
                :updates {:show-fill-markers? true}}
               {:sym 'set-confirm-open-orders-enabled
                :args [false]
                :updates {:confirm-open-orders? false}}
               {:sym 'set-confirm-close-position-enabled
                :args [false]
                :updates {:confirm-close-position? false}}
               {:sym 'set-confirm-market-orders-enabled
                :args [false]
                :updates {:confirm-market-orders? false}}
               {:sym 'set-sound-on-fill-enabled
                :args [true]
                :updates {:sound-on-fill? true}}
               {:sym 'set-open-order-safety-mode
                :args ["extended"]
                :updates {:open-order-safety-mode :extended
                          :open-order-safety-chosen? true}}]]
    (doseq [{:keys [args sym updates]} cases]
      (let [action (resolve-action sym)
            expected-settings (trading-settings/normalize-state
                               (merge base-settings updates))]
        (is (some? action))
        (when action
          (is (= (expected-effects expected-settings)
                 (apply action base-state args))))))))

(deftest set-ui-theme-saves-persists-and-applies-test
  (is (= [[:effects/save [:ui :theme] "institutional"]
          [:effects/local-storage-set "hyperopen-ui-theme" "institutional"]
          [:effects/apply-ui-theme "institutional"]]
         (actions/set-ui-theme {} "institutional"))))

(deftest set-ui-theme-normalizes-unknown-to-default-test
  (is (= [[:effects/save [:ui :theme] "dark"]
          [:effects/local-storage-set "hyperopen-ui-theme" "dark"]
          [:effects/apply-ui-theme "dark"]]
         (actions/set-ui-theme {:ui {:theme "hyperdegen"}} "not-a-theme"))))

(deftest set-ui-theme-is-noop-when-theme-unchanged-test
  (is (= [] (actions/set-ui-theme {:ui {:theme "hyperdegen"}} "hyperdegen")))
  (is (= [] (actions/set-ui-theme {} "dark"))))

(deftest reset-degen-life-increments-counter-test
  (is (= [[:effects/save [:degen :life-resets] 1]]
         (actions/reset-degen-life {})))
  (is (= [[:effects/save [:degen :life-resets] 3]]
         (actions/reset-degen-life {:degen {:life-resets 2}}))))

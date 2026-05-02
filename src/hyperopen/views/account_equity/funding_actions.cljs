(ns hyperopen.views.account-equity.funding-actions
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.views.ui.focus-return :as focus-return]
            [hyperopen.views.ui.funding-modal-positioning :as funding-modal-positioning]))

(defn- funding-focus-request
  [state]
  {:data-role (get-in state [:funding-ui :modal :focus-return-data-role])
   :token (get-in state [:funding-ui :modal :focus-return-token] 0)})

(defn- funding-action-button
  [{:keys [label action primary? data-role focus-request]}]
  [:button (merge
            {:type "button"
             :class (into ["w-full"
                           "h-[34px]"
                           "rounded-[8px]"
                           "border"
                           "px-2.5"
                           "text-sm"
                           "leading-none"
                           "font-medium"
                           "tracking-[0.01em]"
                           "transition-colors"
                           "duration-150"]
                          (if primary?
                            ["border-[#58ded2]"
                             "bg-[#58ded2]"
                             "text-[#072b2f]"
                             "spectate-[inset_0_1px_0_rgba(255,255,255,0.20)]"
                             "hover:border-[#69e5db]"
                             "hover:bg-[#69e5db]"]
                            ["border-[#32cdc2]"
                             "bg-[rgba(4,23,31,0.35)]"
                             "text-[#53ddd1]"
                             "spectate-[inset_0_1px_0_rgba(255,255,255,0.08)]"
                             "hover:border-[#45d8ce]"
                             "hover:bg-[#0f2f36]"
                             "hover:text-[#76e9df]"]))
             :data-role data-role
             :on {:click [action]}}
            (focus-return/data-role-return-focus-props data-role
                                                       (:data-role focus-request)
                                                       (:token focus-request)))
   label])

(defn- funding-actions-cluster [state]
  (let [focus-request (funding-focus-request state)]
  [:div.space-y-2
   (funding-action-button {:label "Deposit"
                           :primary? true
                           :focus-request focus-request
                           :data-role funding-modal-positioning/deposit-action-data-role
                           :action [:actions/open-funding-deposit-modal
                                    :event.currentTarget/bounds
                                    funding-modal-positioning/deposit-action-data-role]})
   [:div.grid.grid-cols-2.gap-2.5
    (funding-action-button {:label "Perps <-> Spot"
                            :focus-request focus-request
                            :data-role funding-modal-positioning/transfer-action-data-role
                            :action [:actions/open-funding-transfer-modal
                                     :event.currentTarget/bounds
                                     funding-modal-positioning/transfer-action-data-role]})
    (funding-action-button {:label "Withdraw"
                            :focus-request focus-request
                            :data-role funding-modal-positioning/withdraw-action-data-role
                            :action [:actions/open-funding-withdraw-modal
                                     :event.currentTarget/bounds
                                     funding-modal-positioning/withdraw-action-data-role]})]]))

(defn funding-actions-view
  ([state]
   (funding-actions-view state {}))
  ([state {:keys [container-classes data-parity-id]
           :or {container-classes ["space-y-2"]}}]
   (when-not (account-context/spectate-mode-active? state)
     [:div (cond-> {:class (into [] container-classes)}
             data-parity-id (assoc :data-parity-id data-parity-id))
      (funding-actions-cluster state)])))

(defn funding-actions-section [state]
  (funding-actions-view state {:container-classes ["space-y-2"
                                                   "py-2.5"
                                                   "border-y"
                                                   "border-[#223b45]"]
                                :data-parity-id "funding-actions-section"}))

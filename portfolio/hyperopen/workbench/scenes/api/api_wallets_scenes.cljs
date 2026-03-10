(ns hyperopen.workbench.scenes.api.api-wallets-scenes
  (:require [portfolio.replicant :as portfolio]
            [hyperopen.workbench.support.fixtures :as fixtures]
            [hyperopen.workbench.support.layout :as layout]
            [hyperopen.workbench.support.state :as ws]
            [hyperopen.views.api-wallets-view :as api-wallets-view]))

(portfolio/configure-scenes
  {:title "API Wallets"
   :collection :api})

(defn- api-store
  [scene-id overrides]
  (ws/create-store scene-id (fixtures/api-wallets-state overrides)))

(defn- open-modal
  [state modal-type row]
  (assoc-in state
            [:api-wallets-ui :modal]
            {:open? true
             :type modal-type
             :row row
             :submitting? false
             :error nil}))

(defn- generated-private-key
  [address]
  (str "0xworkbench" (subs (or address "0000000000") 2 18)))

(defn- api-reducers
  []
  {:actions/set-api-wallet-form-field
   (fn [state _dispatch-data field value]
     (assoc-in state [:api-wallets-ui :form field] value))

   :actions/generate-api-wallet
   (fn [state _dispatch-data]
     (let [address "0x7e57f00d9a6db2b537f1ecf0d2bbf110f4d1abce"]
       (-> state
           (assoc-in [:api-wallets-ui :form :address] address)
           (assoc-in [:api-wallets-ui :generated :address] address)
           (assoc-in [:api-wallets-ui :generated :private-key] (generated-private-key address)))))

   :actions/open-api-wallet-authorize-modal
   (fn [state _dispatch-data]
     (open-modal state :authorize nil))

   :actions/open-api-wallet-remove-modal
   (fn [state _dispatch-data row]
     (open-modal state :remove row))

   :actions/close-api-wallet-modal
   (fn [state _dispatch-data]
     (assoc-in state [:api-wallets-ui :modal] {:open? false
                                               :type :authorize
                                               :row nil
                                               :submitting? false
                                               :error nil}))

   :actions/confirm-api-wallet-modal
   (fn [state _dispatch-data]
     (let [{:keys [type row]} (get-in state [:api-wallets-ui :modal])
           form (get-in state [:api-wallets-ui :form])]
       (case type
         :remove
         (-> state
             (update-in [:api-wallets :extra-agents]
                        (fn [rows]
                          (->> (vec rows)
                               (remove #(= (:address %) (:address row)))
                               vec)))
             (assoc-in [:api-wallets-ui :modal] {:open? false
                                                 :type :authorize
                                                 :row nil
                                                 :submitting? false
                                                 :error nil}))

         :authorize
         (-> state
             (update-in [:api-wallets :extra-agents]
                        (fn [rows]
                          (conj (vec rows)
                                {:row-kind :generated
                                 :name (:name form)
                                 :approval-name (:name form)
                                 :address (:address form)
                                 :valid-until-ms (+ (get-in state [:api-wallets :server-time-ms])
                                                    (* 30 24 60 60 1000))})))
             (assoc-in [:api-wallets-ui :modal] {:open? false
                                                 :type :authorize
                                                 :row nil
                                                 :submitting? false
                                                 :error nil})
             (assoc-in [:api-wallets-ui :form] {:name "" :address "" :days-valid ""}))

         state)))

   :actions/set-api-wallet-sort
   (fn [state _dispatch-data column]
     (update-in state [:api-wallets-ui :sort]
                #(ws/update-sort-state (or % {:column :name :direction :asc}) column)))})

(defonce disconnected-store
  (api-store ::disconnected
             {:wallet {:connected? false
                       :address nil}
              :api-wallets {:default-agent-row nil
                            :extra-agents []}}))

(defonce connected-store
  (api-store ::connected {}))

(defonce authorize-modal-store
  (api-store ::authorize-modal
             {:api-wallets-ui {:modal {:open? true
                                       :type :authorize
                                       :row nil
                                       :submitting? false
                                       :error nil}}}))

(defonce remove-modal-store
  (api-store ::remove-modal
             {:api-wallets-ui {:modal {:open? true
                                       :type :remove
                                       :row {:name "Desk Wallet"
                                             :address "0xa9a94f7ad68eb6d264d240f438ebf4ec4cdbdd69"
                                             :valid-until-ms 1763395200000}
                                       :submitting? false
                                       :error nil}}}))

(defonce validation-error-store
  (api-store ::validation-errors
             {:api-wallets-ui {:form {:name ""
                                      :address "not-an-address"
                                      :days-valid "999"}
                               :form-error "Connect a wallet before authorizing API access."}}))

(defn- api-scene
  [store]
  (layout/page-shell
   (layout/interactive-shell
    store
    (api-reducers)
    (layout/desktop-shell
     (api-wallets-view/api-wallets-view @store)))))

(portfolio/defscene disconnected
  :params disconnected-store
  [store]
  (api-scene store))

(portfolio/defscene connected
  :params connected-store
  [store]
  (api-scene store))

(portfolio/defscene authorize-modal
  :params authorize-modal-store
  [store]
  (api-scene store))

(portfolio/defscene remove-modal
  :params remove-modal-store
  [store]
  (api-scene store))

(portfolio/defscene validation-errors
  :params validation-error-store
  [store]
  (api-scene store))

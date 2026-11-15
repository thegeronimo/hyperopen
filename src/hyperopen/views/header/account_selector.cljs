(ns hyperopen.views.header.account-selector
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.views.header.icons :as icons]
            [hyperopen.wallet.core :as wallet]))

(def ^:private master-value
  "master")

(defn- row-address
  [row]
  (account-context/normalize-address
   (or (:sub-account-user row)
       (:subAccountUser row)
       (get row "subAccountUser")
       (get row "sub-account-user"))))

(defn- row-master
  [row]
  (account-context/normalize-address
   (or (:master row)
       (get row "master"))))

(defn- row-name
  [row]
  (some-> (:name row) str str/trim not-empty))

(defn- owned-row
  [owner row]
  (when-let [address (row-address row)]
    (when (= owner (row-master row))
      (assoc row :sub-account-user address))))

(defn- owned-rows
  [state owner]
  (->> (get-in state [:account-context :subaccounts :rows])
       (keep #(owned-row owner %))
       vec))

(defn- master-option
  [owner selected?]
  {:kind :master
   :value master-value
   :label "Master"
   :address-label (wallet/short-addr owner)
   :selected? selected?
   :data-role "header-account-target-option-master"
   :copy-data-role "header-account-target-copy-master"
   :copy-label "Copy master account address"
   :copy-action [[:actions/copy-subaccount-address owner]]
   :action [[:actions/select-master-account]]})

(defn- subaccount-option
  [selected-address row]
  (let [address (:sub-account-user row)
        name (or (row-name row) "Subaccount")]
    {:kind :subaccount
     :value address
     :label "Sub"
     :name-label name
     :address-label (wallet/short-addr address)
     :selected? (= selected-address address)
     :data-role (str "header-account-target-option-" address)
     :copy-data-role (str "header-account-target-copy-" address)
     :copy-label "Copy subaccount address"
     :copy-action [[:actions/copy-subaccount-address address]]
     :action [[:actions/select-subaccount address]]}))

(defn- selected-owned-address
  [selected rows]
  (some (fn [row]
          (when (= selected (:sub-account-user row))
            selected))
        rows))

(defn vm
  [state]
  (let [owner (account-context/owner-address state)
        connected? (true? (get-in state [:wallet :connected?]))
        rows (owned-rows state owner)
        selected (selected-owned-address
                  (account-context/selected-subaccount-address state)
                  rows)]
    (when (and connected?
               (seq owner)
               (seq rows))
      (let [options (into [(master-option owner (nil? selected))]
                          (mapv (partial subaccount-option selected) rows))
            selected-option (or (some #(when (:selected? %) %) options)
                                (first options))]
        {:trigger-label (if (= :subaccount (:kind selected-option))
                          (str "Sub: " (:name-label selected-option))
                          (:label selected-option))
         :trigger-address-label (:address-label selected-option)
         :active-subaccount (when (= :subaccount (:kind selected-option))
                              {:name (:name-label selected-option)
                               :address (:value selected-option)})
         :disconnect (when (= :subaccount (:kind selected-option))
                       {:label "Disconnect"
                        :action [[:actions/select-master-account]]})
         :options options}))))

(defn option-row
  [{:keys [action address-label copy-action copy-data-role copy-label data-role kind label name-label selected? value]}]
  ^{:key (str "header-account-target:" value)}
  [:div {:class ["flex"
                 "items-center"
                 "border-b"
                 "border-white/5"
                 "last:border-b-0"
                 "hover:bg-white/[0.04]"]}
   [:button {:type "button"
             :data-role data-role
             :aria-current (when selected? "true")
             :class (into ["grid"
                           "min-w-0"
                           "flex-1"
                           "grid-cols-[4.5rem_minmax(0,1fr)]"
                           "items-center"
                           "gap-3"
                           "px-3"
                           "py-2.5"
                           "text-left"
                           "text-xs"
                           "transition-colors"
                           "focus:outline-none"
                           "focus-visible:bg-white/[0.05]"]
                          (if selected?
                            ["text-ho-accent-bright"]
                            ["text-white"]))
             :on {:click action}}
    [:span {:class ["min-w-0" "truncate" "font-medium"]} label]
    [:span {:class ["num"
                    "min-w-0"
                    "truncate"
                    "text-right"
                    (if (= :subaccount kind)
                      "text-white"
                      "text-trading-text-secondary")]}
     (or name-label address-label)]]
   [:button {:type "button"
             :data-role copy-data-role
             :aria-label copy-label
             :class ["mr-2"
                     "inline-flex"
                     "h-7"
                     "w-7"
                     "shrink-0"
                     "items-center"
                     "justify-center"
                     "rounded-md"
                     "text-[#56dcca]"
                     "transition-colors"
                     "hover:bg-ho-accent-soft"
                     "hover:text-ho-accent-bright"
                     "focus:outline-none"
                     "focus-visible:ring-1"
                     "focus-visible:ring-[#56dcca]"]
             :on {:click copy-action}}
    (icons/wallet-copy-icon)]])

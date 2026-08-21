(ns hyperopen.schema.runtime-registration-catalog
  (:require [hyperopen.schema.runtime-registration.api-wallets :as api-wallets]
            [hyperopen.schema.runtime-registration.funding :as funding]
            [hyperopen.schema.runtime-registration.funding-comparison :as funding-comparison]
            [hyperopen.schema.runtime-registration.leaderboard :as leaderboard]
            [hyperopen.schema.runtime-registration.margin-rec :as margin-rec]
            [hyperopen.schema.runtime-registration.pnl-share :as pnl-share]
            [hyperopen.schema.runtime-registration.portfolio :as portfolio]
            [hyperopen.schema.runtime-registration.referrals :as referrals]
            [hyperopen.schema.runtime-registration.spectate-mode :as spectate-mode]
            [hyperopen.schema.runtime-registration.staking :as staking]
            [hyperopen.schema.runtime-registration.subaccounts :as subaccounts]
            [hyperopen.schema.runtime-registration.trade :as trade]
            [hyperopen.schema.runtime-registration.vaults :as vaults]
            [hyperopen.schema.runtime-registration.wallet :as wallet]
            [hyperopen.schema.runtime-registration.websocket :as websocket]))

(defn- concat-row-groups
  [& groups]
  (vec (apply concat groups)))

(def ^:private effect-binding-rows-data
  (concat-row-groups
   websocket/effect-binding-rows
   wallet/effect-binding-rows
   spectate-mode/effect-binding-rows
   api-wallets/effect-binding-rows
   subaccounts/effect-binding-rows
   leaderboard/effect-binding-rows
   referrals/effect-binding-rows
   funding-comparison/effect-binding-rows
   portfolio/effect-binding-rows
   trade/effect-binding-rows
   vaults/effect-binding-rows
   staking/effect-binding-rows
   funding/effect-binding-rows
   margin-rec/effect-binding-rows
   pnl-share/effect-binding-rows))

(def ^:private action-binding-rows-data
  (concat-row-groups
   websocket/action-binding-rows
   wallet/action-binding-rows
   spectate-mode/action-binding-rows
   portfolio/action-binding-rows
   trade/action-binding-rows
   funding/action-binding-rows
   leaderboard/action-binding-rows
   referrals/action-binding-rows
   api-wallets/action-binding-rows
   subaccounts/action-binding-rows
   funding-comparison/action-binding-rows
   staking/action-binding-rows
   vaults/action-binding-rows
   margin-rec/action-binding-rows
   pnl-share/action-binding-rows))

(def ^:private effect-order-policy-required-action-ids-data
  (set
   (concat websocket/effect-order-policy-required-action-ids
           wallet/effect-order-policy-required-action-ids
           portfolio/effect-order-policy-required-action-ids
           trade/effect-order-policy-required-action-ids
           funding/effect-order-policy-required-action-ids
           leaderboard/effect-order-policy-required-action-ids
           referrals/effect-order-policy-required-action-ids
           api-wallets/effect-order-policy-required-action-ids
           subaccounts/effect-order-policy-required-action-ids
           funding-comparison/effect-order-policy-required-action-ids
           staking/effect-order-policy-required-action-ids
           vaults/effect-order-policy-required-action-ids
           margin-rec/effect-order-policy-required-action-ids)))

(defn- duplicate-ids
  [rows]
  (->> rows
       (map first)
       frequencies
       (keep (fn [[id freq]]
               (when (> freq 1)
                 id)))
       sort
       vec))

(defn- assert-unique-ids!
  [label rows]
  (when-let [duplicates (seq (duplicate-ids rows))]
    (throw (js/Error.
            (str label " contains duplicate ids: " (pr-str duplicates)))))
  rows)

(def ^:private validated-effect-binding-rows
  (assert-unique-ids! "effect registration catalog" effect-binding-rows-data))

(def ^:private validated-action-binding-rows
  (assert-unique-ids! "action registration catalog" action-binding-rows-data))

(def ^:private validated-effect-order-policy-required-action-ids
  (let [registered (->> validated-action-binding-rows (map first) set)
        missing (->> effect-order-policy-required-action-ids-data
                     (remove registered)
                     sort
                     vec)]
    (when (seq missing)
      (throw (js/Error.
              (str "effect-order policy requirement references unregistered actions: "
                   (pr-str missing)))))
    effect-order-policy-required-action-ids-data))

(defn effect-binding-rows
  []
  validated-effect-binding-rows)

(defn action-binding-rows
  []
  validated-action-binding-rows)

(defn effect-ids
  []
  (->> validated-effect-binding-rows
       (map first)
       set))

(defn action-ids
  []
  (->> validated-action-binding-rows
       (map first)
       set))

(defn effect-order-policy-required-action-ids
  []
  validated-effect-order-policy-required-action-ids)

(defn effect-handler-keys
  []
  (->> validated-effect-binding-rows
       (map second)
       set))

(defn action-handler-keys
  []
  (->> validated-action-binding-rows
       (map second)
       set))

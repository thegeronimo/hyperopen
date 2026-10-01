(ns hyperopen.account.hyperevm-gate-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.account.context :as account-context]))

(def ^:private owner "0x1111111111111111111111111111111111111111")
(def ^:private subaccount "0x4444444444444444444444444444444444444444")
(def ^:private spectated "0x5555555555555555555555555555555555555555")

(def ^:private connected {:wallet {:address owner} :router {:path "/trade"}})

(defn- with-subaccount
  [state master]
  (assoc state :account-context {:subaccounts {:rows [{:sub-account-user subaccount
                                                       :master master}]
                                               :selected-address subaccount}}))

(deftest hyperevm-moves-blocked-message-test
  (testing "the connected master account may move"
    (is (nil? (account-context/hyperevm-moves-blocked-message connected))))
  (testing "no wallet"
    (is (= "Connect your wallet to move funds."
           (account-context/hyperevm-moves-blocked-message {:router {:path "/trade"}}))))
  (testing "a selected subaccount has no key for its HyperEVM address"
    (is (= "HyperEVM transfers are available for the master account only."
           (account-context/hyperevm-moves-blocked-message (with-subaccount connected owner)))))
  (testing "read-only views keep their own explanation"
    (is (= account-context/spectate-mode-read-only-message
           (account-context/hyperevm-moves-blocked-message
            (assoc connected :account-context {:spectate-mode {:active? true
                                                               :address spectated}}))))
    (is (= account-context/trader-portfolio-read-only-message
           (account-context/hyperevm-moves-blocked-message
            (assoc-in connected [:router :path] (str "/portfolio/trader/" spectated)))))
    (is (= account-context/selected-subaccount-unavailable-message
           (account-context/hyperevm-moves-blocked-message
            (with-subaccount connected "0x9999999999999999999999999999999999999999")))
        "a subaccount owned by someone else is read-only first")))

(deftest core-account-activation-status-test
  (is (nil? (account-context/core-account-activation-status connected)) "unknown until read")
  (is (= :missing (account-context/core-account-activation-status
                   (assoc-in connected [:hyperevm :core-account owner] :missing))))
  (is (= :active (account-context/core-account-activation-status
                  (assoc-in connected [:hyperevm :core-account owner] :active))))
  (is (nil? (account-context/core-account-activation-status
             {:hyperevm {:core-account {owner :active}}}))
      "no owner, no status"))

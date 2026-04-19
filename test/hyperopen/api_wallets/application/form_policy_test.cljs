(ns hyperopen.api-wallets.application.form-policy-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.api-wallets.application.form-policy :as form-policy]
            [hyperopen.wallet.agent-session :as agent-session]))

(def owner-address
  "0x1234567890abcdef1234567890abcdef12345678")

(def generated-address
  "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")

(def day-ms
  (* 24 60 60 1000))

(defn days-valid-error-message
  []
  (str "Enter a value from 1 to "
       agent-session/max-agent-valid-days
       " days."))

(deftest valid-forms-keep-errors-clear-and-drive-previews-test
  (let [form {:name "Desk"
              :address owner-address
              :days-valid "180"}]
    (is (= {:name nil
            :address nil
            :days-valid nil}
           (form-policy/form-errors form)))
    (is (true? (form-policy/form-valid? form)))
    (is (nil? (form-policy/first-form-error form)))
    (is (= "0xpriv"
           (form-policy/generated-private-key
            {:address generated-address
             :private-key "0xpriv"}
            "0xABCDEFabcdefABCDEFabcdefABCDEFabcdefABCD")))
    (is (= 1702592000000
           (form-policy/valid-until-preview-ms 1700000000000 "30")))))

(deftest invalid-or-partial-forms-surface-session-policy-errors-test
  (is (= {:name "Enter an API wallet name."
          :address "Enter a valid wallet address."
          :days-valid nil}
         (form-policy/form-errors {:name "   "
                                   :address " "
                                   :days-valid ""})))
  (is (= {:name "Enter an API wallet name."
          :address "Enter a valid wallet address."
          :days-valid "Enter a value from 1 to 180 days."}
         (form-policy/form-errors {:name nil
                                   :address "bad-address"
                                   :days-valid "0"})))
  (is (= "Enter an API wallet name."
         (form-policy/first-form-error {:name ""
                                        :address owner-address
                                        :days-valid ""})))
  (is (false? (form-policy/form-valid? {:name "Desk"
                                        :address "bad-address"
                                        :days-valid ""})))
  (is (nil? (form-policy/generated-private-key {:address generated-address
                                                :private-key "0xpriv"}
                                               owner-address)))
  (is (nil? (form-policy/valid-until-preview-ms "1700000000000" "30"))))

(deftest form-errors-merge-defaults-and-use-agent-session-days-policy-test
  (is (= {:name "Enter an API wallet name."
          :address "Enter a valid wallet address."
          :days-valid nil}
         (form-policy/form-errors nil)))
  (is (= {:name nil
          :address "Enter a valid wallet address."
          :days-valid nil}
         (form-policy/form-errors {:name " Desk "})))
  (is (= {:name nil
          :address nil
          :days-valid nil}
         (form-policy/form-errors {:name " Desk "
                                   :address " 0x1234567890ABCDEF1234567890ABCDEF12345678 "})))
  (is (= {:name nil
          :address nil
          :days-valid nil}
         (form-policy/form-errors {:name "Desk"
                                   :address owner-address
                                   :days-valid "   "})))
  (is (= (days-valid-error-message)
         (:days-valid (form-policy/form-errors {:name "Desk"
                                                :address owner-address
                                                :days-valid "not-a-number"}))))
  (is (= (days-valid-error-message)
         (:days-valid (form-policy/form-errors {:name "Desk"
                                                :address owner-address
                                                :days-valid "0"})))))

(deftest generated-private-key-requires-a-normalized-valid-address-match-test
  (is (= "0xpriv"
         (form-policy/generated-private-key
          {:address generated-address
           :private-key "0xpriv"}
          "0xABCDEFabcdefABCDEFabcdefABCDEFabcdefABCD")))
  (is (nil? (form-policy/generated-private-key {:address generated-address
                                                :private-key "0xpriv"}
                                               owner-address)))
  (is (nil? (form-policy/generated-private-key {:address "not-a-wallet"
                                                :private-key "0xpriv"}
                                               owner-address)))
  (is (nil? (form-policy/generated-private-key {:address "not-a-wallet"
                                                :private-key "0xpriv"}
                                               "also-not-a-wallet"))))

(deftest valid-until-preview-requires-valid-days-and-numeric-server-time-test
  (let [server-time-ms 1700000000000]
    (is (nil? (form-policy/valid-until-preview-ms server-time-ms nil)))
    (is (nil? (form-policy/valid-until-preview-ms server-time-ms "0")))
    (is (nil? (form-policy/valid-until-preview-ms server-time-ms "not-a-number")))
    (is (nil? (form-policy/valid-until-preview-ms "1700000000000" "7")))
    (is (= (+ server-time-ms (* 7 day-ms))
           (form-policy/valid-until-preview-ms server-time-ms " 7 ")))))

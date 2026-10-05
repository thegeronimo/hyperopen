(ns hyperopen.api.endpoints.vaults-helpers-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.api.endpoints.vaults :as vaults]))

(deftest normalize-snapshot-key-supports-supported-aliases-test
  (let [normalize-snapshot-key @#'hyperopen.api.endpoints.vaults/normalize-snapshot-key]
    (is (= :day (normalize-snapshot-key " day ")))
    (is (= :week (normalize-snapshot-key "Week")))
    (is (= :month (normalize-snapshot-key "month")))
    (is (= :three-month (normalize-snapshot-key "3M")))
    (is (= :three-month (normalize-snapshot-key "quarter")))
    (is (= :six-month (normalize-snapshot-key "half-year")))
    (is (= :one-year (normalize-snapshot-key "1Y")))
    (is (= :two-year (normalize-snapshot-key "2year")))
    (is (= :all-time (normalize-snapshot-key "allTime")))
    (is (nil? (normalize-snapshot-key "unknown")))
    (is (nil? (normalize-snapshot-key nil)))))

(deftest merge-vault-index-with-summaries-appends-recent-and-dedupes-by-address-test
  (let [merged (vaults/merge-vault-index-with-summaries
                [{:summary {:vaultAddress "0x1"
                            :name "Vault One"
                            :createTimeMillis 100}}
                 {:summary {:vaultAddress "0x2"
                            :name "Vault Two"
                            :createTimeMillis 200}}]
                [{:summary {:vaultAddress "0x2"
                            :name "Vault Two New"
                            :createTimeMillis 350}}
                 {:summary {:vaultAddress "0x3"
                            :name "Vault Three"
                            :createTimeMillis 300}}
                 {:summary {:vaultAddress "0x4"
                            :name "Too Old"
                            :createTimeMillis 20}}])]
    (is (= ["0x1" "0x2" "0x3"]
           (mapv :vault-address merged)))
    (is (= "Vault Two New"
           (:name (second merged))))
    (is (= 350
           (:create-time-ms (second merged))))))

(deftest cross-origin-browser-request-private-helper-detects-same-origin-and-invalid-url-test
  (let [cross-origin-browser-request? @#'hyperopen.api.endpoints.vaults/cross-origin-browser-request?]
    (let [original-location (.-location js/globalThis)
          original-url (.-URL js/globalThis)]
      (try
        (set! (.-location js/globalThis)
              #js {:origin "https://vaults.test"
                   :href "https://vaults.test/app"})
        (is (false? (cross-origin-browser-request? "https://vaults.test/index")))
        (is (true? (cross-origin-browser-request? "https://example.com/index")))
        (is (false? (cross-origin-browser-request? "not a url")))
        (set! (.-URL js/globalThis)
              (fn [& _]
                (throw (js/Error. "bad-url"))))
        (is (false? (cross-origin-browser-request? "https://example.com/index")))
        (finally
          (set! (.-URL js/globalThis) original-url)
          (set! (.-location js/globalThis) original-location))))))

(deftest normalize-vault-snapshot-return-and-preview-series-helpers-test
  (let [normalize-vault-snapshot-return @#'hyperopen.api.endpoints.vaults/normalize-vault-snapshot-return
        sample-snapshot-preview-series @#'hyperopen.api.endpoints.vaults/sample-snapshot-preview-series]
    (is (= 4000
           (normalize-vault-snapshot-return 2000 50)))
    (is (= 1000
           (normalize-vault-snapshot-return 1000 50)))
    (is (= 100
           (normalize-vault-snapshot-return 1 10)))
    (is (= 50
           (normalize-vault-snapshot-return 0.5 10)))
    (is (= 2
           (normalize-vault-snapshot-return 2 10)))
    (is (nil? (normalize-vault-snapshot-return "bad" 10)))
    (is (= [0 1 2 3 4 5 6 7]
           (sample-snapshot-preview-series (range 8))))
    (let [values-at-limit [0 1 2 3 4 5 6 7]]
      (is (identical? values-at-limit
                      (sample-snapshot-preview-series values-at-limit))))
    (is (= 8
           (count (sample-snapshot-preview-series (range 10)))))
    (is (= [0 1 3 4 5 6 8 9]
           (sample-snapshot-preview-series (range 10))))))

(deftest normalize-vault-pnls-relationship-summary-and-detail-helpers-test
  (let [boolean-value @#'hyperopen.api.endpoints.vaults/boolean-value
        normalize-vault-pnls @#'hyperopen.api.endpoints.vaults/normalize-vault-pnls
        normalize-vault-relationship @#'hyperopen.api.endpoints.vaults/normalize-vault-relationship
        normalize-vault-summary @#'hyperopen.api.endpoints.vaults/normalize-vault-summary
        normalize-user-vault-equity @#'hyperopen.api.endpoints.vaults/normalize-user-vault-equity
        normalize-follower-state @#'hyperopen.api.endpoints.vaults/normalize-follower-state
        followers-count @#'hyperopen.api.endpoints.vaults/followers-count
        normalize-vault-details @#'hyperopen.api.endpoints.vaults/normalize-vault-details]
    (is (true? (boolean-value true)))
    (is (false? (boolean-value false)))
    (is (true? (boolean-value "true")))
    (is (false? (boolean-value "false")))
    (is (nil? (boolean-value "maybe")))
    (is (= {:day [1 2]
            :week []
            :month []
            :all-time [3 4]}
           (normalize-vault-pnls [["day" ["1" "2" "bad"]]
                                  ["week" nil]
                                  ["month" []]
                                  ["allTime" ["3" "4"]]
                                  [:ignored [5]]])))
    (is (= {:type :parent
            :child-addresses ["0x1" "0x2"]}
           (normalize-vault-relationship {:type "parent"
                                          :data {:childAddresses ["0x1" " " "0x2"]}})))
    (is (= {:type :child
            :parent-address "0xparent"}
           (normalize-vault-relationship {:type "child"
                                          :data {:parentAddress "0xParent"}})))
    (is (= {:type :normal}
           (normalize-vault-relationship {:type "other"})))
    (is (= {:name "0xabc"
            :vault-address "0xabc"
            :leader "0xleader"
            :tvl 0
            :tvl-raw nil
            :is-closed? true
            :relationship {:type :normal}
            :create-time-ms 1700}
           (normalize-vault-summary {:vaultAddress "0xABC"
                                     :leader "0xLeader"
                                     :isClosed "true"
                                     :createTimeMillis "1700"})))
    (is (= {:vault-address "0xabc"
            :equity 0
            :equity-raw nil
            :locked-until-ms 1700}
           (normalize-user-vault-equity {:vaultAddress "0xABC"
                                         :lockedUntilTimestamp "1700"})))
    (is (= {:user "0xabc"
            :vault-equity 2.5
            :days-following 8}
           (normalize-follower-state {:user "0xABC"
                                      :vaultEquity "2.5"
                                      :daysFollowing "8"
                                      :vaultEntryTime nil})))
    (is (= {:leader? true
            :vault-equity 10}
           (normalize-follower-state {:user "Leader"
                                      :vaultEquity "10"})))
    (is (= 0
           (followers-count nil [])))
    (is (= 0
           (followers-count "bad" [])))
    (is (= 3 (followers-count "3" [])))
    (is (= 2 (followers-count nil [{:user "0x1"} {:user "0x2"}])))
    (is (= {:name "Vault Detail"
            :vault-address "0xvault"
            :leader "0xleader"
            :description "hello"
            :tvl nil
            :tvl-raw nil
            :portfolio {}
            :apr 0
            :follower-state {:user "0xf1"
                             :vault-equity 90.5
                             :days-following 8
                             :vault-entry-time-ms 111
                             :lockup-until-ms 222}
            :leader-fraction 0.1
            :leader-commission 0.2
            :followers []
            :followers-count 3
            :max-distributable 120
            :max-withdrawable 80
            :is-closed? true
            :relationship {:type :child
                           :parent-address "0xparent"}
            :allow-deposits? true
            :always-close-on-withdraw? true}
           (normalize-vault-details {:name "Vault Detail"
                                     :vaultAddress "0xVaUlT"
                                     :leader "0xLEADER"
                                     :description "  hello  "
                                     :portfolio []
                                     :apr nil
                                     :followerState {:user "0xF1"
                                                     :vaultEquity "90.5"
                                                     :daysFollowing "8"
                                                     :vaultEntryTime "111"
                                                     :lockupUntil "222"}
                                     :leaderFraction "0.1"
                                     :leaderCommission "0.2"
                                     :followers "3"
                                     :maxDistributable "120"
                                     :maxWithdrawable "80"
                                     :isClosed "true"
                                     :relationship {:type "child"
                                                    :data {:parentAddress "0xPARENT"}}
                                     :allowDeposits "true"
                                     :alwaysCloseOnWithdraw "true"})))
    (is (= {:apr 0
            :is-closed? false
            :allow-deposits? false
            :always-close-on-withdraw? false}
           (select-keys
            (normalize-vault-details {:vaultAddress "0xVaUlT"
                                      :apr nil
                                      :isClosed "maybe"
                                      :allowDeposits "maybe"
                                      :alwaysCloseOnWithdraw "maybe"})
            [:apr
             :is-closed?
             :allow-deposits?
             :always-close-on-withdraw?])))))

(deftest merge-vault-index-with-summaries-prefers-newer-recent-summary-rows-test
  (let [merged (vaults/merge-vault-index-with-summaries
                [{:summary {:vaultAddress "0x1"
                            :name "Vault One"
                            :createTimeMillis 100}}
                 {:summary {:vaultAddress "0x2"
                            :name "Vault Two"
                            :createTimeMillis 200}}]
                [{:summary {:vaultAddress "0x2"
                            :name "Vault Two New"
                            :createTimeMillis 250}}
                 {:summary {:vaultAddress "0x3"
                            :name "Vault Three"
                            :createTimeMillis 300}}
                 {:summary {:vaultAddress "0x4"
                            :name "Too Old"
                            :createTimeMillis 20}}])]
    (is (= ["0x1" "0x2" "0x3"]
           (mapv :vault-address merged)))
    (is (= "Vault Two New" (:name (second merged))))
    (is (= 250 (:create-time-ms (second merged))))))

(deftest merge-vault-index-with-summaries-excludes-equal-age-new-addresses-test
  (let [merged (vaults/merge-vault-index-with-summaries
                [{:summary {:vaultAddress "0x1"
                            :name "Vault One"
                            :createTimeMillis 100}}
                 {:summary {:vaultAddress "0x2"
                            :name "Vault Two"
                            :createTimeMillis 200}}]
                [{:summary {:vaultAddress "0x3"
                            :name "Vault Three Equal"
                            :createTimeMillis 200}}])]
    (is (= ["0x1" "0x2"]
           (mapv :vault-address merged)))))

(deftest merge-vault-index-with-summaries-preserves-first-equal-age-duplicate-address-test
  (let [merged (vaults/merge-vault-index-with-summaries
                [{:summary {:vaultAddress "0x1"
                            :name "Vault One"
                            :createTimeMillis 100}}
                 {:summary {:vaultAddress "0x2"
                            :name "Vault Two Old"
                            :createTimeMillis 200}}
                 {:summary {:vaultAddress "0x2"
                            :name "Vault Two Equal"
                            :createTimeMillis 200}}]
                [])]
    (is (= ["0x1" "0x2"]
           (mapv :vault-address merged)))
    (is (= "Vault Two Old"
           (:name (second merged))))
    (is (= 200
           (:create-time-ms (second merged))))))

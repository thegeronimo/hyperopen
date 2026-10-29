(ns hyperopen.api.endpoints.account.borrow-lend
  "Info requests for HyperCore borrow/lend. Responses are translated by
  `hyperopen.borrow-lend.normalize` before they leave this namespace."
  (:require [clojure.string :as str]
            [hyperopen.api.request-policy :as request-policy]
            [hyperopen.borrow-lend.normalize :as normalize]))

(defn request-borrow-lend-reserve-states!
  [post-info! opts]
  (let [opts* (request-policy/apply-info-request-policy
               :borrow-lend-reserve-states
               (merge {:priority :high
                       :dedupe-key [:borrow-lend-reserve-states]}
                      opts))]
    (-> (post-info! {"type" "allBorrowLendReserveStates"} opts*)
        (.then normalize/normalize-reserve-states))))

(defn request-borrow-lend-user-state!
  [post-info! address opts]
  (if-not address
    (js/Promise.resolve nil)
    (let [requested-address (some-> address str str/lower-case)
          opts* (request-policy/apply-info-request-policy
                 :borrow-lend-user-state
                 (merge {:priority :high
                         :dedupe-key [:borrow-lend-user-state requested-address]}
                        opts))]
      (-> (post-info! {"type" "borrowLendUserState"
                       "user" address}
                      opts*)
          (.then normalize/normalize-user-state)))))

(defn request-borrow-lend-interest!
  "Interest accrual rows since `start-time-ms` (epoch ms)."
  [post-info! address start-time-ms opts]
  (if-not address
    (js/Promise.resolve [])
    (let [requested-address (some-> address str str/lower-case)
          opts* (request-policy/apply-info-request-policy
                 :borrow-lend-interest
                 (merge {:priority :low
                         :dedupe-key [:borrow-lend-interest requested-address start-time-ms]}
                        opts))]
      (-> (post-info! (cond-> {"type" "userBorrowLendInterest"
                               "user" address}
                        (number? start-time-ms) (assoc "startTime" start-time-ms))
                      opts*)
          (.then normalize/normalize-interest-rows)))))

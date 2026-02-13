(ns hyperopen.api.errors-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.api.errors :as api-errors]))

(deftest normalize-error-prefers-explicit-category-and-message-test
  (let [normalized (api-errors/normalize-error {:category :validation
                                                :message "invalid coin"})]
    (is (= :validation (:category normalized)))
    (is (= "invalid coin" (:message normalized)))))

(deftest normalize-error-uses-http-status-and-network-heuristics-test
  (is (= :protocol
         (:category (api-errors/normalize-error {:status 404 :error "not found"}))))
  (is (= :transport
         (:category (api-errors/normalize-error (js/Error. "Failed to fetch")))))
  (is (= :unexpected
         (:category (api-errors/normalize-error (js/Error. "boom"))))))

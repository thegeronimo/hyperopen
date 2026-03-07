(ns hyperopen.vaults.infrastructure.routes-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.vaults.infrastructure.routes :as routes]))

(deftest parse-vault-route-covers-list-detail-and-invalid-address-branches-test
  (is (= {:kind :list
          :path "/vaults"}
         (routes/parse-vault-route "/vaults/")))
  (is (= {:kind :detail
          :path "/vaults/0x1234567890abcdef1234567890abcdef12345678"
          :raw-vault-address "0x1234567890abcdef1234567890abcdef12345678"
          :vault-address "0x1234567890abcdef1234567890abcdef12345678"}
         (routes/parse-vault-route "/vaults/0x1234567890abcdef1234567890abcdef12345678?tab=about")))
  (is (= {:kind :detail
          :path "/vaults/not-an-address"
          :raw-vault-address "not-an-address"
          :vault-address nil}
         (routes/parse-vault-route "/vaults/not-an-address")))
  (is (= {:kind :other
          :path "/trade"}
         (routes/parse-vault-route "/trade"))))

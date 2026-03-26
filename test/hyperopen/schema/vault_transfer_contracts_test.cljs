(ns hyperopen.schema.vault-transfer-contracts-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.schema.vault-transfer-contracts :as contracts]))

(def vault-address
  "0x1234567890abcdef1234567890abcdef12345678")

(deftest preview-success-contract-accepts-canonical-shape-test
  (let [preview {:ok? true
                 :mode :deposit
                 :vault-address vault-address
                 :display-message nil
                 :request {:vault-address vault-address
                           :action {:type "vaultTransfer"
                                    :vaultAddress vault-address
                                    :isDeposit true
                                    :usd 2500000}}}]
    (is (true? (contracts/preview-result-valid? preview)))
    (is (= preview
           (contracts/assert-preview-result! preview {:boundary :test/contracts})))))

(deftest preview-success-contract-rejects-inconsistent-request-test
  (is (thrown-with-msg?
       js/Error
       #"vault transfer preview contract validation failed"
       (contracts/assert-preview-result!
        {:ok? true
         :mode :deposit
         :vault-address vault-address
         :display-message nil
         :request {:vault-address vault-address
                   :action {:type "vaultTransfer"
                            :vaultAddress "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                            :isDeposit true
                            :usd 2500000}}}
        {:boundary :test/contracts}))))

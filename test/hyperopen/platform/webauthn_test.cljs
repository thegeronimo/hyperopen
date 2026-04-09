(ns hyperopen.platform.webauthn-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.platform.webauthn :as webauthn]))

(def default-pub-key-cred-params
  @#'hyperopen.platform.webauthn/default-pub-key-cred-params)

(deftest create-passkey-credential-default-algorithms-test
  (is (= [{:type "public-key"
           :alg -7}
          {:type "public-key"
           :alg -257}]
         default-pub-key-cred-params)))

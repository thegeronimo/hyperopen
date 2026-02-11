(ns hyperopen.websocket.diagnostics-sanitize-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.websocket.diagnostics-sanitize :as sanitize]))

(def sample-address
  "0x1234567890123456789012345678901234567890")

(deftest sanitize-value-redact-mode-redacts-sensitive-fields-test
  (let [payload {:walletAddress sample-address
                 :privateKey "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                 :authorization "Bearer token"
                 :nested {:signature "0xdeadbeef"
                          :user "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"}
                 :nonSensitive "ok"}
        redacted (sanitize/sanitize-value :redact payload)]
    (is (= "<redacted>" (:walletAddress redacted)))
    (is (= "<redacted>" (:privateKey redacted)))
    (is (= "<redacted>" (:authorization redacted)))
    (is (= "<redacted>" (get-in redacted [:nested :signature])))
    (is (= "<redacted>" (get-in redacted [:nested :user])))
    (is (= "ok" (:nonSensitive redacted)))))

(deftest sanitize-value-mask-mode-masks-sensitive-fields-test
  (let [payload {:walletAddress sample-address
                 :token "secret-token"
                 :nested {:cookie "abcd1234"}
                 :nonSensitiveAddress sample-address}
        masked (sanitize/sanitize-value :mask payload)]
    (is (= "0x1234...67890" (:walletAddress masked)))
    (is (= "<masked>" (:token masked)))
    (is (= "<masked>" (get-in masked [:nested :cookie])))
    (is (= "0x1234...67890" (:nonSensitiveAddress masked)))))

(deftest sanitize-value-redact-mode-redacts-non-string-sensitive-values-test
  (let [payload {:signature {:r "0x1" :s "0x2" :v 27}
                 :session {:id "abc"}}
        redacted (sanitize/sanitize-value :redact payload)]
    (is (= "<redacted>" (:signature redacted)))
    (is (= "<redacted>" (:session redacted)))))

(deftest sanitize-value-reveal-mode-preserves-original-values-test
  (let [payload {:walletAddress sample-address
                 :token "secret-token"
                 :nested {:signature "0xdeadbeef"}
                 :nonSensitive "ok"}]
    (testing "Reveal mode should pass through raw values for privileged diagnostics views"
      (is (= payload (sanitize/sanitize-value :reveal payload))))))

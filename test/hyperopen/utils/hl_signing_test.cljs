(ns hyperopen.utils.hl-signing-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.utils.hl-signing :as signing]))

(defn- bytes->vec [bytes]
  (vec (array-seq bytes)))

(deftest hex-bytes-roundtrip-test
  (testing "hex input supports 0x prefix and odd lengths"
    (is (= [10 188] (bytes->vec (signing/hex->bytes "0xabc")))))
  (testing "bytes->hex and hex->bytes roundtrip"
    (let [hex "00ff10ab"
          bytes (signing/hex->bytes hex)]
      (is (= hex (signing/bytes->hex bytes))))))

(deftest build-typed-data-test
  (let [connection-id "0x1234"
        typed-data (signing/build-typed-data connection-id)]
    (is (= "Exchange" (get-in typed-data [:domain :name])))
    (is (= 1337 (get-in typed-data [:domain :chainId])))
    (is (= connection-id (get-in typed-data [:message :connectionId])))))

(deftest split-signature-test
  (let [r (apply str (repeat 64 "a"))
        s (apply str (repeat 64 "b"))
        v "1c"
        signature (str "0x" r s v)
        parts (signing/split-signature signature)]
    (is (= (str "0x" r) (:r parts)))
    (is (= (str "0x" s) (:s parts)))
    (is (= 28 (:v parts)))))

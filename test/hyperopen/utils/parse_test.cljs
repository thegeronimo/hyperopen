(ns hyperopen.utils.parse-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.utils.parse :as parse]))

(deftest parse-int-value-test
  (is (= 12 (parse/parse-int-value "12")))
  (is (= 12 (parse/parse-int-value 12.9)))
  (is (nil? (parse/parse-int-value "not-a-number"))))

(deftest parse-localized-int-value-test
  (is (= 12 (parse/parse-localized-int-value "12,9" "fr-FR")))
  (is (= 1234 (parse/parse-localized-int-value (str "1\u202F234") "fr-FR")))
  (is (= 1234 (parse/parse-localized-int-value "1,234" "en-US")))
  (is (= 12 (parse/parse-localized-int-value "12.9" "en-US")))
  (is (nil? (parse/parse-localized-int-value "not-a-number" "en-US"))))

(deftest normalize-localized-decimal-input-test
  (is (= "1234.56"
         (parse/normalize-localized-decimal-input "1,234.56" "en-US")))
  (is (= "1234.56"
         (parse/normalize-localized-decimal-input (str "1\u202F234,56") "fr-FR")))
  (is (= ".5"
         (parse/normalize-localized-decimal-input ",5" "fr-FR")))
  (is (nil? (parse/normalize-localized-decimal-input "1,2,3" "en-US")))
  (is (nil? (parse/normalize-localized-decimal-input "" "en-US"))))

(deftest parse-localized-decimal-test
  (is (= 1234.56
         (parse/parse-localized-decimal "1,234.56" "en-US")))
  (is (= 1234.56
         (parse/parse-localized-decimal (str "1\u202F234,56") "fr-FR")))
  (is (= 0.5
         (parse/parse-localized-decimal ",5" "fr-FR")))
  (is (nil? (parse/parse-localized-decimal "abc" "fr-FR"))))

(deftest parse-localized-currency-decimal-test
  (is (= 1234.56
         (parse/parse-localized-currency-decimal "$1,234.56" "en-US")))
  (is (= 1234.56
         (parse/parse-localized-currency-decimal (str "1\u202F234,56") "fr-FR")))
  (is (= -12.5
         (parse/parse-localized-currency-decimal "-12,5" "fr-FR")))
  (is (= 1000
         (parse/parse-localized-currency-decimal "1e3" "en-US")))
  (is (nil? (parse/parse-localized-currency-decimal "$abc" "en-US"))))

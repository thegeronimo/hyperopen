(ns hyperopen.i18n.locale-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.i18n.locale :as locale]
            [hyperopen.platform :as platform]))

(deftest normalize-locale-trims-and-validates-test
  (is (= "en-US" (locale/normalize-locale "en_US")))
  (is (= "en-US" (locale/normalize-locale " en-US ")))
  (is (nil? (locale/normalize-locale "not-a-locale")))
  (is (nil? (locale/normalize-locale nil))))

(deftest load-and-resolve-preferred-locale-test
  (with-redefs [platform/local-storage-get (fn [_] "en_US")
                locale/resolve-browser-locale (fn [] "de-DE")]
    (is (= "en-US" (locale/load-stored-locale)))
    (is (= "en-US" (locale/resolve-preferred-locale))))
  (with-redefs [platform/local-storage-get (fn [_] "not-a-locale")
                locale/resolve-browser-locale (fn [] "de-DE")]
    (is (nil? (locale/load-stored-locale)))
    (is (= "de-DE" (locale/resolve-preferred-locale))))
  (with-redefs [platform/local-storage-get (fn [_] nil)
                locale/resolve-browser-locale (fn [] nil)]
    (is (nil? (locale/load-stored-locale)))
    (is (= "en-US" (locale/resolve-preferred-locale)))))

(deftest resolve-browser-locale-picks-first-supported-value-test
  (with-redefs [locale/browser-locales (fn [] ["not-a-locale" "en_US"])]
    (is (= "en-US" (locale/resolve-browser-locale)))))

(deftest coalesce-locale-prefers-explicit-valid-value-test
  (with-redefs [locale/resolve-preferred-locale (fn [] "en-US")]
    (is (= "en-US" (locale/coalesce-locale "en_US")))
    (is (= "en-US" (locale/coalesce-locale "bad-locale")))
    (is (= "en-US" (locale/coalesce-locale nil)))))

(deftest persist-locale-validates-before-write-test
  (let [writes (atom [])]
    (with-redefs [platform/local-storage-set! (fn [k v]
                                                (swap! writes conj [k v]))]
      (is (= "en-US" (locale/persist-locale! "en_US")))
      (is (= [["ui-locale" "en-US"]] @writes))
      (is (nil? (locale/persist-locale! "bad-locale")))
      (is (= [["ui-locale" "en-US"]] @writes)))))

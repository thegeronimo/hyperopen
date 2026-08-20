(ns hyperopen.api.gateway.orders.twap-advanced-details-test
  "Wire coverage for the optional :details block a twapOrder carries when the user sets the
   TWAP ticket's Advanced Settings (Trigger Price, and Max Price for buys / Min Price for
   sells).

   Venue shape, as accepted by the exchange since the 2026-08-01 TWAP upgrade:

     {:type \"twapOrder\"
      :twap {:a :b :s :r :m :t}
      :details {:t {:p <trigger price> :a <true when the mark must rise TO the trigger>}
                :s <price at which the venue terminates the order>}}

   Both keys inside :details are always present, with the unused one nil. The action is
   msgpack-encoded and signed as-is, so key ORDER is part of the signature -- these tests
   pin it, because nothing else in the suite does."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.schema.order-request-contracts :as contracts]
            [hyperopen.api.gateway.orders.commands :as commands]))

(def ^:private command-context
  {:active-asset "BTC"
   :asset-idx 5
   :market {:market-type :perp
            :szDecimals 4
            :mark "100"}})

(def ^:private markless-command-context
  (update command-context :market dissoc :mark))

(defn- twap-form
  [twap]
  {:type :twap
   :side :buy
   :size "3"
   :reduce-only false
   :twap (merge {:hours 0 :minutes 30 :randomize false} twap)})

(defn- action
  ([twap] (action command-context twap))
  ([context twap] (:action (commands/build-order-request context (twap-form twap)))))

(deftest plain-twap-carries-no-details-key-test
  (testing "a TWAP with no advanced settings signs exactly as it always has"
    (let [plain (action {})]
      (is (= [:type :twap] (vec (keys plain))))
      (is (not (contains? plain :details)))
      ;; Blank strings are 'not set', not 'set to nothing'.
      (is (= plain (action {:trigger-px "" :stop-px ""})))
      (is (= plain (action {:trigger-px "   " :stop-px nil}))))))

(deftest twap-details-key-order-is-signed-order-test
  (testing "action keys stay type, twap, details"
    (is (= [:type :twap :details]
           (vec (keys (action {:trigger-px "120"}))))))

  (testing "details keys stay t, s and trigger keys stay p, a"
    (let [details (:details (action {:trigger-px "120" :stop-px "140"}))]
      (is (= [:t :s] (vec (keys details))))
      (is (= [:p :a] (vec (keys (:t details)))))))

  (testing "the order survives conversion to a JS object, which is what msgpack encodes"
    (let [js-action (clj->js (action {:trigger-px "120" :stop-px "140"}))]
      (is (= ["type" "twap" "details"] (vec (js-keys js-action))))
      (is (= ["t" "s"] (vec (js-keys (aget js-action "details"))))))))

(deftest twap-trigger-direction-is-inferred-from-the-mark-test
  (testing "a trigger above the mark arms on the way up"
    (is (= {:p "120" :a true} (:t (:details (action {:trigger-px "120"}))))))

  (testing "a trigger below the mark arms on the way down"
    (is (= {:p "80" :a false} (:t (:details (action {:trigger-px "80"}))))))

  (testing "a trigger exactly at the mark arms on the way up"
    (is (= {:p "100" :a true} (:t (:details (action {:trigger-px "100"})))))))

(deftest twap-details-carries-only-what-the-user-set-test
  (testing "trigger only"
    (let [details (:details (action {:trigger-px "120"}))]
      (is (= {:p "120" :a true} (:t details)))
      (is (nil? (:s details)))))

  (testing "stop only -- no mark is needed, because nothing has to be inferred"
    (let [details (:details (action markless-command-context {:stop-px "140"}))]
      (is (nil? (:t details)))
      (is (= "140" (:s details)))))

  (testing "both"
    (let [details (:details (action {:trigger-px "120" :stop-px "140"}))]
      (is (= {:p "120" :a true} (:t details)))
      (is (= "140" (:s details))))))

(deftest twap-details-prices-are-canonicalized-for-the-wire-test
  (testing "prices go through the same canonical-price-text path as every other wire price"
    ;; 5 significant figures for a perp with szDecimals 4.
    (is (= "120.12" (:p (:t (:details (action {:trigger-px "120.12000"}))))))
    (is (= "140.55" (:s (:details (action {:stop-px "140.55"})))))))

(deftest twap-fails-closed-rather-than-dropping-an-advanced-setting-test
  (testing "a trigger price the wire cannot express kills the order rather than the trigger"
    (is (nil? (commands/build-order-request command-context (twap-form {:trigger-px "0"}))))
    (is (nil? (commands/build-order-request command-context (twap-form {:trigger-px "-5"}))))
    (is (nil? (commands/build-order-request command-context (twap-form {:trigger-px "abc"})))))

  (testing "an unusable stop price kills the order too"
    (is (nil? (commands/build-order-request command-context (twap-form {:stop-px "0"})))))

  (testing "a trigger with no mark to infer its direction from kills the order"
    ;; Silently sending :a false here would arm the order in the wrong direction.
    (is (nil? (commands/build-order-request markless-command-context
                                            (twap-form {:trigger-px "120"}))))))

(deftest twap-details-requests-satisfy-the-runtime-wire-contract-test
  (doseq [[label twap] [["trigger only" {:trigger-px "120"}]
                        ["stop only" {:stop-px "140"}]
                        ["both" {:trigger-px "80" :stop-px "140"}]
                        ["none" {}]]]
    (testing label
      (let [request (commands/build-order-request command-context (twap-form twap))]
        (is (true? (contracts/twap-request-valid? request)))
        (is (= request (contracts/assert-twap-request! request {:vector label})))))))

(deftest seven-day-twap-builds-test
  (testing "the venue accepts runtimes up to 7 days; 10080 minutes must build"
    (is (= 10080 (get-in (action {:days 7 :hours 0 :minutes 0}) [:twap :m]))))

  (testing "past 7 days the builder fails closed"
    (is (nil? (commands/build-order-request
               command-context
               (twap-form {:days 7 :hours 0 :minutes 1}))))))

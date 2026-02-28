(ns hyperopen.asset-selector.active-market-cache-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.asset-selector.active-market-cache :as cache]
            [hyperopen.platform :as platform]))

(def normalize-deps
  {:normalize-display-text (fn [value]
                             (cond
                               (string? value)
                               (let [trimmed (str/trim value)]
                                 (when (seq trimmed) trimmed))

                               (number? value)
                               (str value)

                               :else nil))
   :normalize-market-type (fn [value]
                            (let [normalized (cond
                                               (keyword? value) (name value)
                                               (string? value) (-> value str/trim str/lower-case)
                                               :else nil)]
                              (case normalized
                                "perp" :perp
                                "spot" :spot
                                nil)))
   :parse-max-leverage (fn [value]
                         (let [n (js/parseInt (str value) 10)]
                           (when (and (number? n)
                                      (not (js/isNaN n)))
                             n)))
   :parse-market-index (fn [value]
                         (let [n (js/parseInt (str value) 10)]
                           (when (and (number? n)
                                      (not (js/isNaN n)))
                             n)))})

(deftest normalize-active-market-display-covers-required-and-optional-fields-test
  (is (nil? (cache/normalize-active-market-display nil normalize-deps)))
  (is (nil? (cache/normalize-active-market-display {:coin "   "} normalize-deps)))

  (is (= {:coin "ETH"}
         (cache/normalize-active-market-display {:coin " ETH "} normalize-deps)))

  (is (= {:coin "BTC"
          :key "perp:BTC"
          :symbol "BTC-USDC"
          :base "BTC"
          :quote "USDC"
          :dex "hyna"
          :market-type :perp
          :only-isolated? true
          :margin-mode :no-cross
          :idx 3
          :perp-dex-index 1
          :asset-id 110003
          :maxLeverage 25}
         (cache/normalize-active-market-display
           {:coin " BTC "
            :key " perp:BTC "
            :symbol " BTC-USDC "
            :base " BTC "
            :quote " USDC "
            :dex " hyna "
            :market-type " perp "
            :onlyIsolated "true"
            :marginMode "noCross"
            :idx "3"
            :perp-dex-index "1"
            :asset-id "110003"
            :maxLeverage "25"}
           normalize-deps))))

(deftest persist-active-market-display-persists-normalized-json-and-guards-errors-test
  (testing "valid normalized payload is persisted"
    (let [captured (atom nil)]
      (with-redefs [platform/local-storage-set! (fn [k v]
                                                  (reset! captured [k v]))]
        (cache/persist-active-market-display!
          {:coin " ETH "
           :symbol " ETH-USDC "
           :dex " hyna "
           :asset-id "110000"
           :onlyIsolated true
           :marginMode "strictIsolated"
           :market-type :perp
           :maxLeverage "30"}
          normalize-deps))
      (let [[k raw] @captured
            payload (js->clj (js/JSON.parse raw) :keywordize-keys true)]
        (is (= "active-market-display" k))
        (is (= "ETH" (:coin payload)))
        (is (= "ETH-USDC" (:symbol payload)))
        (is (= "hyna" (:dex payload)))
        (is (= 110000 (:asset-id payload)))
        (is (= true (:only-isolated? payload)))
        (is (= "strict-isolated" (:margin-mode payload)))
        (is (= "perp" (:market-type payload)))
        (is (= 30 (:maxLeverage payload))))))

  (testing "invalid normalized payload does not touch local storage"
    (let [calls (atom 0)]
      (with-redefs [platform/local-storage-set! (fn [_ _]
                                                  (swap! calls inc))]
        (cache/persist-active-market-display!
          {:coin "   "
           :symbol "ETH-USDC"}
          normalize-deps))
      (is (= 0 @calls))))

  (testing "local-storage failures are caught"
    (let [threw? (atom false)]
      (let [original-warn (.-warn js/console)]
        (set! (.-warn js/console) (fn [& _] nil))
        (try
          (with-redefs [platform/local-storage-set! (fn [_ _]
                                                      (throw (js/Error. "disk-full")))]
            (try
              (cache/persist-active-market-display!
                {:coin "ETH" :symbol "ETH-USDC"}
                normalize-deps)
              (catch :default _
                (reset! threw? true))))
          (finally
            (set! (.-warn js/console) original-warn))))
      (is (false? @threw?)))))

(deftest load-active-market-display-rejects-missing-mismatched-and-invalid-cached-data-test
  (testing "matching coin returns parsed normalized payload"
    (with-redefs [platform/local-storage-get
                  (fn [_]
                    (js/JSON.stringify
                      (clj->js {:coin "ETH"
                                :symbol "ETH-USDC"
                                :asset-id "7"
                                :onlyIsolated "true"
                                :marginMode "strictIsolated"
                                :market-type "perp"
                                :maxLeverage "25"})))]
      (is (= {:coin "ETH"
              :symbol "ETH-USDC"
              :asset-id 7
              :only-isolated? true
              :margin-mode :strict-isolated
              :market-type :perp
              :maxLeverage 25}
             (cache/load-active-market-display "ETH" normalize-deps)))))

  (testing "mismatched coin and invalid JSON return nil"
    (with-redefs [platform/local-storage-get (fn [_]
                                               (js/JSON.stringify (clj->js {:coin "BTC"})))]
      (is (nil? (cache/load-active-market-display "ETH" normalize-deps))))
    (with-redefs [platform/local-storage-get (fn [_] "{")]
      (is (nil? (cache/load-active-market-display "ETH" normalize-deps)))))

  (testing "blank active-asset short-circuits"
    (is (nil? (cache/load-active-market-display nil normalize-deps)))))

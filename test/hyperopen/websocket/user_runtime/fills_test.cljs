(ns hyperopen.websocket.user-runtime.fills-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.platform :as platform]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.websocket.user-runtime.fills :as fill-runtime]))

(deftest novel-fills-dedupes-using-fallback-identity-test
  (let [existing [{:coin "ETH"
                   :side "A"
                   :sz "0.5"
                   :px "2000"
                   :time 1000}]
        incoming [{:coin "ETH"
                   :side "A"
                   :sz "0.5"
                   :px "2000"
                   :time 1000}
                  {:coin "ETH"
                   :side "A"
                   :sz "0.25"
                   :px "2100"
                   :time 2000}]]
    (is (= [{:coin "ETH"
             :side "A"
             :sz "0.25"
             :px "2100"
             :time 2000}]
           (vec (fill-runtime/novel-fills existing incoming))))))

(deftest fill-toast-payloads-group-by-coin-and-side-test
  (is (= [{:headline "Bought 6 HYPE"
           :message "Bought 6 HYPE"
           :subline "At average price of $31.66667"}
          {:headline "Sold 1.25 SOL"
           :message "Sold 1.25 SOL"
           :subline "At average price of $90.79"}]
         (fill-runtime/fill-toast-payloads
          [{:tid 10
            :coin "HYPE"
            :side "B"
            :sz "4.00"
            :px "31.00"}
           {:tid 11
            :coin "HYPE"
            :side "B"
            :sz "2.00"
            :px "33.00"}
           {:tid 12
            :coin "SOL"
            :side "A"
            :sz "1.25"
            :px "90.79"}]))))

(deftest fill-identity-and-novel-fills-cover-direct-id-and-unkeyed-rows-test
  (let [fill-identity @#'fill-runtime/fill-identity]
    (is (nil? (fill-identity nil)))
    (is (= ["fill-123" :id]
           (fill-identity {:fillId "fill-123"})))
    (is (= [42 :id]
           (fill-identity {:fill-id 42})))
    (is (= [:fallback 1700 "ETH" "buy" "2.5" "1234.5"]
           (fill-identity {:timestamp 1700
                           :symbol "ETH"
                           :dir "buy"
                           :size "2.5"
                           :price "1234.5"})))
    (is (nil? (fill-identity {})))
    (is (= [{:note "opaque-row"}
            {:fillId "fill-456"}]
           (vec (fill-runtime/novel-fills
                 [{:fillId "fill-123"}]
                 [{:fillId "fill-123"}
                  {:note "opaque-row"}
                  {:fillId "fill-456"}]))))))

(deftest fill-toast-payloads-cover-direction-fallbacks-and-plain-messages-test
  (let [parse-finite-number @#'fill-runtime/parse-finite-number
        normalize-fill-side @#'fill-runtime/normalize-fill-side
        normalized-fill-row @#'fill-runtime/normalized-fill-row]
    (is (= 12.5 (parse-finite-number " 12.5 ")))
    (is (= 7 (parse-finite-number 7)))
    (is (nil? (parse-finite-number "not-a-number")))
    (is (= :sell
           (normalize-fill-side {:direction "open short"})))
    (is (= :buy
           (normalize-fill-side {:dir "close short"})))
    (is (nil? (normalize-fill-side {:direction "hold"})))
    (is (= {:coin "BTC"
            :side :buy
            :size 3
            :price 42000.5}
           (normalized-fill-row {:symbol " btc "
                                 :direction "close short"
                                 :filledSz "-3"
                                 :avgPx "42000.5"})))
    (is (= {:coin "SOL"
            :side :sell
            :size 2.5
            :price nil}
           (normalized-fill-row {:asset " sol "
                                 :side "SHORT"
                                 :filled "2.5"})))
    (is (nil? (normalized-fill-row {:coin "SOL"
                                    :side "B"
                                    :sz "0"})))
    (is (= [{:headline "Bought 3 BTC"
             :message "Bought 3 BTC"}
            {:headline "Sold 2.5 SOL"
             :message "Sold 2.5 SOL"}]
           (fill-runtime/fill-toast-payloads
            [{:symbol " btc "
              :direction "close short"
              :filledSz "-3"}
             {:asset " sol "
              :side "SHORT"
              :filled "2.5"}])))
    (is (= [{:message "Order filled: SOL."}]
           (fill-runtime/fill-toast-payloads
            [{:asset " SOL "}])))
    (is (= [{:message "Order filled."}]
           (fill-runtime/fill-toast-payloads
            [{}])))))

(deftest fill-formatters-and-toast-clear-wrappers-fall-back-to-safe-formatting-test
  (let [clear-timeout! @#'fill-runtime/clear-order-feedback-toast-timeout!
        schedule-clear! @#'fill-runtime/schedule-order-feedback-toast-clear!
        runtime (atom (runtime-state/default-runtime-state))
        store (atom {:ui {:toast {:kind :success
                                  :message "Filled"}
                          :toasts [{:id :toast-2
                                    :kind :success
                                    :message "Filled"}]}})
        captured-timeout (atom nil)
        cleared (atom [])]
    (is (= "0.0000"
           (@#'fill-runtime/format-fill-size "bad-size")))
    (is (= "$0.00"
           (@#'fill-runtime/format-fill-price "bad-price")))
    (swap! runtime assoc-in [:timeouts :order-toast] {:toast-1 :timeout-1
                                                      :toast-2 :timeout-2})
    (with-redefs [runtime-state/runtime runtime
                  platform/clear-timeout! (fn [timeout-id]
                                            (swap! cleared conj timeout-id))
                  platform/set-timeout! (fn [callback ms]
                                          (reset! captured-timeout [callback ms])
                                          :new-timeout)]
      (clear-timeout! :toast-1)
      (is (= [:timeout-1] @cleared))
      (is (= {:toast-2 :timeout-2}
             (get-in @runtime [:timeouts :order-toast])))
      (clear-timeout!)
      (is (= [:timeout-1 :timeout-2]
             @cleared))
      (is (= {}
             (get-in @runtime [:timeouts :order-toast])))
      (schedule-clear! store :toast-2)
      (is (= runtime-state/order-feedback-toast-duration-ms
             (second @captured-timeout)))
      (is (= :new-timeout
             (get-in @runtime [:timeouts :order-toast :toast-2])))
      ((first @captured-timeout))
      (is (nil? (get-in @store [:ui :toast])))
      (is (empty? (get-in @store [:ui :toasts])))
      (is (= {}
             (get-in @runtime [:timeouts :order-toast]))))))

(deftest show-user-fill-toast-stacks-toasts-through-feedback-runtime-test
  (let [runtime (atom (runtime-state/default-runtime-state))
        store (atom {:ui {:toast nil
                          :toasts []}})
        captured-timeouts (atom [])
        cleared (atom [])]
    (with-redefs [runtime-state/runtime runtime
                  platform/set-timeout! (fn [callback ms]
                                          (let [timeout-id (keyword (str "timeout-" (inc (count @captured-timeouts))))]
                                            (swap! captured-timeouts conj [callback ms timeout-id])
                                            timeout-id))
                  platform/clear-timeout! (fn [timeout-id]
                                            (swap! cleared conj timeout-id))]
      (fill-runtime/show-user-fill-toast!
       store
       [{:coin "HYPE"
         :side "B"
         :sz "1.5"
         :px "31.25"}
        {:asset "btc"
         :direction "close short"
         :filledSz "-2"}])
      (is (= 2 (count (get-in @store [:ui :toasts]))))
      (is (= ["Bought 1.5 HYPE"
              "Bought 2 BTC"]
             (mapv :headline (get-in @store [:ui :toasts]))))
      (is (= runtime-state/order-feedback-toast-duration-ms
             (second (last @captured-timeouts))))
      (is (= #{:timeout-1 :timeout-2}
             (set (vals (get-in @runtime [:timeouts :order-toast])))))
      (doseq [[callback _ms _timeout-id] @captured-timeouts]
        (callback))
      (is (nil? (get-in @store [:ui :toast])))
      (is (empty? (get-in @store [:ui :toasts])))
      (is (empty? @cleared)))))

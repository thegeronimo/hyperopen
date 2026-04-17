(ns hyperopen.websocket.user-runtime.fills-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.order.feedback-runtime :as feedback-runtime]
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
  (let [payloads (fill-runtime/fill-toast-payloads
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
                    :px "90.79"}])
        payload (first payloads)]
    (is (= 1 (count payloads)))
    (is (= {:toast-surface :trade-confirmation
            :variant :stack
            :headline "3 fills"
            :message "3 fills"
            :subline "HYPE, SOL"}
           (select-keys payload [:toast-surface :variant :headline :message :subline])))
    (is (= #{[:buy "HYPE" 4 31]
             [:buy "HYPE" 2 33]
             [:sell "SOL" 1.25 90.79]}
           (set (map (juxt :side :symbol :qty :price) (:fills payload)))))))

(deftest fill-toast-payloads-resolve-raw-market-ids-through-market-state-test
  (let [market-by-key {"spot:@107" {:coin "@107"
                                    :market-type :spot
                                    :symbol "AAPL/USDC"
                                    :base "AAPL"
                                    :quote "USDC"}}
        payload (first (fill-runtime/fill-toast-payloads
                        [{:coin "@107"
                          :side "B"
                          :sz "1"
                          :px "100"}]
                        market-by-key))]
    (is (= {:toast-surface :trade-confirmation
            :variant :pill
            :headline "Bought 1 AAPL"
            :message "Bought 1 AAPL"}
           (select-keys payload [:toast-surface :variant :headline :message])))
    (is (= "AAPL"
           (:symbol (first (:fills payload)))))
    (is (= [{:message "Order filled: AAPL."}]
           (fill-runtime/fill-toast-payloads
            [{:coin "@107"}]
            market-by-key)))))

(deftest fill-toast-payloads-classify-trade-confirmation-variants-test
  (let [ordinary [{:tid 1
                   :coin "HYPE"
                   :side "B"
                   :sz "0.26"
                   :px "44.273"
                   :time 1800000000000
                   :orderType "limit"}]
        market [{:tid 2
                 :coin "HYPE"
                 :side "B"
                 :sz "4.23"
                 :px "44.265"
                 :time 1800000000100
                 :orderType "market"}]
        stack [{:tid 3 :coin "HYPE" :side "B" :sz "0.25" :px "44.20" :time 1800000000200}
               {:tid 4 :coin "HYPE" :side "B" :sz "0.30" :px "44.30" :time 1800000003400}
               {:tid 5 :coin "SOL" :side "A" :sz "1.00" :px "198.10" :time 1800000006100}]
        consolidated [{:tid 6 :coin "HYPE" :side "B" :sz "0.25" :px "44.20" :time 1800000010000}
                      {:tid 7 :coin "HYPE" :side "B" :sz "0.30" :px "44.30" :time 1800000013300}
                      {:tid 8 :coin "HYPE" :side "B" :sz "0.20" :px "44.24" :time 1800000016600}
                      {:tid 9 :coin "HYPE" :side "B" :sz "0.28" :px "44.29" :time 1800000019900}]
        high-slippage [{:tid 10
                        :coin "BTC"
                        :side "A"
                        :sz "0.05"
                        :px "65124"
                        :time 1800000020000
                        :slippagePct "0.42"}]]
    (is (= :pill (:variant (first (fill-runtime/fill-toast-payloads ordinary)))))
    (is (= {:id "1"
            :side :buy
            :symbol "HYPE"
            :price 44.273
            :qty 0.26
            :orderType "limit"
            :ts 1800000000000}
           (select-keys (first (:fills (first (fill-runtime/fill-toast-payloads ordinary))))
                        [:id :side :symbol :price :qty :orderType :ts])))
    (is (= :detailed (:variant (first (fill-runtime/fill-toast-payloads market)))))
    (is (= :stack (:variant (first (fill-runtime/fill-toast-payloads stack)))))
    (is (= :consolidated (:variant (first (fill-runtime/fill-toast-payloads consolidated)))))
    (is (= :detailed (:variant (first (fill-runtime/fill-toast-payloads high-slippage)))))
    (is (every? #(= :trade-confirmation (:toast-surface %))
                (map first [(fill-runtime/fill-toast-payloads ordinary)
                            (fill-runtime/fill-toast-payloads market)
                            (fill-runtime/fill-toast-payloads stack)
                            (fill-runtime/fill-toast-payloads consolidated)])))))

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
            :display-coin "BTC"
            :id "BTC-buy-na-3-42000.5"
            :side :buy
            :size 3
            :qty 3
            :symbol "BTC"
            :price 42000.5
            :orderType "limit"
            :ts 0
            :slippagePct nil}
           (normalized-fill-row {:symbol " btc "
                                 :direction "close short"
                                 :filledSz "-3"
                                 :avgPx "42000.5"})))
    (is (= {:coin "SOL"
            :display-coin "SOL"
            :id "SOL-sell-na-2.5-22.5"
            :side :sell
            :size 2.5
            :qty 2.5
            :symbol "SOL"
            :price 22.5
            :orderType "limit"
            :ts 0
            :slippagePct nil}
           (normalized-fill-row {:asset " sol "
                                 :side "SHORT"
                                 :filled "2.5"
                                 :price "22.50"})))
    (is (nil? (normalized-fill-row {:asset " sol "
                                    :side "SHORT"
                                    :filled "2.5"})))
    (is (nil? (normalized-fill-row {:coin "SOL"
                                    :side "B"
                                    :sz "0"})))
    (let [payload (first (fill-runtime/fill-toast-payloads
                          [{:symbol " btc "
                            :direction "close short"
                            :filledSz "-3"
                            :avgPx "42000.5"}
                           {:asset " sol "
                            :side "SHORT"
                            :filled "2.5"
                            :price "22.50"}]))]
      (is (= {:toast-surface :trade-confirmation
              :variant :stack
              :headline "2 fills"
              :message "2 fills"
              :subline "BTC, SOL"}
             (select-keys payload [:toast-surface :variant :headline :message :subline])))
      (is (= #{[:buy "BTC" 3 42000.5]
               [:sell "SOL" 2.5 22.5]}
             (set (map (juxt :side :symbol :qty :price) (:fills payload))))))
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
         :filledSz "-2"
         :avgPx "42000.5"}])
      (is (= 1 (count (get-in @store [:ui :toasts]))))
      (is (= [{:variant :stack
               :headline "2 fills"
               :subline "HYPE, BTC"}]
             (mapv #(select-keys % [:variant :headline :subline])
                   (get-in @store [:ui :toasts]))))
      (is (= runtime-state/order-feedback-toast-duration-ms
             (second (last @captured-timeouts))))
      (is (= #{:timeout-1}
             (set (vals (get-in @runtime [:timeouts :order-toast])))))
      (doseq [[callback _ms _timeout-id] @captured-timeouts]
        (callback))
      (is (nil? (get-in @store [:ui :toast])))
      (is (empty? (get-in @store [:ui :toasts])))
      (is (empty? @cleared)))))

(deftest show-user-fill-toast-resolves-raw-market-ids-through-store-market-state-test
  (let [runtime (atom (runtime-state/default-runtime-state))
        store (atom {:asset-selector {:market-by-key {"spot:@107" {:coin "@107"
                                                                   :market-type :spot
                                                                   :symbol "AAPL/USDC"
                                                                   :base "AAPL"
                                                                   :quote "USDC"}}}
                     :ui {:toast nil
                          :toasts []}})
        captured-timeouts (atom [])]
    (with-redefs [runtime-state/runtime runtime
                  platform/set-timeout! (fn [callback ms]
                                          (let [timeout-id (keyword (str "timeout-" (inc (count @captured-timeouts))))]
                                            (swap! captured-timeouts conj [callback ms timeout-id])
                                            timeout-id))
                  platform/clear-timeout! (fn [_timeout-id] nil)]
      (fill-runtime/show-user-fill-toast!
       store
       [{:coin "@107"
         :side "B"
         :sz "1.5"
         :px "12.50"}])
      (is (= [{:variant :pill
               :headline "Bought 1.5 AAPL"}]
             (mapv #(select-keys % [:variant :headline])
                   (get-in @store [:ui :toasts])))))))

(deftest show-user-fill-toast-keeps-expanded-blotter-open-while-merging-fills-test
  (let [runtime (atom (assoc-in (runtime-state/default-runtime-state)
                                [:timeouts :order-toast "active-blotter"]
                                :active-timeout))
        store (atom {:ui {:toast nil
                          :toasts [{:id "active-blotter"
                                    :kind :success
                                    :toast-surface :trade-confirmation
                                    :variant :consolidated
                                    :expanded? true
                                    :headline "4 fills · Bought 1 HYPE"
                                    :message "4 fills · Bought 1 HYPE"
                                    :fills [{:id "1" :side :buy :symbol "HYPE" :price 44 :qty 0.25 :orderType "limit" :ts 1800000000000}
                                            {:id "2" :side :buy :symbol "HYPE" :price 44.1 :qty 0.25 :orderType "limit" :ts 1800000001000}
                                            {:id "3" :side :buy :symbol "HYPE" :price 44.2 :qty 0.25 :orderType "limit" :ts 1800000002000}
                                            {:id "4" :side :buy :symbol "HYPE" :price 44.3 :qty 0.25 :orderType "limit" :ts 1800000003000}]}]}})
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
       [{:tid 5
         :coin "HYPE"
         :side "B"
         :sz "0.25"
         :px "44.40"
         :time 1800000004000}])
      (let [toast (first (get-in @store [:ui :toasts]))]
        (is (= 1 (count (get-in @store [:ui :toasts]))))
        (is (= "active-blotter" (:id toast)))
        (is (true? (:expanded? toast)))
        (is (= false (:auto-timeout? toast)))
        (is (= :consolidated (:variant toast)))
        (is (= 5 (count (:fills toast)))))
      (is (= [:active-timeout] @cleared))
      (is (empty? @captured-timeouts))
      (is (= {}
             (get-in @runtime [:timeouts :order-toast]))))))

(deftest show-user-fill-toast-respects-fill-alert-preference-test
  (let [runtime (atom (runtime-state/default-runtime-state))
        enabled-store (atom {:trading-settings {:fill-alerts-enabled? true}
                             :ui {:toast nil
                                  :toasts []}})
        disabled-store (atom {:trading-settings {:fill-alerts-enabled? false}
                              :ui {:toast nil
                                   :toasts []}})
        toast-calls (atom [])]
    (with-redefs [runtime-state/runtime runtime
                  feedback-runtime/show-order-feedback-toast! (fn [store kind payload clear-fn]
                                                                (swap! toast-calls conj [store kind payload clear-fn])
                                                                nil)]
      (fill-runtime/show-user-fill-toast!
       enabled-store
       [{:coin "HYPE"
         :side "B"
         :sz "1.5"
         :px "31.25"}
        {:asset "btc"
         :direction "close short"
         :filledSz "-2"
         :avgPx "42000.5"}])
      (is (= 1 (count @toast-calls)))
      (is (= [{:variant :stack
               :headline "2 fills"
               :subline "HYPE, BTC"}]
             (mapv #(select-keys (nth % 2) [:variant :headline :subline])
                   @toast-calls)))
      (reset! toast-calls [])
      (fill-runtime/show-user-fill-toast!
       disabled-store
       [{:coin "HYPE"
         :side "B"
         :sz "1.5"
         :px "31.25"}
        {:asset "btc"
         :direction "close short"
         :filledSz "-2"}])
      (is (empty? @toast-calls))
      (is (= {:trading-settings {:fill-alerts-enabled? false}
              :ui {:toast nil
                   :toasts []}}
             @disabled-store)))))

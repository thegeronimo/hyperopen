(ns hyperopen.websocket.user-runtime.fills-test
  (:require [cljs.test :refer-macros [deftest is]]
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

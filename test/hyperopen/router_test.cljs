(ns hyperopen.router-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.routes :as portfolio-routes]
            [hyperopen.router :as router]))

(defn- with-global-property
  [property-name value f]
  (let [original-descriptor (js/Object.getOwnPropertyDescriptor js/globalThis property-name)]
    (js/Object.defineProperty js/globalThis property-name
                              #js {:value value
                                   :configurable true
                                   :writable true})
    (try
      (f)
      (finally
        (if original-descriptor
          (js/Object.defineProperty js/globalThis property-name original-descriptor)
          (js/Reflect.deleteProperty js/globalThis property-name))))))

(deftest normalize-path-supports-deep-link-variants-test
  (is (= "/trade" (router/normalize-path nil)))
  (is (= "/trade" (router/normalize-path "")))
  (is (= "/trade" (router/normalize-path "/")))
  (is (= "/trade" (router/normalize-path "/index.html")))
  (is (= "/trade" (router/normalize-path "/index.html?market=BTC")))
  (is (= "/staking" (router/normalize-path "/staking")))
  (is (= "/staking" (router/normalize-path "staking")))
  (is (= "/staking" (router/normalize-path "/staking/?tab=validators#history")))
  (is (= "/staking" (router/normalize-path " https://example.com/staking?tab=validators ")))
  (is (= "/vaults/0xABCDEF"
         (router/normalize-path "  /vaults/0xABCDEF///  "))))

(deftest normalize-location-path-prefers-hash-route-when-pathname-is-root-test
  (is (= "/staking"
         (router/normalize-location-path "/" "#/staking")))
  (is (= "/staking"
         (router/normalize-location-path "/index.html" "#/staking?tab=validators")))
  (is (= "/staking"
         (router/normalize-location-path nil "#/staking?tab=validators")))
  (is (= "/portfolio"
         (router/normalize-location-path "/portfolio" "#/staking")))
  (is (= "/trade"
         (router/normalize-location-path "/" "#")))
  (is (= "/trade"
         (router/normalize-location-path nil nil))))

(deftest trade-route-helpers-handle-asset-subroutes-and-encoding-test
  (is (true? (router/trade-route? "/trade")))
  (is (true? (router/trade-route? "/trade/xyz:GOLD")))
  (is (false? (router/trade-route? "/portfolio")))

  (is (nil? (router/trade-route-asset "/trade")))
  (is (= "BTC" (router/trade-route-asset "/trade/BTC")))
  (is (= "xyz:GOLD" (router/trade-route-asset "/trade/xyz:GOLD")))
  (is (= "MEOW/USDC" (router/trade-route-asset "/trade/MEOW/USDC")))
  (is (= "MEOW/USDC" (router/trade-route-asset "/trade/MEOW%2FUSDC")))
  (is (nil? (router/trade-route-asset "/trade/%E0%A4%A")))
  (is (nil? (router/trade-route-asset "/portfolio/BTC")))

  (is (= "/trade" (router/trade-route-path nil)))
  (is (= "/trade/xyz:GOLD" (router/trade-route-path "xyz:GOLD")))
  (is (= "/trade/MEOW/USDC" (router/trade-route-path "MEOW/USDC")))
  (is (= "/trade/HYPE%20TOKEN" (router/trade-route-path "HYPE TOKEN")))

  (is (= "CL" (router/trade-route-market-from-search "?market=CL&tab=positions")))
  (is (= "positions" (router/trade-route-tab-from-search "?market=CL&tab=positions")))
  (is (= "outcomes" (router/trade-route-tab-from-search "?market=CL&tab=outcomes")))
  (is (= "CL" (router/trade-route-asset-or-market "/trade/BTC" "?market=CL")))
  (is (= "BTC" (router/trade-route-asset-or-market "/trade/BTC" nil)))

  (is (= "/trade?market=CL&tab=positions"
         (router/trade-browser-path {:market "CL"
                                     :tab :positions})))
  (is (= "/trade?market=CL&tab=positions&spectate=0xabcdef"
         (router/trade-browser-path {:market "CL"
                                     :tab "positions"
                                     :spectate "0xabcdef"})))
  (is (= "/trade"
         (router/trade-browser-path {}))))

(deftest set-route-invokes-route-change-callback-with-normalized-path-test
  (let [store (atom {:router {:path "/trade"}})
        callbacks (atom [])]
    (router/set-route! store "portfolio" #(swap! callbacks conj %))
    (is (= "/portfolio" (get-in @store [:router :path])))
    (is (= ["/portfolio"] @callbacks))))

(deftest init-rebinds-popstate-listener-and-uses-latest-callback-test
  (let [store (atom {:router {:path "/trade"}})
        add-calls (atom [])
        remove-calls (atom [])
        listeners (atom {})
        callbacks-a (atom [])
        callbacks-b (atom [])
        location-object #js {:pathname "/trade"
                             :hash ""}]
    (with-global-property
      "window"
      #js {:addEventListener (fn [event-name handler]
                               (swap! add-calls conj [event-name handler])
                               (swap! listeners assoc event-name handler))
           :removeEventListener (fn [event-name handler]
                                  (swap! remove-calls conj [event-name handler]))}
      (fn []
        (with-global-property
          "location"
          location-object
          (fn []
            (router/init! store {:skip-route-set? true
                                 :on-route-change #(swap! callbacks-a conj %)})
            (is (= "/trade" (get-in @store [:router :path])))
            (let [first-handler (get @listeners "popstate")]
              (router/init! store {:skip-route-set? true
                                   :on-route-change #(swap! callbacks-b conj %)})
              (let [second-handler (get @listeners "popstate")]
                (is (fn? first-handler))
                (is (fn? second-handler))
                (is (not (identical? first-handler second-handler)))
                (is (= [["popstate" first-handler]]
                       @remove-calls))
                (aset location-object "pathname" "/portfolio")
                (second-handler nil)
                (is (= "/portfolio" (get-in @store [:router :path])))
                (is (empty? @callbacks-a))
                (is (= ["/portfolio"] @callbacks-b))
                (is (= [["popstate" first-handler]
                        ["popstate" second-handler]]
                       @add-calls))))))))))

(deftest portfolio-route-helpers-handle-trader-inspection-subroutes-test
  (let [trader "0x3333333333333333333333333333333333333333"]
    (is (true? (portfolio-routes/portfolio-route? "/portfolio")))
    (is (true? (portfolio-routes/portfolio-route? (str "/portfolio/trader/" trader))))
    (is (false? (portfolio-routes/portfolio-route? "/portfoliox")))
    (is (true? (portfolio-routes/trader-portfolio-route? (str "/portfolio/trader/" trader))))
    (is (= trader
           (portfolio-routes/trader-portfolio-address
            (str "/portfolio/trader/" trader "?ignored=true"))))
    (is (= (str "/portfolio/trader/" trader)
           (portfolio-routes/trader-portfolio-path (str " " trader " "))))))

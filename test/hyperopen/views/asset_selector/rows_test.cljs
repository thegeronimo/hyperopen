(ns hyperopen.views.asset-selector.rows-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.system :as app-system]
            [hyperopen.views.asset-selector.icons :as icons]
            [hyperopen.views.asset-selector.rows :as rows]
            [hyperopen.views.asset-selector.test-support :as support]
            [nexus.registry :as nxr]))

(deftest asset-list-rows-opt-into-paint-containment-for-fast-scrolls-test
  (let [row (rows/asset-list-item {:key "perp:BTC"
                                   :symbol "BTC-USDC"
                                   :coin "BTC"
                                   :market-type :perp}
                                  false
                                  false
                                  #{}
                                  #{}
                                  #{})
        attrs (second row)]
    (is (= "auto" (get-in attrs [:style :content-visibility])))
    (is (= "layout paint style" (get-in attrs [:style :contain])))
    (is (= "24px" (get-in attrs [:style :contain-intrinsic-size])))))

(deftest asset-list-item-sub-cent-formatting-test
  (testing "last price renders adaptive decimals for tiny assets"
    (let [asset {:key "perp:PUMP"
                 :symbol "PUMP-USDC"
                 :coin "PUMP"
                 :base "PUMP"
                 :mark 0.002028
                 :markRaw "0.002028"
                 :volume24h 1000
                 :change24h -0.000329
                 :change24hPct -13.95
                 :fundingRate 0.001
                 :market-type :perp}
          hiccup (rows/asset-list-item asset false false #{} #{} #{})
          strings (support/collect-strings hiccup)
          rendered (set strings)]
      (is (contains? rendered "$0.002028"))
      (is (not (contains? rendered "$0.00"))))))

(deftest asset-list-item-renders-dashes-when-market-data-missing-test
  (let [asset {:key "perp:ABC"
               :symbol "ABC-USDC"
               :coin "ABC"
               :base "ABC"
               :market-type :perp}
        hiccup (rows/asset-list-item asset false false #{} #{} #{})
        strings (set (support/collect-strings hiccup))]
    (is (contains? strings "—"))
    (is (not (contains? strings "+0.00 (0.00%)")))))

(deftest asset-list-item-applies-highlight-class-for-keyboard-navigation-test
  (let [asset {:key "perp:ABC"
               :symbol "ABC-USDC"
               :coin "ABC"
               :base "ABC"
               :market-type :perp}
        row (rows/asset-list-item asset false true #{} #{} #{})
        classes (set (support/collect-all-classes row))
        row-attrs (second row)]
    (is (contains? classes "asset-selector-row-surface"))
    (is (= "highlighted" (:data-row-state row-attrs)))
    (is (not (contains? classes "ring-primary")))))

(deftest favorite-button-uses-lucide-star-styling-for-inactive-and-active-states-test
  (let [inactive (icons/favorite-button false "perp:BTC")
        active (icons/favorite-button true "perp:BTC")
        inactive-attrs (second inactive)
        active-attrs (second active)
        inactive-icon (last inactive)
        active-icon (last active)
        inactive-classes (set (support/collect-all-classes inactive))
        active-classes (set (support/collect-all-classes active))]
    (is (= "Add favorite" (:aria-label inactive-attrs)))
    (is (= "Remove favorite" (:aria-label active-attrs)))
    (is (= "asset-selector-favorite-button" (:data-role inactive-attrs)))
    (is (= "none" (get-in inactive-icon [1 :fill])))
    (is (= "currentColor" (get-in active-icon [1 :fill])))
    (is (contains? inactive-classes "hover:bg-amber-400/10"))
    (is (contains? inactive-classes "group-hover:text-amber-200"))
    (is (contains? active-classes "text-amber-300"))
    (is (contains? active-classes "drop-shadow-[0_0_6px_rgba(245,158,11,0.18)]"))))

(deftest favorite-buttons-stop-row-bubbling-and-dispatch-toggle-test
  (let [dispatches* (atom [])
        stop-calls* (atom 0)
        desktop-button (icons/favorite-button false "perp:BTC")
        mobile-button (icons/mobile-favorite-button false "perp:BTC")
        desktop-click (get-in desktop-button [1 :on :click])
        mobile-click (get-in mobile-button [1 :on :click])
        event #js {}]
    (aset event "stopPropagation" (fn [] (swap! stop-calls* inc)))
    (with-redefs [app-system/store ::store
                  nxr/dispatch (fn [store event actions]
                                 (swap! dispatches* conj {:store store
                                                          :event event
                                                          :actions actions}))]
      (desktop-click event)
      (mobile-click event))
    (is (= 2 @stop-calls*))
    (is (= [{:store ::store
             :event nil
             :actions [[:actions/toggle-asset-favorite "perp:BTC"]]}
            {:store ::store
             :event nil
             :actions [[:actions/toggle-asset-favorite "perp:BTC"]]}]
           @dispatches*))))

(deftest asset-list-item-applies-left-aligned-numeric-utilities-test
  (let [asset {:key "perp:SOL"
               :symbol "SOL-USDC"
               :coin "SOL"
               :base "SOL"
               :mark 101.55
               :markRaw "101.55"
               :volume24h 123456
               :change24h 2.2
               :change24hPct 1.3
               :fundingRate 0.0001
               :openInterest 99999
               :market-type :perp}
        row (rows/asset-list-item asset false false #{} #{} #{})
        classes (set (support/collect-all-classes row))]
    (is (contains? classes "num"))
    (is (contains? classes "text-left"))
    (is (not (contains? classes "num-right")))))

(deftest asset-list-item-uses-fixed-row-height-and-single-line-symbol-test
  (let [asset {:key "perp:xyz:GOOGL"
               :symbol "GOOGL-USDC"
               :coin "xyz:GOOGL"
               :base "GOOGL"
               :market-type :perp
               :dex "xyz"
               :maxLeverage 10
               :mark 10
               :volume24h 100
               :change24hPct 1}
        row (rows/asset-list-item asset false false #{} #{} #{})
        classes (set (support/collect-all-classes row))]
    (is (contains? classes "h-6"))
    (is (contains? classes "box-border"))
    (is (not (contains? classes "border-b")))
    (is (contains? classes "truncate"))
    (is (contains? classes "whitespace-nowrap"))))

(deftest asset-list-item-does-not-render-market-icon-image-test
  (let [asset {:key "perp:BTC"
               :symbol "BTC-USDC"
               :coin "BTC"
               :base "BTC"
               :market-type :perp
               :mark 1
               :volume24h 10
               :change24hPct 1}
        row (rows/asset-list-item asset false false #{} #{} #{})
        img-node (support/find-first-node
                   row
                   (fn [candidate]
                     (and (vector? candidate)
                          (keyword? (first candidate))
                          (str/starts-with? (name (first candidate)) "img"))))]
    (is (nil? img-node))))

(deftest outcome-asset-list-item-renders-question-market-columns-test
  (let [asset {:key "outcome:0"
               :symbol "BTC above 78213 on May 3 at 2:00 AM?"
               :title "BTC above 78213 on May 3 at 2:00 AM?"
               :coin "#0"
               :market-type :outcome
               :mark 0.58
               :markRaw "0.57841"
               :volume24h 180824
               :openInterest 254722
               :change24hPct 4.87}
        row (rows/asset-list-item asset false false #{} #{} #{})
        strings (set (support/collect-strings row))]
    (is (contains? strings "BTC above 78213 on May 3 at 2:00 AM?"))
    (is (contains? strings "OUTCOME"))
    (is (contains? strings "58%"))
    (is (contains? strings "$180,824"))
    (is (contains? strings "$254,722"))))

(deftest mobile-outcome-asset-list-item-renders-chance-and-open-interest-test
  (let [asset {:key "outcome:0"
               :symbol "BTC above 78213 on May 3 at 2:00 AM?"
               :title "BTC above 78213 on May 3 at 2:00 AM?"
               :coin "#0"
               :market-type :outcome
               :mark 0.58
               :markRaw "0.57841"
               :volume24h 180824
               :openInterest 254722
               :change24hPct 4.87}
        row (rows/mobile-asset-list-item asset false false #{})
        strings (set (support/collect-strings row))]
    (is (contains? strings "OUTCOME"))
    (is (contains? strings "58%"))
    (is (contains? strings "$254,722"))))

(deftest asset-list-item-click-handlers-dispatch-selected-market-map-test
  (let [dispatches* (atom [])
        asset {:key "outcome:0"
               :symbol "BTC above 78213 on May 3 at 2:00 AM?"
               :coin "#0"
               :market-type :outcome}
        desktop-row (rows/asset-list-item asset false false #{} #{} #{})
        mobile-row (rows/mobile-asset-list-item asset false false #{})
        desktop-click (get-in desktop-row [1 :on :click])
        mobile-click (get-in mobile-row [1 :on :click])]
    (with-redefs [app-system/store ::store
                  nxr/dispatch (fn [store event actions]
                                 (swap! dispatches* conj {:store store
                                                          :event event
                                                          :actions actions}))]
      (desktop-click #js {})
      (mobile-click #js {}))
    (is (= [{:store ::store
             :event nil
             :actions [[:actions/select-asset-by-market-key "outcome:0"]]}
            {:store ::store
             :event nil
             :actions [[:actions/select-asset-by-market-key "outcome:0"]]}]
           @dispatches*))))

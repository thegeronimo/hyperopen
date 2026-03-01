(ns hyperopen.views.vault-detail.panels-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]
            [hyperopen.views.vault-detail.panels :as panels]))

(deftest detail-tab-button-applies-active-and-inactive-styles-test
  (let [active (panels/detail-tab-button {:value :about
                                          :label "About"}
                                         :about)
        inactive (panels/detail-tab-button {:value :about
                                            :label "About"}
                                           :vault-performance)]
    (is (= [[:actions/set-vault-detail-tab :about]]
           (get-in active [1 :on :click])))
    (is (contains? (hiccup/node-class-set active) "border-[#66e3c5]"))
    (is (contains? (hiccup/node-class-set inactive) "border-transparent"))))

(deftest render-tab-panel-renders-about-relationship-and-component-vaults-test
  (let [parent-address "0x9999999999999999999999999999999999999999"
        view (panels/render-tab-panel {:selected-tab :about
                                       :description ""
                                       :leader "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
                                       :relationship {:parent-address parent-address
                                                      :child-addresses ["0xaaa" "0xbbb"]}})
        nav-button (hiccup/find-first-node view
                                           #(= [[:actions/navigate (str "/vaults/" parent-address)]]
                                               (get-in % [1 :on :click])))
        text (set (hiccup/collect-strings view))]
    (is (some? nav-button))
    (is (contains? text "No vault description available."))
    (is (contains? text "This vault uses the following vaults as component strategies:"))
    (is (contains? text "0xaaa"))
    (is (contains? text "0xbbb"))))

(deftest render-tab-panel-renders-performance-tabs-test
  (let [vault-performance (panels/render-tab-panel {:selected-tab :vault-performance
                                                     :snapshot {:day 1.2
                                                                :week -2.0
                                                                :month 0
                                                                :all-time 10.5}})
        your-performance (panels/render-tab-panel {:selected-tab :your-performance
                                                   :metrics {:your-deposit 100
                                                             :all-time-earned -12.5}})
        vp-text (set (hiccup/collect-strings vault-performance))
        yp-text (set (hiccup/collect-strings your-performance))]
    (is (contains? vp-text "24H"))
    (is (contains? vp-text "7D"))
    (is (contains? vp-text "All-time"))
    (is (contains? yp-text "Your Deposits"))
    (is (contains? yp-text "All-time Earned"))))

(deftest relationship-links-only-render-for-child-relationships-test
  (let [parent-address "0x1234567890abcdef1234567890abcdef12345678"
        links (panels/relationship-links {:relationship {:type :child
                                                         :parent-address parent-address}})
        nav-button (hiccup/find-first-node links
                                           #(= [[:actions/navigate (str "/vaults/" parent-address)]]
                                               (get-in % [1 :on :click])))]
    (is (some? links))
    (is (some? nav-button))
    (is (nil? (panels/relationship-links {:relationship {:type :parent
                                                         :parent-address parent-address}})))))

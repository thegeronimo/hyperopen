(ns hyperopen.views.account-info.tabs.twap-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.account-info.tabs.twap :as twap-tab]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]))

(def ^:private active-grid-template-columns
  "minmax(110px,1fr) minmax(90px,0.9fr) minmax(110px,1fr) minmax(90px,0.85fr) minmax(150px,1.25fr) minmax(128px,1.4fr) minmax(88px,0.72fr) minmax(150px,1.1fr) minmax(96px,0.8fr)")

(def ^:private active-read-only-grid-template-columns
  "minmax(110px,1fr) minmax(90px,0.9fr) minmax(110px,1fr) minmax(90px,0.85fr) minmax(150px,1.25fr) minmax(128px,1.4fr) minmax(88px,0.72fr) minmax(150px,1.1fr)")

(def ^:private history-grid-template-columns
  "minmax(150px,1.2fr) minmax(110px,0.9fr) minmax(90px,0.82fr) minmax(100px,0.9fr) minmax(90px,0.82fr) minmax(110px,0.92fr) minmax(128px,1.4fr) minmax(88px,0.72fr) minmax(88px,0.72fr) minmax(90px,0.82fr)")

(defn- nodes-with-grid-template
  [node template-columns]
  (hiccup/find-all-nodes node #(= template-columns
                                  (get-in % [1 :style :grid-template-columns]))))

(deftest twap-active-table-renders-header-and-row-with-inline-grid-template-test
  (let [content (twap-tab/twap-tab-content
                 {:twap-state {:selected-subtab :active}
                  :twap-active-rows [{:twap-id 17
                                      :creation-time-ms 1700000000000
                                      :coin "xyz:CL"
                                      :side "B"
                                      :size "1.719"
                                      :executed-size "0.133"
                                      :average-price "87.215"
                                      :running-label "00:00:23 / 6 minutes"
                                      :reduce-only? false}]})
        grid-nodes (nodes-with-grid-template content active-grid-template-columns)]
    (is (= 2 (count grid-nodes)))
    (is (every? #(contains? (hiccup/node-class-set %) "grid") grid-nodes))
    (is (not-any? #(str/starts-with? % "grid-cols-[")
                  (mapcat hiccup/node-class-set grid-nodes)))))

(deftest twap-history-table-renders-header-and-row-with-inline-grid-template-test
  (let [content (twap-tab/twap-tab-content
                 {:twap-state {:selected-subtab :history}
                  :twap-history-rows [{:time-ms 1700000000000
                                       :coin "xyz:CL"
                                       :side "S"
                                       :size "1.719"
                                       :executed-size "0.133"
                                       :average-price "87.215"
                                       :total-runtime-label "6m"
                                       :reduce-only? false
                                       :randomize? false
                                       :status-key :finished
                                       :status-label "Finished"}]})
        grid-nodes (nodes-with-grid-template content history-grid-template-columns)]
    (is (= 2 (count grid-nodes)))
    (is (every? #(contains? (hiccup/node-class-set %) "grid") grid-nodes))
    (is (not-any? #(str/starts-with? % "grid-cols-[")
                  (mapcat hiccup/node-class-set grid-nodes)))))

(deftest twap-active-table-read-only-mode-omits-terminate-column-and-button-test
  (let [content (twap-tab/twap-tab-content
                 {:read-only? true
                  :twap-state {:selected-subtab :active
                               :read-only? true}
                  :twap-active-rows [{:twap-id 17
                                      :creation-time-ms 1700000000000
                                      :coin "xyz:CL"
                                      :side "B"
                                      :size "1.719"
                                      :executed-size "0.133"
                                      :average-price "87.215"
                                      :running-label "00:00:23 / 6 minutes"
                                      :reduce-only? false}]})
        header-strings (set (hiccup/collect-strings (first (vec (hiccup/node-children content)))))
        content-node (second (vec (hiccup/node-children content)))
        grid-nodes (nodes-with-grid-template content-node active-read-only-grid-template-columns)
        terminate-button (hiccup/find-first-node content #(and (= :button (first %))
                                                               (contains? (hiccup/direct-texts %) "Terminate")))]
    (is (= 2 (count grid-nodes)))
    (is (not (contains? header-strings "Terminate")))
    (is (nil? terminate-button))))

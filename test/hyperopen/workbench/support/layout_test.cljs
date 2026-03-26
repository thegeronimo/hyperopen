(ns hyperopen.workbench.support.layout-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.workbench.support.dispatch :as dispatch]
            [hyperopen.workbench.support.layout :as layout]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.workbench.support.state :as ws]))

(deftest interactive-shell-merges-base-and-extra-classes-test
  (testing "interactive-shell returns attrs with the workbench scene id and class vector"
    (dispatch/reset-registry!)
    (let [store (ws/create-store ::scene {:ok true})
          view (layout/interactive-shell store {} {:class ["max-w-[640px]"]}
                                         [:div "content"])
          attrs (second view)]
      (is (= :div (first view)))
      (is (= (dispatch/scene-attr store)
             (:data-workbench-scene-id attrs)))
      (is (= ["min-h-full" "max-w-[640px]"]
             (:class attrs))))))

(deftest shell-wrappers-apply-default-layout-classes-test
  (doseq [{:keys [label default-view custom-view expected-classes]}
          [{:label "panel-shell"
            :default-view (layout/panel-shell [:span "content"])
            :custom-view (layout/panel-shell {:class ["extra"] :id "panel"} [:span "content"])
            :expected-classes ["min-h-full" "rounded-2xl" "border-base-300"]}
           {:label "page-shell"
            :default-view (layout/page-shell [:span "content"])
            :custom-view (layout/page-shell {:class ["extra"] :id "page"} [:span "content"])
            :expected-classes ["min-h-screen" "p-4" "text-trading-text"]}
           {:label "mobile-shell"
            :default-view (layout/mobile-shell [:span "content"])
            :custom-view (layout/mobile-shell {:class ["extra"] :id "mobile"} [:span "content"])
            :expected-classes ["mx-auto" "w-[390px]" "max-w-full"]}
           {:label "desktop-shell"
            :default-view (layout/desktop-shell [:span "content"])
            :custom-view (layout/desktop-shell {:class ["extra"] :id "desktop"} [:span "content"])
            :expected-classes ["mx-auto" "w-full" "max-w-[1280px]"]}]]
    (testing label
      (is (= :div (first default-view)))
      (is (= :div (first custom-view)))
      (is (= [[:span "content"]]
             (vec (hiccup/node-children default-view))))
      (is (= [[:span "content"]]
             (vec (hiccup/node-children custom-view))))
      (doseq [class-name expected-classes]
        (is (contains? (hiccup/root-class-set default-view) class-name))
        (is (contains? (hiccup/root-class-set custom-view) class-name)))
      (is (contains? (hiccup/root-class-set custom-view) "extra"))
      (is (string? (:id (second custom-view)))))))

(deftest interactive-shell-three-arity-installs-scene-and-wraps-content-test
  (dispatch/reset-registry!)
  (let [store (ws/create-store ::interactive {:search ""})
        view (layout/interactive-shell store
                                       {:actions/set-search
                                        (fn [state _dispatch-data value]
                                          (assoc state :search value))}
                                       [:div "content"])
        attrs (second view)
        node #js {:getAttribute (fn [attr]
                                  (when (= attr "data-workbench-scene-id")
                                    (:data-workbench-scene-id attrs)))
                  :parentNode nil}]
    (is (= ["min-h-full"] (:class attrs)))
    (is (= (dispatch/scene-attr store)
           (:data-workbench-scene-id attrs)))
    (dispatch/dispatch! {:replicant/node node
                         :replicant/dom-event #js {:target #js {:value "sol"}}}
                        [:actions/set-search :event.target/value])
    (is (= "sol" (:search @store)))))

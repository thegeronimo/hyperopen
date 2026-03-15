(ns hyperopen.workbench.support.layout-test
  (:require [clojure.test :refer [deftest is testing]]
            [hyperopen.workbench.support.dispatch :as dispatch]
            [hyperopen.workbench.support.layout :as layout]
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

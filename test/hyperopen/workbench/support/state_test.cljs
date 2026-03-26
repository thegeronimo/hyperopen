(ns hyperopen.workbench.support.state-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.workbench.support.state :as ws]))

(deftest deep-merge-prefers-right-hand-scalars-and-merges-nested-maps-test
  (is (= {:router {:path "/portfolio"
                   :query {:tab "history"
                           :view "daily"}}
          :wallet {:connected? false}
          :theme :light}
         (ws/deep-merge {:router {:path "/trade"
                                  :query {:tab "history"}}
                         :wallet {:connected? true}
                         :theme :dark}
                        {:router {:path "/portfolio"
                                  :query {:view "daily"}}
                         :wallet {:connected? false}
                         :theme :light}))))

(deftest build-state-merges-defaults-with-scene-overrides-test
  (let [state (ws/build-state {:router {:path "/portfolio"}
                               :wallet {:connected? false}
                               :order-form-ui {:side :sell}}
                              {:wallet {:agent {:status :busy}}
                               :orders {:selected-tab :open-orders}})]
    (is (= "/portfolio" (get-in state [:router :path])))
    (is (false? (get-in state [:wallet :connected?])))
    (is (string? (get-in state [:wallet :address])))
    (is (= :busy (get-in state [:wallet :agent :status])))
    (is (true? (get-in state [:wallet :agent :enabled?])))
    (is (= :sell (get-in state [:order-form-ui :side])))
    (is (contains? state :ui))
    (is (contains? state :order-form-runtime))))

(deftest base-state-exposes-workbench-default-invariants-test
  (let [state (ws/base-state)]
    (is (= "/trade" (get-in state [:router :path])))
    (is (true? (get-in state [:wallet :connected?])))
    (is (= :ready (get-in state [:wallet :agent :status])))
    (is (= :local (get-in state [:wallet :agent :storage-mode])))
    (is (contains? state :ui))
    (is (contains? state :orders))
    (is (contains? state :order-form-runtime))))

(deftest store-and-state-helpers-update-scene-state-test
  (let [store (ws/create-store ::scene {:panel {:open? false}
                                        :sort nil})
        toggled (ws/toggle-in @store [:panel :open?])
        toggled-missing (ws/toggle-in @store [:filters :favorites-only?])
        updated (ws/set-in toggled [:panel :title] "Markets")
        sorted (ws/update-sort-in updated [:sort] :price)
        flipped (ws/update-sort-in sorted [:sort] :price)]
    (is (= {:panel {:open? false}
            :sort nil}
           @store))
    (is (= ::scene (:hyperopen.workbench/scene-id (meta store))))
    (is (true? (get-in toggled [:panel :open?])))
    (is (true? (get-in toggled-missing [:filters :favorites-only?])))
    (is (= "Markets" (get-in updated [:panel :title])))
    (is (= {:column :price
            :direction :desc}
           (:sort sorted)))
    (is (= {:column :price
            :direction :asc}
           (:sort flipped)))))

(deftest update-sort-state-toggles-active-column-and-resets-new-column-test
  (testing "switching to a new column always starts with descending order"
    (is (= {:column :price
            :direction :desc}
           (ws/update-sort-state {:column :name
                                  :direction :asc}
                                 :price))))
  (testing "the active column toggles between descending and ascending"
    (is (= {:column :price
            :direction :desc}
           (ws/update-sort-state {:column :price
                                  :direction :asc}
                                 :price)))
    (is (= {:column :price
            :direction :asc}
           (ws/update-sort-state {:column :price
                                  :direction :desc}
                                 :price)))))

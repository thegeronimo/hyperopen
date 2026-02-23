(ns hyperopen.test-support.hiccup-selectors
  (:require [hyperopen.views.account-info.test-support.hiccup :as hiccup]))

(defn button-text-predicate
  [label]
  (fn [node]
    (and (= :button (first node))
         (contains? (hiccup/direct-texts node) label))))

(defn select-id-predicate
  [id]
  (fn [node]
    (and (= :select (first node))
         (= id (get-in node [1 :id])))))

(defn input-id-predicate
  [id]
  (fn [node]
    (and (= :input (first node))
         (= id (get-in node [1 :id])))))

(def prev-button-predicate
  (button-text-predicate "Prev"))

(def next-button-predicate
  (button-text-predicate "Next"))

(def go-button-predicate
  (button-text-predicate "Go"))

(ns hyperopen.views.footer-view-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.footer-view :as footer-view]))

(defn- find-node [pred node]
  (cond
    (vector? node)
    (or (when (pred node) node)
        (some #(find-node pred %) (rest node)))

    (seq? node)
    (some #(find-node pred %) node)

    :else nil))

(defn- class-values [class-attr]
  (cond
    (nil? class-attr) []
    (string? class-attr) (remove str/blank? (str/split class-attr #"\s+"))
    (sequential? class-attr) (mapcat class-values class-attr)
    :else []))

(defn- root-class-set [node]
  (let [attrs (when (and (vector? node) (map? (second node)))
                (second node))]
    (set (class-values (:class attrs)))))

(deftest retry-button-visible-when-disconnected-test
  (let [view (footer-view/footer-view {:websocket {:status :disconnected}})
        retry-btn (find-node #(and (vector? %)
                                   (keyword? (first %))
                                   (str/starts-with? (name (first %)) "button")
                                   (= "Retry" (last %)))
                             view)]
    (is retry-btn)
    (is (= [[:actions/reconnect-websocket]]
           (get-in retry-btn [1 :on :click])))))

(deftest retry-button-hidden-when-connected-test
  (let [view (footer-view/footer-view {:websocket {:status :connected}})
        retry-btn (find-node #(and (vector? %)
                                   (keyword? (first %))
                                   (str/starts-with? (name (first %)) "button")
                                   (= "Retry" (last %)))
                             view)]
    (is (nil? retry-btn))))

(deftest footer-root-includes-sticky-layering-classes-test
  (let [view (footer-view/footer-view {:websocket {:status :connected}})
        classes (root-class-set view)]
    (is (contains? classes "sticky"))
    (is (contains? classes "bottom-0"))
    (is (contains? classes "z-40"))
    (is (contains? classes "bg-base-200"))
    (is (contains? classes "isolate"))))

(deftest footer-root-class-attr-is-collection-test
  (let [view (footer-view/footer-view {:websocket {:status :connected}})
        class-attr (get-in view [1 :class])]
    (is (coll? class-attr))
    (is (not (string? class-attr)))))

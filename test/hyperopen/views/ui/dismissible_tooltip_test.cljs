(ns hyperopen.views.ui.dismissible-tooltip-test
  "Escape dismisses a hover or focus tooltip without moving the pointer or
   focus, and the next hover or focus shows it again."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.ui.dismissible-tooltip :as dismissible-tooltip]))

(defn- fake-node
  "A group element: attributes in an atom, `inside` the nodes it contains."
  [inside]
  (let [attrs (atom {})]
    #js {:attrs attrs
         :isConnected true
         :setAttribute (fn [k v] (swap! attrs assoc k v))
         :removeAttribute (fn [k] (swap! attrs dissoc k))
         :contains (fn [other] (contains? inside other))}))

(defn- dismissed?
  [node]
  (= "true" (get @(.-attrs ^js node) dismissible-tooltip/dismissed-attribute)))

(defn- with-fake-document
  "Run `(f document listeners)` with a global `document` that records its
   keydown listeners."
  [f]
  (let [had? (.hasOwnProperty js/globalThis "document")
        original (aget js/globalThis "document")
        listeners (atom [])
        document #js {:activeElement nil
                      :addEventListener (fn [type listener]
                                          (swap! listeners conj [type listener]))
                      :removeEventListener (fn [type listener]
                                             (swap! listeners
                                                    (fn [entries]
                                                      (vec (remove #(= [type listener] %) entries)))))}]
    (try
      (aset js/globalThis "document" document)
      (f document listeners)
      (finally
        (if had?
          (aset js/globalThis "document" original)
          (js-delete js/globalThis "document"))))))

(defn- handler
  [event-id]
  (get-in (dismissible-tooltip/group-attrs) [:on event-id]))

(defn- fire!
  [event-id event]
  ((handler event-id) event))

(deftest escape-while-focused-dismisses-until-focus-moves-test
  (let [button #js {}
        node (fake-node #{button})]
    (fire! :keydown #js {:key "Enter" :currentTarget node})
    (is (not (dismissed? node)) "other keys do nothing")
    (fire! :keydown #js {:key "Escape" :currentTarget node})
    (is (dismissed? node))
    (fire! :focusout #js {:currentTarget node :relatedTarget button})
    (is (dismissed? node) "focus moving within the group keeps it dismissed")
    (fire! :focusout #js {:currentTarget node :relatedTarget nil})
    (is (not (dismissed? node)) "focus leaving clears it")
    (fire! :keydown #js {:key "Escape" :currentTarget node})
    (fire! :focusin #js {:currentTarget node})
    (is (not (dismissed? node)) "focus arriving again shows it again")))

(deftest escape-while-hovered-dismisses-through-a-document-listener-test
  (with-fake-document
    (fn [document listeners]
      (let [button #js {}
            node (fake-node #{button})]
        (fire! :mouseenter #js {:currentTarget node})
        (is (= 1 (count @listeners)) "one keydown listener while hovered")
        (is (= "keydown" (ffirst @listeners)))
        ((second (first @listeners)) #js {:key "Escape"})
        (is (dismissed? node))
        (fire! :mouseenter #js {:currentTarget node})
        (is (not (dismissed? node)) "a new hover shows it again")
        (is (= 1 (count @listeners)) "re-entering does not stack listeners")
        (fire! :mouseleave #js {:currentTarget node})
        (is (empty? @listeners) "leaving removes the listener")
        (testing "a tooltip dismissed while its button has focus stays dismissed when the pointer leaves"
          (aset document "activeElement" button)
          (fire! :mouseenter #js {:currentTarget node})
          ((second (first @listeners)) #js {:key "Escape"})
          (fire! :mouseleave #js {:currentTarget node})
          (is (dismissed? node))
          (is (empty? @listeners)))
        (testing "a group removed mid-hover drops its listener on the next key"
          (aset document "activeElement" nil)
          (fire! :mouseenter #js {:currentTarget node})
          (aset node "isConnected" false)
          ((second (first @listeners)) #js {:key "a"})
          (is (empty? @listeners)))))))

(deftest the-hide-class-is-spelled-out-for-tailwind-test
  (is (= "group-data-[tooltip-dismissed=true]:!invisible"
         dismissible-tooltip/hidden-when-dismissed-class))
  (is (= "data-tooltip-dismissed" dismissible-tooltip/dismissed-attribute)))

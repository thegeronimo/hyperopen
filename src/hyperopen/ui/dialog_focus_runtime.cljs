(ns hyperopen.ui.dialog-focus-runtime
  (:require [hyperopen.platform :as platform]))

(def ^:private restore-retry-delay-ms
  32)

(def ^:private restore-retry-attempts
  12)

(defonce ^:private remembered-restore-target* (atom nil))

(defn- connected-node?
  [node]
  (and node
       (true? (.-isConnected node))))

(defn- visible-node?
  [node]
  (when (connected-node? node)
    (let [style (js/getComputedStyle node)]
      (and (not= "none" (.-display style))
           (not= "hidden" (.-visibility style))))))

(defn- document-active-element
  []
  (some-> js/globalThis .-document .-activeElement))

(defn- document-body
  []
  (some-> js/globalThis .-document .-body))

(defn- document-query-selector
  [selector]
  (some-> js/globalThis .-document (.querySelector selector)))

(defn- contained-by?
  [parent child]
  (and parent
       child
       (or (= parent child)
           (.contains parent child))))

(defn- css-escape
  [value]
  (if-let [escape-fn (some-> js/globalThis .-CSS .-escape)]
    (escape-fn value)
    value))

(defn focus-restore-selector
  [node]
  (when node
    (let [id (some-> node (.getAttribute "id"))
          data-role (some-> node (.getAttribute "data-role"))
          data-parity-id (some-> node (.getAttribute "data-parity-id"))]
      (cond
        (seq id) (str "#" (css-escape id))
        (seq data-role) (str "[data-role=\"" (css-escape data-role) "\"]")
        (seq data-parity-id) (str "[data-parity-id=\"" (css-escape data-parity-id) "\"]")
        :else nil))))

(defn- distinct-selectors
  [& selectors]
  (->> selectors
       (filter seq)
       distinct
       vec))

(defn remember-restore-target!
  ([previous-active-element restore-selector]
   (remember-restore-target! {:previous-active-element previous-active-element
                              :exact-selector (focus-restore-selector previous-active-element)
                              :fallback-selector restore-selector}))
  ([{:keys [dialog-node previous-active-element exact-selector fallback-selector]}]
   (reset! remembered-restore-target*
           {:dialog-node dialog-node
            :surface-node (or (some-> dialog-node .-parentElement)
                              dialog-node)
            :previous-active-element previous-active-element
            :restore-selectors (distinct-selectors exact-selector fallback-selector)})))

(defn- restore-focus!
  [dialog-node surface-node previous-active-element restore-selectors]
  (letfn [(resolve-restore-node []
            (let [candidate (cond
                              (visible-node? previous-active-element)
                              previous-active-element

                              :else
                              (some document-query-selector restore-selectors))]
              (when (and candidate
                         (not= candidate (document-body))
                         (visible-node? candidate))
                candidate)))
          (focus-settled-away?
            [candidate active-element]
            (and candidate
                 active-element
                 (not= candidate active-element)
                 (not= active-element (document-body))
                 (connected-node? active-element)
                 (visible-node? active-element)
                 (not (contained-by? (or surface-node dialog-node)
                                     active-element))))
          (attempt-restore!
            [attempts-left]
            (let [candidate (resolve-restore-node)
                  active-element (document-active-element)]
              (if (focus-settled-away? candidate active-element)
                candidate
                (do
                  (when (and candidate
                             (not= candidate active-element))
                    (.focus candidate))
                  (when (pos? attempts-left)
                    (platform/set-timeout!
                     (fn []
                       (attempt-restore! (dec attempts-left)))
                     restore-retry-delay-ms))
                  candidate))))]
    (platform/queue-microtask!
     (fn []
       (attempt-restore! restore-retry-attempts)))))

(defn restore-remembered-focus!
  ([] (restore-remembered-focus! nil))
  ([dialog-node-override]
   (when-let [{:keys [dialog-node surface-node previous-active-element restore-selectors]}
              @remembered-restore-target*]
     (reset! remembered-restore-target* nil)
     (restore-focus! (or dialog-node-override dialog-node)
                     surface-node
                     previous-active-element
                     restore-selectors))))

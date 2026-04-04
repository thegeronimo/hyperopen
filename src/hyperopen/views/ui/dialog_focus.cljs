(ns hyperopen.views.ui.dialog-focus
  (:require [hyperopen.platform :as platform]
            [hyperopen.ui.dialog-focus-runtime :as dialog-focus-runtime]))

(def ^:private focusable-selector
  "button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex='-1'])")

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

(defn- contained-by?
  [parent child]
  (and parent
       child
       (or (= parent child)
           (.contains parent child))))

(defn- focusable-nodes
  [node]
  (->> (.querySelectorAll node focusable-selector)
       array-seq
       (filter visible-node?)
       vec))

(defn- focus-node!
  [node]
  (when node
    (platform/queue-microtask!
     (fn []
       (when (visible-node? node)
         (.focus node))))))

(def restore-remembered-focus! dialog-focus-runtime/restore-remembered-focus!)

(defn- trap-tab-key!
  [event node]
  (when (= "Tab" (.-key event))
    (let [focusables (focusable-nodes node)
          active-element (document-active-element)
          active-inside? (contained-by? node active-element)
          first-focusable (first focusables)
          last-focusable (last focusables)
          shift? (true? (.-shiftKey event))]
      (cond
        (empty? focusables)
        (do
          (.preventDefault event)
          (focus-node! node))

        (and shift?
             (or (not active-inside?)
                 (= active-element first-focusable)
                 (= active-element node)))
        (do
          (.preventDefault event)
          (focus-node! last-focusable))

        (and (not shift?)
             (or (not active-inside?)
                 (= active-element last-focusable)
                 (= active-element node)))
        (do
          (.preventDefault event)
          (focus-node! first-focusable))))))

(defn dialog-focus-on-render
  ([] (dialog-focus-on-render {}))
  ([{:keys [restore-selector]}]
   (fn [{:keys [:replicant/life-cycle :replicant/node :replicant/memory :replicant/remember]}]
     (case life-cycle
       :replicant.life-cycle/mount
       (let [previous-active-element (document-active-element)
             exact-selector (dialog-focus-runtime/focus-restore-selector previous-active-element)
             on-keydown (fn [event]
                          (trap-tab-key! event node))]
         (dialog-focus-runtime/remember-restore-target!
          {:dialog-node node
           :previous-active-element previous-active-element
           :exact-selector exact-selector
           :fallback-selector restore-selector})
         (.addEventListener node "keydown" on-keydown)
         (focus-node! (or (first (focusable-nodes node))
                          node))
         (remember {:on-keydown on-keydown
                    :previous-active-element previous-active-element
                    :exact-selector exact-selector
                    :restore-selector restore-selector}))

       :replicant.life-cycle/update
       (do
         (when-not (contained-by? node (document-active-element))
           (focus-node! (or (first (focusable-nodes node))
                            node)))
         (remember memory))

       :replicant.life-cycle/unmount
       (do
         (when-let [on-keydown (:on-keydown memory)]
           (.removeEventListener node "keydown" on-keydown))
         (restore-remembered-focus! node))

       nil))))

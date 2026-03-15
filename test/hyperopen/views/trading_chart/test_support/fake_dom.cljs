(ns hyperopen.views.trading-chart.test-support.fake-dom)

(declare make-fake-text-node)

(defn- node-text-content
  [node]
  (cond
    (= 3 (.-nodeType node))
    (or (.-data node)
        (.-nodeValue node)
        "")

    :else
    (apply str
           (map node-text-content
                (or (some-> node .-childNodes array-seq)
                    (some-> node .-children array-seq)
                    [])))))

(defn make-fake-text-node
  [text]
  (let [node #js {:nodeType 3
                  :parentNode nil
                  :childNodes #js []
                  :children #js []}]
    (js/Object.defineProperty
     node
     "textContent"
     #js {:configurable true
          :enumerable true
          :get (fn []
                 (or (.-data node)
                     (.-nodeValue node)
                     ""))
          :set (fn [value]
                 (let [next-text (or (some-> value str) "")]
                   (aset node "data" next-text)
                   (aset node "nodeValue" next-text)))})
    (set! (.-textContent node) text)
    node))

(defn make-fake-element [tag]
  (let [children (array)
        child-nodes (array)
        listeners (js-obj)
        style #js {}
        element #js {:tagName tag
                     :nodeType 1
                     :style style
                     :clientWidth 320
                     :clientHeight 200
                     :children children
                     :childNodes child-nodes
                     :listeners listeners
                     :parentNode nil
                     :firstChild nil
                     :className ""
                     :innerHTML ""}]
    (letfn [(refresh-first-child! []
              (set! (.-firstChild element)
                    (when (pos? (alength child-nodes))
                      (aget child-nodes 0))))
            (append-child-node! [child]
              (when child
                (when-let [current-parent (.-parentNode child)]
                  (.removeChild current-parent child))
                (.push child-nodes child)
                (when (= 1 (.-nodeType child))
                  (.push children child))
                (set! (.-parentNode child) element)
                (refresh-first-child!))
              child)
            (remove-child-node! [child]
              (let [child-node-index (.indexOf child-nodes child)]
                (when (>= child-node-index 0)
                  (.splice child-nodes child-node-index 1)
                  (when (= 1 (.-nodeType child))
                    (let [child-index (.indexOf children child)]
                      (when (>= child-index 0)
                        (.splice children child-index 1))))
                  (set! (.-parentNode child) nil)
                  (refresh-first-child!)))
              child)
            (clear-child-nodes! []
              (doseq [child (array-seq child-nodes)]
                (set! (.-parentNode child) nil))
              (.splice child-nodes 0 (alength child-nodes))
              (.splice children 0 (alength children))
              (refresh-first-child!))
            (set-text-content! [value]
              (let [next-text (or (some-> value str) "")]
                (clear-child-nodes!)
                (when (seq next-text)
                  (append-child-node! (make-fake-text-node next-text)))
                next-text))]
      (set! (.-setProperty style)
            (fn [prop value]
              (aset style prop value)))
      (js/Object.defineProperty
       element
       "textContent"
       #js {:configurable true
            :enumerable true
            :get (fn []
                   (node-text-content element))
            :set (fn [value]
                   (set-text-content! value))})
      (set! (.-appendChild element)
            (fn [child]
              (append-child-node! child)))
      (set! (.-removeChild element)
            (fn [child]
              (remove-child-node! child)))
      (set! (.-setAttribute element)
            (fn [attr value]
              (aset element attr value)))
      (set! (.-getAttribute element)
            (fn [attr]
              (aget element attr)))
      (set! (.-addEventListener element)
            (fn [event-name handler]
              (aset listeners event-name handler)))
      (set! (.-removeEventListener element)
            (fn [event-name _handler]
              (js-delete listeners event-name)))
      (set! (.-dispatchEvent element)
            (fn [event-name payload]
              (when-let [handler (aget listeners event-name)]
                (handler payload))))
      (set! (.-getBoundingClientRect element)
            (fn []
              #js {:top 0
                   :left 0
                   :width (.-clientWidth element)
                   :height (.-clientHeight element)
                   :right (.-clientWidth element)
                   :bottom (.-clientHeight element)})))
    element))

(defn make-fake-document []
  (let [listeners (js-obj)
        document #js {:listeners listeners}]
    (aset document
          "createElement"
          (fn [tag]
            (let [element (make-fake-element tag)]
              (aset element "ownerDocument" document)
              element)))
    (aset document
          "createElementNS"
          (fn [_ns tag]
            (let [element (make-fake-element tag)]
              (aset element "ownerDocument" document)
              element)))
    (aset document
          "createTextNode"
          (fn [text]
            (make-fake-text-node text)))
    (set! (.-addEventListener document)
          (fn [event-name handler]
            (aset listeners event-name handler)))
    (set! (.-removeEventListener document)
          (fn [event-name _handler]
            (js-delete listeners event-name)))
    (set! (.-dispatchEvent document)
          (fn [event-name payload]
            (when-let [handler (aget listeners event-name)]
              (handler payload))))
    document))

(defn collect-text-nodes [node]
  (cond
    (nil? node) []
    (= 3 (.-nodeType node)) [node]
    :else
    (mapcat collect-text-nodes
            (or (some-> node .-childNodes array-seq)
                (some-> node .-children array-seq)
                []))))

(defn collect-text-content [node]
  (->> (collect-text-nodes node)
       (keep (fn [text-node]
               (let [text (or (.-data text-node)
                              (.-nodeValue text-node)
                              "")]
                 (when (seq text)
                   text))))
       vec))

(defn find-dom-node [node pred]
  (when node
    (let [children (or (some-> node .-childNodes array-seq)
                       (some-> node .-children array-seq)
                       [])]
      (or (when (pred node) node)
          (some #(find-dom-node % pred) children)))))

(defn dispatch-dom-event-with-payload!
  [node event-name payload]
  (when node
    (let [listeners (.-listeners ^js node)
          handler (when listeners
                    (aget listeners event-name))]
      (when (fn? handler)
        (let [event (or payload #js {})]
          (when-not (fn? (.-preventDefault event))
            (aset event "preventDefault" (fn [] nil)))
          (when-not (fn? (.-stopPropagation event))
            (aset event "stopPropagation" (fn [] nil)))
          (handler event))))))

(defn dispatch-dom-event! [node event-name]
  (dispatch-dom-event-with-payload! node event-name nil))

(defn click-dom-node! [node]
  (dispatch-dom-event! node "click"))

(defn pointer-down-dom-node! [node]
  (dispatch-dom-event! node "pointerdown"))

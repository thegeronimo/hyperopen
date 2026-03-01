(ns hyperopen.views.trading-chart.test-support.fake-dom)

(defn make-fake-element [tag]
  (let [children (array)
        listeners (js-obj)
        element #js {:tagName tag
                     :style #js {}
                     :children children
                     :listeners listeners
                     :parentNode nil
                     :firstChild nil
                     :className ""
                     :innerHTML ""
                     :textContent ""}]
    (letfn [(refresh-first-child! []
              (set! (.-firstChild element)
                    (when (pos? (alength children))
                      (aget children 0))))]
      (set! (.-appendChild element)
            (fn [child]
              (.push children child)
              (set! (.-parentNode child) element)
              (refresh-first-child!)
              child))
      (set! (.-removeChild element)
            (fn [child]
              (let [idx (.indexOf children child)]
                (when (>= idx 0)
                  (.splice children idx 1)))
              (set! (.-parentNode child) nil)
              (refresh-first-child!)
              child))
      (set! (.-setAttribute element)
            (fn [attr value]
              (aset element attr value)))
      (set! (.-addEventListener element)
            (fn [event-name handler]
              (aset listeners event-name handler)))
      (set! (.-removeEventListener element)
            (fn [event-name _handler]
              (js-delete listeners event-name)))
      (set! (.-dispatchEvent element)
            (fn [event-name payload]
              (when-let [handler (aget listeners event-name)]
                (handler payload)))))
    element))

(defn make-fake-document []
  #js {:createElement (fn [tag]
                        (make-fake-element tag))
       :createElementNS (fn [_ns tag]
                          (make-fake-element tag))})

(defn collect-text-content [node]
  (let [own (when-let [text (.-textContent node)]
              (when (and (string? text) (seq text))
                [text]))
        children (or (some-> node .-children array-seq) [])]
    (into (vec own)
          (mapcat collect-text-content children))))

(defn find-dom-node [node pred]
  (when node
    (let [children (or (some-> node .-children array-seq) [])]
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

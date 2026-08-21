(ns hyperopen.pnl-share.raster
  "Rasterizes an inline SVG node into a PNG blob.

   An SVG loaded through an Image element renders in a sandboxed document that
   cannot fetch anything: no external stylesheet, no external font, no external
   image. Locally installed families such as system-ui still resolve, but a
   self-hosted webfont must be fetched separately, base64-encoded, and injected
   as an @font-face rule inside the SVG's own <defs><style> before the node is
   serialized. Fonts are fetched once per session and cached by URL.

   This namespace holds no view code on purpose: effect adapters may not import
   hyperopen.views.* without a boundary exception.")

(def ^:private svg-namespace
  "http://www.w3.org/2000/svg")

(def ^:private default-font-format
  "woff2")

(def ^:private base64-chunk-size
  8192)

(defonce ^:private font-base64-cache
  (atom {}))

(defn- document*
  []
  (when (exists? js/document)
    js/document))

(defn- positive-number
  [value]
  (let [num (js/parseFloat value)]
    (when (and (js/isFinite num)
               (pos? num))
      num)))

(defn- view-box-dimension
  [node index]
  (when-let [raw (some-> node (.getAttribute "viewBox"))]
    (let [parts (-> raw
                    (.trim)
                    (.split #"[\s,]+"))]
      (when (= 4 (.-length parts))
        (positive-number (aget parts index))))))

(defn- intrinsic-dimension
  [node attribute-name index]
  (or (positive-number (some-> node (.getAttribute attribute-name)))
      (view-box-dimension node index)
      (let [rect (some-> node (.getBoundingClientRect))]
        (positive-number (case index
                           2 (some-> rect .-width)
                           (some-> rect .-height))))))

(defn- array-buffer->base64
  [buffer]
  (let [bytes (js/Uint8Array. buffer)
        length (.-length bytes)
        chunks (array)]
    (loop [offset 0]
      (if (< offset length)
        (do
          (.push chunks
                 (.apply js/String.fromCharCode
                         nil
                         (.subarray bytes offset (min length (+ offset base64-chunk-size)))))
          (recur (+ offset base64-chunk-size)))
        (js/btoa (.join chunks ""))))))

(defn- fetch-font-base64
  [url]
  (if-let [cached (get @font-base64-cache url)]
    (js/Promise.resolve cached)
    (-> (js/fetch url)
        (.then (fn [response]
                 (when-not (.-ok response)
                   (throw (js/Error. (str "Could not load share-card font: " url))))
                 (.arrayBuffer response)))
        (.then (fn [buffer]
                 (let [encoded (array-buffer->base64 buffer)]
                   (swap! font-base64-cache assoc url encoded)
                   encoded))))))

(defn- font-face-rule
  [{:keys [family weight style format]} encoded]
  (let [format* (or format default-font-format)]
    (str "@font-face{font-family:'" family "';"
         "src:url(data:font/" format* ";base64," encoded ") format('" format* "');"
         "font-weight:" (or weight 400) ";"
         "font-style:" (or style "normal") ";"
         "font-display:block;}")))

(defn- font-face-css
  [fonts]
  (if (empty? fonts)
    (js/Promise.resolve "")
    (-> (js/Promise.all
         (into-array
          (map (fn [font]
                 (-> (fetch-font-base64 (:url font))
                     (.then (fn [encoded] (font-face-rule font encoded)))))
               fonts)))
        (.then (fn [rules] (.join rules ""))))))

(defn- clone-with-fonts
  [node css width height]
  (let [clone (.cloneNode node true)]
    (.setAttribute clone "xmlns" svg-namespace)
    ;; The on-screen preview scales itself with an inline style. Inside an
    ;; Image the SVG has no containing block, so a percentage width would
    ;; collapse to the 300x150 default replaced-element size and the PNG would
    ;; come out the wrong size. Pin the intrinsic size and drop the style.
    (.removeAttribute clone "style")
    (.setAttribute clone "width" (str width))
    (.setAttribute clone "height" (str height))
    (when (seq css)
      (let [doc (or (.-ownerDocument node) (document*))
            defs (.createElementNS doc svg-namespace "defs")
            style (.createElementNS doc svg-namespace "style")]
        (.setAttribute style "type" "text/css")
        (set! (.-textContent style) css)
        (.appendChild defs style)
        (if-let [first-child (.-firstChild clone)]
          (.insertBefore clone defs first-child)
          (.appendChild clone defs))))
    clone))

(defn- serialize-svg
  [node]
  (.serializeToString (js/XMLSerializer.) node))

(defn- svg-markup->image
  [markup]
  (js/Promise.
   (fn [resolve reject]
     (let [image (js/Image.)]
       (set! (.-onload image) (fn [_] (resolve image)))
       (set! (.-onerror image)
             (fn [_] (reject (js/Error. "Could not rasterize the share card SVG"))))
       (set! (.-src image)
             (str "data:image/svg+xml;charset=utf-8," (js/encodeURIComponent markup)))))))

(defn- image->canvas
  [image width height]
  (let [canvas (.createElement (document*) "canvas")]
    (set! (.-width canvas) width)
    (set! (.-height canvas) height)
    (let [context (.getContext canvas "2d")]
      (when-not context
        (throw (js/Error. "Canvas 2D context unavailable")))
      (.drawImage context image 0 0 width height)
      canvas)))

(defn- canvas->blob
  [canvas]
  (js/Promise.
   (fn [resolve reject]
     (.toBlob canvas
              (fn [blob]
                (if blob
                  (resolve blob)
                  (reject (js/Error. "Could not encode the share card PNG"))))
              "image/png"))))

(defn- normalize-fonts
  [fonts]
  (->> (or fonts [])
       (keep (fn [font]
               (let [font* (if (map? font)
                             font
                             (js->clj font :keywordize-keys true))]
                 (when (and (seq (:family font*))
                            (seq (:url font*)))
                   font*))))
       vec))

(defn- normalize-options
  [options]
  (let [options* (cond
                   (map? options) options
                   (nil? options) {}
                   :else (js->clj options :keywordize-keys true))]
    {:scale (or (positive-number (:scale options*)) 2)
     :data-url? (boolean (or (:data-url options*) (:dataUrl options*)))
     :fonts (normalize-fonts (:fonts options*))}))

(defn render-png
  "Rasterizes an inline SVG node to PNG.

   Returns a Promise of #js {:blob :width :height :dataUrl}. `:dataUrl` is nil
   unless the caller asks for it, because encoding one doubles the work for a
   result only the QA harness reads."
  [node options]
  (let [{:keys [scale data-url? fonts]} (normalize-options options)]
    (cond
      (nil? (document*))
      (js/Promise.reject (js/Error. "No document; cannot rasterize the share card"))

      (nil? node)
      (js/Promise.reject (js/Error. "No share card SVG node to rasterize"))

      :else
      (let [intrinsic-width (intrinsic-dimension node "width" 2)
            intrinsic-height (intrinsic-dimension node "height" 3)
            width (some-> intrinsic-width (* scale) js/Math.round)
            height (some-> intrinsic-height (* scale) js/Math.round)]
        (if-not (and width height)
          (js/Promise.reject (js/Error. "Share card SVG has no intrinsic size"))
          (-> (font-face-css fonts)
              (.then (fn [css]
                       (-> (clone-with-fonts node css intrinsic-width intrinsic-height)
                           (serialize-svg)
                           (svg-markup->image))))
              (.then (fn [image]
                       (let [canvas (image->canvas image width height)]
                         (-> (canvas->blob canvas)
                             (.then (fn [blob]
                                      #js {:blob blob
                                           :width width
                                           :height height
                                           :dataUrl (when data-url?
                                                      (.toDataURL canvas "image/png"))}))))))))))))

(goog/exportSymbol "hyperopen.pnl_share.raster.render_png" render-png)

(ns hyperopen.pnl-share.icons
  "Resolves a coin icon into a data: URI the share card can embed.

   The card is exported by serializing its <svg> and rasterizing it through an
   Image element, which runs in a sandboxed document that loads no external
   resource at all. The icon must therefore be inlined, which means reading its
   bytes. Hyperliquid's icon host serves from S3/CloudFront with no
   Access-Control-Allow-Origin, so a direct fetch rejects, an <img> taints the
   canvas, and an external href inside the serialized SVG silently draws the
   broken-image placeholder. The bytes come through the same-origin proxy at
   /api/coin-icon/<key>.svg instead.

   Every failure resolves to nil rather than throwing: a card with a monogram
   disc is fine, a card that will not export is not."
  (:require [clojure.string :as str]))

(def icon-proxy-prefix
  "/api/coin-icon/")

(def ^:private max-icon-bytes
  "Icons are 1-3KB. Anything an order of magnitude larger is not a coin icon and
   has no business being base64'd into a card."
  262144)

(defonce ^:private data-uri-cache
  (atom {}))

(defn icon-proxy-url
  [icon-key]
  (when-let [key* (some-> icon-key str str/trim not-empty)]
    (str icon-proxy-prefix (js/encodeURIComponent key*) ".svg")))

(defn cached-data-uri
  [icon-key]
  (get @data-uri-cache icon-key))

(defn- svg-markup?
  [text]
  (and (string? text)
       (str/includes? text "<svg")))

(defn- text->data-uri
  [text]
  (let [bytes (.encode (js/TextEncoder.) text)
        length (.-length bytes)
        chunks (array)]
    (loop [offset 0]
      (if (< offset length)
        (do
          (.push chunks
                 (.apply js/String.fromCharCode
                         nil
                         (.subarray bytes offset (min length (+ offset 8192)))))
          (recur (+ offset 8192)))
        (str "data:image/svg+xml;base64," (js/btoa (.join chunks "")))))))

(defn resolve-data-uri!
  "Promise of a data: URI for `icon-key`, or nil when the icon cannot be had.
   Results, including misses, are cached for the session so a card reopened on
   the same coin costs nothing."
  [icon-key]
  (let [key* (some-> icon-key str str/trim not-empty)]
    (cond
      (nil? key*)
      (js/Promise.resolve nil)

      (contains? @data-uri-cache key*)
      (js/Promise.resolve (get @data-uri-cache key*))

      :else
      (-> (js/fetch (icon-proxy-url key*))
          (.then (fn [response]
                   (if (.-ok response)
                     (.text response)
                     nil)))
          (.then (fn [text]
                   (let [data-uri (when (and (svg-markup? text)
                                             (<= (count text) max-icon-bytes))
                                    (text->data-uri text))]
                     (swap! data-uri-cache assoc key* data-uri)
                     data-uri)))
          (.catch (fn [_]
                    (swap! data-uri-cache assoc key* nil)
                    nil))))))

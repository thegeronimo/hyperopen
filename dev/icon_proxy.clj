(ns icon-proxy
  "Serves /api/coin-icon/<key>.svg from the shadow-cljs dev server.

  Named `icon-proxy` rather than `dev.icon-proxy` because shadow-cljs mounts
  \"dev\" as a classpath ROOT for this handler, so the namespace has to match the
  file's path relative to that root. The bb-driven dev/*.clj scripts run with the
  repo root on the classpath instead, which is why they carry the `dev.` prefix.

  Mirrors functions/api/coin-icon/[key].js so `npm run dev` behaves like
  production. Without it the share card's icon fetch 404s in development and
  every card silently falls back to its monogram disc, which reads as a bug in
  the card rather than a missing route."
  (:require [clojure.java.io :as io])
  (:import (java.io ByteArrayOutputStream)
           (java.net URL HttpURLConnection)))

(def ^:private prefix "/api/coin-icon/")
(def ^:private upstream "https://app.hyperliquid.xyz/coins/")
(def ^:private key-pattern #"[A-Za-z0-9_@.-]{1,48}(:[A-Za-z0-9_@.-]{1,48})?")

(defn- not-found []
  {:status 404
   :headers {"content-type" "text/plain"}
   :body "Not found"})

(defn- icon-key
  [uri]
  (let [raw (subs uri (count prefix))]
    (when (.endsWith raw ".svg")
      (let [decoded (java.net.URLDecoder/decode (subs raw 0 (- (count raw) 4)) "UTF-8")]
        (when (re-matches key-pattern decoded)
          decoded)))))

(defn- fetch-icon
  [key]
  (let [url (URL. (str upstream (.replace key ":" "%3A") ".svg"))
        conn ^HttpURLConnection (.openConnection url)]
    (.setConnectTimeout conn 5000)
    (.setReadTimeout conn 5000)
    (when (= 200 (.getResponseCode conn))
      (let [content-type (str (.getContentType conn))]
        (when (.contains content-type "image/svg+xml")
          (with-open [in (.getInputStream conn)
                      out (ByteArrayOutputStream.)]
            (io/copy in out)
            (.toByteArray out)))))))

(defn- spa-route?
  "A GET that reached this handler is a path with no file behind it. Treat it as
  an SPA route when it asks for HTML, or when it has no file extension at all --
  the latter keeps curl and health checks working, which an Accept-only test
  would answer with an empty 200."
  [uri headers]
  (let [accept (str (or (get headers "accept") (get headers :accept) ""))
        last-segment (last (.split (str uri) "/"))]
    (or (.contains accept "text/html")
        (not (.contains (str last-segment) ".")))))

(defn- push-state
  "shadow serves static files before calling this handler, so anything that
  reaches here and wants HTML is an SPA route. The vector form of :dev-http did
  this for us; configuring a :handler replaces it, and returning nil produces an
  empty 200 rather than falling through, which blanks the whole app."
  []
  (let [index (io/file "resources/public/index.html")]
    (when (.exists index)
      {:status 200
       :headers {"content-type" "text/html; charset=utf-8"
                 "cache-control" "no-cache"}
       :body (io/input-stream index)})))

(defn handler
  [{:keys [uri request-method headers]}]
  (cond
    (and (= :get request-method)
         (string? uri)
         (.startsWith uri prefix))
    (if-let [key (icon-key uri)]
      (if-let [bytes (try (fetch-icon key) (catch Exception _ nil))]
        {:status 200
         :headers {"content-type" "image/svg+xml"
                   "cache-control" "public, max-age=3600"
                   "access-control-allow-origin" "*"}
         :body (io/input-stream bytes)}
        (not-found))
      (not-found))

    (and (= :get request-method) (spa-route? uri headers))
    (push-state)

    ;; Anything else reaching here is a missing asset. Returning nil would make
    ;; shadow answer an empty 200, which reads as a corrupt file rather than a
    ;; missing one.
    :else (not-found)))

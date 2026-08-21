(ns hyperopen.platform)

(defn now-ms []
  (.now js/Date))

(defn random-value []
  (js/Math.random))

(defn confirm! [message]
  (js/confirm message))

(defn set-timeout! [f ms]
  (js/setTimeout f ms))

(defn clear-timeout! [timer-id]
  (js/clearTimeout timer-id))

(defn set-interval! [f ms]
  (js/setInterval f ms))

(defn clear-interval! [timer-id]
  (js/clearInterval timer-id))

(defn local-storage-set! [key value]
  (when (exists? js/localStorage)
    (js/localStorage.setItem key value)))

(defn local-storage-get [key]
  (when (exists? js/localStorage)
    (.getItem js/localStorage key)))

(defn local-storage-remove! [key]
  (when (exists? js/localStorage)
    (.removeItem js/localStorage key)))

(defn queue-microtask! [f]
  (if (fn? (.-queueMicrotask js/globalThis))
    (.queueMicrotask js/globalThis f)
    (set-timeout! f 0)))

(defn request-animation-frame! [f]
  (if (fn? (.-requestAnimationFrame js/globalThis))
    (.requestAnimationFrame js/globalThis f)
    (set-timeout! f 16)))

(defn schedule-idle-or-timeout!
  "Run f when the main thread is idle, or after timeout-ms at the latest."
  [f timeout-ms]
  (if (fn? (.-requestIdleCallback js/globalThis))
    (.requestIdleCallback js/globalThis f #js {:timeout timeout-ms})
    (set-timeout! f timeout-ms)))

(defonce ^:private inflate-raw-support (atom nil))

(defn- probe-inflate-raw-support []
  (boolean
   (when (and (exists? js/DecompressionStream)
              (exists? js/Blob)
              (exists? js/Response)
              (fn? (.-atob js/globalThis)))
     (try
       (js/DecompressionStream. "deflate-raw")
       true
       (catch :default _ false)))))

(defn inflate-raw-base64-supported?
  "Whether this runtime can inflate base64 raw-DEFLATE payloads.

   `deflate-raw` is a narrower capability than `DecompressionStream` itself, so the
   probe constructs one rather than sniffing for the constructor. Cached because
   callers hit it on every subscription change."
  []
  (if-some [cached @inflate-raw-support]
    cached
    (reset! inflate-raw-support (probe-inflate-raw-support))))

(defn inflate-raw-base64!
  "base64 raw-DEFLATE text -> Promise of the decompressed string.

   Raw DEFLATE carries neither a zlib nor a gzip header, so `deflate-raw` is the
   only decoder that accepts it."
  [base64-text]
  (js/Promise.
   (fn [resolve reject]
     (try
       (let [binary (.atob js/globalThis base64-text)
             length (.-length binary)
             bytes (js/Uint8Array. length)]
         (dotimes [index length]
           (aset bytes index (.charCodeAt binary index)))
         (-> (.stream (js/Blob. #js [bytes]))
             (.pipeThrough (js/DecompressionStream. "deflate-raw"))
             (js/Response.)
             (.text)
             (.then resolve)
             (.catch reject)))
       (catch :default error
         (reject error))))))

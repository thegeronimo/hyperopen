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

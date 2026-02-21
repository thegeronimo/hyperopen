(ns hyperopen.api.trading.test-support)

(def owner-address "0xowner")

(defn ready-agent-store
  [nonce-cursor]
  (atom {:wallet {:agent {:status :ready
                          :storage-mode :session
                          :nonce-cursor nonce-cursor}}}))

(defn install-fetch-stub!
  [handler]
  (let [original-fetch (.-fetch js/globalThis)]
    (set! (.-fetch js/globalThis) handler)
    (fn []
      (set! (.-fetch js/globalThis) original-fetch))))

(defn fetch-opts->map
  [opts]
  (js->clj opts :keywordize-keys true))

(defn json-body->map
  [json-body]
  (js->clj (js/JSON.parse json-body) :keywordize-keys true))

(defn fetch-body->map
  [opts]
  (-> opts
      fetch-opts->map
      :body
      json-body->map))

(defn json-response
  [payload]
  #js {:ok true
       :json (fn []
               (js/Promise.resolve (clj->js payload)))})

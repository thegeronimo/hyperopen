(ns hyperopen.test-support.info-client)

(defn fake-http-response
  ([status]
   (fake-http-response status {}))
  ([status payload]
   (doto (js-obj)
     (aset "status" status)
     (aset "ok" (= status 200))
     (aset "json" (fn [] (js/Promise.resolve (clj->js payload)))))))

(defn stepping-now-ms
  [values]
  (let [remaining (atom (vec values))
        last-value (atom (or (last values) 0))]
    (fn []
      (if-let [next-value (first @remaining)]
        (do
          (swap! remaining subvec 1)
          (reset! last-value next-value)
          next-value)
        @last-value))))

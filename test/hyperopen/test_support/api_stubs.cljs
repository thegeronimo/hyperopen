(ns hyperopen.test-support.api-stubs)

(defn- ->promise
  [result]
  (if (instance? js/Promise result)
    result
    (js/Promise.resolve result)))

(defn post-info-stub
  ([result-or-fn]
   (post-info-stub nil result-or-fn))
  ([calls result-or-fn]
   (fn [body opts]
     (when (some? calls)
       (swap! calls conj [body opts]))
     (->promise (if (fn? result-or-fn)
                  (result-or-fn body opts)
                  result-or-fn)))))

(defn post-info-body-stub
  ([calls result-or-fn]
   (fn [body opts]
     (swap! calls conj body)
     (->promise (if (fn? result-or-fn)
                  (result-or-fn body opts)
                  result-or-fn)))))

(defn signing-stub
  ([] (signing-stub nil))
  ([calls]
   (fn [private-key action nonce & opts]
     (when (some? calls)
       (swap! calls conj [private-key action nonce opts]))
     (js/Promise.resolve
      (clj->js {:r "0x01"
                :s "0x02"
                :v 27})))))

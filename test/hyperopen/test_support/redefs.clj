(ns hyperopen.test-support.redefs)

(defmacro with-direct-redefs
  [bindings & body]
  (when (odd? (count bindings))
    (throw (IllegalArgumentException.
            "with-direct-redefs requires an even number of binding forms")))
  (if (seq bindings)
    (let [[target replacement & more] bindings
          original-sym (gensym (str (munge (name target)) "_orig__"))]
      `(let [~original-sym ~target]
         (set! ~target ~replacement)
         (try
           (with-direct-redefs [~@more] ~@body)
           (finally
             (set! ~target ~original-sym)))))
    `(do ~@body)))

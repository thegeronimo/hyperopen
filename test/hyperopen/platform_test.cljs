(ns hyperopen.platform-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.platform :as platform]))

(deftest platform-wrapper-functions-test
  (let [orig-confirm (.-confirm js/globalThis)
        orig-set-timeout (.-setTimeout js/globalThis)
        orig-clear-timeout (.-clearTimeout js/globalThis)
        orig-set-interval (.-setInterval js/globalThis)
        orig-clear-interval (.-clearInterval js/globalThis)
        calls (atom [])]
    (try
      (set! (.-confirm js/globalThis) (fn [msg]
                                        (swap! calls conj [:confirm msg])
                                        true))
      (set! (.-setTimeout js/globalThis) (fn [f ms]
                                           (swap! calls conj [:set-timeout ms])
                                           (when (fn? f) (f))
                                           :timeout-id))
      (set! (.-clearTimeout js/globalThis) (fn [id]
                                             (swap! calls conj [:clear-timeout id])
                                             :cleared-timeout))
      (set! (.-setInterval js/globalThis) (fn [f ms]
                                            (swap! calls conj [:set-interval ms])
                                            (when (fn? f) (f))
                                            :interval-id))
      (set! (.-clearInterval js/globalThis) (fn [id]
                                              (swap! calls conj [:clear-interval id])
                                              :cleared-interval))

      (is (number? (platform/now-ms)))
      (is (number? (platform/random-value)))
      (is (true? (platform/confirm! "continue?")))
      (is (= :timeout-id (platform/set-timeout! (fn []) 1)))
      (is (= :cleared-timeout (platform/clear-timeout! :timeout-id)))
      (is (= :interval-id (platform/set-interval! (fn []) 5)))
      (is (= :cleared-interval (platform/clear-interval! :interval-id)))

      ;; These should be safe regardless of environment localStorage availability.
      (platform/local-storage-set! "hyperopen:test" "ok")
      (platform/local-storage-get "hyperopen:test")
      (platform/local-storage-remove! "hyperopen:test")

      (is (some #(= [:confirm "continue?"] %) @calls))
      (is (some #(= [:set-timeout 1] %) @calls))
      (is (some #(= [:set-interval 5] %) @calls))
      (finally
        (set! (.-confirm js/globalThis) orig-confirm)
        (set! (.-setTimeout js/globalThis) orig-set-timeout)
        (set! (.-clearTimeout js/globalThis) orig-clear-timeout)
        (set! (.-setInterval js/globalThis) orig-set-interval)
        (set! (.-clearInterval js/globalThis) orig-clear-interval)))))

(deftest queue-microtask-and-animation-frame-fallbacks-test
  (let [orig-queue-microtask (.-queueMicrotask js/globalThis)
        orig-request-animation-frame (.-requestAnimationFrame js/globalThis)
        queue-called? (atom false)
        raf-called? (atom false)]
    (try
      (set! (.-queueMicrotask js/globalThis) nil)
      (set! (.-requestAnimationFrame js/globalThis) nil)
      (with-redefs [hyperopen.platform/set-timeout! (fn [f _ms]
                                                      (f)
                                                      :fallback-timeout)]
        (is (= :fallback-timeout
               (platform/queue-microtask! #(reset! queue-called? true))))
        (is (= :fallback-timeout
               (platform/request-animation-frame! #(reset! raf-called? true)))))
      (is (true? @queue-called?))
      (is (true? @raf-called?))
      (finally
        (set! (.-queueMicrotask js/globalThis) orig-queue-microtask)
        (set! (.-requestAnimationFrame js/globalThis) orig-request-animation-frame)))))

(deftest queue-microtask-and-animation-frame-native-paths-test
  (async done
    (let [orig-queue-microtask (.-queueMicrotask js/globalThis)
          orig-request-animation-frame (.-requestAnimationFrame js/globalThis)
          calls (atom [])]
      (try
        (set! (.-queueMicrotask js/globalThis) (fn [f]
                                                  (swap! calls conj :queue-native)
                                                  (f)
                                                  :queue-native-id))
        (set! (.-requestAnimationFrame js/globalThis) (fn [f]
                                                         (swap! calls conj :raf-native)
                                                         (f 123)
                                                         :raf-native-id))
        (is (= :queue-native-id (platform/queue-microtask! (fn [] nil))))
        (is (= :raf-native-id (platform/request-animation-frame! (fn [_ts] nil))))
        (is (= [:queue-native :raf-native] @calls))
        (finally
          (set! (.-queueMicrotask js/globalThis) orig-queue-microtask)
          (set! (.-requestAnimationFrame js/globalThis) orig-request-animation-frame)
          (done))))))

(ns hyperopen.websocket.runtime-state-coverage-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.config :as app-config]
            [hyperopen.runtime.state :as runtime-state]))

(deftest runtime-state-message-config-coverage-test
  (is (= (get-in app-config/config [:messages :agent-storage-mode-reset])
         runtime-state/agent-storage-mode-reset-message)))

(deftest runtime-state-mark-and-reset-coverage-test
  (let [runtime (runtime-state/make-runtime-state)]
    (is (false? (runtime-state/app-started? runtime)))
    (is (false? (runtime-state/runtime-bootstrapped? runtime)))

    (is (true? (runtime-state/mark-app-started! runtime)))
    (is (true? (runtime-state/app-started? runtime)))
    (is (false? (runtime-state/mark-app-started! runtime)))

    (is (true? (runtime-state/mark-runtime-bootstrapped! runtime)))
    (is (true? (runtime-state/runtime-bootstrapped? runtime)))
    (is (false? (runtime-state/mark-runtime-bootstrapped! runtime)))

    (swap! runtime assoc
           :app-started? true
           :runtime-bootstrapped? true
           :timeouts {:wallet-copy :timeout-id
                      :order-toast {:toast-1 :toast-id}})
    (runtime-state/reset-runtime-state! runtime)

    (is (= (runtime-state/default-runtime-state) @runtime))
    (is (false? (runtime-state/app-started? runtime)))
    (is (false? (runtime-state/runtime-bootstrapped? runtime)))))

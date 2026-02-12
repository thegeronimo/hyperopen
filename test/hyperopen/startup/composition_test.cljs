(ns hyperopen.startup.composition-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.startup.composition :as startup-composition]
            [hyperopen.startup.init :as startup-init]
            [hyperopen.startup.runtime :as startup-runtime-lib]))

(deftest schedule-startup-summary-log-delegates-to-runtime-module-test
  (let [calls (atom [])]
    (with-redefs [startup-runtime-lib/schedule-startup-summary-log!
                  (fn [deps]
                    (swap! calls conj deps))]
      (startup-composition/schedule-startup-summary-log!
       {:startup-runtime (atom {})
        :store (atom {})
        :get-request-stats (fn [] {})
        :delay-ms 1234
        :log-fn (fn [& _] nil)}))
    (is (= 1 (count @calls)))
    (is (= 1234 (:delay-ms (first @calls))))))

(deftest initialize-remote-data-streams-delegates-to-runtime-module-test
  (let [calls (atom [])]
    (with-redefs [startup-runtime-lib/initialize-remote-data-streams!
                  (fn [deps]
                    (swap! calls conj deps))]
      (startup-composition/initialize-remote-data-streams!
       {:store (atom {})
        :ws-url "wss://example.test/ws"
        :log-fn (fn [& _] nil)
        :init-connection! (fn [& _] nil)
        :init-active-ctx! (fn [& _] nil)
        :init-orderbook! (fn [& _] nil)
        :init-trades! (fn [& _] nil)
        :init-user-ws! (fn [& _] nil)
        :init-webdata2! (fn [& _] nil)
        :dispatch! (fn [& _] nil)
        :install-address-handlers! (fn [] nil)
        :start-critical-bootstrap! (fn [] nil)
        :schedule-deferred-bootstrap! (fn [] nil)}))
    (is (= 1 (count @calls)))
    (is (= "wss://example.test/ws"
           (:ws-url (first @calls))))))

(deftest init-delegates-to-startup-init-module-test
  (let [calls (atom [])]
    (with-redefs [startup-init/init!
                  (fn [deps]
                    (swap! calls conj deps))]
      (startup-composition/init! {:log-fn (fn [& _] nil)
                                  :store (atom {})}))
    (is (= 1 (count @calls)))
    (is (map? (first @calls)))))

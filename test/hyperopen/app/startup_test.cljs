(ns hyperopen.app.startup-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.app.startup :as app-startup]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.startup.collaborators :as startup-collaborators]
            [hyperopen.startup.init :as startup-init]
            [hyperopen.startup.runtime :as startup-runtime]))

(deftest init-builds-startup-sequence-from-permanent-boundaries-test
  (let [store (atom {:active-asset "BTC"})
        runtime (atom {:startup {}})
        captured-init-deps (atom nil)
        summary-calls (atom [])]
    (with-redefs [startup-collaborators/startup-base-deps
                  (fn [deps]
                    (merge
                     {:store (:store deps)
                      :runtime (:runtime deps)
                      :dispatch! (fn [& _] nil)
                      :log-fn (fn [& _] nil)}
                     deps))
                  startup-init/init!
                  (fn [deps]
                    (reset! captured-init-deps deps))
                  startup-runtime/schedule-startup-summary-log!
                  (fn [deps]
                    (swap! summary-calls conj deps))]
      (app-startup/init! {:runtime runtime
                          :store store})
      (is (map? @captured-init-deps))
      (is (identical? startup-runtime/default-startup-runtime-state
                      (:default-startup-runtime-state @captured-init-deps)))
      ((:schedule-startup-summary-log! @captured-init-deps))
      (is (= [runtime-state/startup-summary-delay-ms]
             (map :delay-ms @summary-calls))))))

(deftest initialize-remote-data-streams-routes-through-runtime-owned-callbacks-test
  (let [store (atom {})
        runtime (atom {})
        callback-calls (atom [])]
    (with-redefs [startup-collaborators/startup-base-deps
                  (fn [deps]
                    (merge
                     {:store (:store deps)
                      :runtime (:runtime deps)}
                     deps))
                  startup-runtime/initialize-remote-data-streams!
                  (fn [deps]
                    ((:install-address-handlers! deps))
                    ((:start-critical-bootstrap! deps))
                    ((:schedule-deferred-bootstrap! deps))
                    :ok)
                  startup-runtime/install-address-handlers!
                  (fn [_deps]
                    (swap! callback-calls conj :install-address-handlers))
                  startup-runtime/start-critical-bootstrap!
                  (fn [_deps]
                    (swap! callback-calls conj :start-critical-bootstrap))
                  startup-runtime/schedule-deferred-bootstrap!
                  (fn [deps]
                    (swap! callback-calls conj :schedule-deferred-bootstrap)
                    ((:run-deferred-bootstrap! deps)))
                  startup-runtime/run-deferred-bootstrap!
                  (fn [_deps]
                    (swap! callback-calls conj :run-deferred-bootstrap))]
      (is (= :ok
             (app-startup/initialize-remote-data-streams!
              {:runtime runtime
               :store store})))
      (is (= [:install-address-handlers
              :start-critical-bootstrap
              :schedule-deferred-bootstrap
              :run-deferred-bootstrap]
             @callback-calls)))))

(deftest bootstrap-account-data-forwards-stage-b-through-runtime-boundary-test
  (let [store (atom {})
        runtime (atom {})
        stage-b-calls (atom [])]
    (with-redefs [startup-collaborators/startup-base-deps
                  (fn [deps]
                    (merge
                     {:store (:store deps)
                      :runtime (:runtime deps)
                      :stage-b-account-bootstrap! (fn [address dexs]
                                                    (swap! stage-b-calls conj [address dexs]))}
                     deps))
                  startup-runtime/bootstrap-account-data!
                  (fn [deps]
                    ((:stage-b-account-bootstrap! deps) "0xabc" ["dex-a"])
                    :ok)]
      (is (= :ok
             (app-startup/bootstrap-account-data!
              {:runtime runtime
               :store store}
              "0xabc")))
      (is (= [["0xabc" ["dex-a"]]]
             @stage-b-calls)))))

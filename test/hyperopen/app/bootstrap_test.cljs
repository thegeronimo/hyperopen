(ns hyperopen.app.bootstrap-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.app.bootstrap :as app-bootstrap]
            [hyperopen.funding.actions :as funding-actions]
            [hyperopen.hyperevm.infrastructure.balance-poller :as hyperevm-balance-poller]
            [hyperopen.runtime.bootstrap :as runtime-bootstrap]
            [hyperopen.runtime.wiring :as runtime-wiring]
            [hyperopen.views.app-view :as app-view]
            [replicant.dom :as r]))

(defn- with-fake-document
  [f]
  (let [had-document? (exists? js/document)
        previous-document (when had-document? js/document)
        app-node #js {:id "app"}
        document #js {:title "Hyperopen"
                      :getElementById (fn [id]
                                        (when (= "app" id)
                                          app-node))}]
    (set! (.-document js/globalThis) document)
    (try
      (f document app-node)
      (finally
        (if had-document?
          (set! (.-document js/globalThis) previous-document)
          (js-delete js/globalThis "document"))))))

(defn- without-document
  [f]
  (let [had-document? (exists? js/document)
        previous-document (when had-document? js/document)]
    (js-delete js/globalThis "document")
    (try
      (f)
      (finally
        (when had-document?
          (set! (.-document js/globalThis) previous-document))))))

(defn- bootstrap-watchers-deps
  "The watcher deps app bootstrap hands the runtime, captured without
   registering or installing anything."
  []
  (let [captured (atom nil)]
    (with-redefs [runtime-wiring/runtime-registration-deps (fn ([] {}) ([_] {}))
                  runtime-bootstrap/bootstrap-runtime! (fn [deps] (reset! captured deps))]
      (app-bootstrap/bootstrap-runtime! {:runtime (atom {}) :store (atom {})}))
    (:watchers-deps @captured)))

(deftest hyperevm-balance-poller-is-installed-only-with-a-document-test
  ;; The Node unit-test runtime bootstraps the real app; without this guard
  ;; it would run a real 4 s interval against the public HyperEVM RPC.
  (without-document
   (fn []
     (let [deps (bootstrap-watchers-deps)]
       (is (contains? deps :install-hyperevm-balance-poller!))
       (is (nil? (:install-hyperevm-balance-poller! deps))))))
  (with-fake-document
    (fn [_ _]
      (let [deps (bootstrap-watchers-deps)]
        (is (identical? hyperevm-balance-poller/install-hyperevm-balance-poller!
                        (:install-hyperevm-balance-poller! deps)))
        (is (= #{:store :dispatch! :capacity-refresh-index-fn}
               (set (keys (:hyperevm-balance-poller-deps deps)))))
        (is (identical? funding-actions/transfer-capacity-refresh-index
                        (get-in deps [:hyperevm-balance-poller-deps :capacity-refresh-index-fn]))
            "the poller keeps an open HyperEVM -> Core draft's HyperCore reads current")))))

(deftest render-app-syncs-browser-title-with-active-asset-mark-test
  (with-fake-document
    (fn [document app-node]
      (let [state {:active-asset "xyz:SILVER"
                   :active-market {:coin "xyz:SILVER"
                                   :symbol "SILVER"
                                   :base "SILVER"
                                   :dex "xyz"
                                   :market-type :perp}
                   :active-assets {:contexts {"xyz:SILVER" {:coin "xyz:SILVER"
                                                            :mark 82.65
                                                            :markRaw "82.65"}}}
                   :ui {:locale "en-US"}}
            render-calls (atom [])]
        (with-redefs [app-view/app-view (fn [render-state]
                                          [:main {:data-state render-state}])
                      r/render (fn [node view]
                                 (swap! render-calls conj [node view]))]
          (app-bootstrap/render-app! state))
        (is (= "82.65 | SILVER (xyz) | HyperOpen"
               (.-title document)))
        (is (= [[app-node [:main {:data-state state}]]]
               @render-calls))))))

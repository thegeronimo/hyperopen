(ns hyperopen.app.bootstrap-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.app.bootstrap :as app-bootstrap]
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

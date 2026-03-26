(ns hyperopen.workbench.support.dispatch-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [hyperopen.workbench.support.dispatch :as dispatch]
            [replicant.dom :as r]
            [hyperopen.workbench.support.state :as ws]))

(defn- reset-dispatch-fixture
  [f]
  (dispatch/reset-registry!)
  (f)
  (dispatch/reset-registry!))

(use-fixtures :each reset-dispatch-fixture)

(defn- scene-node
  [scene-id]
  #js {:getAttribute (fn [attr]
                       (when (= attr "data-workbench-scene-id")
                         scene-id))
       :parentNode nil})

(defn- node-with-parent
  [parent]
  #js {:parentNode parent})

(deftest dispatch-interpolates-event-placeholders-into-scene-reducers-test
  (let [store (ws/create-store ::dispatch-test {:search "" :checked? false})
        scene-id (dispatch/install-dispatch! store
                                             {:actions/set-search
                                              (fn [state _dispatch-data value]
                                                (assoc state :search value))

                                              :actions/set-checked
                                              (fn [state _dispatch-data checked?]
                                                (assoc state :checked? checked?))})
        node (scene-node (dispatch/scene-attr store))
        dom-event #js {:target #js {:value "btc"
                                    :checked true}}]
    (dispatch/dispatch! {:replicant/node node
                         :replicant/dom-event dom-event}
                        [[:actions/set-search :event.target/value]
                         [:actions/set-checked :event.target/checked]])
    (is (= "btc" (:search @store)))
    (is (true? (:checked? @store)))))

(deftest dispatch-resolves-current-target-bounds-placeholder-test
  (let [store (ws/create-store ::bounds-test {:anchor nil})
        _scene-id (dispatch/install-dispatch! store
                                              {:actions/set-anchor
                                               (fn [state _dispatch-data bounds]
                                                 (assoc state :anchor bounds))})
        node (scene-node (dispatch/scene-attr store))
        dom-event #js {:currentTarget #js {:getBoundingClientRect
                                           (fn []
                                             #js {:left 100
                                                  :right 180
                                                  :top 240
                                                  :bottom 300
                                                  :width 80
                                                  :height 60})}}]
    (dispatch/dispatch! {:replicant/node node
                         :replicant/dom-event dom-event}
                        [[:actions/set-anchor :event.currentTarget/bounds]])
    (is (= {:left 100
            :right 180
            :top 240
            :bottom 300
            :width 80
            :height 60
            :viewport-width (some-> js/globalThis .-innerWidth)
            :viewport-height (some-> js/globalThis .-innerHeight)}
           (:anchor @store)))))

(deftest dispatch-resolves-common-event-placeholders-into-reducer-args-test
  (let [store (ws/create-store ::event-data {:event nil})
        _scene-id (dispatch/install-dispatch! store
                                              {:actions/capture-event
                                               (fn [state _dispatch-data key meta-key? ctrl-key? scroll-top time-stamp client-x]
                                                 (assoc state :event {:key key
                                                                      :meta-key? meta-key?
                                                                      :ctrl-key? ctrl-key?
                                                                      :scroll-top scroll-top
                                                                      :time-stamp time-stamp
                                                                      :client-x client-x}))})
        node (scene-node (dispatch/scene-attr store))
        dom-event #js {:key "Enter"
                       :metaKey true
                       :ctrlKey false
                       :timeStamp 22
                       :clientX 144
                       :target #js {:scrollTop 96}}]
    (dispatch/dispatch! {:replicant/node node
                         :replicant/dom-event dom-event}
                        [[:actions/capture-event
                          :event/key
                          :event/metaKey
                          :event/ctrlKey
                          :event.target/scrollTop
                          :event/timeStamp
                          :event/clientX]])
    (is (= {:key "Enter"
            :meta-key? true
            :ctrl-key? false
            :scroll-top 96
            :time-stamp 22
            :client-x 144}
           (:event @store)))))

(deftest dispatch-falls-back-to-the-only-registered-scene-test
  (let [store (ws/create-store ::single-scene {:search ""})]
    (dispatch/install-dispatch! store
                                {:actions/set-search
                                 (fn [state _dispatch-data value]
                                   (assoc state :search value))})
    (dispatch/dispatch! {:replicant/dom-event #js {:target #js {:value "eth"}}}
                        [:actions/set-search :event.target/value])
    (is (= "eth" (:search @store)))))

(deftest dispatch-resolves-scene-id-from-an-ancestor-node-test
  (let [store (ws/create-store ::ancestor {:status :idle})
        _scene-id (dispatch/install-dispatch! store
                                              {:actions/set-status
                                               (fn [state _dispatch-data status]
                                                 (assoc state :status status))})
        parent (scene-node (dispatch/scene-attr store))
        child (node-with-parent parent)]
    (dispatch/dispatch! {:replicant/node child}
                        [:actions/set-status :ready])
    (is (= :ready (:status @store)))))

(deftest dispatch-ignores-unsupported-handler-shapes-test
  (let [store (ws/create-store ::handler-shape {:status :idle})
        _scene-id (dispatch/install-dispatch! store
                                              {:actions/set-status
                                               (fn [state _dispatch-data status]
                                                 (assoc state :status status))})
        node (scene-node (dispatch/scene-attr store))]
    (dispatch/dispatch! {:replicant/node node}
                        {:action :actions/set-status
                         :status :ready})
    (is (= {:status :idle} @store))))

(deftest dispatch-leaves-bounds-placeholder-nil-when-current-target-has-no-rect-api-test
  (let [store (ws/create-store ::bounds-missing {:anchor :unset})
        _scene-id (dispatch/install-dispatch! store
                                              {:actions/set-anchor
                                               (fn [state _dispatch-data bounds]
                                                 (assoc state :anchor bounds))})
        node (scene-node (dispatch/scene-attr store))]
    (dispatch/dispatch! {:replicant/node node
                         :replicant/dom-event #js {:currentTarget #js {}}}
                        [:actions/set-anchor :event.currentTarget/bounds])
    (is (nil? (:anchor @store)))))

(deftest dispatch-logs-unsupported-actions-test
  (let [store (ws/create-store ::unsupported {:count 0})
        _scene-id (dispatch/install-dispatch! store nil)
        node (scene-node (dispatch/scene-attr store))
        messages* (atom [])
        original-info (.-info js/console)]
    (set! (.-info js/console)
          (fn [& args]
            (reset! messages* args)))
    (try
      (dispatch/dispatch! {:replicant/node node}
                          [:actions/missing 42])
      (let [[message payload] @messages*
            payload-map (js->clj payload :keywordize-keys true)]
        (is (= {:count 0} @store))
        (is (= "Portfolio workbench action has no reducer" message))
        (is (= ["missing" 42] (:action payload-map)))
        (is (= (dispatch/scene-attr store) (:scene-id payload-map))))
      (finally
        (set! (.-info js/console) original-info)))))

(deftest dispatch-requires-an-explicit-scene-when-multiple-scenes-are-registered-test
  (let [store-a (ws/create-store ::scene-a {:value nil})
        store-b (ws/create-store ::scene-b {:value nil})
        reducers {:actions/set-value
                  (fn [state _dispatch-data value]
                    (assoc state :value value))}]
    (dispatch/install-dispatch! store-a reducers)
    (dispatch/install-dispatch! store-b reducers)
    (dispatch/dispatch! {:replicant/dom-event #js {:target #js {:value "btc"}}}
                        [:actions/set-value :event.target/value])
    (is (nil? (:value @store-a)))
    (is (nil? (:value @store-b)))))

(deftest scene-attr-renders-generated-symbol-and-string-scene-ids-test
  (let [generated-store (atom {})
        generated-id (dispatch/scene-id generated-store)
        symbol-store (atom {} :meta {:hyperopen.workbench.support.dispatch/scene-id 'portfolio.scene/alpha})
        string-store (atom {} :meta {:hyperopen.workbench.support.dispatch/scene-id "scene/custom"})]
    (is (= generated-id (:hyperopen.workbench.support.dispatch/scene-id (meta generated-store))))
    (is (= generated-id (dispatch/scene-id generated-store)))
    (is (= "hyperopen.workbench.scene" (namespace generated-id)))
    (is (= (str "hyperopen.workbench.scene/" (name generated-id))
           (dispatch/scene-attr generated-store)))
    (is (= "portfolio.scene/alpha" (dispatch/scene-attr symbol-store)))
    (is (= "scene/custom" (dispatch/scene-attr string-store)))))

(deftest install-global-dispatch-installs-replicant-dispatch-once-test
  (let [calls* (atom [])]
    (with-redefs [r/set-dispatch! (fn [f]
                                    (swap! calls* conj f))]
      (dispatch/install-global-dispatch!)
      (dispatch/install-global-dispatch!)
      (is (= 1 (count @calls*)))
      (is (= dispatch/dispatch! (first @calls*))))))

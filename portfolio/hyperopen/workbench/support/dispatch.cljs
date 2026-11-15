(ns hyperopen.workbench.support.dispatch
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [replicant.dom :as r]))

(def ^:private scene-id-meta-key
  ::scene-id)

(def ^:private scene-registry
  (atom {}))

(def ^:private dispatch-installed?
  (atom false))

(def ^:private placeholder-resolvers
  {:event.target/value
   (fn [{:replicant/keys [dom-event]}]
     (some-> dom-event .-target .-value))

   :event.target/checked
   (fn [{:replicant/keys [dom-event]}]
     (some-> dom-event .-target .-checked))

   :event/key
   (fn [{:replicant/keys [dom-event]}]
     (some-> dom-event .-key))

   :event/metaKey
   (fn [{:replicant/keys [dom-event]}]
     (some-> dom-event .-metaKey))

   :event/ctrlKey
   (fn [{:replicant/keys [dom-event]}]
     (some-> dom-event .-ctrlKey))

   :event.target/scrollTop
   (fn [{:replicant/keys [dom-event]}]
     (some-> dom-event .-target .-scrollTop))

   :event/timeStamp
   (fn [{:replicant/keys [dom-event]}]
     (some-> dom-event .-timeStamp))

   :event/clientX
   (fn [{:replicant/keys [dom-event]}]
     (some-> dom-event .-clientX))

   :event.currentTarget/bounds
   (fn [{:replicant/keys [dom-event]}]
     (when-let [target (some-> dom-event .-currentTarget)]
       (when (fn? (.-getBoundingClientRect target))
         (let [rect (.getBoundingClientRect target)]
           {:left (.-left rect)
            :right (.-right rect)
            :top (.-top rect)
            :bottom (.-bottom rect)
            :width (.-width rect)
            :height (.-height rect)
            :viewport-width (some-> js/globalThis .-innerWidth)
            :viewport-height (some-> js/globalThis .-innerHeight)}))))})

(defn- scene-id-text
  [scene-id]
  (cond
    (keyword? scene-id)
    (str (when-let [ns (namespace scene-id)]
           (str ns "/"))
         (name scene-id))

    (symbol? scene-id)
    (str scene-id)

    :else
    (str scene-id)))

(defn scene-id
  [store]
  (or (scene-id-meta-key (meta store))
      (let [generated-id (keyword "hyperopen.workbench.scene"
                                  (str "scene-" (random-uuid)))]
        (alter-meta! store assoc scene-id-meta-key generated-id)
        generated-id)))

(defn scene-attr
  [store]
  (scene-id-text (scene-id store)))

(defn- root-scene-id
  [node]
  (loop [node* node]
    (when node*
      (let [scene-id* (when (fn? (.-getAttribute node*))
                        (.getAttribute node* "data-workbench-scene-id"))]
        (if (seq scene-id*)
          scene-id*
          (recur (.-parentNode node*)))))))

(defn- resolve-scene-context
  [{:replicant/keys [node]}]
  (let [scene-id* (some-> node root-scene-id)]
    (or (get @scene-registry scene-id*)
        (when (= 1 (count @scene-registry))
          (val (first @scene-registry))))))

(defn- resolve-placeholder
  [dispatch-data value]
  (if-let [resolver (when (vector? value)
                      (get placeholder-resolvers (first value)))]
    (apply resolver dispatch-data (next value))
    value))

(defn- interpolate-actions
  [dispatch-data actions]
  (walk/postwalk #(resolve-placeholder dispatch-data %)
                 actions))

(defn- normalize-actions
  [handler]
  (cond
    (and (vector? handler)
         (keyword? (first handler)))
    [handler]

    (sequential? handler)
    (vec handler)

    :else
    []))

(defn- log-unsupported!
  [dispatch-data action]
  (js/console.info
   "Portfolio workbench action has no reducer"
   (clj->js {:action action
             :scene-id (some-> dispatch-data :replicant/node root-scene-id)})))

(defn- apply-action!
  [{:keys [store reducers]} dispatch-data [action-id & args :as action]]
  (if-let [reducer (get reducers action-id)]
    (swap! store #(apply reducer % dispatch-data args))
    (log-unsupported! dispatch-data action)))

(defn dispatch!
  [dispatch-data handler]
  (when-let [scene-context (resolve-scene-context dispatch-data)]
    (doseq [action (->> handler
                        normalize-actions
                        (interpolate-actions dispatch-data))]
      (apply-action! scene-context dispatch-data action))))

(defn install-global-dispatch!
  []
  (when-not @dispatch-installed?
    (r/set-dispatch! dispatch!)
    (reset! dispatch-installed? true)))

(defn install-dispatch!
  [store reducers]
  (let [scene-id* (scene-id store)
        scene-key (scene-id-text scene-id*)]
    (swap! scene-registry assoc scene-key {:scene-id scene-id*
                                           :store store
                                           :reducers (or reducers {})})
    scene-id*))

(defn reset-registry!
  []
  (reset! scene-registry {}))

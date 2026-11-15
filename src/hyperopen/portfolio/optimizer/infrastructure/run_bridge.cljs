(ns hyperopen.portfolio.optimizer.infrastructure.run-bridge
  (:require [nexus.registry :as nxr]
            [hyperopen.order.feedback-runtime :as feedback-runtime]
            [hyperopen.portfolio.optimizer.application.run-bridge-workflow :as workflow]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.infrastructure.worker-client :as worker-client]
            [hyperopen.system :as system]))

(declare handle-worker-message!)

(defn next-run-id
  []
  (str "optimizer-run-" (.now js/Date) "-" (rand-int 1000000)))

(defn now-ms
  []
  (.now js/Date))

(defn make-controller
  ([] (make-controller {}))
  ([{:keys [store dispatch! worker-ref worker-url]}]
   {:store (or store system/store)
    :dispatch! (or dispatch! nxr/dispatch)
    :last-run-request (atom nil)
    :worker-url (or worker-url worker-client/default-worker-url)
    :owns-worker? (nil? worker-ref)
    :worker-ref (or worker-ref (atom nil))
    :worker-handler-installed? (atom false)}))

(defn- controller?
  [value]
  (and (map? value)
       (contains? value :store)
       (contains? value :last-run-request)
       (contains? value :worker-url)
       (contains? value :owns-worker?)
       (contains? value :worker-ref)
       (contains? value :worker-handler-installed?)))

(defn controller-for-store!
  [controllers store]
  (or (get @controllers store)
      (let [controller (make-controller {:store store})]
        (get (swap! controllers
                    (fn [controllers*]
                      (if (contains? controllers* store)
                        controllers*
                        (assoc controllers* store controller))))
             store))))

(defn make-controller-resolver
  []
  (let [controllers (atom {})]
    (fn [store]
      (controller-for-store! controllers store))))

(defn- terminate-worker!
  [worker]
  (when (and worker (fn? (.-terminate worker)))
    (.terminate worker)))

(defn- make-owned-worker!
  [{:keys [worker-ref worker-url owns-worker?]}]
  (when owns-worker?
    (when-let [worker (worker-client/current-worker worker-ref)]
      (terminate-worker! worker))
    (reset! worker-ref (worker-client/make-worker! worker-url))))

(defn- ensure-worker!
  [{:keys [worker-ref worker-url owns-worker?]}]
  (if owns-worker?
    (or (worker-client/current-worker worker-ref)
        (reset! worker-ref (worker-client/make-worker! worker-url)))
    (worker-client/current-worker worker-ref)))

(defn- prepare-worker-for-run!
  [{:keys [worker-handler-installed? owns-worker?] :as controller}]
  (when owns-worker?
    (make-owned-worker! controller)
    (reset! worker-handler-installed? false)))

(defn- worker-error-message
  [run-id payload]
  {:id run-id
   :type :optimizer-error
   :payload payload})

(defn- install-worker-handler!
  [{:keys [store worker-handler-installed?] :as controller}]
  (when-not @worker-handler-installed?
    (when-let [worker (ensure-worker! controller)]
      (when (worker-client/add-message-listener!
             worker
             (fn [message]
               (handle-worker-message! controller message)))
        (let [run-id (get-in @store (conj contracts/run-state-path :run-id))]
          (worker-client/add-error-listener!
           worker
           (fn [payload]
             (handle-worker-message!
              controller
              (worker-error-message run-id payload)
              {:computed-at-ms (now-ms)}))))
        (reset! worker-handler-installed? true)))))

(defn- worker-unavailable-payload
  []
  {:code :optimizer-worker-unavailable
   :message "Portfolio optimizer worker is unavailable."})

(defn- interpret-start-command!
  [controller command]
  (case (:command/type command)
    :optimizer.workflow/install-worker-handler
    (install-worker-handler! controller)

    :optimizer.workflow/post-worker-run
    (when-not (worker-client/post-run! (:worker-ref controller)
                                       (:run-id command)
                                       (:request command))
      (handle-worker-message!
       controller
       {:id (:run-id command)
        :type :optimizer-error
        :payload (worker-unavailable-payload)}
       {:computed-at-ms (now-ms)}))

    nil))

(defn- interpret-message-command!
  [{:keys [store dispatch!]} command]
  (case (:command/type command)
    :optimizer.workflow/navigate
    ((or dispatch! nxr/dispatch) store nil [[:actions/navigate (:path command)]])

    :optimizer.workflow/reveal-results
    ((or dispatch! nxr/dispatch)
     store
     nil
     [[:actions/navigate (:path command)]
      [:actions/set-portfolio-optimizer-results-tab :recommendation]])

    :optimizer.workflow/announce-run-complete
    (feedback-runtime/set-order-feedback-toast!
     store
     :success
     {:headline "Optimization complete"
      :subline "The recommendation is ready on the optimizer's Results page."})

    :optimizer.workflow/refresh-portfolio-optimizer-rebalance-slippage-snapshots
    ((or dispatch! nxr/dispatch)
     store
     nil
     [[:effects/refresh-portfolio-optimizer-rebalance-slippage-snapshots]])

    nil))

(defn request-run!
  [{:keys [controller request request-signature computed-at-ms store run-id]}]
  (let [controller* (or controller (make-controller {:store (or store system/store)}))
        store* (or (:store controller*) store system/store)
        last-run-request (:last-run-request controller*)
        started-at-ms (or computed-at-ms (now-ms))
        result (workflow/start-run
                {:state @store*
                 :last-run-request @last-run-request
                 :request request
                 :request-signature request-signature
                 :run-id (or run-id (next-run-id))
                 :explicit-run-id? (some? run-id)
                 :computed-at-ms started-at-ms})]
      (when-let [run-id* (:run-id result)]
        (prepare-worker-for-run! controller*)
        (reset! last-run-request (:last-run-request result))
        (reset! store* (:state result))
        (doseq [command (:commands result)]
          (interpret-start-command! controller* command))
        run-id*)))

(defn handle-worker-message!
  ([message]
   (handle-worker-message! (make-controller {:store system/store})
                           message
                           {:computed-at-ms (now-ms)}))
  ([controller-or-message message-or-opts]
   (if (controller? controller-or-message)
     (handle-worker-message! controller-or-message
                             message-or-opts
                             {:computed-at-ms (now-ms)})
     (handle-worker-message! (make-controller {:store system/store})
                             controller-or-message
                             message-or-opts)))
  ([controller message {:keys [computed-at-ms]}]
   (let [result (atom nil)]
     (swap! (:store controller)
            (fn [state]
              (let [result* (workflow/handle-worker-message
                             {:state state
                              :message message
                              :computed-at-ms computed-at-ms})]
                (reset! result result*)
                (:state result*))))
     (doseq [command (:commands @result)]
       (interpret-message-command! controller command)))
   nil))

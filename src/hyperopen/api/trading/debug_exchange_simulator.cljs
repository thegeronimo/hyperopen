(ns hyperopen.api.trading.debug-exchange-simulator)

(defonce ^:private debug-exchange-simulator
  (atom nil))

(defonce ^:private debug-exchange-simulator-calls
  (atom []))

(defn install!
  [simulator]
  (reset! debug-exchange-simulator simulator)
  (reset! debug-exchange-simulator-calls [])
  true)

(defn clear!
  []
  (reset! debug-exchange-simulator nil)
  (reset! debug-exchange-simulator-calls [])
  true)

(defn snapshot
  []
  (when (some? @debug-exchange-simulator)
    {:installed true
     :config @debug-exchange-simulator
     :calls @debug-exchange-simulator-calls}))

(defn- queued-response!
  [path]
  (let [entry (get-in @debug-exchange-simulator path)]
    (cond
      (and (map? entry)
           (sequential? (:responses entry)))
      (let [responses (vec (:responses entry))
            response (first responses)
            remaining (vec (rest responses))]
        (swap! debug-exchange-simulator assoc-in path
               (assoc entry :responses remaining))
        response)

      (map? entry)
      (or (:response entry) entry)

      (sequential? entry)
      (let [responses (vec entry)
            response (first responses)
            remaining (vec (rest responses))]
        (swap! debug-exchange-simulator assoc-in path remaining)
        response)

      :else
      entry)))

(defn- remaining-responses-count
  [path]
  (let [entry (get-in @debug-exchange-simulator path)]
    (cond
      (and (map? entry)
           (sequential? (:responses entry)))
      (count (:responses entry))

      (sequential? entry)
      (count entry)

      :else
      nil)))

(defn- response-body
  [response]
  (cond
    (and (map? response) (contains? response :body))
    (:body response)

    (map? response)
    (dissoc response :delay-ms :delayMs)

    :else
    response))

(defn- response-status
  [response]
  (some-> (or (:status (response-body response))
              (:status response))
          str))

(defn- record-response-call!
  [paths matched-path response defaulted? request]
  (swap! debug-exchange-simulator-calls conj
         (cond-> {:paths (vec paths)
                  :matchedPath matched-path}
           (some? request)
           (assoc :request request)

           (some? response)
           (assoc :responseStatus (response-status response))

           (some? matched-path)
           (assoc :remainingResponses
                  (remaining-responses-count matched-path))

           defaulted?
           (assoc :defaulted true))))

(defn- schedule-cancel-path
  [paths]
  (some (fn [path]
          (when (and (= :signedActions (first path))
                     (= "scheduleCancel" (some-> (second path) name)))
            path))
        paths))

(defn- default-response
  [paths]
  (when-let [path (schedule-cancel-path paths)]
    {:path path
     :response {:status "ok"}
     :defaulted? true}))

(defn- first-response!
  [paths]
  (or (loop [remaining (seq paths)]
        (when remaining
          (let [path (first remaining)
                response (queued-response! path)]
            (if response
              {:path path
               :response response}
              (recur (next remaining))))))
      (default-response paths)))

(defn- response-like
  [response]
  (let [payload (response-body response)
        status (or (:http-status response)
                   (:httpStatus response)
                   200)]
    #js {:status status
         :json (fn []
                 (js/Promise.resolve (clj->js payload)))
         :text (fn []
                 (js/Promise.resolve (js/JSON.stringify (clj->js payload))))}))

(defn- response-delay-ms
  "A response's `delayMs` (or `:delay-ms`): how long the simulated exchange
   holds it before answering, so a browser test can act while a request is
   still in flight."
  [response]
  (let [delay-ms (when (map? response)
                   (or (:delay-ms response) (:delayMs response)))]
    (when (and (number? delay-ms) (pos? delay-ms))
      delay-ms)))

(defn- settle-after
  [delay-ms settle]
  (if delay-ms
    (js/Promise. (fn [resolve reject]
                   (js/setTimeout #(-> (settle) (.then resolve reject)) delay-ms)))
    (settle)))

(defn simulated-fetch-response
  ([paths]
   (simulated-fetch-response paths nil))
  ([paths request]
   (when (some? @debug-exchange-simulator)
     (let [{:keys [path response defaulted?]} (first-response! paths)]
       (record-response-call! paths path response defaulted? request)
       (when response
         (settle-after
          (response-delay-ms response)
          (fn []
            (if-let [reject-message (or (:reject-message response)
                                        (:rejectMessage response))]
              (js/Promise.reject (js/Error. (str reject-message)))
              (js/Promise.resolve (response-like response))))))))))

(ns hyperopen.order.feedback-runtime
  (:require [clojure.string :as str]))

(def ^:private max-order-feedback-toasts 5)

(defn- normalize-toast-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- normalize-toast-content
  [message]
  (if (map? message)
    (let [headline (normalize-toast-text (:headline message))
          subline (normalize-toast-text (:subline message))
          message* (or (normalize-toast-text (:message message))
                       headline
                       subline)]
      (when message*
        (cond-> (assoc message :message message*)
          headline (assoc :headline headline)
          subline (assoc :subline subline))))
    (when-let [message* (normalize-toast-text message)]
      {:message message*})))

(defn- sanitize-existing-toast
  [toast]
  (when (map? toast)
    (when-let [content (normalize-toast-content toast)]
      (merge toast content))))

(defn- ensure-toast-id
  [toast]
  (if (some? (:id toast))
    toast
    (assoc toast :id (str (random-uuid)))))

(defn- normalize-existing-toasts
  [state]
  (let [toasts (->> (or (get-in state [:ui :toasts]) [])
                    (keep sanitize-existing-toast)
                    (mapv ensure-toast-id))]
    (if (seq toasts)
      toasts
      (if-let [legacy-toast (some-> (get-in state [:ui :toast])
                                    sanitize-existing-toast
                                    ensure-toast-id)]
        [legacy-toast]
        []))))

(defn- bounded-toasts
  [toasts]
  (let [toasts* (vec (or toasts []))
        toast-count (count toasts*)]
    (if (> toast-count max-order-feedback-toasts)
      (subvec toasts* (- toast-count max-order-feedback-toasts))
      toasts*)))

(defn- apply-toast-state
  [state toasts]
  (let [toasts* (vec (or toasts []))
        latest-toast (peek toasts*)]
    (-> state
        (assoc-in [:ui :toasts] toasts*)
        ;; Keep legacy readers stable while stacked toasts migrate.
        (assoc-in [:ui :toast] (some-> latest-toast
                                       (dissoc :id))))))

(defn set-order-feedback-toast!
  [store kind message]
  (let [toast-content (normalize-toast-content message)
        inserted-toast (atom nil)]
    (swap! store
           (fn [state]
             (if-not toast-content
               (do
                 (reset! inserted-toast nil)
                 (apply-toast-state state []))
               (let [toast (ensure-toast-id
                            (assoc toast-content :kind kind))
                     next-toasts (-> (normalize-existing-toasts state)
                                     (conj toast)
                                     bounded-toasts)]
                 (reset! inserted-toast toast)
                 (apply-toast-state state next-toasts)))))
    @inserted-toast))

(defn clear-order-feedback-toast!
  ([store]
   (swap! store apply-toast-state []))
  ([store toast-id]
   (if (some? toast-id)
     (swap! store
            (fn [state]
              (let [remaining (->> (normalize-existing-toasts state)
                                   (remove #(= toast-id (:id %)))
                                   vec)]
                (apply-toast-state state remaining))))
     (clear-order-feedback-toast! store))))

(defn- timeout-map
  [value]
  (cond
    (map? value) value
    (some? value) {:legacy value}
    :else {}))

(defn clear-order-feedback-toast-timeout!
  ([order-feedback-toast-timeout-id clear-timeout-fn]
   (clear-order-feedback-toast-timeout! order-feedback-toast-timeout-id
                                        clear-timeout-fn
                                        nil))
  ([order-feedback-toast-timeout-id clear-timeout-fn toast-id]
   (let [timeouts (timeout-map @order-feedback-toast-timeout-id)]
     (if (some? toast-id)
       (when-let [timeout-id (get timeouts toast-id)]
         (clear-timeout-fn timeout-id)
         (reset! order-feedback-toast-timeout-id
                 (dissoc timeouts toast-id)))
       (do
         (doseq [timeout-id (vals timeouts)]
           (clear-timeout-fn timeout-id))
         (reset! order-feedback-toast-timeout-id {}))))))

(defn clear-order-feedback-toast-timeout-in-runtime!
  ([runtime clear-timeout-fn]
   (clear-order-feedback-toast-timeout-in-runtime! runtime
                                                   clear-timeout-fn
                                                   nil))
  ([runtime clear-timeout-fn toast-id]
   (let [timeouts (timeout-map (get-in @runtime [:timeouts :order-toast]))]
     (if (some? toast-id)
       (when-let [timeout-id (get timeouts toast-id)]
         (clear-timeout-fn timeout-id)
         (swap! runtime assoc-in [:timeouts :order-toast]
                (dissoc timeouts toast-id)))
       (do
         (doseq [timeout-id (vals timeouts)]
           (clear-timeout-fn timeout-id))
         (swap! runtime assoc-in [:timeouts :order-toast] {}))))))

(defn- set-timeout-id!
  [runtime order-feedback-toast-timeout-id toast-id timeout-id]
  (let [toast-id* (or toast-id :legacy)]
    (if runtime
      (swap! runtime update-in [:timeouts :order-toast]
             (fn [current]
               (assoc (timeout-map current) toast-id* timeout-id)))
      (when order-feedback-toast-timeout-id
        (reset! order-feedback-toast-timeout-id
                (assoc (timeout-map @order-feedback-toast-timeout-id)
                       toast-id*
                       timeout-id))))))

(defn- clear-timeout-id-reference!
  [runtime order-feedback-toast-timeout-id toast-id]
  (let [toast-id* (or toast-id :legacy)]
    (if runtime
      (swap! runtime update-in [:timeouts :order-toast]
             (fn [current]
               (dissoc (timeout-map current) toast-id*)))
      (when order-feedback-toast-timeout-id
        (reset! order-feedback-toast-timeout-id
                (dissoc (timeout-map @order-feedback-toast-timeout-id)
                        toast-id*))))))

(defn schedule-order-feedback-toast-clear!
  [{:keys [store
           runtime
           order-feedback-toast-timeout-id
           clear-order-feedback-toast!
           clear-order-feedback-toast-timeout!
           order-feedback-toast-duration-ms
           set-timeout-fn
           toast-id]}]
  (if (some? toast-id)
    (clear-order-feedback-toast-timeout! toast-id)
    (clear-order-feedback-toast-timeout!))
  (let [timeout-id (set-timeout-fn
                    (fn []
                      (if (some? toast-id)
                        (clear-order-feedback-toast! store toast-id)
                        (clear-order-feedback-toast! store))
                      (clear-timeout-id-reference!
                       runtime
                       order-feedback-toast-timeout-id
                       toast-id))
                    order-feedback-toast-duration-ms)]
    (set-timeout-id! runtime
                     order-feedback-toast-timeout-id
                     toast-id
                     timeout-id)))

(defn show-order-feedback-toast!
  [store kind message schedule-order-feedback-toast-clear!]
  (when-let [toast (set-order-feedback-toast! store kind message)]
    (when-not (false? (:auto-timeout? toast))
      (schedule-order-feedback-toast-clear! store (:id toast)))
    toast))

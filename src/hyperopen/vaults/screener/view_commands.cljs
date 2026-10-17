(ns hyperopen.vaults.screener.view-commands
  "Saved-view actions: the save dialog, picking a view on the All vaults page,
  and the view menu (refresh, rename, keep fixed, open in Screener, delete)."
  (:require [clojure.string :as str]
            [hyperopen.vaults.application.ui-state :as ui-state]
            [hyperopen.vaults.screener.commands :as commands]
            [hyperopen.vaults.screener.model :as model]
            [hyperopen.vaults.screener.saved-views :as saved-views]))

(def saved-views-path
  [:vaults :saved-views])

(def saved-views-loaded-path
  [:vaults :saved-views-loaded?])

(def active-view-path
  [:vaults-ui :active-saved-view])

(def dialog-path
  [:vaults-ui :save-view-dialog])

(def menu-path
  [:vaults-ui :saved-view-menu])

(def ^:private user-page-path
  [:vaults-ui :user-vaults-page])

(def ^:private persist-effect
  [:effects/persist-vault-saved-views])

(defn- keyword-token
  [value]
  (cond
    (keyword? value) value
    (string? value) (keyword (str/replace value #"^:" ""))
    :else nil))

(defn views
  [state]
  (vec (or (get-in state saved-views-path) [])))

(defn active-view
  [state]
  (when-let [id (get-in state active-view-path)]
    (saved-views/view-by-id (views state) id)))

(defn load-effects
  "Effects that hydrate saved views (and the screener cache index, which the
  refresh estimate reads) once per session."
  [state]
  (cond-> []
    (not (true? (get-in state saved-views-loaded-path)))
    (conj [:effects/load-vault-saved-views])

    (not (true? (get-in state (conj commands/screener-state-path :cache-index-loaded?))))
    (conj [:effects/load-vault-screener-cache-index])))

;; ---------------------------------------------------------------------------
;; Save dialog

(defn open-vault-save-view-dialog
  [state]
  (let [run (get-in state commands/run-path)]
    (if (seq (get-in state commands/results-path))
      [[:effects/save dialog-path
        {:open? true
         :mode :save
         :name (saved-views/suggested-name
                (saved-views/view-config (:config run) (commands/current-config state)))
         :scope :pass
         :membership :update}]]
      [])))

(defn set-vault-save-view-field
  [state field value]
  (let [field* (keyword-token field)]
    (if (and (true? (get-in state (conj dialog-path :open?)))
             (contains? #{:name :scope :membership} field*))
      [[:effects/save (conj dialog-path field*)
        (case field*
          :name (str (or value ""))
          :scope (saved-views/normalize-scope value)
          :membership (saved-views/normalize-membership value))]]
      [])))

(defn close-vault-save-view-dialog
  [_state]
  [[:effects/save dialog-path nil]])

(defn save-vault-screener-view
  ([state]
   (save-vault-screener-view state (.now js/Date)))
  ([state now-ms]
   (let [{:keys [open? mode view-id name scope membership]} (get-in state dialog-path)
         views* (views state)]
     (cond
       (not open?)
       []

       (= mode :rename)
       (if-let [view (saved-views/view-by-id views* view-id)]
         [[:effects/save-many [[saved-views-path
                                (saved-views/upsert-view
                                 views*
                                 (assoc view :name (or (saved-views/normalize-name name) (:name view))))]
                               [dialog-path nil]]]
          persist-effect]
         [[:effects/save dialog-path nil]])

       (empty? (get-in state commands/results-path))
       []

       :else
       (let [view (saved-views/build-view {:id (str "view-" now-ms)
                                           :name name
                                           :scope scope
                                           :membership membership
                                           :run (get-in state commands/run-path)
                                           :results (get-in state commands/results-path)
                                           :ui-config (commands/current-config state)
                                           :now-ms now-ms})]
         [[:effects/save-many [[saved-views-path (saved-views/upsert-view views* view)]
                               [dialog-path nil]
                               [active-view-path (:id view)]
                               [menu-path nil]
                               [user-page-path ui-state/default-vault-user-page]
                               [commands/view-path :list]]]
          persist-effect])))))

;; ---------------------------------------------------------------------------
;; Picking a view

(defn select-vault-saved-view
  [state view-id]
  (let [id (when (and (string? view-id) (seq view-id)) view-id)
        id* (when (saved-views/view-by-id (views state) id) id)]
    [[:effects/save-many [[active-view-path id*]
                          [menu-path nil]
                          [user-page-path ui-state/default-vault-user-page]]]]))

;; ---------------------------------------------------------------------------
;; View menu

(defn- refresh-effects
  [state view now-ms]
  (let [config (:config view)
        addresses (if (= :fixed (:membership view))
                    (vec (:members view))
                    (commands/universe-addresses state now-ms config))
        run-id (inc (or (get-in state (conj commands/run-path :id)) 0))]
    (if (empty? addresses)
      []
      [[:effects/save-many [[commands/run-path {:id run-id
                                                :status :running
                                                :saved-view-id (:id view)
                                                :config config
                                                :addresses addresses
                                                :total (count addresses)
                                                :done 0
                                                :cached 0
                                                :fetched 0
                                                :failed 0
                                                :started-at-ms now-ms
                                                :interval-ms model/request-interval-ms}]
                            [commands/results-path {}]
                            [menu-path nil]]]
       [:effects/run-vault-screener run-id]])))

(def ^:private min-history-commands
  {:min-history-0 0 :min-history-10 10 :min-history-20 20})

(defn- set-min-history-effects
  "Change the view's minimum-history rule and refresh it right away, so its
  members match the rule (a just-refreshed view is all cached, so instant)."
  [state view views* points now-ms]
  (let [view* (assoc-in view [:config :min-history-points] points)]
    (into [[:effects/save-many [[saved-views-path (saved-views/upsert-view views* view*)]
                                [menu-path nil]]]
           persist-effect]
          (refresh-effects state view* now-ms))))

(defn vault-saved-view-command
  "Commands on the active saved view: :toggle-menu :close-menu :refresh
  :rename :toggle-fixed :open-in-screener :request-delete :cancel-delete
  :confirm-delete, and :min-history-0 / -10 / -20 (set the view's minimum
  history and refresh)."
  ([state command]
   (vault-saved-view-command state command (.now js/Date)))
  ([state command now-ms]
   (let [command* (keyword-token command)
         view (active-view state)
         views* (views state)
         menu (or (get-in state menu-path) {})]
     (if-not view
       []
       (case command*
         (:min-history-0 :min-history-10 :min-history-20)
         (set-min-history-effects state view views* (get min-history-commands command*) now-ms)

         :toggle-menu [[:effects/save menu-path {:open? (not (:open? menu))}]]
         :close-menu [[:effects/save menu-path nil]]
         :refresh (refresh-effects state view now-ms)
         :rename [[:effects/save-many [[menu-path nil]
                                       [dialog-path {:open? true
                                                     :mode :rename
                                                     :view-id (:id view)
                                                     :name (:name view)}]]]]
         :toggle-fixed [[:effects/save-many
                         [[saved-views-path
                           (saved-views/upsert-view
                            views*
                            (assoc view :membership (if (= :fixed (:membership view)) :update :fixed)))]
                          [menu-path nil]]]
                        persist-effect]
         :open-in-screener (into [[:effects/save-many
                                   [[commands/config-path
                                     (model/normalize-config
                                      (merge (commands/current-config state)
                                             (select-keys (:config view)
                                                          [:min-age-days :min-tvl :window :include-deposited?
                                                           :criteria :min-history-points])))]
                                    [commands/panel-path :builder]
                                    [commands/view-path :screener]
                                    [menu-path nil]]]]
                                 (when-not (true? (get-in state (conj commands/screener-state-path
                                                                      :cache-index-loaded?)))
                                   [[:effects/load-vault-screener-cache-index]]))
         :request-delete [[:effects/save menu-path {:open? false :confirm-delete? true}]]
         :cancel-delete [[:effects/save menu-path nil]]
         :confirm-delete [[:effects/save-many [[saved-views-path (saved-views/remove-view views* (:id view))]
                                               [active-view-path nil]
                                               [menu-path nil]]]
                          persist-effect]
         [])))))

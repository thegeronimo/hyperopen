(ns hyperopen.surface-modules
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            [shadow.loader :as loader]))

(def ^:private module-name-by-id
  {:funding-modal "funding_modal"
   :spectate-mode-modal "spectate_mode_modal"
   :pnl-share-modal "pnl_share_modal"
   :account-surfaces "account_surfaces"})

(def ^:private primary-export-id-by-id
  {:funding-modal :view
   :spectate-mode-modal :view
   :pnl-share-modal :view
   :account-surfaces :account-info-view})

(def ^:private exported-paths-by-id
  {:funding-modal {:view ["hyperopen" "views" "funding_modal_module" "funding_modal_view"]}
   :spectate-mode-modal {:view ["hyperopen" "views" "spectate_mode_modal_module" "spectate_mode_modal_view"]}
   :pnl-share-modal {:view ["hyperopen" "views" "pnl_share_modal_module" "pnl_share_modal_view"]}
   :account-surfaces {:account-info-view ["hyperopen" "views" "account_surfaces_module" "account_info_view"]
                      :account-equity-view ["hyperopen" "views" "account_surfaces_module" "account_equity_view"]
                      :account-equity-metrics ["hyperopen" "views" "account_surfaces_module" "account_equity_metrics"]
                      :funding-actions-view ["hyperopen" "views" "account_surfaces_module" "funding_actions_view"]}})

(defonce ^:private resolved-surface-exports (atom {}))

(declare cached-or-exported-export)

(defn default-state
  []
  {:loaded #{}
   :loading nil
   :errors {}})

(defn surface-module-id
  [surface-id]
  (when (contains? module-name-by-id surface-id)
    surface-id))

(defn resolved-surface-view
  [surface-id]
  (cached-or-exported-export surface-id :view))

(defn resolved-surface-export
  [surface-id export-id]
  (cached-or-exported-export surface-id export-id))

(defn- resolve-exported-view
  [path-segments]
  (let [root (or (some-> js/goog .-global)
                 js/globalThis)]
    (reduce (fn [acc segment]
              (when acc
                (gobj/get acc segment)))
            root
            path-segments)))

(defn- resolve-module-export
  [surface-id export-id]
  (when-let [module-id (surface-module-id surface-id)]
    (when-let [path-segments (get-in exported-paths-by-id [module-id export-id])]
      (resolve-exported-view path-segments))))

(defn- cached-or-exported-export
  [surface-id export-id]
  (when-let [module-id (surface-module-id surface-id)]
    (let [cached-export (get-in @resolved-surface-exports [module-id export-id])]
      (cond
        (fn? cached-export)
        cached-export

        (some? cached-export)
        (do
          (swap! resolved-surface-exports update module-id dissoc export-id)
          nil)

        :else
        (when-let [resolved-export (resolve-module-export module-id export-id)]
          (when (fn? resolved-export)
            (swap! resolved-surface-exports assoc-in [module-id export-id] resolved-export)
            resolved-export))))))

(defn- primary-export-id
  [surface-id]
  (get primary-export-id-by-id (surface-module-id surface-id) :view))

(defn surface-ready?
  [_state surface-id]
  (some? (cached-or-exported-export surface-id (primary-export-id surface-id))))

(defn surface-loading?
  [state surface-id]
  (= (get-in state [:surface-modules :loading])
     (surface-module-id surface-id)))

(defn surface-error
  [state surface-id]
  (get-in state [:surface-modules :errors (surface-module-id surface-id)]))

(defn render-surface-view
  [state surface-id]
  (when-let [view (cached-or-exported-export surface-id :view)]
    (view state)))

(defn mark-surface-module-loading
  [state surface-id]
  (if-let [module-id (surface-module-id surface-id)]
    (-> state
        (assoc-in [:surface-modules :loading] module-id)
        (update-in [:surface-modules :errors] dissoc module-id))
    (assoc-in state [:surface-modules :loading] nil)))

(defn mark-surface-module-loaded
  [state surface-id]
  (if-let [module-id (surface-module-id surface-id)]
    (-> state
        (update-in [:surface-modules :loaded] (fnil conj #{}) module-id)
        (assoc-in [:surface-modules :loading] nil)
        (update-in [:surface-modules :errors] dissoc module-id))
    (assoc-in state [:surface-modules :loading] nil)))

(defn mark-surface-module-failed
  [state surface-id err]
  (let [module-id (surface-module-id surface-id)
        message (or (some-> err .-message)
                    (some-> err str str/trim not-empty)
                    "Failed to load surface.")]
    (-> state
        (assoc-in [:surface-modules :loading] nil)
        (assoc-in [:surface-modules :errors module-id] message))))

(defn load-surface-module!
  [store surface-id]
  (if-let [module-id (surface-module-id surface-id)]
    (if-let [existing-export (cached-or-exported-export module-id (primary-export-id module-id))]
      (do
        (swap! store mark-surface-module-loaded module-id)
        (js/Promise.resolve existing-export))
      (let [module-name (get module-name-by-id module-id)
            primary-export-id* (primary-export-id module-id)
            resolve-loaded-export!
            (fn []
              (let [resolved-export (resolve-module-export module-id primary-export-id*)]
                (when-not (fn? resolved-export)
                  (throw (js/Error.
                          (str "Loaded surface module without exported function: "
                               module-id
                               "/"
                               primary-export-id*))))
                (swap! resolved-surface-exports assoc-in [module-id primary-export-id*] resolved-export)
                (swap! store mark-surface-module-loaded module-id)
                resolved-export))]
        (swap! store mark-surface-module-loading module-id)
        (try
          (if (loader/loaded? module-name)
            (js/Promise.resolve (resolve-loaded-export!))
            (-> (loader/load module-name)
                (.then (fn [_]
                         (resolve-loaded-export!)))
                (.catch (fn [err]
                          (swap! store mark-surface-module-failed module-id err)
                          (js/Promise.reject err)))))
          (catch :default err
            (swap! store mark-surface-module-failed module-id err)
            (js/Promise.reject err)))))
    (do
      (swap! store mark-surface-module-loaded nil)
      (js/Promise.resolve nil))))

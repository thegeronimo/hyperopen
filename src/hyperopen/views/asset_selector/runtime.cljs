(ns hyperopen.views.asset-selector.runtime
  (:require [hyperopen.asset-selector.list-metrics :as list-metrics]
            [hyperopen.asset-selector.query :as query]
            [hyperopen.system :as app-system]
            [hyperopen.views.asset-selector.rows :as rows]
            [nexus.registry :as nxr]
            [replicant.dom :as r]))

(def asset-list-full-render-threshold
  1000)

(def asset-list-max-active-scroll-overscan-rows
  120)

(def asset-list-scroll-settle-delay-ms
  120)

(def asset-list-live-subscription-resume-delay-ms
  180)

(defonce asset-list-scroll-active* (atom false))

(defonce asset-list-freeze-active* (atom false))

(defn asset-list-scroll-active? []
  (true? @asset-list-scroll-active*))

(defn asset-list-freeze-active? []
  (true? @asset-list-freeze-active*))

(defn set-asset-list-scroll-active!
  [active?]
  (reset! asset-list-scroll-active* (boolean active?)))

(defn set-asset-list-freeze-active!
  [active?]
  (reset! asset-list-freeze-active* (boolean active?)))

(defn asset-list-full-render?
  [assets]
  (<= (count assets) asset-list-full-render-threshold))

(defn asset-list-window
  [limit scroll-top full-render? overscan-rows]
  (if full-render?
    (query/virtual-window limit scroll-top limit)
    (query/virtual-window limit
                          scroll-top
                          (or overscan-rows query/default-overscan-rows))))

(defn asset-list-window-state
  ([props scroll-top]
   (asset-list-window-state props scroll-top nil))
  ([{:keys [assets]} scroll-top overscan-rows]
   (let [assets* (if (vector? assets) assets (vec assets))
         total (count assets*)
         limit total
         full-render? (asset-list-full-render? assets*)
         scroll-top* (query/normalize-scroll-top scroll-top)
         visible-row-count (-> (/ list-metrics/viewport-height-px
                                  list-metrics/row-height-px)
                               js/Math.ceil
                               int)
         first-visible-row (-> (/ scroll-top* list-metrics/row-height-px)
                               js/Math.floor
                               int)
         last-visible-row (-> (+ first-visible-row visible-row-count)
                              (min limit)
                              (max first-visible-row))]
     {:total total
      :limit limit
      :scroll-top scroll-top*
      :overscan-rows (if full-render?
                       total
                       (or overscan-rows query/default-overscan-rows))
      :first-visible-row first-visible-row
      :last-visible-row last-visible-row
      :window (when (pos? total)
                (asset-list-window limit scroll-top* full-render? overscan-rows))})))

(defn asset-list-viewport-covered?
  [current-window-state next-window-state]
  (let [{:keys [start-index end-index]} (:window current-window-state)]
    (and start-index
         end-index
         (<= start-index (:first-visible-row next-window-state))
         (>= end-index (:last-visible-row next-window-state)))))

(defn asset-list-window-covered?
  [current-window-state next-window-state]
  (let [current-start-index (get-in current-window-state [:window :start-index])
        current-end-index (get-in current-window-state [:window :end-index])
        next-start-index (get-in next-window-state [:window :start-index])
        next-end-index (get-in next-window-state [:window :end-index])]
    (and (number? current-start-index)
         (number? current-end-index)
         (number? next-start-index)
         (number? next-end-index)
         (<= current-start-index next-start-index)
         (>= current-end-index next-end-index))))

(defn asset-list-body
  ([assets selected-market-key highlighted-market-key favorites missing-icons loaded-icons render-limit scroll-top suppress-empty-state?]
   (asset-list-body assets
                    selected-market-key
                    highlighted-market-key
                    favorites
                    missing-icons
                    loaded-icons
                    render-limit
                    scroll-top
                    suppress-empty-state?
                    nil))
  ([assets selected-market-key highlighted-market-key favorites missing-icons loaded-icons render-limit scroll-top suppress-empty-state? overscan-rows]
   (let [assets* (if (vector? assets) assets (vec assets))
         total (count assets*)
         full-render? (asset-list-full-render? assets*)]
     (if (zero? total)
       (if suppress-empty-state?
         [:div.py-8]
         [:div.text-center.py-8.text-gray-400
          [:div "No assets found"]
          [:div.text-xs "Try adjusting your search"]])
       (let [limit total
             scroll-top* (query/normalize-scroll-top scroll-top)
             {:keys [start-index end-index top-spacer-px bottom-spacer-px]}
             (asset-list-window limit scroll-top* full-render? overscan-rows)
             visible-assets (subvec assets* start-index end-index)
             rows* (mapv (fn [asset]
                           ^{:key (:key asset)}
                           (rows/asset-list-item asset
                                                 (= selected-market-key (:key asset))
                                                 (= highlighted-market-key (:key asset))
                                                 favorites
                                                 missing-icons
                                                 loaded-icons))
                         visible-assets)]
         (into
           [:div {:style {:overflow-anchor "none"}}]
           (concat
             (when (pos? top-spacer-px)
               [[:div {:style {:height (str top-spacer-px "px")}}]])
             rows*
             (when (pos? bottom-spacer-px)
               [[:div {:style {:height (str bottom-spacer-px "px")}}]]))))))))

(defn render-asset-list-body!
  ([host-node props scroll-top]
   (render-asset-list-body! host-node props scroll-top nil))
  ([host-node {:keys [assets selected-market-key highlighted-market-key favorites missing-icons loaded-icons
                      render-limit suppress-empty-state?]}
    scroll-top
    overscan-rows]
   (when host-node
     (r/render host-node
               (asset-list-body assets
                                selected-market-key
                                highlighted-market-key
                                favorites
                                missing-icons
                                loaded-icons
                                render-limit
                                scroll-top
                                suppress-empty-state?
                                overscan-rows)))))

(defn asset-list-now-ms []
  (if (exists? js/performance)
    (.now js/performance)
    (.now js/Date)))

(defn asset-list-set-timeout!
  [f delay-ms]
  (js/setTimeout f delay-ms))

(defn asset-list-clear-timeout!
  [timeout-handle]
  (js/clearTimeout timeout-handle))

(defn asset-list-render-limit-sync-required?
  [{:keys [assets render-limit]}]
  (let [assets* (if (vector? assets) assets (vec assets))
        total (count assets*)]
    (and (pos? total)
         (< (query/normalize-render-limit render-limit total)
            total))))

(defn schedule-asset-list-render-limit-sync!
  [props render-limit-sync-timeout*]
  (when (and (asset-list-render-limit-sync-required? props)
             (nil? @render-limit-sync-timeout*)
             app-system/store)
    (reset! render-limit-sync-timeout*
            (js/setTimeout
              (fn []
                (reset! render-limit-sync-timeout* nil)
                (when (asset-list-render-limit-sync-required? props)
                  (nxr/dispatch app-system/store
                                nil
                                [[:actions/show-all-asset-selector-markets]])))
              0))))

(defn persist-asset-list-scroll-top!
  [scroll-top]
  (when app-system/store
    (nxr/dispatch app-system/store
                  nil
                  [[:actions/set-asset-selector-scroll-top
                    scroll-top]])))

(defn set-asset-list-live-market-subscriptions-paused!
  [paused?]
  (when app-system/store
    (nxr/dispatch app-system/store
                  nil
                  [[:actions/set-asset-selector-live-market-subscriptions-paused
                    paused?]])))

(defn sync-asset-list-props!
  [host-node props* pending-props* last-window-state* next-props scroll-top]
  (reset! props* next-props)
  (reset! pending-props* nil)
  (render-asset-list-body! host-node next-props scroll-top)
  (reset! last-window-state* (asset-list-window-state next-props scroll-top)))

(defn clear-asset-list-timeout-atom!
  [timeout*]
  (when-let [timeout-handle @timeout*]
    (asset-list-clear-timeout! timeout-handle)
    (reset! timeout* nil)))

(defn cancel-asset-list-live-subscription-resume!
  [live-subscription-resume-timeout*]
  (clear-asset-list-timeout-atom! live-subscription-resume-timeout*))

(defn schedule-asset-list-live-subscription-resume!
  [{:keys [live-subscription-resume-timeout* scrolling?*]}]
  (when (nil? @live-subscription-resume-timeout*)
    (reset! live-subscription-resume-timeout*
            (asset-list-set-timeout!
              (fn []
                (reset! live-subscription-resume-timeout* nil)
                (when-not @scrolling?*
                  (set-asset-list-freeze-active! false)
                  (set-asset-list-live-market-subscriptions-paused! false)))
              asset-list-live-subscription-resume-delay-ms))))

(declare finalize-asset-list-scroll!)

(defn ensure-asset-list-scroll-settle!
  [{:keys [last-scroll-activity-ms* scroll-settle-timeout*] :as runtime-state}]
  (reset! last-scroll-activity-ms* (asset-list-now-ms))
  (when-not @scroll-settle-timeout*
    (letfn [(tick []
              (let [elapsed-ms (- (asset-list-now-ms)
                                  (or @last-scroll-activity-ms* 0))
                    remaining-ms (- asset-list-scroll-settle-delay-ms elapsed-ms)]
                (if (pos? remaining-ms)
                  (reset! scroll-settle-timeout*
                          (asset-list-set-timeout! tick remaining-ms))
                  (finalize-asset-list-scroll! runtime-state))))]
      (reset! scroll-settle-timeout*
              (asset-list-set-timeout! tick asset-list-scroll-settle-delay-ms)))))

(defn finalize-asset-list-scroll!
  [{:keys [host-node* props* pending-props* last-window-state* scroll-top*
           scroll-settle-timeout* scrolling?* render-limit-sync-timeout*
           live-subscription-resume-timeout*] :as runtime-state}]
  (clear-asset-list-timeout-atom! scroll-settle-timeout*)
  (reset! scrolling?* false)
  (set-asset-list-scroll-active! false)
  (persist-asset-list-scroll-top! @scroll-top*)
  (when-let [pending-props @pending-props*]
    (when-let [host-node @host-node*]
      (sync-asset-list-props! host-node
                              props*
                              pending-props*
                              last-window-state*
                              pending-props
                              @scroll-top*)
      (schedule-asset-list-render-limit-sync! pending-props render-limit-sync-timeout*)))
  (schedule-asset-list-live-subscription-resume! runtime-state))

(defn asset-list-dynamic-overscan-rows
  [current-window-state next-scroll-top]
  (let [previous-scroll-top (or (:scroll-top current-window-state) 0)
        delta-px (-> (- (query/normalize-scroll-top next-scroll-top)
                        previous-scroll-top)
                     js/Math.abs)
        delta-rows (-> (/ delta-px list-metrics/row-height-px)
                       js/Math.ceil
                       int)]
    (-> (+ query/default-overscan-rows
           (* 2 delta-rows))
        (max query/default-overscan-rows)
        (min asset-list-max-active-scroll-overscan-rows))))

(defn asset-list-host-node
  [node]
  (or (.querySelector node "[data-role='asset-selector-list-body-host']")
      (.-firstElementChild node)))

(defn asset-list-on-render
  [props]
  (fn [{:keys [:replicant/life-cycle :replicant/node :replicant/memory :replicant/remember]}]
    (case life-cycle
      :replicant.life-cycle/mount
      (let [props* (atom props)
            pending-props* (atom nil)
            scroll-top* (atom (query/normalize-scroll-top (:scroll-top props)))
            host-node* (atom (asset-list-host-node node))
            last-window-state* (atom nil)
            last-scroll-activity-ms* (atom nil)
            scrolling?* (atom false)
            scroll-settle-timeout* (atom nil)
            render-limit-sync-timeout* (atom nil)
            live-subscription-resume-timeout* (atom nil)
            runtime-state {:host-node* host-node*
                           :props* props*
                           :pending-props* pending-props*
                           :last-window-state* last-window-state*
                           :last-scroll-activity-ms* last-scroll-activity-ms*
                           :scroll-top* scroll-top*
                           :scroll-settle-timeout* scroll-settle-timeout*
                           :scrolling?* scrolling?*
                           :render-limit-sync-timeout* render-limit-sync-timeout*
                           :live-subscription-resume-timeout* live-subscription-resume-timeout*}
            begin-active-scroll!
            (fn []
              (when-not @scrolling?*
                (reset! scrolling?* true)
                (set-asset-list-scroll-active! true)
                (set-asset-list-freeze-active! true)
                (cancel-asset-list-live-subscription-resume! live-subscription-resume-timeout*)
                (set-asset-list-live-market-subscriptions-paused! true)))
            on-wheel (fn [event]
                       (when-not (zero? (or (.-deltaY event) 0))
                         (begin-active-scroll!)
                         (ensure-asset-list-scroll-settle! runtime-state)))
            on-scroll (fn [_event]
                        (let [was-scrolling? @scrolling?*
                              next-scroll-top (.-scrollTop node)
                              current-window-state @last-window-state*
                              overscan-rows (asset-list-dynamic-overscan-rows current-window-state next-scroll-top)
                              next-window-state (asset-list-window-state @props* next-scroll-top overscan-rows)
                              host-node (or @host-node* (asset-list-host-node node))]
                          (reset! scroll-top* next-scroll-top)
                          (reset! host-node* host-node)
                          (when-not was-scrolling?
                            (begin-active-scroll!))
                          (when-not (asset-list-window-covered? current-window-state next-window-state)
                            (render-asset-list-body! host-node @props* next-scroll-top overscan-rows)
                            (reset! last-window-state* next-window-state))
                          (ensure-asset-list-scroll-settle! runtime-state)))]
        (set-asset-list-scroll-active! false)
        (set-asset-list-freeze-active! false)
        (set! (.-scrollTop node) @scroll-top*)
        (.addEventListener node "wheel" on-wheel)
        (.addEventListener node "scroll" on-scroll)
        (render-asset-list-body! @host-node* @props* @scroll-top*)
        (reset! last-window-state* (asset-list-window-state @props* @scroll-top*))
        (schedule-asset-list-render-limit-sync! @props* render-limit-sync-timeout*)
        (remember {:on-wheel on-wheel
                   :on-scroll on-scroll
                   :props* props*
                   :pending-props* pending-props*
                   :scroll-top* scroll-top*
                   :host-node* host-node*
                   :last-window-state* last-window-state*
                   :last-scroll-activity-ms* last-scroll-activity-ms*
                   :scrolling?* scrolling?*
                   :scroll-settle-timeout* scroll-settle-timeout*
                   :render-limit-sync-timeout* render-limit-sync-timeout*
                   :live-subscription-resume-timeout* live-subscription-resume-timeout*}))

      :replicant.life-cycle/update
      (let [props* (or (:props* memory) (atom props))
            pending-props* (or (:pending-props* memory) (atom nil))
            scroll-top* (or (:scroll-top* memory)
                            (atom (query/normalize-scroll-top (:scroll-top props))))
            host-node* (or (:host-node* memory) (atom (asset-list-host-node node)))
            last-window-state* (or (:last-window-state* memory) (atom nil))
            last-scroll-activity-ms* (or (:last-scroll-activity-ms* memory) (atom nil))
            scrolling?* (or (:scrolling?* memory) (atom false))
            scroll-settle-timeout* (or (:scroll-settle-timeout* memory) (atom nil))
            render-limit-sync-timeout* (or (:render-limit-sync-timeout* memory) (atom nil))
            live-subscription-resume-timeout* (or (:live-subscription-resume-timeout* memory) (atom nil))
            on-wheel (:on-wheel memory)
            on-scroll (:on-scroll memory)
            live-scroll-top (or (.-scrollTop node) @scroll-top*)
            host-node (or @host-node* (asset-list-host-node node))]
        (reset! scroll-top* live-scroll-top)
        (reset! host-node* host-node)
        (if @scrolling?*
          (reset! pending-props* props)
          (do
            (sync-asset-list-props! host-node
                                    props*
                                    pending-props*
                                    last-window-state*
                                    props
                                    @scroll-top*)
            (schedule-asset-list-render-limit-sync! @props* render-limit-sync-timeout*)))
        (remember {:on-wheel on-wheel
                   :on-scroll on-scroll
                   :props* props*
                   :pending-props* pending-props*
                   :scroll-top* scroll-top*
                   :host-node* host-node*
                   :last-window-state* last-window-state*
                   :last-scroll-activity-ms* last-scroll-activity-ms*
                   :scrolling?* scrolling?*
                   :scroll-settle-timeout* scroll-settle-timeout*
                   :render-limit-sync-timeout* render-limit-sync-timeout*
                   :live-subscription-resume-timeout* live-subscription-resume-timeout*}))

      :replicant.life-cycle/unmount
      (do
        (set-asset-list-scroll-active! false)
        (set-asset-list-freeze-active! false)
        (set-asset-list-live-market-subscriptions-paused! false)
        (when-let [on-wheel (:on-wheel memory)]
          (.removeEventListener node "wheel" on-wheel))
        (when-let [on-scroll (:on-scroll memory)]
          (.removeEventListener node "scroll" on-scroll))
        (when-let [timeout* (:scroll-settle-timeout* memory)]
          (clear-asset-list-timeout-atom! timeout*))
        (when-let [timeout* (:render-limit-sync-timeout* memory)]
          (clear-asset-list-timeout-atom! timeout*))
        (when-let [timeout* (:live-subscription-resume-timeout* memory)]
          (clear-asset-list-timeout-atom! timeout*))
        (when-let [host-node (some-> (:host-node* memory) deref)]
          (r/unmount host-node)))

      nil)))

(defn asset-list
  ([assets selected-market-key highlighted-market-key favorites missing-icons loaded-icons render-limit scroll-top]
   (asset-list assets selected-market-key highlighted-market-key favorites missing-icons loaded-icons render-limit scroll-top false nil))
  ([assets selected-market-key highlighted-market-key favorites missing-icons loaded-icons render-limit scroll-top suppress-empty-state?]
   (asset-list assets selected-market-key highlighted-market-key favorites missing-icons loaded-icons render-limit scroll-top suppress-empty-state? nil))
  ([assets selected-market-key highlighted-market-key favorites missing-icons loaded-icons render-limit scroll-top suppress-empty-state? scroll-reset-key]
   (let [props {:assets assets
                :selected-market-key selected-market-key
                :highlighted-market-key highlighted-market-key
                :favorites favorites
                :missing-icons missing-icons
                :loaded-icons loaded-icons
                :render-limit render-limit
                :scroll-top scroll-top
                :suppress-empty-state? suppress-empty-state?}]
     [:div.max-h-64.overflow-y-auto.scrollbar-hide
      {:style {:overflow-anchor "none"}
       :data-role "asset-selector-scroll-container"
       :replicant/key (or scroll-reset-key "asset-selector-list")
       :replicant/on-render (asset-list-on-render props)}
      [:div {:style {:overflow-anchor "none"}
             :data-role "asset-selector-list-body-host"}]])))

(ns hyperopen.views.footer-view
  (:require [hyperopen.config :as app-config]
            [hyperopen.platform :as platform]
            [hyperopen.views.footer.connection-meter :as connection-meter]
            [hyperopen.views.footer.diagnostics-drawer :as diagnostics-drawer]
            [hyperopen.views.footer.links :as footer-links]
            [hyperopen.views.footer.mobile-nav :as mobile-nav]
            [hyperopen.websocket.diagnostics.view-model :as diagnostics-vm]))

(def ^:private default-app-version
  (:app-version app-config/config))

(def ^:private diagnostics-timeline-limit
  (get-in app-config/config [:diagnostics :timeline-limit]))

(defn- app-build-id
  []
  (some-> js/globalThis
          (aget "HYPEROPEN_BUILD_ID")
          str))

(defn- browser-network-connection
  []
  (let [navigator (or (.-navigator js/globalThis)
                      (some-> js/globalThis .-window .-navigator))]
    (or (some-> navigator .-connection)
        (some-> navigator (aget "mozConnection"))
        (some-> navigator (aget "webkitConnection")))))

(defn- browser-network-hint
  []
  (let [connection (browser-network-connection)]
    {:effective-type (some-> connection .-effectiveType str)
     :rtt (some-> connection .-rtt)
     :downlink (some-> connection .-downlink)
     :save-data? (true? (some-> connection .-saveData))}))

(defn footer-view
  [state]
  (let [vm (diagnostics-vm/footer-view-model
            state
            {:app-version default-app-version
             :build-id (app-build-id)
             :wall-now-ms (platform/now-ms)
             :diagnostics-timeline-limit diagnostics-timeline-limit
             :network-hint (browser-network-hint)})
        diagnostics-open? (:diagnostics-open? vm)
        footer-z-class (if diagnostics-open? "z-[260]" "z-40")]
    [:footer {:class ["fixed"
                      "inset-x-0"
                      "bottom-0"
                      footer-z-class
                      "isolate"
                      "w-full"
                      "shrink-0"
                      "bg-base-200"
                      "border-t"
                      "border-base-300"]
              :data-parity-id "footer"}
     (mobile-nav/render (:mobile-nav vm))
     [:div {:class ["hidden" "lg:block" "w-full" "app-shell-gutter" "py-2" "relative"]}
      (when-let [banner (:banner vm)]
        [:div {:class (into ["mb-2"
                             "rounded"
                             "border"
                             "px-3"
                             "py-2"
                             "text-xs"
                             "font-medium"]
                            (diagnostics-drawer/banner-classes (:tone banner)))}
         (:message banner)])
      [:div {:class ["flex" "justify-between" "items-center"]}
       [:div {:class ["flex" "items-center"]}
        (connection-meter/render (:connection-meter vm))]
       (footer-links/render (:footer-links vm))]
      (when-let [diagnostics (:diagnostics vm)]
        (diagnostics-drawer/render diagnostics))]]))

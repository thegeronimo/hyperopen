(ns hyperopen.views.trading-chart.utils.chart-interop.chart-context-menu-overlay-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trading-chart.test-support.fake-dom :as fake-dom]
            [hyperopen.views.trading-chart.utils.chart-interop.chart-context-menu-overlay :as chart-context-menu-overlay]))

(defn- make-chart-obj
  [{:keys [coordinate->price]}]
  (doto #js {}
    (aset "mainSeries"
          (doto #js {}
            (aset "coordinateToPrice" (or coordinate->price
                                          (fn [_] nil)))))))

(defn- make-timeout-recorder
  []
  (let [callbacks* (atom [])
        next-id* (atom 0)
        cleared* (atom [])]
    {:set-timeout! (fn [callback _ms]
                     (let [timeout-id (swap! next-id* inc)]
                       (swap! callbacks* conj [timeout-id callback])
                       timeout-id))
     :clear-timeout! (fn [timeout-id]
                       (swap! cleared* conj timeout-id)
                       (swap! callbacks*
                              (fn [entries]
                                (vec (remove #(= timeout-id (first %)) entries)))))
     :flush-next! (fn []
                    (when-let [[_ callback] (first @callbacks*)]
                      (swap! callbacks* #(vec (rest %)))
                      (callback)
                      true))
     :callbacks* callbacks*
     :cleared* cleared*}))

(defn- resolved-promise []
  (let [promise (js-obj)]
    (aset promise "then"
          (fn [resolve]
            (resolve)
            promise))
    (aset promise "catch"
          (fn [_reject]
            promise))
    promise))

(defn- sync-overlay!
  ([chart-obj container candles]
   (sync-overlay! chart-obj container candles {}))
  ([chart-obj container candles opts]
   (chart-context-menu-overlay/sync-chart-context-menu-overlay!
    chart-obj
    container
    candles
    (merge {:document (.-ownerDocument container)
            :format-price (fn [price]
                            (.toFixed price 3))
            :context-key "BTC::1d"}
           opts))))

(defn- install-focus-stubs!
  [document node]
  (aset node
        "focus"
        (fn []
          (aset document "activeElement" node)))
  (aset node
        "blur"
        (fn []
          (when (identical? (.-activeElement document) node)
            (aset document "activeElement" nil)))))

(deftest chart-context-menu-opens-on-right-click-and-flips-inside-chart-bounds-test
  (let [document (fake-dom/make-fake-document)
        container (.createElement document "div")
        chart-obj (make-chart-obj {:coordinate->price (fn [y]
                                                        (+ 70000 (/ y 10)))})
        prevented? (atom false)
        stopped? (atom false)]
    (set! (.-clientWidth container) 240)
    (set! (.-clientHeight container) 120)
    (sync-overlay! chart-obj container [{:close 69999.5}])
    (fake-dom/dispatch-dom-event-with-payload!
     container
     "contextmenu"
     #js {:target container
          :button 2
          :clientX 230
          :clientY 110
          :preventDefault (fn []
                            (reset! prevented? true))
          :stopPropagation (fn []
                             (reset! stopped? true))})
    (let [state (@#'hyperopen.views.trading-chart.utils.chart-interop.chart-context-menu-overlay/overlay-state
                 chart-obj)
          root (:root state)
          panel (:panel state)
          reset-button (:reset-button state)
          copy-button (:copy-button state)]
      (is @prevented?)
      (is @stopped?)
      (is (= "relative" (.-position (.-style container))))
      (is (= "1" (.-opacity (.-style root))))
      (is (= "auto" (.-pointerEvents (.-style root))))
      (is (= "26px" (.-left (.-style root))))
      (is (= "25px" (.-top (.-style root))))
      (is (= "menu" (aget panel "role")))
      (is (= "Chart context menu" (aget panel "aria-label")))
      (is (= "Reset chart view" (.-textContent (aget reset-button "labelNode"))))
      (is (= "Copy price 70011.000" (.-textContent (aget copy-button "labelNode"))))
      (is (= "false" (aget copy-button "aria-disabled")))
      (is (identical? reset-button (.-activeElement document))))
    (chart-context-menu-overlay/clear-chart-context-menu-overlay! chart-obj)))

(deftest chart-context-menu-supports-keyboard-open-navigation-and-escape-close-test
  (let [document (fake-dom/make-fake-document)
        container (.createElement document "div")
        chart-obj (make-chart-obj {:coordinate->price (fn [y]
                                                        (+ 10 y))})
        prevented? (atom false)]
    (install-focus-stubs! document container)
    (set! (.-clientWidth container) 300)
    (set! (.-clientHeight container) 180)
    (sync-overlay! chart-obj container [{:close 111.0}])
    (fake-dom/dispatch-dom-event-with-payload!
     container
     "keydown"
     #js {:target container
          :key "ContextMenu"
          :preventDefault (fn []
                            (reset! prevented? true))
          :stopPropagation (fn [] nil)})
     (let [state (@#'hyperopen.views.trading-chart.utils.chart-interop.chart-context-menu-overlay/overlay-state
                 chart-obj)
          reset-button (:reset-button state)
          copy-button (:copy-button state)]
      (install-focus-stubs! document reset-button)
      (install-focus-stubs! document copy-button)
      (is @prevented?)
      (fake-dom/dispatch-dom-event-with-payload!
       container
       "keydown"
       #js {:target container
            :key "ContextMenu"
            :preventDefault (fn [] nil)
            :stopPropagation (fn [] nil)})
      (is (identical? reset-button (.-activeElement document)))
      (fake-dom/dispatch-dom-event-with-payload!
       reset-button
       "keydown"
       #js {:target reset-button
            :key "ArrowDown"
            :preventDefault (fn [] nil)
            :stopPropagation (fn [] nil)})
      (is (identical? copy-button (.-activeElement document)))
      (fake-dom/dispatch-dom-event-with-payload!
       copy-button
       "keydown"
       #js {:target copy-button
            :key "Escape"
            :preventDefault (fn [] nil)
            :stopPropagation (fn [] nil)})
      (is (false? (:menu-open? (@#'hyperopen.views.trading-chart.utils.chart-interop.chart-context-menu-overlay/overlay-state
                                chart-obj))))
      (is (identical? container (.-activeElement document))))
    (chart-context-menu-overlay/clear-chart-context-menu-overlay! chart-obj)))

(deftest chart-context-menu-copy-action-updates-feedback-and-closes-after-timeout-test
  (let [document (fake-dom/make-fake-document)
        container (.createElement document "div")
        chart-obj (make-chart-obj {:coordinate->price (fn [y]
                                                        (+ 1000 y))})
        clipboard-payloads* (atom [])
        timeouts (make-timeout-recorder)]
    (install-focus-stubs! document container)
    (sync-overlay! chart-obj
                   container
                   [{:close 1001.0}]
                   {:clipboard #js {:writeText (fn [payload]
                                                (swap! clipboard-payloads* conj payload)
                                                (resolved-promise))}
                    :set-timeout-fn (:set-timeout! timeouts)
                    :clear-timeout-fn (:clear-timeout! timeouts)})
    (fake-dom/dispatch-dom-event-with-payload!
     container
     "contextmenu"
     #js {:target container
          :button 2
          :clientX 40
          :clientY 20
          :preventDefault (fn [] nil)
          :stopPropagation (fn [] nil)})
     (let [state (@#'hyperopen.views.trading-chart.utils.chart-interop.chart-context-menu-overlay/overlay-state
                 chart-obj)
          copy-button (:copy-button state)]
      (install-focus-stubs! document copy-button)
      (fake-dom/click-dom-node! copy-button)
      (is (= ["1020.000"] @clipboard-payloads*))
      (is (= "Copied" (.-textContent (aget copy-button "labelNode"))))
      (is (= 1 (count @(:callbacks* timeouts))))
      ((:flush-next! timeouts))
      (is (false? (:menu-open? (@#'hyperopen.views.trading-chart.utils.chart-interop.chart-context-menu-overlay/overlay-state
                                chart-obj))))
      (is (identical? container (.-activeElement document))))
    (chart-context-menu-overlay/clear-chart-context-menu-overlay! chart-obj)))

(deftest chart-context-menu-copy-uses-price-decimals-for-full-precision-label-and-payload-test
  (let [document (fake-dom/make-fake-document)
        container (.createElement document "div")
        chart-obj (make-chart-obj {:coordinate->price (fn [_y]
                                                        0.086823)})
        clipboard-payloads* (atom [])]
    (sync-overlay! chart-obj
                   container
                   [{:close 0.086823}]
                   {:price-decimals 6
                    :format-price (fn [_price]
                                    "0.09")
                    :clipboard #js {:writeText (fn [payload]
                                                (swap! clipboard-payloads* conj payload)
                                                (resolved-promise))}})
    (fake-dom/dispatch-dom-event-with-payload!
     container
     "contextmenu"
     #js {:target container
          :button 2
          :clientX 40
          :clientY 20
          :preventDefault (fn [] nil)
          :stopPropagation (fn [] nil)})
    (let [state (@#'hyperopen.views.trading-chart.utils.chart-interop.chart-context-menu-overlay/overlay-state
                 chart-obj)
          copy-button (:copy-button state)]
      (is (= "Copy price 0.086823" (.-textContent (aget copy-button "labelNode"))))
      (fake-dom/click-dom-node! copy-button)
      (is (= ["0.086823"] @clipboard-payloads*)))
    (chart-context-menu-overlay/clear-chart-context-menu-overlay! chart-obj)))

(deftest chart-context-menu-disables-copy-row-when-no-price-is-available-test
  (let [document (fake-dom/make-fake-document)
        container (.createElement document "div")
        chart-obj (make-chart-obj {:coordinate->price (fn [_]
                                                        nil)})
        clipboard-payloads* (atom [])]
    (sync-overlay! chart-obj
                   container
                   []
                   {:clipboard #js {:writeText (fn [payload]
                                                (swap! clipboard-payloads* conj payload)
                                                (resolved-promise))}})
    (fake-dom/dispatch-dom-event-with-payload!
     container
     "contextmenu"
     #js {:target container
          :button 2
          :clientX 20
          :clientY 20
          :preventDefault (fn [] nil)
          :stopPropagation (fn [] nil)})
    (let [state (@#'hyperopen.views.trading-chart.utils.chart-interop.chart-context-menu-overlay/overlay-state
                 chart-obj)
          copy-button (:copy-button state)]
      (is (= "Copy price --" (.-textContent (aget copy-button "labelNode"))))
      (is (= "true" (aget copy-button "aria-disabled")))
      (fake-dom/click-dom-node! copy-button)
      (is (= [] @clipboard-payloads*))
      (is (true? (:menu-open? (@#'hyperopen.views.trading-chart.utils.chart-interop.chart-context-menu-overlay/overlay-state
                               chart-obj)))))
    (chart-context-menu-overlay/clear-chart-context-menu-overlay! chart-obj)))

(deftest chart-context-menu-closes-when-context-key-changes-test
  (let [document (fake-dom/make-fake-document)
        container (.createElement document "div")
        chart-obj (make-chart-obj {:coordinate->price (fn [y]
                                                        (+ 2000 y))})]
    (sync-overlay! chart-obj container [{:close 2000.0}] {:context-key "BTC::1d"})
    (fake-dom/dispatch-dom-event-with-payload!
     container
     "contextmenu"
     #js {:target container
          :button 2
          :clientX 30
          :clientY 20
          :preventDefault (fn [] nil)
          :stopPropagation (fn [] nil)})
    (is (true? (:menu-open? (@#'hyperopen.views.trading-chart.utils.chart-interop.chart-context-menu-overlay/overlay-state
                             chart-obj))))
    (sync-overlay! chart-obj container [{:close 2000.0}] {:context-key "ETH::1d"})
    (is (not (true? (:menu-open? (@#'hyperopen.views.trading-chart.utils.chart-interop.chart-context-menu-overlay/overlay-state
                                  chart-obj)))))
    (chart-context-menu-overlay/clear-chart-context-menu-overlay! chart-obj)))

(deftest chart-context-menu-clear-removes-root-and-listeners-test
  (let [document (fake-dom/make-fake-document)
        container (.createElement document "div")
        chart-obj (make-chart-obj {:coordinate->price (fn [y]
                                                        y)})]
    (sync-overlay! chart-obj container [{:close 1.0}])
    (fake-dom/dispatch-dom-event-with-payload!
     container
     "contextmenu"
     #js {:target container
          :button 2
          :clientX 10
          :clientY 10
          :preventDefault (fn [] nil)
          :stopPropagation (fn [] nil)})
    (chart-context-menu-overlay/clear-chart-context-menu-overlay! chart-obj)
    (is (zero? (alength (.-children container))))
    (is (= {} (@#'hyperopen.views.trading-chart.utils.chart-interop.chart-context-menu-overlay/overlay-state
               chart-obj)))))

(deftest chart-context-menu-outside-close-blurs-hidden-menu-focus-test
  (let [document (fake-dom/make-fake-document)
        container (.createElement document "div")
        outside-node (.createElement document "div")
        chart-obj (make-chart-obj {:coordinate->price (fn [y]
                                                        (+ 500 y))})]
    (install-focus-stubs! document container)
    (sync-overlay! chart-obj container [{:close 501.0}])
    (fake-dom/dispatch-dom-event-with-payload!
     container
     "contextmenu"
     #js {:target container
          :button 2
          :clientX 30
          :clientY 20
          :preventDefault (fn [] nil)
          :stopPropagation (fn [] nil)})
    (let [state (@#'hyperopen.views.trading-chart.utils.chart-interop.chart-context-menu-overlay/overlay-state
                 chart-obj)
          reset-button (:reset-button state)]
      (install-focus-stubs! document reset-button)
      (.focus reset-button)
      (is (identical? reset-button (.-activeElement document)))
      (fake-dom/dispatch-dom-event-with-payload!
       container
       "pointerdown"
       #js {:target outside-node})
      (is (false? (:menu-open? (@#'hyperopen.views.trading-chart.utils.chart-interop.chart-context-menu-overlay/overlay-state
                                chart-obj))))
      (is (nil? (.-activeElement document))))
    (chart-context-menu-overlay/clear-chart-context-menu-overlay! chart-obj)))

(deftest chart-context-menu-ignores-touch-contextmenu-events-test
  (let [document (fake-dom/make-fake-document)
        container (.createElement document "div")
        chart-obj (make-chart-obj {:coordinate->price (fn [y]
                                                        (+ 10 y))})
        prevented? (atom false)]
    (sync-overlay! chart-obj container [{:close 20.0}])
    (fake-dom/dispatch-dom-event-with-payload!
     container
     "contextmenu"
     #js {:target container
          :button 0
          :pointerType "touch"
          :clientX 30
          :clientY 20
          :preventDefault (fn []
                            (reset! prevented? true))
          :stopPropagation (fn [] nil)})
    (is (false? @prevented?))
    (is (not (true? (:menu-open? (@#'hyperopen.views.trading-chart.utils.chart-interop.chart-context-menu-overlay/overlay-state
                                  chart-obj)))))
    (chart-context-menu-overlay/clear-chart-context-menu-overlay! chart-obj)))

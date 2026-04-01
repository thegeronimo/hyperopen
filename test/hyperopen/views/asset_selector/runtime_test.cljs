(ns hyperopen.views.asset-selector.runtime-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.system :as app-system]
            [hyperopen.views.asset-selector.runtime :as runtime]
            [hyperopen.views.asset-selector.test-support :as support]
            [nexus.registry :as nxr]
            [replicant.dom :as r]))

(deftest asset-list-uses-runtime-backed-scroll-container-and-fully-renders-normal-sized-market-set-test
  (let [assets (vec (for [n (range 150)]
                      {:key (str "perp:T" n)
                       :symbol (str "T" n "-USDC")
                       :coin (str "T" n)
                       :base (str "T" n)
                       :market-type :perp}))
        hiccup (runtime/asset-list assets nil nil #{} #{} #{} 40 0)
        scroll-container hiccup
        attrs (second scroll-container)
        inner-wrapper (first (support/node-children scroll-container))
        inner-attrs (second inner-wrapper)
        body-hiccup (runtime/asset-list-body assets nil nil #{} #{} #{} 40 0 false)
        strings (set (support/collect-strings body-hiccup))]
    (is (ifn? (:replicant/on-render attrs)))
    (is (= "asset-selector-list" (:replicant/key attrs)))
    (is (= "none" (get-in attrs [:style :overflow-anchor])))
    (is (= "asset-selector-list-body-host" (:data-role inner-attrs)))
    (is (= "none" (get-in inner-attrs [:style :overflow-anchor])))
    (is (= 150 (support/count-selectable-asset-rows body-hiccup)))
    (is (not (contains? strings "Showing 40 of 150 markets")))
    (is (not (contains? strings "Load more")))
    (is (not (contains? strings "Show all")))))

(deftest asset-list-falls-back-to-virtual-window-for-large-market-sets-test
  (let [assets (vec (for [n (range 1500)]
                      {:key (str "perp:T" n)
                       :symbol (str "T" n "-USDC")
                       :coin (str "T" n)
                       :base (str "T" n)
                       :market-type :perp}))
        body-hiccup (runtime/asset-list-body assets nil nil #{} #{} #{} 40 0 false)]
    (is (< (support/count-selectable-asset-rows body-hiccup) 200))
    (is (>= (support/count-selectable-asset-rows body-hiccup) 8))))

(deftest asset-list-renders-all-rows-when-render-limit-exceeds-total-test
  (let [assets (vec (for [n (range 8)]
                      {:key (str "perp:T" n)
                       :symbol (str "T" n "-USDC")
                       :coin (str "T" n)
                       :base (str "T" n)
                       :market-type :perp}))
        hiccup (runtime/asset-list-body assets nil nil #{} #{} #{} 120 0 false)
        strings (set (support/collect-strings hiccup))]
    (is (= 8 (support/count-selectable-asset-rows hiccup)))
    (is (not (contains? strings "Showing 120 of 8 markets")))))

(deftest asset-list-allows-callers-to-force-a-scroll-runtime-reset-key-test
  (let [hiccup (runtime/asset-list support/sample-markets nil nil #{} #{} #{} 120 0 false "search-session")
        attrs (second hiccup)]
    (is (= "search-session" (:replicant/key attrs)))
    (is (ifn? (:replicant/on-render attrs)))))

(deftest asset-list-virtual-window-tracks-scroll-position-test
  (let [assets (vec (for [n (range 1500)]
                      {:key (str "perp:T" n)
                       :symbol (str "T" n "-USDC")
                       :coin (str "T" n)
                       :base (str "T" n)
                       :market-type :perp}))
        top-hiccup (runtime/asset-list-body assets nil nil #{} #{} #{} 120 0 false)
        deep-hiccup (runtime/asset-list-body assets nil nil #{} #{} #{} 120 2200 false)
        top-strings (set (support/collect-strings top-hiccup))
        deep-strings (set (support/collect-strings deep-hiccup))]
    (is (contains? top-strings "T0-USDC"))
    (is (not (contains? deep-strings "T0-USDC")))
    (is (contains? deep-strings "T90-USDC"))))

(deftest asset-list-scroll-window-does-not-clip-to-render-limit-test
  (let [assets (vec (for [n (range 1500)]
                      {:key (str "perp:T" n)
                       :symbol (str "T" n "-USDC")
                       :coin (str "T" n)
                       :base (str "T" n)
                       :market-type :perp}))
        deep-hiccup (runtime/asset-list-body assets nil nil #{} #{} #{} 40 2200 false)
        deep-strings (set (support/collect-strings deep-hiccup))]
    (is (contains? deep-strings "T90-USDC"))
    (is (not (contains? deep-strings "T0-USDC")))))

(deftest asset-list-full-render-window-keeps-viewport-covered-across-deep-scrolls-test
  (let [assets (vec (for [n (range 200)]
                      {:key (str "perp:T" n)
                       :symbol (str "T" n "-USDC")
                       :coin (str "T" n)
                       :base (str "T" n)
                       :market-type :perp}))
        current-window-state (runtime/asset-list-window-state {:assets assets} 0)
        deep-window-state (runtime/asset-list-window-state {:assets assets} 3600)]
    (is (true? (runtime/asset-list-viewport-covered? current-window-state deep-window-state)))))

(deftest asset-list-viewport-coverage-only-breaks-when-scroll-leaves-current-window-test
  (let [assets (vec (for [n (range 1500)]
                      {:key (str "perp:T" n)
                       :symbol (str "T" n "-USDC")
                       :coin (str "T" n)
                       :base (str "T" n)
                       :market-type :perp}))
        current-window-state (runtime/asset-list-window-state {:assets assets} 0)
        covered-window-state (runtime/asset-list-window-state {:assets assets} 72)
        uncovered-window-state (runtime/asset-list-window-state {:assets assets} 720)]
    (is (true? (runtime/asset-list-viewport-covered? current-window-state covered-window-state)))
    (is (false? (runtime/asset-list-viewport-covered? current-window-state uncovered-window-state)))))

(deftest asset-list-scroll-runtime-expands-window-before-the-viewport-is-outrun-test
  (let [assets (vec (for [n (range 1500)]
                      {:key (str "perp:T" n)
                       :symbol (str "T" n "-USDC")
                       :coin (str "T" n)
                       :base (str "T" n)
                       :market-type :perp}))
        current-window-state (runtime/asset-list-window-state {:assets assets} 0)
        expanded-window-state (runtime/asset-list-window-state {:assets assets} 72 48)]
    (is (true? (runtime/asset-list-viewport-covered? current-window-state expanded-window-state)))
    (is (false? (runtime/asset-list-window-covered? current-window-state expanded-window-state)))))

(deftest asset-list-runtime-defers-prop-sync-until-scroll-settles-test
  (let [assets (vec (for [n (range 60)]
                      {:key (str "perp:T" n)
                       :symbol (str "T" n "-USDC")
                       :coin (str "T" n)
                       :base (str "T" n)
                       :market-type :perp}))
        update-assets (vec (reverse assets))
        remembered* (atom nil)
        rendered* (atom [])
        now* (atom 0)
        timeouts* (atom [])
        {node :node host-node :host-node listeners* :listeners*} (support/fake-scroll-node)]
    (with-redefs [r/render
                  (fn [runtime-host hiccup]
                    (swap! rendered* conj {:host runtime-host
                                           :strings (set (support/collect-strings hiccup))}))
                  runtime/schedule-asset-list-render-limit-sync!
                  (fn [& _] nil)
                  runtime/asset-list-now-ms
                  (fn [] @now*)
                  runtime/asset-list-set-timeout!
                  (fn [f delay-ms]
                    (let [timeout-handle {:delay-ms delay-ms
                                          :index (count @timeouts*)}]
                      (swap! timeouts* conj {:fn f
                                             :delay-ms delay-ms
                                             :handle timeout-handle})
                      timeout-handle))
                  runtime/asset-list-clear-timeout!
                  (fn [_timeout-handle] nil)]
      (let [mount-on-render (get-in (runtime/asset-list assets "perp:T0" nil #{} #{} #{} 120 0 false)
                                    [1 :replicant/on-render])
            update-on-render (get-in (runtime/asset-list update-assets "perp:T5" nil #{} #{} #{} 120 0 false)
                                     [1 :replicant/on-render])]
        (mount-on-render {:replicant/life-cycle :replicant.life-cycle/mount
                          :replicant/node node
                          :replicant/remember (fn [memory]
                                                (reset! remembered* memory))})
        (set! (.-scrollTop node) 72)
        ((get @listeners* "scroll") #js {:timeStamp 1})
        (update-on-render {:replicant/life-cycle :replicant.life-cycle/update
                           :replicant/node node
                           :replicant/memory @remembered*
                           :replicant/remember (fn [memory]
                                                 (reset! remembered* memory))})
        (is (= 1 (count @timeouts*)))
        (is (= 1 (count @rendered*)))
        (is (= host-node (:host (first @rendered*))))
        (is (contains? (:strings (first @rendered*)) "T0-USDC"))
        (reset! now* 120)
        ((:fn (first @timeouts*)))
        (is (= 2 (count @rendered*)))
        (is (= host-node (:host (second @rendered*))))
        (is (contains? (:strings (second @rendered*)) "T59-USDC"))))))

(deftest asset-list-runtime-reuses-one-settle-timer-during-active-scroll-test
  (let [assets (vec (for [n (range 60)]
                      {:key (str "perp:T" n)
                       :symbol (str "T" n "-USDC")
                       :coin (str "T" n)
                       :base (str "T" n)
                       :market-type :perp}))
        remembered* (atom nil)
        timeout-installs* (atom 0)
        {node :node listeners* :listeners*} (support/fake-scroll-node)]
    (with-redefs [r/render
                  (fn [& _] nil)
                  runtime/schedule-asset-list-render-limit-sync!
                  (fn [& _] nil)
                  runtime/asset-list-set-timeout!
                  (fn [_f _delay-ms]
                    (swap! timeout-installs* inc)
                    :timeout-handle)]
      (let [on-render (get-in (runtime/asset-list assets nil nil #{} #{} #{} 120 0 false)
                              [1 :replicant/on-render])]
        (on-render {:replicant/life-cycle :replicant.life-cycle/mount
                    :replicant/node node
                    :replicant/remember (fn [memory]
                                          (reset! remembered* memory))})
        (set! (.-scrollTop node) 48)
        ((get @listeners* "scroll") #js {:timeStamp 1})
        (set! (.-scrollTop node) 96)
        ((get @listeners* "scroll") #js {:timeStamp 2})
        (is (= 1 @timeout-installs*))))))

(deftest asset-list-runtime-uses-idle-settle-timer-instead-of-scrollend-listener-test
  (let [assets (vec (for [n (range 60)]
                      {:key (str "perp:T" n)
                       :symbol (str "T" n "-USDC")
                       :coin (str "T" n)
                       :base (str "T" n)
                       :market-type :perp}))
        remembered* (atom nil)
        {node :node listeners* :listeners*} (support/fake-scroll-node)]
    (with-redefs [r/render
                  (fn [& _] nil)
                  runtime/schedule-asset-list-render-limit-sync!
                  (fn [& _] nil)]
      (let [on-render (get-in (runtime/asset-list assets nil nil #{} #{} #{} 120 0 false)
                              [1 :replicant/on-render])]
        (on-render {:replicant/life-cycle :replicant.life-cycle/mount
                    :replicant/node node
                    :replicant/remember (fn [memory]
                                          (reset! remembered* memory))})
        (is (contains? @listeners* "wheel"))
        (is (contains? @listeners* "scroll"))
        (is (not (contains? @listeners* "scrollend")))
        (is (nil? (:on-scroll-end @remembered*)))))))

(deftest asset-list-runtime-delays-live-market-subscription-resume-until-post-settle-timeout-test
  (let [assets (vec (for [n (range 60)]
                      {:key (str "perp:T" n)
                       :symbol (str "T" n "-USDC")
                       :coin (str "T" n)
                       :base (str "T" n)
                       :market-type :perp}))
        remembered* (atom nil)
        dispatches* (atom [])
        now* (atom 0)
        timeouts* (atom [])
        original-store app-system/store
        original-dispatch nxr/dispatch
        {node :node listeners* :listeners*} (support/fake-scroll-node)]
    (set! app-system/store ::store)
    (set! nxr/dispatch (fn [store event actions]
                         (swap! dispatches* conj {:store store
                                                  :event event
                                                  :actions actions})))
    (try
      (with-redefs [r/render
                    (fn [& _] nil)
                    runtime/schedule-asset-list-render-limit-sync!
                    (fn [& _] nil)
                    runtime/asset-list-now-ms
                    (fn [] @now*)
                    runtime/asset-list-set-timeout!
                    (fn [f delay-ms]
                      (let [timeout-handle {:delay-ms delay-ms
                                            :index (count @timeouts*)}]
                        (swap! timeouts* conj {:fn f
                                               :delay-ms delay-ms
                                               :handle timeout-handle})
                        timeout-handle))
                    runtime/asset-list-clear-timeout!
                    (fn [_timeout-handle] nil)]
        (let [on-render (get-in (runtime/asset-list assets nil nil #{} #{} #{} 120 0 false)
                                [1 :replicant/on-render])]
          (on-render {:replicant/life-cycle :replicant.life-cycle/mount
                      :replicant/node node
                      :replicant/remember (fn [memory]
                                            (reset! remembered* memory))})
          (set! (.-scrollTop node) 48)
          ((get @listeners* "scroll") #js {:timeStamp 1})
          (set! (.-scrollTop node) 96)
          ((get @listeners* "scroll") #js {:timeStamp 2})
          (is (= 1 (count @timeouts*)))
          (is (true? (runtime/asset-list-scroll-active?)))
          (is (true? (runtime/asset-list-freeze-active?)))
          (reset! now* 120)
          ((:fn (first @timeouts*)))
          (is (false? (runtime/asset-list-scroll-active?)))
          (is (true? (runtime/asset-list-freeze-active?)))
          (is (= [{:store ::store
                   :event nil
                   :actions [[:actions/set-asset-selector-live-market-subscriptions-paused true]]}
                  {:store ::store
                   :event nil
                   :actions [[:actions/set-asset-selector-scroll-top 96]]}]
                 @dispatches*))
          (is (= 2 (count @timeouts*)))
          ((:fn (second @timeouts*)))
          (is (false? (runtime/asset-list-scroll-active?)))
          (is (false? (runtime/asset-list-freeze-active?)))
          (is (= [{:store ::store
                   :event nil
                   :actions [[:actions/set-asset-selector-live-market-subscriptions-paused true]]}
                  {:store ::store
                   :event nil
                   :actions [[:actions/set-asset-selector-scroll-top 96]]}
                  {:store ::store
                   :event nil
                   :actions [[:actions/set-asset-selector-live-market-subscriptions-paused false]]}]
                 @dispatches*))))
      (finally
        (set! nxr/dispatch original-dispatch)
        (set! app-system/store original-store)))))

(deftest asset-list-runtime-cancels-pending-live-market-resume-when-wheel-input-continues-at-boundary-test
  (let [assets (vec (for [n (range 60)]
                      {:key (str "perp:T" n)
                       :symbol (str "T" n "-USDC")
                       :coin (str "T" n)
                       :base (str "T" n)
                       :market-type :perp}))
        remembered* (atom nil)
        cleared-timeouts* (atom [])
        now* (atom 0)
        timeouts* (atom [])
        {node :node listeners* :listeners*} (support/fake-scroll-node)]
    (with-redefs [r/render
                  (fn [& _] nil)
                  runtime/schedule-asset-list-render-limit-sync!
                  (fn [& _] nil)
                  runtime/asset-list-now-ms
                  (fn [] @now*)
                  runtime/asset-list-set-timeout!
                  (fn [f delay-ms]
                    (let [timeout-handle {:delay-ms delay-ms
                                          :index (count @timeouts*)}]
                      (swap! timeouts* conj {:fn f
                                             :delay-ms delay-ms
                                             :handle timeout-handle})
                      timeout-handle))
                  runtime/asset-list-clear-timeout!
                  (fn [timeout-handle]
                    (swap! cleared-timeouts* conj timeout-handle))]
      (let [on-render (get-in (runtime/asset-list assets nil nil #{} #{} #{} 120 0 false)
                              [1 :replicant/on-render])]
        (on-render {:replicant/life-cycle :replicant.life-cycle/mount
                    :replicant/node node
                    :replicant/remember (fn [memory]
                                          (reset! remembered* memory))})
        (set! (.-scrollTop node) 96)
        ((get @listeners* "scroll") #js {:timeStamp 1})
        (reset! now* 120)
        ((:fn (first @timeouts*)))
        (is (false? (runtime/asset-list-scroll-active?)))
        (is (true? (runtime/asset-list-freeze-active?)))
        (let [resume-timeout-handle (:handle (second @timeouts*))]
          ((get @listeners* "wheel") #js {:deltaY 640})
          (is (true? (runtime/asset-list-scroll-active?)))
          (is (true? (runtime/asset-list-freeze-active?)))
          (is (= resume-timeout-handle
                 (last @cleared-timeouts*)))
          (is (= 2 (count @cleared-timeouts*))))))))

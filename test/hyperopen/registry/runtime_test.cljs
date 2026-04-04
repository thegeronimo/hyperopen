(ns hyperopen.registry.runtime-test
  (:require [goog.object :as gobj]
            [cljs.test :refer-macros [deftest is testing]]
            [nexus.registry :as nxr]
            [hyperopen.registry.runtime :as runtime-registry]
            [hyperopen.runtime.validation :as runtime-validation]
            [hyperopen.schema.runtime-registration-catalog :as runtime-registration-catalog]))

(defn- register-placeholders-under-test!
  []
  (let [registered (atom {})]
    (with-redefs [nxr/register-placeholder! (fn [placeholder-id f]
                                              (swap! registered assoc placeholder-id f))]
      (runtime-registry/register-placeholders!))
    @registered))

(deftest register-effects-wraps-and-registers-handlers-test
  (let [registered (atom [])
        wrapped-calls (atom [])]
    (with-redefs [runtime-registration-catalog/effect-binding-rows (fn []
                                                                     [[:effects/test :test-handler]])
                  runtime-validation/wrap-effect-handler (fn [effect-id handler]
                                                           (fn [& args]
                                                             (swap! wrapped-calls conj [effect-id (vec args)])
                                                             (apply handler args)))
                  nxr/register-effect! (fn [effect-id handler]
                                         (swap! registered conj [effect-id handler]))]
      (runtime-registry/register-effects! {:test-handler (fn [ctx store payload]
                                                           {:ctx ctx
                                                            :store store
                                                            :payload payload})}))
    (let [[effect-id wrapped-handler] (first @registered)]
      (is (= :effects/test effect-id))
      (is (= {:ctx :ctx
              :store :store
              :payload 42}
             (wrapped-handler :ctx :store 42)))
      (is (= [[:effects/test [:ctx :store 42]]]
             @wrapped-calls)))))

(deftest register-effects-throws-when-handler-missing-test
  (with-redefs [runtime-registration-catalog/effect-binding-rows (fn []
                                                                   [[:effects/test :missing-handler]])]
    (is (thrown-with-msg?
         js/Error
         #"Missing effect handler :missing-handler for :effects/test"
         (runtime-registry/register-effects! {})))))

(deftest register-actions-wraps-and-registers-handlers-test
  (let [registered (atom [])
        wrapped-calls (atom [])]
    (with-redefs [runtime-registration-catalog/action-binding-rows (fn []
                                                                     [[:actions/test :test-handler]])
                  runtime-validation/wrap-action-handler (fn [action-id handler]
                                                           (fn [& args]
                                                             (swap! wrapped-calls conj [action-id (vec args)])
                                                             (apply handler args)))
                  nxr/register-action! (fn [action-id handler]
                                         (swap! registered conj [action-id handler]))]
      (runtime-registry/register-actions! {:test-handler (fn [state payload]
                                                           [[:effects/save [:test] [state payload]]])}))
    (let [[action-id wrapped-handler] (first @registered)]
      (is (= :actions/test action-id))
      (is (= [[:effects/save [:test] [{:state true} 42]]]
             (wrapped-handler {:state true} 42)))
      (is (= [[:actions/test [{:state true} 42]]]
             @wrapped-calls)))))

(deftest register-actions-throws-when-handler-missing-test
  (with-redefs [runtime-registration-catalog/action-binding-rows (fn []
                                                                   [[:actions/test :missing-handler]])]
    (is (thrown-with-msg?
         js/Error
         #"Missing action handler :missing-handler for :actions/test"
         (runtime-registry/register-actions! {})))))

(deftest register-system-state-registers-deref-test
  (let [registered-system->state (atom nil)
        store (atom {:count 1})]
    (with-redefs [nxr/register-system->state! (fn [system->state]
                                                (reset! registered-system->state system->state))]
      (runtime-registry/register-system-state!))
    (is (= {:count 1}
           ((deref registered-system->state) store)))
    (swap! store assoc :count 2)
    (is (= {:count 2}
           ((deref registered-system->state) store)))))

(deftest register-placeholders-registers-scalar-event-lookups-test
  (let [placeholders (register-placeholders-under-test!)
        dom-event #js {:target #js {:value "BTC"
                                    :checked true
                                    :scrollTop 24}
                       :key "Enter"
                       :metaKey true
                       :ctrlKey false
                       :timeStamp 1001
                       :clientX 480}
        ctx {:replicant/dom-event dom-event}
        scalar-placeholder-ids [:event.target/value
                                :event.target/checked
                                :event/key
                                :event/metaKey
                                :event/ctrlKey
                                :event.target/scrollTop
                                :event/timeStamp
                                :event/clientX]]
    (is (= 10 (count placeholders)))
    (is (= "BTC" ((get placeholders :event.target/value) ctx)))
    (is (= true ((get placeholders :event.target/checked) ctx)))
    (is (= "Enter" ((get placeholders :event/key) ctx)))
    (is (= true ((get placeholders :event/metaKey) ctx)))
    (is (= false ((get placeholders :event/ctrlKey) ctx)))
    (is (= 24 ((get placeholders :event.target/scrollTop) ctx)))
    (is (= 1001 ((get placeholders :event/timeStamp) ctx)))
    (is (= 480 ((get placeholders :event/clientX) ctx)))
    (testing "missing dom-event returns nil for scalar placeholders"
      (doseq [placeholder-id scalar-placeholder-ids]
        (is (nil? ((get placeholders placeholder-id) {}))
            (str placeholder-id " should return nil without a DOM event"))))))

(deftest register-placeholders-registers-current-target-bounds-test
  (let [placeholders (register-placeholders-under-test!)
        bounds-placeholder (get placeholders :event.currentTarget/bounds)
        original-inner-width (gobj/get js/globalThis "innerWidth")
        original-inner-height (gobj/get js/globalThis "innerHeight")]
    (is (nil? (bounds-placeholder {})))
    (is (nil? (bounds-placeholder {:replicant/dom-event #js {:currentTarget #js {:getBoundingClientRect "nope"}}})))
    (try
      (gobj/set js/globalThis "innerWidth" 1280)
      (gobj/set js/globalThis "innerHeight" 720)
      (is (= {:left 10
              :right 110
              :top 20
              :bottom 220
              :width 100
              :height 200
              :viewport-width 1280
              :viewport-height 720}
             (bounds-placeholder
              {:replicant/dom-event
               #js {:currentTarget
                    #js {:getBoundingClientRect
                         (fn []
                           #js {:left 10
                                :right 110
                                :top 20
                                :bottom 220
                                :width 100
                                :height 200})}}})))
      (finally
        (gobj/set js/globalThis "innerWidth" original-inner-width)
        (gobj/set js/globalThis "innerHeight" original-inner-height)))))

(deftest register-placeholders-registers-current-target-data-role-test
  (let [placeholders (register-placeholders-under-test!)
        data-role-placeholder (get placeholders :event.currentTarget/data-role)]
    (is (nil? (data-role-placeholder {})))
    (is (= "funding-action-deposit"
           (data-role-placeholder
            {:replicant/dom-event
             #js {:currentTarget
                  #js {:getAttribute (fn [attr-name]
                                       (when (= attr-name "data-role")
                                         "funding-action-deposit"))}}})))
    (is (nil? (data-role-placeholder
               {:replicant/dom-event
                #js {:currentTarget
                     #js {:getAttribute (fn [_attr-name] nil)}}})))))

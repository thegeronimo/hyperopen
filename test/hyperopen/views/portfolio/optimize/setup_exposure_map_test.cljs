(ns hyperopen.views.portfolio.optimize.setup-exposure-map-test
  "The net-band control's two ceilings. exposure-policy/net-band-pct-slider-max
  (50%) stops the SLIDER; exposure-policy/max-net-band-pct (100%) is what the
  numeric field and the infeasible panel's Widen-net-band remediation may
  actually store. A stored band between the two is therefore normal, and the
  control has to render it without lying: the range element's :max comes from
  the model (never a literal), its :value is the PINNED number so the rendered
  hiccup matches what the browser will show, and the numeric field, the value
  label, aria-valuetext and an explicit pinned note all carry the TRUE percent.

  Why :value is pinned rather than the true percent: a range input sanitizes any
  value past its own :max, so the DOM lands on 50 no matter what we ask for
  (verified in Chromium: setting .value = \"80\" on max=50 reads back \"50\" and
  fires NO input event). Rendering 80 into the vdom would leave Replicant's tree
  disagreeing with the DOM about a control the user can drag."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.view-model.exposure :as vm]
            [hyperopen.portfolio.optimizer.domain.exposure-policy :as policy]
            [hyperopen.views.portfolio.optimize.setup-exposure-map :as exposure-map]
            [hyperopen.views.portfolio.optimize.test-support
             :refer [collect-strings input-actions node-attr node-by-role]]))

(def ^:private slider-role "portfolio-optimizer-exposure-net-band")
(def ^:private value-role (str slider-role "-value"))
(def ^:private input-role (str slider-role "-input"))
(def ^:private pinned-role (str slider-role "-pinned"))

(defn- bands-for
  "Real model → real view: the band block as the Fine-tune drawer renders it."
  [net-band-pct]
  (exposure-map/bands-block
   (vm/exposure-map-model
    {:objective-kind :minimum-variance
     :constraints {:gross-max 2.0
                   :net-min 0.0
                   :net-max 0.0
                   :net-band-pct net-band-pct
                   :max-asset-weight 0.5}})))

(defn- slider [node] (node-by-role node slider-role))
(defn- label-text [node] (first (collect-strings (node-by-role node value-role))))

(deftest net-band-slider-max-comes-from-the-policy-not-a-literal-test
  (doseq [pct [0.0 0.05 0.5 0.8 1.0]]
    (is (= (* 100 policy/net-band-pct-slider-max)
           (node-attr (slider (bands-for pct)) :max))
        (str "the slider ceiling tracks net-band-pct-slider-max at " pct))))

(deftest net-band-within-the-slider-range-is-not-pinned-test
  (let [node (bands-for 0.05)]
    (is (= "5" (node-attr (slider node) :value)))
    (is (= "false" (node-attr (slider node) :data-pinned)))
    (is (= "5.0% of gross" (node-attr (slider node) :aria-valuetext)))
    (is (= "5" (node-attr (node-by-role node input-role) :value)))
    (is (= "± 5.0% of gross" (label-text node)))
    (is (nil? (node-by-role node pinned-role))
        "no pinned note while the slider can actually represent the band"))
  (testing "a band exactly AT the slider ceiling is representable, so not pinned"
    (let [node (bands-for policy/net-band-pct-slider-max)]
      (is (= "50" (node-attr (slider node) :value)))
      (is (= "false" (node-attr (slider node) :data-pinned)))
      (is (nil? (node-by-role node pinned-role))))))

(deftest net-band-above-the-slider-ceiling-renders-coherently-test
  ;; 0.8 is inside max-net-band-pct, so the numeric field and the infeasible
  ;; panel's :widen-net-band fix can both write it.
  (let [node (bands-for 0.8)]
    (testing "the slider renders the value the browser will actually show"
      (is (= "50" (node-attr (slider node) :value))
          "vdom == DOM: the browser sanitizes anything past :max to 50 anyway")
      (is (= "true" (node-attr (slider node) :data-pinned))))
    (testing "every honest readout carries the TRUE percent"
      (is (= "80.0% of gross" (node-attr (slider node) :aria-valuetext))
          "screen readers must not announce the pinned 50 the DOM would report")
      (is (= "80" (node-attr (node-by-role node input-role) :value)))
      (is (= "± 80.0% of gross" (label-text node))))
    (testing "the pin is named, with both numbers and what a drag would cost"
      (let [note (node-by-role node pinned-role)
            copy (first (collect-strings note))]
        (is (some? note))
        (doseq [fragment ["80.0%" "50.0%" "moving the pinned" "Type to keep a wider"]]
          (is (str/includes? copy fragment) fragment))))
    (testing "only a real drag rewrites it: the slider dispatches from the EVENT,
             so nothing our render does can write the pinned 50 back to the draft"
      (let [[action axis source] (first (input-actions (slider node)))]
        (is (= :actions/set-portfolio-optimizer-exposure-band action))
        (is (= :net-pct axis))
        (is (= [:event.target/value] source))))))

(deftest net-band-at-the-hard-ceiling-renders-coherently-test
  (let [node (bands-for policy/max-net-band-pct)]
    (is (= "50" (node-attr (slider node) :value)))
    (is (= "true" (node-attr (slider node) :data-pinned)))
    (is (= "100.0% of gross" (node-attr (slider node) :aria-valuetext)))
    (is (= "100" (node-attr (node-by-role node input-role) :value)))
    (is (= "± 100.0% of gross" (label-text node)))
    (is (some? (node-by-role node pinned-role)))))

(deftest net-band-value-label-and-slider-never-disagree-silently-test
  ;; The regression this file exists for: any band the model can hold must
  ;; either be representable on the slider or be explicitly labelled as pinned.
  (doseq [pct [0.0 0.025 0.2 0.5 0.5005 0.75 1.0]]
    (let [node (bands-for pct)
          shown (node-attr (slider node) :value)
          pinned? (some? (node-by-role node pinned-role))]
      (is (= pinned? (not= shown (str (* 100 pct))))
          (str "at " pct " the slider either shows the band or says it is pinned")))))

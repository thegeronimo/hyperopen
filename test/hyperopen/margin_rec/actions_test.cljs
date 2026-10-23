(ns hyperopen.margin-rec.actions-test
  (:require [cljs.spec.alpha :as s]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.margin-rec.actions :as actions]
            [hyperopen.margin-rec.state :as state]
            [hyperopen.schema.contracts.action-args :as action-args]
            [hyperopen.schema.contracts.common :as common]
            [hyperopen.trading-settings :as trading-settings]))

(deftest toggle-panel-arg-spec-accepts-dispatch-payloads
  ;; The runtime validates the RAW dispatch args before placeholders resolve,
  ;; so the anchor slot carries the literal :event.currentTarget/bounds keyword
  ;; (not a map). The chip's dispatch payload must satisfy the spec, else the
  ;; action is rejected and the popover never opens.
  (is (s/valid? ::action-args/margin-rec-panel-args
                ["xyz:TSM|xyz" :event.currentTarget/bounds]))
  (is (s/valid? ::action-args/margin-rec-panel-args ["xyz:TSM|xyz"]))
  (is (s/valid? ::action-args/margin-rec-panel-args
                ["xyz:TSM|xyz" {:left 1 :top 2}])))

(defn- assert-save-effects-valid!
  "Every emitted :effects/save must satisfy the runtime arg contract —
  notably keyword-only path segments (coin strings belong in values). The
  runtime wrapper enforces this in dev; unit tests bypass it, so pin it here."
  [effects]
  (doseq [[effect-id & args] effects
          :when (= :effects/save effect-id)]
    (is (s/valid? ::common/save-args (vec args))
        (str "invalid save args: " (pr-str args)))))

(def now 1780000000000)

(def xyz-position
  {:coin "xyz:TSM"
   :szi "0.36"
   :entryPx "446.441"
   :positionValue "157.5"
   :liquidationPx "424.20"
   :marginUsed "12.42"
   :maxLeverage 10
   :leverage {:type "isolated" :value 10}})

(defn base-state
  []
  {:webdata2 {:clearinghouseState {:assetPositions []
                                   :withdrawable "800"}}
   :perp-dex-clearinghouse {"xyz" {:assetPositions [{:position xyz-position}]
                                   :withdrawable "500"}}
   :asset-selector {:market-by-key
                    {"perp:xyz:TSM" {:market-type :perp
                                     :coin "xyz:TSM"
                                     :dex "xyz"
                                     :asset-id 100001
                                     :fundingRate 0.0000125
                                     :maxLeverage 10}}}
   :trading-settings {}
   :margin-rec (state/default-state)
   :candles {}})

(defn- fresh-candles
  [n]
  (mapv (fn [i]
          (let [t (- now (* (- n i) 3600000))]
            {:t t :o 440 :c 440 :l 438 :h 442}))
        (range n)))

(deftest sync-emits-projection-before-heavy-io
  (testing "missing candles: stamp save precedes the candle fetch"
    (let [effects (actions/margin-rec-sync (base-state) now)
          ids (mapv first effects)]
      (assert-save-effects-valid! effects)
      (is (= [:effects/save :effects/fetch-candle-snapshot] ids))
      (testing "request stamps keep coin strings in the value, not the path"
        (let [[_ path value] (first effects)]
          (is (= state/candle-requests-path path))
          (is (= {"xyz:TSM" now} value))))
      (is (= [:effects/fetch-candle-snapshot
              :coin "xyz:TSM"
              :interval state/candle-interval
              :bars state/candle-fetch-bars
              :priority :low]
             (second effects)))))
  (testing "ready candles: computing save precedes the compute effect"
    (let [ready (assoc-in (base-state)
                          [:candles "xyz:TSM" state/candle-interval]
                          (fresh-candles 80))
          effects (actions/margin-rec-sync ready now)
          ids (mapv first effects)]
      (assert-save-effects-valid! effects)
      (is (= [:effects/save :effects/margin-rec-compute] ids))
      (let [[_ path value] (first effects)]
        (is (= state/computing-path path))
        (is (= "xyz:TSM|xyz" (:key value))))
      (let [[_ job] (second effects)]
        (is (= "xyz:TSM|xyz" (:key job)))
        (is (map? (:inputs job))))))
  (testing "nothing to do emits nothing"
    (let [idle (assoc (base-state) :perp-dex-clearinghouse {})]
      (is (= [] (actions/margin-rec-sync idle now))))))

(def anchor {:left 400 :right 460 :top 500 :bottom 520
             :viewport-width 1440 :viewport-height 900})

(deftest toggle-panel-stores-and-clears-anchor
  (testing "opening stores the key and the normalized trigger anchor"
    (is (= [[:effects/save state/panel-path "xyz:TSM|xyz"]
            [:effects/save state/panel-anchor-path anchor]]
           (actions/toggle-margin-rec-panel (base-state) "xyz:TSM|xyz" anchor))))
  (let [open (-> (base-state)
                 (assoc-in state/panel-path "xyz:TSM|xyz")
                 (assoc-in state/panel-anchor-path anchor))]
    (testing "re-triggering the open key closes and clears the anchor"
      (is (= [[:effects/save state/panel-path nil]
              [:effects/save state/panel-anchor-path nil]]
             (actions/toggle-margin-rec-panel open "xyz:TSM|xyz" anchor))))
    (testing "triggering another row re-anchors to it"
      (is (= [[:effects/save state/panel-path "other"]
              [:effects/save state/panel-anchor-path anchor]]
             (actions/toggle-margin-rec-panel open "other" anchor)))))
  (testing "non-map anchors normalize to nil (no geometry to position against)"
    (is (= [[:effects/save state/panel-path "xyz:TSM|xyz"]
            [:effects/save state/panel-anchor-path nil]]
           (actions/toggle-margin-rec-panel (base-state) "xyz:TSM|xyz" nil)))))

(deftest close-and-keydown-dismiss-panel
  (is (= [[:effects/save state/panel-path nil]
          [:effects/save state/panel-anchor-path nil]]
         (actions/close-margin-rec-panel (base-state))))
  (testing "Escape closes; other keys are inert"
    (is (= [[:effects/save state/panel-path nil]
            [:effects/save state/panel-anchor-path nil]]
           (actions/handle-margin-rec-panel-keydown (base-state) "Escape")))
    (is (= [] (actions/handle-margin-rec-panel-keydown (base-state) "Enter")))
    (is (= [] (actions/handle-margin-rec-panel-keydown (base-state) "Tab")))))

(deftest settings-setters-persist-normalized-state
  (let [effects (actions/set-margin-rec-risk-mode (base-state) "conservative")
        [[_ path saved] [_ storage-key stored]] effects]
    (is (= [:trading-settings] path))
    (is (= :conservative (:margin-rec-risk-mode saved)))
    (is (= trading-settings/storage-key storage-key))
    (is (= saved stored)))
  (let [effects (actions/set-margin-rec-auto-topup (base-state) true)
        [[_ _ saved]] effects]
    (is (true? (:margin-rec-auto-topup? saved))))
  (testing "unknown mode falls back to balanced"
    (let [[[_ _ saved]] (actions/set-margin-rec-risk-mode (base-state) "wild")]
      (is (= :balanced (:margin-rec-risk-mode saved))))))

(defn- with-intent
  [state intent]
  (assoc-in state (conj state/intents-path (:position-key intent)) intent))

(defn- pending-intent
  [& [overrides]]
  (state/make-intent (merge {:position-key "xyz:TSM|xyz"
                             :coin "xyz:TSM"
                             :dex "xyz"
                             :expected-size 0.36
                             :target-equity 18.64
                             :source :trade}
                            overrides)
                     now))

(deftest process-intents-submits-matched-topup
  (let [state (with-intent (base-state) (pending-intent))
        effects (actions/margin-rec-process-intents state (+ now 5000))
        [save submit] effects]
    (assert-save-effects-valid! effects)
    (is (= 2 (count effects)))
    (is (= :effects/save (first save)))
    (let [saved-intent (get (nth save 2) "xyz:TSM|xyz")]
      (is (= :submitted (:status saved-intent)))
      (is (< (js/Math.abs (- 6.22 (:submitted-amount saved-intent))) 1e-9)))
    (is (= :effects/api-submit-position-margin (first submit)))
    (is (= {:action {:type "updateIsolatedMargin"
                     :asset 100001
                     :isBuy true
                     :ntli 6220000}
            :position {:coin "xyz:TSM" :dex "xyz" :side :long}}
           (second submit)))))

(deftest process-intents-guards
  (testing "expired intents resolve without submitting"
    (let [state (with-intent (base-state) (pending-intent))
          effects (actions/margin-rec-process-intents
                   state
                   (+ now state/intent-ttl-ms 1))
          [save & rest-effects] effects]
      (is (= 1 (count effects)))
      (is (empty? rest-effects))
      (is (= :expired (:status (get (nth save 2) "xyz:TSM|xyz"))))))
  (testing "size mismatch waits (no effects)"
    (let [state (with-intent (base-state)
                             (pending-intent {:expected-size 5}))]
      (is (= [] (actions/margin-rec-process-intents state (+ now 5000))))))
  (testing "no pending intents is a no-op"
    (is (= [] (actions/margin-rec-process-intents (base-state) now))))
  (testing "already-funded positions skip"
    (let [state (with-intent (base-state)
                             (pending-intent {:target-equity 12.5}))
          effects (actions/margin-rec-process-intents state (+ now 5000))
          [save] effects]
      (is (= 1 (count effects)))
      (is (= :skipped (:status (get (nth save 2) "xyz:TSM|xyz"))))
      (is (= :already-funded (:skip-reason (get (nth save 2) "xyz:TSM|xyz"))))))
  (testing "draft intents (no clock at creation) are stamped on first pass"
    (let [draft (state/make-intent-draft {:position-key "xyz:TSM|xyz"
                                          :coin "xyz:TSM"
                                          :dex "xyz"
                                          :expected-size 0.36
                                          :target-equity 18.64
                                          :source :trade})
          state* (with-intent (base-state) draft)
          effects (actions/margin-rec-process-intents state* now)
          [save submit] effects]
      (is (= 2 (count effects)))
      (is (= (+ now state/intent-ttl-ms)
             (:expires-at (get (nth save 2) "xyz:TSM|xyz"))))
      (is (= :effects/api-submit-position-margin (first submit)))))
  (testing "waits for a recommendation when no explicit target"
    (let [state (with-intent (base-state)
                             (pending-intent {:target-equity nil}))]
      (is (= [] (actions/margin-rec-process-intents state (+ now 5000))))
      (let [with-rec (assoc-in state
                               (conj state/recs-path "xyz:TSM|xyz")
                               {:status :ok
                                :result {:recommended {:equity 20.42}}})
            effects (actions/margin-rec-process-intents with-rec (+ now 5000))]
        (is (= 2 (count effects)))
        (is (= 8000000 (get-in (second (second effects))
                               [:action :ntli])))))))

(def high-risk-result
  {:status :ok
   :risk-level :high
   :p-now 0.14
   :p-after 0.02
   :recommended {:equity 18.64 :additional 6.22 :new-liquidation-px 400.0}})

(defn- with-high-risk-rec
  [state]
  (assoc-in state (conj state/recs-path "xyz:TSM|xyz")
            {:status :ok :result high-risk-result}))

(defn- with-agent-status
  [state status]
  (assoc-in state [:wallet :agent :status] status))

(defn- ready-batch-state
  []
  (-> (base-state) with-high-risk-rec (with-agent-status :ready)))

(deftest batch-toggle-arg-spec-accepts-dispatch-payloads
  ;; Raw dispatch args are validated before placeholders resolve, so the
  ;; toolbar trigger's payload carries the literal bounds keyword.
  (is (s/valid? ::action-args/margin-rec-batch-toggle-args
                [:event.currentTarget/bounds]))
  (is (s/valid? ::action-args/margin-rec-batch-toggle-args []))
  (is (s/valid? ::action-args/margin-rec-batch-toggle-args [{:left 1 :top 2}])))

(deftest batch-panel-toggle-and-dismiss
  (testing "opening stores the normalized anchor and a fresh selection"
    (is (= [[:effects/save state/batch-path
             {:open? true :anchor anchor :deselected #{}}]]
           (actions/toggle-margin-rec-batch-panel (base-state) anchor))))
  (testing "toggling while open closes"
    (let [open (assoc-in (base-state) (conj state/batch-path :open?) true)]
      (is (= [[:effects/save state/batch-path
               {:open? false :anchor nil :deselected #{}}]]
             (actions/toggle-margin-rec-batch-panel open anchor)))))
  (testing "Escape closes; other keys are inert"
    (is (= [[:effects/save state/batch-path
             {:open? false :anchor nil :deselected #{}}]]
           (actions/handle-margin-rec-batch-keydown (base-state) "Escape")))
    (is (= [] (actions/handle-margin-rec-batch-keydown (base-state) "Enter")))))

(deftest batch-selection-toggles-per-position
  (let [[[_ _ batch]] (actions/toggle-margin-rec-batch-selection
                       (base-state) "xyz:TSM|xyz")]
    (is (= #{"xyz:TSM|xyz"} (:deselected batch)))
    (let [reselected (assoc-in (base-state) state/batch-path batch)
          [[_ _ batch*]] (actions/toggle-margin-rec-batch-selection
                          reselected "xyz:TSM|xyz")]
      (is (= #{} (:deselected batch*))))))

(deftest apply-batch-closes-panel-then-submits-per-position
  (let [effects (actions/apply-margin-rec-batch (ready-batch-state))]
    (assert-save-effects-valid! effects)
    (is (= [:effects/save :effects/api-submit-position-margin]
           (mapv first effects)))
    (testing "projection closes the panel before the heavy submit"
      (is (= [:effects/save state/batch-path
              {:open? false :anchor nil :deselected #{}}]
             (first effects))))
    (is (= {:action {:type "updateIsolatedMargin"
                     :asset 100001
                     :isBuy true
                     :ntli 6220000}
            :position {:coin "xyz:TSM" :dex "xyz" :side :long}}
           (second (second effects))))))

(deftest apply-batch-guards
  (testing "deselected positions are not submitted"
    (let [state (-> (ready-batch-state)
                    (assoc-in state/batch-path
                              {:open? true :anchor nil
                               :deselected #{"xyz:TSM|xyz"}}))
          effects (actions/apply-margin-rec-batch state)]
      (is (= [:effects/save] (mapv first effects)))))
  (testing "top-up is capped at the dex's available collateral"
    (let [state (-> (ready-batch-state)
                    (assoc-in [:perp-dex-clearinghouse "xyz" :withdrawable] "5"))
          [_ submit] (actions/apply-margin-rec-batch state)]
      (is (= 5000000 (get-in (second submit) [:action :ntli])))))
  (testing "sub-dollar affordable amounts are skipped entirely"
    (let [state (-> (ready-batch-state)
                    (assoc-in [:perp-dex-clearinghouse "xyz" :withdrawable] "0.4"))
          effects (actions/apply-margin-rec-batch state)]
      (is (= [:effects/save] (mapv first effects)))))
  (testing "no candidates -> panel just closes"
    (is (= [:effects/save]
           (mapv first (actions/apply-margin-rec-batch
                        (with-agent-status (base-state) :ready)))))))

(deftest apply-batch-locked-prompts-unlock-and-replays
  ;; Locked trading must surface the passkey unlock and replay the batch on
  ;; success — never dead-end each position on a "Unlock trading…" error toast.
  (let [state (-> (base-state) with-high-risk-rec (with-agent-status :locked))
        effects (actions/apply-margin-rec-batch state)]
    (is (= [:effects/save-many :effects/unlock-agent-trading]
           (mapv first effects)))
    (testing "projection flips status to :unlocking and clears the error"
      (is (= [:effects/save-many [[[:wallet :agent :status] :unlocking]
                                  [[:wallet :agent :error] nil]]]
             (first effects))))
    (testing "unlock replays this action on success (not a per-position submit)"
      (is (= [:effects/unlock-agent-trading
              {:after-success-actions [[:actions/apply-margin-rec-batch]]}]
             (second effects))))
    (testing "the panel is left open so the replay re-plans from intact selections"
      (is (not-any? (fn [[_ path]] (= state/batch-path path))
                    (filter #(= :effects/save (first %)) effects))))
    (testing "the replay payload satisfies the unlock arg contract"
      (is (s/valid? ::common/unlock-agent-trading-args
                    [(second (second effects))])))))

(deftest apply-batch-unlocking-holds-without-submitting
  ;; A passkey prompt is already in flight; a second click must not re-prompt.
  (let [state (-> (base-state) with-high-risk-rec (with-agent-status :unlocking))]
    (is (= [] (actions/apply-margin-rec-batch state)))))

(deftest apply-batch-not-enabled-opens-recovery
  ;; Agent trading never enabled: open the enable-trading recovery modal (the
  ;; same prompt manual order entry shows), not N rejected submits.
  (let [state (with-high-risk-rec (base-state))]
    (is (= [[:effects/save [:wallet :agent :recovery-modal-open?] true]]
           (actions/apply-margin-rec-batch state))))
  (testing "nothing fundable short-circuits to a panel close even when locked"
    (let [state (with-agent-status (base-state) :locked)]
      (is (= [:effects/save]
             (mapv first (actions/apply-margin-rec-batch state)))))))

(deftest batch-plan-reports-skips
  (let [state (-> (base-state)
                  with-high-risk-rec
                  (assoc-in [:perp-dex-clearinghouse "xyz" :withdrawable] "0.4"))
        {:keys [submits skipped]} (actions/margin-rec-batch-plan state)]
    (is (empty? submits))
    (is (= [:insufficient-available] (mapv :skip-reason skipped)))))

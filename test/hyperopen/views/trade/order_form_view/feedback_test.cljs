(ns hyperopen.views.trade.order-form-view.feedback-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trade.order-form.test-support :refer [base-state
                                                                   collect-strings
                                                                   find-first-node]]
            [hyperopen.views.trade.order-form-feedback :as feedback]
            [hyperopen.views.trade.order-form-view :as view]
            [hyperopen.views.trade.order-form-vm :as order-form-vm]))

(deftest twap-schedule-states-the-venue-schedule-once-a-size-is-known-test
  (let [state (base-state {:type :twap
                           :size "6"
                           :twap {:minutes "90"}})
        schedule (feedback/twap-schedule state (get state :order-form) "BTC")]
    ;; 6 BTC at the fixture's best ask of 101 is $606 of notional. The venue will not let a
    ;; clip fall below $10, so it works this as 60 clips rather than the 181 a flat 30s
    ;; cadence would imply, spacing them ~92s apart.
    (is (true? (:known? schedule)))
    (is (= "Buys" (:verb schedule)))
    (is (= 60 (:order-count schedule)))
    (is (= "2 minutes" (:interval-phrase schedule)))
    (is (= "90 minutes" (:runtime-phrase schedule)))
    (is (re-find #" BTC$" (:slice-size schedule)))))

(deftest twap-schedule-uses-the-thirty-second-floor-for-large-orders-test
  (let [state (base-state {:type :twap
                           :size "600"
                           :twap {:minutes "90"}})
        schedule (feedback/twap-schedule state (get state :order-form) "BTC")]
    ;; $60,600 over 90 minutes clears the $10 clip floor at every 30s slot, so the spacing
    ;; floor binds and the count is the classic 1 + 2*minutes.
    (is (= 181 (:order-count schedule)))
    (is (= "30 seconds" (:interval-phrase schedule)))))

(deftest twap-schedule-is-unknown-until-a-size-is-entered-test
  (let [state (base-state {:type :twap
                           :size ""
                           :twap {:minutes "1440"}})
        schedule (feedback/twap-schedule state (get state :order-form) "BTC")]
    ;; No size means no notional, and without a notional the venue's spacing cannot be
    ;; derived at all. The section says so in words rather than showing a bound.
    (is (false? (:known? schedule)))
    (is (nil? (:order-count schedule)))
    (is (nil? (:recap schedule)))
    (is (= "24 hours" (:runtime-phrase schedule)))))

(deftest twap-schedule-reads-durations-the-way-a-person-says-them-test
  (let [phrase (fn [twap]
                 (:runtime-phrase (feedback/twap-schedule
                                   (base-state {:type :twap :size "6" :twap twap})
                                   (get (base-state {:type :twap :size "6" :twap twap})
                                        :order-form)
                                   "BTC")))]
    (is (= "30 minutes" (phrase {:minutes "30"})))
    (is (= "1 minute" (phrase {:minutes "1"})))
    (is (= "4 hours" (phrase {:hours "4"})))
    ;; A one-day run reads as twenty-four hours, which is how traders say it.
    (is (= "24 hours" (phrase {:days "1"})))
    (is (= "7 days" (phrase {:days "7"})))))

(deftest twap-schedule-tracks-the-active-runtime-preset-test
  (let [schedule-for (fn [twap]
                       (let [state (base-state {:type :twap :size "6" :twap twap})]
                         (feedback/twap-schedule state (get state :order-form) "BTC")))]
    (is (= :30m (:preset-key (schedule-for {:minutes "30"}))))
    (is (false? (:custom? (schedule-for {:minutes "30"}))))
    (is (= :1d (:preset-key (schedule-for {:days "1"}))))
    ;; A runtime no preset covers falls to Custom, so a restored draft opens its fields.
    (is (true? (:custom? (schedule-for {:minutes "45"}))))
    ;; And Custom stays pinned even when the value happens to match a preset.
    (is (true? (:custom? (schedule-for {:minutes "30" :custom-runtime? true}))))))

(deftest twap-guards-summarise-themselves-when-collapsed-test
  (let [guards-for (fn [twap]
                     (let [state (base-state {:type :twap :size "6" :twap twap})]
                       (feedback/twap-guards state (get state :order-form))))]
    (is (= "none set" (:summary (guards-for {}))))
    (is (false? (:any-set? (guards-for {}))))
    (is (= "120 \u2192 140" (:summary (guards-for {:trigger-px "120" :stop-px "140"}))))
    (is (= "starts 120" (:summary (guards-for {:trigger-px "120"}))))
    (is (= "stops 140" (:summary (guards-for {:stop-px "140"}))))
    (is (true? (:any-set? (guards-for {:stop-px "140"}))))))

(deftest twap-guards-label-flips-with-the-side-test
  (let [buy (base-state {:type :twap :side :buy :size "6"})
        sell (base-state {:type :twap :side :sell :size "6"})]
    (is (= "Max Price" (:stop-price-label (feedback/twap-guards buy (:order-form buy)))))
    (is (= "Min Price"
           (:stop-price-label (feedback/twap-guards sell (:order-form sell)))))))

(deftest twap-guard-band-places-levels-around-the-mark-test
  (let [state (base-state {:type :twap :size "6"
                           :twap {:trigger-px "80" :stop-px "120"}})
        band (:band (feedback/twap-guards state (get state :order-form)))]
    ;; The band reserves 11% at each end so an extreme level still reads as a marker.
    (is (= 11 (:trigger-pct band)))
    (is (= 89 (:stop-pct band)))
    ;; The fixture's best ask of 101 sits between them.
    (is (< 11 (:mark-pct band) 89))
    (is (= "STARTS 80" (:trigger-label band)))
    (is (= "STOPS 120" (:stop-label band)))))

(deftest twap-guard-band-is-absent-until-there-is-something-to-plot-test
  (let [state (base-state {:type :twap :size "6"})]
    (is (nil? (:band (feedback/twap-guards state (get state :order-form)))))))

(deftest twap-submit-copy-names-the-run-and-recaps-its-guards-test
  (let [state (base-state {:type :twap :size "6"
                           :twap {:days "1" :trigger-px "80" :stop-px "120"}})
        form (get state :order-form)
        copy (feedback/twap-submit-copy
              {:order-type :twap
               :schedule (feedback/twap-schedule state form "BTC")
               :guards (feedback/twap-guards state form)})]
    (is (= "Start TWAP - buy over 24 hours" (:label copy)))
    (is (= "Starts at 80 \u00b7 stops at 120" (:recap copy))))

  (let [sell (base-state {:type :twap :side :sell :size "6" :twap {:hours "4"}})
        form (get sell :order-form)]
    (is (= "Start TWAP - sell over 4 hours"
           (:label (feedback/twap-submit-copy
                    {:order-type :twap
                     :schedule (feedback/twap-schedule sell form "BTC")
                     :guards (feedback/twap-guards sell form)})))))

  ;; Every other order type keeps the voice catalog's label.
  (is (nil? (feedback/twap-submit-copy {:order-type :limit :schedule {} :guards {}})))
  (is (nil? (feedback/twap-submit-copy {:order-type :scale :schedule {} :guards {}}))))

(deftest spectate-stop-affordance-renders-stop-action-test
  (let [affordance (feedback/spectate-mode-stop-affordance)
        stop-button (find-first-node affordance
                                     (fn [node]
                                       (= "order-form-spectate-mode-stop-button"
                                          (get-in node [1 :data-role]))))
        strings (set (collect-strings affordance))]
    (is (contains? strings "Stop Spectate Mode"))
    (is (= [[:actions/stop-spectate-mode]]
           (get-in stop-button [1 :on :click])))))

(deftest tpsl-panel-model-reflects-open-unit-dropdown-state-test
  (let [state (base-state {:type :limit
                           :size "1"
                           :price "100"}
                          {:tpsl-panel-open? true
                           :tpsl-unit-dropdown-open? true})
        vm (order-form-vm/order-form-vm state)
        panel (feedback/tpsl-panel-model state
                                         (get state :order-form)
                                         (get-in state [:order-form :side])
                                         (:ui-leverage vm)
                                         (:controls vm))]
    (is (= true (:unit-dropdown-open? panel)))
    (is (= :usd (:unit panel)))
    (is (contains? panel :tp-offset))
    (is (contains? panel :sl-offset))))

(deftest order-form-skips-hidden-tpsl-panel-model-test
  (let [calls (atom 0)
        original feedback/tpsl-panel-model]
    (with-redefs [feedback/tpsl-panel-model (fn [& args]
                                              (swap! calls inc)
                                              (apply original args))]
      (view/order-form-view (base-state {:type :limit :size "1" :price "100"}))
      (is (zero? @calls))
      (view/order-form-view (base-state {:type :limit :size "1" :price "100"}
                                        {:tpsl-panel-open? true}))
      (is (= 1 @calls)))))

(deftest order-form-skips-twap-schedule-unless-twap-mode-is-active-test
  (let [calls (atom 0)
        original feedback/twap-schedule]
    (with-redefs [feedback/twap-schedule (fn [& args]
                                          (swap! calls inc)
                                          (apply original args))]
      (view/order-form-view (base-state {:type :limit}))
      (is (zero? @calls))
      (view/order-form-view (base-state {:type :twap
                                         :size "6"
                                         :twap {:minutes "90"}}))
      (is (= 1 @calls)))))

(deftest order-form-builds-twap-schedule-for-registry-driven-twap-sections-test
  (let [calls (atom 0)
        original feedback/twap-schedule]
    (with-redefs [order-form-vm/order-type-sections (fn [_order-type] [:twap])
                  feedback/twap-schedule (fn [& args]
                                          (swap! calls inc)
                                          (apply original args))]
      (view/order-form-view (base-state {:type :stop-limit
                                         :size "6"
                                         :twap {:minutes "90"}}))
      (is (= 1 @calls)))))

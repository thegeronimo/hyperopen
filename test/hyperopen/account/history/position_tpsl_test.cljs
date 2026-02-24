(ns hyperopen.account.history.position-tpsl-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.account.history.position-tpsl :as position-tpsl]
            [hyperopen.views.account-info.test-support.fixtures :as fixtures]))

(deftest from-position-row-derives-modal-context-test
  (let [long-modal (position-tpsl/from-position-row
                    (fixtures/sample-position-row "xyz:NVDA" 10 "0.500"))
        short-modal (position-tpsl/from-position-row
                     (fixtures/sample-position-row "xyz:NVDA" 10 "-0.750"))]
    (is (true? (:open? long-modal)))
    (is (= :long (:position-side long-modal)))
    (is (= 0.5 (:position-size long-modal)))
    (is (= "0.5" (:size-input long-modal)))
    (is (= 10 (:entry-price long-modal)))
    (is (= 10 (:mark-price long-modal)))
    (is (= 12 (:margin-used long-modal)))
    (is (= 10 (:leverage long-modal)))
    (is (= :usd (position-tpsl/tp-gain-mode long-modal)))
    (is (= :usd (position-tpsl/sl-loss-mode long-modal)))
    (is (= :short (:position-side short-modal)))
    (is (= 0.75 (:position-size short-modal)))
    (is (= "0.75" (:size-input short-modal)))))

(deftest validate-modal-covers-core-position-constraints-test
  (let [modal (position-tpsl/from-position-row
               (fixtures/sample-position-row "xyz:NVDA" 10 "0.500"))]
    (is (= {:is-ok false :display-message "Place Order"}
           (position-tpsl/validate-modal modal)))
    (is (= {:is-ok false :display-message "Take Profit Price Too Low"}
           (position-tpsl/validate-modal (assoc modal :tp-price "9"))))
    (is (= {:is-ok true :display-message "Place TP Order"}
           (position-tpsl/validate-modal (assoc modal :tp-price "11"))))
    (is (= {:is-ok false :display-message "Stop Loss Price Too High"}
           (position-tpsl/validate-modal (assoc modal :sl-price "12"))))
    (is (= {:is-ok false :display-message "Reduce Too Large"}
           (position-tpsl/validate-modal (-> modal
                                             (assoc :tp-price "11"
                                                    :configure-amount? true
                                                    :size-input "0.6")))))))

(deftest validate-modal-limit-price-branches-test
  (let [modal (-> (position-tpsl/from-position-row
                   (fixtures/sample-position-row "xyz:NVDA" 10 "0.500"))
                  (assoc :tp-price "11"
                         :sl-price "9"
                         :limit-price? true))]
    (testing "limit prices are required when limit mode is on"
      (is (= {:is-ok false :display-message "Limit Price Must Be Set"}
             (position-tpsl/validate-modal modal))))
    (testing "long-side TP/SL limit constraints"
      (is (= {:is-ok false :display-message "Take Profit Limit Price Too High"}
             (position-tpsl/validate-modal (assoc modal :tp-limit "11.1" :sl-limit "9.1"))))
      (is (= {:is-ok false :display-message "Stop Loss Limit Price Too Low"}
             (position-tpsl/validate-modal (assoc modal :tp-limit "10.9" :sl-limit "8.9"))))
      (is (= {:is-ok true :display-message "Place TP/SL Orders"}
             (position-tpsl/validate-modal (assoc modal :tp-limit "10.9" :sl-limit "9.1")))))))

(deftest prepare-submit-builds-request-and-fails-closed-for-missing-market-test
  (let [modal (-> (position-tpsl/from-position-row
                   (fixtures/sample-position-row "xyz:NVDA" 10 "0.500"))
                  (assoc :tp-price "11"))
        state {:asset-selector {:market-by-key {"perp:xyz:NVDA"
                                                {:coin "xyz:NVDA"
                                                 :market-type :perp
                                                 :asset-id 123}}}}
        success (position-tpsl/prepare-submit state modal)
        missing-market (position-tpsl/prepare-submit {:asset-selector {:market-by-key {}}} modal)]
    (is (true? (:ok? success)))
    (is (= "order"
           (get-in success [:request :action :type])))
    (is (= "normalTpsl"
           (get-in success [:request :action :grouping])))
    (is (= "tp"
           (get-in success [:request :action :orders 0 :t :trigger :tpsl])))
    (is (= {:ok? false
            :display-message "Select an asset and ensure market data is loaded."}
           missing-market))))

(deftest gain-and-loss-estimates-use-active-size-test
  (let [modal (-> (position-tpsl/from-position-row
                   (fixtures/sample-position-row "xyz:NVDA" 10 "0.500"))
                  (assoc :configure-amount? true
                         :size-input "0.25"
                         :tp-price "12"
                         :sl-price "9"))]
    (is (= 0.5 (position-tpsl/estimated-gain-usd modal)))
    (is (= 0.25 (position-tpsl/estimated-loss-usd modal)))))

(deftest gain-and-loss-percent-estimates-use-margin-basis-test
  (let [base-modal (-> (position-tpsl/from-position-row
                        (fixtures/sample-position-row "xyz:NVDA" 10 "0.500"))
                       (assoc :tp-price "12"
                              :sl-price "9"))
        configured-size-modal (assoc base-modal :configure-amount? true :size-input "0.25")
        gain-pct (position-tpsl/estimated-gain-percent base-modal)
        configured-gain-pct (position-tpsl/estimated-gain-percent configured-size-modal)
        loss-pct (position-tpsl/estimated-loss-percent base-modal)]
    (is (< (js/Math.abs (- gain-pct 8.333333333333334)) 0.000001))
    (is (< (js/Math.abs (- configured-gain-pct gain-pct)) 0.000001))
    (is (< (js/Math.abs (- loss-pct 4.166666666666667)) 0.000001))))

(deftest set-modal-field-accepts-gain-and-loss-inputs-test
  (let [long-modal (position-tpsl/from-position-row
                    (fixtures/sample-position-row "xyz:NVDA" 10 "0.500"))
        short-modal (position-tpsl/from-position-row
                     (fixtures/sample-position-row "xyz:NVDA" 10 "-0.500"))
        tp-from-gain (position-tpsl/set-modal-field long-modal [:tp-gain] "1")
        sl-from-loss (position-tpsl/set-modal-field long-modal [:sl-loss] "1")
        short-tp-from-gain (position-tpsl/set-modal-field short-modal [:tp-gain] "1")
        short-sl-from-loss (position-tpsl/set-modal-field short-modal [:sl-loss] "1")
        configured-size-modal (assoc long-modal :configure-amount? true :size-input "0.25")
        configured-tp-from-gain (position-tpsl/set-modal-field configured-size-modal [:tp-gain] "1")
        cleared-tp (position-tpsl/set-modal-field (assoc long-modal :tp-price "12") [:tp-gain] "")
        invalid-sl (position-tpsl/set-modal-field (assoc long-modal :sl-price "8") [:sl-loss] "invalid")
        cleared-error (position-tpsl/set-modal-field (assoc long-modal :error "old") [:tp-gain] "1")]
    (is (= "12" (:tp-price tp-from-gain)))
    (is (= "8" (:sl-price sl-from-loss)))
    (is (= "8" (:tp-price short-tp-from-gain)))
    (is (= "12" (:sl-price short-sl-from-loss)))
    (is (= "14" (:tp-price configured-tp-from-gain)))
    (is (= "" (:tp-price cleared-tp)))
    (is (= "" (:sl-price invalid-sl)))
    (is (nil? (:error cleared-error)))))

(deftest set-modal-field-supports-percent-gain-and-loss-modes-test
  (let [long-modal (position-tpsl/from-position-row
                    (fixtures/sample-position-row "xyz:NVDA" 10 "0.500"))
        short-modal (position-tpsl/from-position-row
                     (fixtures/sample-position-row "xyz:NVDA" 10 "-0.500"))
        long-gain-percent-mode (position-tpsl/set-modal-field long-modal [:tp-gain-mode] :toggle)
        long-gain-percent->tp (position-tpsl/set-modal-field long-gain-percent-mode [:tp-gain] "10")
        short-gain-percent-mode (position-tpsl/set-modal-field short-modal [:tp-gain-mode] :toggle)
        short-gain-percent->tp (position-tpsl/set-modal-field short-gain-percent-mode [:tp-gain] "10")
        long-loss-percent-mode (position-tpsl/set-modal-field long-modal [:sl-loss-mode] :toggle)
        long-loss-percent->sl (position-tpsl/set-modal-field long-loss-percent-mode [:sl-loss] "10")
        gain-mode-reset (position-tpsl/set-modal-field long-gain-percent-mode [:tp-gain-mode] :toggle)]
    (is (= :percent (position-tpsl/tp-gain-mode long-gain-percent-mode)))
    (is (= :percent (position-tpsl/sl-loss-mode long-loss-percent-mode)))
    (is (= "12.4" (:tp-price long-gain-percent->tp)))
    (is (= "7.6" (:tp-price short-gain-percent->tp)))
    (is (= "7.6" (:sl-price long-loss-percent->sl)))
    (is (= :usd (position-tpsl/tp-gain-mode gain-mode-reset)))))

(ns hyperopen.outcomes.application.merge-actions-test
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [hyperopen.order.actions :as order-actions]
            [hyperopen.outcomes.application.merge-actions :as merge-actions]
            [hyperopen.outcomes.application.merge-effects :as merge-effects]))

(def ^:private owner "0x1111111111111111111111111111111111111111")

(deftest merge-payload-follows-the-documented-user-outcome-shape-test
  ;; Hyperliquid exchange endpoint, "Merge outcome":
  ;;   {"type": "userOutcome", "mergeOutcome": {"outcome": Number, amount: String | null}}
  (is (= {:type "userOutcome"
          :mergeOutcome {:outcome 27037 :amount "40"}}
         (merge-actions/merge-outcome-action {:outcome-id 27037 :pairs 40})))
  (testing "pairs may arrive typed, as text"
    (is (= "7" (get-in (merge-actions/merge-outcome-action {:outcome-id "27037" :pairs "7"})
                       [:mergeOutcome :amount]))))
  (testing "the wire key order is type first, then mergeOutcome {outcome, amount}"
    (let [action (merge-actions/merge-outcome-action {:outcome-id 27037 :pairs 40})]
      (is (= [:type :mergeOutcome] (keys action)))
      (is (= [:outcome :amount] (keys (:mergeOutcome action))))))
  (testing "no payload without a positive whole number of pairs and a real outcome"
    (is (nil? (merge-actions/merge-outcome-action {:outcome-id 27037 :pairs 0})))
    (is (nil? (merge-actions/merge-outcome-action {:outcome-id 27037 :pairs 2.5})))
    (is (nil? (merge-actions/merge-outcome-action {:outcome-id 27037 :pairs nil})))
    (is (nil? (merge-actions/merge-outcome-action {:outcome-id nil :pairs 3})))))

(deftest submit-emits-one-heavy-effect-after-projection-test
  (let [state {:wallet {:address owner}}
        effects (merge-actions/submit-outcome-merge state {:outcome-id 27037 :pairs 40})]
    (is (= [:effects/save-many [[[:outcome-ticket :merge :submitting?] true]
                                [[:outcome-ticket :merge :error] nil]]]
           (first effects)))
    (is (= [:effects/api-submit-outcome-merge
            {:kind :merge
             :pairs 40
             :action {:type "userOutcome" :mergeOutcome {:outcome 27037 :amount "40"}}}]
           (second effects)))
    (is (= 2 (count effects)))))

(deftest submit-refuses-without-a-wallet-or-pairs-test
  (is (= [[:effects/save [:outcome-ticket :merge :error] "Connect your wallet first."]]
         (merge-actions/submit-outcome-merge {} {:outcome-id 1 :pairs 2})))
  (is (= [[:effects/save [:outcome-ticket :merge :error] "Enter a number of pairs to merge."]]
         (merge-actions/submit-outcome-merge {:wallet {:address owner}} {:outcome-id 1 :pairs 0}))))

(deftest merge-effect-signs-posts-and-closes-the-ticket-test
  (async done
    (let [store (atom {:wallet {:address owner}
                       :outcome-ticket {:merge {:coins ["#1" "#2"] :pairs "40" :submitting? true}}})
          calls (atom [])
          dispatched (atom [])
          toasts (atom [])
          request {:kind :merge :pairs 40
                   :action {:type "userOutcome" :mergeOutcome {:outcome 27037 :amount "40"}}}]
      (-> (merge-effects/api-submit-outcome-merge!
           {:store store
            :request request
            :dispatch! (fn [_ _ actions] (swap! dispatched conj actions))
            :submit-outcome-merge! (fn [_ address action options]
                                     (swap! calls conj [address action options])
                                     (js/Promise.resolve {:status "ok"}))
            :exchange-response-error (fn [_] "x")
            :runtime-error-message (fn [_] "x")
            :show-toast! (fn [_ kind message] (swap! toasts conj [kind message]))})
          (.then (fn [_]
                   (is (= [[owner (:action request) {}]] @calls) "the documented action, nothing else")
                   (is (nil? (get-in @store [:outcome-ticket :merge])))
                   (is (= [[:success "Merged 40 pairs."]] @toasts))
                   (is (= [[[:actions/load-user-data owner]]] @dispatched))
                   (done)))))))

(deftest merge-effect-keeps-the-ticket-open-on-an-exchange-error-test
  (async done
    (let [store (atom {:wallet {:address owner}
                       :outcome-ticket {:merge {:coins ["#1" "#2"] :pairs "40" :submitting? true}}})]
      (-> (merge-effects/api-submit-outcome-merge!
           {:store store
            :request {:kind :merge :pairs 40 :action {:type "userOutcome"}}
            :dispatch! (fn [& _] nil)
            :submit-outcome-merge! (fn [& _] (js/Promise.resolve {:status "err" :response "Insufficient shares"}))
            :exchange-response-error (fn [resp] (:response resp))
            :runtime-error-message (fn [_] "x")
            :show-toast! (fn [& _] nil)})
          (.then (fn [_]
                   (is (= "Merge failed: Insufficient shares" (get-in @store [:outcome-ticket :merge :error])))
                   (is (false? (get-in @store [:outcome-ticket :merge :submitting?])))
                   (is (= "40" (get-in @store [:outcome-ticket :merge :pairs])))
                   (done)))))))

(deftest merge-ticket-entry-writes-test
  (let [state {:outcome-ticket {:merge {:coins ["#1" "#2"] :pairs "5"}}}]
    (is (= [[:effects/save-many [[[:outcome-ticket :merge] {:coins ["#1" "#2"] :pairs "9"}]]]]
           (order-actions/set-outcome-ticket-entry {} {:merge {:coins ["#1" "#2"] :pairs "9" :extra 1}}))
        "opening the merge ticket is one save and writes nothing else")
    (is (= [[:effects/save-many [[[:outcome-ticket :merge :pairs] "3"]
                                 [[:outcome-ticket :merge :error] nil]]]]
           (order-actions/set-outcome-ticket-entry state {:merge-pairs "3"})))
    (is (= [] (order-actions/set-outcome-ticket-entry {} {:merge-pairs "3"}))
        "typing pairs with no merge ticket open is a no-op")
    (let [[[_ writes]] (order-actions/set-outcome-ticket-entry state {:side :buy})]
      (is (some #(= [[:outcome-ticket :merge] nil] %) writes)
          "Buy or Cash out closes the merge ticket in the same save"))))

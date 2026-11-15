(ns hyperopen.runtime.effect-adapters.portfolio-optimizer-scenarios-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.portfolio.optimizer.fixtures :as fixtures]
            [hyperopen.runtime.effect-adapters.portfolio-optimizer :as portfolio-optimizer-adapters]
            [hyperopen.test-support.async :as async-support]))

(deftest save-portfolio-optimizer-scenario-effect-persists-record-index-and-store-state-test
  (async done
    (let [calls (atom [])
          address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          solved-run (fixtures/sample-last-successful-run
                      {:request-signature {:scenario-id "scn_3000"
                                           :input-signature "sig-current"}
                       :result {:expected-return 0.18
                                :volatility 0.42}
                       :computed-at-ms 2000})
          store (atom {:wallet {:address address}
                       :portfolio {:optimizer
                                   {:draft {:name "Core Hedge"
                                            :objective {:kind :max-sharpe}
                                            :return-model {:kind :historical-mean}
                                            :risk-model {:kind :diagonal-shrink}
                                            :metadata {:dirty? false}}
                                    :run-state {:status :succeeded
                                                :request-signature
                                                {:scenario-id "scn_3000"
                                                 :input-signature "sig-current"}}
                                    :scenario-index {:ordered-ids []
                                                     :by-id {}}
                                    :last-successful-run solved-run}}})]
      (with-redefs [portfolio-optimizer-adapters/*now-ms* (fn [] 3000)
                    portfolio-optimizer-adapters/*next-scenario-id* (fn [_now-ms] "scn_3000")
                    portfolio-optimizer-adapters/*load-scenario-index!* (fn [addr]
                                                                          (swap! calls conj [:load-index addr])
                                                                          (js/Promise.resolve nil))
                    portfolio-optimizer-adapters/*save-scenario!* (fn [scenario-id record]
                                                                     (swap! calls conj [:save-scenario scenario-id record])
                                                                     (js/Promise.resolve true))
                    portfolio-optimizer-adapters/*save-scenario-index!* (fn [addr index]
                                                                          (swap! calls conj [:save-index addr index])
                                                                          (js/Promise.resolve true))]
        (-> (portfolio-optimizer-adapters/save-portfolio-optimizer-scenario-effect nil store)
            (.then (fn [record]
                     (is (= "scn_3000" (:id record)))
                     (is (= :saved (:status record)))
                     (is (= solved-run (:saved-run record)))
                     (is (= false (get-in @store [:portfolio :optimizer :draft :metadata :dirty?])))
                     (is (= "scn_3000" (get-in @store [:portfolio :optimizer :active-scenario :loaded-id])))
                     (is (= ["scn_3000"]
                            (get-in @store [:portfolio :optimizer :scenario-index :ordered-ids])))
                     (is (= :saved
                            (get-in @store [:portfolio :optimizer :scenario-save-state :status])))
                     (is (= [:load-index address] (first @calls)))
                     (is (= :save-scenario (ffirst (drop 1 @calls))))
                     (is (= :save-index (ffirst (drop 2 @calls))))
                     (done)))
            (.catch (async-support/unexpected-error done)))))))

(deftest save-portfolio-optimizer-scenario-effect-fails-when-record-write-is-not-persisted-test
  (async done
    (let [calls (atom [])
          address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          solved-run (fixtures/sample-last-successful-run
                      {:result {:expected-return 0.18
                                :volatility 0.42}
                       :computed-at-ms 2000})
          store (atom {:wallet {:address address}
                       :portfolio {:optimizer
                                   {:draft {:name "Core Hedge"
                                            :objective {:kind :max-sharpe}
                                            :return-model {:kind :historical-mean}
                                            :risk-model {:kind :diagonal-shrink}
                                            :metadata {:dirty? true}}
                                    :scenario-index {:ordered-ids []
                                                     :by-id {}}
                                    :last-successful-run solved-run}}})]
      (with-redefs [portfolio-optimizer-adapters/*now-ms* (fn [] 3000)
                    portfolio-optimizer-adapters/*next-scenario-id* (fn [_now-ms] "scn_3000")
                    portfolio-optimizer-adapters/*load-scenario-index!*
                    (fn [addr]
                      (swap! calls conj [:load-index addr])
                      (js/Promise.resolve nil))
                    portfolio-optimizer-adapters/*save-scenario!*
                    (fn [scenario-id record]
                      (swap! calls conj [:save-scenario scenario-id record])
                      (js/Promise.resolve false))
                    portfolio-optimizer-adapters/*save-scenario-index!*
                    (fn [addr index]
                      (swap! calls conj [:save-index addr index])
                      (js/Promise.resolve true))]
        (-> (portfolio-optimizer-adapters/save-portfolio-optimizer-scenario-effect nil store)
            (.then (fn [result]
                     (is (nil? result))
                     (is (= :failed
                            (get-in @store
                                    [:portfolio :optimizer :scenario-save-state :status])))
                     (is (= "Failed to persist optimizer scenario."
                            (get-in @store
                                    [:portfolio :optimizer :scenario-save-state :error :message])))
                     (is (some #(= :save-scenario (first %)) @calls))
                     (is (not-any? #(= :save-index (first %)) @calls))
                     (is (= []
                            (get-in @store
                                    [:portfolio :optimizer :scenario-index :ordered-ids])))
                     (done)))
            (.catch (async-support/unexpected-error done)))))))

(deftest save-portfolio-optimizer-scenario-effect-updates-open-scenario-on-workspace-route-test
  (async done
    (let [calls (atom [])
          address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          solved-run (fixtures/sample-last-successful-run
                      {:result {:expected-return 0.12
                                :volatility 0.3}
                       :computed-at-ms 2500})
          store (atom {:router {:path "/portfolio/optimize"}
                       :wallet {:address address}
                       :portfolio {:optimizer
                                   {:active-scenario {:loaded-id "scn_old"
                                                      :status :saved}
                                    :draft {:id "scn_old"
                                            :name "Fresh Draft"
                                            :objective {:kind :minimum-variance}
                                            :return-model {:kind :historical-mean}
                                            :risk-model {:kind :diagonal-shrink}
                                            :metadata {:dirty? true}}
                                    :scenario-index {:ordered-ids ["scn_old"]
                                                     :by-id {"scn_old" {:id "scn_old"
                                                                        :name "Old"
                                                                        :status :saved}}}
                                    :last-successful-run solved-run}}})]
      (with-redefs [portfolio-optimizer-adapters/*now-ms* (fn [] 3500)
                    portfolio-optimizer-adapters/*next-scenario-id*
                    (fn [now-ms]
                      (swap! calls conj [:next-id now-ms])
                      "scn_new")
                    portfolio-optimizer-adapters/*load-scenario-index!*
                    (fn [addr]
                      (swap! calls conj [:load-index addr])
                      (js/Promise.resolve (get-in @store
                                                  [:portfolio :optimizer :scenario-index])))
                    portfolio-optimizer-adapters/*save-scenario!*
                    (fn [scenario-id record]
                      (swap! calls conj [:save-scenario scenario-id record])
                      (js/Promise.resolve true))
                    portfolio-optimizer-adapters/*save-scenario-index!*
                    (fn [addr index]
                      (swap! calls conj [:save-index addr index])
                      (js/Promise.resolve true))
                    portfolio-optimizer-adapters/*dispatch!*
                    (fn [store* ctx effects]
                      (swap! calls conj [:dispatch store* ctx effects]))]
        (-> (portfolio-optimizer-adapters/save-portfolio-optimizer-scenario-effect nil store)
            (.then (fn [record]
                     (is (= "scn_old" (:id record)))
                     (is (nil? (:saved-run record)))
                     (is (not-any? #(= :next-id (first %)) @calls))
                     (is (= "scn_old"
                            (second (first (filter #(= :save-scenario (first %))
                                                   @calls)))))
                     (is (= ["scn_old"]
                            (get-in @store
                                    [:portfolio :optimizer :scenario-index :ordered-ids])))
                     (is (= "scn_old"
                            (get-in @store
                                    [:portfolio :optimizer :active-scenario :loaded-id])))
                     (is (not-any? #(= :dispatch (first %)) @calls))
                     (done)))
            (.catch (async-support/unexpected-error done)))))))

(deftest save-portfolio-optimizer-scenario-effect-creates-new-id-on-draft-route-test
  (async done
    (let [calls (atom [])
          address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          solved-run (fixtures/sample-last-successful-run
                      {:result {:expected-return 0.16
                                :volatility 0.28}
                       :computed-at-ms 2550})
          store (atom {:router {:path "/portfolio/optimize/draft"}
                       :wallet {:address address}
                       :portfolio {:optimizer
                                   {:active-scenario {:loaded-id "draft"
                                                      :status :computed}
                                    :draft {:id "draft"
                                            :name "Draft Scenario"
                                            :objective {:kind :minimum-variance}
                                            :return-model {:kind :historical-mean}
                                            :risk-model {:kind :diagonal-shrink}
                                            :metadata {:dirty? false}}
                                    :scenario-index {:ordered-ids []
                                                     :by-id {}}
                                    :last-successful-run solved-run}}})]
      (with-redefs [portfolio-optimizer-adapters/*now-ms* (fn [] 3600)
                    portfolio-optimizer-adapters/*next-scenario-id*
                    (fn [now-ms]
                      (swap! calls conj [:next-id now-ms])
                      "scn_3600")
                    portfolio-optimizer-adapters/*load-scenario-index!*
                    (fn [addr]
                      (swap! calls conj [:load-index addr])
                      (js/Promise.resolve nil))
                    portfolio-optimizer-adapters/*save-scenario!*
                    (fn [scenario-id record]
                      (swap! calls conj [:save-scenario scenario-id record])
                      (js/Promise.resolve true))
                    portfolio-optimizer-adapters/*save-scenario-index!*
                    (fn [addr index]
                      (swap! calls conj [:save-index addr index])
                      (js/Promise.resolve true))
                    portfolio-optimizer-adapters/*dispatch!*
                    (fn [store* ctx effects]
                      (swap! calls conj [:dispatch store* ctx effects]))]
        (-> (portfolio-optimizer-adapters/save-portfolio-optimizer-scenario-effect
             nil
             store
             {:name "May Rotation"})
            (.then (fn [record]
                     (is (= "scn_3600" (:id record)))
                     (is (= "May Rotation" (:name record)))
                     (is (= "May Rotation" (get-in record [:config :name])))
                     (is (some #(= [:next-id 3600] %) @calls))
                     (is (= "scn_3600"
                            (second (first (filter #(= :save-scenario (first %))
                                                   @calls)))))
                     (is (not-any? #(and (= :save-scenario (first %))
                                         (= "draft" (second %)))
                                   @calls))
                     (is (= ["scn_3600"]
                            (get-in @store
                                    [:portfolio :optimizer :scenario-index :ordered-ids])))
                     (is (= "May Rotation"
                            (get-in @store
                                    [:portfolio :optimizer :scenario-index :by-id "scn_3600" :name])))
                     (is (nil?
                          (get-in @store
                                  [:portfolio :optimizer :scenario-index :by-id "draft"])))
                     (is (= [[:actions/navigate "/portfolio/optimize/scn_3600" {:replace? true}]]
                            (some (fn [[kind _store ctx effects]]
                                    (when (and (= :dispatch kind)
                                               (nil? ctx))
                                      effects))
                                  @calls)))
                     (done)))
            (.catch (async-support/unexpected-error done)))))))

(deftest save-portfolio-optimizer-scenario-effect-creates-new-id-for-retained-draft-id-test
  (async done
    (let [calls (atom [])
          address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          solved-run (fixtures/sample-last-successful-run
                      {:result {:expected-return 0.2
                                :volatility 0.34}
                       :computed-at-ms 2600})
          store (atom {:router {:path "/portfolio/optimize/draft-current"}
                       :wallet {:address address}
                       :portfolio {:optimizer
                                   {:active-scenario {:loaded-id "draft-current"
                                                      :status :computed}
                                    :draft {:id "draft-current"
                                            :name "Retained Draft"
                                            :objective {:kind :minimum-variance}
                                            :return-model {:kind :historical-mean}
                                            :risk-model {:kind :diagonal-shrink}
                                            :metadata {:dirty? false}}
                                    :scenario-index {:ordered-ids []
                                                     :by-id {}}
                                    :last-successful-run solved-run}}})]
      (with-redefs [portfolio-optimizer-adapters/*now-ms* (fn [] 3700)
                    portfolio-optimizer-adapters/*next-scenario-id*
                    (fn [now-ms]
                      (swap! calls conj [:next-id now-ms])
                      "scn_3700")
                    portfolio-optimizer-adapters/*load-scenario-index!*
                    (fn [addr]
                      (swap! calls conj [:load-index addr])
                      (js/Promise.resolve nil))
                    portfolio-optimizer-adapters/*save-scenario!*
                    (fn [scenario-id record]
                      (swap! calls conj [:save-scenario scenario-id record])
                      (js/Promise.resolve true))
                    portfolio-optimizer-adapters/*save-scenario-index!*
                    (fn [addr index]
                      (swap! calls conj [:save-index addr index])
                      (js/Promise.resolve true))]
        (-> (portfolio-optimizer-adapters/save-portfolio-optimizer-scenario-effect nil store)
            (.then (fn [record]
                     (is (= "scn_3700" (:id record)))
                     (is (some #(= [:next-id 3700] %) @calls))
                     (is (= "scn_3700"
                            (second (first (filter #(= :save-scenario (first %))
                                                   @calls)))))
                     (is (not-any? #(and (= :save-scenario (first %))
                                         (= "draft-current" (second %)))
                                   @calls))
                     (is (= ["scn_3700"]
                            (get-in @store
                                    [:portfolio :optimizer :scenario-index :ordered-ids])))
                     (is (nil?
                          (get-in @store
                                  [:portfolio :optimizer :scenario-index :by-id "draft-current"])))
                     (done)))
            (.catch (async-support/unexpected-error done)))))))

(deftest save-portfolio-optimizer-scenario-effect-preserves-loaded-saved-id-test
  (async done
    (let [calls (atom [])
          address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          solved-run (fixtures/sample-last-successful-run
                      {:result {:expected-return 0.22
                                :volatility 0.31}
                       :computed-at-ms 2650})
          store (atom {:router {:path "/portfolio/optimize/scn_saved"}
                       :wallet {:address address}
                       :portfolio {:optimizer
                                   {:active-scenario {:loaded-id "scn_saved"
                                                      :status :saved}
                                    :draft {:id "scn_saved"
                                            :name "Saved Scenario"
                                            :objective {:kind :minimum-variance}
                                            :return-model {:kind :historical-mean}
                                            :risk-model {:kind :diagonal-shrink}
                                            :metadata {:dirty? false}}
                                    :scenario-index {:ordered-ids ["scn_saved"]
                                                     :by-id {"scn_saved" {:id "scn_saved"
                                                                          :name "Saved Scenario"
                                                                          :status :saved}}}
                                    :last-successful-run solved-run}}})]
      (with-redefs [portfolio-optimizer-adapters/*now-ms* (fn [] 3800)
                    portfolio-optimizer-adapters/*next-scenario-id*
                    (fn [now-ms]
                      (swap! calls conj [:next-id now-ms])
                      "scn_unexpected")
                    portfolio-optimizer-adapters/*load-scenario-index!*
                    (fn [addr]
                      (swap! calls conj [:load-index addr])
                      (js/Promise.resolve (get-in @store
                                                  [:portfolio :optimizer :scenario-index])))
                    portfolio-optimizer-adapters/*save-scenario!*
                    (fn [scenario-id record]
                      (swap! calls conj [:save-scenario scenario-id record])
                      (js/Promise.resolve true))
                    portfolio-optimizer-adapters/*save-scenario-index!*
                    (fn [addr index]
                      (swap! calls conj [:save-index addr index])
                      (js/Promise.resolve true))]
        (-> (portfolio-optimizer-adapters/save-portfolio-optimizer-scenario-effect nil store)
            (.then (fn [record]
                     (is (= "scn_saved" (:id record)))
                     (is (not-any? #(= :next-id (first %)) @calls))
                     (is (= "scn_saved"
                            (second (first (filter #(= :save-scenario (first %))
                                                   @calls)))))
                     (is (= ["scn_saved"]
                            (get-in @store
                                    [:portfolio :optimizer :scenario-index :ordered-ids])))
                     (done)))
            (.catch (async-support/unexpected-error done)))))))

(deftest load-portfolio-optimizer-scenario-index-effect-loads-address-scoped-index-test
  (async done
    (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          index {:ordered-ids ["scn_02" "scn_01"]
                 :by-id {"scn_02" {:id "scn_02"
                                    :name "Fresh Run"
                                    :status :saved}
                         "scn_01" {:id "scn_01"
                                    :name "Core Hedge"
                                    :status :partially-executed}}}
          calls (atom [])
          store (atom {:wallet {:address address}
                       :portfolio {:optimizer {:scenario-index {:ordered-ids []
                                                                 :by-id {}}}}})]
      (with-redefs [portfolio-optimizer-adapters/*load-scenario-index!*
                    (fn [addr]
                      (swap! calls conj [:load-index addr])
                      (js/Promise.resolve index))
                    portfolio-optimizer-adapters/*now-ms* (fn [] 4000)]
        (-> (portfolio-optimizer-adapters/load-portfolio-optimizer-scenario-index-effect
             nil
             store)
            (.then (fn [loaded-index]
                     (is (= index loaded-index))
                     (is (= [[:load-index address]] @calls))
                     (is (= index
                            (get-in @store [:portfolio :optimizer :scenario-index])))
                     (is (= {:status :loaded
                             :started-at-ms 4000
                             :completed-at-ms 4000
                             :error nil}
                            (get-in @store
                                    [:portfolio :optimizer :scenario-index-load-state])))
                     (done)))
            (.catch (async-support/unexpected-error done)))))))

(deftest load-portfolio-optimizer-scenario-effect-hydrates-workspace-state-test
  (async done
    (let [scenario-record {:schema-version 1
                           :id "scn_01"
                           :name "Core Hedge"
                           :status :saved
                           :config {:id "scn_01"
                                    :name "Core Hedge"
                                    :objective {:kind :max-sharpe}
                                    :return-model {:kind :historical-mean}
                                    :risk-model {:kind :diagonal-shrink}
                                    :metadata {:dirty? false}}
                           :saved-run (fixtures/sample-last-successful-run
                                       {:computed-at-ms 2000
                                        :result {:expected-return 0.18
                                                 :volatility 0.42}})
                           :updated-at-ms 3000}
          store (atom {:portfolio {:optimizer {}}})
          calls (atom [])]
      (with-redefs [portfolio-optimizer-adapters/*load-scenario!*
                    (fn [scenario-id]
                      (swap! calls conj [:load-scenario scenario-id])
                      (js/Promise.resolve scenario-record))
                    portfolio-optimizer-adapters/*now-ms* (fn [] 4100)]
        (-> (portfolio-optimizer-adapters/load-portfolio-optimizer-scenario-effect
             nil
             store
             "scn_01")
            (.then (fn [loaded-record]
                     (is (= scenario-record loaded-record))
                     (is (= [[:load-scenario "scn_01"]] @calls))
                     (is (= (:config scenario-record)
                            (get-in @store [:portfolio :optimizer :draft])))
                     (is (= (:saved-run scenario-record)
                            (get-in @store [:portfolio :optimizer :last-successful-run])))
                     (is (= {:loaded-id "scn_01"
                             :status :saved
                             :read-only? false}
                            (get-in @store [:portfolio :optimizer :active-scenario])))
                     (is (= {:status :loaded
                             :scenario-id "scn_01"
                             :started-at-ms 4100
                             :completed-at-ms 4100
                             :error nil}
                            (get-in @store
                                    [:portfolio :optimizer :scenario-load-state])))
                     (is (= "Core Hedge"
                            (get-in @store
                                    [:portfolio :optimizer :scenario-index :by-id "scn_01" :name])))
                     (done)))
            (.catch (async-support/unexpected-error done)))))))

(deftest archive-portfolio-optimizer-scenario-effect-updates-record-index-and-active-state-test
  (async done
    (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          scenario-record {:schema-version 1
                           :id "scn_01"
                           :name "Core Hedge"
                           :address address
                           :status :saved
                           :config {:id "scn_01"
                                    :name "Core Hedge"
                                    :status :saved
                                    :metadata {:dirty? false
                                               :updated-at-ms 3000}}
                           :saved-run (fixtures/sample-last-successful-run)
                           :updated-at-ms 3000}
          scenario-index {:ordered-ids ["scn_02" "scn_01"]
                          :by-id {"scn_02" {:id "scn_02"
                                             :name "Other"
                                             :status :saved}
                                  "scn_01" {:id "scn_01"
                                             :name "Core Hedge"
                                             :status :saved}}}
          store (atom {:wallet {:address address}
                       :portfolio {:optimizer {:active-scenario {:loaded-id "scn_01"
                                                                  :status :saved}
                                               :draft (:config scenario-record)
                                               :scenario-index scenario-index}}})
          calls (atom [])]
      (with-redefs [portfolio-optimizer-adapters/*now-ms* (fn [] 5000)
                    portfolio-optimizer-adapters/*load-scenario!* (fn [scenario-id]
                                                                    (swap! calls conj [:load-scenario scenario-id])
                                                                    (js/Promise.resolve scenario-record))
                    portfolio-optimizer-adapters/*load-scenario-index!* (fn [addr]
                                                                          (swap! calls conj [:load-index addr])
                                                                          (js/Promise.resolve scenario-index))
                    portfolio-optimizer-adapters/*save-scenario!* (fn [scenario-id record]
                                                                     (swap! calls conj [:save-scenario scenario-id record])
                                                                     (js/Promise.resolve true))
                    portfolio-optimizer-adapters/*save-scenario-index!* (fn [addr index]
                                                                          (swap! calls conj [:save-index addr index])
                                                                          (js/Promise.resolve true))]
        (-> (portfolio-optimizer-adapters/archive-portfolio-optimizer-scenario-effect
             nil
             store
             "scn_01")
            (.then (fn [archived-record]
                     (is (= :archived (:status archived-record)))
                     (is (= :archived
                            (get-in @store
                                    [:portfolio :optimizer :scenario-index :by-id "scn_01" :status])))
                     (is (= ["scn_02" "scn_01"]
                            (get-in @store [:portfolio :optimizer :scenario-index :ordered-ids])))
                     (is (= :archived
                            (get-in @store [:portfolio :optimizer :active-scenario :status])))
                     (is (= :archived
                            (get-in @store [:portfolio :optimizer :draft :status])))
                     (is (= :archived
                            (get-in @store
                                    [:portfolio :optimizer :scenario-archive-state :status])))
                     (is (= [[:load-scenario "scn_01"]
                             [:load-index address]
                             [:save-scenario "scn_01" archived-record]
                             [:save-index address
                              (get-in @store [:portfolio :optimizer :scenario-index])]]
                            @calls))
                     (done)))
            (.catch (async-support/unexpected-error done)))))))

(deftest duplicate-portfolio-optimizer-scenario-effect-creates-new-record-and-index-entry-test
  (async done
    (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          scenario-record {:schema-version 1
                           :id "scn_01"
                           :name "Core Hedge"
                           :address address
                           :status :partially-executed
                           :config {:id "scn_01"
                                    :name "Core Hedge"
                                    :status :partially-executed
                                    :metadata {:dirty? false
                                               :updated-at-ms 3000}}
                           :saved-run (fixtures/sample-last-successful-run
                                       {:result {:expected-return 0.18
                                                 :volatility 0.42}})
                           :execution-ledger [{:row-id "row-1"}]
                           :updated-at-ms 3000}
          scenario-index {:ordered-ids ["scn_01"]
                          :by-id {"scn_01" {:id "scn_01"
                                             :name "Core Hedge"
                                             :status :partially-executed}}}
          store (atom {:wallet {:address address}
                       :portfolio {:optimizer {:scenario-index scenario-index}}})
          calls (atom [])]
      (with-redefs [portfolio-optimizer-adapters/*now-ms* (fn [] 5000)
                    portfolio-optimizer-adapters/*next-scenario-id* (fn [_now-ms] "scn_5000")
                    portfolio-optimizer-adapters/*load-scenario!* (fn [scenario-id]
                                                                    (swap! calls conj [:load-scenario scenario-id])
                                                                    (js/Promise.resolve scenario-record))
                    portfolio-optimizer-adapters/*load-scenario-index!* (fn [addr]
                                                                          (swap! calls conj [:load-index addr])
                                                                          (js/Promise.resolve scenario-index))
                    portfolio-optimizer-adapters/*save-scenario!* (fn [scenario-id record]
                                                                     (swap! calls conj [:save-scenario scenario-id record])
                                                                     (js/Promise.resolve true))
                    portfolio-optimizer-adapters/*save-scenario-index!* (fn [addr index]
                                                                          (swap! calls conj [:save-index addr index])
                                                                          (js/Promise.resolve true))]
        (-> (portfolio-optimizer-adapters/duplicate-portfolio-optimizer-scenario-effect
             nil
             store
             "scn_01")
            (.then (fn [duplicated-record]
                     (is (= "scn_5000" (:id duplicated-record)))
                     (is (= "Copy of Core Hedge" (:name duplicated-record)))
                     (is (= [] (:execution-ledger duplicated-record)))
                     (is (= ["scn_5000" "scn_01"]
                            (get-in @store [:portfolio :optimizer :scenario-index :ordered-ids])))
                     (is (= "Copy of Core Hedge"
                            (get-in @store
                                    [:portfolio :optimizer :scenario-index :by-id "scn_5000" :name])))
                     (is (= :duplicated
                            (get-in @store [:portfolio :optimizer :scenario-duplicate-state :status])))
                     (is (= [[:load-scenario "scn_01"]
                             [:load-index address]
                             [:save-scenario "scn_5000" duplicated-record]
                             [:save-index address
                              (get-in @store [:portfolio :optimizer :scenario-index])]]
                            @calls))
                     (done)))
            (.catch (async-support/unexpected-error done)))))))

(deftest reset-portfolio-optimizer-draft-effect-deletes-record-resets-state-and-preseeds-test
  (async done
    (let [calls (atom [])
          address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          store (atom {:router {:path "/portfolio/optimize"}
                       :wallet {:address address}
                       :portfolio {:optimizer
                                   {:active-scenario {:loaded-id "scn_old"
                                                      :status :saved}
                                    :draft {:id "scn_old"
                                            :name "Old Mix"
                                            :universe [{:instrument-id "perp:BTC"}]
                                            :metadata {:dirty? true}}
                                    :draft-persist {:status :saved :at-ms 1000}
                                    :last-successful-run {:result {:status :solved}}}}})]
      (with-redefs [portfolio-optimizer-adapters/*delete-draft!*
                    (fn [addr]
                      (swap! calls conj [:delete-draft addr])
                      (js/Promise.resolve true))
                    portfolio-optimizer-adapters/*dispatch!*
                    (fn [_store* _ctx effects]
                      (swap! calls conj [:dispatch effects]))]
        (-> (portfolio-optimizer-adapters/reset-portfolio-optimizer-draft-effect nil store)
            (.then (fn [_]
                     (is (= [:delete-draft address] (first @calls)))
                     (is (= "Untitled Optimization"
                            (get-in @store [:portfolio :optimizer :draft :name])))
                     (is (= [] (get-in @store [:portfolio :optimizer :draft :universe])))
                     (is (nil? (get-in @store [:portfolio :optimizer :active-scenario :loaded-id])))
                     (is (nil? (get-in @store [:portfolio :optimizer :last-successful-run])))
                     (is (nil? (get-in @store [:portfolio :optimizer :draft-persist])))
                     (is (= [:dispatch [[:actions/set-portfolio-optimizer-universe-from-current]]]
                            (last @calls)))
                     (done)))
            (.catch (async-support/unexpected-error done)))))))

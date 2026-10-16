(ns hyperopen.vaults.screener.commands-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.vaults.application.list-vm.cache :as list-cache]
            [hyperopen.vaults.screener.commands :as commands]))

(def day-ms
  (* 24 60 60 1000))

(def now-ms
  (.getTime (js/Date. "2026-10-16T00:00:00.000Z")))

(defn- index-row
  [address tvl age-days & {:as extra}]
  (merge {:vault-address address
          :name (str "Vault " address)
          :leader "0xleader"
          :tvl tvl
          :create-time-ms (- now-ms (* age-days day-ms))
          :relationship {:type :normal}
          :is-closed? false}
         extra))

(defn- state
  []
  (list-cache/reset-cache!)
  {:vaults {:merged-index-rows [(index-row "0xsmall" 20000 400)
                                (index-row "0xbig" 900000 400)
                                (index-row "0xyoung" 900000 10)
                                (index-row "0xchild" 900000 400 :relationship {:type :child})]}})

(deftest set-vaults-view-loads-the-cache-index-once-test
  (is (= [[:effects/save [:vaults-ui :view] :screener]
          [:effects/load-vault-screener-cache-index]]
         (commands/set-vaults-view {} :screener)))
  (is (= [[:effects/save [:vaults-ui :view] :screener]]
         (commands/set-vaults-view {:vaults {:screener {:cache-index-loaded? true}}} "screener")))
  (is (= [[:effects/save [:vaults-ui :view] :list]]
         (commands/set-vaults-view {} :bogus))))

(deftest run-builds-a-tvl-ordered-run-test
  (let [effects (commands/run-vault-screener (state) now-ms)
        [[save-id path-values] [effect-id run-id]] effects
        run (some (fn [[path value]] (when (= path [:vaults :screener :run]) value)) path-values)]
    (is (= :effects/save-many save-id) "projection comes before the heavy effect")
    (is (= [:effects/run-vault-screener 1] [effect-id run-id]))
    (is (= ["0xbig" "0xsmall"] (:addresses run)))
    (is (= :running (:status run)))
    (is (= :six-month (get-in run [:config :window])))
    (is (some #(= [[:vaults :screener :results] {}] %) path-values) "a new run clears old results")))

(deftest run-with-empty-universe-is-a-no-op-test
  (is (= [] (commands/run-vault-screener {:vaults {:merged-index-rows []}} now-ms))))

(deftest control-commands-follow-run-status-test
  (let [running {:vaults {:screener {:run {:id 4 :status :running}}}}
        paused (assoc-in running [:vaults :screener :run :status] :paused)]
    (is (= [[:effects/save [:vaults :screener :run :status] :paused]]
           (commands/control-vault-screener-run running :pause)))
    (is (= [] (commands/control-vault-screener-run running :resume)))
    (is (= [[:effects/save [:vaults :screener :run :status] :running]
            [:effects/run-vault-screener 4]]
           (commands/control-vault-screener-run paused "resume")))
    (is (= [[:effects/save-many [[[:vaults :screener :run :status] :cancelled]
                                 [[:vaults-ui :screener-panel] :builder]]]]
           (commands/control-vault-screener-run paused :cancel)))))

(defn- saved-config
  [effects]
  (let [[[effect-id path config]] effects]
    (is (= :effects/save effect-id))
    (is (= [:vaults-ui :screener] path))
    config))

(deftest criteria-commands-test
  (let [config (saved-config (commands/add-vault-screener-criterion {} :sortino))]
    (is (= [:sharpe :max-drawdown :sortino] (mapv :metric (:criteria config))))
    (is (= "1.5" (:value (last (:criteria config))))))
  (is (= [] (commands/add-vault-screener-criterion {} :sharpe)) "a metric can only be added once")
  (let [config (saved-config (commands/set-vault-screener-criterion-value {} "sharpe" "1."))]
    (is (= "1." (:value (first (:criteria config)))) "the typed text is kept verbatim"))
  (let [config (saved-config (commands/remove-vault-screener-criterion {} :sharpe))]
    (is (= [:max-drawdown] (mapv :metric (:criteria config))))))

(deftest control-and-sort-test
  (is (= 365 (:min-age-days (saved-config (commands/set-vault-screener-control {} :min-age-days 365)))))
  (is (false? (:include-deposited? (saved-config (commands/set-vault-screener-control {} :include-deposited? false)))))
  (testing "sorting the active column flips direction"
    (let [state {:vaults-ui {:screener {:results-sort {:metric :sharpe :direction :desc}}}}]
      (is (= {:metric :sharpe :direction :asc}
             (:results-sort (saved-config (commands/set-vault-screener-control state :results-sort :sharpe)))))
      (is (= {:metric :tvl :direction :desc}
             (:results-sort (saved-config (commands/set-vault-screener-control state :results-sort :tvl)))))))
  (is (= [] (commands/set-vault-screener-control {} :bogus 1))))

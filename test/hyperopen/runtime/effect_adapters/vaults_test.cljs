(ns hyperopen.runtime.effect-adapters.vaults-test
  (:require [cljs.test :refer-macros [deftest is]]
            [nexus.registry :as nxr]
            [hyperopen.api.default :as api]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.runtime.effect-adapters :as effect-adapters]
            [hyperopen.runtime.effect-adapters.common :as common]
            [hyperopen.runtime.effect-adapters.vaults :as vault-adapters]
            [hyperopen.vaults.effects :as vault-effects]
            [hyperopen.vaults.infrastructure.list-cache :as vault-list-cache]))

(deftest facade-vault-adapters-delegate-to-vault-module-test
  (is (identical? vault-adapters/api-fetch-vault-index-effect
                  effect-adapters/api-fetch-vault-index-effect))
  (is (identical? vault-adapters/api-fetch-vault-index-with-cache-effect
                  effect-adapters/api-fetch-vault-index-with-cache-effect))
  (is (identical? vault-adapters/api-fetch-vault-summaries-effect
                  effect-adapters/api-fetch-vault-summaries-effect))
  (is (identical? vault-adapters/api-fetch-user-vault-equities-effect
                  effect-adapters/api-fetch-user-vault-equities-effect))
  (is (identical? vault-adapters/api-fetch-vault-details-effect
                  effect-adapters/api-fetch-vault-details-effect))
  (is (identical? vault-adapters/api-fetch-vault-webdata2-effect
                  effect-adapters/api-fetch-vault-webdata2-effect))
  (is (identical? vault-adapters/api-fetch-vault-fills-effect
                  effect-adapters/api-fetch-vault-fills-effect))
  (is (identical? vault-adapters/api-fetch-vault-funding-history-effect
                  effect-adapters/api-fetch-vault-funding-history-effect))
  (is (identical? vault-adapters/api-fetch-vault-order-history-effect
                  effect-adapters/api-fetch-vault-order-history-effect))
  (is (identical? vault-adapters/api-fetch-vault-ledger-updates-effect
                  effect-adapters/api-fetch-vault-ledger-updates-effect)))

(deftest vault-submit-wrapper-injects-order-toast-seam-test
  (let [runtime-store (atom {})
        request {:vault-address "0xvault"}
        transfer-call (atom nil)]
    (letfn [(capture-transfer-call!
              [ctx store* request* opts]
              (reset! transfer-call {:ctx ctx
                                     :store store*
                                     :request request*
                                     :opts opts}))]
      (with-redefs [vault-adapters/api-submit-vault-transfer-effect capture-transfer-call!]
        (effect-adapters/api-submit-vault-transfer-effect nil runtime-store request)))
    (is (nil? (:ctx @transfer-call)))
    (is (identical? runtime-store (:store @transfer-call)))
    (is (= request (:request @transfer-call)))
    (is (fn? (get-in @transfer-call [:opts :show-toast!])))
    ((get-in @transfer-call [:opts :show-toast!]) runtime-store :success "Vault submitted")
    (is (= {:kind :success
            :message "Vault submitted"}
           (get-in @runtime-store [:ui :toast])))))

(deftest vault-fetch-adapters-wire-api-and-projection-dependencies-test
  (let [store (atom {})
        calls (atom [])]
    (with-redefs [vault-effects/api-fetch-vault-index!
                  (fn [deps]
                    (swap! calls conj [:index deps])
                    :index-result)
                  vault-effects/api-fetch-vault-index-with-cache!
                  (fn [deps]
                    (swap! calls conj [:index-with-cache deps])
                    :index-with-cache-result)
                  vault-effects/api-fetch-vault-summaries!
                  (fn [deps]
                    (swap! calls conj [:summaries deps])
                    :summaries-result)
                  vault-effects/api-fetch-user-vault-equities!
                  (fn [deps]
                    (swap! calls conj [:equities deps])
                    :equities-result)
                  vault-effects/api-fetch-vault-details!
                  (fn [deps]
                    (swap! calls conj [:details deps])
                    :details-result)
                  vault-effects/api-fetch-vault-webdata2!
                  (fn [deps]
                    (swap! calls conj [:webdata2 deps])
                    :webdata2-result)
                  vault-effects/api-fetch-vault-fills!
                  (fn [deps]
                    (swap! calls conj [:fills deps])
                    :fills-result)
                  vault-effects/api-fetch-vault-funding-history!
                  (fn [deps]
                    (swap! calls conj [:funding-history deps])
                    :funding-history-result)
                  vault-effects/api-fetch-vault-order-history!
                  (fn [deps]
                    (swap! calls conj [:order-history deps])
                    :order-history-result)
                  vault-effects/api-fetch-vault-ledger-updates!
                  (fn [deps]
                    (swap! calls conj [:ledger-updates deps])
                    :ledger-updates-result)]
      (is (= :index-result
             (vault-adapters/api-fetch-vault-index-effect nil store)))
      (is (= :index-with-cache-result
             (vault-adapters/api-fetch-vault-index-with-cache-effect nil store)))
      (is (= :summaries-result
             (vault-adapters/api-fetch-vault-summaries-effect nil store)))
      (is (= :equities-result
             (vault-adapters/api-fetch-user-vault-equities-effect nil store "0xuser")))
      (is (= :details-result
             (vault-adapters/api-fetch-vault-details-effect nil store "0xvault" "0xuser")))
      (is (= :webdata2-result
             (vault-adapters/api-fetch-vault-webdata2-effect nil store "0xvault")))
      (is (= :fills-result
             (vault-adapters/api-fetch-vault-fills-effect nil store "0xvault")))
      (is (= :funding-history-result
             (vault-adapters/api-fetch-vault-funding-history-effect nil store "0xvault")))
      (is (= :order-history-result
             (vault-adapters/api-fetch-vault-order-history-effect nil store "0xvault")))
      (is (= :ledger-updates-result
             (vault-adapters/api-fetch-vault-ledger-updates-effect nil store "0xvault"))))
    (let [captured (into {} (map (juxt first second) @calls))]
      (is (= store (get-in captured [:index :store])))
      (is (identical? api/request-vault-index-response!
                      (get-in captured [:index :request-vault-index-response!])))
      (is (identical? api-projections/begin-vault-index-load
                      (get-in captured [:index :begin-vault-index-load])))
      (is (identical? api-projections/apply-vault-index-success
                      (get-in captured [:index :apply-vault-index-success])))
      (is (identical? api-projections/apply-vault-index-error
                      (get-in captured [:index :apply-vault-index-error])))
      (is (identical? vault-adapters/persist-vault-startup-preview-record!
                      (get-in captured [:index :persist-vault-startup-preview-record!])))
      (is (identical? vault-list-cache/persist-vault-index-cache-record!
                      (get-in captured [:index :persist-vault-index-cache-record!])))

      (is (= store (get-in captured [:index-with-cache :store])))
      (is (identical? api/request-vault-index-response!
                      (get-in captured [:index-with-cache :request-vault-index-response!])))
      (is (identical? vault-list-cache/load-vault-index-cache-metadata!
                      (get-in captured [:index-with-cache :load-vault-index-cache-metadata!])))
      (is (identical? vault-list-cache/load-vault-index-cache-record!
                      (get-in captured [:index-with-cache :load-vault-index-cache-record!])))
      (is (identical? vault-list-cache/persist-vault-index-cache-record!
                      (get-in captured [:index-with-cache :persist-vault-index-cache-record!])))
      (is (identical? api-projections/begin-vault-index-load
                      (get-in captured [:index-with-cache :begin-vault-index-load])))
      (is (identical? api-projections/apply-vault-index-cache-hydration
                      (get-in captured [:index-with-cache :apply-vault-index-cache-hydration])))
      (is (identical? api-projections/apply-vault-index-success
                      (get-in captured [:index-with-cache :apply-vault-index-success])))
      (is (identical? api-projections/apply-vault-index-error
                      (get-in captured [:index-with-cache :apply-vault-index-error])))
      (is (identical? vault-adapters/persist-vault-startup-preview-record!
                      (get-in captured [:index-with-cache :persist-vault-startup-preview-record!])))

      (is (= store (get-in captured [:summaries :store])))
      (is (identical? api/request-vault-summaries!
                      (get-in captured [:summaries :request-vault-summaries!])))
      (is (identical? api-projections/begin-vault-summaries-load
                      (get-in captured [:summaries :begin-vault-summaries-load])))
      (is (identical? api-projections/apply-vault-summaries-success
                      (get-in captured [:summaries :apply-vault-summaries-success])))
      (is (identical? api-projections/apply-vault-summaries-error
                      (get-in captured [:summaries :apply-vault-summaries-error])))

      (is (= store (get-in captured [:equities :store])))
      (is (= "0xuser" (get-in captured [:equities :address])))
      (is (identical? api/request-user-vault-equities!
                      (get-in captured [:equities :request-user-vault-equities!])))
      (is (identical? api-projections/begin-user-vault-equities-load
                      (get-in captured [:equities :begin-user-vault-equities-load])))
      (is (identical? api-projections/apply-user-vault-equities-success
                      (get-in captured [:equities :apply-user-vault-equities-success])))
      (is (identical? api-projections/apply-user-vault-equities-error
                      (get-in captured [:equities :apply-user-vault-equities-error])))

      (is (= store (get-in captured [:details :store])))
      (is (= "0xvault" (get-in captured [:details :vault-address])))
      (is (= "0xuser" (get-in captured [:details :user-address])))
      (is (identical? api/request-vault-details!
                      (get-in captured [:details :request-vault-details!])))
      (is (identical? api-projections/begin-vault-details-load
                      (get-in captured [:details :begin-vault-details-load])))
      (is (identical? api-projections/apply-vault-details-success
                      (get-in captured [:details :apply-vault-details-success])))
      (is (identical? api-projections/apply-vault-details-error
                      (get-in captured [:details :apply-vault-details-error])))

      (is (= store (get-in captured [:webdata2 :store])))
      (is (= "0xvault" (get-in captured [:webdata2 :vault-address])))
      (is (identical? api/request-vault-webdata2!
                      (get-in captured [:webdata2 :request-vault-webdata2!])))
      (is (identical? api-projections/begin-vault-webdata2-load
                      (get-in captured [:webdata2 :begin-vault-webdata2-load])))
      (is (identical? api-projections/apply-vault-webdata2-success
                      (get-in captured [:webdata2 :apply-vault-webdata2-success])))
      (is (identical? api-projections/apply-vault-webdata2-error
                      (get-in captured [:webdata2 :apply-vault-webdata2-error])))

      (is (= store (get-in captured [:fills :store])))
      (is (= "0xvault" (get-in captured [:fills :vault-address])))
      (is (identical? api/request-user-fills!
                      (get-in captured [:fills :request-user-fills!])))
      (is (identical? api-projections/begin-vault-fills-load
                      (get-in captured [:fills :begin-vault-fills-load])))
      (is (identical? api-projections/apply-vault-fills-success
                      (get-in captured [:fills :apply-vault-fills-success])))
      (is (identical? api-projections/apply-vault-fills-error
                      (get-in captured [:fills :apply-vault-fills-error])))

      (is (= store (get-in captured [:funding-history :store])))
      (is (= "0xvault" (get-in captured [:funding-history :vault-address])))
      (is (identical? api/request-user-funding-history!
                      (get-in captured [:funding-history :request-user-funding-history!])))
      (is (identical? api-projections/begin-vault-funding-history-load
                      (get-in captured [:funding-history :begin-vault-funding-history-load])))
      (is (identical? api-projections/apply-vault-funding-history-success
                      (get-in captured [:funding-history :apply-vault-funding-history-success])))
      (is (identical? api-projections/apply-vault-funding-history-error
                      (get-in captured [:funding-history :apply-vault-funding-history-error])))

      (is (= store (get-in captured [:order-history :store])))
      (is (= "0xvault" (get-in captured [:order-history :vault-address])))
      (is (identical? api/request-historical-orders!
                      (get-in captured [:order-history :request-historical-orders!])))
      (is (identical? api-projections/begin-vault-order-history-load
                      (get-in captured [:order-history :begin-vault-order-history-load])))
      (is (identical? api-projections/apply-vault-order-history-success
                      (get-in captured [:order-history :apply-vault-order-history-success])))
      (is (identical? api-projections/apply-vault-order-history-error
                      (get-in captured [:order-history :apply-vault-order-history-error])))

      (is (= store (get-in captured [:ledger-updates :store])))
      (is (= "0xvault" (get-in captured [:ledger-updates :vault-address])))
      (is (identical? api/request-user-non-funding-ledger-updates!
                      (get-in captured [:ledger-updates :request-user-non-funding-ledger-updates!])))
      (is (identical? api-projections/begin-vault-ledger-updates-load
                      (get-in captured [:ledger-updates :begin-vault-ledger-updates-load])))
      (is (identical? api-projections/apply-vault-ledger-updates-success
                      (get-in captured [:ledger-updates :apply-vault-ledger-updates-success])))
      (is (identical? api-projections/apply-vault-ledger-updates-error
                      (get-in captured [:ledger-updates :apply-vault-ledger-updates-error])))))

(deftest vault-preview-persist-helper-delegates-only-when-route-module-hook-is-loaded-test
  (let [calls (atom [])]
    (with-redefs [hyperopen.runtime.effect-adapters.vaults/resolve-global-value
                  (fn [_path]
                    (fn [state]
                      (swap! calls conj state)
                      :persisted))]
      (is (= :persisted
             (vault-adapters/persist-vault-startup-preview-record! {:vaults {}})))
      (is (= [{:vaults {}}] @calls)))
    (with-redefs [hyperopen.runtime.effect-adapters.vaults/resolve-global-value
                  (constantly nil)]
      (is (nil? (vault-adapters/persist-vault-startup-preview-record! {:vaults {}}))))))

(deftest vault-submit-adapter-injects-default-and-custom-toast-seams-test
  (let [store (atom {})
        request {:vault-address "0xvault"}
        calls (atom [])
        custom-show-toast! (fn [& _] :custom-toast)]
    (with-redefs [vault-effects/api-submit-vault-transfer!
                  (fn [deps]
                    (swap! calls conj deps)
                    :submit-result)]
      (is (= :submit-result
             (vault-adapters/api-submit-vault-transfer-effect nil store request)))
      (is (= :submit-result
             (vault-adapters/api-submit-vault-transfer-effect
              nil
              store
              request
              {:show-toast! custom-show-toast!}))))
    (let [[default-deps custom-deps] @calls]
      (is (= store (:store default-deps)))
      (is (= request (:request default-deps)))
      (is (identical? nxr/dispatch (:dispatch! default-deps)))
      (is (identical? common/exchange-response-error
                      (:exchange-response-error default-deps)))
      (is (identical? common/runtime-error-message
                      (:runtime-error-message default-deps)))
      (is (nil? ((:show-toast! default-deps) store :info "ignored")))

      (is (= store (:store custom-deps)))
      (is (= request (:request custom-deps)))
      (is (identical? custom-show-toast! (:show-toast! custom-deps)))))))

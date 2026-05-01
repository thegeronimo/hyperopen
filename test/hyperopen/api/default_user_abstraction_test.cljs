(ns hyperopen.api.default-user-abstraction-test
  (:require [cljs.test :refer-macros [async deftest is use-fixtures]]
            [hyperopen.api.default :as api]))

(use-fixtures
  :each
  {:before (fn []
             (api/reset-request-runtime!))
   :after (fn []
            (api/reset-request-runtime!))})

(deftest fetch-user-abstraction-sends-request-and-projects-unified-mode-test
  (async done
    (let [store (atom {:wallet {:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                       :account {:mode :classic
                                 :abstraction-raw nil}})
          calls (atom [])
          original-post-info hyperopen.api.default/post-info!]
      (set! hyperopen.api.default/post-info!
            (fn post-info-mock
              ([body]
               (post-info-mock body {}))
              ([body _opts]
               (swap! calls conj body)
               (js/Promise.resolve "portfolioMargin"))
              ([body opts _attempt]
               (post-info-mock body opts))))
      (-> (api/fetch-user-abstraction! store "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
          (.then (fn [snapshot]
                   (is (= {"type" "userAbstraction"
                           "user" "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                          (first @calls)))
                   (is (= {:mode :unified
                           :abstraction-raw "portfolioMargin"}
                          snapshot))
                   (is (= snapshot (:account @store)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))
          (.finally
            (fn []
              (set! hyperopen.api.default/post-info! original-post-info)))))))

(deftest fetch-user-abstraction-maps-classic-modes-test
  (async done
    (let [store (atom {:wallet {:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                       :account {:mode :unified
                                 :abstraction-raw "unifiedAccount"}})
          original-post-info hyperopen.api.default/post-info!]
      (set! hyperopen.api.default/post-info!
            (fn post-info-mock
              ([body]
               (post-info-mock body {}))
              ([_body _opts]
               (js/Promise.resolve "default"))
              ([body opts _attempt]
               (post-info-mock body opts))))
      (-> (api/fetch-user-abstraction! store "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
          (.then (fn [snapshot]
                   (is (= {:mode :classic
                           :abstraction-raw "default"}
                          snapshot))
                   (is (= snapshot (:account @store)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))
          (.finally
            (fn []
              (set! hyperopen.api.default/post-info! original-post-info)))))))

(deftest fetch-user-abstraction-maps-dex-abstraction-to-classic-mode-test
  (async done
    (let [store (atom {:wallet {:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                       :account {:mode :unified
                                 :abstraction-raw "unifiedAccount"}})
          original-post-info hyperopen.api.default/post-info!]
      (set! hyperopen.api.default/post-info!
            (fn post-info-mock
              ([body]
               (post-info-mock body {}))
              ([_body _opts]
               (js/Promise.resolve "dexAbstraction"))
              ([body opts _attempt]
               (post-info-mock body opts))))
      (-> (api/fetch-user-abstraction! store "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
          (.then (fn [snapshot]
                   (is (= {:mode :classic
                           :abstraction-raw "dexAbstraction"}
                          snapshot))
                   (is (= snapshot (:account @store)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))
          (.finally
            (fn []
              (set! hyperopen.api.default/post-info! original-post-info)))))))

(deftest fetch-user-abstraction-skips-stale-address-write-test
  (async done
    (let [store (atom {:wallet {:address "0xdddddddddddddddddddddddddddddddddddddddd"}
                       :account {:mode :classic
                                 :abstraction-raw nil}})
          original-post-info hyperopen.api.default/post-info!]
      (set! hyperopen.api.default/post-info!
            (fn post-info-mock
              ([body]
               (post-info-mock body {}))
              ([_body _opts]
               (js/Promise.resolve "unifiedAccount"))
              ([body opts _attempt]
               (post-info-mock body opts))))
      (-> (api/fetch-user-abstraction! store "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
          (.then (fn [snapshot]
                   (is (= {:mode :unified
                           :abstraction-raw "unifiedAccount"}
                          snapshot))
                   (is (= {:mode :classic
                           :abstraction-raw nil}
                          (:account @store)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))
          (.finally
            (fn []
              (set! hyperopen.api.default/post-info! original-post-info)))))))

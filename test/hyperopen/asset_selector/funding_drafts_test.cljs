(ns hyperopen.asset-selector.funding-drafts-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.active-asset.funding-policy :as funding-policy]
            [hyperopen.asset-selector.actions :as actions]))

(deftest set-funding-tooltip-visible-syncs-active-asset-predictability-on-open-test
  (let [btc-pin-id (funding-policy/funding-tooltip-pin-id "BTC")
        eth-pin-id (funding-policy/funding-tooltip-pin-id "ETH")]
    (is (= [[:effects/save [:funding-ui :tooltip :visible-id] btc-pin-id]
            [:effects/sync-active-asset-funding-predictability "BTC"]]
           (actions/set-funding-tooltip-visible {:active-asset "BTC"}
                                                btc-pin-id
                                                true)))
    (is (= [[:effects/save [:funding-ui :tooltip :visible-id] eth-pin-id]]
           (actions/set-funding-tooltip-visible {:active-asset "BTC"}
                                                eth-pin-id
                                                true)))
    (is (= [[:effects/save [:funding-ui :tooltip :visible-id] nil]]
           (actions/set-funding-tooltip-visible {:active-asset "BTC"
                                                 :funding-ui {:tooltip {:visible-id btc-pin-id}}}
                                                btc-pin-id
                                                false)))))

(deftest set-funding-tooltip-visible-syncs-namespaced-active-market-coin-test
  (let [market-pin-id (funding-policy/funding-tooltip-pin-id "xyz:BRENTOIL")]
    (is (= [[:effects/save [:funding-ui :tooltip :visible-id] market-pin-id]
            [:effects/sync-active-asset-funding-predictability "xyz:BRENTOIL"]]
           (actions/set-funding-tooltip-visible {:active-asset "BRENTOIL"
                                                 :active-market {:coin "xyz:BRENTOIL"
                                                                 :dex "xyz"
                                                                 :market-type :perp}}
                                                market-pin-id
                                                true)))))

(deftest funding-tooltip-anchor-is-normalized-retained-and-cleared-with-the-last-open-state-test
  (let [pin-id (funding-policy/funding-tooltip-pin-id "BTC")
        raw-anchor {:left 104
                    :right 176
                    :top 58
                    :bottom 82
                    :width 72
                    :height 24
                    :viewport-width 1280
                    :viewport-height 720}
        anchor {:left 104
                :right 176
                :top 58
                :viewport-width 1280
                :viewport-height 720}
        both-open-state {:active-asset "BTC"
                         :funding-ui {:tooltip {:visible-id pin-id
                                                :pinned-id pin-id
                                                :anchor anchor}}}
        pinned-closed-state {:active-asset "BTC"
                             :funding-ui {:tooltip {:visible-id pin-id
                                                    :pinned-id nil
                                                    :anchor anchor}}}]
    (is (= [[:effects/save-many [[[:funding-ui :tooltip :pinned-id] pin-id]
                                 [[:funding-ui :tooltip :anchor] anchor]]]]
           (actions/set-funding-tooltip-pinned {:active-asset "BTC"}
                                               pin-id
                                               true
                                               raw-anchor)))
    (is (= [[:effects/save-many [[[:funding-ui :tooltip :visible-id] pin-id]
                                 [[:funding-ui :tooltip :anchor] anchor]]]
            [:effects/sync-active-asset-funding-predictability "BTC"]]
           (actions/set-funding-tooltip-visible {:active-asset "BTC"}
                                                pin-id
                                                true
                                                raw-anchor)))
    ;; Legacy callers retain the original three-argument result and do not
    ;; fabricate an anchor.
    (is (= [[:effects/save [:funding-ui :tooltip :pinned-id] pin-id]]
           (actions/set-funding-tooltip-pinned {:active-asset "BTC"}
                                               pin-id
                                               true)))
    (is (= [[:effects/save [:funding-ui :tooltip :pinned-id] pin-id]]
           (actions/set-funding-tooltip-pinned {:active-asset "BTC"}
                                               pin-id
                                               true
                                               {:left 104 :right 176 :top 58})))
    ;; Scrolling/dismissing closes the pinned state first but keeps the anchor
    ;; for the still-visible tooltip; the final close clears it atomically.
    (is (= [[:effects/save [:funding-ui :tooltip :pinned-id] nil]]
           (actions/set-funding-tooltip-pinned both-open-state pin-id false)))
    (is (= [[:effects/save-many [[[:funding-ui :tooltip :visible-id] nil]
                                 [[:funding-ui :tooltip :anchor] nil]]]]
           (actions/set-funding-tooltip-visible pinned-closed-state pin-id false)))))

(deftest funding-tooltip-close-clears-active-hypothetical-draft-only-when-fully-closed-test
  (let [btc-pin-id (funding-policy/funding-tooltip-pin-id "BTC")]
    (is (= [[:effects/save-many [[[:funding-ui :tooltip :visible-id] nil]
                                 [[:funding-ui :hypothetical-position-by-coin] {}]]]]
           (actions/set-funding-tooltip-visible {:active-asset "BTC"
                                                 :funding-ui {:tooltip {:visible-id btc-pin-id
                                                                        :pinned-id nil}
                                                              :hypothetical-position-by-coin {"BTC" {:size-input "0.0200"
                                                                                                     :value-input "1000.00"}}}}
                                                btc-pin-id
                                                false)))
    (is (= [[:effects/save-many [[[:funding-ui :tooltip :pinned-id] nil]
                                 [[:funding-ui :hypothetical-position-by-coin] {}]]]]
           (actions/set-funding-tooltip-pinned {:active-asset "BTC"
                                                :funding-ui {:tooltip {:visible-id nil
                                                                       :pinned-id btc-pin-id}
                                                             :hypothetical-position-by-coin {"BTC" {:size-input "0.0200"
                                                                                                    :value-input "1000.00"}}}}
                                               btc-pin-id
                                               false)))
    (is (= [[:effects/save [:funding-ui :tooltip :visible-id] nil]]
           (actions/set-funding-tooltip-visible {:active-asset "BTC"
                                                 :funding-ui {:tooltip {:visible-id btc-pin-id
                                                                        :pinned-id btc-pin-id}
                                                              :hypothetical-position-by-coin {"BTC" {:size-input "0.0200"
                                                                                                     :value-input "1000.00"}}}}
                                                btc-pin-id
                                                false)))))

(deftest funding-tooltip-close-clears-namespaced-and-base-active-drafts-when-fully-closed-test
  (let [market-pin-id (funding-policy/funding-tooltip-pin-id "xyz:BRENTOIL")]
    (is (= [[:effects/save-many [[[:funding-ui :tooltip :visible-id] nil]
                                 [[:funding-ui :hypothetical-position-by-coin] {"ETH" {:size-input "1.0000"
                                                                                        :value-input "1000.00"}}]]]]
           (actions/set-funding-tooltip-visible
            {:active-asset "BRENTOIL"
             :active-market {:coin "xyz:BRENTOIL"
                             :dex "xyz"
                             :market-type :perp}
             :funding-ui {:tooltip {:visible-id market-pin-id
                                    :pinned-id nil}
                          :hypothetical-position-by-coin {"BRENTOIL" {:size-input "1.3100"
                                                                      :value-input "141.66"}
                                                          "XYZ:BRENTOIL" {:size-input "1.3100"
                                                                           :value-input "141.66"}
                                                          "ETH" {:size-input "1.0000"
                                                                 :value-input "1000.00"}}}}
            market-pin-id
            false)))))

(deftest funding-hypothetical-actions-sync-size-and-value-test
  (is (= [[:effects/save [:funding-ui :hypothetical-position-by-coin]
           {"BTC" {:size-input "0.0185"
                   :value-input "99.39"}}]]
         (actions/enter-funding-hypothetical-position {} "btc" 5372.43 {:size-input "0.0185"
                                                                         :value-input "99.39"})))

  (is (= [[:effects/save [:funding-ui :hypothetical-position-by-coin] {}]]
         (actions/reset-funding-hypothetical-position
          {:funding-ui {:hypothetical-position-by-coin {"BTC" {:size-input "0.0185"
                                                                :value-input "99.39"}}}}
          "btc")))

  (is (= [[:effects/save [:funding-ui :hypothetical-position-by-coin]
           {"BTC" {:size-input "0.02"
                   :value-input "1000.00"}}]]
         (actions/set-funding-hypothetical-size {} "btc" 50000 "0.02")))

  (is (= [[:effects/save [:funding-ui :hypothetical-position-by-coin]
           {"BTC" {:size-input "0,02"
                   :value-input "1000.00"}}]]
         (actions/set-funding-hypothetical-size
          {:ui {:locale "fr-FR"}}
          "btc"
          50000
          "0,02")))

  (is (= [[:effects/save [:funding-ui :hypothetical-position-by-coin]
           {"BTC" {:size-input "oops"
                   :value-input "1000.00"}}]]
         (actions/set-funding-hypothetical-size {} "btc" 50000 "oops")))

  (is (= [[:effects/save [:funding-ui :hypothetical-position-by-coin]
           {"BTC" {:size-input "0.0300"
                   :value-input "161.17"}}]]
         (actions/set-funding-hypothetical-size
          {:funding-ui {:hypothetical-position-by-coin {"BTC" {:size-input "0.0185"
                                                                :value-input "99.39"}}}}
          "btc"
          5372.43
          "0.0300")))

  (is (= [[:effects/save [:funding-ui :hypothetical-position-by-coin]
           {"BTC" {:size-input "0.0300"
                   :value-input "1500"}}]]
         (actions/set-funding-hypothetical-value
          {:funding-ui {:hypothetical-position-by-coin {"BTC" {:size-input "-0.0200"
                                                                :value-input "1000.00"}}}}
          "btc"
          50000
          "1500")))

  (is (= [[:effects/save [:funding-ui :hypothetical-position-by-coin]
           {"BTC" {:size-input "0.0250"
                   :value-input "1250"}}]]
         (actions/set-funding-hypothetical-value {} "BTC" 50000 "1250")))

  (is (= [[:effects/save [:funding-ui :hypothetical-position-by-coin]
           {"BTC" {:size-input "-0.0250"
                   :value-input "-1250"}}]]
         (actions/set-funding-hypothetical-value {} "BTC" 50000 "-1250")))

  (is (= [[:effects/save [:funding-ui :hypothetical-position-by-coin]
           {"BTC" {:size-input "-0.0250"
                   :value-input "-1250,0"}}]]
         (actions/set-funding-hypothetical-value
          {:ui {:locale "fr-FR"}}
          "BTC"
          50000
          "-1250,0")))

  (is (= [[:effects/save [:funding-ui :hypothetical-position-by-coin]
           {"BTC" {:size-input "0.0200"
                   :value-input "1000"}}]]
         (actions/set-funding-hypothetical-value
          {:funding-ui {:hypothetical-position-by-coin {"BTC" {:size-input "-0.0200"
                                                                :value-input "-1000.00"}}}}
          "BTC"
          50000
          "1000")))

  (is (= [[:effects/save [:funding-ui :hypothetical-position-by-coin]
           {"BTC" {:size-input "0.0200"
                   :value-input ""}}]]
         (actions/set-funding-hypothetical-value {} "BTC" 50000 ""))))

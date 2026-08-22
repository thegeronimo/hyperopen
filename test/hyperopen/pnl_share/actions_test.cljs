(ns hyperopen.pnl-share.actions-test
  (:require [cljs.test :refer [deftest is testing]]
            [hyperopen.pnl-share.actions :as actions]))

(def ^:private address "0x1234567890abcdef1234567890abcdef12345678")

(def ^:private position
  {:type "oneWay"
   :position {:coin "SOL" :szi "41.2" :entryPx "142.08" :markPx "168.90"
              :leverage {:type "cross" :value 20}}})

(defn- state
  ([] (state {}))
  ([overrides]
   (merge {:wallet {:address address}
           :pnl-share (actions/default-pnl-share-state)}
          overrides)))

(defn- with-code
  [code]
  (state {:referrals {:raw {:referrerState {:stage "ready" :data {:code code}}}}}))

(deftest opening-requests-the-lazy-module-before-anything-else
  (let [effects (actions/open-pnl-share-card (state) position)]
    (is (= [:effects/load-surface-module :pnl-share-modal] (first effects))
        "the module must be requested first, as the spectate modal does")
    (is (= :effects/save-many (first (second effects))))))

(deftest opening-writes-the-position-and-resets-the-caption
  (let [[_ [_ saves]] (actions/open-pnl-share-card (state) position)
        by-path (into {} (map (fn [[path value]] [path value])) saves)]
    (is (true? (get by-path [:pnl-share :open?])))
    (is (= position (get by-path [:pnl-share :position])))
    (is (= "" (get by-path [:pnl-share :caption])))
    (is (= :neon-arrow (get by-path [:pnl-share :template])))
    (is (= {:show-prices? true :show-size? false :show-funding? true :show-handle? false}
           (get by-path [:pnl-share :options]))
        "size and wallet are opt-in, not opt-out")))

(deftest the-default-card-shows-no-size-and-no-wallet
  (let [defaults (actions/default-options)]
    (is (true? (:show-prices? defaults)))
    (is (true? (:show-funding? defaults)))
    (is (false? (:show-size? defaults))
        "position size is nobody else's business by default")
    (is (false? (:show-handle? defaults))
        "nor is the address holding it")))

(deftest opening-keeps-the-template-and-toggles-the-trader-last-chose
  (let [sticky (state {:pnl-share {:template :number-hero
                                   :options {:show-prices? false
                                             :show-size? true
                                             :show-funding? true
                                             :show-handle? false}}})
        [_ [_ saves]] (actions/open-pnl-share-card sticky position)
        by-path (into {} saves)]
    (is (= :number-hero (get by-path [:pnl-share :template])))
    (is (= {:show-prices? false :show-size? true :show-funding? true :show-handle? false}
           (get by-path [:pnl-share :options]))
        "a trader who turned size on keeps it on next time")))

(deftest opening-fetches-the-referral-code-only-when-it-is-missing
  (testing "not in state yet: the trade route never loads it"
    (is (= [:effects/api-fetch-referral address]
           (last (actions/open-pnl-share-card (state) position)))))
  (testing "already known: no redundant request"
    (is (not= :effects/api-fetch-referral
              (first (last (actions/open-pnl-share-card (with-code "sleepy") position))))))
  (testing "no wallet connected: nothing to fetch for"
    (is (not= :effects/api-fetch-referral
              (first (last (actions/open-pnl-share-card (state {:wallet {}}) position)))))))

(deftest opening-asks-for-the-coin-icon-using-the-app-wide-key-derivation
  (testing "a base-dex coin resolves to its bare symbol"
    (is (= [:effects/resolve-pnl-share-icon "SOL"]
           (some #(when (= :effects/resolve-pnl-share-icon (first %)) %)
                 (actions/open-pnl-share-card (state) position)))))
  (testing "a HIP-3 position keeps its venue prefix, as the tables do"
    (let [named (assoc-in position [:position :coin] "xyz:NVDA")
          named (assoc named :dex "xyz")]
      (is (= [:effects/resolve-pnl-share-icon "xyz:NVDA"]
             (some #(when (= :effects/resolve-pnl-share-icon (first %)) %)
                   (actions/open-pnl-share-card (state) named))))))
  (testing "the derivation is the one the rest of the app uses"
    (is (= "SOL" (actions/position-icon-key position)))
    (is (= "BTC" (actions/position-icon-key
                  {:dex "flx" :position {:coin "flx:BTC" :szi "1"}}))
        "the alias table folds flx:BTC onto BTC")))

(deftest a-resolved-icon-is-stored-against-the-coin-it-belongs-to
  (is (= [[:effects/save [:pnl-share :icon] {:key "SOL" :data-uri "data:image/svg+xml;base64,AAA"}]]
         (actions/set-pnl-share-icon (state) "SOL" "data:image/svg+xml;base64,AAA")))
  (testing "a miss is stored too, so the card stops waiting and draws its monogram"
    (is (= [[:effects/save [:pnl-share :icon] {:key "SOL" :data-uri nil}]]
           (actions/set-pnl-share-icon (state) "SOL" nil)))))

(deftest closing-clears-the-position-so-a-stale-card-cannot-flash-back
  (let [[[_ saves]] (actions/close-pnl-share-card (state))
        by-path (into {} saves)]
    (is (false? (get by-path [:pnl-share :open?])))
    (is (nil? (get by-path [:pnl-share :position])))))

(deftest one-option-action-covers-every-control-in-the-rail
  (is (= [[:effects/save [:pnl-share :template] :number-hero]]
         (actions/set-pnl-share-option (state) :template :number-hero)))
  (is (= [[:effects/save [:pnl-share :template] :neon-arrow]]
         (actions/set-pnl-share-option (state) :template :nonsense))
      "an unknown template falls back rather than corrupting state")
  (is (= [[:effects/save [:pnl-share :options :show-funding?] false]]
         (actions/set-pnl-share-option (state) :show-funding? false)))
  (is (= [[:effects/save [:pnl-share :options :show-size?] true]]
         (actions/set-pnl-share-option (state) :show-size? true)))
  (is (= [] (actions/set-pnl-share-option (state) :not-a-control "x"))))

(deftest the-caption-is-clipped-to-the-limit-x-actually-accepts
  (let [long-text (apply str (repeat 400 "a"))
        [[_ _ value]] (actions/set-pnl-share-option (state) :caption long-text)]
    (is (= actions/caption-limit (count value)))
    (is (= 280 actions/caption-limit)))
  (let [[[_ _ value]] (actions/set-pnl-share-option (state) :caption "gm")]
    (is (= "gm" value))))

(deftest saving-passes-only-what-the-adapter-needs-to-name-the-file
  (let [open-state (state {:pnl-share (assoc (actions/default-pnl-share-state)
                                             :open? true
                                             :position position)})]
    (is (= [[:effects/export-pnl-share-card-png {:coin "SOL" :side :long}]]
           (actions/save-pnl-share-card-image open-state))))
  (testing "a short position is named as one"
    (let [short-state (state {:pnl-share {:open? true
                                          :position (assoc-in position
                                                              [:position :szi] "-41.2")}})]
      (is (= :short (:side (second (first (actions/save-pnl-share-card-image short-state))))))))
  (testing "nothing happens when the modal is closed"
    (is (nil? (actions/save-pnl-share-card-image (state))))))

(deftest a-hip-3-position-loses-its-venue-prefix-in-the-file-name
  (let [named-dex (state {:pnl-share {:open? true
                                      :position (assoc-in position
                                                          [:position :coin] "xyz:NVDA")}})]
    (is (= "NVDA" (:coin (second (first (actions/save-pnl-share-card-image named-dex))))))))

(deftest copying-passes-the-code-and-lets-the-adapter-build-the-url
  (is (= [[:effects/copy-pnl-share-link "sleepy"]]
         (actions/copy-pnl-share-link (with-code "sleepy"))))
  (is (= [[:effects/copy-pnl-share-link nil]]
         (actions/copy-pnl-share-link (state)))))

(deftest escape-closes-and-other-keys-do-nothing
  (let [open-state (state {:pnl-share {:open? true :position position}})]
    (is (= (actions/close-pnl-share-card open-state)
           (actions/handle-pnl-share-card-keydown open-state "Escape")))
    (is (nil? (actions/handle-pnl-share-card-keydown open-state "Enter")))
    (is (nil? (actions/handle-pnl-share-card-keydown (state) "Escape"))
        "a closed modal must not emit close effects on every stray Escape")))

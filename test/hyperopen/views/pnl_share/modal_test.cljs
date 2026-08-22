(ns hyperopen.views.pnl-share.modal-test
  (:require [clojure.string :as str]
            [cljs.test :refer [deftest is testing]]
            [hyperopen.pnl-share.actions :as actions]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.pnl-share.modal :as modal]))

(def ^:private address "0x1234567890abcdef1234567890abcdef12345678")

(def ^:private position
  {:type "oneWay"
   :position {:coin "SOL" :szi "41.2" :entryPx "142.08" :markPx "168.90"
              :unrealizedPnl "18204.11" :returnOnEquity "2.146"
              :leverage {:type "cross" :value 20}
              :cumFunding {:allTime "24.5" :sinceOpen "18.4"}}})

(def ^:private env
  {:now-ms 1787356320000 :site-origin "https://hyperopen.xyz"})

(defn- state
  ([] (state {}))
  ([overrides]
   (merge {:wallet {:address address}
           :orders {:fills []}
           :pnl-share (assoc (actions/default-pnl-share-state)
                             :open? true
                             :position position)}
          overrides)))

(defn- view
  ([] (view (state)))
  ([state*] (modal/pnl-share-modal-view state* env)))

(defn- nodes-by-role
  [tree role]
  (->> (tree-seq vector? seq tree)
       (filter (fn [node]
                 (and (vector? node)
                      (map? (second node))
                      (= role (:data-role (second node))))))
       vec))

(deftest a-closed-modal-renders-nothing
  (is (nil? (view (state {:pnl-share (actions/default-pnl-share-state)}))))
  (is (nil? (view (state {:pnl-share {:open? true :position nil}})))
      "open with no position must not render a card built from nothing"))

(deftest the-panel-is-a-real-dialog
  (let [panel (first (nodes-by-role (view) "pnl-share-modal"))
        attrs (second panel)]
    (is (= "dialog" (:role attrs)))
    (is (true? (:aria-modal attrs)))
    (is (some? (:aria-labelledby attrs)))
    (is (fn? (:replicant/on-render attrs)) "focus trap and restore must be attached")
    (is (= [[:actions/handle-pnl-share-card-keydown [:event/key]]]
           (get-in attrs [:on :keydown])))))

(deftest the-backdrop-closes-the-modal
  (let [tree (view)
        backdrop (->> (tree-seq vector? seq tree)
                      (filter (fn [node]
                                (and (vector? node)
                                     (map? (second node))
                                     (= "Close share card" (:aria-label (second node)))
                                     (= :button (first node)))))
                      first)]
    (is (some? backdrop))
    (is (= [[:actions/close-pnl-share-card]] (get-in (second backdrop) [:on :click])))))

(deftest the-preview-carries-the-card-the-exporter-looks-for
  (let [card (first (nodes-by-role (view) "pnl-share-card-svg"))]
    (is (= :svg (first card)))
    (is (= 1080 (:width (second card))))
    (testing "the preview scales with CSS while keeping its intrinsic size"
      (is (= "100%" (get-in (second card) [:style :width]))))))

(deftest the-template-picker-marks-the-active-card
  (let [options (nodes-by-role (view) "pnl-share-template-option")]
    (is (= 2 (count options)))
    (is (= ["neon-arrow" "number-hero"] (mapv #(:data-template (second %)) options)))
    (is (= ["true" "false"] (mapv #(:aria-pressed (second %)) options)))
    (is (= [[:actions/set-pnl-share-option :template :number-hero]]
           (get-in (second (second options)) [:on :click])))))

(deftest switching-template-flips-which-option-is-pressed
  (let [options (nodes-by-role (view (state {:pnl-share {:open? true
                                                         :position position
                                                         :template :number-hero}}))
                               "pnl-share-template-option")]
    (is (= ["false" "true"] (mapv #(:aria-pressed (second %)) options)))))

(defn- toggle-by-field
  [tree field]
  (->> (nodes-by-role tree "pnl-share-field-toggle")
       (filter #(= field (:data-field (second %))))
       first))

(deftest the-field-toggles-report-and-flip-their-state
  (let [tree (view)
        toggles (nodes-by-role tree "pnl-share-field-toggle")]
    (is (= ["show-prices?" "show-size?" "show-funding?" "show-handle?"]
           (mapv #(:data-field (second %)) toggles)))
    (testing "the defaults are reported honestly: size and wallet start off"
      (is (= ["true" "false" "true" "false"]
             (mapv #(:aria-pressed (second %)) toggles))))
    (is (= [[:actions/set-pnl-share-option :show-prices? false]]
           (get-in (second (first toggles)) [:on :click])))
    (testing "an off toggle offers to turn itself on"
      (is (= [[:actions/set-pnl-share-option :show-size? true]]
             (get-in (second (toggle-by-field tree "show-size?")) [:on :click])))
      (is (= [[:actions/set-pnl-share-option :show-handle? true]]
             (get-in (second (toggle-by-field tree "show-handle?")) [:on :click])))))
  (testing "a toggled-off field reports itself off and offers to turn back on"
    (let [tree (view (state {:pnl-share {:open? true
                                         :position position
                                         :options {:show-funding? false}}}))
          funding (toggle-by-field tree "show-funding?")]
      (is (= "false" (:aria-pressed (second funding))))
      (is (= [[:actions/set-pnl-share-option :show-funding? true]]
             (get-in (second funding) [:on :click]))))))

(deftest the-caption-counter-tracks-the-limit-x-enforces
  (is (= "0/280" (first (hiccup/collect-strings
                         (first (nodes-by-role (view) "pnl-share-caption-count"))))))
  (let [typed (state {:pnl-share {:open? true :position position :caption "gm"}})]
    (is (= "2/280" (first (hiccup/collect-strings
                           (first (nodes-by-role (view typed) "pnl-share-caption-count"))))))))

(deftest a-wallet-with-no-code-is-told-so-instead-of-being-shown-a-fake-one
  (is (seq (nodes-by-role (view) "pnl-share-no-referral-note")))
  (let [with-code (state {:referrals {:raw {:referrerState {:data {:code "sleepy"}}}}})]
    (is (empty? (nodes-by-role (view with-code) "pnl-share-no-referral-note")))))

(deftest the-post-link-opens-a-compose-window-and-publishes-nothing
  (let [link (first (nodes-by-role (view) "pnl-share-post-x"))
        attrs (second link)]
    (is (= :a (first link)))
    (is (= "_blank" (:target attrs)))
    (is (= "noopener noreferrer" (:rel attrs)))
    (is (str/starts-with? (:href attrs) "https://x.com/intent/post?"))))

(deftest the-post-link-carries-the-caption-and-the-join-link
  (let [with-code (state {:referrals {:raw {:referrerState {:data {:code "sleepy"}}}}
                          :pnl-share {:open? true :position position :caption "gm & wagmi"}})
        href (:href (second (first (nodes-by-role (view with-code) "pnl-share-post-x"))))]
    (is (str/includes? href (js/encodeURIComponent "gm & wagmi")))
    (is (str/includes? href (js/encodeURIComponent "https://hyperopen.xyz/join/sleepy")))))

(deftest an-empty-caption-falls-back-to-a-factual-default
  (let [href (:href (second (first (nodes-by-role (view) "pnl-share-post-x"))))]
    (is (str/includes? href (js/encodeURIComponent "+214.6%")))))

(deftest post-url-encodes-both-halves
  (is (= (str "https://x.com/intent/post?text=" (js/encodeURIComponent "a b")
              "&url=" (js/encodeURIComponent "https://x.test/join/c"))
         (modal/post-url "a b" "https://x.test/join/c"))))

(def ^:private icon-uri "data:image/svg+xml;base64,PHN2Zy8+")

(deftest a-resolved-icon-reaches-the-card
  (let [with-icon (state {:pnl-share {:open? true :position position
                                      :icon {:key "SOL" :data-uri icon-uri}}})]
    (is (= icon-uri (:icon-data-uri (modal/card-for-state with-icon env))))
    (testing "and is drawn as an embedded image, not a URL the exporter cannot follow"
      (let [images (->> (tree-seq vector? seq (view with-icon))
                        (filter #(and (vector? %) (= :image (first %))))
                        vec)]
        (is (= 1 (count images)))
        (is (= icon-uri (:href (second (first images)))))))))

(deftest an-icon-resolved-for-a-different-coin-is-ignored
  (let [stale (state {:pnl-share {:open? true :position position
                                  :icon {:key "BTC" :data-uri icon-uri}}})]
    (is (nil? (:icon-data-uri (modal/card-for-state stale env)))
        "opening SOL after BTC must not paint BTC's icon")))

(deftest without-an-icon-the-card-falls-back-to-its-monogram
  (let [tree (view)]
    (is (nil? (:icon-data-uri (modal/card-for-state (state) env))))
    (is (empty? (->> (tree-seq vector? seq tree)
                     (filter #(and (vector? %) (= :image (first %)))))))
    (is (some #{"S"} (->> (tree-seq (some-fn vector? seq?) seq tree)
                          (filter string?))))))

(deftest the-card-data-comes-from-the-same-view-model-the-table-uses
  (let [card (modal/card-for-state (state) env)]
    (is (= "SOL" (:coin-label card)))
    (is (= "+214.6%" (:roe-text card)))
    (is (= "$142.08" (:entry-price-text card)))
    (is (= "hyperopen.xyz" (:join-label card)))))

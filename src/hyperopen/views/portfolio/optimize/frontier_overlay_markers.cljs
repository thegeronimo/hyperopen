(ns hyperopen.views.portfolio.optimize.frontier-overlay-markers
  (:require ["lucide/dist/esm/icons/layers-2.js" :default lucide-layers-2-node]
            [clojure.string :as str]
            [hyperopen.views.asset-icon :as asset-icon]
            [hyperopen.views.portfolio.optimize.frontier-callout :as frontier-callout]
            [hyperopen.views.portfolio.optimize.format :as opt-format]))

(def modes [:standalone :contribution :none])

(def ^:private standalone-color "#8f96a3")
(def ^:private contribution-color "#59a5c8")
(def ^:private vault-accent "#35d7c7")
(def ^:private vault-border "rgba(53, 215, 199, 0.72)")
(def ^:private vault-text "#8ffcf1")
(def ^:private vault-icon-bg "rgba(6, 18, 20, 0.92)")
(def ^:private vault-label-bg "rgba(9, 22, 24, 0.92)")
(def ^:private vault-icon-size 30)
(def ^:private vault-label-height 24)
(def ^:private vault-gap 5)
(def ^:private vault-label-padding-x 8)
(def ^:private vault-label-min-width 44)

(defn- lucide-node->hiccup
  [node]
  (let [tag-name (aget node 0)
        attrs (js->clj (aget node 1) :keywordize-keys true)]
    [(keyword tag-name) attrs]))

(defn- vault-layers-icon
  []
  (into [:svg {:x -12
               :y -12
               :width 24
               :height 24
               :viewBox "0 0 24 24"
               :fill "none"
               :stroke "currentColor"
               :stroke-width 1.75
               :stroke-linecap "round"
               :stroke-linejoin "round"
               :aria-hidden true
               :style {:color vault-text
                       :filter (str "drop-shadow(0 0 2px rgba(143, 252, 241, 0.78)) "
                                    "drop-shadow(0 0 8px rgba(53, 215, 199, 0.36))")}}]
        (map lucide-node->hiccup
             (array-seq lucide-layers-2-node))))

(defn normalize-mode
  [overlay-mode]
  (if (some #{overlay-mode} modes)
    overlay-mode
    :standalone))

(defn visible-points
  [result overlay-mode]
  (let [mode (normalize-mode overlay-mode)]
    (if (contains? #{:standalone :contribution} mode)
      (->> (get-in result [:frontier-overlays mode])
           (filter #(and (opt-format/finite-number? (:volatility %))
                         (opt-format/finite-number? (:expected-return %))))
           vec)
      [])))

(defn all-points
  [result]
  (->> [:standalone :contribution]
       (mapcat #(get-in result [:frontier-overlays %]))
       (filter #(and (opt-format/finite-number? (:volatility %))
                     (opt-format/finite-number? (:expected-return %))))
       vec))

(defn copy
  [overlay-mode]
  (case (normalize-mode overlay-mode)
    :contribution
    {:subtitle "Risk vs return — annualized frontier with contribution overlays"
     :x-axis-prefix "Vol"
     :y-axis-prefix "Ret"
     :reading-text "Frontier points stay on the same risk / return scale. Overlay markers show signed volatility contribution on x and return contribution on y for each selected asset."
     :legend-label "Signed contribution"}

    :standalone
    {:subtitle "Risk vs return — annualized frontier with standalone asset overlays"
     :x-axis-prefix "Vol"
     :y-axis-prefix "Ret"
     :reading-text "Frontier points are feasible portfolios. Overlay markers show each selected asset as its own standalone risk / return point."
     :legend-label "Standalone assets"}

    {:subtitle "Risk vs return — annualized"
     :x-axis-prefix "Vol"
     :y-axis-prefix "Ret"
     :reading-text "Each point is a feasible portfolio."
     :legend-label nil}))

(defn- overlay-label
  [point]
  (or (:label point) (:instrument-id point)))

(defn- vault-point?
  [point]
  (or (= :vault (:market-type point))
      (str/starts-with? (or (some-> (:instrument-id point) str) "") "vault:")))

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- base-symbol
  [value]
  (some-> value
          non-blank-text
          (str/replace #"^.*:" "")
          (str/split #"/|-" 2)
          first
          non-blank-text))

(defn- point-market
  [point]
  (let [instrument-id (non-blank-text (:instrument-id point))
        label (non-blank-text (:label point))
        [kind raw-coin] (when instrument-id
                          (str/split instrument-id #":" 2))
        coin (or (non-blank-text raw-coin)
                 label)
        base (or (base-symbol coin)
                 (base-symbol label))
        market-type (case kind
                      "spot" :spot
                      "perp" :perp
                      nil)]
    {:key instrument-id
     :coin coin
     :symbol (or (when (= :spot market-type) coin)
                 (when (and base (not= coin base))
                   (str base "-USDC"))
                 base)
     :base base
     :market-type market-type}))

(defn- ticker-like-token?
  [token]
  (boolean (re-matches #"[A-Z0-9]{2,6}" token)))

(def ^:private generic-vault-words
  #{"VAULT" "POOL" "FUND" "STRATEGY"})

(def ^:private known-vault-short-codes
  {"hyperliquidity provider" "HLP"
   "hyperliquidity provider (hlp)" "HLP"})

(def ^:private vowels
  #{\A \E \I \O \U})

(defn- short-code-value
  [value]
  (let [text (non-blank-text value)]
    (when (and text
               (re-matches #"[A-Za-z0-9]{2,6}" text))
      (str/upper-case text))))

(defn- first-abbreviation-match
  [pattern text]
  (some->> (re-seq pattern text)
           (map second)
           (filter seq)
           first
           str/upper-case))

(defn- explicit-three-letter-abbreviation
  [value]
  (when-let [text (non-blank-text value)]
    (first-abbreviation-match #"\(([A-Za-z]{3})\)" text)))

(defn- known-vault-short-code
  [value]
  (when-let [text (non-blank-text value)]
    (get known-vault-short-codes (str/lower-case text))))

(defn- padded-code
  [code]
  (let [code* (str/upper-case (or code ""))]
    (subs (str code* "VLT") 0 3)))

(defn- compact-token-code
  [token]
  (let [token* (str/upper-case (or token ""))
        first-char (first token*)
        consonants (->> (rest token*)
                        (remove vowels)
                        (apply str))
        skeleton (str first-char consonants)]
    (padded-code
     (if (>= (count skeleton) 3)
       skeleton
       token*))))

(defn- vault-short-code
  [point]
  (or (some->> [(:abbreviation point)
                (:short-name point)
                (:ticker point)
                (:symbol point)]
               (keep short-code-value)
               first)
      (explicit-three-letter-abbreviation (overlay-label point))
      (known-vault-short-code (overlay-label point))
      (let [tokens (->> (or (overlay-label point) "")
                        (re-seq #"[A-Za-z0-9]+")
                        (map str/upper-case)
                        vec)
            tokens* (if (and (> (count tokens) 3)
                             (ticker-like-token? (first tokens)))
                      (subvec tokens 1)
                      tokens)
            code (cond
                   (>= (count tokens*) 3)
                   (->> tokens*
                        (take 3)
                        (map #(subs % 0 1))
                        (apply str))

                   (= 2 (count tokens*))
                   (let [[first-token second-token] tokens*]
                     (if (generic-vault-words second-token)
                       (compact-token-code first-token)
                       (str (subs first-token 0 1)
                            (subs (compact-token-code second-token) 0 2))))

                   (= 1 (count tokens*))
                   (compact-token-code (first tokens*))

                   :else "VAULT")]
        (padded-code code))))

(defn- marker-color
  [point default-color]
  (if (vault-point? point)
    vault-accent
    default-color))

(defn- vault-marker-layout
  [point]
  (let [code (vault-short-code point)
        label-width (max vault-label-min-width
                         (+ (* 8 (count code))
                            (* 2 vault-label-padding-x)))
        icon-half (/ vault-icon-size 2)
        label-half (/ vault-label-height 2)
        label-x (+ icon-half vault-gap)]
    {:code code
     :icon-half icon-half
     :label-half label-half
     :label-x label-x
     :label-width label-width
     :full-width (+ vault-icon-size vault-gap label-width)}))

(defn- marker-shell-attrs
  ([data-role label rows]
   (marker-shell-attrs data-role label rows nil))
  ([data-role label rows color]
  {:data-role data-role
   :role "img"
   :tabIndex 0
   :tabindex 0
   :focusable "true"
   :class ["portfolio-frontier-marker" "outline-none"]
   :aria-label (frontier-callout/aria-label label rows)
   :style (when color {:color color})}))

(defn- symbol-marker
  [data-role x y label color]
  [:text {:x x
          :y (+ y 3)
          :fill color
          :fontSize 9
          :fontWeight 700
          :text-anchor "middle"
          :class "portfolio-frontier-symbol-marker"
          :data-role data-role}
   label])

(defn- asset-marker
  [data-role x y point color]
  (let [label (overlay-label point)]
    (if (vault-point? point)
      (let [{:keys [code icon-half label-half label-x label-width]}
            (vault-marker-layout point)]
        [:g {:data-role data-role
             :class ["portfolio-frontier-asset-icon-marker"
                     "portfolio-frontier-vault-marker"]
             :transform (str "translate(" x " " y ")")}
         [:g {:data-role (str/replace data-role
                                      "portfolio-optimizer-frontier-overlay-symbol"
                                      "portfolio-optimizer-frontier-vault-icon")}
          [:rect {:x (- icon-half)
                  :y (- icon-half)
                  :width vault-icon-size
                  :height vault-icon-size
                  :rx 6
                  :class "portfolio-frontier-vault-box"
                  :fill vault-icon-bg
                  :stroke vault-border
                  :strokeWidth 1
                  :style {:filter "drop-shadow(0 0 7px rgba(53, 215, 199, 0.26))"}}]
          (vault-layers-icon)]
         [:rect {:x label-x
                 :y (- label-half)
                 :width label-width
                 :height vault-label-height
                 :rx 5
                 :class "portfolio-frontier-vault-box"
                 :fill vault-label-bg
                 :stroke vault-border
                 :strokeWidth 1
                 :style {:filter "drop-shadow(0 0 5px rgba(53, 215, 199, 0.16))"}}]
         [:text {:x (+ label-x (/ label-width 2))
                 :y 0
                 :fill vault-text
                 :fontSize 13
                 :fontWeight 600
                 :letterSpacing "0.035em"
                 :dominantBaseline "middle"
                 :text-anchor "middle"
                 :data-role (str/replace data-role
                                         "portfolio-optimizer-frontier-overlay-symbol"
                                         "portfolio-optimizer-frontier-vault-code")}
          code]])
      (let [icon-url (asset-icon/market-icon-url (point-market point))]
        (if (seq icon-url)
          [:g {:data-role data-role
               :class "portfolio-frontier-asset-icon-marker"}
           [:circle {:cx x
                     :cy y
                     :r 9
                     :fill "var(--optimizer-surface)"
                     :stroke color
                     :strokeWidth 1
                     :opacity 0.9}]
           [:image {:x (- x 7)
                    :y (- y 7)
                    :width 14
                    :height 14
                    :href icon-url
                    :preserveAspectRatio "xMidYMid meet"
                    :aria-hidden true}]]
          (symbol-marker data-role x y label color))))))

(defn- overlay-hitbox
  [data-role x y point]
  (if (vault-point? point)
    (let [{:keys [icon-half full-width]} (vault-marker-layout point)]
      [:rect {:x (- x icon-half)
              :y (- y icon-half)
              :width full-width
              :height vault-icon-size
              :rx 6
              :fill "transparent"
              :stroke "transparent"
              :pointerEvents "all"
              :data-role data-role}])
    (frontier-callout/hitbox data-role x y 16)))

(defn- standalone-point-model
  [{:keys [point-position x-domain y-domain point]}]
  (let [position (point-position x-domain y-domain point)
        {:keys [x y]} position
        label (overlay-label point)
        rows (frontier-callout/point-rows
              point
              {:target-weight (:target-weight point)})]
    {:position position
     :x x
     :y y
     :label label
     :rows rows}))

(defn- standalone-callout
  [{:keys [bounds point] :as opts}]
  (let [{:keys [position label rows]} (standalone-point-model opts)]
    (frontier-callout/callout
     {:bounds bounds
      :data-role (str "portfolio-optimizer-frontier-callout-standalone-"
                      (:instrument-id point))
      :label label
      :point position
      :rows rows})))

(defn- standalone-marker
  [{:keys [point render-callout?] :as opts}]
  (let [{:keys [x y label rows]} (standalone-point-model opts)]
    [:g (marker-shell-attrs
         (str "portfolio-optimizer-frontier-overlay-standalone-"
              (:instrument-id point))
         label
         rows
         (marker-color point standalone-color))
     (asset-marker
      (str "portfolio-optimizer-frontier-overlay-symbol-standalone-"
           (:instrument-id point))
      x
      y
      point
      (marker-color point standalone-color))
     (when-not (vault-point? point)
       (frontier-callout/focus-ring x y 15))
     (overlay-hitbox
      (str "portfolio-optimizer-frontier-overlay-standalone-"
           (:instrument-id point)
           "-hitbox")
      x
      y
      point)
     (when-not (false? render-callout?)
       (standalone-callout opts))]))

(defn- contribution-point-model
  [{:keys [point-position x-domain y-domain point]}]
  (let [position (point-position x-domain y-domain point)
        {:keys [x y]} position
        label (overlay-label point)
        rows (frontier-callout/point-rows
              point
              {:return-label "Return Contribution"
               :volatility-label "Volatility Contribution"
               :target-weight (:target-weight point)})]
    {:position position
     :x x
     :y y
     :label label
     :rows rows}))

(defn- contribution-callout
  [{:keys [bounds point] :as opts}]
  (let [{:keys [position label rows]} (contribution-point-model opts)]
    (frontier-callout/callout
     {:bounds bounds
      :data-role (str "portfolio-optimizer-frontier-callout-contribution-"
                      (:instrument-id point))
      :label label
      :point position
      :rows rows})))

(defn- contribution-marker
  [{:keys [point render-callout?] :as opts}]
  (let [{:keys [x y label rows]} (contribution-point-model opts)]
    [:g (marker-shell-attrs
         (str "portfolio-optimizer-frontier-overlay-contribution-"
              (:instrument-id point))
         label
         rows
         (marker-color point contribution-color))
     (asset-marker
      (str "portfolio-optimizer-frontier-overlay-symbol-contribution-"
           (:instrument-id point))
      x
      y
      point
      (marker-color point contribution-color))
     (when-not (vault-point? point)
       (frontier-callout/focus-ring x y 15))
     (overlay-hitbox
      (str "portfolio-optimizer-frontier-overlay-contribution-"
           (:instrument-id point)
           "-hitbox")
      x
      y
      point)
     (when-not (false? render-callout?)
       (contribution-callout opts))]))

(defn marker
  [{:keys [overlay-mode] :as opts}]
  (case (normalize-mode overlay-mode)
    :contribution (contribution-marker opts)
    :standalone (standalone-marker opts)
    nil))

(defn callout
  [{:keys [overlay-mode] :as opts}]
  (case (normalize-mode overlay-mode)
    :contribution (contribution-callout opts)
    :standalone (standalone-callout opts)
    nil))

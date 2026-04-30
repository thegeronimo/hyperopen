(ns hyperopen.views.portfolio.optimize.frontier-overlay-markers
  (:require [clojure.string :as str]
            [hyperopen.views.asset-icon :as asset-icon]
            [hyperopen.views.portfolio.optimize.frontier-callout :as frontier-callout]
            [hyperopen.views.portfolio.optimize.format :as opt-format]))

(def modes [:standalone :contribution :none])

(def ^:private standalone-color "#8f96a3")
(def ^:private contribution-color "#59a5c8")
(def ^:private vault-color "#59a5c8")

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

(def ^:private vowels
  #{\A \E \I \O \U})

(defn- short-code-value
  [value]
  (let [text (non-blank-text value)]
    (when (and text
               (re-matches #"[A-Za-z0-9]{2,6}" text))
      (str/upper-case text))))

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
    vault-color
    default-color))

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
      (let [code (vault-short-code point)
            pill-width (max 18 (+ 10 (* 5 (count code))))
            pill-x (+ x 8)
            pill-y (- y 7)]
        [:g {:data-role data-role
             :class "portfolio-frontier-asset-icon-marker"}
         [:g {:data-role (str/replace data-role
                                      "portfolio-optimizer-frontier-overlay-symbol"
                                      "portfolio-optimizer-frontier-vault-icon")}
          [:rect {:x (- x 10)
                  :y (- y 10)
                  :width 20
                  :height 20
                  :rx 6
                  :fill "rgba(10, 19, 29, 0.92)"
                  :stroke color
                  :strokeWidth 1.2}]
          [:path {:d (str "M" (- x 4) " " (- y 3)
                          "L" x " " (- y 6)
                          "L" (+ x 4) " " (- y 3)
                          "L" x " " y
                          "Z")
                  :fill "none"
                  :stroke color
                  :strokeWidth 1.1
                  :strokeLinejoin "round"
                  :strokeLinecap "round"}]
          [:path {:d (str "M" (- x 5) " " y
                          "L" (- x 1) " " (- y 3)
                          "L" (+ x 3) " " y
                          "L" (- x 1) " " (+ y 3)
                          "Z")
                  :fill "none"
                  :stroke color
                  :strokeWidth 1.1
                  :strokeLinejoin "round"
                  :strokeLinecap "round"}]
          [:path {:d (str "M" (- x 2) " " (+ y 3)
                          "L" (+ x 2) " " y
                          "L" (+ x 6) " " (+ y 3)
                          "L" (+ x 2) " " (+ y 6)
                          "Z")
                  :fill "none"
                  :stroke color
                  :strokeWidth 1.1
                  :strokeLinejoin "round"
                  :strokeLinecap "round"}]]
         [:rect {:x pill-x
                 :y pill-y
                 :width pill-width
                 :height 14
                 :rx 7
                 :fill "rgba(10, 19, 29, 0.92)"
                 :stroke color
                 :strokeWidth 1
                 :opacity 0.96}]
         [:text {:x (+ pill-x (/ pill-width 2))
                 :y (+ y 3)
                 :fill color
                 :fontSize 8
                 :fontWeight 700
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

(defn- standalone-marker
  [{:keys [bounds point-position x-domain y-domain point]}]
  (let [position (point-position x-domain y-domain point)
        {:keys [x y]} position
        label (overlay-label point)
        rows (frontier-callout/point-rows
              point
              {:target-weight (:target-weight point)})]
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
     (frontier-callout/focus-ring x y 15)
     (frontier-callout/hitbox
      (str "portfolio-optimizer-frontier-overlay-standalone-"
           (:instrument-id point)
           "-hitbox")
      x
      y
      16)
     (frontier-callout/callout
      {:bounds bounds
       :data-role (str "portfolio-optimizer-frontier-callout-standalone-"
                       (:instrument-id point))
       :label label
       :point position
       :rows rows})]))

(defn- contribution-marker
  [{:keys [bounds point-position x-domain y-domain point]}]
  (let [position (point-position x-domain y-domain point)
        {:keys [x y]} position
        label (overlay-label point)
        rows (frontier-callout/point-rows
              point
              {:return-label "Return Contribution"
               :volatility-label "Volatility Contribution"
               :target-weight (:target-weight point)})]
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
     (frontier-callout/focus-ring x y 15)
     (frontier-callout/hitbox
      (str "portfolio-optimizer-frontier-overlay-contribution-"
           (:instrument-id point)
           "-hitbox")
      x
      y
      16)
     (frontier-callout/callout
      {:bounds bounds
       :data-role (str "portfolio-optimizer-frontier-callout-contribution-"
                       (:instrument-id point))
       :label label
       :point position
       :rows rows})]))

(defn marker
  [{:keys [overlay-mode] :as opts}]
  (case (normalize-mode overlay-mode)
    :contribution (contribution-marker opts)
    :standalone (standalone-marker opts)
    nil))

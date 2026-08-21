(ns hyperopen.views.pnl-share.palette
  "Resolves --ho-* theme tokens into concrete colour strings for the share card.

   The card is rasterized inside a sandboxed document that has no access to the
   page's :root, so var(--ho-buy) resolves to nothing there. Every colour on a
   card must already be a literal string by the time the hiccup is built. This
   namespace is the single place raw colour literals are allowed to live; it is
   modelled on hyperopen.views.trading-chart.utils.theme-colors, including the
   per-data-theme cache and the DOM-less fallbacks the node test suite needs.")

(def channel-fallbacks
  "Default (dark) theme channel triplets, used when there is no document."
  {"--ho-bg-deep" [6 19 26]
   "--ho-surface" [27 36 41]
   "--ho-border" [48 54 61]
   "--ho-text" [246 254 253]
   "--ho-text-hi" [255 255 255]
   "--ho-text-secondary" [139 148 158]
   "--ho-text-dim" [111 122 136]
   "--ho-accent" [80 210 193]
   "--ho-accent-bright" [151 252 228]
   "--ho-buy" [0 212 170]
   "--ho-sell" [255 107 107]
   "--ho-sell-tint" [242 184 197]
   "--ho-warn" [251 189 35]})

(def ^:private monogram-gradient-pairs
  "Deterministic monogram disc gradients. Index is a hash of the base symbol, so
   the same coin always gets the same disc across sessions and templates."
  [[[153 69 255] [20 241 149]]
   [[255 183 94] [237 143 3]]
   [[98 168 255] [40 84 214]]
   [[255 107 158] [214 40 108]]
   [[122 226 168] [26 148 122]]
   [[186 148 255] [104 62 214]]
   [[255 214 102] [214 154 20]]
   [[112 214 214] [26 118 148]]])

(defonce ^:private cache
  (atom {:theme ::none :values {}}))

(defn- current-theme
  []
  (when (exists? js/document)
    (some-> (.-documentElement js/document) .-dataset .-theme)))

(defn- parse-channels
  [raw]
  (when (seq raw)
    (let [parts (-> raw (.trim) (.split #"[\s,/]+"))
          nums (keep (fn [part]
                       (let [n (js/parseFloat part)]
                         (when (js/isFinite n) n)))
                     (array-seq parts))]
      (when (<= 3 (count nums))
        (vec (take 3 nums))))))

(defn- read-channels
  [token-name]
  (when (and (exists? js/document)
             (exists? js/getComputedStyle))
    (try
      (-> (js/getComputedStyle (.-documentElement js/document))
          (.getPropertyValue token-name)
          parse-channels)
      (catch :default _
        nil))))

(defn channels
  "Returns the active theme's [r g b] channel triplet for a --ho-* token."
  [token-name]
  (let [theme (current-theme)
        state @cache
        cached (when (= theme (:theme state))
                 (get (:values state) token-name))]
    (or cached
        (let [value (or (read-channels token-name)
                        (get channel-fallbacks token-name)
                        [0 0 0])]
          (swap! cache (fn [state]
                         (if (= theme (:theme state))
                           (assoc-in state [:values token-name] value)
                           {:theme theme :values {token-name value}})))
          value))))

(defn- clamp-channel
  [value]
  (-> value (max 0) (min 255) js/Math.round))

(defn rgb
  "Renders a channel triplet as an opaque CSS colour."
  [[r g b]]
  (str "rgb(" (clamp-channel r) "," (clamp-channel g) "," (clamp-channel b) ")"))

(defn rgba
  "Renders a channel triplet at a given alpha."
  [[r g b] alpha]
  (str "rgba(" (clamp-channel r) "," (clamp-channel g) "," (clamp-channel b) "," alpha ")"))

(defn mix
  "Blends two channel triplets; t of 0 returns `from`, 1 returns `to`."
  [from to t]
  (let [t* (-> t (max 0) (min 1))]
    (mapv (fn [a b] (+ a (* t* (- b a)))) from to)))

(def ^:private black [0 0 0])
(def ^:private white [255 255 255])

(defn monogram-gradient
  "Deterministic [from to] channel pair for a coin's monogram disc."
  [symbol-text]
  (let [text (or symbol-text "")
        ;; A rolling hash rather than a character sum: plain addition collides
        ;; on anagrams and on symbols of similar length, which put SOL and AMZN
        ;; on the same disc.
        hashed (reduce (fn [acc ch]
                         (mod (+ (* acc 31) (.charCodeAt ch 0)) 100003))
                       7
                       (seq text))
        index (mod hashed (count monogram-gradient-pairs))]
    (nth monogram-gradient-pairs index)))

(defn card-palette
  "Concrete colours for one card, keyed by role.

   `winning?` selects the accent family: the buy/accent tokens for a gain, the
   sell tokens for a loss. Both families are derived from live theme tokens, so
   a card exported under HyperDegen picks up that theme's greens and reds."
  [winning?]
  (let [accent (channels (if winning? "--ho-buy" "--ho-sell"))
        bright (channels (if winning? "--ho-accent-bright" "--ho-sell-tint"))
        deep (mix accent black 0.72)
        plate (mix accent black 0.94)]
    {:plate (rgb plate)
     :plate-wash-near (rgba accent 0.16)
     :plate-wash-far (rgba accent 0)
     :plate-wash-corner (rgba accent 0.06)
     :contour (rgba accent 0.2)
     :contour-faint (rgba accent 0.12)
     :card-border (rgba accent 0.18)
     :arrow-fill-low (rgba accent 0.05)
     :arrow-fill-mid (rgba accent 0.18)
     :arrow-fill-high (rgba bright 0.32)
     :arrow-edge-low (rgba deep 0.4)
     :arrow-edge-mid (rgb accent)
     :arrow-edge-high (rgb bright)
     :arrow-glow (rgba accent 0.5)
     :arrow-spark (rgba bright 0.5)
     :hero-high (rgb bright)
     :hero-mid (rgb accent)
     :hero-low (rgb (mix accent black 0.35))
     :hero-label (rgb (mix accent (channels "--ho-text-secondary") 0.72))
     :chip-bg (rgba accent 0.1)
     :chip-border (rgba accent 0.34)
     :chip-text (rgb (mix accent white 0.25))
     :frame-border (rgba accent 0.28)
     :icon (rgb accent)
     :accent (rgb accent)
     :accent-bright (rgb bright)
     :divider (rgba white 0.09)
     :divider-strong (rgba white 0.12)
     :text-hi (rgb (channels "--ho-text-hi"))
     :text-body (rgb (mix (channels "--ho-text") accent 0.18))
     :text-mid (rgb (mix (channels "--ho-text-secondary") accent 0.2))
     :text-dim (rgba (channels "--ho-text-secondary") 0.8)
     :disc-rim (rgba white 0.14)
     :disc-letter (rgba black 0.82)
     :loss-pill-border (rgba accent 0.45)
     :loss-pill-text (rgb (mix accent white 0.2))}))

(defn token-palette
  "Token-only colours for the Number as hero template, which draws no artwork
   and therefore stays inside the plain --ho-* vocabulary."
  [winning?]
  (let [accent (channels (if winning? "--ho-buy" "--ho-sell"))
        bright (channels (if winning? "--ho-accent-bright" "--ho-sell-tint"))]
    {:plate (rgb (channels "--ho-bg-deep"))
     :dot-field (rgba accent 0.16)
     :dot-wash (rgba accent 0.1)
     :dot-wash-far (rgba accent 0)
     :card-border (rgb (channels "--ho-border"))
     :divider (rgb (channels "--ho-border"))
     :accent (rgb accent)
     :accent-bright (rgb bright)
     :chip-bg (rgba accent 0.12)
     :chip-border (rgba accent 0.32)
     :text-hi (rgb (channels "--ho-text-hi"))
     :text-body (rgb (channels "--ho-text"))
     :text-mid (rgb (channels "--ho-text-secondary"))
     :text-dim (rgb (channels "--ho-text-dim"))
     :disc-rim (rgba white 0.14)
     :disc-letter (rgba black 0.82)}))

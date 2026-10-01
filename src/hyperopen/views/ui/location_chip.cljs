(ns hyperopen.views.ui.location-chip
  "A small chip naming where funds sit: Perps or Spot on HyperCore, or
   HyperEVM. The text always names the place, so meaning never rests on
   color alone.")

(def ^:private chip-text
  {:perps "Perps"
   :spot "Spot"
   :hyperevm "EVM"})

(def ^:private tone-classes
  ;; Each tone clears WCAG AA (4.5:1) for its 12 px text in every theme;
  ;; the PERPS text is 80% of the body text (the secondary grey was 4.38:1
  ;; on the raised surface).
  {:perps ["bg-ho-surface-raised" "text-ho-text/80"]
   :spot ["bg-ho-accent-soft" "text-ho-accent-bright"]
   :hyperevm ["bg-ho-info/15" "text-ho-info"]})

(def ^:private base-classes
  ["inline-flex"
   "shrink-0"
   "items-center"
   "rounded"
   "px-1.5"
   "py-0.5"
   "text-xs"
   "font-semibold"
   "leading-none"
   "uppercase"
   "tracking-wide"])

(defn location-chip
  "The chip for `location` (`:perps`, `:spot` or `:hyperevm`), or nil for
   anything else."
  [location]
  (when-let [text (get chip-text location)]
    [:span {:class (into base-classes (get tone-classes location))
            :data-role (str "location-chip-" (name location))}
     text]))

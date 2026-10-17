(ns hyperopen.views.portfolio.optimize.vault-funding-split
  "The funding sheet's \"Split the money by\" choice: optimizer target or
  equal weight, each card summarizing what it would do."
  (:require [hyperopen.portfolio.routes :as portfolio-routes]))

(defn- pct
  [x]
  (str (.toFixed (* 100 (or x 0)) 1) "%"))

(defn- field
  [f v]
  [[:actions/set-portfolio-optimizer-vault-funding-field f v]])

(defn- split-card
  [{:keys [split selected? title sub summary]}]
  (let [{:keys [weights withdrawals deposits drift-after]} summary]
    [:button {:type "button"
              :role "radio"
              :aria-checked (boolean selected?)
              :class (into ["flex" "min-h-11" "flex-col" "gap-1.5" "rounded-xl" "border" "px-4" "py-3" "text-left"]
                           (if selected?
                             ["border-ho-accent-bright/70" "bg-ho-accent-bright/10"]
                             ["border-base-300" "hover:bg-base-200/40"]))
              :data-role (str "portfolio-optimizer-vault-funding-split-" (name split))
              :on {:click (field :split split)}}
     [:span {:class ["flex" "items-center" "gap-2.5" "text-sm" "font-medium" "text-trading-text"]}
      [:span {:class (into ["h-3.5" "w-3.5" "shrink-0" "rounded-full" "border"]
                           (if selected? ["border-[4px]" "border-ho-accent-bright"] ["border-trading-muted"]))}]
      title]
     [:span {:class ["pl-6" "text-xs" "leading-5" "text-trading-muted"]} sub]
     (into [:span {:class ["ml-6" "flex" "h-1.5" "gap-0.5" "overflow-hidden" "rounded-full"] :aria-hidden true}]
           (map-indexed (fn [i w]
                          [:span {:class ["bg-ho-accent-bright"]
                                  :style {:flex-grow w :flex-basis 0 :opacity (- 1 (* 0.07 (mod i 8)))}}]))
           (filter pos? weights))
     [:span {:class ["num" "pl-6" "text-xs" "text-trading-muted"]}
      (str (when (pos? (or withdrawals 0)) (str withdrawals (if (= 1 withdrawals) " withdrawal · " " withdrawals · ")))
           deposits (if (= 1 deposits) " deposit · " " deposits · ")
           (pct drift-after) " off target after")]]))

(defn- tear-sheet-link
  "Compare both splits on the hypothetical tear sheet (it charts Equal weight
  against the target); hidden when the sheet is already open over it."
  [state]
  (let [{:keys [kind view scenario-id]} (portfolio-routes/parse-portfolio-route (get-in state [:router :path]))]
    (if (and (= :optimize-scenario kind) scenario-id (not= :tear-sheet view))
      [:button {:type "button"
                :class ["self-start" "min-h-8" "text-sm" "text-ho-accent-bright" "hover:underline"]
                :data-role "portfolio-optimizer-vault-funding-compare"
                :on {:click [[:actions/portfolio-optimizer-vault-funding-command :close]
                             [:actions/navigate (portfolio-routes/portfolio-optimize-tear-sheet-path scenario-id)]]}}
       "Compare both on the hypothetical tear sheet ›"]
      [:span {:class ["hidden"]}])))

(defn split-picker
  [{:keys [options splits]} state]
  (let [n (get-in splits [:equal :vaults] 0)]
    [:section {:class ["flex" "flex-col" "gap-2" "border-b" "border-base-300" "px-5" "py-4"]}
     [:span {:id "vault-funding-split" :class ["text-sm" "text-trading-muted"]} "Split the money by"]
     [:div {:role "radiogroup" :aria-labelledby "vault-funding-split" :class ["grid" "gap-3" "md:grid-cols-2"]}
      (split-card {:split :target
                   :selected? (= :target (:split options))
                   :title "Optimizer target"
                   :sub "The weights from this run."
                   :summary (:target splits)})
      (split-card {:split :equal
                   :selected? (= :equal (:split options))
                   :title "Equal weight"
                   :sub (str "The same share in each of the " n " vaults the run picked ("
                             (pct (if (pos? n) (/ 1 n) 0)) " each).")
                   :summary (:equal splits)})]
     (tear-sheet-link state)]))

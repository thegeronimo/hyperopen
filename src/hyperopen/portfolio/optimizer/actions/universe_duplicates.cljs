(ns hyperopen.portfolio.optimizer.actions.universe-duplicates
  "The same-stock confirm on the Universe panel's add.

  A plain add of a listing whose underlying is already in the mix (NVDA-USDH
  when NVDA-USDC is held) does not add: it opens an inline confirm beside the
  row. The confirm answers with a choice:
    swap - replace the held listing, keeping its place and side (the default);
    both - add it anyway, and stop asking for this underlying this session;
    keep - close the confirm, change nothing.

  Split out of actions.universe (size cap). The universe add/remove mutations
  stay there and arrive here as `deps`:
    {:add     (fn [state market-key]) - the unguarded single add
     :remove  (fn [state instrument-id])
     :project (fn [state effects]) - state after the effects' saves
     :side    (fn [instrument side]) - the side the instrument can take}"
  (:require [hyperopen.portfolio.optimizer.actions.common :as common]
            [hyperopen.portfolio.optimizer.application.universe-candidates :as universe-candidates]
            [hyperopen.portfolio.optimizer.application.universe-duplicates :as universe-duplicates]
            [hyperopen.portfolio.optimizer.contracts :as contracts]))

(def ^:private close-prompt
  [contracts/ui-universe-duplicate-prompt-path nil])

(defn- dedupe-last-write
  "One entry per path, at its LAST write, so a merged save applies exactly as
  the sequence of saves would have."
  [path-values]
  (->> path-values
       reverse
       (reduce (fn [{:keys [seen] :as acc} [path :as pv]]
                 (if (contains? seen path)
                   acc
                   (-> acc
                       (update :seen conj path)
                       (update :items conj pv))))
               {:seen #{} :items '()})
       :items))

(defn merge-save-effects
  "Fold every :effects/save-many into one (dirty flag last) and keep the other
  effects after it, in order."
  [effects]
  (let [saves (filter #(= :effects/save-many (first %)) effects)
        others (remove #(= :effects/save-many (first %)) effects)
        path-values (->> saves
                         (mapcat second)
                         (remove #(= contracts/draft-dirty-path (first %)))
                         dedupe-last-write)]
    (into (common/save-draft-path-values path-values) others)))

(defn- with-path-values
  [effects path-values]
  (if (seq effects)
    (merge-save-effects (into [[:effects/save-many (vec path-values)]] effects))
    effects))

(defn- swap-in
  "Replace `existing` with the market's listing, in the existing row's place and
  on the existing row's side (Long when the new listing cannot be shorted)."
  [{:keys [add remove project side]} state existing market-key]
  (let [existing-id (:instrument-id existing)
        position (first (keep-indexed #(when (= existing-id (:instrument-id %2)) %1)
                                      (common/draft-universe state)))
        remove-effects (remove state existing-id)
        state* (project state remove-effects)
        add-effects (add state* market-key)]
    (if (empty? add-effects)
      []
      (let [universe* (common/draft-universe (project state* add-effects))
            added (peek universe*)
            added* (assoc added :position-side (side added (:position-side existing)))
            others (pop universe*)
            reordered (vec (concat (take position others) [added*] (drop position others)))]
        (merge-save-effects
         (concat remove-effects
                 add-effects
                 [[:effects/save-many [[contracts/draft-universe-path reordered]
                                       close-prompt]]]))))))

(defn- acknowledge
  [state underlying]
  (vec (distinct (conj (vec (get-in state contracts/ui-universe-duplicate-acknowledged-path))
                       underlying))))

(defn guarded-add
  [{:keys [add] :as deps} state market-key choice]
  (let [market-key* (common/non-blank-text market-key)
        universe (common/draft-universe state)
        market (universe-candidates/resolve-market state market-key*)]
    (case (some-> choice common/normalize-keyword-like)
      :keep
      [[:effects/save contracts/ui-universe-duplicate-prompt-path nil]]

      :both
      (with-path-values (add state market-key*)
        [close-prompt
         [contracts/ui-universe-duplicate-acknowledged-path
          (acknowledge state (universe-duplicates/underlying-key market))]])

      :swap
      (if-let [sibling (when market
                         (universe-duplicates/existing-sibling
                          (universe-duplicates/universe-index universe)
                          market))]
        (swap-in deps state sibling market-key*)
        (add state market-key*))

      (cond
        (and market (universe-duplicates/needs-confirm? state universe market))
        [[:effects/save contracts/ui-universe-duplicate-prompt-path market-key*]]

        ;; A different row added while a confirm is open retires that confirm.
        (universe-duplicates/prompt-market-key state)
        (with-path-values (add state market-key*) [close-prompt])

        :else
        (add state market-key*)))))

(defn handle-keydown
  "Panel keyboard. While a confirm is open, Enter takes its default (swap) and
  Escape keeps the current listing; moving the cursor closes it, so Enter can
  never act on a row other than the one highlighted. `keyboard-effects` is the
  ordinary search keyboard, called when no confirm is open (or to move)."
  [add-instrument keyboard-effects state key]
  (if-let [prompt-key (universe-duplicates/prompt-market-key state)]
    (case key
      "Enter" (add-instrument state prompt-key "swap")
      "Escape" (add-instrument state prompt-key "keep")
      (let [effects (keyboard-effects)]
        (if (seq effects)
          (conj (vec effects) [:effects/save contracts/ui-universe-duplicate-prompt-path nil])
          effects)))
    (keyboard-effects)))

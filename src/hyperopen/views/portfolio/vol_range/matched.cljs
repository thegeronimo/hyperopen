(ns hyperopen.views.portfolio.vol-range.matched
  "The Gross spread on matched fills disclosure of the Vol Range report
  (HO-SPEC-014 v2.1 section 12.5): a secondary diagnostic under the
  headline, collapsed by default. Each shown market lists the matched
  quantity and pairs, the spread, the unmatched marks, the fee and rebate
  impact and its reconciliation to Ladder contribution versus hold, then
  the fees actually paid by token. Renders the view model only.")

(defn- value-classes [tone]
  (case tone :positive ["text-success"] :negative ["text-error"] ["text-ho-text"]))

(defn- row-view [{:keys [id label value]}]
  [:div {:class (cond-> ["flex" "flex-wrap" "items-baseline" "justify-between" "gap-x-3" "gap-y-0.5" "py-1"]
                  (= :total id) (into ["border-t" "border-ho-border" "font-semibold"]))
         :data-role (str "vol-range-report-matched-" (name id))}
   [:dt {:class ["text-ho-text-secondary"]} label]
   (when value
     [:dd {:class (into ["num"] (value-classes (:tone value)))
           :data-role (str "vol-range-report-matched-" (name id) "-value")}
      (:display value)])])

(defn matched-disclosure
  [{:keys [open? title note markets] :as matched}]
  (when matched
    [:details {:class ["rounded-md" "border" "border-ho-border" "p-3" "text-xs" "text-ho-text"]
               :open (boolean open?)
               :data-role "vol-range-report-matched"}
     [:summary {:class ["cursor-pointer" "text-sm" "font-semibold" "text-ho-text" "focus:outline-none"
                        "focus-visible:ring-2" "focus-visible:ring-ho-text"]
                :data-role "vol-range-report-matched-summary"
                :on {:click [[:actions/set-vol-range-report-ui-flag :matched-open? (not open?)]]}}
      title]
     [:div {:class ["mt-2" "flex" "flex-col" "gap-3"]}
      (for [{:keys [campaign-id market rows fees-paid]} markets]
        ^{:key campaign-id}
        [:section {:class ["flex" "flex-col"] :data-role "vol-range-report-matched-market"
                   :data-campaign-id campaign-id}
         (when (< 1 (count markets))
           [:h4 {:class ["font-semibold" "text-ho-text"]} market])
         [:dl {:class ["flex" "flex-col"]}
          (for [r rows] ^{:key (name (:id r))} (row-view r))]
         [:p {:class ["pt-1" "text-ho-text-secondary"] :data-role "vol-range-report-matched-fees-paid"} fees-paid]])
      [:p {:class ["text-ho-text-muted"] :data-role "vol-range-report-matched-note"} note]]]))

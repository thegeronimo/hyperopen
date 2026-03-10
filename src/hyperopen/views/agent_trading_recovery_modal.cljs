(ns hyperopen.views.agent-trading-recovery-modal
  (:require [clojure.string :as str]))

(def ^:private idle-description
  "Hyperliquid no longer recognizes this trading setup. Re-enable trading to continue placing orders.")

(def ^:private approving-description
  "Approve the wallet signature request to restore trading.")

(defn- close-icon []
  [:svg {:viewBox "0 0 20 20"
         :class ["h-4" "w-4"]
         :fill "none"
         :stroke "currentColor"
         :stroke-width "1.8"
         :stroke-linecap "round"
         :aria-hidden "true"}
   [:path {:d "M5 5 15 15"}]
   [:path {:d "M15 5 5 15"}]])

(defn agent-trading-recovery-modal-view
  [state]
  (let [agent-state (get-in state [:wallet :agent] {})
        open? (true? (:recovery-modal-open? agent-state))
        approving? (= :approving (:status agent-state))
        error-text (some-> (:error agent-state) str str/trim not-empty)]
    (when open?
      [:div {:class ["fixed"
                     "inset-0"
                     "z-[295]"
                     "flex"
                     "items-center"
                     "justify-center"
                     "bg-[#041016]/80"
                     "p-4"]
             :data-role "agent-trading-recovery-modal-overlay"}
       [:div {:class ["w-full"
                      "max-w-md"
                      "rounded-2xl"
                      "border"
                      "border-base-300"
                      "bg-[#081b24]"
                      "p-4"
                      "shadow-2xl"
                      "space-y-4"]
              :role "dialog"
              :aria-modal true
              :aria-label "Enable Trading Again"
              :data-role "agent-trading-recovery-modal"}
        [:div {:class ["flex" "items-start" "justify-between" "gap-4"]}
         [:div {:class ["space-y-1"]}
          [:p {:class ["text-xs"
                       "font-semibold"
                       "uppercase"
                       "tracking-[0.12em]"
                       "text-[#8fd8cb]"]}
           "Trading Recovery"]
          [:h2 {:class ["text-lg" "font-semibold" "text-white"]}
           "Enable Trading Again"]]
         [:button {:type "button"
                   :class ["inline-flex"
                           "h-8"
                           "w-8"
                           "shrink-0"
                           "items-center"
                           "justify-center"
                           "rounded-md"
                           "text-gray-400"
                           "hover:bg-base-200"
                           "hover:text-gray-100"
                           "focus:outline-none"
                           "focus:ring-0"
                           "focus:ring-offset-0"]
                   :on {:click [[:actions/close-agent-recovery-modal]]}
                   :aria-label "Close enable trading dialog"
                   :data-role "agent-trading-recovery-modal-close"}
          (close-icon)]]
        [:div {:class ["space-y-3"]}
         [:p {:class ["text-sm" "leading-6" "text-[#d7e7e8]"]}
          (if approving?
            approving-description
            idle-description)]
         (when error-text
           [:div {:class ["rounded-lg"
                          "border"
                          "border-[#23505a]"
                          "bg-[#0b2630]"
                          "px-3"
                          "py-2.5"
                          "text-sm"
                          "text-[#b7d7dd]"]
                  :data-role "agent-trading-recovery-modal-message"}
            error-text])]
        [:div {:class ["flex" "justify-end" "gap-2"]}
         [:button {:type "button"
                   :class ["rounded-lg"
                           "border"
                           "border-[#2c4b50]"
                           "px-3.5"
                           "py-2"
                           "text-sm"
                           "text-[#b7c8cc]"
                           "hover:border-[#3d666b]"
                           "hover:text-[#e5eef1]"
                           "focus:outline-none"
                           "focus:ring-0"
                           "focus:ring-offset-0"]
                   :on {:click [[:actions/close-agent-recovery-modal]]}
                   :data-role "agent-trading-recovery-modal-dismiss"}
          "Not now"]
         [:button {:type "button"
                   :disabled approving?
                   :class (into ["rounded-lg"
                                 "border"
                                 "px-3.5"
                                 "py-2"
                                 "text-sm"
                                 "font-medium"
                                 "focus:outline-none"
                                 "focus:ring-0"
                                 "focus:ring-offset-0"]
                                (if approving?
                                  ["border-[#2a4b4b]"
                                   "bg-[#08202a]/55"
                                   "text-[#6c8e93]"
                                   "cursor-not-allowed"]
                                  ["border-[#2f625a]"
                                   "bg-[#0d3a35]"
                                   "text-[#daf3ef]"
                                   "hover:border-[#3f7f75]"
                                   "hover:bg-[#115046]"]))
                   :on {:click [[:actions/enable-agent-trading]]}
                   :data-role "agent-trading-recovery-modal-confirm"}
          (if approving? "Awaiting signature..." "Enable Trading")]]]])))

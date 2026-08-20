(ns hyperopen.views.subaccounts-view.transfer-dropdowns)

(defn- dropdown-chevron
  [open?]
  [:svg {:class (into ["pointer-events-none"
                       "h-3"
                       "w-3"
                       "shrink-0"
                       "text-ho-accent"
                       "transition-transform"
                       "duration-150"
                       "ease-out"]
                      (if open?
                        ["rotate-180"]
                        ["rotate-0"]))
         :viewBox "0 0 12 12"
         :fill "none"
         :stroke "currentColor"
         :stroke-width "1.5"
         :stroke-linecap "round"
         :stroke-linejoin "round"
         :aria-hidden true}
   [:path {:d "M3.25 4.75 6 7.5l2.75-2.75"}]])

(defn- transfer-account-option
  [{:keys [address value label selected?]}]
  [:button {:type "button"
            :data-role (str "subaccounts-transfer-account-option-" address "-" (name value))
            :role "option"
            :aria-selected (boolean selected?)
            :class (into ["flex"
                          "h-8"
                          "w-full"
                          "items-center"
                          "rounded-md"
                          "px-2"
                          "text-left"
                          "text-sm"
                          "font-semibold"
                          "transition-colors"
                          "focus:outline-none"
                          "focus:ring-0"
                          "focus:ring-offset-0"]
                         (if selected?
                           ["bg-[#12312e]" "text-ho-accent"]
                           ["text-[#aab6b9]" "hover:bg-[#122124]" "hover:text-white"]))
            :on {:click [[:actions/set-subaccount-form-field
                          :transfer-account
                          value]]}}
   label])

(defn transfer-account-dropdown
  [{:keys [address subaccounts unified-account?]}]
  ;; A unified (portfolio-margin) master has one pooled funding source, so it
  ;; keeps a single option — but that option must carry `:spot`, because the
  ;; transfer really does travel Hyperliquid's spot path. It previously carried
  ;; `:trading`, which is what locked the token list to USDC.
  (let [selected (if unified-account?
                   :spot
                   (or (:transfer-account subaccounts) :trading))
        selected-label (if (= :spot selected) "Spot Account" "Trading Account")
        open? (true? (:transfer-account-menu-open? subaccounts))
        options (if unified-account?
                  [{:value :spot :label "Spot Account"}]
                  [{:value :trading :label "Trading Account"}
                   {:value :spot :label "Spot Account"}])]
    [:div {:class ["relative" "h-full" "w-full"]}
     [:button {:type "button"
               :data-role (str "subaccounts-transfer-direction-" address)
               :aria-label "Transfer account"
               :aria-haspopup "listbox"
               :aria-expanded (str (boolean open?))
               :class ["flex"
                       "h-full"
                       "w-full"
                       "items-center"
                       "justify-between"
                       "gap-2"
                       "border-0"
                       "bg-transparent"
                       "text-left"
                       "text-sm"
                       "font-medium"
                       "text-white"
                       "outline-none"
                       "focus:border-0"
                       "focus:outline-none"
                       "focus:ring-0"
                       "focus:ring-offset-0"]
               :style {:border "0"
                       :box-shadow "none"}
               :on {:click [[:actions/set-subaccount-form-field
                             :transfer-account-menu-open?
                             (not open?)]]}}
      [:span {:class ["truncate"]} selected-label]
      (dropdown-chevron open?)]
     (when open?
       (into
        [:div {:data-role (str "subaccounts-transfer-account-menu-" address)
               :role "listbox"
               :aria-label "Transfer account options"
               :class ["absolute"
                       "left-[-0.75rem]"
                       "right-[-0.75rem]"
                       "top-[calc(100%+0.75rem)]"
                       "z-[70]"
                       "rounded-md"
                       "border"
                       "border-[#263b3f]"
                       "bg-[#0b1518]"
                       "p-1"
                       "shadow-[0_18px_50px_rgba(0,0,0,0.44)]"]}]
        (for [{:keys [value label]} options]
          ^{:key (name value)}
          (transfer-account-option {:address address
                                    :value value
                                    :label label
                                    :selected? (= value selected)}))))]))

(defn selected-transfer-token
  [subaccounts transfer-assets]
  (let [selected (:transfer-token subaccounts)]
    (or (some (fn [asset]
                (when (= selected (:token asset))
                  asset))
              transfer-assets)
        ;; The stored default is the bare symbol "USDC" while assets now carry
        ;; `NAME:0x<hash>` wire token ids, so fall back to a symbol match before
        ;; giving up and selecting whichever asset happens to be first.
        (some (fn [asset]
                (when (and (some? selected)
                           (= (str selected) (str (:symbol asset))))
                  asset))
              transfer-assets)
        (first transfer-assets)
        {:symbol "USDC"
         :token "USDC"
         :available-display "--"})))

(def unresolved-token-message
  "Asset details unavailable - refresh balances")

(defn- token-option
  [{:keys [address asset selected?]}]
  (let [token (:token asset)
        symbol (:symbol asset)
        unresolved? (true? (:unresolved? asset))
        available-display (:available-display asset)]
    [:button {:type "button"
              :data-role (str "subaccounts-transfer-token-option-" address "-" token)
              :role "option"
              :aria-selected (boolean selected?)
              :disabled unresolved?
              :title (when unresolved? unresolved-token-message)
              :class (into ["flex"
                            "h-8"
                            "w-full"
                            "items-center"
                            "justify-between"
                            "gap-3"
                            "rounded-md"
                            "px-2"
                            "text-left"
                            "text-sm"
                            "transition-colors"
                            "focus:outline-none"
                            "focus:ring-0"
                            "focus:ring-offset-0"]
                           (cond
                             unresolved? ["cursor-not-allowed" "text-ho-text-dim"]
                             selected? ["bg-[#12312e]" "text-ho-accent"]
                             :else ["text-[#aab6b9]" "hover:bg-[#122124]" "hover:text-white"]))
              :on {:click (when-not unresolved?
                            [[:actions/set-subaccount-form-field
                              :transfer-token
                              token]])}}
     [:span {:class ["font-semibold"]} symbol]
     [:span {:class ["num" "text-xs" "font-medium" "text-[#9aa8ab]"]}
      (if unresolved? "unavailable" available-display)]]))

(defn token-dropdown
  [{:keys [address subaccounts transfer-assets]}]
  (let [selected-asset (selected-transfer-token subaccounts transfer-assets)
        open? (true? (:transfer-token-menu-open? subaccounts))]
    [:div {:class ["relative" "h-full" "w-full"]}
     [:button {:type "button"
               :data-role (str "subaccounts-transfer-token-" address)
               :aria-label "Transfer token"
               :aria-haspopup "listbox"
               :aria-expanded (str (boolean open?))
               :class ["flex"
                       "h-full"
                       "w-full"
                       "items-center"
                       "justify-between"
                       "gap-2"
                       "border-0"
                       "bg-transparent"
                       "text-left"
                       "text-sm"
                       "font-medium"
                       "text-white"
                       "outline-none"
                       "focus:border-0"
                       "focus:outline-none"
                       "focus:ring-0"
                       "focus:ring-offset-0"]
               :style {:border "0"
                       :box-shadow "none"}
               :on {:click [[:actions/set-subaccount-form-field
                             :transfer-token-menu-open?
                             (not open?)]]}}
      [:span (:symbol selected-asset)]
      (dropdown-chevron open?)]
     (when open?
       (into
        [:div {:data-role (str "subaccounts-transfer-token-menu-" address)
               :role "listbox"
               :aria-label "Transfer token options"
               :class ["absolute"
                       "left-[-0.75rem]"
                       "right-[-0.75rem]"
                       "top-[calc(100%+0.75rem)]"
                       "z-[60]"
                       "max-h-56"
                       "overflow-y-auto"
                       "rounded-md"
                       "border"
                       "border-[#263b3f]"
                       "bg-[#0b1518]"
                       "p-1"
                       "shadow-[0_18px_50px_rgba(0,0,0,0.44)]"]}]
        (for [asset transfer-assets]
          ^{:key (:token asset)}
          (token-option {:address address
                         :asset asset
                         :selected? (= (:token asset)
                                       (:token selected-asset))}))))]))

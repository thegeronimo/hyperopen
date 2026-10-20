(ns hyperopen.ui.voice
  "Theme-keyed UI copy (the app's voice).

   Tokens restyle pixels (docs/THEMING.md); this catalog restyles words.
   `:default` holds the canonical copy — sync-tested against the surfaces
   that own those strings (header nav, account-info tab registry) so the
   two can never drift. Other voices override per label key and fall back
   to `:default`. The active voice derives from the active theme, so it
   follows the same persistence and pre-paint restore as the theme."
  (:require [hyperopen.ui.theme :as theme]))

(def ^:private theme->voice
  {"hyperdegen" :degen})

(def labels
  "Label catalog: label key -> voice -> copy. Counted account tabs get
   paren-free degen nouns so registry count suffixes like \" (23)\" compose."
  {:brand/wordmark {:default "HyperOpen" :degen "HyperDegen"}
   :brand/mark {:default "HO" :degen "HD"}
   ;; :default mirrors resources/public/index.html <title>.
   :brand/title {:default "HyperOpen"
                 :degen "HyperDegen — Trade Like a Legend 🐸"}

   :nav/trade {:default "Trade" :degen "Trade (Gamble)"}
   :nav/portfolio {:default "Portfolio" :degen "Portfolio (Hope)"}
   :nav/optimize {:default "Optimize" :degen "Optimize (Cope)"}
   :nav/funding {:default "Funding" :degen "Funding (Brrr)"}
   :nav/vaults {:default "Vaults" :degen "Vaults (LOL)"}
   :nav/earn {:default "Earn" :degen "Earn (Yield Farm)"}
   :nav/staking {:default "Staking" :degen "Staking (Zzz)"}
   :nav/referrals {:default "Referrals" :degen "Referrals (Spam)"}
   :nav/leaderboard {:default "Leaderboard" :degen "Leaderboard (Flex)"}
   :nav/api {:default "API" :degen "API (Nerds)"}
   :nav/subaccounts {:default "Sub-Accounts" :degen "Sub-Accounts (Alts)"}

   :orderbook/book {:default "Order Book" :degen "Order Book (looks important)"}
   :orderbook/trades {:default "Trades" :degen "Trades (other people's mistakes)"}

   ;; Mobile surface tabs share a third of a small screen; degen variants
   ;; stay short on purpose.
   :mobile-surface/chart {:default "Chart" :degen "Chart (hopium)"}
   :mobile-surface/orderbook {:default "Order Book" :degen "Book (lol)"}
   :mobile-surface/trades {:default "Trades" :degen "Trades (pain)"}

   :order-form/buy {:default "Buy / Long" :degen "Buy / Moon 🚀"}
   :order-form/sell {:default "Sell / Short" :degen "Sell / Panic 😱"}
   :order-form/submit {:default "Place Order" :degen "Send It 🚀"}
   :order-form/submitting {:default "Submitting..." :degen "Sending It..."}

   :account-tabs/balances {:default "Balances" :degen "What's Left"}
   :account-tabs/positions {:default "Positions" :degen "Hope & Dreams"}
   :account-tabs/outcomes {:default "Outcomes" :degen "Copium"}
   :account-tabs/open-orders {:default "Open Orders" :degen "Maybe Orders"}
   :account-tabs/twap {:default "TWAP" :degen "Fancy Gambling"}
   :account-tabs/trade-history {:default "Trade History" :degen "Bragging Rights"}
   :account-tabs/funding-history {:default "Funding History" :degen "The Slow Bleed"}
   :account-tabs/order-history {:default "Order History" :degen "Receipts"}})

(defn active-voice
  [state]
  (get theme->voice (theme/active-theme-id state) :default))

(defn degen?
  "True when the degen voice is active; gates parody decor
   (hyperopen.views.degen.widgets) alongside the copy."
  [state]
  (= :degen (active-voice state)))

(defn label-for
  "Copy for `label-key` under `voice`, falling back to the default voice.
   Returns nil for keys the catalog does not know."
  [voice label-key]
  (when-some [entry (get labels label-key)]
    (or (get entry voice)
        (:default entry))))

(defn label
  [state label-key]
  (label-for (active-voice state) label-key))

(defn nav-label
  "Voice copy for a header nav item id; `fallback` when uncataloged."
  [state nav-id fallback]
  (or (label state (keyword "nav" (name nav-id)))
      fallback))

(defn account-tab-overrides
  "Tab-id -> copy map shaped for the account-info tab registry's label
   overrides. nil under the default voice (registry labels are the canon)."
  [state]
  (let [voice (active-voice state)]
    (when (not= :default voice)
      (into {}
            (keep (fn [[label-key entry]]
                    (when (= "account-tabs" (namespace label-key))
                      (when-some [copy (or (get entry voice)
                                           (:default entry))]
                        [(keyword (name label-key)) copy]))))
            labels))))

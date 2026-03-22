(ns hyperopen.views.header.nav
  (:require [clojure.string :as str]
            [hyperopen.api-wallets.actions :as api-wallets-actions]
            [hyperopen.funding-comparison.actions :as funding-comparison-actions]
            [hyperopen.router :as router]))

(def ^:private funding-route
  "/funding-comparison")

(defn- exact-or-child-route?
  [path route-prefix]
  (let [path* (router/normalize-path path)
        route-prefix* (router/normalize-path route-prefix)]
    (or (= path* route-prefix*)
        (str/starts-with? path* (str route-prefix* "/")))))

(def ^:private header-nav-items
  [{:id :trade
    :label "Trade"
    :route "/trade"
    :placements #{:desktop :mobile-primary}
    :active-fn router/trade-route?}
   {:id :portfolio
    :label "Portfolio"
    :route "/portfolio"
    :placements #{:desktop :mobile-primary}
    :active-fn #(exact-or-child-route? % "/portfolio")}
   {:id :funding
    :label "Funding"
    :route funding-route
    :placements #{:desktop :mobile-primary}
    :active-fn funding-comparison-actions/funding-comparison-route?}
   {:id :earn
    :label "Earn"
    :route "/earn"
    :placements #{:desktop :mobile-secondary}
    :active-fn #(exact-or-child-route? % "/earn")}
   {:id :vaults
    :label "Vaults"
    :route "/vaults"
    :placements #{:desktop :mobile-primary}
    :active-fn #(exact-or-child-route? % "/vaults")}
   {:id :staking
    :label "Staking"
    :route "/staking"
    :placements #{:desktop :mobile-secondary}
    :active-fn #(exact-or-child-route? % "/staking")}
   {:id :referrals
    :label "Referrals"
    :route "/referrals"
    :placements #{:desktop :mobile-secondary}
    :active-fn #(exact-or-child-route? % "/referrals")}
   {:id :leaderboard
    :label "Leaderboard"
    :route "/leaderboard"
    :placements #{:desktop :mobile-secondary}
    :active-fn #(exact-or-child-route? % "/leaderboard")}
   {:id :api
    :label "API"
    :route api-wallets-actions/canonical-route
    :placements #{:more}
    :active-fn api-wallets-actions/api-wallet-route?}])

(defn- present-item
  [current-route item]
  (let [{:keys [active-fn id] :as item*} item]
    (cond-> (assoc item*
                   :active? (boolean (when (fn? active-fn)
                                       (active-fn current-route))))
      (contains? (:placements item*) :mobile-primary)
      (assoc :mobile-data-role (str "mobile-header-menu-link-" (name id)))

      (contains? (:placements item*) :mobile-secondary)
      (assoc :mobile-data-role (str "mobile-header-menu-link-" (name id)))

      (contains? (:placements item*) :more)
      (assoc :more-data-role (str "header-more-link-" (name id))))))

(defn items-for-placement
  [current-route placement]
  (->> header-nav-items
       (filter #(contains? (:placements %) placement))
       (mapv #(present-item current-route %))))

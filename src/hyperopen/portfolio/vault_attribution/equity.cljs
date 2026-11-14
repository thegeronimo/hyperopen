(ns hyperopen.portfolio.vault-attribution.equity
  "Which equity vault attribution may trust.

   The ledger is a snapshot, so equity must come from the SAME load: the
   `followerState.vaultEquity` of the `vaultDetails` fetched with it (no
   follower state = the account holds nothing in that vault). The live
   `userVaultEquities` rows are used only for a vault whose details failed,
   and only when they were loaded for the same account; otherwise that
   vault's equity is unknown."
  (:require [hyperopen.portfolio.vault-attribution.model :as model]))

(def drift-ratio
  ;; Live equity this far from the snapshot means money moved since the load.
  0.02)

(def drift-min-usd 1)

(def drift-min-age-ms
  ;; The live rows poll every ~60 s; right after a load they may still trail
  ;; the snapshot, so drift is only trusted once the snapshot is this old.
  60000)

(defn- row-equity
  [{:keys [equity equity-raw]}]
  (or (model/finite equity) (model/number-in equity-raw)))

(defn live-equity-by-vault
  "`{vault equity}` from `[:vaults :user-equities]` rows, or nil when the rows
   are not loaded for `address`."
  [state address]
  (let [rows (get-in state [:vaults :user-equities])]
    (when (and (some? address)
               (vector? rows)
               (= address (get-in state [:vaults :user-equities-for-address])))
      (reduce (fn [acc row]
                (let [vault (when (map? row) (model/normalize-address (:vault-address row)))
                      equity (when (map? row) (row-equity row))]
                  (if (and vault (number? equity))
                    (assoc acc vault (+ (get acc vault 0) equity))
                    acc)))
              {}
              rows))))

(defn- details-equity
  "The account's equity in one vault from its details: the follower state,
   else the account's own row in `:followers` (the vault leader is listed
   there as `:leader? true`), else nil. Details without any of these do not
   prove the account holds nothing (see `equity-inputs`)."
  [details account]
  (let [followers (when (sequential? (:followers details)) (:followers details))
        own-row (when account
                  (some #(when (= account (model/normalize-address (:user %))) %) followers))
        leader-row (when (and account (= account (model/normalize-address (:leader details))))
                     (some #(when (:leader? %) %) followers))]
    (or (model/number-in (get-in details [:follower-state :vault-equity]))
        (model/number-in (:vault-equity own-row))
        (model/number-in (:vault-equity leader-row)))))

(defn details-equity-by-vault
  "`{vault equity-or-nil}` for every vault whose details loaded (see
   `details-equity`); `account` is the address the details were read for."
  ([details-by-vault]
   (details-equity-by-vault details-by-vault nil))
  ([details-by-vault account]
   (let [account (model/normalize-address account)]
     (reduce-kv (fn [acc vault details]
                  (assoc acc (model/normalize-address vault)
                         (details-equity (when (map? details) details) account)))
                {}
                (or details-by-vault {})))))

(defn- snapshot-equity
  "`{vault equity}` the snapshot actually values (numbers only)."
  [slot]
  (into {}
        (filter (comp number? val))
        (details-equity-by-vault (:details-by-vault slot) (:for-address slot))))

(defn equity-inputs
  "`{:equity-by-vault m :equity-unknown #{...}}` for `attribution`. `live` is
   `live-equity-by-vault` (nil when not trustworthy).

   A vault whose details failed takes its live value when the trusted live
   rows list it, and is unknown otherwise (a list without it is no evidence:
   never a phantom total loss). A vault whose details loaded but carry no
   equity for the account is a known zero only when the trusted live rows
   confirm it is absent; otherwise unknown. `attribution` still reads an
   unknown equity as zero when the ledger shows the position fully
   withdrawn."
  [slot live]
  (let [failed (keep model/normalize-address (keys (:errors-by-vault slot)))
        from-details (details-equity-by-vault (:details-by-vault slot) (:for-address slot))
        no-equity (keep (fn [[v equity]] (when (nil? equity) v)) from-details)
        known (into {} (filter (comp number? val)) from-details)
        live? (map? live)
        in-live? #(and live? (contains? live %))
        absent? #(and live? (not (contains? live %)))]
    {:equity-by-vault (-> known
                          (into (comp (filter in-live?) (map (fn [v] [v (get live v)]))) failed)
                          (into (comp (filter absent?) (map (fn [v] [v 0]))) no-equity))
     :equity-unknown (into (into #{} (remove in-live?) failed)
                           (remove absent?)
                           no-equity)}))

(defn live-vaults-at-load
  "Vault addresses with trusted live rows when a load lands, stored in the
   slot so `drifted?` does not count them as new money again."
  [state address]
  (some-> (live-equity-by-vault state address) keys set))

(defn drifted?
  "True once the snapshot is old enough and either
   - over the vaults present in BOTH the snapshot (details with a numeric
     equity) and the live rows, the live total differs from the snapshot by
     more than `drift-ratio` and `drift-min-usd`; or
   - a live vault above `drift-min-usd` is unknown to the snapshot and was not
     in the live rows when it loaded (new money since the load).
   Vaults the snapshot cannot value (failed details, orphans, a cut ledger)
   never drift by themselves, so a reload that changes nothing is quiet."
  [state slot address now-ms]
  (let [live (live-equity-by-vault state address)
        loaded-at-ms (:loaded-at-ms slot)]
    (boolean
     (when (and (map? live)
                (number? loaded-at-ms)
                (number? now-ms)
                (>= (- now-ms loaded-at-ms) drift-min-age-ms))
       (let [snapshot (snapshot-equity slot)
             common (filter #(contains? live %) (keys snapshot))
             before (reduce + 0 (map snapshot common))
             current (reduce + 0 (map live common))
             diff (js/Math.abs (- current before))
             seen (set (:live-vaults-at-load slot))]
         (or (and (> diff drift-min-usd)
                  (> diff (* drift-ratio (js/Math.abs before))))
             (some (fn [[v equity]]
                     (and (> equity drift-min-usd)
                          (not (contains? snapshot v))
                          (not (contains? seen v))))
                   live)))))))

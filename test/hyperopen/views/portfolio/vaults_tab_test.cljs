(ns hyperopen.views.portfolio.vaults-tab-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.vault-attribution.test-fixtures :as fx]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.portfolio.vaults-tab :as view]
            [hyperopen.views.portfolio.vaults-tab-fixtures :as tf]))

(defn- text-of
  [node role]
  (some-> (hiccup/find-by-data-role node role) hiccup/node-text))

(defn- render
  [slot]
  (view/vaults-tab (tf/state-with slot)))

(deftest non-ready-states-test
  (testing "no account"
    (is (= "Connect a wallet or open a trader to see vault returns."
           (text-of (view/vaults-tab {}) "portfolio-vaults-none"))))
  (testing "loading shows a spinner and the loading line"
    (let [node (render {:status :loading :for-address tf/address})]
      (is (= "Loading vault history…" (text-of node "portfolio-vaults-loading")))
      (is (some? (hiccup/find-first-node node #(hiccup/contains-class? % "ho-spinner"))))))
  (testing "error offers a retry that reloads"
    (let [node (render {:status :error :for-address tf/address :error "x"})
          retry (hiccup/find-by-data-role node "portfolio-vaults-retry")]
      (is (re-find #"Vault history could not be loaded\." (text-of node "portfolio-vaults-error")))
      (is (= [[:actions/load-portfolio-vault-attribution]] (get-in retry [1 :on :click])))
      (is (= "Retry" (hiccup/node-text retry)))))
  (testing "empty"
    (is (= "No vault deposits found for this account."
           (text-of (view/vaults-tab (tf/state-without-live
                                      (tf/ready-slot :ledger-rows [] :details-by-vault {})))
                    "portfolio-vaults-empty")))))

(deftest ready-cards-test
  (let [node (render (tf/ready-slot))]
    (doseq [[role label] [["portfolio-vaults-card-pnl" "Vault PNL"]
                          ["portfolio-vaults-card-mwr" "Money-weighted return"]
                          ["portfolio-vaults-card-twr" "Time-weighted return"]
                          ["portfolio-vaults-card-equity" "Vault equity"]]]
      (is (re-find (re-pattern label) (text-of node role)) label))
    (is (re-find #"\$1,800\.00" (text-of node "portfolio-vaults-card-equity")))
    (is (re-find #"~" (text-of node "portfolio-vaults-card-twr")) "estimated mark")
    (is (not (re-find #"~" (text-of node "portfolio-vaults-card-mwr"))))
    (is (hiccup/contains-class? (hiccup/find-by-data-role node "portfolio-vaults-card-pnl")
                                "border-ho-border-accent"))))

(deftest ready-table-test
  (let [node (render (tf/ready-slot))
        rows (hiccup/find-all-nodes node #(= "portfolio-vaults-row" (get-in % [1 :data-role])))
        links (hiccup/find-all-nodes node #(= "portfolio-vaults-vault-link" (get-in % [1 :data-role])))
        row-text (mapv hiccup/node-text rows)]
    (is (= 3 (count rows)))
    (is (= ["HLP" "Orphan" "0x1e37…8d5e"] (mapv hiccup/node-text links)))
    (is (re-find #"/vaults/0xdfc24b077bc1425ad1dea75bcb6f8158e10df303$"
                 (get-in (first links) [1 :href])))
    (testing "headers"
      (is (every? #(re-find (re-pattern %) (hiccup/node-text (hiccup/find-by-data-role node "portfolio-vaults-table")))
                  ["Vault" "Status" "Deposited" "Withdrawn" "Value" "PNL" "Money-weighted"
                   "Vault return while held" "Held"])))
    (testing "the two return headings explain themselves on hover"
      (doseq [[id pattern] [["portfolio-vaults-help-money-weighted" #"yearly rate of return"]
                            ["portfolio-vaults-help-vault-return-while-held" #"vault itself performed"]]]
        (let [tip (hiccup/find-by-data-role node id)
              trigger (hiccup/find-by-data-role node (str id "-trigger"))]
          (is (= "tooltip" (get-in tip [1 :role])))
          (is (re-find pattern (hiccup/node-text tip)))
          (is (= id (get-in trigger [1 :aria-describedby])))
          (is (= 0 (get-in trigger [1 :tabindex]))))))
    (testing "open and closed"
      (is (re-find #"Open" (first row-text)))
      (is (re-find #"Closed" (nth row-text 2))))
    (testing "incomplete vault shows a note and --"
      (is (re-find #"Deposit history incomplete" (second row-text)))
      (is (= 1 (count (hiccup/find-all-nodes
                       node #(= "portfolio-vaults-incomplete-note" (get-in % [1 :data-role])))))))
    (testing "totals row"
      (let [total (hiccup/find-by-data-role node "portfolio-vaults-total-row")]
        (is (re-find #"All vaults" (hiccup/node-text total)))
        (is (= "portfolio-vaults-total-row"
               (get-in (hiccup/find-first-node node #(= :tbody (first %))) [1 1 :data-role]))
            "the total is the first body row")
        (is (hiccup/contains-class? total "border-b-2"))
        (is (re-find #"~" (hiccup/node-text total)))))
    (testing "scrolls horizontally"
      (is (hiccup/contains-class? (hiccup/find-by-data-role node "portfolio-vaults-table-wrap")
                                  "overflow-x-auto")))))

(deftest chart-and-notes-test
  (let [node (render (tf/ready-slot))]
    (is (some? (hiccup/find-by-data-role node "portfolio-vaults-chart")))
    (is (re-find #"Vault value.*Net deposited.*Deposit.*Withdrawal"
                 (text-of node "portfolio-vaults-chart-legend")))
    (is (= 4 (count (hiccup/find-all-nodes
                     node #(#{"portfolio-vaults-chart-deposit" "portfolio-vaults-chart-withdrawal"}
                            (get-in % [1 :data-role]))))))
    (is (every? #(seq (get-in % [1 :aria-label]))
                (hiccup/find-all-nodes
                 node #(= "portfolio-vaults-chart-deposit" (get-in % [1 :data-role])))))
    (is (nil? (hiccup/find-by-data-role node "portfolio-vaults-partial")))
    (is (some? (hiccup/find-by-data-role
                (render (tf/ready-slot :errors-by-vault {fx/vault-b "x"}))
                "portfolio-vaults-partial")))))

(deftest pnl-roles-sit-on-the-pnl-cells-test
  (let [node (render (tf/ready-slot))
        row-pnl (hiccup/find-all-nodes node #(= "portfolio-vaults-row-pnl" (get-in % [1 :data-role])))
        total-pnl (hiccup/find-by-data-role node "portfolio-vaults-total-pnl")]
    (is (= 3 (count row-pnl)))
    (is (every? #(= :td (first %)) row-pnl))
    (is (= "+$200.00" (hiccup/node-text (nth row-pnl 2))))
    (is (= :td (first total-pnl)))
    (is (re-find #"^[+-]\$" (hiccup/node-text total-pnl)))))

(deftest excluded-and-refresh-error-lines-test
  (let [node (render (tf/ready-slot))]
    (is (= "1 vault excluded: incomplete history" (text-of node "portfolio-vaults-excluded")))
    (is (nil? (hiccup/find-by-data-role node "portfolio-vaults-refresh-error"))))
  (let [node (render (tf/ready-slot :status :error :error "boom"))]
    (is (some? (hiccup/find-by-data-role node "portfolio-vaults-ready")))
    (is (nil? (hiccup/find-by-data-role node "portfolio-vaults-error")))
    (is (re-find #"Couldn't refresh vault history"
                 (text-of node "portfolio-vaults-refresh-error")))
    (is (= [[:actions/load-portfolio-vault-attribution]]
           (get-in (hiccup/find-by-data-role node "portfolio-vaults-refresh-retry")
                   [1 :on :click])))))

(deftest ledger-partial-note-test
  (is (= "Vault history may be incomplete: the ledger is too long to load in full."
         (text-of (render (tf/ready-slot :ledger-partial? true)) "portfolio-vaults-partial"))))

(deftest refreshing-marks-the-panel-busy-test
  (let [busy (render (tf/ready-slot :status :loading))
        settled (render (tf/ready-slot))]
    (is (true? (get-in (hiccup/find-by-data-role busy "portfolio-vaults-ready") [1 :aria-busy])))
    (is (seq (hiccup/find-all-nodes busy #(= "portfolio-vaults-row" (get-in % [1 :data-role]))))
        "the held rows stay on screen while refreshing")
    (is (nil? (hiccup/find-by-data-role busy "portfolio-vaults-loading")))
    (is (false? (get-in (hiccup/find-by-data-role settled "portfolio-vaults-ready")
                        [1 :aria-busy])))))

(deftest orphan-row-test
  (let [node (render (tf/ready-slot))
        orphan (some #(when (re-find #"Orphan" (hiccup/node-text %)) %)
                     (hiccup/find-all-nodes node #(= "portfolio-vaults-row" (get-in % [1 :data-role]))))
        cells (hiccup/find-all-nodes orphan #(= :td (first %)))]
    (is (some? orphan))
    (is (= "Deposit history incomplete"
           (text-of orphan "portfolio-vaults-incomplete-note")))
    (is (= "Open" (text-of orphan "portfolio-vaults-status")))
    (is (= "$500.00" (hiccup/node-text (nth cells 4))) "value shown")
    (is (= "--" (text-of orphan "portfolio-vaults-row-pnl")) "PNL unknown")
    (is (= "1 vault excluded: incomplete history" (text-of node "portfolio-vaults-excluded")))))

(deftest row-while-held-is-marked-estimated-test
  (let [node (render (tf/ready-slot))
        open-row (first (hiccup/find-all-nodes
                         node #(= "portfolio-vaults-row" (get-in % [1 :data-role]))))]
    (is (some? (hiccup/find-by-data-role open-row "portfolio-vaults-row-held-return-estimated-mark")))))

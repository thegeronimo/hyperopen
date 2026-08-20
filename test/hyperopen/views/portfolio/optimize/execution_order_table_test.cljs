(ns hyperopen.views.portfolio.optimize.execution-order-table-test
  "Order-list honesty: skipped (no-order) rows live in a collapsed section instead of
  padding the order table, the venue is stated once instead of per row, recommended
  routes carry a by-exception rationale, and the passive type is named Passive maker."
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [hyperopen.test-support.hiccup :as h]
            [hyperopen.views.portfolio.optimize.execution-tab :as execution-tab]))

(def ^:private labels
  {"perp:EWZ" "EWZ" "perp:NOW" "NOW" "perp:REZ" "REZ" "spot:USDH" "USDH"})

(def ^:private plan
  {:status :ready
   :execution-disabled? false
   :summary {:ready-count 3 :blocked-count 0 :skipped-count 1
             :gross-ready-notional-usd 135
             :margin {:after-utilization 0.1 :after-gross-leverage 1.1
                      :before-gross-leverage 1.0 :free-margin-usd 300
                      :capital-usd 320 :warning :none}}
   :rows [{:row-id "perp:EWZ" :instrument-id "perp:EWZ" :instrument-type :perp
           :status :ready :side :buy :quantity 2.47 :order-type :market
           :delta-notional-usd 87
           :cost {:source :snapshot :slippage-bps 45.4 :estimated-slippage-usd 0.4
                  :fee-bps 4.5 :estimated-fee-usd 0.04
                  :maker-fee-bps 1.5 :maker-fee-usd 0.01}}
          {:row-id "perp:NOW" :instrument-id "perp:NOW" :instrument-type :perp
           :status :ready :side :buy :quantity 0.38 :order-type :market
           :delta-notional-usd 40
           :cost {:source :snapshot :slippage-bps 1 :estimated-slippage-usd 0.01
                  :fee-bps 4.5 :estimated-fee-usd 0.02
                  :maker-fee-bps 1.5 :maker-fee-usd 0.005}}
          {:row-id "spot:USDH" :instrument-id "spot:USDH" :instrument-type :spot
           :status :ready :side :sell :quantity 8.28 :order-type :market
           :delta-notional-usd -8.28
           :cost {:source :snapshot :slippage-bps 2 :estimated-slippage-usd 0.002
                  :fee-bps 4.5 :estimated-fee-usd 0.004
                  :maker-fee-bps 1.5 :maker-fee-usd 0.001}}
          {:row-id "perp:REZ" :instrument-id "perp:REZ" :instrument-type :perp
           :status :skipped :side :sell :reason :within-tolerance :tolerance 0.03
           :quantity 2680.5 :delta-notional-usd -8.4}]})

(defn- view
  [modal-overrides]
  (execution-tab/execution-tab
   {:portfolio {:optimizer
                {:last-successful-run {:result {:labels-by-instrument labels}}
                 :execution-modal (merge {:open? true :plan plan :phase :staged
                                          :default-order-type :recommended
                                          :overrides {} :params {}}
                                         modal-overrides)
                 :execution {:status :idle :history []}}}}))

(deftest skipped-rows-collapse-out-of-the-order-list-test
  ;; 3 orders will be sent; REZ is within tolerance. It must not read as a 4th order:
  ;; it renders (with its ledger # and plain-language reason) only inside the collapsed
  ;; "Skipped" section, and the list headline counts the sendable rows.
  (let [node (view nil)
        order-list (h/find-by-data-role node "portfolio-optimizer-execution-order-list")
        skipped (h/find-by-data-role node "portfolio-optimizer-execution-skipped")
        rez (h/find-by-data-role node "portfolio-optimizer-execution-order-row-perp-REZ")]
    (is (some? skipped))
    (is (str/includes? (h/node-text skipped) "Skipped — 1 asset"))
    (is (str/includes? (h/node-text skipped) "no orders will be sent"))
    (is (some? (h/find-by-data-role skipped "portfolio-optimizer-execution-order-row-perp-REZ"))
        "the skipped row lives inside the skipped section")
    (is (str/includes? (h/node-text rez) "within 3 pp band"))
    (is (str/includes? (h/node-text order-list) "Order list — 3 to send"))))

(deftest venue-stated-once-not-per-row-test
  ;; Every optimizer order routes to Hyperliquid — a Venue column repeating it on each
  ;; row wasted the width the type/cost data needs. One line above the table carries it,
  ;; with the perp/spot mix; the per-row perp/spot badge stays (it differs by row).
  (let [node (view nil)
        order-list (h/find-by-data-role node "portfolio-optimizer-execution-order-list")
        text (h/node-text order-list)
        ewz (h/find-by-data-role node "portfolio-optimizer-execution-order-row-perp-EWZ")]
    (is (str/includes? text "All orders route to Hyperliquid — 2 perp · 1 spot."))
    (is (nil? (h/find-first-node order-list #(and (vector? %) (= :th (first %))
                                                  (= "Venue" (last %)))))
        "no Venue column header remains")
    (is (some? (h/find-first-node ewz #(= "perp" (get-in % [1 :data-kind]))))
        "the kind badge moved into the asset cell")))

(deftest route-hint-marks-recommended-non-market-routes-test
  ;; By-exception rationale: the 45.4bp EWZ row routes passive under Recommended and
  ;; says why in the Type cell; the cheap NOW market route stays quiet.
  (let [node (view nil)
        ewz (h/find-by-data-role node "portfolio-optimizer-execution-order-row-perp-EWZ")
        now (h/find-by-data-role node "portfolio-optimizer-execution-order-row-perp-NOW")
        hint (h/find-by-data-role ewz "portfolio-optimizer-execution-route-hint")]
    (is (some? hint))
    (is (str/includes? (h/node-text hint) "45.4 bp as market — rests instead"))
    (is (str/includes? (h/node-text ewz) "Passive maker")
        "the passive type is labeled Passive maker, never a bare Limit synonym")
    (is (nil? (h/find-by-data-role now "portfolio-optimizer-execution-route-hint")))))

(deftest route-hint-absent-for-user-override-test
  ;; A user override is a choice, not an algo route — no rationale hint is claimed.
  (let [node (view {:overrides {"perp:EWZ" :twap}})
        ewz (h/find-by-data-role node "portfolio-optimizer-execution-order-row-perp-EWZ")]
    (is (nil? (h/find-by-data-role ewz "portfolio-optimizer-execution-route-hint")))))

(deftest read-only-view-keeps-order-rows-editable-test
  ;; Spectate / read-only: a staged order row still opens its per-order type/param editor (a pure
  ;; cost simulation — it only writes modal state). The row carries the toggle action and the list
  ;; header invites the edit, even though the plan itself can't be armed or sent.
  (let [read-only-plan (assoc plan
                              :execution-disabled? true
                              :disabled-reason :read-only
                              :disabled-message "Spectate Mode is read-only.")
        node (view {:plan read-only-plan})
        order-list (h/find-by-data-role node "portfolio-optimizer-execution-order-list")
        ewz (h/find-by-data-role node "portfolio-optimizer-execution-order-row-perp-EWZ")]
    (is (= [[:actions/toggle-portfolio-optimizer-execution-row "perp:EWZ"]]
           (get-in ewz [1 :on :click]))
        "the row is clickable to open its type editor")
    (is (str/includes? (h/node-text order-list) "Click any order to change its type"))))

(deftest twap-row-breakdown-shows-worked-clips-and-sliced-figures-test
  ;; A TWAP-typed row's cost equation shows the SLICED estimate (impact spread across the
  ;; venue's clips + permanent residue), says how it is worked, and the editor's clip
  ;; count matches the venue's real slice model (the old copy claimed minutes÷2 "slices").
  ;; $100k at 2 bp spread + 100 bp impact, 20 minutes = 41 clips of $2,439 — every clip
  ;; clears the $10 floor, so the 30s spacing floor binds and the schedule is unchanged:
  ;; price cost 19.07 bp ≈ $190.73 (never the one-shot $1,020).
  (let [split-cost {:source :snapshot :slippage-bps 102 :estimated-slippage-usd 1020
                    :spread-bps 2 :spread-usd 20 :impact-bps 100 :impact-usd 1000
                    :notional-usd 100000
                    :fee-bps 4 :estimated-fee-usd 40
                    :maker-fee-bps 1 :maker-fee-usd 10}
        plan* (-> plan
                  (assoc-in [:rows 0 :delta-notional-usd] 100000)
                  (assoc-in [:rows 0 :cost] split-cost))
        node (view {:plan plan* :overrides {"perp:EWZ" :twap} :open-row "perp:EWZ"})
        editor (h/find-by-data-role node "portfolio-optimizer-execution-order-editor-perp-EWZ")
        breakdown (h/find-by-data-role node "portfolio-optimizer-execution-cost-breakdown")
        text (h/node-text breakdown)]
    (is (some? breakdown))
    (is (str/includes? text "Book impact (worked)"))
    (is (str/includes? text "worked as 41 clips over 20m"))
    (is (str/includes? text "190.73"))
    (is (not (str/includes? text "1,020")))
    (is (str/includes? (h/node-text editor) "41 clips · one every 30s"))))

(deftest twap-editor-states-the-real-clip-spacing-for-a-small-order-test
  ;; 30s is the venue's spacing FLOOR: since 2026-08-01 it spaces clips wider rather than
  ;; let one fall under $10. A $150 leg over 10 minutes is 15 clips ~43s apart, so the old
  ;; unconditional "one every 30s" copy (and the 21 clips it implied) was simply false.
  (let [split-cost {:source :snapshot :slippage-bps 102 :estimated-slippage-usd 1.53
                    :spread-bps 2 :spread-usd 0.03 :impact-bps 100 :impact-usd 1.5
                    :notional-usd 150
                    :fee-bps 4 :estimated-fee-usd 0.06
                    :maker-fee-bps 1 :maker-fee-usd 0.015}
        plan* (-> plan
                  (assoc-in [:rows 0 :delta-notional-usd] 150)
                  (assoc-in [:rows 0 :cost] split-cost))
        node (view {:plan plan* :overrides {"perp:EWZ" :twap} :open-row "perp:EWZ"})
        editor (h/find-by-data-role node "portfolio-optimizer-execution-order-editor-perp-EWZ")
        breakdown (h/find-by-data-role node "portfolio-optimizer-execution-cost-breakdown")
        editor-text (h/node-text editor)]
    (is (str/includes? editor-text "15 clips · one every 43s"))
    (is (not (str/includes? editor-text "21 clips")))
    (is (not (str/includes? editor-text "one every 30s")))
    ;; the cost equation slices against the same 15 clips it names
    (is (str/includes? (h/node-text breakdown) "worked as 15 clips over 10m"))))

(deftest depth-floor-row-disclosure-test
  ;; A depth-overrun row must read as a lower bound everywhere: "≥" on the row's cost
  ;; cell, the coverage disclosure in the editor's cost-basis line, and the floor note
  ;; under the breakdown equation.
  (let [floor-cost {:source :depth-extrapolated :slippage-bps 999.1
                    :estimated-slippage-usd 819
                    :spread-bps 10 :spread-usd 8.2 :impact-bps 989.1 :impact-usd 810.8
                    :notional-usd 8200
                    :depth-status :insufficient-visible-depth
                    :estimate-floor? true :depth-overrun 409 :depth-coverage 0.002445
                    :visible-notional-usd 20
                    :fee-bps 4.5 :estimated-fee-usd 3.7
                    :maker-fee-bps 1.5 :maker-fee-usd 1.2}
        plan* (assoc-in plan [:rows 0 :cost] floor-cost)
        node (view {:plan plan* :overrides {"perp:EWZ" :market} :open-row "perp:EWZ"})
        ewz (h/find-by-data-role node "portfolio-optimizer-execution-order-row-perp-EWZ")
        editor (h/find-by-data-role node "portfolio-optimizer-execution-order-editor-perp-EWZ")
        note (h/find-by-data-role node "portfolio-optimizer-execution-cost-note")]
    (is (str/includes? (h/node-text ewz) "≥"))
    (is (str/includes? (h/node-text editor) "book covers 0.2% of order"))
    (is (some? note))
    (is (str/includes? (h/node-text note) "floor"))))

(deftest resting-row-with-no-maker-fee-reads-unknown-not-zero-test
  ;; The $0-all-in regression: a preview built without the maker-fee assumption carries no
  ;; :maker-fee-usd. A passive row pays no spread or impact, so reading the missing fee as
  ;; 0 printed a confident "$0.00" all-in for a real order. Unknown must render "—".
  (let [feeless-cost {:source :snapshot :slippage-bps 45.4 :estimated-slippage-usd 0.4
                      :notional-usd 87 :fee-bps 4.5 :estimated-fee-usd 0.04}
        plan* (assoc-in plan [:rows 0 :cost] feeless-cost)
        node (view {:plan plan* :overrides {"perp:EWZ" :passive} :open-row "perp:EWZ"})
        breakdown (h/find-by-data-role node "portfolio-optimizer-execution-cost-breakdown")
        note (h/find-by-data-role node "portfolio-optimizer-execution-cost-note")
        text (h/node-text breakdown)]
    (is (some? breakdown))
    (is (str/includes? text "Resting order"))
    (is (str/includes? text "—") "the unknown fee and all-in render as em dashes")
    (is (not (str/includes? text "$0.00")))
    (is (some? note))
    (is (str/includes? (h/node-text note) "fee unknown"))))

(deftest twap-row-without-a-book-says-it-is-the-same-flat-estimate-test
  ;; With no spread/impact split (flat fallback / prebaked bps) twap-cost passes the
  ;; one-shot estimate through unchanged — correct, but it makes TWAP read identically to
  ;; Market. The row must say why instead of showing two equal numbers with no note.
  (let [flat-cost {:source :fallback-bps :slippage-bps 25 :estimated-slippage-usd 25
                   :notional-usd 10000 :fee-bps 4.5 :estimated-fee-usd 4.5
                   :maker-fee-bps 1.5 :maker-fee-usd 1.5}
        plan* (-> plan
                  (assoc-in [:rows 0 :delta-notional-usd] 10000)
                  (assoc-in [:rows 0 :cost] flat-cost))
        node (view {:plan plan* :overrides {"perp:EWZ" :twap} :open-row "perp:EWZ"})
        breakdown (h/find-by-data-role node "portfolio-optimizer-execution-cost-breakdown")
        note (h/find-by-data-role node "portfolio-optimizer-execution-cost-note")]
    (is (str/includes? (h/node-text breakdown) "Not separable"))
    (is (some? note))
    (is (str/includes? (h/node-text note) "no live book to slice"))
    (is (str/includes? (h/node-text note) "same flat estimate a Market order pays"))))

(ns hyperopen.views.pnl-share.card-data-test
  (:require [cljs.test :refer [deftest is testing]]
            [hyperopen.views.account-info.positions-vm :as positions-vm]
            [hyperopen.views.pnl-share.card-data :as card-data]))

(def ^:private hour-ms 3600000)

(defn- position-row
  [overrides]
  {:type "oneWay"
   :dex (:dex overrides)
   :position (merge {:coin "SOL"
                     :szi "41.2"
                     :entryPx "142.08"
                     :markPx "168.90"
                     :positionValue "6958.68"
                     :unrealizedPnl "18204.11"
                     :returnOnEquity "2.146"
                     :liquidationPx "98.4"
                     :marginUsed "8481.2"
                     :leverage {:type "cross" :value 20}
                     :cumFunding {:allTime "24.5" :sinceOpen "18.4"}}
                    (dissoc overrides :dex))})

(defn- card
  ([overrides] (card overrides {}))
  ([overrides ctx]
   (card-data/position-card-data
    (positions-vm/position-row-vm (position-row overrides))
    (merge {:owner-address "0x1234567890abcdef1234567890abcdef12345678"
            :referral-code "sleepy"
            :site-origin "https://hyperopen.xyz"
            :now-ms 1787356320000
            :fills []}
           ctx))))

(deftest winning-long-card-reads-like-its-table-row
  (let [data (card {})]
    (is (true? (:winning? data)))
    (is (= :long (:side data)))
    (is (= "SOL" (:coin-label data)))
    (is (= "S" (:monogram data)))
    (is (= "LONG 20X" (:leverage-label data)))
    (is (= "+214.6%" (:roe-text data)))
    (is (= "+$18,204.11" (:pnl-text data)))
    (is (= "$142.08" (:entry-price-text data))
        "the card quotes prices exactly as the Positions row does")
    (is (= "$168.90" (:mark-price-text data)))
    (is (= "41.2 SOL" (:size-text data)))
    (is (nil? (:loss-pill data)))
    (is (= "UNREALIZED P&L" (:roe-label data))
        "the card must say the figure is unrealized: the position is still open")
    (is (= "Long 20x SOL · open position, marked live" (:summary-line data)))))

(deftest losing-position-is-labelled-as-a-loss-not-recoloured-silently
  (let [data (card {:unrealizedPnl "-3946.44" :returnOnEquity "-0.384"})]
    (is (false? (:winning? data)))
    (is (= "-38.4%" (:roe-text data)))
    (is (= "-$3,946.44" (:pnl-text data)))
    (is (= "CERTIFIED L" (:loss-pill data)))
    (is (= "UNREALIZED LOSS" (:roe-label data)))
    (testing "the minus sign is in the text, so the loss does not depend on colour"
      (is (re-find #"^-" (:roe-text data)))
      (is (re-find #"^-" (:pnl-text data))))))

(deftest short-positions-say-short
  (let [data (card {:szi "-41.2"})]
    (is (= :short (:side data)))
    (is (= "SHORT 20X" (:leverage-label data)))))

(deftest named-dex-positions-carry-a-venue-chip-and-a-base-symbol
  (let [data (card {:coin "xyz:NVDA" :dex "xyz"})]
    (is (= "NVDA" (:coin-label data)))
    (is (= "xyz" (:dex-label data)))
    (is (= "N" (:monogram data)))))

(deftest held-is-omitted-when-no-opening-fill-is-loaded
  (testing "no fills at all"
    (is (nil? (:held-text (card {})))))
  (testing "fills exist but none started from flat, so the episode start is unknown"
    (is (nil? (:held-text (card {} {:fills [{:coin "SOL" :time 1787270000000
                                             :startPosition "12.5" :sz "4" :side "B"}]}))))))

(deftest held-is-derived-from-the-most-recent-flat-start-fill
  (let [now 1787356320000
        data (card {} {:now-ms now
                       :fills [{:coin "SOL" :time (- now (* 240 hour-ms))
                                :startPosition "0" :sz "10" :side "B"}
                               {:coin "SOL" :time (- now (* 100 hour-ms))
                                :startPosition "0" :sz "20" :side "B"}
                               {:coin "SOL" :time (- now (* 4 hour-ms))
                                :startPosition "20" :sz "21.2" :side "B"}
                               {:coin "BTC" :time (- now hour-ms)
                                :startPosition "0" :sz "1" :side "B"}]})]
    (is (= "4d 04h" (:held-text data))
        "held must measure from the last flat start, not the last fill")))

(deftest held-formats-sub-day-and-sub-hour-durations
  (is (= "11h 42m" (card-data/format-held (+ (* 11 hour-ms) (* 42 60000)))))
  (is (= "7m" (card-data/format-held (* 7 60000))))
  (is (nil? (card-data/format-held 0)))
  (is (nil? (card-data/format-held nil))))

(deftest a-wallet-with-no-referral-code-gets-the-bare-site-link
  (let [data (card {} {:referral-code nil})]
    (is (false? (:has-referral-code? data)))
    (is (= "hyperopen.xyz" (:join-label data)))
    (is (= "https://hyperopen.xyz" (:join-link data)))
    (is (= "hyperopen.xyz" (:site-label data)))))

(deftest a-referral-code-becomes-a-join-link
  (let [data (card {})]
    (is (true? (:has-referral-code? data)))
    (is (= "hyperopen.xyz/join/sleepy" (:join-label data)))
    (is (= "https://hyperopen.xyz/join/sleepy" (:join-link data)))))

(deftest the-site-label-follows-wherever-the-app-is-actually-served-from
  (is (= "hyperopen.pages.dev"
         (:site-label (card {} {:site-origin "https://hyperopen.pages.dev"}))))
  (is (= "hyperopen.xyz"
         (:site-label (card {} {:site-origin nil})))))

(deftest toggled-off-fields-are-absent-rather-than-blank
  (let [data (card {} {:options {:show-prices? false
                                 :show-funding? false
                                 :show-handle? false}})]
    (is (nil? (:entry-price-text data)))
    (is (nil? (:mark-price-text data)))
    (is (nil? (:funding-text data)))
    (is (nil? (:handle-text data)))
    (testing "the fields that are not toggleable survive"
      (is (some? (:roe-text data)))
      (is (some? (:size-text data))))))

(deftest handle-is-a-short-address-and-never-a-truncated-nonsense-string
  (is (= "0x1234…5678" (:handle-text (card {}))))
  (is (nil? (card-data/short-address "0xabc")))
  (is (nil? (card-data/short-address nil))))

(deftest funding-comes-through-already-signed
  (is (= "-$18.40" (:funding-text (card {})))))

(deftest timestamp-always-states-utc
  (is (= "2026-08-21 23:52 UTC" (:timestamp-text (card {})))))

(deftest file-name-is-safe-and-descriptive
  (is (= "hyperopen-sol-long-2026-08-21.png" (:file-name (card {}))))
  (is (= "hyperopen-nvda-short-2026-08-21.png"
         (:file-name (card {:coin "xyz:NVDA" :dex "xyz" :szi "-3"})))))

(deftest unknown-templates-fall-back-to-the-default
  (is (= :neon-arrow (:template (card {} {:template :not-a-template}))))
  (is (= :number-hero (:template (card {} {:template :number-hero}))))
  (is (= :neon-arrow (:template (card {} {:template nil})))))

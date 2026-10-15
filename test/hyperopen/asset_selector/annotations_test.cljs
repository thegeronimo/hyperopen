(ns hyperopen.asset-selector.annotations-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.asset-selector.annotations :as annotations]))

;; Descriptions copied verbatim from Hyperliquid `perpAnnotation` on 2026-10-15.
(def ^:private live-descriptions
  {"xyz:SPCX" "SPCX references 1 share of Space Exploration Technologies Corp. Class A common stock (Nasdaq: SPCX), quoted in USD. SpaceX develops launch vehicles, spacecraft, satellite communications, and AI technology."
   "xyz:TSLA" "TSLA references 1 share of Tesla, Inc. common stock (Nasdaq: TSLA), quoted in USD. Tesla designs electric vehicles, energy-storage systems, and related products."
   "xyz:ARM" "ARM references 1 Arm Holdings plc ADS (Nasdaq: ARM), representing 1 ordinary share, quoted in USD. Arm designs and licenses processor architectures and related computing technology."
   "xyz:NBIS" "NBIS references 1 Nebius Group N.V. Class A ordinary share (Nasdaq: NBIS), quoted in USD. Nebius provides GPU-based cloud and AI infrastructure."
   "xyz:SMSN" "SAMSUNG references 1 Samsung Electronics Co., Ltd. common share (KRX: 005930). The oracle converts the KRW share price to USD at the prevailing USD/KRW FX rate. Samsung Electronics makes semiconductors, displays, and consumer electronics."
   "xyz:ZHIPU" "ZHIPU references 1 H-share of Knowledge Atlas Technology Joint Stock Company Limited (HKEX: 2513). The oracle converts the HKD share price to USD at the prevailing USD/HKD FX rate. The company, also known as Zhipu AI or Z.ai, develops AI models and applications."
   "xyz:CL" "WTIOIL references the USD price of 1 barrel of WTI Light Sweet Crude Oil. WTI serves as a primary global benchmark for oil prices due to its high quality (low density, low sulfur)."
   "xyz:GOLD" "GOLD references the USD spot price of 1 troy ounce of gold (XAU/USD)."
   "para:GDX" "GDX tracks the value of one share of the VanEck Gold Miners ETF. GDX provides diversified exposure to global companies involved primarily in gold mining, including major producers and operators whose earnings are leveraged to changes in the price of gold."
   "para:10Y" "10Y tracks the yield on the most recently issued (on-the-run) 10-year US Treasury note, expressed in percent. The standard reference for long-term US dollar borrowing costs."
   "km:US500" "Provides broad exposure to 500 of the largest U.S. companies by market capitalisation, spanning all major sectors of the US equity market. Please refer to km docs for further info on oracle reference pricing sources and design."
   "flx:BTC" "A perpetual future contract tracking the price of Bitcoin (BTC). Each contract represents 1 BTC, the largest cryptocurrency by market capitalization. Bitcoin operates on a decentralized blockchain network and is widely used as a digital store of value."
   "vntl:SPACEX" "SPACEX tracks the total valuation of SpaceX, in billions. E.g. if 1 SPACEX = $500, then the implied total valuation of the company is $500B."})

(deftest issuer-from-description-reads-every-single-share-phrasing-test
  (is (= "Space Exploration Technologies Corp."
         (annotations/issuer-from-description (live-descriptions "xyz:SPCX"))))
  (is (= "Tesla, Inc."
         (annotations/issuer-from-description (live-descriptions "xyz:TSLA"))))
  (is (= "Arm Holdings plc"
         (annotations/issuer-from-description (live-descriptions "xyz:ARM"))))
  (is (= "Nebius Group N.V."
         (annotations/issuer-from-description (live-descriptions "xyz:NBIS"))))
  (is (= "Samsung Electronics Co., Ltd."
         (annotations/issuer-from-description (live-descriptions "xyz:SMSN"))))
  (is (= "Knowledge Atlas Technology Joint Stock Company Limited"
         (annotations/issuer-from-description (live-descriptions "xyz:ZHIPU"))))
  (is (= "VanEck Gold Miners ETF"
         (annotations/issuer-from-description (live-descriptions "para:GDX"))))
  (testing "non-share contracts name no issuer"
    (doseq [coin ["xyz:CL" "xyz:GOLD" "para:10Y" "km:US500" "flx:BTC" "vntl:SPACEX"]]
      (is (nil? (annotations/issuer-from-description (live-descriptions coin))) coin)))
  (is (nil? (annotations/issuer-from-description "")))
  (is (nil? (annotations/issuer-from-description nil))))

(deftest description-first-clause-drops-ticker-leads-test
  (is (= "The USD price of 1 barrel of WTI Light Sweet Crude Oil"
         (annotations/description-first-clause (live-descriptions "xyz:CL"))))
  (is (= "The USD spot price of 1 troy ounce of gold (XAU/USD)"
         (annotations/description-first-clause (live-descriptions "xyz:GOLD"))))
  (is (= "Bitcoin (BTC)"
         (annotations/description-first-clause (live-descriptions "flx:BTC"))))
  (is (= "The total valuation of SpaceX, in billions"
         (annotations/description-first-clause (live-descriptions "vntl:SPACEX"))))
  (testing "abbreviations such as U.S. do not end the sentence, and long clauses clip on a word"
    (let [clause (annotations/description-first-clause (live-descriptions "km:US500"))]
      (is (= "Broad exposure to 500 of the largest U.S. companies by…" clause))
      (is (<= (count clause) 57))))
  (is (nil? (annotations/description-first-clause "   "))))

(deftest normalize-category-folds-deployer-spellings-test
  (is (= :stocks (annotations/normalize-category "stock")))
  (is (= :stocks (annotations/normalize-category "stocks")))
  (is (= :fx (annotations/normalize-category "FX")))
  (is (= :fx (annotations/normalize-category "fx")))
  (is (= :preipo (annotations/normalize-category "preipo")))
  (is (= :weather (annotations/normalize-category " Weather ")))
  (is (nil? (annotations/normalize-category "")))
  (is (= "Pre-IPO" (annotations/category-label :preipo)))
  (is (= "Indices" (annotations/category-plural-label :indices)))
  (is (= "Weather" (annotations/category-label :weather))))

(deftest normalize-concise-payload-test
  (is (= {"xyz:SPCX" {:category :stocks
                      :display-name "SPCX"
                      :keywords ["spacex" "ai"]}
          "km:EUR" {:category :fx}
          "io:OAI" {:category :preipo
                    :display-name "OPENAI"
                    :keywords ["openai" "oai"]}}
         (annotations/normalize-concise-payload
          [["xyz:SPCX" {:category "stocks" :displayName "SPCX" :keywords ["spacex" "AI"]}]
           ["km:EUR" {:category "FX"}]
           ["io:OAI" {:category "preipo" :displayName "OPENAI" :keywords ["openai" "oai" " "]}]
           ["" {:category "stocks"}]
           ["bad"]
           nil])))
  (is (= {} (annotations/normalize-concise-payload nil))))

(deftest normalize-annotation-treats-null-and-blank-descriptions-as-absent-test
  (is (nil? (annotations/normalize-annotation nil)))
  (is (= {:category :preipo :display-name "ANTH" :keywords ["anthropic"]}
         (annotations/normalize-annotation
          {:category "preipo" :description "" :displayName "ANTH" :keywords ["anthropic"]}))))

(deftest projection-tracks-concise-and-detail-loads-test
  (let [state {}]
    (is (annotations/concise-load-needed? state))
    (let [loading (annotations/begin-concise-load state)
          loaded (annotations/apply-concise-success
                  loading
                  [["xyz:SPCX" {:category "stocks" :displayName "SPCX"}]])
          failed (annotations/apply-concise-error loading)]
      (is (= :loading (get-in loading [:perp-annotations :concise-status])))
      (is (not (annotations/concise-load-needed? loading)))
      (is (= :loaded (get-in loaded [:perp-annotations :concise-status])))
      (is (= {:category :stocks :display-name "SPCX"}
             (get-in loaded [:perp-annotations :by-coin "xyz:SPCX"])))
      (is (annotations/concise-load-needed? failed)
          "a failed bulk load is retried on the next market change")))
  (let [coin "xyz:SPCX"
        loading (annotations/begin-detail-load {} coin)
        loaded (annotations/apply-detail-success loading coin {:category "stocks"
                                                               :description (live-descriptions coin)})
        missing (annotations/apply-detail-success loading "cash:HOOD" nil)
        failed (annotations/apply-detail-error loading coin)]
    (is (annotations/detail-load-needed? {} coin))
    (is (not (annotations/detail-load-needed? loading coin)))
    (is (not (annotations/detail-load-needed? loaded coin)))
    (is (not (annotations/detail-load-needed? missing "cash:HOOD"))
        "a deployer that published nothing is not re-requested")
    (is (annotations/detail-load-needed? failed coin))
    (is (= (live-descriptions coin)
           (get-in loaded [:perp-annotations :detail-by-coin coin :annotation :description])))))

(deftest annotatable-coin-is-hip3-only-test
  (is (annotations/annotatable-coin? "xyz:SPCX"))
  (is (not (annotations/annotatable-coin? "BTC")))
  (is (not (annotations/annotatable-coin? "@107")))
  (is (not (annotations/annotatable-coin? nil))))

(defn- state-with
  [coin {:keys [concise detail detail-status full-names]}]
  (cond-> {:perp-annotations {:concise-status :loaded
                              :by-coin (if concise {coin concise} {})
                              :detail-by-coin (if detail-status
                                                {coin {:status detail-status
                                                       :annotation detail}}
                                                {})}}
    full-names (assoc :perp-dex-full-name-by-name full-names)))

(deftest header-metadata-degrades-through-every-coverage-state-test
  (testing "full annotation: issuer plus category"
    (let [meta (annotations/header-metadata
                (state-with "xyz:SPCX" {:concise {:category :stocks :display-name "SPCX"}
                                        :detail {:category :stocks
                                                 :description (live-descriptions "xyz:SPCX")}
                                        :detail-status :loaded
                                        :full-names {"xyz" "XYZ"}})
                "xyz:SPCX")]
      (is (= "Space Exploration Technologies Corp." (:subtitle meta)))
      (is (= "Stock" (:category-label meta)))
      (is (= "XYZ" (:deployer meta)))
      (is (= (live-descriptions "xyz:SPCX") (:description meta)))
      (is (false? (:loading? meta)))))
  (testing "free-form description: first clause"
    (let [meta (annotations/header-metadata
                (state-with "km:US500" {:concise {:category :indices}
                                        :detail {:category :indices
                                                 :description (live-descriptions "km:US500")}
                                        :detail-status :loaded})
                "km:US500")]
      (is (= "Broad exposure to 500 of the largest U.S. companies by…" (:subtitle meta)))
      (is (= "Index" (:category-label meta)))))
  (testing "name only: display name differs from the ticker"
    (let [meta (annotations/header-metadata
                (state-with "io:OAI" {:concise {:category :preipo :display-name "OPENAI"}
                                      :detail {:category :preipo :display-name "OPENAI"}
                                      :detail-status :loaded})
                "io:OAI")]
      (is (= "OPENAI" (:subtitle meta)))
      (is (= "Pre-IPO" (:category-label meta)))
      (is (nil? (:description meta)))))
  (testing "nothing published: attribute the market to its deployer"
    (let [meta (annotations/header-metadata
                (state-with "cash:HOOD" {:detail-status :loaded
                                         :full-names {"cash" "dreamcash"}})
                "cash:HOOD")]
      (is (nil? (:subtitle meta)))
      (is (= "Market by dreamcash" (:fallback-label meta)))
      (is (false? (:loading? meta)))))
  (testing "not loaded yet: loading, no fallback guess"
    (let [meta (annotations/header-metadata {} "cash:HOOD")]
      (is (true? (:loading? meta)))
      (is (nil? (:fallback-label meta)))))
  (is (nil? (annotations/header-metadata {} "BTC"))))

(deftest annotate-markets-merges-only-annotated-rows-test
  (let [markets [{:key "perp:xyz:SPCX" :coin "xyz:SPCX" :base "SPCX"}
                 {:key "perp:io:OAI" :coin "io:OAI" :base "OAI"}
                 {:key "perp:BTC" :coin "BTC" :base "BTC"}]
        annotations* {:by-coin {"xyz:SPCX" {:category :stocks :display-name "SPCX" :keywords ["spacex" "ai"]}
                                "io:OAI" {:category :preipo :display-name "OPENAI" :keywords ["openai"]}}
                      :detail-by-coin {"xyz:SPCX" {:status :loaded
                                                   :annotation {:description (live-descriptions "xyz:SPCX")}}}}
        [spcx oai btc] (annotations/annotate-markets markets annotations*)]
    (is (= :stocks (:asset-class spcx)))
    (is (= ["spacex" "ai"] (:search-keywords spcx)))
    (is (= "Space Exploration Technologies Corp." (:descriptor spcx)))
    (is (= "OPENAI" (:descriptor oai)))
    (is (= {:key "perp:BTC" :coin "BTC" :base "BTC"} btc)))
  (let [markets [{:coin "xyz:SPCX"}]]
    (is (identical? markets (annotations/annotate-markets markets nil)))))

(ns hyperopen.outcomes.test-support.fixture
  "A slice of the live Hyperliquid outcomeMeta and spot asset contexts captured
   2026-11-09 16:25 UTC: one NBA game, a soccer match question, the NBA field,
   the Fed decision, the native BTC daily binary and range, a BTC price-touch
   ladder, a two-venue BTC strike pair, an NVDA single, the Anthropic IPO
   contracts and an invented unknown template.")

(def outcome-meta
  {:outcomes
   [{:outcome 5112 :name "template:companyIpoConfirmed" :description "company:Anthropic|dateTime:20261130-2359" :sideSpecs [{:name "template:Yes"} {:name "template:No"}] :quoteToken "USDC" :venue "txyz"}
    {:outcome 7362 :name "template:companyIpoFirstDayMarketCap" :description "company:Anthropic|listingDeadline:20261231-2359|marketCapThresholdB:1500" :sideSpecs [{:name "template:Yes"} {:name "template:No"}] :quoteToken "USDC" :venue "txyz"}
    {:outcome 7364 :name "template:companyIpoFirstDayMarketCap" :description "company:Anthropic|listingDeadline:20261231-2359|marketCapThresholdB:1750" :sideSpecs [{:name "template:Yes"} {:name "template:No"}] :quoteToken "USDC" :venue "txyz"}
    {:outcome 8572 :name "template:companyIpoFirstDayMarketCap" :description "company:Anthropic|listingDeadline:20270101-0000|marketCapThresholdB:1750" :sideSpecs [{:name "template:Yes"} {:name "template:No"}] :quoteToken "USDC" :venue "out" :deployerFeeScale "1.0"}
    {:outcome 8573 :name "template:companyIpoFirstDayMarketCap" :description "company:Anthropic|listingDeadline:20270101-0000|marketCapThresholdB:1500" :sideSpecs [{:name "template:Yes"} {:name "template:No"}] :quoteToken "USDC" :venue "out" :deployerFeeScale "1.0"}
    {:outcome 11491 :name "template fallback" :description "other" :sideSpecs [{:name "Yes"} {:name "No"}] :quoteToken "USDC" :venue "out" :deployerFeeScale "1.0"}
    {:outcome 11492 :name "template:sportsTournamentParticipant" :description "participant:Oklahoma City Thunder" :sideSpecs [{:name "Yes"} {:name "No"}] :quoteToken "USDC" :venue "out" :deployerFeeScale "1.0"}
    {:outcome 11493 :name "template:sportsTournamentParticipant" :description "participant:San Antonio Spurs" :sideSpecs [{:name "Yes"} {:name "No"}] :quoteToken "USDC" :venue "out" :deployerFeeScale "1.0"}
    {:outcome 11494 :name "template:sportsTournamentParticipant" :description "participant:New York Knicks" :sideSpecs [{:name "Yes"} {:name "No"}] :quoteToken "USDC" :venue "out" :deployerFeeScale "1.0"}
    {:outcome 11495 :name "template:sportsTournamentParticipant" :description "participant:Philadelphia 76ers" :sideSpecs [{:name "Yes"} {:name "No"}] :quoteToken "USDC" :venue "out" :deployerFeeScale "1.0"}
    {:outcome 11496 :name "template:sportsTournamentParticipant" :description "participant:Minnesota Timberwolves" :sideSpecs [{:name "Yes"} {:name "No"}] :quoteToken "USDC" :venue "out" :deployerFeeScale "1.0"}
    {:outcome 11497 :name "template:sportsTournamentParticipant" :description "participant:Boston Celtics" :sideSpecs [{:name "Yes"} {:name "No"}] :quoteToken "USDC" :venue "out" :deployerFeeScale "1.0"}
    {:outcome 11498 :name "template:sportsTournamentParticipant" :description "participant:Miami Heat" :sideSpecs [{:name "Yes"} {:name "No"}] :quoteToken "USDC" :venue "out" :deployerFeeScale "1.0"}
    {:outcome 11499 :name "template:sportsTournamentParticipant" :description "participant:Cleveland Cavaliers" :sideSpecs [{:name "Yes"} {:name "No"}] :quoteToken "USDC" :venue "out" :deployerFeeScale "1.0"}
    {:outcome 19821 :name "template:companyIpoFirstDayMarketCap" :description "company:Anthropic|listingDeadline:20270501-0359|marketCapThresholdB:1500" :sideSpecs [{:name "template:Yes"} {:name "template:No"}] :quoteToken "USDC" :venue "skew" :deployerFeeScale "1.0"}
    {:outcome 19822 :name "template:companyIpoFirstDayMarketCap" :description "company:Anthropic|listingDeadline:20270501-0359|marketCapThresholdB:1750" :sideSpecs [{:name "template:Yes"} {:name "template:No"}] :quoteToken "USDC" :venue "skew" :deployerFeeScale "1.0"}
    {:outcome 19987 :name "template fallback" :description "other" :sideSpecs [{:name "Yes"} {:name "No"}] :quoteToken "USDC" :venue "out" :deployerFeeScale "1.0"}
    {:outcome 19988 :name "template:policyRateNoChange" :description "" :sideSpecs [{:name "Yes"} {:name "No"}] :quoteToken "USDC" :venue "out" :deployerFeeScale "1.0"}
    {:outcome 19989 :name "template:policyRateDecrease" :description "" :sideSpecs [{:name "Yes"} {:name "No"}] :quoteToken "USDC" :venue "out" :deployerFeeScale "1.0"}
    {:outcome 19990 :name "template:policyRateIncrease" :description "" :sideSpecs [{:name "Yes"} {:name "No"}] :quoteToken "USDC" :venue "out" :deployerFeeScale "1.0"}
    {:outcome 22079 :name "template:priceTouch" :description "perp:BTC|priceDescription:BTC-USDC perp mark|seconds:3|target:85000|time:20261201-0000" :sideSpecs [{:name "template:Yes"} {:name "template:No"}] :quoteToken "USDC" :venue "out" :deployerFeeScale "1.0"}
    {:outcome 22080 :name "template:priceTouch" :description "perp:BTC|priceDescription:BTC-USDC perp mark|seconds:3|target:90000|time:20261201-0000" :sideSpecs [{:name "template:Yes"} {:name "template:No"}] :quoteToken "USDC" :venue "out" :deployerFeeScale "1.0"}
    {:outcome 22087 :name "template:priceTouch" :description "perp:BTC|priceDescription:BTC-USDC perp mark|seconds:3|target:70000|time:20261201-0000" :sideSpecs [{:name "template:Yes"} {:name "template:No"}] :quoteToken "USDC" :venue "out" :deployerFeeScale "1.0"}
    {:outcome 22088 :name "template:priceTouch" :description "perp:BTC|priceDescription:BTC-USDC perp mark|seconds:3|target:65000|time:20261201-0000" :sideSpecs [{:name "template:Yes"} {:name "template:No"}] :quoteToken "USDC" :venue "out" :deployerFeeScale "1.0"}
    {:outcome 27037 :name "template:sportsContestWinner" :description "competition:NBA|contestType:Game|countedPlay:the full game including overtime|officialSource:National Basketball Association (NBA)|participantA:Detroit Pistons|participantB:Washington Wizards|resolutionDeadline:20261117-0000|scheduledStart:20261110-0000|season:2026/27|shortNameA:DET|shortNameB:WAS|sport:Basketball|stage:Regular Season" :sideSpecs [{:name "template:{shortNameA}"} {:name "template:{shortNameB}"}] :quoteToken "USDC" :venue "out" :deployerFeeScale "1.0"}
    {:outcome 29068 :name "template fallback" :description "other" :sideSpecs [{:name "Yes"} {:name "No"}] :quoteToken "USDC" :venue "out" :deployerFeeScale "1.0"}
    {:outcome 29069 :name "template:sportsContestParticipant2" :description "participant:Italy" :sideSpecs [{:name "Yes"} {:name "No"}] :quoteToken "USDC" :venue "out" :deployerFeeScale "1.0"}
    {:outcome 29070 :name "template:sportsContestDraw2" :description "" :sideSpecs [{:name "Yes"} {:name "No"}] :quoteToken "USDC" :venue "out" :deployerFeeScale "1.0"}
    {:outcome 29071 :name "template:sportsContestParticipant2" :description "participant:France" :sideSpecs [{:name "Yes"} {:name "No"}] :quoteToken "USDC" :venue "out" :deployerFeeScale "1.0"}
    {:outcome 29614 :name "template:binaryPrice" :description "perp:xyz:NVDA|priceDescription:xyz:NVDA-USDC perp mark|seconds:60|threshold:183.33|time:20261109-2100" :sideSpecs [{:name "template:Yes"} {:name "template:No"}] :quoteToken "USDC" :venue "out" :deployerFeeScale "1.0"}
    {:outcome 30011 :name "Recurring" :description "class:priceBinary|underlying:BTC|expiry:20261110-0600|targetPrice:74456|period:1d" :sideSpecs [{:name "Yes"} {:name "No"}] :quoteToken "USDC"}
    {:outcome 30015 :name "Recurring Fallback" :description "other" :sideSpecs [{:name "Yes"} {:name "No"}] :quoteToken "USDC"}
    {:outcome 30016 :name "Recurring Named Outcome" :description "index:0" :sideSpecs [{:name "Yes"} {:name "No"}] :quoteToken "USDC"}
    {:outcome 30017 :name "Recurring Named Outcome" :description "index:1" :sideSpecs [{:name "Yes"} {:name "No"}] :quoteToken "USDC"}
    {:outcome 30018 :name "Recurring Named Outcome" :description "index:2" :sideSpecs [{:name "Yes"} {:name "No"}] :quoteToken "USDC"}
    {:outcome 30516 :name "template:binaryPrice" :description "perp:BTC|priceDescription:the Hyperliquid BTC perp trade, 1-minute candle|seconds:180|threshold:75150|time:20261109-1630" :sideSpecs [{:name "template:Yes"} {:name "template:No"}] :quoteToken "USDC" :venue "fox"}
    {:outcome 30520 :name "template:binaryPrice" :description "perp:BTC|priceDescription:BTC-USDC perp mark|seconds:60|threshold:75101|time:20261109-1630" :sideSpecs [{:name "template:Yes"} {:name "template:No"}] :quoteToken "USDC" :venue "out" :deployerFeeScale "1.0"}
    {:outcome 99999 :name "template:weatherHighTemperature" :description "city:Austin|thresholdF:95|dateTime:20261115-2300" :sideSpecs [{:name "template:Yes"} {:name "template:No"}] :quoteToken "USDC" :venue "out"}]
   :questions
   [{:question 614 :name "template:sportsContestResult" :description "competition:UEFA Nations League|contestType:Match|countedPlay:regulation time, 90 minutes plus stoppage time|officialSource:Union of European Football Associations|participantA:Italy|participantB:France|resolutionDeadline:20261119-1945|scheduledStart:20261112-1945|season:2026/27|sport:Soccer|stage:A, Matchday 5" :fallbackOutcome 29068 :namedOutcomes [29069 29070 29071] :settledNamedOutcomes []}
    {:question 430 :name "template:sportsTournamentWinner" :description "competition:NBA |officialSource:National Basketball Association|resolutionDeadline:20270630-2359|season:2026/2027|sport:Basketball" :fallbackOutcome 11491 :namedOutcomes [11492 11493 11494 11495 11496 11497 11498 11499] :settledNamedOutcomes []}
    {:question 545 :name "template:policyRateDecision" :description "decisionDeadline:20270127-1900|decisionLabel:December 2026|institution:Federal Open Market Committee|officialSource:Federal Open Market Committee|policyMeasure:the upper bound of the target range for the federal funds rate|scheduledDecision:20261209-1900" :fallbackOutcome 19987 :namedOutcomes [19988 19989 19990] :settledNamedOutcomes []}
    {:question 627 :name "Recurring" :description "class:priceBucket|underlying:BTC|expiry:20261110-0600|priceThresholds:72967,75945|period:1d" :fallbackOutcome 30015 :namedOutcomes [30016 30017 30018] :settledNamedOutcomes []}]}
)

(def asset-ctxs
  [{:prevDayPx "0.84" :dayNtlVlm "0.0" :markPx "0.81446" :midPx "0.789465" :circulatingSupply "1282.0" :coin "#51120" :totalSupply "184467440737095.53125" :dayBaseVlm "0.0"}
   {:prevDayPx "0.16" :dayNtlVlm "0.0" :markPx "0.18554" :midPx "0.210535" :circulatingSupply "1282.0" :coin "#51121" :totalSupply "184467440737095.53125" :dayBaseVlm "0.0"}
   {:prevDayPx "0.5" :dayNtlVlm "0.0" :markPx "0.6255" :midPx "0.6255" :circulatingSupply "613.0" :coin "#73620" :totalSupply "184467440737095.53125" :dayBaseVlm "0.0"}
   {:prevDayPx "0.5" :dayNtlVlm "0.0" :markPx "0.3745" :midPx "0.3745" :circulatingSupply "613.0" :coin "#73621" :totalSupply "184467440737095.53125" :dayBaseVlm "0.0"}
   {:prevDayPx "0.9998" :dayNtlVlm "0.0" :markPx "0.5367" :midPx "0.5367" :circulatingSupply "22.0" :coin "#73640" :totalSupply "184467440737095.53125" :dayBaseVlm "0.0"}
   {:prevDayPx "0.0002" :dayNtlVlm "0.0" :markPx "0.4633" :midPx "0.4633" :circulatingSupply "22.0" :coin "#73641" :totalSupply "184467440737095.53125" :dayBaseVlm "0.0"}
   {:prevDayPx "0.35104" :dayNtlVlm "1077.23455" :markPx "0.42243" :midPx "0.556215" :circulatingSupply "7427.0" :coin "#85720" :totalSupply "184467440737095.53125" :dayBaseVlm "1994.0"}
   {:prevDayPx "0.64896" :dayNtlVlm "916.76545" :markPx "0.57757" :midPx "0.443785" :circulatingSupply "7427.0" :coin "#85721" :totalSupply "184467440737095.53125" :dayBaseVlm "1994.0"}
   {:prevDayPx "0.57001" :dayNtlVlm "101945.92384" :markPx "0.57835" :midPx "0.578345" :circulatingSupply "19409.0" :coin "#85730" :totalSupply "184467440737095.53125" :dayBaseVlm "175506.0"}
   {:prevDayPx "0.42999" :dayNtlVlm "73560.07616" :markPx "0.42165" :midPx "0.421655" :circulatingSupply "19409.0" :coin "#85731" :totalSupply "184467440737095.53125" :dayBaseVlm "175506.0"}
   {:prevDayPx "0.0" :dayNtlVlm "0.0" :markPx "0.5" :midPx nil :circulatingSupply "17895.0" :coin "#114910" :totalSupply "184467440737095.53125" :dayBaseVlm "0.0"}
   {:prevDayPx "0.0" :dayNtlVlm "0.0" :markPx "0.5" :midPx nil :circulatingSupply "0.0" :coin "#114911" :totalSupply "184467440737095.53125" :dayBaseVlm "0.0"}
   {:prevDayPx "0.159" :dayNtlVlm "27542.10081" :markPx "0.15632" :midPx "0.156325" :circulatingSupply "26210.0" :coin "#114920" :totalSupply "184467440737095.53125" :dayBaseVlm "181205.0"}
   {:prevDayPx "0.841" :dayNtlVlm "153662.89919" :markPx "0.84368" :midPx "0.843675" :circulatingSupply "8315.0" :coin "#114921" :totalSupply "184467440737095.53125" :dayBaseVlm "181205.0"}
   {:prevDayPx "0.2148" :dayNtlVlm "60623.53634" :markPx "0.21513" :midPx "0.215135" :circulatingSupply "22885.0" :coin "#114930" :totalSupply "184467440737095.53125" :dayBaseVlm "281824.0"}
   {:prevDayPx "0.7852" :dayNtlVlm "221200.4636600001" :markPx "0.78487" :midPx "0.784865" :circulatingSupply "4990.0" :coin "#114931" :totalSupply "184467440737095.53125" :dayBaseVlm "281824.0"}
   {:prevDayPx "0.06558" :dayNtlVlm "2159.39472" :markPx "0.066065" :midPx "0.063525" :circulatingSupply "25957.0" :coin "#114940" :totalSupply "184467440737095.53125" :dayBaseVlm "32544.0"}
   {:prevDayPx "0.93442" :dayNtlVlm "30384.60528" :markPx "0.933935" :midPx "0.936475" :circulatingSupply "8062.0" :coin "#114941" :totalSupply "184467440737095.53125" :dayBaseVlm "32544.0"}
   {:prevDayPx "0.13172" :dayNtlVlm "10136.16335" :markPx "0.127815" :midPx "0.127815" :circulatingSupply "19010.0" :coin "#114950" :totalSupply "184467440737095.53125" :dayBaseVlm "78131.0"}
   {:prevDayPx "0.86828" :dayNtlVlm "67994.83665" :markPx "0.872185" :midPx "0.872185" :circulatingSupply "1115.0" :coin "#114951" :totalSupply "184467440737095.53125" :dayBaseVlm "78131.0"}
   {:prevDayPx "0.05514" :dayNtlVlm "142.89727" :markPx "0.050545" :midPx "0.050915" :circulatingSupply "18605.0" :coin "#114960" :totalSupply "184467440737095.53125" :dayBaseVlm "2619.0"}
   {:prevDayPx "0.94486" :dayNtlVlm "2476.10273" :markPx "0.949455" :midPx "0.949085" :circulatingSupply "710.0" :coin "#114961" :totalSupply "184467440737095.53125" :dayBaseVlm "2619.0"}
   {:prevDayPx "0.06502" :dayNtlVlm "6397.82984" :markPx "0.06374" :midPx "0.063735" :circulatingSupply "18507.0" :coin "#114970" :totalSupply "184467440737095.53125" :dayBaseVlm "99722.0"}
   {:prevDayPx "0.93498" :dayNtlVlm "93324.17016" :markPx "0.93626" :midPx "0.936265" :circulatingSupply "612.0" :coin "#114971" :totalSupply "184467440737095.53125" :dayBaseVlm "99722.0"}
   {:prevDayPx "0.01088" :dayNtlVlm "0.0" :markPx "0.0155" :midPx "0.0155" :circulatingSupply "18439.0" :coin "#114980" :totalSupply "184467440737095.53125" :dayBaseVlm "0.0"}
   {:prevDayPx "0.98912" :dayNtlVlm "0.0" :markPx "0.9845" :midPx "0.9845" :circulatingSupply "544.0" :coin "#114981" :totalSupply "184467440737095.53125" :dayBaseVlm "0.0"}
   {:prevDayPx "0.01569" :dayNtlVlm "9.47676" :markPx "0.01924" :midPx "0.02455" :circulatingSupply "17903.0" :coin "#114990" :totalSupply "184467440737095.53125" :dayBaseVlm "604.0"}
   {:prevDayPx "0.98431" :dayNtlVlm "594.52324" :markPx "0.98076" :midPx "0.97545" :circulatingSupply "8.0" :coin "#114991" :totalSupply "184467440737095.53125" :dayBaseVlm "604.0"}
   {:prevDayPx "0.5183" :dayNtlVlm "2.5915" :markPx "0.56463" :midPx "0.571975" :circulatingSupply "1743.0" :coin "#198210" :totalSupply "184467440737095.53125" :dayBaseVlm "5.0"}
   {:prevDayPx "0.4817" :dayNtlVlm "2.4085" :markPx "0.43537" :midPx "0.428025" :circulatingSupply "1743.0" :coin "#198211" :totalSupply "184467440737095.53125" :dayBaseVlm "5.0"}
   {:prevDayPx "0.3818" :dayNtlVlm "25.71004" :markPx "0.2675" :midPx "0.267495" :circulatingSupply "3606.0" :coin "#198220" :totalSupply "184467440737095.53125" :dayBaseVlm "71.0"}
   {:prevDayPx "0.6182" :dayNtlVlm "45.28996" :markPx "0.7325" :midPx "0.732505" :circulatingSupply "3606.0" :coin "#198221" :totalSupply "184467440737095.53125" :dayBaseVlm "71.0"}
   {:prevDayPx "0.0" :dayNtlVlm "0.0" :markPx "0.5" :midPx nil :circulatingSupply "15934.0" :coin "#199870" :totalSupply "184467440737095.53125" :dayBaseVlm "0.0"}
   {:prevDayPx "0.0" :dayNtlVlm "0.0" :markPx "0.5" :midPx nil :circulatingSupply "0.0" :coin "#199871" :totalSupply "184467440737095.53125" :dayBaseVlm "0.0"}
   {:prevDayPx "0.21451" :dayNtlVlm "34718.30559" :markPx "0.18685" :midPx "0.18685" :circulatingSupply "23250.0" :coin "#199880" :totalSupply "184467440737095.53125" :dayBaseVlm "156308.0"}
   {:prevDayPx "0.78549" :dayNtlVlm "121589.69441" :markPx "0.81315" :midPx "0.81315" :circulatingSupply "7316.0" :coin "#199881" :totalSupply "184467440737095.53125" :dayBaseVlm "156308.0"}
   {:prevDayPx "0.01401" :dayNtlVlm "34.05519" :markPx "0.0174" :midPx "0.017395" :circulatingSupply "21746.0" :coin "#199890" :totalSupply "184467440737095.53125" :dayBaseVlm "2074.0"}
   {:prevDayPx "0.98599" :dayNtlVlm "2039.94481" :markPx "0.9826" :midPx "0.982605" :circulatingSupply "5812.0" :coin "#199891" :totalSupply "184467440737095.53125" :dayBaseVlm "2074.0"}
   {:prevDayPx "0.76567" :dayNtlVlm "111559.78041" :markPx "0.793595" :midPx "0.793595" :circulatingSupply "25312.0" :coin "#199900" :totalSupply "184467440737095.53125" :dayBaseVlm "145880.0"}
   {:prevDayPx "0.23433" :dayNtlVlm "34320.21959" :markPx "0.206405" :midPx "0.206405" :circulatingSupply "9378.0" :coin "#199901" :totalSupply "184467440737095.53125" :dayBaseVlm "145880.0"}
   {:prevDayPx "0.21444" :dayNtlVlm "20115.93015" :markPx "0.183115" :midPx "0.181985" :circulatingSupply "69341.0" :coin "#220790" :totalSupply "184467440737095.53125" :dayBaseVlm "116420.0"}
   {:prevDayPx "0.78556" :dayNtlVlm "96304.06985" :markPx "0.816885" :midPx "0.818015" :circulatingSupply "69341.0" :coin "#220791" :totalSupply "184467440737095.53125" :dayBaseVlm "116420.0"}
   {:prevDayPx "0.08508" :dayNtlVlm "10179.56859" :markPx "0.069265" :midPx "0.068055" :circulatingSupply "58269.0" :coin "#220800" :totalSupply "184467440737095.53125" :dayBaseVlm "141006.0"}
   {:prevDayPx "0.91492" :dayNtlVlm "130826.43141" :markPx "0.930735" :midPx "0.931945" :circulatingSupply "58269.0" :coin "#220801" :totalSupply "184467440737095.53125" :dayBaseVlm "141006.0"}
   {:prevDayPx "0.43527" :dayNtlVlm "16156.39597" :markPx "0.47934" :midPx "0.476265" :circulatingSupply "64236.0" :coin "#220870" :totalSupply "184467440737095.53125" :dayBaseVlm "32267.0"}
   {:prevDayPx "0.56473" :dayNtlVlm "16110.60403" :markPx "0.52066" :midPx "0.523735" :circulatingSupply "64236.0" :coin "#220871" :totalSupply "184467440737095.53125" :dayBaseVlm "32267.0"}
   {:prevDayPx "0.17944" :dayNtlVlm "13986.1549" :markPx "0.17146" :midPx "0.17452" :circulatingSupply "68031.0" :coin "#220880" :totalSupply "184467440737095.53125" :dayBaseVlm "66645.0"}
   {:prevDayPx "0.82056" :dayNtlVlm "52658.8451" :markPx "0.82854" :midPx "0.82548" :circulatingSupply "68031.0" :coin "#220881" :totalSupply "184467440737095.53125" :dayBaseVlm "66645.0"}
   {:prevDayPx "0.8173" :dayNtlVlm "184799.31845" :markPx "0.81908" :midPx "0.81894" :circulatingSupply "33333.0" :coin "#270370" :totalSupply "184467440737095.53125" :dayBaseVlm "226989.0"}
   {:prevDayPx "0.1827" :dayNtlVlm "42189.68155" :markPx "0.18092" :midPx "0.18106" :circulatingSupply "33333.0" :coin "#270371" :totalSupply "184467440737095.53125" :dayBaseVlm "226989.0"}
   {:prevDayPx "0.0" :dayNtlVlm "0.0" :markPx "0.5" :midPx nil :circulatingSupply "1496.0" :coin "#290680" :totalSupply "184467440737095.53125" :dayBaseVlm "0.0"}
   {:prevDayPx "0.0" :dayNtlVlm "0.0" :markPx "0.5" :midPx nil :circulatingSupply "0.0" :coin "#290681" :totalSupply "184467440737095.53125" :dayBaseVlm "0.0"}
   {:prevDayPx "0.21903" :dayNtlVlm "8790.51272" :markPx "0.209305" :midPx "0.210075" :circulatingSupply "2558.0" :coin "#290690" :totalSupply "184467440737095.53125" :dayBaseVlm "39783.0"}
   {:prevDayPx "0.78097" :dayNtlVlm "30992.48728" :markPx "0.790695" :midPx "0.789925" :circulatingSupply "1062.0" :coin "#290691" :totalSupply "184467440737095.53125" :dayBaseVlm "39783.0"}
   {:prevDayPx "0.25014" :dayNtlVlm "12925.34398" :markPx "0.248" :midPx "0.2464" :circulatingSupply "2327.0" :coin "#290700" :totalSupply "184467440737095.53125" :dayBaseVlm "51839.0"}
   {:prevDayPx "0.74986" :dayNtlVlm "38913.65602" :markPx "0.752" :midPx "0.7536" :circulatingSupply "831.0" :coin "#290701" :totalSupply "184467440737095.53125" :dayBaseVlm "51839.0"}
   {:prevDayPx "0.52909" :dayNtlVlm "33558.79555" :markPx "0.545755" :midPx "0.54622" :circulatingSupply "9403.0" :coin "#290710" :totalSupply "184467440737095.53125" :dayBaseVlm "63157.0"}
   {:prevDayPx "0.47091" :dayNtlVlm "29598.20445" :markPx "0.454245" :midPx "0.45378" :circulatingSupply "7907.0" :coin "#290711" :totalSupply "184467440737095.53125" :dayBaseVlm "63157.0"}
   {:prevDayPx "0.48405" :dayNtlVlm "1028.32048" :markPx "0.035395" :midPx "0.105585" :circulatingSupply "576.0" :coin "#296140" :totalSupply "184467440737095.53125" :dayBaseVlm "3386.0"}
   {:prevDayPx "0.51595" :dayNtlVlm "2357.67952" :markPx "0.964605" :midPx "0.894415" :circulatingSupply "576.0" :coin "#296141" :totalSupply "184467440737095.53125" :dayBaseVlm "3386.0"}
   {:prevDayPx "0.52" :dayNtlVlm "39047.3885" :markPx "0.72547" :midPx "0.71726" :circulatingSupply "85457.0" :coin "#300110" :totalSupply "184467440737095.53125" :dayBaseVlm "76491.0"}
   {:prevDayPx "0.48" :dayNtlVlm "37443.6115" :markPx "0.27453" :midPx "0.28274" :circulatingSupply "85457.0" :coin "#300111" :totalSupply "184467440737095.53125" :dayBaseVlm "76491.0"}
   {:prevDayPx "0.0" :dayNtlVlm "0.0" :markPx "0.5" :midPx nil :circulatingSupply "0.0" :coin "#300150" :totalSupply "184467440737095.53125" :dayBaseVlm "0.0"}
   {:prevDayPx "0.0" :dayNtlVlm "0.0" :markPx "0.5" :midPx nil :circulatingSupply "0.0" :coin "#300151" :totalSupply "184467440737095.53125" :dayBaseVlm "0.0"}
   {:prevDayPx "0.988" :dayNtlVlm "238.38722" :markPx "0.051985" :midPx "0.5" :circulatingSupply "1886.0" :coin "#300160" :totalSupply "184467440737095.53125" :dayBaseVlm "1895.0"}
   {:prevDayPx "0.012" :dayNtlVlm "1656.61278" :markPx "0.948015" :midPx "0.5" :circulatingSupply "1886.0" :coin "#300161" :totalSupply "184467440737095.53125" :dayBaseVlm "1895.0"}
   {:prevDayPx "0.988" :dayNtlVlm "120.296" :markPx "0.944" :midPx "0.5" :circulatingSupply "126.0" :coin "#300170" :totalSupply "184467440737095.53125" :dayBaseVlm "132.0"}
   {:prevDayPx "0.012" :dayNtlVlm "11.704" :markPx "0.056" :midPx "0.5" :circulatingSupply "126.0" :coin "#300171" :totalSupply "184467440737095.53125" :dayBaseVlm "132.0"}
   {:prevDayPx "0.988" :dayNtlVlm "14.82" :markPx "0.5" :midPx "0.5" :circulatingSupply "8.0" :coin "#300180" :totalSupply "184467440737095.53125" :dayBaseVlm "15.0"}
   {:prevDayPx "0.012" :dayNtlVlm "0.18" :markPx "0.5" :midPx "0.5" :circulatingSupply "8.0" :coin "#300181" :totalSupply "184467440737095.53125" :dayBaseVlm "15.0"}
   {:prevDayPx "0.0" :dayNtlVlm "0.0" :markPx "0.32213" :midPx "0.205275" :circulatingSupply "0.0" :coin "#305160" :totalSupply "184467440737095.53125" :dayBaseVlm "0.0"}
   {:prevDayPx "0.0" :dayNtlVlm "0.0" :markPx "0.67787" :midPx "0.794725" :circulatingSupply "0.0" :coin "#305161" :totalSupply "184467440737095.53125" :dayBaseVlm "0.0"}
   {:prevDayPx "0.499" :dayNtlVlm "649.698" :markPx "0.355" :midPx "0.355" :circulatingSupply "874.0" :coin "#305200" :totalSupply "184467440737095.53125" :dayBaseVlm "1302.0"}
   {:prevDayPx "0.501" :dayNtlVlm "652.302" :markPx "0.645" :midPx "0.645" :circulatingSupply "874.0" :coin "#305201" :totalSupply "184467440737095.53125" :dayBaseVlm "1302.0"}]
)

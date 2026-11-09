(ns hyperopen.outcomes.domain.templates
  "Template registry for Hyperliquid outcome contracts.

   No live contract ships display text. Its `name` is the key of the template a
   deployer used (`template:sportsContestWinner`) and its `description` holds the
   template's parameters as pipe-delimited `key:value` pairs. Even side names are
   templates (`template:{shortNameA}`). This namespace turns key + parameters
   into what a person reads: a title, side or option labels, a category, the
   clock that says when the event happens, and the rules. Unknown keys are
   humanized so a raw key never reaches the screen. Pure."
  (:require [clojure.string :as str]
            [hyperopen.outcomes.domain.clock :as clock]
            [hyperopen.outcomes.domain.labels :as labels]))

(defn parse-params
  "Description -> {parameter value}. Each field splits on its first colon, so
   `perp:xyz:SP500` keeps its namespace."
  [description]
  (into {}
        (keep (fn [field]
                (let [[k v] (str/split (str field) #":" 2)
                      k* (labels/clean k)]
                  (when (and k* (some? v))
                    [k* (str/trim v)]))))
        (str/split (str (or description "")) #"\|")))

(def ^:private template-ids
  {"sportsContestWinner" :sports-contest-winner
   "sportsContestResult" :sports-contest-result
   "sportsContestParticipant2" :sports-contest-participant
   "sportsContestDraw2" :sports-contest-draw
   "sportsTournamentWinner" :sports-tournament-winner
   "sportsTournamentParticipant" :sports-tournament-participant
   "policyRateDecision" :policy-rate-decision
   "policyRateIncrease" :policy-rate-increase
   "policyRateNoChange" :policy-rate-no-change
   "policyRateDecrease" :policy-rate-decrease
   "binaryPrice" :binary-price
   "priceTouch" :price-touch
   "companyIpoFirstDayMarketCap" :company-ipo-market-cap
   "companyIpoConfirmed" :company-ipo-confirmed})

(defn template-id
  "Keyword for a contract or question `name`. Names that are not templates
   (older or hand-written markets) are :plain; unrecognized template keys are
   :unknown."
  [name params]
  (let [name* (labels/clean name)]
    (cond
      (nil? name*) :plain
      (contains? #{"template fallback" "template:fallback" "Recurring Fallback"} name*) :fallback
      (= "Recurring Named Outcome" name*) :recurring-bucket-option
      (= "Recurring" name*) (case (get params "class")
                              "priceBinary" :recurring-price-binary
                              "priceBucket" :recurring-price-bucket
                              :unknown)
      (str/starts-with? name* "template:") (get template-ids (subs name* 9) :unknown)
      :else :plain)))

(defn render-text
  "Fill `{param}` holes and drop the `template:` prefix. Nil when the result is
   blank or still has an unfilled hole."
  [text params]
  (let [text* (some-> (labels/clean text) (str/replace #"^template:" ""))
        filled (when text*
                 (str/replace text* #"\{([^}]+)\}"
                              (fn [[_ k]]
                                (or (labels/clean (get params k))
                                    (str "{" k "}")))))]
    (when (and (seq filled)
               (not (str/includes? filled "{")))
      filled)))

(defn side-labels
  "Readable side names: `template:{shortNameA}` -> DET, `template:Yes` -> Yes."
  [side-specs params]
  (vec (map-indexed (fn [idx spec]
                      (or (render-text (or (:name spec) (:sideName spec)) params)
                          (case idx 0 "Yes" 1 "No" (str "Side " idx))))
                    (or (seq side-specs) [{:name "Yes"} {:name "No"}]))))

(defn- utc
  [time-text]
  (labels/utc-time-text (clock/parse-time-ms time-text)))

(defn- join-sentences
  [& sentences]
  (str/join " " (keep labels/clean sentences)))

(defn- schedule-sentence
  [params]
  (let [start (utc (get params "scheduledStart"))
        deadline (utc (get params "resolutionDeadline"))]
    (cond
      (and start deadline) (str "Scheduled start " start "; must resolve by " deadline ".")
      start (str "Scheduled start " start ".")
      deadline (str "Must resolve by " deadline "."))))

(defn- reported-by
  [params]
  (when-let [source (labels/clean (get params "officialSource"))]
    (str ", as reported by " source)))

(def ^:private pays-sentence
  "Each winning share pays $1.00; the other side pays $0.")

(defn- price-source
  [params]
  (let [description (labels/clean (get params "priceDescription"))
        seconds (labels/clean (get params "seconds"))]
    (cond
      (and description seconds) (str "Price source: " description ", " seconds "-second window.")
      description (str "Price source: " description "."))))

(defn- game-facts
  [params]
  (let [a (labels/clean (get params "participantA"))
        b (labels/clean (get params "participantB"))]
    {:title (when (and a b) (str a " vs " b))
     :category :sports
     :subcategory (labels/sport-key (get params "sport"))
     :competition (labels/clean (get params "competition"))
     :clock (clock/clock-spec :starts (get params "scheduledStart") (get params "resolutionDeadline"))
     :search-terms (keep labels/clean [a b (get params "shortNameA") (get params "shortNameB")
                                       (get params "competition")])}))

(defn- price-facts
  "binaryPrice / priceTouch: one strike on one underlying at one time."
  [template params]
  (let [subject (labels/clean (get params "perp"))
        asset (or (labels/asset-label subject) "Price")
        level-param (if (= :price-touch template) "target" "threshold")
        level (labels/level-text subject (get params level-param))
        time (get params "time")
        touch? (= :price-touch template)
        verb (if touch? "reaches" "above")]
    {:title (str asset " " (if touch? "reaches " "above ") level)
     :category (if (labels/stocks-subject? subject) :stocks :crypto)
     :subject subject
     :clock (clock/clock-spec (if touch? :ends :settles) time nil)
     :rules (join-sentences
             (if touch?
               (str "Yes if " asset " reaches " level " at any time before " (utc time) ".")
               (str "Yes if " asset " is above " level " at " (utc time) "."))
             (price-source params)
             "Otherwise No."
             pays-sentence)
     :ladder {:key (str (name template) "|" subject "|" time)
              :title (str asset " " verb "…")
              :threshold (labels/parse-number (get params level-param))
              :rung-label level
              :kind (if touch? :touch :above)}
     :search-terms (keep labels/clean [asset subject])}))

(defn- ipo-market-cap-facts
  [params]
  (let [company (or (labels/clean (get params "company")) "Company")
        cap (labels/market-cap-text (get params "marketCapThresholdB"))
        deadline (get params "listingDeadline")]
    {:title (str company " first-day market cap ≥ " cap)
     :category :companies
     :subject company
     :clock (clock/clock-spec :listing deadline nil)
     :rules (join-sentences
             (str "Yes if " company " lists by " (utc deadline)
                  " and its first-day market cap is at least " cap ".")
             "Otherwise No."
             pays-sentence)
     :ladder {:key (str "company-ipo-market-cap|" company)
              :title (str company " first-day market cap")
              :threshold (labels/parse-number (get params "marketCapThresholdB"))
              :rung-label (str "≥ " cap)
              :kind :at-least}
     :search-terms [company "IPO"]}))

(defn- recurring-binary-facts
  [params]
  (let [subject (labels/clean (get params "underlying"))
        asset (or (labels/asset-label subject) "Price")
        level (labels/level-text subject (get params "targetPrice"))
        expiry (get params "expiry")]
    {:title (str asset " above " level)
     :category :crypto
     :subcategory :daily
     :subject subject
     :clock (clock/clock-spec :settles expiry nil)
     :rules (join-sentences
             (str "Yes if the " asset " mark price is above " level " at settlement, " (utc expiry) ".")
             "Otherwise No."
             pays-sentence)
     :search-terms (keep labels/clean [asset subject "daily"])}))

(def ^:private option-labels
  {:sports-contest-draw "Draw"
   :policy-rate-increase "Hike"
   :policy-rate-no-change "Hold"
   :policy-rate-decrease "Cut"
   :fallback "Other"})

(defn- unknown-facts
  [name params]
  (let [summary (->> params
                     (keep (fn [[k v]]
                             (when-let [v* (labels/clean v)]
                               (str (labels/humanize-key k) ": " v*))))
                     (take 3)
                     (str/join " · "))]
    {:title (str (or (labels/humanize-key name) "Market")
                 (when (seq summary) (str " — " summary)))
     :category :other
     :clock (some (fn [[k v]]
                    (when (and (re-find #"(?i)(time|deadline|expiry|date)" k)
                               (clock/parse-time-ms v))
                      (clock/clock-spec :ends v nil)))
                  params)}))

(defn- outcome-facts
  [template params name]
  (case template
    :sports-contest-winner
    (let [game (game-facts params)]
      (assoc game
             :shape :head-to-head
             :side-long-labels [(labels/clean (get params "participantA"))
                                (labels/clean (get params "participantB"))]
             :rules (join-sentences
                     (str "Resolves to the side that wins"
                          (some->> (labels/clean (get params "countedPlay")) (str " "))
                          (reported-by params) ".")
                     pays-sentence
                     (schedule-sentence params))))

    (:sports-contest-participant :sports-tournament-participant)
    {:shape :option :option-label (labels/clean (get params "participant"))}

    (:sports-contest-draw :policy-rate-increase :policy-rate-no-change
     :policy-rate-decrease :fallback)
    {:shape :option :option-label (get option-labels template)}

    :recurring-bucket-option
    {:shape :option :option-index (labels/parse-number (get params "index"))}

    (:binary-price :price-touch) (assoc (price-facts template params) :shape :yes-no)
    :company-ipo-market-cap (assoc (ipo-market-cap-facts params) :shape :yes-no)

    :company-ipo-confirmed
    (let [company (or (labels/clean (get params "company")) "Company")
          deadline (get params "dateTime")]
      {:shape :yes-no
       :title (str company " IPO")
       :category :companies
       :subject company
       :clock (clock/clock-spec :ends deadline nil)
       :rules (join-sentences (str "Yes if " company "'s IPO is confirmed by " (utc deadline) ".")
                              "Otherwise No."
                              pays-sentence)
       :search-terms [company "IPO"]})

    :recurring-price-binary (assoc (recurring-binary-facts params) :shape :yes-no)
    :plain {:shape :yes-no :title (labels/clean name)}
    (assoc (unknown-facts name params) :shape :yes-no)))

(defn describe-outcome
  "Display facts for one contract: {:template :shape :title :side-labels
   :side-long-labels :option-label :category :subcategory :competition :clock
   :rules :venue :subject :ladder :search-terms}. `:category` is nil for :plain
   contracts, whose category comes from their own metadata."
  [{:keys [name description venue side-specs]}]
  (let [params (parse-params description)
        template (template-id name params)
        sides (if (= :plain template)
                (side-labels (map (fn [spec]
                                    {:name (some-> (or (:name spec) (:sideName spec))
                                                   (str/replace #"[{}]" ""))})
                                  side-specs)
                             {})
                (side-labels side-specs params))]
    (merge {:template template
            :venue (labels/clean venue)
            :side-labels sides}
           (outcome-facts template params name))))

(defn bucket-labels
  "Ranges of a price-bucket question: Below $72,967 · $72,967 – $75,945 · Above $75,945."
  [subject thresholds]
  (let [levels (mapv #(labels/level-text subject %) thresholds)]
    (case (count levels)
      0 []
      1 [(str "Below " (first levels)) (str "Above " (first levels))]
      (vec (concat [(str "Below " (first levels))]
                   (map (fn [[lo hi]] (str lo " – " hi))
                        (partition 2 1 levels))
                   [(str "Above " (last levels))])))))

(defn- question-facts
  [template params name]
  (case template
    :sports-contest-result
    (assoc (game-facts params)
           :shape :pick-one
           :rules (join-sentences
                   (str "Exactly one outcome wins: the result after "
                        (or (labels/clean (get params "countedPlay")) "the match")
                        (reported-by params) ".")
                   "“Other” covers any result not listed. Each winning share pays $1.00."
                   (schedule-sentence params)))

    :sports-tournament-winner
    (let [competition (labels/clean (get params "competition"))
          season (labels/clean (get params "season"))]
      {:shape :field
       :title (str (or competition "Tournament") " champion" (when season (str " " season)))
       :category :sports
       :subcategory (labels/sport-key (get params "sport"))
       :competition competition
       :clock (clock/clock-spec :resolves (get params "resolutionDeadline") nil)
       :rules (join-sentences
               (str "Exactly one outcome wins: the " (or competition "tournament")
                    (when season (str " " season)) " champion" (reported-by params) ".")
               "“Other” covers any contender not listed. Each winning share pays $1.00."
               (some->> (utc (get params "resolutionDeadline")) (str "Must resolve by ") (#(str % "."))))
       :search-terms (keep labels/clean [competition season "champion"])})

    :policy-rate-decision
    (let [institution (labels/institution-label (get params "institution"))
          decision (labels/clean (get params "decisionLabel"))]
      {:shape :pick-one
       :title (str (or institution "Rate") " — " (or decision "next") " decision")
       :category :economics
       :competition institution
       :clock (clock/clock-spec :decision (get params "scheduledDecision") (get params "decisionDeadline"))
       :rules (join-sentences
               (str "Exactly one outcome wins, based on the decision on "
                    (or (labels/clean (get params "policyMeasure")) "the policy rate")
                    " at the " (or decision "scheduled") " meeting"
                    (some->> (utc (get params "scheduledDecision")) (str ", scheduled ")) ".")
               "Hike means an increase, Hold no change, Cut a decrease; “Other” covers anything else."
               (some->> (labels/clean (get params "officialSource")) (str "Source: ") (#(str % ".")))
               (some->> (utc (get params "decisionDeadline")) (str "Must resolve by ") (#(str % "."))))
       :search-terms (keep labels/clean [institution (get params "institution") decision "rates"])})

    :recurring-price-bucket
    (let [subject (labels/clean (get params "underlying"))
          asset (or (labels/asset-label subject) "Price")
          thresholds (->> (str/split (str (get params "priceThresholds" "")) #",")
                          (keep labels/clean)
                          vec)
          expiry (get params "expiry")]
      {:shape :pick-one
       :title (str asset " price range")
       :category :crypto
       :subcategory :daily
       :subject subject
       :bucket-labels (bucket-labels subject thresholds)
       :clock (clock/clock-spec :settles expiry nil)
       :rules (join-sentences
               (str "Exactly one range wins: the one that contains the " asset
                    " mark price at settlement, " (utc expiry) ".")
               "Each winning share pays $1.00.")
       :search-terms (keep labels/clean [asset subject "daily" "range"])})

    :plain {:shape :pick-one :title (labels/clean name)}
    (assoc (unknown-facts name params) :shape :pick-one)))

(defn describe-question
  "Display facts for a question (a group of outcomes where exactly one wins).
   `:shape` is :pick-one or :field."
  [{:keys [name description]}]
  (let [params (parse-params description)
        template (template-id name params)]
    (merge {:template template}
           (question-facts template params name))))

(ns hyperopen.portfolio.routes-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.routes :as routes]))

(deftest parse-portfolio-route-recognizes-optimizer-family-test
  (is (= {:kind :optimize-index
          :path "/portfolio/optimize"}
         (routes/parse-portfolio-route "/portfolio/optimize")))
  (is (= {:kind :optimize-new
          :path "/portfolio/optimize/new"}
         (routes/parse-portfolio-route "/portfolio/optimize/new/")))
  (is (= {:kind :optimize-scenario
          :path "/portfolio/optimize/scn_01HS6PQ"
          :scenario-id "scn_01HS6PQ"}
         (routes/parse-portfolio-route "/portfolio/optimize/scn_01HS6PQ")))
  (is (= {:kind :other
          :path "/portfolio/optimize/scn_01HS6PQ/details"}
         (routes/parse-portfolio-route "/portfolio/optimize/scn_01HS6PQ/details"))))

(deftest portfolio-optimize-route-helpers-test
  (is (true? (routes/portfolio-route? "/portfolio/optimize")))
  (is (true? (routes/portfolio-optimize-route? "/portfolio/optimize/new")))
  (is (false? (routes/portfolio-optimize-route? "/portfolio/trader/0x1234567890abcdef1234567890abcdef12345678")))
  (is (= "scn_01HS6PQ"
         (routes/portfolio-optimize-scenario-id "/portfolio/optimize/scn_01HS6PQ")))
  (is (= "/portfolio/optimize" (routes/portfolio-optimize-index-path)))
  (is (= "/portfolio/optimize/new" (routes/portfolio-optimize-new-path)))
  (is (= "/portfolio/optimize/scn_01HS6PQ"
         (routes/portfolio-optimize-scenario-path " scn_01HS6PQ "))))

(deftest parse-portfolio-route-keeps-existing-family-behavior-test
  (is (= {:kind :page
          :path "/portfolio"}
         (routes/parse-portfolio-route "/portfolio")))
  (is (= {:kind :trader
          :path "/portfolio/trader/0x1234567890abcdef1234567890abcdef12345678"
          :address "0x1234567890abcdef1234567890abcdef12345678"}
         (routes/parse-portfolio-route "/portfolio/trader/0x1234567890abcdef1234567890abcdef12345678")))
  (is (= {:kind :other
          :path "/portfolio/unknown"}
         (routes/parse-portfolio-route "/portfolio/unknown"))))

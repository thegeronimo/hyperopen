(ns hyperopen.views.typography-scale-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]))

(def fs (js/require "fs"))
(def path (js/require "path"))

(def forbidden-small-text-pattern #"text-\[(10|11|13|14|15)px\]")

(defn- project-root []
  (.cwd js/process))

(defn- join-path [& parts]
  (reduce (fn [acc part]
            (.join path acc part))
          (first parts)
          (rest parts)))

(defn- read-text [file-path]
  (.readFileSync fs file-path "utf8"))

(defn- relative-path [file-path]
  (.relative path (project-root) file-path))

(defn- cljs-files-under [dir-path]
  (let [entries (.readdirSync fs dir-path #js {:withFileTypes true})]
    (->> (array-seq entries)
         (mapcat (fn [entry]
                   (let [entry-name (.-name entry)
                         entry-path (.join path dir-path entry-name)]
                     (cond
                       (.isDirectory entry) (cljs-files-under entry-path)
                       (and (.isFile entry) (.endsWith entry-name ".cljs")) [entry-path]
                       :else []))))
         sort
         vec)))

(defn- forbidden-small-text-violations []
  (let [views-dir (join-path (project-root) "src" "hyperopen" "views")]
    (->> (cljs-files-under views-dir)
         (keep (fn [file-path]
                 (let [contents (read-text file-path)
                       matches (re-seq forbidden-small-text-pattern contents)]
                   (when (seq matches)
                     {:file (relative-path file-path)
                      :matches (mapv first matches)}))))
         vec)))

(deftest tailwind-font-scale-defines-12px-baseline-for-xs-and-sm-test
  (let [tailwind-config-path (join-path (project-root) "tailwind.config.js")
        tailwind-config (read-text tailwind-config-path)]
    (testing "text-xs utility is pinned to 12px/16px"
      (is (re-find #"xs\s*:\s*\[\s*[\"']12px[\"']\s*,\s*\{\s*lineHeight\s*:\s*[\"']16px[\"']\s*\}\s*\]"
                   tailwind-config)))
    (testing "text-sm utility is pinned to 12px/16px"
      (is (re-find #"sm\s*:\s*\[\s*[\"']12px[\"']\s*,\s*\{\s*lineHeight\s*:\s*[\"']16px[\"']\s*\}\s*\]"
                   tailwind-config)))))

(deftest views-do-not-use-forbidden-sub-16px-explicit-text-utilities-test
  (let [violations (forbidden-small-text-violations)]
    (is (empty? violations)
        (str "Forbidden explicit text utilities found: "
             (str/join ", " (map (fn [{:keys [file matches]}]
                                   (str file " => " matches))
                                 violations))))))

(deftest balances-tab-uses-12px-typography-for-toggle-and-rows-test
  (let [account-info-path (join-path (project-root) "src" "hyperopen" "views" "account_info_view.cljs")
        account-info-source (read-text account-info-path)]
    (testing "hide small balances label uses text-sm"
      (is (re-find #"\[:label\.text-sm\.text-trading-text(?:\.[A-Za-z0-9_-]+)*\s+\{:for \"hide-small-balances\"\}\s+\"Hide Small Balances\"\]"
                   account-info-source)))
    (testing "balance row wrapper includes text-sm for 12px baseline"
      (is (re-find #"\[:div\.grid\.grid-cols-7\.gap-2\.py-px\.px-3\.hover:bg-base-300\.items-center\.text-sm(?:\.[A-Za-z0-9_-]+)*"
                   account-info-source)))))

(deftest header-nav-link-css-uses-14px-and-600-weight-test
  (let [styles-path (join-path (project-root) "src" "styles" "main.css")
        styles-source (read-text styles-path)]
    (testing "header nav links use 14px and 600 weight with ligatures disabled"
      (is (re-find #"\.header-nav-link\s*\{[\s\S]*?font-weight:\s*600;[\s\S]*?font-size:\s*14px;[\s\S]*?line-height:\s*15px;[\s\S]*?font-feature-settings:\s*\"calt\"\s*off;[\s\S]*?font-variant-ligatures:\s*no-contextual;[\s\S]*?\}"
                   styles-source)))
    (testing "active header link color matches target accent"
      (is (re-find #"\.header-nav-link-active\s*\{[\s\S]*?color:\s*rgb\(151 252 228\);[\s\S]*?\}"
                   styles-source)))))

(ns eos-cljs.cli
  (:require
   [eos-cljs.core :as eos]
   [clojure.tools.cli :refer [parse-opts]]
   fs
   util
   [cljs.pprint :refer [pprint]]
   [cljs.reader :as edn]
   [clojure.string :as string]
   [cljs.core :refer [*command-line-args*]]))

(def commands #{"deploy" "sign" "broadcast" "action"})

(defn usage [options-summary]
  (->> ["This is a CLI interface to `nodeos` for local development"
        ""
        "Commands:"
        commands
        ""
        "Options:"
        options-summary
        ""
        "Actions:"
        "  deploy      Deploy a smart contract"]
       (string/join "\n")))

(def cli-options
  [["-a" "--account NAME" "EOS account name"
    :validate [#(<= 3 (count %)) "Specify at least 2 chars for account"]]
   ["-p" "--path PATH" "Path to smart contract to deploy"
    :validate [#(fs/existsSync (str % ".wasm")) "WASM file could not be fond"]]
   [nil "--write FILE" "Write the transaction to a file. Does not broadcast it."]
   ["-h" "--help"]])

(defn validate-args [args]
  (let [{:keys [options arguments errors summary] :as ee} (parse-opts args cli-options)]
    (cond
      (:help options) {:error (usage summary)}
      errors {:error (string/join "\n" errors)}
      (commands (first arguments))
      {:action (first arguments) :options options :args (rest arguments)}
      :else
      {:error (usage summary)})))

(defn parse-action-arg-array
  "Transform a vector of [param1 value1 param2 value2] into a map of
  {param1 value1 param2 value2}."
  [col]
  (reduce (fn [m [param val]] (assoc m param val)) {} (partition 2 col)))

;; localnet chain id
(def chain-id "cf057bbfb72640471fd910bcb67639c22df9f92470936cddc1ade0e2f2e7dc4f")

;; example run:
;; npm start --silent -- -a sjoerd action checktx rawtx 0000000090e39a96e6459ad319b89e83839189580794a54cd7390b78ab9b526fe08c6af000a150b846bc3c1760966739707632d141762309285066cd9f89fa1c073461361e44f85bc2c62d00451baf6bd4f06d5c4e4e04879cfe60ba3b296b1ff08f112f6071756f
(defn -main [& args]
  (let [{:keys [error action args options]} (validate-args args)]
    (if error
      (do (eos/prnj error) (.exit js/process 0))
      (case action
        "deploy"
        (let [broadcast? (not (contains? options :write))]
          (->
           (eos/deploy (:account options) (:path options) {:broadcast? broadcast?
                                                           :expire-sec 3500})
           (.then #(if broadcast?
                     (do (prn %))
                     (do
                       (fs/writeFileSync
                        (:write options)
                        (.from js/Buffer (.-serializedTransaction %)))
                       (println "> Transaction saved to " (:write options) "\n"))))))
        "sign"
        (let [tx (->> (:write options) fs/readFileSync js/Uint8Array.)
              sig-file (str (:write options) ".sig")]
          (.then
           (eos/sign-tx tx chain-id eos/pub-key)
           #(do (fs/writeFileSync sig-file (pr-str (js->clj (.-signatures %))))
                (println "Signatures saved to " sig-file))))
        "broadcast"
        (let [tx (->> (:write options) fs/readFileSync js/Uint8Array.)
              sig-file (str (:write options) ".sig")
              sigs (-> sig-file (fs/readFileSync #js {:encoding "UTF-8"}) edn/read-string)
              signed-tx (clj->js {:signatures sigs
                                  :serializedTransaction tx})]
          (-> (.pushSignedTransaction @eos/api signed-tx)
              (.then prn)))
        "action"
        (->
         (eos/transact (:account options) (first args) (parse-action-arg-array (rest args)))
         (.then #(do (eos/prnj %)
                     (eos/prnj (str "----\n console:\n----\n"
                                    (aget % "processed" "action_traces" 0 "console"))))))))))

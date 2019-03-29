(ns eos-deploys.cli
  (:require
   [eos-deploys.core :as eos]
   [clojure.tools.cli :refer [parse-opts]]
   fs
   [cljs.pprint :refer [pprint]]
   [clojure.string :as string]
   [cljs.core :refer [*command-line-args*]]))

(defn usage [options-summary]
  (->> ["This is a CLI interface to `nodeos` for local development"
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
   ["-h" "--help"]])

(defn validate-args [args]
  (let [{:keys [options arguments errors summary] :as ee} (parse-opts args cli-options)]
    (cond
      (:help options) {:error (usage summary)}
      errors {:error (string/join "\n" errors)}
      (#{"deploy" "action" "table"} (first arguments))
      {:action (first arguments) :options options :args (rest arguments)}
      :else
      {:error (usage summary)})))

(defn parse-action-arg-array
  "Transform a vector of [param1 value1 param2 value2] into a map of
  {param1 value1 param2 value2}."
  [col]
  (reduce (fn [m [param val]] (assoc m param val)) {} (partition 2 col)))

;; example run:
;; npm start --silent -- -a sjoerd action checktx rawtx 0000000090e39a96e6459ad319b89e83839189580794a54cd7390b78ab9b526fe08c6af000a150b846bc3c1760966739707632d141762309285066cd9f89fa1c073461361e44f85bc2c62d00451baf6bd4f06d5c4e4e04879cfe60ba3b296b1ff08f112f6071756f
(defn -main [& args]
  (let [{:keys [error action args options]} (validate-args args)]
    (if error
      (do (eos/prnj error) (.exit js/process 0))
      (case action
        "deploy"
        (->
         (eos/deploy (:account options) (:path options)))
        "action"
        (->
         (eos/transact (:account options) (first args) (parse-action-arg-array (rest args)))
         (.then #(do (eos/prnj %)
                     (eos/prnj (str "----\n console:\n----\n"
                                    (aget % "processed" "action_traces" 0 "console"))))))
        "table"
        (.then (eos/get-table-rows "test" "test" "nep5")
               prn))))

  ;; (case (first args)
    ;; "deploy" (prn "wtf")
  ;; (prn "haha"))

  ;; (prn args)
  ;; (deploy "/home/jesse/repos/effectai/docker-eos/effect.contracts/contracts/effect.token/effect.token" "jesse")
  ;; (-> {:actions [{:account "jesse" :name "create" :authorization [{:actor "jesse" :permission "active"}]
  ;;                 :data {:issuer "jesse" :maximum_supply "100.0000 SYS" :memo "bic"}}]}
  ;;     clj->js
  ;;     (as-> tx (.transact api tx #js {:blocksBehind 0 :expireSeconds 5}))
  ;;     (.then #(prn %)))
  ;; (-> (transact "jesse" "issue" {:to "jesse" :quantity "1.0000 SYS" :memo "bic"} )
  ;; (.then #(prn %))))
  )

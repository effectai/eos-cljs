(ns eos-cljs.cli
  (:require
   [eos-cljs.core :as eos]
   [clojure.tools.cli :refer [parse-opts]]
   fs
   util
   [eosjs-ecc :as ecc :refer [sha256]]
   [cljs.pprint :refer [pprint]]
   [cljs.reader :as edn]
   [clojure.string :as string]
   [cljs.core :refer [*command-line-args*]]))

(def commands #{"deploy" "sign" "broadcast" "action"})

(defn usage [options-summary]
  (->> ["This is a CLI interface to `nodeos` for local development"
        ""
        "Options:"
        options-summary
        ""
        "Actions:"
        "  deploy      Deploy a smart contract"
        "  sign        Sign a serialized transaction"
        "  broadcast   Publish a signed transaction to the blockchain"]
       (string/join "\n")))

(def cli-options
  [["-n" "--net NAME" (str "The EOSIO network to use " (map name (keys eos/apis)))
    :default :local
    :validate [#(contains? eos/apis %)]
    :parse-fn keyword]
   ["-a" "--account NAME" "EOS account name"
    :validate [#(<= 3 (count %)) "Specify at least 2 chars for account"]]
   ["-p" "--path PATH" "Path to smart contract to deploy"
    ;; :validate [#(fs/existsSync (str % ".wasm")) "WASM file could not be fond"]
    ]
   [nil "--print" "Print transaction objects"]
   [nil "--write FILE" "Write the transaction to a file. Does not broadcast it."]
   [nil "--format FORMAT" "Format of transaction output when (greymass, bin)"
    :default "bin"]
   [nil "--sign" "Signs the transaction"
    :default false]
   [nil "--pub PUBLIC_KEY" "The public key of the keypair to use for signing"]
   [nil "--priv FILE" "An EDN file with private keys to load into the signature provider"
    :validate [fs/existsSync "file does not exist"]]
   ["-h" "--help"]])

(defn validate-args [args]
  (let [{:keys [options arguments errors summary] :as ee} (parse-opts args cli-options)]
    (cond
      (:help options) {:error (usage summary) :options options}
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

(defmulti format-transaction (fn [type serialized-tx txid] type))

(defmethod format-transaction "greymass"
  [_ serialized-tx txid]
  (js/JSON.stringify
   (clj->js
    {"contract" false
     "transaction"
     {"transaction_id" txid
      "broadcast" false
      "transaction"
      {"compression" "none"
       "transaction"
       (js->clj
        (.deserializeTransaction @eos/api serialized-tx))
       "signatures" []}}})))

(defmethod format-transaction "bin" [_ tx _] (js/Buffer.from tx))
(defmethod format-transaction "json" [_ tx txid]
  (js/JSON.stringify (.deserializeTransaction @eos/api tx)))

(defn- handle-transact [res options]
  (let [broadcast? (not (or (contains? options :write)
                            (:print options)))]
    (if broadcast?
      (prn res)
      (let [tx (.-serializedTransaction res)
            chain-id (:chain-id ((:net options) eos/apis))
            txid (->> #js [(js/Buffer.from chain-id "hex")
                           (js/Buffer.from (.-serializedTransaction res))
                           (js/Buffer.from (js/Uint8Array. 32))]
                      (.concat js/Buffer)
                      sha256)
            tx-string (format-transaction (:format options) tx txid)]
        (when (:print options)
          (println tx-string))
        (when (contains? options :write)
          (fs/writeFileSync (:write options) tx-string)
          (println "Transaction saved to " (:write options)))))))

(defmulti command
  "Each method handles a cli action"
  (fn [action args options] action))

(defmethod command "deploy" [_ args options]
  (let [broadcast? (not (contains? options :write))]
    (->
     (eos/deploy (:account options) (:path options)
                 {:broadcast? broadcast? :sign? (:sign options) :expire-sec 3500})
     (.then #(handle-transact % options)))))

(defmethod command "sign" [_ args options]
  (let [tx (->> (:path options) fs/readFileSync js/Uint8Array.)
        sig-file (str (:path options) ".sig")]
    (.then
     (eos/sign-tx tx (:chain-id ((:net options) eos/apis)) (:pub options))
     #(do (fs/writeFileSync sig-file (pr-str (js->clj (.-signatures %))))
          (println "Signatures saved to " sig-file)))))

(defmethod command "broadcast" [_ args options]
  (let [tx (->> (:path options) fs/readFileSync js/Uint8Array.)
        sig-file (str (:path options) ".sig")
        sigs (-> sig-file (fs/readFileSync #js {:encoding "UTF-8"}) edn/read-string)
        signed-tx (clj->js {:signatures sigs
                            :serializedTransaction tx})]
    (-> (.pushSignedTransaction @eos/api signed-tx)
        (.then prn))))

(defmethod command "action" [_ args options]
  (let [broadcast? (not (or (contains? options :write)
                            (:print options)))
        action-promise
        (if (:path options)
          (let [actions (-> (:path options) (fs/readFileSync #js {:encoding "UTF-8"})
                            edn/read-string)]
            (eos/transact actions {:broadcast? broadcast? :sign? (:sign options) :expire-sec 3500}))
          (eos/transact (:account options) (first args) (parse-action-arg-array (rest args))))]
    (->
     action-promise
     (.then #(handle-transact % options)))))

(defn -main [& args]
  (let [{:keys [error action args options]} (validate-args args)]
    (when error
      (do (eos/prnj error) (.exit js/process 0)))

    ;; load any custom private keys into the api
    (let [api-new ((:net options) eos/apis)]
      (if (:priv options)
        (let [priv-keys (-> (:priv options) (fs/readFileSync #js {:encoding "UTF-8"})
                            edn/read-string)]
          (eos/set-api! (assoc api-new :priv-keys priv-keys)))
        (eos/set-api! api-new)))

    (command action args options)))

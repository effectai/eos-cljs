(ns eos-deploys.core
  (:require
   [eosjs :refer [Api JsonRpc RpcError]]
   [eosjs :refer [Serialize]]
   ["eosjs/dist/eosjs-jssig" :refer [JsSignatureProvider]]
   [clojure.tools.cli :refer [parse-opts]]
   fs
   [cljs.pprint :refer [pprint]]
   [clojure.string :as string]
   [util :refer [TextEncoder TextDecoder]]
   [node-fetch :as fetch]
   [cljs.core :refer [*command-line-args*]]))

;; (def rpc-url "http://a105440894b5a11e9a27d027e8e4054e-98b15ae5d3166434.elb.eu-west-1.amazonaws.com:8888")
(def rpc-url "http://localhost:8888")
(def priv-key "5Jmsawgsp1tQ3GD6JyGCwy1dcvqKZgX6ugMVMdjirx85iv5VyPR")

;; (.log js/console *command-line-args*)

(defn prnj "Javascript console.log shorthand" [m] (.log js/console m))

(def rpc (JsonRpc. rpc-url #js {:fetch fetch}))
(def sig-provider (JsSignatureProvider. #js [priv-key]))
(def api (Api. #js {:rpc rpc
                    ;; Optionally provide an authorization
                    ;; controller. It will collect the available public
                    ;; keys for signing.
                    ;; :authorityProvider #js {"getRequiredKeys" (fn [tx avail] #js ["EOS7ijWCBmoXBi3CgtK7DJxentZZeTkeUnaSDvyro9dq7Sd1C3dC4"])}
                    :signatureProvider sig-provider
                    :textDecoder (TextDecoder.) :textEncoder (TextEncoder.)}))



(defn complete-abi
  "Fill a partial `abi` object will all the possible fields defined in
  `abi-definition`"
  [abi-definition abi]
  (reduce
   (fn [m {field "name"}]
     (assoc m field (or (get m field) [])))
   (js->clj abi) (js->clj (.-fields abi-definition))))

(defn read-contract
  "Read a contract binary abi and wasm into a map"
  [file]
  (let [buffer (new (.-SerialBuffer Serialize)
                    #js {:textEncoder (.-textEncoder api)
                         :textDecoder (.-textDecoder api)})
        wasm (.toString (fs/readFileSync (str file ".wasm")) "hex")
        abi (.parse js/JSON (fs/readFileSync (str file ".abi") "utf8"))
        abi-def (.get (.-abiTypes api) "abi_def")
        abi-complete (clj->js (complete-abi abi-def abi))]
    (.serialize abi-def buffer abi-complete)
    {:wasm wasm
     :abi (.toString (->> buffer .asUint8Array (.from js/Buffer)) "hex")}))

(defn deploy [account file]
  (let [{:keys [abi wasm]} (read-contract file)]
    (->
     {:actions [{:account "eosio"
                 :name "setcode"
                 :authorization [{:actor account
                                  :permission "active"}]
                 :data {:account account
                        :vmtype 0
                        :vmversion 0
                        :code wasm}}
                {:account "eosio"
                 :name "setabi"
                 :authorization [{:actor account
                                  :permission "active"}]
                 :data {:account account
                        :abi abi}}]}
     clj->js
     (as-> tx (.transact api tx
                         #js {:sign true
                              :broadcast true
                              :blocksBehind 0
                              :expireSeconds 5}))
     (.then #(prn (.-transaction_id %)))
     (.catch #(prn %1 %2)))))


(defn transact
  ([account name data] (transact account name data account))
  ([account name data auth-account]
   (-> {:actions [{:account account :name name :authorization [{:actor auth-account :permission "active"}]
                   :data data}]}
       clj->js
       (as-> tx (.transact api tx #js {:blocksBehind 0 :expireSeconds 5}))
       (.catch #(prn %))
       )))


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
    (prn ee)
    (cond
      (:help options) {:error (usage summary)}
      errors {:error (string/join "\n" errors)}
      (and (= 1 (count arguments))
           (#{"deploy"} (first arguments)))
      {:action (first arguments) :options options}
      :else
      {:error (usage summary)})))

(defn -main [& args]
  (let [{:keys [error action options]} (validate-args args)]
    (if error
      (do (prnj error) (.exit js/process 0))
      (case action
        "deploy" (deploy (:account options) (:path options)))))

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


(ns eos-deploys.core
  (:require
   [eosjs :refer [Api JsonRpc RpcError]]
   [eosjs :refer [Serialize]]
   ["eosjs/dist/eosjs-jssig" :refer [JsSignatureProvider]]
   fs
   [cljs.pprint :refer [pprint]]
   [clojure.string :as string]
   [util :refer [TextEncoder TextDecoder]]
   [node-fetch :as fetch]))

;; (def rpc-url "http://a105440894b5a11e9a27d027e8e4054e-98b15ae5d3166434.elb.eu-west-1.amazonaws.com:8888")
(def rpc-url "http://localhost:8888")
(def priv-key "5Jmsawgsp1tQ3GD6JyGCwy1dcvqKZgX6ugMVMdjirx85iv5VyPR")


(defn prnj "Javascript console.log shorthand" [m] (.log js/console m))

(def msg-contract-exist "contract is already running this version of code")

(def rpc (JsonRpc. rpc-url #js {:fetch fetch}))
(def sig-provider (JsSignatureProvider. #js [priv-key]))
(def api (Api. #js {:rpc rpc
                    ;; Optionally provide an authorization
                    ;; controller. It will collect the available public
                    ;; keys for signing.
                    ;; :authorityProvider #js {"getRequiredKeys" (fn [tx avail] #js ["EOS7ijWCBmoXBi3CgtK7DJxentZZeTkeUnaSDvyro9dq7Sd1C3dC4"])}
                    :signatureProvider sig-provider
                    :textDecoder (TextDecoder.) :textEncoder (TextEncoder.)}))

(defn get-table-rows [account scope table]
  (-> (.get_table_rows rpc #js {:code account :scope scope :table table
                                :limit 10})
      (.then js->clj)
      (.then #(get % "rows"))))
(defn get-table-row [account scope table id]
  (->
   (.get_table_rows rpc #js {:code account :scope scope :table table
                             :lower_bound id
                             :upper_bound id
                             :limit 10})
   (.then js->clj)
   (.then #(get % "rows"))
   (.then first)))

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
                              :expireSeconds 5})))))

(defn create-account [creator name]
  (->
   {:actions
    [{:account "eosio"
      :name "newaccount"
      :authorization [{:actor creator
                       :permission "active"}]
      :data {:creator creator
             :name name
             :owner {:threshold 1
                     :keys [{:key "EOS7ijWCBmoXBi3CgtK7DJxentZZeTkeUnaSDvyro9dq7Sd1C3dC4"
                             :weight 1}]
                     :accounts []
                     :waits []}
             :active {:threshold 1
                      :keys [{:key "EOS7ijWCBmoXBi3CgtK7DJxentZZeTkeUnaSDvyro9dq7Sd1C3dC4"
                              :weight 1}]
                      :accounts []
                      :waits []}}}]}
   clj->js
   (as-> tx (.transact api tx #js {:sign true :broadcast true :blocksBehind 0 :expireSeconds 5}))))

(defn update-auth [account permission delegate delegate-permission]
  (->
   {:actions
    [{:account "eosio"
      :name "updateauth"
      :authorization [{:actor account
                       :permission "owner"}]
      :data {:account account
             :permission permission
             :parent "owner"
             :auth {:keys [{:key "EOS7ijWCBmoXBi3CgtK7DJxentZZeTkeUnaSDvyro9dq7Sd1C3dC4"
                            :weight 1}]
                    :threshold 1
                    :accounts [{:permission {:actor delegate
                                             :permission delegate-permission}
                                :weight 1}]
                    :waits []}}}]}
   clj->js
   (as-> tx (.transact api tx #js {:sign true :broadcast true :blocksBehind 0 :expireSeconds 5}))))


(defn transact
  ([account name data] (transact account name data
                                 [{:actor account :permission "active"}]))
  ([account name data auths]
   (-> {:actions [{:account account :name name
                   :authorization auths
                   :data data}]}
       clj->js
       (as-> tx (.transact api tx #js {:blocksBehind 0 :expireSeconds 1}))
       (.catch #(prn %))
       )))

(defn wait-block
  "For now waitblock is a static timeout"
  [a]
  (js/Promise. (fn [resolve reject] (js/setTimeout (fn [] (resolve a)) 500))))


(defn tx-get-console
  "Retrieve console output from a raw EOS transaction"
  [tx]
  (try
    (aget tx "processed" "action_traces" 0 "console")
    (catch js/Error e "")))

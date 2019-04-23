(ns eos-cljs.core
  (:require
   [eosjs :refer [Api JsonRpc RpcError]]
   [eosjs :refer [Serialize]]
   ["eosjs/dist/eosjs-jssig" :refer [JsSignatureProvider]]
   fs
   [cljs.pprint :refer [pprint]]
   [clojure.string :as string]
   [util :refer [TextEncoder TextDecoder]]
   [node-fetch :as fetch]))

(def rpc-url "http://localhost:8888")
(def priv-key "5KQwrPbwdL6PhXujxW37FSSQZ1JiwsST4cqQzDeyXtP79zkvFD3")
(def pub-key "EOS6MRyAjQq8ud7hVNYcfnVPJqcVpscN5So8BhtHuGYqET5GDW5CV")

(defn prnj "Javascript console.log shorthand" [m] (.log js/console m))

(def msg-contract-exist "contract is already running this version of code")


(defn make-api [{:keys [rpc-url priv-keys]}]
  (let [sig-provider (JsSignatureProvider. (clj->js priv-keys))
        rpc (JsonRpc. rpc-url #js {:fetch fetch})]
    (Api. #js {:rpc rpc
               ;; Optionally provide an authorization
               ;; controller. It will collect the available public
               ;; keys for signing.
               ;; :authorityProvider #js {"getRequiredKeys" (fn [tx avail] #js ["EOS7ijWCBmoXBi3CgtK7DJxentZZeTkeUnaSDvyro9dq7Sd1C3dC4"])}
               :signatureProvider sig-provider
               :textDecoder (TextDecoder.) :textEncoder (TextEncoder.)})))

(def apis {:local {:rpc-url "https://localhost:8888"
                   :priv-keys ["5Jmsawgsp1tQ3GD6JyGCwy1dcvqKZgX6ugMVMdjirx85iv5VyPR"]}
           :jungle {:rpc-url "http://a4903032c523311e9acf0068515e584b-4e2aafacf4bb783e.elb.eu-west-1.amazonaws.com:8888"
                    :priv-keys []}})

(def api (atom (make-api {:rpc-url rpc-url :priv-keys [priv-key]})))

(defn set-api!
  ([a]
   (reset! api (make-api a))))

(defn get-table-rows [account scope table]
  (-> (.get_table_rows (.-rpc @api) #js {:code account :scope scope :table table
                                         :limit 10})
      (.then js->clj)
      (.then #(get % "rows"))))

(defn get-table-row [account scope table id]
  (->
   (.get_table_rows (.-rpc @api) #js {:code account :scope scope :table table
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
                    #js {:textEncoder (.-textEncoder @api)
                         :textDecoder (.-textDecoder @api)})
        wasm (.toString (fs/readFileSync (str file ".wasm")) "hex")
        abi (.parse js/JSON (fs/readFileSync (str file ".abi") "utf8"))
        abi-def (.get (.-abiTypes @api) "abi_def")
        abi-complete (clj->js (complete-abi abi-def abi))]
    (.serialize abi-def buffer abi-complete)
    {:wasm wasm
     :abi (.toString (->> buffer .asUint8Array (.from js/Buffer)) "hex")}))

(def deploy-opts {:sign? true
                  :broadcast? true
                  :expire-sec 5})

(defn deploy
  ([account file] (deploy account file {}))
  ([account file opts]
   (let [{:keys [abi wasm]} (read-contract file)]
     (let [{:keys [sign? broadcast? expire-sec]} (merge deploy-opts opts)]
       (->
        {:actions [{:account "eosio"
                    :name "setcode"
                    :authorization [{:actor account
                                     :permission "owner"}]
                    :data {:account account
                           :vmtype 0
                           :vmversion 0
                           :code wasm}}
                   {:account "eosio"
                    :name "setabi"
                    :authorization [{:actor account
                                     :permission "owner"}]
                    :data {:account account
                           :abi abi}}]}
        clj->js
        (as-> tx (.transact @api tx
                            #js {:sign sign?
                                 :broadcast broadcast?
                                 :blocksBehind 0
                                 :expireSeconds expire-sec})))))))

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
                     :keys [{:key pub-key
                             :weight 1}]
                     :accounts []
                     :waits []}
             :active {:threshold 1
                      :keys [{:key pub-key
                              :weight 1}]
                      :accounts []
                      :waits []}}}]}
   clj->js
   (as-> tx (.transact @api tx #js {:sign true :broadcast true :blocksBehind 0 :expireSeconds 5}))))

(defn random-account [prefix]
  (str prefix (reduce str (map apply (repeat 5 #(rand-nth ["a" "b" "c" "d" "e" "f" "g" "h"]))))))

(defn update-auth
  ([account permission delegate delegate-permission]
   (update-auth account permission [{:permission {:actor delegate :permission delegate-permission}
                                     :weight 1}]))
  ([account permission delegates]
   (->
    {:actions
     [{:account "eosio"
       :name "updateauth"
       :authorization [{:actor account
                        :permission "owner"}]
       :data {:account account
              :permission permission
              :parent "owner"
              :auth {:keys []
                     :threshold 1
                     :accounts delegates
                     :waits []}}}]}
    clj->js
    (as-> tx (.transact @api tx #js {:sign true :broadcast true :blocksBehind 0 :expireSeconds 5})))))


(defn transact
  ([actions]
   (-> {:actions actions}
       clj->js
       (as-> tx (.transact @api tx #js {:blocksBehind 0 :expireSeconds 1}))))
  ([account name data] (transact account name data
                                 [{:actor account :permission "active"}]))
  ([account name data auths]
   (-> (transact [{:account account :name name
                   :authorization auths
                   :data data}]))))

(defn wait-block
  "For now waitblock is a static timeout"
  ([p] (wait-block p 1))
  ([p n]
   (.then p
          #(js/Promise. (fn [resolve reject] (js/setTimeout (fn [] (resolve %)) (* n 500)))))))


(defn tx-get-console
  "Retrieve console output from a raw EOS transaction"
  [tx]
  (try
    (aget tx "processed" "action_traces" 0 "console")
    (catch js/Error e "")))


(defn sign-tx [serialized-tx chain-id pub]
  (let [sig-provider (.-signatureProvider @api)]
    (.sign sig-provider #js {"chainId" chain-id
                             "requiredKeys" #js [pub]
                             "serializedTransaction" serialized-tx})))

(ns eos-cljs.core
  (:require
   [eosjs :refer [Api JsonRpc RpcError Serialize]]
   ["eosjs/dist/eosjs-jssig" :refer [JsSignatureProvider]]
   [cljs.pprint :refer [pprint]]
   [clojure.string :as string]))

(def priv-key "5KQwrPbwdL6PhXujxW37FSSQZ1JiwsST4cqQzDeyXtP79zkvFD3")
(def pub-key "EOS6MRyAjQq8ud7hVNYcfnVPJqcVpscN5So8BhtHuGYqET5GDW5CV")

(defn prnj "Javascript console.log shorthand" [m] (.log js/console m))

(def msg-contract-exist "contract is already running this version of code")

(defn make-api
  "Create an API object that can be used for RPC interactions in the browser."
  [{:keys [rpc-url priv-keys]}]
  (let [sig-provider (JsSignatureProvider. (clj->js priv-keys))
        rpc (JsonRpc. rpc-url)]
    (Api. #js {:rpc rpc
               ;; Optionally provide an authorization
               ;; controller. It will collect the available public
               ;; keys for signing.
               ;; :authorityProvider #js {"getRequiredKeys" (fn [tx avail] #js ["EOS7ijWCBmoXBi3CgtK7DJxentZZeTkeUnaSDvyro9dq7Sd1C3dC4"])}
               :signatureProvider sig-provider})))

(def apis {:local {:rpc-url "http://127.0.0.1:8888"
                   :chain-id "cf057bbfb72640471fd910bcb67639c22df9f92470936cddc1ade0e2f2e7dc4f"
                   :priv-keys ["5Jmsawgsp1tQ3GD6JyGCwy1dcvqKZgX6ugMVMdjirx85iv5VyPR"
                               "5KQwrPbwdL6PhXujxW37FSSQZ1JiwsST4cqQzDeyXtP79zkvFD3"]}
           :jungle {:rpc-url "http://jungle2.cryptolions.io:80"
                    :chain-id "e70aaab8997e1dfce58fbfac80cbbb8fecec7b99cf982a9444273cbc64c41473"
                    :priv-keys []}
           :kylin {:rpc-url "https://api.kylin.alohaeos.com"
                   :chain-id "5fff1dae8dc8e2fc4d5b23b2c7665c97f9e9d8edf2b6485a86ba311c25639191"
                   :priv-keys []}
           :mainnet {:rpc-url "https://eos.greymass.com"
                     ;;:rpc-url "https://nodes.get-scatter.com"
                     ;; :rpc-url "https://eu.eosdac.io"
                     :chain-id "aca376f206b8fc25a6ed44dbdc66547c36c6c33e3a119ffbeaef943642f0e906"
                     :priv-keys []}})

(defonce api (atom (make-api (:local apis))))

(defn set-api!
  ([a] (reset! api (make-api a))))

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

(defn get-account [account]
  (.get_account (.-rpc @api) account))

(defn get-scheduled-txs []
  (.then (.fetch (.-rpc @api) "/v1/chain/get_scheduled_transactions")
         #(js->clj % :keywordize-keys true)))

(defn complete-abi
  "Fill a partial `abi` object will all the possible fields defined in
  `abi-definition`"
  [abi-definition abi]
  (reduce
   (fn [m {field "name"}]
     (assoc m field (or (get m field) [])))
   (js->clj abi) (js->clj (.-fields abi-definition))))

(def transact-opts {:sign? true
                    :broadcast? true
                    :expire-sec 20})

(defn deploy
  ([account contract] (deploy account contract {}))
  ([account contract opts]
   (let [{:keys [abi wasm]} contract]
     (let [{:keys [sign? broadcast? expire-sec]} (merge transact-opts opts)]
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
  ([account permission delegates]
   (update-auth account permission "owner" delegates))
  ([account permission parent-permission delegates]
   (->
    {:actions
     [{:account "eosio"
       :name "updateauth"
       :authorization [{:actor account
                        :permission "owner"}]
       :data {:account account
              :permission permission
              :parent parent-permission
              :auth {:keys []
                     :threshold 1
                     :accounts delegates
                     :waits []}}}]}
    clj->js
    (as-> tx (.transact @api tx #js {:sign true :broadcast true :blocksBehind 0 :expireSeconds 50})))))

(defn transact
  ([actions opts]
   (let [{:keys [sign? broadcast? expire-sec] :as pp} (merge transact-opts opts)]
     (-> {:actions actions}
         clj->js
         (as-> tx (.transact @api tx #js {:sign sign?
                                          :broadcast broadcast?
                                          :blocksBehind 0
                                          :expireSeconds expire-sec})))))
  ([actions] (transact actions {}))
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

(ns eos-cljs.node-api
  (:require
   [crypto :as crypto]
   [eosjs :refer [Api JsonRpc RpcError Serialize]]
   ["eosjs/dist/eosjs-jssig" :refer [JsSignatureProvider]]
   fs
   [util :refer [TextEncoder TextDecoder]]
   [node-fetch :as fetch]
   [eos-cljs.core :as eos]))

(defn make-api
  "Create an API object that can be used for RPC interactions in NodeJS."
  [{:keys [rpc-url priv-keys]}]
  (let [sig-provider (JsSignatureProvider. (clj->js priv-keys))
        rpc (JsonRpc. rpc-url #js {:fetch fetch})]
    (Api. #js {:rpc rpc
               :signatureProvider sig-provider
               :textDecoder (TextDecoder.) :textEncoder (TextEncoder.)})))

(defn set-api!
  "Sets the global EOS api endpoint to: :mainnet :local :jungle :kylin

  Extra api config can be passed as a map. It's often useful to pass custom
  signing keys as {:priv-keys [\"PRV...\"]}"
  ([api] (set-api! api {}))
  ([api extra-config]
   (let [api-config (api eos/apis)]
     (reset! eos/api (make-api (merge api-config extra-config))))))

;; override the default API with a NodeJS compatible instance
(set-api! :local)

(defn read-contract
  "Read a contract binary abi and wasm into a map.

  The map can be passed to `eos-cljs.core/deploy`."
  [file]
  (let [buffer (new (.-SerialBuffer Serialize)
                    #js {:textEncoder (.-textEncoder @eos/api)
                         :textDecoder (.-textDecoder @eos/api)})
        wasm (.toString (fs/readFileSync (str file ".wasm")) "hex")
        abi (.parse js/JSON (fs/readFileSync (str file ".abi") "utf8"))
        abi-def (.get (.-abiTypes @eos/api) "abi_def")
        abi-complete (clj->js (eos/complete-abi abi-def abi))]
    (.serialize abi-def buffer abi-complete)
    {:wasm wasm
     :abi (.toString (->> buffer .asUint8Array (.from js/Buffer)) "hex")}))

(defn deploy-file
  ([account contract] (deploy-file account contract {}))
  ([account contract opts] (eos/deploy account (read-contract contract) opts)))

(defn get-file-hash
  "Return the sha256 hash of a file on the filesystem"
  [path]
  (let [file-buffer (fs/readFileSync path)
        hash-sum (crypto/createHash "sha256")]
    (.update hash-sum file-buffer)
    (.digest hash-sum "hex")))

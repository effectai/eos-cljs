(ns eos-cljs.re-frame
  (:require
   [eos-cljs.core :as eos]
   [re-frame.core :refer [reg-fx dispatch]]))

(defn- eos-get-table-rows-fx
  [{:keys [account scope table limit index-position key-type
           lower-bound upper-bound on-success on-failure reverse]
    :or {index-position 1 limit 10 reverse false key-type ""
         on-success [::no-on-success]
         on-failure [::no-on-failure]}
    :as request}]
  (.then
   (eos/get-table-rows
    account scope table
    {:limit limit :reverse reverse :index_position index-position
     :lower_bound lower-bound :upper_bound upper-bound
     :key_type key-type})
   #(dispatch (conj on-success %))))

(reg-fx :eos-get-table-rows eos-get-table-rows-fx)

(reg-fx
 :eos-set-api
 (fn [value]
   (eos/set-api! (get eos/apis value))
   (dispatch [::eos-api-changed value])))

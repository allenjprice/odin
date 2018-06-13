(ns iface.components.ptm.ui.button
  (:require [reagent.core :as r]
            [cljs.spec.alpha :as s]))



(defn- get-button-class [type]
  (get {:primary ""
        :secondary   "button-secondary"
        :text        "button-text"} type))


(s/def ::disabled
  boolean?)

(s/def ::type
  #{:primary :secondary :text :upload})

(defn button [{:keys [disabled type] :as props}]
  (let [props' (-> props
                   (merge {:class (str
                                   (:class props) " "
                                   (when (some? type) (get-button-class type))
                                   (when disabled " button-disabled"))})
                   (dissoc :disabled :type))]
    (into [:button.button props']
          (r/children (r/current-component)))))


(s/fdef button
        :args (s/cat :props (s/keys :opt-un [::disabled
                                             ::type])))


(defn primary
  [props]
  (into [button props] (r/children (r/current-component ))))


(defn secondary
  [props]
  [:div])

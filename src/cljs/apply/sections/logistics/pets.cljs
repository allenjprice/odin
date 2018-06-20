(ns apply.sections.logistics.pets
  (:require [apply.content :as content]
            [antizer.reagent :as ant]
            [re-frame.core :refer [dispatch subscribe]]
            [iface.utils.log :as log]
            [apply.events :as events]
            [apply.db :as db]))


;; ==============================================================================
;; Step Name ====================================================================
;; ==============================================================================
;; NOTE - This step is implemented using the approach described in this PR as "Possibility 4"



(def step :logistics/pets)


;; db ===========================================================================



(defmethod db/next-step step
  [db]
  (case (step db)
    :dog   :logistics.pets/dog
    :other :logistics.pets/other
    :community/select))


(defmethod db/previous-step step
  [db]
  :logistics/occupancy)


;; events =======================================================================


(defmethod events/save-step-fx step
  [db params]
  {:db       (assoc db step params)
   :dispatch [:step/advance]})


;; views ========================================================================


(defmethod content/view step
  [_]
  [:div
   [:div.w-60-l.w-100
    [:h1 "Tell us about your fur family."]
    [:p "Most of our communities are dog-friendly, but we unfortunately do not
    allow cats. If you have a dog, please let us know what breed and
    weight."]]
   [:div.page-content.w-90-l.w-100
    [ant/button
     {:on-click #(dispatch [:step.current/next :dog])}
     "I have a dog"]
    [ant/button
     {:on-click #(dispatch [:step.current/next :none])}
     "No pets"]
    [ant/button
     {:on-click #(dispatch [:step.current/next :other])}
     "Other"]]])

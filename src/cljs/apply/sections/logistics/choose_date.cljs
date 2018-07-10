(ns apply.sections.logistics.choose-date
  (:require [apply.content :as content]
            [antizer.reagent :as ant]
            [re-frame.core :refer [dispatch subscribe reg-event-fx reg-sub]]
            [apply.events :as events]
            [apply.db :as db]
            [iface.utils.log :as log]
            [iface.utils.time :as time]
            [toolbelt.core :as tb]))


(def step :logistics.move-in-date/choose-date)


;; db ===========================================================================


(defmethod db/get-last-saved step
  [db s]
  (if (>= (time/days-between (s db)) 45)
    :logistics.move-in-date/outside-application-window
    :logistics/occupancy))


(defmethod db/next-step step
  [db]
  (if (>= (time/days-between (step db)) 45)
    :logistics.move-in-date/outside-application-window
    :logistics/occupancy))


(defmethod db/previous-step step
  [db]
  :logistics/move-in-date)


(defmethod db/step-complete? step
  [db step]
  (some? (step db)))


;; events =======================================================================


(defmethod events/save-step-fx step
  [db params]
  (let [date (or params (step db))]
   {:dispatch [:application/update {:move_in (.toISOString date)}]}))


(defmethod events/gql->rfdb :move_in [k] step)


;; views ========================================================================


(defmethod content/view step
  [_]
  (let [data (subscribe [:step/data step])]
    (log/log @data)
    [:div
     [:div.w-60-l.w-100
      [:h1 "Let's get started." [:br] "When do you want to move-in?"]
      [:p "We'll do our best to accommodate your move-in date, but we cannot
    guarantee that the date you choose will be the date that you move in."]]
     [:div.page-content.w-90-l.w-100
      [ant/date-picker
       {:on-change #(dispatch [:step.current/next %])
        :value (if (some? @data)
                 (js/moment @data)
                 (js/moment))}]]]))

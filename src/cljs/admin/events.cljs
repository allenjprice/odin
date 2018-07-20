(ns admin.events
  (:require [admin.db :as db]
            [admin.accounts.events]
            [admin.overview.events]
            [admin.kami.events]
            [admin.license-terms.events]
            [admin.metrics.events]
            [admin.notes.events]
            [admin.profile.events]
            [admin.properties.events]
            [admin.services.events]
            [re-frame.core :refer [reg-event-db reg-event-fx]]))


(reg-event-fx
 :app/init
 (fn [_ [_ account]]
   {:db         (db/bootstrap account)
    :dispatch-n [[:history/bootstrap]]}))


(reg-event-db
 :layout.mobile-menu/toggle
 (fn [db _]
   (update-in db [:menu :showing] not)))


(reg-event-db
 :user/update
 (fn [db [_ data]]
   (update db :account merge data)))

(ns admin.db
  (:require [admin.account.db :as accounts]))


(def menu-items
  [{:menu/key :home
    :menu/uri "/"}
   {:menu/key :accounts}
   {:menu/key :properties}
   {:menu/key :services}
   {:menu/key         :log-out
    :menu/uri         "/logout"
    :menu/text        "Log Out"
    :menu.ui/excluded #{:side}}])


(def default-value
  (merge
   {:menu         {:showing false
                   :items   menu-items}
    :route        {:current :home
                   :root    :home
                   :params  {}}}
   accounts/default-value))

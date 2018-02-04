(ns admin.db
  (:require [admin.accounts.db :as accounts]
            [admin.kami.db :as kami]
            [admin.metrics.db :as metrics]
            [admin.orders.db :as orders]
            [admin.properties.db :as properties]
            [iface.modules.payments :as payments]
            [iface.modules.loading :as loading]))


(defn bootstrap [account]
  (merge
   {:lang    :en
    :menu    {:showing false
              :items   [{:key  :metrics
                         :name "Metrics"
                         :uri  "/metrics"}
                        {:key  :accounts
                         :name "Accounts"
                         :uri  "/accounts"}
                        {:key  :orders
                         :name "Orders"
                         :uri  "/orders"}
                        {:key  :kami
                         :name "Kami"
                         :uri  "/kami"}]}
    :account account
    :route   {:page      :home
              :path      [:home]
              :params    {}
              :requester account}}
   loading/db
   accounts/default-value
   kami/default-value
   metrics/default-value
   orders/default-value
   payments/default-value
   properties/default-value))

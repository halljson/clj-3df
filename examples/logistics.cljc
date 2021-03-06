(ns logistics
  (:require
   [clj-3df.core :as df :use [exec!]]
   [clj-3df.attribute :as attribute]))

(def schema
  {;; shipments
   :shipment/payload (merge
                      (attribute/of-type :String)
                      (attribute/input-semantics :db.semantics.cardinality/many)
                      (attribute/tx-time))
   :shipment/value   (merge
                      (attribute/of-type :Number)
                      (attribute/input-semantics :db.semantics.cardinality/many)
                      (attribute/tx-time))
   :shipment/dest    (merge
                      (attribute/of-type :Number)
                      (attribute/input-semantics :db.semantics.cardinality/many)
                      (attribute/tx-time))
   :shipment/pos     (merge
                      (attribute/of-type :Number)
                      (attribute/input-semantics :db.semantics.cardinality/many)
                      (attribute/tx-time))
   ;; ports
   :port/id          (merge
                      (attribute/of-type :Number)
                      (attribute/input-semantics :db.semantics.cardinality/many)
                      (attribute/tx-time))
   ;; connections
   :conn/from        (merge
                      (attribute/of-type :Number)
                      (attribute/input-semantics :db.semantics.cardinality/many)
                      (attribute/tx-time))
   :conn/to          (merge
                      (attribute/of-type :Number)
                      (attribute/input-semantics :db.semantics.cardinality/many)
                      (attribute/tx-time))
   ;; user control handle
   :control/ports    (merge
                      (attribute/of-type :Number)
                      (attribute/input-semantics :db.semantics.cardinality/many)
                      (attribute/tx-time))})

(def db (df/create-db schema))

(def rules
  '[;; A shipment is completed if its current position is its
    ;; destination
    [(completed? ?id)
     [?id :shipment/dest ?dest]
     [?id :shipment/pos ?pos]
     [(= ?dest ?pos)]]

    ;; A shipment is on-going if it is not completed
    [(on-going? ?id)
     [?id :shipment/payload ?t] 
     (not (completed? ?id))]

    ;; Generalize to edges
    [(edge ?a ?b)
     (or (and [?t :conn/from ?a]
              [?t :conn/to ?b])
         (and [?t :conn/from ?b]
              [?t :conn/to ?a]))]

    ;; Two nodes in a graph are connected if they share an edge or an
    ;; intermediate edge connects them
    [(connected? ?from ?to)
     (edge ?from ?to)]

    [(connected? ?from ?to)
     (edge ?from ?middleman)
     (connected? ?middleman ?to)]

    ;; An error exists if the destination of a shipment is not
    ;; reachable
    [(error ?shipment)
     [?shipment :shipment/dest ?dest]
     [?shipment :shipment/pos ?pos]
     (not (connected? ?pos ?dest))]])

(comment

  (do
    (def conn (df/create-debug-conn! "ws://127.0.0.1:6262"))

    (exec! conn
      ;; 'manual' inputs
      (df/create-attribute :shipment/payload (get schema :shipment/payload))
      (df/create-attribute :shipment/value (get schema :shipment/value))
      (df/create-attribute :shipment/dest (get schema :shipment/dest))
      (df/create-attribute :shipment/pos (get schema :shipment/pos))
      (df/create-attribute :port/id (get schema :port/id))
      (df/create-attribute :conn/from (get schema :conn/from))
      (df/create-attribute :conn/to (get schema :conn/to))
      (df/create-attribute :control/ports (get schema :control/ports)))

    (exec! conn
      (df/query db "logistics/finished"
                         '[:find ?payload (sum ?val)
                           :where
                           (completed? ?e)
                           [?e :shipment/payload ?payload]
                           [?e :shipment/value ?val]] rules)

      (df/query db "logistics/open"
                         '[:find (count ?e)
                           :where
                           (on-going? ?e)] rules)

      (df/query db "logistics/errors"
                         '[:find ?shipment
                           :where
                           (error ?shipment)] rules))
    )

  (exec! conn
    (df/query db "logistics/error-cost"
                       '[:find (sum ?value)
                         :where
                         (logistics/errors ?shipment )
                         [?shipment :shipment/value ?value]] rules))

  ;; Ports and connections
  (do                                                            ;;      +-+
    (exec! conn                                                  ;;      |1|
      (df/transact db [{:db/id 1 :port/id 1}                     ;;      +-+
                       {:db/id 2 :port/id 2}                     ;;       +
                       {:db/id 3 :port/id 3}                     ;;      +-+
                       {:db/id 4 :port/id 4}                     ;;      |2|
                       {:db/id 5 :port/id 5}                     ;;      +-+
                       {:db/id 6 :port/id 6}                     ;;       +
                       {:db/id 7 :port/id 7}                     ;; +-+  +-+  +-+  +-+
                       {:db/id 8 :port/id 8}]))                  ;; |4|++|3|++|5|++|6|
    (exec! conn                                                  ;; +-+  +-+  +-+  +-+
      (df/transact db [{:db/id 9 :conn/from 1 :conn/to 2}        ;;            +
                       {:db/id 10 :conn/from 2 :conn/to 3}       ;;           +-+
                       {:db/id 11 :conn/from 3 :conn/to 4}       ;;           |7|
                       {:db/id 12 :conn/from 3 :conn/to 5}       ;;           +-+
                       {:db/id 13 :conn/from 5 :conn/to 6}       ;;            +
                       {:db/id 14 :conn/from 5 :conn/to 7}       ;;           +-+
                       {:db/id 15 :conn/from 7 :conn/to 8}])))   ;;           |8|
                                                                 ;;           +-+
  
  ;; Some initial shipments.
  
  (exec! conn
    (df/transact db [{:db/id            100
                      :shipment/payload "Chocolate"
                      :shipment/value   500
                      :shipment/dest    4
                      :shipment/pos     1}
                     {:db/id            101
                      :shipment/payload "Wisdome"
                      :shipment/value   1250
                      :shipment/dest    8
                      :shipment/pos     6}]))

  ;; Some time passes and the "Chocolate" shipment reaches its
  ;; destination.
  
  (exec! conn
    (df/transact db [{:db/id 100 :shipment/pos 4}]))

  ;; One edge in the port graph is deleted, which leads to an error in
  ;; the "Wisdome" shipment.
  
  (exec! conn
    (df/transact db [[:db/retract 14 :conn/from 5]
                     [:db/retract 14 :conn/to 7]]))

  ;; We are interested to monitor what shipements are at given ports
  ;; Via the `:control/ports` handle we specify the ports of interest.

  (exec! conn
    (df/transact db [{:db/id 999 :control/ports 6}]))

  (exec! conn
    (df/query db "analyst/shipments-at-port"
                       '[:find ?e ?s
                         :where
                         [999 :control/ports ?p]
                         [?e :shipment/pos ?p]
                         [?e :shipment/payload ?s]]))

  (exec! conn
    (df/transact db [[:db/retract 999 :control/ports 6]]))

  )

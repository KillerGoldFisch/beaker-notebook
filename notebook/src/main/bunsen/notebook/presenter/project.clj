(ns bunsen.notebook.presenter.project
  (:require [datomic.api :as d]
            [clojure.instant :as inst]
            [clojure.set :as set]
            [bunsen.common.helper.utils :as utils]
            [bunsen.notebook.helper.notebook :as nb-helper]
            [bouncer.core :as b]
            [bouncer.validators :as v]))

(def project-pattern
  [:db/id :project/name :project/description :project/created-at
   :project/public-id :project/updated-at :project/owner-id
   {:notebook/_project [
                        :notebook/public-id
                        :notebook/name
                        :notebook/open
                        :notebook/opened-at
                        :notebook/created-at
                        :notebook/updated-at
                        {:notebook/project [:project/public-id]}
                        {:publication/_notebook [
                                                 :publication/public-id
                                                 :publication/description]}]}])

(defn find-project [db owner-id project-id]
  (d/q '[:find (pull ?p pattern) .
         :in $ pattern [?oid ?pid]
         :where
         [?p :project/public-id ?pid]
         [?p :project/owner-id ?oid]]
       db project-pattern (mapv utils/uuid-from-str [owner-id project-id])))

(defn last-updated-at [p]
  (let [notebook-timestamps (mapv #(:notebook/updated-at %) (:notebook/_project p))
        timestamps (conj notebook-timestamps (:project/updated-at p))]
    (-> (sort timestamps)
        last)))

(defn fix-project-format [project]
  (-> (dissoc project :db/id)
      (assoc :last-updated-at (last-updated-at project))
      (assoc :notebooks (map nb-helper/fix-notebook-format (:notebook/_project project)))
      (dissoc :notebook/_project)))

(defn load-project [db owner-id project-id]
  (when-let [p (when (and owner-id project-id)
                 (find-project db owner-id project-id))]
    (fix-project-format p)))

(defn create-project! [conn owner-id {:keys [name description created-at updated-at]}]
  (let [p {:db/id (d/tempid :db.part/user)
           :project/public-id (d/squuid)
           :project/created-at (if created-at (inst/read-instant-timestamp created-at) (utils/now))
           :project/updated-at (if updated-at (inst/read-instant-timestamp updated-at) (utils/now))
           :project/name name
           :project/description description
           :project/owner-id (utils/uuid-from-str owner-id)}]
    @(d/transact conn [(utils/remove-nils p)])
    (dissoc p :db/id)))

(defn update-project! [conn owner-id project-id params]
  (when-let [p (find-project (d/db conn) owner-id project-id)]
    (let [updated-at (if (:updated-at params) (:updated-at params) (utils/now))
          opened-at (when (:open params) (utils/now))
          tx (-> params
                 (dissoc :public-id :project-id)
                 (assoc :project/opened-at opened-at)
                 utils/remove-nils
                 (utils/namespace-keys "project")
                 (assoc :db/id (:db/id p) :project/updated-at updated-at))]
      @(d/transact conn [tx])
      tx)))

(defn associated-notebook-eids [conn project-eid]
  (d/q '[:find [?n ...]
         :in $ ?eid
         :where [?n :notebook/project ?eid]]
        (d/db conn) project-eid))

(defn delete-project! [conn owner-id project-id]
  (when-let [p (find-project (d/db conn) owner-id project-id)]
    (let [eids (conj (associated-notebook-eids conn (:db/id p)) (:db/id p))]
      (->> eids
           (map (fn [eid] [:db.fn/retractEntity eid]))
           (d/transact conn)
           deref))))

(defn find-another-project-with-name [db owner-id project-id name]
  (d/q '[:find (pull ?p [*]) .
         :in $ [?n ?oid ?pid]
         :where
         [?p :project/name ?n]
         [?p :project/owner-id ?oid]
         [?p :project/public-id ?id]
         [(not= ?id ?pid)]]
       db [name (utils/uuid-from-str owner-id) (if project-id (utils/uuid-from-str project-id) nil)]))

(defn unique-project-name? [name project-id owner-id db]
  (not (find-another-project-with-name db owner-id project-id name)))

(defn validate-project [db owner-id project-id params]
  (-> (b/validate (select-keys params [:name :owner-id])
                  :name [v/required [unique-project-name? project-id owner-id db
                                     :message "project with this name already exists"]])
      first))

(defn find-projects [db owner-id]
  (when owner-id
    (let [projects (d/q '[:find [(pull ?p pattern) ...]
                          :in $ pattern ?oid
                          :where
                          [?p :project/owner-id ?oid]]
                        db project-pattern (utils/uuid-from-str owner-id))]
      (map fix-project-format projects))))
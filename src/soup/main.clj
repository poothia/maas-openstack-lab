(ns soup.main
  "Helps to automate some steps in MAAS/Juju OpenStack installation."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]))

; http://maas.io/docs/en/manage-cli-common#create-a-reserved-ip-range

(def rack-id "4y3h7n")

(def host-octets
  {
   "labrat-a" 10
   "labrat-b" 11
   "labrat-c" 12
   "labrat-d" 13})

(def spaces
  {"unused" nil
   "admin-api"          150
   "internal-api"       100
   "public-api"          50
   "compute-data"       250
   "compute-external"    99
   "storage-data"       200
   "storage-cluster"     30})

(defn is-subnet-managed? [subnet-name] (not (#{"compute-external"} subnet-name)))
(defn is-space-subnet? [space-name] (some? (get spaces space-name)))
(defn all-spaces [] (keys spaces))
(defn all-subnets [] (filter is-space-subnet? (keys spaces)))
(defn all-vlan-tags [] (filter some? (vals spaces)))
(defn managed-subnets [] (filter is-subnet-managed? (all-subnets)))

(defn managed-hostnames [] (keys host-octets))

;;;----------------------------------------------------------------------

; maas maas spaces read
(defn read-spaces []
  (json/read (io/reader (io/resource "spaces.json")) :key-fn keyword))
; maas maas vlans read 41
(defn read-vlans []
  (json/read (io/reader (io/resource "vlans.json")) :key-fn keyword))
; maas maas nodes read
(defn read-nodes []
  (json/read (io/reader (io/resource "nodes.json")) :key-fn keyword))


(defn space-to-id-map []
  (apply hash-map (flatten (map (juxt :name :id)  (read-spaces)))))

(defn vlan-tag-to-id-map []
  (apply hash-map (flatten (map (juxt :vid :id)  (read-vlans)))))

(defn node-to-id-map []
  (apply hash-map (flatten (map (juxt :hostname :system_id)  (read-nodes)))))

(defn hostname->interface_set-map []
  (apply hash-map (apply concat (map (juxt :hostname :interface_set)  (read-nodes)))))

(defn hostname->interface_set [hostname]
  (apply hash-set (get (hostname->interface_set-map) hostname)))

(defn hostname-fabric->nic [hostname fabric]
  (first (clojure.set/select #(and (= "physical" (:type % ))
                                   (= fabric (get-in % [:vlan :fabric]))) (hostname->interface_set hostname))))

(defn hostname-fabric->vlan-nics [hostname fabric]
  (clojure.set/select #(and (= "vlan" (:type % ))
                            (= fabric (get-in % [:vlan :fabric]))) (hostname->interface_set hostname)))

(defn get-nic-id-in-fabric [hostname fabric-name]
  (:id (hostname-fabric->nic hostname fabric-name)))
(defn get-nic-name-in-fabric [hostname fabric-name]
  (:name (hostname-fabric->nic hostname fabric-name)))

(defn vlan-tag->maas-id [vlan-tag]
  (get (vlan-tag-to-id-map) vlan-tag))

(defn hostname->octet [hostname]
  (get host-octets hostname))



;;;----------------------------------------------------------------------

(defn cmd-create-space [name]
  (format "spaces create name=%s" name))

(defn cmd-update-subnet [space-name]
  (let [vlan-number (get spaces space-name)
        subnet-name space-name
        space-number (get (space-to-id-map) space-name)]
    (if vlan-number
      (format "subnet update vlan:%s name=%s space=%d gateway_ip=10.%s.0.1" vlan-number space-name space-number vlan-number)
      nil)))

(defn cmd-create-iprange [subnet-name]
  (format "ipranges create type=dynamic start_ip=10.%d.0.40 end_ip=10.%d.0.90" (get spaces subnet-name) (get spaces subnet-name)))

(defn cmd-update-vlan [space-name]
  (let [vlan-number (get spaces space-name)
        fabric-id 41
        subnet-name space-name]
    (format "vlan update %d %d name=%s dhcp_on=True primary_rack=%s" fabric-id vlan-number subnet-name rack-id)))

(defn cmd-interface-create-vlan [node-maas-id parent-nic-maas-id vlan-maas-id]
  (format  "interfaces create-vlan %s vlan=%d parent=%d" node-maas-id vlan-maas-id parent-nic-maas-id))

(defn cmd-interface-delete-vlan [node-maas-id vlan-nic-maas-id]
  (format  "interface delete %s %d" node-maas-id vlan-nic-maas-id))

(defn cmd-link-subnet [node-maas-id parent-nic-name node-octet vlan-tag]
  (format "interface link-subnet %s %s.%d subnet=cidr:10.%d.0.0/20 mode=static ip_address=10.%d.0.%d"
          node-maas-id parent-nic-name vlan-tag vlan-tag vlan-tag node-octet))


(defn cmds-create-spaces []
  (map cmd-create-space (all-spaces)))
(defn cmds-update-subnets []
  (map cmd-update-subnet (all-subnets)))
(defn cmds-create-ipranges []
  (map cmd-create-iprange (managed-subnets)))
(defn cmds-update-vlans []
  (map cmd-update-vlan (managed-subnets)))

(defn cmds-create-node-vlan-interfaces [hostname]
  (let [node-maas-id (get (node-to-id-map) hostname)
        parent-nic-maas-id (get-nic-id-in-fabric hostname "private")]
    (for [vlan-tag (all-vlan-tags)]
      (cmd-interface-create-vlan node-maas-id parent-nic-maas-id (vlan-tag->maas-id vlan-tag)))))

(defn cmds-delete-node-vlan-interfaces [hostname]
  (let [node-maas-id (get (node-to-id-map) hostname)
        vlan-nics (hostname-fabric->vlan-nics hostname "private")]
    (for [vlan-nic-maas-id (map :id vlan-nics)]
      (cmd-interface-delete-vlan node-maas-id vlan-nic-maas-id ))))

(defn cmds-link-node-subnet [hostname]
  (let [node-maas-id (get (node-to-id-map) hostname)
        parent-nic-name (get-nic-name-in-fabric hostname "private")
        node-octet (hostname->octet hostname)]
    (for [vlan-tag (all-vlan-tags)]
      (cmd-link-subnet node-maas-id parent-nic-name node-octet vlan-tag))))

(defn cmds-create-vlan-interfaces []
  (apply concat (map cmds-create-node-vlan-interfaces (managed-hostnames))))
(defn cmds-delete-vlan-interfaces []
  (apply concat (map cmds-delete-node-vlan-interfaces (managed-hostnames))))

(defn cmds-link-subnets []
  (apply concat (map cmds-link-node-subnet (managed-hostnames))))

(defn cmds [what]
  (filter some?
          (case what
            :scorch (cmds-delete-vlan-interfaces)
             (concat (cmds-create-spaces)
                    (cmds-update-subnets)
                    (cmds-create-ipranges)
                    (cmds-update-vlans)
                    (cmds-create-vlan-interfaces)
                    (cmds-link-subnets)))))


;;;----------------------------------------------------------------------
(defn cli-line [suffix]
  (str "maas maas " suffix))

(defn main [what]
  (dorun (map println (map cli-line (cmds what)))))
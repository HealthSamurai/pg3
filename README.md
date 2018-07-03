# Postgres cluster controller for kubernetes [![Build Status](https://travis-ci.org/HealthSamurai/pg3.svg?branch=master)](https://travis-ci.org/HealthSamurai/pg3)

Kubernetes custom controller for HA PostgreSQL

## Architecture

![pg3](https://github.com/niquola/pg3/raw/master/doc/pg3.png)

## Installation

Use `deploy-controller/secret.yaml` as a template to create secrets.
You need to specify:
* KUBE_TOKEN
* KUBE_URL
* TELEGRAM_TOKEN
* TELEGRAM_CHATID

```sh
kubectl create namespace pg3-controller
kubectl -n pg3-controller apply -f deploy-controller/setup.yml
```

Create `PgCluster` resource to start a cluster initialization.
You can use `deploy-controller/pg-cluster.yaml` as an example of `PgCluster`.

```sh
kubectl create namespace pg3
kubectl -n pg3 apply -f deploy-controller/pg-cluster.yml
```

## Development

Using minikube  

To cleanup cluster you can use this command  
```
kubectl -n pg3 delete pgbackups --all && kubectl delete pgcluster -n pg3 --all && kubectl delete pginstance -n pg3 --all  && kubectl delete all -n pg3 -l system=pg3 && kubectl delete -n pg3 persistentvolumeclaim --all
```

## License

Copyright © 2017-2018 Health-Samurai

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.

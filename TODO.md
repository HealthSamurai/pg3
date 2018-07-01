## CRITICAL
* Create base backups
* Human friendly notification

## MEDIUM 
* Pluggable monitoring
* Initialize replica from WAL files first
* New master selection

## LOW
* Multireplica support
* Consistent naming, everything have "pg3-" prefix except cluster name
* Unify the way how params are stored in labels. `(get-in res [:metadata :labels :service])` should work for any resource.
* Why we use service name for pginstances search pg3.utils:10
* Liveness probes
* Readiness probes

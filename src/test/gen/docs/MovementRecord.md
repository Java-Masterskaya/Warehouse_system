

# MovementRecord


## Properties

| Name | Type | Description | Notes |
|------------ | ------------- | ------------- | -------------|
|**id** | **Long** |  |  [optional] |
|**type** | [**TypeEnum**](#TypeEnum) | RECEIVE - приход, WRITE_OFF - списание, ADJUSTMENT - корректировка |  [optional] |
|**quantity** | **Integer** |  |  [optional] |
|**createdAt** | **OffsetDateTime** |  |  [optional] |
|**createdBy** | **String** | Username администратора, выполнившего операцию |  [optional] |
|**comment** | **String** |  |  [optional] |



## Enum: TypeEnum

| Name | Value |
|---- | -----|
| RECEIVE | &quot;RECEIVE&quot; |
| WRITE_OFF | &quot;WRITE_OFF&quot; |
| ADJUSTMENT | &quot;ADJUSTMENT&quot; |




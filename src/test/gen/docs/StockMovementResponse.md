

# StockMovementResponse

Ответ при операции движения товара (приход, списание, инвентаризация)

## Properties

| Name | Type | Description | Notes |
|------------ | ------------- | ------------- | -------------|
|**itemId** | **Long** | ID товара |  [optional] |
|**movementId** | **Long** | ID созданного движения (null, если изменений не было) |  [optional] |
|**type** | [**TypeEnum**](#TypeEnum) | Тип движения (null, если изменений не было) |  [optional] |
|**quantity** | **Integer** | Количество операции (дельта для ADJUSTMENT) |  [optional] |
|**stockAfter** | **Integer** | Остаток после операции |  [optional] |
|**createdAt** | **OffsetDateTime** |  |  [optional] |
|**lowStockAlert** | **Boolean** | Флаг низкого остатка |  [optional] |



## Enum: TypeEnum

| Name | Value |
|---- | -----|
| RECEIVE | &quot;RECEIVE&quot; |
| WRITE_OFF | &quot;WRITE_OFF&quot; |
| ADJUSTMENT | &quot;ADJUSTMENT&quot; |




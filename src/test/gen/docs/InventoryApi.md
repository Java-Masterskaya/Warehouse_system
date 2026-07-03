# InventoryApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**stocktake**](InventoryApi.md#stocktake) | **POST** /api/v1/inventory/stocktake | Провести инвентаризацию (скорректировать остаток) |


<a id="stocktake"></a>
# **stocktake**
> StockMovementResponse stocktake(stocktakeRequest)

Провести инвентаризацию (скорректировать остаток)

Доступно только роли Admin. Сравнивает учётный остаток с фактическим и создаёт корректирующее движение ADJUSTMENT.

### Example
```java
// Import classes:
import org.openapitools.client.ApiClient;
import org.openapitools.client.ApiException;
import org.openapitools.client.Configuration;
import org.openapitools.client.models.*;
import org.openapitools.client.api.InventoryApi;

public class Example {
  public static void main(String[] args) {
    ApiClient defaultClient = Configuration.getDefaultApiClient();
    defaultClient.setBasePath("http://localhost");

    InventoryApi apiInstance = new InventoryApi(defaultClient);
    StocktakeRequest stocktakeRequest = new StocktakeRequest(); // StocktakeRequest | 
    try {
      StockMovementResponse result = apiInstance.stocktake(stocktakeRequest);
      System.out.println(result);
    } catch (ApiException e) {
      System.err.println("Exception when calling InventoryApi#stocktake");
      System.err.println("Status code: " + e.getCode());
      System.err.println("Reason: " + e.getResponseBody());
      System.err.println("Response headers: " + e.getResponseHeaders());
      e.printStackTrace();
    }
  }
}
```

### Parameters

| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **stocktakeRequest** | [**StocktakeRequest**](StocktakeRequest.md)|  | |

### Return type

[**StockMovementResponse**](StockMovementResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Инвентаризация проведена |  -  |
| **400** | Ошибка валидации |  -  |
| **401** | Не авторизован |  -  |
| **403** | Доступ запрещен (необходима роль Admin) |  -  |
| **404** | Товар или остаток не найден |  -  |


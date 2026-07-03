# ItemsApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**updateItem**](ItemsApi.md#updateItem) | **PUT** /api/v1/items/{itemId} | Обновить информацию о товаре |


<a id="updateItem"></a>
# **updateItem**
> ItemResponse updateItem(itemId, updateItemRequest)

Обновить информацию о товаре

Доступно только Admin. SKU изменить нельзя. Требуется полная замена (все поля обязательны).

### Example
```java
// Import classes:
import org.openapitools.client.ApiClient;
import org.openapitools.client.ApiException;
import org.openapitools.client.Configuration;
import org.openapitools.client.models.*;
import org.openapitools.client.api.ItemsApi;

public class Example {
  public static void main(String[] args) {
    ApiClient defaultClient = Configuration.getDefaultApiClient();
    defaultClient.setBasePath("http://localhost");

    ItemsApi apiInstance = new ItemsApi(defaultClient);
    Long itemId = 56L; // Long | 
    UpdateItemRequest updateItemRequest = new UpdateItemRequest(); // UpdateItemRequest | 
    try {
      ItemResponse result = apiInstance.updateItem(itemId, updateItemRequest);
      System.out.println(result);
    } catch (ApiException e) {
      System.err.println("Exception when calling ItemsApi#updateItem");
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
| **itemId** | **Long**|  | |
| **updateItemRequest** | [**UpdateItemRequest**](UpdateItemRequest.md)|  | |

### Return type

[**ItemResponse**](ItemResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Товар успешно обновлен |  -  |
| **400** | Неверный запрос (отрицательный minStock, пустая строка или отсутствует обязательное поле) |  -  |
| **401** | Не авторизован (отсутствует или недействительный токен) |  -  |
| **403** | Доступ запрещен (необходима роль Admin) |  -  |
| **404** | Товар не найден или неактивен (is_active &#x3D; false) |  -  |


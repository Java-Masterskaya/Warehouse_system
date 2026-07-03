# DefaultApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**apiV1ItemsGet**](DefaultApi.md#apiV1ItemsGet) | **GET** /api/v1/items | Получить каталог товаров |
| [**apiV1ItemsItemIdGet**](DefaultApi.md#apiV1ItemsItemIdGet) | **GET** /api/v1/items/{itemId} | Получить информацию о товаре и его текущем остатке |
| [**apiV1ItemsItemIdMovementsGet**](DefaultApi.md#apiV1ItemsItemIdMovementsGet) | **GET** /api/v1/items/{itemId}/movements | Получить историю движения товара |
| [**apiV1ItemsItemIdMovementsPost**](DefaultApi.md#apiV1ItemsItemIdMovementsPost) | **POST** /api/v1/items/{itemId}/movements | Оформить приход или расход товара |
| [**apiV1ItemsPost**](DefaultApi.md#apiV1ItemsPost) | **POST** /api/v1/items | Добавить новый товар в каталог |


<a id="apiV1ItemsGet"></a>
# **apiV1ItemsGet**
> List&lt;Item&gt; apiV1ItemsGet(category)

Получить каталог товаров

Доступно ролям User и Admin

### Example
```java
// Import classes:
import org.openapitools.client.ApiClient;
import org.openapitools.client.ApiException;
import org.openapitools.client.Configuration;
import org.openapitools.client.models.*;
import org.openapitools.client.api.DefaultApi;

public class Example {
  public static void main(String[] args) {
    ApiClient defaultClient = Configuration.getDefaultApiClient();
    defaultClient.setBasePath("http://localhost");

    DefaultApi apiInstance = new DefaultApi(defaultClient);
    String category = "category_example"; // String | 
    try {
      List<Item> result = apiInstance.apiV1ItemsGet(category);
      System.out.println(result);
    } catch (ApiException e) {
      System.err.println("Exception when calling DefaultApi#apiV1ItemsGet");
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
| **category** | **String**|  | [optional] |

### Return type

[**List&lt;Item&gt;**](Item.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Успешный ответ |  -  |

<a id="apiV1ItemsItemIdGet"></a>
# **apiV1ItemsItemIdGet**
> ItemDetails apiV1ItemsItemIdGet(itemId)

Получить информацию о товаре и его текущем остатке

Доступно ролям User и Admin

### Example
```java
// Import classes:
import org.openapitools.client.ApiClient;
import org.openapitools.client.ApiException;
import org.openapitools.client.Configuration;
import org.openapitools.client.models.*;
import org.openapitools.client.api.DefaultApi;

public class Example {
  public static void main(String[] args) {
    ApiClient defaultClient = Configuration.getDefaultApiClient();
    defaultClient.setBasePath("http://localhost");

    DefaultApi apiInstance = new DefaultApi(defaultClient);
    Long itemId = 56L; // Long | 
    try {
      ItemDetails result = apiInstance.apiV1ItemsItemIdGet(itemId);
      System.out.println(result);
    } catch (ApiException e) {
      System.err.println("Exception when calling DefaultApi#apiV1ItemsItemIdGet");
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

### Return type

[**ItemDetails**](ItemDetails.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Успешный ответ |  -  |
| **404** | Товар не найден |  -  |

<a id="apiV1ItemsItemIdMovementsGet"></a>
# **apiV1ItemsItemIdMovementsGet**
> List&lt;MovementRecord&gt; apiV1ItemsItemIdMovementsGet(itemId)

Получить историю движения товара

Доступно ролям User и Admin

### Example
```java
// Import classes:
import org.openapitools.client.ApiClient;
import org.openapitools.client.ApiException;
import org.openapitools.client.Configuration;
import org.openapitools.client.models.*;
import org.openapitools.client.api.DefaultApi;

public class Example {
  public static void main(String[] args) {
    ApiClient defaultClient = Configuration.getDefaultApiClient();
    defaultClient.setBasePath("http://localhost");

    DefaultApi apiInstance = new DefaultApi(defaultClient);
    Long itemId = 56L; // Long | 
    try {
      List<MovementRecord> result = apiInstance.apiV1ItemsItemIdMovementsGet(itemId);
      System.out.println(result);
    } catch (ApiException e) {
      System.err.println("Exception when calling DefaultApi#apiV1ItemsItemIdMovementsGet");
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

### Return type

[**List&lt;MovementRecord&gt;**](MovementRecord.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Успешный ответ |  -  |

<a id="apiV1ItemsItemIdMovementsPost"></a>
# **apiV1ItemsItemIdMovementsPost**
> apiV1ItemsItemIdMovementsPost(itemId, movementRequest)

Оформить приход или расход товара

Доступно только роли Admin

### Example
```java
// Import classes:
import org.openapitools.client.ApiClient;
import org.openapitools.client.ApiException;
import org.openapitools.client.Configuration;
import org.openapitools.client.models.*;
import org.openapitools.client.api.DefaultApi;

public class Example {
  public static void main(String[] args) {
    ApiClient defaultClient = Configuration.getDefaultApiClient();
    defaultClient.setBasePath("http://localhost");

    DefaultApi apiInstance = new DefaultApi(defaultClient);
    Long itemId = 56L; // Long | 
    MovementRequest movementRequest = new MovementRequest(); // MovementRequest | 
    try {
      apiInstance.apiV1ItemsItemIdMovementsPost(itemId, movementRequest);
    } catch (ApiException e) {
      System.err.println("Exception when calling DefaultApi#apiV1ItemsItemIdMovementsPost");
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
| **movementRequest** | [**MovementRequest**](MovementRequest.md)|  | |

### Return type

null (empty response body)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: Not defined

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **201** | Операция успешно выполнена |  -  |
| **400** | Ошибка валидации (например, попытка списать больше, чем есть на складе) |  -  |
| **403** | Доступ запрещен (необходима роль Admin) |  -  |

<a id="apiV1ItemsPost"></a>
# **apiV1ItemsPost**
> apiV1ItemsPost(itemCreateRequest)

Добавить новый товар в каталог

Доступно только роли Admin

### Example
```java
// Import classes:
import org.openapitools.client.ApiClient;
import org.openapitools.client.ApiException;
import org.openapitools.client.Configuration;
import org.openapitools.client.models.*;
import org.openapitools.client.api.DefaultApi;

public class Example {
  public static void main(String[] args) {
    ApiClient defaultClient = Configuration.getDefaultApiClient();
    defaultClient.setBasePath("http://localhost");

    DefaultApi apiInstance = new DefaultApi(defaultClient);
    ItemCreateRequest itemCreateRequest = new ItemCreateRequest(); // ItemCreateRequest | 
    try {
      apiInstance.apiV1ItemsPost(itemCreateRequest);
    } catch (ApiException e) {
      System.err.println("Exception when calling DefaultApi#apiV1ItemsPost");
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
| **itemCreateRequest** | [**ItemCreateRequest**](ItemCreateRequest.md)|  | |

### Return type

null (empty response body)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: Not defined

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **201** | Товар успешно создан |  -  |
| **403** | Доступ запрещен (необходима роль Admin) |  -  |


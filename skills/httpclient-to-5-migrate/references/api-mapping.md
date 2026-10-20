# HttpClient API mapping

Lookup tables and worked examples for [migration.md](migration.md). The lists
are not exhaustive; resolve anything missing from the HttpClient 5 Javadoc and
the [official migration guide](https://hc.apache.org/httpcomponents-client-5.4.x/migration-guide/).

## Packages

| Legacy | HttpClient 5 |
|---|---|
| `org.apache.http.impl.client.{HttpClients,CloseableHttpClient,HttpClientBuilder}` | `org.apache.hc.client5.http.impl.classic.*` |
| `org.apache.http.client.methods.{HttpGet,HttpPost,HttpPut,HttpDelete,…}` | `org.apache.hc.client5.http.classic.methods.*` |
| `org.apache.http.client.methods.HttpRequestBase` | `org.apache.hc.client5.http.classic.methods.HttpUriRequestBase` |
| `org.apache.http.client.config.RequestConfig` | `org.apache.hc.client5.http.config.RequestConfig` |
| `org.apache.http.client.entity.UrlEncodedFormEntity` | `org.apache.hc.client5.http.entity.UrlEncodedFormEntity` (client5, not core5) |
| `org.apache.http.entity.mime.MultipartEntityBuilder` (httpmime) | `org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder` |
| `org.apache.http.client.protocol.HttpClientContext` | `org.apache.hc.client5.http.protocol.HttpClientContext` |
| `org.apache.http.impl.conn.PoolingHttpClientConnectionManager` | `org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager` (create with `PoolingHttpClientConnectionManagerBuilder`) |
| `org.apache.http.auth.{AuthScope,UsernamePasswordCredentials,NTCredentials}` | `org.apache.hc.client5.http.auth.*` |
| `org.apache.http.impl.client.BasicCredentialsProvider` | `org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider` |
| `org.apache.http.{HttpEntity,HttpHost,HttpStatus,NameValuePair,Header}` | `org.apache.hc.core5.http.*` |
| `org.apache.http.entity.{StringEntity,ByteArrayEntity,ContentType}` | `org.apache.hc.core5.http.io.entity.*`; `ContentType` is `org.apache.hc.core5.http.ContentType` |
| `org.apache.http.util.EntityUtils` | `org.apache.hc.core5.http.io.entity.EntityUtils` (`toString` also throws `ParseException`) |
| `org.apache.http.message.BasicNameValuePair` | `org.apache.hc.core5.http.message.BasicNameValuePair` |
| `org.apache.http.client.utils.URIBuilder` | `org.apache.hc.core5.net.URIBuilder` |
| `org.apache.http.ssl.SSLContextBuilder` / `SSLContexts` | `org.apache.hc.core5.ssl.*` |

## HttpClient 4.x behavior changes

| HttpClient 4.x | HttpClient 5.x |
|---|---|
| `HttpRequestBase#getURI()` | `HttpUriRequest#getUri()`, which throws `URISyntaxException` |
| `HttpResponse#getStatusLine().getStatusCode()` | `HttpResponse#getCode()` |
| `HttpResponse#getStatusLine()` | `new StatusLine(response)` |
| `HttpEntity#getContentType().getValue()` | `HttpEntity#getContentType()` (returns a `String`) |
| `HttpMessage#getAllHeaders()` | `MessageHeaders#getHeaders()` |
| `HttpRequest#getRequestLine()` | `new RequestLine(request)` |
| `HttpEntityEnclosingRequest` | `HttpEntityContainer`; any method can carry an entity |
| `client.execute(request)` returning a response to close | `client.execute(request, response -> …)`, which consumes and closes the response |
| `RequestConfig.custom().setConnectTimeout(int)` | `ConnectionConfig.custom().setConnectTimeout(Timeout)` on the connection manager (`RequestConfig#setConnectTimeout(Timeout)` remains but is deprecated) |
| `RequestConfig.custom().setSocketTimeout(int)` | `RequestConfig.custom().setResponseTimeout(Timeout)`, or `ConnectionConfig`/`SocketConfig#setSocketTimeout`/`setSoTimeout` |
| `RequestConfig.custom().setConnectionRequestTimeout(int)` | `setConnectionRequestTimeout(Timeout)` |
| `CookieSpecs.STANDARD` / `IGNORE_COOKIES` | `StandardCookieSpec.RELAXED` / `StandardCookieSpec.IGNORE` |
| `HttpClients.custom().setDefaultSocketConfig(…)` | `PoolingHttpClientConnectionManagerBuilder.create().setDefaultSocketConfig(…)`, then `setConnectionManager(…)` |
| `HttpClientBuilder#setMaxConnTotal/setMaxConnPerRoute` | the same methods on `PoolingHttpClientConnectionManagerBuilder` |
| `HttpClients.custom().setRetryHandler(…)` | `HttpClients.custom().setRetryStrategy(…)` |
| `HttpClients.custom().addInterceptorLast(<logging>)` | `addExecInterceptorFirst("name", <logging>)`, so compressed payloads are not logged |
| `SSLConnectionSocketFactory` | `ClientTlsStrategyBuilder`/`DefaultClientTlsStrategy` passed to the connection manager builder |
| `HttpClientConnectionManager#closeExpiredConnections()` / `closeIdleConnections()` | `ConnPoolControl#closeExpired()` / `closeIdle(TimeValue)` |
| `BasicHttpContext` | `HttpClientContext.create()` |
| `HttpContext.getAttribute(HttpCoreContext.HTTP_TARGET_HOST)` | `HttpClientContext#getHttpRoute().getTargetHost()` |
| `new UsernamePasswordCredentials(user, password)` | `new UsernamePasswordCredentials(user, password.toCharArray())` |
| `AuthScope.ANY` | `new AuthScope(null, -1)` |
| `CredentialsProvider#setCredentials/clear` | `CredentialsStore` (`BasicCredentialsProvider` implements it); `CredentialsProvider#getCredentials(AuthScope, HttpContext)` |
| `DefaultHttpClient`, `HttpParams`, `BasicHttpParams` | `HttpClients.custom()` builders and `RequestConfig`/`ConnectionConfig` |
| `URIUtils.normalizeSyntax`, `RequestConfig#isNormalizeUri` | Removed; `URIBuilder#optimize()` normalizes more aggressively, so check the behavior |

HttpClient 5 disables SSL and weak TLS versions by default. Keep the existing
TLS policy explicit when the app configured one.

## Commons HttpClient 3.x

| Commons HttpClient 3.x | HttpClient 5.x classic API |
|---|---|
| `org.apache.commons.httpclient.HttpClient` | `CloseableHttpClient` from `HttpClients.createDefault()` or a configured builder |
| `HttpClient#getState()` | `BasicCredentialsProvider`, `BasicCookieStore`, or an `HttpClientContext` |
| `methods.GetMethod` / `methods.PostMethod` | `HttpGet` / `HttpPost` |
| `HttpMethod#getName()` | `HttpUriRequest#getMethod()` |
| `PostMethod#addParameter(name, value)` | a list of `BasicNameValuePair` set as `new UrlEncodedFormEntity(list)` on the `HttpPost` |
| `org.apache.commons.httpclient.NameValuePair` | `org.apache.hc.core5.http.NameValuePair` / `message.BasicNameValuePair` |
| `HttpClient#executeMethod(method)` | `client.execute(request, responseHandler)` |
| `HttpMethod#getResponseBodyAsString()` | `EntityUtils.toString(response.getEntity())` inside the handler |
| `method.releaseConnection()` | Remove; the response handler closes the response |

Move 3.x credentials and cookies into a configured provider, cookie store or
`HttpClientContext`, and migrate response consumption and client lifecycle, not
only the request objects.

## Checked exceptions

`CloseableHttpClient#close()` throws `IOException`, and `getUri()` throws
`URISyntaxException`. Introducing them can break compilation when the original
method declared no checked exceptions.

- Catch and wrap the exception when the method contract must stay unchanged.
- Add a `throws` clause only when every caller and implemented interface allows it.
- Do not close an injected or shared client per request; leave its lifecycle
  to its owner. Clients are thread-safe and expensive to create, so reuse them.

## Examples

### Request objects, configuration and form entities

Before:

```java
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;

HttpGet request = new HttpGet(url);
URI uri = request.getURI();

RequestConfig config = RequestConfig.custom()
        .setConnectTimeout(1000)
        .setSocketTimeout(2000)
        .build();

UrlEncodedFormEntity entity = new UrlEncodedFormEntity(
        List.of(new BasicNameValuePair("name", "value")));
String contentType = entity.getContentType().getValue();
```

After:

```java
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.util.Timeout;

HttpGet request = new HttpGet(url);
URI uri = request.getUri(); // throws URISyntaxException

RequestConfig config = RequestConfig.custom()
        .setConnectTimeout(Timeout.ofMilliseconds(1000)) // prefer ConnectionConfig for a shared manager
        .setResponseTimeout(Timeout.ofMilliseconds(2000))
        .build();

UrlEncodedFormEntity entity = new UrlEncodedFormEntity(
        List.of(new BasicNameValuePair("name", "value")));
String contentType = entity.getContentType();
```

### Timeouts on a connection manager

Before:

```java
SocketConfig socketConfig = SocketConfig.custom()
        .setSoTimeout(readTimeoutMs)
        .setTcpNoDelay(true)
        .build();
RequestConfig requestConfig = RequestConfig.custom()
        .setConnectTimeout(connectTimeoutMs)
        .setSocketTimeout(readTimeoutMs)
        .setConnectionRequestTimeout(connectTimeoutMs)
        .setCookieSpec(CookieSpecs.STANDARD)
        .build();
CloseableHttpClient client = HttpClients.custom()
        .setDefaultSocketConfig(socketConfig)
        .setDefaultRequestConfig(requestConfig)
        .setMaxConnPerRoute(maxConnections)
        .build();
```

After:

```java
PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
        .setDefaultSocketConfig(SocketConfig.custom()
                .setSoTimeout(Timeout.ofMilliseconds(readTimeoutMs))
                .setTcpNoDelay(true)
                .build())
        .setDefaultConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                .setSocketTimeout(Timeout.ofMilliseconds(readTimeoutMs))
                .build())
        .setMaxConnPerRoute(maxConnections)
        .build();
CloseableHttpClient client = HttpClients.custom()
        .setConnectionManager(connectionManager)
        .setDefaultRequestConfig(RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                .setResponseTimeout(Timeout.ofMilliseconds(readTimeoutMs))
                .setCookieSpec(StandardCookieSpec.RELAXED)
                .build())
        .build();
```

### Response handling

Before:

```java
HttpResponse response = httpClient.execute(request);
if (response.getStatusLine().getStatusCode() >= 300) {
    throw new IOException("Unexpected status " + response.getStatusLine());
}
String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
```

After:

```java
String body = httpClient.execute(request, response -> {
    if (response.getCode() >= 300) {
        throw new ClientProtocolException(new StatusLine(response).toString());
    }
    return EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
});
```

### Preemptive basic authentication

Before:

```java
DefaultHttpClient client = new DefaultHttpClient();
client.getCredentialsProvider().setCredentials(AuthScope.ANY,
        new UsernamePasswordCredentials(username, password));
AuthCache authCache = new BasicAuthCache();
authCache.put(new HttpHost("localhost", port), new BasicScheme());
BasicHttpContext context = new BasicHttpContext();
context.setAttribute(HttpClientContext.AUTH_CACHE, authCache);
HttpResponse response = client.execute(put, context);
```

After:

```java
CloseableHttpClient client = HttpClients.createDefault();
HttpClientContext context = ContextBuilder.create()
        .preemptiveBasicAuth(new HttpHost("localhost", port),
                new UsernamePasswordCredentials(username, password.toCharArray()))
        .build();
int status = client.execute(put, context, response -> response.getCode());
```

### Custom credentials provider

In 5.x `CredentialsProvider` has a single method,
`getCredentials(AuthScope, HttpContext)`. Remove `setCredentials` and `clear`
overrides from an immutable implementation (or implement `CredentialsStore`
when they are used), add the `HttpContext` parameter, and pass passwords as
`char[]` to `UsernamePasswordCredentials` and `NTCredentials`.

### Commons HttpClient 3 form post

Before:

```java
HttpClient client = new HttpClient();
PostMethod post = new PostMethod(url);
post.addParameter("name", "value");
try {
    client.executeMethod(post);
    return post.getResponseBodyAsString();
} finally {
    post.releaseConnection();
}
```

After:

```java
HttpPost post = new HttpPost(url);
post.setEntity(new UrlEncodedFormEntity(List.of(new BasicNameValuePair("name", "value"))));
return client.execute(post, response -> EntityUtils.toString(response.getEntity()));
```

### Dependencies

Before:

```xml
<dependency>
    <groupId>org.apache.httpcomponents</groupId>
    <artifactId>httpclient</artifactId>
    <scope>provided</scope>
</dependency>
<dependency>
    <groupId>org.apache.httpcomponents</groupId>
    <artifactId>httpclient-cache</artifactId>
    <scope>provided</scope>
</dependency>
```

After:

```xml
<dependency>
    <groupId>org.apache.httpcomponents.client5</groupId>
    <artifactId>httpclient5</artifactId>
    <version>${httpclient5.version}</version>
    <scope>compile</scope>
    <exclusions>
        <exclusion>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </exclusion>
    </exclusions>
</dependency>
<dependency>
    <groupId>org.apache.httpcomponents.client5</groupId>
    <artifactId>httpclient5-cache</artifactId>
    <version>${httpclient5.version}</version>
    <scope>compile</scope>
    <exclusions>
        <exclusion>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

Remove `httpmime`; `MultipartEntityBuilder` is part of `httpclient5`.

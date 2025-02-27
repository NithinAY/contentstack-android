/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.contentstack.okhttp.internal.http;

import com.contentstack.okhttp.Connection;
import com.contentstack.okhttp.Headers;
import com.contentstack.okhttp.MediaType;
import com.contentstack.okhttp.OkHttpClient;
import com.contentstack.okhttp.Protocol;
import com.contentstack.okhttp.Request;
import com.contentstack.okhttp.Response;
import com.contentstack.okhttp.ResponseBody;
import com.contentstack.okhttp.Route;
import com.contentstack.okhttp.internal.Internal;
import com.contentstack.okhttp.internal.InternalCache;
import com.contentstack.okhttp.internal.Util;
import com.contentstack.okio.Buffer;
import com.contentstack.okio.BufferedSink;
import com.contentstack.okio.BufferedSource;
import com.contentstack.okio.GzipSource;
import com.contentstack.okio.Okio;
import com.contentstack.okio.Sink;
import com.contentstack.okio.Source;

import java.io.IOException;
import java.io.InputStream;
import java.net.CookieHandler;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.URL;
import java.security.cert.CertificateException;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLHandshakeException;

import static com.contentstack.okhttp.internal.Util.closeQuietly;
import static com.contentstack.okhttp.internal.http.StatusLine.HTTP_CONTINUE;
import static com.contentstack.okhttp.internal.http.StatusLine.HTTP_TEMP_REDIRECT;
import static java.net.HttpURLConnection.HTTP_MOVED_PERM;
import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.net.HttpURLConnection.HTTP_MULT_CHOICE;
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static java.net.HttpURLConnection.HTTP_PROXY_AUTH;
import static java.net.HttpURLConnection.HTTP_SEE_OTHER;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;

/**
 * Handles a single HTTP request/response pair. Each HTTP engine follows this
 * lifecycle:
 * <ol>
 * <li>It is created.
 * <li>The HTTP request message is sent with sendRequest(). Once the request
 * is sent it is an error to modify the request headers. After
 * sendRequest() has been called the request body can be written to if
 * it exists.
 * <li>The HTTP response message is read with readResponse(). After the
 * response has been read the response headers and body can be read.
 * All responses have a response body input stream, though in some
 * instances this stream is empty.
 * </ol>
 *
 * <p>The request and response may be served by the HTTP response cache, by the
 * network, or by both in the event of a conditional GET.
 */
public final class HttpEngine {
  /**
   * How many redirects should we follow? Chrome follows 21; Firefox, curl,
   * and wget follow 20; Safari follows 16; and HTTP/1.0 recommends 5.
   */
  public static final int MAX_REDIRECTS = 20;

  private static final ResponseBody EMPTY_BODY = new ResponseBody() {
    @Override public MediaType contentType() {
      return null;
    }
    @Override public long contentLength() {
      return 0;
    }
    @Override public BufferedSource source() {
      return new Buffer();
    }
  };

  final OkHttpClient client;

  private Connection connection;
  private RouteSelector routeSelector;
  private Route route;
  private final Response priorResponse;

  private Transport transport;

  /** The time when the request headers were written, or -1 if they haven't been written yet. */
  long sentRequestMillis = -1;

  /**
   * True if this client added an "Accept-Encoding: gzip" header field and is
   * therefore responsible for also decompressing the transfer stream.
   */
  private boolean transparentGzip;

  /**
   * True if the request body must be completely buffered before transmission;
   * false if it can be streamed. Buffering has two advantages: we don't need
   * the content-length in advance and we can retransmit if necessary. The
   * upside of streaming is that we can save memory.
   */
  public final boolean bufferRequestBody;

  /**
   * The original application-provided request. Never modified by OkHttp. When
   * follow-up requests are necessary, they are derived from this request.
   */
  private final Request userRequest;

  /**
   * The request to send on the network, or null for no network request. This is
   * derived from the user request, and customized to support OkHttp features
   * like compression and caching.
   */
  private Request networkRequest;

  /**
   * The cached response, or null if the cache doesn't exist or cannot be used
   * for this request. Conditional caching means this may be non-null even when
   * the network request is non-null. Never modified by OkHttp.
   */
  private Response cacheResponse;

  /**
   * The response read from the network. Null if the network response hasn't
   * been read yet, or if the network is not used. Never modified by OkHttp.
   */
  private Response networkResponse;

  /**
   * The user-visible response. This is derived from either the network
   * response, cache response, or both. It is customized to support OkHttp
   * features like compression and caching.
   */
  private Response userResponse;

  private Sink requestBodyOut;
  private BufferedSink bufferedRequestBody;

  /** Null until a response is received from the network or the cache. */
  private Source responseTransferSource;
  private BufferedSource responseBody;
  private InputStream responseBodyBytes;

  /** The cache request currently being populated from a network response. */
  private CacheRequest storeRequest;
  private CacheStrategy cacheStrategy;

  /**
   * @param request the HTTP request without a body. The body must be
   *     written via the engine's request body stream.
   * @param connection the connection used for an intermediate response
   *     immediately prior to this request/response pair, such as a same-host
   *     redirect. This engine assumes ownership of the connection and must
   *     release it when it is unneeded.
   * @param routeSelector the route selector used for a failed attempt
   *     immediately preceding this attempt, or null if this request doesn't
   *     recover from a failure.
   */
  public HttpEngine(OkHttpClient client, Request request, boolean bufferRequestBody,
      Connection connection, RouteSelector routeSelector, RetryableSink requestBodyOut,
      Response priorResponse) {
    this.client = client;
    this.userRequest = request;
    this.bufferRequestBody = bufferRequestBody;
    this.connection = connection;
    this.routeSelector = routeSelector;
    this.requestBodyOut = requestBodyOut;
    this.priorResponse = priorResponse;

    if (connection != null) {
      Internal.instance.setOwner(connection, this);
      this.route = connection.getRoute();
    } else {
      this.route = null;
    }
  }

  /**
   * Figures out what the response source will be, and opens a socket to that
   * source if necessary. Prepares the request headers and gets ready to start
   * writing the request body if it exists.
   */
  public void sendRequest() throws IOException {
    if (cacheStrategy != null) return; // Already sent.
    if (transport != null) throw new IllegalStateException();

    Request request = networkRequest(userRequest);

    InternalCache responseCache = Internal.instance.internalCache(client);
    Response cacheCandidate = responseCache != null
        ? responseCache.get(request)
        : null;

    long now = System.currentTimeMillis();
    cacheStrategy = new CacheStrategy.Factory(now, request, cacheCandidate).get();
    networkRequest = cacheStrategy.networkRequest;
    cacheResponse = cacheStrategy.cacheResponse;

    if (responseCache != null) {
      responseCache.trackResponse(cacheStrategy);
    }

    if (cacheCandidate != null && cacheResponse == null) {
      Util.closeQuietly(cacheCandidate.body()); // The cache candidate wasn't applicable. Close it.
    }

    if (networkRequest != null) {
      // Open a connection unless we inherited one from a redirect.
      if (connection == null) {
        connect(networkRequest);
      }

      transport = Internal.instance.newTransport(connection, this);

      // Create a request body if we don't have one already. We'll already have
      // one if we're retrying a failed POST.
      if (hasRequestBody() && requestBodyOut == null) {
        requestBodyOut = transport.createRequestBody(request);
      }

    } else {
      // We aren't using the network. Recycle a connection we may have inherited from a redirect.
      if (connection != null) {
        Internal.instance.recycle(client.getConnectionPool(), connection);
        connection = null;
      }

      if (cacheResponse != null) {
        // We have a valid cached response. Promote it to the user response immediately.
        this.userResponse = cacheResponse.newBuilder()
            .request(userRequest)
            .priorResponse(stripBody(priorResponse))
            .cacheResponse(stripBody(cacheResponse))
            .build();
      } else {
        // We're forbidden from using the network, and the cache is insufficient.
        this.userResponse = new Response.Builder()
            .request(userRequest)
            .priorResponse(stripBody(priorResponse))
            .protocol(Protocol.HTTP_1_1)
            .code(504)
            .message("Unsatisfiable Request (only-if-cached)")
            .body(EMPTY_BODY)
            .build();
      }

      if (userResponse.body() != null) {
        initContentStream(userResponse.body().source());
      }
    }
  }

  private static Response stripBody(Response response) {
    return response != null && response.body() != null
        ? response.newBuilder().body(null).build()
        : response;
  }

  /** Connect to the origin server either directly or via a proxy. */
  private void connect(Request request) throws IOException {
    if (connection != null) throw new IllegalStateException();

    if (routeSelector == null) {
      routeSelector = RouteSelector.get(request, client);
    }

    connection = routeSelector.next(this);
    route = connection.getRoute();
  }

  /**
   * Called immediately before the transport transmits HTTP request headers.
   * This is used to observe the sent time should the request be cached.
   */
  public void writingRequestHeaders() {
    if (sentRequestMillis != -1) throw new IllegalStateException();
    sentRequestMillis = System.currentTimeMillis();
  }

  boolean hasRequestBody() {
    return HttpMethod.hasRequestBody(userRequest.method())
        && !Util.emptySink().equals(requestBodyOut);
  }

  /** Returns the request body or null if this request doesn't have a body. */
  public Sink getRequestBody() {
    if (cacheStrategy == null) throw new IllegalStateException();
    return requestBodyOut;
  }

  public BufferedSink getBufferedRequestBody() {
    BufferedSink result = bufferedRequestBody;
    if (result != null) return result;
    Sink requestBody = getRequestBody();
    return requestBody != null
        ? (bufferedRequestBody = Okio.buffer(requestBody))
        : null;
  }

  public boolean hasResponse() {
    return userResponse != null;
  }

  public Request getRequest() {
    return userRequest;
  }

  public Response getResponse() {
    if (userResponse == null) throw new IllegalStateException();
    return userResponse;
  }

  public BufferedSource getResponseBody() {
    if (userResponse == null) throw new IllegalStateException();
    return responseBody;
  }

  public InputStream getResponseBodyBytes() {
    InputStream result = responseBodyBytes;
    return result != null
        ? result
        : (responseBodyBytes = Okio.buffer(getResponseBody()).inputStream());
  }

  public Connection getConnection() {
    return connection;
  }

  /**
   * Report and attempt to recover from {@code e}. Returns a new HTTP engine
   * that should be used for the retry if {@code e} is recoverable, or null if
   * the failure is permanent. Requests with a body can only be recovered if the
   * body is buffered.
   */
  public HttpEngine recover(IOException e, Sink requestBodyOut) {
    if (routeSelector != null && connection != null) {
      routeSelector.connectFailed(connection, e);
    }

    boolean canRetryRequestBody = requestBodyOut == null || requestBodyOut instanceof RetryableSink;
    if (routeSelector == null && connection == null // No connection.
        || routeSelector != null && !routeSelector.hasNext() // No more routes to attempt.
        || !isRecoverable(e)
        || !canRetryRequestBody) {
      return null;
    }

    Connection connection = close();

    // For failure recovery, use the same route selector with a new connection.
    return new HttpEngine(client, userRequest, bufferRequestBody, connection, routeSelector,
        (RetryableSink) requestBodyOut, priorResponse);
  }

  public HttpEngine recover(IOException e) {
    return recover(e, requestBodyOut);
  }

  private boolean isRecoverable(IOException e) {
    // If the problem was a CertificateException from the X509TrustManager,
    // do not retry, we didn't have an abrupt server-initiated exception.
    boolean sslFailure =
        e instanceof SSLHandshakeException && e.getCause() instanceof CertificateException;
    boolean protocolFailure = e instanceof ProtocolException;
    return !sslFailure && !protocolFailure;
  }

  /**
   * Returns the route used to retrieve the response. Null if we haven't
   * connected yet, or if no connection was necessary.
   */
  public Route getRoute() {
    return route;
  }

  private void maybeCache() throws IOException {
    InternalCache responseCache = Internal.instance.internalCache(client);
    if (responseCache == null) return;

    // Should we cache this response for this request?
    if (!CacheStrategy.isCacheable(userResponse, networkRequest)) {
      if (HttpMethod.invalidatesCache(networkRequest.method())) {
        try {
          responseCache.remove(networkRequest);
        } catch (IOException ignored) {
          // The cache cannot be written.
        }
      }
      return;
    }

    // Offer this request to the cache.
    storeRequest = responseCache.put(stripBody(userResponse));
  }

  /**
   * Configure the socket connection to be either pooled or closed when it is
   * either exhausted or closed. If it is unneeded when this is called, it will
   * be released immediately.
   */
  public void releaseConnection() throws IOException {
    if (transport != null && connection != null) {
      transport.releaseConnectionOnIdle();
    }
    connection = null;
  }

  /**
   * Immediately closes the socket connection if it's currently held by this
   * engine. Use this to interrupt an in-flight request from any thread. It's
   * the caller's responsibility to close the request body and response body
   * streams; otherwise resources may be leaked.
   */
  public void disconnect() {
    if (transport != null) {
      try {
        transport.disconnect(this);
      } catch (IOException ignored) {
      }
    }
  }

  /**
   * Release any resources held by this engine. If a connection is still held by
   * this engine, it is returned.
   */
  public Connection close() {
    if (bufferedRequestBody != null) {
      // This also closes the wrapped requestBodyOut.
      closeQuietly(bufferedRequestBody);
    } else if (requestBodyOut != null) {
      Util.closeQuietly(requestBodyOut);
    }

    // If this engine never achieved a response body, its connection cannot be reused.
    if (responseBody == null) {
      if (connection != null) closeQuietly(connection.getSocket());
      connection = null;
      return null;
    }

    // Close the response body. This will recycle the connection if it is eligible.
    closeQuietly(responseBody);

    // Clear the buffer held by the response body input stream adapter.
    Util.closeQuietly(responseBodyBytes);

    // Close the connection if it cannot be reused.
    if (transport != null && connection != null && !transport.canReuseConnection()) {
      closeQuietly(connection.getSocket());
      connection = null;
      return null;
    }

    // Prevent this engine from disconnecting a connection it no longer owns.
    if (connection != null && !Internal.instance.clearOwner(connection)) {
      connection = null;
    }

    Connection result = connection;
    connection = null;
    return result;
  }

  /**
   * Initialize the response content stream from the response transfer source.
   * These two sources are the same unless we're doing transparent gzip, in
   * which case the content source is decompressed.
   *
   * <p>Whenever we do transparent gzip we also strip the corresponding headers.
   * We strip the Content-Encoding header to prevent the application from
   * attempting to double decompress. We strip the Content-Length header because
   * it is the length of the compressed content, but the application is only
   * interested in the length of the uncompressed content.
   *
   * <p>This method should only be used for non-empty response bodies. Response
   * codes like "304 Not Modified" can include "Content-Encoding: gzip" without
   * a response body and we will crash if we attempt to decompress the zero-byte
   * source.
   */
  private void initContentStream(Source transferSource) throws IOException {
    responseTransferSource = transferSource;
    if (transparentGzip && "gzip".equalsIgnoreCase(userResponse.header("Content-Encoding"))) {
      userResponse = userResponse.newBuilder()
          .removeHeader("Content-Encoding")
          .removeHeader("Content-Length")
          .build();
      responseBody = Okio.buffer(new GzipSource(transferSource));
    } else {
      responseBody = Okio.buffer(transferSource);
    }
  }

  /**
   * Returns true if the response must have a (possibly 0-length) body.
   * See RFC 2616 section 4.3.
   */
  public boolean hasResponseBody() {
    // HEAD requests never yield a body regardless of the response headers.
    if (userRequest.method().equals("HEAD")) {
      return false;
    }

    int responseCode = userResponse.code();
    if ((responseCode < HTTP_CONTINUE || responseCode >= 200)
        && responseCode != HTTP_NO_CONTENT
        && responseCode != HTTP_NOT_MODIFIED) {
      return true;
    }

    // If the Content-Length or Transfer-Encoding headers disagree with the
    // response code, the response is malformed. For best compatibility, we
    // honor the headers.
    if (OkHeaders.contentLength(networkResponse) != -1
        || "chunked".equalsIgnoreCase(networkResponse.header("Transfer-Encoding"))) {
      return true;
    }

    return false;
  }

  /**
   * Populates request with defaults and cookies.
   *
   * <p>This client doesn't specify a default {@code Accept} header because it
   * doesn't know what content publishType the application is interested in.
   */
  private Request networkRequest(Request request) throws IOException {
    Request.Builder result = request.newBuilder();

    if (request.header("Host") == null) {
      result.header("Host", hostHeader(request.url()));
    }

    if ((connection == null || connection.getProtocol() != Protocol.HTTP_1_0)
        && request.header("Connection") == null) {
      result.header("Connection", "Keep-Alive");
    }

    if (request.header("Accept-Encoding") == null) {
      transparentGzip = true;
      result.header("Accept-Encoding", "gzip");
    }

    CookieHandler cookieHandler = client.getCookieHandler();
    if (cookieHandler != null) {
      // Capture the request headers added so far so that they can be offered to the CookieHandler.
      // This is mostly to stay close to the RI; it is unlikely any of the headers above would
      // affect cookie choice besides "Host".
      Map<String, List<String>> headers = OkHeaders.toMultimap(result.build().headers(), null);

      Map<String, List<String>> cookies = cookieHandler.get(request.uri(), headers);

      // Add any new cookies to the request.
      OkHeaders.addCookies(result, cookies);
    }

    return result.build();
  }

  public static String hostHeader(URL url) {
    return Util.getEffectivePort(url) != Util.getDefaultPort(url.getProtocol())
        ? url.getHost() + ":" + url.getPort()
        : url.getHost();
  }

  /**
   * Flushes the remaining request header and body, parses the HTTP response
   * headers and starts reading the HTTP response body if it exists.
   */
  public void readResponse() throws IOException {
    if (userResponse != null) {
      return; // Already ready.
    }
    if (networkRequest == null && cacheResponse == null) {
      throw new IllegalStateException("call sendRequest() first!");
    }
    if (networkRequest == null) {
      return; // No network response to read.
    }

    // Flush the request body if there's data outstanding.
    if (bufferedRequestBody != null && bufferedRequestBody.buffer().size() > 0) {
      bufferedRequestBody.flush();
    }

    if (sentRequestMillis == -1) {
      if (OkHeaders.contentLength(networkRequest) == -1
          && requestBodyOut instanceof RetryableSink) {
        // We might not learn the Content-Length until the request body has been buffered.
        long contentLength = ((RetryableSink) requestBodyOut).contentLength();
        networkRequest = networkRequest.newBuilder()
            .header("Content-Length", Long.toString(contentLength))
            .build();
      }
      transport.writeRequestHeaders(networkRequest);
    }

    if (requestBodyOut != null) {
      if (bufferedRequestBody != null) {
        // This also closes the wrapped requestBodyOut.
        bufferedRequestBody.close();
      } else {
        requestBodyOut.close();
      }
      if (requestBodyOut instanceof RetryableSink && !Util.emptySink().equals(requestBodyOut)) {
        transport.writeRequestBody((RetryableSink) requestBodyOut);
      }
    }

    transport.flushRequest();

    networkResponse = transport.readResponseHeaders()
        .request(networkRequest)
        .handshake(connection.getHandshake())
        .header(OkHeaders.SENT_MILLIS, Long.toString(sentRequestMillis))
        .header(OkHeaders.RECEIVED_MILLIS, Long.toString(System.currentTimeMillis()))
        .build();
    Internal.instance.setProtocol(connection, networkResponse.protocol());
    receiveHeaders(networkResponse.headers());

    // If we have a cache response too, then we're doing a conditional get.
    if (cacheResponse != null) {
      if (validate(cacheResponse, networkResponse)) {
        userResponse = cacheResponse.newBuilder()
            .request(userRequest)
            .priorResponse(stripBody(priorResponse))
            .headers(combine(cacheResponse.headers(), networkResponse.headers()))
            .cacheResponse(stripBody(cacheResponse))
            .networkResponse(stripBody(networkResponse))
            .build();
        transport.emptyTransferStream();
        releaseConnection();

        // Update the cache after combining headers but before stripping the
        // Content-Encoding header (as performed by initContentStream()).
        InternalCache responseCache = Internal.instance.internalCache(client);
        responseCache.trackConditionalCacheHit();
        responseCache.update(cacheResponse, stripBody(userResponse));

        if (cacheResponse.body() != null) {
          initContentStream(cacheResponse.body().source());
        }
        return;
      } else {
        Util.closeQuietly(cacheResponse.body());
      }
    }

    userResponse = networkResponse.newBuilder()
        .request(userRequest)
        .priorResponse(stripBody(priorResponse))
        .cacheResponse(stripBody(cacheResponse))
        .networkResponse(stripBody(networkResponse))
        .build();

    if (!hasResponseBody()) {
      // Don't call initContentStream() when the response doesn't have any content.
      responseTransferSource = transport.getTransferStream(storeRequest);
      responseBody = Okio.buffer(responseTransferSource);
      return;
    }

    maybeCache();
    initContentStream(transport.getTransferStream(storeRequest));
  }

  /**
   * Returns true if {@code cached} should be used; false if {@code network}
   * response should be used.
   */
  private static boolean validate(Response cached, Response network) {
    if (network.code() == HTTP_NOT_MODIFIED) {
      return true;
    }

    // The HTTP spec says that if the network's response is older than our
    // cached response, we may return the cache's response. Like Chrome (but
    // unlike Firefox), this client prefers to return the newer response.
    Date lastModified = cached.headers().getDate("Last-Modified");
    if (lastModified != null) {
      Date networkLastModified = network.headers().getDate("Last-Modified");
      if (networkLastModified != null
          && networkLastModified.getTime() < lastModified.getTime()) {
        return true;
      }
    }

    return false;
  }

  /**
   * Combines cached headers with a network headers as defined by RFC 2616,
   * 13.5.3.
   */
  private static Headers combine(Headers cachedHeaders, Headers networkHeaders) throws IOException {
    Headers.Builder result = new Headers.Builder();

    for (int i = 0; i < cachedHeaders.size(); i++) {
      String fieldName = cachedHeaders.name(i);
      String value = cachedHeaders.value(i);
      if ("Warning".equals(fieldName) && value.startsWith("1")) {
        continue; // drop 100-level freshness warnings
      }
      if (!OkHeaders.isEndToEnd(fieldName) || networkHeaders.get(fieldName) == null) {
        result.add(fieldName, value);
      }
    }

    for (int i = 0; i < networkHeaders.size(); i++) {
      String fieldName = networkHeaders.name(i);
      if (OkHeaders.isEndToEnd(fieldName)) {
        result.add(fieldName, networkHeaders.value(i));
      }
    }

    return result.build();
  }

  public void receiveHeaders(Headers headers) throws IOException {
    CookieHandler cookieHandler = client.getCookieHandler();
    if (cookieHandler != null) {
      cookieHandler.put(userRequest.uri(), OkHeaders.toMultimap(headers, null));
    }
  }

  /**
   * Figures out the HTTP request to make in response to receiving this engine's
   * response. This will either add authentication headers or follow redirects.
   * If a follow-up is either unnecessary or not applicable, this returns null.
   */
  public Request followUpRequest() throws IOException {
    if (userResponse == null) throw new IllegalStateException();
    Proxy selectedProxy = getRoute() != null
        ? getRoute().getProxy()
        : client.getProxy();
    int responseCode = userResponse.code();

    switch (responseCode) {
      case HTTP_PROXY_AUTH:
        if (selectedProxy.type() != Proxy.Type.HTTP) {
          throw new ProtocolException("Received HTTP_PROXY_AUTH (407) code while not using proxy");
        }
        // fall-through
      case HTTP_UNAUTHORIZED:
        return OkHeaders.processAuthHeader(client.getAuthenticator(), userResponse, selectedProxy);

      case HTTP_TEMP_REDIRECT:
        // "If the 307 status code is received in response to a request other than GET or HEAD,
        // the user agent MUST NOT automatically redirect the request"
        if (!userRequest.method().equals("GET") && !userRequest.method().equals("HEAD")) {
          return null;
        }
        // fall-through
      case HTTP_MULT_CHOICE:
      case HTTP_MOVED_PERM:
      case HTTP_MOVED_TEMP:
      case HTTP_SEE_OTHER:
        // Does the client allow redirects?
        if (!client.getFollowRedirects()) return null;

        String location = userResponse.header("Location");
        if (location == null) return null;
        URL url = new URL(userRequest.url(), location);

        // Don't follow redirects to unsupported protocols.
        if (!url.getProtocol().equals("https") && !url.getProtocol().equals("http")) return null;

        // If configured, don't follow redirects between SSL and non-SSL.
        boolean sameProtocol = url.getProtocol().equals(userRequest.url().getProtocol());
        if (!sameProtocol && !client.getFollowSslRedirects()) return null;

        // Redirects don't include a request body.
        Request.Builder requestBuilder = userRequest.newBuilder();
        if (HttpMethod.hasRequestBody(userRequest.method())) {
          requestBuilder.method("GET", null);
          requestBuilder.removeHeader("Transfer-Encoding");
          requestBuilder.removeHeader("Content-Length");
          requestBuilder.removeHeader("Content-Type");
        }

        // When redirecting across hosts, drop all authentication headers. This
        // is potentially annoying to the application layer since they have no
        // way to retain them.
        if (!sameConnection(url)) {
          requestBuilder.removeHeader("Authorization");
        }

        return requestBuilder.url(url).build();

      default:
        return null;
    }
  }

  /**
   * Returns true if an HTTP request for {@code followUp} can reuse the
   * connection used by this engine.
   */
  public boolean sameConnection(URL followUp) {
    URL url = userRequest.url();
    return url.getHost().equals(followUp.getHost())
        && Util.getEffectivePort(url) == Util.getEffectivePort(followUp)
        && url.getProtocol().equals(followUp.getProtocol());
  }
}

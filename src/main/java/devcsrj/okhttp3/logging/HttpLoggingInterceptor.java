/*
 * Copyright © 2017 Reijhanniel Jearl Campos (devcsrj@apache.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package devcsrj.okhttp3.logging;

import java.nio.charset.StandardCharsets;
import okhttp3.Connection;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static okhttp3.internal.http.StatusLine.HTTP_CONTINUE;

/**
 * An OkHttp interceptor that logs request and response information as a single consolidated log entry.
 * Can be applied as an {@linkplain OkHttpClient#interceptors() application interceptor} or as a
 * {@linkplain OkHttpClient#networkInterceptors() network interceptor}.
 * <p>
 * The interceptor uses StringBuilder to accumulate all log information and outputs it as a single
 * log entry, making it easier to correlate request and response data.
 * <p>
 * Log levels:
 * <ul>
 *   <li>{@code INFO}: Logs request and response information including headers. Response body is
 *       logged for failed requests (non-2xx status codes) regardless of log level.</li>
 *   <li>{@code DEBUG}: When enabled, also logs request and response bodies for all requests.</li>
 *   <li>{@code ERROR}: Used for logging HTTP failures when exceptions occur.</li>
 * </ul>
 * <p>
 * Example output:
 * <pre>{@code
 * --> REQUEST
 * Request Method = POST
 * Request URL = https://example.com/greeting
 * Protocol = http/1.1
 * Request Headers:
 *   Host: example.com
 *   Content-Type: plain/text
 * Content-Type = plain/text
 * Content-Length = 3
 * Request Body = Hi?
 * <-- RESPONSE
 * Response Code = 200
 * Response Message = OK
 * Time = 22 ms
 * Response Headers:
 *   Content-Type: plain/text
 *   Content-Length: 6
 * Response Body = Hello!
 * }</pre>
 */
public final class HttpLoggingInterceptor implements Interceptor {

    public static final String DEFAULT_LOGGER_NAME = "okhttp3.logging.wire";

    private static final Charset UTF8 = StandardCharsets.UTF_8;
    private static final Logger DEFAULT_LOGGER = LoggerFactory.getLogger(DEFAULT_LOGGER_NAME);

    private final Logger logger;
    private final long peekBodySize;

    public HttpLoggingInterceptor() {
        this(DEFAULT_LOGGER, Long.MAX_VALUE);
    }

    public HttpLoggingInterceptor(Logger logger) {
        this(logger, Long.MAX_VALUE);
    }

    public HttpLoggingInterceptor(long peekBodySize) {
        this(DEFAULT_LOGGER, peekBodySize);
    }

    public HttpLoggingInterceptor(Logger logger, long peekBodySize) {
        if (logger == null)
            throw new IllegalArgumentException("Can't use null logger");
        if (peekBodySize < 0)
            throw new IllegalArgumentException("peekBodySize can't be negative");
        this.logger = logger;
        this.peekBodySize = peekBodySize;
    }

    /**
     * Returns true if the body in question probably contains human readable text. Uses a small
     * sample of code points to detect unicode control characters commonly used in binary file
     * signatures.
     */
    static boolean isPlaintext(Buffer buffer) {
        try {
            Buffer prefix = new Buffer();
            long byteCount = buffer.size() < 64 ? buffer.size() : 64;
            buffer.copyTo(prefix, 0, byteCount);
            for (int i = 0; i < 16; i++) {
                if (prefix.exhausted())
                    break;
                int codePoint = prefix.readUtf8CodePoint();
                if (Character.isISOControl(codePoint) && !Character.isWhitespace(codePoint))
                    return false;
            }
            return true;
        } catch (EOFException e) {
            return false; // Truncated UTF-8 sequence.
        }
    }

    /**
     * Returns true if the response must have a (possibly 0-length) body. See RFC 7231.
     */
    static boolean hasBody(Response response) {
        // HEAD requests never yield a body regardless of the response headers.
        if (response.request().method().equals("HEAD"))
            return false;

        int responseCode = response.code();
        if ((responseCode < HTTP_CONTINUE || responseCode >= 200)
                && responseCode != HTTP_NO_CONTENT
                && responseCode != HTTP_NOT_MODIFIED)
            return true;

        // If the Content-Length or Transfer-Encoding headers disagree with the response code, the
        // response is malformed. For best compatibility, we honor the headers.
        if (contentLength(response) != -1
                || "chunked".equalsIgnoreCase(response.header("Transfer-Encoding")))
            return true;

        return false;
    }

    static long contentLength(Response response) {
        return contentLength(response.headers());
    }

    static long contentLength(Headers headers) {
        return stringToLong(headers.get("Content-Length"));
    }

    private static long stringToLong(String s) {
        if (s == null)
            return -1;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        StringBuilder logBuilder = new StringBuilder();
        
        boolean logBody = logger.isDebugEnabled();

        Request request = chain.request();
        RequestBody requestBody = request.body();
        boolean hasRequestBody = requestBody != null;

        Connection connection = chain.connection();
        Protocol protocol = connection != null ? connection.protocol() : Protocol.HTTP_1_1;

        // Build request log
        logBuilder.append("\n--> REQUEST\n");
        logBuilder.append("Request Method = ").append(request.method()).append("\n");
        logBuilder.append("Request URL = ").append(request.url()).append("\n");
        logBuilder.append("Protocol = ").append(protocol).append("\n");

        // Log request headers
        Headers headers = request.headers();
        if (headers.size() > 0) {
            logBuilder.append("Request Headers:\n");
            for (int i = 0; i < headers.size(); i++) {
                logBuilder.append("  ").append(headers.name(i)).append(": ").append(headers.value(i)).append("\n");
            }
        }

        // Log request body info and content
        if (hasRequestBody) {
            // Request body headers are only present when installed as a network interceptor.
            // Force them to be included (when available) so there values are known.

            if (logBody && !bodyEncoded(request.headers())) {
                Buffer buffer = new Buffer();
                requestBody.writeTo(buffer);

                Charset charset = UTF8;
                MediaType contentType = requestBody.contentType();
                if (contentType != null && contentType.charset() != null) {
                    charset = contentType.charset();
                }

                if (isPlaintext(buffer)) {
                    String bodyContent = buffer.readString(charset);
                    logBuilder.append("Request Body = ").append(bodyContent).append("\n");
                } else {
                    logBuilder.append("Request Body = (binary ").append(requestBody.contentLength()).append("-byte body omitted)\n");
                }
            } else if (bodyEncoded(request.headers())) {
                logBuilder.append("Request Body = (encoded body omitted)\n");
            }
        }

        long startNs = System.nanoTime();
        Response response;
        try {
            response = chain.proceed(request);
        } catch (Exception e) {
            // Log request failure
            logBuilder.append("<-- HTTP FAILED: ").append(e.getMessage()).append("\n");
            
            // Log everything collected so far
            logger.error(logBuilder.toString());
            throw e;
        }

        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
        ResponseBody responseBody = response.body();

        // Build response log
        logBuilder.append("<-- RESPONSE\n");
        logBuilder.append("Response Code = ").append(response.code()).append("\n");
        logBuilder.append("Response Message = ").append(response.message()).append("\n");
        logBuilder.append("Time = ").append(tookMs).append(" ms\n");

        // Log response headers
        Headers responseHeaders = response.headers();
        if (responseHeaders.size() > 0) {
            logBuilder.append("Response Headers:\n");
            for (int i = 0; i < responseHeaders.size(); i++) {
                logBuilder.append("  ").append(responseHeaders.name(i)).append(": ").append(responseHeaders.value(i)).append("\n");
            }
        }

        // Log response body for failed requests regardless of log level
        boolean shouldLogResponseBody = logBody || !response.isSuccessful();
        
        if (responseBody != null && shouldLogResponseBody && hasBody(response) && !bodyEncoded(responseHeaders)) {
            BufferedSource source = responseBody.source();
            source.request(peekBodySize);
            Buffer buffer = source.buffer();

            Charset charset = UTF8;
            MediaType contentType = responseBody.contentType();
            if (contentType != null) {
                Charset responseCharset = contentType.charset();
                if (responseCharset != null) {
                    charset = responseCharset;
                }
            }

            long contentLength = responseBody.contentLength();
            if (!isPlaintext(buffer)) {
                logBuilder.append("Response Body = (binary ").append(contentLength).append("-byte body omitted)\n");
            } else if (contentLength != 0) {
                String responseBodyContent = buffer.clone().readString(charset);
                logBuilder.append("Response Body = ").append(responseBodyContent).append("\n");
            }
        } else if (bodyEncoded(responseHeaders)) {
            logBuilder.append("Response Body = (encoded body omitted)\n");
        }

        logger.info(logBuilder.toString());

        return response;
    }

    private boolean bodyEncoded(Headers headers) {
        String contentEncoding = headers.get("Content-Encoding");
        return contentEncoding != null && !contentEncoding.equalsIgnoreCase("identity");
    }

}

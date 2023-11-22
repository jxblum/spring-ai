/*
 * Copyright 2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ai.azure.openai;

import static org.springframework.ai.test.config.MockAiTestConfiguration.SPRING_AI_API_PATH;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpHeader;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;

import org.springframework.ai.azure.openai.client.AzureOpenAiClient;
import org.springframework.ai.test.config.MockAiTestConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockWebServer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@link SpringBootConfiguration} for testing {@literal Azure OpenAI's} API using mock
 * objects.
 * <p>
 * This test configuration allows Spring AI framework developers to mock Azure OpenAI's
 * API with Spring {@link MockMvc} and a test provided Spring Web MVC
 * {@link org.springframework.web.bind.annotation.RestController}.
 * <p>
 * This test configuration makes use of the OkHttp3 {@link MockWebServer} and
 * {@link Dispatcher} to integrate with Spring {@link MockMvc}.
 *
 * @author John Blum
 * @see org.springframework.boot.SpringBootConfiguration
 * @see org.springframework.ai.test.config.MockAiTestConfiguration
 * @since 0.7.0
 */
@SpringBootConfiguration
@Profile("spring-ai-azure-openai-mocks")
@Import(MockAiTestConfiguration.class)
@SuppressWarnings("unused")
public class MockAzureOpenAiTestConfiguration {

	@Bean
	OkHttpClient httpClient() {
		return new OkHttpClient();
	}

	@Bean
	OpenAIClient microsoftAzureOpenAiClient(OkHttpClient httpClient, MockWebServer webServer) {

		String apiKey = UUID.randomUUID().toString();

		HttpUrl baseUrl = webServer.url(SPRING_AI_API_PATH);

		return new OpenAIClientBuilder()
			// .httpClient(new OkHttpClientToAzureHttpClientAdapter(httpClient))
			// .credential(new AzureKeyCredential(apiKey))
			.endpoint(baseUrl.toString())
			.buildClient();
	}

	@Bean
	AzureOpenAiClient azureOpenAiClient(OpenAIClient microsoftAzureOpenAiClient) {
		return new AzureOpenAiClient(microsoftAzureOpenAiClient);
	}

	static class OkHttpClientToAzureHttpClientAdapter implements HttpClient {

		private final OkHttpClient httpClient;

		OkHttpClientToAzureHttpClientAdapter(OkHttpClient httpClient) {
			Assert.notNull(httpClient, "OkHttp HttpClient must not be null");
			this.httpClient = httpClient;
		}

		@Override
		public Mono<HttpResponse> send(HttpRequest httpRequest) {

			return Mono.fromSupplier(() -> {

				Request request = toOkHttpRequest(httpRequest);

				Call call = this.httpClient.newCall(request);

				return runOperationSafely(() -> {
					Response response = call.execute();
					return new OkHttpResponseToAzureHttpResponse(httpRequest, response);
				});
			});
		}

		@SuppressWarnings("all")
		private Request toOkHttpRequest(HttpRequest httpRequest) {

			HttpHeaders requestHeaders = httpRequest.getHeaders();

			MediaType mediaType = MediaType.get(requestHeaders.getValue(HttpHeaderName.CONTENT_TYPE));

			byte[] bodyContent = httpRequest.getBodyAsBinaryData().toBytes();

			RequestBody body = RequestBody.create(bodyContent, mediaType);

			Request.Builder requestBuilder = new Request.Builder().url(httpRequest.getUrl()).post(body);

			for (HttpHeader httpHeader : requestHeaders) {
				String httpHeaderName = httpHeader.getName();
				String httpHeaderValue = StringUtils.arrayToDelimitedString(httpHeader.getValues(), ";");
				requestBuilder.addHeader(httpHeaderName, httpHeaderValue);
			}

			Request request = requestBuilder.build();

			return request;
		}

	}

	static class OkHttpResponseToAzureHttpResponse extends HttpResponse {

		private final AtomicReference<HttpHeaders> responseHeadersCache = new AtomicReference<>(null);

		private final Response response;

		OkHttpResponseToAzureHttpResponse(HttpRequest httpRequest, Response response) {
			super(httpRequest);
			Assert.notNull(response, "HTTP Response must not be null");
			this.response = response;
		}

		@Override
		public int getStatusCode() {
			return this.response.code();
		}

		@Override
		public String getHeaderValue(String headerName) {
			return this.response.header(headerName);
		}

		@Override
		public HttpHeaders getHeaders() {

			Supplier<HttpHeaders> lazyGetHttpHeaders = () -> {

				List<HttpHeader> responseHeaders = this.response.headers()
					.toMultimap()
					.entrySet()
					.stream()
					.map(header -> new HttpHeader(header.getKey(), header.getValue()))
					.toList();

				return new HttpHeaders(responseHeaders);
			};

			return this.responseHeadersCache.getAndUpdate(it -> it != null ? it : lazyGetHttpHeaders.get());
		}

		@Override
		public Flux<ByteBuffer> getBody() {
			return runOperationSafely(() -> {
				ResponseBody responseBody = this.response.body();
				return responseBody != null ? Flux.just(ByteBuffer.wrap(responseBody.bytes())) : Flux.empty();
			});
		}

		@Override
		public Mono<byte[]> getBodyAsByteArray() {
			return runOperationSafely(() -> {
				ResponseBody responseBody = this.response.body();
				return responseBody != null ? Mono.just(responseBody.bytes()) : Mono.empty();
			});
		}

		@Override
		public Mono<String> getBodyAsString() {
			return getBodyAsByteArray().map(String::new);
		}

		@Override
		public Mono<String> getBodyAsString(Charset charset) {
			return getBodyAsByteArray().map(bytes -> new String(bytes, charset));
		}

	}

	private static <T> T runOperationSafely(ThrowableOperation<T> operation) {
		try {
			return operation.run();
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@FunctionalInterface
	interface ThrowableOperation<T> {

		T run() throws Exception;

	}

}

package com.example.feishuagent;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** A {@link WebClient} that records what it was asked to send and answers with a canned body. */
final class RecordingExchange implements ExchangeFunction {

    static final String OK_RESPONSE = "{\"code\":0,\"msg\":\"success\",\"data\":{\"message_id\":\"om_1\"}}";

    final List<ClientRequest> requests = new CopyOnWriteArrayList<>();
    private final String responseBody;

    RecordingExchange(String responseBody) {
        this.responseBody = responseBody;
    }

    @Override
    public Mono<ClientResponse> exchange(ClientRequest request) {
        requests.add(request);
        return Mono.just(
                ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body(responseBody)
                        .build());
    }

    WebClient client() {
        return WebClient.builder().baseUrl("https://open.feishu.cn").exchangeFunction(this).build();
    }

    ClientRequest onlyRequest() {
        if (requests.size() != 1) {
            throw new AssertionError("expected exactly one request, got " + requests.size());
        }
        return requests.get(0);
    }

    /** Renders a request's body the way the transport would, so tests can assert on the JSON. */
    static String bodyOf(ClientRequest request) {
        MockClientHttpRequest rendered =
                new MockClientHttpRequest(request.method(), request.url());
        request.body()
                .insert(
                        rendered,
                        new BodyInserter.Context() {
                            @Override
                            public List<HttpMessageWriter<?>> messageWriters() {
                                return ExchangeStrategies.withDefaults().messageWriters();
                            }

                            @Override
                            public Optional<ServerHttpRequest> serverRequest() {
                                return Optional.empty();
                            }

                            @Override
                            public Map<String, Object> hints() {
                                return Map.of();
                            }
                        })
                .block();
        return rendered.getBodyAsString().block();
    }
}

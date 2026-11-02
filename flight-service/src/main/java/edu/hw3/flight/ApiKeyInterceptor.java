package edu.hw3.flight;

import io.grpc.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ApiKeyInterceptor implements ServerInterceptor {
  public static final Metadata.Key<String> KEY =
      Metadata.Key.of("x-service-api-key", Metadata.ASCII_STRING_MARSHALLER);
  private final byte[] expected;

  public ApiKeyInterceptor(@Value("${grpc.api-key}") String key) {
    if (key.isBlank()) throw new IllegalArgumentException("API key required");
    expected = key.getBytes(StandardCharsets.UTF_8);
  }

  public <Q, S> ServerCall.Listener<Q> interceptCall(
      ServerCall<Q, S> call, Metadata headers, ServerCallHandler<Q, S> next) {
    String key = headers.get(KEY);
    if (key == null || !MessageDigest.isEqual(expected, key.getBytes(StandardCharsets.UTF_8))) {
      call.close(
          Status.UNAUTHENTICATED.withDescription("Service authentication failed"), new Metadata());
      return new ServerCall.Listener<>() {};
    }
    org.slf4j.LoggerFactory.getLogger(getClass())
        .info(
            "GRPC_REQUEST operation={} correlation={}",
            call.getMethodDescriptor().getBareMethodName(),
            java.util.UUID.randomUUID());
    return next.startCall(call, headers);
  }
}

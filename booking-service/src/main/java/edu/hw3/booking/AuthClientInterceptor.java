package edu.hw3.booking;

import io.grpc.*;

public class AuthClientInterceptor implements ClientInterceptor {
  private final String key;

  public AuthClientInterceptor(String key) {
    if (key.isBlank()) throw new IllegalArgumentException("API key required");
    this.key = key;
  }

  public <Q, S> ClientCall<Q, S> interceptCall(MethodDescriptor<Q, S> m, CallOptions o, Channel c) {
    return new ForwardingClientCall.SimpleForwardingClientCall<>(c.newCall(m, o)) {
      @Override
      public void start(Listener<S> l, Metadata h) {
        h.put(Metadata.Key.of("x-service-api-key", Metadata.ASCII_STRING_MARSHALLER), key);
        super.start(l, h);
      }
    };
  }
}

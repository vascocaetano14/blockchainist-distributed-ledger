package pt.tecnico.blockchainist.node.grpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

import io.grpc.Context;
import io.grpc.Contexts;

public class NodeDelayInterceptor implements ServerInterceptor {

    static final Metadata.Key<String> NODE_DELAY_KEY = Metadata.Key.of("node_delay", Metadata.ASCII_STRING_MARSHALLER);

    static final Metadata.Key<String> REQUEST_ID_KEY = Metadata.Key.of("request_id", Metadata.ASCII_STRING_MARSHALLER);

    static final Context.Key<String> REQUEST_ID_CTX_KEY = Context.key("request_id");

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call,
      final Metadata requestHeaders,
      ServerCallHandler<ReqT, RespT> next) {

        String headerValue = requestHeaders.get(NODE_DELAY_KEY);

        if (headerValue != null) {
          try {
                int delay = Integer.parseInt(headerValue);
                if (delay > 0) {
                    Thread.sleep(delay * 1000L); 
                    System.out.println("atraso de " + delay + " segundos");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted during delay", e);
            }
        }

        //propaga o request_id no gRPC pra os handlers seguintes
        String requestId = requestHeaders.get(REQUEST_ID_KEY);
        Context ctx = Context.current().withValue(REQUEST_ID_CTX_KEY, requestId);
        return Contexts.interceptCall(ctx, call, requestHeaders, next);
  } 

}
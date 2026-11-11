package pt.tecnico.blockchainist.client.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.concurrent.TimeUnit;

import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;

import pt.tecnico.blockchainist.contract.*;

public class ClientNodeService {

    //chave gRPC lida pelo interceptor para aplicar o sleep antes do handler
    private static final Metadata.Key<String> DELAY_KEY = Metadata.Key.of("node_delay", Metadata.ASCII_STRING_MARSHALLER);

    //request_id propagado ao nó para encontrarmos os duplicados
    private static final Metadata.Key<String> REQUEST_ID_KEY = Metadata.Key.of("request_id", Metadata.ASCII_STRING_MARSHALLER);

    private final ManagedChannel channel;
    private final NodeServiceGrpc.NodeServiceBlockingStub blockingStub;
    private final NodeServiceGrpc.NodeServiceStub asyncStub;
    private final String organization;

    public ClientNodeService(String host, int port, String organization) {
        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        this.organization = organization;
        this.blockingStub = NodeServiceGrpc.newBlockingStub(channel);
        this.asyncStub = NodeServiceGrpc.newStub(channel);
    }


    private Metadata buildMetadata(int nodeDelay, String requestId) {
        Metadata metadata = new Metadata();
        metadata.put(DELAY_KEY, String.valueOf(nodeDelay));
        if (requestId != null && !requestId.isBlank()) {
            metadata.put(REQUEST_ID_KEY, requestId);
        }
        return metadata;
    }

    public NodeServiceGrpc.NodeServiceBlockingStub blockingStubWithDelay(int nodeDelay) {
        if (nodeDelay <= 0) {
            return blockingStub;
        }
        Metadata metadata = buildMetadata(nodeDelay, null);
        return blockingStub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    private NodeServiceGrpc.NodeServiceStub asyncStubWithMetadata(int nodeDelay, String requestId) {
        Metadata metadata = buildMetadata(nodeDelay, requestId);
        return asyncStub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }


    private NodeServiceGrpc.NodeServiceBlockingStub blockingStubWithMetadataAndTimeout(
            int nodeDelay, String requestId, long timeoutMs) {
        Metadata metadata = buildMetadata(nodeDelay, requestId);
        return blockingStub
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS);
    }    

    public CreateWalletResponse createWallet(String userId, String walletId, int nodeDelay) {
        CreateWalletRequest request = CreateWalletRequest.newBuilder()
                .setUserId(userId)
                .setWalletId(walletId)
                .build();
        return blockingStubWithDelay(nodeDelay).createWallet(request);
    }

    public DeleteWalletResponse deleteWallet(String userId, String walletId, int nodeDelay) {
        DeleteWalletRequest request = DeleteWalletRequest.newBuilder()
                .setUserId(userId)
                .setWalletId(walletId)
                .build();
        return blockingStubWithDelay(nodeDelay).deleteWallet(request);
    }

    public ReadBalanceResponse readBalance(String walletId, int nodeDelay) {
        ReadBalanceRequest request = ReadBalanceRequest.newBuilder()
                .setWalletId(walletId)
                .build();
        return blockingStubWithDelay(nodeDelay).readBalance(request);
    }

    public TransferResponse transfer(String srcUserId, String srcWalletId, String dstWalletId, long value, int nodeDelay) {
        TransferRequest request = TransferRequest.newBuilder()
                .setSrcUserId(srcUserId)
                .setSrcWalletId(srcWalletId)
                .setDstWalletId(dstWalletId)
                .setValue(value)
                .build();
        return blockingStubWithDelay(nodeDelay).transfer(request);
    }

    public GetBlockchainStateResponse getBlockchainState() {
        GetBlockchainStateRequest request = GetBlockchainStateRequest.newBuilder().build();
        return blockingStubWithDelay(0).getBlockchainState(request);
    }

    //variantes WithTimeout usadas por C/E/T/S para failover em DEADLINE_EXCEEDED
    public CreateWalletResponse createWalletWithTimeout(
        String userId, String walletId, int nodeDelay, String requestId, long timeoutMs, Signature txSignature) {
        CreateWalletRequest request = CreateWalletRequest.newBuilder()
                .setUserId(userId)
                .setWalletId(walletId)
                .setSignature(txSignature)
                .build();
        return blockingStubWithMetadataAndTimeout(nodeDelay, requestId, timeoutMs).createWallet(request);
    }

    public DeleteWalletResponse deleteWalletWithTimeout(
            String userId, String walletId, int nodeDelay, String requestId, long timeoutMs, Signature txSignature) {
        DeleteWalletRequest request = DeleteWalletRequest.newBuilder()
                .setUserId(userId)
                .setWalletId(walletId)
                .setSignature(txSignature)
                .build();
        return blockingStubWithMetadataAndTimeout(nodeDelay, requestId, timeoutMs).deleteWallet(request);
    }

    public TransferResponse transferWithTimeout(
            String srcUserId, String srcWalletId, String dstWalletId, long value,
            int nodeDelay, String requestId, long timeoutMs, Signature txSignature) {
        TransferRequest request = TransferRequest.newBuilder()
                .setSrcUserId(srcUserId)
                .setSrcWalletId(srcWalletId)
                .setDstWalletId(dstWalletId)
                .setValue(value)
                .setSignature(txSignature)
                .build();
        return blockingStubWithMetadataAndTimeout(nodeDelay, requestId, timeoutMs).transfer(request);
    }

    public ReadBalanceResponse readBalanceWithTimeout(
            String walletId, int nodeDelay, long timeoutMs) {
        ReadBalanceRequest request = ReadBalanceRequest.newBuilder()
                .setWalletId(walletId)
                .build();
        return blockingStubWithMetadataAndTimeout(nodeDelay, null, timeoutMs).readBalance(request);
    }

    public GetBlockchainStateResponse getBlockchainStateWithTimeout(long timeoutMs) {
        GetBlockchainStateRequest request = GetBlockchainStateRequest.newBuilder().build();
        return blockingStub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS).getBlockchainState(request);
    }

    //variantes async: usadas por c/e/t/s e devolvem controlo imediatamente ao utilizador
    public void createWalletAsync(String userId, String requestId, String walletId, int nodeDelay, Signature txSignature, StreamObserver<CreateWalletResponse> observer) {
        CreateWalletRequest request = CreateWalletRequest.newBuilder()
                .setUserId(userId)
                .setWalletId(walletId)
                .setSignature(txSignature)
                .build();
        Metadata metadata = buildMetadata(nodeDelay, requestId);
        asyncStub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                 .createWallet(request, observer);
    }

    public void deleteWalletAsync(String userId,String requestId,  String walletId, int nodeDelay, Signature txSignature, StreamObserver<DeleteWalletResponse> observer) {
        DeleteWalletRequest request = DeleteWalletRequest.newBuilder()
                .setUserId(userId)
                .setWalletId(walletId)
                .setSignature(txSignature)
                .build();
        Metadata metadata = buildMetadata(nodeDelay, requestId);
        asyncStub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                 .deleteWallet(request, observer);
    }

    public void readBalanceAsync(String walletId, int nodeDelay, StreamObserver<ReadBalanceResponse> observer) {
        ReadBalanceRequest request = ReadBalanceRequest.newBuilder()
                .setWalletId(walletId)
                .build();
        asyncStubWithMetadata(nodeDelay, null).readBalance(request, observer);
    }

    public void transferAsync(String srcUserId,String requestId, String srcWalletId, String dstWalletId, long value, int nodeDelay, Signature txSignature, StreamObserver<TransferResponse> observer) {
        TransferRequest request = TransferRequest.newBuilder()
                .setSrcUserId(srcUserId)
                .setSrcWalletId(srcWalletId)
                .setDstWalletId(dstWalletId)
                .setValue(value)
                .setSignature(txSignature)
                .build();
        Metadata metadata = buildMetadata(nodeDelay, requestId);
        asyncStub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                 .transfer(request, observer);
    }

    public void shutdown() {
        if (channel != null && !channel.isShutdown()) {
            channel.shutdown();
            try {
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                }
            } catch (InterruptedException e) {
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
package pt.tecnico.blockchainist.sequencer.grpc;

import java.util.List;
import java.security.PrivateKey;

import io.grpc.stub.StreamObserver;
import pt.tecnico.blockchainist.contract.*;
import pt.tecnico.blockchainist.sequencer.domain.SequencerState;
import pt.tecnico.blockchainist.sequencer.domain.SequencerTransaction;
import com.google.protobuf.ByteString;

public class SequencerServiceImpl extends SequencerServiceGrpc.SequencerServiceImplBase {

    private final SequencerState state;
    private final boolean debug;
    private final PrivateKey privateKey;

    public SequencerServiceImpl(SequencerState state, boolean debug) {
        this.state = state;
        this.debug = debug;

        try {
            this.privateKey = CryptoUtils.readPrivateKey("/Seq.priv"); //temos que meter o path correto para o ficheiro de keys
            System.out.println("Sequencer's private key loaded successfully.");
        } catch (Exception e) {
            System.err.println("Error loading the sequencer's private key: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to load sequencer's private key", e);
        }
    }

    @Override
    public void broadcast(BroadcastRequest request, StreamObserver<BroadcastResponse> responseObserver) {
        
        try {
            String requestId = request.getRequestId();
            if (requestId == null || requestId.isBlank()) {
                responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT
                    .withDescription("Missing request_id in BroadcastRequest")
                    .asRuntimeException());
                return;
            }

            //identifica transferências para responder com early ack e relaxar ordem total para causal
            boolean isTransfer = request.getTransaction().hasTransfer();

            SequencerTransaction domainTx = SequencerTransaction.fromContract(request.getTransaction());

            if (debug) {
                System.err.println("Received broadcast requestId=" + requestId + " tx=" + domainTx);
            }

            SequencerState.BroadcastResult result = state.addTransaction(requestId, domainTx);

            if (debug) {
                System.err.println("Broadcast result requestId=" + requestId
                    + " block=" + result.getBlockNumber()
                    + " deduplicated=" + result.isDeduplicated());
            }

            responseObserver.onNext(BroadcastResponse.newBuilder().setBlockNumber(result.getBlockNumber()).setDeduplicated(result.isDeduplicated()).setEarlyAck(isTransfer).build());
            responseObserver.onCompleted();

        } catch (Exception e) {
            responseObserver.onError(io.grpc.Status.INTERNAL
                .withDescription(e.getMessage() == null ? "broadcast error" : e.getMessage())
                .asRuntimeException());
        }
    }


    //Devolver signed block
    @Override
    public void deliverBlock(DeliverBlockRequest request, StreamObserver<SignedDeliverBlockResponse> responseObserver) {
        int blockNumber = request.getBlockNumber();

        if (debug) {
             System.err.println("DeliverBlock requested for block: " + blockNumber);
        }

        List<SequencerTransaction> blockTransactions = state.getBlock(blockNumber);

        if (debug) {
            System.err.println("Delivering block: " + blockNumber + " -> " + blockTransactions.size() + " transactions");
        }

        Block.Builder blockBuilder = Block.newBuilder().setBlockNumber(blockNumber);
        for (SequencerTransaction tx : blockTransactions) {
            blockBuilder.addTransactions(tx.toContract());
        }

        DeliverBlockResponse deliverBlockResponse = DeliverBlockResponse.newBuilder()
            .setBlock(blockBuilder.build())
            .build();

        //Assinar DeliverBlockResponse com a chave privada do sequenciador
        Signature blockSignature;

        try {
            byte[] sigBytes = CryptoUtils.sign(deliverBlockResponse.toByteArray(), privateKey); //assina com chave priv
            blockSignature = Signature.newBuilder().        //constroi mensagem Signature com id e value(assinatura)
                setSignerIdentifier("Seq")
                .setSignatureValue(ByteString.copyFrom(sigBytes))
                .build();
        } catch (Exception e) {
            responseObserver.onError(io.grpc.Status.INTERNAL
                .withDescription("Failed to sign the block: " + e.getMessage())
                .asRuntimeException());
            return;     //se der erro a assinar, devolve erro gRPC e termina
        }

        //Construir resposta com resposta deliver block e nova assinatura do bloco
        SignedDeliverBlockResponse signedResponse = SignedDeliverBlockResponse.newBuilder()
            .setResponse(deliverBlockResponse)
            .setSignature(blockSignature)
            .build();

        responseObserver.onNext(signedResponse);
        responseObserver.onCompleted();
    }
}

package pt.tecnico.blockchainist.node.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import pt.tecnico.blockchainist.contract.*;
import pt.tecnico.blockchainist.node.domain.MembershipTable;
import pt.tecnico.blockchainist.node.domain.NodeState;
import pt.tecnico.blockchainist.node.domain.Transaction;
import java.security.PublicKey;
import java.util.Map;
import java.util.HashMap;
import com.google.protobuf.ByteString;

import static io.grpc.Status.INVALID_ARGUMENT;

import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;


public class NodeServiceImpl extends NodeServiceGrpc.NodeServiceImplBase {

    private static final long INITIAL_SYNC_PROBE_TIMEOUT_MS = 300;
    
    //Set de chaves de transações que foram executadas especulativamente com sucesso, para evitar re-execução quando bloco chegar
    private final Set<String> speculativelyExecuted = ConcurrentHashMap.newKeySet();

    //HashMap <walletId, Flag> para saber que wallets podem ser eliminadas antecipadamente

    // Chaves públicas dos users
    private final Map<String, PublicKey> userPublicKeys;

    // Chave pública do sequenciador
    private final PublicKey sequencerPublicKey;

    private final NodeState nodeState;
    private final String orgId;
    private final SequencerServiceGrpc.SequencerServiceBlockingStub sequencerStub;
    private final ManagedChannel sequencerChannel;
    private final boolean debug;

    private final Thread blockPullWorker;   //Worker Thread que "puxa" blocos do sequenciador ativamente
    private final Object startupSyncLock = new Object();
    private volatile boolean initialSyncComplete = false;
    private volatile boolean blockPullWorkerStarted = false;
    private volatile boolean running = true;

    //Classe que agrupa transação a possível erro ocurrido -> Permite delegar erros a respetivos donos clientes
    private static final class TxOutcome {
        private final String txKey;
        private final String error;

        private TxOutcome(String txKey, String error) {
            this.txKey = txKey;
            this.error = error;
        }
    }

    //Mapa que guarda possíveis erros de cada transação de um bloco
    private final ConcurrentMap<Integer, List<TxOutcome>> outcomesByBlock = new ConcurrentHashMap<>();


    //Construtor serviço NodeService, inclui criação de stub que permite comunicação com o sequenciador
    public NodeServiceImpl(String orgId, String sequencerHost, int sequencerPort, boolean debug) {

        this.nodeState = new NodeState();
        this.orgId = orgId;
        this.debug = debug;
        this.sequencerChannel = ManagedChannelBuilder.forAddress(sequencerHost, sequencerPort)
                .usePlaintext()
                .build();

        this.sequencerStub = SequencerServiceGrpc.newBlockingStub(sequencerChannel);
        this.blockPullWorker = new Thread(this::pullBlocksLoop, "block-pull-worker");
        this.blockPullWorker.setDaemon(true);   //define thread como low-priority no sistema, impede zombies
    
        this.userPublicKeys = new HashMap<>();
        for (String user : MembershipTable.getAllUserIds()) {
                try {
                    PublicKey pk = CryptoUtils.readPublicKey("/" + user + ".pub");
                    userPublicKeys.put(user, pk);
                    if (debug) System.err.println("Loaded public key for user: " + user);
                } catch (Exception e) {
                    System.err.println("Error: Could not load public key for " + user + ": " + e.getMessage());
                }
        }
        try {
            this.sequencerPublicKey = CryptoUtils.readPublicKey("/seq/Seq.pub");
            if (debug) System.err.println("Loaded sequencer public key");
        } catch (Exception e) {
            throw new RuntimeException("Error: Could not load sequencer public key", e);
        }    
    }    
    

    public void initialSync() {
        synchronized (startupSyncLock) {
            if (blockPullWorkerStarted) {
                return;
            }
            blockPullWorkerStarted = true;
        }
        this.blockPullWorker.start();
    }

    //Função que "puxa" blocos e aplica-os ao estado local, a ser executada em loop pela worker thread
    private void pullBlocksLoop() {
        boolean probingInitialHistory = true;

        while (running && !Thread.currentThread().isInterrupted()) {
            int nextBlockNum = nodeState.getLastAppliedBlockNum() + 1;
            DeliverBlockRequest request = DeliverBlockRequest.newBuilder()
                .setBlockNumber(nextBlockNum)
                .build();

            try {
                SequencerServiceGrpc.SequencerServiceBlockingStub stub = sequencerStub;
                if (probingInitialHistory) {
                    stub = stub.withDeadlineAfter(INITIAL_SYNC_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                }
                
                SignedDeliverBlockResponse signedResponse = stub.deliverBlock(request);
                
                DeliverBlockResponse deliverResp = signedResponse.getResponse();
                Signature blockSig = signedResponse.getSignature();

                if (!verifyBlockSignature(deliverResp, blockSig)) {
                    if (debug) {
                        System.err.println("Invalid sequencer signature!");
                    }
                    sleepQuietly(500);
                    continue;  
                }                
                                         
                Block block = deliverResp.getBlock();
                applyDeliveredBlock(block);

                if (debug) {
                    System.err.println("Node: synchronized block " + block.getBlockNumber());
                }
            } catch (StatusRuntimeException e) {
                if (probingInitialHistory && e.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED) {
                    markInitialSyncComplete();
                    probingInitialHistory = false;
                    continue;
                }

                if (debug) {
                    System.err.println("Node: deliverBlock failed, retrying. reason=" + e.getStatus());
                }
                sleepQuietly(200);
            } catch (RuntimeException e) {
                if (debug) {
                    System.err.println("Node: block apply failed. " + e.getMessage());
                }
                sleepQuietly(200);
            }
        }
    }

    private void markInitialSyncComplete() {
        synchronized (startupSyncLock) {
            if (initialSyncComplete) {
                return;
            }
            initialSyncComplete = true;
            startupSyncLock.notifyAll();
        }

        System.out.println(
            "Sincronização inicial concluída."
        );
    }

    private void waitUntilInitialSyncComplete() throws InterruptedException {
        synchronized (startupSyncLock) {
            while (running && !initialSyncComplete) {
                startupSyncLock.wait();
            }
        }
    }

    //Efetivamente aplica fetched block ao estado local do nó (Faz fronteira gRPC/domain)
    private void applyDeliveredBlock(Block block) {
        int expected = nodeState.getLastAppliedBlockNum() + 1;   //esperado é o próximo

        if (block.getBlockNumber() < expected) {   //já aplicou este bloco
            return;
        }

        //confirmar que é o esperado
        if(block.getBlockNumber() > expected) {
            throw new IllegalStateException("Unexpected block number, expected=" + expected + " got=" + block.getBlockNumber());
        }

        //definir lista de possíveis erros para este novo bloco
        List<TxOutcome> blockOutcomes = new ArrayList<>(block.getTransactionsCount());

        if (debug) {
            System.err.println("Node: applying block " + block.getBlockNumber() + " with " + block.getTransactionsCount() + " txs");
        }

       //Processa transações no bloco, guardando possíveis erros em blockOutcomes.
        for (pt.tecnico.blockchainist.contract.Transaction tx : block.getTransactionsList()) {
            String txKey = txKey(tx);
            Transaction domainTx = Transaction.fromContractTransaction(tx);
            
            if (speculativelyExecuted.remove(txKey)) {              //Se já tinha executado especulativamente(com sucesso), apenas registra como entregue, sem re-executar    
                nodeState.recordDeliveredTransaction(domainTx);
                blockOutcomes.add(new TxOutcome(txKey, null));
                continue;
            }

            try {       //ainda nao tinha executado especulativamente, executa normalmente e guarda possível erro
                nodeState.executeTransaction(domainTx);
                blockOutcomes.add(new TxOutcome(txKey, null));  //Não houve erro associado à execução da transação
            } catch (Exception e) {
                blockOutcomes.add(new TxOutcome(txKey, safeErrorMessage(e)));   //Houve erro
            }
        }

        //adicionar id deste bloco a mapa de erros de todos blocos
        outcomesByBlock.put(block.getBlockNumber(), blockOutcomes);

        //Notificar threads que novo bloco foi aplicado
        nodeState.markBlockApplied(block.getBlockNumber());

        //Se houverem muito mais que 2000 blocos ativos na blockchain, limpa listas de outcomes antigas
        int cleanupThreshold = block.getBlockNumber() - 2000;
        if (cleanupThreshold > 0) {
            outcomesByBlock.keySet().removeIf(k -> k <= cleanupThreshold);
        }
    }


    private void waitUntilApplied(int blockNumber) throws InterruptedException {
        nodeState.waitUntilBlockApplied(blockNumber);
    }

    //"Getter" para o outcome de transação específica no bloco indicado
    private String outcomeForTransactionInBlock(int blockNum, String targetTxKey) {
        List<TxOutcome> blockOutcomes = outcomesByBlock.get(blockNum);
        if (blockOutcomes == null) {
            return "Transaction outcome unavailable for block " + blockNum;
        }

        for (TxOutcome outcome : blockOutcomes) {
            if (outcome.txKey.equals(targetTxKey)) {
                return outcome.error;
            }
        }
        return "Transaction not found in expected block " + blockNum;
    }


    //Função que faz conversão de domain.Transaction para contract.Transaction
    private static pt.tecnico.blockchainist.contract.Transaction toContractTransaction(Transaction domainTx) {
        switch(domainTx.getType()) {
            case CREATE_WALLET:
                return pt.tecnico.blockchainist.contract.Transaction.newBuilder()
                    .setCreateWallet(CreateWalletRequest.newBuilder()
                        .setUserId(domainTx.getUserId()).setWalletId(domainTx.getWalletId()).build()).build();
            case DELETE_WALLET:
                return pt.tecnico.blockchainist.contract.Transaction.newBuilder()
                    .setDeleteWallet(DeleteWalletRequest.newBuilder()
                        .setUserId(domainTx.getUserId()).setWalletId(domainTx.getWalletId()).build()).build();
            case TRANSFER:
                return pt.tecnico.blockchainist.contract.Transaction.newBuilder()
                    .setTransfer(TransferRequest.newBuilder()
                    .setSrcUserId(domainTx.getSrcUserId())
                    .setSrcWalletId(domainTx.getSrcWalletId())
                    .setDstWalletId(domainTx.getDstWalletId())
                    .setValue(domainTx.getAmount()).build()).build();
            default:
                throw new IllegalArgumentException("Unknown transaction type: " + domainTx.getType());
        }
    }

    //helper que obtém o identificador de transação através de metadados de mensagem de transação, para usar como chave em mapas de outcomes
    private String requireRequestId() {
        String requestId = NodeDelayInterceptor.REQUEST_ID_CTX_KEY.get();

        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Missing request_id metadata");
        }
        return requestId;
    }

    private void validateUserBelongsToNodeOrganization(String userId) {
        if (!MembershipTable.belongsTo(userId, orgId)) {
            throw new IllegalArgumentException(" User " + userId + " does not belong to organization " + orgId);
        }
    }

    @Override
    public void createWallet(CreateWalletRequest request, StreamObserver<CreateWalletResponse> responseObserver){

        try {
            waitUntilInitialSyncComplete();
            validateUserBelongsToNodeOrganization(request.getUserId());     //pedido delete tem de ser feito na própria org
            //Broadcast
            //Construir transação para enviar ao sequenciador
            pt.tecnico.blockchainist.contract.Transaction tx =
                pt.tecnico.blockchainist.contract.Transaction.newBuilder()
                    .setCreateWallet(CreateWalletRequest.newBuilder()
                    .setUserId(request.getUserId())
                    .setWalletId(request.getWalletId())
                    .build())
                .build();

            verifyTransactionSignature(tx, request.getSignature());

            //obter chave de transação que virá do sequenciador
            String expectedTxKey = txKey(tx);

            //Constrói request de broadcast a enviar ao sequenciador
            String requestId = requireRequestId();

            BroadcastRequest broadcastReq = BroadcastRequest.newBuilder()
                .setRequestId(requestId)
                .setTransaction(tx)
                .build();

            //Obtém resposta broadcast a partir de request
            BroadcastResponse broadcastResp = sequencerStub.broadcast(broadcastReq);


            //Esperar até bloco com transação aplicada seja recebido e processado pelo nó
            waitUntilApplied(broadcastResp.getBlockNumber());

            //Obter erro da transação especifica no bloco em mãos
            String txError = outcomeForTransactionInBlock(broadcastResp.getBlockNumber(), expectedTxKey);

            //Erro não corretamente associado a Transação específica
            if (txError != null) {
                throw new IllegalArgumentException(txError);
            }

            //Construir resposta a enviar ao cliente
            CreateWalletResponse response = CreateWalletResponse.newBuilder().build();

            // Send a single response through the stream.
            responseObserver.onNext(response);
            // Notify the client that the operation has been completed.
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT.withDescription(safeErrorMessage(e)).asRuntimeException());
        }
    }

    @Override
    public void deleteWallet(DeleteWalletRequest request, StreamObserver<DeleteWalletResponse> responseObserver ){

        try {
            waitUntilInitialSyncComplete();
            validateUserBelongsToNodeOrganization(request.getUserId());     //pedido delete tem de ser feito na própria org

            nodeState.addPendingDeletion(request.getWalletId());   //Marca wallet como pendente de eliminação, para bloquear transferências futuras envolvendo esta wallet enquanto transação não for aplicada

            //Bloco try-finally que encapsula broadcast e aplicação de bloco com estado temporário pendingDeletion
            try {

             //Broadcast
            pt.tecnico.blockchainist.contract.Transaction tx =
                pt.tecnico.blockchainist.contract.Transaction.newBuilder()
                    .setDeleteWallet(DeleteWalletRequest.newBuilder()
                        .setUserId(request.getUserId())
                        .setWalletId(request.getWalletId())
                        .build())
                    .build();

            verifyTransactionSignature(tx, request.getSignature());

            //obter chave de transação que virá do sequenciador
            String expectedTxKey = txKey(tx);

            String requestId = requireRequestId();

            BroadcastRequest broadcastReq = BroadcastRequest.newBuilder()
                .setRequestId(requestId)
                .setTransaction(tx)
                .build();

            //Obtém resposta broadcast a partir de request
            BroadcastResponse broadcastResp = sequencerStub.broadcast(broadcastReq);


            //Esperar até bloco com transação aplicada seja recebido e processado pelo nó
            waitUntilApplied(broadcastResp.getBlockNumber());

            //Obter erro da transação especifica no bloco em mãos
            String txError = outcomeForTransactionInBlock(broadcastResp.getBlockNumber(), expectedTxKey);

            //Erro não corretamente associado a Transação específica
            if (txError != null) {
                throw new IllegalArgumentException(txError);
            }


            DeleteWalletResponse response = DeleteWalletResponse.newBuilder().build();
            // Send a single response through the stream.
            responseObserver.onNext(response);
            // Notify the client that the operation has been completed.
            responseObserver.onCompleted();

            } finally {
                nodeState.removePendingDeletion(request.getWalletId());    //Remove flag, para limpar estado temporário (em qualquer dos casos, sucesso ou erro)
            }
        }
        catch (Exception e) {
            responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT.withDescription(safeErrorMessage(e)).asRuntimeException());
        }
    }

    @Override
    public void transfer(TransferRequest request, StreamObserver<TransferResponse> responseObserver ){

        try {
            waitUntilInitialSyncComplete();
            validateUserBelongsToNodeOrganization(request.getSrcUserId());     //pedido de transferência tem de ser feito na própria org

            //Verificar que transferência é intra-organizacional, ou seja, que dstWallet pertence a user da mesma org do srcUser

            //Verificar que nenhuma das wallets envolvidas está pendente de eliminação, se estiverem, excepção é lançada e pedido é rejeitado
            nodeState.checkPendingDeletions(request.getSrcWalletId(), request.getDstWalletId());

            //Início de construção de transação através de request
            pt.tecnico.blockchainist.contract.Transaction tx =
                pt.tecnico.blockchainist.contract.Transaction.newBuilder()
                    .setTransfer(TransferRequest.newBuilder()
                        .setSrcUserId(request.getSrcUserId())
                        .setSrcWalletId(request.getSrcWalletId())
                        .setDstWalletId(request.getDstWalletId())
                        .setValue(request.getValue())
                        .build())
                    .build();

            verifyTransactionSignature(tx, request.getSignature());
            //obter chave de transação que virá do sequenciador
            String expectedTxKey = txKey(tx);

            String requestId = requireRequestId();

            BroadcastRequest broadcastReq = BroadcastRequest.newBuilder()
                .setRequestId(requestId)
                .setTransaction(tx)
                .build();

            //Obtém resposta broadcast a partir de request
            BroadcastResponse broadcastResp = sequencerStub.broadcast(broadcastReq);


            if(broadcastResp.getEarlyAck()){
                Transaction domainTx = Transaction.fromContractTransaction(tx);
                
                nodeState.executeTransferSpeculatively(domainTx);

                //Adicionar txKey ao set para ignorar quando chegar no bloco
                speculativelyExecuted.add(expectedTxKey);
            } else {
                //Esperar até bloco com transação aplicada seja recebido e processado pelo nó
                waitUntilApplied(broadcastResp.getBlockNumber());

                //Obter erro da transação especifica no bloco em mãos
                String txError = outcomeForTransactionInBlock(broadcastResp.getBlockNumber(), expectedTxKey);

                //Erro não corretamente associado a Transação específica 
                if (txError != null) {
                    throw new IllegalArgumentException(txError);
                }
            }
            
            TransferResponse response = TransferResponse.newBuilder().build();
            // Send a single response through the stream.
            responseObserver.onNext(response);
            // Notify the client that the operation has been completed.
            responseObserver.onCompleted();
        }
        catch(Exception e) {
            responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT.withDescription(safeErrorMessage(e)).asRuntimeException());   
        }
    }

    @Override
    public void readBalance(ReadBalanceRequest request, StreamObserver<ReadBalanceResponse> responseObserver ){
        
        try {
            waitUntilInitialSyncComplete();
            long balance = nodeState.readBalance(request.getWalletId());

            ReadBalanceResponse response = ReadBalanceResponse.newBuilder().setBalance(balance).build();
            // Send a single response through the stream.
            responseObserver.onNext(response);
            // Notify the client that the operation has been completed.
            responseObserver.onCompleted();
        } catch(Exception e) {
            responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT.withDescription(safeErrorMessage(e)).asRuntimeException());   
        }
    }

    @Override
    public void getBlockchainState(GetBlockchainStateRequest request, StreamObserver<GetBlockchainStateResponse> responseObserver){
        

        try {            
            waitUntilInitialSyncComplete();
            List<pt.tecnico.blockchainist.contract.Transaction> contractTxs = nodeState.getBlockchainState()
            .stream().map(NodeServiceImpl :: toContractTransaction)
                .collect(Collectors.toList());

            GetBlockchainStateResponse response = GetBlockchainStateResponse.newBuilder().addAllTransactions(contractTxs).build();
            // Send a single response through the stream.
            responseObserver.onNext(response);
            // Notify the client that the operation has been completed.
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(INVALID_ARGUMENT.withDescription(safeErrorMessage(e)).asRuntimeException());
        }
    }

    //desliga forçadamente o nó, interrompendo a worker thread e fechando canal gRPC
    public void shutdown() {
        running = false;
        synchronized (startupSyncLock) {
            startupSyncLock.notifyAll();
        }
        blockPullWorker.interrupt();

        sequencerChannel.shutdown();
        try {
            if (!sequencerChannel.awaitTermination(3, TimeUnit.SECONDS)) {
                sequencerChannel.shutdownNow();
            }
        }catch (InterruptedException e) {
            sequencerChannel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    //função que "dorme" por um certo tempo, ignorando interrupções
    public static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }


    private static String txKey(pt.tecnico.blockchainist.contract.Transaction tx) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(tx.toByteArray());
            return toHex(digest);
        } catch (NoSuchAlgorithmException e) {
            return new String(tx.toByteArray(), StandardCharsets.ISO_8859_1);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b: bytes) {
            sb.append(String.format("%02x", b));

        }
        return sb.toString();
    }

    //Obtém mensagem de erro de forma segura para usar em onError
    private static String safeErrorMessage(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) {
            return t.getClass().getSimpleName();
        } 
        return msg;
    }

    private void verifyTransactionSignature(pt.tecnico.blockchainist.contract.Transaction tx,
                                        Signature signature) {
        String signerId = signature.getSignerIdentifier();
        ByteString sigValue = signature.getSignatureValue();

        if (signerId == null || signerId.isBlank() || sigValue.isEmpty()) {
            throw new IllegalArgumentException(" Missing signature on transaction");
        }

        PublicKey pubKey = userPublicKeys.get(signerId);
        if (pubKey == null) {
            throw new IllegalArgumentException(" Unknown signer: " + signerId);
        }

        String txUserId = extractUserId(tx);
        if (!signerId.equals(txUserId)) {
            throw new IllegalArgumentException(
                " Signer mismatch: signature by " + signerId + " but transaction for user " + txUserId);
        }

        try {
            boolean valid = CryptoUtils.verify(tx.toByteArray(), sigValue.toByteArray(), pubKey);
            if (!valid) {
                throw new IllegalArgumentException(" Invalid signature for user " + signerId);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(" Signature verification failed: " + e.getMessage());
        }

        if (debug) {
            System.err.println("Node: signature VALID for user " + signerId);
        }
    }

    private boolean verifyBlockSignature(DeliverBlockResponse response, Signature signature) {
        if (signature == null || signature.getSignatureValue().isEmpty()) {
            return false;
        }

        try {
            boolean valid = CryptoUtils.verify(response.toByteArray(), signature.getSignatureValue().toByteArray(), sequencerPublicKey);
            return valid;
        } catch (Exception e) {
            if (debug) System.err.println("Node: signature verification error: " + e.getMessage());
            return false;
        }
    }

    private static String extractUserId(pt.tecnico.blockchainist.contract.Transaction tx) {
        switch (tx.getOperationCase()) {
            case CREATE_WALLET: return tx.getCreateWallet().getUserId();
            case DELETE_WALLET: return tx.getDeleteWallet().getUserId();
            case TRANSFER: return tx.getTransfer().getSrcUserId();
            default: throw new IllegalArgumentException("Unknown transaction type");
        }
    }
}

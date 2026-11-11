package pt.tecnico.blockchainist.client;

import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import java.util.concurrent.atomic.AtomicInteger;

import java.util.UUID;

import pt.tecnico.blockchainist.client.grpc.ClientNodeService;
import pt.tecnico.blockchainist.client.grpc.ResponseObserver;
import io.grpc.StatusRuntimeException;
import io.grpc.Status;
import pt.tecnico.blockchainist.contract.*;




public class CommandProcessor {

    private static final String SPACE = " ";
    private static final String CREATE_BLOCKING = "C";
    private static final String CREATE_ASYNC = "c";
    private static final String DELETE_BLOCKING = "E";
    private static final String DELETE_ASYNC = "e";
    private static final String BALANCE_BLOCKING = "S";
    private static final String BALANCE_ASYNC = "s";
    private static final String TRANSFER_BLOCKING = "T";
    private static final String TRANSFER_ASYNC = "t";
    private static final String DEBUG_BLOCKCHAIN_STATE = "B";
    private static final String PAUSE = "P";
    private static final String EXIT = "X";

    private static final Pattern ID_PATTERN = Pattern.compile("^[a-zA-Z0-9]+$");

    //timeout aplicado às chamadas bloqueantes, se expirar failover tenta o nó seguinte
    private static final long CLIENT_RPC_TIMEOUT_MS = 10000L;

    private final AtomicLong commandCounter = new AtomicLong(0);
    private final ArrayList<ClientNodeService> nodes;
    private final boolean debug;
    private final String signerId;        
    private final PrivateKey privateKey;

    //UUID único desta instância do cliente, usado para construir request_id por comando
    private final String clientInstanceId = UUID.randomUUID().toString();

    private final AtomicInteger pendingCount = new AtomicInteger(0);
    private final Object pendingLock = new Object();
    private final Object printLock = new Object();

    public CommandProcessor(ArrayList<ClientNodeService> nodes, boolean debug, String signerId, PrivateKey privateKey) {
        this.nodes = nodes;
        this.debug = debug;
        this.signerId = signerId;
        this.privateKey = privateKey;
    }

    void userInputLoop() {

        Scanner scanner = new Scanner(System.in);
        boolean exit = false;

        while (!exit) {
            System.out.print("\n> ");
            String line = scanner.nextLine().trim();
            String[] split = line.split(SPACE);
            try {
                switch (split[0]) {
                    case CREATE_BLOCKING:
                        this.create(split, true);
                        break;

                    case CREATE_ASYNC:
                        this.create(split, false);
                        break;

                    case DELETE_BLOCKING:
                        this.delete(split, true);
                        break;

                    case DELETE_ASYNC:
                        this.delete(split, false);
                        break;

                    case BALANCE_BLOCKING:
                        this.balance(split, true);
                        break;

                    case BALANCE_ASYNC:
                        this.balance(split, false);
                        break;

                    case TRANSFER_BLOCKING:
                        this.transfer(split, true);
                        break;

                    case TRANSFER_ASYNC:
                        this.transfer(split, false);
                        break;

                    case DEBUG_BLOCKCHAIN_STATE:
                        this.debugBlockchainState(split);
                        break;

                    case PAUSE:
                        this.pause(split);
                        break;

                    case EXIT:
                        awaitPendingAsync();
                        exit = true;
                        break;

                    default:
                        printUsage();
                        break;
                }
            } catch (IllegalArgumentException e) {
                System.err.println("Error: " + e.getMessage());
                printUsage();
            }
        }
        scanner.close();
    }

    private void create(String[] split, boolean isBlocking) {
        this.checkCreateCommandArgs(split);

        Long commandNumber = this.commandCounter.incrementAndGet();

        String userId = split[1];
        String walletId = split[2];
        Integer nodeIndex = Integer.parseInt(split[3]);
        Integer nodeDelay = Integer.parseInt(split[4]);

        if (debug) {                                                    
            System.err.println("DEBUG [" + commandNumber + "]: createWallet userId=" + userId + " walletId=" + walletId + " node=" + nodeIndex);
        }

        ClientNodeService node = nodes.get(nodeIndex);
        String requestId = requestIdForCommand(commandNumber);

        //reconstroi o Transaction exacto que o nó irá receber e assina os seus bytes
        Transaction tx = Transaction.newBuilder()
            .setCreateWallet(CreateWalletRequest.newBuilder()
                    .setUserId(userId)
                    .setWalletId(walletId))
            .build();
        
        Signature txSignature = buildTransactionSignature(tx);
        
        //modo blocking para so devolver controlo ao user depois da resposta do nó
        if (isBlocking) {
            try {
                
                invokeWithFailover(nodeIndex, nodeSvc -> {
                    nodeSvc.createWalletWithTimeout(userId, walletId, nodeDelay, requestId, CLIENT_RPC_TIMEOUT_MS, txSignature);
                    return null;
                });
                synchronized (printLock) {
                   // System.out.println(commandNumber + " OK");
                   System.out.println("OK " + commandNumber);
                }
            } catch (StatusRuntimeException e) {
                synchronized (printLock) {
                    System.err.println("ERROR: " + commandNumber + e.getStatus().getDescription());
                }
            }
            return;
        }

        //modo async: incrementa pendentes antes de disparar e o ResponseObserver decrementa em onCompleted/onError
        ResponseObserver<CreateWalletResponse> observer = new ResponseObserver<>(commandNumber, pendingCount, pendingLock, printLock);

        pendingCount.incrementAndGet();
        try {
            node.createWalletAsync(userId,requestId, walletId, nodeDelay, txSignature, observer);
            return;
        } catch (RuntimeException e) {
            synchronized (pendingLock) {
                pendingCount.decrementAndGet();
                pendingLock.notifyAll();
            }
            synchronized (printLock) {
                System.err.println("ERROR: " + commandNumber + extractErrorDescription(e));
            }
        }
    }

    private void delete(String[] split, boolean isBlocking) {
        this.checkDeleteCommandArgs(split);

        Long commandNumber = this.commandCounter.incrementAndGet();

        String userId = split[1];
        String walletId = split[2];
        Integer nodeIndex = Integer.parseInt(split[3]);
        Integer nodeDelay = Integer.parseInt(split[4]);

        if (debug) {                                                    
            System.err.println("DEBUG [" + commandNumber + "]: deleteWallet userId=" + userId + " walletId=" + walletId + " node=" + nodeIndex);
        }

        ClientNodeService node = nodes.get(nodeIndex);
        String requestId = requestIdForCommand(commandNumber);

        Transaction tx = Transaction.newBuilder()
                .setDeleteWallet(DeleteWalletRequest.newBuilder()
                        .setUserId(userId)
                        .setWalletId(walletId))
                .build();
        Signature txSignature = buildTransactionSignature(tx);
        if (isBlocking) {
            try {
                invokeWithFailover(nodeIndex, nodeSvc -> {
                    nodeSvc.deleteWalletWithTimeout(userId, walletId, nodeDelay, requestId, CLIENT_RPC_TIMEOUT_MS, txSignature);
                    return null;
                });
                synchronized (printLock) {
                    System.out.println("OK " + commandNumber);
                }
            } catch (StatusRuntimeException e) {
                synchronized (printLock) {
                    System.err.println("ERROR: " + commandNumber + e.getStatus().getDescription());
                }
            }
            return;
        }

        ResponseObserver<DeleteWalletResponse> observer = new ResponseObserver<>(commandNumber, pendingCount, pendingLock, printLock);

        pendingCount.incrementAndGet();
        try {
            node.deleteWalletAsync(userId,requestId, walletId, nodeDelay,txSignature, observer);
            return;
        } catch (RuntimeException e) {
            synchronized (pendingLock) {
                pendingCount.decrementAndGet();
                pendingLock.notifyAll();
            }
            synchronized (printLock) {
                System.err.println("ERROR: " + commandNumber + extractErrorDescription(e));
            }
        }
        
    }

    private void balance(String[] split, boolean isBlocking) {
        this.checkBalanceCommandArgs(split);

        Long commandNumber = this.commandCounter.incrementAndGet();

        String walletId = split[1];
        Integer nodeIndex = Integer.parseInt(split[2]);
        Integer nodeDelay = Integer.parseInt(split[3]);

        if (debug) {                                                    
            System.err.println("DEBUG [" + commandNumber + "]: readBalance walletId=" + walletId + " node=" + nodeIndex);
        } 

        ClientNodeService node = nodes.get(nodeIndex);

        if (isBlocking) {
            try {
                ReadBalanceResponse response = invokeWithFailover(nodeIndex, nodeSvc -> nodeSvc.readBalanceWithTimeout(walletId, nodeDelay, CLIENT_RPC_TIMEOUT_MS));
                synchronized (printLock) {
                    //System.out.println(commandNumber + " OK");
                    System.out.println("OK " + commandNumber);
                    System.out.println(response.getBalance());
                }
            } catch (StatusRuntimeException e) {
                synchronized (printLock) {
                    System.err.println("ERROR: " + commandNumber +  e.getStatus().getDescription());
                }
            }
            return;
        }

        ResponseObserver<ReadBalanceResponse> observer = new ResponseObserver<>(commandNumber, pendingCount, pendingLock, printLock);

        pendingCount.incrementAndGet();
        try {
            node.readBalanceAsync(walletId, nodeDelay, observer);
            return;
        } catch (RuntimeException e) {
            synchronized (pendingLock) {
                pendingCount.decrementAndGet();
                pendingLock.notifyAll();
            }
            synchronized (printLock) {
                System.err.println("ERROR: " + commandNumber +  extractErrorDescription(e));
            }
        }        
    }

    private void transfer(String[] split, boolean isBlocking) {
        this.checkTransferCommandArgs(split);

        Long commandNumber = this.commandCounter.incrementAndGet();

        String sourceUserId = split[1];
        String sourceWalletId = split[2];
        String destinationWalletId = split[3];
        Long amount = Long.parseLong(split[4]);
        Integer nodeIndex = Integer.parseInt(split[5]);
        Integer nodeDelay = Integer.parseInt(split[6]);


        if (debug) {                                                    
            System.err.println("DEBUG [" + commandNumber + "]: transfer " + sourceWalletId + " -> " + destinationWalletId + " amount=" + amount + " node=" + nodeIndex);
        }

        ClientNodeService node = nodes.get(nodeIndex);
        String requestId = requestIdForCommand(commandNumber);

        // assina a transação de transferência com os mesmos bytes que o nó irá verificar
        Transaction tx = Transaction.newBuilder()
                .setTransfer(TransferRequest.newBuilder()
                        .setSrcUserId(sourceUserId)
                        .setSrcWalletId(sourceWalletId)
                        .setDstWalletId(destinationWalletId)
                        .setValue(amount))
                .build();
        Signature txSignature = buildTransactionSignature(tx);
        
        if (isBlocking) {
            try {
                
                invokeWithFailover(nodeIndex, nodeSvc -> {
                    nodeSvc.transferWithTimeout(sourceUserId, sourceWalletId, destinationWalletId, amount,
                        nodeDelay, requestId, CLIENT_RPC_TIMEOUT_MS, txSignature);
                    return null;
                });
                synchronized (printLock) {
                    System.out.println("OK " + commandNumber);
                }
            } catch (StatusRuntimeException e) {
                synchronized (printLock) {
                    System.err.println("ERROR: " + commandNumber + e.getStatus().getDescription());
                }
            }
            return;
        }

        ResponseObserver<TransferResponse> observer = new ResponseObserver<>(commandNumber, pendingCount, pendingLock, printLock);

        pendingCount.incrementAndGet();
        try {
            node.transferAsync(sourceUserId,requestId, sourceWalletId, destinationWalletId, amount, nodeDelay, txSignature, observer);
            return;
        } catch (RuntimeException e) {
            synchronized (pendingLock) {
                pendingCount.decrementAndGet();
                pendingLock.notifyAll();
            }
            synchronized (printLock) {
                System.err.println("ERROR: " + commandNumber +  extractErrorDescription(e));
            }
        }
        
    }

    private void debugBlockchainState(String[] split) {
        this.checkDebugBlockchainStateArgs(split);

        Long commandNumber = this.commandCounter.incrementAndGet();

        Integer nodeIndex = Integer.parseInt(split[1]);

        if (debug) {                                                    
            System.err.println("DEBUG [" + commandNumber + "]: getBlockchainState node=" + nodeIndex);
        }

        
        try {
            GetBlockchainStateResponse response = invokeWithFailover(nodeIndex,
                nodeSvc -> nodeSvc.getBlockchainStateWithTimeout(CLIENT_RPC_TIMEOUT_MS));
            //System.out.println(commandNumber + " OK");
            System.out.println("OK " + commandNumber);
            String responseStr = response.toString().trim();
            if (!responseStr.isEmpty()) {
                System.out.println(responseStr);
            }
        } catch (StatusRuntimeException e) {
            System.err.println("ERROR: " + commandNumber +  e.getStatus().getDescription());
        }
        
    }

    private void pause(String[] split) {
        this.checkPauseArgs(split);

        Integer time;

        time = Integer.parseInt(split[1]);

        if (debug) {                                                    
            System.err.println("DEBUG: pause " + time + "s");
        }

        try {
            Thread.sleep(time * 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();  
        }
    }

    private void checkCreateCommandArgs(String[] split) {
        // C|c <user_id> <wallet_id> <node_index> <node_delay>
        if (split.length != 5) {
            throw new IllegalArgumentException("Expected 5 arguments, got " + split.length);
        }

        if (!ID_PATTERN.matcher(split[1]).matches()) {
            throw new IllegalArgumentException("Expected User ID to be composed of ASCII alphanumeric characters, got \"" + split[1] + "\"");
        }

        if (!ID_PATTERN.matcher(split[2]).matches()) {
            throw new IllegalArgumentException("Expected Wallet ID to be composed of ASCII alphanumeric characters, got \"" + split[2] + "\"");
        }

        try {
            int nodeIndex = Integer.parseInt(split[3]);
            if (nodeIndex < 0 || nodeIndex >= this.nodes.size()) {
                throw new IllegalArgumentException("Node index must be between 0 and " + (this.nodes.size() - 1));
            }
            if (Integer.parseInt(split[4]) < 0) {
                throw new IllegalArgumentException("Node delay cannot be negative");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expected initial balance, node number, and node delay to be integers");
        }
    }

    private void checkDeleteCommandArgs(String[] split) {
        // E|e <user_id> <wallet_id> <node_index> <node_delay>
        if (split.length != 5) {
            throw new IllegalArgumentException("Expected 5 arguments, got " + split.length);
        }

        if (!ID_PATTERN.matcher(split[1]).matches()) {
            throw new IllegalArgumentException("Expected User ID to be composed of ASCII alphanumeric characters, got \"" + split[1] + "\"");
        }

        if (!ID_PATTERN.matcher(split[2]).matches()) {
            throw new IllegalArgumentException("Expected Wallet ID to be composed of ASCII alphanumeric characters, got \"" + split[2] + "\"");
        }

        try {
            int nodeIndex = Integer.parseInt(split[3]);
            if (nodeIndex < 0 || nodeIndex >= this.nodes.size()) {
                throw new IllegalArgumentException("Node index must be between 0 and " + (this.nodes.size() - 1));
            }
            if (Integer.parseInt(split[4]) < 0) {
                throw new IllegalArgumentException("Node delay cannot be negative");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expected node number and node delay to be integers");
        }
    }

    private void checkBalanceCommandArgs(String[] split) {
        // S|s <wallet_id> <node_index> <node_delay>
        if (split.length != 4) {
            throw new IllegalArgumentException("Expected 4 arguments, got " + split.length);
        }

        if (!ID_PATTERN.matcher(split[1]).matches()) {
            throw new IllegalArgumentException("Expected Wallet ID to be composed of ASCII alphanumeric characters, got \"" + split[1] + "\"");
        }

        try {
            int nodeIndex = Integer.parseInt(split[2]);
            if (nodeIndex < 0 || nodeIndex >= this.nodes.size()) {
                throw new IllegalArgumentException("Node index must be between 0 and " + (this.nodes.size() - 1));
            }
            if (Integer.parseInt(split[3]) < 0) {
                throw new IllegalArgumentException("Node delay cannot be negative");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expected node number and node delay to be integers");
        }
    }

    private void checkTransferCommandArgs(String[] split) {
        // T|t <source_user_id> <source_wallet_id> <destination_wallet_id> <amount> <node_index> <node_delay>
        if (split.length != 7) {
            throw new IllegalArgumentException("Expected 7 arguments, got " + split.length);
        }

        if (!ID_PATTERN.matcher(split[1]).matches()) {
            throw new IllegalArgumentException("Expected Source User ID to be composed of ASCII alphanumeric characters, got \"" + split[1] + "\"");
        }

        if (!ID_PATTERN.matcher(split[2]).matches()) {
            throw new IllegalArgumentException("Expected Source Wallet ID to be composed of ASCII alphanumeric characters, got \"" + split[2] + "\"");
        }

        if (!ID_PATTERN.matcher(split[3]).matches()) {
            throw new IllegalArgumentException("Expected Destination Wallet ID to be composed of ASCII alphanumeric characters, got \"" + split[1] + "\"");
        }

        try {
            if (Long.parseLong(split[4]) < 0) {
                throw new IllegalArgumentException("Amount cannot be negative");
            }
            int nodeIndex = Integer.parseInt(split[5]);
            if (nodeIndex < 0 || nodeIndex >= this.nodes.size()) {
                throw new IllegalArgumentException("Node index must be between 0 and " + (this.nodes.size() - 1));
            }
            if (Integer.parseInt(split[6]) < 0) {
                throw new IllegalArgumentException("Node delay cannot be negative");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expected amount, node number, and node delay to be integers");
        }
    }

    private void checkDebugBlockchainStateArgs(String[] split) {
        // B <node_index>
        if (split.length != 2) {
            throw new IllegalArgumentException("Expected 2 arguments, got " + split.length);
        }

        try {
            int nodeIndex = Integer.parseInt(split[1]);
            if (nodeIndex < 0 || nodeIndex >= this.nodes.size()) {
                throw new IllegalArgumentException("Node index must be between 0 and " + (this.nodes.size() - 1));
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expected node index to be an integer");
        }
    }

    private void checkPauseArgs(String[] split) {
        // P <integer>
        if (split.length != 2) {
            throw new IllegalArgumentException("Expected 2 arguments, got " + split.length);
        }

        try {
            if (Integer.parseInt(split[1]) < 0) {
                throw new IllegalArgumentException("Pause time cannot be negative");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expected pause time to be an integer");
        }
    }

    //helpers

    //bloqueia até não haver callbacks async pendentes
    private void awaitPendingAsync() {
        synchronized (pendingLock) {
            while (pendingCount.get() > 0) {
                try {
                    pendingLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    //transforma exceções gRPC em mensagens curtas e consistentes
    private String extractErrorDescription(Throwable t) {
        if (t instanceof StatusRuntimeException) {
            StatusRuntimeException sre = (StatusRuntimeException) t;
            if (sre.getStatus() != null && sre.getStatus().getDescription() != null) {
                return sre.getStatus().getDescription();
            } else {
                return sre.getStatus().getCode().name();
            }
        }

        Status status = Status.fromThrowable(t);
        if (status != null && status.getDescription() != null) {
            return status.getDescription();
        }
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }

    @FunctionalInterface
    private interface RpcCall<R> {
        R call(ClientNodeService node) throws StatusRuntimeException;
    }

    //só faz sentido tentar noutro nó em falhas transitórias
    private boolean isRetryable(StatusRuntimeException e) {
        Status.Code code = e.getStatus().getCode();
        return code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED;
    }

    //tenta no nó inicial e vai pelos restantes até sucesso ou esgotar nós
    private <R> R invokeWithFailover(int initialNodeIndex, RpcCall<R> call) {
        StatusRuntimeException lastError = null;
        int total = nodes.size();

        for (int attempt = 0; attempt < total; attempt++) {
            int idx = (initialNodeIndex + attempt) % total;
            ClientNodeService node = nodes.get(idx);
            try {
                return call.call(node);
            } catch (StatusRuntimeException e) {
                lastError = e;
                if (!isRetryable(e)) {
                    throw e;
                }
                if (debug) {
                    System.err.println("DEBUG: failover attempt=" + (attempt + 1)
                        + " node=" + idx + " reason=" + e.getStatus().getCode());
                }
            }
        }

        throw lastError == null
            ? Status.UNAVAILABLE.withDescription("No nodes available").asRuntimeException()
            : lastError;
    }

    //gera um request_id por comando
    private String requestIdForCommand(long commandNumber) {
        return clientInstanceId + ":" + commandNumber;
    }

    //constrói a Signature protobuf para uma transação e assina tx.toByteArray() com a chave privada do utilizador.
    private Signature buildTransactionSignature(Transaction tx) {
    if (privateKey == null || signerId == null) {
        // Sem chave privada → assinatura vazia (modo degradado para debug)
        return Signature.getDefaultInstance();
    }
    try {
        byte[] sigBytes = CryptoUtils.sign(tx.toByteArray(), privateKey);
        return Signature.newBuilder()
                .setSignerIdentifier(signerId)
                .setSignatureValue(com.google.protobuf.ByteString.copyFrom(sigBytes))
                .build();
    } catch (Exception e) {
        System.err.println("ERROR: failed to sign transaction: " + e.getMessage());
        return Signature.getDefaultInstance();
    }
}

    private static void printUsage() {
        System.err.println("Usage:\n" +
                "- C|c <user_id> <wallet_id> <node_index> <node_delay>\n" +
                "- E|e <user_id> <wallet_id> <node_index> <node_delay>\n" +
                "- S|s <wallet_id> <node_index> <node_delay>\n" +
                "- T|t <source_user_id> <source_wallet_id> <destination_wallet_id> <amount> <node_index> <node_delay>\n" +
                "- B <node_index>\n" +
                "- P <integer>\n" +
                "- X\n");
    }
}

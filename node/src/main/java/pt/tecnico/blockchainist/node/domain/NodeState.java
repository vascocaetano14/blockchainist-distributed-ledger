package pt.tecnico.blockchainist.node.domain;

import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.ArrayList;

public class NodeState {


    // mapa de carteiras na blockchain <walletId, Wallet object>
    private final Map<String, Wallet> wallets = new ConcurrentHashMap<>();

    // estado atual de blockchain que nó possuí
    //tem que passar a ser lista lista transações
    private final List<Transaction> localLedger = new ArrayList<>();

    //set thread-safe para guardar ids de carteiras pendentes de eliminaçao
    private final Set<String> pendingDeletions = ConcurrentHashMap.newKeySet();

    //Tracker do último bloco aplicado no estado do nó
    private int lastAppliedBlockNum = 0;

    private final Object blockLock = new Object();

    //Lock para garantir consistência de leitura/escrita
    private final Object stateLock = new Object();

    public NodeState() {
        wallets.put("bc", new Wallet("bc", "BC", 1000L));
    }


    //getter thread-safe para lastAppliedBlockNum
    public int getLastAppliedBlockNum() {
        synchronized (blockLock) {
            return lastAppliedBlockNum;
        }
    }

    //função para atualizar lastAppliedBlockNum e notificar threads à espera
    public void waitUntilBlockApplied(int targetBlockNum) throws InterruptedException {

        synchronized (blockLock) {
            while (lastAppliedBlockNum < targetBlockNum) {
                blockLock.wait();
            }
        }
    }

    
    //Obtém próximo bloco, marca como applied e notifica threads
    public void markBlockApplied(int blockNum) {
        synchronized(blockLock) {
            if (blockNum == lastAppliedBlockNum + 1) {  //é o próximo
                lastAppliedBlockNum = blockNum;
                blockLock.notifyAll();
                return;
            }
            if(blockNum <= lastAppliedBlockNum) {   //ainda não chegou a próximo
                return;
            }
            throw new IllegalStateException( "Out-of-order block, expected=" + (lastAppliedBlockNum + 1) + " got=" + blockNum); //nenhmum caso anterior-> bloco errado
        }
    }
    

    private void createWallet(String userId, String walletId, Transaction tx, boolean recordInLedger) {
        if (wallets.containsKey(walletId)) {
            throw new IllegalArgumentException("Wallet " + walletId + " already exists");
        }

        wallets.put(walletId, new Wallet(walletId, userId));

        if (recordInLedger) {
            synchronized (localLedger) {
                localLedger.add(tx);
            }
        }
    }

    private void deleteWallet(String userId, String walletId, Transaction tx, boolean recordInLedger) {
       Wallet w = wallets.get(walletId);
        if (w == null) {    //carteira não existe
            throw new IllegalArgumentException("Wallet " + walletId + " does not exist");
        }
        if (!w.getOwnerId().equals(userId)) {    //carteira existe mas não pertence ao userId
            throw new IllegalArgumentException("Wallet " + walletId + " does not belong to user " + userId);
        }
        if (w.getBalance().get() != 0L) {    //carteira ainda tem saldo
            throw new IllegalArgumentException("Wallet " + walletId + " has credit");
        }
        wallets.remove(walletId);

        if (recordInLedger) {
            synchronized (localLedger) { //documenta transação no localLedger
                localLedger.add(tx);
            }
        }
    }

    private void transfer(String srcUserId, String srcWalletId, String dstWalletId, Long amount, Transaction tx, boolean recordInLedger) {
        Wallet srcWallet = wallets.get(srcWalletId);
        if (srcWallet == null) {    //source wallet não existe
            throw new IllegalArgumentException("Source wallet " + srcWalletId + " does not exist");
        }
        if (!srcWallet.getOwnerId().equals(srcUserId)) {    //source wallet existe mas não pertence ao srcUserId
            throw new IllegalArgumentException("Source wallet " + srcWalletId + " does not belong to user " + srcUserId);
        }

        Wallet dstWallet = wallets.get(dstWalletId);
        if (dstWallet == null) {    //destination wallet não existe
            throw new IllegalArgumentException("Destination wallet " + dstWalletId + " does not exist");
        }

        if(amount <= 0L) {    //amount a transferir inválido
            throw new IllegalArgumentException("Amount must be positive");
        }

        if(srcWallet.getBalance().get() < amount) {    //source wallet não tem saldo suficiente
            throw new IllegalArgumentException("Source wallet " + srcWalletId + " has insufficient balance");
        }

        srcWallet.subtractBalance(amount);
        dstWallet.addBalance(amount);

        if (recordInLedger) {
            synchronized (localLedger) { //documenta transação no localLedger
                localLedger.add(tx);
            }
        }
    }

    public long readBalance(String walletId) {
        //Lock leitura
        synchronized (stateLock) {
            Wallet w = wallets.get(walletId);
            if (w == null) {
                throw new IllegalArgumentException("Wallet " + walletId + " does not exist");
            }
            return w.getBalance().get();
        }
    }

    public List<Transaction> getBlockchainState() {
        //Lock escrita
        synchronized (stateLock) {
            return List.copyOf(localLedger); 
        }
    }

    //função que executa transação no estado do nó
    public synchronized void executeTransaction(Transaction domainTx) {
        synchronized (stateLock) {
            switch(domainTx.getType()) {
                case CREATE_WALLET:
                    createWallet(domainTx.getUserId(), domainTx.getWalletId(), domainTx, true);
                    break;
                case DELETE_WALLET:
                    deleteWallet(domainTx.getUserId(), domainTx.getWalletId(), domainTx, true);
                    break;
                case TRANSFER:
                    transfer(domainTx.getSrcUserId(), domainTx.getSrcWalletId(), domainTx.getDstWalletId(), domainTx.getAmount(), domainTx, true);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown transaction type: " + domainTx.getType());
            }
        }
    }


    //executa a transferência especulativamente para otimizar latência com em ordem causal
    public synchronized void executeTransferSpeculatively(Transaction domainTx) {
        synchronized (stateLock) {
            if (domainTx.getType() != Transaction.Type.TRANSFER) {
                throw new IllegalArgumentException("Speculative execution is only supported for transfer transactions");
            }
            transfer(domainTx.getSrcUserId(), domainTx.getSrcWalletId(), domainTx.getDstWalletId(), domainTx.getAmount(), domainTx, false);
        }
    }

    public void recordDeliveredTransaction(Transaction domainTx) {
        synchronized (stateLock) {
            synchronized (localLedger) {
                localLedger.add(domainTx);
            }
        }
    }

    //métodos de gestão de wallets pendentes de eliminação

    //Adiciona carteira a set de pendentes de eliminação
    public void addPendingDeletion(String walletId) {
        pendingDeletions.add(walletId);
    }
 
    //Remove carteira de set de pendentes de eliminação
    public void removePendingDeletion(String walletId) {
        pendingDeletions.remove(walletId);
    }

    //Verifica se srcWallet ou dstWallet estão pendentes de eliminação, lançando exceção se estiverem
    public void checkPendingDeletions(String srcWalletId, String dstWalletId) {
        if (pendingDeletions.contains(srcWalletId)) {
            throw new IllegalArgumentException("Source wallet " + srcWalletId + " is pending deletion");
        }
        if (pendingDeletions.contains(dstWalletId)) {
            throw new IllegalArgumentException("Destination wallet " + dstWalletId + " is pending deletion");
        }
    }
}

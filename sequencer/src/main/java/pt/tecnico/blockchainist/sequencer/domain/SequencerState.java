package pt.tecnico.blockchainist.sequencer.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class SequencerState {

    private final int n;

    private final int t;

    private final List<List<SequencerTransaction>> blocks = new ArrayList<>();

    private final List<SequencerTransaction> pendingBuffer = new ArrayList<>();

    private final Map<String, Integer> requestToBlock = new HashMap<>();

    int nextBlockNumber = 1;

    private final Thread timerThread;

    public static final class BroadcastResult {
        private final int blockNumber;
        private final boolean deduplicated;     //Indica se a transação foi deduplicada (true) ou se é uma nova transação (false)
        private final boolean earlyAck;         //Indica se a transação foi aceite de forma antecipada (true) ou não (false) (Se é transfer ou não)

        public BroadcastResult(int blockNumber, boolean deduplicated, boolean earlyAck) {
            this.blockNumber = blockNumber;
            this.deduplicated = deduplicated;
            this.earlyAck = earlyAck;
        }

        public int getBlockNumber() { return blockNumber; }

        public boolean isDeduplicated() { return deduplicated; }

        public boolean isEarlyAck() { return earlyAck; }
    }

    public SequencerState(int N, int T) {
        this.n = N;
        this.t = T;

        timerThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                synchronized (this) {
                    try {
                        if (pendingBuffer.isEmpty()) {
                            wait();
                        } else {
                            long startTime = System.currentTimeMillis();
                            long timeToWait = t * 1000L;

                            while (timeToWait > 0 && pendingBuffer.size() < n && !pendingBuffer.isEmpty()) {
                                wait(timeToWait); 
                                
                                long elapsedTime = System.currentTimeMillis() - startTime;
                                timeToWait = (t * 1000L) - elapsedTime;
                            }

                            if (!pendingBuffer.isEmpty()) {
                                closeBlock();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        });

        timerThread.setDaemon(true);
        timerThread.start();
    }

    public synchronized BroadcastResult addTransaction(String requestId, SequencerTransaction transaction) {

        //Deduplicação de transação
        Integer existingBlock = requestToBlock.get(requestId);
        if (existingBlock != null) {
            return new BroadcastResult(existingBlock, true, false);
        }
    
        pendingBuffer.add(transaction);
        int assignedBlock = nextBlockNumber;
        requestToBlock.put(requestId, assignedBlock);

        if (pendingBuffer.size() >= n){
            closeBlock();
        }else{
            notifyAll();
        }
        
        boolean isTransfer = transaction.getType() == SequencerTransaction.Type.TRANSFER;    //filtrar que é transação
        return new BroadcastResult(assignedBlock, false, isTransfer);   //Informar se pode ou não fazer execução antecipada
    }

    public synchronized void closeBlock(){
        List<SequencerTransaction> newBlock = new ArrayList<>(pendingBuffer);
        blocks.add(newBlock);
        pendingBuffer.clear();
        nextBlockNumber++;

        notifyAll();
    }

    public synchronized List<SequencerTransaction>  getBlock(int blockNumber) {
        
        int i = blockNumber - 1; 

        while (i >= blocks.size() || i < 0) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for the Transaction " + blockNumber, e);
            }
        }
        return blocks.get(i);
    }

    public void shutdown() {
        timerThread.interrupt();
    }
  
}

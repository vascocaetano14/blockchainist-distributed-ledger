package pt.tecnico.blockchainist.client.grpc;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import pt.tecnico.blockchainist.contract.ReadBalanceResponse;

public class ResponseObserver<R> implements StreamObserver<R> {

    private final long commandNumber;
    private final AtomicInteger pendingCount;
    private final Object pendingLock;
    private final Object printLock;
    private final AtomicBoolean finished = new AtomicBoolean(false);

    public ResponseObserver(long commandNumber, AtomicInteger pendingCount, Object pendingLock, Object printLock) {
        this.commandNumber = commandNumber;
        this.pendingCount = pendingCount;
        this.pendingLock = pendingLock;
        this.printLock = printLock;
    }

    @Override
    public void onNext(R response) {
        synchronized (printLock) {
            //printLock garante que "OK N" e o saldo (se s) saem como bloco indivisível
            System.out.println("OK " + commandNumber);

            if (response instanceof ReadBalanceResponse) {
                System.out.println(((ReadBalanceResponse) response).getBalance());
            }
            //redesenha o prompt após output assincrono
            System.out.print("> ");
        }
    }

    @Override
    public void onError(Throwable t) {
        String description = extractErrorDescription(t);
        synchronized (printLock) {
            System.err.println("ERROR " + commandNumber + ": " + description);
            System.out.print("> ");
        }
        finishOnce();
    }
    @Override
    public void onCompleted() {
        finishOnce();
    }

    //decrementa pendingCount uma única vez
    private void finishOnce() {
        if (finished.compareAndSet(false, true)) {
            synchronized (pendingLock) {
                pendingCount.decrementAndGet();
                pendingLock.notifyAll();
            }
        }
    }

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

        return t.getMessage() != null ? t.getMessage() : "Unknown error";
    }
}

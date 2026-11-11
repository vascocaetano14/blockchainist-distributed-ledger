package pt.tecnico.blockchainist.node.domain;

import java.util.concurrent.atomic.AtomicLong;

public class Wallet {
    private final String walletId;
    private final String ownerId;
    private final AtomicLong balance;

    public Wallet(String walletId, String ownerId, long balance) {
        this.walletId = walletId;
        this.ownerId = ownerId;
        this.balance = new AtomicLong(balance);
    }

    public Wallet(String walletId, String ownerId) {
        this(walletId, ownerId, 0L);
    }

    public String getWalletId() {
        return walletId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public AtomicLong getBalance() {
        return balance;
    }

    public void addBalance(long amount) {
        this.balance.addAndGet(amount);
    }

    public void subtractBalance(long amount) {
        if(amount > this.balance.get()) {
            throw new IllegalArgumentException("Insufficient balance in wallet " + walletId);
        }
        this.balance.addAndGet(-amount);
    }
}

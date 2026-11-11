package pt.tecnico.blockchainist.sequencer.domain;

import pt.tecnico.blockchainist.contract.CreateWalletRequest;
import pt.tecnico.blockchainist.contract.DeleteWalletRequest;
import pt.tecnico.blockchainist.contract.TransferRequest;

public class SequencerTransaction {

    public enum Type { CREATE_WALLET, DELETE_WALLET, TRANSFER }

    private final Type type;
    private final String userId;
    private final String walletId;
    private final String srcUserId;
    private final String srcWalletId;
    private final String dstWalletId;
    private final long amount;

    //conversão de protobuf para domínio (entrada na fronteira gRPC)
    private SequencerTransaction(Type type, String userId, String walletId, String srcUserId, String srcWalletId, String dstWalletId, long amount) {
        this.type = type;
        this.userId = userId;
        this.walletId = walletId;
        this.srcUserId = srcUserId;
        this.srcWalletId = srcWalletId;
        this.dstWalletId = dstWalletId;
        this.amount = amount;
    }

    public static SequencerTransaction fromContract(pt.tecnico.blockchainist.contract.Transaction tx) {
        switch (tx.getOperationCase()) {
            case CREATE_WALLET:
                return new SequencerTransaction(Type.CREATE_WALLET, tx.getCreateWallet().getUserId(), tx.getCreateWallet().getWalletId(), null, null, null, 0);
            case DELETE_WALLET:
                return new SequencerTransaction(Type.DELETE_WALLET, tx.getDeleteWallet().getUserId(), tx.getDeleteWallet().getWalletId(), null, null, null, 0);
            case TRANSFER:
                return new SequencerTransaction(Type.TRANSFER, null, null, tx.getTransfer().getSrcUserId(), tx.getTransfer().getSrcWalletId(), tx.getTransfer().getDstWalletId(), tx.getTransfer().getValue());
            default:
                throw new IllegalArgumentException("Unknown transaction type: " + tx.getOperationCase());

        }
    }

    // conversão de domínio para protobuf (saída na fronteira gRPC)
    public pt.tecnico.blockchainist.contract.Transaction toContract() {
        pt.tecnico.blockchainist.contract.Transaction.Builder builder = pt.tecnico.blockchainist.contract.Transaction.newBuilder();
        switch (type) {
            case CREATE_WALLET:
                builder.setCreateWallet(CreateWalletRequest.newBuilder().setUserId(userId).setWalletId(walletId));
                break;
            case DELETE_WALLET:
                builder.setDeleteWallet(DeleteWalletRequest.newBuilder().setUserId(userId).setWalletId(walletId));
                break;
            case TRANSFER:
                builder.setTransfer(TransferRequest.newBuilder().setSrcUserId(srcUserId).setSrcWalletId(srcWalletId).setDstWalletId(dstWalletId).setValue(amount));
                break;
        }
        return builder.build(); 
    }

    public Type getType() { return type; }
    public String getUserId() { return userId; }
    public String getWalletId() { return walletId; }
    public String getSrcUserId() { return srcUserId; }
    public String getSrcWalletId() { return srcWalletId; }
    public String getDstWalletId() { return dstWalletId; }
    public Long getAmount() { return amount; }

    @Override
    public String toString() {
        return switch (type) {
            case CREATE_WALLET -> "createWallet(userId=" + userId + ", walletId=" + walletId + ")";
            case DELETE_WALLET -> "deleteWallet(userId=" + userId + ", walletId=" + walletId + ")";
            case TRANSFER -> "transfer(srcUserId=" + srcUserId + ", srcWalletId=" + srcWalletId + ", dstWalletId=" + dstWalletId + ", amount=" + amount + ")";
        };
    }
}

package pt.tecnico.blockchainist.node.domain;

//Classe que permite separação entre classe Transaction de contract e Transaction de domínio (protoc/gRPC vs Domain)
//Essencialmente permite conversão de classe Transaction gerada por protoc para classe domínio. 
//Único ponto de contacto com a classe contract

//Decopula lógica de transporte de dados(gRPC) de regras de negócio da aplicação(Domain)
//Lógica de transporte de dados vs lógica de processamento de dados

public class Transaction {
    
    public enum Type {
        CREATE_WALLET,
        DELETE_WALLET,
        TRANSFER
    }

    private final Type type;

    //atributos CREATE_WALLET e DELETE_WALLET
    private final String userId;
    private final String walletId;


    //atributos TRANSFER
    private final String srcUserId;
    private final String srcWalletId;
    private final String dstWalletId;
    private final Long amount;

    public static Transaction createWallet(String userId, String walletId) {
        return new Transaction(Type.CREATE_WALLET, userId, walletId, null, null, null, 0L);
    }

    public static Transaction deleteWallet(String userId, String walletId) {
        return new Transaction(Type.DELETE_WALLET, userId, walletId, null, null, null, 0L);
    }

    public static Transaction transfer(String srcUserId, String srcWalletId, String dstWalletId, Long amount) {
        return new Transaction(Type.TRANSFER, null, null, srcUserId, srcWalletId, dstWalletId, amount);
    }

    //conversão de contract.Transaction para domain.Transaction
    public static Transaction fromContractTransaction(pt.tecnico.blockchainist.contract.Transaction Tx) {
        switch (Tx.getOperationCase()) {
            case CREATE_WALLET:
                return createWallet(Tx.getCreateWallet().getUserId(), Tx.getCreateWallet().getWalletId());
            case DELETE_WALLET:
                return deleteWallet(Tx.getDeleteWallet().getUserId(), Tx.getDeleteWallet().getWalletId());
            case TRANSFER:
                return transfer(Tx.getTransfer().getSrcUserId(), Tx.getTransfer().getSrcWalletId(), Tx.getTransfer().getDstWalletId(), Tx.getTransfer().getValue());
            default:
                throw new IllegalArgumentException("Unknown transaction type: " + Tx.getOperationCase());
        }
    }

    private Transaction(Type type, String userId, String walletId, String srcUserId, String srcWalletId, String dstWalletId, Long amount) {
        this.type = type;
        this.userId = userId;
        this.walletId = walletId;
        this.srcUserId = srcUserId;
        this.srcWalletId = srcWalletId;
        this.dstWalletId = dstWalletId;
        this.amount = amount;
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
        return switch(type) {
            case CREATE_WALLET -> "createWallet(" + userId + ", " + walletId + ")";
            case DELETE_WALLET -> "deleteWallet(" + userId + ", " + walletId + ")";
            case TRANSFER -> "transfer(" + srcUserId + ", " + srcWalletId + ", " + dstWalletId + ", " + amount + ")";
        };
    }
}

package pt.tecnico.blockchainist.contract;

import java.io.InputStream;
import java.security.Signature;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;


public class CryptoUtils {
    
    private static final String ALGORITHM = "RSA";
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    //lê chave privada de um resource (PKCS8 DER)
    public static PrivateKey readPrivateKey(String resourcePath) throws Exception {
        try (InputStream is = CryptoUtils.class.getResourceAsStream(resourcePath)) {
            if (is == null) throw new IllegalArgumentException("Resource not found: " + resourcePath);
            byte[] keyBytes = is.readAllBytes();
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
            return KeyFactory.getInstance(ALGORITHM).generatePrivate(spec);
        }
    }

    //lê chave pública de um resource (X509 DER)
    public static PublicKey readPublicKey(String resourcePath) throws Exception {
        try (InputStream is = CryptoUtils.class.getResourceAsStream(resourcePath)) {
            if (is == null) throw new IllegalArgumentException("Resource not found: " + resourcePath);
            byte[] keyBytes = is.readAllBytes();
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            return KeyFactory.getInstance(ALGORITHM).generatePublic(spec);
        }
    }

    //assina dados com chave privada RSA (SHA256withRSA)
    public static byte[] sign(byte[] data, PrivateKey key) throws Exception {
        Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
        sig.initSign(key);
        sig.update(data);
        return sig.sign();
    }

    //verifica assinatura com chave pública RSA (SHA256withRSA)
    public static boolean verify(byte[] data, byte[] signature, PublicKey key) throws Exception {
        Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
        sig.initVerify(key);
        sig.update(data);
        return sig.verify(signature);
    }
}

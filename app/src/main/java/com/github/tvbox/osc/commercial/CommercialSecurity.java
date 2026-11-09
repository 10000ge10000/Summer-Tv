package com.github.tvbox.osc.commercial;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;


final class CommercialSecurity {
    private CommercialSecurity() {
    }

    static boolean verifySignature(String payloadJson, String signatureBase64, String publicKeyBase64) {
        if (payloadJson == null || signatureBase64 == null || signatureBase64.trim().isEmpty()) return false;
        if (publicKeyBase64 == null || publicKeyBase64.trim().isEmpty()) return false;
        try {
            byte[] keyBytes = Base64.decode(publicKeyBase64.trim(), Base64.DEFAULT);
            byte[] signatureBytes = Base64.decode(signatureBase64.trim(), Base64.DEFAULT);
            PublicKey publicKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(keyBytes));
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update(payloadJson.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(signatureBytes);
        } catch (Throwable th) {
            return false;
        }
    }
}

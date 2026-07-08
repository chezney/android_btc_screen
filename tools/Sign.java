import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Minimal replacement for the SDK's apksigner CLI, built on the apksig
 * library from Maven Central (the SDK build-tools are not downloadable in
 * this build environment).
 *
 * Usage: Sign <keystore.p12> <password> <alias> <in.apk> <out.apk>
 */
public final class Sign {
    public static void main(String[] args) throws Exception {
        String ksPath = args[0], password = args[1], alias = args[2];
        File inApk = new File(args[3]), outApk = new File(args[4]);

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(ksPath)) {
            ks.load(in, password.toCharArray());
        }
        PrivateKey key = (PrivateKey) ks.getKey(alias, password.toCharArray());
        List<X509Certificate> certs = new ArrayList<>();
        for (Certificate c : ks.getCertificateChain(alias)) {
            certs.add((X509Certificate) c);
        }

        ApkSigner.SignerConfig signer =
                new ApkSigner.SignerConfig.Builder(alias, key, certs).build();
        new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(inApk)
                .setOutputApk(outApk)
                // v1 needs JDK-internal PKCS7 APIs removed in modern JDKs;
                // v2 alone is valid for minSdk >= 24 (v2 exists since Android 7)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .build()
                .sign();

        ApkVerifier.Result result = new ApkVerifier.Builder(outApk).build().verify();
        System.out.println("verified=" + result.isVerified()
                + " v1=" + result.isVerifiedUsingV1Scheme()
                + " v2=" + result.isVerifiedUsingV2Scheme());
        if (!result.isVerified()) {
            for (ApkVerifier.IssueWithParams e : result.getErrors()) {
                System.out.println("ERROR: " + e);
            }
            System.exit(1);
        }
    }
}

package space.blockera.twofa.security;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.logging.Logger;

public class CryptoUtil {
    private final byte[] key; // может быть null -> PLAINTEXT режим
    private final Logger log;
    private static final SecureRandom RNG = new SecureRandom();

    public CryptoUtil(byte[] key, Logger log) { this.key = key; this.log = log; }

    public byte[] protect(String plaintext) {
        if (key == null) return ("PLA:" + plaintext).getBytes();
        try {
            byte[] iv = new byte[12]; RNG.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes());
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return ("ENC:" + Base64.getEncoder().encodeToString(out)).getBytes();
        } catch (Exception e) {
            log.warning("Шифрование не удалось, сохраню PLAINTEXT: " + e.getMessage());
            return ("PLA:" + plaintext).getBytes();
        }
    }

    public String reveal(byte[] stored) {
        String s = new String(stored);
        if (s.startsWith("PLA:")) return s.substring(4);
        if (s.startsWith("ENC:")) {
            if (key == null) throw new IllegalStateException("Нет ключа для расшифровки ENC-секрета");
            try {
                byte[] blob = Base64.getDecoder().decode(s.substring(4));
                byte[] iv = new byte[12];
                byte[] ct = new byte[blob.length - 12];
                System.arraycopy(blob, 0, iv, 0, 12);
                System.arraycopy(blob, 12, ct, 0, ct.length);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
                return new String(cipher.doFinal(ct));
            } catch (Exception e) {
                throw new IllegalStateException("Расшифровка не удалась: " + e.getMessage(), e);
            }
        }
        // для обратной совместимости: если префикса нет — считаем PLA
        return s;
    }
}
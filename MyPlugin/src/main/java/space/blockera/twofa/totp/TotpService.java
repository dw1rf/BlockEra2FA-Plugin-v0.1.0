package space.blockera.twofa.totp;

import org.apache.commons.codec.binary.Base32;
import org.bukkit.configuration.file.FileConfiguration;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;

public class TotpService {
    private final String issuer;
    private final int digits;
    private final int periodSeconds;
    private final int window;

    private static final SecureRandom RNG = new SecureRandom();

    public TotpService(FileConfiguration cfg) {
        this.issuer = cfg.getString("security.totp.issuer", "BlockEra");
        this.digits = cfg.getInt("security.totp.digits", 6);
        this.periodSeconds = cfg.getInt("security.totp.period_seconds", 30);
        this.window = cfg.getInt("security.totp.window_steps", 1);
    }

    public String generateBase32Secret() {
        byte[] buffer = new byte[20]; // 160 бит
        RNG.nextBytes(buffer);
        return new Base32().encodeAsString(buffer).replace("=", "");
    }

    public boolean verifyCode(String base32Secret, String code) {
        if (code == null || !code.matches("\\d{" + digits + "}")) return false;
        try {
            int provided = Integer.parseInt(code);
            long currentInterval = Instant.now().getEpochSecond() / periodSeconds;
            for (int i = -window; i <= window; i++) {
                int expected = generateCode(base32Secret, currentInterval + i);
                if (expected == provided) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private int generateCode(String base32Secret, long interval) throws Exception {
        byte[] keyBytes = new Base32().decode(base32Secret);
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "HmacSHA1");
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(keySpec);

        byte[] data = ByteBuffer.allocate(8).putLong(interval).array();
        byte[] hash = mac.doFinal(data);

        int offset = hash[hash.length - 1] & 0xF;
        int binary =
                ((hash[offset] & 0x7F) << 24) |
                ((hash[offset + 1] & 0xFF) << 16) |
                ((hash[offset + 2] & 0xFF) << 8) |
                (hash[offset + 3] & 0xFF);

        int otp = binary % (int) Math.pow(10, digits);
        return otp;
    }

    public String buildOtpAuthUri(String accountName, String base32Secret) {
        String label = url(issuer + ":" + accountName);
        String secret = url(base32Secret);
        String iss = url(issuer);
        return String.format(Locale.ROOT,
                "otpauth://totp/%s?secret=%s&issuer=%s&digits=%d&period=%d",
                label, secret, iss, digits, periodSeconds);
    }

    public String buildQrLink(String otpauth, String template) {
        return String.format(template, url(otpauth));
    }

    private static String url(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}

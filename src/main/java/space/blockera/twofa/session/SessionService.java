package space.blockera.twofa.session;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SessionService {
    private final int expireMinutes;
    private final Map<UUID, Instant> verifiedUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> pending = new ConcurrentHashMap<>();

    public SessionService(int expireMinutes) { this.expireMinutes = expireMinutes; }

    public void markPending(UUID uuid) { pending.put(uuid, true); }
    public void clearPending(UUID uuid) { pending.remove(uuid); }
    public boolean isPending(UUID uuid) { return pending.containsKey(uuid); }

    public void markVerified(UUID uuid) {
        verifiedUntil.put(uuid, Instant.now().plusSeconds(expireMinutes * 60L));
        clearPending(uuid);
    }

    public boolean isVerified(UUID uuid) {
        Instant until = verifiedUntil.get(uuid);
        if (until == null) return false;
        if (Instant.now().isAfter(until)) {
            verifiedUntil.remove(uuid);
            return false;
        }
        return true;
    }

    public void clear(UUID uuid) {
        verifiedUntil.remove(uuid);
        pending.remove(uuid);
    }
}
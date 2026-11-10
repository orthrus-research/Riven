package cc.orthrus.riven;

import cc.orthrus.riven.worldgen.RegionalStore;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Per-construction ownership, independent of loaders and host resources. Callers execute returned
 * cleanup outside this registry. Factory setup and its selected host abandonment remain serialized
 * by the server lifecycle; a cleanup decision is not a lease against concurrent new factories.
 */
final class RegionalOwnership {
    record Attachment(boolean accepted, RegionalStore unowned) {}

    record FailureCleanup(RegionalStore store, boolean abandonPool) {
        private static final FailureCleanup NONE = new FailureCleanup(null, false);
    }

    private enum Phase {
        PENDING,
        LIVE,
        RELEASED
    }

    /** An identity nonce; old tokens never keep an old server alive. */
    private static final class Epoch {}

    private static final class Group {
        final Epoch epoch;
        final WeakReference<Object> world;
        final IdentityHashMap<Token, Boolean> tokens = new IdentityHashMap<>();
        boolean open = true;

        Group(Epoch epoch, Object world) {
            this.epoch = epoch;
            this.world = new WeakReference<>(world);
        }
    }

    static final class Token {
        private final RegionalOwnership owner;
        private final Group group;
        private Phase phase = Phase.PENDING;
        private RegionalStore store;
        private boolean attached;

        private Token(RegionalOwnership owner, Group group) {
            this.owner = owner;
            this.group = group;
        }
    }

    private final Object lock = new Object();
    private final IdentityHashMap<Object, Group> worlds = new IdentityHashMap<>();
    private final List<WeakReference<Object>> closed = new ArrayList<>();
    private final List<WeakReference<RegionalStore>> transferred = new ArrayList<>();
    private Object server;
    private Epoch epoch;

    /** Repeated notification for the same active server is harmless; replacement needs a stop. */
    void start(Object server) {
        Objects.requireNonNull(server);
        synchronized (lock) {
            seen(closed, null);
            seen(transferred, null);
            if (this.server == server) return;
            if (this.server != null) throw new IllegalStateException("Another server is active");
            this.server = server;
            epoch = new Epoch();
        }
    }

    Token claim(Object server, Object world) {
        Objects.requireNonNull(server);
        Objects.requireNonNull(world);
        synchronized (lock) {
            if (this.server != server || epoch == null)
                throw new IllegalStateException("Server is not active");
            if (seen(closed, world)) throw new IllegalStateException("World is closed");
            Group group = worlds.computeIfAbsent(world, key -> new Group(epoch, key));
            Token token = new Token(this, group);
            group.tokens.put(token, Boolean.TRUE);
            return token;
        }
    }

    /**
     * Transfers each fresh handle either to a token or once back to its caller for disposal.
     * Later attempts cannot reuse it or obtain a second cleanup, even after a token was released.
     */
    Attachment attach(Token token, RegionalStore store) {
        own(token);
        Objects.requireNonNull(store);
        synchronized (lock) {
            boolean alreadyOwned = seen(transferred, store);
            if (alreadyOwned) return new Attachment(false, null);
            transferred.add(new WeakReference<>(store));
            if (current(token) && token.phase == Phase.PENDING && !token.attached) {
                token.store = store;
                token.attached = true;
                return new Attachment(true, null);
            }
            return new Attachment(false, store);
        }
    }

    /** A memory-only construction completes without an attachment and still protects its group. */
    boolean complete(Token token) {
        own(token);
        synchronized (lock) {
            if (!current(token) || token.phase != Phase.PENDING) return false;
            token.phase = Phase.LIVE;
            return true;
        }
    }

    /** A failed request already notified its host; only a successfully returned request may abandon. */
    FailureCleanup fail(Token token, boolean requestReturned) {
        own(token);
        synchronized (lock) {
            if (token.phase == Phase.RELEASED) return FailureCleanup.NONE;
            boolean active = current(token);
            Group group = token.group;
            token.phase = Phase.RELEASED;
            group.tokens.remove(token);
            RegionalStore store = take(token);
            Object world = group.world.get();
            boolean last =
                    active && group.tokens.isEmpty() && world != null && worlds.get(world) == group;
            if (last) worlds.remove(world);
            return new FailureCleanup(store, requestReturned && last);
        }
    }

    /** Even an unload before a first claim terminalizes that exact World identity. */
    List<RegionalStore> unload(Object world) {
        Objects.requireNonNull(world);
        synchronized (lock) {
            remember(world);
            Group group = worlds.remove(world);
            return group == null ? List.of() : release(group);
        }
    }

    /** A late event from an old server cannot disable or close the next server's owners. */
    List<RegionalStore> stop(Object server) {
        Objects.requireNonNull(server);
        synchronized (lock) {
            if (this.server != server) return List.of();
            this.server = null;
            epoch = null;
            List<RegionalStore> detached = new ArrayList<>();
            for (var entry : worlds.entrySet()) {
                remember(entry.getKey());
                detached.addAll(release(entry.getValue()));
            }
            worlds.clear();
            return List.copyOf(detached);
        }
    }

    private void own(Token token) {
        if (token == null || token.owner != this)
            throw new IllegalArgumentException("Token belongs to another registry");
    }

    private boolean current(Token token) {
        return token.phase != Phase.RELEASED
                && token.group.open
                && token.group.epoch == epoch
                && token.group.tokens.containsKey(token);
    }

    private RegionalStore take(Token token) {
        RegionalStore store = token.store;
        token.store = null;
        return store;
    }

    private List<RegionalStore> release(Group group) {
        group.open = false;
        List<RegionalStore> detached = new ArrayList<>();
        for (Token token : group.tokens.keySet()) {
            token.phase = Phase.RELEASED;
            RegionalStore store = take(token);
            if (store != null) detached.add(store);
        }
        group.tokens.clear();
        return List.copyOf(detached);
    }

    /** Called only under lock; weak markers never invoke referent equality or retain dead values. */
    private static <T> boolean seen(List<WeakReference<T>> markers, T value) {
        boolean found = false;
        for (var iterator = markers.iterator(); iterator.hasNext(); ) {
            T identity = iterator.next().get();
            if (identity == null) iterator.remove();
            else if (identity == value) found = true;
        }
        return found;
    }

    private void remember(Object world) {
        if (!seen(closed, world)) closed.add(new WeakReference<>(world));
    }
}

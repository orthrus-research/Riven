package cc.orthrus.riven;

import cc.orthrus.riven.worldgen.RegionalData;
import cc.orthrus.riven.worldgen.RegionalIdentity;
import cc.orthrus.riven.worldgen.RegionalStore;
import cc.orthrus.riven.worldgen.WorldScale;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Offline ownership transitions with independent handles sharing genuine derived-file entries. */
public final class RegionalOwnershipCheck {
    private static final RegionalStore.Codec<Integer> CODEC =
            new RegionalStore.Codec<>() {
                @Override
                public int version() {
                    return 1;
                }

                @Override
                public void write(RegionalData.Writer out, Integer value) {
                    out.integer(value);
                }

                @Override
                public Integer read(RegionalData.Reader in) throws RegionalData.Malformed {
                    return in.integer();
                }
            };

    private static final class EqualIdentity {
        @Override
        public boolean equals(Object other) {
            return other instanceof EqualIdentity;
        }

        @Override
        public int hashCode() {
            return 0;
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Path root;
        final RegionalIdentity identity = RegionalIdentity.capture(8675309, WorldScale.DEFAULT);
        final List<RegionalStore> created = new ArrayList<>();

        Fixture(Path root) {
            this.root = root;
        }

        RegionalStore store() {
            RegionalStore store = new RegionalStore(root, identity);
            created.add(store);
            open(store);
            return store;
        }

        @Override
        public void close() {
            for (RegionalStore store : created) store.close();
        }
    }

    private RegionalOwnershipCheck() {}

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void open(RegionalStore store) {
        require(store.put("ownership", 0, 0, 17, CODEC), "Store was closed or unavailable");
        require(
                Integer.valueOf(17).equals(store.get("ownership", 0, 0, CODEC)),
                "Open store lost entry");
    }

    private static void closed(RegionalStore store) {
        require(!store.put("ownership", 0, 0, 17, CODEC), "Closed store accepted a write");
        require(store.get("ownership", 0, 0, CODEC) == null, "Closed store returned data");
    }

    private static void claimRejected(RegionalOwnership registry, Object server, Object world) {
        try {
            registry.claim(server, world);
            throw new AssertionError("Terminal identity accepted a claim");
        } catch (IllegalStateException expected) {
            // Resource setup has not started at a rejected claim.
        }
    }

    private static void attached(
            RegionalOwnership registry, RegionalOwnership.Token token, RegionalStore store) {
        RegionalOwnership.Attachment result = registry.attach(token, store);
        require(result.accepted() && result.unowned() == null, "Fresh attachment rejected");
    }

    private static void rejected(
            RegionalOwnership registry,
            RegionalOwnership.Token token,
            RegionalStore incoming,
            RegionalStore unowned) {
        RegionalOwnership.Attachment result = registry.attach(token, incoming);
        require(
                !result.accepted() && result.unowned() == unowned,
                "Rejected attachment changed ownership");
        if (unowned != null) {
            open(unowned);
            unowned.close();
            closed(unowned);
        }
    }

    private static void failure(
            RegionalOwnership registry,
            RegionalOwnership.Token token,
            boolean requestReturned,
            RegionalStore store,
            boolean abandon) {
        RegionalOwnership.FailureCleanup cleanup = registry.fail(token, requestReturned);
        require(
                cleanup.store() == store && cleanup.abandonPool() == abandon,
                "Wrong failure cleanup");
        if (store != null) {
            open(store); // Decisions do not close anything under the registry's lock.
            store.close();
            closed(store);
        }
    }

    private static void closeBatch(List<RegionalStore> actual, RegionalStore... expected) {
        require(actual.size() == expected.length, "Wrong terminal cleanup size");
        for (RegionalStore store : expected) {
            require(
                    actual.stream().filter(found -> found == store).count() == 1,
                    "Store detached twice or lost");
            open(store);
        }
        for (RegionalStore store : actual) store.close();
        for (RegionalStore store : expected) closed(store);
    }

    private static void identities(Fixture f) {
        Object server = new EqualIdentity(), otherServer = new EqualIdentity();
        Object world = new EqualIdentity(), otherWorld = new EqualIdentity();
        RegionalOwnership registry = new RegionalOwnership();
        registry.start(server);
        registry.start(server);
        RegionalOwnership.Token a = registry.claim(server, world),
                b = registry.claim(server, otherWorld);
        RegionalStore first = f.store(), second = f.store();
        attached(registry, a, first);
        attached(registry, b, second);
        require(registry.complete(a) && registry.complete(b), "Independent completion failed");
        failure(registry, a, true, first, true);
        open(second);
        a = registry.claim(server, world);
        first = f.store();
        attached(registry, a, first);
        require(registry.complete(a), "Ordinary failure blocked an independent world");
        claimRejected(registry, otherServer, new Object());
        require(registry.stop(otherServer).isEmpty(), "Foreign server closed an owner");
        try {
            registry.start(otherServer);
            throw new AssertionError("Active epoch was silently replaced");
        } catch (IllegalStateException expected) {
            open(first);
            open(second);
        }
        List<RegionalStore> detached = registry.unload(world);
        claimRejected(registry, server, world);
        closeBatch(detached, first);
        open(second);
        require(registry.unload(world).isEmpty(), "Repeated unload detached twice");
        Object unseen = new Object();
        require(registry.unload(unseen).isEmpty(), "Unseen unload invented a resource");
        claimRejected(registry, server, unseen);
        detached = registry.stop(server);
        claimRejected(registry, server, new Object());
        closeBatch(detached, second);
        failure(registry, a, true, null, false);
        failure(registry, b, true, null, false);
        registry.start(otherServer);
        claimRejected(registry, otherServer, world);
        RegionalOwnership.Token fresh = registry.claim(otherServer, new Object());
        require(registry.complete(fresh), "New epoch did not accept a fresh world");
        require(registry.stop(server).isEmpty(), "Late stop disabled the new epoch");
        require(
                registry.complete(registry.claim(otherServer, new Object())),
                "Late stop blocked claims");
        closeBatch(registry.stop(otherServer));
        require(registry.stop(otherServer).isEmpty(), "Repeated stop detached twice");
        require(!registry.complete(fresh), "Old token completed in a stopped epoch");
    }

    private static void duplicates(Fixture f) {
        Object server = new Object(), world = new Object();
        RegionalOwnership registry = new RegionalOwnership();
        registry.start(server);
        RegionalOwnership.Token a = registry.claim(server, world),
                b = registry.claim(server, world);
        RegionalStore first = f.store(), second = f.store();
        attached(registry, a, first);
        attached(registry, b, second);
        failure(registry, a, true, first, false);
        open(second);
        require(registry.complete(b), "Other pending owner was lost");
        RegionalOwnership.Token c = registry.claim(server, world);
        RegionalStore third = f.store();
        attached(registry, c, third);
        failure(registry, c, false, third, false);
        open(second);
        failure(registry, c, true, null, false);
        RegionalOwnership.Token d = registry.claim(server, world);
        RegionalStore fourth = f.store();
        attached(registry, d, fourth);
        failure(registry, b, true, second, false);
        open(fourth);
        require(registry.complete(d), "Later pending owner was lost");
        failure(registry, d, true, fourth, true);
        failure(registry, b, true, null, false);
        RegionalOwnership.Token replacement = registry.claim(server, world);
        RegionalStore next = f.store();
        attached(registry, replacement, next);
        require(registry.complete(replacement), "Ordinary failure terminalized the world");
        RegionalStore late = f.store();
        rejected(registry, a, late, late);
        require(!registry.complete(a), "Released token completed against a replacement group");
        failure(registry, a, true, null, false);
        open(next);
        closeBatch(registry.stop(server), next);
    }

    private static void requestOutcomes(Fixture f) {
        Object server = new Object(), world = new Object();
        RegionalOwnership registry = new RegionalOwnership();
        registry.start(server);
        RegionalOwnership.Token waiting = registry.claim(server, world);
        RegionalOwnership.Token reentrant = registry.claim(server, world);
        failure(registry, reentrant, true, null, false);
        failure(registry, waiting, true, null, true);
        RegionalOwnership.Token failedRequest = registry.claim(server, world);
        RegionalStore store = f.store();
        attached(registry, failedRequest, store);
        failure(registry, failedRequest, false, store, false);
        failure(registry, failedRequest, true, null, false);
        RegionalOwnership.Token memory = registry.claim(server, world);
        require(registry.complete(memory), "Memory-only completion failed");
        RegionalOwnership.Token failedDuplicate = registry.claim(server, world);
        store = f.store();
        attached(registry, failedDuplicate, store);
        failure(registry, failedDuplicate, true, store, false);
        failure(registry, memory, true, null, true);
        failure(registry, memory, true, null, false);
        RegionalOwnership.Token returnedNullPool = registry.claim(server, world);
        failure(registry, returnedNullPool, true, null, true);
        closeBatch(registry.stop(server));
    }

    private static void terminal(Fixture f) {
        Object server = new Object(), world = new Object();
        RegionalOwnership registry = new RegionalOwnership();
        registry.start(server);
        RegionalOwnership.Token pending = registry.claim(server, world),
                live = registry.claim(server, world);
        RegionalStore first = f.store(), second = f.store();
        attached(registry, pending, first);
        attached(registry, live, second);
        require(registry.complete(live), "Live setup failed");
        List<RegionalStore> detached = registry.unload(world);
        RegionalOwnership.Token otherWorld = registry.claim(server, new Object());
        rejected(registry, otherWorld, first, null);
        RegionalStore independent = f.store();
        attached(registry, otherWorld, independent);
        open(first);
        closeBatch(detached, first, second);
        open(independent);
        require(
                !registry.complete(pending) && !registry.complete(live),
                "Late completion reopened a world");
        failure(registry, pending, true, null, false);
        failure(registry, live, true, null, false);
        rejected(registry, pending, first, null);
        RegionalStore late = f.store();
        rejected(registry, pending, late, late);
        claimRejected(registry, server, world);
        RegionalOwnership.Token beforeAttach = registry.claim(server, new Object());
        closeBatch(registry.stop(server), independent);
        late = f.store();
        rejected(registry, beforeAttach, late, late);
        require(!registry.complete(beforeAttach), "Stopped pending token completed");
        failure(registry, beforeAttach, true, null, false);
        Object nextServer = new Object();
        registry.start(nextServer);
        RegionalOwnership.Token nextEpoch = registry.claim(nextServer, new Object());
        rejected(registry, nextEpoch, first, null);
        late = f.store();
        rejected(registry, beforeAttach, late, late);
        require(!registry.complete(beforeAttach), "Old token completed in a later epoch");
        closeBatch(registry.stop(nextServer));
    }

    private static void attachments(Fixture f) {
        Object server = new Object(), world = new Object();
        RegionalOwnership registry = new RegionalOwnership();
        registry.start(server);
        RegionalOwnership.Token a = registry.claim(server, world),
                b = registry.claim(server, world);
        RegionalStore first = f.store(), second = f.store();
        attached(registry, a, first);
        rejected(registry, a, first, null);
        rejected(registry, b, first, null);
        rejected(registry, a, second, second);
        open(first);
        require(
                registry.complete(a) && registry.complete(b),
                "Attached/memory-only completion failed");
        RegionalStore late = f.store();
        rejected(registry, a, late, late);
        open(first);
        RegionalOwnership.FailureCleanup detached = registry.fail(a, true);
        require(
                detached.store() == first && !detached.abandonPool(),
                "Wrong delayed failure cleanup");
        RegionalOwnership.Token otherWorld = registry.claim(server, new Object());
        rejected(registry, otherWorld, first, null);
        RegionalStore independent = f.store();
        attached(registry, otherWorld, independent);
        open(first);
        detached.store().close();
        closed(first);
        open(independent);
        rejected(registry, a, first, null);
        failure(registry, b, true, null, true);
        closeBatch(registry.stop(server), independent);
    }

    private static void foreignTokens(Fixture f) {
        Object server = new Object(), world = new Object();
        RegionalOwnership first = new RegionalOwnership(), second = new RegionalOwnership();
        first.start(server);
        second.start(server);
        RegionalOwnership.Token a = first.claim(server, world), b = second.claim(server, world);
        RegionalStore owned = f.store(), other = f.store(), incoming = f.store();
        attached(first, a, owned);
        attached(second, b, other);
        for (Runnable wrong :
                List.<Runnable>of(
                        () -> second.attach(a, incoming),
                        () -> second.complete(a),
                        () -> second.fail(a, true))) {
            try {
                wrong.run();
                throw new AssertionError("Foreign token was accepted");
            } catch (IllegalArgumentException expected) {
                open(owned);
                open(other);
                open(incoming);
            }
        }
        incoming.close();
        closeBatch(first.stop(server), owned);
        open(other);
        closeBatch(second.stop(server), other);
    }

    private static void rejectedDisposal(Fixture f) {
        for (boolean unload : new boolean[] {false, true}) {
            Object server = new Object(), world = new Object();
            RegionalOwnership registry = new RegionalOwnership();
            registry.start(server);
            RegionalOwnership.Token a = registry.claim(server, world);
            require(registry.complete(a), "Disposal setup failed");
            if (unload) closeBatch(registry.unload(world));
            RegionalStore candidate = f.store();
            RegionalOwnership.Attachment disposal = registry.attach(a, candidate);
            require(
                    !disposal.accepted() && disposal.unowned() == candidate,
                    "Disposal was not transferred");
            RegionalOwnership.Token b = registry.claim(server, new Object());
            rejected(registry, b, candidate, null);
            rejected(registry, a, candidate, null);
            RegionalStore independent = f.store();
            attached(registry, b, independent);
            require(registry.complete(b), "Disposal blocked a fresh independent store");
            open(candidate);
            disposal.unowned().close();
            closed(candidate);
            open(independent);
            closeBatch(registry.stop(server), independent);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1)
            throw new IllegalArgumentException("Give a parent directory for fixtures");
        Path parent = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(parent);
        Path root = Files.createTempDirectory(parent, "regional-ownership-");
        require(root.toRealPath().startsWith(parent.toRealPath()), "Fixture escaped parent");
        try (Fixture fixture = new Fixture(root)) {
            identities(fixture);
            duplicates(fixture);
            requestOutcomes(fixture);
            terminal(fixture);
            attachments(fixture);
            foreignTokens(fixture);
            rejectedDisposal(fixture);
            System.out.println(
                    "Regional ownership identity, token, failure and epoch checks passed");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }
}

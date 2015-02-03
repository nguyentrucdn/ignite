/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache;

import org.apache.ignite.internal.processors.cache.version.*;
import org.apache.ignite.internal.util.*;
import org.apache.ignite.internal.util.tostring.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.apache.ignite.internal.processors.cache.GridCacheMvccCandidate.Mask.*;

/**
 * Lock candidate.
 */
public class GridCacheMvccCandidate<K> implements Externalizable,
    Comparable<GridCacheMvccCandidate<K>> {
    /** */
    private static final long serialVersionUID = 0L;

    /** ID generator. */
    private static final AtomicLong IDGEN = new AtomicLong();

    /** Locking node ID. */
    @GridToStringInclude
    private UUID nodeId;

    /** Lock version. */
    @GridToStringInclude
    private GridCacheVersion ver;

    /** Maximum wait time. */
    @GridToStringInclude
    private long timeout;

    /** Candidate timestamp. */
    @GridToStringInclude
    private long ts;

    /** Thread ID. */
    @GridToStringInclude
    private long threadId;

    /** Use flags approach to preserve space. */
    @GridToStringExclude
    private short flags;

    /** ID. */
    private long id;

    /** Topology version. */
    @SuppressWarnings( {"TransientFieldNotInitialized"})
    @GridToStringInclude
    private transient volatile long topVer = -1;

    /** Linked reentry. */
    private GridCacheMvccCandidate<K> reentry;

    /** Previous lock for the thread. */
    @GridToStringExclude
    private transient volatile GridCacheMvccCandidate<K> prev;

    /** Next lock for the thread. */
    @GridToStringExclude
    private transient volatile GridCacheMvccCandidate<K> next;

    /** Parent entry. */
    @GridToStringExclude
    private transient GridCacheEntryEx<K, ?> parent;

    /** Alternate node ID specifying additional node involved in this lock. */
    private transient volatile UUID otherNodeId;

    /** Other lock version (near version vs dht version). */
    private transient GridCacheVersion otherVer;

    /** Mapped node IDS. */
    @GridToStringInclude
    private transient volatile Collection<UUID> mappedNodeIds;

    /** Owned lock version by the moment this candidate was added. */
    @GridToStringInclude
    private transient volatile GridCacheVersion ownerVer;

    /**
     * Empty constructor required by {@link Externalizable}.
     */
    public GridCacheMvccCandidate() {
        /* No-op. */
    }

    /**
     * @param parent Parent entry.
     * @param nodeId Requesting node ID.
     * @param otherNodeId Near node ID.
     * @param otherVer Other version.
     * @param threadId Requesting thread ID.
     * @param ver Cache version.
     * @param timeout Maximum wait time.
     * @param loc {@code True} if the lock is local.
     * @param reentry {@code True} if candidate is for reentry.
     * @param tx Transaction flag.
     * @param singleImplicit Single-key-implicit-transaction flag.
     * @param nearLoc Near-local flag.
     * @param dhtLoc DHT local flag.
     */
    public GridCacheMvccCandidate(
        GridCacheEntryEx<K, ?> parent,
        UUID nodeId,
        @Nullable UUID otherNodeId,
        @Nullable GridCacheVersion otherVer,
        long threadId,
        GridCacheVersion ver,
        long timeout,
        boolean loc,
        boolean reentry,
        boolean tx,
        boolean singleImplicit,
        boolean nearLoc,
        boolean dhtLoc) {
        assert nodeId != null;
        assert ver != null;
        assert parent != null;

        this.parent = parent;
        this.nodeId = nodeId;
        this.otherNodeId = otherNodeId;
        this.otherVer = otherVer;
        this.threadId = threadId;
        this.ver = ver;
        this.timeout = timeout;

        mask(LOCAL, loc);
        mask(REENTRY, reentry);
        mask(TX, tx);
        mask(SINGLE_IMPLICIT, singleImplicit);
        mask(NEAR_LOCAL, nearLoc);
        mask(DHT_LOCAL, dhtLoc);

        ts = U.currentTimeMillis();

        id = IDGEN.incrementAndGet();
    }

    /**
     * Sets mask value.
     *
     * @param mask Mask.
     * @param on Flag.
     */
    private void mask(Mask mask, boolean on) {
        flags = mask.set(flags, on);
    }

    /**
     * @return Flags.
     */
    public short flags() {
        return flags;
    }

    /**
     * @return Parent entry.
     */
    @SuppressWarnings({"unchecked"})
    public <V> GridCacheEntryEx<K, V> parent() {
        return (GridCacheEntryEx<K, V>)parent;
    }

    /**
     * @return Topology for which this lock was acquired.
     */
    public long topologyVersion() {
        return topVer;
    }

    /**
     * @param topVer Topology version.
     */
    public void topologyVersion(long topVer) {
        this.topVer = topVer;
    }

    /**
     * @return Reentry candidate.
     */
    public GridCacheMvccCandidate<K> reenter() {
        GridCacheMvccCandidate<K> old = reentry;

        GridCacheMvccCandidate<K> reentry = new GridCacheMvccCandidate<>(
            parent,
            nodeId,
            otherNodeId,
            otherVer,
            threadId,
            ver,
            timeout,
            local(),
            /*reentry*/true,
            tx(),
            singleImplicit(),
            nearLocal(),
            dhtLocal());

        reentry.topVer = topVer;

        if (old != null)
            reentry.reentry = old;

        this.reentry = reentry;

        return reentry;
    }

    /**
     * @return {@code True} if has reentry.
     */
    public boolean hasReentry() {
        return reentry != null;
    }

    /**
     * @return Removed reentry candidate or {@code null}.
     */
    @Nullable public GridCacheMvccCandidate<K> unenter() {
        if (reentry != null) {
            GridCacheMvccCandidate<K> old = reentry;

            // Link to next.
            reentry = reentry.reentry;

            return old;
        }

        return null;
    }

    /**
     * @param parent Sets locks parent entry.
     */
    public void parent(GridCacheEntryEx<K, ?> parent) {
        assert parent != null;

        this.parent = parent;
    }

    /**
     * @return Node ID.
     */
    public UUID nodeId() {
        return nodeId;
    }

    /**
     * @return Near or DHT node ID.
     */
    public UUID otherNodeId() {
        return otherNodeId;
    }

    /**
     * @param otherNodeId Near or DHT node ID.
     */
    public void otherNodeId(UUID otherNodeId) {
        this.otherNodeId = otherNodeId;
    }

    /**
     * @return Mapped node IDs.
     */
    public Collection<UUID> mappedNodeIds() {
        return mappedNodeIds;
    }

    /**
     * @param mappedNodeIds Mapped node IDs.
     */
    public void mappedNodeIds(Collection<UUID> mappedNodeIds) {
        this.mappedNodeIds = mappedNodeIds;
    }

    /**
     * @return Near version.
     */
    public GridCacheVersion otherVersion() {
        return otherVer;
    }

    /**
     * Sets mapped version for candidate. For dht local candidates {@code otherVer} is near local candidate version.
     * For near local candidates {@code otherVer} is dht mapped candidate version.
     *
     * @param otherVer Alternative candidate version.
     * @return {@code True} if other version was set, {@code false} if other version is already set.
     */
    public boolean otherVersion(GridCacheVersion otherVer) {
        assert otherVer != null;

        if (this.otherVer == null) {
            this.otherVer = otherVer;

            return true;
        }

        return this.otherVer.equals(otherVer);
    }

    /**
     * Sets owned version for proper lock ordering when remote candidate is added.
     *
     * @param ownerVer Version of owned candidate by the moment this candidate was added.
     * @return {@code True} if owned version was set, {@code false} otherwise.
     */
    public boolean ownerVersion(GridCacheVersion ownerVer) {
        assert ownerVer != null;

        if (this.ownerVer == null) {
            this.ownerVer = ownerVer;

            return true;
        }

        return this.ownerVer.equals(ownerVer);
    }

    /**
     * @return Version of owned candidate by the time this candidate was added, or {@code null}
     *      if there were no owned candidates.
     */
    @Nullable public GridCacheVersion ownerVersion() {
        return ownerVer;
    }

    /**
     * @return Thread ID.
     * @see Thread#getId()
     */
    public long threadId() {
        return threadId;
    }

    /**
     * @return Lock version.
     */
    public GridCacheVersion version() {
        return ver;
    }

    /**
     * @return Maximum wait time.
     */
    public long timeout() {
        return timeout;
    }

    /**
     * @return Timestamp at the time of entering pending set.
     */
    public long timestamp() {
        return ts;
    }

    /**
     * @return {@code True} if lock is local.
     */
    public boolean local() {
        return LOCAL.get(flags());
    }

    /**
     * @return {@code True} if transaction flag is set.
     */
    public boolean tx() {
        return TX.get(flags());
    }

    /**
     * @return {@code True} if implicit transaction.
     */
    public boolean singleImplicit() {
        return SINGLE_IMPLICIT.get(flags());
    }

    /**
     * @return Near local flag.
     */
    public boolean nearLocal() {
        return NEAR_LOCAL.get(flags());
    }

    /**
     * @return Near local flag.
     */
    public boolean dhtLocal() {
        return DHT_LOCAL.get(flags());
    }

    /**
     * @return {@code True} if this candidate is a reentry.
     */
    public boolean reentry() {
        return REENTRY.get(flags());
    }

    /**
     * Sets reentry flag.
     */
    public void setReentry() {
        mask(REENTRY, true);
    }

    /**
     * @return Ready flag.
     */
    public boolean ready() {
        return READY.get(flags());
    }

    /**
     * Sets ready flag.
     */
    public void setReady() {
        mask(READY, true);
    }

    /**
     * @return {@code True} if lock was released.
     */
    public boolean used() {
        return USED.get(flags());
    }

    /**
     * Sets used flag.
     */
    public void setUsed() {
        mask(USED, true);
    }

    /**
     * @return Removed flag.
     */
    public boolean removed() {
        return REMOVED.get(flags());
    }

    /**
     * Sets removed flag.
     */
    public void setRemoved() {
        mask(REMOVED, true);
    }

    /**
     * @return {@code True} if is or was an owner.
     */
    public boolean owner() {
        return OWNER.get(flags());
    }

    /**
     * Sets owner flag.
     */
    public void setOwner() {
        mask(OWNER, true);
    }

    /**
     * @return Lock that comes before in the same thread, possibly <tt>null</tt>.
     */
    public GridCacheMvccCandidate<K> previous() {
        return prev;
    }

    /**
     * @param prev Lock that comes before in the same thread, possibly <tt>null</tt>.
     */
    public void previous(GridCacheMvccCandidate<K> prev) {
        this.prev = prev;
    }

    /**
     *
     * @return Gets next candidate in this thread.
     */
    public GridCacheMvccCandidate<K> next() {
        return next;
    }

    /**
     * @param next Next candidate in this thread.
     */
    public void next(GridCacheMvccCandidate<K> next) {
        this.next = next;
    }

    /**
     * @return Key.
     */
    public K key() {
        GridCacheEntryEx<K, ?> parent0 = parent;

        if (parent0 == null)
            throw new IllegalStateException("Parent entry was not initialized for MVCC candidate: " + this);

        return parent0.key();
    }

    /**
     * Checks if this candidate matches version or thread-nodeId combination.
     *
     * @param nodeId Node ID to check.
     * @param ver Version to check.
     * @param threadId Thread ID to check.
     * @return {@code True} if matched.
     */
    public boolean matches(GridCacheVersion ver, UUID nodeId, long threadId) {
        return ver.equals(this.ver) || (nodeId.equals(this.nodeId) && threadId == this.threadId);
    }

    /** {@inheritDoc} */
    @Override public void writeExternal(ObjectOutput out) throws IOException {
        IgniteUtils.writeUuid(out, nodeId);

        CU.writeVersion(out, ver);

        out.writeLong(timeout);
        out.writeLong(threadId);
        out.writeLong(id);
        out.writeShort(flags());
    }

    /** {@inheritDoc} */
    @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        nodeId = IgniteUtils.readUuid(in);

        ver = CU.readVersion(in);

        timeout = in.readLong();
        threadId = in.readLong();
        id = in.readLong();

        short flags = in.readShort();

        mask(OWNER, OWNER.get(flags));
        mask(USED, USED.get(flags));
        mask(TX, TX.get(flags));

        ts = U.currentTimeMillis();
    }

    /** {@inheritDoc} */
    @Override public int compareTo(GridCacheMvccCandidate<K> o) {
        if (o == this)
            return 0;

        int c = ver.compareTo(o.ver);

        // This is done, so compare and equals methods will be consistent.
        if (c == 0)
            return key().equals(o.key()) ? 0 : id < o.id ? -1 : 1;

        return c;
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"unchecked"})
    @Override public boolean equals(Object o) {
        if (o == null)
            return false;

        if (o == this)
            return true;

        GridCacheMvccCandidate<K> other = (GridCacheMvccCandidate<K>)o;

        assert key() != null && other.key() != null : "Key is null [this=" + this + ", other=" + o + ']';

        return ver.equals(other.ver) && key().equals(other.key());
    }

    /** {@inheritDoc} */
    @Override public int hashCode() {
        return ver.hashCode();
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        GridCacheMvccCandidate<?> prev = previous();
        GridCacheMvccCandidate<?> next = next();

        return S.toString(GridCacheMvccCandidate.class, this,
            "key", parent == null ? null : parent.key(),
            "masks", Mask.toString(flags()),
            "prevVer", (prev == null ? null : prev.version()),
            "nextVer", (next == null ? null : next.version()));
    }

    /**
     * Mask.
     */
    @SuppressWarnings({"PackageVisibleInnerClass"})
    enum Mask {
        /** */
        LOCAL(0x01),

        /** */
        OWNER(0x02),

        /** */
        READY(0x04),

        /** */
        REENTRY(0x08),

        /** */
        USED(0x10),

        /** */
        TX(0x40),

        /** */
        SINGLE_IMPLICIT(0x80),

        /** */
        DHT_LOCAL(0x100),

        /** */
        NEAR_LOCAL(0x200),

        /** */
        REMOVED(0x400);

        /** All mask values. */
        private static final Mask[] MASKS = values();

        /** Mask bit. */
        private final short bit;

        /**
         * @param bit Mask value.
         */
        Mask(int bit) {
            this.bit = (short)bit;
        }

        /**
         * @param flags Flags to check.
         * @return {@code True} if mask is set.
         */
        boolean get(short flags) {
            return (flags & bit) == bit;
        }

        /**
         * @param flags Flags.
         * @param on Mask to set.
         * @return Updated flags.
         */
        short set(short flags, boolean on) {
            return (short)(on ? flags | bit : flags & ~bit);
        }

        /**
         * @param flags Flags to check.
         * @return {@code 1} if mask is set, {@code 0} otherwise.
         */
        int bit(short flags) {
            return get(flags) ? 1 : 0;
        }

        /**
         * @param flags Flags.
         * @return String builder containing all flags.
         */
        static String toString(short flags) {
            SB sb = new SB();

            for (Mask m : MASKS) {
                if (m.ordinal() != 0)
                    sb.a('|');

                sb.a(m.name().toLowerCase()).a('=').a(m.bit(flags));
            }

            return sb.toString();
        }
    }
}

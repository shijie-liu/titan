package com.thinkaurelius.titan.diskstorage.locking.consistentkey;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.thinkaurelius.titan.diskstorage.StaticBuffer;
import com.thinkaurelius.titan.diskstorage.StorageException;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.Entry;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.KeyColumnValueStore;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.KeySliceQuery;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.StoreTransaction;
import com.thinkaurelius.titan.diskstorage.locking.PermanentLockingException;
import com.thinkaurelius.titan.diskstorage.util.KeyColumn;
import com.thinkaurelius.titan.diskstorage.util.RecordIterator;

/**
 * A wrapper that adds locking support to a {@link KeyColumnValueStore} by
 * overridding
 * {@link #acquireLock(StaticBuffer, StaticBuffer, StaticBuffer, StoreTransaction)
 * acquireLock()} and {@link #mutate(StaticBuffer, List, List, StoreTransaction)
 * mutate()}.
 */
public class ConsistentKeyLockStore implements KeyColumnValueStore {

    /**
     * Configuration setting key for the local lock mediator prefix
     */
    public static final String LOCAL_LOCK_MEDIATOR_PREFIX_KEY = "local-lock-mediator-prefix";
    
    private static final Logger log = LoggerFactory.getLogger(ConsistentKeyLockStore.class);


    /**
     * Titan data store.
     */
    final KeyColumnValueStore dataStore;
    
    final ConsistentKeyLocker locker;
    
    public ConsistentKeyLockStore(KeyColumnValueStore dataStore, ConsistentKeyLocker locker) {
        Preconditions.checkNotNull(dataStore);
        this.dataStore = dataStore;
        this.locker = locker;
    }

    public KeyColumnValueStore getDataStore() {
        return dataStore;
    }

    private StoreTransaction getBaseTx(StoreTransaction txh) {
        Preconditions.checkNotNull(txh);
        Preconditions.checkArgument(txh instanceof ConsistentKeyLockTransaction);
        return ((ConsistentKeyLockTransaction) txh).getBaseTransaction();
    }

    @Override
    public boolean containsKey(StaticBuffer key, StoreTransaction txh) throws StorageException {
        return dataStore.containsKey(key, getBaseTx(txh));
    }

    @Override
    public List<Entry> getSlice(KeySliceQuery query, StoreTransaction txh) throws StorageException {
        return dataStore.getSlice(query, getBaseTx(txh));
    }

    /**
     * {@inheritDoc}
     * 
     * <p/>
     * 
     * This implementation supports locking when {@code lockStore} is non-null.  
     */
    @Override
    public void mutate(StaticBuffer key, List<Entry> additions, List<StaticBuffer> deletions, StoreTransaction txh) throws StorageException {
        if (locker != null) {
            ConsistentKeyLockTransaction tx = (ConsistentKeyLockTransaction) txh;
            if (!tx.isMutationStarted()) {
                tx.mutationStarted();
                locker.checkLocks(tx.getConsistentTransaction());
                tx.checkExpectedValues();
            }
        }
        dataStore.mutate(key, additions, deletions, getBaseTx(txh));
    }
    
    /**
     * {@inheritDoc}
     * 
     * <p/>
     * 
     * This implementation supports locking when {@code lockStore} is non-null.
     * <p>
     * Consider the following scenario. This method is called twice with
     * identical key, column, and txh arguments, but with different
     * expectedValue arguments in each call. In testing, it seems titan's
     * graphdb requires that implementations discard the second expectedValue
     * and, when checking expectedValues vs actual values just prior to mutate,
     * only the initial expectedValue argument should be considered.
     */
    @Override
    public void acquireLock(StaticBuffer key, StaticBuffer column, StaticBuffer expectedValue, StoreTransaction txh) throws StorageException {
        if (locker != null) {
            ConsistentKeyLockTransaction tx = (ConsistentKeyLockTransaction) txh;
            if (tx.isMutationStarted())
                throw new PermanentLockingException("Attempted to obtain a lock after mutations had been persisted");
            KeyColumn lockID = new KeyColumn(key, column);
            log.debug("Attempting to acquireLock on {} ev={}", lockID, expectedValue);
            locker.writeLock(lockID, tx.getConsistentTransaction());
            tx.storeExpectedValue(this, lockID, expectedValue);
        } else {
            dataStore.acquireLock(key, column, expectedValue, getBaseTx(txh));
        }
    }

    @Override
    public RecordIterator<StaticBuffer> getKeys(StoreTransaction txh) throws StorageException {
        return dataStore.getKeys(getBaseTx(txh));
    }

    @Override
    public StaticBuffer[] getLocalKeyPartition() throws StorageException {
        return dataStore.getLocalKeyPartition();
    }

    @Override
    public String getName() {
        return dataStore.getName();
    }

    @Override
    public void close() throws StorageException {
        dataStore.close();
        // TODO close locker?
    }
    
    void deleteLocks(ConsistentKeyLockTransaction tx) throws StorageException {
        locker.deleteLocks(tx.getConsistentTransaction());
    }
}

/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.transaction.aote.log;

import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import org.lealone.common.exceptions.DbException;
import org.lealone.common.util.DataUtils;
import org.lealone.db.DataBuffer;
import org.lealone.db.value.ValueString;

public abstract class RedoLogRecord {

    private static ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

    private static byte TYPE_CHECKPOINT = 0;
    private static byte TYPE_DROPPED_MAP_REDO_LOG_RECORD = 1;
    private static byte TYPE_LOCAL_TRANSACTION_REDO_LOG_RECORD = 2;

    private volatile boolean synced;
    private CountDownLatch latch;

    boolean isSynced() {
        return synced;
    }

    void setSynced(boolean synced) {
        this.synced = synced;
        if (latch != null)
            latch.countDown();
    }

    void setLatch(CountDownLatch latch) {
        this.latch = latch;
    }

    boolean isCheckpoint() {
        return false;
    }

    abstract long initPendingRedoLog(Map<String, List<ByteBuffer>> pendingRedoLog,
            long lastTransactionId);

    abstract void write(DataBuffer buff);

    static RedoLogRecord read(ByteBuffer buff) {
        int type = buff.get();
        if (type == TYPE_CHECKPOINT) {
            return Checkpoint.read(buff);
        } else if (type == TYPE_DROPPED_MAP_REDO_LOG_RECORD) {
            return DroppedMapRedoLogRecord.read(buff);
        } else if (type == TYPE_LOCAL_TRANSACTION_REDO_LOG_RECORD) {
            return LocalTransactionRedoLogRecord.read(buff);
        } else {
            throw DbException.getInternalError("unknow type: " + type);
        }
    }

    public static Checkpoint createCheckpoint(long checkpointId, boolean saved) {
        return new Checkpoint(checkpointId, saved);
    }

    public static DroppedMapRedoLogRecord createDroppedMapRedoLogRecord(String mapName) {
        return new DroppedMapRedoLogRecord(mapName);
    }

    public static LocalTransactionRedoLogRecord createLocalTransactionRedoLogRecord(long transactionId,
            DataBuffer operations) {
        return new LocalTransactionRedoLogRecord(transactionId, operations);
    }

    static class Checkpoint extends RedoLogRecord {

        private final long checkpointId;
        private final boolean saved;

        Checkpoint(long checkpointId, boolean saved) {
            this.checkpointId = checkpointId;
            this.saved = saved;
        }

        @Override
        public boolean isCheckpoint() {
            return true;
        }

        public boolean isSaved() {
            return saved;
        }

        @Override
        public long initPendingRedoLog(Map<String, List<ByteBuffer>> pendingRedoLog,
                long lastTransactionId) {
            pendingRedoLog.clear();
            if (checkpointId < lastTransactionId) {
                throw DbException.getInternalError(
                        "checkpointId=" + checkpointId + ", lastTransactionId=" + lastTransactionId);
            }
            return checkpointId;
        }

        @Override
        public void write(DataBuffer buff) {
            buff.put(TYPE_CHECKPOINT);
            buff.putVarLong(checkpointId);
        }

        public static RedoLogRecord read(ByteBuffer buff) {
            long checkpointId = DataUtils.readVarLong(buff);
            return new Checkpoint(checkpointId, true);
        }
    }

    static class DroppedMapRedoLogRecord extends RedoLogRecord {

        private final String mapName;

        DroppedMapRedoLogRecord(String mapName) {
            DataUtils.checkNotNull(mapName, "mapName");
            this.mapName = mapName;
        }

        @Override
        public long initPendingRedoLog(Map<String, List<ByteBuffer>> pendingRedoLog,
                long lastTransactionId) {
            List<ByteBuffer> logs = pendingRedoLog.get(mapName);
            if (logs != null) {
                logs = new LinkedList<>();
                pendingRedoLog.put(mapName, logs);
            }
            return lastTransactionId;
        }

        @Override
        public void write(DataBuffer buff) {
            buff.put(TYPE_DROPPED_MAP_REDO_LOG_RECORD);
            ValueString.type.write(buff, mapName);
        }

        public static RedoLogRecord read(ByteBuffer buff) {
            String mapName = ValueString.type.read(buff);
            return new DroppedMapRedoLogRecord(mapName);
        }
    }

    static class TransactionRedoLogRecord extends RedoLogRecord {

        protected final long transactionId;
        protected final ByteBuffer operations;

        public TransactionRedoLogRecord(long transactionId, ByteBuffer operations) {
            this.transactionId = transactionId;
            this.operations = operations;
        }

        @Override
        public long initPendingRedoLog(Map<String, List<ByteBuffer>> pendingRedoLog,
                long lastTransactionId) {
            ByteBuffer buff = operations;
            while (buff.hasRemaining()) {
                // 此时还没有打开底层存储的map，所以只预先解析出mapName和keyValue字节数组
                // 写时格式参照UndoLogRecord.writeForRedo()
                String mapName = ValueString.type.read(buff);
                List<ByteBuffer> keyValues = pendingRedoLog.get(mapName);
                if (keyValues == null) {
                    keyValues = new LinkedList<>();
                    pendingRedoLog.put(mapName, keyValues);
                }
                int len = buff.getInt();
                byte[] keyValue = new byte[len];
                buff.get(keyValue);
                keyValues.add(ByteBuffer.wrap(keyValue));
            }
            return transactionId > lastTransactionId ? transactionId : lastTransactionId;
        }

        @Override
        public void write(DataBuffer buff) {
            write(buff, TYPE_LOCAL_TRANSACTION_REDO_LOG_RECORD);
        }

        public void write(DataBuffer buff, byte type) {
            buff.put(type);
            buff.putVarLong(transactionId);
            buff.putInt(operations.remaining());
            buff.put(operations);
        }

        public static ByteBuffer readOperations(ByteBuffer buff) {
            ByteBuffer operations;
            int len = buff.getInt(); // DataUtils.readVarInt(buff);
            if (len > 0) {
                byte[] value = new byte[len];
                buff.get(value);
                operations = ByteBuffer.wrap(value);
            } else {
                operations = EMPTY_BUFFER;
            }
            return operations;
        }
    }

    static class LocalTransactionRedoLogRecord extends TransactionRedoLogRecord {

        private DataBuffer buffer;

        public LocalTransactionRedoLogRecord(long transactionId, ByteBuffer operations) {
            super(transactionId, operations);
        }

        public LocalTransactionRedoLogRecord(long transactionId, DataBuffer operations) {
            super(transactionId, operations.getBuffer());
            this.buffer = operations;
        }

        @Override
        public void write(DataBuffer buff) {
            write(buff, TYPE_LOCAL_TRANSACTION_REDO_LOG_RECORD);
            if (buffer != null) {
                buffer.close();
            }
        }

        public static LocalTransactionRedoLogRecord read(ByteBuffer buff) {
            long transactionId = DataUtils.readVarLong(buff);
            ByteBuffer operations = readOperations(buff);
            return new LocalTransactionRedoLogRecord(transactionId, operations);
        }
    }
}

package com.nexus.voxysync.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * 网络通道定义与载荷编解码（Minecraft 1.20.1 Fabric 经典 ResourceLocation + FriendlyByteBuf 风格）。
 *
 * <p>协议流程（C2S/S2C 均为 1.20.1 自定义载荷；注意 C2S 上限 32767 字节、S2C 上限 1048576 字节）：</p>
 * <ol>
 *   <li>C→S capability_request：客户端探测服务器是否启用同步。</li>
 *   <li>S→C capability(enabled, reason)：返回开关状态。</li>
 *   <li>C→S sync_request(分块)：请求同步当前维度；每块携带文件名→(时间戳/大小) 元数据（增量）。</li>
 *   <li>S→C sync_start(syncId, dimensionId, totalRegions, totalBytes)。</li>
 *   <li>S→C region_part(...) 若干次（限速分片）。</li>
 *   <li>S→C sync_progress(...)（每个区域完成后）。</li>
 *   <li>S→C sync_complete(syncId, success, message, transferredRegions, transferredBytes)。</li>
 *   <li>S→C request_sync(modeHint)：OP 用 /voxysync sync 命令提示客户端立刻发起同步。</li>
 * </ol>
 */
public final class VoxyPackets {
    public static final ResourceLocation CAPABILITY_REQUEST = new ResourceLocation("voxysync", "capability_request");
    public static final ResourceLocation CAPABILITY = new ResourceLocation("voxysync", "capability");
    public static final ResourceLocation SYNC_REQUEST = new ResourceLocation("voxysync", "sync_request");
    public static final ResourceLocation SYNC_START = new ResourceLocation("voxysync", "sync_start");
    public static final ResourceLocation REGION_PART = new ResourceLocation("voxysync", "region_part");
    public static final ResourceLocation SYNC_PROGRESS = new ResourceLocation("voxysync", "sync_progress");
    public static final ResourceLocation SYNC_COMPLETE = new ResourceLocation("voxysync", "sync_complete");
    public static final ResourceLocation REQUEST_SYNC = new ResourceLocation("voxysync", "request_sync");
    /** 客户端手动中止当前同步（C2S，空载荷） */
    public static final ResourceLocation ABORT_SYNC = new ResourceLocation("voxysync", "abort_sync");

    /** 字符串字段最大长度（防异常包占满内存） */
    public static final int MAX_STRING = 256;
    /** 客户端元数据总条数上限 */
    public static final int MAX_META_ENTRIES = 20000;
    /** 每个 sync_request 块携带的元数据条数上限 */
    public static final int MAX_ENTRIES_PER_CHUNK = 300;
    /** sync_request 分块数上限 */
    public static final int MAX_CHUNKS = 64;

    private VoxyPackets() {
    }

    /** 区域元数据（用于增量判断：时间戳 + 大小均一致则视为未变化） */
    public record RegionMeta(long timestampSeconds, long sizeBytes) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeLong(this.timestampSeconds);
            buf.writeLong(this.sizeBytes);
        }

        public static RegionMeta decode(FriendlyByteBuf buf) {
            return new RegionMeta(buf.readLong(), buf.readLong());
        }
    }

    /** 服务器能力（是否已启用 + 原因/模式说明） */
    public record CapabilityPayload(boolean enabled, String reason) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeBoolean(this.enabled);
            buf.writeUtf(this.reason, 64);
        }

        public static CapabilityPayload decode(FriendlyByteBuf buf) {
            return new CapabilityPayload(buf.readBoolean(), buf.readUtf(64));
        }
    }

    /** 能力探测请求（客户端 → 服务端）：携带客户端 mod 版本，便于服务端日志排查 */
    public record CapabilityRequestPayload(String clientVersion) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(this.clientVersion == null ? "" : this.clientVersion, 32);
        }

        public static CapabilityRequestPayload decode(FriendlyByteBuf buf) {
            return new CapabilityRequestPayload(buf.readUtf(32));
        }
    }

    /**
     * 同步请求（客户端 → 服务端，按块发送：1.20.1 C2S 自定义载荷上限 32767 字节）。
     *
     * @param dimensionId 当前维度 id（ResourceLocation of dimension key）
     * @param requestId   本次请求的随机 id（服务端聚合判据）
     * @param chunkIndex  第几块（0 起）
     * @param totalChunks 总块数
     * @param entries     本块的元数据（key = fileName，不含维度前缀）
     */
    public record SyncRequestPayload(String dimensionId, String requestId, int chunkIndex, int totalChunks,
                                     Map<String, RegionMeta> entries) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(this.dimensionId, MAX_STRING);
            buf.writeUtf(this.requestId, 36);
            buf.writeInt(this.chunkIndex);
            buf.writeInt(this.totalChunks);
            buf.writeInt(this.entries.size());
            for (Map.Entry<String, RegionMeta> entry : this.entries.entrySet()) {
                buf.writeUtf(entry.getKey(), 64);
                entry.getValue().encode(buf);
            }
        }

        public static SyncRequestPayload decode(FriendlyByteBuf buf) {
            String dimensionId = buf.readUtf(MAX_STRING);
            String requestId = buf.readUtf(36);
            int chunkIndex = Math.max(buf.readInt(), 0);
            int totalChunks = Math.min(Math.max(buf.readInt(), 1), MAX_CHUNKS);
            int size = Math.min(Math.max(buf.readInt(), 0), MAX_ENTRIES_PER_CHUNK);
            Map<String, RegionMeta> meta = new HashMap<>();
            for (int i = 0; i < size; i++) {
                meta.put(buf.readUtf(64), RegionMeta.decode(buf));
            }
            return new SyncRequestPayload(dimensionId, requestId, chunkIndex, totalChunks, meta);
        }
    }

    /** 同步开始（服务端 → 客户端） */
    public record SyncStartPayload(String syncId, String dimensionId, int totalRegions, long totalBytes) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(this.syncId, MAX_STRING);
            buf.writeUtf(this.dimensionId, MAX_STRING);
            buf.writeInt(this.totalRegions);
            buf.writeLong(this.totalBytes);
        }

        public static SyncStartPayload decode(FriendlyByteBuf buf) {
            return new SyncStartPayload(buf.readUtf(MAX_STRING), buf.readUtf(MAX_STRING),
                    buf.readInt(), buf.readLong());
        }
    }

    /** 区域文件分片（服务端 → 客户端） */
    public record RegionPartPayload(String syncId, String dimensionId, int regionX, int regionZ,
                                    int partIndex, int totalParts, long byteOffset, long totalBytes,
                                    long timestampSeconds, byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(this.syncId, MAX_STRING);
            buf.writeUtf(this.dimensionId, MAX_STRING);
            buf.writeInt(this.regionX);
            buf.writeInt(this.regionZ);
            buf.writeInt(this.partIndex);
            buf.writeInt(this.totalParts);
            buf.writeLong(this.byteOffset);
            buf.writeLong(this.totalBytes);
            buf.writeLong(this.timestampSeconds);
            buf.writeByteArray(this.data);
        }

        public static RegionPartPayload decode(FriendlyByteBuf buf) {
            return new RegionPartPayload(buf.readUtf(MAX_STRING), buf.readUtf(MAX_STRING),
                    buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(),
                    buf.readLong(), buf.readLong(), buf.readLong(), buf.readByteArray());
        }
    }

    /** 同步进度（服务端 → 客户端） */
    public record SyncProgressPayload(String syncId, int processedRegions, int totalRegions,
                                      long processedBytes, long totalBytes, String status) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(this.syncId, MAX_STRING);
            buf.writeInt(this.processedRegions);
            buf.writeInt(this.totalRegions);
            buf.writeLong(this.processedBytes);
            buf.writeLong(this.totalBytes);
            buf.writeUtf(this.status, 64);
        }

        public static SyncProgressPayload decode(FriendlyByteBuf buf) {
            return new SyncProgressPayload(buf.readUtf(MAX_STRING), buf.readInt(), buf.readInt(),
                    buf.readLong(), buf.readLong(), buf.readUtf(64));
        }
    }

    /** 同步完成/失败（服务端 → 客户端） */
    public record SyncCompletePayload(String syncId, boolean success, String message,
                                      int transferredRegions, long transferredBytes) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(this.syncId, MAX_STRING);
            buf.writeBoolean(this.success);
            buf.writeUtf(this.message, MAX_STRING);
            buf.writeInt(this.transferredRegions);
            buf.writeLong(this.transferredBytes);
        }

        public static SyncCompletePayload decode(FriendlyByteBuf buf) {
            return new SyncCompletePayload(buf.readUtf(MAX_STRING), buf.readBoolean(),
                    buf.readUtf(MAX_STRING), buf.readInt(), buf.readLong());
        }
    }

    /** 服务端请求客户端立刻发起同步（OP 命令用；modeHint = radius/all/空串按配置） */
    public record RequestSyncPayload(String modeHint, String note) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(this.modeHint == null ? "" : this.modeHint, 16);
            buf.writeUtf(this.note == null ? "" : this.note, MAX_STRING);
        }

        public static RequestSyncPayload decode(FriendlyByteBuf buf) {
            return new RequestSyncPayload(buf.readUtf(16), buf.readUtf(MAX_STRING));
        }
    }
}

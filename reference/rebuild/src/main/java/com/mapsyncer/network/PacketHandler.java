package com.mapsyncer.network;

import com.mapsyncer.MapSyncer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 网络包处理器
 *
 * <p>负责定义和处理MapSyncer模组的所有网络通信包。
 * 包含同步请求、同步响应和同步进度三种类型的网络包。</p>
 *
 * <p>网络包类型：</p>
 * <ul>
 *   <li>{@link SyncRequestPayload} - 客户端发送的同步请求，包含本地region元数据</li>
 *   <li>{@link SyncResponsePayload} - 服务端发送的同步响应，包含需要更新的地图数据</li>
 *   <li>{@link SyncProgressPayload} - 服务端发送的同步进度通知</li>
 * </ul>
 */
public class PacketHandler {

    private static final int MAX_CLIENT_META_ENTRIES = 200_000;
    private static final int MAX_PATH_LENGTH = 512;
    private static final int MAX_DIMENSION_LENGTH = 256;
    private static final int MAX_HASH_LENGTH = 64;
    private static final int MAX_PART_DATA_LENGTH = 1_000_000;
    private static final int MAX_WAYPOINTS = 10_000;
    private static final int MAX_WAYPOINT_FIELD_LENGTH = 256;

    /** 同步请求包的资源定位符 */
    public static final Identifier SYNC_REQUEST_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "sync_request");
    /** 同步响应包的资源定位符 */
    public static final Identifier SYNC_RESPONSE_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "sync_response");
    /** 同步进度包的资源定位符 */
    public static final Identifier SYNC_PROGRESS_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "sync_progress");
    public static final Identifier SYNC_REGION_PART_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "sync_region_part");
    public static final Identifier SYNC_REGION_COMPLETE_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "sync_region_complete");
    public static final Identifier RADIUS_SYNC_REQUEST_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "radius_sync_request");
    public static final Identifier PUBLIC_WAYPOINTS_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "public_waypoints");
    public static final Identifier PUBLIC_WAYPOINTS_REQUEST_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "public_waypoints_request");
    public static final Identifier PUBLIC_WAYPOINT_ADD_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "public_waypoint_add");
    public static final Identifier PUBLIC_WAYPOINT_ADD_RESULT_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "public_waypoint_add_result");

    /** 服务端已安装通知包的资源定位符 */
    public static final Identifier SERVER_INSTALLED_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "server_installed");
    public static final Identifier ADMIN_STATUS_REQUEST_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "admin_status_request");
    public static final Identifier ADMIN_STATUS_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "admin_status");
    public static final Identifier ADMIN_SETTINGS_UPDATE_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "admin_settings_update");
    public static final Identifier OPEN_GUI_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "open_gui");
    public static final Identifier VOXY_CAPABILITY_REQUEST_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "voxy_capability_request");
    public static final Identifier VOXY_CAPABILITY_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "voxy_capability");
    public static final Identifier VOXY_SYNC_REQUEST_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "voxy_sync_request");
    public static final Identifier VOXY_SYNC_START_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "voxy_sync_start");
    public static final Identifier VOXY_REGION_PART_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "voxy_region_part");
    public static final Identifier VOXY_SYNC_PROGRESS_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "voxy_sync_progress");
    public static final Identifier VOXY_SYNC_COMPLETE_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "voxy_sync_complete");

    /**
     * 同步请求包 - 客户端发送各region的元数据（时间戳+哈希）
     *
     * <p>客户端通过此包向服务端报告本地已有的地图数据状态，
     * 服务端据此判断哪些数据需要同步。</p>
     *
     * @param clientMeta 客户端元数据映射，键为region路径，值为时间戳和哈希值
     */
    public record SyncRequestPayload(Map<String, ClientMeta> clientMeta) implements CustomPacketPayload {
        /** 包类型标识 */
        public static final Type<SyncRequestPayload> TYPE = new Type<>(SYNC_REQUEST_ID);
        /** 流编解码器，用于网络传输的序列化和反序列化 */
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncRequestPayload> STREAM_CODEC = StreamCodec.of(
                SyncRequestPayload::encode, SyncRequestPayload::decode
        );

        /**
         * 将同步请求包编码到网络缓冲区
         *
         * @param buf     网络缓冲区
         * @param payload 要编码的请求包
         */
        public static void encode(RegistryFriendlyByteBuf buf, SyncRequestPayload payload) {
            buf.writeInt(payload.clientMeta.size());
            for (var entry : payload.clientMeta.entrySet()) {
                buf.writeUtf(entry.getKey());
                buf.writeLong(entry.getValue().timestampSeconds());
                buf.writeUtf(entry.getValue().hash());
            }
        }

        /**
         * 从网络缓冲区解码同步请求包
         *
         * @param buf 网络缓冲区
         * @return 解码后的同步请求包
         */
        public static SyncRequestPayload decode(RegistryFriendlyByteBuf buf) {
            int size = buf.readInt();
            if (size < 0 || size > MAX_CLIENT_META_ENTRIES) {
                throw new IllegalArgumentException("Invalid sync metadata count: " + size);
            }
            Map<String, ClientMeta> metaMap = new HashMap<>();
            for (int i = 0; i < size; i++) {
                String path = buf.readUtf(MAX_PATH_LENGTH);
                long timestampSeconds = buf.readLong();
                String hash = buf.readUtf(MAX_HASH_LENGTH);
                metaMap.put(path, new ClientMeta(timestampSeconds, hash));
            }
            return new SyncRequestPayload(metaMap);
        }

        /**
         * 获取此负载的包类型
         *
         * @return 包类型标识
         */
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record RadiusSyncRequestPayload(
            Map<String, ClientMeta> clientMeta,
            String dimensionId,
            int radiusBlocks,
            int playerX,
            int playerY,
            int playerZ
    ) implements CustomPacketPayload {
        public static final Type<RadiusSyncRequestPayload> TYPE = new Type<>(RADIUS_SYNC_REQUEST_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, RadiusSyncRequestPayload> STREAM_CODEC = StreamCodec.of(
                RadiusSyncRequestPayload::encode, RadiusSyncRequestPayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, RadiusSyncRequestPayload payload) {
            buf.writeInt(payload.clientMeta.size());
            for (var entry : payload.clientMeta.entrySet()) {
                buf.writeUtf(entry.getKey());
                buf.writeLong(entry.getValue().timestampSeconds());
                buf.writeUtf(entry.getValue().hash());
            }
            buf.writeUtf(payload.dimensionId);
            buf.writeInt(payload.radiusBlocks);
            buf.writeInt(payload.playerX);
            buf.writeInt(payload.playerY);
            buf.writeInt(payload.playerZ);
        }

        public static RadiusSyncRequestPayload decode(RegistryFriendlyByteBuf buf) {
            int size = buf.readInt();
            if (size < 0 || size > MAX_CLIENT_META_ENTRIES) {
                throw new IllegalArgumentException("Invalid radius sync metadata count: " + size);
            }
            Map<String, ClientMeta> metaMap = new HashMap<>();
            for (int i = 0; i < size; i++) {
                String path = buf.readUtf(MAX_PATH_LENGTH);
                long timestampSeconds = buf.readLong();
                String hash = buf.readUtf(MAX_HASH_LENGTH);
                metaMap.put(path, new ClientMeta(timestampSeconds, hash));
            }
            return new RadiusSyncRequestPayload(
                    metaMap,
                    buf.readUtf(MAX_DIMENSION_LENGTH),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt()
            );
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 同步响应包 - 服务端发送需要更新的地图数据
     *
     * <p>服务端通过此包将需要同步的地图数据发送给客户端。
     * 可能包含多个region的数据，并标识是否为最后一包。</p>
     *
     * @param chunks     地图数据列表，包含需要更新的region数据
     * @param isComplete 是否为最后一包（true表示同步完成）
     * @param worldId    世界ID，用于客户端识别当前同步的世界
     * @param status     同步状态："ok"=有数据同步, "uptodate"=已是最新, "no_cache"=无缓存, "dim_not_available"=维度不存在
     */
    public record SyncResponsePayload(List<ChunkMapData> chunks, boolean isComplete, int worldId, String status) implements CustomPacketPayload {
        /** 包类型标识 */
        public static final Type<SyncResponsePayload> TYPE = new Type<>(SYNC_RESPONSE_ID);
        /** 流编解码器，用于网络传输的序列化和反序列化 */
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncResponsePayload> STREAM_CODEC = StreamCodec.of(
                SyncResponsePayload::encode, SyncResponsePayload::decode
        );

        /**
         * 将同步响应包编码到网络缓冲区
         *
         * @param buf     网络缓冲区
         * @param payload 要编码的响应包
         */
        public static void encode(RegistryFriendlyByteBuf buf, SyncResponsePayload payload) {
            buf.writeInt(payload.worldId);
            buf.writeInt(payload.chunks.size());
            for (ChunkMapData data : payload.chunks) {
                ChunkMapData.encode(buf, data);
            }
            buf.writeBoolean(payload.isComplete);
            buf.writeUtf(payload.status);
        }

        /**
         * 从网络缓冲区解码同步响应包
         *
         * @param buf 网络缓冲区
         * @return 解码后的同步响应包
         */
        public static SyncResponsePayload decode(RegistryFriendlyByteBuf buf) {
            int worldId = buf.readInt();
            int size = buf.readInt();
            List<ChunkMapData> chunks = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                chunks.add(ChunkMapData.decode(buf));
            }
            boolean isComplete = buf.readBoolean();
            String status = buf.readUtf();
            return new SyncResponsePayload(chunks, isComplete, worldId, status);
        }

        /**
         * 获取此负载的包类型
         *
         * @return 包类型标识
         */
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 同步进度包 - 服务端发送同步进度通知
     *
     * <p>用于向客户端报告当前同步进度，让客户端能够显示进度条或状态信息。</p>
     *
     * @param processed 已处理的region数量
     * @param total     总region数量
     * @param status    当前状态描述文本
     */
    public record SyncProgressPayload(int processed, int total, String status) implements CustomPacketPayload {
        /** 包类型标识 */
        public static final Type<SyncProgressPayload> TYPE = new Type<>(SYNC_PROGRESS_ID);
        /** 流编解码器，用于网络传输的序列化和反序列化 */
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncProgressPayload> STREAM_CODEC = StreamCodec.of(
                SyncProgressPayload::encode, SyncProgressPayload::decode
        );

        /**
         * 将同步进度包编码到网络缓冲区
         *
         * @param buf     网络缓冲区
         * @param payload 要编码的进度包
         */
        public static void encode(RegistryFriendlyByteBuf buf, SyncProgressPayload payload) {
            buf.writeInt(payload.processed);
            buf.writeInt(payload.total);
            buf.writeUtf(payload.status);
        }

        /**
         * 从网络缓冲区解码同步进度包
         *
         * @param buf 网络缓冲区
         * @return 解码后的同步进度包
         */
        public static SyncProgressPayload decode(RegistryFriendlyByteBuf buf) {
            return new SyncProgressPayload(buf.readInt(), buf.readInt(), buf.readUtf());
        }

        /**
         * 获取此负载的包类型
         *
         * @return 包类型标识
         */
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 服务端已安装通知包 - 服务端在玩家加入时发送
     *
     * <p>用于告知客户端服务端已安装 MapSyncer，客户端可以据此提前知道服务端状态。</p>
     *
     * @param version 服务端模组版本号
     */
    public record SyncRegionPartPayload(
            String syncId,
            int worldId,
            String regionKey,
            String dimension,
            int regionX,
            int regionZ,
            int caveLayer,
            int partIndex,
            int totalParts,
            long byteOffset,
            long totalBytes,
            long timestampSeconds,
            String hash,
            byte[] data
    ) implements CustomPacketPayload {
        public static final Type<SyncRegionPartPayload> TYPE = new Type<>(SYNC_REGION_PART_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncRegionPartPayload> STREAM_CODEC = StreamCodec.of(
                SyncRegionPartPayload::encode, SyncRegionPartPayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, SyncRegionPartPayload payload) {
            buf.writeUtf(payload.syncId);
            buf.writeInt(payload.worldId);
            buf.writeUtf(payload.regionKey);
            buf.writeUtf(payload.dimension);
            buf.writeInt(payload.regionX);
            buf.writeInt(payload.regionZ);
            buf.writeInt(payload.caveLayer);
            buf.writeInt(payload.partIndex);
            buf.writeInt(payload.totalParts);
            buf.writeLong(payload.byteOffset);
            buf.writeLong(payload.totalBytes);
            buf.writeLong(payload.timestampSeconds);
            buf.writeUtf(payload.hash);
            buf.writeByteArray(payload.data);
        }

        public static SyncRegionPartPayload decode(RegistryFriendlyByteBuf buf) {
            return new SyncRegionPartPayload(
                    buf.readUtf(MAX_PATH_LENGTH),
                    buf.readInt(),
                    buf.readUtf(MAX_PATH_LENGTH),
                    buf.readUtf(MAX_DIMENSION_LENGTH),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readLong(),
                    buf.readLong(),
                    buf.readLong(),
                    buf.readUtf(MAX_HASH_LENGTH),
                    buf.readByteArray(MAX_PART_DATA_LENGTH)
            );
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SyncRegionCompletePayload(
            String syncId,
            int worldId,
            String regionKey,
            String dimension,
            int regionX,
            int regionZ,
            int caveLayer,
            int totalParts,
            long totalBytes,
            long timestampSeconds,
            String hash
    ) implements CustomPacketPayload {
        public static final Type<SyncRegionCompletePayload> TYPE = new Type<>(SYNC_REGION_COMPLETE_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncRegionCompletePayload> STREAM_CODEC = StreamCodec.of(
                SyncRegionCompletePayload::encode, SyncRegionCompletePayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, SyncRegionCompletePayload payload) {
            buf.writeUtf(payload.syncId);
            buf.writeInt(payload.worldId);
            buf.writeUtf(payload.regionKey);
            buf.writeUtf(payload.dimension);
            buf.writeInt(payload.regionX);
            buf.writeInt(payload.regionZ);
            buf.writeInt(payload.caveLayer);
            buf.writeInt(payload.totalParts);
            buf.writeLong(payload.totalBytes);
            buf.writeLong(payload.timestampSeconds);
            buf.writeUtf(payload.hash);
        }

        public static SyncRegionCompletePayload decode(RegistryFriendlyByteBuf buf) {
            return new SyncRegionCompletePayload(
                    buf.readUtf(MAX_PATH_LENGTH),
                    buf.readInt(),
                    buf.readUtf(MAX_PATH_LENGTH),
                    buf.readUtf(MAX_DIMENSION_LENGTH),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readLong(),
                    buf.readLong(),
                    buf.readUtf(MAX_HASH_LENGTH)
            );
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ServerInstalledPayload(String version) implements CustomPacketPayload {
        /** 包类型标识 */
        public static final Type<ServerInstalledPayload> TYPE = new Type<>(SERVER_INSTALLED_ID);
        /** 流编解码器 */
        public static final StreamCodec<RegistryFriendlyByteBuf, ServerInstalledPayload> STREAM_CODEC = StreamCodec.of(
                ServerInstalledPayload::encode, ServerInstalledPayload::decode
        );

        /**
         * 编码到网络缓冲区
         */
        public static void encode(RegistryFriendlyByteBuf buf, ServerInstalledPayload payload) {
            buf.writeUtf(payload.version);
        }

        /**
         * 从网络缓冲区解码
         */
        public static ServerInstalledPayload decode(RegistryFriendlyByteBuf buf) {
            return new ServerInstalledPayload(buf.readUtf());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record AdminStatusRequestPayload() implements CustomPacketPayload {
        public static final Type<AdminStatusRequestPayload> TYPE = new Type<>(ADMIN_STATUS_REQUEST_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, AdminStatusRequestPayload> STREAM_CODEC = StreamCodec.of(
                AdminStatusRequestPayload::encode, AdminStatusRequestPayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, AdminStatusRequestPayload payload) {
        }

        public static AdminStatusRequestPayload decode(RegistryFriendlyByteBuf buf) {
            return new AdminStatusRequestPayload();
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record AdminStatusPayload(
            boolean allowed,
            boolean running,
            int processed,
            int total,
            int updated,
            int skipped,
            int dirtyCount,
            int cacheDimensionCount,
            int cacheRegionCount,
            long cacheSizeBytes,
            int syncSpeedLimitKBps,
            boolean radiusSyncEnabled,
            int maxRadiusSyncBlocks,
            String radiusSyncCenterMode,
            String radiusSyncFixedDimension,
            int radiusSyncFixedX,
            int radiusSyncFixedY,
            int radiusSyncFixedZ,
            boolean publicWaypointsEnabled,
            String publicWaypointsGroup,
            int publicWaypointsCount,
            String publicWaypointsHash,
            String status,
            String currentDimension,
            String incrementalStatus
    ) implements CustomPacketPayload {
        public static final Type<AdminStatusPayload> TYPE = new Type<>(ADMIN_STATUS_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, AdminStatusPayload> STREAM_CODEC = StreamCodec.of(
                AdminStatusPayload::encode, AdminStatusPayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, AdminStatusPayload payload) {
            buf.writeBoolean(payload.allowed);
            buf.writeBoolean(payload.running);
            buf.writeInt(payload.processed);
            buf.writeInt(payload.total);
            buf.writeInt(payload.updated);
            buf.writeInt(payload.skipped);
            buf.writeInt(payload.dirtyCount);
            buf.writeInt(payload.cacheDimensionCount);
            buf.writeInt(payload.cacheRegionCount);
            buf.writeLong(payload.cacheSizeBytes);
            buf.writeInt(payload.syncSpeedLimitKBps);
            buf.writeBoolean(payload.radiusSyncEnabled);
            buf.writeInt(payload.maxRadiusSyncBlocks);
            buf.writeUtf(payload.radiusSyncCenterMode);
            buf.writeUtf(payload.radiusSyncFixedDimension);
            buf.writeInt(payload.radiusSyncFixedX);
            buf.writeInt(payload.radiusSyncFixedY);
            buf.writeInt(payload.radiusSyncFixedZ);
            buf.writeBoolean(payload.publicWaypointsEnabled);
            buf.writeUtf(payload.publicWaypointsGroup);
            buf.writeInt(payload.publicWaypointsCount);
            buf.writeUtf(payload.publicWaypointsHash);
            buf.writeUtf(payload.status);
            buf.writeUtf(payload.currentDimension);
            buf.writeUtf(payload.incrementalStatus);
        }

        public static AdminStatusPayload decode(RegistryFriendlyByteBuf buf) {
            return new AdminStatusPayload(
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readLong(),
                    buf.readInt(),
                    buf.readBoolean(),
                    buf.readInt(),
                    buf.readUtf(MAX_WAYPOINT_FIELD_LENGTH),
                    buf.readUtf(MAX_DIMENSION_LENGTH),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readBoolean(),
                    buf.readUtf(MAX_WAYPOINT_FIELD_LENGTH),
                    buf.readInt(),
                    buf.readUtf(MAX_HASH_LENGTH),
                    buf.readUtf(),
                    buf.readUtf(),
                    buf.readUtf()
            );
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record AdminSettingsUpdatePayload(
            boolean radiusSyncEnabled,
            int maxRadiusSyncBlocks,
            String radiusSyncCenterMode,
            String radiusSyncFixedDimension,
            int radiusSyncFixedX,
            int radiusSyncFixedY,
            int radiusSyncFixedZ
    ) implements CustomPacketPayload {
        public static final Type<AdminSettingsUpdatePayload> TYPE = new Type<>(ADMIN_SETTINGS_UPDATE_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, AdminSettingsUpdatePayload> STREAM_CODEC = StreamCodec.of(
                AdminSettingsUpdatePayload::encode, AdminSettingsUpdatePayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, AdminSettingsUpdatePayload payload) {
            buf.writeBoolean(payload.radiusSyncEnabled);
            buf.writeInt(payload.maxRadiusSyncBlocks);
            buf.writeUtf(payload.radiusSyncCenterMode);
            buf.writeUtf(payload.radiusSyncFixedDimension);
            buf.writeInt(payload.radiusSyncFixedX);
            buf.writeInt(payload.radiusSyncFixedY);
            buf.writeInt(payload.radiusSyncFixedZ);
        }

        public static AdminSettingsUpdatePayload decode(RegistryFriendlyByteBuf buf) {
            return new AdminSettingsUpdatePayload(
                    buf.readBoolean(),
                    buf.readInt(),
                    buf.readUtf(MAX_WAYPOINT_FIELD_LENGTH),
                    buf.readUtf(MAX_DIMENSION_LENGTH),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt()
            );
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record OpenGuiPayload() implements CustomPacketPayload {
        public static final Type<OpenGuiPayload> TYPE = new Type<>(OPEN_GUI_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenGuiPayload> STREAM_CODEC = StreamCodec.of(
                OpenGuiPayload::encode, OpenGuiPayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, OpenGuiPayload payload) {
        }

        public static OpenGuiPayload decode(RegistryFriendlyByteBuf buf) {
            return new OpenGuiPayload();
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record PublicWaypoint(
            String name,
            String initial,
            int x,
            int y,
            int z,
            int color,
            boolean disabled,
            String type,
            String set,
            String dimension
    ) {
        public static void encode(RegistryFriendlyByteBuf buf, PublicWaypoint waypoint) {
            buf.writeUtf(waypoint.name);
            buf.writeUtf(waypoint.initial);
            buf.writeInt(waypoint.x);
            buf.writeInt(waypoint.y);
            buf.writeInt(waypoint.z);
            buf.writeInt(waypoint.color);
            buf.writeBoolean(waypoint.disabled);
            buf.writeUtf(waypoint.type);
            buf.writeUtf(waypoint.set);
            buf.writeUtf(waypoint.dimension);
        }

        public static PublicWaypoint decode(RegistryFriendlyByteBuf buf) {
            return new PublicWaypoint(
                    buf.readUtf(MAX_WAYPOINT_FIELD_LENGTH),
                    buf.readUtf(16),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readBoolean(),
                    buf.readUtf(MAX_WAYPOINT_FIELD_LENGTH),
                    buf.readUtf(MAX_WAYPOINT_FIELD_LENGTH),
                    buf.readUtf(MAX_DIMENSION_LENGTH)
            );
        }
    }

    public record PublicWaypointsPayload(
            String groupName,
            boolean replaceGroup,
            String hash,
            List<PublicWaypoint> waypoints
    ) implements CustomPacketPayload {
        public static final Type<PublicWaypointsPayload> TYPE = new Type<>(PUBLIC_WAYPOINTS_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, PublicWaypointsPayload> STREAM_CODEC = StreamCodec.of(
                PublicWaypointsPayload::encode, PublicWaypointsPayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, PublicWaypointsPayload payload) {
            buf.writeUtf(payload.groupName);
            buf.writeBoolean(payload.replaceGroup);
            buf.writeUtf(payload.hash);
            buf.writeInt(payload.waypoints.size());
            for (PublicWaypoint waypoint : payload.waypoints) {
                PublicWaypoint.encode(buf, waypoint);
            }
        }

        public static PublicWaypointsPayload decode(RegistryFriendlyByteBuf buf) {
            String groupName = buf.readUtf(MAX_WAYPOINT_FIELD_LENGTH);
            boolean replaceGroup = buf.readBoolean();
            String hash = buf.readUtf(MAX_HASH_LENGTH);
            int size = buf.readInt();
            if (size < 0 || size > MAX_WAYPOINTS) {
                throw new IllegalArgumentException("Invalid public waypoint count: " + size);
            }
            List<PublicWaypoint> waypoints = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                waypoints.add(PublicWaypoint.decode(buf));
            }
            return new PublicWaypointsPayload(groupName, replaceGroup, hash, waypoints);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record PublicWaypointsRequestPayload() implements CustomPacketPayload {
        public static final Type<PublicWaypointsRequestPayload> TYPE = new Type<>(PUBLIC_WAYPOINTS_REQUEST_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, PublicWaypointsRequestPayload> STREAM_CODEC = StreamCodec.of(
                PublicWaypointsRequestPayload::encode, PublicWaypointsRequestPayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, PublicWaypointsRequestPayload payload) {
        }

        public static PublicWaypointsRequestPayload decode(RegistryFriendlyByteBuf buf) {
            return new PublicWaypointsRequestPayload();
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record PublicWaypointAddPayload(PublicWaypoint waypoint) implements CustomPacketPayload {
        public static final Type<PublicWaypointAddPayload> TYPE = new Type<>(PUBLIC_WAYPOINT_ADD_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, PublicWaypointAddPayload> STREAM_CODEC = StreamCodec.of(
                PublicWaypointAddPayload::encode, PublicWaypointAddPayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, PublicWaypointAddPayload payload) {
            PublicWaypoint.encode(buf, payload.waypoint);
        }

        public static PublicWaypointAddPayload decode(RegistryFriendlyByteBuf buf) {
            return new PublicWaypointAddPayload(PublicWaypoint.decode(buf));
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record PublicWaypointAddResultPayload(String status, String name) implements CustomPacketPayload {
        public static final Type<PublicWaypointAddResultPayload> TYPE = new Type<>(PUBLIC_WAYPOINT_ADD_RESULT_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, PublicWaypointAddResultPayload> STREAM_CODEC = StreamCodec.of(
                PublicWaypointAddResultPayload::encode, PublicWaypointAddResultPayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, PublicWaypointAddResultPayload payload) {
            buf.writeUtf(payload.status);
            buf.writeUtf(payload.name);
        }

        public static PublicWaypointAddResultPayload decode(RegistryFriendlyByteBuf buf) {
            return new PublicWaypointAddResultPayload(
                    buf.readUtf(MAX_WAYPOINT_FIELD_LENGTH),
                    buf.readUtf(MAX_WAYPOINT_FIELD_LENGTH)
            );
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record VoxyCapabilityRequestPayload() implements CustomPacketPayload {
        public static final Type<VoxyCapabilityRequestPayload> TYPE = new Type<>(VOXY_CAPABILITY_REQUEST_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, VoxyCapabilityRequestPayload> STREAM_CODEC = StreamCodec.of(
                VoxyCapabilityRequestPayload::encode, VoxyCapabilityRequestPayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, VoxyCapabilityRequestPayload payload) {
        }

        public static VoxyCapabilityRequestPayload decode(RegistryFriendlyByteBuf buf) {
            return new VoxyCapabilityRequestPayload();
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record VoxyCapabilityPayload(boolean enabled, String reason) implements CustomPacketPayload {
        public static final Type<VoxyCapabilityPayload> TYPE = new Type<>(VOXY_CAPABILITY_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, VoxyCapabilityPayload> STREAM_CODEC = StreamCodec.of(
                VoxyCapabilityPayload::encode, VoxyCapabilityPayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, VoxyCapabilityPayload payload) {
            buf.writeBoolean(payload.enabled);
            buf.writeUtf(payload.reason);
        }

        public static VoxyCapabilityPayload decode(RegistryFriendlyByteBuf buf) {
            return new VoxyCapabilityPayload(buf.readBoolean(), buf.readUtf());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record VoxyRegionMeta(long timestampSeconds, long sizeBytes) {
        public static void encode(RegistryFriendlyByteBuf buf, VoxyRegionMeta meta) {
            buf.writeLong(meta.timestampSeconds);
            buf.writeLong(meta.sizeBytes);
        }

        public static VoxyRegionMeta decode(RegistryFriendlyByteBuf buf) {
            return new VoxyRegionMeta(buf.readLong(), buf.readLong());
        }
    }

    public record VoxySyncRequestPayload(String dimensionId, Map<String, VoxyRegionMeta> clientMeta) implements CustomPacketPayload {
        public static final Type<VoxySyncRequestPayload> TYPE = new Type<>(VOXY_SYNC_REQUEST_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, VoxySyncRequestPayload> STREAM_CODEC = StreamCodec.of(
                VoxySyncRequestPayload::encode, VoxySyncRequestPayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, VoxySyncRequestPayload payload) {
            buf.writeUtf(payload.dimensionId);
            buf.writeInt(payload.clientMeta.size());
            for (var entry : payload.clientMeta.entrySet()) {
                buf.writeUtf(entry.getKey());
                VoxyRegionMeta.encode(buf, entry.getValue());
            }
        }

        public static VoxySyncRequestPayload decode(RegistryFriendlyByteBuf buf) {
            String dimensionId = buf.readUtf();
            int size = buf.readInt();
            Map<String, VoxyRegionMeta> metaMap = new HashMap<>();
            for (int i = 0; i < size; i++) {
                metaMap.put(buf.readUtf(), VoxyRegionMeta.decode(buf));
            }
            return new VoxySyncRequestPayload(dimensionId, metaMap);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record VoxySyncStartPayload(String syncId, String dimensionId, int totalRegions, long totalBytes) implements CustomPacketPayload {
        public static final Type<VoxySyncStartPayload> TYPE = new Type<>(VOXY_SYNC_START_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, VoxySyncStartPayload> STREAM_CODEC = StreamCodec.of(
                VoxySyncStartPayload::encode, VoxySyncStartPayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, VoxySyncStartPayload payload) {
            buf.writeUtf(payload.syncId);
            buf.writeUtf(payload.dimensionId);
            buf.writeInt(payload.totalRegions);
            buf.writeLong(payload.totalBytes);
        }

        public static VoxySyncStartPayload decode(RegistryFriendlyByteBuf buf) {
            return new VoxySyncStartPayload(buf.readUtf(), buf.readUtf(), buf.readInt(), buf.readLong());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record VoxyRegionPartPayload(String syncId, String dimensionId, int regionX, int regionZ,
                                        int partIndex, int totalParts, long byteOffset, long totalBytes,
                                        long timestampSeconds, byte[] data) implements CustomPacketPayload {
        public static final Type<VoxyRegionPartPayload> TYPE = new Type<>(VOXY_REGION_PART_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, VoxyRegionPartPayload> STREAM_CODEC = StreamCodec.of(
                VoxyRegionPartPayload::encode, VoxyRegionPartPayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, VoxyRegionPartPayload payload) {
            buf.writeUtf(payload.syncId);
            buf.writeUtf(payload.dimensionId);
            buf.writeInt(payload.regionX);
            buf.writeInt(payload.regionZ);
            buf.writeInt(payload.partIndex);
            buf.writeInt(payload.totalParts);
            buf.writeLong(payload.byteOffset);
            buf.writeLong(payload.totalBytes);
            buf.writeLong(payload.timestampSeconds);
            buf.writeByteArray(payload.data);
        }

        public static VoxyRegionPartPayload decode(RegistryFriendlyByteBuf buf) {
            return new VoxyRegionPartPayload(
                    buf.readUtf(),
                    buf.readUtf(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readLong(),
                    buf.readLong(),
                    buf.readLong(),
                    buf.readByteArray()
            );
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record VoxySyncProgressPayload(String syncId, int processedRegions, int totalRegions,
                                          long processedBytes, long totalBytes, String status) implements CustomPacketPayload {
        public static final Type<VoxySyncProgressPayload> TYPE = new Type<>(VOXY_SYNC_PROGRESS_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, VoxySyncProgressPayload> STREAM_CODEC = StreamCodec.of(
                VoxySyncProgressPayload::encode, VoxySyncProgressPayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, VoxySyncProgressPayload payload) {
            buf.writeUtf(payload.syncId);
            buf.writeInt(payload.processedRegions);
            buf.writeInt(payload.totalRegions);
            buf.writeLong(payload.processedBytes);
            buf.writeLong(payload.totalBytes);
            buf.writeUtf(payload.status);
        }

        public static VoxySyncProgressPayload decode(RegistryFriendlyByteBuf buf) {
            return new VoxySyncProgressPayload(buf.readUtf(), buf.readInt(), buf.readInt(),
                    buf.readLong(), buf.readLong(), buf.readUtf());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record VoxySyncCompletePayload(String syncId, boolean success, String message,
                                          int transferredRegions, long transferredBytes) implements CustomPacketPayload {
        public static final Type<VoxySyncCompletePayload> TYPE = new Type<>(VOXY_SYNC_COMPLETE_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, VoxySyncCompletePayload> STREAM_CODEC = StreamCodec.of(
                VoxySyncCompletePayload::encode, VoxySyncCompletePayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, VoxySyncCompletePayload payload) {
            buf.writeUtf(payload.syncId);
            buf.writeBoolean(payload.success);
            buf.writeUtf(payload.message);
            buf.writeInt(payload.transferredRegions);
            buf.writeLong(payload.transferredBytes);
        }

        public static VoxySyncCompletePayload decode(RegistryFriendlyByteBuf buf) {
            return new VoxySyncCompletePayload(buf.readUtf(), buf.readBoolean(), buf.readUtf(),
                    buf.readInt(), buf.readLong());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}

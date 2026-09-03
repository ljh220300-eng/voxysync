package com.nexus.voxysync.client.voxy;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Voxy 反射桥实现。
 *
 * <p>直接调用 Voxy 官方导入管线：构造 {@code WorldImporter(WorldEngine, Level, ServiceManager,
 * BooleanSupplier)} 并调用 {@code importRegionDirectoryAsync(File)}，经
 * {@code ImportManager.makeAndRunIfNone} 保证每个世界只跑一个 importer。</p>
 *
 * <p>1.20.1 移植版 voxy-0.2.7-alpha 的 8/8 方法/字段签名已逐条核验匹配
 * （见 VoxySync 交接文档 §4），本实现原样可用。改编自 MapSyncer-rebuild（GPL-3.0）。</p>
 */
public class VoxyBridgeImpl implements IVoxyBridge {
    private final Method getInstanceMethod;
    private final Method ofEngineMethod;
    private final Method getImportManagerMethod;
    private final Method getServiceManagerMethod;
    private final Field savingServiceRateLimiterField;
    private final Constructor<?> worldImporterConstructor;
    private final Method importRegionDirectoryAsyncMethod;
    private final Method makeAndRunIfNoneMethod;

    public VoxyBridgeImpl() throws Exception {
        Class<?> voxyCommonClass = Class.forName("me.cortex.voxy.commonImpl.VoxyCommon");
        Class<?> worldIdentifierClass = Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier");
        Class<?> voxyInstanceClass = Class.forName("me.cortex.voxy.commonImpl.VoxyInstance");
        Class<?> worldEngineClass = Class.forName("me.cortex.voxy.common.world.WorldEngine");
        Class<?> importManagerClass = Class.forName("me.cortex.voxy.commonImpl.ImportManager");
        Class<?> serviceManagerClass = Class.forName("me.cortex.voxy.common.thread.ServiceManager");
        Class<?> worldImporterClass = Class.forName("me.cortex.voxy.commonImpl.importers.WorldImporter");

        this.getInstanceMethod = voxyCommonClass.getMethod("getInstance");
        this.ofEngineMethod = worldIdentifierClass.getMethod("ofEngine", Level.class);
        this.getImportManagerMethod = voxyInstanceClass.getMethod("getImportManager");
        this.getServiceManagerMethod = voxyInstanceClass.getMethod("getServiceManager");
        this.savingServiceRateLimiterField = voxyInstanceClass.getField("savingServiceRateLimiter");
        this.worldImporterConstructor = worldImporterClass.getConstructor(
                worldEngineClass,
                Level.class,
                serviceManagerClass,
                BooleanSupplier.class);
        this.importRegionDirectoryAsyncMethod = worldImporterClass.getMethod("importRegionDirectoryAsync", File.class);
        this.makeAndRunIfNoneMethod = importManagerClass.getMethod("makeAndRunIfNone", worldEngineClass, Supplier.class);
    }

    @Override
    public boolean isAvailable(Minecraft client) {
        try {
            return client != null
                    && client.level != null
                    && getInstanceMethod.invoke(null) != null
                    && ofEngineMethod.invoke(null, client.level) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean startImport(Minecraft client, Path regionDirectory) throws Exception {
        Object instance = getInstanceMethod.invoke(null);
        if (instance == null || client == null || client.level == null) {
            return false;
        }
        Object engine = ofEngineMethod.invoke(null, client.level);
        if (engine == null) {
            return false;
        }
        Object importManager = getImportManagerMethod.invoke(instance);
        Object serviceManager = getServiceManagerMethod.invoke(instance);
        BooleanSupplier limiter = (BooleanSupplier) savingServiceRateLimiterField.get(instance);

        Supplier<Object> factory = () -> {
            try {
                Object importer = worldImporterConstructor.newInstance(engine, client.level, serviceManager, limiter);
                importRegionDirectoryAsyncMethod.invoke(importer, regionDirectory.toFile());
                return importer;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        return (Boolean) makeAndRunIfNoneMethod.invoke(importManager, engine, factory);
    }

    @Override
    public boolean isImportBusy(Minecraft client) throws Exception {
        Object instance = getInstanceMethod.invoke(null);
        if (instance == null || client == null || client.level == null) {
            return false;
        }
        Object engine = ofEngineMethod.invoke(null, client.level);
        if (engine == null) {
            return false;
        }
        Object importManager = getImportManagerMethod.invoke(instance);
        if (importManager == null) {
            return false;
        }
        // ImportManager.activeImporters: Map<WorldEngine, ImportTask>
        try {
            java.lang.reflect.Field active = importManager.getClass().getDeclaredField("activeImporters");
            active.setAccessible(true);
            Object map = active.get(importManager);
            if (map instanceof java.util.Map<?, ?> m) {
                return m.containsKey(engine);
            }
        } catch (NoSuchFieldException ignored) {
            // 字段名变化时保守返回 busy=false，不影响主流程
        }
        return false;
    }
}

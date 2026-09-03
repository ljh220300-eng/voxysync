package com.mapsyncer.client.voxy;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

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
}

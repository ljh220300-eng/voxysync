package com.nexus.voxysync.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 导入状态跟踪（manifest = 稳定目录里的 imported-regions.json）。
 * 记录哪些区域文件已经完成“转换”（Stage A），避免每次登录重复渲染；
 * 未记录的 = 待导入（首次全量 / 之后增量）。
 */
public final class VoxyImportTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger(VoxyImportTracker.class);
    private static final Gson GSON = new GsonBuilder().create();
    private static final Type SET_TYPE = new TypeToken<Set<String>>() {}.getType();

    private final Path manifestFile;
    private final Set<String> imported = new HashSet<>();

    public VoxyImportTracker(Path stableDir) {
        this.manifestFile = stableDir.resolve("imported-regions.json");
        load();
    }

    /** 是否已存在导入清单（首次全量 vs 之后增量） */
    public boolean hasManifest() {
        return Files.exists(manifestFile);
    }

    /** 列出需要导入的文件（稳定目录中尚未导入者，字典序） */
    public List<String> pending(Set<String> stableNames) {
        List<String> result = new ArrayList<>();
        for (String name : stableNames) {
            if (!imported.contains(name)) {
                result.add(name);
            }
        }
        result.sort(String::compareTo);
        return result;
    }

    /** 标记已导入并落盘 */
    public synchronized void markImported(Collection<String> names) {
        imported.addAll(names);
        save();
    }

    /** 批量视为已导入（用于旧版 .full-import-done 迁移：那些文件此前已渲染过） */
    public synchronized void bootstrapAll(Set<String> stableNames) {
        imported.clear();
        imported.addAll(stableNames);
        save();
    }

    public int importedCount() {
        return imported.size();
    }

    private void load() {
        if (!Files.exists(manifestFile)) {
            return;
        }
        try {
            Set<String> loaded = GSON.fromJson(Files.readString(manifestFile), SET_TYPE);
            if (loaded != null) {
                imported.clear();
                imported.addAll(loaded);
            }
        } catch (Exception e) {
            LOGGER.warn("加载 imported-regions.json 失败，将按全部待导入处理", e);
            imported.clear();
        }
    }

    private void save() {
        try {
            Files.createDirectories(manifestFile.getParent());
            Files.writeString(manifestFile, GSON.toJson(imported));
        } catch (IOException e) {
            LOGGER.warn("保存 imported-regions.json 失败", e);
        }
    }
}

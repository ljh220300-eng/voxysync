package com.mapsyncer.nbt;

import com.mapsyncer.util.BoundedStringPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * NBT读取器 - 零依赖实现
 *
 * <p>用于解析Minecraft NBT（Named Binary Tag）格式的二进制数据。
 * 所有数据采用大端序（Big-Endian）存储，符合Minecraft的NBT规范。</p>
 *
 * <p>安全限制：为防止恶意NBT数据导致内存溢出，对数组大小、列表长度和嵌套深度设有限制。
 * 如遇到超出限制的数据，请将日志信息汇报给开发者以便分析。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * try (NbtReader reader = new NbtReader(inputStream)) {
 *     Tag.Compound root = reader.readDocument();
 *     // 处理NBT数据...
 * }
 * }</pre>
 *
 * @see Tag
 */
public class NbtReader implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(NbtReader.class);

    /**
     * 数组大小上限（ByteArray/IntArray/LongArray）
     *
     * <p>实际最大需求约 25,000（单区块所有section的block_states.data），
     * 此限制设为5倍 = 125,000，足够容纳正常数据。</p>
     */
    private static final int MAX_ARRAY_SIZE = 125_000;

    /**
     * 列表长度上限
     *
     * <p>实际最大需求约 1,000（block palette），
     * 此限制设为5倍 = 5,000，足够容纳正常数据。</p>
     */
    private static final int MAX_LIST_SIZE = 5_000;

    /**
     * Compound嵌套深度上限
     *
     * <p>实际最大需求约 5-6层（Chunk→sections→section→block_states→palette），
     * 此限制设为5倍 = 30层，足够容纳正常数据。</p>
     */
    private static final int MAX_COMPOUND_DEPTH = 30;

    /**
     * Maximum modified-UTF payload size accepted for strings that are kept.
     *
     * <p>Chunk map generation only needs short identifiers such as block,
     * biome, status, and heightmap names. Very large strings are skipped as a
     * corrupt or irrelevant chunk payload instead of letting readUTF allocate
     * until the generator thread runs out of heap.</p>
     */
    private static final int MAX_STRING_UTF_BYTES = 32_767;

    private static final Set<String> SKIPPED_TAG_NAMES = Set.of(
            "fluid_ticks",
            "block_ticks",
            "TileTicks",
            "ToBeTicked",
            "LiquidsToBeTicked",
            "entities",
            "Entities",
            "block_entities",
            "TileEntities",
            "PostProcessing",
            "Lights",
            "CarvingMasks",
            "structures"
    );

    /** 数据输入流，用于读取二进制NBT数据 */
    private final DataInputStream in;

    /** 当前嵌套深度 */
    private int currentDepth = 0;

    /**
     * 构造NBT读取器
     *
     * @param in 输入流，包含NBT格式的二进制数据
     */
    public NbtReader(InputStream in) {
        this.in = new DataInputStream(in);
    }

    /**
     * 读取完整的NBT文档（根Compound）
     *
     * <p>读取整个NBT文档，返回根Compound标签。
     * NBT文档必须以Compound类型开头。</p>
     *
     * @return 根Compound标签
     * @throws IOException 如果读取失败或文档格式不正确
     */
    public Tag.Compound readDocument() throws IOException {
        byte type = in.readByte();
        if (type != Tag.TAG_COMPOUND) {
            throw new IOException("NBT文档必须以Compound开头，实际类型: " + type);
        }
        String name = readUtf();
        return readCompoundContent(name);
    }

    /**
     * 读取单个Tag（包含类型和名称）
     *
     * <p>从输入流中读取一个完整的标签，包括类型标识、名称和数据内容。</p>
     *
     * @return 读取的Tag对象
     * @throws IOException 如果读取失败
     */
    public Tag readTag() throws IOException {
        byte type = in.readByte();
        if (type == Tag.TAG_END) {
            return new Tag.End();
        }
        String name = readUtf();
        return readPayload(type, name);
    }

    /**
     * 读取Tag内容（不含类型和名称前缀）
     *
     * <p>根据给定的类型标识读取对应的数据内容。</p>
     *
     * @param type NBT类型标识
     * @param name 标签名称
     * @return 读取的Tag对象
     * @throws IOException 如果读取失败或类型未知
     */
    private Tag readPayload(byte type, String name) throws IOException {
        switch (type) {
            case Tag.TAG_END:
                return new Tag.End();
            case Tag.TAG_BYTE:
                return new Tag.Byte(name, in.readByte());
            case Tag.TAG_SHORT:
                return new Tag.Short(name, in.readShort());
            case Tag.TAG_INT:
                return new Tag.Int(name, in.readInt());
            case Tag.TAG_LONG:
                return new Tag.Long(name, in.readLong());
            case Tag.TAG_FLOAT:
                return new Tag.Float(name, in.readFloat());
            case Tag.TAG_DOUBLE:
                return new Tag.Double(name, in.readDouble());
            case Tag.TAG_BYTE_ARRAY:
                return readByteArray(name);
            case Tag.TAG_STRING:
                return new Tag.StringTag(name, readUtf());
            case Tag.TAG_LIST:
                return readListContent(name);
            case Tag.TAG_COMPOUND:
                return readCompoundContent(name);
            case Tag.TAG_INT_ARRAY:
                return readIntArray(name);
            case Tag.TAG_LONG_ARRAY:
                return readLongArray(name);
            default:
                throw new IOException("未知NBT类型: " + type);
        }
    }

    /**
     * 读取ByteArray类型标签
     *
     * @param name 标签名称
     * @return ByteArray标签对象
     * @throws IOException 如果读取失败或长度超限
     */
    private Tag.ByteArray readByteArray(String name) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            throw new IOException("ByteArray长度不能为负: " + length);
        }
        if (length > MAX_ARRAY_SIZE) {
            LOGGER.warn("NBT size limit exceeded: ByteArray '{}' length={}, max={}. " +
                    "Please report this with the MCA file location for analysis.", name, length, MAX_ARRAY_SIZE);
            throw new IOException("ByteArray长度超限: " + length + " (最大 " + MAX_ARRAY_SIZE + ")");
        }
        byte[] data = new byte[length];
        in.readFully(data);
        return new Tag.ByteArray(name, data);
    }

    /**
     * 读取IntArray类型标签
     *
     * @param name 标签名称
     * @return IntArray标签对象
     * @throws IOException 如果读取失败或长度超限
     */
    private Tag.IntArray readIntArray(String name) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            throw new IOException("IntArray长度不能为负: " + length);
        }
        if (length > MAX_ARRAY_SIZE) {
            LOGGER.warn("NBT size limit exceeded: IntArray '{}' length={}, max={}. " +
                    "Please report this with the MCA file location for analysis.", name, length, MAX_ARRAY_SIZE);
            throw new IOException("IntArray长度超限: " + length + " (最大 " + MAX_ARRAY_SIZE + ")");
        }
        int[] data = new int[length];
        for (int i = 0; i < length; i++) {
            data[i] = in.readInt();
        }
        return new Tag.IntArray(name, data);
    }

    /**
     * 读取LongArray类型标签
     *
     * @param name 标签名称
     * @return LongArray标签对象
     * @throws IOException 如果读取失败或长度超限
     */
    private Tag.LongArray readLongArray(String name) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            throw new IOException("LongArray长度不能为负: " + length);
        }
        if (length > MAX_ARRAY_SIZE) {
            LOGGER.warn("NBT size limit exceeded: LongArray '{}' length={}, max={}. " +
                    "Please report this with the MCA file location for analysis.", name, length, MAX_ARRAY_SIZE);
            throw new IOException("LongArray长度超限: " + length + " (最大 " + MAX_ARRAY_SIZE + ")");
        }
        long[] data = new long[length];
        for (int i = 0; i < length; i++) {
            data[i] = in.readLong();
        }
        return new Tag.LongArray(name, data);
    }

    /**
     * 读取List类型标签内容
     *
     * <p>List中的所有元素必须是相同类型。元素没有名称，使用空字符串作为名称。</p>
     *
     * @param name 标签名称
     * @return ListTag标签对象
     * @throws IOException 如果读取失败或长度超限
     */
    private Tag.ListTag readListContent(String name) throws IOException {
        byte elementType = in.readByte();
        int length = in.readInt();
        if (length < 0) {
            throw new IOException("List长度不能为负: " + length);
        }
        if (length > MAX_LIST_SIZE) {
            LOGGER.warn("NBT size limit exceeded: List '{}' length={}, max={}. " +
                    "Please report this with the MCA file location for analysis.", name, length, MAX_LIST_SIZE);
            throw new IOException("List长度超限: " + length + " (最大 " + MAX_LIST_SIZE + ")");
        }
        List<Tag> items = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            // List元素没有名称，传入空字符串
            items.add(readPayload(elementType, ""));
        }
        return new Tag.ListTag(name, elementType, items);
    }

    /**
     * 读取Compound类型标签内容
     *
     * <p>Compound是一个键值对集合，以TAG_END作为结束标记。
     * 子标签按读取顺序保存。</p>
     *
     * <p>检查嵌套深度，防止无限递归。</p>
     *
     * @param name 标签名称
     * @return Compound标签对象
     * @throws IOException 如果读取失败或嵌套深度超限
     */
    private Tag.Compound readCompoundContent(String name) throws IOException {
        currentDepth++;
        if (currentDepth > MAX_COMPOUND_DEPTH) {
            LOGGER.warn("NBT depth limit exceeded: Compound '{}' depth={}, max={}. " +
                    "Please report this with the MCA file location for analysis.", name, currentDepth, MAX_COMPOUND_DEPTH);
            throw new IOException("Compound嵌套深度超限: " + currentDepth + " (最大 " + MAX_COMPOUND_DEPTH + ")");
        }

        Map<String, Tag> children = new LinkedHashMap<>();
        while (true) {
            byte type = in.readByte();
            if (type == Tag.TAG_END) {
                currentDepth--;
                break;
            }
            String childName = readUtf();
            if (shouldSkipTag(childName)) {
                skipPayload(type);
                continue;
            }
            children.put(childName, readPayload(type, childName));
        }
        return new Tag.Compound(name, children);
    }

    private boolean shouldSkipTag(String name) {
        return SKIPPED_TAG_NAMES.contains(name);
    }

    private String readUtf() throws IOException {
        int utfLength = in.readUnsignedShort();
        if (utfLength > MAX_STRING_UTF_BYTES) {
            skipFully(utfLength);
            throw new IOException("NBT string length exceeded: " + utfLength + " (max " + MAX_STRING_UTF_BYTES + ")");
        }

        byte[] utfData = new byte[utfLength + 2];
        utfData[0] = (byte) ((utfLength >>> 8) & 0xFF);
        utfData[1] = (byte) (utfLength & 0xFF);
        in.readFully(utfData, 2, utfLength);
        try (DataInputStream utfIn = new DataInputStream(new ByteArrayInputStream(utfData))) {
            return BoundedStringPool.canonicalize(utfIn.readUTF());
        } catch (OutOfMemoryError e) {
            throw new IOException("Failed to decode NBT string: Java heap space", e);
        }
    }

    private void skipPayload(byte type) throws IOException {
        switch (type) {
            case Tag.TAG_END:
                return;
            case Tag.TAG_BYTE:
                skipFully(1);
                return;
            case Tag.TAG_SHORT:
                skipFully(2);
                return;
            case Tag.TAG_INT:
            case Tag.TAG_FLOAT:
                skipFully(4);
                return;
            case Tag.TAG_LONG:
            case Tag.TAG_DOUBLE:
                skipFully(8);
                return;
            case Tag.TAG_BYTE_ARRAY: {
                int length = readNonNegativeLength("ByteArray");
                skipFully(length);
                return;
            }
            case Tag.TAG_STRING: {
                int length = in.readUnsignedShort();
                skipFully(length);
                return;
            }
            case Tag.TAG_LIST: {
                byte elementType = in.readByte();
                int length = readNonNegativeLength("List");
                for (int i = 0; i < length; i++) {
                    skipPayload(elementType);
                }
                return;
            }
            case Tag.TAG_COMPOUND:
                while (true) {
                    byte childType = in.readByte();
                    if (childType == Tag.TAG_END) {
                        return;
                    }
                    skipPayload(Tag.TAG_STRING);
                    skipPayload(childType);
                }
            case Tag.TAG_INT_ARRAY: {
                int length = readNonNegativeLength("IntArray");
                skipFully((long) length * Integer.BYTES);
                return;
            }
            case Tag.TAG_LONG_ARRAY: {
                int length = readNonNegativeLength("LongArray");
                skipFully((long) length * Long.BYTES);
                return;
            }
            default:
                throw new IOException("未知NBT类型: " + type);
        }
    }

    private int readNonNegativeLength(String tagType) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            throw new IOException(tagType + "长度不能为负: " + length);
        }
        return length;
    }

    private void skipFully(long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() == -1) {
                    throw new EOFException("Unexpected EOF while skipping NBT payload");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    /**
     * 关闭读取器并释放资源
     *
     * @throws IOException 如果关闭时发生I/O错误
     */
    @Override
    public void close() throws IOException {
        in.close();
    }
}

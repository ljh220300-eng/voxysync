package com.mapsyncer.network;

/**
 * Client-side cached metadata for one synced region.
 *
 * @param timestampSeconds region timestamp in seconds
 * @param hash CRC32 hash as an eight-character hexadecimal string
 */
public record ClientMeta(long timestampSeconds, String hash) {
}

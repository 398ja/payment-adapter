package xyz.tcheeric.payment.adapter.cash.nostr.codec;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Builds NDEF (NFC Data Exchange Format) URI records for nostr+cash:// URIs.
 * This is a pure data format builder with no hardware dependency.
 *
 * <p>NDEF URI Record format:
 * <ul>
 *   <li>TNF (Type Name Format): 0x01 (NFC Forum well-known type)</li>
 *   <li>Type: "U" (URI Record Type)</li>
 *   <li>Identifier code: 0x00 (no abbreviation, full URI)</li>
 *   <li>URI field: the full nostr+cash:// URI</li>
 * </ul>
 */
public class NfcNdefBuilder {

    private static final byte TNF_WELL_KNOWN = 0x01;
    private static final byte[] URI_TYPE = new byte[]{'U'};
    private static final byte URI_ID_CODE_NONE = 0x00;
    // Flags: MB=1, ME=1, CF=0, SR=1, IL=0
    private static final byte FLAGS_SHORT_RECORD = (byte) 0xD1;

    /**
     * Build an NDEF message containing a single URI record for a nostr+cash:// URI.
     *
     * @param uri the NostrCashUri to encode
     * @return NDEF message bytes
     */
    public static byte[] buildNdefMessage(NostrCashUri uri) {
        return buildNdefMessage(uri.encode());
    }

    /**
     * Build an NDEF message from a URI string.
     *
     * @param uriString the full URI string
     * @return NDEF message bytes
     */
    public static byte[] buildNdefMessage(String uriString) {
        byte[] uriBytes = uriString.getBytes(StandardCharsets.UTF_8);
        // Payload = identifier code (1 byte) + URI bytes
        byte[] payload = new byte[1 + uriBytes.length];
        payload[0] = URI_ID_CODE_NONE;
        System.arraycopy(uriBytes, 0, payload, 1, uriBytes.length);

        return buildRecord(payload);
    }

    /**
     * Build a single NDEF URI record.
     */
    private static byte[] buildRecord(byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        if (payload.length <= 255) {
            // Short record (SR=1)
            out.write(FLAGS_SHORT_RECORD);
            out.write(URI_TYPE.length);     // TYPE_LENGTH
            out.write(payload.length);       // PAYLOAD_LENGTH (1 byte for SR)
            out.write(URI_TYPE, 0, URI_TYPE.length);
            out.write(payload, 0, payload.length);
        } else {
            // Normal record (SR=0)
            byte flags = (byte) (0xC1); // MB=1, ME=1, CF=0, SR=0, IL=0, TNF=0x01
            out.write(flags);
            out.write(URI_TYPE.length);
            // PAYLOAD_LENGTH (4 bytes, big-endian)
            out.write((payload.length >> 24) & 0xFF);
            out.write((payload.length >> 16) & 0xFF);
            out.write((payload.length >> 8) & 0xFF);
            out.write(payload.length & 0xFF);
            out.write(URI_TYPE, 0, URI_TYPE.length);
            out.write(payload, 0, payload.length);
        }

        return out.toByteArray();
    }

    /**
     * Estimate the NDEF message size for a NostrCashUri.
     *
     * @param uri the URI to estimate
     * @return approximate byte count
     */
    public static int estimateSize(NostrCashUri uri) {
        String encoded = uri.encode();
        // Header (3 bytes for short record) + 1 byte URI code + URI bytes
        return 3 + 1 + encoded.getBytes(StandardCharsets.UTF_8).length;
    }
}

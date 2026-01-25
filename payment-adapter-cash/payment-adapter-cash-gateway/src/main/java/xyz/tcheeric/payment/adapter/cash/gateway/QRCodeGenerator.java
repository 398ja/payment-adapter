package xyz.tcheeric.payment.adapter.cash.gateway;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.payment.adapter.cash.nostr.codec.NostrCashUri;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * QR code generator for cash payment invoices.
 * Uses ZXing library to create scannable QR codes from NostrCashUri.
 */
@Slf4j
public class QRCodeGenerator {

    private static final int DEFAULT_SIZE = 300;
    private static final ErrorCorrectionLevel DEFAULT_ERROR_CORRECTION = ErrorCorrectionLevel.M;

    private final int size;
    private final ErrorCorrectionLevel errorCorrection;

    public QRCodeGenerator() {
        this(DEFAULT_SIZE, DEFAULT_ERROR_CORRECTION);
    }

    public QRCodeGenerator(int size) {
        this(size, DEFAULT_ERROR_CORRECTION);
    }

    public QRCodeGenerator(int size, ErrorCorrectionLevel errorCorrection) {
        this.size = size;
        this.errorCorrection = errorCorrection;
    }

    /**
     * Generate a QR code image from a NostrCashUri.
     *
     * @param uri the cash payment URI
     * @return BufferedImage of the QR code
     * @throws WriterException if encoding fails
     */
    public BufferedImage generate(NostrCashUri uri) throws WriterException {
        return generate(uri.encode());
    }

    /**
     * Generate a QR code image from a URI string.
     *
     * @param content the content to encode
     * @return BufferedImage of the QR code
     * @throws WriterException if encoding fails
     */
    public BufferedImage generate(String content) throws WriterException {
        log.debug("Generating QR code for content of length: {}", content.length());

        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.ERROR_CORRECTION, errorCorrection);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 1);

        QRCodeWriter writer = new QRCodeWriter();
        BitMatrix bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints);

        return MatrixToImageWriter.toBufferedImage(bitMatrix);
    }

    /**
     * Generate a QR code as PNG bytes.
     *
     * @param uri the cash payment URI
     * @return PNG image bytes
     * @throws WriterException if encoding fails
     * @throws IOException if image writing fails
     */
    public byte[] generatePng(NostrCashUri uri) throws WriterException, IOException {
        return generatePng(uri.encode());
    }

    /**
     * Generate a QR code as PNG bytes.
     *
     * @param content the content to encode
     * @return PNG image bytes
     * @throws WriterException if encoding fails
     * @throws IOException if image writing fails
     */
    public byte[] generatePng(String content) throws WriterException, IOException {
        BufferedImage image = generate(content);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", baos);
        return baos.toByteArray();
    }

    /**
     * Generate a QR code as Base64-encoded PNG.
     *
     * @param uri the cash payment URI
     * @return Base64-encoded PNG string
     * @throws WriterException if encoding fails
     * @throws IOException if image writing fails
     */
    public String generateBase64Png(NostrCashUri uri) throws WriterException, IOException {
        byte[] png = generatePng(uri);
        return java.util.Base64.getEncoder().encodeToString(png);
    }

    /**
     * Generate a data URI for embedding in HTML.
     *
     * @param uri the cash payment URI
     * @return data URI string (data:image/png;base64,...)
     * @throws WriterException if encoding fails
     * @throws IOException if image writing fails
     */
    public String generateDataUri(NostrCashUri uri) throws WriterException, IOException {
        return "data:image/png;base64," + generateBase64Png(uri);
    }

    /**
     * Estimate the QR code data size.
     * Per spec, target <300 bytes for good scannability.
     *
     * @param uri the cash payment URI
     * @return estimated byte count
     */
    public static int estimateSize(NostrCashUri uri) {
        return uri.encode().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /**
     * Check if URI is within recommended size limits.
     * QR codes work best with <300 bytes.
     *
     * @param uri the cash payment URI
     * @return true if within limits
     */
    public static boolean isWithinSizeLimit(NostrCashUri uri) {
        return estimateSize(uri) <= 300;
    }

    /**
     * Builder for creating QRCodeGenerator with custom settings.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int size = DEFAULT_SIZE;
        private ErrorCorrectionLevel errorCorrection = DEFAULT_ERROR_CORRECTION;

        public Builder size(int size) {
            this.size = size;
            return this;
        }

        public Builder errorCorrection(ErrorCorrectionLevel level) {
            this.errorCorrection = level;
            return this;
        }

        /**
         * Use low error correction (7%) for smaller QR codes.
         */
        public Builder lowErrorCorrection() {
            this.errorCorrection = ErrorCorrectionLevel.L;
            return this;
        }

        /**
         * Use medium error correction (15%) - default.
         */
        public Builder mediumErrorCorrection() {
            this.errorCorrection = ErrorCorrectionLevel.M;
            return this;
        }

        /**
         * Use quartile error correction (25%) for durability.
         */
        public Builder quartileErrorCorrection() {
            this.errorCorrection = ErrorCorrectionLevel.Q;
            return this;
        }

        /**
         * Use high error correction (30%) for maximum durability.
         */
        public Builder highErrorCorrection() {
            this.errorCorrection = ErrorCorrectionLevel.H;
            return this;
        }

        public QRCodeGenerator build() {
            return new QRCodeGenerator(size, errorCorrection);
        }
    }
}

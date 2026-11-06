package org.netpilot.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.netpilot.model.HotspotUser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Builds hotspot auto-login links and renders them as QR codes.
 */
public class QrCodeService {

    /**
     * Builds {@code <loginUrl>?username=..&password=..}, the GET form accepted by the default
     * MikroTik hotspot login page.
     *
     * @throws IllegalArgumentException if {@code loginUrl} is not an http(s) URL
     */
    public String loginLink(String loginUrl, HotspotUser user) {
        validateLoginUrl(loginUrl);
        String separator = loginUrl.contains("?") ? "&" : "?";
        return loginUrl + separator
                + "username=" + encode(user.username())
                + "&password=" + encode(user.password());
    }

    public static void validateLoginUrl(String loginUrl) {
        try {
            URI uri = URI.create(loginUrl);
            String scheme = uri.getScheme();
            if (uri.getHost() == null || scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException(
                    "Login URL must look like http://10.10.0.1/login (got: " + loginUrl + ")");
        }
    }

    /**
     * @return PNG bytes of a square QR code
     */
    public byte[] png(String content, int sizePx) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(
                    content, BarcodeFormat.QR_CODE, sizePx, sizePx,
                    Map.of(EncodeHintType.MARGIN, 1,
                            EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                            EncodeHintType.CHARACTER_SET, "UTF-8"));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (WriterException e) {
            throw new IllegalArgumentException("Cannot encode QR content", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

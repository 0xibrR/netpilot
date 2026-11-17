package org.netpilot.service.router;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The RouterOS API's wire format (TCP 8728, or 8729 with TLS): a sentence is a list of words, each
 * word its length (1 to 5 bytes) then its UTF-8 bytes, and an empty word ends the sentence. Replies
 * start with {@code !re}, {@code !done}, {@code !trap}, {@code !empty} or {@code !fatal}; attributes
 * are words like {@code =name=value}; {@code .tag=} ties a reply to its command.
 */
final class ApiProtocol {

    /** Longest word accepted from the router; protects against a broken stream. */
    static final int MAX_WORD = 16 * 1024 * 1024;

    private ApiProtocol() {
    }

    /** One reply sentence. */
    record Reply(String type, Optional<String> tag, Map<String, String> attributes) {

        Reply {
            attributes = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        }

        String get(String name) {
            return attributes.getOrDefault(name, "");
        }

        boolean is(String kind) {
            return type.equals(kind);
        }

        static Reply of(List<String> words) {
            String type = words.isEmpty() ? "" : words.getFirst();
            Optional<String> tag = Optional.empty();
            Map<String, String> attributes = new LinkedHashMap<>();
            for (String word : words.subList(Math.min(1, words.size()), words.size())) {
                if (word.startsWith(".tag=")) {
                    tag = Optional.of(word.substring(5));
                } else if (word.startsWith("=")) {
                    int split = word.indexOf('=', 1);
                    if (split > 0) {
                        attributes.put(word.substring(1, split), word.substring(split + 1));
                    } else {
                        attributes.put(word.substring(1), "");
                    }
                } else if (type.equals("!fatal")) {
                    attributes.put("message", word); // !fatal carries its reason as a bare word
                }
            }
            return new Reply(type, tag, attributes);
        }
    }

    static byte[] encodeLength(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("negative length");
        }
        if (length < 0x80) {
            return new byte[]{(byte) length};
        }
        if (length < 0x4000) {
            return new byte[]{(byte) (length >> 8 | 0x80), (byte) length};
        }
        if (length < 0x200000) {
            return new byte[]{(byte) (length >> 16 | 0xC0), (byte) (length >> 8), (byte) length};
        }
        if (length < 0x10000000) {
            return new byte[]{(byte) (length >> 24 | 0xE0), (byte) (length >> 16), (byte) (length >> 8),
                    (byte) length};
        }
        return new byte[]{(byte) 0xF0, (byte) (length >> 24), (byte) (length >> 16), (byte) (length >> 8),
                (byte) length};
    }

    static int readLength(InputStream in) throws IOException {
        int first = next(in);
        if ((first & 0x80) == 0) {
            return first;
        }
        if ((first & 0xC0) == 0x80) {
            return (first & 0x3F) << 8 | next(in);
        }
        if ((first & 0xE0) == 0xC0) {
            return (first & 0x1F) << 16 | next(in) << 8 | next(in);
        }
        if ((first & 0xF0) == 0xE0) {
            return (first & 0x0F) << 24 | next(in) << 16 | next(in) << 8 | next(in);
        }
        if (first == 0xF0) {
            return next(in) << 24 | next(in) << 16 | next(in) << 8 | next(in);
        }
        throw new IOException("Bad RouterOS API length byte " + first);
    }

    private static int next(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException("The router closed the connection");
        }
        return b;
    }

    /** Writes one sentence; the caller flushes. */
    static void writeSentence(OutputStream out, List<byte[]> words) throws IOException {
        for (byte[] word : words) {
            out.write(encodeLength(word.length));
            out.write(word);
        }
        out.write(0);
    }

    static byte[] word(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** {@code prefix + secret} as UTF-8, without ever making a String of the secret. */
    static byte[] secretWord(String prefix, char[] secret) {
        CharBuffer chars = CharBuffer.allocate(prefix.length() + secret.length);
        chars.put(prefix).put(secret).flip();
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(chars);
        byte[] out = new byte[bytes.remaining()];
        bytes.get(out);
        Arrays.fill(chars.array(), '\0');
        if (bytes.hasArray()) {
            Arrays.fill(bytes.array(), (byte) 0);
        }
        return out;
    }

    /** Reads one sentence; empty sentences (keep-alive) are skipped. */
    static List<String> readSentence(InputStream in) throws IOException {
        while (true) {
            List<String> words = new ArrayList<>();
            for (int length = readLength(in); length != 0; length = readLength(in)) {
                if (length > MAX_WORD) {
                    throw new IOException("RouterOS API word too long: " + length);
                }
                byte[] bytes = in.readNBytes(length);
                if (bytes.length < length) {
                    throw new EOFException("The router closed the connection");
                }
                words.add(new String(bytes, StandardCharsets.UTF_8));
            }
            if (!words.isEmpty()) {
                return words;
            }
        }
    }
}

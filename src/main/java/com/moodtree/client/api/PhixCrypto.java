package com.moodtree.client.api;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.text.Normalizer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** phix AuthHash 派生（规范 §2.1 / §2.4）：
 *  口令归一化（NFKC + 去首尾 JS 空白）→ scrypt 得 MK → HKDF-SHA256 得 AuthHash。
 *  与 Python 参考实现 web/core/phix_e2e.py 逐字节一致；HKDF（extract+expand）手写。 */
public final class PhixCrypto {

    private static final byte[] AUTH_INFO = "phix/v1/auth".getBytes(StandardCharsets.UTF_8);
    private static final int AUTH_HASH_LEN = 32;
    private static final int SHA256_LEN = 32;

    private PhixCrypto() { }

    /** password + auth_salt(hex) → AuthHash(hex)。v2 账号的登录凭据，绝不包含口令原文。 */
    public static String authHashHex(String password, String authSaltHex) {
        byte[] salt = hexToBytes(authSaltHex);
        byte[] mk = ScryptUtil.scrypt(normalizePassword(password).getBytes(StandardCharsets.UTF_8), salt);
        byte[] authHash = hkdfSha256(mk, salt, AUTH_INFO, AUTH_HASH_LEN);
        return bytesToHex(authHash);
    }

    /** 口令归一化：NFKC + 去掉首尾 JS 空白（与规范 §2.4 两端必须一致）。 */
    private static String normalizePassword(String password) {
        String nfkc = Normalizer.normalize(password, Normalizer.Form.NFKC);
        return trimJsWhitespace(nfkc);
    }

    private static String trimJsWhitespace(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isJsWhitespace(s.charAt(start))) start++;
        while (end > start && isJsWhitespace(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }

    /** 与 JavaScript String.prototype.trim() 一致的空白集合。 */
    private static boolean isJsWhitespace(char c) {
        if (c >= 0x09 && c <= 0x0D) return true;          // \t \n \v \f \r
        if (c == 0x20 || c == 0x00A0 || c == 0x1680) return true;
        if (c >= 0x2000 && c <= 0x200A) return true;
        return c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F
                || c == 0x3000 || c == 0xFEFF;
    }

    /** HKDF-SHA256（RFC 5869）extract + expand，纯 javax.crypto.Mac 手写。 */
    private static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int len) {
        byte[] prk = mac(salt, ikm);   // extract
        byte[] okm = new byte[len];
        byte[] t = new byte[0];
        int off = 0;
        int counter = 1;
        while (off < len) {
            byte[] data = new byte[t.length + info.length + 1];
            System.arraycopy(t, 0, data, 0, t.length);
            System.arraycopy(info, 0, data, t.length, info.length);
            data[data.length - 1] = (byte) counter;
            t = mac(prk, data);        // expand：T(i) = HMAC(PRK, T(i-1) || info || i)
            int n = Math.min(SHA256_LEN, len - off);
            System.arraycopy(t, 0, okm, off, n);
            off += n;
            counter++;
        }
        return okm;
    }

    private static byte[] mac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 不可用", e);
        }
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}

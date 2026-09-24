package com.moodtree.client.api;

import java.security.GeneralSecurityException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 7914 scrypt（N=32768, r=8, p=1, dkLen=32），纯 Java、零第三方依赖。
 *  与 phix 服务端 / Python hashlib.scrypt 逐字节一致（跨语言互操作测试钉着）。
 *  PBKDF2-HMAC-SHA256 用 javax.crypto.Mac 手写（迭代次数 c=1），
 *  Salsa20/8 核、BlockMix、ROMix 手写。 */
public final class ScryptUtil {

    private static final int N = 32768;   // 2^15
    private static final int R = 8;
    private static final int P = 1;
    private static final int DK_LEN = 32;
    private static final int SHA256_LEN = 32;

    private ScryptUtil() { }

    /** scrypt(password, salt) → 32 字节输出。 */
    public static byte[] scrypt(byte[] password, byte[] salt) {
        return scrypt(password, salt, N, R, P, DK_LEN);
    }

    private static byte[] scrypt(byte[] password, byte[] salt, int n, int r, int p, int dkLen) {
        int blockSize = 128 * r;
        // B = PBKDF2-HMAC-SHA256(P, S, 1, p * 128 * r)
        byte[] b = pbkdf2HmacSha256(password, salt, 1, p * blockSize);
        for (int i = 0; i < p; i++) {
            byte[] block = new byte[blockSize];
            System.arraycopy(b, i * blockSize, block, 0, blockSize);
            roMix(block, r, n);
            System.arraycopy(block, 0, b, i * blockSize, blockSize);
        }
        // DK = PBKDF2-HMAC-SHA256(P, B, 1, dkLen)
        return pbkdf2HmacSha256(password, b, 1, dkLen);
    }

    /** ROMix：先 N 轮填 V 表，再 N 轮按 Integerify 混合；B 就地被替换为结果。 */
    private static void roMix(byte[] b, int r, int n) {
        int blockSize = 128 * r;
        byte[] v = new byte[n * blockSize];
        byte[] x = new byte[blockSize];
        System.arraycopy(b, 0, x, 0, blockSize);

        for (int i = 0; i < n; i++) {
            System.arraycopy(x, 0, v, i * blockSize, blockSize);
            x = blockMix(x, r);
        }
        for (int i = 0; i < n; i++) {
            int j = integerify(x, r) & (n - 1);   // n 是 2 的幂，等价于 mod n
            byte[] t = new byte[blockSize];
            for (int k = 0; k < blockSize; k++) {
                t[k] = (byte) (x[k] ^ v[j * blockSize + k]);
            }
            x = blockMix(t, r);
        }
        System.arraycopy(x, 0, b, 0, blockSize);
    }

    /** BlockMix：2r 个 64 字节块做 Salsa20/8 链式混合，再按偶数/奇数交叠重排。 */
    private static byte[] blockMix(byte[] b, int r) {
        int blockSize = 128 * r;
        byte[] x = new byte[64];
        System.arraycopy(b, (2 * r - 1) * 64, x, 0, 64);   // X = 最后一个 64 字节块
        byte[] y = new byte[blockSize];
        byte[] t = new byte[64];
        for (int i = 0; i < 2 * r; i++) {
            for (int k = 0; k < 64; k++) t[k] = (byte) (x[k] ^ b[i * 64 + k]);
            salsa20_8(t, x);
            System.arraycopy(x, 0, y, i * 64, 64);
        }
        byte[] out = new byte[blockSize];
        for (int i = 0; i < r; i++) {
            System.arraycopy(y, (2 * i) * 64, out, i * 64, 64);
            System.arraycopy(y, (2 * i + 1) * 64, out, (r + i) * 64, 64);
        }
        return out;
    }

    /** Salsa20/8 核心：in/out 各 64 字节（16 个 32 位小端字），8 轮 = 4 个双轮。 */
    private static void salsa20_8(byte[] in, byte[] out) {
        int[] x = new int[16];
        for (int i = 0; i < 16; i++) x[i] = le32(in, i * 4);
        int[] z = x.clone();
        for (int round = 0; round < 8; round += 2) {
            // 列轮
            z[ 4] ^= rotl(z[ 0] + z[12], 7);
            z[ 8] ^= rotl(z[ 4] + z[ 0], 9);
            z[12] ^= rotl(z[ 8] + z[ 4], 13);
            z[ 0] ^= rotl(z[12] + z[ 8], 18);
            z[ 9] ^= rotl(z[ 5] + z[ 1], 7);
            z[13] ^= rotl(z[ 9] + z[ 5], 9);
            z[ 1] ^= rotl(z[13] + z[ 9], 13);
            z[ 5] ^= rotl(z[ 1] + z[13], 18);
            z[14] ^= rotl(z[10] + z[ 6], 7);
            z[ 2] ^= rotl(z[14] + z[10], 9);
            z[ 6] ^= rotl(z[ 2] + z[14], 13);
            z[10] ^= rotl(z[ 6] + z[ 2], 18);
            z[ 3] ^= rotl(z[15] + z[11], 7);
            z[ 7] ^= rotl(z[ 3] + z[15], 9);
            z[11] ^= rotl(z[ 7] + z[ 3], 13);
            z[15] ^= rotl(z[11] + z[ 7], 18);
            // 行轮
            z[ 1] ^= rotl(z[ 0] + z[ 3], 7);
            z[ 2] ^= rotl(z[ 1] + z[ 0], 9);
            z[ 3] ^= rotl(z[ 2] + z[ 1], 13);
            z[ 0] ^= rotl(z[ 3] + z[ 2], 18);
            z[ 6] ^= rotl(z[ 5] + z[ 4], 7);
            z[ 7] ^= rotl(z[ 6] + z[ 5], 9);
            z[ 4] ^= rotl(z[ 7] + z[ 6], 13);
            z[ 5] ^= rotl(z[ 4] + z[ 7], 18);
            z[11] ^= rotl(z[10] + z[ 9], 7);
            z[ 8] ^= rotl(z[11] + z[10], 9);
            z[ 9] ^= rotl(z[ 8] + z[11], 13);
            z[10] ^= rotl(z[ 9] + z[ 8], 18);
            z[12] ^= rotl(z[15] + z[14], 7);
            z[13] ^= rotl(z[12] + z[15], 9);
            z[14] ^= rotl(z[13] + z[12], 13);
            z[15] ^= rotl(z[14] + z[13], 18);
        }
        for (int i = 0; i < 16; i++) {
            le32Store(out, i * 4, z[i] + x[i]);
        }
    }

    /** Integerify：取最后一个 64 字节块的低 32 位（小端）；上层再 & (n-1) 得 mod n。 */
    private static int integerify(byte[] x, int r) {
        return le32(x, (2 * r - 1) * 64);
    }

    private static int rotl(int x, int b) {
        return (x << b) | (x >>> (32 - b));
    }

    private static int le32(byte[] a, int off) {
        return (a[off] & 0xFF)
                | ((a[off + 1] & 0xFF) << 8)
                | ((a[off + 2] & 0xFF) << 16)
                | ((a[off + 3] & 0xFF) << 24);
    }

    private static void le32Store(byte[] a, int off, int v) {
        a[off] = (byte) v;
        a[off + 1] = (byte) (v >>> 8);
        a[off + 2] = (byte) (v >>> 16);
        a[off + 3] = (byte) (v >>> 24);
    }

    /** PBKDF2-HMAC-SHA256（RFC 2898）；本算法只以迭代次数 c=1 调用。 */
    private static byte[] pbkdf2HmacSha256(byte[] password, byte[] salt, int iterations, int dkLen) {
        byte[] dk = new byte[dkLen];
        int blocks = (dkLen + SHA256_LEN - 1) / SHA256_LEN;
        for (int block = 1; block <= blocks; block++) {
            byte[] u = mac(password, concat(salt, int32be(block)));
            byte[] t = u.clone();
            for (int it = 1; it < iterations; it++) {
                u = mac(password, u);
                for (int k = 0; k < t.length; k++) t[k] ^= u[k];
            }
            int off = (block - 1) * SHA256_LEN;
            System.arraycopy(t, 0, dk, off, Math.min(SHA256_LEN, dkLen - off));
        }
        return dk;
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

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] int32be(int v) {
        return new byte[] { (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v };
    }
}

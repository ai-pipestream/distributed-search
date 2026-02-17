package ai.pipestream.search.node;

import org.apache.lucene.util.VectorUtil;

/**
 * Utility for generating and comparing topological sketches (binary signatures) of vectors.
 */
public class TopologySketchUtil {

    /**
     * Converts a float vector into a 1-bit binary signature (bitset).
     * For each dimension: bit is 1 if value >= 0, else 0.
     *
     * @param vector The 1024-dim float vector.
     * @return A 128-byte array containing the 1024-bit signature.
     */
    public static byte[] computeBinarySignature(float[] vector) {
        byte[] signature = new byte[vector.length / 8];
        for (int i = 0; i < vector.length; i++) {
            if (vector[i] >= 0) {
                signature[i >> 3] |= (1 << (i & 7));
            }
        }
        return signature;
    }

    /**
     * Calculates the Hamming distance (number of differing bits) between two binary signatures.
     * Uses Lucene's optimized VectorUtil.xorBitCount.
     *
     * @param sig1 First signature.
     * @param sig2 Second signature.
     * @return The number of differing bits.
     */
    public static int hammingDistance(byte[] sig1, byte[] sig2) {
        return VectorUtil.xorBitCount(sig1, sig2);
    }

    /**
     * Estimates if two vectors are in the same "neighborhood" based on their binary signatures.
     *
     * @param sig1 First signature.
     * @param sig2 Second signature.
     * @param maxBitDiff The maximum number of differing bits to be considered "near".
     * @return True if they are likely in the same neighborhood.
     */
    public static boolean isNear(byte[] sig1, byte[] sig2, int maxBitDiff) {
        return hammingDistance(sig1, sig2) <= maxBitDiff;
    }
}

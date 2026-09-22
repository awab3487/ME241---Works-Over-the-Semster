package app.revanced.extension.musicremover;

/**
 * In-place iterative radix-2 complex FFT with precomputed twiddle factors.
 */
final class FFT {
    private final int size;
    private final int[] bitReversed;
    private final float[] cos;
    private final float[] sin;

    FFT(int size) {
        if (size < 2 || (size & (size - 1)) != 0) {
            throw new IllegalArgumentException("FFT size must be a power of two, got " + size);
        }
        this.size = size;

        int bits = Integer.numberOfTrailingZeros(size);
        bitReversed = new int[size];
        for (int i = 0; i < size; i++) {
            bitReversed[i] = Integer.reverse(i) >>> (32 - bits);
        }

        cos = new float[size / 2];
        sin = new float[size / 2];
        for (int i = 0; i < size / 2; i++) {
            double angle = 2.0 * Math.PI * i / size;
            cos[i] = (float) Math.cos(angle);
            sin[i] = (float) Math.sin(angle);
        }
    }

    int size() {
        return size;
    }

    void forward(float[] re, float[] im) {
        transform(re, im, false);
    }

    /** Inverse transform, including the 1/N normalization. */
    void inverse(float[] re, float[] im) {
        transform(re, im, true);
        float scale = 1f / size;
        for (int i = 0; i < size; i++) {
            re[i] *= scale;
            im[i] *= scale;
        }
    }

    private void transform(float[] re, float[] im, boolean inverse) {
        for (int i = 0; i < size; i++) {
            int j = bitReversed[i];
            if (j > i) {
                float tmp = re[i];
                re[i] = re[j];
                re[j] = tmp;
                tmp = im[i];
                im[i] = im[j];
                im[j] = tmp;
            }
        }

        for (int length = 2; length <= size; length <<= 1) {
            int half = length >> 1;
            int step = size / length;
            for (int start = 0; start < size; start += length) {
                for (int k = 0; k < half; k++) {
                    float wr = cos[k * step];
                    float wi = inverse ? sin[k * step] : -sin[k * step];
                    int a = start + k;
                    int b = a + half;
                    float tr = re[b] * wr - im[b] * wi;
                    float ti = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                }
            }
        }
    }
}

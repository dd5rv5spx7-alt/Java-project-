#include "agro_ae.h"
#include <math.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

enum { SM = 0, ST, AT, RH, RAIN, EC, FLOW, CMD };

static float residual(const float *prev, const float *cur, int step) {
    float h = (float) (step % AE_STEPS_PER_DAY) / 4.0f;
    float s = sinf((float) M_PI * (h - 6.0f) / 12.0f);
    float evap = (s > 0 ? s : 0) * (1.0f + 0.03f * (cur[AT] - 29.0f));
    float pred = balance_coef[0] * cur[RAIN] + balance_coef[1] * cur[FLOW] + balance_coef[2] * evap + balance_coef[3];
    return (cur[SM] - prev[SM]) - pred;
}

void agro_features(const float rows[AE_LONG_WINDOW + 1][AE_NF], int step, float z[AE_DIM]) {
    float x[AE_DIM];
    const int last = AE_LONG_WINDOW;              /* index of the newest row */
    const int w = AE_WINDOW;
    float xm = (w - 1) / 2.0f, xv = 0;
    for (int k = 0; k < w; k++) xv += (k - xm) * (k - xm);
    for (int f = 0; f < AE_NF; f++) {
        float sum = 0, ss = 0, cov = 0;
        for (int k = 0; k < w; k++) sum += rows[last - w + 1 + k][f];
        float mean = sum / w;
        for (int k = 0; k < w; k++) {
            float v = rows[last - w + 1 + k][f];
            ss += (v - mean) * (v - mean);
            cov += (k - xm) * (v - mean);
        }
        x[f * 4] = rows[last][f];
        x[f * 4 + 1] = mean;
        x[f * 4 + 2] = logf(sqrtf(ss / w) + 1e-3f);
        x[f * 4 + 3] = cov / xv;
    }
    float rs = 0, rmax = 0, air = 0;
    for (int k = 0; k < w; k++) {
        int r = last - w + 1 + k;
        float e = residual(rows[r - 1], rows[r], step - w + 1 + k);
        rs += e;
        if (fabsf(e) > rmax) rmax = fabsf(e);
        air += rows[r][AT];
    }
    x[32] = rs;
    x[33] = rmax;
    x[34] = rows[last][ST] - air / w;
    float h = (float) (step % AE_STEPS_PER_DAY) / 4.0f;
    x[35] = sinf(2 * (float) M_PI * h / 24);
    x[36] = cosf(2 * (float) M_PI * h / 24);
    float clip = 3 * balance_residual_std, longSum = 0;
    for (int k = 0; k < AE_LONG_WINDOW; k++) {
        float e = residual(rows[last - k - 1], rows[last - k], step - k);
        longSum += e > clip ? clip : (e < -clip ? -clip : e);
    }
    x[37] = longSum;
    for (int i = 0; i < AE_DIM; i++) {
        float v = (x[i] - feat_mean[i]) / feat_std[i];
        z[i] = v > feat_clip ? feat_clip : (v < -feat_clip ? -feat_clip : v);
    }
}

static int clampi(long v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : (int) v);
}

float agro_ae_score(const float z[AE_DIM]) {
    int8_t a[64], next[64];
    float out[64];
    for (int i = 0; i < AE_DIM; i++) a[i] = (int8_t) clampi(lroundf(z[i] / ae_act_scale[0]), -127, 127);
    for (int l = 0; l < AE_LAYERS; l++) {
        int in = ae_sizes[l], n = ae_sizes[l + 1];
        float m = ae_w_scale[l] * ae_act_scale[l];
        for (int o = 0; o < n; o++) {
            int32_t acc = ae_b[l][o];
            const int8_t *row = ae_w[l] + o * in;
            for (int i = 0; i < in; i++) acc += (int32_t) row[i] * a[i];
            out[o] = (float) acc * m;
            if (l < AE_LAYERS - 1) {
                float r = out[o] > 0 ? out[o] : 0;
                next[o] = (int8_t) clampi(lroundf(r / ae_act_scale[l + 1]), 0, 127);
            }
        }
        for (int o = 0; o < n; o++) a[o] = next[o];
    }
    float s = 0;
    for (int i = 0; i < AE_DIM; i++) s += (out[i] - z[i]) * (out[i] - z[i]);
    return s / AE_DIM;
}

int agro_ae_level(float score) {
    return score > ae_tau ? 2 : (score > ae_tau_low ? 1 : 0);
}

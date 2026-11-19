/* Checks the C detector against the Java reference on a PC:
 *   cc -O2 -o host_test host_test.c agro_ae.c -lm && ./host_test   */
#include <stdio.h>
#include <math.h>
#include "agro_ae.h"
#include "test_vectors.h"

int main(void) {
    int bad = 0;
    float worstF = 0, worstS = 0;
    for (int t = 0; t < TV_COUNT; t++) {
        float z[AE_DIM];
        agro_features(tv_raw[t], tv_step[t], z);
        for (int i = 0; i < AE_DIM; i++) {
            float d = fabsf(z[i] - tv_features[t][i]);
            if (d > worstF) worstF = d;
        }
        float s = agro_ae_score(z);
        float rel = fabsf(s - tv_score[t]) / (tv_score[t] > 1e-3f ? tv_score[t] : 1e-3f);
        if (rel > worstS) worstS = rel;
        int ok = rel < 0.02f && agro_ae_level(s) == agro_ae_level(tv_score[t]);
        if (!ok) bad++;
        printf("vector %2d  step %4d  java %.4f  C %.4f  level %d/%d  %s\n", t, tv_step[t], tv_score[t], s,
               agro_ae_level(tv_score[t]), agro_ae_level(s), ok ? "OK" : "MISMATCH");
    }
    printf("largest feature difference %.2e, largest relative score difference %.2e\n", worstF, worstS);
    printf("%s: %d of %d vectors match\n", bad ? "FAILED" : "PASSED", TV_COUNT - bad, TV_COUNT);
    printf("model memory: weights %d bytes int8\n", (int) (sizeof(ae_w0) + sizeof(ae_w1) + sizeof(ae_w2) + sizeof(ae_w3)));
    return bad ? 1 : 0;
}

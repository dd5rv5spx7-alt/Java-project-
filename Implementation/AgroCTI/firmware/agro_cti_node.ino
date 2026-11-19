/*
 * Agro-CTI sensor node for ESP32 (Arduino framework) - reference sketch.
 * Reads the field sensors every 15 minutes, keeps the last 25 readings,
 * scores them with the int8 autoencoder and publishes the result to the farm gateway.
 * The detector code (agro_ae.c) is tested on a PC with host_test.c; the sensor and
 * network functions below are placeholders to be replaced with the real drivers.
 */
#include <Arduino.h>
extern "C" {
#include "agro_ae.h"
}

static float rows[AE_LONG_WINDOW + 1][AE_NF];
static int filled = 0;
static long step = 0;

static void readSensors(float *v) {
  v[0] = analogRead(34) * 100.0f / 4095.0f;   /* capacitive soil moisture, % */
  v[1] = 27.0f;                              /* DS18B20 soil temperature */
  v[2] = 30.0f;                              /* DHT22 air temperature */
  v[3] = 65.0f;                              /* DHT22 humidity */
  v[4] = 0.0f;                               /* tipping-bucket rain gauge, mm in 15 min */
  v[5] = 1.2f;                               /* soil EC probe, dS/m */
  v[6] = 0.0f;                               /* flow meter, L/min */
  v[7] = 0.0f;                               /* valve commands received in 15 min */
}

static void publish(float score, int level) {
  Serial.printf("{\"step\":%ld,\"score\":%.3f,\"level\":%d}\n", step, score, level);  /* MQTT in production */
}

void setup() {
  Serial.begin(115200);
}

void loop() {
  for (int r = 0; r < AE_LONG_WINDOW; r++)
    for (int f = 0; f < AE_NF; f++) rows[r][f] = rows[r + 1][f];
  readSensors(rows[AE_LONG_WINDOW]);
  if (filled < AE_LONG_WINDOW + 1) filled++;
  if (filled == AE_LONG_WINDOW + 1) {
    float z[AE_DIM];
    agro_features(rows, (int) step, z);
    float s = agro_ae_score(z);
    publish(s, agro_ae_level(s));
  }
  step++;
  delay(15UL * 60UL * 1000UL);
}

/*
 * Agro-CTI on-device detector (ESP32 / any C99 microcontroller).
 * Feature extraction + int8 autoencoder, the same computation as the Java classes
 * FeatureExtractor and QuantizedAutoencoder.
 */
#ifndef AGRO_AE_H
#define AGRO_AE_H

#include "agro_ae_model.h"

/* rows[0..AE_LONG_WINDOW] are the last 25 readings (oldest first), each with AE_NF channels:
   soil moisture, soil temp, air temp, humidity, rain, soil EC, flow, commands.
   step = index of the newest reading (15-minute steps since midnight of day 0). */
void agro_features(const float rows[AE_LONG_WINDOW + 1][AE_NF], int step, float z[AE_DIM]);

/* Reconstruction error of a standardised feature vector, with integer-only arithmetic. */
float agro_ae_score(const float z[AE_DIM]);

/* 0 = normal, 1 = suspicious, 2 = alert. */
int agro_ae_level(float score);

#endif

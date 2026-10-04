package com.kerosene.kfe.wallet.domain.service;

import com.kerosene.kfe.wallet.domain.model.Outpoint;
import com.kerosene.kfe.wallet.domain.model.UtxoSnapshot;

import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic merge of chain observations by immutable outpoint. */
public final class UtxoProjectionPolicy {
    private UtxoProjectionPolicy() {
    }

    public static void merge(Map<Outpoint, UtxoSnapshot> projection, UtxoSnapshot candidate) {
        if (projection == null || candidate == null) {
            return;
        }
        projection.merge(candidate.outpoint(), candidate, UtxoProjectionPolicy::preferFreshest);
    }

    public static Map<Outpoint, UtxoSnapshot> newProjection() {
        return new LinkedHashMap<>();
    }

    private static UtxoSnapshot preferFreshest(UtxoSnapshot previous, UtxoSnapshot candidate) {
        return candidate.confirmations() > previous.confirmations() ? candidate : previous;
    }
}

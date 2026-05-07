package fi.monopoly.domain.decision;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed marker for typed {@link PendingDecision} payloads.
 *
 * <p>The {@code @JsonTypeInfo} / {@code @JsonSubTypes} annotations live here so that Jackson can
 * round-trip {@code DecisionPayload} values through the HTTP transport without requiring any
 * transport-layer MixIns or changes to the domain records themselves.</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "payloadType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = PropertyPurchaseDecisionPayload.class, name = "PropertyPurchase")
})
public sealed interface DecisionPayload permits PropertyPurchaseDecisionPayload {
}

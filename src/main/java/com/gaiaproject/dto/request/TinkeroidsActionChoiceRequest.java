package com.gaiaproject.dto.request;

import java.util.UUID;

public record TinkeroidsActionChoiceRequest(
        UUID playerId,
        String actionCode  // TINK_TERRAFORM_1, TINK_POWER_4, TINK_QIC_1, TINK_TERRAFORM_3, TINK_KNOWLEDGE_3, TINK_QIC_2
) {}

package com.fraud;

import com.fraud.model.Transaction;
import com.fraud.service.DecisionEngineService;
import com.fraud.service.FeatureEngineeringService;
import com.fraud.service.MlClientService;
import com.fraud.dto.Dtos.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FraudDetectionApplicationTests {

    @Autowired MockMvc mockMvc;

    @MockBean DecisionEngineService decisionEngine;

    @Test
    void contextLoads() {}

    @Test
    void scoreTransaction_returnsDecision() throws Exception {
        var response = TransactionResponse.builder()
                .transactionId("txn-001")
                .fraudScore(0.85)
                .decision("BLOCK")
                .nlExplanation("High velocity and large amount.")
                .shapValues(Map.of("amount_zscore", 0.4))
                .modelVersion("v3")
                .processedAt(Instant.now())
                .build();

        when(decisionEngine.evaluate(any())).thenReturn(response);

        mockMvc.perform(post("/v1/transactions")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "transactionId": "txn-001",
                      "userId": "user-1",
                      "merchantId": "merch-1",
                      "amount": 9999.00,
                      "currency": "USD",
                      "channel": "WEB_CHECKOUT"
                    }
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("BLOCK"))
                .andExpect(jsonPath("$.fraudScore").value(0.85));
    }
}

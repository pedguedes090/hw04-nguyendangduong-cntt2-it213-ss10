package com.rikkeipay.service;

import io.langfuse.client.LangfuseClient;
import io.langfuse.client.model.Generation;
import io.langfuse.client.model.Usage;
import io.langfuse.client.model.UsageDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * TokenUsageReporter - gửi token usage THỦ CÔNG lên Langfuse
 * cho các custom model KHÔNG hỗ trợ tự động đếm token.
 *
 * Khi Langfuse không biết bảng giá của model (custom/self-host model,
 * model mới, hoặc model gọi qua gateway riêng), ta phải:
 *   1. Tự đếm token input/output (vd: tiktoken, tokenizer riêng của model).
 *   2. Gửi Usage + UsageDetails kèm theo Generation.
 *   3. Langfuse sẽ ánh xạ modelName vào Model Price List đã cấu hình
 *      trong Settings -> Model Prices để tính ra chi phí.
 */
@Service
public class TokenUsageReporter {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageReporter.class);

    private final LangfuseClient langfuseClient;

    public TokenUsageReporter(LangfuseClient langfuseClient) {
        this.langfuseClient = langfuseClient;
    }

    /**
     * Ghi một generation có kèm token usage thủ công.
     *
     * @param traceId     id trace cha (đã tạo từ trước)
     * @param modelName   tên model, VD: "gemini-2.5-flash", "deepseek-v3"
     * @param input       prompt đầu vào
     * @param output      phản hồi model
     * @param inputTokens số token input do ta tự đếm
     * @param outputTokens số token output do ta tự đếm
     */
    public void reportManualUsage(String traceId,
                                  String modelName,
                                  String input,
                                  String output,
                                  int inputTokens,
                                  int outputTokens) {

        int totalTokens = inputTokens + outputTokens;

        Usage usage = new Usage()
                .input(inputTokens)
                .output(outputTokens)
                .total(totalTokens)
                // UsageDetails: chi tiết token theo từng loại (cache read/write, reasoning...)
                .inputDetails(new UsageDetails().input(inputTokens))
                .outputDetails(new UsageDetails().output(outputTokens));

        langfuseClient.generation(new Generation()
                .traceId(traceId)
                .name("custom-model-call")
                .model(modelName)
                .input(input)
                .output(output)
                .usage(usage));

        log.info("Đã gửi token usage thủ công cho model={}: input={}, output={}, total={}",
                modelName, inputTokens, outputTokens, totalTokens);

        // Ghi chú vận hành: chi phí được Langfuse tính = totalTokens * đơn giá
        // trong Model Price List. VD nếu cấu hình gemini-2.5-flash = 0.3$/1M input
        // và 1.5$/1M output, với 1000 input + 500 output:
        //   cost = 1000/1e6 * 0.3 + 500/1e6 * 1.5 = 0.00105$
        log.debug("Chi phí ước tính (tham khảo): {}", estimateCostUsd(modelName, inputTokens, outputTokens));
    }

    /**
     * Minh họa cách tự đếm token khi model không trả token usage
     * (thay thế bằng tokenizer thật của model nếu có).
     */
    public int estimateTokens(String text) {
        // Cách đơn giản: ~1 token / 4 ký tự tiếng Anh
        if (text == null || text.isBlank()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / 4.0);
    }

    /** Bảng giá nội bộ (demo) - trên thực tế giá nằm trong Langfuse Model Price List. */
    private double estimateCostUsd(String modelName, int inputTokens, int outputTokens) {
        Map<String, double[]> pricePerMillion = Map.of(
                "gemini-2.5-flash", new double[]{0.30, 1.50},  // input, output (USD/1M token)
                "deepseek-v3", new double[]{0.27, 1.10}
        );
        double[] p = pricePerMillion.getOrDefault(modelName, new double[]{0.50, 1.50});
        return (inputTokens / 1_000_000.0) * p[0] + (outputTokens / 1_000_000.0) * p[1];
    }
}

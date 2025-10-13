package com.example.acp.service;

import com.stripe.Stripe;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.model.PaymentIntent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {

    public PaymentService(@Value("${stripe.secret}") String secretKey) {
        Stripe.overrideApiBase("https://api.stripe.com");
        Stripe.apiKey = secretKey;
        Stripe.setAppInfo("acp-checkout-demo", "0.0.1", null);
    }

    public PaymentIntent createIntent(long amount, String currency,
                                      String delegatedPaymentToken) throws Exception {
        PaymentIntentCreateParams params =
            PaymentIntentCreateParams.builder()
                .setAmount(amount)
                .setCurrency(currency)
                .setPaymentMethodData(
                    PaymentIntentCreateParams.PaymentMethodData.builder()
                       .putExtraParam("type", "delegated_payment_token")           
                       .putExtraParam("delegated_payment_token", delegatedPaymentToken)  
                       .build()
                )
                .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.AUTOMATIC)
                .build();
        return PaymentIntent.create(params);
    }
}

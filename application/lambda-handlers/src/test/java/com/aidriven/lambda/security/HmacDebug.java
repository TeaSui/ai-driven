package com.aidriven.lambda.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

public class HmacDebug {
    public static void main(String[] args) throws Exception {
        System.out.println("GitHub: " + computeHash("my_secret", "{\"action\":\"opened\"}"));
        System.out.println("Bitbucket: " + computeHash("bitbucket_secret", "{\"push\":{}}"));
    }

    private static String computeHash(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKeySpec);
        byte[] hashBytes = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        StringBuilder hashString = new StringBuilder("sha256=");
        for (byte b : hashBytes) {
            hashString.append(String.format("%02x", b));
        }
        return hashString.toString();
    }
}

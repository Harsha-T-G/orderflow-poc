package com.codewalnut.orderflow;

import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

public final class OrderFlowApplication {
    private OrderFlowApplication() {
    }

    public static void main(String[] args) {
        if (isShopMode(args)) {
            new OrderFlowShopSession(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8),
                    new PrintWriter(System.out, true))
                    .run();
            return;
        }
        new OrderFlowDemonstration(new PrintWriter(System.out, true)).run();
    }

    private static boolean isShopMode(String[] args) {
        for (String argument : args) {
            if ("--shop".equals(argument) || "--interactive".equals(argument)) {
                return true;
            }
        }
        return false;
    }
}

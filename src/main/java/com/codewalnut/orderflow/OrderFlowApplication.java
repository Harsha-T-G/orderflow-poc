package com.codewalnut.orderflow;

import java.io.PrintWriter;

public final class OrderFlowApplication {
    private OrderFlowApplication() {
    }

    public static void main(String[] args) {
        new OrderFlowDemonstration(new PrintWriter(System.out, true)).run();
    }
}

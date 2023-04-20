/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.test;

import java.util.concurrent.CountDownLatch;

import org.lealone.main.Lealone;
import org.lealone.main.config.Config;

public class TcpServerStart {

    public static void main(String[] args) {
        Config.setProperty("config", "lealone-test.yaml");
        Lealone.main(args);
    }

    public static void run() {
        CountDownLatch latch = new CountDownLatch(1);
        new Thread(() -> {
            Lealone.run(new String[0], false, latch);
        }).start();
        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}

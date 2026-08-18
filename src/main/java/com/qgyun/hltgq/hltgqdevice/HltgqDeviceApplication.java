package com.qgyun.hltgq.hltgqdevice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class HltgqDeviceApplication {

    public static void main(String[] args) {
        SpringApplication.run(HltgqDeviceApplication.class, args);
    }

}
